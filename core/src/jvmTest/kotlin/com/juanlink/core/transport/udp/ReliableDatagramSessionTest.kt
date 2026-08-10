package com.juanlink.core.transport.udp

import java.net.InetSocketAddress
import java.util.PriorityQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 可靠 UDP 会话故障注入测试：
 * 手动时钟 + 可注入丢包/乱序的投递通道 + 手拨定时器（tick 推进），
 * 验证分片重组、严格按序交付、ACK 滑窗、窗口背压、重传最终一致。
 */
class ReliableDatagramSessionTest {

    private class FakeClock(var now: Long = 0L) {
        fun advance(ms: Long) { now += ms }
    }

    private class ScheduledTask(val at: Long, val task: () -> Unit)

    private class Harness(
        val clock: FakeClock,
        private val lossRate: Double = 0.0,
        private val reorder: Boolean = false,
        seed: Int = 42,
    ) {
        private val rand = Random(seed)
        private val tasks = PriorityQueue<ScheduledTask>(compareBy { it.at })
        private val delayed = ArrayDeque<Pair<Long, () -> Unit>>()
        val aRecv = mutableListOf<ByteArray>()
        val bRecv = mutableListOf<ByteArray>()
        var lost = false
        lateinit var a: ReliableDatagramSession
        lateinit var b: ReliableDatagramSession

        init {
            val aPeer = InetSocketAddress("127.0.0.1", 10001)
            val bPeer = InetSocketAddress("127.0.0.1", 10002)
            a = ReliableDatagramSession(
                sendDatagram = { d, _ -> deliver(d, { b.onDatagram(d, d.size) }) },
                peer = bPeer,
                onFrame = { aRecv.add(it) },
                schedule = { delay, task -> tasks.add(ScheduledTask(clock.now + delay, task)) },
                clock = { clock.now },
                onLost = { lost = true },
            )
            b = ReliableDatagramSession(
                sendDatagram = { d, _ -> deliver(d, { a.onDatagram(d, d.size) }) },
                peer = aPeer,
                onFrame = { bRecv.add(it) },
                schedule = { delay, task -> tasks.add(ScheduledTask(clock.now + delay, task)) },
                clock = { clock.now },
                onLost = { lost = true },
            )
        }

        private fun deliver(datagram: ByteArray, action: () -> Unit) {
            if (rand.nextDouble() < lossRate) return
            if (reorder) delayed.add(clock.now + (rand.nextInt(3) + 1).toLong() to action)
            else action()
        }

        /** 推进时间：先投递到期乱序报文，再执行到期定时任务（重传/ACK） */
        fun tick(ms: Long) {
            clock.advance(ms)
            val due = delayed.toList().filter { it.first <= clock.now }.sortedBy { it.first }
            delayed.clear()
            for (d in due) d.second()
            var guard = 0
            while (tasks.isNotEmpty() && tasks.peek().at <= clock.now && guard < 200_000) {
                tasks.poll().task()
                guard++
            }
        }

        fun drain(rounds: Int, stepMs: Long = 200) {
            repeat(rounds) { tick(stepMs) }
        }
    }

    @Test
    fun singleFrameDelivered() {
        val h = Harness(FakeClock())
        val frame = ByteArray(100) { it.toByte() }
        assertTrue(h.a.sendFrame(frame))
        h.tick(100)
        assertEquals(1, h.bRecv.size)
        assertContentEquals(frame, h.bRecv[0])
    }

    @Test
    fun largeFrameFragmentedAndReassembled() {
        val h = Harness(FakeClock())
        val frame = ByteArray(8192) { (it % 251).toByte() } // 8192/1200 → 7 分片
        assertTrue(h.a.sendFrame(frame))
        h.tick(200)
        assertEquals(1, h.bRecv.size)
        assertContentEquals(frame, h.bRecv[0])
        assertFalse(h.lost)
    }

    @Test
    fun lossyChannelStillOrderedAndComplete() {
        val h = Harness(FakeClock(), lossRate = 0.2)
        val frames = (0 until 20).map { "frame-$it".encodeToByteArray() }
        for (f in frames) assertTrue(h.a.sendFrame(f))
        h.drain(40, 200) // 模拟 8s，多次重传补丢包
        assertEquals(20, h.bRecv.size)
        for (i in frames.indices) assertContentEquals(frames[i], h.bRecv[i])
        assertFalse(h.lost, "不应触发 onLost")
    }

    @Test
    fun reorderedDatagramsStillDeliveredInOrder() {
        val h = Harness(FakeClock(), reorder = true)
        val frames = (0 until 10).map { "seq-$it".encodeToByteArray() }
        for (f in frames) assertTrue(h.a.sendFrame(f))
        h.drain(30, 200)
        assertEquals(10, h.bRecv.size)
        for (i in frames.indices) assertContentEquals(frames[i], h.bRecv[i], "乱序到达仍应严格按 udpSeq 序交付")
    }

    @Test
    fun ackClearsSendWindow() {
        val h = Harness(FakeClock())
        for (i in 0 until 3) assertTrue(h.a.sendFrame("f$i".encodeToByteArray()))
        // 帧同步投递到 b；tick 推进 ackLoop(60ms) → b 发 ACK → a 窗口清空
        h.tick(100)
        assertEquals(0, h.a.unackedCount, "ACK 到达后发送窗口应清空")
        assertEquals(0, h.b.unackedCount)
    }

    /**
     * #6 修复：窗口满（对端 ACK 慢）不再是致命错误——第 33 帧进入待发队列返回 true，
     * ACK 腾出窗口后自动补发。蜂窝高丢包路径上这是「背压」，不是「断连」。
     */
    @Test
    fun windowFullQueuesAndFlushesWithoutFalse() {
        val h = Harness(FakeClock(), lossRate = 0.3)
        val frames = (0 until 40).map { ByteArray(200) { it.toByte() } }
        // 40 帧连续发送：前 32 帧填满窗口，其余帧入队背压而非返回 false
        for (f in frames) assertTrue(h.a.sendFrame(f), "窗口满时帧应入队返回 true（背压，不断连）")
        h.drain(80, 200) // 模拟 16s：慢 ACK + 丢包下排队帧全部补发
        assertEquals(40, h.bRecv.size, "窗口满入队的帧应在 ACK 腾窗后全部交付")
        for (i in frames.indices) assertContentEquals(frames[i], h.bRecv[i])
        assertFalse(h.lost, "排队背压不应触发 onLost")
        assertEquals(0, h.a.unackedCount, "全部确认后发送窗口应清空")
        h.a.close()
        h.b.close()
    }

    /** 蜂窝重度丢包下大批帧：排队补发最终一致，全程不断连不丢帧 */
    @Test
    fun manyFramesHeavyLossQueuedFlushedEventualConsistency() {
        val h = Harness(FakeClock(), lossRate = 0.45, seed = 11)
        val frames = (0 until 70).map { ByteArray(600) { (it % 251).toByte() } } // 每次仅 1 分片，压力在窗口
        for (f in frames) assertTrue(h.a.sendFrame(f), "所有帧都应被接受（排队）")
        h.drain(120, 200) // 模拟 24s，重传+排队持续收敛
        assertEquals(70, h.bRecv.size, "70 帧在 45% 丢包下应最终全部交付")
        assertFalse(h.lost, "重传与排队应消化丢包，不触发 onLost")
        assertEquals(0, h.a.unackedCount)
        h.a.close()
        h.b.close()
    }

    @Test
    fun heavyLossEventualDeliveryWithoutLost() {
        val h = Harness(FakeClock(), lossRate = 0.5, seed = 7)
        val frame = ByteArray(2000) { (it % 7).toByte() } // 2 分片
        assertTrue(h.a.sendFrame(frame))
        h.drain(60, 200) // 模拟 12s
        assertEquals(1, h.bRecv.size)
        assertContentEquals(frame, h.bRecv[0])
        assertFalse(h.lost, "50% 丢包下重传不应触发 onLost")
    }

    @Test
    fun bidirectionalTraffic() {
        val h = Harness(FakeClock(), lossRate = 0.1)
        assertTrue(h.a.sendFrame("from-A".encodeToByteArray()))
        assertTrue(h.b.sendFrame("from-B".encodeToByteArray()))
        h.drain(30, 200)
        assertEquals(1, h.aRecv.size)
        assertEquals(1, h.bRecv.size)
        assertContentEquals("from-B".encodeToByteArray(), h.aRecv[0])
        assertContentEquals("from-A".encodeToByteArray(), h.bRecv[0])
    }
}

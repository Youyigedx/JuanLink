package com.juanlink.core.draw

import com.juanlink.core.protocol.EnvelopeCodec
import com.juanlink.core.protocol.OpEnvelope
import com.juanlink.core.protocol.OpId
import com.juanlink.core.protocol.OpTransport
import com.juanlink.core.protocol.OpType
import com.juanlink.core.protocol.Resync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 泛型化 OpSyncEngine 的单元测试（DrawOp + MemDoc）。
 * 覆盖排序/缓冲/ack/补同步/撤销重做全部原行为，以及新增的「同元素 undo 合并」。
 */
class DrawOpSyncEngineTest {

    private class FakeTransport : OpTransport {
        val sent = mutableListOf<OpEnvelope>()
        val acks = mutableListOf<Pair<String, Long>>()
        val resyncs = mutableListOf<Resync>()
        var lastSyncRequestSince = -1L

        override fun sendOp(envelope: OpEnvelope): Boolean {
            sent.add(envelope)
            return true
        }

        override fun sendAck(peerId: String, lastAppliedSeq: Long, requestResync: Boolean): Boolean {
            acks.add(peerId to lastAppliedSeq)
            return true
        }

        override fun sendSyncRequest(sinceSeq: Long): Boolean {
            lastSyncRequestSince = sinceSeq
            return true
        }

        override fun sendResync(ops: List<ByteArray>, lastSeq: Long): Boolean {
            resyncs.add(Resync(0, ops, lastSeq))
            return true
        }

        override fun sendResyncDone(lastSeq: Long): Boolean = true
    }

    private fun remoteEnvelope(peer: String, lamport: Long, seq: Long, elementId: String = "s-$peer-$lamport"): OpEnvelope =
        OpEnvelope(
            opId = OpId(lamport, peer),
            peerId = peer,
            opType = OpType.ElementUpsert.code,
            payload = DrawOpCodec.encode(DrawOp.ElementUpsert(wireElement(elementId))),
            seq = seq,
            timestamp = 0,
        )

    @Test
    fun localOpAssignedOpIdAndSent() {
        val t = FakeTransport()
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", t)
        val opId = engine.applyLocal(DrawOp.ElementUpsert(wireElement("e1")))
        assertNotNull(opId)
        assertEquals("peer-A", opId!!.peerId)
        assertEquals(1, t.sent.size)
        assertEquals(OpType.ElementUpsert.code, t.sent[0].opType)
        assertEquals(1, doc.elements.size)
    }

    @Test
    fun remoteOpsApplyInOrder() {
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", FakeTransport())
        engine.onRemoteOp(remoteEnvelope("peer-B", 1, 1))
        engine.onRemoteOp(remoteEnvelope("peer-B", 2, 2))
        assertEquals(2, doc.elements.size)
    }

    @Test
    fun outOfOrderRemoteOpBufferedUntilGapFilled() {
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", FakeTransport())
        // 先到 seq=2，存在缺口（缺 seq=1），应缓冲不应用
        engine.onRemoteOp(remoteEnvelope("peer-B", 2, 2))
        assertEquals(0, doc.elements.size)
        // 补齐 seq=1 → 连续前缀全部应用
        engine.onRemoteOp(remoteEnvelope("peer-B", 1, 1))
        assertEquals(2, doc.elements.size)
    }

    @Test
    fun duplicateRemoteOpIgnored() {
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", FakeTransport())
        val e = remoteEnvelope("peer-B", 1, 1)
        engine.onRemoteOp(e)
        engine.onRemoteOp(e)
        assertEquals(1, doc.elements.size)
    }

    @Test
    fun ackClearsOutgoingWindow() {
        val t = FakeTransport()
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", t)
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("e1")))
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("e2")))
        assertEquals(2, engine.pendingOutCount)
        engine.onAck(1)
        assertEquals(1, engine.pendingOutCount)
        engine.onAck(2)
        assertEquals(0, engine.pendingOutCount)
    }

    @Test
    fun deleteBroadcastsInverseOnUndo() {
        val t = FakeTransport()
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", t)
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("e1"))) // 新建：无 undo
        engine.applyLocal(DrawOp.ElementRemove("e1"))              // 删除：记录 undo
        assertEquals(0, doc.elements.size)
        assertTrue(engine.canUndo())
        assertTrue(engine.undo())
        assertEquals(1, doc.elements.size)
        assertEquals(OpType.ElementUpsert.code, t.sent.last().opType) // 逆 op = 恢复 upsert
        assertFalse(engine.canUndo()) // 逆操作不应再次记录
    }

    @Test
    fun redoRestoresElement() {
        val t = FakeTransport()
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", t)
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("e1")))
        engine.applyLocal(DrawOp.ElementRemove("e1"))
        engine.undo()
        assertEquals(1, doc.elements.size)
        assertTrue(engine.redo())
        assertEquals(0, doc.elements.size)
        assertEquals(OpType.ElementRemove.code, t.sent.last().opType)
    }

    @Test
    fun resyncReturnsOnlyOwnOpsAfterSinceSeq() {
        val t = FakeTransport()
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", t)
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("e1"))) // my seq 1
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("e2"))) // my seq 2
        engine.onRemoteOp(remoteEnvelope("peer-B", 5, 1)) // B's op
        engine.onSyncRequest(sinceSeq = 1)
        assertEquals(1, t.resyncs.size)
        val resync = t.resyncs[0]
        assertEquals(1, resync.ops.size)
        val env = EnvelopeCodec.decode(resync.ops[0])
        assertNotNull(env)
        assertEquals("peer-A", env!!.peerId)
        assertEquals(2, env.seq)
    }

    @Test
    fun resyncReplayReappliesMissingOps() {
        // 模拟断线补同步：远端把 1..2 重放过来，本地只缺 2
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", FakeTransport())
        engine.onRemoteOp(remoteEnvelope("peer-B", 1, 1))
        // 远端重放 1,2
        engine.onResync(listOf(
            EnvelopeCodec.encode(remoteEnvelope("peer-B", 1, 1)),
            EnvelopeCodec.encode(remoteEnvelope("peer-B", 2, 2)),
        ))
        assertEquals(2, doc.elements.size)
    }

    @Test
    fun lamportClockMovesBeyondRemote() {
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", FakeTransport())
        engine.onRemoteOp(remoteEnvelope("peer-B", 100, 1))
        val opId = engine.applyLocal(DrawOp.ElementUpsert(wireElement("local-after")))
        assertNotNull(opId)
        assertTrue(opId!!.lamport > 100, "本地时钟必须越过远端时钟")
    }

    @Test
    fun sameElementUpsertsMergeIntoOneUndo() {
        // 一次手势内对同一元素连续 upsert（如移动/改样式）应合并为一条 undo，
        // 且 inverse 保留手势首次捕获的旧值（恢复即回到手势起点）。
        val t = FakeTransport()
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", t)
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("e1", version = 1)))
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("e1", version = 2)))
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("e1", version = 3)))
        assertEquals(1, doc.elements.size)
        assertTrue(engine.canUndo())
        assertEquals("3", doc.elements["e1"]!!.modifiedAt.toString())

        assertTrue(engine.undo())
        // 合并成一条 undo：一次撤销回到手势起点（v1），而非逐帧回退
        assertEquals(1, doc.elements.size)
        assertEquals("1", doc.elements["e1"]!!.modifiedAt.toString())
        assertFalse(engine.canUndo()) // 只有一条 undo
    }

    @Test
    fun interleavedUpsertsAcrossElementsDoNotMerge() {
        val t = FakeTransport()
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", t)
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("a", version = 1))) // 新建：无 undo
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("b", version = 1))) // 新建：无 undo
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("a", version = 2))) // undo#1 (a)
        engine.applyLocal(DrawOp.ElementUpsert(wireElement("b", version = 2))) // undo#2 (b)
        assertTrue(engine.canUndo())

        // a、b 交替 upsert：合并只对「栈顶相邻同元素」生效，各记一条 undo
        engine.undo() // 撤销 b v2 → v1
        assertEquals("2", doc.elements["a"]!!.modifiedAt.toString())
        assertEquals("1", doc.elements["b"]!!.modifiedAt.toString())
        assertTrue(engine.canUndo(), "a 的 undo 应仍在（未被吞掉）")

        engine.undo() // 撤销 a v2 → v1
        assertEquals("1", doc.elements["a"]!!.modifiedAt.toString())
        assertFalse(engine.canUndo())
    }

    /**
     * 并发修复验证：UI 线程（applyLocal 画线）与网络线程（onAck 清出站窗口）
     * 同时操作共享状态，不得抛 ConcurrentModificationException/状态撕裂。
     * 复现真机「划一笔即断」根因：连接稳定后 ACK 频繁返回，与 UI 画线并发。
     */
    @Test
    fun concurrentApplyLocalAndOnAckNoCrash() {
        val t = FakeTransport()
        val doc = MemDoc()
        val engine = memEngine(doc, "peer-A", t)

        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        var uiOps = 0
        var ackOps = 0

        // 网络线程：持续模拟对端 ACK 已应用 seq 1..N
        val netThread = Thread {
            var seq = 0L
            while (!stop.get()) {
                seq++
                try {
                    engine.onAck(seq)
                    ackOps++
                } catch (e: Throwable) {
                    errors.add(e); stop.set(true)
                }
            }
        }.apply { start() }

        // UI 线程：持续画新笔画（applyLocal 写 outgoing）
        try {
            var i = 0
            while (!stop.get() && i < 50_000) {
                i++
                try {
                    engine.applyLocal(DrawOp.ElementUpsert(wireElement("e-$i")))
                    uiOps++
                } catch (e: Throwable) {
                    errors.add(e); stop.set(true)
                }
            }
        } finally {
            stop.set(true)
            netThread.join(3000)
        }

        assertTrue(errors.isEmpty(), "并发访问不得抛异常：${errors.firstOrNull()?.stackTraceToString()}")
        assertTrue(uiOps > 0, "UI 线程应完成画线")
        assertTrue(ackOps > 0, "网络线程应完成 ACK")
        // 出站窗口应被 ACK 清理干净（onAck 滑动到最大 seq）
        assertEquals(0, engine.pendingOutCount, "ACK 应清空出站窗口")
    }
}

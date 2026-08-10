package com.juanlink.core.transport.udp

import com.juanlink.core.util.nowEpochMillis
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.TreeMap

/**
 * 可靠有序 UDP 会话：在不可靠 UDP 之上提供「分片传输 + ACK 重传 + 严格按序交付」。
 *
 * 线程模型：全部 I/O 与定时器注入，本类内部用一把锁同步状态，可在单测中注入
 * 丢包/乱序/手拨定时器。上层（[UdpTransport]）负责 socket 收发与 daemon 线程。
 *
 * 语义对齐 TCP 字节流之上的协议栈：
 * - 交付顺序严格按 udpSeq（帧号）递增 → FrameDecoder / EncryptedFrame.seq 语义不变
 * - 发送窗口 [windowSize] 帧背压 + 待发队列 [pending]：窗口满时帧排队等待
 *   （对端 ACK 延迟=背压信号，**不是**致命错误），ACK 腾出窗口后自动补发。
 *   sendFrame 仅在会话关闭/帧超限/队列溢出时返回 false。
 * - ACK 累计，重传 RTO 指数退避封顶 3s，attempts>10 → onLost（真失联才断连）
 * - 重组/缓冲内存上限 4MB，超限丢最旧（抗慢速对端/注入）
 */
class ReliableDatagramSession(
    /** 实际发送一个报文（注入方负责线程切换与 socket） */
    private val sendDatagram: (ByteArray, InetSocketAddress) -> Unit,
    private val peer: InetSocketAddress,
    /** 完整帧按序交付 */
    private val onFrame: (ByteArray) -> Unit,
    /** 一次性调度（delayMs 后执行一次；本类负责重排下一轮） */
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    /** 重传耗尽/不可恢复时回调（上层断连） */
    private val onLost: () -> Unit = {},
    private val clock: () -> Long = ::nowEpochMillis,
    private val rtoMs: Long = 300,
    private val windowSize: Int = 32,
    private val maxPayload: Int = DatagramHeader.MAX_PAYLOAD,
    private val retransmitIntervalMs: Long = 200,
    private val ackIntervalMs: Long = 60,
    private val memoryLimit: Int = 4 * 1024 * 1024,
    /** 待发队列上限：远超窗口，仅防失控积压 */
    private val pendingLimit: Int = 512,
) {
    private val lock = Any()

    @Volatile private var closed = false

    // ---- 发送侧 ----
    private var sendSeq = 0
    private val sendBuffer = LinkedHashMap<Int, FrameEntry>()
    /** 窗口满时待发的完整帧（原始字节），按入队顺序保持 udpSeq 单调 */
    private val pending = ArrayDeque<ByteArray>()

    // ---- 接收侧 ----
    private var nextDeliver = 1
    private val reorder = TreeMap<Int, ByteArray>()          // 完整帧，按 udpSeq 缓冲
    private val reassembly = HashMap<Int, Reassemble>()       // 分片重组
    private var pendingBytes = 0                             // reorder + reassembly 总字节
    private var ackDirty = false

    /** 已交付的最大帧号（= nextDeliver - 1；ACK 内容） */
    val deliveredSeq: Int get() = synchronized(lock) { nextDeliver - 1 }

    /** 未 ACK 帧数（背压观察用） */
    val unackedCount: Int get() = synchronized(lock) { sendBuffer.size }

    private class FrameEntry(val fragments: List<ByteArray>, val frameLen: Int, var lastSent: Long, var attempts: Int)

    private class Reassemble(val frameLen: Int, val parts: TreeMap<Int, ByteArray>, var received: Int)

    init {
        startRetransmitLoop()
        startAckLoop()
    }

    /**
     * 发送一帧（完整协议帧字节）。
     * @return false 仅表示会话关闭/帧超限/待发队列溢出 —— 窗口满（对端 ACK 慢）
     *  不是致命错误：帧进入待发队列，ACK 腾出窗口后自动补发，绝不静默丢帧。
     */
    fun sendFrame(frame: ByteArray): Boolean = synchronized(lock) {
        if (closed) return false
        if (frame.size > DatagramHeader.MAX_FRAME) return false
        val totalFrags = (frame.size + maxPayload - 1) / maxPayload
        if (totalFrags > 0xFFFF) return false // 分片索引 u16 上限
        if (sendBuffer.size >= windowSize) {
            if (pending.size >= pendingLimit) {
                println("[RDS] tx FAIL pending overflow (${pending.size}/$pendingLimit)")
                return false
            }
            pending.addLast(frame)
            println("[RDS] tx queued len=${frame.size} pending=${pending.size} buffer=${sendBuffer.size}")
            return true
        }
        sendNow(frame)
        true
    }

    /** 把一帧切分入发送窗口并发出（调用方须持锁） */
    private fun sendNow(frame: ByteArray) {
        val totalFrags = (frame.size + maxPayload - 1) / maxPayload
        val seq = ++sendSeq
        val fragments = ArrayList<ByteArray>(totalFrags)
        for (i in 0 until totalFrags) {
            val from = i * maxPayload
            val to = minOf(from + maxPayload, frame.size)
            fragments.add(frame.copyOfRange(from, to))
        }
        sendBuffer[seq] = FrameEntry(fragments, frame.size, clock(), 0)
        sendFragments(seq, fragments, frame.size)
        println("[RDS] tx seq=$seq len=${frame.size} frags=$totalFrags buffer=${sendBuffer.size}")
    }

    /** 窗口腾出空间后把待发队列补发进窗口（调用方须持锁） */
    private fun flushPending() {
        while (sendBuffer.size < windowSize && pending.isNotEmpty()) {
            sendNow(pending.removeFirst())
        }
    }

    /** 收到一个 UDP 报文（reader 线程调用） */
    fun onDatagram(buf: ByteArray, len: Int) = synchronized(lock) {
        if (closed) return
        when (DatagramHeader.kindOf(buf, len)) {
            DatagramHeader.KIND_DATA -> onData(buf, len)
            DatagramHeader.KIND_ACK -> onAck(DatagramHeader.readAckSeq(buf, len))
            else -> {}
        }
    }

    /** 关闭会话：清空状态，停止后续调度 */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            sendBuffer.clear()
            pending.clear()
            reorder.clear()
            reassembly.clear()
            pendingBytes = 0
        }
    }

    // ---- 发送侧实现 ----

    private fun sendFragments(seq: Int, fragments: List<ByteArray>, frameLen: Int) {
        val totalFrags = fragments.size
        for (i in fragments.indices) {
            sendDatagram(DatagramHeader.encodeData(seq, i, totalFrags, frameLen, fragments[i]), peer)
        }
    }

    private fun startRetransmitLoop() {
        schedule(retransmitIntervalMs) {
            val lost = synchronized(lock) {
                if (closed) return@schedule
                var anyLost = false
                val now = clock()
                val it = sendBuffer.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next().value
                    if (now - e.lastSent >= rtoFor(e.attempts)) {
                        e.attempts++
                        if (e.attempts > MAX_ATTEMPTS) {
                            it.remove()
                            println("[RDS] LOST seq=${findSeqOf(e)} attempts=${e.attempts}")
                            anyLost = true
                        } else {
                            e.lastSent = now
                            println("[RDS] retx seq=${findSeqOf(e)} attempts=${e.attempts} buffer=${sendBuffer.size}")
                            sendFragments(e, now)
                        }
                    }
                }
                anyLost
            }
            if (lost) { onLost(); return@schedule }
            if (!closed) startRetransmitLoop()
        }
    }

    /** 重发一个 entry 的全部分片（调 lock 内执行） */
    private fun sendFragments(e: FrameEntry, now: Long) {
        for (i in e.fragments.indices) {
            sendDatagram(
                DatagramHeader.encodeData(findSeqOf(e), i, e.fragments.size, e.frameLen, e.fragments[i]),
                peer,
            )
        }
    }

    /** 从 sendBuffer 反查 entry 的帧号（retransmit 时用；buffer 小，线性可接受） */
    private fun findSeqOf(e: FrameEntry): Int =
        sendBuffer.entries.firstOrNull { it.value === e }?.key ?: 0

    private fun rtoFor(attempts: Int): Long {
        val shift = minOf(attempts, 6)
        return minOf(rtoMs * (1L shl shift), 3000L)
    }

    private fun onAck(ackSeq: Int) {
        if (ackSeq <= 0) return
        val before = sendBuffer.size
        val it = sendBuffer.entries.iterator()
        while (it.hasNext()) {
            if (it.next().key <= ackSeq) it.remove() else break // 帧号单调，超出的停止
        }
        if (before != sendBuffer.size) {
            println("[RDS] ack<- $ackSeq buffer $before->${sendBuffer.size}")
        }
        // 窗口腾出空间 → 待发队列补发（蜂窝慢 ACK 下不丢帧不断连）
        flushPending()
    }

    // ---- 接收侧实现 ----

    private fun onData(buf: ByteArray, len: Int) {
        val h = DatagramHeader.decodeData(buf, len) ?: return
        if (h.udpSeq <= 0) return
        if (h.udpSeq < nextDeliver) return // 已交付的旧帧

        var r = reassembly[h.udpSeq]
        if (r == null) {
            r = Reassemble(h.frameLen, TreeMap(), 0)
            reassembly[h.udpSeq] = r
            pendingBytes += h.frameLen
        } else if (r.parts.containsKey(h.fragIndex)) {
            return // 重复分片
        }
        r.parts[h.fragIndex] = h.payload
        r.received++

        if (r.received == h.totalFrags && r.parts.size == h.totalFrags) {
            reassembly.remove(h.udpSeq)
            val frame = ByteArray(r.frameLen)
            var off = 0
            for ((_, part) in r.parts) {
                part.copyInto(frame, off)
                off += part.size
            }
            reorder[h.udpSeq] = frame
            pendingBytes -= r.frameLen   // 已计入 reassembly；转到 reorder
            pendingBytes += frame.size
            println("[RDS] rx complete seq=${h.udpSeq} len=${r.frameLen}")
            drain()
        }
        trimMemory()
    }

    /** 按序交付完整帧 */
    private fun drain() {
        while (true) {
            val frame = reorder[nextDeliver] ?: break
            reorder.remove(nextDeliver)
            pendingBytes -= frame.size
            nextDeliver++
            ackDirty = true
            onFrame(frame)
        }
    }

    /** 内存上限：丢最旧未交付帧 */
    private fun trimMemory() {
        while (pendingBytes > memoryLimit) {
            val rk = reorder.keys.firstOrNull()
            val ak = reassembly.keys.minOrNull()
            when {
                rk != null -> {
                    pendingBytes -= reorder.remove(rk)!!.size
                }
                ak != null -> {
                    val r = reassembly.remove(ak)!!
                    pendingBytes -= r.frameLen
                }
                else -> break
            }
        }
    }

    private fun startAckLoop() {
        schedule(ackIntervalMs) {
            synchronized(lock) {
                if (closed) return@schedule
                val seq = nextDeliver - 1
                // 周期无条件重发当前累计 ACK：ACK 丢失自愈（蜂窝高丢包下，若 ACK
                // 只发一次且恰好丢失，对端窗口将永远卡死——#6 实测根因）。
                // 对端重复 ACK 幂等无害；无新交付时仅保活不刷日志。
                sendDatagram(DatagramHeader.encodeAck(seq), peer)
                if (ackDirty) {
                    ackDirty = false
                    println("[RDS] ack-> $seq")
                }
            }
            if (!closed) startAckLoop()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 10
    }
}

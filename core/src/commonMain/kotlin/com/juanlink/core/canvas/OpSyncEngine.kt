package com.juanlink.core.canvas

import com.juanlink.core.protocol.EnvelopeCodec
import com.juanlink.core.protocol.OpEnvelope
import com.juanlink.core.protocol.OpId
import com.juanlink.core.protocol.OpTransport
import com.juanlink.core.protocol.OpType
import com.juanlink.core.util.SyncLock
import com.juanlink.core.util.nowEpochMillis

/**
 * 操作级同步引擎：双端状态一致的唯一入口。
 *
 * 排序模型：
 * - **每端出站 seq**：发送方对出站 Op 编连续序号（1,2,3…）。
 * - **每端前沿队列**：接收方按「seq 连续前缀」缓冲每端操作——未补齐的
 *   seq 缺口会阻塞该端，绝不越序应用，避免破坏单端因果。
 * - **全局 OpId 全序**：在各端「前沿可达」的操作中，按 OpId(lamport, peerId)
 *   取最小者应用，保证跨端排序一致（Lamport 时钟双端收敛）。
 *
 * 其余：ack 滑动窗口清出站、applyLog 重连补同步、撤销/重做广播逆操作。
 *
 * 线程安全：本类被**两个线程**共享——UI 线程（applyLocal/undo/redo）与网络读线程
 * （onRemoteOp/onAck/resendOutstanding）。全部公共入口以 [lock] 互斥，防止
 * ConcurrentModificationException 与状态撕裂。锁为可重入（JVM synchronized），
 * 嵌套调用（如 undo → applyLocal）安全。
 */
class OpSyncEngine(
    val document: CanvasDocument,
    val peerId: String,
    private val transport: OpTransport,
) {
    /** 跨线程互斥锁（UI 画线 + 网络 ACK 并发访问共享状态） */
    private val lock = SyncLock()

    // ------------------------------------------------------------ 时钟与序列
    private var lamport: Long = 0L
    private var nextOutSeq: Long = 1L
    private var lastAppliedOpId: OpId = OpId(0, "")

    // ------------------------------------------------------------ 每端入站前沿
    /** peerId -> (seq -> envelope)，只保留 seq >= 该端 nextExpectedSeq 的包 */
    private val pendingSeq = HashMap<String, HashMap<Long, OpEnvelope>>()
    /** peerId -> 该端下一期望应用的 seq（每端从 1 起） */
    private val nextExpectedSeq = HashMap<String, Long>()

    // ------------------------------------------------------------ 出站
    private val outgoing = LinkedHashMap<Long, OpEnvelope>()

    // ------------------------------------------------------------ 应用日志
    private val applyLog = RingBuffer<OpEnvelope>(APPLY_LOG_CAPACITY)

    // ------------------------------------------------------------ 撤销历史
    private val undoStack = ArrayDeque<UndoEntry>()
    private val redoStack = ArrayDeque<UndoEntry>()
    private data class UndoEntry(val forward: CanvasOp, val inverse: CanvasOp)

    /** 事件：某端出现 seq 缺口（可触发补同步） */
    var onGapDetected: (peerId: String) -> Unit = {}

    /** 事件：状态变化（供 UI 刷新） */
    var onStateChange: () -> Unit = {}

    val pendingOutCount: Int get() = lock.withLock { outgoing.size }

    // ================================================================ 本地操作
    fun applyLocal(op: CanvasOp, recordUndo: Boolean = true): OpId? {
        // 锁内只做状态修改（document/outgoing/applyLog），**不持有锁**做网络发送——
        // 否则 UI 线程持 opEngine 锁时去取 RDS 发送锁，与网络线程持 RDS 锁取 opEngine
        // 锁（onAck）形成锁序相反死锁。envelope 在锁内生成，锁外发送（op 已入
        // outgoing，发送失败由重发机制兜底，不丢失）。
        val envelope: OpEnvelope? = lock.withLock {
            if (!canApplyNow(op)) {
                null
            } else {
                lamport++
                val opId = OpId(lamport, peerId)
                val changed = document.apply(op)
                if (recordUndo) recordUndoEntry(op, changed)
                val env = OpEnvelope(
                    opId = opId,
                    peerId = peerId,
                    opType = opTypeCode(op),
                    payload = OpCodec.encode(op),
                    seq = nextOutSeq++,
                    timestamp = nowEpochMillis(),
                )
                applyLog.add(env)
                outgoing[env.seq] = env
                env
            }
        }
        if (envelope == null) return null
        transport.sendOp(envelope)
        onStateChange()
        return envelope.opId
    }

    private fun canApplyNow(op: CanvasOp): Boolean = when (op) {
        is StrokeAppend -> document.strokeById(op.strokeId) != null
        is StrokeFinish -> document.strokeById(op.strokeId) != null
        else -> true
    }

    // ================================================================ 远程操作
    fun onRemoteOp(envelope: OpEnvelope): Boolean = lock.withLock {
        val p = envelope.peerId
        lamport = maxOf(lamport, envelope.opId.lamport)
        val expected = nextExpectedSeq[p] ?: run {
            nextExpectedSeq[p] = 1L
            1L
        }
        if (envelope.seq < expected) return@withLock false // 重复/过期
        pendingSeq.getOrPut(p) { HashMap() }[envelope.seq] = envelope
        if (envelope.seq > expected) onGapDetected(p) // 缺口：请求补同步
        drain()
        return@withLock true
    }

    /**
     * 应用各端前沿中 OpId 最小的操作，直到无前沿可达。
     * 每端严格按 seq 连续应用，缺口即停。
     */
    private fun drain() {
        while (true) {
            var best: OpEnvelope? = null
            var bestPeer: String? = null
            for ((p, expected) in nextExpectedSeq) {
                val front = pendingSeq[p]?.get(expected) ?: continue
                if (best == null || front.opId < best.opId) {
                    best = front
                    bestPeer = p
                }
            }
            val envelope = best ?: break
            val peer = bestPeer!!

            val op = OpCodec.decode(envelope.payload)
            if (op != null) {
                document.apply(op)
                applyLog.add(envelope)
                lastAppliedOpId = envelope.opId
            }
            advance(peer)
        }
        onStateChange()
    }

    /** 推进某端前沿：应用 seq=expected 后，下一期望 +1 */
    private fun advance(peer: String) {
        val expected = nextExpectedSeq[peer] ?: return
        val map = pendingSeq[peer] ?: return
        map.remove(expected)
        nextExpectedSeq[peer] = expected + 1
        if (map.isEmpty()) pendingSeq.remove(peer)
        // ack：由于按 seq 连续应用，当前 seq 即连续最高
        val applied = expected
        transport.sendAck(peer, applied, requestResync = false)
    }

    // ================================================================ ack 与重发
    /** 收到对端 Ack：清除本端已确认的出站窗口 */
    fun onAck(lastAppliedSeq: Long) = lock.withLock {
        val iter = outgoing.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().key <= lastAppliedSeq) iter.remove()
        }
        onStateChange()
    }

    /** 超时重发所有未确认操作（由定时器驱动） */
    fun resendOutstanding() = lock.withLock {
        for (envelope in outgoing.values) transport.sendOp(envelope)
    }

    // ================================================================ 补同步
    fun onSyncRequest(sinceSeq: Long) = lock.withLock {
        val ops = applyLog.filter { it.peerId == peerId && it.seq > sinceSeq }
            .sortedBy { it.seq }
            .map { EnvelopeCodec.encode(it) }
        transport.sendResync(ops, nextOutSeq - 1)
    }

    fun onResync(ops: List<ByteArray>) = lock.withLock {
        for (bytes in ops) {
            val env = EnvelopeCodec.decode(bytes) ?: continue
            if (env.peerId == peerId) continue // 不回放本端数据
            onRemoteOp(env)
        }
    }

    // ================================================================ 撤销/重做
    private fun recordUndoEntry(op: CanvasOp, changed: Boolean) {
        val inverse = buildInverse(op) ?: return
        undoStack.addLast(UndoEntry(op, inverse))
        redoStack.clear()
        if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
    }

    fun undo(): Boolean {
        val entry: UndoEntry? = lock.withLock {
            val e = undoStack.removeLastOrNull() ?: null
            if (e != null) redoStack.addLast(e)
            e
        }
        if (entry == null) return false
        // 锁外发送：applyLocal 内部锁内改状态、锁外发送，避免持锁路径
        applyLocal(entry.inverse, recordUndo = false)
        return true
    }

    fun redo(): Boolean {
        val entry: UndoEntry? = lock.withLock {
            val e = redoStack.removeLastOrNull() ?: null
            if (e != null) undoStack.addLast(e)
            e
        }
        if (entry == null) return false
        applyLocal(entry.forward, recordUndo = false)
        return true
    }

    fun canUndo(): Boolean = lock.withLock { undoStack.isNotEmpty() }
    fun canRedo(): Boolean = lock.withLock { redoStack.isNotEmpty() }

    private fun buildInverse(op: CanvasOp): CanvasOp? = when (op) {
        is StrokeAdd -> StrokeRemove(op.stroke.id)
        is StrokeAppend -> null
        is StrokeFinish -> null
        is StrokeRemove -> null // 删除撤销需保存原笔画，由增强层处理
        is LayerAddOp -> LayerRemoveOp(op.layer.id)
        is LayerRemoveOp -> {
            val l = document.layerById(op.layerId) ?: return null
            LayerAddOp(l)
        }
        is LayerReorderOp -> null
        is LayerUpdateOp -> {
            val l = document.layerById(op.layerId) ?: return null
            LayerUpdateOp(op.layerId, l.visible, l.opacity, l.locked)
        }
        is CanvasClearOp -> null
        is ImageAddOp -> ImageRemoveOp(op.imageId)
        is ImageChunkOp -> null
        is ImageReadyOp -> null
        is ImageTransformOp -> null // 撤销需变换前状态，由增强层实现
        is ImageRemoveOp -> null // 删除撤销需保存原图片，由增强层处理
        is UndoOp -> null
        is RedoOp -> null
    }

    companion object {
        const val APPLY_LOG_CAPACITY = 20000
        const val UNDO_LIMIT = 200
    }
}

// ---------------------------------------------------------------- 辅助结构
/** 环形缓冲（容量上限，淘汰最旧） */
class RingBuffer<T>(private val capacity: Int) {
    private val items = ArrayDeque<T>()

    val size: Int get() = items.size

    fun add(value: T) {
        items.addLast(value)
        while (items.size > capacity) items.removeFirst()
    }

    fun filter(predicate: (T) -> Boolean): List<T> = items.filter(predicate)

    fun lastOrNull(): T? = items.lastOrNull()

    fun clear() = items.clear()
}

/** OpType 编码工具 */
internal fun opTypeCode(op: CanvasOp): Int = when (op) {
    is StrokeAdd -> OpType.StrokeAdd.code
    is StrokeAppend -> OpType.StrokeAppend.code
    is StrokeFinish -> OpType.StrokeFinish.code
    is StrokeRemove -> OpType.StrokeRemove.code
    is LayerAddOp -> OpType.LayerAdd.code
    is LayerRemoveOp -> OpType.LayerRemove.code
    is LayerReorderOp -> OpType.LayerReorder.code
    is LayerUpdateOp -> OpType.LayerUpdate.code
    is CanvasClearOp -> OpType.CanvasClear.code
    is ImageAddOp -> OpType.ImageAdd.code
    is ImageChunkOp -> OpType.ImageChunk.code
    is ImageReadyOp -> OpType.ImageReady.code
    is ImageTransformOp -> OpType.ImageTransform.code
    is ImageRemoveOp -> OpType.ImageRemove.code
    is UndoOp -> OpType.Undo.code
    is RedoOp -> OpType.Redo.code
}

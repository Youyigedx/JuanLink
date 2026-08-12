package com.juanlink.core.draw

import com.juanlink.core.protocol.EnvelopeCodec
import com.juanlink.core.protocol.OpEnvelope
import com.juanlink.core.protocol.OpId
import com.juanlink.core.protocol.OpTransport
import com.juanlink.core.util.SyncLock
import com.juanlink.core.util.nowEpochMillis

/**
 * 文档宿主契约：操作级同步引擎对「文档」的全部要求。
 *
 * 由宿主实现（如 composeUi 的 DrawBoxHost），core 不关心文档具体形态。
 * 注意：[captureInverse] 必须在 [apply] **之前**调用——逆 op 需要读取应用前的旧状态。
 */
interface SyncDocument<Op> {
    /** 该 op 当前是否可应用（如：追加类 op 需要目标元素已存在） */
    fun canApply(op: Op): Boolean

    /**
     * 捕获 op 的逆操作（apply 前调用）。返回 null 表示该 op 不可单步撤销
     * （不记录 undo 历史，如图片分块）。
     */
    fun captureInverse(op: Op): Op?

    /** 应用 op（幂等），返回是否实际变更了状态 */
    fun apply(op: Op): Boolean

    /**
     * undo 同元素合并键：一次手势内对同一元素连续 upsert 应合并为一条 undo
     * （对齐 DrawBox 手势语义——移动/改样式逐帧产生多个 upsert）。
     * 返回 null 表示每次单独记录。
     */
    fun opElementKey(op: Op): String? = null
}

/**
 * 操作级同步引擎（泛型）：双端状态一致的唯一入口。
 *
 * 排序模型（与元素无关，原样保留自自研引擎）：
 * - **每端出站 seq**：发送方对出站 Op 编连续序号（1,2,3…）。
 * - **每端前沿队列**：接收方按「seq 连续前缀」缓冲每端操作——未补齐的
 *   seq 缺口会阻塞该端，绝不越序应用，避免破坏单端因果。
 * - **全局 OpId 全序**：在各端「前沿可达」的操作中，按 OpId(lamport, peerId)
 *   取最小者应用，保证跨端排序一致（Lamport 时钟双端收敛）。
 *
 * 其余：ack 滑动窗口清出站、applyLog 重连补同步、撤销/重做广播逆操作。
 *
 * 文档相关行为全部委托 [doc]（[SyncDocument]）：canApply/apply/captureInverse；
 * op 编解码由 [encodeOp]/[decodeOp] 提供；op 类型码由 [opTypeOf] 提供。
 *
 * 线程安全：本类被**两个线程**共享——UI 线程（applyLocal/undo/redo）与网络读线程
 * （onRemoteOp/onAck/resendOutstanding）。全部公共入口以 [lock] 互斥，防止
 * ConcurrentModificationException 与状态撕裂。锁为可重入（JVM synchronized），
 * 嵌套调用（如 undo → applyLocal）安全。
 */
class OpSyncEngine<Op>(
    private val doc: SyncDocument<Op>,
    private val encodeOp: (Op) -> ByteArray,
    private val decodeOp: (ByteArray) -> Op?,
    private val opTypeOf: (Op) -> Int,
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
    private val undoStack = ArrayDeque<UndoEntry<Op>>()
    private val redoStack = ArrayDeque<UndoEntry<Op>>()
    /**
     * 撤销项。一次手势可能产生多个 op（橡皮路径分割、批量 upsert），故
     * `forward`/`inverse` 均为列表：undo 逆序应用 inverse、redo 顺序应用
     * forward，把一次用户操作原子地回退/重放。
     */
    private data class UndoEntry<Op>(val forward: List<Op>, val inverse: List<Op>)

    /** 事件：某端出现 seq 缺口（可触发补同步） */
    var onGapDetected: (peerId: String) -> Unit = {}

    /** 事件：状态变化（供 UI 刷新） */
    var onStateChange: () -> Unit = {}

    val pendingOutCount: Int get() = lock.withLock { outgoing.size }

    // ================================================================ 本地操作
    fun applyLocal(op: Op, recordUndo: Boolean = true): OpId? =
        applyLocalImpl(op, explicitInverse = null, recordUndo = recordUndo)

    /**
     * 应用本地 op 并记录撤销项，逆 op 由调用方**显式提供**而非 [captureInverse]。
     * 宿主用 reducer 先行应用 intent 后，engine 的 captureInverse 捕获到的是
     * 「手势后」状态（逆与正同值，undo 无效）；显式逆基于 diff 的 `before` 生成，
     * undo 能正确回到手势前状态。
     */
    fun applyLocalWithInverse(op: Op, inverse: Op?): OpId? =
        applyLocalImpl(op, explicitInverse = inverse, recordUndo = true)

    private fun applyLocalImpl(op: Op, explicitInverse: Op?, recordUndo: Boolean): OpId? {
        // 锁内只做状态修改（doc/outgoing/applyLog），**不持有锁**做网络发送——
        // 否则 UI 线程持 opEngine 锁时去取 RDS 发送锁，与网络线程持 RDS 锁取 opEngine
        // 锁（onAck）形成锁序相反死锁。envelope 在锁内生成，锁外发送（op 已入
        // outgoing，发送失败由重发机制兜底，不丢失）。
        val envelope: OpEnvelope? = lock.withLock {
            if (!doc.canApply(op)) {
                null
            } else {
                lamport++
                val opId = OpId(lamport, peerId)
                // 逆 op 必须在 apply 之前捕获（读取应用前状态）
                val inverse = if (recordUndo) {
                    if (explicitInverse != null) explicitInverse else doc.captureInverse(op)
                } else {
                    null
                }
                doc.apply(op)
                if (recordUndo && inverse != null) recordUndoEntry(op, inverse)
                val env = OpEnvelope(
                    opId = opId,
                    peerId = peerId,
                    opType = opTypeOf(op),
                    payload = encodeOp(op),
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

            val op = decodeOp(envelope.payload)
            if (op != null) {
                doc.apply(op)
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
    /**
     * 记录撤销项（单 op）。同元素手势内连续 upsert 合并为一条：
     * `forward` 更新为最新 op，`inverse` 保留手势首次捕获的旧值（恢复即回到手势起点）。
     */
    private fun recordUndoEntry(op: Op, inverse: Op) {
        val key = doc.opElementKey(op)
        if (key != null && undoStack.isNotEmpty()) {
            val top = undoStack.last()
            if (top.forward.isNotEmpty() && doc.opElementKey(top.forward.last()) == key) {
                undoStack.removeLast()
                undoStack.addLast(UndoEntry(listOf(op), top.inverse))
                redoStack.clear()
                if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
                return
            }
        }
        undoStack.addLast(UndoEntry(listOf(op), listOf(inverse)))
        redoStack.clear()
        if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
    }

    /**
     * 批量应用一组本地 op 并记录为**一条**撤销项。用于一次手势产生多个
     * 元素变化的场景（橡皮路径分割：主段 upsert + 新段 upsert），保证一次
     * 撤销原子回退整次手势。
     *
     * 每个元素携带**显式逆**（由宿主 diff 时基于 `before` 生成）：宿主用 reducer
     * 先行应用 intent 后，engine 若用 captureInverse 捕获，拿到的是「手势后」状态
     * （逆与正同值，undo 无效）。显式逆在 undo 时逆序应用，跳过 captureInverse。
     * 逆为 null 的 op（图片分块等）仍广播，但不进撤销项。
     */
    fun applyLocalBatch(ops: List<Pair<Op, Op?>>): Boolean {
        if (ops.isEmpty()) return false
        val forward = ArrayList<Op>()
        val inverse = ArrayList<Op>()
        val envelopes = ArrayList<OpEnvelope>()
        lock.withLock {
            for ((op, inv) in ops) {
                if (!doc.canApply(op)) continue
                doc.apply(op)
                lamport++
                val opId = OpId(lamport, peerId)
                val env = OpEnvelope(
                    opId = opId,
                    peerId = peerId,
                    opType = opTypeOf(op),
                    payload = encodeOp(op),
                    seq = nextOutSeq++,
                    timestamp = nowEpochMillis(),
                )
                applyLog.add(env)
                outgoing[env.seq] = env
                envelopes.add(env)
                if (inv != null) {
                    forward.add(op)
                    inverse.add(inv)
                }
            }
        }
        for (env in envelopes) transport.sendOp(env)
        if (forward.isNotEmpty()) {
            lock.withLock {
                undoStack.addLast(UndoEntry(forward, inverse))
                redoStack.clear()
                if (undoStack.size > UNDO_LIMIT) undoStack.removeFirst()
            }
        }
        onStateChange()
        return forward.isNotEmpty()
    }

    fun undo(): Boolean {
        val entry: UndoEntry<Op>? = lock.withLock {
            val e = undoStack.removeLastOrNull() ?: null
            if (e != null) redoStack.addLast(e)
            e
        }
        if (entry == null) return false
        // 锁外发送：applyLocal 内部锁内改状态、锁外发送，避免持锁路径
        for (op in entry.inverse.asReversed()) {
            applyLocal(op, recordUndo = false)
        }
        return true
    }

    fun redo(): Boolean {
        val entry: UndoEntry<Op>? = lock.withLock {
            val e = redoStack.removeLastOrNull() ?: null
            if (e != null) undoStack.addLast(e)
            e
        }
        if (entry == null) return false
        for (op in entry.forward) {
            applyLocal(op, recordUndo = false)
        }
        return true
    }

    fun canUndo(): Boolean = lock.withLock { undoStack.isNotEmpty() }
    fun canRedo(): Boolean = lock.withLock { redoStack.isNotEmpty() }

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

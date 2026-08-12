package com.juanlink.composeui.draw

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.juanlink.core.crypto.CryptoEngine
import com.juanlink.core.draw.DrawOp
import com.juanlink.core.draw.ImageChunking
import com.juanlink.core.draw.OpSyncEngine
import com.juanlink.core.draw.SyncDocument
import com.juanlink.core.draw.WireElement
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.PayLoad
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer

/**
 * DrawBox 引擎的协作宿主。
 *
 * 两个角色：
 * - **本地意图入口**：DrawBox 组件 `onIntent` / 工具栏回调 → `reducer.reduce`
 *   改 state → diff 前后 elements → 生成同步 op 交 `engine.applyLocal` 广播。
 * - **SyncDocument&lt;DrawOp&gt;**（引擎的 doc）：远程 op 幂等应用，直接
 *   `State.copy` 替换元素，**不调 reducer、不 snapshot、不广播**——天然回环防护
 *   （`DrawBox` 组件的 `onIntent` 是本地意图的唯一入口，远程应用不经过它）。
 *
 * 图片同步（分块）：内容变更的 Image 元素发「占位 upsert」（imageData 空）
 * → ImageChunkOp×N → ImageReadyOp（SHA-256 校验）；接收端缓冲/重组/校验后
 * 写回 `Element.Image.bytes`。纯几何变更（移动/缩放/旋转，bytes 未变）走完整
 * upsert（imageData base64），避免重传字节。
 *
 * 撤销走引擎自身的 forward/inverse 逆 op（跨端严格一致），DrawBox 内置
 * `State.history` 快照栈保持空栈不用（reducer 的 snapshot 只在本地手势时短暂
 * 填充，不参与跨端语义）。
 */
class DrawBoxHost(
    private val reducer: Reducer = Reducer(UseCase()),
    private val useCase: UseCase = UseCase(),
) : SyncDocument<DrawOp> {

    /** 响应式 DrawBox 状态（Compose 渲染直接订阅，远程/本地变更均驱动重组） */
    var state by mutableStateOf(State(bgColor = Color.White))
        private set

    /** 操作同步引擎（AppState 接线；null 时本地绘制可用、撤销/同步不可用） */
    var engine: OpSyncEngine<DrawOp>? = null

    /** 文本编辑请求回调（SELECT 模式点击/双击 Text 元素触发，UI 弹编辑框） */
    var onTextEditRequested: (elementId: String) -> Unit = {}

    /**
     * 擦除手势批处理：BeginErase 起收集该手势内每次 EraseAt diff 出的
     * (op, 显式逆) 对，EndErase 统一 applyLocalBatch 提交为**一条** undo 项——
     * 橡皮路径分割一次手势产生主段 upsert + 新段 upsert 多个 op，若不合并则
     * 一次撤销只回退一段。显式逆由 diff 的 before 生成：captureInverse 不可用，
     * 因为 reducer 已先行改 state，engine 捕获到的是擦除后的状态（逆与正同值，
     * undo 无效）。
     */
    private val eraseSessionOps = ArrayList<Pair<DrawOp, DrawOp?>>()
    private var inEraseSession = false

    /** 图片数据就绪回调（缺块/校验信息，UI 可选订阅） */
    var onImageReady: (imageId: String, bytes: ByteArray, checksumOk: Boolean) -> Unit = { _, _, _ -> }

    /** 缺块重传请求回调（校验失败时触发，阶段内由上层实现重传） */
    var onImageResendNeeded: (imageId: String, missingChunks: List<Int>) -> Unit = { _, _ -> }

    val elements: List<Element> get() = state.elements

    /** 当前画布可序列化载荷（快照捕获用） */
    fun toPayLoad(): PayLoad = PayLoad(state.bgColor, state.elements)

    // ================================================================ 本地意图
    /**
     * 本地意图入口。除 reducer 应用 + 同步 diff 外，还复刻 DrawBoxController 的
     * 文本编辑事件缝（`Event.TextEditRequested` 原本由 controller 发射）：
     * - `RequestTextEditAt`（SELECT 模式双击文本）→ 命中 Text 触发编辑；
     * - `SelectAt` 二次点击已唯一选中的 Text → 触发编辑；
     * - `InsertText`（Mode.TEXT 点击）→ 插入空文本后立即弹编辑框。
     * 命中判定复用 UseCase.hitTopmost（与 Reducer 同一语义）。
     */
    fun onLocalIntent(intent: Intent) {
        when (intent) {
            is Intent.BeginErase -> {
                // 新一轮擦除手势：清空待批量 op（防御残留），进入收集模式。
                // reducer 的 BeginErase 只清 dirty flag，不产生元素变化。
                eraseSessionOps.clear()
                inEraseSession = true
            }
            is Intent.RequestTextEditAt -> {
                val hit = useCase.hitTopmost(state.elements, intent.offset, intent.tolerance)
                if (hit is Element.Text) onTextEditRequested(hit.id)
            }
            is Intent.SelectAt -> {
                val hit = useCase.hitTopmost(state.elements, intent.offset, intent.tolerance)
                if (hit is Element.Text && state.selectedIds == setOf(hit.id)) onTextEditRequested(hit.id)
            }
            else -> {}
        }
        val before = elementsById()
        state = reducer.reduce(state, intent)
        val after = elementsById()
        if (intent is Intent.InsertText) {
            val newId = after.keys.firstOrNull { it !in before }
            if (newId != null) onTextEditRequested(newId)
        }
        val ops = dispatchLocalOps(before, after)
        when (intent) {
            is Intent.EraseAt -> {
                if (inEraseSession) eraseSessionOps.addAll(ops)
                else ops.forEach { engine?.applyLocalWithInverse(it.first, it.second) }
            }
            is Intent.EndErase -> {
                inEraseSession = false
                if (eraseSessionOps.isNotEmpty()) engine?.applyLocalBatch(eraseSessionOps)
                eraseSessionOps.clear()
            }
            // 全部本地 op 用显式逆（before 语义）：captureInverse 在 reducer 已应用后
            // 捕获到的是手势后状态，对移动等无同元素合并的操作 undo 无效。
            else -> ops.forEach { engine?.applyLocalWithInverse(it.first, it.second) }
        }
    }

    /**
     * diff 前后元素 → (同步 op, 显式逆) 列表。图片内容变更走占位+分块（chunk/ready
     * 无逆）。显式逆基于 `before` 生成：upsert 有旧值 → 恢复旧值；无旧值 → 移除；
     * remove → 恢复被删元素。reducer 已先行改 state，engine 无法从当前 state 反推
     * 旧值，故逆必须在 diff 时捕获。
     */
    private fun dispatchLocalOps(before: Map<String, Element>, after: Map<String, Element>): List<Pair<DrawOp, DrawOp?>> {
        val ops = ArrayList<Pair<DrawOp, DrawOp?>>()
        for ((id, el) in after) {
            val prev = before[id]
            if (prev == el) continue
            val inverse: DrawOp? = if (prev != null) DrawOp.ElementUpsert(prev.toWire())
            else DrawOp.ElementRemove(id)
            val contentChanged = prev !is Element.Image ||
                (el is Element.Image && !el.bytes.contentEquals(prev.bytes))
            if (el is Element.Image && el.bytes.isNotEmpty() && contentChanged) {
                ops += DrawOp.ElementUpsert(el.toWire(includeImageData = false)) to inverse
                val chunks = ImageChunking.chunkBytes(el.bytes)
                chunks.forEachIndexed { i, chunk ->
                    ops += DrawOp.ImageChunkOp(el.id, i, chunks.size, chunk) to null
                }
                ops += DrawOp.ImageReadyOp(el.id, CryptoEngine.sha256(el.bytes)) to null
            } else {
                ops += DrawOp.ElementUpsert(el.toWire()) to inverse
            }
        }
        for (id in before.keys) {
            if (!after.containsKey(id)) {
                ops += DrawOp.ElementRemove(id) to before[id]?.let { DrawOp.ElementUpsert(it.toWire()) }
            }
        }
        return ops
    }

    // ================================================================ 撤销/重做
    fun undo(): Boolean = engine?.undo() ?: false
    fun redo(): Boolean = engine?.redo() ?: false
    fun canUndo(): Boolean = engine?.canUndo() ?: false
    fun canRedo(): Boolean = engine?.canRedo() ?: false

    // ================================================================ SyncDocument<DrawOp>
    override fun canApply(op: DrawOp): Boolean = true

    override fun captureInverse(op: DrawOp): DrawOp? = when (op) {
        // upsert 已有元素 → 逆 = 旧内容 upsert；upsert 新元素（橡皮路径分割的新段）→
        // 逆 = remove（否则撤销时新段残留、且无逆 op 不进 undo 栈导致撤销不完整）。
        is DrawOp.ElementUpsert -> {
            val existing = elementById(op.element.id)
            if (existing != null) DrawOp.ElementUpsert(existing.toWire())
            else DrawOp.ElementRemove(op.element.id)
        }
        is DrawOp.ElementRemove -> elementById(op.elementId)?.let { DrawOp.ElementUpsert(it.toWire()) }
        else -> null
    }

    override fun apply(op: DrawOp): Boolean = when (op) {
        is DrawOp.ElementUpsert -> applyUpsert(op.element)
        is DrawOp.ElementRemove -> applyRemove(op.elementId)
        is DrawOp.ImageChunkOp -> applyImageChunk(op)
        is DrawOp.ImageReadyOp -> applyImageReady(op)
    }

    override fun opElementKey(op: DrawOp): String? = when (op) {
        is DrawOp.ElementUpsert -> op.element.id
        is DrawOp.ElementRemove -> op.elementId
        else -> null
    }

    // ================================================================ diff
    private fun elementsById(): Map<String, Element> = state.elements.associateBy { it.id }

    // ================================================================ 远程应用
    private fun applyUpsert(wire: WireElement): Boolean {
        val incoming = wire.toElement()
        val existing = elementById(wire.id)
        if (existing != null && !shouldOverride(existing, incoming)) return false
        state = state.copy(
            elements = state.elements.filterNot { it.id == wire.id } + incoming,
        )
        return true
    }

    private fun applyRemove(id: String): Boolean {
        if (!state.elements.any { it.id == id }) return false
        state = state.copy(
            elements = state.elements.filterNot { it.id == id },
            selectedIds = state.selectedIds - id,
        )
        return true
    }

    /**
     * 冲突消解：按内容决定覆盖。engine 全序（Lamport + 每端 seq 连续应用）已保证
     * 双端 apply 顺序一致，同元素多次 upsert 的结果自然收敛，无需 modifiedAt LWW——
     * 反而必须去掉：撤销/重做广播的逆 op 恢复的是旧内容（旧 modifiedAt），若按
     * modifiedAt 判定会被「后到者胜」拒绝，跨端 undo/redo 全部失效。
     * 内容序列化等价（本地重放/重复 upsert）→ 跳过，保持幂等；否则覆盖。
     */
    private fun shouldOverride(existing: Element, incoming: Element): Boolean =
        existing.toWire() != incoming.toWire()

    private fun elementById(id: String): Element? = state.elements.firstOrNull { it.id == id }

    // ================================================================ 图片分块缓冲
    /** 图片分块缓冲：imageId -> (chunkIndex -> bytes) */
    private val imageChunks = HashMap<String, HashMap<Int, ByteArray>>()
    private val expectedChunks = HashMap<String, Int>()

    private fun applyImageChunk(op: DrawOp.ImageChunkOp): Boolean {
        imageChunks.getOrPut(op.imageId) { HashMap() }[op.chunkIndex] = op.payload
        expectedChunks[op.imageId] = op.totalChunks
        return true
    }

    private fun applyImageReady(op: DrawOp.ImageReadyOp): Boolean {
        val chunks = imageChunks[op.imageId] ?: return false
        val total = expectedChunks[op.imageId] ?: 0
        // 缺块检测
        val missing = (0 until total).filter { !chunks.containsKey(it) }
        if (missing.isNotEmpty()) {
            onImageResendNeeded(op.imageId, missing)
            return false
        }
        // 组装（末块可能较短，按实际总大小）
        val totalSize = (0 until total).sumOf { chunks[it]?.size ?: 0 }
        val bytes = ByteArray(totalSize)
        var offset = 0
        for (i in 0 until total) {
            val chunk = chunks[i]!!
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        val checksumOk = CryptoEngine.constantTimeEquals(CryptoEngine.sha256(bytes), op.checksum)
        imageChunks.remove(op.imageId)
        expectedChunks.remove(op.imageId)
        // 校验通过才写入元素字节；失败保留占位（等待重传）
        if (checksumOk) {
            val el = elementById(op.imageId) as? Element.Image
            if (el != null && !el.bytes.contentEquals(bytes)) {
                state = state.copy(
                    elements = state.elements.map { if (it.id == op.imageId) el.copy(bytes = bytes) else it },
                )
            }
        }
        onImageReady(op.imageId, bytes, checksumOk)
        return true
    }
}

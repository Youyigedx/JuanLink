package com.juanlink.core.canvas

import com.juanlink.core.crypto.CryptoEngine
import com.juanlink.core.model.Affine2
import com.juanlink.core.model.ImageObject
import com.juanlink.core.model.Layer
import com.juanlink.core.model.Stroke
import com.juanlink.core.model.StrokePoint

/**
 * 无限画布文档（本地状态）。
 *
 * 状态由 Op 序列驱动：任意 Op 应用后，状态机保持一致。
 * 图层按 index 升序绘制（小者在下）；stroke/images 依 id 索引。
 */
class CanvasDocument(
    val id: String,
    var title: String = "未命名画布",
) {
    val layers = mutableListOf<Layer>()
    private val strokes = LinkedHashMap<String, Stroke>()
    private val images = LinkedHashMap<String, ImageObject>()
    private val imageData = HashMap<String, ByteArray>()
    private val pendingStrokes = HashMap<String, Stroke>()
    private val orphanAppends = HashMap<String, MutableList<StrokePoint>>()

    /** 图片分块缓冲：imageId -> (chunkIndex -> bytes) */
    private val imageChunks = HashMap<String, HashMap<Int, ByteArray>>()
    private val expectedChunks = HashMap<String, Int>()
    private val expectedChecksum = HashMap<String, ByteArray>()
    /** 图片二进制就绪回调（UI 订阅渲染） */
    var onImageReady: (imageId: String, bytes: ByteArray, checksumOk: Boolean) -> Unit = { _, _, _ -> }
    /** 缺块重传请求回调（校验失败时触发） */
    var onImageResendNeeded: (imageId: String, missingChunks: List<Int>) -> Unit = { _, _ -> }

    /** 状态变更回调（渲染层订阅，收集脏矩形） */
    var onChange: (CanvasOp) -> Unit = {}

    init {
        layers.add(Layer(id = "layer-1", name = "图层 1", index = 0))
    }

    fun strokeById(id: String): Stroke? = strokes[id] ?: pendingStrokes[id]

    fun allStrokes(): List<Stroke> = strokes.values.toList() + pendingStrokes.values

    fun strokesOnLayer(layerId: String): List<Stroke> =
        strokes.values.filter { it.layerId == layerId }

    fun imageById(id: String): ImageObject? = images[id]

    fun allImages(): List<ImageObject> = images.values.toList()

    /** 已就绪图片的二进制数据 */
    fun imageBytes(imageId: String): ByteArray? = imageData[imageId]

    fun layerById(id: String): Layer? = layers.firstOrNull { it.id == id }

    /**
     * 应用一个操作到文档。
     * @return 是否改变了文档状态（用于脏矩形判定）
     */
    fun apply(op: CanvasOp): Boolean {
        val changed = when (op) {
            is StrokeAdd -> applyStrokeAdd(op.stroke)
            is StrokeAppend -> applyStrokeAppend(op.strokeId, op.points)
            is StrokeFinish -> applyStrokeFinish(op.strokeId)
            is StrokeRemove -> applyStrokeRemove(op.strokeId)
            is LayerAddOp -> applyLayerAdd(op.layer)
            is LayerRemoveOp -> applyLayerRemove(op.layerId)
            is LayerReorderOp -> applyLayerReorder(op.layerId, op.newIndex)
            is LayerUpdateOp -> applyLayerUpdate(op.layerId, op.visible, op.opacity, op.locked)
            is CanvasClearOp -> applyClear(op)
            is ImageAddOp -> applyImageAdd(op)
            is ImageChunkOp -> applyImageChunk(op)
            is ImageReadyOp -> applyImageReady(op)
            is ImageTransformOp -> applyImageTransform(op)
            is ImageRemoveOp -> applyImageRemove(op)
            is UndoOp -> false
            is RedoOp -> false
        }
        if (changed) onChange(op)
        return changed
    }

    // ---------------------------------------------------------------- 应用
    private fun applyStrokeAdd(stroke: Stroke): Boolean {
        val key = stroke.id
        val merged = strokes[key]
        if (merged != null) return false
        // 合并进行中笔画与跨端乱序到达的追加点
        val pending = pendingStrokes.remove(key)
        val orphan = orphanAppends.remove(key)
        val final = when {
            pending != null && orphan != null -> stroke.copy(points = orphan + pending.points + stroke.points)
            pending != null -> stroke.copy(points = pending.points + stroke.points)
            orphan != null -> stroke.copy(points = orphan + stroke.points)
            else -> stroke
        }
        strokes[key] = final
        return true
    }

    private fun applyStrokeAppend(strokeId: String, points: List<StrokePoint>): Boolean {
        if (points.isEmpty()) return false
        val existing = strokes[strokeId]
        if (existing != null) {
            strokes[strokeId] = existing.copy(points = existing.points + points)
            return true
        }
        val pending = pendingStrokes[strokeId]
        if (pending != null) {
            pendingStrokes[strokeId] = pending.copy(points = pending.points + points)
            return true
        }
        // 未知笔画（跨端因果乱序）：缓冲追加点，待 StrokeAdd 到达时合并
        val orphan = orphanAppends.getOrPut(strokeId) { mutableListOf() }
        orphan.addAll(points)
        return false
    }

    private fun applyStrokeFinish(strokeId: String): Boolean {
        val pending = pendingStrokes.remove(strokeId) ?: return false
        strokes[strokeId] = pending
        return true
    }

    private fun applyStrokeRemove(strokeId: String): Boolean {
        val removed = strokes.remove(strokeId) != null
        pendingStrokes.remove(strokeId)
        return removed
    }

    private fun applyLayerAdd(layer: Layer): Boolean {
        if (layers.any { it.id == layer.id }) return false
        layers.add(layer)
        layers.sortBy { it.index }
        return true
    }

    private fun applyLayerRemove(layerId: String): Boolean {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx < 0) return false
        layers.removeAt(idx)
        return true
    }

    private fun applyLayerReorder(layerId: String, newIndex: Int): Boolean {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx < 0) return false
        val layer = layers.removeAt(idx)
        layers.add(newIndex.coerceIn(0, layers.size), layer)
        layers.forEachIndexed { i, l -> layers[i] = l.copy(index = i) }
        return true
    }

    private fun applyLayerUpdate(layerId: String, visible: Boolean?, opacity: Float?, locked: Boolean?): Boolean {
        val idx = layers.indexOfFirst { it.id == layerId }
        if (idx < 0) return false
        val l = layers[idx]
        layers[idx] = l.copy(
            visible = visible ?: l.visible,
            opacity = opacity ?: l.opacity,
            locked = locked ?: l.locked,
        )
        return true
    }

    private fun applyClear(op: CanvasClearOp): Boolean {
        val removed = strokes.isNotEmpty() || pendingStrokes.isNotEmpty() || images.isNotEmpty()
        strokes.clear()
        pendingStrokes.clear()
        orphanAppends.clear()
        images.clear()
        imageData.clear()
        imageChunks.clear()
        return removed
    }

    // ---------------------------------------------------------------- 图片
    private fun applyImageAdd(op: ImageAddOp): Boolean {
        if (images.containsKey(op.imageId)) return false
        images[op.imageId] = ImageObject(
            id = op.imageId,
            layerId = op.layerId,
            uri = op.uri,
            transform = op.transform,
            width = op.width,
            height = op.height,
        )
        return true
    }

    private fun applyImageChunk(op: ImageChunkOp): Boolean {
        if (!images.containsKey(op.imageId)) return false
        imageChunks.getOrPut(op.imageId) { HashMap() }[op.chunkIndex] = op.payload
        expectedChunks[op.imageId] = op.totalChunks
        return true
    }

    private fun applyImageReady(op: ImageReadyOp): Boolean {
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
        // 校验
        val checksumOk = CryptoEngine.constantTimeEquals(CryptoEngine.sha256(bytes), op.checksum)
        imageData[op.imageId] = bytes
        imageChunks.remove(op.imageId)
        expectedChunks.remove(op.imageId)
        expectedChecksum.remove(op.imageId)
        onImageReady(op.imageId, bytes, checksumOk)
        return true
    }

    private fun applyImageTransform(op: ImageTransformOp): Boolean {
        val img = images[op.imageId] ?: return false
        images[op.imageId] = img.copy(transform = op.transform)
        return true
    }

    private fun applyImageRemove(op: ImageRemoveOp): Boolean {
        val removed = images.remove(op.imageId) != null
        imageData.remove(op.imageId)
        imageChunks.remove(op.imageId)
        return removed
    }
}

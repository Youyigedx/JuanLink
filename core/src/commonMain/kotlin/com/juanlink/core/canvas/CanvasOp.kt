package com.juanlink.core.canvas

import com.juanlink.core.model.Affine2
import com.juanlink.core.model.Layer
import com.juanlink.core.model.Stroke
import com.juanlink.core.model.StrokePoint
import com.juanlink.core.protocol.OpId
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * 画布业务操作（操作级同步的最小单元）。
 * 铁律：只同步 Op，绝不同步整画布。每 Op 经 OpEnvelope 封装后加密传输。
 * 图片操作（ImageAdd/Chunk/Transform 等）在阶段 5 加入。
 */
@Serializable
sealed interface CanvasOp

// ---- 笔迹 ----
@Serializable
data class StrokeAdd(val stroke: Stroke) : CanvasOp

/** 进行中笔画的增量点列（绘制过程实时同步） */
@Serializable
data class StrokeAppend(val strokeId: String, val points: List<StrokePoint>) : CanvasOp

/** 笔画完成（结束压力/笔锋采样，触发渲染缓存） */
@Serializable
data class StrokeFinish(val strokeId: String) : CanvasOp

@Serializable
data class StrokeRemove(val strokeId: String) : CanvasOp

// ---- 图层 ----
@Serializable
data class LayerAddOp(val layer: Layer) : CanvasOp

@Serializable
data class LayerRemoveOp(val layerId: String) : CanvasOp

@Serializable
data class LayerReorderOp(val layerId: String, val newIndex: Int) : CanvasOp

@Serializable
data class LayerUpdateOp(
    val layerId: String,
    val visible: Boolean? = null,
    val opacity: Float? = null,
    val locked: Boolean? = null,
) : CanvasOp

// ---- 画布 ----
@Serializable
data class CanvasClearOp(val token: Long) : CanvasOp

// ---- 图片 ----
/** 图片元数据（即时同步，本地先占位） */
@Serializable
data class ImageAddOp(
    val imageId: String,
    val layerId: String,
    val uri: String,
    val transform: Affine2 = Affine2.IDENTITY,
    val width: Float,
    val height: Float,
) : CanvasOp

/** 图片二进制分块 */
@Serializable
data class ImageChunkOp(
    val imageId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val payload: ByteArray,
) : CanvasOp

/** 分块齐整后的校验（SHA-256），失败触发缺块重传 */
@Serializable
data class ImageReadyOp(
    val imageId: String,
    val checksum: ByteArray,
) : CanvasOp

/** 图片变换（移动/缩放/旋转） */
@Serializable
data class ImageTransformOp(
    val imageId: String,
    val transform: Affine2,
) : CanvasOp

@Serializable
data class ImageRemoveOp(
    val imageId: String,
) : CanvasOp

// ---- 撤销/重做（带目标 OpId，供跨端历史对齐） ----
@Serializable
data class UndoOp(val targetOpId: OpId) : CanvasOp

@Serializable
data class RedoOp(val targetOpId: OpId) : CanvasOp

/** CanvasOp 编解码（CBOR 二进制） */
@OptIn(ExperimentalSerializationApi::class)
object OpCodec {

    private val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(op: CanvasOp): ByteArray = cbor.encodeToByteArray(CanvasOp.serializer(), op)

    fun decode(bytes: ByteArray): CanvasOp? =
        runCatching { cbor.decodeFromByteArray(CanvasOp.serializer(), bytes) }.getOrNull()
}

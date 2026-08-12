package com.juanlink.core.draw

import com.juanlink.core.protocol.OpType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * 绘图业务操作（操作级同步的最小单元）。
 *
 * 铁律：只同步 Op，绝不同步整画布。每 Op 经 OpEnvelope 封装后加密传输。
 *
 * 模型以 DrawBox 引擎的元素为准（[WireElement] 逐字段镜像 DrawBox 的
 * SerializableElement，core 不依赖 DrawBox 类型——映射由 composeUi 适配层完成）：
 * - [ElementUpsert]：元素全量幂等替换（新增/变更，LWW 后到者胜）。
 * - [ElementRemove]：删除元素。
 * - [ImageChunkOp]/[ImageReadyOp]：图片二进制分块 + SHA-256 校验（占位后填充）。
 */
@Serializable
sealed interface DrawOp {

    /** 元素全量幂等替换 */
    @Serializable
    data class ElementUpsert(val element: WireElement) : DrawOp

    /** 删除元素 */
    @Serializable
    data class ElementRemove(val elementId: String) : DrawOp

    /** 图片二进制分块（元素占位后逐步填充） */
    @Serializable
    data class ImageChunkOp(
        val imageId: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val payload: ByteArray,
    ) : DrawOp

    /** 分块齐整后的校验（SHA-256），失败触发缺块重传 */
    @Serializable
    data class ImageReadyOp(
        val imageId: String,
        val checksum: ByteArray,
    ) : DrawOp
}

/**
 * 线协议上的元素 DTO：逐字段镜像 DrawBox `SerializableElement`
 * （见 composeUi `io.ak1.drawbox.domain.model.Serialization.kt`）。
 * 坐标/点列/采样用逗号分隔字符串编码，颜色用 hex，图片数据用 Base64——
 * 与 DrawBox 的 `toDto()`/`toElement()` 约定完全一致，composeUi 适配层可直接互转。
 *
 * 这些字段是 DrawBox 的「线协议稳定格式」：任何新增可空字段必须带默认值，
 * 保证旧文件 / 旧 op 仍可解码（ignoreUnknownKeys 兜底 + 默认值兜底）。
 */
@Serializable
data class WireElement(
    val id: String,
    val type: String,
    val zIndex: Int,
    /** Shape / Image 位置列表（"x,y" 串）；Image 为 `[topLeft, bottomRight]` */
    val points: List<String>,
    val strokeColor: String,
    val strokeWidth: Float,
    val alpha: Float? = null,
    val shapeType: String? = null,
    val fillColor: String? = null,
    val rotation: Float? = null,
    val cornerRadius: Float? = null,
    val strokeStyle: String? = null,
    val bend: String? = null,
    val startBinding: String? = null,
    val endBinding: String? = null,
    val createdAt: Long? = null,
    val modifiedAt: Long? = null,
    /** Path 采样列表（"x,y,w" 串）；仅 type = "Path" 有值 */
    val samples: List<String>? = null,
    /** Shape 描边开关：null/true = 画描边，false = 仅填充 */
    val strokeEnabled: Boolean? = null,
    /** 图片编码数据（Base64）；仅 Image 元素有值 */
    val imageData: String? = null,
    val intrinsicWidth: Float? = null,
    val intrinsicHeight: Float? = null,
    /** Image / Text 的渲染透明度 */
    val opacity: Float? = null,
    /** Text 内容；仅 Text 元素有值 */
    val text: String? = null,
    val fontFamilyKey: String? = null,
    val fontSize: Float? = null,
    /** Text 水平对齐：LEFT / CENTER / RIGHT */
    val alignment: String? = null,
    /** Text 环绕盒左上角（"x,y" 串）；仅 Text 元素有值 */
    val textTopLeft: String? = null,
    val wrapWidth: Float? = null,
)

/** DrawOp 编解码（CBOR 二进制） */
@OptIn(ExperimentalSerializationApi::class)
object DrawOpCodec {

    private val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(op: DrawOp): ByteArray = cbor.encodeToByteArray(DrawOp.serializer(), op)

    fun decode(bytes: ByteArray): DrawOp? =
        runCatching { cbor.decodeFromByteArray(DrawOp.serializer(), bytes) }.getOrNull()
}

/** DrawOp 类型编码（OpEnvelope.opType，诊断用） */
fun drawOpTypeCode(op: DrawOp): Int = when (op) {
    is DrawOp.ElementUpsert -> OpType.ElementUpsert.code
    is DrawOp.ElementRemove -> OpType.ElementRemove.code
    is DrawOp.ImageChunkOp -> OpType.ImageChunk.code
    is DrawOp.ImageReadyOp -> OpType.ImageReady.code
}

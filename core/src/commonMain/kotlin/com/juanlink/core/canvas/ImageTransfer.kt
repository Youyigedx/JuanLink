package com.juanlink.core.canvas

import com.juanlink.core.crypto.CryptoEngine
import com.juanlink.core.model.Affine2

/**
 * 图片传输辅助：把图片字节分块并作为操作序列发送。
 *
 * 流程：ImageAdd（元数据占位）→ ImageChunk（分块）→ ImageReady（SHA-256 校验）。
 * 接收端缺块时经 onImageResendNeeded 请求重传（阶段内由上层实现重传）。
 */
object ImageTransfer {

    /** 单块大小（16KB，兼顾可靠性） */
    const val CHUNK_SIZE = 16 * 1024

    fun sendImage(
        engine: OpSyncEngine,
        imageId: String,
        layerId: String,
        bytes: ByteArray,
        width: Float,
        height: Float,
        transform: Affine2 = Affine2.IDENTITY,
    ) {
        engine.applyLocal(ImageAddOp(imageId, layerId, "image://$imageId", transform, width, height))
        val total = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE
        for (i in 0 until total) {
            val from = i * CHUNK_SIZE
            val to = minOf(from + CHUNK_SIZE, bytes.size)
            engine.applyLocal(ImageChunkOp(imageId, i, total, bytes.copyOfRange(from, to)))
        }
        engine.applyLocal(ImageReadyOp(imageId, CryptoEngine.sha256(bytes)))
    }
}

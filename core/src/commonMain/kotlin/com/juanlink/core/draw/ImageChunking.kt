package com.juanlink.core.draw

/**
 * 图片分块辅助：把图片字节切成固定大小块，供操作级同步逐块发送。
 *
 * 发送流程（composeUi DrawBoxHost 编排）：占位 upsert（imageData 空）
 * → [chunkBytes] 逐块 ImageChunkOp → ImageReadyOp（SHA-256 校验）。
 * 接收端在 apply(ImageReadyOp) 时缺块检测 / 重组 / 校验，与自研引擎时代
 * ImageTransfer 的 16KB 分块 + SHA-256 校验模式一致。
 */
object ImageChunking {

    /** 单块大小（16KB，兼顾可靠性） */
    const val CHUNK_SIZE = 16 * 1024

    fun chunkCount(bytes: ByteArray): Int = (bytes.size + CHUNK_SIZE - 1) / CHUNK_SIZE

    /** 切块（末块较短） */
    fun chunkBytes(bytes: ByteArray): List<ByteArray> {
        val total = chunkCount(bytes)
        return (0 until total).map { i ->
            val from = i * CHUNK_SIZE
            val to = minOf(from + CHUNK_SIZE, bytes.size)
            bytes.copyOfRange(from, to)
        }
    }
}

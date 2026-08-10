package com.juanlink.core.qr

/**
 * 跨平台二维码位图（普通数据类，纯 Kotlin）。
 * 像素为 ARGB 8888（Int），各平台渲染层自行转换为 Image/Bitmap。
 */
class QrBitmap(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

/** 将编码矩阵渲染为带静区的位图（纯 Kotlin，全平台一致） */
object QrRenderer {

    private const val DARK = 0xFF000000.toInt()
    private const val LIGHT = 0xFFFFFFFF.toInt()

    /**
     * @param scale 每模块像素数（放大率，保证可扫码）
     * @param quiet 静区模块数（默认 4，规范要求 ≥4）
     */
    fun render(matrix: QrEncoder.QrMatrix, scale: Int = 4, quiet: Int = 4): QrBitmap {
        require(scale >= 1) { "scale 必须 >= 1" }
        val n = matrix.size
        val out = n + quiet * 2
        val size = out * scale
        val pixels = IntArray(size * size)

        for (y in 0 until size) {
            val my = y / scale - quiet
            for (x in 0 until size) {
                val mx = x / scale - quiet
                val dark = mx in 0 until n && my in 0 until n && matrix[mx, my]
                pixels[y * size + x] = if (dark) DARK else LIGHT
            }
        }
        return QrBitmap(size, size, pixels)
    }
}

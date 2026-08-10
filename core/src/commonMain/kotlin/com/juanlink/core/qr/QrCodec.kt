package com.juanlink.core.qr

/**
 * 二维码编解码抽象。
 *
 * - [encode]：纯 Kotlin（QrEncoder + QrRenderer），全平台一致
 * - [decode]：平台实现（JVM 用 ZXing 解析图片；移动端用摄像头/相册帧）
 */
expect object QrCodec {

    /** 编码为位图 */
    fun encode(text: String, scale: Int = 4, quiet: Int = 4): QrBitmap

    /** 从位图解码，失败返回 null */
    fun decode(bitmap: QrBitmap): String?
}

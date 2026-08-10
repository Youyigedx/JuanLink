package com.juanlink.core.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Android 平台实际：编码用共享 QrRenderer（纯 Kotlin），解码用 ZXing（与 JVM 相同的纯 Java 库） */
actual object QrCodec {

    actual fun encode(text: String, scale: Int, quiet: Int): QrBitmap {
        val matrix = QrEncoder.encode(text)
        return QrRenderer.render(matrix, scale, quiet)
    }

    actual fun decode(bitmap: QrBitmap): String? = runCatching {
        val source: LuminanceSource = RGBLuminanceSource(bitmap.width, bitmap.height, bitmap.pixels)
        val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
        result.text
    }.getOrNull()
}

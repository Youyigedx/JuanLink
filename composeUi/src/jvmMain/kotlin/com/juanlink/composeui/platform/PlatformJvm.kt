@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTextApi::class)

package com.juanlink.composeui.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.SystemFont
import com.juanlink.core.qr.QrBitmap
import com.juanlink.core.transport.CompositeTransport
import com.juanlink.core.transport.Transport
import com.juanlink.core.transport.TurnTransport
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.text.SimpleDateFormat
import java.util.Date

/** 简体中文宋体（SimSun/新宋体）。需 JVM 参数 --add-opens java.desktop/sun.font=ALL-UNNAMED */
actual val SongFontFamily: FontFamily = FontFamily(SystemFont("SimSun"))

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? = runCatching {
    val image = Image.makeFromEncoded(bytes) ?: return null
    image.toComposeImageBitmap()
}.getOrNull()

actual fun decodeImageDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
    val image = Image.makeFromEncoded(bytes) ?: return null
    image.width to image.height
}.getOrNull()

/** 将跨平台 QrBitmap（ARGB IntArray）转为 Compose ImageBitmap（BGRA premul） */
actual fun QrBitmap.toComposeImage(): ImageBitmap {
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        val pr = (r * a) / 255
        val pg = (g * a) / 255
        val pb = (b * a) / 255
        bytes[i * 4] = pb.toByte()
        bytes[i * 4 + 1] = pg.toByte()
        bytes[i * 4 + 2] = pr.toByte()
        bytes[i * 4 + 3] = a.toByte()
    }
    val info = ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL)
    val image = Image.makeRaster(info, bytes, width * 4)
    return image.toComposeImageBitmap()
}

private val timeFormat = SimpleDateFormat("HH:mm:ss")

actual fun formatTimestamp(ms: Long): String = timeFormat.format(Date(ms))

actual fun createPlatformTransport(): Transport = CompositeTransport(relay = TurnTransport())

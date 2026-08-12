package com.juanlink.composeui.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import com.juanlink.core.qr.QrBitmap
import com.juanlink.core.transport.CompositeTransport
import com.juanlink.core.transport.Transport
import com.juanlink.core.transport.TurnTransport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Android 移动端：回退到系统 serif 字体族 */
actual val SongFontFamily: FontFamily = FontFamily.Serif

actual fun decodeImageBytes(bytes: ByteArray): ImageBitmap? = runCatching {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

actual fun decodeImageDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) null else opts.outWidth to opts.outHeight
}.getOrNull()

/** QrBitmap 为 ARGB IntArray，Bitmap.setPixels 同格式，直接写入 */
actual fun QrBitmap.toComposeImage(): ImageBitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bmp.setPixels(pixels, 0, width, 0, 0, width, height)
    return bmp.asImageBitmap()
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

actual fun formatTimestamp(ms: Long): String = timeFormat.format(Date(ms))

actual fun createPlatformTransport(): Transport = CompositeTransport(relay = TurnTransport())

@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTextApi::class)

package com.juanlink.composeui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.IntSize
import com.juanlink.core.model.Viewport
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

/**
 * 桌面：Ctrl + 鼠标滚轮以光标为中心缩放视口。
 * 仅 Ctrl 按住时缩放，普通滚轮不触发，避免与面板/滚动容器冲突。
 */
@Composable
actual fun Modifier.wheelZoom(
    viewport: MutableState<Viewport>,
    viewportSizeProvider: () -> IntSize,
): Modifier = onPointerEvent(PointerEventType.Scroll) { event ->
    val size = viewportSizeProvider()
    val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
    if (delta != 0f && event.keyboardModifiers.isCtrlPressed && size.width > 0 && size.height > 0) {
        val vp = viewport.value
        val cursor = event.changes.first().position
        val wx = vp.screenToWorldX(cursor.x, size.width.toFloat())
        val wy = vp.screenToWorldY(cursor.y, size.height.toFloat())
        // 缩放灵敏度：归一化到"格"（鼠标一格 scrollDelta.y ≈ ±40~53）→ 每格约 ±33%。
        // 触控板是像素级增量（绝对值极小），线性系数再大也几乎无感；
        // 故给每个事件一个最小生效幅度（半格）→ 任何一次滚动事件都至少有 ≥12% 的可感知缩放。
        val dir = if (delta > 0) 1f else -1f
        val mag = kotlin.math.abs(delta)
        val units = (mag / 40f).coerceAtLeast(0.5f) * dir
        val factor = (1f + units * 0.25f).coerceAtLeast(0.35f)
        val newScale = (vp.scale * factor).coerceIn(0.05f, 60f)
        val cx = wx - (cursor.x - size.width / 2f) / newScale
        val cy = wy - (cursor.y - size.height / 2f) / newScale
        viewport.value = Viewport(cx, cy, newScale)
    }
}

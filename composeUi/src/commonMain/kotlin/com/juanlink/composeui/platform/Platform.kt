package com.juanlink.composeui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import com.juanlink.core.model.Viewport
import com.juanlink.core.qr.QrBitmap
import com.juanlink.core.transport.Transport

/**
 * UI 层平台抽象（expect/actual）。
 * 桌面端用 Skia / SystemFont / JVM IO；安卓端用 BitmapFactory / Serif / Context 文件。
 */

/** 宋体字体族（桌面用系统 SimSun，移动端回退 Serif） */
expect val SongFontFamily: FontFamily

/** 解码 PNG/JPEG 字节为 Compose ImageBitmap（画布图片渲染） */
expect fun decodeImageBytes(bytes: ByteArray): ImageBitmap?

/** 只读图片尺寸（宽, 高），导入时用于定位到画布中心 */
expect fun decodeImageDimensions(bytes: ByteArray): Pair<Int, Int>?

/** 将跨平台 QrBitmap（ARGB IntArray）转为 Compose ImageBitmap */
expect fun QrBitmap.toComposeImage(): ImageBitmap

/** 格式化毫秒时间戳为 HH:mm:ss */
expect fun formatTimestamp(ms: Long): String

/** 平台默认传输（桌面/安卓均为 TcpTransport） */
expect fun createPlatformTransport(): Transport

/**
 * 平台鼠标滚轮缩放手势（以光标为中心缩放视口）。
 * 桌面：监听滚轮 Scroll 事件；移动端无鼠标滚轮，返回原样（触摸缩放暂未接入）。
 */
@Composable
expect fun Modifier.wheelZoom(
    viewport: MutableState<Viewport>,
    viewportSizeProvider: () -> IntSize,
): Modifier

/**
 * 快照持久化接口。
 * 桌面端读写 user.home/.juanlink/snapshots.json；安卓端读写 filesDir。
 */
interface SnapshotIo {
    fun load(): String?
    fun save(text: String)
}

/** 内存快照存储（无持久化能力的平台回退） */
class InMemorySnapshotIo : SnapshotIo {
    private var data: String? = null
    override fun load(): String? = data
    override fun save(text: String) {
        data = text
    }
}

/**
 * TURN 服务器配置持久化接口（用户自定义服务器列表，JSON 文本）。
 * 桌面端读写 user.home/.juanlink/turn.json；安卓端读写 SharedPreferences。
 */
interface TurnConfigIo {
    fun load(): String?
    fun save(text: String)
}

/** 内存 TURN 配置存储（无持久化能力平台回退） */
class InMemoryTurnConfigIo : TurnConfigIo {
    private var data: String? = null
    override fun load(): String? = data
    override fun save(text: String) {
        data = text
    }
}

package com.juanlink.composeui.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import com.juanlink.core.qr.QrBitmap
import com.juanlink.core.transport.Transport
import kotlinx.serialization.json.Json

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

/**
 * 会合服务配置持久化接口（短配对码接入，JSON 文本）。
 * 桌面端读写 user.home/.juanlink/rendezvous.json；安卓端读写 SharedPreferences。
 *
 * ⚠️ 该配置指向的服务器会获得「公钥分发权」——请只填自己掌控的服务器。
 * 详见 `docs/protocol.md` §10。
 */
interface RendezvousConfigIo {
    fun load(): String?
    fun save(text: String)
}

/** 内存会合配置存储（无持久化能力平台回退 / 测试用） */
class InMemoryRendezvousConfigIo : RendezvousConfigIo {
    private var data: String? = null
    override fun load(): String? = data
    override fun save(text: String) {
        data = text
    }
}

/** 会合配置的 JSON 编解码（复用 [RendezvousConfigIo] 的纯文本通道） */
object RendezvousConfigJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(config: com.juanlink.core.rendezvous.RendezvousConfig): String =
        json.encodeToString(com.juanlink.core.rendezvous.RendezvousConfig.serializer(), config)

    /** 解析失败返回 null（调用方回退到默认「未配置」） */
    fun decode(text: String): com.juanlink.core.rendezvous.RendezvousConfig? =
        runCatching {
            json.decodeFromString(com.juanlink.core.rendezvous.RendezvousConfig.serializer(), text)
        }.getOrNull()
}

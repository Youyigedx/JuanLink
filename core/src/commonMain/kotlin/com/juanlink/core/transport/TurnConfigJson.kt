package com.juanlink.core.transport

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * TURN 服务器列表 JSON 编解码（持久化）。
 * 桌面端读写 .juanlink/turn.json，安卓端读写 SharedPreferences；格式均为 JSON 数组。
 * 沿用 SnapshotJson 的宽松策略（忽略未知键），保证旧版配置可平滑升级。
 */
object TurnConfigJson {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    fun encodeList(servers: List<TurnServer>): String =
        json.encodeToString(ListSerializer(TurnServer.serializer()), servers)

    /** 解码失败返回空列表（配置损坏不致命，仅回退默认公共列表） */
    fun decodeList(text: String): List<TurnServer> =
        runCatching { json.decodeFromString(ListSerializer(TurnServer.serializer()), text) }.getOrElse { emptyList() }
}

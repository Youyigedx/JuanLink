package com.juanlink.composeui.draw

import com.juanlink.core.util.nowEpochMillis
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.PayLoad
import io.ak1.drawbox.domain.model.toColor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 画布快照：时间戳 + DrawBox 画布序列化 JSON（元素 + 背景色）。
 *
 * 旧自研快照（CanvasSnapshot）已随第 1 步删除，本模型以 DrawBox 的
 * [DrawingSerializer] JSON 为唯一持久化格式；旧的 snapshots.json 结构
 * 不兼容，加载时解析失败会被忽略（对应「放弃旧快照」决策）。
 *
 * 持久化复用平台 [SnapshotIo] 文本通道（纯字符串）；
 * 缩略图渲染经 [payload] 惰性解析，避免每次重组反序列化。
 */
@Serializable
data class DrawingSnapshot(
    val timestamp: Long,
    val json: String,
) {
    /** 惰性解析为可渲染 PayLoad（缩略图用）；损坏数据回退空画布 */
    val payload: PayLoad by lazy {
        runCatching { DrawingSerializer.deserialize(json) }
            .getOrElse { PayLoad(bgColor = "#FFFFFFFF".toColor(), elements = emptyList()) }
    }

    val elementCount: Int get() = payload.elements.size
}

/** 快照存储：环形保留最近 N 张 */
class DrawingSnapshotStore(private val capacity: Int = 50) {
    private val list = mutableListOf<DrawingSnapshot>()
    val all: List<DrawingSnapshot> get() = list.toList()

    fun capture(payLoad: PayLoad): DrawingSnapshot {
        val snap = DrawingSnapshot(nowEpochMillis(), DrawingSerializer.serialize(payLoad))
        list.add(snap)
        if (list.size > capacity) list.removeAt(0)
        return snap
    }

    fun loadAll(snapshots: List<DrawingSnapshot>) {
        list.clear()
        list.addAll(snapshots.take(capacity))
    }
}

/** 快照列表 JSON 编解码（SnapshotIo 文本通道） */
object DrawingSnapshotJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeList(list: List<DrawingSnapshot>): String = json.encodeToString(list)

    fun decodeList(text: String): List<DrawingSnapshot> =
        runCatching { json.decodeFromString<List<DrawingSnapshot>>(text) }.getOrDefault(emptyList())
}

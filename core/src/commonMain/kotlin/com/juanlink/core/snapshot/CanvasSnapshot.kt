package com.juanlink.core.snapshot

import com.juanlink.core.canvas.CanvasDocument
import com.juanlink.core.model.ImageObject
import com.juanlink.core.model.Layer
import com.juanlink.core.model.Stroke
import com.juanlink.core.util.nowEpochMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 画布快照：某一时刻的文档状态（用于历史记录与查看）。
 * 图片仅保存元数据（二进制过大），预览时按占位处理。
 */
@Serializable
data class CanvasSnapshot(
    val timestamp: Long,
    val strokeCount: Int,
    val imageCount: Int,
    val layerCount: Int,
    val strokes: List<Stroke>,
    val layers: List<Layer>,
    val imageMeta: List<ImageObject>,
)

/** 快照 JSON 编解码（持久化） */
object SnapshotJson {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = false }

    fun encodeList(snapshots: List<CanvasSnapshot>): String =
        json.encodeToString(ListSerializer(CanvasSnapshot.serializer()), snapshots)

    fun decodeList(text: String): List<CanvasSnapshot> =
        runCatching { json.decodeFromString(ListSerializer(CanvasSnapshot.serializer()), text) }.getOrElse { emptyList() }
}

/**
 * 快照历史存储：环形缓冲（保留最近 [capacity] 张），支持捕获与持久化。
 */
class SnapshotStore(private val capacity: Int = 50) {

    private val snapshots = ArrayDeque<CanvasSnapshot>()

    val all: List<CanvasSnapshot> get() = snapshots.toList()

    val size: Int get() = snapshots.size

    /** 捕获当前文档状态 */
    fun capture(document: CanvasDocument): CanvasSnapshot {
        val snap = CanvasSnapshot(
            timestamp = nowEpochMillis(),
            strokeCount = document.allStrokes().size,
            imageCount = document.allImages().size,
            layerCount = document.layers.size,
            strokes = document.allStrokes(),
            layers = document.layers.toList(),
            imageMeta = document.allImages(),
        )
        snapshots.addLast(snap)
        while (snapshots.size > capacity) snapshots.removeFirst()
        return snap
    }

    fun clear() {
        snapshots.clear()
    }

    fun loadAll(list: List<CanvasSnapshot>) {
        snapshots.clear()
        snapshots.addAll(list.takeLast(capacity))
    }
}

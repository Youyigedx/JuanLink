package com.juanlink.core.draw

import com.juanlink.core.protocol.OpTransport

/**
 * 测试用内存文档：模拟 DrawBoxHost 的最小 SyncDocument 契约。
 * 元素以 LinkedHashMap 保存，upsert 幂等替换、remove 幂等删除。
 */
class MemDoc : SyncDocument<DrawOp> {
    val elements = LinkedHashMap<String, WireElement>()

    override fun canApply(op: DrawOp): Boolean = true

    override fun captureInverse(op: DrawOp): DrawOp? = when (op) {
        is DrawOp.ElementUpsert -> elements[op.element.id]?.let { DrawOp.ElementUpsert(it) }
        is DrawOp.ElementRemove -> elements[op.elementId]?.let { DrawOp.ElementUpsert(it) }
        else -> null
    }

    override fun apply(op: DrawOp): Boolean = when (op) {
        is DrawOp.ElementUpsert -> {
            val old = elements[op.element.id]
            elements[op.element.id] = op.element
            old != op.element
        }
        is DrawOp.ElementRemove -> elements.remove(op.elementId) != null
        else -> false
    }

    override fun opElementKey(op: DrawOp): String? = when (op) {
        is DrawOp.ElementUpsert -> op.element.id
        is DrawOp.ElementRemove -> op.elementId
        else -> null
    }
}

/** 便捷构造泛型引擎（测试共用） */
fun memEngine(
    doc: MemDoc,
    peerId: String,
    transport: OpTransport,
): OpSyncEngine<DrawOp> = OpSyncEngine(
    doc = doc,
    encodeOp = { DrawOpCodec.encode(it) },
    decodeOp = { DrawOpCodec.decode(it) },
    opTypeOf = { drawOpTypeCode(it) },
    peerId = peerId,
    transport = transport,
)

/** 便捷构造 Path 元素（modifiedAt 作区分版本用） */
fun wireElement(id: String, version: Int = 1): WireElement = WireElement(
    id = id,
    type = "Path",
    zIndex = 0,
    points = emptyList(),
    strokeColor = "#000000FF",
    strokeWidth = 3f,
    modifiedAt = version.toLong(),
)

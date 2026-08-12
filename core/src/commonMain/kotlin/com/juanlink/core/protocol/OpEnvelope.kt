package com.juanlink.core.protocol

import kotlinx.serialization.Serializable

/**
 * 操作类型（稳定编码，协议兼容性依赖此值不变）。
 * 操作级同步：只同步「一笔、一图、一图层」的增量，绝不传输整画布。
 */
enum class OpType(val code: Int) {
    StrokeAdd(1),
    StrokeAppend(2),
    StrokeFinish(3),
    StrokeRemove(4),
    StrokeClear(5),

    LayerAdd(10),
    LayerRemove(11),
    LayerReorder(12),
    LayerUpdate(13),

    ImageAdd(20),
    ImageChunk(21),
    ImageReady(22),
    ImageTransform(23),
    ImageRemove(24),

    CanvasClear(30),
    CanvasMeta(31),
    Undo(40),
    Redo(41),

    ElementUpsert(50),
    ElementRemove(51);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): OpType? = byCode[code]
    }
}

/**
 * 操作标识：Lamport 逻辑时钟全序，双端排序一致，不依赖系统时钟。
 */
@Serializable
data class OpId(
    val lamport: Long,
    val peerId: String,
) : Comparable<OpId> {
    override fun compareTo(other: OpId): Int {
        val c = lamport.compareTo(other.lamport)
        return if (c != 0) c else peerId.compareTo(other.peerId)
    }
}

/**
 * 操作信封：序列化后的业务操作（CanvasOp）经加密通道传输。
 * [payload] 为 CanvasOp 的 CBOR 字节。
 */
@Serializable
data class OpEnvelope(
    val opId: OpId,
    val peerId: String,
    val opType: Int,
    val payload: ByteArray,
    /** 发送方出站操作序号（ack/补同步定位用，单调递增） */
    val seq: Long,
    val timestamp: Long,
)

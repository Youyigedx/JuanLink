package com.juanlink.core.protocol

import kotlinx.serialization.ExperimentalSerializationApi

/**
 * 操作同步所需的传输通道（由 SessionManager 实现）。
 * 使 OpSyncEngine 不直接依赖传输层实现，便于单测与替换。
 */
interface OpTransport {

    /** 发送一个操作信封（加密通道） */
    fun sendOp(envelope: OpEnvelope): Boolean

    /** 向对端确认已应用到的连续序列号 */
    fun sendAck(peerId: String, lastAppliedSeq: Long, requestResync: Boolean): Boolean

    /** 请求对端从 sinceSeq 之后补同步 */
    fun sendSyncRequest(sinceSeq: Long): Boolean

    /** 发送增量补同步数据 */
    fun sendResync(ops: List<ByteArray>, lastSeq: Long): Boolean

    /** 通知对端补同步结束 */
    fun sendResyncDone(lastSeq: Long): Boolean
}

/** OpEnvelope 编解码（CBOR） */
@OptIn(ExperimentalSerializationApi::class)
object EnvelopeCodec {

    private val cbor = kotlinx.serialization.cbor.Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(e: OpEnvelope): ByteArray = cbor.encodeToByteArray(OpEnvelope.serializer(), e)

    fun decode(bytes: ByteArray): OpEnvelope? =
        runCatching { cbor.decodeFromByteArray(OpEnvelope.serializer(), bytes) }.getOrNull()
}

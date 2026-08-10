package com.juanlink.core.protocol

import kotlinx.serialization.Serializable

/**
 * 线协议消息集（控制面）。
 *
 * 帧格式：`[4B BE 长度][1B 类型][CBOR 负载]`
 *
 * 握手消息（Hello / HandshakeResponse）明文一次；
 * 建立密钥后的全部业务数据走 EncryptedFrame（AES-256-GCM 保护）。
 */
sealed interface WireMessage {
    /** 连接内全局递增消息号，用于去重与日志追踪 */
    val messageId: Long
}

/** 握手请求（明文，发起方 → 接收方） */
@Serializable
data class Hello(
    override val messageId: Long,
    val protocolVersion: Int,
    val deviceId: String,
    val sessionId: String,
    /** X25519 公钥（32B 原始字节） */
    val publicKey: ByteArray,
    val nonce: ByteArray,
    val role: String,
) : WireMessage

/** 握手响应（明文，含双向认证 MAC） */
@Serializable
data class HandshakeResponse(
    override val messageId: Long,
    val deviceId: String,
    val sessionId: String,
    val publicKey: ByteArray,
    /** HMAC(macKey, pkInitiator || pkResponder || sessionId || nonce) */
    val mac: ByteArray,
) : WireMessage

/** 加密业务帧：载荷为 AES-256-GCM 密文 + 包级 HMAC */
@Serializable
data class EncryptedFrame(
    override val messageId: Long,
    val seq: Long,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val mac: ByteArray,
) : WireMessage

@Serializable
data class Ping(
    override val messageId: Long,
    val timestamp: Long,
    val seq: Int,
) : WireMessage

@Serializable
data class Pong(
    override val messageId: Long,
    val timestamp: Long,
    val seq: Int,
) : WireMessage

/** 操作确认：lastAppliedSeq 为对端已应用到的连续操作序列号 */
@Serializable
data class Ack(
    override val messageId: Long,
    val lastAppliedSeq: Long,
    val requestResync: Boolean = false,
) : WireMessage

/** 质量探测 */
@Serializable
data class QualityProbe(
    override val messageId: Long,
    val seq: Int,
    val sendTimestamp: Long,
    val payloadSize: Int,
) : WireMessage

@Serializable
data class Heartbeat(
    override val messageId: Long,
    val timestamp: Long,
) : WireMessage

/** 请求从 sinceSeq 之后的增量补同步 */
@Serializable
data class SyncRequest(
    override val messageId: Long,
    val sinceSeq: Long,
) : WireMessage

/** 增量补同步数据：ops 为序列化后的 OpEnvelope 字节数组 */
@Serializable
data class Resync(
    override val messageId: Long,
    val ops: List<ByteArray>,
    val lastSeq: Long,
) : WireMessage

@Serializable
data class ResyncDone(
    override val messageId: Long,
    val lastSeq: Long,
) : WireMessage

@Serializable
data class Bye(
    override val messageId: Long,
    val reason: Int,
) : WireMessage

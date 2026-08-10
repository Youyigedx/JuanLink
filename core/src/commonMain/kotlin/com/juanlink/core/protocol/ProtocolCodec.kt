package com.juanlink.core.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * 线协议编解码。
 *
 * 帧格式：`[4B BE 长度][1B 类型][CBOR 负载]`，
 * 长度字段计 payload（类型字节 + CBOR 数据）。
 */
@OptIn(ExperimentalSerializationApi::class)
object ProtocolCodec {

    // 帧类型字节（与 docs/protocol.md 保持一致）
    const val T_HELLO = 0x01
    const val T_HANDSHAKE_RESPONSE = 0x02
    const val T_ENCRYPTED_FRAME = 0x10
    const val T_PING = 0x21
    const val T_PONG = 0x22
    const val T_ACK = 0x23
    const val T_QUALITY_PROBE = 0x24
    const val T_HEARTBEAT = 0x25
    const val T_SYNC_REQUEST = 0x26
    const val T_RESYNC = 0x27
    const val T_RESYNC_DONE = 0x28
    const val T_BYE = 0x3F

    private val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** 将消息编码为完整帧 */
    fun encode(msg: WireMessage): ByteArray {
        val payload = encodePayload(msg)
        val frame = ByteArray(4 + payload.size)
        writeUInt32(frame, 0, payload.size)
        payload.copyInto(frame, 4)
        return frame
    }

    /** 从完整帧字节解码（帧内不得含粘包） */
    fun decode(frame: ByteArray): WireMessage? {
        if (frame.size < 4) return null
        val len = readUInt32(frame, 0)
        if (frame.size != 4 + len) return null
        return decodePayload(frame, 4, len)
    }

    /** 从帧的 payload 段解码（frame 指针已越过 4B 长度头） */
    fun decodePayload(frame: ByteArray, offset: Int, len: Int): WireMessage? {
        if (len < 1) return null
        val type = frame[offset].toInt() and 0xFF
        val body = frame.copyOfRange(offset + 1, offset + len)
        return runCatching { decodeTyped(type, body) }.getOrNull()
    }

    private fun encodePayload(msg: WireMessage): ByteArray {
        val type: Int
        val body: ByteArray
        when (msg) {
            is Hello -> { type = T_HELLO; body = cbor.encodeToByteArray(Hello.serializer(), msg) }
            is HandshakeResponse -> { type = T_HANDSHAKE_RESPONSE; body = cbor.encodeToByteArray(HandshakeResponse.serializer(), msg) }
            is EncryptedFrame -> { type = T_ENCRYPTED_FRAME; body = cbor.encodeToByteArray(EncryptedFrame.serializer(), msg) }
            is Ping -> { type = T_PING; body = cbor.encodeToByteArray(Ping.serializer(), msg) }
            is Pong -> { type = T_PONG; body = cbor.encodeToByteArray(Pong.serializer(), msg) }
            is Ack -> { type = T_ACK; body = cbor.encodeToByteArray(Ack.serializer(), msg) }
            is QualityProbe -> { type = T_QUALITY_PROBE; body = cbor.encodeToByteArray(QualityProbe.serializer(), msg) }
            is Heartbeat -> { type = T_HEARTBEAT; body = cbor.encodeToByteArray(Heartbeat.serializer(), msg) }
            is SyncRequest -> { type = T_SYNC_REQUEST; body = cbor.encodeToByteArray(SyncRequest.serializer(), msg) }
            is Resync -> { type = T_RESYNC; body = cbor.encodeToByteArray(Resync.serializer(), msg) }
            is ResyncDone -> { type = T_RESYNC_DONE; body = cbor.encodeToByteArray(ResyncDone.serializer(), msg) }
            is Bye -> { type = T_BYE; body = cbor.encodeToByteArray(Bye.serializer(), msg) }
        }
        val out = ByteArray(1 + body.size)
        out[0] = type.toByte()
        body.copyInto(out, 1)
        return out
    }

    private fun decodeTyped(type: Int, body: ByteArray): WireMessage = when (type) {
        T_HELLO -> cbor.decodeFromByteArray(Hello.serializer(), body)
        T_HANDSHAKE_RESPONSE -> cbor.decodeFromByteArray(HandshakeResponse.serializer(), body)
        T_ENCRYPTED_FRAME -> cbor.decodeFromByteArray(EncryptedFrame.serializer(), body)
        T_PING -> cbor.decodeFromByteArray(Ping.serializer(), body)
        T_PONG -> cbor.decodeFromByteArray(Pong.serializer(), body)
        T_ACK -> cbor.decodeFromByteArray(Ack.serializer(), body)
        T_QUALITY_PROBE -> cbor.decodeFromByteArray(QualityProbe.serializer(), body)
        T_HEARTBEAT -> cbor.decodeFromByteArray(Heartbeat.serializer(), body)
        T_SYNC_REQUEST -> cbor.decodeFromByteArray(SyncRequest.serializer(), body)
        T_RESYNC -> cbor.decodeFromByteArray(Resync.serializer(), body)
        T_RESYNC_DONE -> cbor.decodeFromByteArray(ResyncDone.serializer(), body)
        T_BYE -> cbor.decodeFromByteArray(Bye.serializer(), body)
        else -> throw IllegalArgumentException("未知帧类型: $type")
    }

    internal fun writeUInt32(buf: ByteArray, pos: Int, value: Int) {
        buf[pos] = (value ushr 24).toByte()
        buf[pos + 1] = (value ushr 16).toByte()
        buf[pos + 2] = (value ushr 8).toByte()
        buf[pos + 3] = value.toByte()
    }

    internal fun readUInt32(buf: ByteArray, pos: Int): Int =
        ((buf[pos].toInt() and 0xFF) shl 24) or
            ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or
            (buf[pos + 3].toInt() and 0xFF)
}

/**
 * 流式帧解码器：TCP 流任意分块，按 4B 长度头切帧。
 * 单帧超过 [maxFrameSize] 或解码失败即重置（协议异常防护）。
 */
class FrameDecoder(private val maxFrameSize: Int = 16 * 1024 * 1024) {

    private var buffer = ByteArray(8192)
    private var length = 0
    private val out = ArrayList<WireMessage>(4)

    /** 注入一段流字节，返回其中完整的帧；不足一帧返回空列表 */
    fun push(data: ByteArray, offset: Int = 0, count: Int = data.size): List<WireMessage> {
        out.clear()
        ensureCapacity(length + count)
        data.copyInto(buffer, length, offset, offset + count)
        length += count

        var pos = 0
        var valid = true
        while (valid) {
            if (length - pos < 4) break
            val frameLen = ProtocolCodec.readUInt32(buffer, pos)
            if (frameLen > maxFrameSize || frameLen < 1) {
                valid = false
                break
            }
            if (length - pos < 4 + frameLen) break
            val msg = ProtocolCodec.decodePayload(buffer, pos + 4, frameLen)
            if (msg == null) {
                valid = false
                break
            }
            out.add(msg)
            pos += 4 + frameLen
        }

        if (pos > 0) {
            val remaining = length - pos
            if (remaining > 0) buffer.copyInto(buffer, 0, pos, length)
            length = remaining
        }
        if (!valid) length = 0
        // 返回防御性拷贝：调用方持有的引用不应被后续 push 改写
        return out.toList()
    }

    fun reset() {
        length = 0
    }

    private fun ensureCapacity(min: Int) {
        if (buffer.size >= min) return
        var newCap = buffer.size * 2
        while (newCap < min) newCap *= 2
        val nb = ByteArray(newCap)
        buffer.copyInto(nb)
        buffer = nb
    }
}

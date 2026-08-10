package com.juanlink.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolCodecTest {

    @Test
    fun helloRoundtrip() {
        val msg = Hello(42, 1, "dev-A", "sess-1", byteArrayOf(1, 2, 3), byteArrayOf(9), "responder")
        val decoded = ProtocolCodec.decode(ProtocolCodec.encode(msg))
        assertWireEquals(msg, decoded)
    }

    @Test
    fun handshakeResponseRoundtrip() {
        val msg = HandshakeResponse(7, "dev-B", "sess-1", byteArrayOf(4, 5), byteArrayOf(6, 7, 8, 9))
        assertWireEquals(msg, ProtocolCodec.decode(ProtocolCodec.encode(msg)))
    }

    @Test
    fun encryptedFrameRoundtrip() {
        val msg = EncryptedFrame(3, 100L, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), b(0xDE, 0xAD), b(0xBE, 0xEF))
        assertWireEquals(msg, ProtocolCodec.decode(ProtocolCodec.encode(msg)))
    }

    @Test
    fun pingPongRoundtrip() {
        assertWireEquals(Ping(1, 123456L, 5), ProtocolCodec.decode(ProtocolCodec.encode(Ping(1, 123456L, 5))))
        assertWireEquals(Pong(2, 123456L, 5), ProtocolCodec.decode(ProtocolCodec.encode(Pong(2, 123456L, 5))))
    }

    @Test
    fun resyncWithOpsRoundtrip() {
        val msg = Resync(9, listOf(byteArrayOf(1), byteArrayOf(2, 3)), 88L)
        val decoded = ProtocolCodec.decode(ProtocolCodec.encode(msg))
        assertWireEquals(msg, decoded)
    }

    @Test
    fun garbageFrameDecodesToNull() {
        val frame = byteArrayOf(0, 0, 0, 5, 0x7F, 1, 2, 3, 4) // 类型 0x7F 未知
        assertNull(ProtocolCodec.decode(frame))
    }

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private fun assertWireEquals(expected: WireMessage, actual: WireMessage?) {
        if (actual == null) {
            throw AssertionError("解码结果为 null")
        }
        assertEquals(expected::class, actual::class)
        when (expected) {
            is Hello -> {
                val a = actual as Hello
                assertEquals(expected.messageId, a.messageId)
                assertEquals(expected.protocolVersion, a.protocolVersion)
                assertEquals(expected.deviceId, a.deviceId)
                assertEquals(expected.sessionId, a.sessionId)
                assertEquals(expected.role, a.role)
                kotlin.test.assertContentEquals(expected.publicKey, a.publicKey)
                kotlin.test.assertContentEquals(expected.nonce, a.nonce)
            }
            is HandshakeResponse -> {
                val a = actual as HandshakeResponse
                assertEquals(expected.deviceId, a.deviceId)
                assertEquals(expected.sessionId, a.sessionId)
                kotlin.test.assertContentEquals(expected.publicKey, a.publicKey)
                kotlin.test.assertContentEquals(expected.mac, a.mac)
            }
            is EncryptedFrame -> {
                val a = actual as EncryptedFrame
                assertEquals(expected.seq, a.seq)
                kotlin.test.assertContentEquals(expected.iv, a.iv)
                kotlin.test.assertContentEquals(expected.ciphertext, a.ciphertext)
                kotlin.test.assertContentEquals(expected.mac, a.mac)
            }
            is Ping -> assertEquals(expected, actual)
            is Pong -> assertEquals(expected, actual)
            is Ack -> assertEquals(expected, actual)
            is QualityProbe -> assertEquals(expected, actual)
            is Heartbeat -> assertEquals(expected, actual)
            is SyncRequest -> assertEquals(expected, actual)
            is Resync -> {
                val a = actual as Resync
                assertEquals(expected.lastSeq, a.lastSeq)
                assertEquals(expected.ops.size, a.ops.size)
                for (i in expected.ops.indices) kotlin.test.assertContentEquals(expected.ops[i], a.ops[i])
            }
            is ResyncDone -> assertEquals(expected, actual)
            is Bye -> assertEquals(expected, actual)
        }
    }
}

class FrameDecoderTest {

    @Test
    fun singleFrameWholeChunk() {
        val decoder = FrameDecoder()
        val frames = decoder.push(ProtocolCodec.encode(Ping(1, 1, 1)))
        assertEquals(1, frames.size)
    }

    @Test
    fun singleFrameSplitAcrossChunks() {
        val decoder = FrameDecoder()
        val bytes = ProtocolCodec.encode(Pong(2, 2, 2))
        val first = bytes.copyOfRange(0, 3)
        val rest = bytes.copyOfRange(3, bytes.size)
        assertEquals(0, decoder.push(first).size)
        assertEquals(0, decoder.push(byteArrayOf()).size)
        val frames = decoder.push(rest)
        assertEquals(1, frames.size)
        assertEquals(Pong::class, frames[0]::class)
    }

    @Test
    fun multipleFramesInOneChunk() {
        val decoder = FrameDecoder()
        val a = ProtocolCodec.encode(Ping(1, 1, 1))
        val b = ProtocolCodec.encode(Ack(2, 99, false))
        val c = ProtocolCodec.encode(Heartbeat(3, 5))
        val merged = a + b + c
        val frames = decoder.push(merged)
        assertEquals(3, frames.size)
    }

    @Test
    fun framesArrivingInterleaved() {
        val decoder = FrameDecoder()
        val a = ProtocolCodec.encode(Ping(1, 1, 1))
        val b = ProtocolCodec.encode(Pong(2, 2, 2))
        // 交错推送
        val out1 = decoder.push(a.copyOfRange(0, 2))
        val out2 = decoder.push(a.copyOfRange(2, a.size) + b.copyOfRange(0, 1))
        val out3 = decoder.push(b.copyOfRange(1, b.size))
        assertEquals(0, out1.size)
        assertEquals(1, out2.size)
        assertEquals(1, out3.size)
    }

    @Test
    fun oversizedFrameResets() {
        val decoder = FrameDecoder(maxFrameSize = 32)
        // 伪造超大帧头
        val frame = ProtocolCodec.encode(Heartbeat(1, 1))
        val corrupted = frame.copyOf().also { it[0] = 0; it[1] = 0; it[2] = 0x40; it[3] = 0 } // 64KB 帧头
        assertEquals(0, decoder.push(corrupted).size)
        // 解码器应重置，后续正常帧仍可解析
        assertEquals(1, decoder.push(ProtocolCodec.encode(Ping(5, 5, 5))).size)
    }
}

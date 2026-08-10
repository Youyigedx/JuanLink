package com.juanlink.core.transport.udp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 报文头编解码：往返一致、非法 magic/kind/长度拒绝、分片边界 */
class DatagramHeaderTest {

    @Test
    fun dataHeaderRoundTrip() {
        val payload = ByteArray(100) { it.toByte() }
        val pkt = DatagramHeader.encodeData(7, 0, 1, 100, payload)
        assertEquals(DatagramHeader.KIND_DATA, DatagramHeader.kindOf(pkt, pkt.size))
        val h = DatagramHeader.decodeData(pkt, pkt.size)
        assertTrue(h != null)
        h!!
        assertEquals(7, h.udpSeq)
        assertEquals(0, h.fragIndex)
        assertEquals(1, h.totalFrags)
        assertEquals(100, h.frameLen)
        assertContentEquals(payload, h.payload)
    }

    @Test
    fun fragmentBoundary() {
        // payload 恰等于 MAX_PAYLOAD → 单分片；+1 字节 → 两分片
        val exact = ByteArray(DatagramHeader.MAX_PAYLOAD) { 0x11 }
        val pkt1 = DatagramHeader.encodeData(1, 0, 1, exact.size, exact)
        assertEquals(DatagramHeader.HEADER_DATA + exact.size, pkt1.size)
        assertEquals(1, DatagramHeader.decodeData(pkt1, pkt1.size)!!.totalFrags)

        val over = ByteArray(DatagramHeader.MAX_PAYLOAD + 1) { 0x22 }
        val pkt2 = DatagramHeader.encodeData(1, 0, 2, over.size, over)
        assertEquals(2, DatagramHeader.decodeData(pkt2, pkt2.size)!!.totalFrags)
        assertTrue(DatagramHeader.decodeData(pkt2, pkt2.size)!!.payload.size == DatagramHeader.MAX_PAYLOAD + 1)
    }

    @Test
    fun badMagicRejected() {
        val pkt = DatagramHeader.encodeData(1, 0, 1, 4, byteArrayOf(1, 2, 3, 4))
        pkt[0] = 0x00 // 破坏 magic 首字节
        assertEquals(-1, DatagramHeader.kindOf(pkt, pkt.size))
        assertNull(DatagramHeader.decodeData(pkt, pkt.size))
    }

    @Test
    fun badKindRejected() {
        val pkt = DatagramHeader.encodeData(1, 0, 1, 4, byteArrayOf(1, 2, 3, 4))
        pkt[2] = 0x7F.toByte() // 未知 kind
        assertEquals(-1, DatagramHeader.kindOf(pkt, pkt.size))
    }

    @Test
    fun lengthMismatchRejected() {
        val payload = ByteArray(16) { it.toByte() }
        val pkt = DatagramHeader.encodeData(3, 0, 1, 16, payload)
        // 截断一字节 → 头内 payloadLen 与实际不符
        assertNull(DatagramHeader.decodeData(pkt, pkt.size - 1))
        // 额外加一字节 → 同样不符
        val padded = ByteArray(pkt.size + 1) { i -> if (i < pkt.size) pkt[i] else 0 }
        assertNull(DatagramHeader.decodeData(padded, padded.size))
    }

    @Test
    fun illegalFragIndexRejected() {
        val pkt = DatagramHeader.encodeData(1, 5, 3, 10, byteArrayOf(1)) // fragIndex >= totalFrags
        assertNull(DatagramHeader.decodeData(pkt, pkt.size))
    }

    @Test
    fun oversizedFrameRejected() {
        // frameLen 超 MAX_FRAME → 拒绝（抗注入）
        val pkt = DatagramHeader.encodeData(1, 0, 1, DatagramHeader.MAX_FRAME + 1, byteArrayOf(1))
        assertNull(DatagramHeader.decodeData(pkt, pkt.size))
    }

    @Test
    fun ackRoundTrip() {
        val ack = DatagramHeader.encodeAck(42)
        assertEquals(DatagramHeader.KIND_ACK, DatagramHeader.kindOf(ack, ack.size))
        assertEquals(42, DatagramHeader.readAckSeq(ack, ack.size))
        assertNull(DatagramHeader.decodeData(ack, ack.size)) // ACK 不是 DATA
    }

    @Test
    fun pokeShape() {
        val poke = DatagramHeader.encodePoke()
        assertEquals(DatagramHeader.SIZE_POKE, poke.size)
        assertEquals(DatagramHeader.KIND_POKE, DatagramHeader.kindOf(poke, poke.size))
        assertNull(DatagramHeader.decodeData(poke, poke.size))
    }
}

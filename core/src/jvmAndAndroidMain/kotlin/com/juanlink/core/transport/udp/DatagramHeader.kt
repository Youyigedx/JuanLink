package com.juanlink.core.transport.udp

/**
 * UDP 报文头字节布局（全部大端）。
 *
 * - POKE（kind=0x01, 20B）：打洞探测/回包。`[magic 0x4A55][kind][reserved][sessionId 16B]`
 * - DATA（kind=0x02, 头18B + 负载）：`[magic][kind][reserved][udpSeq u32][fragIndex u16][totalFrags u16][frameLen u32][payloadLen u16][payload]`
 * - ACK（kind=0x03, 12B）：`[magic][kind][reserved][cumulativeAckSeq u32][ackBitmap u32(预留恒0)]`
 *
 * 帧字节 = ProtocolCodec.encode(WireMessage) 的完整输出；超过 [MAX_PAYLOAD] 时按分片发送。
 * [MAX_PAYLOAD]=1200 规避 IP 分片（1500 MTU - 20 IP - 8 UDP 留余量）。
 */
object DatagramHeader {

    const val MAGIC: Int = 0x4A55 // "JU"

    const val KIND_POKE = 1
    const val KIND_DATA = 2
    const val KIND_ACK = 3

    /** 单分片负载上限（字节），规避 IP 分片 */
    const val MAX_PAYLOAD = 1200

    /** 单帧上限（重组后字节数）；超限丢弃（抗注入） */
    const val MAX_FRAME = 1024 * 1024 // 1MB

    const val HEADER_DATA = 18
    const val SIZE_POKE = 20
    const val SIZE_ACK = 12

    /** 判定报文类型；非法 magic 或未知 kind 返回 -1 */
    fun kindOf(buf: ByteArray, len: Int): Int {
        if (len < 4) return -1
        val magic = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (magic != MAGIC) return -1
        return when (val kind = buf[2].toInt() and 0xFF) {
            KIND_POKE, KIND_DATA, KIND_ACK -> kind
            else -> -1
        }
    }

    /** 构造 POKE 报文（sessionId 不足 16B 右侧补零；未启用校验时可传空数组） */
    fun encodePoke(sessionIdRaw: ByteArray = ByteArray(16)): ByteArray {
        val b = ByteArray(SIZE_POKE)
        writeMagic(b)
        b[2] = KIND_POKE.toByte()
        b[3] = 0
        sessionIdRaw.copyInto(b, 4, 0, minOf(16, sessionIdRaw.size))
        return b
    }

    /** 构造 DATA 报文（单分片） */
    fun encodeData(udpSeq: Int, fragIndex: Int, totalFrags: Int, frameLen: Int, payload: ByteArray): ByteArray {
        val b = ByteArray(HEADER_DATA + payload.size)
        writeMagic(b)
        b[2] = KIND_DATA.toByte()
        b[3] = 0
        writeU32(b, 4, udpSeq)
        writeU16(b, 8, fragIndex)
        writeU16(b, 10, totalFrags)
        writeU32(b, 12, frameLen)
        writeU16(b, 16, payload.size)
        payload.copyInto(b, HEADER_DATA)
        return b
    }

    /** 解析 DATA 报文；校验 magic/kind/长度/分片索引，非法返回 null */
    fun decodeData(buf: ByteArray, len: Int): DataHeader? {
        if (kindOf(buf, len) != KIND_DATA || len < HEADER_DATA) return null
        val payloadLen = readU16(buf, 16)
        if (len != HEADER_DATA + payloadLen) return null
        val udpSeq = readU32(buf, 4)
        val fragIndex = readU16(buf, 8)
        val totalFrags = readU16(buf, 10)
        val frameLen = readU32(buf, 12)
        if (totalFrags < 1 || fragIndex >= totalFrags) return null
        if (frameLen <= 0 || frameLen > MAX_FRAME) return null
        val payload = buf.copyOfRange(HEADER_DATA, HEADER_DATA + payloadLen)
        return DataHeader(udpSeq, fragIndex, totalFrags, frameLen, payload)
    }

    /** 构造 ACK 报文（累计 ACK + 预留 bitmap 位） */
    fun encodeAck(cumulativeAckSeq: Int): ByteArray {
        val b = ByteArray(SIZE_ACK)
        writeMagic(b)
        b[2] = KIND_ACK.toByte()
        b[3] = 0
        writeU32(b, 4, cumulativeAckSeq)
        writeU32(b, 8, 0)
        return b
    }

    /** 读取 ACK 累计序号；非法报文返回 0 */
    fun readAckSeq(buf: ByteArray, len: Int): Int {
        if (kindOf(buf, len) != KIND_ACK || len < SIZE_ACK) return 0
        return readU32(buf, 4)
    }

    private fun writeMagic(b: ByteArray) {
        b[0] = (MAGIC ushr 8).toByte()
        b[1] = MAGIC.toByte()
    }

    private fun writeU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    private fun writeU32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}

/** DATA 报文解析结果（负载已切片） */
data class DataHeader(
    val udpSeq: Int,
    val fragIndex: Int,
    val totalFrags: Int,
    val frameLen: Int,
    val payload: ByteArray,
)

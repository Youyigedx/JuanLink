package com.juanlink.core.turn

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TURN（RFC 5766）最小报文编解码 + Long-Term Credential（RFC 5389 §10.2）。
 *
 * 纯函数层，可单测。只实现我们需要的子集：
 * - Allocate / Refresh / CreatePermission（request/response，带认证）
 * - Send / Data Indication（数据面）
 * - 不做 ChannelData（Send/Data 头开销 ~48B/1200B，可接受）
 *
 * 全部大端。STUN 头 20B：`[type u16][length u16][magic cookie u32=0x2112A442][transaction id 12B]`。
 *
 * ⚠️ RFC 5766 method 值与 type 编码：method=Send 0x006 / Data 0x007，而**type** = method + class 位
 * （Send Indication type=0x0016、Data Indication=0x0017）。class 位：request=0、indication=0x0010、
 * success=0x0100、error=0x0110。
 */
object TurnProtocol {

    // ---- 方法（RFC 5766 §6）----
    const val METHOD_ALLOCATE = 0x003
    const val METHOD_REFRESH = 0x004
    const val METHOD_SEND = 0x006
    const val METHOD_DATA = 0x007
    const val METHOD_CREATE_PERMISSION = 0x008

    // ---- 属性 ----
    const val ATTR_ERROR_CODE = 0x0009
    const val ATTR_LIFETIME = 0x000D
    const val ATTR_XOR_PEER_ADDRESS = 0x0012
    const val ATTR_DATA = 0x0013
    const val ATTR_REALM = 0x0014
    const val ATTR_NONCE = 0x0015
    const val ATTR_XOR_RELAYED_ADDRESS = 0x0016
    const val ATTR_REQUESTED_TRANSPORT = 0x0019
    const val ATTR_USERNAME = 0x0006
    const val ATTR_MESSAGE_INTEGRITY = 0x0008

    const val MAGIC_COOKIE = 0x2112A442
    private val COOKIE_BYTES = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)

    // ---- type 编码 ----
    fun requestType(method: Int): Int = method
    fun successType(method: Int): Int = method or 0x0100
    fun errorType(method: Int): Int = method or 0x0110
    fun indicationType(method: Int): Int = method or 0x0010

    // ---- 头 ----
    fun header(type: Int, attrsLength: Int, txId: ByteArray): ByteArray {
        require(txId.size == 12) { "STUN 事务 ID 必须 12B" }
        val b = ByteArray(20)
        writeU16(b, 0, type)
        writeU16(b, 2, attrsLength)
        writeU32(b, 4, MAGIC_COOKIE)
        txId.copyInto(b, 8)
        return b
    }

    // ---- 属性编码 ----

    /** 通用属性：`[type u16][length u16][value + 4 字节对齐补位]` */
    fun attribute(attrType: Int, value: ByteArray): ByteArray {
        val padded = ((value.size + 3) / 4) * 4
        val b = ByteArray(4 + padded)
        writeU16(b, 0, attrType)
        writeU16(b, 2, value.size)
        value.copyInto(b, 4)
        return b
    }

    fun stringAttribute(attrType: Int, s: String): ByteArray =
        attribute(attrType, s.toByteArray(Charsets.UTF_8))

    fun u32Attribute(attrType: Int, value: Long): ByteArray {
        val v = ByteArray(4)
        writeU32(v, 0, value.toInt())
        return attribute(attrType, v)
    }

    /** REQUESTED-TRANSPORT（UDP = 0x11）+ 4B padding */
    fun requestedTransportUdpAttribute(): ByteArray {
        val v = ByteArray(4)
        v[0] = 0x11
        return attribute(ATTR_REQUESTED_TRANSPORT, v)
    }

    /**
     * XOR-MAPPED 族地址属性（XOR-PEER-ADDRESS / XOR-RELAYED-ADDRESS）。
     * IPv4：X-Port = port xor (cookie>>16)，X-Addr = addr xor cookie(4B)；
     * IPv6：X-Addr = addr xor (cookie||txId)(16B)。
     */
    fun encodeXorAddressAttr(attrType: Int, host: String, port: Int, txId: ByteArray): ByteArray? {
        val addr = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return null
        return when (addr.size) {
            4 -> {
                val value = ByteArray(8)
                value[1] = 0x01
                writeU16(value, 2, port xor (MAGIC_COOKIE ushr 16))
                for (i in 0 until 4) value[4 + i] = (addr[i].toInt() xor COOKIE_BYTES[i].toInt()).toByte()
                attribute(attrType, value)
            }
            16 -> {
                val value = ByteArray(20)
                value[1] = 0x02
                writeU16(value, 2, port xor (MAGIC_COOKIE ushr 16))
                val key = ByteArray(16)
                COOKIE_BYTES.copyInto(key, 0)
                txId.copyInto(key, 4)
                for (i in 0 until 16) value[4 + i] = (addr[i].toInt() xor key[i].toInt()).toByte()
                attribute(attrType, value)
            }
            else -> null
        }
    }

    /** XOR 地址属性解码 → `(host, port)`；非法返回 null */
    fun decodeXorAddress(attr: RawAttr, txId: ByteArray): Pair<String, Int>? {
        val v = attr.value
        if (v.size < 8) return null
        val family = v[1].toInt() and 0xFF
        val port = readU16(v, 2) xor (MAGIC_COOKIE ushr 16)
        return when (family) {
            0x01 -> {
                if (v.size < 8) return null
                val addr = ByteArray(4)
                for (i in 0 until 4) addr[i] = (v[4 + i].toInt() xor COOKIE_BYTES[i].toInt()).toByte()
                ipString(addr) to port
            }
            0x02 -> {
                if (v.size < 20) return null
                val key = ByteArray(16)
                COOKIE_BYTES.copyInto(key, 0)
                txId.copyInto(key, 4)
                val addr = ByteArray(16)
                for (i in 0 until 16) addr[i] = (v[4 + i].toInt() xor key[i].toInt()).toByte()
                ipString(addr) to port
            }
            else -> null
        }
    }

    // ---- 报文构造 ----

    /** Allocate request（无凭据；收到 401 后带凭据重发） */
    fun buildAllocateRequest(txId: ByteArray): ByteArray {
        val body = requestedTransportUdpAttribute()
        return header(requestType(METHOD_ALLOCATE), body.size, txId) + body
    }

    /** Send Indication：数据面单条报文，物理发给 TURN 服务器，逻辑对端进 XOR-PEER-ADDRESS */
    fun buildSendIndication(txId: ByteArray, peerHost: String, peerPort: Int, payload: ByteArray): ByteArray? {
        val peerAttr = encodeXorAddressAttr(ATTR_XOR_PEER_ADDRESS, peerHost, peerPort, txId) ?: return null
        val dataAttr = attribute(ATTR_DATA, payload)
        val body = peerAttr + dataAttr
        return header(indicationType(METHOD_SEND), body.size, txId) + body
    }

    /**
     * 带凭据的 request（Allocate 重发 / Refresh / CreatePermission）。
     * 属性顺序：业务属性 → USERNAME → REALM → NONCE → MESSAGE-INTEGRITY（最后，值占位后计算覆盖）。
     */
    fun buildAuthenticatedRequest(
        method: Int,
        attrs: List<ByteArray>,
        txId: ByteArray,
        username: String,
        realm: String,
        nonce: String,
        key: ByteArray,
    ): ByteArray {
        val body = ByteArrayOutputStream()
        for (a in attrs) body.write(a)
        body.write(stringAttribute(ATTR_USERNAME, username))
        body.write(stringAttribute(ATTR_REALM, realm))
        body.write(stringAttribute(ATTR_NONCE, nonce))
        val integrityOffsetInBody = body.size()
        body.write(attribute(ATTR_MESSAGE_INTEGRITY, ByteArray(20))) // 属性头(type=8,len=20)+值 20B 占位
        val fullBody = body.toByteArray()
        val msg = header(requestType(method), fullBody.size, txId) + fullBody
        val mac = messageIntegrity(msg, 20 + integrityOffsetInBody, key)
        mac.copyInto(msg, 20 + integrityOffsetInBody + 4)
        return msg
    }

    // ---- Long-Term Credential（RFC 5389 §10.2）----

    /** key = MD5(username:realm:password) */
    fun credentialKey(username: String, realm: String, password: String): ByteArray =
        MessageDigest.getInstance("MD5").digest("$username:$realm:$password".toByteArray(Charsets.UTF_8))

    /**
     * HMAC-SHA1 over `msg[0..integrityOffset)`——MESSAGE-INTEGRITY 属性起始偏移之前的部分。
     *
     * RFC 8489 §14.5 / RFC 5389 §15.4：HMAC 输入为 STUN 报文（含头）直到（不含）MESSAGE-INTEGRITY
     * 属性本身，即"up to and including the attribute preceding the MESSAGE-INTEGRITY attribute"。
     * header 的 Length 字段才指向 MI 结束（所以报文里 MI 属性头 len=20 依然计入 length）。
     *
     * ⚠️ 不能像初版那样把 HMAC 覆盖到 MI 结束（integrityOffset+24 对齐）——那会把 MI 占位值算进
     * 输入，导致自认证 401→400。已用 RFC 5769 §2.4 官方向量确认：不含 MI 语义
     * `f6702465...` 匹配，含 MI 语义 `b35d9a80...` 不匹配。
     */
    fun messageIntegrity(msg: ByteArray, integrityOffset: Int, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        mac.update(msg, 0, integrityOffset)
        return mac.doFinal()
    }

    // ---- 报文解析 ----

    data class RawAttr(val type: Int, val value: ByteArray)

    data class ParsedMessage(val type: Int, val txId: ByteArray, val attrs: List<RawAttr>)

    /** 解析 STUN/TURN 消息；非法头返回 null（不校验 txId，由调用方匹配） */
    fun parse(buf: ByteArray, len: Int): ParsedMessage? {
        if (len < 20) return null
        val type = readU16(buf, 0)
        val attrsLen = readU16(buf, 2)
        if (readU32(buf, 4) != MAGIC_COOKIE) return null
        val txId = buf.copyOfRange(8, 20)
        val attrs = ArrayList<RawAttr>(4)
        var off = 20
        val end = minOf(20 + attrsLen, len)
        while (off + 4 <= end) {
            val at = readU16(buf, off)
            val al = readU16(buf, off + 2)
            if (off + 4 + al > end) break
            attrs.add(RawAttr(at, buf.copyOfRange(off + 4, off + 4 + al)))
            // 属性按 4 字节对齐：length 只含 value，推进须含 padding（否则 padding 区被误当属性头）
            off += 4 + ((al + 3) / 4) * 4
        }
        return ParsedMessage(type, txId, attrs)
    }

    fun attr(msg: ParsedMessage, attrType: Int): RawAttr? =
        msg.attrs.firstOrNull { it.type == attrType }

    fun stringAttr(msg: ParsedMessage, attrType: Int): String? =
        attr(msg, attrType)?.value?.toString(Charsets.UTF_8)

    /** ERROR-CODE：`[reserved u16][class u8][number u8]` → code = class*100 + number */
    fun errorCodeOf(attr: RawAttr): Int {
        if (attr.value.size < 4) return -1
        val cls = attr.value[2].toInt() and 0x7
        val num = attr.value[3].toInt() and 0xFF
        return cls * 100 + num
    }

    fun lifetimeOf(msg: ParsedMessage): Int? =
        attr(msg, ATTR_LIFETIME)?.value?.let { if (it.size >= 4) readU32(it, 0) else null }

    /** Data Indication 解析结果 */
    data class DataIndication(val peerHost: String, val peerPort: Int, val payload: ByteArray)

    fun parseDataIndication(buf: ByteArray, len: Int): DataIndication? {
        val msg = parse(buf, len) ?: return null
        if (msg.type != indicationType(METHOD_DATA)) return null
        val peerAttr = attr(msg, ATTR_XOR_PEER_ADDRESS) ?: return null
        val dataAttr = attr(msg, ATTR_DATA) ?: return null
        val (host, port) = decodeXorAddress(peerAttr, msg.txId) ?: return null
        return DataIndication(host, port, dataAttr.value)
    }

    private fun ipString(bytes: ByteArray): String =
        runCatching { InetAddress.getByAddress(bytes).hostAddress }.getOrNull() ?: bytes.joinToString(".")

    // ---- 大端读写（独立实现，与 StunClient 解耦）----

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

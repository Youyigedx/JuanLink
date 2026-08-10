package com.juanlink.core.turn

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TURN 报文编解码 + Long-Term Credential：报文格式、XOR 地址往返、MESSAGE-INTEGRITY 与 JCA 一致 */
class TurnProtocolTest {

    private val txId = ByteArray(12) { it.toByte() }

    private fun rxid(seed: Int): ByteArray = ByteArray(12) { (it + seed).toByte() }

    @Test
    fun allocateRequestWellFormed() {
        val req = TurnProtocol.buildAllocateRequest(txId)
        assertEquals(20 + 8, req.size) // 头 20B + REQUESTED-TRANSPORT(4B value + 4B 补位)
        assertEquals(0x0003, readU16(req, 0), "Allocate request type = method 0x003")
        assertEquals(TurnProtocol.MAGIC_COOKIE, readU32(req, 4))
        for (i in 0 until 12) assertEquals(txId[i], req[8 + i])
        // REQUESTED-TRANSPORT = UDP (0x11)
        assertEquals(0x0019, readU16(req, 20))
        assertEquals(0x11, req[24].toInt() and 0xFF)
    }

    @Test
    fun typeClassEncoding() {
        assertEquals(0x0016, TurnProtocol.indicationType(TurnProtocol.METHOD_SEND), "Send Indication type")
        assertEquals(0x0017, TurnProtocol.indicationType(TurnProtocol.METHOD_DATA), "Data Indication type")
        assertEquals(0x0103, TurnProtocol.successType(TurnProtocol.METHOD_ALLOCATE), "Allocate Success")
        assertEquals(0x0113, TurnProtocol.errorType(TurnProtocol.METHOD_ALLOCATE), "Allocate Error")
        assertEquals(0x0108, TurnProtocol.successType(TurnProtocol.METHOD_CREATE_PERMISSION))
    }

    @Test
    fun xorPeerAddressRoundtrip() {
        val attr = TurnProtocol.encodeXorAddressAttr(TurnProtocol.ATTR_XOR_PEER_ADDRESS, "203.0.113.7", 49152, txId)
        assertNotNull(attr)
        val decoded = TurnProtocol.decodeXorAddress(TurnProtocol.RawAttr(TurnProtocol.ATTR_XOR_PEER_ADDRESS, attr.copyOfRange(4, 4 + readU16(attr, 2))), txId)
        assertNotNull(decoded)
        assertEquals("203.0.113.7", decoded!!.first)
        assertEquals(49152, decoded.second)
    }

    @Test
    fun xorRelayedAddressRoundtrip() {
        val attr = TurnProtocol.encodeXorAddressAttr(TurnProtocol.ATTR_XOR_RELAYED_ADDRESS, "127.0.0.1", 60000, txId)
        assertNotNull(attr)
        val decoded = TurnProtocol.decodeXorAddress(TurnProtocol.RawAttr(TurnProtocol.ATTR_XOR_RELAYED_ADDRESS, attr.copyOfRange(4, 4 + readU16(attr, 2))), txId)
        assertEquals("127.0.0.1" to 60000, decoded)
    }

    @Test
    fun sendIndicationParsedAsDataHasPeerAndPayload() {
        // 构造 Send Indication（与 parseDataIndication 的 Data 结构同构，验证编码正确性）
        val payload = "你好JUAN".toByteArray()
        val send = TurnProtocol.buildSendIndication(txId, "203.0.113.9", 3478, payload)
        assertNotNull(send)
        assertEquals(0x0016, readU16(send!!, 0))

        // 用 Data Indication 类型重写 header → parseDataIndication 应解析
        val data = ByteArray(send.size)
        send.copyInto(data)
        writeU16(data, 0, TurnProtocol.indicationType(TurnProtocol.METHOD_DATA))
        val di = TurnProtocol.parseDataIndication(data, data.size)
        assertNotNull(di)
        assertEquals("203.0.113.9", di!!.peerHost)
        assertEquals(3478, di.peerPort)
        assertContentEquals(payload, di.payload)
    }

    @Test
    fun dataIndicationRejectsWrongType() {
        val req = TurnProtocol.buildAllocateRequest(txId)
        assertNull(TurnProtocol.parseDataIndication(req, req.size), "Allocate request 不应被当作 Data Indication")
    }

    @Test
    fun credentialKeyLengthAndDeterministic() {
        val k1 = TurnProtocol.credentialKey("user", "realm", "pass")
        val k2 = TurnProtocol.credentialKey("user", "realm", "pass")
        assertEquals(16, k1.size, "MD5 输出 16B")
        assertContentEquals(k1, k2)
        val k3 = TurnProtocol.credentialKey("user", "realm", "other")
        assertTrue(!k1.contentEquals(k3), "不同 password 应产生不同 key")
    }

    @Test
    fun messageIntegrityMatchesJcaHmac() {
        val key = TurnProtocol.credentialKey("user", "realm", "pass")
        // 手工拼一个带凭据的 Allocate（复用 buildAuthenticatedRequest 保证属性顺序/占位）
        val msg = TurnProtocol.buildAuthenticatedRequest(
            TurnProtocol.METHOD_ALLOCATE,
            listOf(TurnProtocol.requestedTransportUdpAttribute()),
            txId, "user", "realm", "nonce", key,
        )
        // 从报文中定位 MESSAGE-INTEGRITY 属性偏移
        val integrityOffset = findIntegrityOffset(msg)
        assertTrue(integrityOffset > 0, "应包含 MESSAGE-INTEGRITY 属性")
        // 报文里存的是 builder 算好的 MAC（在值区为 0 时计算）
        val storedMac = msg.copyOfRange(integrityOffset + 4, integrityOffset + 24)
        // RFC 5389 §15.4 / RFC 8489 §14.5：HMAC 输入 = 值区为 0 的报文，覆盖到 MI 属性**起始偏移**为止
        // （不能覆盖到 MI 属性本身——含头 4B 与占位值，否则与 builder/服务器计算不一致）
        for (i in integrityOffset + 4 until integrityOffset + 24) msg[i] = 0
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        mac.update(msg, 0, integrityOffset)
        // ⚠️ doFinal() 只可调用一次（调用后重置状态，第二次返回空输入 HMAC）
        val recomputed = mac.doFinal()
        assertContentEquals(storedMac, recomputed, "MESSAGE-INTEGRITY 应与独立 JCA HMAC-SHA1 完全一致")
        // 同时验证 TurnProtocol.messageIntegrity（MD5 key 推导 + 对齐逻辑）与 JCA 一致
        assertContentEquals(recomputed, TurnProtocol.messageIntegrity(msg, integrityOffset, key))
    }

    @Test
    fun authenticatedRequestHasCredentialAttrsInOrder() {
        val key = TurnProtocol.credentialKey("user", "realm", "pass")
        val msg = TurnProtocol.buildAuthenticatedRequest(
            TurnProtocol.METHOD_CREATE_PERMISSION,
            emptyList(),
            txId, "user", "realm", "nonce", key,
        )
        val parsed = TurnProtocol.parse(msg, msg.size)
        assertNotNull(parsed)
        val types = parsed!!.attrs.map { it.type }
        assertTrue(types.containsAll(listOf(
            TurnProtocol.ATTR_USERNAME,
            TurnProtocol.ATTR_REALM,
            TurnProtocol.ATTR_NONCE,
            TurnProtocol.ATTR_MESSAGE_INTEGRITY,
        )), "应含 USERNAME/REALM/NONCE/MESSAGE-INTEGRITY（实际 $types）")
        assertEquals(TurnProtocol.ATTR_MESSAGE_INTEGRITY, types.last(), "MESSAGE-INTEGRITY 必须是最后一个属性")
        assertEquals("user", TurnProtocol.stringAttr(parsed, TurnProtocol.ATTR_USERNAME))
        assertEquals("nonce", TurnProtocol.stringAttr(parsed, TurnProtocol.ATTR_NONCE))
    }

    @Test
    fun errorCodeParsing() {
        // ERROR-CODE: [0,0,4,1] = 401
        val value = byteArrayOf(0, 0, 4, 1)
        assertEquals(401, TurnProtocol.errorCodeOf(TurnProtocol.RawAttr(TurnProtocol.ATTR_ERROR_CODE, value)))
        val value300 = byteArrayOf(0, 0, 3, 0)
        assertEquals(300, TurnProtocol.errorCodeOf(TurnProtocol.RawAttr(TurnProtocol.ATTR_ERROR_CODE, value300)))
    }

    /** 在报文中定位 MESSAGE-INTEGRITY 属性的起始偏移（整个报文）；未找到返回 -1 */
    private fun findIntegrityOffset(msg: ByteArray): Int {
        var off = 20
        val end = 20 + readU16(msg, 2)
        while (off + 4 <= end) {
            val type = readU16(msg, off)
            val len = readU16(msg, off + 2)
            if (type == TurnProtocol.ATTR_MESSAGE_INTEGRITY) return off
            off += 4 + ((len + 3) / 4) * 4 // 属性 4 字节对齐推进
        }
        return -1
    }

    private fun writeU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 8).toByte()
        b[off + 1] = v.toByte()
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    // 抑制未用告警：rxid 供未来扩展
    @Suppress("unused")
    private fun unusedRxid() = rxid(1)
}

package com.juanlink.core.turn

import com.juanlink.core.transport.TurnServer
import com.juanlink.core.turn.TurnProtocol.ParsedMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TurnClient 有状态事务：本地 mock TURN server 走完整 Long-Term Credential 流程。
 * mock server 对带凭据请求**独立重算 MESSAGE-INTEGRITY 校验**（用 JCA 对照，而非信任客户端）。
 */
class TurnClientTest {

    @Test
    fun allocateCompletesAuthChallengeAndServerVerifiesIntegrity() {
        var credentialReceived = false
        val server = MockTurnServer { raw, len, msg ->
            when {
                // 无凭据 Allocate → 401 质询（带 REALM/NONCE）
                msg.type == TurnProtocol.requestType(TurnProtocol.METHOD_ALLOCATE) &&
                    TurnProtocol.attr(msg, TurnProtocol.ATTR_MESSAGE_INTEGRITY) == null ->
                    errorResp(TurnProtocol.METHOD_ALLOCATE, 401, REALM, NONCE_1, msg.txId)

                // 带凭据 Allocate → 校验凭据 + MI，通过则 200
                msg.type == TurnProtocol.requestType(TurnProtocol.METHOD_ALLOCATE) -> {
                    credentialReceived = true
                    assertTrue(
                        verifyCredentialsAndIntegrity(raw, len, msg),
                        "服务端独立校验 USERNAME/REALM/NONCE + MESSAGE-INTEGRITY 应通过",
                    )
                    allocateOk(msg.txId, "127.0.0.1", RELAY_PORT)
                }

                else -> null
            }
        }.start()

        try {
            val ep = TurnClient(newClientSocket(), TurnServer("127.0.0.1", server.port, USER, PASSWORD))
                .allocate(3000)
            assertNotNull(ep, "带凭据重发后应分配成功")
            assertEquals("127.0.0.1", ep!!.relayedHost)
            assertEquals(RELAY_PORT, ep.relayedPort)
            assertTrue(credentialReceived, "mock server 应收到带凭据请求")
        } finally {
            server.close()
        }
    }

    @Test
    fun allocateRejectsUnexpectedError() {
        val server = MockTurnServer { _, _, msg ->
            if (msg.type == TurnProtocol.requestType(TurnProtocol.METHOD_ALLOCATE))
                errorResp(TurnProtocol.METHOD_ALLOCATE, 500, null, null, msg.txId)
            else null
        }.start()
        try {
            val ep = TurnClient(newClientSocket(), TurnServer("127.0.0.1", server.port, USER, PASSWORD))
                .allocate(1500)
            assertNull(ep, "500 非认证错误不应被当作质询，allocate 应失败")
        } finally {
            server.close()
        }
    }

    @Test
    fun allocateRetriesOnceOnStaleNonce() {
        var phase = 0
        val noncesSent = mutableListOf<String>()
        val server = MockTurnServer { raw, len, msg ->
            when {
                // 第一次：401 质询
                msg.type == TurnProtocol.requestType(TurnProtocol.METHOD_ALLOCATE) &&
                    TurnProtocol.attr(msg, TurnProtocol.ATTR_MESSAGE_INTEGRITY) == null -> {
                    errorResp(TurnProtocol.METHOD_ALLOCATE, 401, REALM, NONCE_1, msg.txId)
                }
                // 带凭据：nonce_1 → 438 换 nonce_2；nonce_2 → 200
                msg.type == TurnProtocol.requestType(TurnProtocol.METHOD_ALLOCATE) -> {
                    assertTrue(verifyCredentialsAndIntegrity(raw, len, msg), "438 前凭据也应合法")
                    noncesSent.add(TurnProtocol.stringAttr(msg, TurnProtocol.ATTR_NONCE) ?: "")
                    when {
                        phase == 0 -> {
                            phase++
                            errorResp(TurnProtocol.METHOD_ALLOCATE, 438, REALM, NONCE_2, msg.txId)
                        }
                        else -> allocateOk(msg.txId, "127.0.0.1", RELAY_PORT)
                    }
                }
                else -> null
            }
        }.start()
        try {
            val ep = TurnClient(newClientSocket(), TurnServer("127.0.0.1", server.port, USER, PASSWORD))
                .allocate(3000)
            assertNotNull(ep, "438 Stale Nonce 更新后重试应成功")
            assertEquals(NONCE_1, noncesSent.firstOrNull(), "第一次带凭据请求应携带旧 nonce")
            assertTrue(noncesSent.contains(NONCE_2), "438 后应换新 nonce 重试（实际 $noncesSent）")
            assertEquals(RELAY_PORT, ep!!.relayedPort)
        } finally {
            server.close()
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun newClientSocket(): DatagramSocket =
        DatagramSocket(null).apply {
            bind(InetSocketAddress("0.0.0.0", 0))
        }

    /** 校验 USERNAME/REALM/NONCE 属性 + 独立重算 MESSAGE-INTEGRITY（值区清零后 JCA 对照） */
    private fun verifyCredentialsAndIntegrity(raw: ByteArray, len: Int, msg: ParsedMessage): Boolean {
        if (TurnProtocol.stringAttr(msg, TurnProtocol.ATTR_USERNAME) != USER) return false
        if (TurnProtocol.stringAttr(msg, TurnProtocol.ATTR_REALM) != REALM) return false
        val mi = TurnProtocol.attr(msg, TurnProtocol.ATTR_MESSAGE_INTEGRITY) ?: return false
        if (mi.value.size != 20) return false
        val off = findIntegrityOffset(raw, len) ?: return false
        // 值区清零后重算（RFC 5389 §15.4）
        val copy = raw.copyOfRange(0, len)
        for (i in off + 4 until off + 24) copy[i] = 0
        val key = TurnProtocol.credentialKey(USER, REALM, PASSWORD)
        val expected = TurnProtocol.messageIntegrity(copy, off, key)
        return expected.contentEquals(mi.value)
    }

    private fun findIntegrityOffset(buf: ByteArray, len: Int): Int? {
        var off = 20
        val end = 20 + readU16(buf, 2)
        while (off + 4 <= end) {
            val type = readU16(buf, off)
            val al = readU16(buf, off + 2)
            if (type == TurnProtocol.ATTR_MESSAGE_INTEGRITY) return off
            off += 4 + ((al + 3) / 4) * 4 // 属性 4 字节对齐推进
        }
        return null
    }

    private fun errorResp(method: Int, code: Int, realm: String?, nonce: String?, txId: ByteArray): ByteArray {
        val ec = byteArrayOf(0, 0, (code / 100).toByte(), (code % 100).toByte())
        val body = TurnProtocol.attribute(TurnProtocol.ATTR_ERROR_CODE, ec)
        if (realm != null) {
            return TurnProtocol.header(TurnProtocol.errorType(method), body.size + realmBodySize(realm, nonce!!), txId) +
                body +
                TurnProtocol.stringAttribute(TurnProtocol.ATTR_REALM, realm) +
                TurnProtocol.stringAttribute(TurnProtocol.ATTR_NONCE, nonce)
        }
        return TurnProtocol.header(TurnProtocol.errorType(method), body.size, txId) + body
    }

    private fun realmBodySize(realm: String, nonce: String): Int =
        TurnProtocol.stringAttribute(TurnProtocol.ATTR_REALM, realm).size +
            TurnProtocol.stringAttribute(TurnProtocol.ATTR_NONCE, nonce).size

    private fun allocateOk(txId: ByteArray, relayedHost: String, relayedPort: Int): ByteArray {
        val relayed = TurnProtocol.encodeXorAddressAttr(
            TurnProtocol.ATTR_XOR_RELAYED_ADDRESS, relayedHost, relayedPort, txId,
        ) ?: return ByteArray(0)
        val lifetime = TurnProtocol.u32Attribute(TurnProtocol.ATTR_LIFETIME, 600)
        val body = relayed + lifetime
        return TurnProtocol.header(TurnProtocol.successType(TurnProtocol.METHOD_ALLOCATE), body.size, txId) + body
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    // ---------------------------------------------------------------- 本地 mock TURN server

    private class MockTurnServer(
        private val handler: (raw: ByteArray, len: Int, msg: ParsedMessage) -> ByteArray?,
    ) {
        val socket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val port: Int get() = socket.localPort
        private val thread = Thread { loop() }.apply { isDaemon = true }

        fun start(): MockTurnServer {
            thread.start()
            return this
        }

        private fun loop() {
            val buf = ByteArray(8192)
            while (!socket.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                val len = try {
                    socket.receive(pkt)
                    pkt.length
                } catch (e: Exception) {
                    break
                }
                val msg = TurnProtocol.parse(buf, len) ?: continue
                val resp = handler(buf, len, msg) ?: continue
                runCatching { socket.send(DatagramPacket(resp, resp.size, pkt.socketAddress)) }
            }
        }

        fun close() = socket.close()
    }

    private companion object {
        const val USER = "test"
        const val PASSWORD = "testpass"
        const val REALM = "testrealm"
        const val NONCE_1 = "nonce-1"
        const val NONCE_2 = "nonce-2"
        const val RELAY_PORT = 34567
    }
}

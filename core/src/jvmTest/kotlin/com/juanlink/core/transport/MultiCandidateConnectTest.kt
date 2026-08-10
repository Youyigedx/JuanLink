package com.juanlink.core.transport

import com.juanlink.core.pairing.Candidate
import com.juanlink.core.pairing.PairingCodec
import com.juanlink.core.pairing.PairingManager
import com.juanlink.core.qr.QrCodec
import com.juanlink.core.session.SessionManager
import com.juanlink.core.util.nowEpochMillis
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 多候选（局域网 IP 枚举）场景测试：
 * - 响应方首候选不可达时自动落到下一候选
 * - 发起方自动枚举多候选且二维码往返一致
 * - 显式 bindHost 保持单候选（兼容 loopback 测试）
 */
class MultiCandidateConnectTest {

    private val noopListener = object : TransportListener {
        override fun onConnected() {}
        override fun onDisconnected(reason: DisconnectReason) {}
        override fun onFrame(message: com.juanlink.core.protocol.WireMessage) {}
    }

    @Test
    fun responderFailsOverToReachableCandidate() = runBlocking {
        val pm = PairingManager()
        val tA = TcpTransport(bindHost = "127.0.0.1", bindPort = 0)
        val mA = SessionManager("dev-A", tA)
        val receivedA = CopyOnWriteArrayList<ByteArray>()
        mA.onEncryptedPlain = { receivedA.add(it) }
        val pairingA = mA.startInitiator(pm)
        assertTrue(
            pairingA.candidates.size == 1 && pairingA.candidates[0].host == "127.0.0.1",
            "显式绑定 127.0.0.1 应只产单候选（实际 ${pairingA.candidates}）",
        )

        // 首候选是必不通的端口（本机无监听，立即拒绝），次候选才是真实端点
        val fallback = pairingA.copy(candidates = listOf(Candidate("127.0.0.1", 1), pairingA.candidates[0]))

        val tB = TcpTransport()
        val mB = SessionManager("dev-B", tB)
        val receivedB = CopyOnWriteArrayList<ByteArray>()
        mB.onEncryptedPlain = { receivedB.add(it) }
        assertTrue(mB.connectAsResponder(fallback), "首候选不可达时应收敛到次候选连接成功")

        awaitUntil(5000) { mA.isConnected && mB.isConnected }
        assertTrue(mA.isConnected && mB.isConnected, "经次候选应完成握手")

        assertTrue(mA.sendEncrypted("over-tailscale".encodeToByteArray()))
        awaitUntil(5000) { receivedB.size == 1 }
        assertEquals("over-tailscale", String(receivedB[0]))
    }

    @Test
    fun multiCandidatePairingSurvivesQrRoundtrip() {
        val pm = PairingManager()
        val tA = TcpTransport(bindHost = "0.0.0.0", bindPort = 0)
        val mA = SessionManager("dev-A", tA)
        val pairing = mA.startInitiator(pm)
        assertTrue(pairing.candidates.isNotEmpty(), "发起方应自动枚举候选")
        assertTrue(pairing.candidates.size <= 4, "候选数应受限（当前 ${pairing.candidates.size}）")
        assertEquals(
            pairing.candidates.size,
            pairing.candidates.map { it.host }.distinct().size,
            "候选 host 应去重",
        )

        val payload = PairingCodec.toQrPayload(pairing)
        println("[QR] 多候选 payload ${payload.length}B candidates=${pairing.candidates.map { "${it.host}:${it.port}" }}")
        val decoded = QrCodec.decode(QrCodec.encode(payload, scale = 6))
        assertNotNull(decoded, "多候选二维码应可解码")
        val info = PairingCodec.fromQrPayload(decoded)
        assertEquals(pairing.candidates, info.candidates, "二维码往返候选应一致")
    }

    @Test
    fun explicitBindHostKeepsSingleCandidate() {
        val t = TcpTransport(bindHost = "127.0.0.1", bindPort = 0)
        t.start(noopListener)
        val hosts = t.localCandidates().map { it.first }
        assertEquals(listOf("127.0.0.1"), hosts)
        t.disconnect()
    }

    private suspend fun awaitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = nowEpochMillis() + timeoutMs
        while (!condition()) {
            if (nowEpochMillis() > deadline) throw AssertionError("等待条件超时 (${timeoutMs}ms)")
            delay(20)
        }
    }
}

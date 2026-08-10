package com.juanlink.core

import com.juanlink.core.crypto.CryptoEngine
import com.juanlink.core.pairing.Candidate
import com.juanlink.core.pairing.PairingCodec
import com.juanlink.core.pairing.PairingInfo
import com.juanlink.core.pairing.PairingManager
import com.juanlink.core.qr.QrCodec
import com.juanlink.core.transport.TcpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 二维码链路往返测试：
 * 真实 PairingInfo JSON → QrEncoder 编码 → QrRenderer 渲染 → ZXing 解码 → fromQrPayload。
 * 还原 ConnectionPanel 的确切调用（scale = 6，默认静区）。
 * 模拟手机 CameraScanner 的真实输出（scale=2 小图更接近相机画面）。
 */
class QrRoundTripTest {

    private fun makePairing(): PairingInfo {
        // 与 AppState.createRoom 相同的来源：startInitiator 产出的 PairingInfo
        val transport = TcpTransport(bindHost = "0.0.0.0", bindPort = 0)
        transport.start(object : com.juanlink.core.transport.TransportListener {
            override fun onConnected() {}
            override fun onDisconnected(reason: com.juanlink.core.transport.DisconnectReason) {}
            override fun onFrame(message: com.juanlink.core.protocol.WireMessage) {}
        })
        val endpoint = transport.localEndpoint()
        assertNotNull(endpoint, "transport 应产出局域网端点")
        assertTrue(endpoint.first != "127.0.0.1", "局域网端点不应是环回地址")
        val pm = PairingManager()
        val kp = CryptoEngine.generateX25519KeyPair()
        return pm.issue("desktop-test", kp.publicKey, listOf(Candidate(endpoint.first, endpoint.second)))
    }

    @Test
    fun desktopQrSurvivesPhoneDecode() {
        val pairing = makePairing()
        val payload = PairingCodec.toQrPayload(pairing)
        assertTrue(payload.isNotEmpty())

        // 桌面 ConnectionPanel 渲染参数
        val qr = QrCodec.encode(payload, scale = 6)
        val version = (qr.width / 6 - 8 - 17) / 4
        println("[QR] payload ${payload.length}B, version=$version, bitmap ${qr.width}x${qr.height}")
        assertTrue(payload.length < 250, "负载应紧凑（当前 ${payload.length}B），保证 QR 版本低、屏幕模块大")
        assertTrue(version <= 11, "QR 版本应 ≤ 11（当前 v$version），否则屏幕模块过小难扫码")

        // 手机 ZXing 解码
        val decoded = QrCodec.decode(qr)
        assertNotNull(decoded, "ZXing 应能解码桌面二维码")
        assertEquals(payload, decoded, "解码结果必须与原始 payload 完全一致")

        // 手机再走 fromQrPayload，必须还原 PairingInfo（默认字段回填）
        val info = PairingCodec.fromQrPayload(decoded)
        assertEquals(pairing.sessionId, info.sessionId)
        assertEquals(pairing.publicKey, info.publicKey)
        assertEquals(pairing.candidates, info.candidates)
        assertEquals(pairing.role, info.role)
        assertEquals(pairing.magic, info.magic)
        assertTrue(PairingCodec.isValid(info), "还原的配对信息应有效")
    }

    @Test
    fun udpCandidateSurvivesQrRoundtrip() {
        // 阶段 C：二维码同时携带 TCP 局域网候选 + UDP 公网打洞候选（type="udp"）
        val transport = TcpTransport(bindHost = "0.0.0.0", bindPort = 0)
        transport.start(object : com.juanlink.core.transport.TransportListener {
            override fun onConnected() {}
            override fun onDisconnected(reason: com.juanlink.core.transport.DisconnectReason) {}
            override fun onFrame(message: com.juanlink.core.protocol.WireMessage) {}
        })
        val ep = transport.localEndpoint()!!
        val pm = PairingManager()
        val kp = CryptoEngine.generateX25519KeyPair()
        val pairing = pm.issue(
            "desktop-test",
            kp.publicKey,
            listOf(
                Candidate(ep.first, ep.second, type = "tcp"),
                Candidate("203.0.113.7", 30000, type = "udp"),
            ),
        )

        val payload = PairingCodec.toQrPayload(pairing)
        println("[QR] 含 udp 候选 payload ${payload.length}B")
        val qr = QrCodec.encode(payload, scale = 6)
        val version = (qr.width / 6 - 8 - 17) / 4
        // 单候选 ~v11；加 UDP 公网候选后 payload 增大到 ~265B → 物理升到 v12。
        // v12-13 在 scale=6 下仍 390-414px，手机屏幕可扫，故容忍到 v13（体积换跨网能力）
        assertTrue(version <= 13, "多候选（TCP+UDP）payload 增大，QR 版本应可容忍到 v13（当前 v$version）")

        val decoded = QrCodec.decode(qr)
        val info = PairingCodec.fromQrPayload(decoded!!)
        assertEquals(pairing.candidates, info.candidates, "udp 候选应无损往返")
        assertTrue(info.candidates.any { it.type == "udp" }, "往返后应保留 udp 候选")
        assertTrue(info.candidates.any { it.type == "tcp" }, "往返后应保留 tcp 候选")
    }

    @Test
    fun relayCandidateAndTurnSurviveQrRoundtrip() {
        // TURN 中继兜底：二维码携带 relay 候选（type="relay"）+ PairingInfo.turn（Initiator 实际分配成功的那台）
        val transport = TcpTransport(bindHost = "0.0.0.0", bindPort = 0)
        transport.start(object : com.juanlink.core.transport.TransportListener {
            override fun onConnected() {}
            override fun onDisconnected(reason: com.juanlink.core.transport.DisconnectReason) {}
            override fun onFrame(message: com.juanlink.core.protocol.WireMessage) {}
        })
        val ep = transport.localEndpoint()!!
        val pm = PairingManager()
        val kp = CryptoEngine.generateX25519KeyPair()
        val pairing = pm.issue(
            "desktop-test",
            kp.publicKey,
            listOf(
                Candidate(ep.first, ep.second, type = "tcp"),
                Candidate("203.0.113.7", 30000, type = "udp"),
                Candidate("203.0.113.9", 51000, type = "relay"),
            ),
        ).copy(turn = "openrelay.metered.ca:3478")

        val payload = PairingCodec.toQrPayload(pairing)
        assertTrue(payload.contains("\"relay\""), "payload 应包含 relay 候选")
        assertTrue(payload.contains("openrelay.metered.ca:3478"), "payload 应包含 turn 服务器地址")
        println("[QR] 含 relay+turn payload ${payload.length}B")

        val qr = QrCodec.encode(payload, scale = 6)
        val version = (qr.width / 6 - 8 - 17) / 4
        // 多候选（TCP+UDP+relay）+ turn 字段体积更大：relay 是跨网最后兜底，体积成本可接受。
        // 容忍到 v14（scale=6 下 438px，手机屏幕仍可扫）
        assertTrue(version <= 14, "加 relay 候选 + turn 后 QR 版本应容忍到 v14（当前 v$version）")

        val decoded = QrCodec.decode(qr)
        val info = PairingCodec.fromQrPayload(decoded!!)
        assertEquals(pairing.candidates, info.candidates, "含 relay 的候选应无损往返")
        assertTrue(info.candidates.any { it.type == "relay" }, "往返后应保留 relay 候选")
        assertEquals("openrelay.metered.ca:3478", info.turn, "turn 字段应往返")
        assertTrue(PairingCodec.isValid(info), "还原的配对信息应有效")
    }

    @Test
    fun smallQrStillDecodes() {
        // 手机屏幕上若由小屏渲染，scale 更小；相机采集到的画面更小，验证小图仍可解码
        val pairing = makePairing()
        val payload = PairingCodec.toQrPayload(pairing)
        for (scale in listOf(3, 4)) {
            val qr = QrCodec.encode(payload, scale = scale)
            val decoded = QrCodec.decode(qr)
            assertNotNull(decoded, "scale=$scale 时应可解码")
            assertEquals(payload, decoded)
            println("[QR] scale=$scale -> ${qr.width}x${qr.height} decode OK")
        }
    }
}

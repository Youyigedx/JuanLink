package com.juanlink.core.rendezvous

import com.juanlink.core.AppInfo
import com.juanlink.core.pairing.Candidate
import com.juanlink.core.pairing.PairingCodec
import com.juanlink.core.pairing.PairingInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 会合层单元测试：短码格式、分配重试、以及「配对信息 ↔ Wire JSON」往返。
 * 不含网络测试（网络由 `RendezvousServerTest` 用假客户端 + 真服务器覆盖）。
 */
class RendezvousTest {

    private fun info(): PairingInfo = PairingInfo(
        sessionId = "sess-abc123",
        deviceId = "dev-1",
        publicKey = "cHVibGlja2V5MzJieXRlc2Jhc2U2NHVybGVuY29kZWQ",
        ts = 1_700_000_000_000,
        nonce = "bm9uY2U4Ynl0ZXM",
        candidates = listOf(
            Candidate("192.168.1.5", 45678, type = "tcp"),
            Candidate("203.0.113.9", 50000, type = "relay"),
        ),
        turn = "turn.example.com:3478",
    )

    @Test
    fun generatedCodeIsValidAndWellFormed() {
        repeat(200) { seed ->
            val rand = ByteArray(8) { (it * 31 + seed).toByte() }
            val code = RendezvousCodeFormat.generate(rand)
            assertTrue(RendezvousCodeFormat.validate(code), "应通过严格校验: $code")
            val n = RendezvousCodeFormat.normalize(code)
            assertEquals(8, n.length, "标准化后应为 8 字符: $code")
            // 短码格式：前 4 位必须是数字，后 3 位必须是字母（不含 0-9）
            assertTrue(n.take(4).all { it in '0'..'9' }, "前 4 位应为数字: $code")
            assertTrue(n.substring(4, 7).none { it in '0'..'9' }, "中 3 位应为字母: $code")
        }
    }

    @Test
    fun tamperedCodeFailsValidation() {
        val code = RendezvousCodeFormat.generate(ByteArray(8) { 7 })
        val n = RendezvousCodeFormat.normalize(code)
        // 改末位校验字符 → 必须失败
        val bad = n.dropLast(1) + if (n.last() == 'A') 'B' else 'A'
        assertFalse(RendezvousCodeFormat.validate(bad), "篡改校验位应失败: $bad")
        // 长度不足 / 前 4 位含字母 → 必须失败
        assertFalse(RendezvousCodeFormat.validate("123"))
        assertFalse(RendezvousCodeFormat.validate("A234-BCDE"))
    }

    @Test
    fun normalizeIsForgiving() {
        assertEquals("5689ABCD", RendezvousCodeFormat.normalize("5689-ABCD"))
        assertEquals("5689ABCD", RendezvousCodeFormat.normalize(" 5689 abcd "))
        assertEquals("5689ABCD", RendezvousCodeFormat.normalize("5689abcd"))
    }

    @Test
    fun looksLikeCodeDistinguishesPayloadFromCode() {
        // 完整连接串（QR JSON）不应被当成短码
        val payload = PairingCodec.toQrPayload(info())
        assertFalse(RendezvousCodeFormat.looksLikeCode(payload), "连接串不应被判为短码")
        // 合法短码应被识别（含分隔符与大小写）
        val code = RendezvousCodeFormat.generate(ByteArray(8) { 3 })
        assertTrue(RendezvousCodeFormat.looksLikeCode(code))
        assertTrue(RendezvousCodeFormat.looksLikeCode(code.lowercase()))
    }

    @Test
    fun allocateSkipsTakenCodesAndSucceeds() {
        val first = RendezvousCodeFormat.generate(ByteArray(8) { 1 })
        val taken = setOf(first)
        // 前两次撞码、第三次成功 → 应返回非 taken 的码
        var calls = 0
        val allocated = RendezvousCodeFormat.allocate(
            random = {
                calls++
                if (calls <= 2) ByteArray(8) { 1 } else ByteArray(8) { 9 }
            },
            taken = { it in taken },
        )
        assertFalse(allocated in taken, "不得返回已占用的码")
        assertEquals(3, calls, "应在前两次碰撞后第三次成功")
    }

    @Test
    fun allocateThrowsWhenSpaceExhausted() {
        var thrown = false
        try {
            RendezvousCodeFormat.allocate(
                random = { ByteArray(8) { 1 } },
                taken = { true },
                maxAttempts = 4,
            )
        } catch (e: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown, "短码空间耗尽应抛 IllegalStateException 而非静默失败")
    }

    @Test
    fun pairingInfoSurvivesWireRoundTrip() {
        val original = info()
        val wire = PairingInfoWire(json = PairingCodec.toQrPayload(original), code = "5689-ABCN")
        // 走 Wire（JSON 文本）后应能还原出等价 PairingInfo
        val restored = PairingCodec.fromQrPayload(wire.json)
        assertEquals(original, restored)
        assertEquals("5689-ABCN", wire.code)
    }

    @Test
    fun configNormalizesHostAndComputesBaseUrl() {
        val cfg = RendezvousConfig(serverHost = "  http://1.2.3.4/  ", serverPort = 9000)
        assertEquals("1.2.3.4", cfg.normalized().serverHost)
        assertEquals("http://1.2.3.4:9000", cfg.normalized().baseUrl())
    }

    @Test
    fun blankHostMeansDisabled() {
        assertFalse(RendezvousConfig().enabled, "未配置服务器时不应启用会合")
        assertNull(DefaultRendezvousClient.lookup("1234-ABCD", RendezvousConfig()), "未启用时应直接返回 null，不发网络请求")
        assertFalse(DefaultRendezvousClient.ping(RendezvousConfig()))
    }

    @Test
    fun defaultExpiryMatchesAppInfo() {
        // 短码有效期必须与 PairingInfo 默认有效期一致，否则两端对「何时过期」理解不同
        assertNotNull(AppInfo.DEFAULT_PAIRING_EXPIRES_SEC)
        assertEquals(180L, AppInfo.DEFAULT_PAIRING_EXPIRES_SEC)
    }
}

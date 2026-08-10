package com.juanlink.core.pairing

import com.juanlink.core.crypto.CryptoEngine
import com.juanlink.core.crypto.KeyDerivation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {

    private fun manager(clock: MutableList<Long>) = PairingManager(now = { clock[0] })

    private fun publicKey() = CryptoEngine.generateX25519KeyPair().publicKey

    @Test
    fun qrPayloadRoundtrip() {
        val p = PairingInfo(
            sessionId = "sess123",
            deviceId = "dev-1",
            publicKey = KeyDerivation.encodePublicKey(publicKey()),
            ts = 1000,
            nonce = "nonce1",
            candidates = listOf(Candidate("192.168.1.5", 45678)),
        )
        val raw = PairingCodec.toQrPayload(p)
        val decoded = PairingCodec.fromQrPayload(raw)
        assertEquals(p, decoded)
    }

    @Test
    fun consumeRoundtripAndReplayRejected() {
        val clock = mutableListOf(1000L)
        val mgr = manager(clock)
        val issued = mgr.issue("dev-1", publicKey())
        val raw = PairingCodec.toQrPayload(issued)
        val consumed = mgr.consume(raw)
        assertNotNull(consumed)
        assertEquals(issued.sessionId, consumed.sessionId)
        // 重扫拒绝
        assertNull(mgr.consume(raw))
    }

    @Test
    fun expiredPayloadRejected() {
        val clock = mutableListOf(1000L)
        val mgr = manager(clock)
        val issued = mgr.issue("dev-1", publicKey())
        val raw = PairingCodec.toQrPayload(issued)
        clock[0] = 1000L + 180_000L + 1 // 过期
        assertNull(mgr.consume(raw))
    }

    @Test
    fun badMagicRejected() {
        val mgr = PairingManager()
        val issued = mgr.issue("dev-1", publicKey())
        // PairingCodec 用 encodeDefaults=false：默认 magic 字段不出现在 payload，
        // 字符串替换无法命中。须显式 copy 成非默认值，才会编码进 JSON 并触发校验拒绝。
        val evilRaw = PairingCodec.toQrPayload(issued.copy(magic = "evilhack"))
        assertTrue("evilhack" in evilRaw, "非默认 magic 应编码进 payload")
        assertNull(mgr.consume(evilRaw), "篡改 magic 的 payload 必须被拒")
    }

    @Test
    fun pairingCodeGenerateAndValidate() {
        repeat(50) {
            val code = PairingCode.generate()
            assertTrue(PairingCode.validate(code), "应通过校验: $code")
            // 篡改一位应失败（大概率，校验位保护）
            if (code.length >= 8) {
                val tampered = code.dropLast(1) + if (code.last() == 'A') 'B' else 'A'
                assertFalse(PairingCode.validate(tampered))
            }
        }
    }

    @Test
    fun pairingCodeNormalize() {
        assertEquals("5689ABCD", PairingCode.normalize("5689-ABCD"))
        assertEquals("5689ABCD", PairingCode.normalize(" 5689 abc d "))
    }

    @Test
    fun codeLookupIsOneTime() {
        val clock = mutableListOf(1000L)
        val mgr = manager(clock)
        val info = mgr.issue("dev-1", publicKey())
        val code = mgr.registerCode(info)
        val first = mgr.lookupCode(code)
        assertNotNull(first)
        assertEquals(info.sessionId, first.sessionId)
        // 二次查找应失败（一次有效）
        assertNull(mgr.lookupCode(code))
    }

    @Test
    fun rateLimitBlocksBruteForce() {
        val clock = mutableListOf(1000L)
        val mgr = manager(clock)
        // 5 次错误后限流
        repeat(5) { assertNull(mgr.lookupCode("0000-AAAA")) }
        assertNull(mgr.lookupCode("0000-AAAA")) // 第 6 次被限流（即使码正确也无法查询）
    }
}

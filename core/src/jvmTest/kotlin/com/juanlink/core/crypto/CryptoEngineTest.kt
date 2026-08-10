package com.juanlink.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoEngineTest {

    @Test
    fun x25519SharedSecretIsSymmetric() {
        val a = CryptoEngine.generateX25519KeyPair()
        val b = CryptoEngine.generateX25519KeyPair()
        val s1 = CryptoEngine.computeSharedSecret(a.privateKey, b.publicKey)
        val s2 = CryptoEngine.computeSharedSecret(b.privateKey, a.publicKey)
        assertContentEquals(s1, s2)
        assertEquals(32, s1.size)
    }

    @Test
    fun x25519DifferentPeersGiveDifferentSecrets() {
        val a = CryptoEngine.generateX25519KeyPair()
        val b = CryptoEngine.generateX25519KeyPair()
        val c = CryptoEngine.generateX25519KeyPair()
        val sab = CryptoEngine.computeSharedSecret(a.privateKey, b.publicKey)
        val sac = CryptoEngine.computeSharedSecret(a.privateKey, c.publicKey)
        assertFalse(sab.contentEquals(sac))
    }

    @Test
    fun x25519MatchesRfc7748TestVector() {
        // RFC 7748 §5.2 官方测试向量（little-endian 字节序列）。
        // 此向量同时锁死跨平台字节序：JVM 导出/解析 X25519 密钥必须为 little-endian，
        // 与 Android Conscrypt DER 一致（此前 JVM 用 big-endian，跨平台 ECDH 密钥不一致）。
        val aPriv = h("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bPub = h("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expected = h("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertContentEquals(expected, CryptoEngine.computeSharedSecret(aPriv, bPub))
    }

    @Test
    fun hkdfSha256MatchesRfc5869TestVector() {
        // RFC 5869 测试用例 1（SHA-256）
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())
        val okm = CryptoEngine.hkdfSha256(ikm, salt, info, 42)
        val expected = b(
            0x3c, 0xb2, 0x5f, 0x25, 0xfa, 0xac, 0xd5, 0x7a, 0x90, 0x43, 0x4f, 0x64, 0xd0, 0x36, 0x2f, 0x2a,
            0x2d, 0x2d, 0x0a, 0x90, 0xcf, 0x1a, 0x5a, 0x4c, 0x5d, 0xb0, 0x2d, 0x56, 0xec, 0xc4, 0xc5, 0xbf,
            0x34, 0x00, 0x72, 0x08, 0xd5, 0xb8, 0x87, 0x18, 0x58, 0x65,
        )
        assertContentEquals(expected, okm)
    }

    @Test
    fun aesGcmRoundtrip() {
        val key = CryptoEngine.randomBytes(32)
        val iv = CryptoEngine.randomBytes(12)
        val aad = "session-1".encodeToByteArray()
        val plain = "婵娟实时协作数据".encodeToByteArray()
        val ct = CryptoEngine.aes256GcmEncrypt(key, iv, aad, plain)
        assertNotEquals(plain.contentToString(), ct.contentToString())
        val de = CryptoEngine.aes256GcmDecrypt(key, iv, aad, ct)
        assertNotNull(de)
        assertContentEquals(plain, de)
    }

    @Test
    fun aesGcmTamperedCiphertextFails() {
        val key = CryptoEngine.randomBytes(32)
        val iv = CryptoEngine.randomBytes(12)
        val aad = byteArrayOf(1, 2, 3)
        val ct = CryptoEngine.aes256GcmEncrypt(key, iv, aad, "hello".encodeToByteArray())
        ct[ct.size / 2] = (ct[ct.size / 2].toInt() xor 0x01).toByte()
        assertNull(CryptoEngine.aes256GcmDecrypt(key, iv, aad, ct))
    }

    @Test
    fun aesGcmWrongAadFails() {
        val key = CryptoEngine.randomBytes(32)
        val iv = CryptoEngine.randomBytes(12)
        val ct = CryptoEngine.aes256GcmEncrypt(key, iv, byteArrayOf(1), "x".encodeToByteArray())
        assertNull(CryptoEngine.aes256GcmDecrypt(key, iv, byteArrayOf(2), ct))
    }

    /** Int 序列转 ByteArray（规避 >127 字面量） */
    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /** hex 字符串转 ByteArray（RFC 测试向量） */
    private fun h(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    @Test
    fun constantTimeEqualsWorks() {
        assertTrue(CryptoEngine.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(CryptoEngine.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(CryptoEngine.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
    }

    @Test
    fun randomBytesAreDistinctAndSized() {
        val a = CryptoEngine.randomBytes(64)
        val b = CryptoEngine.randomBytes(64)
        assertEquals(64, a.size)
        assertFalse(a.contentEquals(b))
    }
}

class SessionCryptoTest {

    private fun fresh(): SessionCrypto {
        val shared = CryptoEngine.randomBytes(32)
        val keys = KeyDerivation.deriveKeys(shared, "sess-1", CryptoEngine.randomBytes(8))
        return SessionCrypto("sess-1", keys)
    }

    @Test
    fun encryptDecryptRoundtrip() {
        val crypto = fresh()
        val plain = "操作级同步载荷".encodeToByteArray()
        val payload = crypto.encrypt(plain)
        val de = crypto.decrypt(payload)
        assertNotNull(de)
        assertContentEquals(plain, de)
    }

    @Test
    fun replayOfSamePayloadRejected() {
        val crypto = fresh()
        val payload = crypto.encrypt(byteArrayOf(1, 2, 3))
        assertNotNull(crypto.decrypt(payload))
        // 同一载荷重放
        assertNull(crypto.decrypt(payload))
    }

    @Test
    fun outOfOrderWithinWindowAccepted() {
        val crypto = fresh()
        val p1 = crypto.encrypt(byteArrayOf(1))
        val p3 = crypto.encrypt(byteArrayOf(3))
        // 打乱顺序：先解 p3 再解 p1（窗口内乱序允许）
        assertNotNull(crypto.decrypt(p3))
        assertNotNull(crypto.decrypt(p1))
    }

    @Test
    fun tamperedCiphertextRejected() {
        val crypto = fresh()
        val payload = crypto.encrypt(byteArrayOf(9, 9, 9))
        val forged = payload.copy(ciphertext = payload.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() })
        assertNull(crypto.decrypt(forged))
    }

    @Test
    fun twoSendersKeysAreIsolated() {
        // 不同会话密钥互不可解
        val a = fresh()
        val b = fresh()
        val payload = a.encrypt(byteArrayOf(1))
        assertNull(b.decrypt(payload))
    }
}

class ReplayGuardTest {

    @Test
    fun acceptsNewSequence() {
        val g = ReplayGuard(64)
        assertTrue(g.checkAndMark(1))
        assertTrue(g.checkAndMark(2))
    }

    @Test
    fun rejectsReplayedSequence() {
        val g = ReplayGuard(64)
        assertTrue(g.checkAndMark(5))
        assertFalse(g.checkAndMark(5))
    }

    @Test
    fun rejectsOldSequenceOutOfWindow() {
        val g = ReplayGuard(64)
        for (i in 1L..100L) assertTrue(g.checkAndMark(i))
        // 已出窗口的旧序号应被拒绝
        assertFalse(g.checkAndMark(1))
        assertFalse(g.checkAndMark(40))
    }

    @Test
    fun acceptsOutOfOrderWithinWindow() {
        val g = ReplayGuard(64)
        assertTrue(g.checkAndMark(100))
        assertTrue(g.checkAndMark(99))
        assertTrue(g.checkAndMark(101))
    }

    @Test
    fun rejectsNegative() {
        val g = ReplayGuard(64)
        assertFalse(g.checkAndMark(-1))
    }

    @Test
    fun windowModuloCollisionHandled() {
        // 相差恰好 windowSize 的序号共享槽位，旧者已出窗应拒绝，新者接受
        val g = ReplayGuard(64)
        assertTrue(g.checkAndMark(0))
        assertFalse(g.checkAndMark(0))
        assertTrue(g.checkAndMark(64))
    }
}

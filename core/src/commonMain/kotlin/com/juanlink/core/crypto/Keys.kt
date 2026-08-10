package com.juanlink.core.crypto

import com.juanlink.core.util.Base64Url

/** X25519 原始密钥对 */
data class KeyPairRaw(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is KeyPairRaw && privateKey.contentEquals(other.privateKey) && publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = privateKey.contentHashCode() * 31 + publicKey.contentHashCode()
}

/**
 * 会话密钥三件套，由 HKDF 从 ECDH 共享秘密派生。
 *
 * - [encKey] AES-256 数据加密密钥（32B）
 * - [macKey] 包级 HMAC 完整性密钥（32B）
 * - [ivBase] 12B IV 基值，逐包与 seq 异或生成唯一 IV
 */
data class SessionKeys(
    val encKey: ByteArray,
    val macKey: ByteArray,
    val ivBase: ByteArray,
)

/** 派生上下文：保证「同秘密 + 同会话 + 同方向」才产生相同密钥 */
object KeyDerivation {
    const val INFO_ENCRYPT = "juanlink:enc:v1"
    const val INFO_MAC = "juanlink:mac:v1"
    const val INFO_IV = "juanlink:iv:v1"

    /**
     * RFC 5869 HKDF 主派生。salt 使用 sessionId || nonce，
     * 使每会话密钥唯一（会话隔离）。
     */
    fun deriveKeys(sharedSecret: ByteArray, sessionId: String, nonce: ByteArray): SessionKeys {
        val salt = sessionId.encodeToByteArray() + nonce
        val prk = CryptoEngine.hkdfSha256(sharedSecret, salt, "juanlink:master".encodeToByteArray(), 32)
        return SessionKeys(
            encKey = CryptoEngine.hkdfSha256(prk, salt, INFO_ENCRYPT.encodeToByteArray(), 32),
            macKey = CryptoEngine.hkdfSha256(prk, salt, INFO_MAC.encodeToByteArray(), 32),
            ivBase = CryptoEngine.hkdfSha256(prk, salt, INFO_IV.encodeToByteArray(), 12),
        )
    }

    /** 公钥编码为 Base64Url（进二维码/配对信息） */
    fun encodePublicKey(pk: ByteArray): String = Base64Url.encode(pk)

    /** 从 Base64Url 还原公钥 */
    fun decodePublicKey(text: String): ByteArray = Base64Url.decode(text)
}

/** 加密后的传输载荷（seq + iv + 密文 + 完整性 MAC） */
data class EncryptedPayload(
    val seq: Long,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val mac: ByteArray,
)

/**
 * 会话级加密器：
 * - IV = ivBase ⊕ seq（保证唯一性，防重放）
 * - AAD = sessionId || seq（绑定会话与序号）
 * - 密文 = AES-256-GCM(encKey, iv, aad, plaintext)，含 16B tag
 * - 包级 HMAC(macKey, seq||iv||ciphertext) 二次认证
 * - 接收侧 ReplayGuard 滑动窗口防重放
 */
class SessionCrypto(
    val sessionId: String,
    keys: SessionKeys,
    private val replayGuard: ReplayGuard = ReplayGuard(ReplayGuard.DEFAULT_WINDOW),
) {
    private val encKey: ByteArray = keys.encKey
    private val macKey: ByteArray = keys.macKey
    private val ivBase: ByteArray = keys.ivBase

    /** 发送计数器：全连接单调递增，从 1 起（0 保留给握手） */
    private var sendCounter: Long = 1L

    val sendSeq: Long get() = sendCounter

    fun nextSeq(): Long {
        val s = sendCounter
        sendCounter++
        return s
    }

    /** 加密一段业务明文 */
    fun encrypt(plaintext: ByteArray): EncryptedPayload {
        val seq = nextSeq()
        val iv = deriveIv(seq)
        val aad = deriveAad(seq)
        val ciphertext = CryptoEngine.aes256GcmEncrypt(encKey, iv, aad, plaintext)
        val mac = CryptoEngine.hmacSha256(macKey, macInput(seq, iv, ciphertext))
        return EncryptedPayload(seq, iv, ciphertext, mac)
    }

    /**
     * 解密一段载荷。三重防线：防重放（窗口）→ MAC 校验 → GCM 认证解密。
     * 任一步失败返回 null，调用方丢弃。
     */
    fun decrypt(payload: EncryptedPayload): ByteArray? {
        if (!replayGuard.checkAndMark(payload.seq)) return null
        val expectedMac = CryptoEngine.hmacSha256(macKey, macInput(payload.seq, payload.iv, payload.ciphertext))
        if (!CryptoEngine.constantTimeEquals(expectedMac, payload.mac)) return null
        return CryptoEngine.aes256GcmDecrypt(encKey, payload.iv, deriveAad(payload.seq), payload.ciphertext)
    }

    private fun deriveIv(seq: Long): ByteArray {
        val iv = ivBase.copyOf()
        var v = seq
        for (i in iv.size - 1 downTo 0) {
            iv[i] = (iv[i].toInt() xor (v and 0xFF).toInt()).toByte()
            v = v ushr 8
        }
        return iv
    }

    private fun deriveAad(seq: Long): ByteArray =
        sessionId.encodeToByteArray() + byteArrayOf(
            (seq ushr 56).toByte(), (seq ushr 48).toByte(), (seq ushr 40).toByte(), (seq ushr 32).toByte(),
            (seq ushr 24).toByte(), (seq ushr 16).toByte(), (seq ushr 8).toByte(), seq.toByte(),
        )

    private fun macInput(seq: Long, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val head = byteArrayOf(
            (seq ushr 56).toByte(), (seq ushr 48).toByte(), (seq ushr 40).toByte(), (seq ushr 32).toByte(),
            (seq ushr 24).toByte(), (seq ushr 16).toByte(), (seq ushr 8).toByte(), seq.toByte(),
        )
        return head + iv + ciphertext
    }

    fun copyOf(): SessionCrypto = SessionCrypto(sessionId, SessionKeys(encKey, macKey, ivBase), ReplayGuard(ReplayGuard.DEFAULT_WINDOW))
}

/**
 * 防重放滑动窗口。
 *
 * 接受规则：seq 单调推进，允许窗口内乱序，拒绝「已见」与「过旧」序号。
 * 用「槽位存完整 seq」的环形表，窗口内两个相差恰好 windowSize 的序号
 * 共用一个槽位时，较旧者必然已出窗口，语义无歧义。
 */
class ReplayGuard(private val windowSize: Int) {

    private var highest: Long = -1L
    private val slots = LongArray(windowSize) { -1L }

    /** @return true 表示可接受（并已记录），false 表示重放/过旧应丢弃 */
    fun checkAndMark(seq: Long): Boolean {
        if (seq < 0) return false
        if (seq <= highest - windowSize) return false
        val slot = (seq % windowSize).toInt()
        if (slots[slot] == seq) return false
        if (seq > highest) {
            highest = seq
            val floor = seq - windowSize
            for (i in slots.indices) {
                if (slots[i] < floor) slots[i] = -1L
            }
        }
        slots[slot] = seq
        return true
    }

    companion object {
        const val DEFAULT_WINDOW: Int = 4096
    }
}

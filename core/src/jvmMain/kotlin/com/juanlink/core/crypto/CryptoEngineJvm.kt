package com.juanlink.core.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM / macOS 平台加密实现（JDK 21 内置 JCA，零第三方依赖）。
 */
actual object CryptoEngine {

    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_BITS = 256

    /**
     * 共享强随机源：仅初始化一次并复用。
     * （getInstanceStrong 每次调用都触发熵采集，在 UI 线程可能阻塞导致白屏）
     */
    private val secureRandom: SecureRandom by lazy {
        SecureRandom.getInstanceStrong()
    }

    actual fun randomBytes(n: Int): ByteArray {
        if (n < 0) throw IllegalArgumentException("n 不能为负: $n")
        val bytes = ByteArray(n)
        synchronized(secureRandom) { secureRandom.nextBytes(bytes) }
        return bytes
    }

    actual fun generateX25519KeyPair(): KeyPairRaw {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val pair = kpg.generateKeyPair()
        // 与 Android（Conscrypt DER）完全一致：X25519 的 32B 原始标量/u 坐标位于
        // DER 编码末尾（RFC 8410 固定布局），且为 little-endian。此前用 BigInteger→大端
        // 导出，导致跨平台 ECDH 共享密钥不一致 → 握手 MAC 校验失败（RFC 7748 §5.2 实测）。
        return KeyPairRaw(
            privateKey = extractLast32(pair.private.encoded, "X25519 私钥"),
            publicKey = extractLast32(pair.public.encoded, "X25519 公钥"),
        )
    }

    /** X25519 的 32 字节原始密钥位于 DER 编码末尾（与 Android CryptoEngine 一致） */
    private fun extractLast32(encoded: ByteArray, what: String): ByteArray {
        if (encoded.size < 32) throw IllegalStateException("$what 编码异常（${encoded.size}B）")
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    actual fun computeSharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("X25519")
        // 私钥标量：little-endian（RFC 7748），XECPrivateKeySpec 期望该格式。
        val privKey = kf.generatePrivate(XECPrivateKeySpec(NamedParameterSpec.X25519, privateKey))
        // 公钥 u：peerPublicKey 为 little-endian 协议字节，先反转成 big-endian 再取数学值。
        // 直接 BigInteger(1, LE字节) 会把端序解析错 → 共享密钥与对端不一致。
        val pubKey = kf.generatePublic(XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger(1, peerPublicKey.reversedArray())))
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(privKey)
        ka.doPhase(pubKey, true)
        return ka.generateSecret()
    }

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    actual fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * HKDF-SHA256（RFC 5869）。
     * extract: PRK = HMAC(salt, IKM)；salt 为空时使用 32B 零盐。
     */
    actual fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        require(outLen > 0) { "outLen 必须为正" }
        require(outLen <= 255 * 32) { "outLen 超出 HKDF 上限" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val okm = ByteArray(outLen)
        var t = ByteArray(0)
        var written = 0
        var counter = 1
        val macExpand = Mac.getInstance("HmacSHA256")
        macExpand.init(SecretKeySpec(prk, "HmacSHA256"))
        while (written < outLen) {
            macExpand.reset()
            macExpand.update(t)
            macExpand.update(info)
            macExpand.update(counter.toByte())
            t = macExpand.doFinal()
            val n = minOf(t.size, outLen - written)
            System.arraycopy(t, 0, okm, written, n)
            written += n
            counter++
        }
        return okm
    }

    actual fun aes256GcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    actual fun aes256GcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? =
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        }.getOrNull()

    actual fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

}

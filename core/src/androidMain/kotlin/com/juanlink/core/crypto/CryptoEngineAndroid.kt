package com.juanlink.core.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android 平台加密实现。
 *
 * - X25519：minSdk 33+ 平台 JCA 原生支持（XDH/XEC，无需 Conscrypt 注入）。
 * - AES/GCM、HmacSHA256、SHA-256：Android 平台 JCA 原生支持。
 * - SecureRandom 用默认构造（getInstanceStrong 在部分 Android 版本会阻塞或不可用）。
 */
actual object CryptoEngine {

    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_BITS = 256

    private val secureRandom: SecureRandom by lazy { SecureRandom() }

    actual fun randomBytes(n: Int): ByteArray {
        if (n < 0) throw IllegalArgumentException("n 不能为负: $n")
        val bytes = ByteArray(n)
        synchronized(secureRandom) { secureRandom.nextBytes(bytes) }
        return bytes
    }

    actual fun generateX25519KeyPair(): KeyPairRaw {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val pair = kpg.generateKeyPair()
        // Android 系统 Conscrypt 的 X25519 密钥类刻意不实现 java.security.interfaces.XEC*
        // 接口（OpenSSLX25519PublicKey 等），强转会抛 ClassCastException。改为从密钥的
        // ASN.1 编码（RFC 8410）提取 32 字节原始数据：X25519 的公钥 u 与私钥标量都固定
        // 位于 DER 编码末尾 32 字节。
        return KeyPairRaw(
            privateKey = extractLast32(pair.private.encoded, "X25519 私钥"),
            publicKey = extractLast32(pair.public.encoded, "X25519 公钥"),
        )
    }

    /** X25519 的 32 字节原始密钥位于 DER 编码末尾（RFC 8410 固定布局，不依赖 XEC 接口） */
    private fun extractLast32(encoded: ByteArray, what: String): ByteArray {
        if (encoded.size < 32) throw IllegalStateException("$what 编码异常（${encoded.size}B）")
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    actual fun computeSharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val kf = KeyFactory.getInstance("X25519")
        // Android 系统 Conscrypt 的 XDH KeyFactory 对 java.security.spec.XEC*Spec 的
        // instanceof 检查有坑（抛 "was XECPrivateKeySpec"），明确只接受 PKCS8/X509 编码
        // spec。这里把 32B 原始密钥按 RFC 8410 包成标准 ASN.1 再交给 KeyFactory。
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(encodeX25519PrivateKey(privateKey)))
        val pubKey = kf.generatePublic(X509EncodedKeySpec(encodeX25519PublicKey(peerPublicKey)))
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(privKey)
        ka.doPhase(pubKey, true)
        return ka.generateSecret()
    }

    /**
     * X25519 私钥标量 → PKCS#8。设备实测 Android 原生格式：
     * SEQUENCE{INT 0, SEQUENCE{OID 1.3.101.110}, OCTET STRING{ OCTET STRING{scalar} }}
     * 即：算法参数无 NULL，私钥为双层 OCTET STRING（04 22 包 04 20 + 32B），共 48B。
     * （与 JDK 的单层/带 NULL 格式不同，Conscrypt 只接受本格式。）
     */
    private fun encodeX25519PrivateKey(scalar: ByteArray): ByteArray {
        require(scalar.size == 32) { "X25519 私钥必须为 32B" }
        val out = ByteArray(48)
        out[0] = 0x30; out[1] = 0x2E.toByte()
        out[2] = 0x02; out[3] = 0x01; out[4] = 0x00
        out[5] = 0x30; out[6] = 0x05
        out[7] = 0x06; out[8] = 0x03; out[9] = 0x2B.toByte(); out[10] = 0x65; out[11] = 0x6E
        out[12] = 0x04; out[13] = 0x22
        out[14] = 0x04; out[15] = 0x20
        System.arraycopy(scalar, 0, out, 16, 32)
        return out
    }

    /**
     * X25519 公钥 u → SPKI：SEQUENCE{SEQUENCE{OID 1.3.101.110}, BIT STRING{u}}
     * 设备实测 Android 原生公钥 SPKI 不带 NULL 参数（44B），与 Conscrypt 一致。
     */
    private fun encodeX25519PublicKey(u: ByteArray): ByteArray {
        require(u.size == 32) { "X25519 公钥必须为 32B" }
        val out = ByteArray(44)
        out[0] = 0x30; out[1] = 0x2A.toByte()
        out[2] = 0x30; out[3] = 0x05
        out[4] = 0x06; out[5] = 0x03; out[6] = 0x2B.toByte(); out[7] = 0x65; out[8] = 0x6E
        out[9] = 0x03; out[10] = 0x21; out[11] = 0x00
        System.arraycopy(u, 0, out, 12, 32)
        return out
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

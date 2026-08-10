package com.juanlink.core.crypto

/**
 * 平台加密引擎抽象（expect）。
 *
 * 各平台 actual：
 * - JVM/macOS：JDK 21 内置 JCA（X25519 / AES-GCM / HmacSHA256）
 * - Android：minSdk 33+ 平台 JCA 原生支持（XDH/XEC）
 * - iOS：ionspin multiplatform-crypto（libsodium）
 *
 * 业务代码只依赖本接口，不感知平台差异。
 */
expect object CryptoEngine {

    /** 密码学安全随机字节 */
    fun randomBytes(n: Int): ByteArray

    /** 生成 X25519 临时密钥对（32B 原始密钥） */
    fun generateX25519KeyPair(): KeyPairRaw

    /** ECDH：由本端私钥 + 对端公钥计算共享秘密（32B） */
    fun computeSharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray

    /** HMAC-SHA256 */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** SHA-256 摘要（图片分块校验等） */
    fun sha256(data: ByteArray): ByteArray

    /** HKDF-SHA256（RFC 5869 extract + expand） */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int): ByteArray

    /** AES-256-GCM 加密，返回 [16B GCM tag 拼接在末尾] 的密文 */
    fun aes256GcmEncrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** AES-256-GCM 解密；认证失败返回 null */
    fun aes256GcmDecrypt(key: ByteArray, iv: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray?

    /** 常量时间比较，防时序侧信道 */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean
}

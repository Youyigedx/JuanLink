package com.juanlink.core.session

import com.juanlink.core.crypto.CryptoEngine
import com.juanlink.core.crypto.KeyDerivation
import com.juanlink.core.crypto.KeyPairRaw
import com.juanlink.core.crypto.SessionCrypto
import com.juanlink.core.crypto.SessionKeys
import com.juanlink.core.protocol.HandshakeResponse
import com.juanlink.core.protocol.Hello

/**
 * 握手协议（带外信令内完成 ECDH 密钥交换，全程无服务器）。
 *
 * 流程：
 * 1. 发起方 A（二维码持有方）：生成临时 X25519 密钥对，公钥进二维码/配对信息。
 * 2. 响应方 B（扫码方）：连接 A，发送 Hello（含 B 的公钥）。
 * 3. A 收到 Hello：X25519(私A, pkB) → 派生会话密钥 → 回 HandshakeResponse(公A + MAC)。
 * 4. B 收到响应：X25519(私B, pkA) → 派生会话密钥 → 常量时间校验 MAC，双向认证达成。
 *
 * MAC = HMAC(macKey, pkB || pkA || sessionId || nonce)，其中 nonce 来自带外信令，
 * 防止中间人重放已捕获的握手响应。
 */
object HandshakeProtocol {

    /** B（扫码方）构造 Hello */
    fun buildHello(
        messageId: Long,
        protocolVersion: Int,
        deviceId: String,
        sessionId: String,
        keyPair: KeyPairRaw,
        role: String,
    ): Hello = Hello(
        messageId = messageId,
        protocolVersion = protocolVersion,
        deviceId = deviceId,
        sessionId = sessionId,
        publicKey = keyPair.publicKey,
        nonce = ByteArray(0), // nonce 经带外（二维码）传递，不在此重复
        role = role,
    )

    /** A（二维码持有方）收到 Hello 后构建响应并派生会话密钥 */
    fun buildResponse(
        hello: Hello,
        myKeyPair: KeyPairRaw,
        sessionId: String,
        nonce: ByteArray,
        messageId: Long,
    ): Pair<HandshakeResponse, SessionCrypto> {
        require(hello.sessionId == sessionId) { "会话 ID 不匹配" }
        val shared = CryptoEngine.computeSharedSecret(myKeyPair.privateKey, hello.publicKey)
        val keys = KeyDerivation.deriveKeys(shared, sessionId, nonce)
        val mac = computeMac(keys, hello.publicKey, myKeyPair.publicKey, sessionId, nonce)
        val response = HandshakeResponse(
            messageId = messageId,
            deviceId = hello.deviceId,
            sessionId = sessionId,
            publicKey = myKeyPair.publicKey,
            mac = mac,
        )
        return response to SessionCrypto(sessionId, keys)
    }

    /** B（扫码方）验证响应并派生会话密钥；验证失败返回 null */
    fun verifyResponse(
        hello: Hello,
        response: HandshakeResponse,
        myKeyPair: KeyPairRaw,
        sessionId: String,
        nonce: ByteArray,
    ): SessionCrypto? {
        if (response.sessionId != sessionId) return null
        val shared = CryptoEngine.computeSharedSecret(myKeyPair.privateKey, response.publicKey)
        val keys = KeyDerivation.deriveKeys(shared, sessionId, nonce)
        val expectedMac = computeMac(keys, hello.publicKey, response.publicKey, sessionId, nonce)
        if (!CryptoEngine.constantTimeEquals(expectedMac, response.mac)) return null
        return SessionCrypto(sessionId, keys)
    }

    private fun computeMac(
        keys: SessionKeys,
        pkB: ByteArray,
        pkA: ByteArray,
        sessionId: String,
        nonce: ByteArray,
    ): ByteArray {
        val data = pkB + pkA + sessionId.encodeToByteArray() + nonce
        return CryptoEngine.hmacSha256(keys.macKey, data)
    }
}

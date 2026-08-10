package com.juanlink.core.turn

import com.juanlink.core.crypto.CryptoEngine
import com.juanlink.core.transport.TurnRelayEndpoint
import com.juanlink.core.transport.TurnServer
import com.juanlink.core.turn.TurnProtocol.ParsedMessage
import com.juanlink.core.util.nowEpochMillis
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 有状态 TURN 事务客户端（RFC 5766 client）。绑定一个 DatagramSocket 连单台 TURN 服务器。
 *
 * 事务路由模型（与 UdpTransport 的「reader 启动前收 STUN」约束同源）：
 * - [route] 由上层读线程调用：把收到的 STUN 报文路由到挂起事务（txId 匹配则完成 future）。
 * - [allocate] 在 reader 启动前调用，自己 pump（receive+route）等响应；
 * - [createPermission] / [refresh] 在 reader 启动后调用（**非 reader 线程**，否则等自己路由会死锁），
 *   register + send 后阻塞等 future 由 reader 路由完成。
 *
 * Long-Term Credential：首次无凭据 Allocate → 401（带 REALM/NONCE）→ 缓存并带凭据重发；
 * 遇 438 Stale Nonce 更新后重试一次。
 */
class TurnClient(
    private val socket: DatagramSocket,
    val server: TurnServer,
) {
    private val pending = ConcurrentHashMap<String, CompletableFuture<ParsedMessage?>>()
    private val serverAddr by lazy { InetAddress.getByName(server.host) }

    // Long-Term Credential 缓存（401 学到；之后所有请求带）
    private var realm: String? = null
    private var nonce: String? = null
    private var key: ByteArray? = null

    /** 路由一个入站报文到挂起事务；返回 true 表示已被消费（非 Data Indication 的响应） */
    fun route(data: ByteArray, len: Int): Boolean {
        val msg = TurnProtocol.parse(data, len) ?: return false
        val f = pending.remove(keyOf(msg.txId)) ?: return false
        f.complete(msg)
        return true
    }

    /**
     * Allocate：无凭据 → 401 → 带凭据重发（438 重试一次）。reader 启动前调用（自己收包）。
     * @return 中继端点（含本端 relayed transport address）；失败/超时 null
     */
    fun allocate(timeoutMs: Long): TurnRelayEndpoint? {
        val deadline = nowEpochMillis() + timeoutMs

        val tx1 = CryptoEngine.randomBytes(12)
        val (k1, f1) = register(tx1)
        sendRaw(TurnProtocol.buildAllocateRequest(tx1))
        val r1 = pumpUntil(f1, deadline)
        if (r1 == null) { pending.remove(k1); return null }
        when {
            r1.type == TurnProtocol.successType(TurnProtocol.METHOD_ALLOCATE) -> return relayedOf(r1)
            errorCode(r1) in AUTH_CODES -> {
                applyAuth(r1)
                pending.remove(k1)
            }
            else -> { pending.remove(k1); return null }
        }

        // 带凭据重发（stale nonce 至多重试一次）
        for (attempt in 1..2) {
            val realm = realm ?: return null
            val nonce = nonce ?: return null
            val key = key ?: return null
            val txN = CryptoEngine.randomBytes(12)
            val (kN, fN) = register(txN)
            sendRaw(
                TurnProtocol.buildAuthenticatedRequest(
                    TurnProtocol.METHOD_ALLOCATE,
                    listOf(TurnProtocol.requestedTransportUdpAttribute()),
                    txN, server.username, realm, nonce, key,
                ),
            )
            val rN = pumpUntil(fN, deadline)
            if (rN == null) { pending.remove(kN); return null }
            pending.remove(kN)
            when {
                rN.type == TurnProtocol.successType(TurnProtocol.METHOD_ALLOCATE) -> return relayedOf(rN)
                errorCode(rN) == 438 -> applyAuth(rN) // Stale Nonce：更新重试
                else -> return null
            }
        }
        return null
    }

    /** CreatePermission（对 relayed peer 授权，Send 的前提）。reader 启动后非 reader 线程调用 */
    fun createPermission(peerHost: String, peerPort: Int, timeoutMs: Long): Boolean {
        val realm = realm ?: return false
        val nonce = nonce ?: return false
        val key = key ?: return false
        val txId = CryptoEngine.randomBytes(12)
        val peerAttr = TurnProtocol.encodeXorAddressAttr(TurnProtocol.ATTR_XOR_PEER_ADDRESS, peerHost, peerPort, txId) ?: return false
        val req = TurnProtocol.buildAuthenticatedRequest(
            TurnProtocol.METHOD_CREATE_PERMISSION, listOf(peerAttr), txId, server.username, realm, nonce, key,
        )
        val (k, f) = register(txId)
        sendRaw(req)
        val r = awaitFuture(f, timeoutMs)
        pending.remove(k)
        return r != null && r.type == TurnProtocol.successType(TurnProtocol.METHOD_CREATE_PERMISSION)
    }

    /** Refresh：续期 allocation。reader 启动后非 reader 线程调用 */
    fun refresh(lifetimeSec: Int, timeoutMs: Long): Boolean {
        val realm = realm ?: return false
        val nonce = nonce ?: return false
        val key = key ?: return false
        val txId = CryptoEngine.randomBytes(12)
        val req = TurnProtocol.buildAuthenticatedRequest(
            TurnProtocol.METHOD_REFRESH,
            listOf(TurnProtocol.u32Attribute(TurnProtocol.ATTR_LIFETIME, lifetimeSec.toLong())),
            txId, server.username, realm, nonce, key,
        )
        val (k, f) = register(txId)
        sendRaw(req)
        val r = awaitFuture(f, timeoutMs)
        pending.remove(k)
        return r != null && r.type == TurnProtocol.successType(TurnProtocol.METHOD_REFRESH)
    }

    /** Send Indication：数据面（无需响应）。物理发给 TURN 服务器，对端地址进 XOR-PEER-ADDRESS */
    fun sendData(peerHost: String, peerPort: Int, payload: ByteArray) {
        val txId = CryptoEngine.randomBytes(12)
        val ind = TurnProtocol.buildSendIndication(txId, peerHost, peerPort, payload) ?: return
        sendRaw(ind)
    }

    // ---------------------------------------------------------------- 内部

    private fun register(txId: ByteArray): Pair<String, CompletableFuture<ParsedMessage?>> {
        val key = keyOf(txId)
        val f = CompletableFuture<ParsedMessage?>()
        pending[key] = f
        return key to f
    }

    /** 无 reader 阶段专用：自己 receive + route，直到 future 完成或超时 */
    private fun pumpUntil(future: CompletableFuture<ParsedMessage?>, deadline: Long): ParsedMessage? {
        val buf = ByteArray(8192)
        val oldTimeout = socket.soTimeout
        socket.soTimeout = 150
        try {
            while (nowEpochMillis() < deadline) {
                future.getNow(null)?.let { return it }
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(pkt)
                    route(buf, pkt.length)
                } catch (e: SocketTimeoutException) { /* 继续轮询 */ }
            }
        } finally {
            socket.soTimeout = oldTimeout
        }
        return future.getNow(null)
    }

    private fun awaitFuture(future: CompletableFuture<ParsedMessage?>, timeoutMs: Long): ParsedMessage? =
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        }

    private fun sendRaw(msg: ByteArray) {
        runCatching { socket.send(DatagramPacket(msg, msg.size, serverAddr, server.port)) }
    }

    private fun applyAuth(msg: ParsedMessage) {
        val r = TurnProtocol.stringAttr(msg, TurnProtocol.ATTR_REALM) ?: return
        val n = TurnProtocol.stringAttr(msg, TurnProtocol.ATTR_NONCE) ?: return
        realm = r
        nonce = n
        key = TurnProtocol.credentialKey(server.username, r, server.password)
    }

    private fun relayedOf(msg: ParsedMessage): TurnRelayEndpoint? {
        val attr = TurnProtocol.attr(msg, TurnProtocol.ATTR_XOR_RELAYED_ADDRESS) ?: return null
        val (h, p) = TurnProtocol.decodeXorAddress(attr, msg.txId) ?: return null
        return TurnRelayEndpoint(server.host, server.port, h, p)
    }

    private fun errorCode(msg: ParsedMessage): Int =
        TurnProtocol.attr(msg, TurnProtocol.ATTR_ERROR_CODE)?.let { TurnProtocol.errorCodeOf(it) } ?: -1

    private fun keyOf(txId: ByteArray): String = txId.joinToString("") { "%02x".format(it) }

    private companion object {
        /** 需要带凭据重发的错误码：401 Unauthorized、438 Stale Nonce */
        val AUTH_CODES = setOf(401, 438)
    }
}

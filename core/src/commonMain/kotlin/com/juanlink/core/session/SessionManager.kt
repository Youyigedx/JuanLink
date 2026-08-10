package com.juanlink.core.session

import com.juanlink.core.AppInfo
import com.juanlink.core.canvas.OpSyncEngine
import com.juanlink.core.crypto.CryptoEngine
import com.juanlink.core.crypto.EncryptedPayload
import com.juanlink.core.crypto.KeyPairRaw
import com.juanlink.core.crypto.SessionCrypto
import com.juanlink.core.pairing.Candidate
import com.juanlink.core.pairing.PairingInfo
import com.juanlink.core.pairing.PairingManager
import com.juanlink.core.protocol.Ack
import com.juanlink.core.protocol.Bye
import com.juanlink.core.protocol.EncryptedFrame
import com.juanlink.core.protocol.EnvelopeCodec
import com.juanlink.core.protocol.HandshakeResponse
import com.juanlink.core.protocol.Heartbeat
import com.juanlink.core.protocol.Hello
import com.juanlink.core.protocol.OpEnvelope
import com.juanlink.core.protocol.OpTransport
import com.juanlink.core.protocol.Ping
import com.juanlink.core.protocol.Pong
import com.juanlink.core.protocol.QualityProbe
import com.juanlink.core.protocol.Resync
import com.juanlink.core.protocol.ResyncDone
import com.juanlink.core.protocol.SyncRequest
import com.juanlink.core.protocol.WireMessage
import com.juanlink.core.transport.DisconnectReason
import com.juanlink.core.transport.PeerInfo
import com.juanlink.core.transport.Transport
import com.juanlink.core.transport.TransportListener
import com.juanlink.core.util.Base64Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 会话编排器：驱动握手、加密帧路由、状态机。
 *
 * 角色：
 * - Initiator（二维码持有方）：TCP 监听，等待扫码方接入，完成密钥交换
 * - Responder（扫码/配对码输入方）：连接 Initiator，发起 Hello，校验响应
 *
 * 加密帧解密后的明文（序列化 OpEnvelope）经 [onEncryptedPlain] 上抛给操作同步层。
 */
class SessionManager(
    private val deviceId: String,
    private val transport: Transport,
) : OpTransport {
    enum class Role { Initiator, Responder }

    private val _state = MutableStateFlow(SessionState.Idle)
    val state: StateFlow<SessionState> get() = _state

    /** 操作同步引擎（接线后驱动操作级同步） */
    var opEngine: OpSyncEngine? = null

    /** 解密后的明文载荷回调（未接线 opEngine 时使用） */
    var onEncryptedPlain: (ByteArray) -> Unit = {}

    /** 状态变化回调 */
    var onStateChanged: (SessionState) -> Unit = {}

    /** 质量探测采样回调 (seq, sendTs) */
    var onQualityProbe: (Int, Long) -> Unit = { _, _ -> }

    /** 收到对端 Pong 回显回调（喂给 QualityMonitor） */
    var onPongReceived: (seq: Int, ts: Long) -> Unit = { _, _ -> }

    /** 断线保护状态机（只负责"何时提示暂停"；不再自动关闭） */
    private val sessionScope = CoroutineScope(SupervisorJob())
    val disconnectProtector = DisconnectProtector(
        scope = sessionScope,
        onPaused = { setState(SessionState.Paused) },
    )

    fun closeScope() = sessionScope.cancel()

    // ------------------------------------------------------------ 重连（Responder）

    /** 首次加入时的候选（重连循环复用同一组） */
    private var responderPeers: List<PeerInfo>? = null

    /** 用户主动断开标记：置位后停止一切重连尝试 */
    private val explicitlyDisconnected = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 重连循环任务（单实例，防并发叠加） */
    private var reconnectJob: Job? = null

    var role: Role? = null
        private set

    val isConnected: Boolean get() = _state.value == SessionState.Connected

    private var myKeyPair: KeyPairRaw? = null
    private var sessionId: String = ""
    private var nonce: ByteArray = ByteArray(0)
    private var pendingHello: Hello? = null
    private var crypto: SessionCrypto? = null
    private var nextMessageId = 1L

    private val listener = object : TransportListener {
        override fun onConnected() {
            disconnectProtector.onReconnected()
            if (role == Role.Responder) sendHello()
        }

        override fun onDisconnected(reason: DisconnectReason) {
            handleDisconnect(reason)
        }

        override fun onFrame(message: WireMessage) {
            // 协议处理不得让网络读线程崩溃：任何异常帧都转为安全断连
            try {
                handleFrame(message)
            } catch (e: Exception) {
                println("[JUAN] protocol error, disconnecting: ${e.stackTraceToString()}")
                runCatching { transport.disconnect() }
                setState(SessionState.Closed)
            }
        }
    }

    /**
     * 以发起方（二维码持有方）启动：绑定监听、生成密钥对、产出一次性配对信息。
     *
     * ⚠️ 候选枚举必须发生在 [transport.start]（绑定监听端口）之后：localCandidates()
     * 依赖端口已绑定，若在 start 前调用会返回空，导致首次创建的房间丢失 TCP 局域网
     * 兜底候选。
     *
     * 自动枚举局域网可达端点，与外部传入候选（如 relay 中继候选）合并去重：
     * 外部候选优先，总候选数 ≤ [MAX_QR_CANDIDATES] 控二维码体积。
     */
    fun startInitiator(pairingManager: PairingManager, candidates: List<Candidate> = emptyList()): PairingInfo {
        role = Role.Initiator
        myKeyPair = CryptoEngine.generateX25519KeyPair()
        transport.start(listener)
        val autoTcp = transport.localCandidates().map { (h, p) -> Candidate(h, p) }
        val effCandidates = (candidates + autoTcp)
            .distinctBy { "${it.host}:${it.port}:${it.type}" }
            .take(MAX_QR_CANDIDATES)
        val info = pairingManager.issue(deviceId, myKeyPair!!.publicKey, effCandidates)
        sessionId = info.sessionId
        nonce = Base64Url.decode(info.nonce)
        setState(SessionState.Pairing)
        return info
    }

    private companion object {
        /** 二维码候选上限：控制 payload 体积，保证 QR 版本低、屏幕模块大、易扫码 */
        const val MAX_QR_CANDIDATES = 4
        /** 重连尝试间隔：断线后每 3s 重试一次 connectAny */
        const val RECONNECT_DELAY_MS = 3000L
        /** 主动断开时 Bye 帧的"出网宽限"：Bye 走写线程异步发送 + RDS 300ms 重传，覆盖蜂窝 RTT，
         *  延迟释放传输保证对端能收到（否则对端永久停留在 Connected） */
        const val BYE_GRACE_MS = 800L
    }

    /** 以响应方（扫码方）连接发起方：逐个候选尝试，任一成功即握手 */
    fun connectAsResponder(pairing: PairingInfo): Boolean {
        role = Role.Responder
        sessionId = pairing.sessionId
        nonce = Base64Url.decode(pairing.nonce)
        myKeyPair = CryptoEngine.generateX25519KeyPair()
        val publicKey = Base64Url.decode(pairing.publicKey)
        val peers = pairing.candidates.map { cand ->
            PeerInfo(
                pairing.deviceId, pairing.sessionId, cand.host, cand.port, publicKey,
                type = cand.type,
                // relay 候选携带 TURN 服务器地址：Responder 必须连与 Initiator 相同的服务器
                relayServer = pairing.turn,
            )
        }
        if (peers.isEmpty()) return false
        responderPeers = peers
        explicitlyDisconnected.set(false)
        reconnectJob?.cancel()
        setState(SessionState.Handshaking)
        return transport.connectAny(peers, listener)
    }

    /** 发送一段加密明文（业务层：序列化 OpEnvelope 后调用） */
    fun sendEncrypted(plaintext: ByteArray): Boolean {
        val c = crypto ?: return false
        val enc = c.encrypt(plaintext)
        return transport.send(EncryptedFrame(nextId(), enc.seq, enc.iv, enc.ciphertext, enc.mac))
    }

    /** 发送控制帧（明文路径仅限握手与探测） */
    fun sendControl(message: WireMessage): Boolean = transport.send(message)

    /** 发送质量探测包（QualityMonitor 周期调用） */
    fun sendQualityProbe(seq: Int, ts: Long): Boolean =
        transport.send(QualityProbe(nextId(), seq, ts, 32))

    fun disconnect(reason: DisconnectReason = DisconnectReason.LocalShutdown) {
        // 用户主动断开：置位停止一切重连，释放传输
        explicitlyDisconnected.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        // 通知对端再断开：先发 Bye（走写线程异步出网），延迟释放传输——立即 transport.disconnect()
        // 会关 socket，异步排队的 Bye datagram 发不出去，对端将永远停留在 Connected（真机复现
        // 「一端断开，另一端仍显示正在连接」）。延迟留出网时间，对端收到 Bye 后同步断开。
        if (_state.value == SessionState.Connected || _state.value == SessionState.Paused) {
            runCatching { transport.send(Bye(nextId(), DisconnectReason.LocalShutdown.ordinal)) }
            sessionScope.launch {
                delay(BYE_GRACE_MS)
                transport.disconnect()
            }
        } else {
            transport.disconnect()
        }
        setState(SessionState.Closed)
    }

    // ------------------------------------------------- OpTransport 实现
    override fun sendOp(envelope: OpEnvelope): Boolean {
        val payload = EnvelopeCodec.encode(envelope)
        return sendEncrypted(payload)
    }

    override fun sendAck(peerId: String, lastAppliedSeq: Long, requestResync: Boolean): Boolean =
        transport.send(Ack(nextId(), lastAppliedSeq, requestResync))

    override fun sendSyncRequest(sinceSeq: Long): Boolean =
        transport.send(SyncRequest(nextId(), sinceSeq))

    override fun sendResync(ops: List<ByteArray>, lastSeq: Long): Boolean =
        transport.send(Resync(nextId(), ops, lastSeq))

    override fun sendResyncDone(lastSeq: Long): Boolean =
        transport.send(ResyncDone(nextId(), lastSeq))

    // ---------------------------------------------------------------- 内部
    private fun sendHello() {
        val kp = myKeyPair ?: return
        val hello = HandshakeProtocol.buildHello(nextId(), AppInfo.PROTOCOL_VERSION, deviceId, sessionId, kp, "responder")
        pendingHello = hello
        transport.send(hello)
    }

    private fun handleFrame(msg: WireMessage) {
        when (msg) {
            is Hello -> handleHello(msg)
            is HandshakeResponse -> handleHandshakeResponse(msg)
            is EncryptedFrame -> handleEncrypted(msg)
            is Ping -> transport.send(Pong(nextId(), msg.timestamp, msg.seq))
            is QualityProbe -> {
                onQualityProbe(msg.seq, msg.sendTimestamp)
                transport.send(Pong(nextId(), msg.sendTimestamp, msg.seq))
            }
            is Pong -> onPongReceived(msg.seq, msg.timestamp)
            is Heartbeat -> { /* 心跳回显由断线保护层处理（Phase 6） */ }
            is Bye -> { transport.disconnect(); setState(SessionState.Closed) }
            is Ack -> opEngine?.onAck(msg.lastAppliedSeq)
            is SyncRequest -> opEngine?.onSyncRequest(msg.sinceSeq)
            is Resync -> opEngine?.onResync(msg.ops)
            is ResyncDone -> { /* 补同步结束（暂无需处理） */ }
        }
    }

    private fun handleHello(hello: Hello) {
        if (role != Role.Initiator) return
        val kp = myKeyPair ?: return
        println("[JUAN] initiator: Hello from device=${hello.deviceId}, sessionMatch=${hello.sessionId == sessionId}")
        val (resp, c) = HandshakeProtocol.buildResponse(hello, kp, sessionId, nonce, nextId())
        crypto = c
        transport.send(resp)
        setState(SessionState.Connected)
        // 重连/补同步：用新 crypto 重放未 ack 操作（首次连接 outgoing 为空，无害）
        opEngine?.resendOutstanding()
    }

    private fun handleHandshakeResponse(response: HandshakeResponse) {
        if (role != Role.Responder) return
        val hello = pendingHello ?: return
        val c = HandshakeProtocol.verifyResponse(hello, response, myKeyPair ?: return, sessionId, nonce)
        if (c == null) {
            println("[JUAN] responder: handshake MAC verify FAILED (session=$sessionId, mac=${response.mac.size}B)")
            transport.disconnect()
            setState(SessionState.Closed)
            return
        }
        println("[JUAN] responder: handshake OK, session established")
        crypto = c
        setState(SessionState.Connected)
        // 重连/补同步：用新 crypto 重放未 ack 操作（断线期间本端画的操作补发给对端）
        opEngine?.resendOutstanding()
    }

    private fun handleEncrypted(frame: EncryptedFrame) {
        val c = crypto ?: return
        val plain = c.decrypt(EncryptedPayload(frame.seq, frame.iv, frame.ciphertext, frame.mac)) ?: return
        val engine = opEngine
        if (engine != null) {
            // 操作同步路径：解密载荷即序列化 OpEnvelope
            val envelope = EnvelopeCodec.decode(plain)
            if (envelope != null) engine.onRemoteOp(envelope)
        } else {
            onEncryptedPlain(plain)
        }
    }

    private fun handleDisconnect(reason: DisconnectReason) {
        println("[JUAN] disconnect: reason=$reason, state=${_state.value}")
        if (reason == DisconnectReason.LocalShutdown) {
            setState(SessionState.Closed)
            return
        }
        // 断线保护：进入 Reconnecting 并持续尝试恢复（不自动关闭；用户主动断开才结束）
        if (_state.value == SessionState.Connected || _state.value == SessionState.Reconnecting || _state.value == SessionState.Paused) {
            setState(SessionState.Reconnecting)
            disconnectProtector.onDisconnected()
            startReconnectLoop()
        } else {
            setState(SessionState.Closed)
        }
    }

    /**
     * Responder 重连循环：断线后每 [RECONNECT_DELAY_MS] 复用首次候选重试 connectAny，
     * 直到重连成功或用户主动断开。重连成功后由握手（Hello/Response）推进到 Connected，
     * 并重放未 ack 操作。connectAny 是阻塞调用（TCP 多候选 + relay 8s 预算），必须在
     * sessionScope（Dispatchers.Default）后台执行，避免卡 UI 线程。
     */
    private fun startReconnectLoop() {
        if (role != Role.Responder) return
        val peers = responderPeers ?: return
        if (reconnectJob?.isActive == true) return
        reconnectJob = sessionScope.launch {
            while (isActive && !explicitlyDisconnected.get() && _state.value != SessionState.Closed) {
                delay(RECONNECT_DELAY_MS)
                if (explicitlyDisconnected.get() || _state.value == SessionState.Connected) break
                println("[JUAN] reconnect: 尝试重连 ${peers.size} 候选")
                val ok = runCatching { transport.connectAny(peers, listener) }.getOrDefault(false)
                if (ok) break
                // 失败已由 relay 兜底上报 onDisconnected(IoError) → handleDisconnect 复位状态机
            }
        }
    }

    private fun setState(s: SessionState) {
        if (_state.value == s) return
        _state.value = s
        // 握手成功进入 Connected：停止断线保护计时（否则 5s 后会把已恢复的会话误判为 Paused）
        if (s == SessionState.Connected) disconnectProtector.onReconnected()
        onStateChanged(s)
    }

    private fun nextId(): Long = nextMessageId++
}

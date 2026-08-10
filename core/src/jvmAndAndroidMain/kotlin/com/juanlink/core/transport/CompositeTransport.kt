package com.juanlink.core.transport

import com.juanlink.core.protocol.WireMessage
import java.util.concurrent.atomic.AtomicReference

/**
 * 组合传输：TCP（局域网直连）+ relay（TURN 中继兜底）。
 *
 * 客户端角色（Responder）按 `PeerInfo.type` 分组**两阶段**连接：
 * - **Phase 1** 非 relay（tcp）：TCP 直连；失败且存在 relay 兜底时**不上报**（suppressFailure），
 * - **Phase 2** relay 兜底：Phase 1 失败且二维码带 relay 候选时尝试 TURN 中继，
 *   **最后阶段正常上报**（全阶段失败恰好一次 onDisconnected，防打坏断线保护状态机）。
 *
 * Initiator 角色（[start]）：两端都监听，入站连接直接透传 listener。
 */
class CompositeTransport(
    private val tcp: TcpTransport = TcpTransport(),
    private val relay: Transport? = null, // TURN 中继（对称 NAT 跨网兜底）
) : Transport, TurnCapable {

    private val active = AtomicReference<Transport?>(null)

    override val kind: TransportKind get() = TransportKind.Tcp
    override val isConnected: Boolean get() = active.get()?.isConnected == true

    // ---- Initiator ----

    override fun start(listener: TransportListener) {
        // Initiator：两端都监听。必须用 CompositeRelayListener 包裹（而非直接透传）：
        // 入站连接建立（tcp accept / relay 首包）时 CAS 设置 active，后续 send 才能路由到
        // 对端实际接入的传输。直接透传会让 active 恒为 null → Initiator 侧 send 全部静默失败
        // （收数据正常，发不出去；handshake 响应因此丢失）。
        // ⚠️ 每个传输用各自的 listener，owner 必须是对应传输（不是 this）——否则 active 指向
        // CompositeTransport 自身，isConnected 无限递归。
        tcp.start(CompositeRelayListener(tcp, listener, active, suppressFailure = true))
        relay?.let { it.start(CompositeRelayListener(it, listener, active, suppressFailure = true)) }
    }

    // ---- Responder ----

    override fun connect(peer: PeerInfo, listener: TransportListener): Boolean =
        connectAny(listOf(peer), listener)

    override fun connectAny(peers: List<PeerInfo>, listener: TransportListener): Boolean {
        val relayPeers = peers.filter { it.type == "relay" }
        val tcpPeers = peers.filter { it.type != "relay" }
        val hasRelayFallback = relay != null && relayPeers.isNotEmpty()

        // Phase 1：TCP 直连；有 relay 兜底时失败抑制上报
        if (tcpPeers.isNotEmpty()) {
            val phase1Ok = runSingle(tcp, tcpPeers, listener, suppressFailure = hasRelayFallback)
            if (phase1Ok) return true
        }

        // Phase 2：relay 兜底（最后阶段，失败正常上报）
        if (hasRelayFallback) {
            println("[JUAN] transport: 直连失败，走 TURN 中继兜底")
            return runSingle(relay!!, relayPeers, listener)
        }
        return false
    }

    private fun runSingle(
        t: Transport,
        peers: List<PeerInfo>,
        outer: TransportListener,
        suppressFailure: Boolean = false,
    ): Boolean {
        if (peers.isEmpty()) {
            if (!suppressFailure) outer.onDisconnected(DisconnectReason.IoError)
            return false
        }
        val relay = CompositeRelayListener(t, outer, active, suppressFailure)
        return t.connectAny(peers, relay)
    }

    // ---- 数据面 ----

    override fun send(message: WireMessage): Boolean = active.get()?.send(message) ?: false

    override fun disconnect() {
        runCatching { tcp.disconnect() }
        relay?.let { runCatching { it.disconnect() } }
        active.set(null)
    }

    override fun localEndpoint(): Pair<String, Int>? = tcp.localEndpoint()

    override fun localCandidates(): List<Pair<String, Int>> = tcp.localCandidates()

    // ---- TurnCapable：委托给中继 ----

    override fun allocateRelayEndpoint(): TurnRelayEndpoint? =
        (relay as? TurnCapable)?.allocateRelayEndpoint()

    override fun updateTurnServers(custom: List<TurnServer>) {
        (relay as? TurnCapable)?.updateTurnServers(custom)
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 单传输的转发监听器。
     * - [onConnected]：CAS 设 active；已有活动连接时自我关闭（防双连接）
     * - [onDisconnected]：active 断开直接上报；尚未连接成功时失败仅在 [suppressFailure] 为 false 才上报
     *   （存在 relay 兜底时抑制，由 Phase 2 统一收口）
     */
    private inner class CompositeRelayListener(
        private val owner: Transport,
        private val outer: TransportListener,
        private val activeRef: AtomicReference<Transport?>,
        private val suppressFailure: Boolean = false,
    ) : TransportListener {

        override fun onConnected() {
            if (activeRef.compareAndSet(null, owner)) {
                outer.onConnected()
            } else {
                // 已有活动连接（本端非首个）：自我关闭，避免双连接
                runCatching { owner.disconnect() }
            }
        }

        override fun onDisconnected(reason: DisconnectReason) {
            if (activeRef.get() === owner) {
                // 活动传输断开：清空 active——否则重连时 onConnected 的 CAS(null→owner) 永不成功，
                // 触发「自我关闭」导致重连循环永远失败
                activeRef.set(null)
                outer.onDisconnected(reason)
            } else if (activeRef.get() == null && !suppressFailure) {
                outer.onDisconnected(DisconnectReason.IoError)
            }
            // active 已定时本端断开：忽略
        }

        override fun onFrame(message: WireMessage) {
            outer.onFrame(message)
        }
    }
}

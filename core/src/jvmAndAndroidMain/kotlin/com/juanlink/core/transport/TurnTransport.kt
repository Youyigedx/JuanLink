package com.juanlink.core.transport

import com.juanlink.core.protocol.FrameDecoder
import com.juanlink.core.protocol.Heartbeat
import com.juanlink.core.protocol.ProtocolCodec
import com.juanlink.core.protocol.WireMessage
import com.juanlink.core.turn.TurnClient
import com.juanlink.core.turn.TurnProtocol
import com.juanlink.core.transport.udp.ReliableDatagramSession
import com.juanlink.core.util.nowEpochMillis
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * TURN 中继传输（对称 NAT 兜底）。
 *
 * 与打洞同为「数据报 + 可靠层」模型，但**不依赖 NAT 映射**：本端连一台公共 TURN 服务器分配
 * relayed transport address，对端也连**同一台**（二维码带 PairingInfo.turn 保证），经服务器
 * 双向转发。数据面复用 [ReliableDatagramSession]：`sendDatagram` 注入为「封装 Send Indication
 * 发给 TURN 服务器」，接收从 Data Indication 解包喂给 session——可靠层零改动。
 *
 * 角色不对称（与 UDP 打洞同构）：
 * - Initiator：allocateRelayEndpoint() 分配 → start() **先预授权自己的 relayed IP**（permission
 *   按 IP 匹配，同服务器 peer 的 relayed IP 相同 → 首包可进，破解「接收方 permission」死锁）→ 监听；
 *   收到首个 Data Indication 从 XOR-PEER-ADDRESS 学到对端 relayed 地址 → createPermission → 建 session → 双向。
 * - Responder：二维码已知对端 relayed 地址，连服务器分配后**立即建 session** → createPermission
 *   → 发首个帧（Heartbeat）触发对端学习并回 ACK → 双向确认后 onConnected。
 *
 * 线程模型与 TcpTransport 同构：reader（daemon 收包/路由事务）+ 单线程写执行器 + scheduler
 * （Refresh/重传定时）。Android 主线程零网络 I/O。
 */
class TurnTransport(
    /** TURN 服务器候选（可注入测试指向本地模拟服务器；生产默认免费公共列表） */
    private val servers: List<TurnServer> = DEFAULT_TURN_SERVERS,
) : Transport, TurnCapable {

    private enum class Phase { Idle, Allocated, Listening, Establishing, Established, Closed }

    companion object {
        /** 单台 TURN Allocate 预算（含 401→带凭据重发往返） */
        const val ALLOCATE_TIMEOUT_MS = 3000L
        /** CreatePermission / Refresh 单事务预算 */
        const val PERM_TIMEOUT_MS = 2000L
        /** Responder 双向建立预算：发首帧后 8s 内未收到对端回包即放弃 */
        const val RELAY_BUDGET_MS = 8000L
        /** permission(300s)/allocation(600s) 提前刷新 */
        const val REFRESH_INTERVAL_MS = 240_000L
        const val ALLOCATE_LIFETIME_SEC = 600
    }

    @Volatile private var phase = Phase.Idle
    private var listener: TransportListener? = null
    private var socket: DatagramSocket? = null
    private var client: TurnClient? = null
    private var server: TurnServer? = null

    /** 用户自定义服务器（设置面板运行时更新）；分配/解析用 [effectiveServers] */
    @Volatile private var customServers: List<TurnServer> = emptyList()

    @Volatile private var session: ReliableDatagramSession? = null
    @Volatile private var peer: InetSocketAddress? = null
    @Volatile private var establishedLatch: CountDownLatch? = null

    /** 本端 relayed 地址（allocate 成功后记录；Initiator 预授权自己 relayed IP 用） */
    @Volatile private var relayedHost: String? = null
    @Volatile private var relayedPort: Int = 0

    /** 角色标记：Initiator（start 启动，断开后保持监听等对端重连）/ Responder（connectAny 启动，断开后走重连循环） */
    @Volatile private var isInitiator = false

    private var readerThread: Thread? = null

    /** reader 代数：每次 startReader 递增；旧代 reader 在下轮循环发现代数不符即退出，
     *  防止重连时旧 reader 误读新 socket（旧 reader 可能仍阻塞在已关闭 socket 的 receive 上） */
    private val readerGen = java.util.concurrent.atomic.AtomicInteger(0)

    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "juanlink-turn-write").apply { isDaemon = true }
    }
    private val scheduler = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "juanlink-turn-sched").apply { isDaemon = true }
    }
    private val refreshFuture = AtomicReference<ScheduledFuture<*>?>(null)

    private val decoder = FrameDecoder()

    override val kind: TransportKind get() = TransportKind.Udp // 数据报语义（无专用中继枚举）
    override val isConnected: Boolean get() = phase == Phase.Established && session != null

    // ---- TurnCapable：Initiator 侧中继分配 ----

    /** 运行时更新自定义服务器：自定义优先 + 默认公共列表 fallback（同 host:port 去重，自定义优先） */
    override fun updateTurnServers(custom: List<TurnServer>) {
        customServers = custom
        println("[JUAN] turn: 自定义服务器 ${custom.size} 台（${custom.joinToString { "${it.host}:${it.port}" }}）")
    }

    /** 候选列表 = 自定义 + 默认，去重（自定义优先）。与 resolveServer 保持同一来源，避免应答方连不上发起方 */
    private fun effectiveServers(): List<TurnServer> =
        (customServers + servers).distinctBy { it.host to it.port }

    override fun allocateRelayEndpoint(): TurnRelayEndpoint? {
        synchronized(this) {
            when (phase) {
                Phase.Idle -> {}
                // 断开后重新创建：重置回可分配（socket 已释放，重新绑定 + 重新 allocate 得新端点）
                Phase.Closed -> phase = Phase.Idle
                else -> {
                    // 已有有效分配（Allocated/Listening/Establishing/Established）：复用当前 relayed
                    // 端点。重复 createRoom（未断开）时避免重新分配、避免二维码丢 relay 候选——
                    // 若在此返回 null，start() 又因 phase 非 Allocated 提前退出，二维码只剩 TCP，
                    // 跨网对端无法经中继接入（真机已复现「局域网直连失败」）。
                    val srv = server
                    val rh = relayedHost
                    if (srv != null && rh != null && relayedPort > 0) {
                        return TurnRelayEndpoint(srv.host, srv.port, rh, relayedPort)
                    }
                    return null
                }
            }
            ensureBound()
        }
        for (srv in effectiveServers()) {
            val sock = socket ?: return null
            val c = TurnClient(sock, srv)
            val ep = runCatching { c.allocate(ALLOCATE_TIMEOUT_MS) }.getOrNull()
            if (ep != null) {
                synchronized(this) {
                    client = c
                    server = srv
                    relayedHost = ep.relayedHost
                    relayedPort = ep.relayedPort
                    phase = Phase.Allocated
                }
                println("[JUAN] turn: 分配成功 server=${srv.host}:${srv.port} relayed=${ep.relayedHost}:${ep.relayedPort}")
                return ep
            }
            println("[JUAN] turn: ${srv.host}:${srv.port} 分配失败，换下一台")
        }
        return null
    }

    // ---- Transport：Initiator ----

    override fun start(listener: TransportListener) {
        this.listener = listener
        isInitiator = true
        if (phase == Phase.Idle) allocateRelayEndpoint()
        if (phase == Phase.Allocated) {
            startReader()
            phase = Phase.Listening
            // 破局：Initiator 预授权自己的 relayed IP。
            // RFC 5766 permission 按 IP 匹配；同一台 TURN 服务器上所有 client 的 relayed IP 相同
            // （= 服务器 external-ip）。授权后同服务器任意 peer 的首包即可通过转发（无需本端预知地址）。
            // 否则服务器要求「接收方对发送方 relayed 有 permission」→ 首包被静默丢弃 → 学习式死锁。
            val rh = relayedHost
            if (rh != null) {
                runCatching { client?.createPermission(rh, relayedPort, PERM_TIMEOUT_MS) }
                    .onSuccess { println("[JUAN] turn: 已预授权 relayed IP $rh（同服务器 peer 首包可进）") }
                    .onFailure { println("[JUAN] turn: 预授权失败，本端可能收不到对端首包: ${it.message}") }
            }
            startRefreshLoop()
            println("[JUAN] turn: 中继监听中（relayed=$rh，等对端首包）")
        } else if (phase == Phase.Listening || phase == Phase.Establishing || phase == Phase.Established) {
            // 已在监听/连接中：幂等（重复 createRoom 复用现有中继分配，避免丢 relay 候选）
            println("[JUAN] turn: 中继已在监听中（幂等 start，relayed=$relayedHost）")
        } else {
            // 中继分配失败：静默不监听（不拖垮 CompositeTransport.start）
            println("[JUAN] turn: 中继分配失败，本端无中继监听")
        }
    }

    // ---- Transport：Responder ----

    override fun connect(peer: PeerInfo, listener: TransportListener): Boolean {
        if (peer.type != "relay") {
            listener.onDisconnected(DisconnectReason.IoError)
            return false
        }
        return connectAny(listOf(peer), listener)
    }

    override fun connectAny(peers: List<PeerInfo>, listener: TransportListener): Boolean {
        val relayPeer = peers.firstOrNull { it.type == "relay" } ?: run {
            listener.onDisconnected(DisconnectReason.IoError)
            return false
        }
        this.listener = listener
        isInitiator = false
        val srv = resolveServer(relayPeer.relayServer)
        if (srv == null) {
            println("[JUAN] turn responder: 服务器地址无效 (relayServer=${relayPeer.relayServer})")
            listener.onDisconnected(DisconnectReason.IoError)
            return false
        }
        synchronized(this) {
            when (phase) {
                Phase.Idle -> {}
                // 重连：上次断开后的终端态重置回 Idle（socket/client 已释放，可重新分配）
                Phase.Closed -> phase = Phase.Idle
                else -> {
                    listener.onDisconnected(DisconnectReason.IoError)
                    return false
                }
            }
            ensureBound()
        }

        val c = TurnClient(socket!!, srv)
        val ep = runCatching { c.allocate(ALLOCATE_TIMEOUT_MS) }.getOrNull()
        if (ep == null) {
            println("[JUAN] turn responder: ${srv.host}:${srv.port} 分配失败")
            listener.onDisconnected(DisconnectReason.IoError)
            return false
        }
        synchronized(this) {
            client = c
            server = srv
            phase = Phase.Allocated
        }
        startReader()

        // 对端 relayed 地址二维码已知 → 立即授权 + 建 session（无需学习）
        val target = InetSocketAddress(relayPeer.host, relayPeer.port)
        peer = target
        val permOk = c.createPermission(relayPeer.host, relayPeer.port, PERM_TIMEOUT_MS)
        println("[JUAN] turn responder: permission(${target.hostString}:${target.port})=$permOk")

        val latch = CountDownLatch(1)
        establishedLatch = latch
        synchronized(this) {
            if (phase != Phase.Allocated) {
                establishedLatch = null
                listener.onDisconnected(DisconnectReason.IoError)
                return false
            }
            session = newSession(target)
            phase = Phase.Establishing
        }

        // 发首个帧：对端学到本端 relayed 地址后回 ACK → 双向通
        session?.sendFrame(ProtocolCodec.encode(Heartbeat(0, nowEpochMillis())))
        val won = runCatching { latch.await(RELAY_BUDGET_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        establishedLatch = null
        if (!won) {
            println("[JUAN] turn responder: 双向确认超时（${RELAY_BUDGET_MS}ms 未收到对端回包）")
            handleDisconnect(DisconnectReason.IoError)
            return false
        }
        startRefreshLoop()
        println("[JUAN] turn responder: 双向通，中继已建立")
        return true
    }

    // ---- Transport：数据面 ----

    /**
     * 背压待发队列：RDS 窗口(32)+pending(512) 都满时（对端 ACK 慢于本端产生速率），
     * sendFrame 返回 false。此场景**不是**失联——帧在此排队，由调度器周期补发。
     * 只有 RDS 重传耗尽（onLost）或显式断开才触发 handleDisconnect。
     */
    private val backpressureQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var backpressureLoop = false

    override fun send(message: WireMessage): Boolean {
        if (phase != Phase.Established) return false
        val s = session ?: return false
        val bytes = ProtocolCodec.encode(message)
        if (s.sendFrame(bytes)) return true
        // 背压满：缓存待发（绝不 handleDisconnect——对端只是 ACK 慢）。补发由本层保证，
        // 返回 true 让上层 op 无需重发；真失联由 onLost→handleDisconnect 清理本队列。
        backpressureQueue.add(bytes)
        startBackpressureLoop()
        return true
    }

    private fun startBackpressureLoop() {
        synchronized(this) {
            if (backpressureLoop) return
            backpressureLoop = true
        }
        scheduler.schedule({ drainBackpressure() }, 25, TimeUnit.MILLISECONDS)
    }

    /** 周期补发：窗口/pending 腾出空间即发送；本轮仍满则下轮再试（25ms tick） */
    private fun drainBackpressure() {
        val s = session
        val drain: Boolean = if (s == null || phase != Phase.Established) {
            backpressureQueue.clear()
            false
        } else {
            var drained = 0
            while (drained < 64) {
                val frame = backpressureQueue.peek() ?: break
                if (!s.sendFrame(frame)) break // 仍满：下轮再试
                backpressureQueue.poll()
                drained++
            }
            backpressureQueue.isNotEmpty()
        }
        if (drain) {
            scheduler.schedule({ drainBackpressure() }, 25, TimeUnit.MILLISECONDS)
        } else {
            synchronized(this) { backpressureLoop = false }
        }
    }

    override fun disconnect() = handleDisconnect(DisconnectReason.LocalShutdown)

    /** 中继无本地监听端点（候选由 TCP 提供）；对端地址由二维码交换 */
    override fun localEndpoint(): Pair<String, Int>? = null
    override fun localCandidates(): List<Pair<String, Int>> = emptyList()

    // ---- 收包路由 ----

    private fun onDataIndication(di: TurnProtocol.DataIndication) {
        when (phase) {
            // Initiator：首个 Data → 学到对端 relayed 地址，异步授权 + 建 session
            Phase.Listening -> onFirstData(di)
            // Responder：等对端回包确认双向
            Phase.Establishing -> onPeerData(di)
            Phase.Established -> {
                if (isPeer(di)) {
                    println("[TURN] data rx ${di.peerHost}:${di.peerPort} len=${di.payload.size}")
                    session?.onDatagram(di.payload, di.payload.size)
                } else {
                    // 对端重连：relayed 地址变化（新分配）→ 重置 session 绑定新地址再交付
                    onReattach(di)
                }
            }
            else -> {}
        }
    }

    private fun onFirstData(di: TurnProtocol.DataIndication) {
        val target = InetSocketAddress(di.peerHost, di.peerPort)
        val claimed = synchronized(this) {
            if (phase != Phase.Listening) {
                false
            } else {
                peer = target
                phase = Phase.Establishing
                true
            }
        }
        if (!claimed) return
        println("[JUAN] turn initiator: 收到对端首包 ${di.peerHost}:${di.peerPort}，授权并建立")
        // 授权对端 relayed：阻塞等服务器响应（reader 路由），必须移出 reader 线程 → 异步执行。
        // ⚠️ 不阻塞 promote/onConnected：同服务器 peer 的 relayed IP 与本端相同，start() 已预授权
        // 本端 relayed IP → 对端首包早已可进服务器，此显式授权仅为补全规范。
        writeExecutor.execute {
            runCatching { client?.createPermission(di.peerHost, di.peerPort, PERM_TIMEOUT_MS) }
        }
        // reader 线程立即 promote + 交付首包 + onConnected：让上层（CompositeTransport）尽快
        // CAS 记录 active，避免 createPermission 阻塞期间对端后续包（Hello）到达时 send 因
        // active 未设而静默失败。
        val promoted = synchronized(this) {
            if (phase == Phase.Establishing) {
                session = newSession(target)
                phase = Phase.Established
                true
            } else {
                false
            }
        }
        if (promoted) {
            session?.onDatagram(di.payload, di.payload.size)
            listener?.onConnected()
        }
    }

    /**
     * 对端重连重新绑定：Established 后收到非当前 peer 地址的 Data Indication，
     * 判定对端断线后用新 allocation 重连（relayed 地址变化）。重置 session 绑定新地址
     * 并交付首个帧——首帧会被新 session 视为 DATA seq=1，驱动回 ACK，对端据此完成重连。
     *
     * 安全性：同服务器 peer 的 relayed IP 与本地相同（start 已预授权），能进数据面的
     * 发送方本就在授权集合内，与「破局」首包学习模型一致。
     */
    private fun onReattach(di: TurnProtocol.DataIndication) {
        val target = InetSocketAddress(di.peerHost, di.peerPort)
        val claimed = synchronized(this) {
            if (phase != Phase.Established) {
                false
            } else {
                println("[JUAN] turn: 对端重连，重新绑定 ${di.peerHost}:${di.peerPort}")
                session?.close()
                peer = target
                session = newSession(target)
                true
            }
        }
        if (claimed) {
            session?.onDatagram(di.payload, di.payload.size)
        }
    }

    private fun onPeerData(di: TurnProtocol.DataIndication) {
        if (!isPeer(di)) return
        val promoted = synchronized(this) {
            if (phase == Phase.Establishing) {
                phase = Phase.Established
                true
            } else {
                false
            }
        }
        if (promoted) {
            establishedLatch?.countDown()
            listener?.onConnected()
        }
        session?.onDatagram(di.payload, di.payload.size)
    }

    private fun isPeer(di: TurnProtocol.DataIndication): Boolean {
        val p = peer ?: return false
        return di.peerPort == p.port && di.peerHost == p.hostString
    }

    private fun newSession(target: InetSocketAddress): ReliableDatagramSession = ReliableDatagramSession(
        // 物理发给 TURN 服务器（logicalPeer 作 XOR-PEER-ADDRESS）；切写线程防 Android 主线程网络 I/O
        sendDatagram = { datagram, logicalPeer ->
            writeExecutor.execute { client?.sendData(logicalPeer.hostString, logicalPeer.port, datagram) }
        },
        peer = target,
        onFrame = { frame -> decoder.push(frame, 0, frame.size).forEach { listener?.onFrame(it) } },
        schedule = { delayMs, task -> scheduler.schedule({ task() }, delayMs, TimeUnit.MILLISECONDS) },
        onLost = { handleDisconnect(DisconnectReason.IoError) },
    )

    // ---- 生命周期 ----

    private fun handleDisconnect(reason: DisconnectReason) {
        // Initiator 断开（对端失联/本地超时）时保持中继监听等待对端重连：关闭旧 session/peer
        // 绑定回 Listening，保留 socket/client/allocation（reader 继续读、refresh 重启），
        // 对端重连首包经 onFirstData 重建会话。设计意图「Initiator 保持监听靠对端重新接入恢复」。
        // 否则 socket 关闭后对端重连首包无人接收 → Responder 重连循环永远双向确认超时。
        if (isInitiator && reason != DisconnectReason.LocalShutdown) {
            refreshFuture.getAndSet(null)?.cancel(false)
            backpressureQueue.clear()
            synchronized(this) {
                session?.close()
                session = null
                peer = null
            }
            phase = Phase.Listening
            startRefreshLoop()
            println("[JUAN] turn initiator: 会话断开（$reason），保持中继监听等待对端重连")
            listener?.onDisconnected(reason)
            return
        }
        refreshFuture.getAndSet(null)?.cancel(false)
        backpressureQueue.clear()
        val wasActive = phase != Phase.Closed
        phase = Phase.Closed
        synchronized(this) {
            session?.close()
            session = null
            client = null
            runCatching { socket?.close() }
            socket = null
        }
        establishedLatch?.countDown()
        if (wasActive) listener?.onDisconnected(reason)
    }

    private fun startRefreshLoop() {
        refreshFuture.set(
            scheduler.scheduleAtFixedRate(
                {
                    runCatching {
                        client?.refresh(ALLOCATE_LIFETIME_SEC, PERM_TIMEOUT_MS)
                        peer?.let { p -> client?.createPermission(p.hostString, p.port, PERM_TIMEOUT_MS) }
                    }
                },
                REFRESH_INTERVAL_MS,
                REFRESH_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            ),
        )
    }

    private fun resolveServer(relayServer: String?): TurnServer? {
        if (relayServer == null) return effectiveServers().firstOrNull()
        val idx = relayServer.lastIndexOf(':')
        if (idx <= 0 || idx >= relayServer.length - 1) return null
        val host = relayServer.substring(0, idx)
        val port = relayServer.substring(idx + 1).toIntOrNull() ?: return null
        // 凭据从已知列表匹配；未知服务器用空凭据尽力而为（public 未认证服务器）
        return effectiveServers().firstOrNull { it.host == host && it.port == port }
            ?: TurnServer(host, port, "", "")
    }

    private fun ensureBound() {
        synchronized(this) {
            if (socket == null) {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", 0))
                    receiveBufferSize = 1024 * 1024
                    sendBufferSize = 1024 * 1024
                }
            }
        }
    }

    private fun startReader() {
        // 每次连接/监听都启动全新 reader；旧代 reader 靠代数不符在下一轮循环退出
        val gen = readerGen.incrementAndGet()
        val t = Thread { readLoop(gen) }.apply {
            name = "juanlink-turn-read"
            isDaemon = true
        }
        readerThread = t
        t.start()
    }

    private fun readLoop(gen: Int) {
        val buf = ByteArray(8192)
        while (phase != Phase.Closed) {
            if (readerGen.get() != gen) break // 旧代 reader：让位给新连接
            val sock = socket ?: break
            val pkt = DatagramPacket(buf, buf.size)
            val len = try {
                sock.receive(pkt)
                pkt.length
            } catch (e: Exception) {
                break
            }
            if (len <= 0) continue
            val c = client ?: continue
            if (c.route(buf, len)) continue // 挂起事务响应（Allocate/Permission/Refresh）已消费
            val di = TurnProtocol.parseDataIndication(buf, len)
            if (di != null) {
                onDataIndication(di)
                continue
            }
            // 其他（Error Indication 等）：忽略
        }
    }
}

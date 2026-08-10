package com.juanlink.core.transport

import com.juanlink.core.protocol.FrameDecoder
import com.juanlink.core.protocol.ProtocolCodec
import com.juanlink.core.protocol.WireMessage
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JVM 局域网 TCP 直连传输。
 *
 * - 服务端角色：绑定端口监听入站连接
 * - 客户端角色：connect 到对端候选地址
 * - 传输层保证可靠有序，协议层负责加密与语义
 */
class TcpTransport(
    private val bindHost: String = "0.0.0.0",
    private val bindPort: Int = 0,
) : Transport {

    override val kind: TransportKind = TransportKind.Tcp

    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var listener: TransportListener? = null
    private val decoder = FrameDecoder()
    private val writeLock = Any()
    private val connectedFlag = AtomicBoolean(false)
    private var readerThread: Thread? = null

    /**
     * 单线程写执行器：所有帧写入在此串行执行。
     * - 避免 Android 主线程直接网络 I/O（NetworkOnMainThreadException）
     * - FIFO 保证帧序；daemon 线程不阻塞 JVM 退出
     */
    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "juanlink-tcp-write").apply { isDaemon = true }
    }

    override fun start(listener: TransportListener) {
        this.listener = listener
        server = ServerSocket(bindPort, 50, InetAddress.getByName(bindHost)).also {
            it.reuseAddress = true
        }
        val t = Thread({ acceptLoop() }, "juanlink-tcp-accept")
        t.isDaemon = true
        t.start()
    }

    override fun connect(peer: PeerInfo, listener: TransportListener): Boolean {
        this.listener = listener
        if (tryConnect(peer)) return true
        listener.onDisconnected(DisconnectReason.IoError)
        return false
    }

    /**
     * 多候选连接：按序逐个尝试，任一成功即建立连接并返回 true。
     * 全部失败才回调一次 IoError（避免中间失败触发断线保护状态机）。
     */
    override fun connectAny(peers: List<PeerInfo>, listener: TransportListener): Boolean {
        this.listener = listener
        for (peer in peers) {
            if (tryConnect(peer)) return true
        }
        println("[JUAN] transport connectAny 全部候选不可达 (${peers.size} 个)")
        listener.onDisconnected(DisconnectReason.IoError)
        return false
    }

    /** 尝试连接单个候选；失败不回调（由 connect/connectAny 决定整体结果） */
    private fun tryConnect(peer: PeerInfo): Boolean {
        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(peer.host, peer.port), CONNECT_TIMEOUT_MS)
            attachSocket(s)
            true
        } catch (e: Exception) {
            println("[JUAN] transport 候选 ${peer.host}:${peer.port} 连接失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    override fun send(message: WireMessage): Boolean {
        val s = socket ?: return false
        if (!connectedFlag.get()) return false
        // 帧编码可能在调用线程（Android 上可能是 UI 主线程）执行：仅做纯内存操作，安全。
        // 实际 socket 写入一律提交到单线程写执行器，避免 Android 主线程网络 I/O
        // 抛 NetworkOnMainThreadException，同时 FIFO 保证帧顺序。
        return try {
            val frame = ProtocolCodec.encode(message)
            writeExecutor.execute {
                val cur = socket
                if (cur === s && connectedFlag.get()) {
                    try {
                        synchronized(writeLock) {
                            cur.getOutputStream().write(frame)
                            cur.getOutputStream().flush()
                        }
                    } catch (e: Exception) {
                        println("[JUAN] transport send IO error: ${e.javaClass.simpleName}: ${e.message}")
                        handleDisconnect(DisconnectReason.IoError)
                    }
                }
            }
            true
        } catch (e: Exception) {
            println("[JUAN] transport send encode error: ${e.javaClass.simpleName}: ${e.message}")
            handleDisconnect(DisconnectReason.IoError)
            false
        }
    }

    override fun disconnect() {
        server?.let { runCatching { it.close() } }
        server = null
        handleDisconnect(DisconnectReason.LocalShutdown)
    }

    /** 关闭写执行器（进程退出/传输废弃时调用） */
    fun shutdownWriteExecutor() {
        runCatching { writeExecutor.shutdownNow() }
    }

    override fun localEndpoint(): Pair<String, Int>? {
        val s = socket
        val port = s?.localPort ?: server?.localPort ?: return null
        // 客户端角色取已连接 socket 的本地地址。
        // 服务端角色：绑定了具体地址（如 127.0.0.1）直接返回该地址；绑定通配地址（0.0.0.0）时
        // 枚举首个局域网 IPv4，否则二维码/连接串带 127.0.0.1 环回地址，跨设备无法连通。
        val host = when {
            s != null -> s.localAddress.hostAddress
            bindHost == "0.0.0.0" || bindHost == "::" || bindHost.isEmpty() ->
                firstSiteLocalIpv4() ?: "127.0.0.1"
            else -> bindHost
        }
        return host to port
    }

    override fun localCandidates(): List<Pair<String, Int>> {
        val port = socket?.localPort ?: server?.localPort ?: return emptyList()
        // 显式绑定具体地址（如 127.0.0.1/测试）时只返回该地址，保持单候选行为；
        // 绑定通配地址时枚举局域网 + 组网全部候选。
        val hosts = when {
            bindHost != "0.0.0.0" && bindHost != "::" && bindHost.isNotEmpty() -> listOf(bindHost)
            else -> enumerateCandidateHosts().ifEmpty { listOf("127.0.0.1") }
        }
        return hosts.distinct().map { it to port }
    }

    /**
     * 选择对外可达的局域网 IPv4（单地址，兼容单候选调用）。
     * 只从 [enumerateCandidateHosts] 的有序结果中挑第一个私网地址；
     * 无私网地址时返回 null（由调用方回退 127.0.0.1）。
     */
    private fun firstSiteLocalIpv4(): String? =
        enumerateCandidateHosts().firstOrNull { isSiteLocalIpv4(it) }

    /**
     * 枚举所有对外候选 IPv4，有序去重限 [MAX_CANDIDATES] 个。
     * 仅局域网私网（192.168 / 10 / 172.16-31）作为候选（同一 Wi-Fi 直连）；
     * 跨网一律走 TURN 中继，不依赖组网虚拟 IP。排除环回、链路本地（169.254）与
     * 虚拟网卡（Tailscale/WSL/Hyper-V vEthernet、VMware、VirtualBox、Docker、隧道）。
     */
    private fun enumerateCandidateHosts(): List<String> {
        val virtualMarkers = listOf(
            "vether", "virtual", "vmware", "vmnet", "virtualbox", "wsl", "hyper",
            "docker", "bridge", "tun", "tap", "hamachi", "tailscale", "utun",
        )
        return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .asSequence()
            .filter { ni ->
                val name = (ni.displayName + " " + ni.name).lowercase()
                ni.isUp && virtualMarkers.none { name.contains(it) }
            }
            .flatMap { ni -> ni.inetAddresses.toList().asSequence() }
            .filter { a -> !a.isLoopbackAddress && !a.isLinkLocalAddress && a is Inet4Address }
            .mapNotNull { it.hostAddress }
            .filter { it != "0.0.0.0" && it.isNotBlank() }
            .distinct()
            .sortedByDescending(::scoreAddress)
            .take(MAX_CANDIDATES)
            .toList()
    }

    /** 候选可达性排序：192.168.x（30）> 10.x（20）> 172.16-31.x（10）> 其他（0） */
    private fun scoreAddress(host: String): Int {
        val oct = host.split('.').mapNotNull { it.toIntOrNull() }
        if (oct.size < 2) return 0
        return when {
            oct[0] == 192 && oct[1] == 168 -> 30
            oct[0] == 10 -> 20
            oct[0] == 172 && oct[1] in 16..31 -> 10
            else -> 0
        }
    }

    /** 是否 RFC1918 私网地址（局域网判定） */
    private fun isSiteLocalIpv4(host: String): Boolean {
        val oct = host.split('.').mapNotNull { it.toIntOrNull() }
        if (oct.size < 4) return false
        return oct[0] == 10 ||
            (oct[0] == 172 && oct[1] in 16..31) ||
            (oct[0] == 192 && oct[1] == 168)
    }

    override val isConnected: Boolean get() = connectedFlag.get()

    // ---------------------------------------------------------------- 内部
    private fun acceptLoop() {
        while (true) {
            val s = try {
                server?.accept() ?: break
            } catch (e: Exception) {
                break
            }
            attachSocket(s)
        }
    }

    private fun attachSocket(s: Socket) {
        // 单连接语义：新连接替换旧连接。
        // ⚠️ 被替换的旧 socket 关闭后，其残留 readLoop 线程会读到 EOF/异常。
        // 若该线程随后调用 handleDisconnect，会把【当前活动连接】误杀 → 因此
        // readLoop 退出时仅当 `socket === s`（本连接仍是最新）才允许断开通知。
        val old = socket
        if (old != null && old !== s) {
            println("[JUAN] transport attach: 替换旧连接 old=${describe(old)} -> new=${describe(s)}")
            runCatching { old.close() }
        }
        socket = s
        connectedFlag.set(true)
        println("[JUAN] transport attach: 活动连接 ${describe(s)}")
        listener?.onConnected()
        val t = Thread({ readLoop(s) }, "juanlink-tcp-read")
        t.isDaemon = true
        readerThread = t
        t.start()
    }

    private fun readLoop(s: Socket) {
        val input = s.getInputStream()
        val buf = ByteArray(16384)
        var reason = DisconnectReason.RemoteClosed
        while (true) {
            val n = try {
                input.read(buf)
            } catch (e: Exception) {
                println("[JUAN] transport read IO error on ${describe(s)}: ${e.javaClass.simpleName}: ${e.message}")
                reason = DisconnectReason.IoError
                break
            }
            if (n > 0) {
                val msgs = decoder.push(buf, 0, n)
                for (m in msgs) listener?.onFrame(m)
            } else if (n == -1) {
                println("[JUAN] transport read EOF on ${describe(s)}")
                reason = DisconnectReason.RemoteClosed
                break
            } else {
                println("[JUAN] transport read IO error (n=$n) on ${describe(s)}")
                reason = DisconnectReason.IoError
                break
            }
        }
        // 仅当本连接仍是当前活动连接时才允许断开通知；被替换的旧连接静默退出
        val current = socket
        if (current === s) {
            println("[JUAN] transport readLoop 结束: 当前连接, reason=$reason")
            handleDisconnect(reason)
        } else {
            println("[JUAN] transport readLoop 退出: 陈旧连接(残留), reason=$reason, 当前=${current?.let { describe(it) } ?: "null"}")
        }
    }

    private fun describe(s: Socket): String =
        "${s.inetAddress?.hostAddress}:${s.port}"

    private fun handleDisconnect(reason: DisconnectReason) {
        if (!connectedFlag.compareAndSet(true, false)) {
            return // 已在断开状态，避免重复通知
        }
        socket?.let { runCatching { it.close() } }
        socket = null
        listener?.onDisconnected(reason)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8000
        /** 二维码候选上限：控制 payload 体积，保证 QR 版本低、屏幕模块大、易扫码 */
        const val MAX_CANDIDATES = 4
    }
}

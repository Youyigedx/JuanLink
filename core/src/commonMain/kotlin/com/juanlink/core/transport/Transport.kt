package com.juanlink.core.transport

import com.juanlink.core.protocol.WireMessage

/** 传输类型 */
enum class TransportKind { Tcp, Udp, WebRtc }

/** 对端网络信息（由二维码/配对码带外交换） */
data class PeerInfo(
    val deviceId: String,
    val sessionId: String,
    val host: String,
    val port: Int,
    /** X25519 公钥（32B） */
    val publicKey: ByteArray,
    /** 候选类型：tcp | udp | relay。CompositeTransport 据此分派传输 */
    val type: String = "tcp",
    /** relay 候选时携带的 TURN 服务器 `host:port`（来自 PairingInfo.turn）。内存结构，非序列化 */
    val relayServer: String? = null,
)

/** 断开原因（断线保护状态机输入） */
enum class DisconnectReason {
    RemoteClosed,   // 对端主动关闭
    IoError,        // 网络错误
    HeartbeatTimeout,
    ProtocolError,
    LocalShutdown,
    PairingExpired,
}

/**
 * 传输层抽象。
 *
 * 职责边界：只负责「可靠字节流/报文的语义化」。加密在协议之上完成——
 * 调用方先 `SessionCrypto.encrypt` 生成密文帧，再 `send`。
 * WebRTC 传输为预留接口（kind = WebRtc 的空实现由后续版本接入）。
 */
interface Transport {
    val kind: TransportKind

    /** 启动监听（服务端角色，等待入站连接） */
    fun start(listener: TransportListener)

    /** 连接对端（客户端角色） */
    fun connect(peer: PeerInfo, listener: TransportListener): Boolean

    /**
     * 连接对端（客户端角色，多候选）：按序逐个尝试，任一成功即返回 true。
     * 全部失败才回调一次 [TransportListener.onDisconnected]（IoError）。
     * 用于局域网直连：候选为对端 TCP 局域网地址，逐个尝试直至成功。
     */
    fun connectAny(peers: List<PeerInfo>, listener: TransportListener): Boolean =
        peers.firstOrNull()?.let { connect(it, listener) } ?: false

    /** 发送一帧（已加密）。@return 是否进入发送队列 */
    fun send(message: WireMessage): Boolean

    /** 主动断开 */
    fun disconnect()

    /** 本地监听地址描述（用于二维码/配对码候选） */
    fun localEndpoint(): Pair<String, Int>?

    /**
     * 本地监听地址的全部候选（用于二维码/配对码：局域网 + 组网虚拟 IP）。
     * 默认实现退化为单候选，覆盖未来非 TCP 传输。
     */
    fun localCandidates(): List<Pair<String, Int>> =
        localEndpoint()?.let { listOf(it) } ?: emptyList()

    /** 是否已连接 */
    val isConnected: Boolean
}

/** 传输事件回调（由 SessionManager 消费） */
interface TransportListener {
    fun onConnected()
    fun onDisconnected(reason: DisconnectReason)
    fun onFrame(message: WireMessage)
}

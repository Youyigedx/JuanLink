package com.juanlink.core.transport

import kotlinx.serialization.Serializable

/**
 * TURN 服务器（默认公共 fallback；用户可自配服务器凭据）。
 * 多服务器 fallback 缓解单点/凭据变更风险：Initiator 逐个尝试，成功者连同
 * relayed 地址写进二维码（PairingInfo.turn），Responder 据此连同一台。
 *
 * 注意：默认列表只含公共测试服务器（密码为公开值）。自建 TURN 凭据
 * （如自部署 coturn）经 [updateTurnServers] / `~/.juanlink/turn.json` 提供，
 * 不入源码默认值。
 *
 * [Serializable]：用户自定义服务器列表需持久化（桌面 .juanlink/turn.json / 安卓 SharedPreferences），
 * 走 TurnConfigJson 编解码（见 TurnConfigJson.kt）。
 */
@Serializable
data class TurnServer(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

val DEFAULT_TURN_SERVERS: List<TurnServer> = listOf(
    // 公共测试服务器（密码公开，仅作 fallback；实际中继请配置自建/付费 TURN）
    TurnServer("openrelay.metered.ca", 3478, "openrelayproject", "openrelayproject"),
    TurnServer("turn.evan-brass.net", 3478, "user", "password"),
    TurnServer("turn.jami.net", 3478, "ring", "ring"),
)

/** 已分配的中继端点：服务器地址（Responder 据此连同一台）+ 本端 relayed transport address（进二维码） */
data class TurnRelayEndpoint(
    val serverHost: String,
    val serverPort: Int,
    val relayedHost: String,
    val relayedPort: Int,
)

/**
 * 具备 TURN 中继分配能力的传输（[com.juanlink.core.transport.TurnTransport] 实现）。
 * AppState 弱类型探测（`transport as? TurnCapable`），便于单测替换。
 */
interface TurnCapable {
    /**
     * 逐台尝试候选服务器（用户自定义 + [DEFAULT_TURN_SERVERS]）分配中继，返回成功者及本端 relayed 地址。
     * 阻塞（每台 ~4s 预算）；全部失败返回 null（仅丢中继兜底，局域网/打洞直连不受影响）。
     */
    fun allocateRelayEndpoint(): TurnRelayEndpoint?

    /**
     * 运行时更新自定义 TURN 服务器列表（AppState 启动加载用户配置 / 设置面板保存时调用）。
     * 自定义服务器排在候选最前优先尝试，默认公共列表保留作 fallback（同 host:port 去重，自定义优先）。
     */
    fun updateTurnServers(custom: List<TurnServer>)
}

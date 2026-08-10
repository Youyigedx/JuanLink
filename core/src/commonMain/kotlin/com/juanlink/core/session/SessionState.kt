package com.juanlink.core.session

/** 会话状态机 */
enum class SessionState {
    Idle,           // 未开始
    Pairing,        // 等待配对（展示二维码/配对码）
    Handshaking,    // 正在密钥交换
    Connected,      // 已连接（实时协作）
    Degraded,       // 质量降级（Phase 6）
    Paused,         // 同步暂停（断线 5s+，Phase 6）
    Reconnecting,   // 重连中（断线 3s 内）
    Closed,         // 已关闭
}

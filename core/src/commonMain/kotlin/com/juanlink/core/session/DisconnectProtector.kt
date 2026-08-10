package com.juanlink.core.session

import com.juanlink.core.util.nowEpochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 断线保护状态机：
 *
 * - 断线 → 立即进入 Reconnecting，由 SessionManager 的重连循环持续尝试恢复
 * - 断线 > 5s → 暂停同步（本地继续可画，提示「同步已暂停」）
 * - **永不自动关闭**：只要用户不主动断开，会话就一直保持可恢复状态（需求：断开必须显式）
 *
 * 全程无服务器；重连成功后由握手回调推进到 Connected 并重放未 ack 操作（OpSyncEngine）。
 * 重连循环与状态推进归 SessionManager 管，本类只负责「何时提示暂停」的计时。
 */
class DisconnectProtector(
    private val scope: CoroutineScope,
    private val onPaused: () -> Unit,
    private val pauseMs: Long = 5000,
    private val tickMs: Long = 250,
) {
    private var disconnectedAt: Long? = null
    private var pausedNotified = false
    private var ticker: Job? = null

    /** 传输断开：记录时间并启动计时 */
    fun onDisconnected() {
        disconnectedAt = nowEpochMillis()
        pausedNotified = false
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(tickMs)
                val d = disconnectedAt ?: continue
                val elapsed = nowEpochMillis() - d
                if (elapsed > pauseMs && !pausedNotified) {
                    pausedNotified = true
                    onPaused()
                }
            }
        }
    }

    /** 传输恢复：停止计时，重置状态（会话状态由握手回调推进到 Connected） */
    fun onReconnected() {
        disconnectedAt = null
        pausedNotified = false
        ticker?.cancel()
    }

    val isDisconnected: Boolean get() = disconnectedAt != null

    fun stop() {
        ticker?.cancel()
        disconnectedAt = null
    }
}

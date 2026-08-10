package com.juanlink.core.quality

import com.juanlink.core.session.DisconnectProtector
import com.juanlink.core.util.nowEpochMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class QualityTest {

    private fun monitor(sendProbe: (Int, Long) -> Unit = { _, _ -> }): QualityMonitor =
        QualityMonitor(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), sendProbe, probeIntervalMs = 10_000)

    @Test
    fun excellentGradeForLowLatencyAndNoLoss() {
        val m = monitor()
        val base = nowEpochMillis()
        // 低延迟 (<80ms)，无丢包
        m.probe(); m.probe(); m.probe(); m.probe(); m.probe()
        for (i in 0 until 5) m.onPong(i, base - 50) // RTT 50ms
        assertEquals(QualityGrade.Excellent, m.grade.value)
    }

    @Test
    fun normalGradeForHigherLatency() {
        val m = monitor()
        val base = nowEpochMillis()
        m.probe(); m.probe(); m.probe()
        for (i in 0 until 3) m.onPong(i, base - 120) // RTT 120ms → Normal
        assertEquals(QualityGrade.Normal, m.grade.value)
    }

    @Test
    fun unstableGradeForHighLatency() {
        val m = monitor()
        val base = nowEpochMillis()
        m.probe(); m.probe()
        for (i in 0 until 2) m.onPong(i, base - 500) // RTT 500ms → Unstable
        assertEquals(QualityGrade.Unstable, m.grade.value)
    }

    @Test
    fun unstableGradeForPacketLoss() {
        val m = monitor()
        // 发送 10 个探测，只回 6 个 → 丢包 40% → Unstable
        repeat(10) { m.probe() }
        for (i in 0 until 6) m.onPong(i, nowEpochMillis() - 30)
        assertEquals(QualityGrade.Unstable, m.grade.value)
        assertTrue(m.metrics.value.packetLossPct >= 40.0)
    }

    @Test
    fun policyMapping() {
        assertEquals(SyncPolicy.Full, QualityPolicyEngine.policyFor(QualityGrade.Excellent))
        assertEquals(SyncPolicy.Optimized, QualityPolicyEngine.policyFor(QualityGrade.Normal))
        assertEquals(SyncPolicy.Blocked, QualityPolicyEngine.policyFor(QualityGrade.Unstable))
    }
}

class DisconnectProtectorTest {

    @Test
    fun pausesAfterPauseWindow() = runBlocking {
        var paused = false
        val p = DisconnectProtector(
            scope = this,
            onPaused = { paused = true },
            pauseMs = 200, tickMs = 30,
        )
        p.onDisconnected()
        delay(400) // > pauseMs
        assertTrue(paused, "超过暂停窗口应触发 onPaused")
        p.stop()
    }

    @Test
    fun recoveryStopsTimer() = runBlocking {
        var paused = false
        val p = DisconnectProtector(
            scope = this,
            onPaused = { paused = true },
            pauseMs = 200, tickMs = 30,
        )
        p.onDisconnected()
        delay(100) // < pauseMs
        p.onReconnected()
        delay(400)
        assertFalse(paused, "恢复后不应再触发 onPaused")
        p.stop()
    }

    @Test
    fun neverClosesAutomatically() = runBlocking {
        // 需求：只要用户不主动断开就保持可恢复状态——长时间断线不得自动关闭。
        // 新 API 已无 onClosed/closeMs：断线后仅触发暂停提示，保护计时一直保持。
        var paused = false
        val p = DisconnectProtector(
            scope = this,
            onPaused = { paused = true },
            pauseMs = 200, tickMs = 30,
        )
        p.onDisconnected()
        delay(800) // 远超旧 closeMs(30s 按比例折算)，仍应处于保护状态
        assertTrue(paused, "断线后应提示暂停")
        assertTrue(p.isDisconnected, "长时间断线后仍处于断线保护状态（未自动关闭）")
        p.stop()
    }
}

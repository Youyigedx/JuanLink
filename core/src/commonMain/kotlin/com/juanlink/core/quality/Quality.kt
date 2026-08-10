package com.juanlink.core.quality

import com.juanlink.core.util.nowEpochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 通信质量等级 */
enum class QualityGrade { Excellent, Normal, Unstable }

/** 实时质量指标 */
data class QualityMetrics(
    val latencyMs: Double,
    val jitterMs: Double,
    val packetLossPct: Double,
    val throughputKbps: Double,
    val stableSeconds: Long,
)

/**
 * 通信质量监测器。
 *
 * 周期 1s 发送 QualityProbe，对端回 Pong；滑动窗口（近 20 样本）计算：
 * 延迟（RTT 中位数）、抖动（标准差）、丢包率（窗口内）、吞吐。
 *
 * 分级规则（与需求文档一致）：
 * - Excellent：延迟 < 80ms 且丢包 < 1%
 * - Normal：80ms ≤ 延迟 ≤ 200ms 或 1% ≤ 丢包 ≤ 5%
 * - Unstable：延迟 > 200ms 或丢包 > 5% → 禁止进入实时协作
 *
 * 全程无服务器；质量差不换路，只降级或中止。
 */
class QualityMonitor(
    private val scope: CoroutineScope,
    private val sendProbe: (seq: Int, ts: Long) -> Unit,
    private val probeIntervalMs: Long = 1000,
) {
    private val rtts = ArrayDeque<Long>()
    private val probeTimes = HashMap<Int, Long>()
    private var sentWindow = 0
    private var pongWindow = 0
    private var windowStart = nowEpochMillis()

    private val _grade = MutableStateFlow(QualityGrade.Normal)
    val grade: StateFlow<QualityGrade> get() = _grade

    private val _metrics = MutableStateFlow(QualityMetrics(0.0, 0.0, 0.0, 0.0, 0))
    val metrics: StateFlow<QualityMetrics> get() = _metrics

    private var seq = 0
    private var stableSince = nowEpochMillis()
    private var probeJob: Job? = null

    /** 开始周期探测 */
    fun start() {
        probeJob = scope.launch {
            while (isActive) {
                probe()
                delay(probeIntervalMs)
            }
        }
    }

    fun stop() {
        probeJob?.cancel()
    }

    /** 收到对端 Pong（seq 为对应 Probe 的序号，ts 为 Probe 的发送时间戳回显） */
    fun onPong(seq: Int, ts: Long) {
        pongWindow++
        probeTimes.remove(seq)
        val rtt = nowEpochMillis() - ts
        rtts.addLast(rtt)
        while (rtts.size > WINDOW) rtts.removeFirst()
        evaluate()
    }

    /** 主动触达：发送一个探测包 */
    fun probe() {
        val s = seq++
        val ts = nowEpochMillis()
        probeTimes[s] = ts
        sentWindow++
        sendProbe(s, ts)
    }

    private fun evaluate() {
        val latency = if (rtts.isEmpty()) 0.0 else rtts.sorted()[rtts.size / 2].toDouble()
        val jitter = if (rtts.size < 2) 0.0 else {
            val mean = rtts.average()
            kotlin.math.sqrt(rtts.sumOf { (it - mean) * (it - mean) } / rtts.size)
        }
        val loss = if (sentWindow == 0) 0.0
        else ((sentWindow - pongWindow).toDouble() / sentWindow) * 100.0

        val grade = when {
            latency < EXCELLENT_LATENCY_MS && loss < EXCELLENT_LOSS_PCT -> QualityGrade.Excellent
            latency <= NORMAL_LATENCY_MS && loss <= NORMAL_LOSS_PCT -> QualityGrade.Normal
            else -> QualityGrade.Unstable
        }
        if (grade != QualityGrade.Unstable) {
            stableSince = nowEpochMillis()
        }
        _metrics.value = QualityMetrics(
            latencyMs = latency,
            jitterMs = jitter,
            packetLossPct = loss,
            throughputKbps = 0.0,
            stableSeconds = (nowEpochMillis() - stableSince) / 1000,
        )
        _grade.value = grade
    }

    companion object {
        const val WINDOW = 20
        const val EXCELLENT_LATENCY_MS = 80.0
        const val EXCELLENT_LOSS_PCT = 1.0
        const val NORMAL_LATENCY_MS = 200.0
        const val NORMAL_LOSS_PCT = 5.0
    }
}

/**
 * 质量策略引擎：把分级转换为同步策略（压缩/降频/优先级/限速）。
 */
enum class SyncPolicy {
    /** 全量实时 */
    Full,

    /** 自动优化：笔迹抽稀、图片限速、非关键帧降频、载荷压缩 */
    Optimized,

    /** 不稳定：禁止进入实时协作 */
    Blocked,
}

object QualityPolicyEngine {
    fun policyFor(grade: QualityGrade): SyncPolicy = when (grade) {
        QualityGrade.Excellent -> SyncPolicy.Full
        QualityGrade.Normal -> SyncPolicy.Optimized
        QualityGrade.Unstable -> SyncPolicy.Blocked
    }
}

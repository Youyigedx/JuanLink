package com.juanlink.composeui

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juanlink.composeui.platform.InMemorySnapshotIo
import com.juanlink.composeui.platform.InMemoryTurnConfigIo
import com.juanlink.composeui.platform.SnapshotIo
import com.juanlink.composeui.platform.TurnConfigIo
import com.juanlink.composeui.platform.createPlatformTransport
import com.juanlink.composeui.platform.decodeImageDimensions
import com.juanlink.composeui.ui.ToolState
import com.juanlink.core.canvas.CanvasDocument
import com.juanlink.core.canvas.CanvasOp
import com.juanlink.core.canvas.ImageTransfer
import com.juanlink.core.canvas.OpSyncEngine
import com.juanlink.core.model.Affine2
import com.juanlink.core.model.Viewport
import com.juanlink.core.pairing.Candidate
import com.juanlink.core.pairing.PairingCodec
import com.juanlink.core.pairing.PairingInfo
import com.juanlink.core.pairing.PairingManager
import com.juanlink.core.quality.QualityGrade
import com.juanlink.core.quality.QualityMonitor
import com.juanlink.core.session.SessionManager
import com.juanlink.core.session.SessionState
import com.juanlink.core.snapshot.CanvasSnapshot
import com.juanlink.core.snapshot.SnapshotJson
import com.juanlink.core.snapshot.SnapshotStore
import com.juanlink.core.transport.Transport
import com.juanlink.core.transport.TurnCapable
import com.juanlink.core.transport.TurnConfigJson
import com.juanlink.core.transport.TurnServer
import com.juanlink.core.util.nowEpochMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用状态：画布 + P2P 会话 + 操作同步 + 工具状态的单一装配点。
 * 桌面与安卓共用；传输与快照持久化通过构造注入平台实现。
 */
class AppState(
    private val transport: Transport = createPlatformTransport(),
    private val snapshotIo: SnapshotIo = InMemorySnapshotIo(),
    private val turnConfigIo: TurnConfigIo = InMemoryTurnConfigIo(),
) {

    val document = CanvasDocument("canvas-1")

    private val session: SessionManager
    val engine: OpSyncEngine
    private val pairingManager = PairingManager()

    val viewport = mutableStateOf(Viewport(0f, 0f, 1f))
    val tools = ToolState()

    var pairing by mutableStateOf<PairingInfo?>(null)
    var pairingCode by mutableStateOf<String?>(null)
    var statusText by mutableStateOf("未连接")
    var isConnected by mutableStateOf(false)
    var showConnection by mutableStateOf(false)

    /** 有进行中的会话（待配对/握手/已连接/重连中）→ 显示"断开连接"入口 */
    var inSession by mutableStateOf(false)
        private set

    /** 撤销/重做可用性（由 engine.onStateChange 驱动，按钮据此启用） */
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** TURN 设置面板开关（桌面顶部栏/安卓顶部操作栏入口） */
    var showSettings by mutableStateOf(false)

    /** 用户自定义 TURN 服务器（设置面板编辑态；空 = 仅默认公共列表） */
    var turnServers by mutableStateOf<List<TurnServer>>(emptyList())
        private set

    /** 文档版本（文档变更时自增，驱动画布/面板重组）。暴露为 State 供组件直接订阅 */
    private val _docVersion = mutableIntStateOf(0)
    val docVersion: State<Int> get() = _docVersion

    /** 质量监测与展示 */
    private val appScope = CoroutineScope(SupervisorJob())
    private val qualityMonitor = QualityMonitor(appScope, { seq, ts -> session.sendQualityProbe(seq, ts) }, 1000)
    var qualityGrade by mutableStateOf(QualityGrade.Normal)
        private set
    var qualityText by mutableStateOf("")
        private set

    /** 画布快照历史 */
    private val snapshotStore = SnapshotStore(50)
    var snapshots by mutableStateOf<List<CanvasSnapshot>>(emptyList())
        private set
    var autoIntervalSec by mutableStateOf(60)
    var showHistory by mutableStateOf(false)

    init {
        val deviceId = "dev-${nowEpochMillis() % 100000}"
        session = SessionManager(deviceId, transport)
        engine = OpSyncEngine(document, deviceId, session)
        session.opEngine = engine
        session.onStateChanged = { state -> handleState(state) }
        session.onPongReceived = { seq, ts -> qualityMonitor.onPong(seq, ts) }
        document.onChange = { _docVersion.value++ }
        // 撤销/重做按钮启用状态：操作应用/回放后刷新（Compose state 驱动重组）
        engine.onStateChange = {
            canUndo = engine.canUndo()
            canRedo = engine.canRedo()
        }

        appScope.launch {
            qualityMonitor.grade.collectLatest { grade ->
                qualityGrade = grade
                qualityText = when (grade) {
                    QualityGrade.Excellent -> "优秀"
                    QualityGrade.Normal -> "正常"
                    QualityGrade.Unstable -> "不稳定"
                }
                if (grade == QualityGrade.Unstable && isConnected) {
                    statusText = "当前双方网络环境无法保证实时交流稳定性，请改善网络环境后重新连接。"
                }
            }
        }
        qualityMonitor.start()

        // TURN 配置：加载用户自定义服务器并注入传输（自定义优先 + 默认公共列表 fallback）。
        // 在 createRoom/join 之前完成，确保下次分配/连接即用新列表。
        turnServers = runCatching {
            turnConfigIo.load()?.let { TurnConfigJson.decodeList(it) } ?: emptyList()
        }.getOrDefault(emptyList())
        if (turnServers.isNotEmpty()) {
            (transport as? TurnCapable)?.updateTurnServers(turnServers)
            println("[JUAN] TURN 自定义服务器已加载: ${turnServers.size} 台")
        }

        // 快照历史：加载持久化 + 自动捕获循环
        loadSnapshots()
        appScope.launch {
            while (isActive) {
                if (autoIntervalSec > 0) {
                    delay(autoIntervalSec * 1000L)
                    captureSnapshot()
                } else {
                    delay(1000)
                }
            }
        }
    }

    /** 手动/自动捕获画布快照 */
    fun captureSnapshot() {
        snapshotStore.capture(document)
        snapshots = snapshotStore.all
        saveSnapshots()
    }

    fun setAutoInterval(sec: Int) {
        autoIntervalSec = sec
    }

    private fun saveSnapshots() {
        runCatching { snapshotIo.save(SnapshotJson.encodeList(snapshotStore.all)) }
    }

    private fun loadSnapshots() {
        runCatching {
            snapshotIo.load()?.let {
                snapshotStore.loadAll(SnapshotJson.decodeList(it))
                snapshots = snapshotStore.all
            }
        }
    }

    /** 保存 TURN 服务器配置：注入传输（下次分配/连接生效）+ 持久化 */
    fun saveTurnConfig(servers: List<TurnServer>) {
        turnServers = servers
        (transport as? TurnCapable)?.updateTurnServers(servers)
        runCatching { turnConfigIo.save(TurnConfigJson.encodeList(servers)) }
        statusText = if (servers.isEmpty()) {
            "已清空自定义 TURN，使用公共服务器（尽力而为）"
        } else {
            "TURN 配置已保存，自定义服务器将优先尝试"
        }
    }

    /** 创建协作房间（发起方）：产出二维码 + 配对码（重活放后台线程，避免 UI 冻结） */
    fun createRoom() {
        statusText = "正在创建协作…"
        appScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    // TURN 中继兜底：逐台默认/自定义服务器分配 relayed 地址（后台线程 ~8s；失败仅丢中继，不致命）
                    val relayEp = (transport as? TurnCapable)?.allocateRelayEndpoint()
                    println(
                        "[JUAN] createRoom: turn 中继=${
                            relayEp?.let { "${it.serverHost}:${it.serverPort} -> ${it.relayedHost}:${it.relayedPort}" } ?: "null"
                        }",
                    )
                    // TCP 局域网候选由 startInitiator 在 transport.start（绑定监听端口）之后自动枚举，
                    // 再与中继候选合并去重（≤4 控二维码体积）。
                    // ⚠️ 不能在此处提前调 localCandidates()：start 前无监听端口，枚举为空会丢 TCP 兜底候选。
                    val external = listOfNotNull(
                        relayEp?.let { Candidate(it.relayedHost, it.relayedPort, type = "relay") },
                    )
                    val baseInfo = if (external.isNotEmpty()) {
                        session.startInitiator(pairingManager, external)
                    } else {
                        session.startInitiator(pairingManager)
                    }
                    // 携带实际使用的 TURN 服务器地址：Responder 必须连与 Initiator 相同的服务器
                    val info = if (relayEp != null) {
                        baseInfo.copy(turn = "${relayEp.serverHost}:${relayEp.serverPort}")
                    } else {
                        baseInfo
                    }
                    println("[JUAN] createRoom: 二维码候选 ${info.candidates.joinToString { "${it.host}:${it.port}(type=${it.type})" }}, turn=${info.turn}")
                    val code = pairingManager.registerCode(info)
                    info to code
                }
            }
            result.onSuccess { (info, code) ->
                pairing = info
                pairingCode = code
                showConnection = true
                val hasTcp = info.candidates.any { it.type == "tcp" }
                val hasRelay = info.candidates.any { it.type == "relay" }
                statusText = when {
                    hasTcp && hasRelay -> "等待扫码/配对…（局域网直连 + 中继兜底）"
                    hasTcp -> "等待扫码/配对…（支持局域网直连）"
                    hasRelay -> "等待扫码/配对…（仅中继可达）"
                    else -> "等待扫码/配对…"
                }
            }.onFailure { e ->
                statusText = "创建协作失败，请重试"
                println("[JUAN] createRoom failed: ${e.stackTraceToString()}")
            }
        }
    }

    /** 加入协作（响应方）：粘贴对方二维码连接串（TCP 连接放后台线程） */
    fun join(raw: String) {
        val info = runCatching { PairingCodec.fromQrPayload(raw) }.getOrNull()
        if (info == null) {
            statusText = "连接串无效，请粘贴完整二维码内容"
            return
        }
        val hasTcp = info.candidates.any { it.type == "tcp" }
        val hasRelay = info.candidates.any { it.type == "relay" }
        println("[JUAN] join: 二维码候选 ${info.candidates.joinToString { "${it.host}:${it.port}(type=${it.type})" }}, hasTcp=$hasTcp, hasRelay=$hasRelay")
        showConnection = true
        statusText = when {
            hasTcp -> "正在尝试局域网直连…"
            hasRelay -> "正在尝试中继连接…"
            else -> "正在连接…"
        }
        appScope.launch {
            val ok = withContext(Dispatchers.Default) {
                runCatching { session.connectAsResponder(info) }.getOrElse {
                    println("[JUAN] join failed: ${it.stackTraceToString()}")
                    false
                }
            }
            statusText = when {
                ok -> "正在安全握手…"
                hasTcp && !hasRelay -> "连接失败：局域网直连失败，请连接同一 Wi-Fi 后重试"
                hasRelay -> "连接失败：中继不可达，请稍后重试"
                else -> "连接失败（地址不可达）"
            }
        }
    }

    fun disconnect() {
        session.disconnect()
        isConnected = false
        pairing = null
    }

    /** 关闭连接面板（连接状态由状态栏持续显示） */
    fun closePanel() {
        showConnection = false
    }

    fun applyLocal(op: CanvasOp) {
        engine.applyLocal(op)
    }

    /** 导入图片字节到画布中心（分块同步到对端） */
    fun importImageBytes(bytes: ByteArray) {
        val dims = decodeImageDimensions(bytes)
        if (dims == null || dims.first <= 0 || dims.second <= 0) {
            statusText = "无法解析图片文件"
            return
        }
        val id = "img-${nowEpochMillis()}"
        val vp = viewport.value
        val transform = Affine2(e = vp.cx - dims.first / 2.0, f = vp.cy - dims.second / 2.0)
        ImageTransfer.sendImage(
            engine = engine,
            imageId = id,
            layerId = "layer-1",
            bytes = bytes,
            width = dims.first.toFloat(),
            height = dims.second.toFloat(),
            transform = transform,
        )
        statusText = "已导入图片（正在同步）"
    }

    fun undo() {
        engine.undo()
    }

    fun redo() {
        engine.redo()
    }

    private fun handleState(state: SessionState) {
        isConnected = state == SessionState.Connected
        // 有会话即显示"断开"入口（待配对/握手/已连接/重连/暂停均可主动断开）
        inSession = state != SessionState.Idle && state != SessionState.Closed
        statusText = when (state) {
            SessionState.Connected -> "已连接 · 实时协作中"
            SessionState.Pairing -> "等待扫码/配对…"
            SessionState.Handshaking -> "正在安全握手…"
            SessionState.Reconnecting -> "网络中断，正在重连…"
            SessionState.Paused -> "同步已暂停，正在恢复…"
            SessionState.Closed -> "连接已关闭"
            else -> "未连接"
        }
    }
}

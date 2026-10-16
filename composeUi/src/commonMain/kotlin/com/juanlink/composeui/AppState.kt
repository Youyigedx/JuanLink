package com.juanlink.composeui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.juanlink.composeui.draw.DrawBoxHost
import com.juanlink.composeui.draw.DrawingSnapshot
import com.juanlink.composeui.draw.DrawingSnapshotJson
import com.juanlink.composeui.draw.DrawingSnapshotStore
import com.juanlink.composeui.platform.InMemoryRendezvousConfigIo
import com.juanlink.composeui.platform.InMemorySnapshotIo
import com.juanlink.composeui.platform.InMemoryTurnConfigIo
import com.juanlink.composeui.platform.RendezvousConfigIo
import com.juanlink.composeui.platform.RendezvousConfigJson
import com.juanlink.composeui.platform.SnapshotIo
import com.juanlink.composeui.platform.TurnConfigIo
import com.juanlink.composeui.platform.createPlatformTransport
import com.juanlink.composeui.platform.decodeImageDimensions
import com.juanlink.core.draw.DrawOp
import com.juanlink.core.draw.DrawOpCodec
import com.juanlink.core.draw.OpSyncEngine
import com.juanlink.core.draw.drawOpTypeCode
import com.juanlink.core.pairing.Candidate
import com.juanlink.core.pairing.PairingCodec
import com.juanlink.core.pairing.PairingInfo
import com.juanlink.core.pairing.PairingManager
import com.juanlink.core.quality.QualityGrade
import com.juanlink.core.quality.QualityMonitor
import com.juanlink.core.rendezvous.DefaultRendezvousClient
import com.juanlink.core.rendezvous.PairingInfoWire
import com.juanlink.core.rendezvous.RendezvousClient
import com.juanlink.core.rendezvous.RendezvousCodeFormat
import com.juanlink.core.rendezvous.RendezvousConfig
import com.juanlink.core.session.SessionManager
import com.juanlink.core.session.SessionState
import com.juanlink.core.transport.Transport
import com.juanlink.core.transport.TurnCapable
import com.juanlink.core.transport.TurnConfigJson
import com.juanlink.core.transport.TurnServer
import com.juanlink.core.util.nowEpochMillis
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.TextAlignment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用状态：DrawBox 画布宿主 + P2P 会话 + 操作同步 + 快照历史的单一装配点。
 * 桌面与安卓共用；传输与快照持久化通过构造注入平台实现。
 *
 * 第 4 步起画布改为 DrawBox：本地意图走 [host.onLocalIntent]（diff 后广播同步
 * op），远程元素变化由 host 的 `apply(op)` 直接改写响应式 state 驱动重组。
 */
class AppState(
    private val transport: Transport = createPlatformTransport(),
    private val snapshotIo: SnapshotIo = InMemorySnapshotIo(),
    private val turnConfigIo: TurnConfigIo = InMemoryTurnConfigIo(),
    private val rendezvousConfigIo: RendezvousConfigIo = InMemoryRendezvousConfigIo(),
    /** 会合客户端可注入：测试用假实现，生产用平台默认（JDK HttpClient） */
    private val rendezvous: RendezvousClient = DefaultRendezvousClient,
) {

    /** DrawBox 画布宿主（响应式 state + 本地意图 + SyncDocument<DrawOp>） */
    val host = DrawBoxHost()

    /** 操作同步引擎（host ↔ 会话）。单机模式也接线，未连接时 sendOp 失败无害 */
    lateinit var drawSync: OpSyncEngine<DrawOp>

    private val session: SessionManager
    private val pairingManager = PairingManager()

    var pairing by mutableStateOf<PairingInfo?>(null)
    var pairingCode by mutableStateOf<String?>(null)

    /** 会合服务配置（短配对码）。未配置 → 短码仅在同机可用，跨设备须用连接串/扫码 */
    var rendezvousConfig by mutableStateOf(RendezvousConfig())
        private set

    /** 会合服务器连通性（设置面板显示）；null = 未测 */
    var rendezvousOnline by mutableStateOf<Boolean?>(null)
        private set

    /** 短码是否由服务器下发（true=可跨设备；false=本地兜底，仅同机可解析） */
    var pairingCodeIsRemote by mutableStateOf(false)
        private set
    var statusText by mutableStateOf("未连接")
    var isConnected by mutableStateOf(false)
    var showConnection by mutableStateOf(false)

    /** 有进行中的会话（待配对/握手/已连接/重连中）→ 显示"断开连接"入口 */
    var inSession by mutableStateOf(false)
        private set

    /** 撤销/重做可用性（由 drawSync.onStateChange 驱动，按钮据此启用） */
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** 文本编辑对话框：当前编辑的文本元素 id（null = 未在编辑） */
    var editingTextId by mutableStateOf<String?>(null)
        private set

    /** 文本编辑对话框预填内容（编辑既有文本时读当前 text；新建为空串） */
    var textDraft by mutableStateOf("")
        private set

    /** 文本编辑对话框预填字号（编辑既有文本时读当前 fontSize） */
    var textFontSize by mutableStateOf(24f)
        private set

    /** 文本编辑对话框预填对齐（编辑既有文本时读当前 alignment） */
    var textAlignment by mutableStateOf(TextAlignment.LEFT)
        private set

    /** 文本编辑对话框预填字体（编辑既有文本时读当前 fontFamilyKey） */
    var textFontFamily by mutableStateOf("sans")
        private set

    /** 辅助网格开关（本地会话装饰，不同步、不进导出） */
    var showGrid by mutableStateOf(false)
        private set

    fun toggleGrid() {
        showGrid = !showGrid
    }

    /** TURN 设置面板开关（桌面顶部栏/安卓顶部操作栏入口） */
    var showSettings by mutableStateOf(false)

    /** TURN 配置引导面板可见性（首次启动自动弹；未配置点协作时强制弹） */
    var showSetup by mutableStateOf(false)
    /** 引导是否锁定（未配置点协作触发 → 不可关闭，需保存或跳过） */
    var setupLocked by mutableStateOf(false)
    /** 本会话是否已显式选择「仅局域网直连，跳过配置」（内存态，重启重置） */
    var setupSkipped by mutableStateOf(false)

    /** 用户自定义 TURN 服务器（设置面板编辑态；空 = 仅默认公共列表） */
    var turnServers by mutableStateOf<List<TurnServer>>(emptyList())
        private set

    /** 质量监测与展示 */
    private val appScope = CoroutineScope(SupervisorJob())
    private val qualityMonitor = QualityMonitor(appScope, { seq, ts -> session.sendQualityProbe(seq, ts) }, 1000)
    var qualityGrade by mutableStateOf(QualityGrade.Normal)
        private set
    var qualityText by mutableStateOf("")
        private set

    /** 画布快照历史（DrawBox SerializableDrawing JSON） */
    private val snapshotStore = DrawingSnapshotStore(50)
    var snapshots by mutableStateOf<List<DrawingSnapshot>>(emptyList())
        private set
    var autoIntervalSec by mutableStateOf(60)
    var showHistory by mutableStateOf(false)

    init {
        val deviceId = "dev-${nowEpochMillis() % 100000}"
        session = SessionManager(deviceId, transport)
        drawSync = OpSyncEngine(
            doc = host,
            encodeOp = { DrawOpCodec.encode(it) },
            decodeOp = { DrawOpCodec.decode(it) },
            opTypeOf = { drawOpTypeCode(it) },
            peerId = deviceId,
            transport = session,
        )
        host.engine = drawSync
        session.opEngine = drawSync
        // 文本编辑事件缝：宿主命中 Text（新建/双击/二次点击）→ 弹编辑框并预填内容与样式
        host.onTextEditRequested = { id ->
            editingTextId = id
            val el = host.state.elements.firstOrNull { it.id == id } as? Element.Text
            textDraft = el?.text ?: ""
            textFontSize = el?.fontSize ?: 24f
            textAlignment = el?.alignment ?: TextAlignment.LEFT
            textFontFamily = el?.fontFamilyKey ?: "sans"
        }
        session.onStateChanged = { state -> handleState(state) }
        session.onPongReceived = { seq, ts -> qualityMonitor.onPong(seq, ts) }
        // 撤销/重做按钮启用状态：操作应用/回放后刷新（Compose state 驱动重组）
        drawSync.onStateChange = {
            canUndo = drawSync.canUndo()
            canRedo = drawSync.canRedo()
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
        // 首次启动未配置自定义 TURN → 自动弹配置引导（可关闭，先本地画画）
        if (turnServers.isEmpty()) showSetup = true

        // 会合服务配置：加载并异步探活（不阻塞启动；探活只在后台线程发一个 GET /health）
        rendezvousConfig = runCatching {
            rendezvousConfigIo.load()?.let { RendezvousConfigJson.decode(it) }
        }.getOrNull() ?: RendezvousConfig()
        if (rendezvousConfig.enabled) {
            appScope.launch {
                rendezvousOnline = withContext(Dispatchers.IO) {
                    rendezvous.ping(rendezvousConfig)
                }
                println("[JUAN] 会合服务器 ${rendezvousConfig.baseUrl()} 探活=${rendezvousOnline}")
            }
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
        snapshotStore.capture(host.toPayLoad())
        snapshots = snapshotStore.all
        saveSnapshots()
    }

    fun setAutoInterval(sec: Int) {
        autoIntervalSec = sec
    }

    private fun saveSnapshots() {
        runCatching { snapshotIo.save(DrawingSnapshotJson.encodeList(snapshotStore.all)) }
    }

    private fun loadSnapshots() {
        runCatching {
            snapshotIo.load()?.let {
                snapshotStore.loadAll(DrawingSnapshotJson.decodeList(it))
                snapshots = snapshotStore.all
            }
        }
    }

    // ================================================================ 工具状态访问器
    // 读 DrawBox state（响应式），写经 intent（reducer 改 state + diff 广播同步 op）。

    /** 当前工具模式 */
    val currentMode: Mode get() = host.state.mode
    /** 当前描边颜色 */
    val strokeColor: androidx.compose.ui.graphics.Color get() = host.state.strokeColor
    /** 当前描边粗细 */
    val strokeWidth: Float get() = host.state.strokeWidth
    /** 当前透明度 */
    val opacity: Float get() = host.state.opacity

    fun setMode(mode: Mode) = host.onLocalIntent(Intent.SetMode(mode))
    fun setStrokeColor(color: androidx.compose.ui.graphics.Color) = host.onLocalIntent(Intent.SetStrokeColor(color))
    fun setStrokeWidth(width: Float) = host.onLocalIntent(Intent.SetStrokeWidth(width))
    fun setOpacity(value: Float) = host.onLocalIntent(Intent.SetOpacity(value))

    /** 选中元素置顶/置底（替代旧图层面板） */
    fun bringToFront() = host.onLocalIntent(Intent.BringSelectionToFront)
    fun sendToBack() = host.onLocalIntent(Intent.SendSelectionToBack)
    /** 删除选中元素 */
    fun deleteSelected() = host.onLocalIntent(Intent.DeleteSelected)
    /** 复制选中元素（宿主生成新 id + 偏移，diff 广播同步） */
    fun duplicateSelected(): Int = host.duplicateSelected()
    /** 清空画布：批量移除同步 + 单条撤销，保留背景色/工具样式 */
    fun clearCanvas() = host.resetCanvas()

    // ================================================================ 文本编辑

    /**
     * 提交文本编辑：UpdateText + 样式（字号/对齐/字体）一并广播同步；
     * 空串视为放弃（新建的空占位一并删除）。
     * 样式参数带默认值，旧调用（只传文本）保持兼容。
     */
    fun commitTextEdit(
        text: String,
        fontSize: Float = 24f,
        alignment: TextAlignment = TextAlignment.LEFT,
        fontFamily: String = "sans",
    ) {
        val id = editingTextId ?: return
        editingTextId = null
        if (text.isBlank()) {
            host.onLocalIntent(Intent.DeleteElement(id))
        } else {
            host.onLocalIntent(Intent.UpdateText(id, text))
            host.applyTextStyle(id, fontSize, alignment, fontFamily)
        }
    }

    /** 取消文本编辑：若元素仍是空占位则删除（Mode.TEXT 点出空框直接取消不留痕） */
    fun dismissTextEdit() {
        val id = editingTextId ?: return
        editingTextId = null
        val el = host.state.elements.firstOrNull { it.id == id }
        if (el is Element.Text && el.text.isBlank()) host.onLocalIntent(Intent.DeleteElement(id))
    }

    // ================================================================ 协作

    /** 未配置 TURN 且未跳过 → 弹强制引导、置 statusText、返回 false（不执行协作操作） */
    private fun requireTurnSetup(): Boolean {
        if (turnServers.isEmpty() && !setupSkipped) {
            showSetup = true
            setupLocked = true
            statusText = "请先配置 TURN 服务器，才能进行跨网协作"
            return false
        }
        return true
    }

    /** 关闭引导：仅非锁定态可用；不标记跳过（点协作仍会强制引导） */
    fun dismissSetup() {
        if (setupLocked) return
        showSetup = false
    }

    /** 锁定引导下显式跳过：仅局域网直连，本会话不再拦截 */
    fun skipSetup() {
        setupSkipped = true
        showSetup = false
        setupLocked = false
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
        // 保存了至少一台服务器 → 配置完成，关闭引导（含锁定态）
        if (servers.isNotEmpty()) {
            showSetup = false
            setupLocked = false
        }
    }

    /** 创建协作房间（发起方）：产出二维码 + 配对码（重活放后台线程，避免 UI 冻结） */
    fun createRoom() {
        if (!requireTurnSetup()) return
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

                    // ---- 短配对码 ----
                    // 本地先生成候选码（服务端权威分配，本地仅作兜底），再决定本地登记还是登记到会合服务器。
                    val payloadJson = PairingCodec.toQrPayload(info)
                    val localCode = RendezvousCodeFormat.generate(
                        com.juanlink.core.crypto.CryptoEngine.randomBytes(8),
                    )
                    if (rendezvousConfig.enabled) {
                        // 服务端模式：登记失败即报错，绝不让用户拿到一个「谁也解析不了」的死码
                        val issued = withContext(Dispatchers.IO) {
                            rendezvous.register(
                                PairingInfoWire(json = payloadJson, code = localCode),
                                rendezvousConfig,
                            )
                        } ?: throw IllegalStateException(
                            "会合服务器不可达（${rendezvousConfig.baseUrl()}），无法下发短配对码",
                        )
                        rendezvousOnline = true
                        pairingManager.registerCode(info, issued.code)
                        Triple(info, issued.code, true)
                    } else {
                        // 未配置服务器：短码仅本机可解析（同机双开自测），跨设备请扫码或用连接串
                        rendezvousOnline = null
                        val code = pairingManager.registerCode(info, localCode)
                        Triple(info, code, false)
                    }
                }
            }
            result.onSuccess { (info, code, remote) ->
                pairing = info
                pairingCode = code
                pairingCodeIsRemote = remote
                showConnection = true
                val hasTcp = info.candidates.any { it.type == "tcp" }
                val hasRelay = info.candidates.any { it.type == "relay" }
                val netHint = when {
                    hasTcp && hasRelay -> "局域网直连 + 中继兜底"
                    hasTcp -> "支持局域网直连"
                    hasRelay -> "仅中继可达"
                    else -> ""
                }
                statusText = if (remote) {
                    if (netHint.isEmpty()) "等待对方输入配对码…" else "等待对方输入配对码…（$netHint）"
                } else {
                    "配对码仅本机可用；跨设备请让对方扫码或粘贴连接串"
                }
            }.onFailure { e ->
                statusText = when {
                    e.message?.contains("会合服务器不可达") == true ->
                        "会合服务器不可达，请检查设置中的服务器地址"
                    else -> "创建协作失败，请重试"
                }
                println("[JUAN] createRoom failed: ${e.stackTraceToString()}")
            }
        }
    }

    /**
     * 加入协作（响应方）。
     *
     * 输入形态自动分流：
     * - **短配对码**（如 `5689-ABCN`）→ 经会合服务器换回完整配对信息
     * - **完整连接串**（二维码 JSON）→ 直接解析，无需服务器（保留兼容）
     */
    fun join(raw: String) {
        if (!requireTurnSetup()) return
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            statusText = "请输入配对码，或粘贴完整连接串"
            return
        }
        if (!RendezvousCodeFormat.looksLikeCode(trimmed) && !trimmed.startsWith("{")) {
            statusText = "配对码格式不对（应为 5689-ABCN 形式），或粘贴完整连接串"
            return
        }

        statusText = "正在解析配对信息…"
        appScope.launch {
            val info = withContext(Dispatchers.Default) {
                resolvePairingInfo(trimmed)
            }
            if (info == null) return@launch   // 失败原因已由 resolvePairingInfo 写入 statusText

            val hasTcp = info.candidates.any { it.type == "tcp" }
            val hasRelay = info.candidates.any { it.type == "relay" }
            println("[JUAN] join: 候选 ${info.candidates.joinToString { "${it.host}:${it.port}(type=${it.type})" }}, hasTcp=$hasTcp, hasRelay=$hasRelay")
            showConnection = true
            statusText = when {
                hasTcp -> "正在尝试局域网直连…"
                hasRelay -> "正在尝试中继连接…"
                else -> "正在连接…"
            }
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

    /**
     * 把用户输入解析成 [PairingInfo]。
     * 短码 → 服务器换取；连接串 → 本地解析。失败时写入 statusText 并返回 null。
     */
    private suspend fun resolvePairingInfo(input: String): PairingInfo? {
        if (input.startsWith("{")) {
            val p = runCatching { PairingCodec.fromQrPayload(input) }.getOrNull()
            if (p == null) {
                statusText = "连接串无效，请粘贴完整二维码内容"
                return null
            }
            // 校验魔数/版本/有效期（短码路径由服务器 TTL 保证，连接串必须本地校验）
            if (!PairingCodec.isValid(p)) {
                statusText = "连接串已过期或版本不匹配，请让对方重新创建"
                return null
            }
            return p
        }

        val code = RendezvousCodeFormat.normalize(input)
        if (!RendezvousCodeFormat.validate(code)) {
            statusText = "配对码校验位不对，请核对后重新输入"
            return null
        }
        if (!rendezvousConfig.enabled) {
            // 未配置服务器：尝试本机兜底（同机双开自测场景）
            val local = pairingManager.lookupCode(code)
            if (local != null) return local
            statusText = "未配置会合服务器，无法远程解析配对码；请在设置中填写服务器地址"
            return null
        }

        statusText = "正在向服务器查询配对码…"
        val wire = withContext(Dispatchers.IO) {
            rendezvous.lookup(code, rendezvousConfig)
        }
        if (wire == null) {
            // 区分「服务器不可达」与「码不存在/过期」能让用户少走弯路
            val online = withContext(Dispatchers.IO) { rendezvous.ping(rendezvousConfig) }
            rendezvousOnline = online
            statusText = if (online) {
                "配对码无效、已过期或已被使用，请让对方重新创建"
            } else {
                "会合服务器不可达，请检查网络与设置中的服务器地址"
            }
            return null
        }
        rendezvousOnline = true
        val parsed = runCatching { PairingCodec.fromQrPayload(wire.json) }.getOrNull()
        if (parsed == null) {
            statusText = "服务器返回的配对信息无法解析，版本可能不匹配"
            return null
        }
        if (!PairingCodec.isValid(parsed)) {
            statusText = "该配对码已过期，请让对方重新创建"
            return null
        }
        return parsed
    }

    /** 保存会合服务配置：持久化 + 后台探活 */
    fun saveRendezvousConfig(config: RendezvousConfig) {
        val normalized = config.normalized()
        rendezvousConfig = normalized
        runCatching { rendezvousConfigIo.save(RendezvousConfigJson.encode(normalized)) }
        if (!normalized.enabled) {
            rendezvousOnline = null
            statusText = "已清除会合服务器；跨设备将只能扫码或用连接串"
            return
        }
        statusText = "正在测试会合服务器…"
        appScope.launch {
            val ok = withContext(Dispatchers.IO) { rendezvous.ping(normalized) }
            rendezvousOnline = ok
            statusText = if (ok) {
                "会合服务器已连通，短配对码可用"
            } else {
                "会合服务器不可达（${normalized.baseUrl()}），请检查地址与端口"
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

    /** 导入图片字节到画布中心（占位 upsert + 16KB 分块同步到对端） */
    fun importImageBytes(bytes: ByteArray) {
        val dims = decodeImageDimensions(bytes)
        if (dims == null || dims.first <= 0 || dims.second <= 0) {
            statusText = "无法解析图片文件"
            return
        }
        val w = dims.first.toFloat()
        val h = dims.second.toFloat()
        // 以世界原点为中心放置（默认视口下即画布中心）
        host.onLocalIntent(
            Intent.InsertImage(
                bytes = bytes,
                position = Offset(-w / 2f, -h / 2f),
                intrinsicSize = Size(w, h),
            ),
        )
        statusText = "已导入图片（正在同步）"
    }

    fun undo() {
        drawSync.undo()
    }

    fun redo() {
        drawSync.redo()
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

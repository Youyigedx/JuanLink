# Juan LinK — API 文档

> 核心 API 位于 `core`（KMP commonMain），桌面/移动端复用。
> 详见 `data-structures.md`（数据结构）与 `protocol.md`（线协议）。
>
> **文档约定**：本文只写**现行实现**的签名。已被重构替换的旧模型一律标注
> 「历史设计（已废弃）」，不作为可调用接口。
>
> 最近一次逐签名核对：2026-10-16，基线 `16affc9`。

## 1. 会话与会话管理

```kotlin
// 创建会话（二维码持有方 = Initiator）
val session = SessionManager(deviceId, transport)
session.opEngine = opEngine
val pairingInfo: PairingInfo = session.startInitiator(pairingManager, candidates)

// 加入（扫码 / 输入配对码 / 粘贴连接串方 = Responder）
val ok: Boolean = session.connectAsResponder(pairingInfo)

// 状态流
session.state: StateFlow<SessionState>   // Idle/Pairing/Handshaking/Connected/Reconnecting/Paused/Closed
session.role: Role?                      // Initiator / Responder
session.isConnected: Boolean
session.disconnect(reason)
```

`SessionManager` 同时实现 `OpTransport`，因此可直接作为同步引擎的传输层。

## 2. 操作同步

```kotlin
val engine = OpSyncEngine<DrawOp>(
    doc = host,                                  // SyncDocument<Op>
    encodeOp = { DrawOpCodec.encode(it) },
    decodeOp = { DrawOpCodec.decode(it) },
    opTypeOf = { drawOpTypeCode(it) },
    peerId = deviceId,
    transport = session,
)

// 本地操作（画笔/形状/图层/图片/撤销都以 DrawOp 表达）
engine.applyLocal(op, recordUndo = true): OpId?
engine.applyLocalWithInverse(op, inverse): OpId?
engine.applyLocalBatch(ops: List<Pair<Op, Op?>>): Boolean

engine.undo(): Boolean; engine.redo(): Boolean
engine.canUndo(): Boolean; engine.canRedo(): Boolean

// 远程操作（SessionManager 解密后自动分发，一般无需手调）
engine.onRemoteOp(envelope: OpEnvelope): Boolean
engine.onAck(lastAppliedSeq: Long)
engine.resendOutstanding()                   // 重连后重放未 ack 操作
```

> 类型说明：`OpSyncEngine` 对操作类型是**泛型** `Op`，本项目实例化为 `DrawOp`。
> 文档旧版写作 `CanvasOp`——那是**历史设计（已废弃）**，该类已不存在。

## 3. 图片传输

图片没有独立的 `ImageTransfer` 对象（**历史设计，已废弃**）。
现行流程由 `DrawBoxHost` 编排，`ImageChunking` 只负责切块：

```kotlin
// 分块辅助（core/draw/ImageChunking.kt）
ImageChunking.CHUNK_SIZE            // 16 KB
ImageChunking.chunkCount(bytes): Int
ImageChunking.chunkBytes(bytes): List<ByteArray>

// 发送流程（composeUi/DrawBoxHost 内实现）：
//   占位 upsert（imageData 为空）
//   → 逐块 DrawOp.ImageChunkOp
//   → DrawOp.ImageReadyOp（SHA-256 校验）
// 接收端在 apply(ImageReadyOp) 时做缺块检测 / 重组 / 校验。
```

对外入口仍是 `AppState.importImageBytes(bytes)`（见 §9）。

## 4. 画布文档

现行实现以 **`DrawBoxHost` 为宿主**，`DrawOp` 为线协议操作
（元素 upsert/remove、图片分块、`CanvasMeta`）——见 `data-structures.md`。

```kotlin
// 核心入口
host.onLocalIntent(intent: Intent)      // 本地意图 → reducer 改 state → diff 广播
host.apply(op: DrawOp)                  // 远程操作应用（响应式 state 驱动重组）
host.state                              // DrawBox 响应式状态
host.toPayLoad()                        // 快照序列化
```

### 4a. 画布宿主增强 API（`DrawBoxHost`）

```kotlin
host.resetCanvas()                          // 清空：批量移除同步 + 单条撤销，保留背景/样式/模式
host.duplicateSelected(): Int               // 复制选中（新 id + 偏移，diff 广播，选中副本）
host.applyTextStyle(id, fontSize, alignment, fontFamily)  // 文本样式同步
host.setViewport(viewport)                  // 本地相机直写（不同步、不进撤销）

// 背景色属画布内容，随协作同步（DrawOp.CanvasMetaOp）：
host.onLocalIntent(Intent.SetBgColor(color))   // 本地改 → diff 广播；远端 apply 同步
```

### 4b. 历史设计（已废弃，勿照抄）

以下为重构前的自研画布模型，`CanvasDocument` / `CanvasOp` / `Affine2` / `Layer`
**在当前代码中均不存在**，仅作沿革说明保留：

```kotlin
// ⚠️ 历史设计，已废弃 —— 代码中不存在
val doc = CanvasDocument("canvas-1")
doc.apply(op: CanvasOp)
doc.allStrokes(); doc.allImages(); doc.layers
doc.imageBytes(imageId): ByteArray?
doc.onChange = { }
```

## 5. 加密

```kotlin
val keyPair = CryptoEngine.generateX25519KeyPair()            // KeyPairRaw
val shared = CryptoEngine.computeSharedSecret(priv, peerPub)  // ByteArray
val keys = KeyDerivation.deriveKeys(shared, sessionId, nonce) // SessionKeys(encKey, macKey, ivBase)

val crypto = SessionCrypto(sessionId, keys)                   // 内含 ReplayGuard
val enc = crypto.encrypt(plaintext)                           // EncryptedPayload(seq, iv, ciphertext, mac)
val plain = crypto.decrypt(payload)                           // ByteArray?（认证失败为 null）
```

## 6. 二维码

```kotlin
val payload = PairingCodec.toQrPayload(pairingInfo)   // 连接串（JSON）
val bitmap = QrCodec.encode(payload, scale = 6)       // QrBitmap（ARGB）
val text = QrCodec.decode(bitmap)                     // 解码（ZXing）
```

配对码（短码）见 §10。

## 7. 质量监测

```kotlin
val monitor = QualityMonitor(
    scope = appScope,
    sendProbe = { seq, ts -> session.sendQualityProbe(seq, ts) },
    probeIntervalMs = 1000,
)
monitor.grade: StateFlow<QualityGrade>      // Excellent / Normal / Unstable
monitor.metrics: StateFlow<QualityMetrics>
monitor.start(); monitor.onPong(seq, ts)

QualityPolicyEngine.policyFor(grade): SyncPolicy   // Full / Optimized / Blocked
```

## 8. 断线保护

```kotlin
val protector = DisconnectProtector(
    scope = sessionScope,
    onPaused = { setState(SessionState.Paused) },
    pauseMs = 5000,
    tickMs = 250,
)
protector.onDisconnected()    // 传输断开：记录时间并启动计时
protector.onReconnected()     // 恢复：取消计时（未超 pauseMs 则不触发 onPaused）
```

> 修正记录：本文旧版写作 `DisconnectProtector(scope, onPaused, onClosed, onRecovered)`——
> **`onClosed` / `onRecovered` 两名参数均不存在**。该类只接受 `onPaused` 一个回调，
> 且不自动关闭协作（"永不自动关闭"由 `SessionManager` 的状态机保证）。

## 9. 应用状态与共享 UI（`:composeUi`）

`AppState` 是画布 + 会话 + 同步 + 工具状态的**单一装配点**，桌面与安卓共用。

### 9.1 构造

```kotlin
AppState(
    transport = createPlatformTransport(),          // 平台注入
    snapshotIo = InMemorySnapshotIo(),              // 桌面/安卓注入真实实现
    turnConfigIo = InMemoryTurnConfigIo(),
    rendezvousConfigIo = InMemoryRendezvousConfigIo(),
    rendezvous = DefaultRendezvousClient,           // 测试可注入假实现
)
```

### 9.2 公开可读状态

```kotlin
app.host: DrawBoxHost                 // 画布宿主
app.drawSync: OpSyncEngine<DrawOp>    // 同步引擎
app.pairing / app.pairingCode         // 本次配对的二维码信息与短码
app.rendezvousConfig / app.rendezvousOnline / app.pairingCodeIsRemote
app.statusText / app.isConnected / app.inSession
app.showConnection / app.showSettings / app.showSetup / app.setupLocked / app.setupSkipped
app.turnServers
app.qualityGrade / app.qualityText
app.canUndo / app.canRedo
app.editingTextId / app.textDraft / app.textFontSize / app.textAlignment / app.textFontFamily
app.showGrid / app.showHistory / app.autoIntervalSec / app.snapshots
app.currentMode / app.strokeColor / app.strokeWidth / app.opacity
```

### 9.3 公开方法

```kotlin
// 协作
app.createRoom()                              // 产出二维码 + 短配对码
app.join(raw: String)                         // 短码 / 连接串自动分流
app.disconnect(); app.closePanel()
app.saveRendezvousConfig(config: RendezvousConfig)
app.saveTurnConfig(servers: List<TurnServer>)
app.dismissSetup(); app.skipSetup()

// 绘画与工具
app.setMode(mode: Mode)
app.setStrokeColor(color); app.setStrokeWidth(width); app.setOpacity(value)
app.clearCanvas(); app.deleteSelected(); app.bringToFront(); app.sendToBack()
app.duplicateSelected(): Int

// 文本编辑
app.commitTextEdit(text, fontSize, alignment, fontFamily); app.dismissTextEdit()

// 图片
app.importImageBytes(bytes: ByteArray)

// 撤销 / 快照 / 网格
app.undo(); app.redo()
app.captureSnapshot(); app.setAutoInterval(sec); app.toggleGrid()
```

> 修正记录：本文旧版 §9 列出的 `AppState.document` / `.viewport` / `.tools` /
> `.importImage()` / `.applyLocal()` **均不存在**，已按实际成员重写。

### 9.4 共享 UI 组件（实际签名）

```kotlin
DrawBoxCanvas(
    host: DrawBoxHost,
    modifier: Modifier = Modifier,
    showGrid: Boolean = false,
    zoomControls: Boolean = true,
    onToggleGrid: (() -> Unit)? = null,
)

FloatingToolBar(
    host: DrawBoxHost,
    onUndo: () -> Unit, onRedo: () -> Unit,
    canUndo: Boolean, canRedo: Boolean,
    modifier: Modifier = Modifier,
)

ContextStyleBar(host: DrawBoxHost, modifier: Modifier = Modifier)

ZoomControls(
    scalePercent: Int, showGrid: Boolean,
    onZoomOut: () -> Unit, onZoomIn: () -> Unit,
    onZoomToFit: () -> Unit, onZoomTo100: () -> Unit,
    onToggleGrid: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)

TextEditOverlay(
    title: String, initialText: String,
    initialFontSize: Float, initialAlignment: TextAlignment, initialFontFamily: String,
    onCommit: (text: String, fontSize: Float, alignment: TextAlignment, fontFamily: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)

HistoryPanel(
    snapshots: List<DrawingSnapshot>, autoIntervalSec: Int,
    onAutoIntervalChange: (Int) -> Unit, onManualCapture: () -> Unit,
    modifier: Modifier = Modifier,
)

ConnectionPanel(
    pairing: PairingInfo?, pairingCode: String?,
    statusText: String, isConnected: Boolean,
    onClose: () -> Unit, onJoin: (String) -> Unit, onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    onScanClick: (() -> Unit)? = null,
    codeIsRemote: Boolean = true,        // 短码是否由服务器下发
    shortCodeEnabled: Boolean = true,    // 是否已配置会合服务器
)

SettingsPanel(
    servers: List<TurnServer>, onSave: (List<TurnServer>) -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false, onSkip: (() -> Unit)? = null,
    rendezvous: RendezvousConfig = RendezvousConfig(),
    rendezvousOnline: Boolean? = null,
    onSaveRendezvous: (RendezvousConfig) -> Unit = {},
)
```

> **`LayerPanel` 不存在**。图层相关能力只有 `FloatingToolBar` 与 `ContextStyleBar`
> 内的「前移 / 后移」按钮（`ToolBar.kt` 注释中说明其为"替代旧 LayerPanel"）。
> 完整图层面板（新建/删除/显隐/锁定）为后续增强方向。

## 10. 配对与会合服务

```kotlin
// 本地配对码（同机兜底）
val code: String = PairingCode.generate()          // DDDD-LLLL，校验位正确
PairingCode.validate(code); PairingCode.sample()   // sample() 为 UI/文档共用的合法样例
PairingManager.issue(...) / .registerCode(info, code) / .lookupCode(code)

// 会合服务（短配对码跨设备，需自建服务器）
val issued: RendezvousCode? = DefaultRendezvousClient.register(
    PairingInfoWire(json = payload, code = candidateCode), config,
)
val wire: PairingInfoWire? = DefaultRendezvousClient.lookup(code, config)
DefaultRendezvousClient.ping(config)
```

接口与部署见 `../server/README.md`；信任边界见 `protocol.md` §10。

> **注意**：短码路径下公钥由会合服务器下发，服务器因此具备替换公钥的能力。
> 该路径**不满足**"服务器看不到内容"；二维码 / 连接串路径才满足。


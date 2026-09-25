# Juan LinK — API 文档

> 核心 API 位于 `core`（KMP commonMain），桌面/移动端复用。
> 详见 `data-structures.md`（数据结构）与 `protocol.md`（线协议）。

## 1. 会话与会话管理

```kotlin
// 创建会话（二维码持有方 = Initiator）
val session = SessionManager(deviceId, TcpTransport())
session.opEngine = opEngine
val pairingInfo: PairingInfo = session.startInitiator(pairingManager)

// 加入（扫码/粘贴连接串方 = Responder）
val ok: Boolean = session.connectAsResponder(pairingInfo)

// 状态流
session.state: StateFlow<SessionState>   // Idle/Pairing/Handshaking/Connected/.../Closed
```

## 2. 操作同步

```kotlin
val engine = OpSyncEngine(document, peerId, transport /* SessionManager 实现 OpTransport */)

// 本地操作（画笔、形状、图层、图片、撤销）
engine.applyLocal(op: CanvasOp)        // → OpId?
engine.undo(); engine.redo()
engine.canUndo(); engine.canRedo()

// 远程操作（SessionManager 解密后自动分发，一般无需手调）
engine.onRemoteOp(envelope: OpEnvelope)

// 补同步
engine.onSyncRequest(sinceSeq); engine.onResync(ops)
```

## 3. 图片传输

```kotlin
ImageTransfer.sendImage(engine, imageId, layerId, bytes, width, height, transform = Affine2.IDENTITY)
// 接收端：document.onImageReady(imageId, bytes, checksumOk)
// 缺块：   document.onImageResendNeeded(imageId, missingChunks)
```

## 4. 画布文档

```kotlin
val doc = CanvasDocument("canvas-1")
doc.apply(op: CanvasOp)                    // 应用操作（本地/远程共用）
doc.allStrokes(); doc.allImages(); doc.layers
doc.imageBytes(imageId): ByteArray?
doc.onChange = { }                         // 状态变更通知
```
（注：上述 CanvasDocument/CanvasOp 为旧自研模型；现实现以 DrawBoxHost 为宿主、
DrawOp（元素 upsert/remove、图片分块、CanvasMeta）为线协议 op——见 data-structures.md。）

## 4b. 画布宿主增强 API（DrawBoxHost，重构新增）

```kotlin
host.resetCanvas()                          // 清空：批量移除同步 + 单条撤销，保留背景/样式/模式
host.duplicateSelected(): Int               // 复制选中（新 id + 偏移，diff 广播，选中副本）
host.applyTextStyle(id, fontSize, alignment, fontFamily)  // 文本样式同步（编辑对话框提交用）
host.setViewport(viewport)                  // 本地相机直写（不同步、不进撤销）

// 背景色属画布内容，随协作同步（DrawOp.CanvasMetaOp）：
app.host.onLocalIntent(Intent.SetBgColor(color))   // 本地改 → diff 广播；远端 apply 同步
```

上下文 UI（新增）：

```kotlin
ContextStyleBar(host)                       // 随工具/选中态切换：选中样式编辑、形状默认、橡皮大小、背景
ZoomControls(...)                           // 画布右下角：缩小/放大/适应/100%/网格（DrawBoxCanvas 内置）
TextEditOverlay(..., onCommit = { text, fontSize, alignment, fontFamily -> ... })  // 文本样式随提交同步
```

## 5. 加密

```kotlin
val keyPair = CryptoEngine.generateX25519KeyPair()
val shared = CryptoEngine.computeSharedSecret(priv, peerPub)
val keys = KeyDerivation.deriveKeys(shared, sessionId, nonce)
val crypto = SessionCrypto(sessionId, keys)
val enc = crypto.encrypt(plaintext)          // EncryptedPayload(seq, iv, ct, mac)
val plain = crypto.decrypt(payload)          // ByteArray?（认证失败为 null）
```

## 6. 二维码

```kotlin
val payload = PairingCodec.toQrPayload(pairingInfo)   // 连接串（JSON）
val bitmap = QrCodec.encode(payload, scale = 6)       // QrBitmap（ARGB）
val text = QrCodec.decode(bitmap)                     // 解码（ZXing）
```

## 7. 质量监测

```kotlin
val monitor = QualityMonitor(scope, sendProbe = { seq, ts -> session.sendQualityProbe(seq, ts) })
monitor.grade: StateFlow<QualityGrade>   // Excellent/Normal/Unstable
monitor.metrics: StateFlow<QualityMetrics>
val policy = QualityPolicyEngine.policyFor(grade)   // Full/Optimized/Blocked
```

## 8. 断线保护

```kotlin
val protector = DisconnectProtector(scope, onPaused, onClosed, onRecovered)
protector.onDisconnected()   // 断开时
protector.onReconnected()    // 恢复时（<3s 触发 onRecovered）
```

## 9. 桌面 UI（desktop 模块）

```kotlin
AppState:  // 画布 + 会话 + 同步 + 工具状态装配
  .document / .engine / .viewport / .tools
  .createRoom() / .join(connectionString) / .importImage()
  .applyLocal(op) / .undo() / .redo()
  .statusText / .isConnected / .qualityGrade

CanvasViewport(document, viewport, tools, docVersion, onOp)
FloatingToolBar(tools, onUndo, onRedo, canUndo, canRedo)
ConnectionPanel(pairing, pairingCode, statusText, isConnected, onJoin, onDisconnect)
LayerPanel(document, docVersion, onOp)
```

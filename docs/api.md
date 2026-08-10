# 婵娟 JUAN Link — API 文档

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

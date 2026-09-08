# Juan LinK — 数据结构文档

> 版本：1.0 · 状态：随开发演进（阶段 1 起，阶段 8 定稿）
> 全部数据结构定义于 `core/src/commonMain/kotlin/com/juanlink/core/`，跨平台共享。

## 1. 数据模型（model 包）

坐标一律使用**画布世界坐标**，与屏幕/视口无关，保证多端一致。

### 1.1 Stroke（笔迹）

```kotlin
data class StrokePoint(val x: Float, val y: Float, val pressure: Float = 1f, val timestamp: Long = 0L)
data class StrokeStyle(val color: Int, val width: Float, val alpha: Float, val tool: BrushTool, val brushTip: Float)
data class Stroke(
    val id: String,
    val layerId: String,
    val points: List<StrokePoint>,
    val style: StrokeStyle,
    val bounds: RectF,          // 轴对齐包围盒（渲染裁剪/脏矩形）
)
```

`BrushTool` 枚举：Pencil / Pen / Calligraphy / Marker / Highlighter / Eraser / Line / Rect / Circle / Arrow。

### 1.2 Layer（图层）

```kotlin
data class Layer(
    val id: String,
    val name: String,
    val index: Int,             // z 序，越小越靠下
    val visible: Boolean,
    val opacity: Float,
    val locked: Boolean,
)
```

### 1.3 ImageObject（图片对象）

```kotlin
data class ImageObject(
    val id: String,
    val layerId: String,
    val uri: String,            // 平台图片句柄
    val transform: Affine2,     // 平移/旋转/缩放（世界坐标）
    val width: Float,
    val height: Float,
)
```

### 1.4 Affine2（2D 仿射变换）

KMP 无 `android.graphics.Matrix`，自实现 2D 仿射矩阵：

```
|x'|   |a c e|   |x|
|y'| = |b d f| * |y|
|1 |   |0 0 1|   |1|
```

提供 `transform/multiply/inverse/translate/scale/rotateDeg`。图片上涂鸦 = 以图片局部坐标绘制 Stroke，随图片变换。

### 1.5 RectF / Viewport

```kotlin
data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class Viewport(val cx: Float, val cy: Float, val scale: Float)  // 相机：中心 + 缩放
```

## 2. 操作级同步（protocol 包 + canvas 包）

**铁律：只同步操作，绝不同步整画布。**

### 2.1 OpId（Lamport 全序）

```kotlin
data class OpId(val lamport: Long, val peerId: String) : Comparable<OpId>
```

按 `lamport` 升序、`peerId` 字典序决胜，双端排序一致，不依赖系统时钟。
本地操作 `lamport++`；收到远程操作 `lamport = max(lamport, 对方.lamport)`。

### 2.2 OpType（稳定编码，协议兼容依赖此值不变）

| code | 类型 | 说明 |
|---|---|---|
| 1 | StrokeAdd | 新建笔迹 |
| 2 | StrokeAppend | 进行中笔迹追加点列 |
| 3 | StrokeFinish | 笔迹完成 |
| 4 | StrokeRemove | 删除笔迹 |
| 5 | StrokeClear | 清空图层 |
| 10 | LayerAdd / 11 LayerRemove / 12 LayerReorder / 13 LayerUpdate | 图层操作 |
| 20-24 | ImageAdd / ImageChunk / ImageReady / ImageTransform / ImageRemove | 图片操作（阶段 5） |
| 30 | CanvasClear / 31 CanvasMeta | 画布操作 |
| 40 | Undo / 41 Redo | 撤销重做 |

### 2.3 OpEnvelope（操作信封）

```kotlin
data class OpEnvelope(
    val opId: OpId,
    val peerId: String,        // 发送方
    val opType: Int,           // OpType.code
    val payload: ByteArray,    // CanvasOp 的 CBOR 字节
    val seq: Long,             // 发送方出站序号（ack/补同步定位）
    val timestamp: Long,
)
```

### 2.4 CanvasOp（业务操作，sealed）

`StrokeAdd/StrokeAppend/StrokeFinish/StrokeRemove`、`LayerAddOp/...`、`CanvasClearOp/UndoOp/RedoOp`。
图片操作类型在阶段 5 加入。全部 `@Serializable`，CBOR 编解码（`OpCodec`）。

### 2.5 同步引擎状态（OpSyncEngine）

- **每端前沿队列** `pendingSeq[peer]`：按 seq 连续应用，缺口阻塞该端并触发补同步。
- **出站窗口** `outgoing: seq -> OpEnvelope`：未 ack 缓存，超时重发。
- **applyLog**：`RingBuffer<OpEnvelope>`（容量 20000），重连补同步数据源。
- **撤销历史**：`undoStack/redoStack`，逆操作作为新 Op 广播。

## 3. 加密结构（crypto 包）

### 3.1 会话密钥（HKDF-SHA256 派生）

```kotlin
data class KeyPairRaw(val privateKey: ByteArray, val publicKey: ByteArray)  // X25519
data class SessionKeys(val encKey: ByteArray, val macKey: ByteArray, val ivBase: ByteArray)
```

派生：`PRK = HKDF-Extract(salt = sessionId || nonce, IKM = ECDH共享秘密)`，再 Expand 出 `encKey(32B)/macKey(32B)/ivBase(12B)`。每会话唯一（会话隔离）。

### 3.2 EncryptedPayload（加密帧载荷）

```kotlin
data class EncryptedPayload(val seq: Long, val iv: ByteArray, val ciphertext: ByteArray, val mac: ByteArray)
```

- IV = `ivBase ⊕ seq`（8B BE 异或进前 8 字节）
- AAD = `sessionId || seq`（8B BE）
- ciphertext = AES-256-GCM(encKey, iv, aad, plaintext)，含 16B tag
- mac = HMAC-SHA256(macKey, seq || iv || ciphertext)
- 防重放：`ReplayGuard` 滑动窗口（默认 4096），拒绝已见/过旧序号

## 4. 连接配对结构（pairing 包）

### 4.1 PairingInfo（二维码/配对码载体）

```kotlin
data class PairingInfo(
    val version: Int, val magic: String, val sessionId: String, val deviceId: String,
    val publicKey: String,   // X25519 公钥，base64url
    val ts: Long, val exp: Long, val nonce: String,
    val role: String, val candidates: List<Candidate>,
)
data class Candidate(val host: String, val port: Int)
```

- 有效期默认 180s，一次性消费，防重放（nonce）。
- **二维码只含公钥，绝不含私钥**。

### 4.2 二维码 payload（JSON，base64url 字段）

```json
{"v":1,"magic":"juanlink","sid":"kF4x7tZ9qW2nB6s3","did":"dev-7f3a",
 "pk":"BAx9q2...","ts":1760000000000,"exp":180,"n":"a1B2c3d4",
 "cands":[{"h":"192.168.1.5","p":45678}],"role":"initiator"}
```

### 4.3 配对码（PairingCode）

格式 `DDDD-LLLC`，如 `5689-ABCD`。字符集 Crockford 变体（去 I/L/O/U）：
`0123456789ABCDEFGHJKMNPQRSTVWXYZ`。前 4 位数字，中 3 位字母，尾位校验字符
`= CHARSET[Σ(charValue × position) % 32]`。大小写/分隔符宽容。

## 5. 二维码（qr 包）

- `QrMatrix(size, version, modules: BooleanArray)`：自研编码器输出。
- `QrBitmap(width, height, pixels: IntArray)`：ARGB 8888 位图（含静区）。
- 自研编码器：ISO/IEC 18004 字节模式，版本 1-10，四档纠错等级；数据过长自动降级 EC 等级（M→L）。
- 解码：桌面端 ZXing（JVM）。

## 6. 版本与序列约定

- 协议版本 `PROTOCOL_VERSION = 1`；魔数 `magic = "juanlink"`。
- 加密 seq 从 1 起（0 保留握手）；Op seq 从 1 起。
- 时间戳一律 epoch 毫秒（`nowEpochMillis` expect/actual）。

# 婵娟 JUAN Link

跨平台 **点对点（P2P）协作绘图** 应用，基于 Kotlin Multiplatform + Compose Multiplatform。

两台设备通过二维码配对，**无需账号、无需中心服务器**即可实时同步画笔与橡皮。同网段走局域网 TCP 直连；跨网络（对称 NAT / 蜂窝 CGNAT）经可配置的 **TURN 中继**兜底。

> 「婵娟」：千里共婵娟 —— 两地之人共享同一块画布。

## 功能

- ✍️ 实时笔画同步（Lamport 时钟全序合并，双端状态一致）
- 🧽 橡皮擦 / 撤销与重做，双端同步
- 🔍 画布缩放 / 平移，画笔精确跟随指针
- 🔗 二维码配对，扫码即连
- 🛡️ 断线保护：自动重连 + 断线期间操作补同步，永不自动关闭（可手动断开）
- 🌐 跨网兜底：TURN 中继穿透对称 NAT；协作前强制引导配置 TURN（可跳过，仅局域网直连）
- 🔐 端到端加密：X25519 密钥交换 + AES-256-GCM 加密帧

## 平台状态

| 平台 | 状态 |
|---|---|
| Windows / macOS / Linux（桌面） | ✅ 可用 |
| Android（API 33+） | ✅ 可用 |
| iOS | 🔧 工程骨架占位，UI 层未实现 |

## 架构

```
:core        纯内核（协议/加密/同步引擎/传输），不依赖 UI
:composeUi   共享 Compose UI（画布/工具栏/面板/连接）
:desktop     JVM 桌面壳（启动入口 + 平台 IO）
:androidApp  Android 客户端（CameraX 扫码 + 完整实现）
```

**传输栈**（`:core` `jvmAndAndroidMain`）：

```
上层:  OpSyncEngine（操作级同步） → SessionManager（握手/加密帧） → EncryptedFrame
中间:  ReliableDatagramSession（可靠有序 UDP，分片+ACK+重传） ← 中继数据面复用
底层:  TcpTransport（局域网直连） | TurnTransport（TURN 中继，UDP 数据报）
汇聚:  CompositeTransport（先 TCP 后 relay 兜底）
```

源集结构：`commonMain`（跨平台）+ `jvmAndAndroidMain`（JVM/Android 共享中间层）+ `jvmMain` / `androidMain`（平台实现）。

## 构建

```bash
# 依赖：JDK 21，Android SDK（若构建安卓端）
./gradlew :core:jvmTest                 # 运行核心测试
./gradlew :desktop:createDistributable  # 桌面端可执行
./gradlew :androidApp:assembleDebug     # 安卓 Debug APK
```

Android 模块条件启用：存在 `local.properties`（含 `sdk.dir`）或 `ANDROID_HOME` 环境变量时才加入构建。

### Android release 签名

签名密码不入源码，release 构建需设置环境变量：

```bash
export JUANLINK_STORE_PASSWORD=...
export JUANLINK_KEY_PASSWORD=...
```

`androidApp/release.jks`（签名密钥库）已被 `.gitignore` 忽略，不随仓库分发。

## TURN 中继配置

默认仅携带**公共测试服务器**作 fallback（凭据公开、通常不可用）。跨网络连接需配置自己的 TURN 服务器（如自部署 coturn 或付费 TURN），否则只能局域网直连。

首次启动未配置 TURN 时会弹出**配置引导**（可关闭，先本地画画）；未配置就点击「创建/加入协作」会弹出**强制引导**——只能保存至少一台服务器，或选择「跳过（仅局域网直连）」。

1. 桌面端：应用设置面板添加，或编辑 `~/.juanlink/turn.json`（JSON 数组，可按需多台）：

```json
[
  { "host": "your-turn.example.com", "port": 3478, "username": "user", "password": "pass" }
]
```

2. 安卓端：设置面板添加同一台服务器（两端须配置相同服务器）。
3. 跨网连接时，Initiator 会优先尝试自定义服务器，成功者连同中继地址写进二维码，Responder 据此连同一台。

## 文档

- [通信协议设计](docs/protocol.md) — P2P 帧格式、加密方案、NAT 穿越
- [数据结构](docs/data-structures.md) — Stroke / Layer / CanvasDocument 模型
- [API 设计](docs/api.md) — SessionManager / OpSyncEngine 等公开接口
- [压测方案](docs/stress-test.md) — 长稳压测场景与指标
- [用户手册](docs/user-guide.md) — 协作 / 绘画 / 图片操作说明

## License

[MIT](LICENSE) © 2026 JUAN Link contributors

<p align="center">
  <img src="https://raw.githubusercontent.com/Youyigedx/JuanLink/main/assets/banner.svg" alt="Juan LinK" width="100%">
</p>

# Juan LinK

两台设备，一个二维码，打开就是同一张画布。

Juan LinK 是一个 P2P 协作画板：一端点「创建协作」，另一端扫码加入，两台设备立刻共享一块无限画布。没有账号，没有中心服务器，画的东西只在参与者之间传输——关掉连接，这次协作就散场，谁也不留底。

名字取意「婵娟」：千里共婵娟，两端共享一张画。应用内是中式色板（宣纸白、墨黑、竹青、朱砂红），画的是笔迹，不是 SaaS。

## 上手（约 30 秒）

1. 同一 Wi-Fi 下的两台设备（或一台电脑开两个实例）都可以。
2. A 点「**创建协作**」→ 出现二维码和配对码（如 `5689-ABCD`）。
3. B 点「**加入协作**」，扫码或粘贴连接串 → 握手 → 同一张画布，画笔实时同步。

两端不在同一网络（比如一边走手机流量）时，需要配一台 TURN 中继，应用内有配置引导；不配就只走局域网直连。

## 能画什么

- **工具**：画笔 / 直线 / 矩形 / 圆形 / 三角形 / 箭头 / 文字 / 橡皮，双端实时同步
- **形状样式**：描边（实线 / 虚线 / 点线）、填充色、描边开关、圆角，画之前可预设，画完可选中再改
- **选中编辑**：改色、复制一份、删除、前移 / 后移；撤销 / 重做跨端一致
- **文字**：内容、字号、对齐、字体（默认 / 衬线 / 等宽）随提交同步
- **图片**：导入即按 16KB 分块加密同步，缺块自动补传
- **画布**：背景色随协作同步；右下角缩放按钮（缩小 / 放大 / 适应 / 100%）和辅助网格开关
- **历史**：手动或定时快照，侧栏回看每一版

## 现在能用 / 还不能用的

能用：

- Windows / macOS / Linux 桌面
- Android（含摄像头扫码加入）

别抱期待：

- **iOS 只有工程骨架，UI 没做**
- 图片的缩放 / 旋转只有选中手柄，没有专门的工具
- 没有真正的图层面板，只有前移 / 后移
- 8 小时长稳压测方案写好了，还没实跑

## 设计上为什么这么做

**只同步操作，不同步画布。** 每一笔、每一条线都是增量操作，按 Lamport 时钟全序合并后广播；断线重连按序号补同步。整张画布只在两端各自重建，从不搬来搬去。

**端到端加密。** 每次协作握手用 X25519 协商一次性会话密钥，之后所有操作帧走 AES-256-GCM。密钥只在两端内存里；二维码只装公钥，泄露了最多只能发起一个临时会话。

**连接永不自动关闭。** 断线自动重连，断线期间本端照常画，恢复后自动补发。想结束只能手动点「断开连接」。

**加密通道只在两端之间。** 局域网直连时没有第三方参与；跨网时数据经你配置的 TURN 服务器——它只转发密文，看不到内容。

## 技术速览

- 语言 / 框架：Kotlin Multiplatform + Compose Multiplatform
- 模块：`:core`（协议 / 加密 / 同步 / 传输，零 UI）· `:composeUi`（共享 UI）· `:desktop` · `:androidApp`
- 画布渲染：[DrawBox](https://github.com/ak1/design-oop)（Compose Multiplatform 矢量绘图引擎），桌面与安卓共用
- 传输：局域网 TCP 直连，失败后 TURN 中继兜底；可靠有序 UDP（分片 / ACK / 重传）作中继数据面
- 同步：操作级 `OpSyncEngine`——seq 连续前沿 + Lamport 全序 + applyLog 补同步

```
:core        纯内核（协议 / 加密 / 同步引擎 / 传输）
:composeUi   共享 Compose UI（画布 / 工具栏 / 面板 / 连接）
:desktop     JVM 桌面壳（启动入口 + 平台 IO）
:androidApp  Android 客户端（CameraX 扫码 + 完整实现）
```

## 构建

依赖 JDK 21；构建安卓端另需 Android SDK。

```bash
./gradlew :core:jvmTest                 # 核心测试
./gradlew :desktop:createDistributable  # 桌面端可执行
./gradlew :androidApp:assembleDebug     # 安卓 Debug APK
```

Android 模块在检测到 `local.properties`（含 `sdk.dir`）或 `ANDROID_HOME` 时才加入构建。release 签名密码不入源码：构建时设置环境变量 `JUANLINK_STORE_PASSWORD` / `JUANLINK_KEY_PASSWORD`，密钥库 `androidApp/release.jks` 已被 `.gitignore` 忽略。

## TURN 配置（只有跨网才需要）

默认只带一台公共测试服务器兜底（凭据公开，通常不可用）。跨网连接请配置自己的服务器（如 coturn 或付费 TURN），否则只能局域网直连。

桌面端：设置面板添加，或编辑 `~/.juanlink/turn.json`（JSON 数组，可多台）：

```json
[
  { "host": "your-turn.example.com", "port": 3478, "username": "user", "password": "pass" }
]
```

安卓端：设置面板添加**同一台**（两端必须一致）。跨网连接时发起方优先尝试自定义服务器，成功的中继地址连同二维码一起交给扫码方。

## 文档

- [通信协议](docs/protocol.md) — 帧格式、加密方案、NAT 穿越
- [数据结构](docs/data-structures.md) — 元素模型与操作编码
- [API 设计](docs/api.md) — 会话 / 同步 / 画布公开接口
- [用户手册](docs/user-guide.md) — 协作 / 绘画 / 图片操作
- [压测方案](docs/stress-test.md) — 8h 长稳场景与指标

## License

[MIT](LICENSE) © 2026 Juan LinK contributors

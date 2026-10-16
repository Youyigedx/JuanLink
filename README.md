<div align="center">

<img src="https://raw.githubusercontent.com/Youyigedx/JuanLink/main/assets/banner.svg" alt="Juan LinK Banner" width="100%">

</div>

# Juan LinK

<div align="center">

![License: MIT](https://img.shields.io/badge/License-MIT-2F6F5E)
![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![UI: Compose Multiplatform](https://img.shields.io/badge/UI-Compose%20Multiplatform-3D5A80)
![Platform: Desktop + Android](https://img.shields.io/static/v1?label=Platform&message=Desktop%20%2B%20Android&color=1A1A1A)
![Encryption: E2E](https://img.shields.io/static/v1?label=Encryption&message=End-to-End&color=C0392B)

</div>

两台设备，一个二维码，打开就是同一张画布。

Juan LinK 是 P2P 协作画板。一端点“创建协作”，另一端扫码加入，两台设备立刻共享一块无限画布。没有账号，没有中心服务器。画的东西只在参与者之间传输。关掉连接，这次协作就散场，谁也不留底。

名字取意苏轼《水调歌头》中的“婵娟”：千里共婵娟，两端共享一张画。应用内是中式色板（宣纸白、墨黑、竹青、朱砂红）。

---

## 功能

| 能力 | 说明 |
|---|---|
| 工具 | 画笔、直线、矩形、圆形、三角形、箭头、文字、橡皮，双端实时同步 |
| 形状样式 | 描边（实线、虚线、点线）、填充色、描边开关、圆角。画之前可预设，画完可选中再改 |
| 选中编辑 | 改色、复制、删除、前移、后移。撤销和重做跨端一致 |
| 文字 | 内容、字号、对齐、字体（默认、衬线、等宽）随提交同步 |
| 图片 | 导入后按 16 KB 分块加密同步，缺块自动补传 |
| 画布 | 背景色随协作同步。右下角有缩放按钮（缩小、放大、适应、100%）和辅助网格开关 |
| 历史 | 手动或定时快照，侧栏回看每一版 |
| 配对 | 扫码 / **8 位短配对码**（需自建会合服务器）/ 连接串三种方式 |

## 快速上手

约 30 秒就能开始。同一 Wi-Fi 下的两台设备即可，或在一台电脑上开两个实例。

1. A 点“创建协作”，出现二维码和配对码（如 `5689-ABCN`）。
2. B 点“加入协作”，扫码或输入配对码。
3. 握手完成后，两台设备进入同一张画布，画笔实时同步。

**关于短配对码**：默认未启用，因为公钥需要一台会合服务器代为分发（见下方“设计边界”）。
配好服务器后，对方只需输入 8 位配对码即可加入，不必再粘贴长连接串。
部署方式见 [`server/README.md`](server/README.md)。
未配置服务器时，配对码仅本机可用，请用扫码或“复制连接串（备用）”。

两端不在同一网络时（比如一边走手机流量），需要配一台 TURN 中继。应用内有配置引导。不配就只走局域网直连。

## 平台支持与边界

| 平台 | 状态 |
|---|---|
| Windows、macOS、Linux 桌面 | 可用 |
| Android（含摄像头扫码加入） | 可用（需 API 33+） |
| iOS | 只有工程骨架，UI 没做 |

其他边界：

- 图片的缩放、旋转只有选中手柄，没有专门工具
- 没有真正的图层面板，只有前移、后移
- 8 小时长稳压测方案已写好，还没实跑

## 设计上为什么这么做

| 原则 | 说明 |
|---|---|
| 只同步操作，不同步画布 | 每一笔、每一条线都是增量操作，按 Lamport 时钟全序合并后广播。断线重连按序号补同步。整张画布只在两端各自重建，从不搬来搬去 |
| 端到端加密 | 每次协作握手用 X25519 协商一次性会话密钥，之后所有操作帧走 AES-256-GCM。密钥只在两端内存里 |
| 连接永不自动关闭 | 断线自动重连，断线期间本端照常画，恢复后自动补发。想结束只能手动点“断开连接” |
| 加密通道只在两端之间 | 局域网直连时没有第三方参与。跨网时数据经你配置的 TURN 服务器转发。它只转发密文，看不到内容 |

### ⚠️ 配对方式的信任差异（重要，别混淆）

“端到端加密”指的是**画布内容**。但**公钥怎么送到对方手里**，两种配对方式的安全强度不同：

| 配对方式 | 公钥来源 | 服务器能否解密内容 |
|---|---|---|
| 扫码 / 连接串 | 二维码或文本**带外**传递 | **不能**（服务器无法替换公钥） |
| **短配对码** | 由**会合服务器**下发 | **能**（服务器可返回自己的公钥做中间人） |

也就是说：**短配对码换来了便利，代价是会合服务器成为信任方。**
所以会合服务器请只填自己掌控的那台；只追求安全性时请用扫码。
更完整的分析见 [`docs/protocol.md`](docs/protocol.md) 第 10 节。

未来计划用“握手后比对短认证串（SAS）”消除这个信任需求——**尚未实现**。

## 技术栈

| 层次 | 选型 |
|---|---|
| 语言和框架 | Kotlin Multiplatform、Compose Multiplatform |
| 画布渲染 | DrawBox（`io.ak1.drawbox`，Apache-2.0 开源绘图引擎，已 vendored 进 `composeUi` 并适配；上游见 `composeUi/src/commonMain/kotlin/io/ak1/drawbox/NOTICE.txt`） |
| 传输 | 局域网 TCP 直连，失败后 TURN 中继兜底。可靠有序 UDP（分片、ACK、重传）作中继数据面 |
| 同步 | 操作级 `OpSyncEngine`：seq 连续前沿、Lamport 全序、applyLog 补同步 |
| 配对信令 | 自研会合服务（`server/rendezvous_server.py`，Python 3 标准库零依赖）+ 客户端 `RendezvousClient`（JDK 内置 HttpClient） |

## 项目结构

```
:core        纯内核（协议、加密、同步引擎、传输、会合客户端）
:composeUi   共享 Compose UI（画布、工具栏、面板、连接）
:desktop     JVM 桌面壳（启动入口 + 平台 IO）
:androidApp  Android 客户端（CameraX 扫码 + 完整实现）
server/      会合服务（可选，短配对码用）
```

## 构建

依赖 JDK 21。构建安卓端另需 Android SDK。

```bash
./gradlew :core:jvmTest                 # 核心测试
./gradlew :desktop:createDistributable  # 桌面端可执行
./gradlew :androidApp:assembleDebug     # 安卓 Debug APK
```

会合服务自检（改过服务端代码后请跑）：

```bash
python3 server/selftest.py              # 28 项断言，含与客户端算法的一致性交叉比对
```

<details>
<summary>Android 模块启用条件与 release 签名</summary>

只要满足 `local.properties`（含 `sdk.dir`）、`ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 任一项，就加入构建。

release 签名密码不入源码：构建时设置环境变量 `JUANLINK_STORE_PASSWORD`、`JUANLINK_KEY_PASSWORD`，密钥库 `androidApp/release.jks` 已被 `.gitignore` 忽略。

</details>

## TURN 配置（只有跨网才需要）

默认只带一台公共测试服务器兜底，凭据公开，通常不可用。跨网连接请配置自己的服务器（如 coturn 或付费 TURN），否则只能局域网直连。

<details>
<summary>配置方法</summary>

桌面端：设置面板添加，或编辑 `~/.juanlink/turn.json`（JSON 数组，可多台）：

```json
[
  { "host": "your-turn.example.com", "port": 3478, "username": "user", "password": "pass" }
]
```

安卓端：设置面板添加同一台，两端必须一致。跨网连接时发起方优先尝试自定义服务器，成功的中继地址连同二维码一起交给扫码方。

</details>

## 会合服务（短配对码，可选）

想用 8 位短配对码代替长连接串，需要一台你自己的会合服务器。零第三方依赖：

```bash
python3 server/rendezvous_server.py --port 8080 --token "$(openssl rand -hex 16)"
```

然后两端在「设置 → 会合服务（短配对码）」里填同一台服务器地址与令牌，点「保存并测试」。

服务端只搬运配对信令（公钥 + 地址），**不接触画布内容**；但它因此掌握公钥分发权，
是短码模式的信任方。完整部署、HTTPS 升级与安全边界说明见
[`server/README.md`](server/README.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [通信协议](docs/protocol.md) | 帧格式、加密方案、NAT 穿越、**会合服务与信任边界（§10 必读）** |
| [数据结构](docs/data-structures.md) | 元素模型与操作编码 |
| [API 设计](docs/api.md) | 会话、同步、画布公开接口 |
| [用户手册](docs/user-guide.md) | 协作、绘画、图片操作、会合服务配置 |
| [会合服务](server/README.md) | 部署、接口、HTTPS 升级、安全边界 |
| [压测方案](docs/stress-test.md) | 8 小时长稳场景与指标 |

## License

[MIT](LICENSE) © 2026 Juan LinK contributors

> ⚠️ 第三方开源组件声明：本项目 `composeUi` 模块内含 vendored 的 **DrawBox**
> （Apache-2.0，作者 Akshay Sharma），位于 `composeUi/src/commonMain/kotlin/io/ak1/drawbox/`，
> 其许可与归属声明见该目录下的 `LICENSE` 与 `NOTICE.txt`，**不得删除**。

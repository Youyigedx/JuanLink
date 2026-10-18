## v1.0.0 — 首个正式版本（软著登记版本）

> 版本号：**V1.0**（构建产物中为三段式 `1.0.0`，见 `core/.../AppInfo.kt` 的
> `DISPLAY_VERSION` / `VERSION`）。
> 中国版权保护中心建议写法为 `V1.0`，申请表的版本号、源程序与文档页眉须与此逐字一致。

### 概述

端到端加密的点对点协作画板：一端创建协作，另一端扫码或输入 **8 位短配对码**加入，
两台设备共享一块无限画布。无账号、无中心数据服务器。

### v1.0.0 新增（相对 v0.2.0）

- **短配对码（会合服务）**：用 8 位码（如 `5689-ABCN`）代替数百字符的连接串。
  - 客户端：`core/rendezvous/`（`RendezvousConfig` / `RendezvousClient` expect-actual /
    `RendezvousCodeFormat`），基于 JDK 内置 `HttpClient`，**零第三方依赖**
  - 服务端：`server/rendezvous_server.py`，Python 3 标准库**零依赖**；
    `POST /rendezvous/register`、`GET /rendezvous/lookup`、`GET /health`；
    反枚举措施：180s TTL、一次性消费、按 IP 限流、记录上限、统一 404 防探测
  - ⚠️ **信任模型变更**：短码路径下公钥由会合服务器下发，服务器因此具备**替换公钥**的能力，
    即该路径**不满足**"服务器看不到内容"。扫码/连接串路径不受影响。
    完整分析见 `docs/protocol.md` §10。根治方案（握手后比对短认证串 SAS）**尚未实现**。
- **设置面板**新增「会合服务」配置区（保存并测试 + 连通状态）
- **连接面板**改为短码优先展示，长连接串降级为兜底

### 修复（v1.0.0 开发期间实测发现）

- 会合服务端短码**存储键与查询键形态不一致**，导致登记成功却取回 404
- 桌面端 jpackage **最小运行时缺 `java.net.http` 模块**，打包后一启动即崩
  （Gradle run/test 环境不暴露；已在 `desktop/build.gradle.kts` 补 `modules` 声明）
- Android release 构建 **R8 报 `Missing class java.net.http.HttpClient`**
  （`android.jar` 不含该类；已在 `androidApp/proguard-rules.pro` 补 `-dontwarn`）
- 文档与 UI 的样例码 `5689-ABCD` **校验位错误**（正确为 `N`），
  用户照抄提示会被判校验失败；已改为 `5689-ABCN` 并加回归测试
- 修正 `docs/api.md` 中**三节描述不存在的 API**（`CanvasDocument` / `CanvasViewport` /
  `LayerPanel` / `DisconnectProtector(onClosed,onRecovered)` / `ImageTransfer` 等），
  并新增 `tools/check_api_doc_sync.py` 防止再犯
- 文档与代码不一致：删除不存在的「五种笔」「UDP 广播自动发现」描述，补上三角形工具

### 质量状态

| 项 | 结果 |
|---|---|
| Kotlin 测试 | **181 用例全绿**（core 146 + desktop 35） |
| 会合服务自检 | **31 项全通过**（含服务端↔客户端校验算法交叉比对） |
| 桌面端 | `createDistributable` 产物**实测启动成功**（窗口标题 `Juan LinK`） |
| Android | `assembleDebug` 成功；APK 内已确认会合实现编入 |
| API 文档一致性 | `tools/check_api_doc_sync.py` 通过（48 个符号全部存在） |

### 平台

- 桌面：**Windows 11**（Compose Desktop）；macOS / Linux 代码可移植但**未构建、未实测**
- Android：**API 33+**（`minSdk 33` / `targetSdk 35`，含 CameraX 扫码加入），已在真机跑通双机协作
- iOS：工程骨架占位，**无 Kotlin 源码，未实现**

### 已知边界

- 图片的缩放、旋转只有选中手柄，无专门工具
- 无完整图层面板，只有前移 / 后移
- 8 小时长稳压测方案已写好，**尚未实跑**（见 `docs/stress-test.md`）
- 跨网需自配 TURN 服务器；仓库仅随附公开测试 fallback（通常不可用）
- 短配对码需自建会合服务器；未配置时短码仅本机可用

### 第三方组件

`composeUi/src/commonMain/kotlin/io/ak1/drawbox/` 内含 vendored 的 **DrawBox**
（Apache-2.0，作者 Akshay Sharma），其 `LICENSE` 与 `NOTICE.txt` 依许可要求**必须保留**。
该组件不计入本软件的独创部分。

---

## v0.2.0 — 画布模块重构（功能补全）

画布模块经审计重构（详见 `docs/audit-canvas.md`）：补足 DrawBox 引擎已有但未暴露的能力，并修复三处缺陷。

### 新增功能

- **画布背景色**：可更换并随协作同步（CanvasMeta 操作），双端外观一致
- **上下文样式栏**（随工具/选中态切换）：
  - 选中编辑：删除 / 复制 / 描边色 / 填充（可无填充）/ 描边样式（实线·虚线·点线）/ 描边开关 / 圆角 / 前移后移
  - 形状工具：描边样式 / 填充 / 描边开关 / 圆角（新建默认）
  - 橡皮大小调节（5–60）
  - 文字工具：默认字号 / 字体 / 对齐
- **文本编辑对话框增强**：字号 / 对齐（左中右）/ 字体（默认·衬线·等宽）随提交同步
- **画布缩放控件**（右下角）：缩小 / 放大 / 适应画布 / 100%
- **辅助网格开关**（本地会话装饰，不进导出）
- **复制选中元素**（偏移一份并选中副本，随协作同步）

### 修复

- 颜色序列化精度：`toHexString` 截断导致的颜色跨端往返漂移（1/255 色差）
- 清空画布不再重置背景色与工具样式（保留会话设置），且可单步撤销
- `Undo/Redo` 意图防御：避免与跨端撤销历史混淆

### 测试

- core + desktop 全部通过；新增 7 项画布能力链路测试（背景同步与撤销 / 远端背景应用 / 复制 / 清空保留背景 / 文本样式 / Undo 防御 / 空选中复制）

### 平台

- 桌面：Windows / macOS / Linux（Compose Desktop）
  > ⚠️ 此为 v0.2.0 当时的描述。**实际构建并验证的只有 Windows**；
  > macOS / Linux 未构建、未实测。现行口径见 v1.0.0 的「平台」一节。
- Android（含 CameraX 扫码加入）
- iOS：工程骨架占位，UI 层未实现

> 跨网连接需自配 TURN 服务器（应用内配置引导有说明）；仓库默认仅随附公开测试 fallback，通常不可用。

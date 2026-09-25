# Juan LinK — 画布模块审计与重构报告

> 日期：2026 · 状态：已完成首轮审计 + 重构落地
> 范围：core `draw`/`protocol` 操作层、composeUi `draw`/`ui` 宿主与 UI、桌面/安卓壳接线

## 1. 审计结论

### 1.1 模块现状

画布模块由四层组成：

| 层 | 位置 | 职责 | 审计结论 |
|---|---|---|---|
| 线协议操作 | `core/.../draw/` + `protocol/OpEnvelope.kt` | DrawOp（元素/图片分块）+ OpSyncEngine（Lamport 全序、seq 前沿、ack、补同步、撤销） | 同步引擎健壮（并发锁、缺口阻塞、环形日志），泛型设计良好 |
| 引擎模型 | `composeUi/.../io/ak1/drawbox/` | DrawBox 渲染/手势/Reducer/UseCase | 引擎能力强，但大量能力未被应用层暴露 |
| 协作宿主 | `composeUi/.../draw/DrawBoxHost.kt` | 本地意图 → reducer → diff → 同步 op；远程 op 幂等应用 | 结构清晰，diff/逆 op 方案正确 |
| 应用 UI | `composeUi/.../ui/` + AppState | 工具栏/画布/面板 | 功能暴露不足，是「模块功能太少」的主因 |

### 1.2 发现的功能缺口（引擎支持但未接线 / 文档承诺未落地）

1. **背景色不同步**：`Intent.SetBgColor` 只改本地 State，`dispatchLocalOps` 只 diff 元素 → 协作两端画布外观分叉。`OpType.CanvasMeta(31)` 定义了但从未使用。
2. **描边样式（实线/虚线/点线）**：引擎 `StrokeStyle` + `SetStrokeStyle`/`SetSelectedStrokeStyle` 无任何 UI 入口。
3. **形状填充**：`SetFillColor`/`SetSelectedFillColor`（含无填充）无 UI 入口。
4. **描边开关（仅填充形状）**：`SetStrokeEnabled`/`SetSelectedStrokeEnabled` 无 UI 入口。
5. **圆角**：`SetCornerRadius`/`SetSelectedCornerRadius` 无 UI 入口。
6. **选中元素样式编辑**：没有选择上下文条（描边色/填充/样式/圆角/删除/复制/层序）。
7. **橡皮大小**：`SetEraserSize` 无 UI 入口（固定 20px）。
8. **文本样式**：编辑对话框只有内容文本框，字号/对齐/字体无法设置（引擎支持 `SetSelectedFontSize/Alignment/Family`）。
9. **缩放控制**：只有手势缩放，无按钮（适应画布/100%/步进），状态栏仅显示百分比。
10. **复制选中**：无任何复制能力（引擎无 Duplicate 意图）。
11. **辅助网格**：`showGrid` 硬编码 `false`，无开关。

### 1.3 发现的缺陷（重构中修复）

1. **清空画布破坏会话设置**：`Intent.Reset` 经 reducer 返回 `State()`，把背景色重置为黑、工具样式恢复默认——`AppState.clearCanvas()` 调用即中招。
2. **Undo/Redo 落入 reducer 快照栈**：若 UI 误派发 `Intent.Undo/Redo`，会操作引擎内部 `State.history`（宿主设计保持空栈），与跨端撤销搅在一起。
3. **颜色序列化精度丢失**：`Color.toHexString()` 用 `.toInt()` 截断而非四舍五入，`192/255f*255` 浮点误差使 8 位颜色往返漂移 1 级（`#ffc0392b` → `#ffbf392b`），跨端元素颜色会有 1/255 色差。

## 2. 重构内容

### 2.1 core：背景色同步 op

- `DrawOp` 新增 `CanvasMetaOp(bgColor: String)`（hex，与 WireElement 颜色约定一致）；`drawOpTypeCode` 映射 `OpType.CanvasMeta(31)`。
- 新增 `DrawOpCodec` 往返 + 类型码测试（`DrawOpSyncEngineTest`）。

### 2.2 DrawBoxHost：画布级操作 + 同步修复

- **背景色同步**：`onLocalIntent` 拦截 `SetBgColor` → 前后 diff → 追加 `CanvasMetaOp`（显式逆 = 变更前背景色）；`apply`/`captureInverse` 支持该 op；`applyCanvasMeta` 严格校验 `#RRGGBBAA` 并幂等。
- **`resetCanvas()`**：清空 = 全量移除批量 op（同步 + 单条撤销），保留背景色/工具样式/模式；`Intent.Reset` 在宿主层被接管（不再落入 reducer）。
- **`duplicateSelected()`**：复制选中元素（新 id + (12,12)×N 偏移），经 `AddElement` 意图走 diff 广播（upsert/逆=remove），无需扩展线协议；复制后选中副本。
- **`applyTextStyle(id, fontSize, alignment, fontFamily)`**：经 `UpdateElement` 意图 diff 广播，文本对话框提交样式。
- **`setViewport(vp)`**：本地相机直写（不同步、不进撤销）。
- **Undo/Redo 防御**：`Intent.Undo/Redo` 在宿主直接忽略（走引擎跨端撤销）。

### 2.3 UI：新增组件

- **`ContextStyleBar.kt`**（上下文样式栏，随工具/选中态切换）：
  - 选择模式：删除 / 复制 / 描边色 / 填充（含无填充）/ 描边样式 / 描边开关 / 圆角滑块 / 前移后移；
  - 形状工具：新建默认样式（描边样式 / 填充 / 描边开关 / 圆角）；
  - 文字工具：字号 / 字体 / 对齐默认；
  - 橡皮：半径滑块；
  - 手形/画笔：画布背景色（同步）。
- **`ZoomControls.kt`**（画布右下角）：缩小 / 百分比 / 放大 / 适应画布 / 100% / 网格开关，锚点屏幕中心；集成进 `DrawBoxCanvas`（`onSizeChanged` 捕获画布尺寸）。
- **`TextEditDialog`**：新增字号预设 / 对齐（左中右）/ 字体（默认/衬线/等宽），随提交同步。
- **`theme/InkColors.kt`**：共享中式色板（ToolBar 与样式栏复用）。
- **`draw/ViewportFit.kt`**：从 HistoryPanel 提取 `fitViewport`（缩略图与「适应画布」共用）。

### 2.4 AppState / 壳接线

- AppState：文本样式草稿（字号/对齐/字体）、`showGrid` + `toggleGrid()`、`commitTextEdit(text, fontSize, alignment, fontFamily)`（带默认值兼容旧调用）、`clearCanvas()` 改走 `host.resetCanvas()`、`duplicateSelected()`。
- 桌面 `Main.kt` / 安卓 `AndroidRoot.kt`：工具栏下方挂上下文样式栏；画布传 `showGrid`/`onToggleGrid`；文本浮层传样式草稿。

### 2.5 序列化修复

- `Color.toHexString()` 改 `roundToInt`（修复颜色往返漂移，提升跨端元素/背景色保真）。

## 3. 验证

- `:core:jvmTest` 全绿（含新增 CanvasMetaOp 编解码测试）。
- `:desktop:jvmTest` 全绿（35 项，含新增 7 项画布能力链路测试：背景同步+撤销、远程背景应用、复制、清空保留背景、文本样式、Undo 防御、空选中复制）。
- 构建环境说明：JDK 需 21（AGP 要求），`JAVA_HOME=C:\Program Files\Java\jdk-21.0.10`；安卓壳因本机无 Android SDK 未编译验证（改动与桌面镜像，风险低）。

## 4. 后续建议（未在本轮实现）

- 图层面板（DrawBox 以 zIndex 模拟图层，多选层级操作受限）。
- 图片缩放/旋转的专用工具（当前仅选中手柄）。
- iOS UI 层（工程骨架占位）。
- 8h 长稳压测实跑（`docs/stress-test.md` 阶段 7 遗留执行项）。

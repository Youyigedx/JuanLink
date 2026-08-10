package com.juanlink.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.juanlink.composeui.AppState
import com.juanlink.composeui.platform.SnapshotIo
import com.juanlink.composeui.platform.TurnConfigIo
import com.juanlink.composeui.theme.JuanTheme
import com.juanlink.composeui.theme.Palette
import com.juanlink.composeui.ui.CanvasViewport
import com.juanlink.composeui.ui.ConnectionPanel
import com.juanlink.composeui.ui.FloatingToolBar
import com.juanlink.composeui.ui.HistoryPanel
import com.juanlink.composeui.ui.LayerPanel
import com.juanlink.composeui.ui.SettingsPanel
import java.awt.Dimension
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 婵娟 JUAN Link — 桌面入口。
 */
fun main() {
    // stdout 重定向到文件/管道时为块缓冲，println 不会即时落盘，导致 `[JUAN]` 日志丢失。
    // 强制 autoFlush，便于排查 P2P 握手问题（配合 createDistributable + 重定向启动）。
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "婵娟 JUAN Link",
            icon = painterResource("icon.png"),
            state = rememberWindowState(width = 1200.dp, height = 800.dp),
        ) {
            // 最小窗口：过小会导致顶部栏/工具栏/图层/历史面板互相重叠。
            // 本版本 WindowState 无 minimumSize 参数，直接设置底层 AWT ComposeWindow
            // （JFrame）的最小尺寸（按 DPI 缩放换算成像素）。
            val awtWindow = this.window
            SideEffect {
                val scale = awtWindow.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
                awtWindow.minimumSize = Dimension((1000 * scale).toInt(), (640 * scale).toInt())
            }
            AppRoot()
        }
    }
}

/** 桌面快照持久化：user.home/.juanlink/snapshots.json */
class DesktopSnapshotIo : SnapshotIo {
    private val file = File(System.getProperty("user.home"), ".juanlink/snapshots.json")
    override fun load(): String? = runCatching {
        if (file.exists()) file.readText() else null
    }.getOrNull()
    override fun save(text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
    }
}

/** 桌面 TURN 配置持久化：user.home/.juanlink/turn.json */
class DesktopTurnConfigIo : TurnConfigIo {
    private val file = File(System.getProperty("user.home"), ".juanlink/turn.json")
    override fun load(): String? = runCatching {
        if (file.exists()) file.readText() else null
    }.getOrNull()
    override fun save(text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }
    }
}

/** 桌面图片导入：文件选择器 → 字节 → 共享 AppState */
private fun importImageFromDisk(app: AppState) {
    val chooser = JFileChooser()
    chooser.fileFilter = FileNameExtensionFilter("图片文件", "png", "jpg", "jpeg", "bmp", "webp", "gif")
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
    val file = chooser.selectedFile ?: return
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
    app.importImageBytes(bytes)
}

@Composable
fun AppRoot() {
    val app = remember {
        AppState(snapshotIo = DesktopSnapshotIo(), turnConfigIo = DesktopTurnConfigIo())
    }
    JuanTheme {
        Box(Modifier.fillMaxSize()) {
            // 无限画布
            CanvasViewport(
                document = app.document,
                viewport = app.viewport,
                tools = app.tools,
                docVersion = app.docVersion,
                onOp = { app.applyLocal(it) },
                modifier = Modifier.fillMaxSize(),
            )

            // 图层面板（右侧）
            LayerPanel(
                document = app.document,
                docVersion = app.docVersion,
                onOp = { app.applyLocal(it) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp),
            )

            // 历史记录面板（工具栏下方左侧）
            if (app.showHistory) {
                HistoryPanel(
                    snapshots = app.snapshots,
                    autoIntervalSec = app.autoIntervalSec,
                    onAutoIntervalChange = { app.setAutoInterval(it) },
                    onManualCapture = { app.captureSnapshot() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 112.dp, start = 12.dp),
                )
            }

            // 顶部栏
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("婵娟", color = Palette.MoHei, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("JUAN Link", color = Palette.HuiMo, fontSize = 13.sp)
                TopButton("创建协作") {
                    if (!app.isConnected) app.createRoom()
                }
                TopButton("加入协作") { if (!app.isConnected) app.showConnection = true }
                TopButton("导入图片") { importImageFromDisk(app) }
                TopButton("历史") { app.showHistory = !app.showHistory }
                TopButton("设置") { app.showSettings = !app.showSettings }
            }

            // 悬浮工具栏（限宽 + 水平滚动，任意窗口尺寸不重叠）
            FloatingToolBar(
                tools = app.tools,
                onUndo = { app.undo() },
                onRedo = { app.redo() },
                canUndo = app.canUndo,
                canRedo = app.canRedo,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 56.dp, start = 12.dp)
                    .widthIn(max = 780.dp),
            )

            // 底部状态栏
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.DanMo.copy(alpha = 0.85f))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 连接状态点
                    Box(
                        Modifier
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (app.isConnected) Palette.ZhuQing else Palette.HuiMo)
                            .padding(5.dp)
                    )
                    Text(app.statusText, color = Palette.HuiMo, fontSize = 13.sp)
                    // 质量指示
                    if (app.isConnected) {
                        val gradeColor = when (app.qualityGrade) {
                            com.juanlink.core.quality.QualityGrade.Excellent -> Palette.ZhuQing
                            com.juanlink.core.quality.QualityGrade.Normal -> Palette.ZhuShaHong
                            com.juanlink.core.quality.QualityGrade.Unstable -> Palette.ZhuShaHong
                        }
                        Box(
                            Modifier
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(gradeColor)
                                .padding(5.dp)
                        )
                        Text("质量 ${app.qualityText}", color = Palette.HuiMo, fontSize = 13.sp)
                    }
                    // 缩放倍率（Ctrl+滚轮缩放）
                    Text(
                        "缩放 ${(app.viewport.value.scale * 100).toInt()}%",
                        color = Palette.HuiMo,
                        fontSize = 13.sp,
                    )
                    // 断开连接：只要会话存在（等待配对/已连接/重连中）即可主动断开
                    if (app.inSession) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Palette.ZhuShaHong)
                                .clickable { app.disconnect() }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("断开连接", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }

            // 连接面板（覆盖层）
            if (app.showConnection) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Palette.MoHei.copy(alpha = 0.25f))
                        .clickable { app.closePanel() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.wrapContentSize().clickable(enabled = false) {}) {
                        ConnectionPanel(
                            pairing = app.pairing,
                            pairingCode = app.pairingCode,
                            statusText = app.statusText,
                            isConnected = app.isConnected,
                            onClose = { app.closePanel() },
                            onJoin = { raw -> app.join(raw) },
                            onDisconnect = { app.disconnect() },
                        )
                    }
                }
            }

            // 首次使用/未配置强制引导（覆盖连接面板之上；锁定态 dismissSetup 自动忽略关闭）
            if (app.showSetup) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Palette.MoHei.copy(alpha = 0.45f))
                        .clickable { app.dismissSetup() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.wrapContentSize().clickable(enabled = false) {}) {
                        SettingsPanel(
                            servers = app.turnServers,
                            onSave = { app.saveTurnConfig(it) },
                            onClose = { app.dismissSetup() },
                            locked = app.setupLocked,
                            onSkip = { app.skipSetup() },
                        )
                    }
                }
            } else if (app.showSettings) {
                // TURN 设置面板（覆盖层）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Palette.MoHei.copy(alpha = 0.25f))
                        .clickable { app.showSettings = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.wrapContentSize().clickable(enabled = false) {}) {
                        SettingsPanel(
                            servers = app.turnServers,
                            onSave = {
                                app.saveTurnConfig(it)
                                app.showSettings = false
                            },
                            onClose = { app.showSettings = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.ZhuQing)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

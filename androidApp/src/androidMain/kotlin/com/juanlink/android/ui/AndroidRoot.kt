package com.juanlink.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juanlink.android.scan.CameraScanner
import com.juanlink.composeui.AppState
import com.juanlink.composeui.theme.JuanTheme
import com.juanlink.composeui.theme.Palette
import com.juanlink.composeui.ui.ConnectionPanel
import com.juanlink.composeui.ui.DrawBoxCanvas
import com.juanlink.composeui.ui.FloatingToolBar
import com.juanlink.composeui.ui.HistoryPanel
import com.juanlink.composeui.ui.SettingsPanel
import com.juanlink.composeui.ui.TextEditOverlay
import com.juanlink.core.quality.QualityGrade

/**
 * 手机竖屏自适应根布局：
 * - 顶部操作栏（创建/加入/导入/历史/图层，横向滚动）
 * - 画布铺满
 * - 底部悬浮工具栏（拇指区）+ 状态条
 * - 图层/历史/连接/扫码为开关浮层
 */
@Composable
fun AndroidRoot(app: AppState) {
    val context = LocalContext.current
    var showScanner by remember { mutableStateOf(false) }

    // SAF 选择图片 → 字节 → 共享 importImageBytes（分块同步到对端）
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) app.importImageBytes(bytes)
        }
    }

    JuanTheme {
        Box(Modifier.fillMaxSize()) {
            // 无限画布（DrawBox：缩放/平移/双指捏合内置）
            DrawBoxCanvas(
                host = app.host,
                modifier = Modifier.fillMaxSize(),
            )

            // 顶部操作栏（statusBarsPadding 避开系统状态栏，防止按钮被遮挡/无法点击）
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Palette.XuanZhiBai.copy(alpha = 0.85f))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("婵娟", color = Palette.MoHei, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                ActionChip("创建") { if (!app.isConnected) app.createRoom() }
                ActionChip("加入") { if (!app.isConnected) app.showConnection = true }
                ActionChip("导入") { imagePicker.launch("image/*") }
                ActionChip("历史") { app.showHistory = !app.showHistory }
                ActionChip("设置") { app.showSettings = !app.showSettings }
                // 断开连接：只要会话存在（等待配对/已连接/重连中）即可主动断开（红色警示）
                if (app.inSession) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Palette.ZhuShaHong)
                            .clickable { app.disconnect() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("断开", color = Color.White, fontSize = 13.sp)
                    }
                }
            }

            // 底部：悬浮工具栏 + 状态条（navigationBarsPadding 避开手势导航条）
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FloatingToolBar(
                    host = app.host,
                    onUndo = { app.undo() },
                    onRedo = { app.redo() },
                    canUndo = app.canUndo,
                    canRedo = app.canRedo,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 状态条
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Palette.DanMo.copy(alpha = 0.9f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Box(
                        Modifier
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (app.isConnected) Palette.ZhuQing else Palette.HuiMo)
                            .padding(5.dp)
                    )
                    Text(app.statusText, color = Palette.HuiMo, fontSize = 12.sp)
                    if (app.isConnected) {
                        val gradeColor = when (app.qualityGrade) {
                            QualityGrade.Excellent -> Palette.ZhuQing
                            QualityGrade.Normal -> Palette.ZhuShaHong
                            QualityGrade.Unstable -> Palette.ZhuShaHong
                        }
                        Box(
                            Modifier
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(gradeColor)
                                .padding(5.dp)
                        )
                        Text("质量 ${app.qualityText}", color = Palette.HuiMo, fontSize = 12.sp)
                    }
                }
            }

            // 历史记录浮层
            if (app.showHistory) {
                Overlay(onDismiss = { app.showHistory = false }) {
                    HistoryPanel(
                        snapshots = app.snapshots,
                        autoIntervalSec = app.autoIntervalSec,
                        onAutoIntervalChange = { app.setAutoInterval(it) },
                        onManualCapture = { app.captureSnapshot() },
                    )
                }
            }

            // TURN 设置浮层
            if (app.showSettings) {
                Overlay(onDismiss = { app.showSettings = false }) {
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

            // 连接面板覆盖层
            if (app.showConnection) {
                Overlay(onDismiss = { app.closePanel() }) {
                    ConnectionPanel(
                        pairing = app.pairing,
                        pairingCode = app.pairingCode,
                        statusText = app.statusText,
                        isConnected = app.isConnected,
                        onClose = { app.closePanel() },
                        onJoin = { raw -> app.join(raw) },
                        onDisconnect = { app.disconnect() },
                        onScanClick = { showScanner = true },
                    )
                }
            }

            // 首次使用/未配置强制引导（覆盖连接面板；锁定态 dismissSetup 自动忽略关闭）
            if (app.showSetup) {
                Overlay(onDismiss = { app.dismissSetup() }) {
                    SettingsPanel(
                        servers = app.turnServers,
                        onSave = { app.saveTurnConfig(it) },
                        onClose = { app.dismissSetup() },
                        locked = app.setupLocked,
                        onSkip = { app.skipSetup() },
                    )
                }
            }

            // 文本编辑浮层（Mode.TEXT 插入 / 双击编辑文本）
            val editingId = app.editingTextId
            if (editingId != null) {
                TextEditOverlay(
                    title = "编辑文字",
                    initialText = app.textDraft,
                    onCommit = { app.commitTextEdit(it) },
                    onDismiss = { app.dismissTextEdit() },
                )
            }

            // 扫码覆盖层（最高层）
            if (showScanner) {
                CameraScanner(
                    onResult = { raw ->
                        showScanner = false
                        app.showConnection = false
                        app.join(raw)
                    },
                    onClose = { showScanner = false },
                )
            }
        }
    }
}

/** 半透明遮罩 + 居中浮层 */
@Composable
private fun Overlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.MoHei.copy(alpha = 0.25f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = false) {}) {
            content()
        }
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
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

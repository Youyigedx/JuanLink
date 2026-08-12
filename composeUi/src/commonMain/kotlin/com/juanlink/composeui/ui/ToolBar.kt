package com.juanlink.composeui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juanlink.composeui.draw.DrawBoxHost
import com.juanlink.composeui.theme.Palette
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode

/** 中式色板（渲染为可点击色块） */
private val INK_COLORS = listOf(
    Color(0xFF1A1A1A),  // 墨黑
    Color(0xFF2F6F5E),  // 竹青
    Color(0xFFC0392B),  // 朱砂红
    Color(0xFF7A4E2D),  // 赭石
    Color(0xFF3D5A80),  // 黛蓝
    Color(0xFF9C6B30),  // 赭黄
)

/** 工具映射：标签 → DrawBox Mode（手形/选择/画笔/图形/文字/橡皮） */
private val TOOLS = listOf(
    Mode.PAN to "手形",
    Mode.SELECT to "选择",
    Mode.PEN to "画笔",
    Mode.RECTANGLE to "矩形",
    Mode.CIRCLE to "圆形",
    Mode.TRIANGLE to "三角形",
    Mode.ARROW to "箭头",
    Mode.LINE to "直线",
    Mode.TEXT to "文字",
    Mode.ERASER to "橡皮",
)

/**
 * 悬浮工具栏：DrawBox 工具格 + 色板 + 粗细/透明度 + 图层(Z 序) + 撤销重做。
 * 直连 [DrawBoxHost]：读 `host.state` 驱动选中态，写经 `host.onLocalIntent`。
 */
@Composable
fun FloatingToolBar(
    host: DrawBoxHost,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    modifier: Modifier = Modifier,
) {
    val state = host.state
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.DanMo.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 第 1 行：工具格 + 色板（独立横向滚动，窄屏时仍可横向滑动查看）
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((mode, label) in TOOLS) {
                val selected = state.mode == mode
                ToolChip(
                    label = label,
                    selected = selected,
                    enabled = true,
                    onClick = { host.onLocalIntent(Intent.SetMode(mode)) },
                )
            }

            Spacer(Modifier.width(8.dp))

            for (c in INK_COLORS) {
                val selected = state.strokeColor == c
                Box(
                    modifier = Modifier
                        .size(if (selected) 20.dp else 16.dp)
                        .clip(CircleShape)
                        .background(c)
                        .clickable { host.onLocalIntent(Intent.SetStrokeColor(c)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                }
            }
        }

        // 第 2 行：粗细 + 透明度 + 图层(Z 序) + 撤销重做（独立一行，确保滑杆完整可见不被工具行挤出）
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("粗细", color = Palette.HuiMo, fontSize = 12.sp)
            Slider(
                value = state.strokeWidth,
                onValueChange = { host.onLocalIntent(Intent.SetStrokeWidth(it)) },
                valueRange = 1f..30f,
                modifier = Modifier.width(90.dp),
            )

            Text("透明", color = Palette.HuiMo, fontSize = 12.sp)
            Slider(
                value = state.opacity,
                onValueChange = { host.onLocalIntent(Intent.SetOpacity(it)) },
                valueRange = 0.05f..1f,
                modifier = Modifier.width(70.dp),
            )

            Spacer(Modifier.width(8.dp))

            // 图层控制：选中元素前移/后移（替代旧 LayerPanel 的 zIndex 需求）
            val hasSelection = state.selectedIds.isNotEmpty()
            ToolChip("前移", selected = false, enabled = hasSelection) {
                host.onLocalIntent(Intent.BringSelectionToFront)
            }
            ToolChip("后移", selected = false, enabled = hasSelection) {
                host.onLocalIntent(Intent.SendSelectionToBack)
            }

            Spacer(Modifier.width(8.dp))

            ToolChip("撤销", selected = false, enabled = canUndo, onClick = onUndo)
            ToolChip("重做", selected = false, enabled = canRedo, onClick = onRedo)
        }
    }
}

/** 工具/动作胶囊按钮：选中或禁用态着色 */
@Composable
private fun ToolChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> Palette.ZhuQing
                    enabled -> Palette.ZhuQing.copy(alpha = 0.15f)
                    else -> Color.Transparent
                },
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = when {
                selected -> Color.White
                enabled -> Palette.ZhuQing
                else -> Palette.HuiMo.copy(alpha = 0.4f)
            },
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

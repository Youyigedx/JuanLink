package com.juanlink.composeui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.juanlink.composeui.theme.Palette
import com.juanlink.core.model.BrushTool

/** 中式色板（渲染为可点击色块） */
private val INK_COLORS = listOf(
    0xFF1A1A1A.toInt(),  // 墨黑
    0xFF2F6F5E.toInt(),  // 竹青
    0xFFC0392B.toInt(),  // 朱砂红
    0xFF7A4E2D.toInt(),  // 赭石
    0xFF3D5A80.toInt(),  // 黛蓝
    0xFF9C6B30.toInt(),  // 赭黄
)

private val TOOLS = listOf(
    null to "手形",
    BrushTool.Pencil to "铅笔",
    BrushTool.Pen to "钢笔",
    BrushTool.Calligraphy to "毛笔",
    BrushTool.Marker to "马克笔",
    BrushTool.Highlighter to "荧光笔",
    BrushTool.Eraser to "橡皮",
    BrushTool.Line to "直线",
    BrushTool.Rect to "矩形",
    BrushTool.Circle to "圆形",
    BrushTool.Arrow to "箭头",
)

/**
 * 悬浮工具栏：工具格 + 色板 + 粗细/透明度 + 撤销重做。
 */
@Composable
fun FloatingToolBar(
    tools: ToolState,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    modifier: Modifier = Modifier,
) {
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
            for ((tool, label) in TOOLS) {
                val selected = tools.tool == tool
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Palette.ZhuQing else Color.Transparent)
                        .clickable { tools.tool = tool }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (selected) Color.White else Palette.HuiMo,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            for (c in INK_COLORS) {
                val color = Color(c)
                val selected = tools.color == c
                Box(
                    modifier = Modifier
                        .size(if (selected) 20.dp else 16.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable { tools.color = c },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                }
            }
        }

        // 第 2 行：粗细 + 透明度 + 撤销重做（独立一行，确保滑杆完整可见不被工具行挤出）
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("粗细", color = Palette.HuiMo, fontSize = 12.sp)
            Slider(
                value = tools.width,
                onValueChange = { tools.width = it },
                valueRange = 1f..30f,
                modifier = Modifier.width(90.dp),
            )

            Text("透明", color = Palette.HuiMo, fontSize = 12.sp)
            Slider(
                value = tools.alpha,
                onValueChange = { tools.alpha = it },
                valueRange = 0.05f..1f,
                modifier = Modifier.width(70.dp),
            )

            Spacer(Modifier.width(8.dp))

            for ((action, enabled, label) in listOf(
                Triple({ onUndo() }, canUndo, "撤销"),
                Triple({ onRedo() }, canRedo, "重做"),
            )) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (enabled) Palette.ZhuQing.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable(enabled = enabled) { action.invoke() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(label, color = if (enabled) Palette.ZhuQing else Palette.HuiMo.copy(alpha = 0.4f), fontSize = 13.sp)
                }
            }
        }
    }
}

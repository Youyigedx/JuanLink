package com.juanlink.composeui.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.juanlink.core.model.BrushTool
import com.juanlink.core.model.StrokeStyle

/**
 * 当前工具状态（工具栏与画布共享）。
 * tool == null 表示「手形/平移」工具。
 */
class ToolState {
    var tool by mutableStateOf<BrushTool?>(BrushTool.Pencil)
    var color by mutableStateOf(0xFF1A1A1A.toInt())   // 墨黑
    var width by mutableStateOf(3f)
    var alpha by mutableStateOf(1f)

    /** 生成当前笔迹样式 */
    fun style(): StrokeStyle {
        val t = tool ?: BrushTool.Pencil
        val erasing = t == BrushTool.Eraser
        return StrokeStyle(
            // 橡皮：用画布背景色（宣纸白 0xFFF7F3E9，与 Palette.XuanZhiBai 一致）绘制，
            // 视觉上覆盖下方笔迹实现擦除；两端渲染同色故同步后一致。
            color = if (erasing) 0xFFF7F3E9.toInt() else color,
            // 橡皮加宽（至少 12px）确保能盖住墨线；透明度必须为 1 才盖得干净。
            width = if (erasing) (width * 3f).coerceAtLeast(12f) else width,
            alpha = if (erasing) 1f else alpha,
            tool = t,
            brushTip = if (t == BrushTool.Calligraphy) 1f else 0f,
        )
    }
}

package com.juanlink.composeui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import com.juanlink.composeui.theme.INK_COLORS
import com.juanlink.composeui.theme.Palette
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.State
import io.ak1.drawbox.domain.model.StrokeStyle
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.model.effectiveMode

/** 描边样式选项（新建默认 + 选中编辑共用） */
private val STROKE_STYLE_OPTIONS = listOf(
    StrokeStyle.SOLID to "实线",
    StrokeStyle.DASHED to "虚线",
    StrokeStyle.DOTTED to "点线",
)

/** 字号预设（新建默认 + 文本编辑共用） */
private val FONT_SIZE_OPTIONS = listOf(12f, 16f, 24f, 32f, 48f)

/** 字体族选项（key → 标签） */
private val FONT_FAMILY_OPTIONS = listOf(
    "sans" to "默认",
    "serif" to "衬线",
    "mono" to "等宽",
)

/** 文本对齐选项 */
private val ALIGN_OPTIONS = listOf(
    TextAlignment.LEFT to "左",
    TextAlignment.CENTER to "中",
    TextAlignment.RIGHT to "右",
)

/**
 * 上下文样式栏：随当前工具/选中态切换内容，补足工具栏未覆盖的引擎能力。
 *
 * - 选择模式：删除 / 复制 + 描边色 / 填充（含无填充）/ 描边样式 / 描边开关 /
 *   圆角 / 前移后移（全部作用于选中元素，diff 自动广播同步）。
 * - 形状工具：新建形状的默认样式（描边样式 / 填充 / 描边开关 / 圆角）。
 * - 文字工具：新建文本的默认样式（字号 / 字体 / 对齐）。
 * - 橡皮：橡皮半径。
 * - 其它工具：提示占位。
 */
@Composable
fun ContextStyleBar(
    host: DrawBoxHost,
    modifier: Modifier = Modifier,
) {
    val state = host.state
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.DanMo.copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (state.effectiveMode) {
            Mode.SELECT -> SelectionStyleBar(host)
            Mode.ERASER -> EraserStyleBar(host)
            Mode.TEXT -> TextDefaultsBar(host)
            Mode.RECTANGLE, Mode.CIRCLE, Mode.TRIANGLE, Mode.ARROW, Mode.LINE ->
                ShapeDefaultsBar(host)
            // 手形/画笔等无专属样式的工具：显示画布背景（随协作同步）
            Mode.PEN, Mode.PAN -> BackgroundBar(host)
            else -> Text(
                "选择工具或元素开始编辑",
                color = Palette.HuiMo,
                fontSize = 12.sp,
            )
        }
    }
}

// ================================================================ 选择模式
@Composable
private fun SelectionStyleBar(host: DrawBoxHost) {
    val state = host.state
    val hasSel = state.selectedIds.isNotEmpty()

    StyleChip("删除", enabled = hasSel) { host.onLocalIntent(Intent.DeleteSelected) }
    StyleChip("复制", enabled = hasSel) { host.duplicateSelected() }
    StyleDivider()

    val strokeColor = firstSelectedStrokeColor(state)
    Text("描边色", color = Palette.HuiMo, fontSize = 12.sp)
    for (c in INK_COLORS) {
        ColorDot(
            color = c,
            selected = hasSel && strokeColor == c,
            enabled = hasSel,
        ) { host.onLocalIntent(Intent.SetSelectedStrokeColor(c)) }
    }
    StyleDivider()

    val fillColor = firstSelectedShape(state)?.fillColor
    val hasShape = hasSel && firstSelectedShape(state) != null
    Text("填充", color = Palette.HuiMo, fontSize = 12.sp)
    StyleChip("无", selected = hasShape && fillColor == null, enabled = hasShape) {
        host.onLocalIntent(Intent.SetSelectedFillColor(null))
    }
    for (c in INK_COLORS) {
        ColorDot(
            color = c,
            selected = hasShape && fillColor == c,
            enabled = hasShape,
        ) { host.onLocalIntent(Intent.SetSelectedFillColor(c)) }
    }
    StyleDivider()

    val shape = firstSelectedShape(state)
    for ((s, label) in STROKE_STYLE_OPTIONS) {
        StyleChip(
            label,
            selected = hasShape && shape?.strokeStyle == s,
            enabled = hasShape,
        ) { host.onLocalIntent(Intent.SetSelectedStrokeStyle(s)) }
    }
    StyleChip(
        "描边",
        selected = hasShape && shape?.strokeEnabled == true,
        enabled = hasShape,
    ) { host.onLocalIntent(Intent.SetSelectedStrokeEnabled(!(shape?.strokeEnabled ?: true))) }
    Text("圆角", color = Palette.HuiMo, fontSize = 12.sp)
    Slider(
        value = shape?.cornerRadius ?: 0f,
        onValueChange = { host.onLocalIntent(Intent.SetSelectedCornerRadius(it)) },
        valueRange = 0f..24f,
        enabled = hasShape,
        modifier = Modifier.width(80.dp),
    )
    StyleDivider()

    StyleChip("前移", enabled = hasSel) { host.onLocalIntent(Intent.BringSelectionToFront) }
    StyleChip("后移", enabled = hasSel) { host.onLocalIntent(Intent.SendSelectionToBack) }
}

// ================================================================ 橡皮
@Composable
private fun EraserStyleBar(host: DrawBoxHost) {
    val size = host.state.eraserSize
    Text("橡皮大小", color = Palette.HuiMo, fontSize = 12.sp)
    Slider(
        value = size,
        onValueChange = { host.onLocalIntent(Intent.SetEraserSize(it)) },
        valueRange = 5f..60f,
        modifier = Modifier.width(120.dp),
    )
    Text("${size.toInt()}px", color = Palette.HuiMo, fontSize = 12.sp)
}

// ================================================================ 形状默认
@Composable
private fun ShapeDefaultsBar(host: DrawBoxHost) {
    val state = host.state
    for ((s, label) in STROKE_STYLE_OPTIONS) {
        StyleChip(label, selected = state.currentItemStrokeStyle == s) {
            host.onLocalIntent(Intent.SetStrokeStyle(s))
        }
    }
    StyleDivider()

    Text("填充", color = Palette.HuiMo, fontSize = 12.sp)
    StyleChip("无", selected = state.currentItemFillColor == null) {
        host.onLocalIntent(Intent.SetFillColor(null))
    }
    for (c in INK_COLORS) {
        ColorDot(color = c, selected = state.currentItemFillColor == c) {
            host.onLocalIntent(Intent.SetFillColor(c))
        }
    }
    StyleDivider()

    StyleChip("描边", selected = state.currentItemStrokeEnabled) {
        host.onLocalIntent(Intent.SetStrokeEnabled(!state.currentItemStrokeEnabled))
    }
    Text("圆角", color = Palette.HuiMo, fontSize = 12.sp)
    Slider(
        value = state.currentItemCornerRadius,
        onValueChange = { host.onLocalIntent(Intent.SetCornerRadius(it)) },
        valueRange = 0f..24f,
        modifier = Modifier.width(80.dp),
    )
}

// ================================================================ 文字默认
@Composable
private fun TextDefaultsBar(host: DrawBoxHost) {
    val state = host.state
    for (size in FONT_SIZE_OPTIONS) {
        StyleChip("${size.toInt()}", selected = state.currentItemFontSize == size) {
            host.onLocalIntent(Intent.SetFontSize(size))
        }
    }
    StyleDivider()
    for ((key, label) in FONT_FAMILY_OPTIONS) {
        StyleChip(label, selected = state.currentItemFontFamilyKey == key) {
            host.onLocalIntent(Intent.SetFontFamily(key))
        }
    }
    StyleDivider()
    for ((al, label) in ALIGN_OPTIONS) {
        StyleChip(label, selected = state.currentItemTextAlignment == al) {
            host.onLocalIntent(Intent.SetTextAlignment(al))
        }
    }
}

// ================================================================ 画布背景
@Composable
private fun BackgroundBar(host: DrawBoxHost) {
    val bg = host.state.bgColor
    Text("背景", color = Palette.HuiMo, fontSize = 12.sp)
    ColorDot(color = Color.White, selected = bg == Color.White) {
        host.onLocalIntent(Intent.SetBgColor(Color.White))
    }
    ColorDot(color = Color.Black, selected = bg == Color.Black) {
        host.onLocalIntent(Intent.SetBgColor(Color.Black))
    }
    for (c in INK_COLORS) {
        ColorDot(color = c, selected = bg == c) {
            host.onLocalIntent(Intent.SetBgColor(c))
        }
    }
}

// ================================================================ 选中态查询
private fun firstSelectedStrokeColor(state: State): Color? =
    state.elements.firstOrNull { it.id in state.selectedIds }?.let { el ->
        when (el) {
            is Element.Path -> el.strokeColor
            is Element.Shape -> el.strokeColor
            is Element.Text -> el.color
            is Element.Image -> null
        }
    }

private fun firstSelectedShape(state: State): Element.Shape? =
    state.elements.firstOrNull { it.id in state.selectedIds && it is Element.Shape } as? Element.Shape

// ================================================================ 基础组件
@Composable
private fun StyleDivider() {
    Box(
        Modifier
            .size(width = 1.dp, height = 22.dp)
            .background(Palette.HuiMo.copy(alpha = 0.25f)),
    )
}

@Composable
private fun StyleChip(
    label: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    selected -> Palette.ZhuQing
                    enabled -> Palette.XuanZhiBai
                    else -> Color.Transparent
                },
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            color = when {
                selected -> Color.White
                enabled -> Palette.HuiMo
                else -> Palette.HuiMo.copy(alpha = 0.4f)
            },
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ColorDot(
    color: Color,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(if (selected) 22.dp else 18.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
    }
}

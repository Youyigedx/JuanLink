package com.juanlink.composeui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * 画布右下角缩放控制簇：缩小 / 百分比 / 放大 / 适应画布 / 100% / 网格开关。
 * 纯展示组件，动作经回调上抛（由宿主调度 Intent）。
 */
@Composable
fun ZoomControls(
    scalePercent: Int,
    showGrid: Boolean,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomToFit: () -> Unit,
    onZoomTo100: () -> Unit,
    onToggleGrid: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.DanMo.copy(alpha = 0.9f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ZoomChip("−", enabled = scalePercent > 10, onClick = onZoomOut)
        Text(
            "缩放 $scalePercent%",
            color = Palette.MoHei,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        ZoomChip("＋", enabled = scalePercent < 800, onClick = onZoomIn)
        ZoomChip("适应", onClick = onZoomToFit)
        ZoomChip("100%", onClick = onZoomTo100)
        if (onToggleGrid != null) {
            ZoomChip(
                if (showGrid) "网格开" else "网格",
                selected = showGrid,
                onClick = onToggleGrid,
            )
        }
    }
}

@Composable
private fun ZoomChip(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
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
            .padding(horizontal = 7.dp, vertical = 4.dp),
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

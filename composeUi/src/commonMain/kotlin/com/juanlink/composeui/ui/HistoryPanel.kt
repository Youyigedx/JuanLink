package com.juanlink.composeui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juanlink.composeui.draw.DrawingSnapshot
import com.juanlink.composeui.platform.formatTimestamp
import com.juanlink.composeui.theme.Palette
import io.ak1.drawbox.DrawBox
import io.ak1.drawbox.DrawingPreview
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Viewport
import io.ak1.drawbox.domain.model.bounds

/** 自动快照间隔预设（秒），0 = 关闭 */
val AUTO_INTERVAL_PRESETS = listOf(0 to "关闭", 30 to "30秒", 60 to "1分", 120 to "2分", 300 to "5分")

/** 缩略图尺寸（dp） */
private val THUMB_WIDTH = 96.dp
private val THUMB_HEIGHT = 64.dp

/**
 * 历史记录面板：DrawBox 画布快照列表（时间 + 缩略图 + 元素数）+ 手动快照 + 自动间隔设置。
 * 缩略图用 DrawBox 原生 [DrawingPreview] 渲染，视口按元素包围盒自适应。
 */
@Composable
fun HistoryPanel(
    snapshots: List<DrawingSnapshot>,
    autoIntervalSec: Int,
    onAutoIntervalChange: (Int) -> Unit,
    onManualCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(280.dp)
            .height(360.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.DanMo.copy(alpha = 0.94f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("历史记录", color = Palette.MoHei, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("${snapshots.size} 张", color = Palette.HuiMo, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            HistoryButton("快照") { onManualCapture() }
        }

        // 自动间隔
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("自动", color = Palette.HuiMo, fontSize = 12.sp)
            for ((sec, label) in AUTO_INTERVAL_PRESETS) {
                val selected = autoIntervalSec == sec
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) Palette.ZhuQing else Palette.XuanZhiBai)
                        .clickable { onAutoIntervalChange(sec) }
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(label, color = if (selected) Color.White else Palette.HuiMo, fontSize = 11.sp)
                }
            }
        }

        if (snapshots.isEmpty()) {
            Text("暂无快照。点击「快照」手动截取，或设置自动间隔。", color = Palette.HuiMo, fontSize = 12.sp)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (snap in snapshots.reversed()) {
                    SnapshotRow(snap)
                }
            }
        }
    }
}

@Composable
private fun SnapshotRow(snap: DrawingSnapshot) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.XuanZhiBai)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SnapshotThumb(snap)
        Column {
            Text(formatTimestamp(snap.timestamp), color = Palette.MoHei, fontSize = 12.sp)
            Text("元素 ${snap.elementCount}", color = Palette.HuiMo, fontSize = 11.sp)
        }
    }
}

/** 快照缩略图：用 DrawBox 原生渲染器，视口按元素包围盒自适应居中 */
@Composable
private fun SnapshotThumb(snap: DrawingSnapshot) {
    val payload = snap.payload
    val viewport = rememberThumbViewport(payload.elements)
    DrawingPreview(
        elements = payload.elements,
        bgColor = payload.bgColor,
        viewport = viewport,
        modifier = Modifier
            .size(width = THUMB_WIDTH, height = THUMB_HEIGHT)
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.XuanZhiBai),
    )
}

/**
 * 把元素包围盒适配进缩略图视口：等比缩放（上限 1，避免放大模糊）并居中。
 * 元素为空或几何损坏 → 恒等视口。
 */
@Composable
private fun rememberThumbViewport(elements: List<Element>): Viewport {
    val boxW = with(androidx.compose.ui.platform.LocalDensity.current) { THUMB_WIDTH.toPx() }
    val boxH = with(androidx.compose.ui.platform.LocalDensity.current) { THUMB_HEIGHT.toPx() }
    return androidx.compose.runtime.remember(elements) { fitViewport(elements, boxW, boxH) }
}

private fun fitViewport(elements: List<Element>, boxW: Float, boxH: Float): Viewport {
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = Float.MIN_VALUE
    var bottom = Float.MIN_VALUE
    var any = false
    for (el in elements) {
        val bb = runCatching { el.bounds() }.getOrNull() ?: continue
        left = minOf(left, bb.left); top = minOf(top, bb.top)
        right = maxOf(right, bb.right); bottom = maxOf(bottom, bb.bottom)
        any = true
    }
    if (!any || right <= left || bottom <= top) return Viewport()
    val w = (right - left).coerceAtLeast(1f)
    val h = (bottom - top).coerceAtLeast(1f)
    val scale = minOf(boxW / w, boxH / h, 1f).coerceAtLeast(0.01f)
    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f
    return Viewport(
        offset = Offset(boxW / 2f - cx * scale, boxH / 2f - cy * scale),
        scale = scale,
    )
}

@Composable
private fun HistoryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.ZhuQing)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

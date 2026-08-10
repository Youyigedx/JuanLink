package com.juanlink.composeui.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.juanlink.composeui.platform.formatTimestamp
import com.juanlink.composeui.theme.Palette
import com.juanlink.core.canvas.StrokeRasterizer
import com.juanlink.core.snapshot.CanvasSnapshot

/** 自动快照间隔预设（秒），0 = 关闭 */
val AUTO_INTERVAL_PRESETS = listOf(0 to "关闭", 30 to "30秒", 60 to "1分", 120 to "2分", 300 to "5分")

/**
 * 历史记录面板：画布快照列表（时间 + 缩略图 + 笔迹数）+ 手动快照 + 自动间隔设置。
 */
@Composable
fun HistoryPanel(
    snapshots: List<CanvasSnapshot>,
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
                for ((idx, snap) in snapshots.withIndex().reversed()) {
                    SnapshotRow(snap)
                }
            }
        }
    }
}

@Composable
private fun SnapshotRow(snap: CanvasSnapshot) {
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
            Text("笔迹 ${snap.strokeCount} · 图层 ${snap.layerCount}", color = Palette.HuiMo, fontSize = 11.sp)
        }
    }
}

/** 快照缩略图：把快照笔迹缩放绘制到小画布 */
@Composable
private fun SnapshotThumb(snap: CanvasSnapshot) {
    Canvas(Modifier.size(width = 96.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)).background(Palette.XuanZhiBai)) {
        val allPts = snap.strokes.flatMap { it.points }
        if (allPts.isEmpty()) return@Canvas
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        for (p in allPts) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val w = (maxX - minX).coerceAtLeast(1f)
        val h = (maxY - minY).coerceAtLeast(1f)
        val scale = minOf(size.width / w, size.height / h)
        val offX = (size.width - w * scale) / 2f - minX * scale
        val offY = (size.height - h * scale) / 2f - minY * scale

        for (stroke in snap.strokes) {
            for (seg in StrokeRasterizer.build(stroke)) {
                val path = Path()
                seg.points.forEachIndexed { i, p ->
                    val sx = p.x * scale + offX
                    val sy = p.y * scale + offY
                    if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                }
                drawPath(
                    path,
                    Color(seg.color).copy(alpha = seg.alpha.coerceIn(0f, 1f)),
                    style = Stroke(
                        width = (seg.width * scale).coerceAtLeast(0.8f),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
    }
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

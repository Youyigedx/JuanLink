package com.juanlink.composeui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.juanlink.core.canvas.CanvasDocument
import com.juanlink.core.canvas.CanvasOp
import com.juanlink.core.canvas.LayerAddOp
import com.juanlink.core.canvas.LayerRemoveOp
import com.juanlink.core.canvas.LayerReorderOp
import com.juanlink.core.canvas.LayerUpdateOp
import com.juanlink.core.model.Layer
import com.juanlink.core.util.nowEpochMillis

/**
 * 图层面板：列表 + 新建/删除/上移/下移/隐藏/锁定。
 */
@Composable
fun LayerPanel(
    document: CanvasDocument,
    docVersion: androidx.compose.runtime.State<Int>,
    onOp: (CanvasOp) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_EXPRESSION")
    val _refresh = docVersion.value
    val layers = document.layers.sortedBy { it.index }

    Column(
        modifier = modifier
            .width(230.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.DanMo.copy(alpha = 0.9f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("图层", color = Palette.MoHei, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            PanelButton("＋ 新建") {
                val idx = (document.layers.maxOfOrNull { it.index } ?: -1) + 1
                onOp(LayerAddOp(Layer(id = "layer-${nowEpochMillis()}", name = "图层 ${idx + 1}", index = idx)))
            }
        }
        for (layer in layers) {
            LayerRow(layer, onOp)
        }
    }
}

@Composable
private fun LayerRow(layer: Layer, onOp: (CanvasOp) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (layer.visible) Palette.XuanZhiBai else Palette.XuanZhiBaiDark)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 可见性
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (layer.visible) Palette.ZhuQing else Palette.HuiMo.copy(alpha = 0.3f))
                .clickable { onOp(LayerUpdateOp(layer.id, visible = !layer.visible)) },
        )
        Text(
            layer.name,
            color = if (layer.visible) Palette.MoHei else Palette.HuiMo.copy(alpha = 0.6f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        // 锁定
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(if (layer.locked) Palette.ZhuShaHong.copy(alpha = 0.2f) else Color.Transparent)
                .clickable { onOp(LayerUpdateOp(layer.id, locked = !layer.locked)) }
                .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
            Text(if (layer.locked) "锁" else "", color = Palette.ZhuShaHong, fontSize = 11.sp)
        }
        PanelButton("↑") { onOp(LayerReorderOp(layer.id, layer.index + 1)) }
        PanelButton("↓") { onOp(LayerReorderOp(layer.id, layer.index - 1)) }
        PanelButton("删") { onOp(LayerRemoveOp(layer.id)) }
    }
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.ZhuQing.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(label, color = Palette.ZhuQing, fontSize = 11.sp)
    }
}

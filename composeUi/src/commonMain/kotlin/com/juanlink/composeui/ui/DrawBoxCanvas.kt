package com.juanlink.composeui.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.juanlink.composeui.draw.DrawBoxHost
import com.juanlink.composeui.draw.fitViewport
import io.ak1.drawbox.DrawBox
import io.ak1.drawbox.domain.model.Intent

/** 按钮缩放的步进系数（与 Ctrl+滚轮一致） */
private const val ZOOM_STEP = 1.25f

/**
 * 协作画布：DrawBox 受控组件直连宿主。
 *
 * 缩放/平移/触控手势（滚轮、Ctrl+滚轮、双指捏合、空格临时抓手、中键拖拽）
 * 全部由 DrawBox 内置实现；本地意图经 `onIntent` 进宿主 diff 后广播为
 * 同步 op，远程元素变化由宿主的 `apply(op)` 直接改写 `state` 驱动重组。
 *
 * 右下角内置缩放控制簇（缩小/放大/适应/100%/网格），画布尺寸经
 * [Modifier.onSizeChanged] 捕获，缩放锚点为屏幕中心。
 */
@Composable
fun DrawBoxCanvas(
    host: DrawBoxHost,
    modifier: Modifier = Modifier,
    showGrid: Boolean = false,
    zoomControls: Boolean = true,
    onToggleGrid: (() -> Unit)? = null,
) {
    var canvasSize by remember { mutableStateOf<IntSize?>(null) }

    DrawBox(
        state = host.state,
        onIntent = { host.onLocalIntent(it) },
        modifier = modifier.onSizeChanged { canvasSize = it },
        // 纯白背景默认不绘制辅助网格；网格为本地会话装饰，不同步、不进导出
        showGrid = showGrid,
        overlay = {
            val size = canvasSize
            if (zoomControls && size != null) {
                val center = Offset(size.width / 2f, size.height / 2f)
                ZoomControls(
                    scalePercent = host.state.viewport.scalePercent,
                    showGrid = showGrid,
                    onZoomOut = { host.onLocalIntent(Intent.ZoomBy(1f / ZOOM_STEP, center)) },
                    onZoomIn = { host.onLocalIntent(Intent.ZoomBy(ZOOM_STEP, center)) },
                    onZoomToFit = {
                        host.setViewport(
                            fitViewport(
                                host.elements,
                                size.width.toFloat(),
                                size.height.toFloat(),
                            ),
                        )
                    },
                    onZoomTo100 = { host.onLocalIntent(Intent.ZoomTo(1f, center)) },
                    onToggleGrid = onToggleGrid,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                )
            }
        },
    )
}

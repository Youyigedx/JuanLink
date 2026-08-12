package com.juanlink.composeui.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.juanlink.composeui.draw.DrawBoxHost
import io.ak1.drawbox.DrawBox

/**
 * 协作画布：DrawBox 受控组件直连宿主。
 *
 * 缩放/平移/触控手势（滚轮、Ctrl+滚轮、双指捏合、空格临时抓手、中键拖拽）
 * 全部由 DrawBox 内置实现；本地意图经 `onIntent` 进宿主 diff 后广播为
 * 同步 op，远程元素变化由宿主的 `apply(op)` 直接改写 `state` 驱动重组。
 */
@Composable
fun DrawBoxCanvas(
    host: DrawBoxHost,
    modifier: Modifier = Modifier,
) {
    DrawBox(
        state = host.state,
        onIntent = { host.onLocalIntent(it) },
        modifier = modifier,
        // 纯白背景（与旧版一致），不绘制辅助网格
        showGrid = false,
    )
}

package com.juanlink.composeui.draw

import androidx.compose.ui.geometry.Offset
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Viewport
import io.ak1.drawbox.domain.model.bounds

/**
 * 把元素集合的包围盒适配进给定画布尺寸（等比缩放 + 居中）。
 * 元素为空或几何损坏 → 恒等视口。
 *
 * 历史缩略图与画布「适应」控件共用同一套适配逻辑。
 */
fun fitViewport(elements: List<Element>, boxW: Float, boxH: Float): Viewport {
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

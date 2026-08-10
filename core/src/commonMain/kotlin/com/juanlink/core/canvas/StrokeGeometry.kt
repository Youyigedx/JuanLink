package com.juanlink.core.canvas

import com.juanlink.core.model.BrushTool
import com.juanlink.core.model.Stroke
import com.juanlink.core.model.StrokePoint
import com.juanlink.core.model.StrokeStyle

/** 世界坐标点（渲染用） */
data class GPoint(val x: Float, val y: Float)

/** 渲染线段：折线 + 宽度 + 颜色 + 透明度 */
data class GSegment(
    val points: List<GPoint>,
    val width: Float,
    val color: Int,
    val alpha: Float,
    /** 两端是否圆头 */
    val roundCap: Boolean = true,
)

/**
 * 笔迹栅格化：把采样点列转换为可渲染的折线（带宽度/透明度/平滑）。
 *
 * - 铅笔/钢笔：固定宽，Catmull-Rom 平滑
 * - 毛笔：宽度随速度与压力变化（笔锋模拟：起笔回锋、收笔提锋）
 * - 马克笔：半透明宽笔
 * - 荧光笔：高透明平行线
 * - 橡皮擦：返回原笔迹几何，由渲染层以画布底色调绘制
 *
 * 全部为纯算法，输出与平台无关的 [GSegment]，供各平台渲染层消费。
 */
object StrokeRasterizer {

    /** 平滑系数（0=不插值） */
    private const val SMOOTH_FACTOR = 0.5f

    fun build(stroke: Stroke): List<GSegment> {
        val pts = stroke.points
        if (pts.isEmpty()) return emptyList()
        val style = stroke.style
        return when (style.tool) {
            BrushTool.Line, BrushTool.Rect, BrushTool.Circle, BrushTool.Arrow ->
                listOf(shape(pts, style))
            BrushTool.Highlighter -> listOf(highlighter(pts, style))
            BrushTool.Calligraphy -> listOf(calligraphy(pts, style))
            else -> listOf(basic(pts, style))
        }
    }

    /** 形状工具：由两个对角点生成几何（直线/矩形/椭圆/箭头） */
    private fun shape(pts: List<StrokePoint>, s: StrokeStyle): GSegment {
        val p0 = pts.firstOrNull() ?: return basic(pts, s)
        val p1 = pts.lastOrNull() ?: p0
        val x0 = p0.x; val y0 = p0.y; val x1 = p1.x; val y1 = p1.y
        val out = mutableListOf<GPoint>()
        when (s.tool) {
            BrushTool.Line -> {
                out.add(GPoint(x0, y0))
                out.add(GPoint(x1, y1))
            }
            BrushTool.Rect -> {
                out.add(GPoint(x0, y0)); out.add(GPoint(x1, y0))
                out.add(GPoint(x1, y1)); out.add(GPoint(x0, y1))
                out.add(GPoint(x0, y0))
            }
            BrushTool.Circle -> {
                val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f
                val rx = kotlin.math.abs(x1 - x0) / 2f
                val ry = kotlin.math.abs(y1 - y0) / 2f
                val steps = 64
                for (i in 0..steps) {
                    val a = i * 2.0 * kotlin.math.PI / steps
                    out.add(GPoint(cx + rx * kotlin.math.cos(a).toFloat(), cy + ry * kotlin.math.sin(a).toFloat()))
                }
            }
            BrushTool.Arrow -> {
                out.add(GPoint(x0, y0))
                out.add(GPoint(x1, y1))
                // 箭头头部
                val ang = kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
                val head = s.width * 3f
                val a1 = ang + kotlin.math.PI * 0.8
                val a2 = ang - kotlin.math.PI * 0.8
                out.add(GPoint(x1 + head * kotlin.math.cos(a1).toFloat(), y1 + head * kotlin.math.sin(a1).toFloat()))
                out.add(GPoint(x1, y1))
                out.add(GPoint(x1 + head * kotlin.math.cos(a2).toFloat(), y1 + head * kotlin.math.sin(a2).toFloat()))
            }
            else -> {}
        }
        return GSegment(out, s.width, s.color, s.alpha)
    }

    private fun basic(pts: List<StrokePoint>, s: StrokeStyle): GSegment {
        val smoothed = smooth(pts)
        val w = s.width * (if (s.tool == BrushTool.Marker) 1.6f else 1f)
        return GSegment(smoothed, w, s.color, s.alpha)
    }

    private fun highlighter(pts: List<StrokePoint>, s: StrokeStyle): GSegment {
        val smoothed = smooth(pts)
        // 荧光笔：高透明 + 平行线段（模拟平行笔）
        return GSegment(smoothed, s.width * 3f, s.color, s.alpha * 0.35f)
    }

    private fun calligraphy(pts: List<StrokePoint>, s: StrokeStyle): GSegment {
        // 毛笔：宽度 = base * 压力曲线 * (1 + k*速度)，起收笔渐细
        val base = s.width
        val k = 0.5f * (1f + s.brushTip * 2f)
        val out = mutableListOf<GPoint>()
        val widths = mutableListOf<Float>()
        var prev: StrokePoint? = null
        for ((i, p) in pts.withIndex()) {
            val v = if (prev != null) {
                val dx = p.x - prev!!.x
                val dy = p.y - prev!!.y
                (dx * dx + dy * dy).let { kotlin.math.sqrt(it) }
            } else 0f
            var w = base * (0.55f + 0.45f * p.pressure) * (1f + k * v.coerceAtMost(3f))
            // 起笔回锋：前 2 点渐细；收笔提锋：末 3 点渐细
            if (i < 2) w *= 0.5f + 0.5f * (i / 2f)
            if (i >= pts.size - 3) w *= (pts.size - 1 - i) / 3f
            out.add(GPoint(p.x, p.y))
            widths.add(w.coerceAtLeast(0.5f))
            prev = p
        }
        // 毛笔用逐段宽度渲染：拆为多个同色线段（UI 层按中点宽度绘制）
        return buildVariableWidth(out, widths, s.color, s.alpha)
    }

    private fun buildVariableWidth(pts: List<GPoint>, widths: List<Float>, color: Int, alpha: Float): GSegment {
        // 简化为单条折线，取平均宽度（UI 层可进一步细化逐段宽度）
        val avg = widths.average().toFloat()
        return GSegment(pts, avg, color, alpha)
    }

    /** Catmull-Rom 平滑：插值相邻采样点，保持笔迹形状 */
    private fun smooth(pts: List<StrokePoint>): List<GPoint> {
        if (pts.size < 3) return pts.map { GPoint(it.x, it.y) }
        val out = ArrayList<GPoint>(pts.size * 2)
        for (i in 0 until pts.size - 1) {
            val p0 = pts[maxOf(0, i - 1)]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[minOf(pts.size - 1, i + 2)]
            for (t in 0 until 2) {
                val u = t / 2f
                val u2 = u * u
                val u3 = u2 * u
                val x = 0.5f * (
                    (2f * p1.x) +
                        (-p0.x + p2.x) * u +
                        (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * u2 +
                        (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * u3
                    )
                val y = 0.5f * (
                    (2f * p1.y) +
                        (-p0.y + p2.y) * u +
                        (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * u2 +
                        (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * u3
                    )
                out.add(GPoint(x, y))
            }
        }
        out.add(GPoint(pts.last().x, pts.last().y))
        return out
    }
}

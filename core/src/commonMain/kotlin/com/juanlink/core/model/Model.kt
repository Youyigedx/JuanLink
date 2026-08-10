package com.juanlink.core.model

import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin

/**
 * 婵娟核心数据模型。
 *
 * 全部为纯 Kotlin @Serializable 数据类，可在 commonMain 编译并被
 * 协议层序列化为操作级同步消息。坐标一律使用**画布世界坐标**，
 * 与屏幕/视口无关，保证多端一致。
 */

/** 画笔工具类型 */
@Serializable
enum class BrushTool {
    Pencil,      // 铅笔
    Pen,         // 钢笔
    Calligraphy, // 毛笔
    Marker,      // 马克笔
    Highlighter, // 荧光笔
    Eraser,      // 橡皮擦
    Line,        // 直线
    Rect,        // 矩形
    Circle,      // 圆形
    Arrow,       // 箭头
}

/** 笔迹采样点（世界坐标） */
@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val timestamp: Long = 0L,
)

/** 笔迹样式 */
@Serializable
data class StrokeStyle(
    val color: Int = 0xFF1A1A1A.toInt(),  // ARGB，默认墨黑
    val width: Float = 3f,
    val alpha: Float = 1f,
    val tool: BrushTool = BrushTool.Pencil,
    /** 笔锋强度 0..1，影响毛笔起收笔的宽度渐变 */
    val brushTip: Float = 0f,
)

/** 一条完整笔迹（点列 + 样式） */
@Serializable
data class Stroke(
    val id: String,
    val layerId: String,
    val points: List<StrokePoint>,
    val style: StrokeStyle,
    /** 轴对齐包围盒（世界坐标），渲染裁剪与脏矩形判定用 */
    val bounds: RectF,
) {
    companion object {
        /** 由点列计算包围盒（含宽度外扩） */
        fun computeBounds(points: List<StrokePoint>, width: Float): RectF {
            if (points.isEmpty()) return RectF(0f, 0f, 0f, 0f)
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE
            for (p in points) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            val m = width / 2f + 1f
            return RectF(minX - m, minY - m, maxX + m, maxY + m)
        }
    }
}

/** 图层 */
@Serializable
data class Layer(
    val id: String,
    val name: String,
    val index: Int,       // z 序，越小越靠下
    val visible: Boolean = true,
    val opacity: Float = 1f,
    val locked: Boolean = false,
)

/** 轴对齐矩形 */
@Serializable
data class RectF(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun intersects(o: RectF): Boolean =
        left < o.right && right > o.left && top < o.bottom && bottom > o.top

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    fun union(o: RectF): RectF = RectF(
        minOf(left, o.left), minOf(top, o.top),
        maxOf(right, o.right), maxOf(bottom, o.bottom),
    )

    companion object {
        val EMPTY = RectF(0f, 0f, 0f, 0f)
    }
}

/**
 * 2D 仿射变换（KMP 无 android.graphics.Matrix，自实现）。
 *
 * |x'|   |a c e|   |x|
 * |y'| = |b d f| * |y|
 * |1 |   |0 0 1|   |1|
 *
 * 组合顺序：先应用的变换在左侧（列向量约定）。
 */
@Serializable
data class Affine2(
    val a: Double = 1.0, val b: Double = 0.0,
    val c: Double = 0.0, val d: Double = 1.0,
    val e: Double = 0.0, val f: Double = 0.0,
) {
    fun transform(x: Float, y: Float): FloatArray = floatArrayOf(
        (a * x + c * y + e).toFloat(),
        (b * x + d * y + f).toFloat(),
    )

    fun transform(x: Double, y: Double): DoubleArray = doubleArrayOf(a * x + c * y + e, b * x + d * y + f)

    /** 矩阵乘法：this 先作用于向量，再作用 other（结果 = this * other） */
    fun multiply(other: Affine2): Affine2 = Affine2(
        a * other.a + c * other.b, b * other.a + d * other.b,
        a * other.c + c * other.d, b * other.c + d * other.d,
        a * other.e + c * other.f + e, b * other.e + d * other.f + f,
    )

    fun inverse(): Affine2 {
        val det = a * d - b * c
        if (det == 0.0) return Affine2()
        val inv = 1.0 / det
        val na = d * inv
        val nb = -b * inv
        val nc = -c * inv
        val nd = a * inv
        return Affine2(na, nb, nc, nd, -(na * e + nc * f), -(nb * e + nd * f))
    }

    fun translate(dx: Double, dy: Double): Affine2 = multiply(Affine2(e = dx, f = dy))

    fun scale(sx: Double, sy: Double): Affine2 = multiply(Affine2(a = sx, d = sy))

    fun rotateDeg(deg: Double): Affine2 {
        val rad = deg * RAD_PER_DEG
        return multiply(Affine2(cos(rad), sin(rad), -sin(rad), cos(rad)))
    }

    val isIdentity: Boolean get() = a == 1.0 && b == 0.0 && c == 0.0 && d == 1.0 && e == 0.0 && f == 0.0

    companion object {
        val IDENTITY = Affine2()
        private const val RAD_PER_DEG = 0.017453292519943295
    }
}

/**
 * 图片对象：进入画布的图片，位置/旋转/缩放由 [transform] 表达（世界坐标）。
 * 图片上涂鸦 = 往同一图层叠加以图片局部坐标绘制的 Stroke。
 */
@Serializable
data class ImageObject(
    val id: String,
    val layerId: String,
    /** 平台图片句柄（URI），由 PlatformImageResolver 解码 */
    val uri: String,
    val transform: Affine2 = Affine2.IDENTITY,
    val width: Float = 0f,
    val height: Float = 0f,
) {
    /** 世界坐标包围盒（由本地坐标四角变换而来） */
    fun worldBounds(): RectF {
        if (width <= 0f || height <= 0f) return RectF.EMPTY
        val p0 = transform.transform(0f, 0f)
        val p1 = transform.transform(width, 0f)
        val p2 = transform.transform(0f, height)
        val p3 = transform.transform(width, height)
        val xs = floatArrayOf(p0[0], p1[0], p2[0], p3[0])
        val ys = floatArrayOf(p0[1], p1[1], p2[1], p3[1])
        return RectF(xs.min(), ys.min(), xs.max(), ys.max())
    }
}

/** 视口/相机：中心点 + 缩放（唯一相机状态） */
@Serializable
data class Viewport(
    val cx: Float = 0f,
    val cy: Float = 0f,
    val scale: Float = 1f,
) {
    fun worldToScreenX(wx: Float, width: Float): Float = (wx - cx) * scale + width / 2f
    fun worldToScreenY(wy: Float, height: Float): Float = (wy - cy) * scale + height / 2f
    fun screenToWorldX(sx: Float, width: Float): Float = (sx - width / 2f) / scale + cx
    fun screenToWorldY(sy: Float, height: Float): Float = (sy - height / 2f) / scale + cy

    /** 世界坐标下可见矩形 */
    fun visibleWorldRect(width: Float, height: Float): RectF = RectF(
        screenToWorldX(0f, width), screenToWorldY(0f, height),
        screenToWorldX(width, width), screenToWorldY(height, height),
    )
}

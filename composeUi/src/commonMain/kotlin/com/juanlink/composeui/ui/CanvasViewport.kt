package com.juanlink.composeui.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.juanlink.composeui.platform.decodeImageBytes
import com.juanlink.composeui.platform.wheelZoom
import com.juanlink.composeui.theme.Palette
import com.juanlink.core.canvas.CanvasDocument
import com.juanlink.core.canvas.CanvasOp
import com.juanlink.core.canvas.GPoint
import com.juanlink.core.canvas.ImageTransformOp
import com.juanlink.core.canvas.StrokeAdd
import com.juanlink.core.canvas.StrokeAppend
import com.juanlink.core.canvas.StrokeFinish
import com.juanlink.core.canvas.StrokeRasterizer
import com.juanlink.core.model.BrushTool
import com.juanlink.core.model.RectF
import com.juanlink.core.model.Stroke
import com.juanlink.core.model.StrokePoint
import com.juanlink.core.model.Viewport
import com.juanlink.core.util.nowEpochMillis

/**
 * 无限画布：
 * - 拖拽 = 绘制（选中画笔）或平移（手形工具）
 * - 滚轮 = 以光标为中心缩放（桌面；移动端触控缩放暂未接入）
 * - 增量渲染：仅在视口可见范围内的笔迹被绘制（分块思想简化为视口裁剪）
 */
@Composable
fun CanvasViewport(
    document: CanvasDocument,
    viewport: MutableState<Viewport>,
    tools: ToolState,
    docVersion: androidx.compose.runtime.State<Int>,
    onOp: (CanvasOp) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var currentStrokeId by remember { mutableStateOf<String?>(null) }
    var shapeStart by remember { mutableStateOf<StrokePoint?>(null) }
    var shapeEnd by remember { mutableStateOf<StrokePoint?>(null) }
    var draggingImage by remember { mutableStateOf<String?>(null) }
    var grabOffset by remember { mutableStateOf<GPoint?>(null) }
    var imageBitmaps by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var strokeSeq by remember { mutableStateOf(0) }

    val shapeTools = setOf(BrushTool.Line, BrushTool.Rect, BrushTool.Circle, BrushTool.Arrow)
    // 直接订阅文档版本 State：文档变更 → 本组件重组 → 画布重绘（不依赖父组件转发）
    @Suppress("UNUSED_EXPRESSION")
    val _refresh = docVersion.value

    DisposableEffect(document) {
        document.onImageReady = { id, bytes, _ ->
            val bmp = decodeImageBytes(bytes)
            if (bmp != null) imageBitmaps = imageBitmaps + (id to bmp)
        }
        onDispose {
            document.onImageReady = { _, _, _ -> }
        }
    }

    val vp = viewport.value

    /**
     * 屏幕 → 世界：每次读取最新视口。
     * ⚠️ 手势协程（pointerInput(Unit)）不会随重组重启，闭包捕获的 `vp` 会过期——
     * 缩放/平移后画笔/橡皮落点会错位。必须取 viewport.value 当前值。
     */
    fun toWorld(pos: Offset): StrokePoint {
        val v = viewport.value
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        return StrokePoint(v.screenToWorldX(pos.x, w), v.screenToWorldY(pos.y, h))
    }

    fun newStrokeId(): String {
        strokeSeq++
        return "stroke-${nowEpochMillis()}-$strokeSeq"
    }

    /** 开始一笔（越过触摸阈值后调用）：形状工具记录起点，否则直接落第一点 */
    fun beginStroke(pos: Offset, tool: BrushTool) {
        val id = newStrokeId()
        currentStrokeId = id
        val wp = toWorld(pos)
        if (shapeTools.contains(tool)) {
            shapeStart = wp
        } else {
            val stroke = Stroke(
                id = id,
                layerId = "layer-1",
                points = listOf(wp),
                style = tools.style(),
                bounds = RectF(wp.x, wp.y, wp.x, wp.y),
            )
            onOp(StrokeAdd(stroke))
        }
    }

    /** 多指质心 */
    fun centroidOf(changes: List<PointerInputChange>): Offset {
        var x = 0f
        var y = 0f
        for (c in changes) {
            x += c.position.x
            y += c.position.y
        }
        val n = changes.size.coerceAtLeast(1)
        return Offset(x / n, y / n)
    }

    /** 双指间距（缩放比计算基准） */
    fun spanOf(changes: List<PointerInputChange>): Float {
        if (changes.size < 2) return 0f
        val a = changes[0].position
        val b = changes[1].position
        val dx = b.x - a.x
        val dy = b.y - a.y
        return kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
    }

    /** 双指变换：以质心为锚缩放 + 双指位移平移画布 */
    fun applyViewportTransform(centroid: Offset, pan: Offset, zoom: Float) {
        val vp = viewport.value
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val newScale = (vp.scale * zoom).coerceIn(0.05f, 60f)
        // 质心对应的世界点在新 scale 下应仍映射到质心屏幕位置
        val wx = vp.screenToWorldX(centroid.x, w)
        val wy = vp.screenToWorldY(centroid.y, h)
        val cx = wx - (centroid.x - w / 2f) / newScale
        val cy = wy - (centroid.y - h / 2f) / newScale
        // 叠加双指位移（屏幕像素 → 世界坐标），内容跟随手指移动
        viewport.value = Viewport(cx - pan.x / newScale, cy - pan.y / newScale, newScale)
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { size = it }
            .wheelZoom(viewport) { size }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startPos = down.position
                    val slop = viewConfiguration.touchSlop
                    var mode = "idle" // idle / draw / pan / image / transform
                    var lastCentroid = startPos
                    var lastSpan = 0f

                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            // 双指：捏合缩放 + 双指位移平移画布
                            pressed.size >= 2 -> {
                                if (mode != "transform") {
                                    // 取消进行中的单指手势，进入变换模式
                                    currentStrokeId = null
                                    shapeStart = null
                                    shapeEnd = null
                                    draggingImage = null
                                    grabOffset = null
                                    mode = "transform"
                                    lastCentroid = centroidOf(pressed)
                                    lastSpan = spanOf(pressed)
                                } else {
                                    val c = centroidOf(pressed)
                                    val s = spanOf(pressed)
                                    val zoom = if (lastSpan > 0f) s / lastSpan else 1f
                                    val pan = c - lastCentroid
                                    applyViewportTransform(c, pan, zoom)
                                    lastCentroid = c
                                    lastSpan = s
                                }
                            }
                            pressed.size == 1 -> {
                                val ch = pressed[0]
                                when (mode) {
                                    "transform" -> mode = "idle" // 一根手指抬起，退出变换
                                    "draw" -> {
                                        val id = currentStrokeId
                                        val tool = tools.tool
                                        val wp = toWorld(ch.position)
                                        if (shapeTools.contains(tool) && shapeStart != null) {
                                            shapeEnd = wp
                                        } else if (id != null) {
                                            onOp(StrokeAppend(id, listOf(wp)))
                                        }
                                        ch.consume()
                                    }
                                    "image" -> {
                                        val imgId = draggingImage
                                        val off = grabOffset
                                        if (imgId != null && off != null) {
                                            val wp = toWorld(ch.position)
                                            val img = document.imageById(imgId)
                                            if (img != null) {
                                                val nt = img.transform.copy(
                                                    e = (wp.x - off.x).toDouble(),
                                                    f = (wp.y - off.y).toDouble(),
                                                )
                                                onOp(ImageTransformOp(imgId, nt))
                                            }
                                        }
                                        ch.consume()
                                    }
                                    "pan" -> {
                                        val vp = viewport.value
                                        val d = ch.position - ch.previousPosition
                                        viewport.value = Viewport(
                                            cx = vp.cx - d.x / vp.scale,
                                            cy = vp.cy - d.y / vp.scale,
                                            scale = vp.scale,
                                        )
                                        ch.consume()
                                    }
                                    else -> {
                                        // idle：越过触摸阈值后判定进入绘制/平移/图片
                                        val dist = kotlin.math.sqrt(
                                            (ch.position.x - startPos.x) * (ch.position.x - startPos.x) +
                                                (ch.position.y - startPos.y) * (ch.position.y - startPos.y)
                                        )
                                        if (dist > slop) {
                                            val tool = tools.tool
                                            if (tool != null) {
                                                mode = "draw"
                                                beginStroke(startPos, tool)
                                            } else {
                                                // 手形：命中图片则拖动，否则平移视口
                                                val wp = toWorld(startPos)
                                                val hit = document.allImages()
                                                    .firstOrNull { it.worldBounds().contains(wp.x, wp.y) }
                                                if (hit != null) {
                                                    mode = "image"
                                                    draggingImage = hit.id
                                                    grabOffset = GPoint(
                                                        wp.x - hit.transform.e.toFloat(),
                                                        wp.y - hit.transform.f.toFloat(),
                                                    )
                                                } else {
                                                    mode = "pan"
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    // 手势结束收尾
                    when (mode) {
                        "draw" -> {
                            val id = currentStrokeId
                            val tool = tools.tool
                            if (id != null) {
                                if (shapeTools.contains(tool) && shapeStart != null && shapeEnd != null) {
                                    // 形状：构建 [起, 终] 两点笔迹
                                    val start = shapeStart!!
                                    val end = shapeEnd!!
                                    onOp(StrokeAdd(Stroke(
                                        id = id,
                                        layerId = "layer-1",
                                        points = listOf(start, end),
                                        style = tools.style(),
                                        bounds = RectF(
                                            minOf(start.x, end.x), minOf(start.y, end.y),
                                            maxOf(start.x, end.x), maxOf(start.y, end.y),
                                        ),
                                    )))
                                } else {
                                    onOp(StrokeFinish(id))
                                }
                            }
                            currentStrokeId = null
                            shapeStart = null
                            shapeEnd = null
                        }
                        "image" -> {
                            draggingImage = null
                            grabOffset = null
                        }
                        else -> {
                            currentStrokeId = null
                            shapeStart = null
                            shapeEnd = null
                            draggingImage = null
                            grabOffset = null
                        }
                    }
                }
            },
    ) {
        // 关键：在 draw 块内读取文档版本 State —— 状态变化时直接使绘制失效并重绘，
        // 不依赖组合重组（重组可能被 Compose 跳过导致不重绘）。
        @Suppress("UNUSED_EXPRESSION")
        val _drawRefresh = docVersion.value

        val w = size.width.toFloat()
        val h = size.height.toFloat()
        // 宣纸底
        drawRect(Palette.XuanZhiBai)

        /** 淡墨网格（固定屏幕大小，不随缩放变化）—— 只在背景上画一次，永不被橡皮触碰 */
        fun drawGrid(w: Float, h: Float) {
            // 参考线固定 50px 屏幕间距：缩放画布内容时网格保持不变，充当固定参照
            val step = 50f
            var sx = 0f
            while (sx <= w) {
                drawLine(Palette.XuanZhiBaiDark, Offset(sx, 0f), Offset(sx, h), strokeWidth = 1f)
                sx += step
            }
            var sy = 0f
            while (sy <= h) {
                drawLine(Palette.XuanZhiBaiDark, Offset(0f, sy), Offset(w, sy), strokeWidth = 1f)
                sy += step
            }
        }
        drawGrid(w, h)

        val visible = vp.visibleWorldRect(w, h)
        val visibleLayers = document.layers.filter { it.visible }.map { it.id }.toSet()

        /** 渲染一条笔迹（折线 + 宽度/圆帽/透明度） */
        fun drawStroke(stroke: Stroke) {
            for (seg in StrokeRasterizer.build(stroke)) {
                val color = Color(seg.color).copy(alpha = seg.alpha.coerceIn(0f, 1f))
                val path = Path()
                seg.points.forEachIndexed { i, p ->
                    val sx = vp.worldToScreenX(p.x, w)
                    val sy = vp.worldToScreenY(p.y, h)
                    if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                }
                drawPath(
                    path,
                    color,
                    style = DrawStroke(
                        width = seg.width * vp.scale,
                        cap = if (seg.roundCap) StrokeCap.Round else StrokeCap.Butt,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }

        /** 把一条橡皮笔迹的涂抹区域（世界坐标）累加成屏幕坐标路径：圆帽粗折线 = 每段胶囊并集 */
        fun eraserSwathInto(stroke: Stroke, path: Path) {
            val pts = stroke.points
            val r = stroke.style.width / 2f
            fun dot(wx: Float, wy: Float) {
                path.lineTo(vp.worldToScreenX(wx, w), vp.worldToScreenY(wy, h))
            }
            // 绕 (cx,cy) 的弧：法线 n 为固定侧，角度从 fromDeg 线性扫到 toDeg（10 段采样）
            fun arc(cx: Float, cy: Float, ux: Float, uy: Float, nx: Float, ny: Float, fromDeg: Int, toDeg: Int) {
                for (k in 1..10) {
                    val th = (fromDeg + (toDeg - fromDeg) * k / 10.0) * kotlin.math.PI / 180.0
                    val x = cx + (kotlin.math.cos(th) * ux + kotlin.math.sin(th) * nx) * r
                    val y = cy + (kotlin.math.cos(th) * uy + kotlin.math.sin(th) * ny) * r
                    dot(x.toFloat(), y.toFloat())
                }
            }
            if (pts.size == 1) {
                // 单击：整圆
                val p0 = pts[0]
                val step = 2.0 * kotlin.math.PI / 20.0
                path.moveTo(vp.worldToScreenX(p0.x + r, w), vp.worldToScreenY(p0.y, h))
                for (k in 1..20) {
                    val th = step * k
                    dot(p0.x + r * kotlin.math.cos(th).toFloat(), p0.y + r * kotlin.math.sin(th).toFloat())
                }
                path.close()
                return
            }
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                val dx = b.x - a.x
                val dy = b.y - a.y
                val len = kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
                if (len < 1e-4f) continue
                val ux = dx / len
                val uy = dy / len
                val nx = -uy
                val ny = ux
                // 胶囊轮廓：a 端弧（背离 b 的半圆，90°→270°）→ 直线边 → b 端弧（270°→450°）→ 闭合
                path.moveTo(vp.worldToScreenX(a.x + nx * r, w), vp.worldToScreenY(a.y + ny * r, h))
                arc(a.x, a.y, ux, uy, nx, ny, 90, 270)
                dot(b.x - nx * r, b.y - ny * r)
                arc(b.x, b.y, ux, uy, nx, ny, 270, 450)
                path.close()
            }
        }

        // 橡皮 = 遮罩方案（不画白底、不碰网格）：
        // - 网格只在背景上画一次 → 擦除区直接透出网格，彻底根除"参考线压顶/覆盖旧笔迹"
        // - 橡皮笔迹不渲染，只把其涂抹路径（屏幕坐标胶囊并集）按 z 序登记
        // - 每条墨线被 z 序更晚（index 更大）的橡皮擦除：橡皮只擦它下方先画的墨线，
        //   故擦除后新画的笔迹不受影响（新墨线 index 更大，不被早前橡皮裁剪）
        val strokes = document.allStrokes()
        data class EraserSlot(val index: Int, val path: Path)
        val eraserSlots = buildList<EraserSlot> {
            for ((i, stroke) in strokes.withIndex()) {
                if (stroke.style.tool != BrushTool.Eraser) continue
                if (stroke.layerId !in visibleLayers) continue
                if (stroke.points.isEmpty()) continue
                val b = Stroke.computeBounds(stroke.points, stroke.style.width)
                if (!b.intersects(visible)) continue
                val p = Path()
                eraserSwathInto(stroke, p)
                add(EraserSlot(i, p))
            }
        }
        for ((i, stroke) in strokes.withIndex()) {
            if (stroke.layerId !in visibleLayers) continue
            if (stroke.style.tool == BrushTool.Eraser) continue
            if (!stroke.bounds.intersects(visible)) continue
            var clip: Path? = null
            for (slot in eraserSlots) {
                if (slot.index > i) {
                    if (clip == null) clip = Path()
                    clip.addPath(slot.path)
                }
            }
            if (clip != null) {
                clipPath(clip, ClipOp.Difference) { drawStroke(stroke) }
            } else {
                drawStroke(stroke)
            }
        }

        // 绘制图片（变换：平移 + 旋转 + 缩放）
        for (img in document.allImages()) {
            if (img.layerId !in visibleLayers) continue
            val bitmap = imageBitmaps[img.id] ?: continue
            val t = img.transform
            val sx = vp.worldToScreenX(t.e.toFloat(), size.width.toFloat())
            val sy = vp.worldToScreenY(t.f.toFloat(), size.height.toFloat())
            val angleDeg = (kotlin.math.atan2(t.b, t.a) * 180.0 / kotlin.math.PI).toFloat()
            val scaleX = kotlin.math.sqrt(t.a * t.a + t.b * t.b).toFloat() * vp.scale
            val scaleY = kotlin.math.sqrt(t.c * t.c + t.d * t.d).toFloat() * vp.scale
            withTransform({
                translate(sx, sy)
                rotate(angleDeg)
                scale(scaleX, scaleY)
            }) {
                drawImage(bitmap)
            }
        }

        // 形状工具实时预览：拖动过程中以 [起点, 当前点] 栅格化显示，抬起时才提交。
        // 读取 shapeStart/shapeEnd/tool 状态 → 拖动中状态变化自动触发重绘。
        val ps = shapeStart
        val pe = shapeEnd
        val ptool = tools.tool
        if (ps != null && pe != null && shapeTools.contains(ptool)) {
            drawStroke(
                Stroke(
                    id = "__preview__",
                    layerId = "layer-1",
                    points = listOf(ps, pe),
                    style = tools.style(),
                    bounds = RectF.EMPTY,
                )
            )
        }
    }
}

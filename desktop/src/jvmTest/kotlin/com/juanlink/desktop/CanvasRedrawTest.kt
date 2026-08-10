@file:OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)

package com.juanlink.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.juanlink.composeui.AppState
import com.juanlink.composeui.ui.CanvasViewport
import com.juanlink.composeui.ui.ToolState
import com.juanlink.core.canvas.StrokeAdd
import com.juanlink.core.model.RectF
import com.juanlink.core.model.Stroke
import com.juanlink.core.model.StrokePoint
import com.juanlink.core.model.StrokeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证画布在文档变更后必须立即重绘（不依赖按钮点击触发重组）。
 */
class CanvasRedrawTest {

    private fun stroke(id: String): Stroke = Stroke(
        id = id,
        layerId = "layer-1",
        points = listOf(StrokePoint(0f, 0f), StrokePoint(50f, 50f)),
        style = StrokeStyle(),
        bounds = RectF(0f, 0f, 50f, 50f),
    )

    /** 机制验证：在 draw 块内读取 State，状态变化时 draw 必须重跑 */
    @Test
    fun drawRerunsWhenStateReadInsideDraw() = runComposeUiTest {
        val version = mutableIntStateOf(0)
        val drawnVersions = mutableListOf<Int>()
        setContent {
            Canvas(Modifier.size(200.dp)) {
                // 在 draw 内读状态 —— 状态变化应使本次 draw 失效并重跑
                drawnVersions.add(version.value)
            }
        }
        waitForIdle()
        val before = drawnVersions.size
        assertTrue(before >= 1, "初始应至少绘制一次")

        version.value = 1
        waitForIdle()
        assertTrue(drawnVersions.size > before, "状态变化后 draw 应重跑（关键机制）")
        assertEquals(1, drawnVersions.last(), "draw 应读到最新状态值")
    }

    /** 集成验证：CanvasViewport 渲染 + 应用笔画 → 文档与版本更新 */
    @Test
    fun canvasViewportSeesStrokeAndVersion() = runComposeUiTest {
        val app = AppState()
        setContent {
            Box(Modifier.size(400.dp)) {
                CanvasViewport(
                    document = app.document,
                    viewport = app.viewport,
                    tools = ToolState(),
                    docVersion = app.docVersion,
                    onOp = { app.applyLocal(it) },
                    modifier = Modifier.size(400.dp),
                )
            }
        }
        waitForIdle()

        app.applyLocal(StrokeAdd(stroke("s1")))
        waitForIdle()

        assertEquals(1, app.document.allStrokes().size, "笔画应进入文档")
        assertEquals(1, app.docVersion.value, "文档版本应自增（驱动画布重绘）")
    }
}

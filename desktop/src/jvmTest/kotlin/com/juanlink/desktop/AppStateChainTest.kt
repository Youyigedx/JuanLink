package com.juanlink.desktop

import com.juanlink.composeui.AppState
import com.juanlink.core.canvas.StrokeAdd
import com.juanlink.core.model.RectF
import com.juanlink.core.model.Stroke
import com.juanlink.core.model.StrokePoint
import com.juanlink.core.model.StrokeStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证「绘画 → document.onChange → docVersion++」链路：
 * 字迹必须实时反映到画布，不依赖任何按钮点击。
 */
class AppStateChainTest {

    private fun stroke(id: String): Stroke = Stroke(
        id = id,
        layerId = "layer-1",
        points = listOf(StrokePoint(0f, 0f), StrokePoint(5f, 5f)),
        style = StrokeStyle(),
        bounds = RectF(0f, 0f, 5f, 5f),
    )

    @Test
    fun applyingLocalStrokeIncrementsDocVersion() {
        val app = AppState()
        val before = app.docVersion.value
        app.applyLocal(StrokeAdd(stroke("s1")))
        assertTrue(app.docVersion.value > before, "applyLocal 后 docVersion 应自增（触发画布重组）")
        assertEquals(1, app.document.allStrokes().size)
    }

    @Test
    fun appStateOnChangeWireIsActive() {
        val app = AppState()
        val doc = app.document
        // AppState.init 已设 document.onChange = { docVersion++ }
        app.applyLocal(StrokeAdd(stroke("s1")))
        app.applyLocal(StrokeAdd(stroke("s2")))
        assertEquals(2, app.docVersion.value, "每次笔画都应使 docVersion 自增")
        assertEquals(2, doc.allStrokes().size)
    }
}

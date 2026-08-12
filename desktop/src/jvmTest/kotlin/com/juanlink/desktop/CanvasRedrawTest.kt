@file:OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)

package com.juanlink.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.juanlink.composeui.AppState
import com.juanlink.composeui.ui.DrawBoxCanvas
import io.ak1.drawbox.domain.model.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证画布在文档变更后必须立即重绘（不依赖按钮点击触发重组）。
 * 机制：DrawBoxHost.state 为 Compose 响应式状态，本地意图经宿主应用后
 * 状态变化驱动 DrawBox 重绘（不再有 docVersion 计数器）。
 */
class CanvasRedrawTest {

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

    /** 集成验证：DrawBox 画布渲染 + 本地画线 → 宿主状态更新（驱动重绘） */
    @Test
    fun drawBoxCanvasSeesLocalStroke() = runComposeUiTest {
        val app = AppState()
        setContent {
            Box(Modifier.size(400.dp)) {
                DrawBoxCanvas(
                    host = app.host,
                    modifier = Modifier.size(400.dp),
                )
            }
        }
        waitForIdle()

        app.host.onLocalIntent(Intent.InsertNewPath(Offset(0f, 0f)))
        app.host.onLocalIntent(Intent.UpdateLatestPath(Offset(50f, 50f)))
        waitForIdle()

        assertEquals(1, app.host.state.elements.size, "本地画线应进入宿主状态（驱动画布重绘）")
    }
}

@file:OptIn(ExperimentalTestApi::class)

package com.juanlink.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.juanlink.composeui.AppState
import com.juanlink.composeui.ui.DrawBoxCanvas
import com.juanlink.composeui.ui.TextEditOverlay
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 文字工具 UI 链路验证：
 * 1. Mode.TEXT 点击画布 → DrawBox 手势发 InsertText → 宿主插入空文本 + 弹编辑框
 * 2. TextEditOverlay 输入文字 + 点确定 → 回调携带输入内容
 */
class TextToolUiTest {

    @Test
    fun textTapInsertsElementAndOpensEditor() = runComposeUiTest {
        val app = AppState()
        setContent {
            Box(Modifier.size(400.dp)) {
                DrawBoxCanvas(app.host, Modifier.size(400.dp))
            }
        }
        waitForIdle()
        app.host.onLocalIntent(Intent.SetMode(Mode.TEXT))
        waitForIdle()

        // 模拟用户点击画布中心（标准 tap 注入：down → 停顿 → up）。
        // onTap 已移除 doubleTapTimeout 等待（onDoubleTap 不再注册），tap 应
        // 立即响应，无需推进虚拟时钟。
        onRoot().performTouchInput {
            down(center)
            advanceEventTime(16)
            up()
        }
        waitForIdle()

        println("TEXT tap → mode=${app.host.state.mode} elements=${app.host.state.elements.size} selected=${app.host.state.selectedIds}")
        assertEquals(1, app.host.state.elements.size, "TEXT 模式点击画布应插入文本元素")
        val text = app.host.state.elements.first() as Element.Text
        assertEquals("", text.text, "新插入的文本元素应为空（等待编辑）")
        assertNotNull(app.editingTextId, "插入后应触发文本编辑请求（弹出编辑框）")
    }

    /** 对照：PEN 模式拖拽应插入路径（验证 pointer 事件到达 onDragStart） */
    @Test
    fun penDragInsertsPath() = runComposeUiTest {
        val app = AppState()
        setContent {
            Box(Modifier.size(400.dp)) {
                DrawBoxCanvas(app.host, Modifier.size(400.dp))
            }
        }
        waitForIdle()
        app.host.onLocalIntent(Intent.SetMode(Mode.PEN))
        waitForIdle()
        onRoot().performTouchInput {
            down(center)
            moveBy(Offset(50f, 50f))
            up()
        }
        waitForIdle()
        println("PEN drag → mode=${app.host.state.mode} elements=${app.host.state.elements.size}")
        assertTrue(app.host.state.elements.isNotEmpty(), "PEN 模式拖拽应插入路径")
    }

    @Test
    fun overlayEditsAndCommits() = runComposeUiTest {
        val committed = mutableListOf<String>()
        var dismissed = false
        setContent {
            TextEditOverlay(
                title = "编辑文字",
                initialText = "",
                onCommit = { committed.add(it) },
                onDismiss = { dismissed = true },
            )
        }
        waitForIdle()

        // 输入文字（OutlinedTextField 应可聚焦并接收输入）
        onNode(hasSetTextAction()).performTextInput("你好")
        onNodeWithText("确定").performClick()
        waitForIdle()

        assertEquals(listOf("你好"), committed, "点确定应回调输入内容")
        assertEquals(false, dismissed, "确定不应触发关闭回调")
    }

    @Test
    fun overlayCancelDoesNotCommit() = runComposeUiTest {
        var committed = false
        var dismissed = false
        setContent {
            TextEditOverlay(
                title = "编辑文字",
                initialText = "",
                onCommit = { committed = true },
                onDismiss = { dismissed = true },
            )
        }
        waitForIdle()
        onNodeWithText("取消").performClick()
        waitForIdle()
        assertEquals(false, committed, "取消不应提交")
        assertEquals(true, dismissed, "取消应触发关闭回调")
    }
}

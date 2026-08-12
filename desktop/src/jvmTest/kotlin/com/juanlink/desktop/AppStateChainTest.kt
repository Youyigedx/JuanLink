package com.juanlink.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.juanlink.composeui.AppState
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.TextAlignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 验证「本地意图 → host.state → 同步引擎」链路：
 * 画线必须实时反映到 DrawBox 宿主状态（响应式驱动画布重组），且 host 与
 * drawSync 正确接线，撤销经引擎逆 op 生效。
 */
class AppStateChainTest {

    @Test
    fun paintingInsertsElementAndWiresEngine() {
        val app = AppState()
        assertEquals(0, app.host.state.elements.size)
        assertSame(app.host.engine, app.drawSync, "host.engine 应与 drawSync 同一实例")

        app.host.onLocalIntent(Intent.InsertNewPath(Offset(0f, 0f)))
        assertEquals(1, app.host.state.elements.size, "本地画线应进入 host.state（驱动画布重组）")
    }

    @Test
    fun drawingGestureEnablesUndoAndUndoReverts() {
        val app = AppState()
        // 一次手势：插入起点 + 更新终点 → 同一元素第二次 upsert 合并为一条 undo
        app.host.onLocalIntent(Intent.InsertNewPath(Offset(0f, 0f)))
        app.host.onLocalIntent(Intent.UpdateLatestPath(Offset(10f, 10f)))
        assertEquals(1, app.host.state.elements.size)
        assertTrue(app.canUndo, "手势完成应产生可撤销项")

        app.undo()
        assertFalse(app.canUndo, "单条合并 undo 消费后应清空")
        assertEquals(0, app.host.state.elements.size, "撤销应删除整条手势（回到手势前）")
    }

    @Test
    fun captureSnapshotSerializesElements() {
        val app = AppState()
        app.host.onLocalIntent(Intent.InsertNewPath(Offset(0f, 0f)))
        app.captureSnapshot()
        assertEquals(1, app.snapshots.size, "捕获应产生一张快照")
        assertEquals(1, app.snapshots.first().elementCount, "快照应序列化当前元素")
    }

    @Test
    fun textInsertionOpensEditorAndCommitUpdatesElement() {
        val app = AppState()
        // Mode.TEXT 点击等价：宿主插入空文本元素 → 立即触发文本编辑请求
        app.host.onLocalIntent(
            Intent.InsertText("", Offset.Zero, 20f, "sans", TextAlignment.LEFT, Color.Black),
        )
        val id = app.editingTextId
        assertNotNull(id, "插入空文本应触发编辑请求")
        assertEquals("", app.textDraft, "新建文本预填为空")

        app.commitTextEdit("你好")
        assertNull(app.editingTextId, "提交后应关闭编辑框")
        val el = app.host.state.elements.single() as Element.Text
        assertEquals("你好", el.text, "UpdateText 应写入元素")
    }

    @Test
    fun dismissOnEmptyInsertDeletesElement() {
        val app = AppState()
        app.host.onLocalIntent(
            Intent.InsertText("", Offset.Zero, 20f, "sans", TextAlignment.LEFT, Color.Black),
        )
        assertNotNull(app.editingTextId)
        app.dismissTextEdit()
        assertNull(app.editingTextId)
        assertEquals(0, app.host.state.elements.size, "取消未填写的空文本应删除占位元素")
    }

    @Test
    fun doubleTapTextRequestsEditWithCurrentContent() {
        val app = AppState()
        app.host.onLocalIntent(
            Intent.InsertText("旧文字", Offset.Zero, 20f, "sans", TextAlignment.LEFT, Color.Black),
        )
        app.dismissTextEdit()
        // SELECT 模式双击已存在文本 → RequestTextEditAt 命中 → 触发编辑并预填当前内容
        app.host.onLocalIntent(Intent.RequestTextEditAt(Offset(5f, 5f)))
        assertEquals("旧文字", app.textDraft, "双击编辑应预填当前文本内容")
        app.commitTextEdit("新文字")
        assertEquals("新文字", (app.host.state.elements.single() as Element.Text).text)
    }
}

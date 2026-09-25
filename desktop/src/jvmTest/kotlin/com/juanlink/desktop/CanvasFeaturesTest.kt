package com.juanlink.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.juanlink.composeui.AppState
import com.juanlink.core.draw.DrawOp
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.model.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 画布模块重构后新增能力的链路测试：
 * 背景色同步（CanvasMetaOp）、复制选中、清空画布（保留背景/样式）、
 * 文本样式提交、Undo/Redo 意图防御（不落入 reducer 快照栈）。
 */
class CanvasFeaturesTest {

    private fun drawPath(app: AppState) {
        app.host.onLocalIntent(Intent.InsertNewPath(Offset(0f, 0f)))
        app.host.onLocalIntent(Intent.UpdateLatestPath(Offset(10f, 10f)))
    }

    @Test
    fun bgColorChangeBroadcastsAndUndoReverts() {
        val app = AppState()
        val target = Color(0xFF2F6F5E)
        app.host.onLocalIntent(Intent.SetBgColor(target))
        assertEquals(target, app.host.state.bgColor, "本地背景色应即时生效")
        assertEquals(1, app.drawSync.pendingOutCount, "背景色变更应产生 1 条待确认同步 op")

        // 撤销：逆 op（旧背景色）应恢复
        assertTrue(app.canUndo)
        app.undo()
        assertEquals(Color.White, app.host.state.bgColor, "撤销应恢复变更前背景色")
    }

    @Test
    fun remoteCanvasMetaOpApplies() {
        // 对端应用背景色 op → 本端背景色跟随
        val app = AppState()
        assertTrue(app.host.apply(DrawOp.CanvasMetaOp("#ffc0392b")))
        // 经 hex 往返断言（toHexString 输出小写，且 Compose Color 的
        // Float/Long 构造路径存在低 16 位舍入差异，不做严格相等）
        assertEquals("#ffc0392b", app.host.state.bgColor.toHexString())
        // 相同背景色幂等：不产生状态变更
        assertFalse(app.host.apply(DrawOp.CanvasMetaOp("#ffc0392b")))
        // 非法 hex：忽略不崩溃、不改背景
        assertFalse(app.host.apply(DrawOp.CanvasMetaOp("not-a-color")))
        assertEquals("#ffc0392b", app.host.state.bgColor.toHexString())
    }

    @Test
    fun duplicateSelectedCreatesOffsetCopiesAndSelectsThem() {
        val app = AppState()
        drawPath(app)
        assertEquals(1, app.host.state.elements.size)
        val originalId = app.host.state.elements.single().id

        app.host.onLocalIntent(Intent.SelectAt(Offset(5f, 5f), 12f))
        assertEquals(setOf(originalId), app.host.state.selectedIds)

        val copied = app.duplicateSelected()
        assertEquals(1, copied)
        assertEquals(2, app.host.state.elements.size, "复制后应有 2 个元素")
        val copy = app.host.state.elements.first { it.id != originalId }
        assertTrue(copy is Element.Path)
        assertTrue(
            copy.samples.first().position.x > 0f && copy.samples.first().position.y > 0f,
            "副本应相对原图偏移（避免完全重叠）",
        )
        assertEquals(setOf(copy.id), app.host.state.selectedIds, "复制后应选中副本")
        assertTrue(app.drawSync.pendingOutCount > 0, "复制应广播 upsert 同步 op")
    }

    @Test
    fun clearCanvasKeepsBackgroundAndStyleAndIsUndoable() {
        val app = AppState()
        drawPath(app)
        val bg = Color(0xFF7A4E2D)
        app.host.onLocalIntent(Intent.SetBgColor(bg))

        app.clearCanvas()
        assertEquals(0, app.host.state.elements.size, "清空后应无元素")
        assertEquals(bg, app.host.state.bgColor, "清空应保留背景色（reducer Reset 会重置为黑，宿主持有）")
        assertTrue(app.canUndo, "清空应可撤销")

        app.undo()
        assertEquals(1, app.host.state.elements.size, "撤销清空应恢复元素")
    }

    @Test
    fun textStyleCommitUpdatesElementAndBroadcasts() {
        val app = AppState()
        app.host.onLocalIntent(
            Intent.InsertText("旧文字", Offset.Zero, 20f, "sans", TextAlignment.LEFT, Color.Black),
        )
        val id = app.editingTextId
        assertNotNull(id)

        app.commitTextEdit("新文字", 32f, TextAlignment.CENTER, "serif")
        val el = app.host.state.elements.single() as Element.Text
        assertEquals("新文字", el.text)
        assertEquals(32f, el.fontSize)
        assertEquals(TextAlignment.CENTER, el.alignment)
        assertEquals("serif", el.fontFamilyKey)
        assertTrue(app.drawSync.pendingOutCount >= 2, "内容 + 样式应产生至少 2 条同步 op")
    }

    @Test
    fun undoRedoIntentsAreIgnoredByHost() {
        // 防御：Intent.Undo/Redo 不得落入 reducer 内部快照栈（会与引擎跨端撤销搅在一起）
        val app = AppState()
        drawPath(app)
        app.host.onLocalIntent(Intent.Undo)
        assertEquals(1, app.host.state.elements.size, "Intent.Undo 应被宿主忽略（走引擎撤销）")
        app.host.onLocalIntent(Intent.Redo)
        assertEquals(1, app.host.state.elements.size)
    }

    @Test
    fun duplicateWithNoSelectionIsNoOp() {
        val app = AppState()
        drawPath(app)
        assertEquals(0, app.duplicateSelected(), "无选中时复制应为空操作")
        assertEquals(1, app.host.state.elements.size)
    }
}

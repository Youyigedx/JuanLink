package com.juanlink.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.juanlink.composeui.draw.DrawBoxHost
import com.juanlink.core.draw.DrawOp
import com.juanlink.core.draw.DrawOpCodec
import com.juanlink.core.draw.OpSyncEngine
import com.juanlink.core.draw.drawOpTypeCode
import com.juanlink.core.protocol.OpEnvelope
import com.juanlink.core.protocol.OpTransport
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.TextAlignment
import io.ak1.drawbox.domain.usecase.UseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 跨端收敛集成测试：两个真实 DrawBoxHost + 共享内存双向传输。
 * 验证本地 onLocalIntent（画线/删除/撤销/文本）经引擎全序同步后，对端 state 收敛一致。
 * 这是 DrawBoxHost（真实 SyncDocument）与泛型引擎的端到端接线验证。
 */
class DrawBoxConsistencyTest {

    /** 双向内存传输：一端 sendOp 直投对端 onRemoteOp（含 ack/补同步） */
    private class RelayTransport : OpTransport {
        lateinit var peer: OpSyncEngine<DrawOp>
        override fun sendOp(envelope: OpEnvelope): Boolean {
            peer.onRemoteOp(envelope); return true
        }
        override fun sendAck(peerId: String, lastAppliedSeq: Long, requestResync: Boolean): Boolean {
            peer.onAck(lastAppliedSeq); return true
        }
        override fun sendSyncRequest(sinceSeq: Long): Boolean {
            peer.onSyncRequest(sinceSeq); return true
        }
        override fun sendResync(ops: List<ByteArray>, lastSeq: Long): Boolean {
            peer.onResync(ops); return true
        }
        override fun sendResyncDone(lastSeq: Long): Boolean = true
    }

    private class PeerPair {
        val hostA = DrawBoxHost()
        val hostB = DrawBoxHost()
        private val transportA = RelayTransport()
        private val transportB = RelayTransport()
        lateinit var engineA: OpSyncEngine<DrawOp>
        lateinit var engineB: OpSyncEngine<DrawOp>

        init {
            engineA = OpSyncEngine(
                doc = hostA,
                encodeOp = { DrawOpCodec.encode(it) },
                decodeOp = { DrawOpCodec.decode(it) },
                opTypeOf = { drawOpTypeCode(it) },
                peerId = "peer-A",
                transport = transportA,
            )
            engineB = OpSyncEngine(
                doc = hostB,
                encodeOp = { DrawOpCodec.encode(it) },
                decodeOp = { DrawOpCodec.decode(it) },
                opTypeOf = { drawOpTypeCode(it) },
                peerId = "peer-B",
                transport = transportB,
            )
            transportA.peer = engineB
            transportB.peer = engineA
            hostA.engine = engineA
            hostB.engine = engineB
        }
    }

    /** A 端画一条线（Insert + Update 同元素合并为一条 undo） */
    private fun PeerPair.stroke() {
        hostA.onLocalIntent(Intent.InsertNewPath(Offset(0f, 0f)))
        hostA.onLocalIntent(Intent.UpdateLatestPath(Offset(100f, 100f)))
    }

    @Test
    fun localStrokeReplicatesToPeer() {
        val p = PeerPair()
        p.stroke()
        assertEquals(1, p.hostA.state.elements.size)
        assertEquals(1, p.hostB.state.elements.size, "画线应复制到对端")
        assertEquals(
            p.hostA.state.elements.first().id,
            p.hostB.state.elements.first().id,
            "两端元素 id 应一致（同源复制）",
        )
    }

    @Test
    fun deleteReplicatesToPeer() {
        val p = PeerPair()
        p.stroke()
        val id = p.hostA.state.elements.first().id
        p.hostA.onLocalIntent(Intent.DeleteElement(id))
        assertEquals(0, p.hostA.state.elements.size)
        assertEquals(0, p.hostB.state.elements.size, "删除应复制到对端")
    }

    /** 关键：撤销恢复的逆 op 带旧 modifiedAt，LWW 冲突消解不能拒绝它 */
    @Test
    fun undoRevertsOnPeer() {
        val p = PeerPair()
        p.stroke()
        assertEquals(1, p.hostB.state.elements.size)
        val finalPath = p.hostA.state.elements.first() as Element.Path
        assertTrue(finalPath.samples.size >= 2, "画线应产生多段路径")

        p.engineA.undo()
        assertEquals(0, p.hostA.state.elements.size, "本地撤销应删除整条手势（回到手势前）")
        assertEquals(0, p.hostB.state.elements.size, "对端撤销必须与本端一致（逆 op 不能被 LWW 拒绝）")
    }

    @Test
    fun textEditReplicatesToPeer() {
        val p = PeerPair()
        // A 端插入文本 + 提交内容（同一元素两次 upsert）
        p.hostA.onLocalIntent(
            Intent.InsertText("", Offset.Zero, 20f, "sans", TextAlignment.LEFT, Color.Black),
        )
        val id = p.hostA.state.elements.first().id
        p.hostA.onLocalIntent(Intent.UpdateText(id, "你好"))
        val peerText = p.hostB.state.elements.first() as Element.Text
        assertEquals("你好", peerText.text, "文本编辑应复制到对端")
        assertEquals(id, peerText.id)
    }

    // ================================================================ 橡皮（笔画级裁剪）

    private fun pathOf(pts: List<Offset>): Element.Path = Element.Path(
        samples = pts.map { Element.PathSample(position = it, width = 5f) },
        strokeColor = Color.Black,
        strokeWidth = 5f,
        alpha = 1f,
    )

    private fun PeerPair.addLine() {
        hostA.onLocalIntent(
            Intent.AddElement(
                pathOf(
                    listOf(
                        Offset(0f, 0f), Offset(50f, 0f), Offset(100f, 0f),
                        Offset(150f, 0f), Offset(200f, 0f),
                    ),
                ),
            ),
        )
    }

    /** 中间擦除 → 剩余样本拆分为两段（笔画级裁剪，非整元素删除），跨端收敛 */
    @Test
    fun eraserSplitsPathNotDeletesIt() {
        val p = PeerPair()
        p.addLine()
        assertEquals(1, p.hostA.state.elements.size)

        p.hostA.onLocalIntent(Intent.BeginErase)
        p.hostA.onLocalIntent(Intent.EraseAt(Offset(100f, 0f), 15f))
        p.hostA.onLocalIntent(Intent.EndErase)

        val a = p.hostA.state.elements
        assertEquals(2, a.size, "擦中间应拆成两段，而非整线删除")
        assertEquals(
            4, a.sumOf { (it as Element.Path).samples.size },
            "被擦 1 个 sample 移除，剩余 4 个保留",
        )
        val b = p.hostB.state.elements
        assertEquals(2, b.size, "跨端同样收敛为两段")
        assertEquals(
            a.map { it.id }.toSet(), b.map { it.id }.toSet(),
            "两端段 id 一致",
        )
        assertEquals(
            a.map { (it as Element.Path).samples.size }.sorted(),
            b.map { (it as Element.Path).samples.size }.sorted(),
            "两端分段样本数一致",
        )
    }

    /** 整条路径完全擦除 → 元素删除并同步 */
    @Test
    fun eraserFullyRemovesPathOnPeer() {
        val p = PeerPair()
        p.addLine()
        p.hostA.onLocalIntent(Intent.BeginErase)
        p.hostA.onLocalIntent(Intent.EraseAt(Offset(100f, 0f), 300f))
        p.hostA.onLocalIntent(Intent.EndErase)
        assertEquals(0, p.hostA.state.elements.size)
        assertEquals(0, p.hostB.state.elements.size)
    }

    /** 一次撤销原子回退整次擦除手势（批量 undo entry），跨端同步恢复 */
    @Test
    fun undoAfterEraseRestoresPathOnPeerInOneStep() {
        val p = PeerPair()
        p.addLine()
        p.hostA.onLocalIntent(Intent.BeginErase)
        p.hostA.onLocalIntent(Intent.EraseAt(Offset(100f, 0f), 15f))
        p.hostA.onLocalIntent(Intent.EndErase)
        assertEquals(2, p.hostA.state.elements.size)

        assertTrue(p.engineA.undo(), "擦除手势应产生一条可撤销项")
        val a = p.hostA.state.elements
        assertEquals(1, a.size, "一次 undo 应恢复为一条完整路径")
        assertEquals(5, (a.single() as Element.Path).samples.size, "恢复完整 5 样本")
        assertEquals(1, p.hostB.state.elements.size, "对端 undo 同步恢复")
        assertEquals(5, (p.hostB.state.elements.single() as Element.Path).samples.size)
    }

    /** 重做：批量 forward 顺序应用，恢复擦除分割的两段 */
    @Test
    fun redoAfterEraseRestoresSplitOnPeer() {
        val p = PeerPair()
        p.addLine()
        p.hostA.onLocalIntent(Intent.BeginErase)
        p.hostA.onLocalIntent(Intent.EraseAt(Offset(100f, 0f), 15f))
        p.hostA.onLocalIntent(Intent.EndErase)
        p.engineA.undo()
        assertEquals(1, p.hostA.state.elements.size)
        p.engineA.redo()
        assertEquals(2, p.hostA.state.elements.size, "重做应恢复擦除分割的两段")
        assertEquals(2, p.hostB.state.elements.size, "对端重做同步恢复")
    }

    /** 未命中擦除盘 → 返回原列表引用（Reducer 引用比较短路依赖此约定） */
    @Test
    fun eraserMissReturnsSameListReference() {
        val base = listOf(pathOf(listOf(Offset(0f, 0f), Offset(100f, 0f))))
        val result = UseCase().eraseAt(base, Offset(500f, 500f), 20f)
        assertSame(base, result, "未命中必须返回原列表实例")
    }
}

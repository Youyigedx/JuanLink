package com.juanlink.core.canvas

import com.juanlink.core.model.RectF
import com.juanlink.core.model.Stroke
import com.juanlink.core.model.StrokePoint
import com.juanlink.core.model.StrokeStyle
import com.juanlink.core.protocol.EnvelopeCodec
import com.juanlink.core.protocol.OpEnvelope
import com.juanlink.core.protocol.OpId
import com.juanlink.core.protocol.OpTransport
import com.juanlink.core.protocol.OpType
import com.juanlink.core.protocol.Resync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpSyncEngineTest {

    private class FakeTransport : OpTransport {
        val sent = mutableListOf<OpEnvelope>()
        val acks = mutableListOf<Pair<String, Long>>()
        val resyncs = mutableListOf<Resync>()
        var lastSyncRequestSince = -1L

        override fun sendOp(envelope: OpEnvelope): Boolean {
            sent.add(envelope)
            return true
        }

        override fun sendAck(peerId: String, lastAppliedSeq: Long, requestResync: Boolean): Boolean {
            acks.add(peerId to lastAppliedSeq)
            return true
        }

        override fun sendSyncRequest(sinceSeq: Long): Boolean {
            lastSyncRequestSince = sinceSeq
            return true
        }

        override fun sendResync(ops: List<ByteArray>, lastSeq: Long): Boolean {
            resyncs.add(Resync(0, ops, lastSeq))
            return true
        }

        override fun sendResyncDone(lastSeq: Long): Boolean = true
    }

    private fun stroke(id: String): Stroke = Stroke(
        id = id,
        layerId = "layer-1",
        points = listOf(StrokePoint(0f, 0f), StrokePoint(10f, 10f)),
        style = StrokeStyle(),
        bounds = RectF(0f, 0f, 10f, 10f),
    )

    private fun remoteEnvelope(peer: String, lamport: Long, seq: Long): OpEnvelope = OpEnvelope(
        opId = OpId(lamport, peer),
        peerId = peer,
        opType = OpType.StrokeAdd.code,
        payload = OpCodec.encode(StrokeAdd(stroke("s-$peer-$lamport"))),
        seq = seq,
        timestamp = 0,
    )

    @Test
    fun localOpAssignedOpIdAndSent() {
        val t = FakeTransport()
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", t)
        val opId = engine.applyLocal(StrokeAdd(stroke("s1")))
        assertNotNull(opId)
        assertEquals("peer-A", opId!!.peerId)
        assertEquals(1, t.sent.size)
        assertEquals(OpType.StrokeAdd.code, t.sent[0].opType)
        assertEquals(1, doc.allStrokes().size)
    }

    @Test
    fun remoteOpsApplyInLamportOrder() {
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", FakeTransport())
        engine.onRemoteOp(remoteEnvelope("peer-B", 1, 1))
        engine.onRemoteOp(remoteEnvelope("peer-B", 2, 2))
        assertEquals(2, doc.allStrokes().size)
    }

    @Test
    fun outOfOrderRemoteOpBufferedUntilGapFilled() {
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", FakeTransport())
        // 先到 lamport=2，存在间隙（缺 lamport=1），应缓冲不应用
        engine.onRemoteOp(remoteEnvelope("peer-B", 2, 2))
        assertEquals(0, doc.allStrokes().size)
        // 补齐 lamport=1 → 连续前缀全部应用
        engine.onRemoteOp(remoteEnvelope("peer-B", 1, 1))
        assertEquals(2, doc.allStrokes().size)
    }

    @Test
    fun duplicateRemoteOpIgnored() {
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", FakeTransport())
        val e = remoteEnvelope("peer-B", 1, 1)
        engine.onRemoteOp(e)
        engine.onRemoteOp(e)
        assertEquals(1, doc.allStrokes().size)
    }

    @Test
    fun ackClearsOutgoingWindow() {
        val t = FakeTransport()
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", t)
        engine.applyLocal(StrokeAdd(stroke("s1")))
        engine.applyLocal(StrokeAdd(stroke("s2")))
        assertEquals(2, engine.pendingOutCount)
        engine.onAck(1)
        assertEquals(1, engine.pendingOutCount)
        engine.onAck(2)
        assertEquals(0, engine.pendingOutCount)
    }

    @Test
    fun undoBroadcastsInverseAndClearsDocument() {
        val t = FakeTransport()
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", t)
        engine.applyLocal(StrokeAdd(stroke("s1")))
        assertEquals(1, doc.allStrokes().size)
        assertTrue(engine.undo())
        assertEquals(0, doc.allStrokes().size)
        assertEquals(OpType.StrokeRemove.code, t.sent.last().opType)
        assertFalse(engine.canUndo()) // 逆操作不应再次记录
    }

    @Test
    fun redoRestoresStroke() {
        val t = FakeTransport()
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", t)
        engine.applyLocal(StrokeAdd(stroke("s1")))
        engine.undo()
        assertEquals(0, doc.allStrokes().size)
        assertTrue(engine.redo())
        assertEquals(1, doc.allStrokes().size)
        assertEquals(OpType.StrokeAdd.code, t.sent.last().opType)
    }

    @Test
    fun resyncReturnsOnlyOwnOpsAfterSinceSeq() {
        val t = FakeTransport()
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", t)
        engine.applyLocal(StrokeAdd(stroke("s1"))) // my seq 1
        engine.applyLocal(StrokeAdd(stroke("s2"))) // my seq 2
        engine.onRemoteOp(remoteEnvelope("peer-B", 5, 1)) // B's op
        engine.onSyncRequest(sinceSeq = 1)
        assertEquals(1, t.resyncs.size)
        val resync = t.resyncs[0]
        assertEquals(1, resync.ops.size)
        val env = EnvelopeCodec.decode(resync.ops[0])
        assertNotNull(env)
        assertEquals("peer-A", env!!.peerId)
        assertEquals(2, env.seq)
    }

    @Test
    fun resyncReplayReappliesMissingOps() {
        // 模拟断线补同步：远端把 1..2 重放过来，本地只缺 2
        val t = FakeTransport()
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", t)
        engine.onRemoteOp(remoteEnvelope("peer-B", 1, 1))
        // 远端重放 1,2
        engine.onResync(listOf(
            EnvelopeCodec.encode(remoteEnvelope("peer-B", 1, 1)),
            EnvelopeCodec.encode(remoteEnvelope("peer-B", 2, 2)),
        ))
        assertEquals(2, doc.allStrokes().size)
    }

    @Test
    fun lamportClockMovesBeyondRemote() {
        val t = FakeTransport()
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", t)
        engine.onRemoteOp(remoteEnvelope("peer-B", 100, 1))
        val opId = engine.applyLocal(StrokeAdd(stroke("local-after")))
        assertNotNull(opId)
        assertTrue(opId!!.lamport > 100, "本地时钟必须越过远端时钟")
    }

    /**
     * 并发修复验证：UI 线程（applyLocal 画线）与网络线程（onAck 清出站窗口）
     * 同时操作共享状态，不得抛 ConcurrentModificationException/状态撕裂。
     * 复现真机「划一笔即断」根因：连接稳定后 ACK 频繁返回，与 UI 画线并发。
     */
    @Test
    fun concurrentApplyLocalAndOnAckNoCrash() {
        val t = FakeTransport()
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", t)

        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        var uiOps = 0
        var ackOps = 0

        // 网络线程：持续模拟对端 ACK 已应用 seq 1..N
        val netThread = Thread {
            var seq = 0L
            while (!stop.get()) {
                seq++
                try {
                    engine.onAck(seq)
                    ackOps++
                } catch (e: Throwable) {
                    errors.add(e); stop.set(true)
                }
            }
        }.apply { start() }

        // UI 线程：持续画新笔画（applyLocal 写 outgoing）
        try {
            var i = 0
            while (!stop.get() && i < 50_000) {
                i++
                try {
                    engine.applyLocal(StrokeAdd(stroke("s-$i")))
                    uiOps++
                } catch (e: Throwable) {
                    errors.add(e); stop.set(true)
                }
            }
        } finally {
            stop.set(true)
            netThread.join(3000)
        }

        assertTrue(errors.isEmpty(), "并发访问不得抛异常：${errors.firstOrNull()?.stackTraceToString()}")
        assertTrue(uiOps > 0, "UI 线程应完成画线")
        assertTrue(ackOps > 0, "网络线程应完成 ACK")
        // 出站窗口应被 ACK 清理干净（onAck 滑动到最大 seq）
        assertEquals(0, engine.pendingOutCount, "ACK 应清空出站窗口")
    }
}

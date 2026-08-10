package com.juanlink.core.canvas

import com.juanlink.core.crypto.CryptoEngine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageSyncTest {

    /** 假传输：接收发操作，供引擎测试 */
    private class FakeTransport : com.juanlink.core.protocol.OpTransport {
        val sent = mutableListOf<com.juanlink.core.protocol.OpEnvelope>()
        override fun sendOp(envelope: com.juanlink.core.protocol.OpEnvelope): Boolean {
            sent.add(envelope)
            return true
        }
        override fun sendAck(peerId: String, lastAppliedSeq: Long, requestResync: Boolean): Boolean = true
        override fun sendSyncRequest(sinceSeq: Long): Boolean = true
        override fun sendResync(ops: List<ByteArray>, lastSeq: Long): Boolean = true
        override fun sendResyncDone(lastSeq: Long): Boolean = true
    }

    private fun decodeEnvelope(e: com.juanlink.core.protocol.OpEnvelope): CanvasOp =
        OpCodec.decode(e.payload)!!

    /** 生成递增 opId/seq 的信封（模拟远端有序发送） */
    private class Sender {
        private var lamport = 0L
        private var seq = 0L

        fun envelope(op: CanvasOp): com.juanlink.core.protocol.OpEnvelope =
            com.juanlink.core.protocol.OpEnvelope(
                opId = com.juanlink.core.protocol.OpId(++lamport, "peer-B"),
                peerId = "peer-B",
                opType = 0,
                payload = OpCodec.encode(op),
                seq = ++seq,
                timestamp = 0,
            )
    }

    @Test
    fun imageChunksReassembleAndVerify() {
        val doc = CanvasDocument("doc")
        var readyBytes: ByteArray? = null
        var checksumOk = false
        doc.onImageReady = { _, bytes, ok -> readyBytes = bytes; checksumOk = ok }

        // 构造 100KB 图片（多块）
        val bytes = CryptoEngine.randomBytes(100 * 1024)
        val total = (bytes.size + ImageTransfer.CHUNK_SIZE - 1) / ImageTransfer.CHUNK_SIZE
        assertTrue(total > 6, "应分为多块")

        val t = FakeTransport()
        val engine = OpSyncEngine(doc, "peer-A", t)
        val sender = Sender()

        // 模拟接收端：逐块应用
        engine.onRemoteOp(sender.envelope(ImageAddOp("img-1", "layer-1", "image://img-1", com.juanlink.core.model.Affine2.IDENTITY, 100f, 80f)))
        for (i in 0 until total) {
            val from = i * ImageTransfer.CHUNK_SIZE
            val to = minOf(from + ImageTransfer.CHUNK_SIZE, bytes.size)
            engine.onRemoteOp(sender.envelope(ImageChunkOp("img-1", i, total, bytes.copyOfRange(from, to))))
        }
        engine.onRemoteOp(sender.envelope(ImageReadyOp("img-1", CryptoEngine.sha256(bytes))))

        assertNotNull(readyBytes, "图片数据应就绪")
        assertTrue(checksumOk, "校验应通过")
        assertContentEquals(bytes, readyBytes!!)
    }

    @Test
    fun missingChunkTriggersResendRequest() {
        val doc = CanvasDocument("doc")
        var missingList: List<Int>? = null
        doc.onImageResendNeeded = { _, missing -> missingList = missing }

        val bytes = CryptoEngine.randomBytes(50 * 1024)
        val total = (bytes.size + ImageTransfer.CHUNK_SIZE - 1) / ImageTransfer.CHUNK_SIZE
        val engine = OpSyncEngine(doc, "peer-A", FakeTransport())
        val sender = Sender()

        engine.onRemoteOp(sender.envelope(ImageAddOp("img-2", "layer-1", "image://img-2", com.juanlink.core.model.Affine2.IDENTITY, 50f, 50f)))
        // 故意跳过第 1 块
        for (i in 0 until total) {
            if (i == 1) continue
            val from = i * ImageTransfer.CHUNK_SIZE
            val to = minOf(from + ImageTransfer.CHUNK_SIZE, bytes.size)
            engine.onRemoteOp(sender.envelope(ImageChunkOp("img-2", i, total, bytes.copyOfRange(from, to))))
        }
        engine.onRemoteOp(sender.envelope(ImageReadyOp("img-2", CryptoEngine.sha256(bytes))))

        assertNotNull(missingList, "缺块应触发重传请求")
        assertEquals(listOf(1), missingList)
        assertNull(doc.imageBytes("img-2"), "校验未齐不应就绪")
    }

    @Test
    fun imageTransformAndRemove() {
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", FakeTransport())
        val sender = Sender()
        engine.onRemoteOp(sender.envelope(ImageAddOp("img-3", "layer-1", "image://img-3", com.juanlink.core.model.Affine2.IDENTITY, 100f, 100f)))
        assertNotNull(doc.imageById("img-3"))

        val moved = com.juanlink.core.model.Affine2(e = 200.0, f = 150.0)
        engine.onRemoteOp(sender.envelope(ImageTransformOp("img-3", moved)))
        assertEquals(moved, doc.imageById("img-3")!!.transform)

        engine.onRemoteOp(sender.envelope(ImageRemoveOp("img-3")))
        assertNull(doc.imageById("img-3"))
    }

    @Test
    fun imageTransferSendsMetadataThenChunksThenReady() {
        val t = FakeTransport()
        val doc = CanvasDocument("doc")
        val engine = OpSyncEngine(doc, "peer-A", t)
        val bytes = CryptoEngine.randomBytes(40 * 1024)

        ImageTransfer.sendImage(engine, "img-4", "layer-1", bytes, 80f, 60f)

        val ops = t.sent.map { decodeEnvelope(it) }
        assertEquals(ImageAddOp::class, ops.first()::class)
        assertEquals(ImageReadyOp::class, ops.last()::class)
        val chunkCount = ops.count { it is ImageChunkOp }
        val total = (bytes.size + ImageTransfer.CHUNK_SIZE - 1) / ImageTransfer.CHUNK_SIZE
        assertEquals(total, chunkCount)
    }

}

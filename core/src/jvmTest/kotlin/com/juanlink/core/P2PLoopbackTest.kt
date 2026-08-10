package com.juanlink.core

import com.juanlink.core.canvas.CanvasDocument
import com.juanlink.core.canvas.OpSyncEngine
import com.juanlink.core.canvas.StrokeAdd
import com.juanlink.core.model.RectF
import com.juanlink.core.model.Stroke
import com.juanlink.core.model.StrokePoint
import com.juanlink.core.model.StrokeStyle
import com.juanlink.core.pairing.PairingManager
import com.juanlink.core.protocol.QualityProbe
import com.juanlink.core.session.SessionManager
import com.juanlink.core.transport.TcpTransport
import com.juanlink.core.util.nowEpochMillis
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 双实例回环 P2P 集成测试：
 * 同进程 PeerA/PeerB 经 127.0.0.1 TCP 完成完整握手 → 双向加密帧 → 质量探测。
 */
class P2PLoopbackTest {

    private data class Peer(val manager: SessionManager, val received: CopyOnWriteArrayList<ByteArray>)

    private fun runBlockingPeerSetup(block: suspend (Pair<Peer, Peer>) -> Unit) {
        runBlocking {
            val pairingManager = PairingManager()
            val transportA = TcpTransport(bindHost = "127.0.0.1", bindPort = 0)
            val managerA = SessionManager("dev-A", transportA)
            val receivedA = CopyOnWriteArrayList<ByteArray>()
            managerA.onEncryptedPlain = { receivedA.add(it) }
            val pairingA = managerA.startInitiator(pairingManager)
            assertTrue(pairingA.candidates.isNotEmpty(), "发起方应暴露候选地址")

            val transportB = TcpTransport()
            val managerB = SessionManager("dev-B", transportB)
            val receivedB = CopyOnWriteArrayList<ByteArray>()
            managerB.onEncryptedPlain = { receivedB.add(it) }
            assertTrue(managerB.connectAsResponder(pairingA), "响应方应连接成功")

            block(Peer(managerA, receivedA) to Peer(managerB, receivedB))
        }
    }

    @Test
    fun handshakeAndBidirectionalEncryption() {
        runBlockingPeerSetup { (a, b) ->
            awaitUntil(5000) { a.manager.isConnected && b.manager.isConnected }
            assertTrue(a.manager.isConnected, "A 应完成握手")
            assertTrue(b.manager.isConnected, "B 应完成握手")

            // A → B
            val payloadAB = "婵娟-来自A的加密数据".encodeToByteArray()
            assertTrue(a.manager.sendEncrypted(payloadAB))
            awaitUntil(5000) { b.received.size == 1 }
            assertContentEquals(payloadAB, b.received[0])

            // B → A
            val payloadBA = "reply-from-B-0001".encodeToByteArray()
            assertTrue(b.manager.sendEncrypted(payloadBA))
            awaitUntil(5000) { a.received.size == 1 }
            assertContentEquals(payloadBA, a.received[0])

            // 连续多帧（A→B 共 1 + 50 = 51 帧）
            for (i in 0 until 50) {
                a.manager.sendEncrypted("frame-$i".encodeToByteArray())
            }
            awaitUntil(5000) { b.received.size == 51 }
            assertContentEquals("frame-49".encodeToByteArray(), b.received[50])
        }
    }

    @Test
    fun qualityProbeReachesPeer() {
        runBlockingPeerSetup { (a, b) ->
            awaitUntil(5000) { a.manager.isConnected && b.manager.isConnected }
            val probesSeen = AtomicInteger(0)
            b.manager.onQualityProbe = { _, _ -> probesSeen.incrementAndGet() }

            repeat(3) { i ->
                assertTrue(a.manager.sendControl(QualityProbe(i.toLong() + 1, i, nowEpochMillis(), 32)))
            }
            awaitUntil(5000) { probesSeen.get() == 3 }
            assertTrue(probesSeen.get() == 3, "B 应收到全部探测包")
        }
    }

    @Test
    fun maliciousHandshakeResponseRejected() {
        // 响应方 B 用错误的 nonce 派生密钥，A 应仍能正常握手；
        // 模拟对端发来篡改 MAC 的握手响应：由 B 直接验证伪造响应被拒绝
        runBlocking {
            val pairingManager = PairingManager()
            val transportA = TcpTransport(bindHost = "127.0.0.1", bindPort = 0)
            val managerA = SessionManager("dev-A", transportA)
            managerA.startInitiator(pairingManager)
            // 这里验证握手协议的 verify 层：构造错误 MAC 的响应应返回 null
            val kpA = com.juanlink.core.crypto.CryptoEngine.generateX25519KeyPair()
            val kpB = com.juanlink.core.crypto.CryptoEngine.generateX25519KeyPair()
            val hello = com.juanlink.core.session.HandshakeProtocol.buildHello(1, AppInfo.PROTOCOL_VERSION, "dev-B", "sess", kpB, "responder")
            // 用错误 nonce 构造响应
            val wrongNonce = byteArrayOf(1, 2, 3, 4)
            val (response, _) = com.juanlink.core.session.HandshakeProtocol.buildResponse(hello, kpA, "sess", wrongNonce, 2)
            val verified = com.juanlink.core.session.HandshakeProtocol.verifyResponse(hello, response, kpB, "sess", byteArrayOf(9, 9, 9))
            assertTrue(verified == null, "不同 nonce 派生的 MAC 必须校验失败")
        }
    }

    @Test
    fun strokesSyncAcrossPeersOverEncryptedChannel() {
        runBlocking {
            val transportA = TcpTransport(bindHost = "127.0.0.1", bindPort = 0)
            val managerA = SessionManager("dev-A", transportA)
            val docA = CanvasDocument("doc")
            val engineA = OpSyncEngine(docA, "dev-A", managerA)
            managerA.opEngine = engineA
            val pairingA = managerA.startInitiator(PairingManager())

            val transportB = TcpTransport()
            val managerB = SessionManager("dev-B", transportB)
            val docB = CanvasDocument("doc")
            val engineB = OpSyncEngine(docB, "dev-B", managerB)
            managerB.opEngine = engineB
            assertTrue(managerB.connectAsResponder(pairingA))

            awaitUntil(5000) { managerA.isConnected && managerB.isConnected }
            assertTrue(managerA.isConnected && managerB.isConnected, "握手应完成")

            // A 画一笔 → B 端实时重现
            val strokeA = Stroke(
                id = "s-1", layerId = "layer-1",
                points = listOf(StrokePoint(0f, 0f), StrokePoint(5f, 5f)),
                style = StrokeStyle(),
                bounds = RectF(0f, 0f, 5f, 5f),
            )
            engineA.applyLocal(StrokeAdd(strokeA))
            awaitUntil(5000) { docB.allStrokes().size == 1 }
            assertEquals(1, docB.allStrokes().size, "B 应收到 A 的笔迹")
            assertEquals("s-1", docB.allStrokes()[0].id)
            assertEquals(2, docB.allStrokes()[0].points.size)

            // B 画一笔 → A 端实时重现
            val strokeB = Stroke(
                id = "s-2", layerId = "layer-1",
                points = listOf(StrokePoint(10f, 10f)),
                style = StrokeStyle(),
                bounds = RectF(10f, 10f, 10f, 10f),
            )
            engineB.applyLocal(StrokeAdd(strokeB))
            awaitUntil(5000) { docA.allStrokes().size == 2 }
            assertEquals(2, docA.allStrokes().size, "A 应收到 B 的笔迹")

            // 撤销在双端一致
            engineA.undo()
            awaitUntil(5000) { docB.allStrokes().size == 1 }
            assertEquals(1, docB.allStrokes().size, "撤销应同步到 B")
            assertEquals(1, docA.allStrokes().size)
        }
    }

    private suspend fun awaitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = nowEpochMillis() + timeoutMs
        while (!condition()) {
            if (nowEpochMillis() > deadline) throw AssertionError("等待条件超时 (${timeoutMs}ms)")
            delay(20)
        }
    }
}

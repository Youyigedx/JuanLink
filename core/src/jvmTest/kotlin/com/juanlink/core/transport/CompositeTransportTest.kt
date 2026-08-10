package com.juanlink.core.transport

import com.juanlink.core.protocol.Heartbeat
import com.juanlink.core.util.nowEpochMillis
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 组合传输分派测试：
 * - 仅 TCP 候选 → 走 tcp（回归）
 * - TCP 不可达 → 恰好一次 onDisconnected（收口，防重复上报）
 * - TCP 不可达 + relay 兜底 → 自动走 TURN 中继
 * - Initiator 侧 CompositeTransport 经中继回发数据（回归：start 必须记录 active）
 * - 断开后同一实例重连（回归 #15：活动传输断开必须清空 active，CAS 才可重新设置）
 * - 全部不可达（含 relay）→ 恰好一次 onDisconnected（全阶段收口）
 */
class CompositeTransportTest {

    private class TListener : TransportListener {
        val connections = AtomicInteger(0)
        val disconnects = CopyOnWriteArrayList<DisconnectReason>()
        val frames = CopyOnWriteArrayList<com.juanlink.core.protocol.WireMessage>()
        override fun onConnected() { connections.incrementAndGet() }
        override fun onDisconnected(reason: DisconnectReason) { disconnects.add(reason) }
        override fun onFrame(message: com.juanlink.core.protocol.WireMessage) { frames.add(message) }
    }

    private fun peer(host: String, port: Int, type: String) =
        PeerInfo("dev-A", "sess", host, port, ByteArray(32), type = type)

    @Test
    fun tcpOnlyCandidatesReachable() = runBlocking {
        val tA = CompositeTransport()
        val lA = TListener()
        tA.start(lA)
        val ep = tA.localEndpoint()!!
        val tB = CompositeTransport()
        val lB = TListener()
        assertTrue(tB.connectAny(listOf(peer(ep.first, ep.second, "tcp")), lB), "仅 TCP 候选应连接成功")
        // A 是 Initiator（active 语义只适用于客户端），用 onConnected 事件计数判定
        awaitUntil(4000) { lA.connections.get() >= 1 && tB.isConnected }
        assertTrue(lA.connections.get() >= 1 && tB.isConnected)
        tA.disconnect()
        tB.disconnect()
    }

    @Test
    fun tcpUnreachableReportsDisconnectedExactlyOnce() = runBlocking {
        val tB = CompositeTransport()
        val lB = TListener()
        val peers = listOf(
            peer("127.0.0.1", 1, "tcp"), // 无监听，立即拒绝
        )
        val ok = tB.connectAny(peers, lB)
        assertFalse(ok, "全部候选不可达应失败")
        assertEquals(1, lB.disconnects.size, "全部失败应恰好上报一次 onDisconnected")
        assertEquals(DisconnectReason.IoError, lB.disconnects[0])
        tB.disconnect()
    }

    @Test
    fun tcpUnreachableButRelaySucceeds() = runBlocking {
        val server = TestTurnServer().start()
        try {
            // Initiator 侧：纯 TurnTransport 分配 + 监听（模拟 AppState.createRoom 产出 relay 候选）
            val initiatorRelay = TurnTransport(servers = listOf(TurnServer("127.0.0.1", server.port, "test", "test")))
            val lA = TListener()
            val ep = initiatorRelay.allocateRelayEndpoint()!!
            initiatorRelay.start(lA)

            // Responder 侧：CompositeTransport，候选 = 不可达 tcp + relay（二维码带 relay 候选 + turn）
            val tB = CompositeTransport(relay = TurnTransport(servers = listOf(TurnServer("127.0.0.1", server.port, "test", "test"))))
            val lB = TListener()
            val peers = listOf(
                peer("127.0.0.1", 1, "tcp"),
                PeerInfo(
                    "dev-B", "sess", ep.relayedHost, ep.relayedPort, ByteArray(32),
                    type = "relay", relayServer = "127.0.0.1:${server.port}",
                ),
            )
            assertTrue(tB.connectAny(peers, lB), "tcp 不可达时应自动走 TURN 中继兜底")
            awaitUntil(5000) { initiatorRelay.isConnected && tB.isConnected }
            assertTrue(initiatorRelay.isConnected && tB.isConnected, "经中继兜底应双向建立")
            assertEquals(0, lB.disconnects.size, "relay 兜底成功不应触发 onDisconnected")

            // 数据面：Responder → Initiator 一帧（经中继）
            assertTrue(tB.send(Heartbeat(5, nowEpochMillis())))
            awaitUntil(3000) { lA.frames.size >= 1 }
            assertIs<Heartbeat>(lA.frames.first())

            tB.disconnect()
            initiatorRelay.disconnect()
        } finally {
            server.close()
        }
    }

    @Test
    fun initiatorCompositeSendsOverRelay() = runBlocking {
        val server = TestTurnServer().start()
        try {
            // Initiator 侧：CompositeTransport（tcp + relay），start 监听两端。
            // 回归：start 必须经 CompositeRelayListener 记录 active——否则 Initiator 侧
            // active 恒为 null，send 全部静默失败（真机现象：桌面"已连接"但握手响应丢）。
            val initiator = CompositeTransport(
                relay = TurnTransport(servers = listOf(TurnServer("127.0.0.1", server.port, "test", "test"))),
            )
            val lA = TListener()
            val ep = initiator.allocateRelayEndpoint()!!
            initiator.start(lA)

            // Responder 侧：纯 TurnTransport，经 relay 连入
            val responder = TurnTransport(servers = listOf(TurnServer("127.0.0.1", server.port, "test", "test")))
            val lB = TListener()
            val peers = listOf(
                PeerInfo(
                    "dev-B", "sess", ep.relayedHost, ep.relayedPort, ByteArray(32),
                    type = "relay", relayServer = "127.0.0.1:${server.port}",
                ),
            )
            assertTrue(responder.connectAny(peers, lB), "Responder 应能经中继连入")
            awaitUntil(5000) { initiator.isConnected && responder.isConnected }
            assertTrue(initiator.isConnected && responder.isConnected, "经中继应双向建立")

            // 数据面：Initiator（CompositeTransport）→ Responder 一帧——回归核心：
            // Initiator 侧 send 必须路由到已连接的 relay（active 由 CompositeRelayListener 设置）
            assertTrue(initiator.send(Heartbeat(5, nowEpochMillis())), "Initiator 侧 send 应成功")
            awaitUntil(3000) { lB.frames.size >= 1 }
            assertIs<Heartbeat>(lB.frames.first())

            initiator.disconnect()
            responder.disconnect()
        } finally {
            server.close()
        }
    }

    @Test
    fun reconnectAfterDisconnectRestoresSession() = runBlocking {
        // 回归 #15：活动传输断开必须清空 active，否则重连时 onConnected 的 CAS(null→owner)
        // 永不成功 → 自我关闭 → 重连循环永远失败。
        // Initiator 保持监听（不主动断开）；Responder 断开后用同一实例重连。
        val tA = CompositeTransport()
        val lA = TListener()
        tA.start(lA)
        val ep = tA.localEndpoint()!!

        val tB = CompositeTransport()
        val lB = TListener()
        val peers = listOf(peer(ep.first, ep.second, "tcp"))
        assertTrue(tB.connectAny(peers, lB))
        awaitUntil(4000) { lA.connections.get() >= 1 && tB.isConnected }
        assertTrue(tB.isConnected)
        assertTrue(tB.send(Heartbeat(5, nowEpochMillis())))
        awaitUntil(3000) { lA.frames.size >= 1 }

        // Responder 断开（模拟网络中断）→ active 清空 + 通知上层
        tB.disconnect()
        assertFalse(tB.isConnected)
        awaitUntil(3000) { lB.disconnects.isNotEmpty() }

        // 同一 Responder 实例重连：TCP 直连（Initiator 仍在监听）
        assertTrue(tB.connectAny(peers, lB), "断开后应能重连")
        awaitUntil(4000) { tB.isConnected && lA.connections.get() >= 2 }
        assertTrue(tB.isConnected, "重连后数据面应恢复")
        assertTrue(tB.send(Heartbeat(6, nowEpochMillis())))
        awaitUntil(3000) { lA.frames.any { it is Heartbeat && it.messageId == 6L } }

        tA.disconnect()
        tB.disconnect()
    }

    @Test
    fun allUnreachableIncludingRelayReportsDisconnectedOnce() = runBlocking {
        val tB = CompositeTransport(relay = TurnTransport(servers = listOf(TurnServer("127.0.0.1", 1, "test", "test"))))
        val lB = TListener()
        val peers = listOf(
            peer("127.0.0.1", 1, "tcp"),
            PeerInfo(
                "dev-B", "sess", "127.0.0.1", 1, ByteArray(32),
                type = "relay", relayServer = "127.0.0.1:1",
            ),
        )
        val ok = tB.connectAny(peers, lB)
        assertFalse(ok, "全部候选（含 relay）不可达应失败")
        assertEquals(1, lB.disconnects.size, "全阶段（直连 + relay）失败应恰好上报一次 onDisconnected")
        assertEquals(DisconnectReason.IoError, lB.disconnects[0])
        tB.disconnect()
    }

    private suspend fun awaitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = nowEpochMillis() + timeoutMs
        while (!condition()) {
            if (nowEpochMillis() > deadline) throw AssertionError("等待条件超时 (${timeoutMs}ms)")
            delay(20)
        }
    }
}

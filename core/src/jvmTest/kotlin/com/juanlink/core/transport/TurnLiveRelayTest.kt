package com.juanlink.core.transport

import com.juanlink.core.protocol.Heartbeat
import com.juanlink.core.protocol.WireMessage
import com.juanlink.core.turn.TurnClient
import com.juanlink.core.util.nowEpochMillis
import java.io.File
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * TURN 中继端到端（live，连真实服务器）。
 * 环境变量 JUAN_TURN_LIVE=1 才真正连网；默认空跑（不 @Ignore，否则方法体不执行，门控失效）。
 *
 * 实测结论（本机对 Cloudflare 与自建 coturn 4.6.1 双证实）：
 * - 两家都要求「接收方对发送方 relayed 有 permission」才转发（严格于 RFC 5766「只查发送方」）：
 *   B 发首包给 A 时，A 必须先对 B 的 relayed IP 建 permission，否则数据被服务器静默丢弃。
 * - 学习式握手因此死锁（Initiator 收不到首包 → 学不到地址 → 建不了 permission）。
 *   ChannelData 同约束；0.0.0.0 通配 permission 被拒（Cloudflare 400 / coturn 403）。
 * - **破局**：Initiator allocate 后预建「对自己 relayed IP 的 permission」——permission 按 IP 匹配，
 *   同一台 TURN 服务器所有 client 的 relayed IP 相同（= 服务器 external-ip），
 *   故同服务器任意 peer 的首包可进，学习式握手成立。见 TurnTransport.start() 预授权逻辑。
 * - 对照：双方互建 permission 后中继双向数据面无损通过（见 TurnLiveSymPermissionTest）。
 */
class TurnLiveRelayTest {

    private class TListener : TransportListener {
        val connections = CopyOnWriteArrayList<String>()
        val frames = CopyOnWriteArrayList<WireMessage>()
        val disconnects = CopyOnWriteArrayList<DisconnectReason>()
        override fun onConnected() { connections.add("conn") }
        override fun onDisconnected(reason: DisconnectReason) { disconnects.add(reason) }
        override fun onFrame(message: WireMessage) { frames.add(message) }
    }

    private fun configuredServers(): List<TurnServer> {
        val home = System.getProperty("user.home")
        val file = File(home, ".juanlink/turn.json")
        println("[LIVE-RELAY] user.home=$home exists=${file.exists()}")
        if (!file.exists()) return emptyList()
        return runCatching { TurnConfigJson.decodeList(file.readText()) }.getOrDefault(emptyList())
    }

    @Test
    fun socketOptionComparisonAllocate() = runBlocking {
        if (System.getenv("JUAN_TURN_LIVE") != "1") return@runBlocking
        val srv = configuredServers().firstOrNull() ?: return@runBlocking

        // 样式 A：TurnTransport.ensureBound（reuseAddress=true）
        val sockA = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress("0.0.0.0", 0))
        }
        val epA = TurnClient(sockA, srv).allocate(3000)
        println("[DIAG] reuseAddress=true  → ${epA?.let { "OK ${it.relayedHost}:${it.relayedPort}" } ?: "FAIL"}")
        sockA.close()

        // 样式 B：probe 的裸 socket（无 reuseAddress）
        val sockB = DatagramSocket(null).apply { bind(InetSocketAddress("0.0.0.0", 0)) }
        val epB = TurnClient(sockB, srv).allocate(3000)
        println("[DIAG] 无 reuseAddress    → ${epB?.let { "OK ${it.relayedHost}:${it.relayedPort}" } ?: "FAIL"}")
        sockB.close()
    }

    @Test
    fun relayEstablishAndBidirectionalFrames() = runBlocking {
        if (System.getenv("JUAN_TURN_LIVE") != "1") return@runBlocking
        val srv = configuredServers().firstOrNull()
        assertNotNull(srv, "turn.json 应有 TURN 凭据")
        println("[LIVE-RELAY] 服务器 ${srv.host}:${srv.port} user=${srv.username}")

        val tA = TurnTransport(servers = listOf(srv))
        val lA = TListener()
        val ep = tA.allocateRelayEndpoint()
        assertNotNull(ep, "Initiator 应能从 ${srv.host} 分配到 relayed 地址")
        println("[LIVE-RELAY] Initiator relayed=${ep!!.relayedHost}:${ep.relayedPort}")
        tA.start(lA)

        val tB = TurnTransport(servers = listOf(srv))
        val lB = TListener()
        val peer = PeerInfo("dev-B", "sess", ep.relayedHost, ep.relayedPort, ByteArray(32),
            type = "relay", relayServer = "${srv.host}:${srv.port}")
        val ok = tB.connectAny(listOf(peer), lB)
        assertTrue(ok, "Responder 经 ${srv.host} 中继连接应成功")
        println("[LIVE-RELAY] Responder 连接成功")

        awaitUntil(8000) { tA.isConnected && tB.isConnected }
        assertTrue(tA.isConnected && tB.isConnected, "双向应建立")
        assertTrue(lA.connections.size >= 1, "A 应收到 onConnected")
        assertTrue(lB.connections.size >= 1, "B 应收到 onConnected")
        assertEquals(0, lA.disconnects.size, "中继连接成功不应触发 onDisconnected")
        assertEquals(0, lB.disconnects.size, "中继连接成功不应触发 onDisconnected")
        println("[LIVE-RELAY] 双向建立（A→B 与 B→A 均 onConnected）")

        // B → A 帧（稳态路径）
        assertTrue(tB.send(Heartbeat(3, nowEpochMillis())))
        awaitUntil(3000) { lA.frames.size >= 1 }
        assertIs<Heartbeat>(lA.frames.first())

        // A → B 帧
        assertTrue(tA.send(Heartbeat(4, nowEpochMillis())))
        awaitUntil(3000) { lB.frames.size >= 1 }
        assertIs<Heartbeat>(lB.frames.first())
        println("[LIVE-RELAY] 帧双向往返通过（B→A 与 A→B）")

        tA.disconnect()
        tB.disconnect()
    }

    /**
     * 长连接稳态：双向建立后持续 20s 双向互发帧（模拟 QualityMonitor 每秒 probe），
     * 期间任何一端不得 onDisconnected。复现真机「建立成功 ~11s 后两端同时 IoError」的断点：
     * 若本机宽带路径稳定而真机蜂窝断 → 问题在蜂窝 UDP 路径；若本机也断 → 客户端/服务器 bug。
     */
    @Test
    fun sustainedBidirectionalTraffic() = runBlocking {
        if (System.getenv("JUAN_TURN_LIVE") != "1") return@runBlocking
        val srv = configuredServers().firstOrNull()
        assertNotNull(srv, "turn.json 应有 TURN 凭据")
        println("[LIVE-RELAY] 服务器 ${srv.host}:${srv.port} user=${srv.username}")

        val tA = TurnTransport(servers = listOf(srv))
        val lA = TListener()
        val ep = tA.allocateRelayEndpoint()
        assertNotNull(ep, "Initiator 分配失败")
        tA.start(lA)

        val tB = TurnTransport(servers = listOf(srv))
        val lB = TListener()
        val peer = PeerInfo("dev-B", "sess", ep!!.relayedHost, ep.relayedPort, ByteArray(32),
            type = "relay", relayServer = "${srv.host}:${srv.port}")
        assertTrue(tB.connectAny(listOf(peer), lB), "Responder 中继连接成功")
        awaitUntil(8000) { tA.isConnected && tB.isConnected }
        assertTrue(tA.isConnected && tB.isConnected, "双向建立")
        println("[LIVE-RELAY] 双向建立，开始 20s 持续双向流量")

        var bSeq = 100
        var aSeq = 200
        val deadline = nowEpochMillis() + 20_000
        var round = 0
        while (nowEpochMillis() < deadline) {
            round++
            val bId = bSeq++.toLong()
            val aId = aSeq++.toLong()
            assertTrue(tB.send(Heartbeat(bId, nowEpochMillis())))
            assertTrue(tA.send(Heartbeat(aId, nowEpochMillis())))
            awaitUntil(2000) { lA.frames.any { it is Heartbeat && it.messageId == bId } }
            awaitUntil(2000) { lB.frames.any { it is Heartbeat && it.messageId == aId } }
            assertEquals(0, lA.disconnects.size, "第 $round 轮：A 不应断开")
            assertEquals(0, lB.disconnects.size, "第 $round 轮：B 不应断开")
            delay(1000)
        }
        println("[LIVE-RELAY] 持续 ${round} 轮（20s）双向流量稳定，两端均未断开")

        tA.disconnect()
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

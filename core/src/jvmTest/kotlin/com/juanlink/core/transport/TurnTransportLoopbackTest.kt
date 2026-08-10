package com.juanlink.core.transport

import com.juanlink.core.protocol.EncryptedFrame
import com.juanlink.core.protocol.Heartbeat
import com.juanlink.core.protocol.WireMessage
import com.juanlink.core.turn.TurnProtocol
import com.juanlink.core.turn.TurnProtocol.ParsedMessage
import com.juanlink.core.util.nowEpochMillis
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * TURN 中继回环测试（真实 DatagramSocket + 本地 TestTurnServer）：
 * Initiator allocate → start 监听 → Responder 经**同一台**服务器连对端 → 双向 onConnected →
 * 帧往返 → 16KB 大帧分片重组。验证 ReliableDatagramSession 数据面在 Send/Data Indication
 * 上零改动复用（物理发给服务器，逻辑对端进 XOR-PEER-ADDRESS）。
 */
class TurnTransportLoopbackTest {

    private class TListener : TransportListener {
        val connections = CopyOnWriteArrayList<String>()
        val frames = CopyOnWriteArrayList<WireMessage>()
        val disconnects = CopyOnWriteArrayList<DisconnectReason>()
        override fun onConnected() { connections.add("conn") }
        override fun onDisconnected(reason: DisconnectReason) { disconnects.add(reason) }
        override fun onFrame(message: WireMessage) { frames.add(message) }
    }

    private fun relayPeer(host: String, port: Int, relayServer: String) =
        PeerInfo("dev-B", "sess", host, port, ByteArray(32), type = "relay", relayServer = relayServer)

    private fun servers(server: TestTurnServer) =
        listOf(TurnServer("127.0.0.1", server.port, "test", "test"))

    @Test
    fun relayEstablishAndBidirectionalFrames() = runBlocking {
        val server = TestTurnServer().start()
        try {
            val tA = TurnTransport(servers = servers(server))
            val lA = TListener()
            val ep = tA.allocateRelayEndpoint()
            assertNotNull(ep, "Initiator 应能从中继服务器分配到 relayed 地址")
            assertEquals("127.0.0.1", ep!!.relayedHost)
            tA.start(lA)

            val tB = TurnTransport(servers = servers(server))
            val lB = TListener()
            assertTrue(
                tB.connectAny(listOf(relayPeer(ep.relayedHost, ep.relayedPort, "127.0.0.1:${server.port}")), lB),
                "Responder 经 TURN 中继连接应成功",
            )

            awaitUntil(5000) { tA.isConnected && tB.isConnected }
            assertTrue(tA.isConnected && tB.isConnected, "双向应建立")
            assertTrue(lA.connections.size >= 1, "A 应收到 onConnected")
            assertTrue(lB.connections.size >= 1, "B 应收到 onConnected")
            assertEquals(0, lA.disconnects.size, "中继连接成功不应触发 onDisconnected")
            assertEquals(0, lB.disconnects.size, "中继连接成功不应触发 onDisconnected")

            // B → A 帧（Responder 首包在学习阶段已交付；再发一帧验证稳态路径）
            assertTrue(tB.send(Heartbeat(3, nowEpochMillis())))
            awaitUntil(3000) { lA.frames.size >= 1 }
            assertIs<Heartbeat>(lA.frames.first())

            // A → B 帧
            assertTrue(tA.send(Heartbeat(4, nowEpochMillis())))
            awaitUntil(3000) { lB.frames.size >= 1 }
            assertIs<Heartbeat>(lB.frames.first())

            tA.disconnect()
            tB.disconnect()
        } finally {
            server.close()
        }
    }

    @Test
    fun largeFrameRoundTripOverRelay() = runBlocking {
        val server = TestTurnServer().start()
        try {
            val tA = TurnTransport(servers = servers(server))
            val ep = tA.allocateRelayEndpoint()!!
            tA.start(TListener())

            val tB = TurnTransport(servers = servers(server))
            val lB = TListener()
            assertTrue(tB.connectAny(listOf(relayPeer(ep.relayedHost, ep.relayedPort, "127.0.0.1:${server.port}")), lB))
            awaitUntil(5000) { tA.isConnected && tB.isConnected }

            // 16KB 密文 → 14 分片 → 经中继逐片转发 → 重组 → 严格按序交付
            val cipher = ByteArray(16000) { (it % 251).toByte() }
            val big = EncryptedFrame(9, 9, ByteArray(12) { 1 }, cipher, ByteArray(32) { 2 })
            assertTrue(tA.send(big))
            awaitUntil(5000) { lB.frames.size == 1 }
            val got = assertIs<EncryptedFrame>(lB.frames[0])
            assertEquals(9, got.seq)
            assertContentEquals(cipher, got.ciphertext, "16KB 密文应经中继无损往返")

            tA.disconnect()
            tB.disconnect()
        } finally {
            server.close()
        }
    }

    @Test
    fun reconnectAfterDisconnectRelearnsPeerAddress() = runBlocking {
        val server = TestTurnServer().start()
        try {
            // Initiator：分配 + 监听。断开期间中继分配保持活跃（Initiator 自身未断线）
            val tA = TurnTransport(servers = servers(server))
            val lA = TListener()
            val ep = tA.allocateRelayEndpoint()!!
            tA.start(lA)

            // Responder 首次连接
            val tB = TurnTransport(servers = servers(server))
            val lB = TListener()
            val peers = listOf(relayPeer(ep.relayedHost, ep.relayedPort, "127.0.0.1:${server.port}"))
            assertTrue(tB.connectAny(peers, lB))
            awaitUntil(5000) { tA.isConnected && tB.isConnected }
            assertTrue(tB.send(Heartbeat(1, nowEpochMillis())))
            awaitUntil(3000) { lA.frames.isNotEmpty() }

            // Responder 断开（模拟网络中断：socket/分配全释放 → phase=Closed）
            tB.disconnect()
            assertFalse(tB.isConnected)
            awaitUntil(3000) { lB.disconnects.isNotEmpty() }

            // 同一传输实例重连：Closed→Idle 重置 + 新分配（新 relayed 地址）→
            // Initiator 收到陌生地址首包 → onReattach 重新绑定 → 双向恢复
            assertTrue(tB.connectAny(peers, lB), "断开后同一传输应能经同服务器重连")
            awaitUntil(5000) { tA.isConnected && tB.isConnected }
            assertTrue(tA.isConnected && tB.isConnected, "重连后双向应建立")
            assertTrue(tB.send(Heartbeat(2, nowEpochMillis())))
            awaitUntil(3000) { lA.frames.any { it is Heartbeat } }
            assertIs<Heartbeat>(lA.frames.last())

            tA.disconnect()
            tB.disconnect()
        } finally {
            server.close()
        }
    }

    /**
     * #7 修复验证：Initiator 收到 IoError（对端失联，本地重传耗尽）后**保持中继监听**等待
     * 对端重连，而非关闭 socket。Responder 重连（新 allocation）→ Initiator 回 Listening
     * 经 onFirstData 重建会话 → 双向恢复。若 Initiator 断开时 socket 被关闭（旧行为），
     * Responder 重连首包无人接收，重连必然失败。
     */
    @Test
    fun initiatorKeepsListeningAfterIoErrorAndResponderReconnects() = runBlocking {
        val server = TestTurnServer().start()
        try {
            val tA = TurnTransport(servers = servers(server))
            val lA = TListener()
            val ep = tA.allocateRelayEndpoint()!!
            tA.start(lA)

            val tB = TurnTransport(servers = servers(server))
            val lB = TListener()
            val peers = listOf(relayPeer(ep.relayedHost, ep.relayedPort, "127.0.0.1:${server.port}"))
            assertTrue(tB.connectAny(peers, lB))
            awaitUntil(5000) { tA.isConnected && tB.isConnected }
            assertTrue(tB.send(Heartbeat(1, nowEpochMillis())))
            awaitUntil(3000) { lA.frames.isNotEmpty() }

            // Responder 断开 → Initiator 发帧 ACK 永不回来 → 重传耗尽 → IoError
            tB.disconnect()
            assertTrue(tA.send(Heartbeat(5, nowEpochMillis())))
            // Initiator 应收到 IoError（回 Listening，保留 socket），而非保持 Connected
            awaitUntil(40_000) { lA.disconnects.contains(DisconnectReason.IoError) }
            assertFalse(tA.isConnected, "Initiator 应已脱离 Established")

            // Responder 重连：Initiator 若仍监听（#7 修复），首包经 onFirstData 重建 → 双向恢复
            assertTrue(tB.connectAny(peers, lB), "Responder 断开后应能重连")
            awaitUntil(8000) { tA.isConnected && tB.isConnected }
            assertTrue(tA.isConnected && tB.isConnected, "Initiator 保持监听时重连应双向建立")
            assertTrue(lA.connections.size >= 2, "Initiator 应第二次 onConnected")
            assertTrue(tB.send(Heartbeat(2, nowEpochMillis())))
            awaitUntil(3000) { lA.frames.any { it is Heartbeat } }

            tA.disconnect()
            tB.disconnect()
        } finally {
            server.close()
        }
    }

    /**
     * 重复 createRoom 中继复用：Initiator 已监听时再次 allocateRelayEndpoint + start
     * （AppState.createRoom 每次调用都会执行）必须**复用现有中继端点**而非返回 null——
     * 否则二维码丢 relay 候选，跨网对端只能走 TCP → 局域网直连失败。
     * 断开后重新创建则应重新分配（Closed → Idle 重置）新端点。
     */
    @Test
    fun repeatedCreateRoomReusesRelayEndpoint() = runBlocking {
        val server = TestTurnServer().start()
        try {
            val tA = TurnTransport(servers = servers(server))
            val lA = TListener()

            // 第一次创建：分配 + 监听
            val ep1 = tA.allocateRelayEndpoint()
            assertNotNull(ep1, "第一次创建应分配中继")
            tA.start(lA)

            // 第二次创建（未断开）：必须复用同一端点，不得返回 null
            val ep2 = tA.allocateRelayEndpoint()
            assertNotNull(ep2, "重复 createRoom 应复用中继端点而非返回 null")
            assertEquals(ep1!!.relayedPort, ep2!!.relayedPort, "复用应保持同一 relayed 端点")
            tA.start(lA) // 幂等，不应报「中继分配失败」

            // 第三次创建后仍可被 Responder 正常接入（中继仍在监听）
            val tB = TurnTransport(servers = servers(server))
            val lB = TListener()
            assertTrue(
                tB.connectAny(listOf(relayPeer(ep2.relayedHost, ep2.relayedPort, "127.0.0.1:${server.port}")), lB),
                "复用后 Responder 应仍能经中继接入",
            )
            awaitUntil(5000) { tA.isConnected && tB.isConnected }
            assertTrue(tB.send(Heartbeat(1, nowEpochMillis())))
            awaitUntil(3000) { lA.frames.isNotEmpty() }

            // 断开后重新创建：Closed → Idle 重置，重新分配新端点
            tB.disconnect()
            tA.disconnect()
            val ep3 = tA.allocateRelayEndpoint()
            assertNotNull(ep3, "断开后重新 createRoom 应重新分配中继")

            tA.disconnect()
        } finally {
            server.close()
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

/**
 * 本地模拟 TURN server（jvmTest 专用，不连公网）。
 *
 * 简化：relayed 地址 = 客户端 socket 自身地址（127.0.0.1:客户端源端口），因此 Data Indication
 * 直接发到客户端 socket，无需服务器另开转发端口——回环语义等价。allocation 表仍按
 * `relayed("host:port") → 客户端 socket 地址` 维护，模拟真实 TURN 的地址空间映射。
 *
 * 流程：Allocate 401 质询 → 带凭据 200；CreatePermission / Refresh 直接 200；
 * Send Indication → 查表把 Data Indication 转发给目标 relayed 的客户端
 * （XOR-PEER-ADDRESS = 发送者 relayed，DATA = payload）。
 */
class TestTurnServer {
    val socket = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
    val port: Int get() = socket.localPort
    private val thread = Thread { loop() }.apply { isDaemon = true }

    /** relayed(`host:port`) → 客户端 socket 地址 */
    private val allocations = ConcurrentHashMap<String, InetSocketAddress>()

    fun start(): TestTurnServer {
        thread.start()
        return this
    }

    private fun loop() {
        val buf = ByteArray(8192)
        while (!socket.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            val len = try {
                socket.receive(pkt)
                pkt.length
            } catch (e: Exception) {
                break
            }
            val msg = TurnProtocol.parse(buf, len) ?: continue
            val from = pkt.socketAddress as InetSocketAddress
            when (msg.type) {
                TurnProtocol.requestType(TurnProtocol.METHOD_ALLOCATE) ->
                    handleAllocate(msg, from)

                TurnProtocol.requestType(TurnProtocol.METHOD_CREATE_PERMISSION) ->
                    reply(TurnProtocol.successType(TurnProtocol.METHOD_CREATE_PERMISSION), ByteArray(0), msg.txId, from)

                TurnProtocol.requestType(TurnProtocol.METHOD_REFRESH) ->
                    reply(TurnProtocol.successType(TurnProtocol.METHOD_REFRESH), ByteArray(0), msg.txId, from)

                TurnProtocol.indicationType(TurnProtocol.METHOD_SEND) ->
                    handleSend(msg, from)

                else -> {}
            }
        }
    }

    private fun handleAllocate(msg: ParsedMessage, from: InetSocketAddress) {
        // 无凭据 → 401 质询（带 REALM/NONCE）
        if (TurnProtocol.attr(msg, TurnProtocol.ATTR_MESSAGE_INTEGRITY) == null) {
            val ec = byteArrayOf(0, 0, 4, 1)
            val body = TurnProtocol.attribute(TurnProtocol.ATTR_ERROR_CODE, ec) +
                TurnProtocol.stringAttribute(TurnProtocol.ATTR_REALM, REALM) +
                TurnProtocol.stringAttribute(TurnProtocol.ATTR_NONCE, NONCE)
            reply(TurnProtocol.errorType(TurnProtocol.METHOD_ALLOCATE), body, msg.txId, from)
            return
        }
        // 带凭据 → 200：relayed = 127.0.0.1:客户端源端口（回环下即其 socket 地址）
        allocations["$RELAYED_HOST:${from.port}"] = from
        val ra = TurnProtocol.encodeXorAddressAttr(
            TurnProtocol.ATTR_XOR_RELAYED_ADDRESS, RELAYED_HOST, from.port, msg.txId,
        ) ?: return
        val body = ra + TurnProtocol.u32Attribute(TurnProtocol.ATTR_LIFETIME, 600)
        reply(TurnProtocol.successType(TurnProtocol.METHOD_ALLOCATE), body, msg.txId, from)
    }

    private fun handleSend(msg: ParsedMessage, from: InetSocketAddress) {
        val peerAttr = TurnProtocol.attr(msg, TurnProtocol.ATTR_XOR_PEER_ADDRESS) ?: return
        val (destHost, destPort) = TurnProtocol.decodeXorAddress(peerAttr, msg.txId) ?: return
        val target = allocations["$destHost:$destPort"] ?: return
        val dataAttr = TurnProtocol.attr(msg, TurnProtocol.ATTR_DATA) ?: return
        // Data Indication：XOR-PEER-ADDRESS = 发送者 relayed（回环下 = 其 socket 地址）
        val txId = ByteArray(12) { (it + 7).toByte() }
        val peer = TurnProtocol.encodeXorAddressAttr(
            TurnProtocol.ATTR_XOR_PEER_ADDRESS, from.hostString, from.port, txId,
        ) ?: return
        val body = peer + TurnProtocol.attribute(TurnProtocol.ATTR_DATA, dataAttr.value)
        reply(TurnProtocol.indicationType(TurnProtocol.METHOD_DATA), body, txId, target)
    }

    private fun reply(type: Int, body: ByteArray, txId: ByteArray, to: InetSocketAddress) {
        runCatching {
            socket.send(DatagramPacket(TurnProtocol.header(type, body.size, txId) + body, body.size + 20, to))
        }
    }

    fun close() = socket.close()

    private companion object {
        const val RELAYED_HOST = "127.0.0.1"
        const val REALM = "test"
        const val NONCE = "testnonce"
    }
}

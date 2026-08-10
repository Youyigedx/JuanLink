package com.juanlink.core.transport

import com.juanlink.core.turn.TurnClient
import com.juanlink.core.turn.TurnProtocol
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * TURN 中继数据面（live，连真实服务器）。
 * 环境变量 JUAN_TURN_LIVE=1 才真正连网。
 *
 * 实测 Cloudflare Calls 与自建 coturn 都要求「接收方对发送方 relayed 有 permission」才转发
 * （严格于 RFC 5766「只查发送方」）。本测试模拟对称握手：双方各自 allocate，**互建 permission**
 * 后双向 sendData——验证「若协议能交换 relayed 地址，中继数据面双向可通」。
 */
class TurnLiveSymPermissionTest {

    private data class Peer(val sock: DatagramSocket, val client: TurnClient, val ep: TurnRelayEndpoint)

    @Test
    fun symmetricPermissionBidirectionalDataOverCloudflare() {
        if (System.getenv("JUAN_TURN_LIVE") != "1") return
        val file = File(System.getProperty("user.home"), ".juanlink/turn.json")
        val srv = TurnConfigJson.decodeList(file.readText()).firstOrNull()
            ?: return

        // 各自 socket + allocate（allocate 自行 pump，不依赖 reader）
        val a = allocate(srv)
        val b = allocate(srv)
        println("[SYM] A=${a.ep.relayedHost}:${a.ep.relayedPort} B=${b.ep.relayedHost}:${b.ep.relayedPort}")

        // reader 先启动（CreatePermission 依赖 reader 调 route 完成挂起事务）
        val rxA = LinkedBlockingQueue<Triple<String, Int, ByteArray>>()
        val rxB = LinkedBlockingQueue<Triple<String, Int, ByteArray>>()
        thread(isDaemon = true, name = "rx-a") { readData(a, rxA) }
        thread(isDaemon = true, name = "rx-b") { readData(b, rxB) }

        // Cloudflare 要求：互建 permission（双方各自对对方 relayed 建）
        assertTrue(a.client.createPermission(b.ep.relayedHost, b.ep.relayedPort, 2000), "A 对 B permission")
        assertTrue(b.client.createPermission(a.ep.relayedHost, a.ep.relayedPort, 2000), "B 对 A permission")

        // B → A
        val msgB = "HELLO-FROM-B".toByteArray()
        b.client.sendData(a.ep.relayedHost, a.ep.relayedPort, msgB)
        val gotBtoA = rxA.poll(5000, TimeUnit.MILLISECONDS)
        assertNotNull(gotBtoA, "A 应收到 B 的数据")
        assertContentEquals(msgB, gotBtoA!!.third, "B→A 载荷应无损")

        // A → B
        val msgA = "HELLO-FROM-A".toByteArray()
        a.client.sendData(b.ep.relayedHost, b.ep.relayedPort, msgA)
        val gotAtoB = rxB.poll(5000, TimeUnit.MILLISECONDS)
        assertNotNull(gotAtoB, "B 应收到 A 的数据")
        assertContentEquals(msgA, gotAtoB!!.third, "A→B 载荷应无损")

        println("[SYM] 双向 sendData 经中继通过")
        a.sock.close(); b.sock.close()
    }

    private fun allocate(srv: TurnServer): Peer {
        val sock = DatagramSocket(null).apply { bind(InetSocketAddress("0.0.0.0", 0)) }
        val client = TurnClient(sock, srv)
        val ep = assertNotNull(client.allocate(3000), "allocate 应成功 (${srv.host})")
        return Peer(sock, client, ep)
    }

    private fun readData(p: Peer, out: LinkedBlockingQueue<Triple<String, Int, ByteArray>>) {
        val buf = ByteArray(8192)
        while (!p.sock.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            val len = try {
                p.sock.receive(pkt)
                pkt.length
            } catch (e: Exception) {
                break
            }
            if (p.client.route(buf, len)) continue // 挂起事务响应（CreatePermission 等）已消费
            val di = TurnProtocol.parseDataIndication(buf, len) ?: continue
            out.put(Triple(di.peerHost, di.peerPort, di.payload))
        }
    }
}

package com.juanlink.core.turn

import com.juanlink.core.crypto.CryptoEngine
import com.juanlink.core.transport.DEFAULT_TURN_SERVERS
import com.juanlink.core.transport.TurnConfigJson
import com.juanlink.core.transport.TurnServer
import com.juanlink.core.turn.TurnProtocol.ParsedMessage
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test

/**
 * 公共 TURN 服务器可用性探测（live）。
 * 环境变量 JUAN_TURN_LIVE=1 才真正连网；默认空跑（不 @Ignore，否则方法体不执行，门控失效）。
 * allocate 成功仅说明「凭据有效 + 能分配中继」，不代表数据面通（后者需端到端）。
 */
class TurnLiveProbeTest {

    @Test
    fun probePublicTurnServers() {
        if (System.getenv("JUAN_TURN_LIVE") != "1") return
        // 在默认列表基础上补几个常见公共端点（凭据失效也算 FAIL，记录以供决策）
        val extra = listOf(
            TurnServer("openrelay.metered.ca", 3478, "openrelayproject", "openrelayproject"),
            TurnServer("relay.metered.ca", 3478, "openrelayproject", "openrelayproject"),
            TurnServer("turn.cloudflare.com", 3478, "", ""),
        )
        val all = (DEFAULT_TURN_SERVERS + extra).distinctBy { it.host to it.port }
        for (srv in all) {
            val sock = DatagramSocket(null).apply { bind(InetSocketAddress("0.0.0.0", 0)) }
            val t0 = System.currentTimeMillis()
            val ep = TurnClient(sock, srv).allocate(3000)
            val ms = System.currentTimeMillis() - t0
            println("[PROBE] ${srv.host}:${srv.port} user=${srv.username} → " +
                (if (ep != null) "OK relayed=${ep.relayedHost}:${ep.relayedPort} (${ms}ms)" else "FAIL (${ms}ms)"))
            sock.close()
        }
    }

    /**
     * 探测 turn.json 里配置的服务器（与桌面生产同一路径 + 同一 TurnConfigJson 解码）。
     * 验证「配置格式 + 服务器连通 + 凭据有效」整条链路。
     */
    @Test
    fun probeConfiguredTurnServers() {
        if (System.getenv("JUAN_TURN_LIVE") != "1") return
        val file = java.io.File(System.getProperty("user.home"), ".juanlink/turn.json")
        if (!file.exists()) {
            println("[PROBE] 无配置文件 ${file.absolutePath}")
            return
        }
        val servers = TurnConfigJson.decodeList(file.readText())
        if (servers.isEmpty()) {
            println("[PROBE] turn.json 解析为空")
            return
        }
        for (srv in servers) {
            val sock = DatagramSocket(null).apply { bind(InetSocketAddress("0.0.0.0", 0)) }
            val t0 = System.currentTimeMillis()
            val ep = TurnClient(sock, srv).allocate(3000)
            val ms = System.currentTimeMillis() - t0
            println("[PROBE] config ${srv.host}:${srv.port} user=${srv.username} → " +
                (if (ep != null) "OK relayed=${ep.relayedHost}:${ep.relayedPort} (${ms}ms)" else "FAIL (${ms}ms)"))
            sock.close()
        }
    }

    /** 精确定位 Cloudflare 拒绝原因：裸 Allocate → 401 → 带凭据重发，逐步打印服务器响应 */
    @Test
    fun diagnoseCloudflareAllocate() {
        if (System.getenv("JUAN_TURN_LIVE") != "1") return
        val file = java.io.File(System.getProperty("user.home"), ".juanlink/turn.json")
        val srv = TurnConfigJson.decodeList(file.readText()).firstOrNull() ?: run {
            println("[DIAG] 无配置")
            return
        }
        val sock = DatagramSocket(null).apply {
            bind(InetSocketAddress("0.0.0.0", 0))
            soTimeout = 2500
        }
        val addr = InetAddress.getByName(srv.host)
        val buf = ByteArray(8192)

        // 1) 裸 Allocate（无凭据）→ 期望 401 + realm/nonce
        val tx1 = CryptoEngine.randomBytes(12)
        val req1 = TurnProtocol.buildAllocateRequest(tx1)
        sock.send(DatagramPacket(req1, req1.size, addr, srv.port))
        val p1 = DatagramPacket(buf, buf.size)
        val m1 = try {
            sock.receive(p1)
            TurnProtocol.parse(buf, p1.length)
        } catch (e: Exception) {
            println("[DIAG] 裸Allocate无响应: ${e.javaClass.simpleName}")
            null
        }
        if (m1 == null) {
            sock.close()
            return
        }
        val err1 = TurnProtocol.attr(m1, TurnProtocol.ATTR_ERROR_CODE)?.let { TurnProtocol.errorCodeOf(it) }
        println("[DIAG] 裸Allocate: type=0x${m1.type.toString(16)} error=${err1} " +
            "realm=${TurnProtocol.stringAttr(m1, TurnProtocol.ATTR_REALM)} " +
            "nonce=${TurnProtocol.stringAttr(m1, TurnProtocol.ATTR_NONCE)}")

        // 2) 带凭据重发
        val realm = TurnProtocol.stringAttr(m1, TurnProtocol.ATTR_REALM) ?: return
        val nonce = TurnProtocol.stringAttr(m1, TurnProtocol.ATTR_NONCE) ?: return
        val key = TurnProtocol.credentialKey(srv.username, realm, srv.password)
        val tx2 = CryptoEngine.randomBytes(12)
        val req2 = TurnProtocol.buildAuthenticatedRequest(
            TurnProtocol.METHOD_ALLOCATE,
            listOf(TurnProtocol.requestedTransportUdpAttribute()),
            tx2, srv.username, realm, nonce, key,
        )
        sock.send(DatagramPacket(req2, req2.size, addr, srv.port))
        val p2 = DatagramPacket(buf, buf.size)
        val m2 = try {
            sock.receive(p2)
            TurnProtocol.parse(buf, p2.length)
        } catch (e: Exception) {
            println("[DIAG] 带凭据无响应: ${e.javaClass.simpleName}")
            null
        }
        if (m2 == null) {
            sock.close()
            return
        }
        val err2 = TurnProtocol.attr(m2, TurnProtocol.ATTR_ERROR_CODE)?.let { TurnProtocol.errorCodeOf(it) }
        println("[DIAG] 带凭据Allocate: type=0x${m2.type.toString(16)} error=${err2}")
        if (m2.type == TurnProtocol.successType(TurnProtocol.METHOD_ALLOCATE)) {
            val ra = TurnProtocol.attr(m2, TurnProtocol.ATTR_XOR_RELAYED_ADDRESS)
            println("[DIAG]   RELAYED_OK = ${ra?.let { TurnProtocol.decodeXorAddress(it, m2.txId) }}")
        }
        sock.close()
    }
}

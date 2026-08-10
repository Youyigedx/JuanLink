package com.juanlink.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * TURN 配置：JSON 编解码往返 + 运行时 updateTurnServers 的候选优先级/去重。
 * 服务器优先级用本地 TestTurnServer 验证（不连公网），逻辑与 TurnTransportLoopbackTest 复用。
 */
class TurnConfigTest {

    @Test
    fun encodeDecodeRoundTrips() {
        val servers = listOf(
            TurnServer("turn.example.com", 3478, "alice", "secret"),
            TurnServer("turn2.example.com", 5349, "bob", "s3cr3t"),
        )
        val text = TurnConfigJson.encodeList(servers)
        assertEquals(servers, TurnConfigJson.decodeList(text))
    }

    @Test
    fun emptyListRoundTrips() {
        val text = TurnConfigJson.encodeList(emptyList())
        assertEquals(emptyList<TurnServer>(), TurnConfigJson.decodeList(text))
    }

    @Test
    fun corruptedTextFallsBackToEmpty() {
        assertEquals(emptyList<TurnServer>(), TurnConfigJson.decodeList("not json {{{"))
        // 缺必需字段 port → 解码失败 → 空列表（不致命，回退默认公共列表）
        assertEquals(emptyList<TurnServer>(), TurnConfigJson.decodeList("""[{"host":"no-port"}]"""))
    }

    @Test
    fun ignoresUnknownKeysForForwardCompat() {
        val text = """[{"host":"turn.example.com","port":3478,"username":"a","password":"b","newField":123}]"""
        assertEquals(
            listOf(TurnServer("turn.example.com", 3478, "a", "b")),
            TurnConfigJson.decodeList(text),
        )
    }

    @Test
    fun customServersTakePrecedenceOverConstructor() {
        val defaultServer = TestTurnServer().start()
        val customServer = TestTurnServer().start()
        try {
            val t = TurnTransport(servers = listOf(TurnServer("127.0.0.1", defaultServer.port, "test", "test")))
            // 运行时注入自定义服务器（应排到候选最前）
            t.updateTurnServers(listOf(TurnServer("127.0.0.1", customServer.port, "test", "test")))

            val ep = t.allocateRelayEndpoint()
            assertNotNull(ep, "分配应成功")
            assertEquals(customServer.port, ep!!.serverPort, "自定义服务器应优先于构造函数默认")
        } finally {
            defaultServer.close()
            customServer.close()
        }
    }

    @Test
    fun customDeadServerFallsBackToConstructor() {
        val aliveServer = TestTurnServer().start()
        try {
            val t = TurnTransport(servers = listOf(TurnServer("127.0.0.1", aliveServer.port, "test", "test")))
            // 自定义服务器无响应（dead port）→ 应回退到构造函数默认服务器
            t.updateTurnServers(listOf(TurnServer("127.0.0.1", 1, "test", "test")))

            val ep = t.allocateRelayEndpoint()
            assertNotNull(ep, "自定义服务器失败后应回退默认服务器")
            assertEquals(aliveServer.port, ep!!.serverPort)
        } finally {
            aliveServer.close()
        }
    }

    @Test
    fun duplicateCustomDoesNotDoubleTry() {
        val server = TestTurnServer().start()
        try {
            val t = TurnTransport(servers = listOf(TurnServer("127.0.0.1", server.port, "test", "test")))
            // 自定义与默认同 host:port：effective 应去重（自定义优先），仍正常分配
            t.updateTurnServers(listOf(TurnServer("127.0.0.1", server.port, "test", "test")))

            val ep = t.allocateRelayEndpoint()
            assertNotNull(ep, "去重后仍应分配成功")
            assertEquals(server.port, ep!!.serverPort)
        } finally {
            server.close()
        }
    }
}

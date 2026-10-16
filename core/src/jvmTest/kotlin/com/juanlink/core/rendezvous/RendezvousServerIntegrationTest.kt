package com.juanlink.core.rendezvous

import com.juanlink.core.pairing.Candidate
import com.juanlink.core.pairing.PairingCodec
import com.juanlink.core.pairing.PairingInfo
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 会合服务**真实集成测试**：启动真 Python 服务器，用真 HTTP 客户端往返。
 *
 * 与 `RendezvousTest`（纯单元）的分工：
 * - `RendezvousTest` 钉住短码格式与配置逻辑，不需要网络。
 * - 本测试钉住**客户端与服务端真的能对话**：Wire 字段名、状态码、JSON 结构、
 *   短码标准化、一次性消费。这类不一致只有真跑才能发现（开发期就抓到过一个
 *   「存储用带连字符码、查表用去连字符码」的键不一致 bug）。
 *
 * 若本机没有可用的 python 解释器，测试会**跳过（返回）**并在输出中说明，
 * 而不是误报成功——避免 CI 上假绿。
 */
class RendezvousServerIntegrationTest {

    private fun findPython(): String? {
        val candidates = listOf(
            "D:\\anaconda3\\python.exe",
            "python3",
            "python",
            System.getenv("PYTHON") ?: "",
        ).filter { it.isNotBlank() }
        for (c in candidates) {
            val ok = runCatching {
                val p = ProcessBuilder(c, "--version").redirectErrorStream(true).start()
                p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
            }.getOrDefault(false)
            if (ok) return c
        }
        return null
    }

    /** 项目根目录：从测试工作目录（模块目录）向上找含 server/ 的目录 */
    private fun projectRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            if (dir != null && File(dir, "server/rendezvous_server.py").exists()) return dir
            dir = dir?.parentFile
        }
        return null
    }

    private fun httpGet(url: String): Pair<Int, String> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        return try {
            val code = conn.responseCode
            val body = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun waitForHealth(base: String, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching { httpGet("$base/health").first == 200 }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(150)
        }
        return false
    }

    @Test
    fun realServerRoundTripRegisterAndLookup() {
        val python = findPython()
        if (python == null) {
            println("[RendezvousIntegration] 跳过：未找到可用 python 解释器")
            return
        }
        val root = projectRoot()
        if (root == null) {
            println("[RendezvousIntegration] 跳过：未找到项目根目录（server/rendezvous_server.py）")
            return
        }

        val port = 18255
        val proc = ProcessBuilder(
            python,
            File(root, "server/rendezvous_server.py").absolutePath,
            "--port", port.toString(),
            "--ttl", "60",
            "--quiet",
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

        val config = RendezvousConfig(serverHost = "127.0.0.1", serverPort = port, scheme = "http")
        try {
            assertTrue(waitForHealth(config.baseUrl()), "会合服务器未在超时内就绪")

            // 1) 探活：真实客户端实现应能识别服务器
            assertTrue(DefaultRendezvousClient.ping(config), "ping 应返回 true")

            // 2) 登记：服务端分配短码
            val info = PairingInfo(
                sessionId = "sess-integration",
                deviceId = "dev-it-1",
                publicKey = "cHVibGlja2V5MzJieXRlc2Jhc2U2NHVybGVuY29kZWQ",
                ts = System.currentTimeMillis(),
                nonce = "bm9uY2U4Ynl0ZXM",
                candidates = listOf(
                    Candidate("192.168.1.5", 45678, type = "tcp"),
                    Candidate("203.0.113.9", 50000, type = "relay"),
                ),
                turn = "turn.example.com:3478",
            )
            val payloadJson = PairingCodec.toQrPayload(info)
            val candidate = RendezvousCodeFormat.generate(ByteArray(8) { 11 })
            val issued = DefaultRendezvousClient.register(
                PairingInfoWire(json = payloadJson, code = candidate),
                config,
            )
            assertNotNull(issued, "登记应成功并返回短码")
            assertTrue(
                RendezvousCodeFormat.validate(issued.code),
                "服务端下发的码应通过客户端严格校验: ${issued.code}",
            )
            assertTrue(issued.expiresInSec in 1..60, "TTL 应被服务端夹紧: ${issued.expiresInSec}")

            // 3) 取回：应还原出等价 PairingInfo（关键：Wire JSON 字段与序列化一致）
            val wire = DefaultRendezvousClient.lookup(issued.code, config)
            assertNotNull(wire, "用短码应能取回配对信息")
            assertEquals(info, PairingCodec.fromQrPayload(wire.json), "往返后 PairingInfo 必须完全等价")

            // 4) 一次性消费：同码再查必须失败
            assertNull2(DefaultRendezvousClient.lookup(issued.code, config), "同码二次查询必须失败")

            // 5) 宽容输入：小写 + 无连字符也能查（新登记一条）
            val issued2 = DefaultRendezvousClient.register(
                PairingInfoWire(json = payloadJson, code = candidate),
                config,
            )
            assertNotNull(issued2)
            val loose = issued2.code.replace("-", "").lowercase()
            val wire2 = DefaultRendezvousClient.lookup(loose, config)
            assertNotNull(wire2, "小写无连字符的码也应能取回: $loose")
            assertEquals(info, PairingCodec.fromQrPayload(wire2.json))

            // 6) 畸形码：客户端本地就应拒绝，不发请求
            assertNull2(DefaultRendezvousClient.lookup("AAAA-AAAA", config), "畸形码应被拒")

            // 7) 未启用的配置不发请求
            assertNull2(DefaultRendezvousClient.lookup(issued2.code, RendezvousConfig()), "未配置服务器时不应查询")
            assertFalse(DefaultRendezvousClient.ping(RendezvousConfig()), "未配置服务器时 ping 应为 false")
        } finally {
            proc.destroy()
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    /** 本地包装：让断言失败信息更清楚（避免 import assertNull 与泛型推断冲突） */
    private fun <T> assertNull2(value: T?, message: String) {
        assertTrue(value == null, message)
    }
}

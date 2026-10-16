package com.juanlink.core.rendezvous

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * JVM / 桌面端会合客户端：JDK 11+ 内置 `java.net.http.HttpClient`，**零第三方依赖**。
 *
 * 与安卓实现共用同一套 Wire 协议（见 `server/README.md`）。
 * 所有异常都吞成 null / false —— 会合失败不应让应用崩溃，只应让用户重试。
 */
actual object DefaultRendezvousClient : RendezvousClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            // 短码信令必须快速失败：超时由每个请求单独控制，这里给连接阶段兜底
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    override fun register(info: PairingInfoWire, config: RendezvousConfig): RendezvousCode? = runCatching {
        val code = info.code ?: return null
        val body = json.encodeToString(
            RegisterRequest.serializer(),
            RegisterRequest(code = code, ttl = INFO_TTL_SEC, payload = info.json),
        )
        val resp = send(
            config = config,
            path = "/rendezvous/register",
            method = "POST",
            body = body,
        ) ?: return null
        if (resp.statusCode() != 200) return null
        val parsed = runCatching {
            json.decodeFromString(RegisterResponse.serializer(), resp.body())
        }.getOrNull() ?: return null
        RendezvousCode(parsed.code, parsed.expiresIn)
    }.getOrNull()

    override fun lookup(code: String, config: RendezvousConfig): PairingInfoWire? = runCatching {
        val normalized = RendezvousCodeFormat.normalize(code)
        if (!RendezvousCodeFormat.validate(normalized)) return null
        val resp = send(
            config = config,
            path = "/rendezvous/lookup?code=$normalized",
            method = "GET",
        ) ?: return null
        if (resp.statusCode() != 200) return null
        val parsed = runCatching {
            json.decodeFromString(LookupResponse.serializer(), resp.body())
        }.getOrNull() ?: return null
        if (parsed.payload.isBlank()) return null
        PairingInfoWire(json = parsed.payload)
    }.getOrNull()

    override fun ping(config: RendezvousConfig): Boolean = runCatching {
        val resp = send(config, "/health", "GET") ?: return false
        resp.statusCode() == 200
    }.getOrDefault(false)

    private fun send(
        config: RendezvousConfig,
        path: String,
        method: String,
        body: String? = null,
    ): HttpResponse<String>? {
        if (!config.enabled) return null
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl() + path))
            .timeout(Duration.ofMillis(config.timeoutMs))
        val publisher = if (body != null) {
            builder.header("Content-Type", "application/json")
            HttpRequest.BodyPublishers.ofString(body)
        } else {
            HttpRequest.BodyPublishers.noBody()
        }
        if (config.accessToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.accessToken}")
        }
        val request = builder.method(method, publisher).build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private const val INFO_TTL_SEC = 180L
}

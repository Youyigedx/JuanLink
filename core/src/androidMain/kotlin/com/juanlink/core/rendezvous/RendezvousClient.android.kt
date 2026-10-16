package com.juanlink.core.rendezvous

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Android 会合客户端。
 *
 * 与 JVM 版同为 `java.net.http.HttpClient`（Android API 33+ 起内置），
 * 因此两端 Wire 协议完全一致，服务器只需一种实现。
 *
 * ⚠️ 两点平台约束：
 * 1. 必须持有 `android.permission.INTERNET`（见 AndroidManifest.xml）。
 * 2. 本方法是阻塞式的，**不得在主线程调用**。调用方 `AppState` 统一经
 *    `Dispatchers.IO` 派发；Android 主线程做网络 I/O 会直接抛 NetworkOnMainThreadException。
 */
actual object DefaultRendezvousClient : RendezvousClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
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
        val resp = send(config, "/rendezvous/register", "POST", body) ?: return null
        if (resp.statusCode() != 200) return null
        val parsed = runCatching {
            json.decodeFromString(RegisterResponse.serializer(), resp.body())
        }.getOrNull() ?: return null
        RendezvousCode(parsed.code, parsed.expiresIn)
    }.getOrNull()

    override fun lookup(code: String, config: RendezvousConfig): PairingInfoWire? = runCatching {
        val normalized = RendezvousCodeFormat.normalize(code)
        if (!RendezvousCodeFormat.validate(normalized)) return null
        val resp = send(config, "/rendezvous/lookup?code=$normalized", "GET") ?: return null
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
        return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    private const val INFO_TTL_SEC = 180L
}

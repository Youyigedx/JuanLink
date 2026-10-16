package com.juanlink.core.rendezvous

import com.juanlink.core.util.Base64Url
import kotlinx.serialization.Serializable

/**
 * 会合服务（Rendezvous）配置。
 *
 * ⚠️ 信任模型变更提示：启用会合服务后，**公钥分发权交给服务器**。
 * 服务器（或劫持者）若返回自己的公钥即可建立中间人，两端无法察觉。
 * 详见 `docs/protocol.md` §10「会合服务与信任边界」。
 *
 * 因此本配置只应指向**你自己掌控的服务器**；`serverHost` 为空表示未启用会合，
 * 此时退化为纯二维码/手动连接串模式（无服务器信任需求）。
 */
@Serializable
data class RendezvousConfig(
    /** 服务器主机（IP 或域名）。空 = 未启用会合服务 */
    val serverHost: String = "",
    val serverPort: Int = 8080,
    /** "http" 或 "https"。没有域名/证书时用 http（见文档的安全边界说明） */
    val scheme: String = "http",
    /** 可选 Bearer 令牌：服务器配置了 accessToken 时必须一致 */
    val accessToken: String = "",
    /** 会合信令超时（毫秒）。仅影响配对阶段，不影响已建立的 P2P 会话 */
    val timeoutMs: Long = 8_000,
) {
    val enabled: Boolean get() = serverHost.isNotBlank()

    /** 拼接基础 URL，如 `http://1.2.3.4:8080` */
    fun baseUrl(): String {
        val host = serverHost.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        return "$scheme://$host:$serverPort"
    }

    /** 网络侧规范化：去掉协议前缀与尾部斜杠，便于持久化与比较 */
    fun normalized(): RendezvousConfig =
        copy(serverHost = serverHost.trim().removePrefix("http://").removePrefix("https://").trimEnd('/'))
}

/** 会合请求/响应 DTO（与服务器 JSON 字段一一对应） */

@Serializable
internal data class RegisterRequest(
    val code: String,
    val ttl: Long,
    val payload: String,
)

@Serializable
internal data class RegisterResponse(
    val code: String,
    val expiresIn: Long,
)

@Serializable
internal data class LookupResponse(
    val payload: String,
)

@Serializable
internal data class ErrorResponse(
    val error: String = "",
)

/**
 * 会合客户端：把「短配对码」换成「完整配对信息」，反之亦然。
 *
 * 只搬运配对信令（公钥 + 候选地址 + TURN 地址），**不参与业务数据传输**：
 * 会话建立后画布数据仍走 P2P 直连或 TURN 中继，服务器不接触。
 *
 * 实现见各平台 actual（JVM/Android 均基于 `java.net.http.HttpClient`）。
 */
interface RendezvousClient {
    /** 连服务器拿一个短码并登记配对信息；失败返回 null（调用方应提示重试） */
    fun register(info: PairingInfoWire, config: RendezvousConfig): RendezvousCode?

    /** 用短码换回配对信息；码不存在/过期/被取用/限流 → null */
    fun lookup(code: String, config: RendezvousConfig): PairingInfoWire?

    /** 探活：服务器可达且版本兼容则返回 true */
    fun ping(config: RendezvousConfig): Boolean
}

/** 登记成功的结果：服务器分配的短码与有效期（秒） */
data class RendezvousCode(val code: String, val expiresInSec: Long)

/**
 * 会合信令载体：`PairingInfo` 的 JSON 文本 + 短码。
 *
 * 之所以在协议层用「JSON 文本」而非直接嵌 `PairingInfo` 对象，是为了让
 * `core` 的会合层与 `pairing` 包解耦（避免循环依赖），同时让服务器的存储
 * 保持为不透明字符串——服务器无需理解配对内容，也就无需随之升级。
 */
data class PairingInfoWire(val json: String, val code: String? = null)

/**
 * 短码生成与校验的共享工具。
 *
 * 格式 `DDDD-LLLC`：4 位数字 + 3 位字母 + 1 位校验字符。
 * 字母集为 Crockford 变体（去 I/L/O/U），避免手抄混淆。
 *
 * ⚠️ 信息安全量仅约 20 bit（约 10^6 组合）。因此服务器**必须**做严格限流，
 * 否则公网可枚举短码抢走待配对记录。见 `server/README.md`。
 */
object RendezvousCodeFormat {

    const val CHARSET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 服务端权威短码生成：8 位随机字节 → 4 数字 + 3 字母 + 1 校验 */
    fun generate(random: ByteArray): String {
        require(random.size >= 7) { "随机源至少 7 字节" }
        val digits = StringBuilder(4)
        val letters = StringBuilder(3)
        for (i in 0 until 4) digits.append(CHARSET[(random[i].toInt() and 0xFF) % 10])
        for (i in 4 until 7) letters.append(CHARSET[10 + ((random[i].toInt() and 0xFF) % 22)])
        val body = digits.toString() + letters.toString()
        return "${digits}-${letters}${checkChar(body)}"
    }

    /** 校验位：前 7 字符加权求和 mod 32 */
    fun checkChar(body7: String): Char {
        require(body7.length == 7) { "校验输入必须为 7 字符" }
        var sum = 0
        for (i in 0 until 7) {
            val v = CHARSET.indexOf(body7[i])
            require(v >= 0) { "非法字符: ${body7[i]}" }
            sum += v * (i + 1)
        }
        return CHARSET[sum % 32]
    }

    /** 严格校验：长度 8、前 4 位数字、校验位匹配 */
    fun validate(code: String): Boolean {
        val n = normalize(code)
        if (n.length != 8) return false
        if (n.take(4).any { it !in '0'..'9' }) return false
        return runCatching { checkChar(n.substring(0, 7)) == n[7] }.getOrDefault(false)
    }

    /** 标准化：去分隔符与空白、转大写 */
    fun normalize(code: String): String =
        code.replace("-", "").replace(" ", "").replace("\t", "").uppercase()

    /** 判断输入是否是「短码形态」（用于区分粘贴连接串 vs 输入配对码） */
    fun looksLikeCode(raw: String): Boolean {
        val n = normalize(raw)
        return n.length == 8 && n.all { it in CHARSET }
    }

    /**
     * 带重试的短码分配：最多 [maxAttempts] 次，由 [taken] 判定是否已占用。
     * 短码空间约 10^6，活跃记录数远小于此，碰撞概率低但必须处理。
     */
    fun allocate(
        random: () -> ByteArray,
        taken: (String) -> Boolean,
        maxAttempts: Int = 8,
    ): String {
        repeat(maxAttempts) {
            val candidate = generate(random())
            if (!taken(candidate)) return candidate
        }
        throw IllegalStateException("短码空间拥挤，分配失败（活跃配对过多）")
    }

    /** 供服务端生成随机短码的辅助：base64url 解码容错版（保留给未来扩展） */
    fun decodeBase64Url(s: String): ByteArray? = runCatching { Base64Url.decode(s) }.getOrNull()
}

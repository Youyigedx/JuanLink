package com.juanlink.core.pairing

import com.juanlink.core.AppInfo
import com.juanlink.core.crypto.CryptoEngine
import com.juanlink.core.util.nowEpochMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 连接配对信息：二维码 / 配对码交换的完整握手载体。
 * 全部为 @Serializable，经 JSON 编码进入二维码 payload 或配对码查找响应。
 */
@Serializable
data class PairingInfo(
    val version: Int = AppInfo.PROTOCOL_VERSION,
    val magic: String = AppInfo.MAGIC,
    /** 会话 ID（16B 随机，base64url），每会话唯一 */
    val sessionId: String,
    /** 发起方设备 ID */
    val deviceId: String,
    /** 发起方 X25519 公钥（32B base64url）。只含公钥，绝不含私钥 */
    val publicKey: String,
    /** 生成时间（epoch ms） */
    val ts: Long,
    /** 有效期（秒），默认 180 */
    val exp: Long = AppInfo.DEFAULT_PAIRING_EXPIRES_SEC,
    /** 一次性 nonce（8B base64url），防重放 */
    val nonce: String,
    val role: String = "initiator",
    /** 候选地址（tcp 局域网直连 / relay 中继） */
    val candidates: List<Candidate> = emptyList(),
    /**
     * TURN 中继服务器 `host:port`（Initiator 实际分配成功的那台；null 表示未启用中继）。
     * 两端必须连同一台 TURN（relayed 地址只对分配它的服务器有意义），故必须带进二维码。
     * encodeDefaults=false + null 默认 → 旧二维码字节不变；旧端忽略新字段。
     */
    val turn: String? = null,
)

@Serializable
data class Candidate(
    val host: String,
    val port: Int,
    /** 候选类型：tcp（局域网直连）| relay（TURN 中继）。默认 tcp 不落盘，旧端兼容 */
    val type: String = "tcp",
)

/** 二维码 payload 编解码 */
object PairingCodec {

    private val json = Json {
        // 不编码默认值字段（version/magic/exp/role），二维码负载更小 → QR 版本更低、屏幕模块更大、更易扫码
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    fun toQrPayload(p: PairingInfo): String = json.encodeToString(PairingInfo.serializer(), p)

    fun fromQrPayload(raw: String): PairingInfo =
        json.decodeFromString(PairingInfo.serializer(), raw)

    /** 校验 payload 是否有效（魔数 + 版本 + 未过期） */
    fun isValid(p: PairingInfo, now: Long = nowEpochMillis()): Boolean {
        if (p.magic != AppInfo.MAGIC) return false
        if (p.version != AppInfo.PROTOCOL_VERSION) return false
        if (p.ts <= 0 || p.exp <= 0) return false
        return now - p.ts <= p.exp * 1000
    }

    /** 是否已过期 */
    fun isExpired(p: PairingInfo, now: Long = nowEpochMillis()): Boolean =
        now - p.ts > p.exp * 1000
}

/**
 * 配对码：格式 `DDDD-LLLC`，如 `5689-ABCD`。
 *
 * - 查找令牌而非数据载体（仅 ~20bit 信息量，装不下公钥）
 * - 字符集 Crockford 变体（去 I/L/O/U），尾位为校验字符，防误输
 * - 密码学安全随机，一次性使用
 */
object PairingCode {

    /** Crockford 32 字符集（去 I、L、O、U） */
    const val CHARSET: String = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** 生成一个配对码（数字位限定 0-9，字母位限定 A-Z 去混淆区，尾位为校验） */
    fun generate(): String {
        val rand = CryptoEngine.randomBytes(8)
        val digits = StringBuilder(4)
        val letters = StringBuilder(3)
        for (i in 0 until 4) digits.append(CHARSET[(rand[i].toInt() and 0xFF) % 10])        // 0..9 数字
        for (i in 4 until 7) letters.append(CHARSET[10 + ((rand[i].toInt() and 0xFF) % 22)]) // 10..31 字母
        val body = digits.toString() + letters.toString()
        val check = checkChar(body)
        return "${digits}-${letters}$check"
    }

    /** 校验位：对前 7 字符加权求和 mod 32 */
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

    /** 校验完整配对码（含校验位），接受忽略大小写与分隔符 */
    fun validate(code: String): Boolean {
        val normalized = normalize(code)
        if (normalized.length != 8) return false
        if (normalized[0] !in '0'..'9' || normalized[1] !in '0'..'9' ||
            normalized[2] !in '0'..'9' || normalized[3] !in '0'..'9') return false
        val body = normalized.substring(0, 7)
        return checkChar(body) == normalized[7]
    }

    /** 标准化：去分隔符、转大写 */
    fun normalize(code: String): String =
        code.replace("-", "").replace(" ", "").uppercase()
}

/**
 * 配对管理器：
 * - 二维码：issue() 生成一次性配对信息；consume() 校验过期与防重
 * - 配对码：registerCode() 本地登记待查找配对；lookupCode() 供输入方换取
 * - 限流：错误输入指数退避，防爆破
 */
class PairingManager(private val now: () -> Long = ::nowEpochMillis) {

    /** 已消费 key（sessionId:nonce），防止重扫/重输 */
    private val consumed = HashSet<String>()

    /** 配对码 → 配对信息（本地待查找表，配对成功即移除） */
    private val codeTable = HashMap<String, PairingInfo>()

    /** 限流状态：key(deviceId|ip) → 失败次数 */
    private val failCount = HashMap<String, Int>()
    private val failWindowStart = HashMap<String, Long>()

    /**
     * 发起一次配对，生成一次性配对信息。
     * @param publicKey 本端 X25519 公钥（由握手层生成的真实密钥对）
     */
    fun issue(deviceId: String, publicKey: ByteArray, candidates: List<Candidate> = emptyList()): PairingInfo {
        val sessionId = CryptoEngine.randomBytes(16)
        val nonce = CryptoEngine.randomBytes(8)
        return PairingInfo(
            sessionId = base64(sessionId),
            deviceId = deviceId,
            publicKey = com.juanlink.core.crypto.KeyDerivation.encodePublicKey(publicKey),
            ts = now(),
            nonce = base64(nonce),
            candidates = candidates,
        )
    }

    /**
     * 消费一个二维码 payload。
     * @return 校验通过且未被消费则返回 PairingInfo 并标记消费；否则 null
     */
    fun consume(raw: String): PairingInfo? {
        val p = runCatching { PairingCodec.fromQrPayload(raw) }.getOrNull() ?: return null
        if (!PairingCodec.isValid(p, now())) return null
        val key = "${p.sessionId}:${p.nonce}"
        if (!consumed.add(key)) return null // 已消费，防重复连接
        return p
    }

    /** 为一次配对登记配对码（持有方），返回生成的码 */
    fun registerCode(info: PairingInfo): String {
        val code = PairingCode.generate()
        codeTable[PairingCode.normalize(code)] = info
        return code
    }

    /** 输入方用配对码换取配对信息；带限流 */
    fun lookupCode(code: String): PairingInfo? {
        val norm = PairingCode.normalize(code)
        if (!PairingCode.validate(norm)) return null
        if (isRateLimited(norm)) return null
        val info = codeTable[norm] ?: run {
            registerFailure(norm)
            return null
        }
        codeTable.remove(norm)
        return info
    }

    private fun isRateLimited(key: String): Boolean {
        val start = failWindowStart[key] ?: 0L
        if (now() - start > 60_000L) {
            failCount.remove(key)
            failWindowStart.remove(key)
            return false
        }
        return (failCount[key] ?: 0) >= 5
    }

    private fun registerFailure(key: String) {
        failCount[key] = (failCount[key] ?: 0) + 1
        if (failWindowStart[key] == null) failWindowStart[key] = now()
    }

    private fun base64(bytes: ByteArray): String = com.juanlink.core.util.Base64Url.encode(bytes)
}

package com.juanlink.core.util

/**
 * Base64 URL 安全编解码（RFC 4648 §5），纯 Kotlin 实现，全平台一致。
 * - 字符集：A-Z a-z 0-9 - _（替代 + /）
 * - 无填充（padded = false），常用于 URL/二维码 payload
 * - 解码时宽容处理：接受 48 字节数据时长度对 4 对齐或带填充均可
 */
object Base64Url {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else -1
            sb.append(ALPHABET[b0 ushr 2])
            sb.append(ALPHABET[((b0 shl 4) or (if (b1 >= 0) b1 ushr 4 else 0)) and 0x3F])
            if (b1 >= 0) sb.append(ALPHABET[((b1 shl 2) or (if (b2 >= 0) b2 ushr 6 else 0)) and 0x3F])
            if (b2 >= 0) sb.append(ALPHABET[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    fun decode(text: String): ByteArray {
        val s = text.trim()
        if (s.isEmpty()) return ByteArray(0)
        // 宽容：若含标准填充则剥离
        var body = s
        while (body.endsWith("=")) {
            body = body.dropLast(1)
        }
        val out = ArrayList<Byte>(body.length * 3 / 4)
        var buf = 0
        var bits = 0
        for (c in body) {
            val v = valueOf(c)
            if (v < 0) throw IllegalArgumentException("非法 Base64Url 字符: '$c'")
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buf ushr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    private fun valueOf(c: Char): Int {
        val idx = ALPHABET.indexOf(c)
        return if (idx >= 0) idx else -1
    }
}

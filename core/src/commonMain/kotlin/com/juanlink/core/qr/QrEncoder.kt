package com.juanlink.core.qr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 纯 Kotlin QR 码编码器（ISO/IEC 18004，字节模式，版本 1..14）。
 *
 * 零第三方依赖、全平台一致。完整实现：GF(256) Reed-Solomon 纠错、
 * 功能图形（探测/时序/对齐/格式/版本）、Z 形数据摆放、8 掩码评估选优。
 *
 * 仅实现字节模式（应用场景为 UTF-8 JSON 负载），容量以 RS 块表为准。
 */
object QrEncoder {

    enum class EcLevel(val formatBits: Int, val ord: Int) {
        L(0b01, 0), M(0b00, 1), Q(0b11, 2), H(0b10, 3)
    }

    /** 编码结果：模块矩阵（true=暗色） */
    data class QrMatrix(val size: Int, val version: Int, val modules: BooleanArray) {
        operator fun get(x: Int, y: Int): Boolean = modules[y * size + x]
        fun set(x: Int, y: Int, v: Boolean) {
            modules[y * size + x] = v
        }
    }

    /**
     * 编码字符串为 QR 矩阵。
     *
     * @param ecLevel 纠错等级，默认 M
     * @param minVersion 允许的最小版本（可强制升版容纳数据）
     */
    fun encode(text: String, ecLevel: EcLevel = EcLevel.M, minVersion: Int = 1): QrMatrix {
        require(text.isNotEmpty()) { "内容不能为空" }
        require(minVersion in 1..14) { "版本范围 1..14" }
        val data = text.encodeToByteArray()

        // 优先使用请求等级；装不下时自动降级到 L（纠错更少但容量更大）
        val levels = listOf(ecLevel) + if (ecLevel != EcLevel.L) listOf(EcLevel.L) else emptyList()
        for (level in levels) {
            for (v in minVersion..14) {
                if (data.size <= byteCapacity(v, level)) {
                    return buildVersion(data, v, level)
                }
            }
        }
        throw IllegalArgumentException("数据过长：${data.size} 字节超过版本14-L 容量")
    }

    /** 字节模式容量（字节）：扣去 4 位模式指示符与 8/16 位字符计数 */
    private fun byteCapacity(version: Int, level: EcLevel): Int {
        val countBits = if (version <= 9) 8 else 16
        val overheadBits = 4 + countBits
        return (rsDataCodewords(version, level) * 8 - overheadBits) / 8
    }

    // ---------------------------------------------------------------- RS 块表
    // 每版本 × 每等级：Pair(每块纠错码字数, List(Pair(块数, 每块数据码字数)))
    private val RS_TABLE: Map<Int, Map<EcLevel, Pair<Int, List<Pair<Int, Int>>>>> = run {
        fun rs(v: Int, ec: Int, blocks: List<Pair<Int, Int>>) = ec to blocks
        val m = mutableMapOf<Int, Map<EcLevel, Pair<Int, List<Pair<Int, Int>>>>>()
        m[1] = mapOf(EcLevel.L to rs(1, 7, listOf(1 to 19)), EcLevel.M to rs(1, 10, listOf(1 to 16)), EcLevel.Q to rs(1, 13, listOf(1 to 13)), EcLevel.H to rs(1, 17, listOf(1 to 9)))
        m[2] = mapOf(EcLevel.L to rs(2, 10, listOf(1 to 34)), EcLevel.M to rs(2, 16, listOf(1 to 28)), EcLevel.Q to rs(2, 22, listOf(1 to 22)), EcLevel.H to rs(2, 28, listOf(1 to 16)))
        m[3] = mapOf(EcLevel.L to rs(3, 15, listOf(1 to 55)), EcLevel.M to rs(3, 26, listOf(1 to 44)), EcLevel.Q to rs(3, 18, listOf(2 to 17)), EcLevel.H to rs(3, 22, listOf(2 to 13)))
        m[4] = mapOf(EcLevel.L to rs(4, 20, listOf(1 to 80)), EcLevel.M to rs(4, 18, listOf(2 to 32)), EcLevel.Q to rs(4, 26, listOf(2 to 24)), EcLevel.H to rs(4, 16, listOf(4 to 9)))
        m[5] = mapOf(EcLevel.L to rs(5, 26, listOf(1 to 108)), EcLevel.M to rs(5, 24, listOf(2 to 43)), EcLevel.Q to rs(5, 18, listOf(2 to 15, 2 to 16)), EcLevel.H to rs(5, 22, listOf(2 to 11, 2 to 12)))
        m[6] = mapOf(EcLevel.L to rs(6, 18, listOf(2 to 68)), EcLevel.M to rs(6, 16, listOf(4 to 27)), EcLevel.Q to rs(6, 24, listOf(4 to 19)), EcLevel.H to rs(6, 28, listOf(4 to 15)))
        m[7] = mapOf(EcLevel.L to rs(7, 20, listOf(2 to 78)), EcLevel.M to rs(7, 18, listOf(4 to 31)), EcLevel.Q to rs(7, 18, listOf(2 to 14, 4 to 15)), EcLevel.H to rs(7, 26, listOf(4 to 13, 1 to 14)))
        m[8] = mapOf(EcLevel.L to rs(8, 24, listOf(2 to 97)), EcLevel.M to rs(8, 22, listOf(2 to 38, 2 to 39)), EcLevel.Q to rs(8, 22, listOf(4 to 18, 2 to 19)), EcLevel.H to rs(8, 26, listOf(4 to 14, 2 to 15)))
        m[9] = mapOf(EcLevel.L to rs(9, 30, listOf(2 to 116)), EcLevel.M to rs(9, 22, listOf(3 to 36, 2 to 37)), EcLevel.Q to rs(9, 20, listOf(4 to 16, 4 to 17)), EcLevel.H to rs(9, 24, listOf(4 to 12, 4 to 13)))
        m[10] = mapOf(EcLevel.L to rs(10, 18, listOf(2 to 68, 2 to 69)), EcLevel.M to rs(10, 26, listOf(4 to 43, 1 to 44)), EcLevel.Q to rs(10, 24, listOf(6 to 19, 2 to 20)), EcLevel.H to rs(10, 28, listOf(6 to 15, 2 to 16)))
        m[11] = mapOf(EcLevel.L to rs(11, 20, listOf(4 to 81)), EcLevel.M to rs(11, 30, listOf(1 to 50, 4 to 51)), EcLevel.Q to rs(11, 28, listOf(4 to 22, 4 to 23)), EcLevel.H to rs(11, 24, listOf(3 to 12, 8 to 13)))
        m[12] = mapOf(EcLevel.L to rs(12, 24, listOf(2 to 92, 2 to 93)), EcLevel.M to rs(12, 22, listOf(6 to 36, 2 to 37)), EcLevel.Q to rs(12, 26, listOf(4 to 20, 6 to 21)), EcLevel.H to rs(12, 28, listOf(7 to 14, 4 to 15)))
        m[13] = mapOf(EcLevel.L to rs(13, 26, listOf(4 to 107)), EcLevel.M to rs(13, 22, listOf(8 to 37, 1 to 38)), EcLevel.Q to rs(13, 24, listOf(8 to 20, 4 to 21)), EcLevel.H to rs(13, 22, listOf(12 to 11, 4 to 12)))
        m[14] = mapOf(EcLevel.L to rs(14, 30, listOf(3 to 115, 1 to 116)), EcLevel.M to rs(14, 24, listOf(4 to 40, 5 to 41)), EcLevel.Q to rs(14, 20, listOf(11 to 16, 5 to 17)), EcLevel.H to rs(14, 24, listOf(11 to 12, 5 to 13)))
        m
    }

    // 对齐图形中心坐标（每版本）
    private val ALIGNMENT: Map<Int, IntArray> = mapOf(
        1 to intArrayOf(), 2 to intArrayOf(6, 18), 3 to intArrayOf(6, 22), 4 to intArrayOf(6, 26),
        5 to intArrayOf(6, 30), 6 to intArrayOf(6, 34), 7 to intArrayOf(6, 22, 38), 8 to intArrayOf(6, 24, 42),
        9 to intArrayOf(6, 26, 46), 10 to intArrayOf(6, 28, 50), 11 to intArrayOf(6, 30, 54),
        12 to intArrayOf(6, 32, 58), 13 to intArrayOf(6, 34, 62), 14 to intArrayOf(6, 26, 46, 66),
    )

    // ------------------------------------------------------------------ GF(256)
    private const val GF_PRIMITIVE = 0x11D

    private class Gf256 private constructor() {
        val exp = IntArray(512)
        val log = IntArray(256)

        init {
            var x = 1
            for (i in 0 until 255) {
                exp[i] = x
                log[x] = i
                x = x shl 1
                if (x and 0x100 != 0) x = x xor GF_PRIMITIVE
            }
            for (i in 255 until 512) exp[i] = exp[i - 255]
        }

        fun mul(a: Int, b: Int): Int {
            if (a == 0 || b == 0) return 0
            return exp[log[a] + log[b]]
        }

        companion object {
            val INSTANCE = Gf256()
        }
    }

    /** 生成多项式：∏(x - α^i)，i in 0 until degree，返回系数（低次在前） */
    private fun generatorPoly(degree: Int): IntArray {
        val gf = Gf256.INSTANCE
        var poly = intArrayOf(1) // 常数 1
        for (i in 0 until degree) {
            // poly *= (x + α^i)
            val alpha = gf.exp[i]
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor gf.mul(poly[j], alpha)
                next[j + 1] = next[j + 1] xor poly[j]
            }
            poly = next
        }
        return poly
    }

    /**
     * 计算 RS 纠错码字（degree 个）。
     *
     * 移位寄存器 rem[i] = 余式 x^(degree-1-i) 的系数（高位在前 = 传输顺序）。
     * 生成多项式按「常数→首项」存储（gen[0]=常数，gen[degree]=1）。
     * 每次反馈：rem[i] ^= factor·gen[degree-1-i]（i in 0..degree-2），
     * rem[degree-1] ^= factor·gen[0]（常数项），首项 gen[degree]=1 隐含消去。
     */
    private fun rsEncode(data: IntArray, degree: Int): IntArray {
        val gf = Gf256.INSTANCE
        val gen = generatorPoly(degree)
        val rem = IntArray(degree)
        for (b in data) {
            val factor = b xor rem[0]
            for (i in 0 until degree - 1) {
                rem[i] = rem[i + 1] xor gf.mul(gen[degree - 1 - i], factor)
            }
            rem[degree - 1] = gf.mul(gen[0], factor)
        }
        return rem
    }

    // ------------------------------------------------------------------ 构建
    private fun rsDataCodewords(version: Int, level: EcLevel): Int {
        val (_, blocks) = RS_TABLE.getValue(version).getValue(level)
        return blocks.sumOf { it.first * it.second }
    }

    private fun buildVersion(data: ByteArray, version: Int, level: EcLevel): QrMatrix {
        val (ecCount, blocks) = RS_TABLE.getValue(version).getValue(level)
        val dataCodewordsTotal = blocks.sumOf { it.first * it.second }

        // 1) 构造数据位流（模式 + 计数 + 数据 + 终止/填充）
        val stream = BitWriter()
        stream.appendBits(0b0100, 4)                                  // 字节模式指示符
        val countBits = if (version <= 9) 8 else 16
        stream.appendBits(data.size, countBits)                       // 字符计数
        for (b in data) stream.appendBits(b.toInt(), 8)

        // 终止符（最多 4 个 0）+ 补齐到字节边界
        var remainingBits = dataCodewordsTotal * 8 - stream.length
        stream.appendBits(0, minOf(4, remainingBits))
        remainingBits = dataCodewordsTotal * 8 - stream.length
        if (remainingBits > 0 && remainingBits < 8) {
            stream.appendBits(0, remainingBits)
        }
        // 填充码字 0xEC / 0x11 交替
        val dataCodewords = stream.toBytes(dataCodewordsTotal)

        // 2) 分块 + RS 纠错 + 交错
        val blockData = ArrayList<IntArray>()
        val blockEc = ArrayList<IntArray>()
        var offset = 0
        for ((count, len) in blocks) {
            for (i in 0 until count) {
                val seg = dataCodewords.copyOfRange(offset, offset + len)
                    .map { it.toInt() and 0xFF }
                    .toIntArray()
                offset += len
                blockData.add(seg)
                blockEc.add(rsEncode(seg, ecCount))
            }
        }
        val interleaved = interleave(blockData, blockEc)

        // 3) 生成功能图形 + 放置数据 + 掩码 + 格式/版本
        val size = 17 + 4 * version
        val matrix = QrMatrix(size, version, BooleanArray(size * size))
        placeFunctionPatterns(matrix, version)
        placeData(matrix, interleaved, version)
        applyMaskAndFormat(matrix, version, level)
        return matrix
    }

    private fun interleave(dataBlocks: List<IntArray>, ecBlocks: List<IntArray>): IntArray {
        val maxData = dataBlocks.maxOf { it.size }
        val maxEc = ecBlocks.maxOf { it.size }
        val out = ArrayList<Int>(dataBlocks.size * maxData + ecBlocks.size * maxEc)
        for (i in 0 until maxData) {
            for (b in dataBlocks) if (i < b.size) out.add(b[i])
        }
        for (i in 0 until maxEc) {
            for (b in ecBlocks) if (i < b.size) out.add(b[i])
        }
        return out.toIntArray()
    }

    // ------------------------------------------------------------------ 图形摆放
    private fun placeFunctionPatterns(m: QrMatrix, version: Int) {
        val size = m.size

        fun setFinder(cx: Int, cy: Int) {
            for (dy in -3..3) for (dx in -3..3) {
                // 7x7 探测图形：外框(d=3)暗、环(d=2)亮、中心 3x3(d<=1)暗
                val d = maxOf(abs(dx), abs(dy))
                val dark = d != 2
                m.set(cx + dx, cy + dy, dark)
            }
        }
        setFinder(3, 3)
        setFinder(size - 4, 3)
        setFinder(3, size - 4)

        // 分隔符
        for (i in 0 until 8) {
            m.set(i, 7, false); m.set(7, i, false)
            m.set(size - 1 - i, 7, false); m.set(7, size - 1 - i, false)
            m.set(i, size - 8, false); m.set(size - 8, i, false)
        }

        // 时序图形
        for (i in 8 until size - 8) {
            val dark = i % 2 == 0
            m.set(i, 6, dark)
            m.set(6, i, dark)
        }

        // 对齐图形
        val aligns = ALIGNMENT.getValue(version)
        for (cy in aligns) for (cx in aligns) {
            if (cx == 6 && cy == 6) continue // 跳过左上探测
            if (cx == 6 && cy == size - 7) continue // 跳过左下
            if (cx == size - 7 && cy == 6) continue // 跳过右上
            for (dy in -2..2) for (dx in -2..2) {
                val dark = maxOf(abs(dx), abs(dy)) != 1
                m.set(cx + dx, cy + dy, dark)
            }
        }

        // 暗色模块 (7, size-8)
        m.set(8, size - 8, true)
    }

    private fun placeData(m: QrMatrix, data: IntArray, version: Int) {
        val size = m.size
        var bitIndex = 0
        var upward = true
        var col = size - 1
        while (col > 0) {
            if (col == 6) col-- // 跳过时序列
            for (i in 0 until size) {
                val row = if (upward) size - 1 - i else i
                for (j in 0..1) {
                    val x = col - j
                    if (x < 0) continue
                    // 功能图形（含浅色模块）一律跳过，防止数据覆盖
                    if (isFunctionModule(m, x, row, version)) continue
                    val bit = if (bitIndex < data.size * 8) {
                        (data[bitIndex ushr 3] ushr (7 - (bitIndex and 7))) and 1
                    } else 0
                    m.set(x, row, bit == 1)
                    bitIndex++
                }
            }
            col -= 2
            upward = !upward
        }
    }

    // ------------------------------------------------------------------ 掩码与格式
    private fun maskFn(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
        else -> false
    }

    private fun applyMaskAndFormat(m: QrMatrix, version: Int, level: EcLevel) {
        val size = m.size
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        var bestModules: BooleanArray? = null

        for (mask in 0..7) {
            val candidate = BooleanArray(size * size)
            for (y in 0 until size) for (x in 0 until size) {
                val v = m[x, y]
                candidate[y * size + x] = if (isFunctionModule(m, x, y, version)) v else v xor maskFn(mask, x, y)
            }
            val score = penaltyScore(candidate, size)
            if (score < bestScore) {
                bestScore = score
                bestMask = mask
                bestModules = candidate
            }
        }

        // 写入选中的掩码模块
        for (i in bestModules!!.indices) m.modules[i] = bestModules[i]

        // 格式信息
        val format = ((level.formatBits shl 3) or bestMask)
        val formatBits = bchFormat(format)
        placeFormatInfo(m, formatBits)
        if (version >= 7) {
            val versionBits = bchVersion(version)
            placeVersionInfo(m, versionBits)
        }
    }

    /** 判断某模块是否为功能模块（探测/分隔/时序/对齐/暗/格式/版本占位） */
    private fun isFunctionModule(m: QrMatrix, x: Int, y: Int, version: Int): Boolean {
        val size = m.size
        val inFinder = (x <= 8 && y <= 8) || (x >= size - 8 && y <= 8) || (x <= 8 && y >= size - 8)
        if (inFinder) return true
        if (x == 6 || y == 6) return true
        // 对齐图形
        val aligns = ALIGNMENT.getValue(version)
        for (cy in aligns) for (cx in aligns) {
            if (abs(x - cx) <= 2 && abs(y - cy) <= 2) {
                // 排除与三个探测重叠的区域（探测已被 inFinder 覆盖）
                val topLeftOverlap = cx == 6 && cy == 6
                val topRightOverlap = cx == size - 7 && cy == 6
                val bottomLeftOverlap = cx == 6 && cy == size - 7
                if (!topLeftOverlap && !topRightOverlap && !bottomLeftOverlap) return true
            }
        }
        if (version >= 7) {
            // 版本信息占位：左上两角 6x3 与 3x6
            val inVerInfo = (x in size - 11..size - 9 && y <= 5) || (x <= 5 && y in size - 11..size - 9)
            if (inVerInfo) return true
        }
        return false
    }

    private fun bchFormat(data: Int): Int {
        // data = 5 bits，BCH(15,5)，生成多项式 0x537
        var d = data shl 10
        val gen = 0x537
        for (i in 14 downTo 10) {
            if ((d ushr i) and 1 == 1) d = d xor (gen shl (i - 10))
        }
        return ((data shl 10) or d) xor 0x5412
    }

    private fun placeFormatInfo(m: QrMatrix, format: Int) {
        val size = m.size
        // Copy1：左上角（列 8 顶部 = bits 0..5，(8,7)=b6，(8,8)=b7，(7,8)=b8；行 8 左侧 = bits 9..14）
        for (i in 0..5) {
            m.set(8, i, ((format ushr i) and 1) == 1)
        }
        m.set(8, 7, ((format ushr 6) and 1) == 1)
        m.set(8, 8, ((format ushr 7) and 1) == 1)
        m.set(7, 8, ((format ushr 8) and 1) == 1)
        for (i in 9..14) {
            m.set(14 - i, 8, ((format ushr i) and 1) == 1)
        }
        // Copy2：顶右（行 8，列 size-1..size-8 = bits 14..7）+ 左下（列 8，行 size-1..size-7 = bits 6..0）
        for (i in 7..14) {
            m.set(size - 15 + i, 8, ((format ushr i) and 1) == 1)
        }
        for (i in 0..6) {
            m.set(8, size - 1 - i, ((format ushr i) and 1) == 1)
        }
        // 暗模块 (8, size-8)：placeFunctionPatterns 已置暗，勿覆盖
        m.set(8, size - 8, true)
    }

    private fun bchVersion(version: Int): Int {
        var d = version shl 12
        val gen = 0x1F25
        for (i in 17 downTo 12) {
            if ((d ushr i) and 1 == 1) d = d xor (gen shl (i - 12))
        }
        return (version shl 12) or d
    }

    private fun placeVersionInfo(m: QrMatrix, bits: Int) {
        val size = m.size
        for (i in 0..17) {
            val bit = ((bits ushr i) and 1) == 1
            val a = size - 11 + i % 3
            val b = i / 3
            m.set(a, b, bit)          // 左上角 6x3
            m.set(b, a, bit)          // 左下 3x6
        }
    }

    // ------------------------------------------------------------------ 罚分
    private fun penaltyScore(modules: BooleanArray, size: Int): Int {
        var score = 0
        // 规则1：行/列中连续同色 >=5
        fun runPenaltyLine(count: Int): Int {
            if (count < 5) return 0
            return 3 + (count - 5)
        }
        // 行
        for (y in 0 until size) {
            var run = 0
            var prev = -1
            for (x in 0 until size) {
                val v = if (modules[y * size + x]) 1 else 0
                if (v == prev) run++ else { score += runPenaltyLine(run); run = 1; prev = v }
            }
            score += runPenaltyLine(run)
        }
        // 列
        for (x in 0 until size) {
            var run = 0
            var prev = -1
            for (y in 0 until size) {
                val v = if (modules[y * size + x]) 1 else 0
                if (v == prev) run++ else { score += runPenaltyLine(run); run = 1; prev = v }
            }
            score += runPenaltyLine(run)
        }
        // 规则2：2x2 同色块
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = modules[y * size + x]
            if (modules[y * size + x + 1] == c && modules[(y + 1) * size + x] == c && modules[(y + 1) * size + x + 1] == c) score += 3
        }
        // 规则3：1:1:3:1:1 模式（带 4 亮边）
        for (y in 0 until size) {
            for (x in 0 until size - 6) {
                val p = BooleanArray(7)
                for (i in 0..6) p[i] = modules[y * size + x + i]
                if (p[0] && !p[1] && p[2] && p[3] && p[4] && !p[5] && p[6]) {
                    if (x - 4 < 0 || (!modules[y * size + x - 4] && !modules[y * size + x - 3] && !modules[y * size + x - 2] && !modules[y * size + x - 1])) score += 40
                    if (x + 11 >= size || (!modules[y * size + x + 7] && !modules[y * size + x + 8] && !modules[y * size + x + 9] && !modules[y * size + x + 10])) score += 40
                }
            }
        }
        for (x in 0 until size) {
            for (y in 0 until size - 6) {
                val p = BooleanArray(7)
                for (i in 0..6) p[i] = modules[(y + i) * size + x]
                if (p[0] && !p[1] && p[2] && p[3] && p[4] && !p[5] && p[6]) {
                    if (y - 4 < 0 || (!modules[(y - 4) * size + x] && !modules[(y - 3) * size + x] && !modules[(y - 2) * size + x] && !modules[(y - 1) * size + x])) score += 40
                    if (y + 11 >= size || (!modules[(y + 7) * size + x] && !modules[(y + 8) * size + x] && !modules[(y + 9) * size + x] && !modules[(y + 10) * size + x])) score += 40
                }
            }
        }
        // 规则4：暗色比例
        var dark = 0
        for (v in modules) if (v) dark++
        val total = size * size
        val percent = dark * 100 / total
        val prev5 = percent / 5
        val next5 = (percent + 4) / 5
        val deviation = minOf(abs(prev5 - 50), abs(next5 - 50))
        score += deviation / 5 * 10
        return score
    }
}

/** 紧凑位流写入器（纯 Kotlin，替代 JVM BitSet） */
private class BitWriter {
    private val data = ArrayList<Int>()
    private var bitCount = 0

    val length: Int get() = bitCount

    fun appendBits(value: Int, count: Int) {
        for (i in count - 1 downTo 0) {
            data.add((value ushr i) and 1)
            bitCount++
        }
    }

    fun toBytes(totalBytes: Int): ByteArray {
        val out = ByteArray(totalBytes)
        var byte = 0
        var bits = 0
        var idx = 0
        for (bit in data) {
            byte = (byte shl 1) or bit
            bits++
            if (bits == 8) {
                out[idx] = byte.toByte()
                byte = 0
                bits = 0
                idx++
            }
        }
        // 剩余位置填充分段码字 0xEC/0x11 交替
        var pad = 0xEC
        while (idx < totalBytes) {
            out[idx] = pad.toByte()
            pad = if (pad == 0xEC) 0x11 else 0xEC
            idx++
        }
        return out
    }
}

package com.fluxdown.core.protocol

/** 二维码矩阵：[size] × [size] 个模块，`true` = 深色。无静区（绘制方自行留白）。 */
class QrMatrix internal constructor(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && dark[y * size + x]
}

/**
 * 最小 QR Model 2 编码器：字节模式（UTF-8）、纠错级别 M、自动选最小版本（1–40）、自动选最优掩码。
 * 算法与常量表对齐 ISO/IEC 18004（功能图形 / 格式信息 / 版本信息 / RS(GF256,0x11D) 纠错 / 四项罚分）。
 * 本地编码，不解析也不请求载荷里的任何 URL。
 */
object QrEncoder {
    private const val MIN_VERSION = 1
    private const val MAX_VERSION = 40

    /** 纠错级别 M 的格式位（`00`）。 */
    private const val ECC_FORMAT_BITS = 0

    // 下标 = 版本号（0 位占位）；纠错级别 M。
    private val ECC_CODEWORDS_PER_BLOCK = intArrayOf(
        -1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26,
        26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28,
    )
    private val NUM_ERROR_CORRECTION_BLOCKS = intArrayOf(
        -1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14,
        16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49,
    )

    /** 字节模式字符计数位宽（版本 1–9 为 8，其余 16）。 */
    private fun countBits(version: Int): Int = if (version <= 9) 8 else 16

    /** [version] 的数据码字数（不含纠错）。 */
    internal fun numDataCodewords(version: Int): Int =
        numRawDataModules(version) / 8 - ECC_CODEWORDS_PER_BLOCK[version] * NUM_ERROR_CORRECTION_BLOCKS[version]

    /** 字节模式下能容纳的最大字节数（版本 40-M）。 */
    val maxBytes: Int = (numDataCodewords(MAX_VERSION) * 8 - 4 - countBits(MAX_VERSION)) / 8

    /** 编码 [text]（UTF-8 字节）；空串 / 超出版本 40-M 容量返回 null。 */
    fun encode(text: String): QrMatrix? {
        if (text.isEmpty()) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        var version = MIN_VERSION
        while (true) {
            val capacityBits = numDataCodewords(version) * 8
            val used = 4 + countBits(version) + bytes.size * 8
            if (used <= capacityBits) break
            if (version >= MAX_VERSION) return null
            version++
        }
        val capacityBits = numDataCodewords(version) * 8

        val bits = BitBuffer()
        bits.append(0x4, 4)
        bits.append(bytes.size, countBits(version))
        for (b in bytes) bits.append(b.toInt() and 0xFF, 8)
        bits.append(0, minOf(4, capacityBits - bits.length))
        bits.append(0, (8 - bits.length % 8) % 8)
        var pad = 0xEC
        while (bits.length < capacityBits) {
            bits.append(pad, 8)
            pad = pad xor (0xEC xor 0x11)
        }
        val data = ByteArray(bits.length / 8)
        for (i in 0 until bits.length) {
            if (bits[i]) data[i ushr 3] = (data[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
        }
        return Builder(version, addEccAndInterleave(data, version)).build()
    }

    // ───────────── 纠错 ─────────────

    private fun addEccAndInterleave(data: ByteArray, version: Int): ByteArray {
        val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[version]
        val blockEccLen = ECC_CODEWORDS_PER_BLOCK[version]
        val rawCodewords = numRawDataModules(version) / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks

        val blocks = ArrayList<ByteArray>(numBlocks)
        val divisor = reedSolomonDivisor(blockEccLen)
        var k = 0
        for (i in 0 until numBlocks) {
            val datLen = shortBlockLen - blockEccLen + if (i < numShortBlocks) 0 else 1
            val dat = data.copyOfRange(k, k + datLen)
            k += datLen
            val block = dat.copyOf(shortBlockLen + 1)
            val ecc = reedSolomonRemainder(dat, divisor)
            System.arraycopy(ecc, 0, block, block.size - blockEccLen, ecc.size)
            blocks += block
        }
        val result = ByteArray(rawCodewords)
        var r = 0
        for (i in 0 until blocks[0].size) {
            for (j in blocks.indices) {
                // 短块在数据末尾有一个占位字节，交织时跳过
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[r++] = blocks[j][i]
                }
            }
        }
        return result
    }

    private fun reedSolomonDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in result.indices) {
                result[j] = gfMultiply(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < result.size) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
        val result = ByteArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) {
                result[i] = (result[i].toInt() xor gfMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }

    /** 数据码字 → [eccLen] 个纠错码字（测试用入口）。 */
    internal fun eccFor(data: ByteArray, eccLen: Int): ByteArray = reedSolomonRemainder(data, reedSolomonDivisor(eccLen))

    /** GF(2^8)（本原多项式 0x11D）乘法。 */
    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    /** 版本的数据模块总数（含纠错，不含功能图形）。 */
    internal fun numRawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    private class BitBuffer {
        private var bits = BooleanArray(256)
        var length = 0
            private set

        operator fun get(i: Int): Boolean = bits[i]

        fun append(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                if (length == bits.size) bits = bits.copyOf(bits.size * 2)
                bits[length++] = ((value ushr i) and 1) != 0
            }
        }
    }

    // ───────────── 矩阵构造 ─────────────

    private class Builder(private val version: Int, private val codewords: ByteArray) {
        private val size = version * 4 + 17
        private val modules = Array(size) { BooleanArray(size) }
        private val isFunction = Array(size) { BooleanArray(size) }

        fun build(): QrMatrix {
            drawFunctionPatterns()
            drawCodewords()
            var bestMask = 0
            var minPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask)
                drawFormatBits(mask)
                val penalty = penaltyScore()
                if (penalty < minPenalty) {
                    bestMask = mask
                    minPenalty = penalty
                }
                applyMask(mask) // 异或自逆：撤销
            }
            applyMask(bestMask)
            drawFormatBits(bestMask)
            val flat = BooleanArray(size * size)
            for (y in 0 until size) for (x in 0 until size) flat[y * size + x] = modules[y][x]
            return QrMatrix(size, flat)
        }

        private fun setFunction(x: Int, y: Int, dark: Boolean) {
            modules[y][x] = dark
            isFunction[y][x] = true
        }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) {
                setFunction(6, i, i % 2 == 0)
                setFunction(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val pos = alignmentPositions()
            val n = pos.size
            for (i in 0 until n) {
                for (j in 0 until n) {
                    if (!((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0))) {
                        drawAlignment(pos[i], pos[j])
                    }
                }
            }
            drawFormatBits(0) // 占位，之后按实际掩码覆盖
            drawVersion()
        }

        private fun drawFormatBits(mask: Int) {
            val data = (ECC_FORMAT_BITS shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            for (i in 0..5) setFunction(8, i, bit(bits, i))
            setFunction(8, 7, bit(bits, 6))
            setFunction(8, 8, bit(bits, 7))
            setFunction(7, 8, bit(bits, 8))
            for (i in 9..14) setFunction(14 - i, 8, bit(bits, i))
            for (i in 0..7) setFunction(size - 1 - i, 8, bit(bits, i))
            for (i in 8..14) setFunction(8, size - 15 + i, bit(bits, i))
            setFunction(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val b = bit(bits, i)
                val a = size - 11 + i % 3
                val c = i / 3
                setFunction(a, c, b)
                setFunction(c, a, b)
            }
        }

        private fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = maxOf(Math.abs(dx), Math.abs(dy))
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) setFunction(x, y, dist != 2 && dist != 4)
                }
            }
        }

        private fun drawAlignment(cx: Int, cy: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) {
                    setFunction(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
                }
            }
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val step = if (version == 32) 26 else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = size - 7
            for (i in numAlign - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        private fun drawCodewords() {
            var i = 0
            val totalBits = codewords.size * 8
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    for (j in 0..1) {
                        val x = right - j
                        val upward = ((right + 1) and 2) == 0
                        val y = if (upward) size - 1 - vert else vert
                        if (!isFunction[y][x] && i < totalBits) {
                            modules[y][x] = bit(codewords[i ushr 3].toInt(), 7 - (i and 7))
                            i++
                        }
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val invert = when (mask) {
                        0 -> (x + y) % 2 == 0
                        1 -> y % 2 == 0
                        2 -> x % 3 == 0
                        3 -> (x + y) % 3 == 0
                        4 -> (x / 3 + y / 2) % 2 == 0
                        5 -> x * y % 2 + x * y % 3 == 0
                        6 -> (x * y % 2 + x * y % 3) % 2 == 0
                        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    }
                    if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
                }
            }
        }

        private fun penaltyScore(): Int {
            var result = 0
            for (y in 0 until size) {
                var runColor = false
                var runLen = 0
                val history = IntArray(7)
                for (x in 0 until size) {
                    if (modules[y][x] == runColor) {
                        runLen++
                        if (runLen == 5) result += PENALTY_N1 else if (runLen > 5) result++
                    } else {
                        addHistory(runLen, history)
                        if (!runColor) result += countPatterns(history) * PENALTY_N3
                        runColor = modules[y][x]
                        runLen = 1
                    }
                }
                result += terminateAndCount(runColor, runLen, history) * PENALTY_N3
            }
            for (x in 0 until size) {
                var runColor = false
                var runLen = 0
                val history = IntArray(7)
                for (y in 0 until size) {
                    if (modules[y][x] == runColor) {
                        runLen++
                        if (runLen == 5) result += PENALTY_N1 else if (runLen > 5) result++
                    } else {
                        addHistory(runLen, history)
                        if (!runColor) result += countPatterns(history) * PENALTY_N3
                        runColor = modules[y][x]
                        runLen = 1
                    }
                }
                result += terminateAndCount(runColor, runLen, history) * PENALTY_N3
            }
            for (y in 0 until size - 1) {
                for (x in 0 until size - 1) {
                    val c = modules[y][x]
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += PENALTY_N2
                }
            }
            var dark = 0
            for (row in modules) for (m in row) if (m) dark++
            val total = size * size
            val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            result += k * PENALTY_N4
            return result
        }

        private fun countPatterns(h: IntArray): Int {
            val n = h[1]
            val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
            return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) +
                (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
        }

        private fun terminateAndCount(runColor: Boolean, runLength: Int, h: IntArray): Int {
            var len = runLength
            if (runColor) {
                addHistory(len, h)
                len = 0
            }
            len += size
            addHistory(len, h)
            return countPatterns(h)
        }

        private fun addHistory(runLength: Int, h: IntArray) {
            var len = runLength
            if (h[0] == 0) len += size
            System.arraycopy(h, 0, h, 1, h.size - 1)
            h[0] = len
        }

        private fun bit(value: Int, i: Int): Boolean = ((value ushr i) and 1) != 0
    }

    private const val PENALTY_N1 = 3
    private const val PENALTY_N2 = 3
    private const val PENALTY_N3 = 40
    private const val PENALTY_N4 = 10
}

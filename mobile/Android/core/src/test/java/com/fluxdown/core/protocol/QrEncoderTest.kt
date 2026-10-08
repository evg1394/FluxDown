package com.fluxdown.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrEncoderTest {
    private fun unsigned(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun reedSolomonMatchesReferenceVector() {
        // ISO 18004 教程常用向量：1-Q（13 数据 + 13 纠错）"HELLO WORLD"。
        val data = unsigned(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236)
        val ecc = QrEncoder.eccFor(data, 13)
        assertEquals(listOf(168, 72, 22, 82, 217, 54, 156, 0, 46, 15, 180, 122, 16), ecc.map { it.toInt() and 0xFF })
    }

    @Test
    fun dataCapacityMatchesSpecTableForLevelM() {
        val expected = intArrayOf(
            16, 28, 44, 64, 86, 108, 124, 154, 182, 216, 254, 290, 334, 365, 415, 453, 507, 563, 627, 669,
            714, 782, 860, 914, 1000, 1062, 1128, 1193, 1267, 1373, 1455, 1541, 1631, 1725, 1812, 1914, 1992, 2102, 2216, 2334,
        )
        for (version in 1..40) assertEquals("version $version", expected[version - 1], QrEncoder.numDataCodewords(version))
        // 字节模式在 40-M 的容量：2331 字节。
        assertEquals(2331, QrEncoder.maxBytes)
    }

    @Test
    fun picksSmallestVersionForPayloadLength() {
        // 1-M：128 位 = 4 + 8 + 8n → n ≤ 14。
        assertEquals(21, QrEncoder.encode("a".repeat(14))!!.size)
        assertEquals(25, QrEncoder.encode("a".repeat(15))!!.size)
        // 版本 10 起字符计数位宽 16：10-M 容量 216 码字 → 4 + 16 + 8n ≤ 1728 → n ≤ 213。
        assertEquals(57, QrEncoder.encode("a".repeat(213))!!.size)
        assertEquals(61, QrEncoder.encode("a".repeat(214))!!.size)
    }

    @Test
    fun rejectsEmptyAndOversizedPayloads() {
        assertNull(QrEncoder.encode(""))
        assertNotNull(QrEncoder.encode("x".repeat(QrEncoder.maxBytes)))
        assertNull(QrEncoder.encode("x".repeat(QrEncoder.maxBytes + 1)))
        // 码点按 UTF-8 字节计：3 字节字符。
        assertNull(QrEncoder.encode("中".repeat(QrEncoder.maxBytes / 3 + 1)))
    }

    @Test
    fun matrixHasFinderTimingAndValidFormatInfo() {
        for (text in listOf("https://example.com/login?x=1", "a".repeat(300), "你好，FluxDown")) {
            val m = QrEncoder.encode(text)!!
            val n = m.size
            // 三个定位图形：7×7 外环深、5×5 次环浅、3×3 中心深。
            for ((ox, oy) in listOf(0 to 0, n - 7 to 0, 0 to n - 7)) {
                for (i in 0 until 7) for (j in 0 until 7) {
                    val ring = maxOf(Math.abs(i - 3), Math.abs(j - 3))
                    assertEquals("finder ($ox,$oy) $i,$j", ring != 2, m[ox + i, oy + j])
                }
            }
            // 定时图形交替。
            for (i in 8 until n - 8) {
                assertEquals(i % 2 == 0, m[i, 6])
                assertEquals(i % 2 == 0, m[6, i])
            }
            // 固定深色模块。
            assertTrue(m[8, n - 8])
            // 格式信息：第一份拷贝，BCH(15,5) 余式为 0，纠错级别位 = M(00)。
            var bits = 0
            for (i in 0..5) if (m[8, i]) bits = bits or (1 shl i)
            if (m[8, 7]) bits = bits or (1 shl 6)
            if (m[8, 8]) bits = bits or (1 shl 7)
            if (m[7, 8]) bits = bits or (1 shl 8)
            for (i in 9..14) if (m[14 - i, 8]) bits = bits or (1 shl i)
            val unmasked = bits xor 0x5412
            var rem = unmasked
            for (i in 14 downTo 10) if ((rem ushr i) and 1 != 0) rem = rem xor (0x537 shl (i - 10))
            assertEquals(0, rem)
            assertEquals(0, (unmasked ushr 13) and 0x3)
            // 第二份拷贝与第一份一致。
            var second = 0
            for (i in 0..7) if (m[n - 1 - i, 8]) second = second or (1 shl i)
            for (i in 8..14) if (m[8, n - 15 + i]) second = second or (1 shl i)
            assertEquals(bits, second)
        }
    }

    @Test
    fun encodingIsDeterministic() {
        val a = QrEncoder.encode("https://example.com/qr")!!
        val b = QrEncoder.encode("https://example.com/qr")!!
        assertEquals(a.size, b.size)
        for (y in 0 until a.size) for (x in 0 until a.size) assertEquals(a[x, y], b[x, y])
    }
}

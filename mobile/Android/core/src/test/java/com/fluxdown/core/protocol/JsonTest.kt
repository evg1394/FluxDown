package com.fluxdown.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {
    @Test
    fun roundTripsNestedValuesPreservingKeyOrderAndNumbers() {
        val text = """{"b":[1,-2.5,1e3,true,null],"a":{"x":"y"},"big":18446744073709551615}"""
        val v = Json.parse(text)
        assertEquals(text, v.toJson())
        assertEquals(listOf("b", "a", "big"), v.objOrNull!!.keys.toList())
        // u64 上限超出 Long：保留原文，按整数读取为 null，不经 Double 静默丢精度
        assertNull(v["big"].longOrNull)
        assertEquals(1000L, v["b"][2].longOrNull)
        assertEquals(-2.5, v["b"][1].doubleOrNull!!, 0.0)
    }

    @Test
    fun decodesEscapesIncludingSurrogatePairs() {
        val v = Json.parse("\"a\\n\\u00e9\\ud83d\\ude00\\\"\"")
        assertEquals("a\né😀\"", v.stringOrNull)
        assertEquals("\"a\\né😀\\\"\"", v.toJson())
        assertEquals("\"\\u0001\"", JsonValue.Str("\u0001").toJson())
    }

    @Test
    fun rejectsMalformedInput() {
        for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "01", "1.", "\"\u0001\"", "tru", "{} x", "-")) {
            assertThrows(bad, JsonException::class.java) { Json.parse(bad) }
        }
        assertNull(Json.parseOrNull("nope"))
    }

    @Test
    fun formatsDoublesLikeRust() {
        assertEquals("1", JsonValue.formatDouble(1.0))
        assertEquals("0.5", JsonValue.formatDouble(0.5))
        assertEquals("0.0000001", JsonValue.formatDouble(1e-7))
        assertEquals(JsonValue.Null, JsonValue.of(Double.NaN))
    }

    @Test
    fun fromConvertsKotlinValues() {
        val v = jsonObjectOmitNulls("a" to 1, "b" to null, "c" to listOf("x", false), "d" to mapOf("k" to 2L))
        assertEquals("""{"a":1,"c":["x",false],"d":{"k":2}}""", v.toJson())
        assertEquals("""{"b":null}""", jsonObject("b" to null).toJson())
    }
}

package com.fluxdown.core.protocol

/**
 * 协议 serde wire 的任意 JSON 值（通用通道 `HostSession.call` 的参数 / 结果、`HostSnapshot.sections` 分区）。
 *
 * `:core` 不依赖序列化库，也不能用 Android 的 `org.json`（JVM 单测里是桩），因此自带一个严格解析器 + 编码器。
 * 数字保留原始文本（[Num.raw]），整数 / 浮点按读取方需要解释，避免 `u64` 经 Double 丢精度。
 * 对象保持键的出现顺序（LinkedHashMap）。
 */
sealed interface JsonValue {
    data object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    /** [raw] 是合法的 JSON 数字文本。 */
    data class Num(val raw: String) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = fields[key]
    }

    /** 编码为紧凑 JSON 文本。 */
    fun toJson(): String = StringBuilder().also { JsonWriter.write(this, it) }.toString()

    companion object {
        val True = Bool(true)
        val False = Bool(false)

        fun of(value: Boolean): JsonValue = if (value) True else False
        fun of(value: Long): JsonValue = Num(value.toString())
        fun of(value: Int): JsonValue = Num(value.toString())

        /** 非有限值编码为 null（JSON 无 NaN / Infinity）。整数值不带小数点（同 Rust `f64::to_string`）。 */
        fun of(value: Double): JsonValue = if (value.isFinite()) Num(formatDouble(value)) else Null

        fun of(value: String?): JsonValue = if (value == null) Null else Str(value)

        /**
         * Kotlin 值 → JSON：`null` / [JsonValue] / String / Boolean / Int / Long / Double / Float /
         * List / Map<String, *>（含嵌套）。其它类型是编程错误。
         */
        fun from(value: Any?): JsonValue = when (value) {
            null -> Null
            is JsonValue -> value
            is String -> Str(value)
            is Boolean -> of(value)
            is Int -> of(value)
            is Long -> of(value)
            is Double -> of(value)
            is Float -> of(value.toDouble())
            is List<*> -> Arr(value.map { from(it) })
            is Map<*, *> -> Obj(
                LinkedHashMap<String, JsonValue>(value.size).also { out ->
                    value.forEach { (k, v) -> out[k as String] = from(v) }
                },
            )
            else -> throw IllegalArgumentException("unsupported JSON value: ${value::class.java.name}")
        }

        /** 近似 Rust `f64::to_string()`：整数值不带小数点，其余用最短往返表示（不使用指数）。 */
        fun formatDouble(value: Double): String {
            if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
            val text = value.toString()
            if ('E' !in text && 'e' !in text) return text
            return java.math.BigDecimal(text).stripTrailingZeros().toPlainString()
        }
    }
}

/** `jsonObject("a" to 1, "b" to listOf("x"))`：值经 [JsonValue.from] 转换；值为 `null` 的项编码为 JSON null。 */
fun jsonObject(vararg pairs: Pair<String, Any?>): JsonValue.Obj =
    JsonValue.Obj(LinkedHashMap<String, JsonValue>(pairs.size).also { m -> pairs.forEach { (k, v) -> m[k] = JsonValue.from(v) } })

/** 同 [jsonObject]，但跳过值为 `null` 的项（对应 serde `skip_serializing_if = "Option::is_none"`）。 */
fun jsonObjectOmitNulls(vararg pairs: Pair<String, Any?>): JsonValue.Obj =
    JsonValue.Obj(
        LinkedHashMap<String, JsonValue>(pairs.size).also { m ->
            pairs.forEach { (k, v) -> if (v != null) m[k] = JsonValue.from(v) }
        },
    )

// ───────────────────────────── 读取 ─────────────────────────────

operator fun JsonValue?.get(key: String): JsonValue? = (this as? JsonValue.Obj)?.fields?.get(key)

operator fun JsonValue?.get(index: Int): JsonValue? = (this as? JsonValue.Arr)?.items?.getOrNull(index)

val JsonValue?.isNull: Boolean get() = this == null || this == JsonValue.Null

val JsonValue?.stringOrNull: String? get() = (this as? JsonValue.Str)?.value

val JsonValue?.boolOrNull: Boolean? get() = (this as? JsonValue.Bool)?.value

/** 整数（含 `1.0` 这种整数值的浮点文本）；越界 / 非整数为 null。 */
val JsonValue?.longOrNull: Long?
    get() {
        val raw = (this as? JsonValue.Num)?.raw ?: return null
        raw.toLongOrNull()?.let { return it }
        val d = raw.toDoubleOrNull() ?: return null
        return if (d == Math.rint(d) && d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble()) d.toLong() else null
    }

val JsonValue?.intOrNull: Int?
    get() = longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

val JsonValue?.doubleOrNull: Double? get() = (this as? JsonValue.Num)?.raw?.toDoubleOrNull()

val JsonValue?.arrayOrNull: List<JsonValue>? get() = (this as? JsonValue.Arr)?.items

val JsonValue?.objOrNull: Map<String, JsonValue>? get() = (this as? JsonValue.Obj)?.fields

/** 字段读取（缺失 / 类型不符 → [default]，宽松同 serde `#[serde(default)]`）。 */
fun JsonValue?.str(key: String, default: String = ""): String = this[key].stringOrNull ?: default

fun JsonValue?.strOrNull(key: String): String? = this[key].stringOrNull

fun JsonValue?.bool(key: String, default: Boolean = false): Boolean = this[key].boolOrNull ?: default

fun JsonValue?.long(key: String, default: Long = 0): Long = this[key].longOrNull ?: default

fun JsonValue?.longOrNull(key: String): Long? = this[key].longOrNull

fun JsonValue?.int(key: String, default: Int = 0): Int = this[key].intOrNull ?: default

fun JsonValue?.double(key: String, default: Double = 0.0): Double = this[key].doubleOrNull ?: default

fun JsonValue?.list(key: String): List<JsonValue> = this[key].arrayOrNull.orEmpty()

fun JsonValue?.strings(key: String): List<String> = list(key).mapNotNull { it.stringOrNull }

/** 字符串值的映射（`BTreeMap<String, String>`）；非字符串值跳过。 */
fun JsonValue?.stringMap(key: String): Map<String, String> =
    this[key].objOrNull?.mapNotNull { (k, v) -> v.stringOrNull?.let { k to it } }?.toMap().orEmpty()

// ───────────────────────────── 解析 ─────────────────────────────

class JsonException(message: String) : IllegalArgumentException(message)

object Json {
    /** 严格解析（RFC 8259）；非法抛 [JsonException]。 */
    fun parse(text: String): JsonValue = JsonReader(text).parseDocument()

    fun parseOrNull(text: String?): JsonValue? = if (text == null) null else try {
        parse(text)
    } catch (_: JsonException) {
        null
    }
}

private class JsonReader(private val s: String) {
    private var i = 0

    fun parseDocument(): JsonValue {
        skipWs()
        val v = value(0)
        skipWs()
        if (i != s.length) fail("trailing characters")
        return v
    }

    private fun fail(msg: String): Nothing = throw JsonException("$msg at $i")

    private fun skipWs() {
        while (i < s.length) {
            when (s[i]) {
                ' ', '\t', '\n', '\r' -> i++
                else -> return
            }
        }
    }

    private fun value(depth: Int): JsonValue {
        if (depth > MAX_DEPTH) fail("nesting too deep")
        if (i >= s.length) fail("unexpected end")
        return when (val c = s[i]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> JsonValue.Str(string())
            't' -> literal("true", JsonValue.True)
            'f' -> literal("false", JsonValue.False)
            'n' -> literal("null", JsonValue.Null)
            else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
        }
    }

    private fun literal(word: String, v: JsonValue): JsonValue {
        if (!s.startsWith(word, i)) fail("invalid literal")
        i += word.length
        return v
    }

    private fun obj(depth: Int): JsonValue {
        i++ // {
        val out = LinkedHashMap<String, JsonValue>()
        skipWs()
        if (i < s.length && s[i] == '}') {
            i++
            return JsonValue.Obj(out)
        }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') fail("expected key")
            val key = string()
            skipWs()
            if (i >= s.length || s[i] != ':') fail("expected ':'")
            i++
            skipWs()
            out[key] = value(depth + 1)
            skipWs()
            if (i >= s.length) fail("unexpected end")
            when (s[i]) {
                ',' -> i++
                '}' -> {
                    i++
                    return JsonValue.Obj(out)
                }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun arr(depth: Int): JsonValue {
        i++ // [
        val out = ArrayList<JsonValue>()
        skipWs()
        if (i < s.length && s[i] == ']') {
            i++
            return JsonValue.Arr(out)
        }
        while (true) {
            skipWs()
            out += value(depth + 1)
            skipWs()
            if (i >= s.length) fail("unexpected end")
            when (s[i]) {
                ',' -> i++
                ']' -> {
                    i++
                    return JsonValue.Arr(out)
                }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun string(): String {
        i++ // "
        val b = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return b.toString()
                c == '\\' -> {
                    if (i >= s.length) fail("bad escape")
                    when (val e = s[i++]) {
                        '"' -> b.append('"')
                        '\\' -> b.append('\\')
                        '/' -> b.append('/')
                        'b' -> b.append('\b')
                        'f' -> b.append('\u000C')
                        'n' -> b.append('\n')
                        'r' -> b.append('\r')
                        't' -> b.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("bad unicode escape")
                            val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad unicode escape")
                            i += 4
                            b.append(code.toChar())
                        }
                        else -> fail("bad escape '\\$e'")
                    }
                }
                c < ' ' -> fail("control character in string")
                else -> b.append(c)
            }
        }
    }

    private fun number(): JsonValue {
        val start = i
        if (s[i] == '-') i++
        if (i >= s.length) fail("bad number")
        if (s[i] == '0') {
            i++
        } else if (s[i] in '1'..'9') {
            while (i < s.length && s[i] in '0'..'9') i++
        } else {
            fail("bad number")
        }
        if (i < s.length && s[i] == '.') {
            i++
            val d = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == d) fail("bad fraction")
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            val d = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == d) fail("bad exponent")
        }
        return JsonValue.Num(s.substring(start, i))
    }

    private companion object {
        const val MAX_DEPTH = 256
    }
}

private object JsonWriter {
    fun write(v: JsonValue, b: StringBuilder) {
        when (v) {
            JsonValue.Null -> b.append("null")
            is JsonValue.Bool -> b.append(if (v.value) "true" else "false")
            is JsonValue.Num -> b.append(v.raw)
            is JsonValue.Str -> string(v.value, b)
            is JsonValue.Arr -> {
                b.append('[')
                v.items.forEachIndexed { idx, item ->
                    if (idx > 0) b.append(',')
                    write(item, b)
                }
                b.append(']')
            }
            is JsonValue.Obj -> {
                b.append('{')
                var first = true
                for ((k, item) in v.fields) {
                    if (!first) b.append(',')
                    first = false
                    string(k, b)
                    b.append(':')
                    write(item, b)
                }
                b.append('}')
            }
        }
    }

    private fun string(s: String, b: StringBuilder) {
        b.append('"')
        for (c in s) {
            when {
                c == '"' -> b.append("\\\"")
                c == '\\' -> b.append("\\\\")
                c == '\n' -> b.append("\\n")
                c == '\r' -> b.append("\\r")
                c == '\t' -> b.append("\\t")
                c < ' ' || c == '\u2028' || c == '\u2029' -> b.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                else -> b.append(c)
            }
        }
        b.append('"')
    }
}

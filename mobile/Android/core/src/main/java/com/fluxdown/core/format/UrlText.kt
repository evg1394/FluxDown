package com.fluxdown.core.format

import java.io.ByteArrayOutputStream

/** URL 文本的宽松处理（不依赖 `android.net.Uri`：`:core` 的 JVM 单测里它是桩）。 */
internal object UrlText {
    /**
     * 百分号按 UTF-8 字节解码；非法 / 截断的 `%` 序列原样保留，非法 UTF-8 字节替换为 U+FFFD。
     * [plusAsSpace]：查询串语义（`application/x-www-form-urlencoded`，同 `Uri.getQueryParameter`）。
     */
    fun percentDecode(input: String, plusAsSpace: Boolean = false): String {
        if ('%' !in input && !(plusAsSpace && '+' in input)) return input
        val bytes = input.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            val hi = if (b == PERCENT && i + 2 < bytes.size) Character.digit(bytes[i + 1].toInt(), 16) else -1
            val lo = if (hi >= 0) Character.digit(bytes[i + 2].toInt(), 16) else -1
            when {
                lo >= 0 -> {
                    out.write(hi * 16 + lo)
                    i += 3
                }
                plusAsSpace && b == PLUS -> {
                    out.write(SPACE.toInt())
                    i++
                }
                else -> {
                    out.write(b.toInt())
                    i++
                }
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** 层级 URL（`scheme://authority/path`）路径的末段（未解码）；无路径 / 非层级 URL 返回 null。 */
    fun lastPathSegment(url: String): String? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val rest = trimmed.substring(schemeEnd + 3)
        val pathStart = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (pathStart < 0 || rest[pathStart] != '/') return null
        return rest.substring(pathStart).substringBefore('#').substringBefore('?').substringAfterLast('/')
    }

    /**
     * 查询串 → 参数（键值均按查询串语义解码，同名取首个，同 `Uri.getQueryParameter`）。
     * [query] 不含前导 `?` 与片段。
     */
    fun queryParameters(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (query.isEmpty()) return out
        for (part in query.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = percentDecode(if (eq < 0) part else part.substring(0, eq), plusAsSpace = true)
            val value = if (eq < 0) "" else percentDecode(part.substring(eq + 1), plusAsSpace = true)
            out.putIfAbsent(key, value)
        }
        return out
    }

    private const val PERCENT = '%'.code.toByte()
    private const val PLUS = '+'.code.toByte()
    private const val SPACE = ' '.code.toByte()
}

package com.fluxdown.core.capture

import com.fluxdown.core.protocol.JsonValue
import com.fluxdown.core.protocol.jsonObject
import java.util.Base64
import java.util.Locale

/**
 * `.torrent` 文件导入的纯逻辑（镜像 iOS `FluxKit/.../Protocol/Tasks.swift` 的 `TorrentFile`）：
 * 粗校验 + `daemon.task.create` 参数（`request.torrentB64`，标准 base64；`url` 为空）。
 */
object TorrentFile {
    /** daemon blob / 种子上限（`REQUEST_BODY_LIMIT`、RSS `MAX_TORRENT_BYTES`）。 */
    const val MAX_BYTES: Int = 4 * 1024 * 1024

    /** 种子 MIME（系统「打开方式」/ 文件选择器）。 */
    const val MIME_TYPE = "application/x-bittorrent"

    enum class Issue {
        Empty,
        TooLarge,

        /** 不是 bencode 字典 / 没有 `info` 键（登录页、误选文件）。 */
        NotTorrent,
    }

    private val infoKey = "4:info".toByteArray(Charsets.US_ASCII)

    /** 粗校验：非空、≤ 4 MiB、bencode 字典（以 `d` 开头）且含 `4:info`（真实种子必有）。合法返回 null。 */
    fun validate(data: ByteArray): Issue? {
        if (data.isEmpty()) return Issue.Empty
        if (data.size > MAX_BYTES) return Issue.TooLarge
        if (data[0] != 'd'.code.toByte() || indexOf(data, infoKey) < 0) return Issue.NotTorrent
        return null
    }

    /** `daemon.task.create` 参数（`DaemonCreateTaskParams`）。[saveDir] / [queueId] 为空 = 主机默认。 */
    fun createParams(data: ByteArray, saveDir: String, queueId: String, startPaused: Boolean): JsonValue =
        jsonObject(
            "request" to jsonObject(
                "url" to "",
                "saveDir" to saveDir,
                "queueId" to queueId,
                "torrentB64" to Base64.getEncoder().encodeToString(data),
                "startPaused" to startPaused,
            ),
            "unattended" to false,
        )

    fun isTorrentFileName(name: String): Boolean = name.lowercase(Locale.ROOT).endsWith(".torrent")

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        val last = haystack.size - needle.size
        var i = 0
        while (i <= last) {
            var j = 0
            while (j < needle.size && haystack[i + j] == needle[j]) j++
            if (j == needle.size) return i
            i++
        }
        return -1
    }
}

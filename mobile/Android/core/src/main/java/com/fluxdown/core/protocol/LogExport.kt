package com.fluxdown.core.protocol

/**
 * 远端 `--server` 主机的日志导出：`GET <host>/api/web/logs/export`（Bearer 访问密钥）。
 * `agent.diagnostics.exportLogs` 会写主机上的任意路径，服务器模式的网关拒绝它，只留 HTTP 这条路（同 Web `exportLogs`）。
 */
object LogExportHttp {
    const val PATH = "/api/web/logs/export"

    /**
     * 保存的主机地址（`host[:port]` / `http(s)://…` / `ws(s)://…[/base][/rpc]`）→ 导出地址。
     * 反向代理子路径保留；`/rpc`、查询串、片段丢弃（同 `native/mobile` 的 `normalize_endpoint`）。
     * 不支持的 scheme / 无主机 / 端口非法 → null。
     */
    fun exportUrl(endpoint: String): String? {
        var text = endpoint.trim()
        if (!text.contains("://")) text = "http://$text"
        val schemeEnd = text.indexOf("://")
        val scheme = when (text.substring(0, schemeEnd).lowercase()) {
            "ws", "http" -> "http"
            "wss", "https" -> "https"
            else -> return null
        }
        var rest = text.substring(schemeEnd + 3)
        rest.indexOf('#').takeIf { it >= 0 }?.let { rest = rest.substring(0, it) }
        rest.indexOf('?').takeIf { it >= 0 }?.let { rest = rest.substring(0, it) }
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        var base = if (slash >= 0) rest.substring(slash) else ""
        if (!validAuthority(authority)) return null
        base = base.trimEnd('/')
        if (base.endsWith("/rpc")) base = base.removeSuffix("/rpc")
        return "$scheme://$authority$base$PATH"
    }

    /** `[userinfo@]host[:port]`：主机非空，端口（若有）为 0–65535 的数字，不含空白。 */
    private fun validAuthority(authority: String): Boolean {
        if (authority.isEmpty() || authority.any { it.isWhitespace() }) return false
        val hostPort = authority.substringAfterLast('@')
        val host: String
        val port: String?
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return false
            host = hostPort.substring(1, close)
            val tail = hostPort.substring(close + 1)
            port = when {
                tail.isEmpty() -> null
                tail.startsWith(":") -> tail.substring(1)
                else -> return false
            }
        } else {
            host = hostPort.substringBeforeLast(':')
            port = if (hostPort.contains(':')) hostPort.substringAfterLast(':') else null
        }
        if (host.isEmpty()) return false
        if (port != null) {
            if (port.isEmpty()) return true // `host:`：同 URL 解析，空端口 = 默认端口
            if (!port.all { it in '0'..'9' } || port.length > 5 || port.toInt() > 65535) return false
        }
        return true
    }
}

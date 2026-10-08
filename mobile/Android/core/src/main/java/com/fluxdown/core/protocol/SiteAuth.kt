package com.fluxdown.core.protocol

/*
 * 代理连通性测试、系统代理检测与站点 HTTP Basic 凭据（`daemon.config.proxyTest` / `systemProxy`、`daemon.siteAuth.*`）。
 * 镜像 `native/protocol/src/daemon.rs`（camelCase，解码宽松：缺字段取默认）。
 *
 * 密码类字段（`pass` / `password`）只出现在需要原文的表单里：这些 DTO 的 `toString()` 一律脱敏，
 * 避免被日志、断言消息带出明文。
 */

private const val REDACTED = "‹redacted›"

/** `daemon.config.proxyTest` 参数。`proxyType` 为 http / https / socks4 / socks5；`port` 是字符串（与配置键一致）。 */
data class ProxyTestRequest(
    val proxyType: String,
    val host: String,
    val port: String,
    val username: String = "",
    val password: String = "",
) {
    fun toJson(): JsonValue = jsonObject(
        "proxyType" to JsonValue.of(proxyType),
        "host" to JsonValue.of(host),
        "port" to JsonValue.of(port),
        "username" to JsonValue.of(username),
        "password" to JsonValue.of(password),
    )

    override fun toString(): String =
        "ProxyTestRequest($proxyType://$host:$port, username: $username, password: ${if (password.isEmpty()) "" else REDACTED})"

    companion object {
        fun fromJson(v: JsonValue): ProxyTestRequest = ProxyTestRequest(
            proxyType = v.str("proxyType", ""),
            host = v.str("host", ""),
            port = v.str("port", ""),
            username = v.str("username", ""),
            password = v.str("password", ""),
        )
    }
}

/** `daemon.config.proxyTest` 结果：经代理完成一次探测请求的往返延迟。 */
data class ProxyTestResponse(val latencyMs: Long = 0) {
    companion object {
        fun fromJson(v: JsonValue): ProxyTestResponse = ProxyTestResponse(v.long("latencyMs", 0L))
    }
}

/** `daemon.config.systemProxy` 结果。未检测到时 [detected] 为 false，其余字段为空串 / 0。 */
data class SystemProxyDto(
    val detected: Boolean = false,
    /** http / https / socks4 / socks5；未检测到时为空串。 */
    val proxyType: String = "",
    val host: String = "",
    val port: Int = 0,
    /** 逗号分隔的排除列表；未检测到时为空串。 */
    val noList: String = "",
) {
    companion object {
        fun fromJson(v: JsonValue): SystemProxyDto = SystemProxyDto(
            detected = v.bool("detected", false),
            proxyType = v.str("proxyType", ""),
            host = v.str("host", ""),
            port = v.int("port", 0),
            noList = v.str("noList", ""),
        )
    }
}

/** 站点凭据列表条目：站点与用户名，**不含密码**。[site] 为 `host` 或 `host:port`。 */
data class SiteAuthEntryDto(val site: String, val user: String = "") {
    companion object {
        fun fromJson(v: JsonValue): SiteAuthEntryDto = SiteAuthEntryDto(v.str("site", ""), v.str("user", ""))

        /** `daemon.siteAuth.list` / `delete` / `clear` 的结果数组；非数组按空列表。 */
        fun listFromJson(v: JsonValue): List<SiteAuthEntryDto> = v.arrayOrNull.orEmpty().map(::fromJson)
    }
}

/** `daemon.siteAuth.get` / `match` 结果（可为 `null`）：含**明文**密码，仅用于编辑 / 新建下载认证表单回填。 */
data class SiteAuthCredentialDto(val site: String, val user: String = "", val pass: String = "") {
    override fun toString(): String =
        "SiteAuthCredentialDto(site: $site, user: $user, pass: ${if (pass.isEmpty()) "" else REDACTED})"

    companion object {
        /** `null` / 非对象 → null。 */
        fun fromJsonOrNull(v: JsonValue): SiteAuthCredentialDto? {
            if (v.objOrNull == null) return null
            return SiteAuthCredentialDto(v.str("site", ""), v.str("user", ""), v.str("pass", ""))
        }
    }
}

/** `daemon.siteAuth.save` 参数（[site] 可写 `host` / `host:port` 或完整 URL，服务端归一化）。 */
data class SiteAuthSaveRequest(val site: String, val user: String, val pass: String) {
    fun toJson(): JsonValue = jsonObject(
        "site" to JsonValue.of(site),
        "user" to JsonValue.of(user),
        "pass" to JsonValue.of(pass),
    )

    override fun toString(): String =
        "SiteAuthSaveRequest(site: $site, user: $user, pass: ${if (pass.isEmpty()) "" else REDACTED})"
}

/** `daemon.siteAuth.delete` 参数；结果为删除后的完整列表。 */
data class SiteAuthDeleteParams(val site: String) {
    fun toJson(): JsonValue = jsonObject("site" to JsonValue.of(site))
}

/** `daemon.siteAuth.get` 参数：[site] 可写 `host` / `host:port` 或完整 URL；结果为 [SiteAuthCredentialDto]?。 */
data class SiteAuthGetParams(val site: String) {
    fun toJson(): JsonValue = jsonObject("site" to JsonValue.of(site))
}

/** `daemon.siteAuth.match` 参数：下载链接；结果为 [SiteAuthCredentialDto]?。 */
data class SiteAuthMatchParams(val url: String) {
    fun toJson(): JsonValue = jsonObject("url" to JsonValue.of(url))
}

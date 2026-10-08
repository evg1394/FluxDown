import Foundation

// 代理连通性测试、系统代理检测与站点 HTTP Basic 凭据（`daemon.config.proxyTest` / `systemProxy`、`daemon.siteAuth.*`）。
// 镜像 `native/protocol/src/daemon.rs`（camelCase，解码宽松：缺字段取默认）与 `web/src/lib/rpc/protocol/*.ts`。
//
// 密码类字段（`pass` / `password`）只出现在需要原文的表单里：这些 DTO 的 `description` / `debugDescription`
// 一律脱敏，避免被日志、`print`、断言消息带出明文。

private let redacted = "‹redacted›"

// MARK: - 代理测试

/// `daemon.config.proxyTest` 参数。`proxyType` 为 `http` / `https` / `socks4` / `socks5`；`port` 是字符串（与配置键一致）。
public struct ProxyTestRequest: Sendable, Hashable, Codable, CustomStringConvertible, CustomDebugStringConvertible {
    public var proxyType: String
    public var host: String
    public var port: String
    public var username: String
    public var password: String

    public init(proxyType: String, host: String, port: String, username: String = "", password: String = "") {
        self.proxyType = proxyType
        self.host = host
        self.port = port
        self.username = username
        self.password = password
    }

    private enum CodingKeys: String, CodingKey { case proxyType, host, port, username, password }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        proxyType = try container.decodeIfPresent(String.self, forKey: .proxyType) ?? ""
        host = try container.decodeIfPresent(String.self, forKey: .host) ?? ""
        port = try container.decodeIfPresent(String.self, forKey: .port) ?? ""
        username = try container.decodeIfPresent(String.self, forKey: .username) ?? ""
        password = try container.decodeIfPresent(String.self, forKey: .password) ?? ""
    }

    public var description: String {
        "ProxyTestRequest(\(proxyType)://\(host):\(port), username: \(username), password: \(password.isEmpty ? "" : redacted))"
    }

    public var debugDescription: String { description }
}

/// `daemon.config.proxyTest` 结果：经代理完成一次探测请求的往返延迟。
public struct ProxyTestResponse: Sendable, Hashable, Codable {
    public var latencyMs: Int64

    public init(latencyMs: Int64 = 0) {
        self.latencyMs = latencyMs
    }

    private enum CodingKeys: String, CodingKey { case latencyMs }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        latencyMs = try container.decodeIfPresent(Int64.self, forKey: .latencyMs) ?? 0
    }
}

// MARK: - 系统代理

/// `daemon.config.systemProxy` 结果。未检测到时 `detected == false`，其余字段为空串 / 0。
public struct SystemProxyDto: Sendable, Hashable, Codable {
    public var detected: Bool
    /// `http` / `https` / `socks4` / `socks5`；未检测到时为空串。
    public var proxyType: String
    public var host: String
    public var port: UInt16
    /// 逗号分隔的排除列表；未检测到时为空串。
    public var noList: String

    public init(detected: Bool = false, proxyType: String = "", host: String = "", port: UInt16 = 0, noList: String = "") {
        self.detected = detected
        self.proxyType = proxyType
        self.host = host
        self.port = port
        self.noList = noList
    }

    private enum CodingKeys: String, CodingKey { case detected, proxyType, host, port, noList }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        detected = try container.decodeIfPresent(Bool.self, forKey: .detected) ?? false
        proxyType = try container.decodeIfPresent(String.self, forKey: .proxyType) ?? ""
        host = try container.decodeIfPresent(String.self, forKey: .host) ?? ""
        port = try container.decodeIfPresent(UInt16.self, forKey: .port) ?? 0
        noList = try container.decodeIfPresent(String.self, forKey: .noList) ?? ""
    }
}

// MARK: - 站点凭据

/// `daemon.siteAuth.list` / `delete` / `clear` / `save` 的条目：站点与用户名，**不含密码**。
public struct SiteAuthEntryDto: Sendable, Hashable, Codable, Identifiable {
    /// `host` 或 `host:port`。
    public var site: String
    public var user: String

    public var id: String { site }

    public init(site: String, user: String = "") {
        self.site = site
        self.user = user
    }

    private enum CodingKeys: String, CodingKey { case site, user }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        site = try container.decodeIfPresent(String.self, forKey: .site) ?? ""
        user = try container.decodeIfPresent(String.self, forKey: .user) ?? ""
    }
}

/// `daemon.siteAuth.get` / `match` 结果（可为 `null`）：含**明文**密码，仅用于编辑 / 新建下载认证表单回填。
public struct SiteAuthCredentialDto: Sendable, Hashable, Codable, Identifiable, CustomStringConvertible, CustomDebugStringConvertible {
    public var site: String
    public var user: String
    public var pass: String

    public var id: String { site }

    public init(site: String, user: String = "", pass: String = "") {
        self.site = site
        self.user = user
        self.pass = pass
    }

    private enum CodingKeys: String, CodingKey { case site, user, pass }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        site = try container.decodeIfPresent(String.self, forKey: .site) ?? ""
        user = try container.decodeIfPresent(String.self, forKey: .user) ?? ""
        pass = try container.decodeIfPresent(String.self, forKey: .pass) ?? ""
    }

    public var description: String {
        "SiteAuthCredentialDto(site: \(site), user: \(user), pass: \(pass.isEmpty ? "" : redacted))"
    }

    public var debugDescription: String { description }
}

/// `daemon.siteAuth.save` 参数（`site` 可写 `host` / `host:port` 或完整 URL，服务端归一化）。
public struct SiteAuthSaveRequest: Sendable, Hashable, Codable, CustomStringConvertible, CustomDebugStringConvertible {
    public var site: String
    public var user: String
    public var pass: String

    public init(site: String, user: String, pass: String) {
        self.site = site
        self.user = user
        self.pass = pass
    }

    private enum CodingKeys: String, CodingKey { case site, user, pass }

    public init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        site = try container.decodeIfPresent(String.self, forKey: .site) ?? ""
        user = try container.decodeIfPresent(String.self, forKey: .user) ?? ""
        pass = try container.decodeIfPresent(String.self, forKey: .pass) ?? ""
    }

    public var description: String {
        "SiteAuthSaveRequest(site: \(site), user: \(user), pass: \(pass.isEmpty ? "" : redacted))"
    }

    public var debugDescription: String { description }
}

/// `daemon.siteAuth.delete` 参数；结果为删除后的完整列表 `[SiteAuthEntryDto]`。
public struct SiteAuthDeleteParams: Sendable, Hashable, Codable {
    public var site: String

    public init(site: String) {
        self.site = site
    }
}

/// `daemon.siteAuth.get` 参数：`site` 可写 `host` / `host:port` 或完整 URL；结果为 `SiteAuthCredentialDto?`。
public struct SiteAuthGetParams: Sendable, Hashable, Codable {
    public var site: String

    public init(site: String) {
        self.site = site
    }
}

/// `daemon.siteAuth.match` 参数：下载链接（按与引擎自动套用同一规则匹配）；结果为 `SiteAuthCredentialDto?`。
public struct SiteAuthMatchParams: Sendable, Hashable, Codable {
    public var url: String

    public init(url: String) {
        self.url = url
    }
}

import Foundation

// 环境诊断与日志（`agent.diagnostics.*` / `daemon.diagnostics.describe`）。
// 镜像 `native/protocol/src/agent.rs`（DiagnosticLevel / DiagnosticCheckDto / DiagnosticRepairParams /
// DiagnosticsReportDto / LogExportParams / LogExportResult / LogPathsDto）与 `native/daemon` 的
// `DAEMON_DIAGNOSTICS_DESCRIBE` 结果（Web `DaemonDiagnosticsDescribe`）。

/// Doctor 检查项级别（`DiagnosticLevel`，serde camelCase：info / ok / warn / error）。
public enum DiagnosticLevel: WireStringEnum {
    case info
    case ok
    case warn
    case error
    /// 主机新增了本端不认识的级别。
    case unknown(String)

    public init(wire: String) {
        switch wire {
        case "info": self = .info
        case "ok": self = .ok
        case "warn": self = .warn
        case "error": self = .error
        default: self = .unknown(wire)
        }
    }

    public var wire: String {
        switch self {
        case .info: "info"
        case .ok: "ok"
        case .warn: "warn"
        case .error: "error"
        case let .unknown(raw): raw
        }
    }

    /// 计入「N 项问题」：warn + error（同 Web / GPUI）。
    public var isIssue: Bool {
        switch self {
        case .warn, .error: true
        case .info, .ok, .unknown: false
        }
    }

    /// 报告里的级别标签（`[WARN]`）：wire 名大写（同 Web `check.level.toUpperCase()`）。
    public var reportTag: String { wire.uppercased() }
}

/// `agent.diagnostics.repair` 参数（也是检查项上可用的就地修复）。
public struct DiagnosticRepairParams: Codable, Sendable, Hashable {
    public var action: String
    public var target: String

    public init(action: String, target: String = "") {
        self.action = action
        self.target = target
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        action = try c.decode(String.self, forKey: .action)
        target = try c.decodeIfPresent(String.self, forKey: .target) ?? ""
    }
}

/// 单条 Doctor 检查结果。`id` 稳定（据此取 `doctorCheck{Camel}` 文案）；同一 `id` 可有多个 `target`。
public struct DiagnosticCheckDto: Codable, Sendable, Hashable {
    public var id: String
    /// 同一 `id` 下的子目标（浏览器名、scheme、目录路径…）；无则为空。
    public var target: String
    public var level: DiagnosticLevel
    public var detail: String
    /// 既有 hint code（如 `check_disk`）；无则为空。
    public var hint: String
    /// 可用的就地修复动作；无则 nil。
    public var repair: DiagnosticRepairParams?

    public init(
        id: String, target: String = "", level: DiagnosticLevel = .info, detail: String = "",
        hint: String = "", repair: DiagnosticRepairParams? = nil
    ) {
        self.id = id
        self.target = target
        self.level = level
        self.detail = detail
        self.hint = hint
        self.repair = repair
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        target = try c.decodeIfPresent(String.self, forKey: .target) ?? ""
        level = try c.decode(DiagnosticLevel.self, forKey: .level)
        detail = try c.decode(String.self, forKey: .detail)
        hint = try c.decodeIfPresent(String.self, forKey: .hint) ?? ""
        repair = try c.decodeIfPresent(DiagnosticRepairParams.self, forKey: .repair)
    }
}

/// `agent.diagnostics.run` 结果。
public struct DiagnosticsReportDto: Codable, Sendable, Hashable {
    public var generatedAtUnixMs: Int64
    public var appVersion: String
    public var platform: String
    public var agentDataDir: String
    public var daemonConnected: Bool
    public var checks: [DiagnosticCheckDto]

    public init(
        generatedAtUnixMs: Int64 = 0, appVersion: String = "", platform: String = "", agentDataDir: String = "",
        daemonConnected: Bool = false, checks: [DiagnosticCheckDto] = []
    ) {
        self.generatedAtUnixMs = generatedAtUnixMs
        self.appVersion = appVersion
        self.platform = platform
        self.agentDataDir = agentDataDir
        self.daemonConnected = daemonConnected
        self.checks = checks
    }
}

/// `agent.diagnostics.exportLogs` 参数：目标 `.zip` 路径（主机本地路径）。
public struct LogExportParams: Codable, Sendable, Hashable {
    public var targetPath: String

    public init(targetPath: String) {
        self.targetPath = targetPath
    }
}

/// `agent.diagnostics.exportLogs` 结果。
public struct LogExportResult: Codable, Sendable, Hashable {
    public var path: String
    public var bytes: UInt64

    public init(path: String, bytes: UInt64) {
        self.path = path
        self.bytes = bytes
    }
}

/// `agent.diagnostics.logPaths`：agent / daemon 日志目录（只有路径，不含大小）。
public struct LogPathsDto: Codable, Sendable, Hashable {
    public var agentLogDir: String
    public var daemonLogDir: String

    public init(agentLogDir: String = "", daemonLogDir: String = "") {
        self.agentLogDir = agentLogDir
        self.daemonLogDir = daemonLogDir
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        agentLogDir = try c.decodeIfPresent(String.self, forKey: .agentLogDir) ?? ""
        daemonLogDir = try c.decodeIfPresent(String.self, forKey: .daemonLogDir) ?? ""
    }
}

/// `daemon.diagnostics.describe` 里的 `service`（`ServiceHello`）。
public struct DiagnosticsServiceInfo: Codable, Sendable, Hashable {
    public var serviceName: String
    public var serviceVersion: String
    public var protocolVersion: UInt32
    public var instanceId: String
    public var capabilities: [String]

    public init(
        serviceName: String = "", serviceVersion: String = "", protocolVersion: UInt32 = 0,
        instanceId: String = "", capabilities: [String] = []
    ) {
        self.serviceName = serviceName
        self.serviceVersion = serviceVersion
        self.protocolVersion = protocolVersion
        self.instanceId = instanceId
        self.capabilities = capabilities
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        serviceName = try c.decodeIfPresent(String.self, forKey: .serviceName) ?? ""
        serviceVersion = try c.decodeIfPresent(String.self, forKey: .serviceVersion) ?? ""
        protocolVersion = try c.decodeIfPresent(UInt32.self, forKey: .protocolVersion) ?? 0
        instanceId = try c.decodeIfPresent(String.self, forKey: .instanceId) ?? ""
        capabilities = try c.decodeIfPresent([String].self, forKey: .capabilities) ?? []
    }
}

/// `daemon.diagnostics.describe` 结果（任务 / 队列 / 任务组数量、配置版本、daemon 日志目录）。
/// `components`（外部组件状态）这里不用，解码时忽略。
public struct DaemonDiagnosticsDescribe: Codable, Sendable, Hashable {
    public var service: DiagnosticsServiceInfo
    public var tasks: Int
    public var queues: Int
    public var groups: Int
    public var configRevision: UInt64
    public var logDir: String

    public init(
        service: DiagnosticsServiceInfo = DiagnosticsServiceInfo(), tasks: Int = 0, queues: Int = 0, groups: Int = 0,
        configRevision: UInt64 = 0, logDir: String = ""
    ) {
        self.service = service
        self.tasks = tasks
        self.queues = queues
        self.groups = groups
        self.configRevision = configRevision
        self.logDir = logDir
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        service = try c.decodeIfPresent(DiagnosticsServiceInfo.self, forKey: .service) ?? DiagnosticsServiceInfo()
        tasks = try c.decodeIfPresent(Int.self, forKey: .tasks) ?? 0
        queues = try c.decodeIfPresent(Int.self, forKey: .queues) ?? 0
        groups = try c.decodeIfPresent(Int.self, forKey: .groups) ?? 0
        configRevision = try c.decodeIfPresent(UInt64.self, forKey: .configRevision) ?? 0
        logDir = try c.decodeIfPresent(String.self, forKey: .logDir) ?? ""
    }
}

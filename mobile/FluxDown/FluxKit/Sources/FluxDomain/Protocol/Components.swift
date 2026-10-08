import Foundation

// 托管组件（ffmpeg / yt-dlp，`daemon.component.*`）DTO：镜像 `native/protocol/src/daemon.rs` 的组件段
// （同 `web/src/lib/rpc/protocol/plugin.ts` 末段 + `params.ts`）。

/// 组件标识（wire：`ffmpeg` / `ytdlp`）。宽松：未知组件落入 `unknown`，不让整个分区解码失败。
public enum ComponentKind: WireStringEnum {
    case ffmpeg, ytdlp
    case unknown(String)

    public static let known: [ComponentKind] = [.ffmpeg, .ytdlp]

    public init(wire: String) {
        switch wire {
        case "ffmpeg": self = .ffmpeg
        case "ytdlp": self = .ytdlp
        default: self = .unknown(wire)
        }
    }

    public var wire: String {
        switch self {
        case .ffmpeg: "ffmpeg"
        case .ytdlp: "ytdlp"
        case let .unknown(raw): raw
        }
    }
}

/// ffmpeg / yt-dlp 组件状态（两者字段相同）。
public struct ComponentStatus: Codable, Sendable, Hashable {
    /// 生效路径来源：`manual` / `managed` / `system` / `none`。
    public var source: String
    /// 生效的可执行文件路径（`none` 时为空）。
    public var path: String
    /// 探测到的版本串（失败 / 未找到为空）。
    public var version: String
    /// 托管安装记录的版本（空 = 未托管安装）。
    public var managedVersion: String
    /// 系统 PATH 中探测到的路径（空 = 无）。
    public var systemPath: String
    /// 当前平台是否提供托管安装。
    public var managedSupported: Bool

    public init(
        source: String = "none",
        path: String = "",
        version: String = "",
        managedVersion: String = "",
        systemPath: String = "",
        managedSupported: Bool = true
    ) {
        self.source = source
        self.path = path
        self.version = version
        self.managedVersion = managedVersion
        self.systemPath = systemPath
        self.managedSupported = managedSupported
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        source = try c.decodeIfPresent(String.self, forKey: .source) ?? "none"
        path = try c.decodeIfPresent(String.self, forKey: .path) ?? ""
        version = try c.decodeIfPresent(String.self, forKey: .version) ?? ""
        managedVersion = try c.decodeIfPresent(String.self, forKey: .managedVersion) ?? ""
        systemPath = try c.decodeIfPresent(String.self, forKey: .systemPath) ?? ""
        managedSupported = try c.decodeIfPresent(Bool.self, forKey: .managedSupported) ?? true
    }

    public var hasManagedInstall: Bool { !managedVersion.isEmpty }
}

/// 受管组件类型化状态：serde 相邻标记 `{"component": "...", "status": {...}}`。
public struct ComponentStatusDto: Codable, Sendable, Hashable, Identifiable {
    public var component: ComponentKind
    public var status: ComponentStatus

    public var id: String { component.wire }

    public init(component: ComponentKind, status: ComponentStatus) {
        self.component = component
        self.status = status
    }
}

/// 组件可安装版本（`ComponentVersions`）。
public struct ComponentVersions: Codable, Sendable, Hashable {
    /// 降序排列的稳定版本号。
    public var versions: [String]
    /// 最新稳定版（空 = 解析失败）。
    public var latestStable: String

    public init(versions: [String] = [], latestStable: String = "") {
        self.versions = versions
        self.latestStable = latestStable
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        versions = try c.decodeIfPresent([String].self, forKey: .versions) ?? []
        latestStable = try c.decodeIfPresent(String.self, forKey: .latestStable) ?? ""
    }

    /// 默认选中版本：优先最新稳定版，其次列表首项；保留仍在列表里的现有选择。
    public func defaultSelection(current: String?) -> String? {
        if let current, versions.contains(current) { return current }
        return latestStable.isEmpty ? versions.first : latestStable
    }
}

/// `daemon.component.{get,uninstall,listVersions}`。
public struct ComponentParams: Codable, Sendable, Hashable {
    public var component: ComponentKind

    public init(component: ComponentKind) {
        self.component = component
    }
}

/// `daemon.component.install`：`version == nil` = 最新稳定版。
public struct ComponentInstallParams: Codable, Sendable, Hashable {
    public var component: ComponentKind
    public var version: String?

    public init(component: ComponentKind, version: String?) {
        self.component = component
        self.version = version
    }
}

/// `WsServerMsg::ComponentProgress`（`HostNoticeName.componentProgress` 的载荷；`totalBytes == 0` = 未知）。
public struct ComponentProgressNotice: Codable, Sendable, Hashable {
    public var component: ComponentKind
    public var downloadedBytes: Int64
    public var totalBytes: Int64

    public init(component: ComponentKind, downloadedBytes: Int64, totalBytes: Int64) {
        self.component = component
        self.downloadedBytes = downloadedBytes
        self.totalBytes = totalBytes
    }

    /// 0…1；总量未知为 nil。
    public var fraction: Double? {
        totalBytes > 0 ? min(1, max(0, Double(downloadedBytes) / Double(totalBytes))) : nil
    }
}

/// `WsServerMsg::ComponentResult`（`HostNoticeName.componentResult` 的载荷）。
public struct ComponentResultNotice: Codable, Sendable, Hashable {
    public var component: ComponentKind
    public var ok: Bool
    public var message: String

    public init(component: ComponentKind, ok: Bool, message: String = "") {
        self.component = component
        self.ok = ok
        self.message = message
    }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        component = try c.decode(ComponentKind.self, forKey: .component)
        ok = try c.decode(Bool.self, forKey: .ok)
        message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
    }
}

/// 手动路径的 daemon 配置键（`component.ffmpeg.path` / `component.ytdlp.path`）。
public extension ComponentKind {
    var manualPathConfigKey: String? {
        switch self {
        case .ffmpeg: "component.ffmpeg.path"
        case .ytdlp: "component.ytdlp.path"
        case .unknown: nil
        }
    }
}

import FluxDomain
import FluxUI
import Foundation

/// 外观偏好 ↔ `agent.preferences` 的线上形态映射（纯逻辑）。
///
/// PC 端只认 `appearance.theme_mode`（system/light/dark）、`appearance.color_scheme`（blue/green/violet/rose/custom，
/// 未知值回落蓝色）与 `appearance.custom_color`（Flutter `Color.toARGB32()` 的无符号 ARGB 整数，仅 `custom` 时生效）。
/// iOS 还有两个预设色（orange / indigo）：它们在线上写成 `custom` + 对应 ARGB，读回时按色值认回预设，
/// 这样同步到 PC 端也是同一种颜色，而不是被当作未知方案回落成蓝色。
nonisolated enum AppearanceWire {
    static let modeKey = "appearance.theme_mode"
    static let schemeKey = "appearance.color_scheme"
    static let customKey = "appearance.custom_color"

    /// PC 端识别的预设方案。
    private static let sharedPresets: Set<String> = ["blue", "green", "violet", "rose"]

    /// 主机偏好里的外观值（缺失为 nil）。
    nonisolated struct Host: Equatable {
        var mode: String?
        var scheme: String?
        var customARGB: Int64?

        init(mode: String? = nil, scheme: String? = nil, customARGB: Int64? = nil) {
            self.mode = mode
            self.scheme = scheme
            self.customARGB = customARGB
        }

        init(_ prefs: AgentPreferencesDto) {
            self.init(
                mode: prefs[AppearanceWire.modeKey]?.stringValue,
                scheme: prefs[AppearanceWire.schemeKey]?.stringValue,
                customARGB: prefs[AppearanceWire.customKey]?.intValue
            )
        }

        var isEmpty: Bool { mode == nil && scheme == nil && customARGB == nil }
    }

    /// 应用到本地的结果：nil = 保持本地不变。
    nonisolated struct Local: Equatable {
        var mode: ThemeMode?
        var scheme: String?
        var customRGB: UInt32?
    }

    static func rgb(fromARGB argb: Int64) -> UInt32 {
        UInt32(truncatingIfNeeded: argb) & 0xFFFFFF
    }

    static func argb(fromRGB rgb: UInt32) -> Int64 {
        Int64(0xFF00_0000) | Int64(rgb & 0xFFFFFF)
    }

    /// 主机值 → 本地外观。
    static func decode(_ host: Host) -> Local {
        var local = Local()
        if let raw = host.mode, let mode = ThemeMode(rawValue: raw) { local.mode = mode }
        switch host.scheme {
        case let id? where sharedPresets.contains(id):
            local.scheme = id
        case "custom"?:
            if let argb = host.customARGB {
                let value = rgb(fromARGB: argb)
                if let preset = FluxAccent.presets.first(where: { $0.rgb == value }) {
                    local.scheme = preset.id
                } else {
                    local.scheme = "custom"
                    local.customRGB = value
                }
            } else {
                local.scheme = "custom"
            }
        default:
            break // 缺失 / 未知方案：保持本地
        }
        return local
    }

    /// 本地外观 → 要写的偏好键值（wire 字符串；方案与自定义色同批写入，同 GPUI）。
    static func encode(scheme: String, customRGB: UInt32) -> [String: String] {
        if sharedPresets.contains(scheme) { return [schemeKey: scheme] }
        let value: UInt32 = scheme == "custom" ? customRGB : FluxAccent.preset(scheme).rgb
        return [schemeKey: "custom", customKey: String(argb(fromRGB: value))]
    }
}

/// 外观同步（根级）：主机偏好变化 → 应用到 `AppearanceStore`（`UserDefaults` 兜底仍由它负责）；
/// 用户在外观页的修改 → `agent.preferences.patch`（立即写入，不防抖）。
///
/// - 主机值变化（含切换主机后的首个快照）以主机为准；
/// - 离线（只读）期间的修改只写本机，标脏，重连后若主机值没有变化就补推上去；
/// - 本机主机首次发现偏好里没有外观值而本机已自定义过（旧版本只存 `UserDefaults`）时，把本机值迁移上去。
@MainActor
final class AppearanceSync {
    static let shared = AppearanceSync()

    private weak var container: AppContainer?
    private var writer: ConfigEditor?
    private var lastHost: AppearanceWire.Host?
    private var lastHostID: String?
    private var dirty = false

    private init() {}

    func attach(container: AppContainer) {
        guard self.container == nil else { return }
        self.container = container
        writer = ConfigEditor(transport: ContainerConfigTransport(container))
    }

    /// 偏好分区 / 连接状态 / 主机变化时调用（幂等）。
    func refresh() {
        guard let container, container.store.state.connection == .live else { return }
        let hostID = container.host.id
        let host = AppearanceWire.Host(container.store.state.preferences)
        if hostID != lastHostID || host != lastHost {
            let firstForHost = hostID != lastHostID
            lastHostID = hostID
            lastHost = host
            apply(AppearanceWire.decode(host), to: container.appearance)
            if firstForHost, container.isLocalHost { migrateLocalValues(host, container.appearance) }
            dirty = false
        } else if dirty {
            dirty = false
            pushAll()
        }
    }

    /// 用户改了明暗模式。
    func modeChanged(_ mode: ThemeMode) {
        push([AppearanceWire.modeKey: mode.rawValue])
    }

    /// 用户改了强调色方案 / 自定义色。
    func accentChanged() {
        guard let appearance = container?.appearance else { return }
        push(AppearanceWire.encode(scheme: appearance.scheme, customRGB: appearance.customColor))
    }

    private func push(_ values: [String: String]) {
        guard let container, let writer else { return }
        guard container.store.state.connection == .live else {
            dirty = true // 本机已生效；重连后补推
            return
        }
        writer.setNow(values)
    }

    private func pushAll() {
        guard let appearance = container?.appearance else { return }
        var values = AppearanceWire.encode(scheme: appearance.scheme, customRGB: appearance.customColor)
        values[AppearanceWire.modeKey] = appearance.mode.rawValue
        push(values)
    }

    private func apply(_ local: AppearanceWire.Local, to appearance: AppearanceStore) {
        if let mode = local.mode, mode != appearance.mode { appearance.mode = mode }
        if let custom = local.customRGB, custom != appearance.customColor { appearance.customColor = custom }
        if let scheme = local.scheme, scheme != appearance.scheme { appearance.scheme = scheme }
    }

    /// 旧版本外观只存 `UserDefaults`：主机偏好里没有的键，本机非默认就迁移上去。
    private func migrateLocalValues(_ host: AppearanceWire.Host, _ appearance: AppearanceStore) {
        var values: [String: String] = [:]
        if host.mode == nil, appearance.mode != .system { values[AppearanceWire.modeKey] = appearance.mode.rawValue }
        if host.scheme == nil, appearance.scheme != FluxAccent.blue.id {
            values.merge(AppearanceWire.encode(scheme: appearance.scheme, customRGB: appearance.customColor)) { _, new in new }
        }
        if !values.isEmpty { push(values) }
    }
}

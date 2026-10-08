import FluxDomain
import FluxUI
import Foundation
import Observation

/// `ConfigEditor` 依赖的主机端口（生产实现 = `AppContainer`；测试用假实现）。
@MainActor
protocol ConfigTransport: AnyObject {
    /// 当前主机身份；切换主机后尚未提交的修改丢弃，避免写到另一台主机。
    var hostID: String { get }
    /// `daemon.config` 快照值与版本。
    var config: [String: String] { get }
    var configRevision: UInt64 { get }
    /// `agent.preferences` 分区（值与版本）。
    var preferences: AgentPreferencesDto { get }
    var isReadOnly: Bool { get }
    /// `daemon.config.patch`：版本不符抛 `.conflict`。
    func patchConfig(expectedRevision: UInt64, values: [String: String]) async throws(HostError)
    /// `agent.preferences.patch`：`sync == false` 只写本机偏好。返回写入落定后的偏好版本。
    func patchPreferences(_ values: [String: JSONValue], sync: Bool) async throws(HostError) -> UInt64
    /// `daemon.config.get` 的当前版本（冲突后重放用）；取不到为 nil。
    func fetchConfigRevision() async -> UInt64?
    /// 出错反馈（toast；错误触感由 toast 宿主按语气触发）。
    func report(_ message: String)
}

/// 设置的乐观写入器（Android `ConfigEditor` 的 SwiftUI 版，写路径镜像 Web `writeStore.ts` / GPUI `SettingsStore`）。
///
/// 写入按 `SettingsCatalog` 路由（`SettingsWritePlan`）：
/// - 不在云同步目录的 daemon 键 → `daemon.config.patch {expectedRevision, values}`，`.conflict` 时读取最新版本重放（至多 3 次）；
/// - 落在云同步目录的 daemon 键 → `agent.preferences.patch`，用同步键名与 JSON 类型值（否则下次拉取会把旧云端值覆盖回来）；
/// - agent 偏好 → `agent.preferences.patch`：目录内默认同步，其余 `sync: false`。
///
/// 行为：`set` 先写乐观值 → 同键 250ms 防抖（`immediate` 跳过）→ 提交；提交之间串行，并等到 store 反映新值
/// （偏好以返回的 revision 为准）后才结束；失败回滚乐观值，行内红色说明（3 秒后淡出）+ toast。
/// 写入任务强引用本对象，离开页面不会丢失尚在防抖中的修改。
@MainActor
@Observable
final class ConfigEditor {
    /// 尚未被主机确认的本地值（wire 字符串，已规范化）：界面读取 `optimistic[key] ?? host[key]`。
    private(set) var optimistic: [String: String] = [:]
    /// 尚未被确认的原始 JSON 偏好（如 `custom_categories`）。
    private(set) var optimisticPrefs: [String: JSONValue] = [:]
    /// 最近一次失败的原因（按键）；用于行下方的红色 caption。
    private(set) var failures: [String: String] = [:]

    private enum Edit {
        case wire(String)
        case json(JSONValue)
    }

    private struct Batch {
        var wires: [String: String] = [:]
        var json: [String: JSONValue] = [:]
        var keys: [String] { Array(wires.keys) + Array(json.keys) }
    }

    @ObservationIgnored private let transport: any ConfigTransport
    @ObservationIgnored private let disconnectedText: String
    @ObservationIgnored private let describe: @MainActor (HostError) -> String
    @ObservationIgnored private var timers: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var queued: [String: Edit] = [:]
    @ObservationIgnored private var failureTimers: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var tail: Task<Void, Never>?

    static let debounce: Duration = .milliseconds(250)
    static let confirmTimeout: Duration = .seconds(2)
    static let conflictWait: Duration = .seconds(1)
    static let failureLifetime: Duration = .seconds(3)
    /// `daemon.config.patch` / 偏好通道冲突后的最多重放次数（同 Web `MAX_CONFLICT_RETRIES`）。
    static let maxConflictRetries = 3

    init(
        transport: any ConfigTransport,
        disconnectedText: String = L("localServiceDisconnected"),
        describe: @escaping @MainActor (HostError) -> String = { ErrorText.describe($0) }
    ) {
        self.transport = transport
        self.disconnectedText = disconnectedText
        self.describe = describe
    }

    /// 当前表单视图（乐观值叠在主机值之上）。
    var form: SettingsConfigForm {
        SettingsConfigForm(
            host: transport.config, optimistic: optimistic,
            prefs: transport.preferences.values, optimisticPrefs: optimisticPrefs
        )
    }

    var isReadOnly: Bool { transport.isReadOnly }

    /// 无乐观值时的主机侧有效值（用于忽略空操作）。
    private var hostForm: SettingsConfigForm {
        SettingsConfigForm(host: transport.config, prefs: transport.preferences.values)
    }

    // MARK: 写入

    /// 防抖写入单个键（daemon 配置键或目录内偏好键，值为 wire 字符串）。
    /// 值非法（越界 / 非成员 / 只读键）→ 不发请求，行内显示被拒原因；与主机一致且无待提交修改时忽略。
    /// `immediate`：跳过防抖（外观类需要立即生效）。
    func set(_ key: String, _ value: String, immediate: Bool = false) {
        guard writable() else { return }
        let wire: String
        switch SettingsCatalog.normalize(key, value) {
        case let .failure(error):
            reject(error)
            return
        case let .success(normalized):
            wire = normalized
        }
        if wire == hostForm.value(key), timers[key] == nil, optimistic[key] == nil { return }
        optimistic[key] = wire
        clearFailure(key)
        stage(key, .wire(wire), immediate: immediate)
    }

    /// 防抖写入一个原始 JSON 偏好（`custom_categories` 等无 wire 字符串形态的值）。
    func setPreference(_ key: String, _ value: JSONValue, immediate: Bool = false) {
        guard writable() else { return }
        if let error = SettingsWritePlan.validatePreferenceKey(key) {
            reject(error)
            return
        }
        if value == hostForm.pref(key), timers[key] == nil, optimisticPrefs[key] == nil { return }
        optimisticPrefs[key] = value
        clearFailure(key)
        stage(key, .json(value), immediate: immediate)
    }

    /// 立即写入多个键（对话框确认等一次性动作）；整批合法才发出。
    func setNow(_ values: [String: String], preferences: [String: JSONValue] = [:]) {
        guard writable() else { return }
        var normalized: [String: String] = [:]
        for (key, value) in values {
            switch SettingsCatalog.normalize(key, value) {
            case let .failure(error):
                reject(error)
                return
            case let .success(wire):
                normalized[key] = wire
            }
        }
        for key in preferences.keys {
            if let error = SettingsWritePlan.validatePreferenceKey(key) {
                reject(error)
                return
            }
        }
        var batch = Batch()
        for (key, wire) in normalized {
            cancelPending(key)
            optimistic[key] = wire
            clearFailure(key)
            batch.wires[key] = wire
        }
        for (key, value) in preferences {
            cancelPending(key)
            optimisticPrefs[key] = value
            clearFailure(key)
            batch.json[key] = value
        }
        enqueue(batch, host: transport.hostID)
    }

    /// 等待所有已排队 / 防抖中的写入结束（测试与「离开页面冲刷」用）。
    func settle() async {
        while !timers.isEmpty {
            let pending = Array(timers.values)
            for task in pending { await task.value }
        }
        await tail?.value
    }

    private func writable() -> Bool {
        guard !transport.isReadOnly else {
            transport.report(disconnectedText)
            return false
        }
        return true
    }

    private func cancelPending(_ key: String) {
        timers[key]?.cancel()
        timers[key] = nil
        queued[key] = nil
    }

    private func stage(_ key: String, _ edit: Edit, immediate: Bool) {
        let host = transport.hostID
        timers[key]?.cancel()
        queued[key] = edit
        if immediate {
            timers[key] = nil
            flush(key, host: host)
            return
        }
        timers[key] = Task { [self] in
            do {
                try await Task.sleep(for: Self.debounce)
            } catch {
                return // 被同键的新修改取代
            }
            guard !Task.isCancelled else { return }
            timers[key] = nil
            flush(key, host: host)
        }
    }

    private func flush(_ key: String, host: String) {
        guard let edit = queued.removeValue(forKey: key) else { return }
        var batch = Batch()
        switch edit {
        case let .wire(wire): batch.wires[key] = wire
        case let .json(json): batch.json[key] = json
        }
        enqueue(batch, host: host)
    }

    // MARK: 提交

    private func enqueue(_ batch: Batch, host: String) {
        let previous = tail
        tail = Task { [self] in
            await previous?.value
            await commit(batch, host: host)
        }
    }

    private func commit(_ batch: Batch, host: String) async {
        defer { release(batch) }
        guard transport.hostID == host else { return } // 期间切换了主机：丢弃
        var plan: SettingsWritePlan
        switch SettingsWritePlan.make(batch.wires) {
        case let .success(made): plan = made
        case let .failure(error):
            reject(error)
            return
        }
        for (key, value) in batch.json { plan.addPreference(key, value) }

        var failedKeys: [String] = []
        var firstError: HostError?
        var confirmations: [@MainActor () -> Bool] = []

        func record(_ error: HostError, _ keys: [String]) {
            firstError = firstError ?? error
            failedKeys += keys
        }

        if !plan.daemon.isEmpty {
            do throws(HostError) {
                try await patchDaemonWithRetry(plan.daemon)
                let expected = plan.daemon
                confirmations.append { [transport] in
                    expected.allSatisfy { Self.configMatches(transport.config[$0.key], $0.value) }
                }
            } catch {
                record(error, Array(plan.daemon.keys))
            }
        }
        for sync in [true, false] {
            let values = sync ? plan.syncedPreferences : plan.localPreferences
            guard !values.isEmpty else { continue }
            let uiKeys = values.keys.map { plan.syncedDaemonKeys[$0] ?? $0 }
            do throws(HostError) {
                let revision = try await patchPreferencesWithRetry(values, sync: sync)
                let daemonExpect = values.compactMap { name, json -> (String, String)? in
                    guard let daemonKey = plan.syncedDaemonKeys[name], let wire = SettingsCatalog.wire(fromJSON: json) else { return nil }
                    return (daemonKey, wire)
                }
                confirmations.append { [transport] in
                    transport.preferences.revision >= revision
                        && daemonExpect.allSatisfy { Self.configMatches(transport.config[$0.0], $0.1) }
                }
            } catch {
                record(error, uiKeys)
            }
        }

        if !confirmations.isEmpty {
            await waitUntil(timeout: Self.confirmTimeout) { confirmations.allSatisfy { $0() } }
        }
        if let firstError {
            let message = describe(firstError)
            for key in failedKeys where isStillPending(key, in: batch) {
                showFailure(key, message)
            }
            transport.report(message)
        }
    }

    /// 乐观值仍是本批写入的值（没有被之后的修改取代）。
    private func isStillPending(_ key: String, in batch: Batch) -> Bool {
        if let wire = batch.wires[key] { return optimistic[key] == wire }
        if let json = batch.json[key] { return optimisticPrefs[key] == json }
        return false
    }

    /// 提交结束（成功等到 store 反映 / 失败回滚）：丢弃仍等于本批值且没有更新修改在途的乐观值。
    private func release(_ batch: Batch) {
        for (key, value) in batch.wires where optimistic[key] == value && timers[key] == nil && queued[key] == nil {
            optimistic[key] = nil
        }
        for (key, value) in batch.json where optimisticPrefs[key] == value && timers[key] == nil && queued[key] == nil {
            optimisticPrefs[key] = nil
        }
    }

    private func patchDaemonWithRetry(_ values: [String: String]) async throws(HostError) {
        var retries = 0
        var hint: UInt64 = 0
        while true {
            let revision = max(transport.configRevision, hint)
            do throws(HostError) {
                try await transport.patchConfig(expectedRevision: revision, values: values)
                return
            } catch {
                guard error.code == .conflict, retries < Self.maxConflictRetries else { throw error }
                retries += 1
                // HostError 不携带冲突回带的 revision：读取最新版本；读不到就等 store 推进。
                if let fresh = await transport.fetchConfigRevision(), fresh != revision {
                    hint = fresh
                } else {
                    await waitUntil(timeout: Self.conflictWait) { [self] in transport.configRevision != revision }
                }
            }
        }
    }

    private func patchPreferencesWithRetry(_ values: [String: JSONValue], sync: Bool) async throws(HostError) -> UInt64 {
        var retries = 0
        while true {
            do throws(HostError) {
                return try await transport.patchPreferences(values, sync: sync)
            } catch {
                // 同步的 daemon 键由 agent 代写 daemon，版本竞争时回报 conflict；偏好本身是逐键 LWW。
                guard error.code == .conflict, retries < Self.maxConflictRetries else { throw error }
                retries += 1
                do {
                    try await Task.sleep(for: .milliseconds(200))
                } catch {
                    throw HostError(.cancelled)
                }
            }
        }
    }

    /// 主机值与期望值相同（浮点按数值比较：daemon 把 `1.0` 规范成 `1`）。
    static func configMatches(_ actual: String?, _ expected: String) -> Bool {
        guard let actual else { return false }
        if actual == expected { return true }
        if let a = Double(actual), let e = Double(expected) { return a == e }
        return false
    }

    /// 轮询（50ms）直到条件成立或超时；store 的发布节奏本身是 ≤100ms 合并，无需更精细的订阅。
    private func waitUntil(timeout: Duration, _ condition: @MainActor () -> Bool) async {
        let clock = ContinuousClock()
        let deadline = clock.now + timeout
        while !condition(), clock.now < deadline {
            do {
                try await Task.sleep(for: .milliseconds(50))
            } catch {
                return
            }
        }
    }

    // MARK: 失败说明

    /// 校验失败（不发请求）：行内说明 + toast。
    private func reject(_ error: SettingsValidationError) {
        let message = L("localServiceInvalidArgument")
        showFailure(error.key, message)
        transport.report(message)
    }

    private func showFailure(_ key: String, _ message: String) {
        failures[key] = message
        failureTimers[key]?.cancel()
        failureTimers[key] = Task { [self] in
            do {
                try await Task.sleep(for: Self.failureLifetime)
            } catch {
                return
            }
            failures[key] = nil
            failureTimers[key] = nil
        }
    }

    private func clearFailure(_ key: String) {
        failureTimers[key]?.cancel()
        failureTimers[key] = nil
        failures[key] = nil
    }
}

// MARK: - 生产端口

/// `AppContainer` 适配：读 `store.state`，写 `session`，反馈走 toast。
@MainActor
final class ContainerConfigTransport: ConfigTransport {
    private let container: AppContainer

    init(_ container: AppContainer) {
        self.container = container
    }

    var hostID: String { container.host.id }
    var config: [String: String] { container.store.state.config }
    var configRevision: UInt64 { container.store.state.configRevision }
    var preferences: AgentPreferencesDto { container.store.state.preferences }
    var isReadOnly: Bool { container.store.state.isReadOnly }

    func patchConfig(expectedRevision: UInt64, values: [String: String]) async throws(HostError) {
        try await container.session.patchConfig(expectedRevision: expectedRevision, values: values)
    }

    func patchPreferences(_ values: [String: JSONValue], sync: Bool) async throws(HostError) -> UInt64 {
        let params = PreferencesPatchParams(values: values, sync: sync ? nil : false)
        let result: PreferencesPatchResult = try await container.session.call(HostMethod.agentPreferencesPatch, params: params)
        return result.revision
    }

    func fetchConfigRevision() async -> UInt64? {
        let snapshot: DaemonConfigSnapshotDto? = try? await container.session.call(HostMethod.daemonConfigGet)
        return snapshot?.revision
    }

    func report(_ message: String) {
        container.toasts.show(text: message, tone: .error)
    }
}

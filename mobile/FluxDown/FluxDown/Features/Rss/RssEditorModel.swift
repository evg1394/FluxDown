import FluxDomain
import Foundation
import Observation

/// R3 订阅编辑器的表单与流程（同 Web `SourceEditor` / GPUI `editor.rs`）：
/// 新建须先验证 feed（验证结果绑定其请求参数，URL / Cookie / UA / 代理变化即失效），
/// 编辑保留只读运行态字段并整体写回（`updateSource` 需要完整订阅）。
@MainActor
@Observable
final class RssEditorModel {
    nonisolated enum Tab: Int, CaseIterable, Identifiable {
        case basic, filter, advanced
        var id: Int { rawValue }

        var titleKey: String {
            switch self {
            case .basic: "rssTabBasic"
            case .filter: "rssTabFilter"
            case .advanced: "rssTabAdvanced"
            }
        }
    }

    nonisolated enum Load: Equatable {
        case loading
        case ready
        case failed(String)
    }

    /// 表单字段（文本框保持原始输入，保存时再校验 / 转换）。
    nonisolated struct Form: Equatable {
        var name = ""
        var url = ""
        var saveDir = ""
        var include = ""
        var exclude = ""
        var sizeMin = ""
        var sizeMax = ""
        var cookies = ""
        var userAgent = ""
        var proxyUrl = ""
        var maxPerFetch = String(RssSourceDetail.defaultMaxPerFetch)
        var interval = RssSourceDetail.defaultIntervalMinutes
        var queueId = TaskQueue.main
        var enabled = true
        var autoDownload = true
        var startPaused = false
        var useRegex = false
        var smartEpisode = false
        var sendReferer = true
        var notifyOnDownload = true

        init() {}

        init(_ source: RssSourceDetail) {
            name = source.name
            url = source.url
            saveDir = source.saveDir
            include = source.includePattern
            exclude = source.excludePattern
            sizeMin = RssSizeLiteral.format(source.sizeMinBytes)
            sizeMax = RssSizeLiteral.format(source.sizeMaxBytes)
            cookies = source.cookies
            userAgent = source.userAgent
            proxyUrl = source.proxyUrl
            maxPerFetch = String(source.effectiveMaxPerFetch)
            interval = source.effectiveIntervalMinutes
            queueId = source.queueId.isEmpty ? TaskQueue.main : source.queueId
            enabled = source.enabled
            autoDownload = source.autoDownload
            startPaused = source.startPaused
            useRegex = source.useRegex
            smartEpisode = source.smartEpisode
            sendReferer = source.sendReferer
            notifyOnDownload = source.notifyOnDownload
        }
    }

    /// 验证状态（`validate` 是慢方法：按钮转圈，不阻塞其它输入）。
    nonisolated enum Validation: Equatable {
        case idle
        case running
        case passed(title: String, itemCount: Int)
        case failed(String)
    }

    /// 校验 / RPC 错误：落在哪个页签与字段下。
    nonisolated struct FieldError: Equatable {
        var tab: Tab
        var message: String
    }

    let target: RssEditorTarget
    var form = Form()
    var tab: Tab = .basic
    private(set) var load: Load
    private(set) var validation: Validation = .idle
    private(set) var saving = false
    var error: FieldError?
    /// 在编辑的现有订阅（最新副本）；新建为 nil。
    private(set) var original: RssSourceDetail?

    @ObservationIgnored private unowned let container: AppContainer
    @ObservationIgnored private var initialForm = Form()
    /// 最近一次通过验证时的请求；与当前输入不同即失效。
    @ObservationIgnored private var validatedRequest: RssValidateRequest?
    @ObservationIgnored private var validateTask: Task<Void, Never>?

    init(target: RssEditorTarget, container: AppContainer) {
        self.target = target
        self.container = container
        switch target {
        case .create: load = .ready
        case .edit: load = .loading
        }
    }

    var isEditing: Bool {
        if case .edit = target { return true }
        return false
    }

    var isDirty: Bool { form != initialForm }

    var request: RssValidateRequest {
        RssValidateRequest(
            url: form.url.trimmingCharacters(in: .whitespacesAndNewlines),
            cookies: form.cookies.trimmingCharacters(in: .whitespacesAndNewlines),
            userAgent: form.userAgent.trimmingCharacters(in: .whitespacesAndNewlines),
            proxyUrl: form.proxyUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        )
    }

    /// 当前输入是否仍对应一次通过的验证。
    var isValidated: Bool {
        guard case .passed = validation, let validatedRequest else { return false }
        return validatedRequest == request
    }

    /// 页签与基本页签其余字段何时可见（同 Android `RssEditorSheet`）：编辑直接可见；
    /// 新建须先验证通过，之后才能进入过滤规则 / 高级页签。
    var showsDetails: Bool { isEditing || isValidated }

    /// 实际呈现的页签：验证未通过（含改动 Cookie / UA / 代理使验证失效）时固定在基本页签。
    var visibleTab: Tab { showsDetails ? tab : .basic }

    var isValidating: Bool { validation == .running }
    var canValidate: Bool { !isValidating && !saving && !request.url.isEmpty }
    var canSave: Bool { load == .ready && !saving && !isValidating && (isEditing || isValidated) }

    // MARK: 加载（编辑）

    /// 取最新副本（`listSources`）作为整体写回的底稿。
    func loadIfNeeded() async {
        guard case let .edit(sourceId) = target, original == nil else { return }
        load = .loading
        let session = container.session
        do throws(HostError) {
            let list: [RssSourceDetail] = try await session.call(HostMethod.daemonRssListSources)
            guard let found = list.first(where: { $0.sourceId == sourceId }) else {
                load = .failed(L("localServiceActionFailed"))
                return
            }
            original = found
            form = Form(found)
            initialForm = form
            load = .ready
        } catch {
            load = .failed(ErrorText.describe(error))
        }
    }

    // MARK: 表单变更

    /// 会改变验证请求的字段（URL / Cookie / UA / 代理）变化：清错误，验证结果随 `isValidated` 自动失效。
    func requestFieldChanged() {
        error = nil
        switch validation {
        case .failed: validation = .idle
        case .passed where !isValidated: validation = .idle
        case .idle, .running, .passed: break
        }
    }

    // MARK: 验证

    func validate() {
        guard canValidate else { return }
        let sent = request
        guard !sent.url.isEmpty else {
            fail(.basic, "rssFeedRequired")
            return
        }
        validation = .running
        validatedRequest = nil
        error = nil
        let session = container.session
        validateTask?.cancel()
        validateTask = Task { [weak self] in
            do throws(HostError) {
                let response: RssValidateResponse = try await session.call(HostMethod.daemonRssValidate, params: sent)
                guard let self, !Task.isCancelled else { return }
                // 编辑过程中即使旧请求晚到，也不接受与当前输入不同的结果。
                guard self.request == sent else {
                    self.validation = .idle
                    return
                }
                if response.error.isEmpty {
                    self.validatedRequest = sent
                    self.validation = .passed(title: response.feedTitle, itemCount: response.items.count)
                    self.tab = .basic
                } else {
                    self.validation = .failed(response.error)
                }
            } catch {
                guard let self, !Task.isCancelled else { return }
                if self.request == sent {
                    self.validation = .failed(ErrorText.describe(error))
                } else {
                    self.validation = .idle
                }
            }
        }
    }

    // MARK: 保存

    /// 校验并写入。@return 成功后的订阅 id（新建时来自 `RssCreateResult`）。
    func save() async -> Bool {
        guard canSave else { return false }
        let sent = request
        if sent.url.isEmpty { return fail(.basic, "rssFeedRequired") }
        if !isEditing, !isValidated { return fail(.basic, "rssValidateBeforeSave") }
        guard let min = sizeBytes(form.sizeMin), let max = sizeBytes(form.sizeMax) else {
            return fail(.filter, "rssInvalidNumber")
        }
        if min > 0, max > 0, max < min { return fail(.filter, "rssInvalidSizeRange") }
        guard let limit = fetchLimit(form.maxPerFetch) else { return fail(.advanced, "rssInvalidNumber") }
        if !form.saveDir.isEmpty, !isValidSaveDir(form.saveDir) { return fail(.basic, "mobileSaveDirInvalid") }

        let trimmedName = form.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let feedTitle: String
        if case let .passed(title, _) = validation { feedTitle = title } else { feedTitle = "" }
        let name = !trimmedName.isEmpty ? trimmedName : (isEditing ? "" : feedTitle)

        var input = original ?? RssSourceDetail(url: sent.url)
        input.url = sent.url
        input.name = name
        input.enabled = form.enabled
        input.autoDownload = form.autoDownload
        input.startPaused = form.startPaused
        input.intervalMinutes = form.interval
        input.queueId = form.queueId
        input.saveDir = form.saveDir.trimmingCharacters(in: .whitespacesAndNewlines)
        input.includePattern = form.include.trimmingCharacters(in: .whitespacesAndNewlines)
        input.excludePattern = form.exclude.trimmingCharacters(in: .whitespacesAndNewlines)
        input.useRegex = form.useRegex
        input.smartEpisode = form.smartEpisode
        input.sizeMinBytes = min
        input.sizeMaxBytes = max
        input.cookies = sent.cookies
        input.userAgent = sent.userAgent
        input.proxyUrl = sent.proxyUrl
        input.maxPerFetch = limit
        input.sendReferer = form.sendReferer
        input.notifyOnDownload = form.notifyOnDownload

        error = nil
        saving = true
        defer { saving = false }
        let session = container.session
        do throws(HostError) {
            if case let .edit(sourceId) = target {
                input.sourceId = sourceId
                try await session.callVoid(HostMethod.daemonRssUpdateSource, params: input)
            } else {
                let _: RssCreateResult = try await session.call(HostMethod.daemonRssCreateSource, params: input)
            }
            return true
        } catch {
            self.error = FieldError(tab: tab, message: Self.describe(error))
            return false
        }
    }

    func cancelValidation() {
        validateTask?.cancel()
    }

    // MARK: 内部

    @discardableResult
    private func fail(_ tab: Tab, _ key: String) -> Bool {
        self.tab = tab
        error = FieldError(tab: tab, message: L(key))
        return false
    }

    /// 空体积 = 不限（0）；非法为 nil。
    private func sizeBytes(_ text: String) -> Int64? {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0 : RssSizeLiteral.parse(text)
    }

    /// 抓取上限：1...100 的整数。
    private func fetchLimit(_ text: String) -> Int32? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.allSatisfy({ $0 >= "0" && $0 <= "9" }),
              let value = Int32(trimmed), RssSourceDetail.maxPerFetchRange.contains(value)
        else { return nil }
        return value
    }

    /// 本机服务拒绝了该值时带上服务端给出的细节（`localServiceInvalidArgument` + detail）。
    private static func describe(_ error: HostError) -> String {
        let text = ErrorText.describe(error)
        if error.code == .invalidArgument, !error.message.isEmpty { return text + ": " + error.message }
        return text
    }
}

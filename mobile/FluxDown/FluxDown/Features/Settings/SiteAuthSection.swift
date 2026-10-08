import FluxDomain
import FluxUI
import SwiftUI

// 已保存的网站凭据（03-settings §9.3）：`daemon.siteAuth.*`。列表只含站点与用户名；明文密码只在编辑表单里经
// `daemon.siteAuth.get` 取回，不进入列表状态、不写日志。

/// 编辑表单的目标：新建 / 编辑已有站点。
nonisolated enum SiteAuthSheetTarget: Identifiable, Equatable {
    case add
    case edit(SiteAuthEntryDto)

    var id: String {
        switch self {
        case .add: "add"
        case let .edit(entry): "edit:\(entry.site)"
        }
    }
}

struct SiteAuthSection: View {
    let model: SiteAuthModel

    @Environment(AppContainer.self) private var container
    @Environment(HostStore.self) private var store

    @State private var query = ""
    @State private var sheet: SiteAuthSheetTarget?
    @State private var confirmClear = false
    @State private var successTick = 0

    private var readOnly: Bool { store.state.isReadOnly }
    private var showsSearch: Bool { SiteAuthFilter.showsSearch(count: model.entries.count) }
    /// 搜索框消失（条数降到阈值以下）时查询随之失效。
    private var activeQuery: String { showsSearch ? query : "" }

    var body: some View {
        let visible = SiteAuthFilter.filter(model.entries, query: activeQuery)
        Section {
            titleRow(visible: visible.count)
            switch model.phase {
            case .loading:
                EmptyView()
            case let .failed(message):
                failedRow(message)
            case .loaded:
                if model.entries.isEmpty {
                    emptyRow(L("settingsSiteAuthEmpty"), systemImage: "key")
                } else {
                    if showsSearch { searchRow }
                    if visible.isEmpty {
                        emptyRow(L("settingsSiteAuthNoMatch"), systemImage: FluxSymbol.search)
                    }
                    ForEach(visible) { entry in entryRow(entry) }
                }
            }
            SettingsActionRow(title: L("settingsSiteAuthAdd"), systemImage: FluxSymbol.add) { sheet = .add }
                .disabled(readOnly)
                .settingsRow(NetworkRow.siteAuthAdd.id)
            SettingsActionRow(
                title: L("settingsSiteAuthClearAll"), systemImage: FluxSymbol.delete, role: .destructive,
                isRunning: model.isClearing
            ) { confirmClear = true }
                .disabled(readOnly || model.entries.isEmpty)
                .alert(L("settingsSiteAuthClearAll"), isPresented: $confirmClear) {
                    Button(L("settingsSiteAuthClearAll"), role: .destructive) { clearAll() }
                    Button(L("cancel"), role: .cancel) {}
                } message: {
                    Text(L("mobileSiteAuthClearConfirm", ["n": model.entries.count]))
                }
                .settingsRow(NetworkRow.siteAuthClear.id)
        } footer: {
            if !container.isLocalHost {
                Text(L("mobileSiteAuthRemoteNote", ["host": container.host.displayName]))
            }
        }
        .fluxAnimation(.smooth, value: model.entries)
        .fluxAnimation(.smooth, value: model.phase)
        .sensoryFeedback(FluxHaptic.success.sensoryFeedback, trigger: successTick)
        .sheet(item: $sheet) { target in
            SiteAuthEditSheet(target: target, model: model) { successTick += 1 }
        }
        
    }

    // MARK: 行

    /// 标题 + 说明 + 右侧状态（加载中转圈 / 条数 / 过滤后条数）。
    private func titleRow(visible: Int) -> some View {
        let item = NetworkRow.siteAuth
        return SettingsTrailingLayout(title: L(item.titleKey), detail: item.detailKey.map { L($0) }) {
            if model.phase == .loading {
                ProgressView()
            } else if model.phase == .loaded, !model.entries.isEmpty {
                Text(countText(visible: visible))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                    .contentTransition(.numericText())
            }
        }
        .settingsRow(item.id)
    }

    private func countText(visible: Int) -> String {
        if activeQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return L("settingsSiteAuthCount", ["n": model.entries.count])
        }
        return L("settingsSiteAuthCountFiltered", ["m": visible, "n": model.entries.count])
    }

    private var searchRow: some View {
        HStack(spacing: 8) {
            Image(systemName: FluxSymbol.search)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            TextField(L("settingsSiteAuthSearchHint"), text: $query)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("mobileSiteAuthSearchClear"))
            }
        }
        .frame(minHeight: 44)
    }

    private func entryRow(_ entry: SiteAuthEntryDto) -> some View {
        Button {
            sheet = .edit(entry)
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.site)
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .truncationMode(.middle)
                if !entry.user.isEmpty {
                    Text(entry.user)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(readOnly)
        .accessibilityElement(children: .combine)
        .accessibilityHint(L("settingsSiteAuthEdit"))
        .accessibilityAction(named: L("settingsSiteAuthDelete")) { if !readOnly { delete(entry) } }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            if !readOnly {
                Button(role: .destructive) {
                    delete(entry)
                } label: {
                    Label(L("settingsSiteAuthDelete"), systemImage: FluxSymbol.delete)
                }
            }
        }
        .contextMenu {
            if !readOnly {
                Button {
                    sheet = .edit(entry)
                } label: {
                    Label(L("settingsSiteAuthEdit"), systemImage: FluxSymbol.edit)
                }
                Button(role: .destructive) {
                    delete(entry)
                } label: {
                    Label(L("settingsSiteAuthDelete"), systemImage: FluxSymbol.delete)
                }
            }
        }
    }

    private func emptyRow(_ text: String, systemImage: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: systemImage)
                .font(.title2)
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
            Text(text)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, minHeight: 72)
        .accessibilityElement(children: .combine)
    }

    private func failedRow(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            SettingsStatusLine(text: message, tone: .failure)
            SettingsActionRow(title: L("mobileRetry"), systemImage: FluxSymbol.retry) { reload() }
        }
    }

    // MARK: 动作

    private func reload() {
        let session = container.session
        let hostID = container.host.id
        Task { await model.load(using: session, hostID: hostID) }
    }

    /// 删除不二次确认（同 PC）；服务端返回新列表，失败时列表已恢复，仅提示。
    private func delete(_ entry: SiteAuthEntryDto) {
        let session = container.session
        Task {
            do throws(HostError) {
                try await model.delete(entry.site, using: session)
                successTick += 1
            } catch {
                container.toasts.show(text: ErrorText.describe(error), tone: .error)
            }
        }
    }

    private func clearAll() {
        let session = container.session
        Task {
            do throws(HostError) {
                try await model.clearAll(using: session)
                successTick += 1
            } catch {
                container.toasts.show(text: ErrorText.describe(error), tone: .error)
            }
        }
    }
}

// MARK: - 编辑表单

/// 添加 / 编辑凭据：站点、用户名、密码（`SecureField` + 显示 / 隐藏）。
/// 编辑时站点是主键只读；明文密码经 `daemon.siteAuth.get` 取回，仅存活于本表单。
struct SiteAuthEditSheet: View {
    let target: SiteAuthSheetTarget
    let model: SiteAuthModel
    let onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(AppContainer.self) private var container

    private nonisolated enum Phase: Equatable {
        case loading, ready, failed(String)
    }

    @State private var site = ""
    @State private var user = ""
    @State private var pass = ""
    @State private var phase: Phase = .ready
    @State private var isSaving = false
    @State private var saveError: String?
    @State private var errorTick = 0
    @State private var revealed = false

    private var editingSite: String? {
        if case let .edit(entry) = target { return entry.site }
        return nil
    }

    private var canSave: Bool {
        phase == .ready && !isSaving && SiteAuthFilter.canSave(site: site, user: user)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    siteField
                    LabeledContent(L("taskHttpAuthUser")) {
                        TextField("", text: $user)
                            .multilineTextAlignment(.trailing)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .submitLabel(.next)
                            .frame(minHeight: 44)
                    }
                    LabeledContent(L("taskHttpAuthPassword")) { passwordField }
                } footer: {
                    if editingSite == nil {
                        Text(L("settingsSiteAuthSitePlaceholder"))
                    }
                }
                .disabled(phase == .loading)

                switch phase {
                case .loading:
                    Section { HStack(spacing: 10) { ProgressView(); Text(L("mobileLoading")).foregroundStyle(.secondary) } }
                case let .failed(message):
                    Section {
                        SettingsStatusLine(text: message, tone: .failure)
                        SettingsActionRow(title: L("mobileRetry"), systemImage: FluxSymbol.retry) {
                            Task { await loadCredential() }
                        }
                    }
                case .ready:
                    EmptyView()
                }
                if let saveError {
                    Section { SettingsStatusLine(text: saveError, tone: .failure) }
                }
            }
            .navigationTitle(L(editingSite == nil ? "settingsSiteAuthAdd" : "settingsSiteAuthEdit"))
            .navigationBarTitleDisplayMode(.inline)
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isSaving {
                        ProgressView()
                    } else {
                        Button(L("settingsSiteAuthSave")) { save() }
                            .disabled(!canSave)
                    }
                }
            }
            .sensoryFeedback(FluxHaptic.error.sensoryFeedback, trigger: errorTick)
            .fluxAnimation(.smooth, value: phase)
            .fluxAnimation(.smooth, value: saveError)
            .interactiveDismissDisabled(isSaving)
            .task { await prepare() }
        }
        .presentationDetents([.medium, .large])
    }

    @ViewBuilder
    private var siteField: some View {
        if let editingSite {
            LabeledContent(L("settingsSiteAuthSite")) {
                Text(editingSite)
                    .font(.fluxMono)
                    .textSelection(.enabled)
                    .multilineTextAlignment(.trailing)
            }
        } else {
            LabeledContent(L("settingsSiteAuthSite")) {
                TextField("", text: $site, prompt: Text(L("settingsSiteAuthSitePlaceholder")))
                    .multilineTextAlignment(.trailing)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .frame(minHeight: 44)
            }
        }
    }

    private var passwordField: some View {
        HStack(spacing: 4) {
            Group {
                if revealed {
                    TextField("", text: $pass)
                } else {
                    SecureField("", text: $pass)
                }
            }
            .multilineTextAlignment(.trailing)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.done)
            .frame(minHeight: 44)
            Button {
                revealed.toggle()
            } label: {
                Image(systemName: revealed ? "eye.slash" : "eye")
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L(revealed ? "mobileHidePassword" : "mobileShowPassword"))
        }
    }

    // MARK: 加载 / 保存

    private func prepare() async {
        guard case let .edit(entry) = target else { return }
        site = entry.site
        user = entry.user
        await loadCredential()
    }

    /// 编辑：取回明文密码（`daemon.siteAuth.get`）。站点已被别处删除 → 保留列表里的用户名、密码留空。
    private func loadCredential() async {
        guard let editingSite else { return }
        phase = .loading
        do throws(HostError) {
            let credential = try await model.credential(for: editingSite, using: container.session)
            guard !Task.isCancelled else { return }
            if let credential {
                user = credential.user
                pass = credential.pass
            }
            phase = .ready
        } catch {
            guard !Task.isCancelled else { return }
            phase = .failed(ErrorText.describe(error))
        }
    }

    private func save() {
        guard canSave else { return }
        let request = SiteAuthSaveRequest(
            site: (editingSite ?? site).trimmingCharacters(in: .whitespacesAndNewlines),
            user: user.trimmingCharacters(in: .whitespacesAndNewlines),
            pass: pass
        )
        let session = container.session
        isSaving = true
        saveError = nil
        Task {
            defer { isSaving = false }
            do throws(HostError) {
                try await model.save(request, using: session)
                onSaved()
                dismiss()
            } catch {
                saveError = ErrorText.describe(error)
                errorTick += 1
            }
        }
    }
}

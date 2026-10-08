import FluxDomain
import FluxUI
import SwiftUI

/// R3 订阅编辑器（sheet · large）：基本 / 过滤规则 / 高级三个页签。
/// 新建须先验证 feed（`daemon.rss.validate`，慢方法：按钮转圈、不阻塞其它输入）：验证前只显示地址与影响验证请求的
/// Cookie / UA / 代理，通过后才显示页签与其余字段（同 Android）；编辑模式全部直接可见，底部有「删除订阅」。有未保存修改时拦截下拉关闭并确认。
/// 过滤规则由主机引擎求值，这里只收集规则字段，不做客户端预览。
struct RssEditorSheet: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var model: RssEditorModel
    @State private var confirmDiscard = false
    @State private var confirmDelete = false
    @State private var pickingDirectory = false

    private let rss: RssModel

    init(target: RssEditorTarget, container: AppContainer, rss: RssModel) {
        _model = State(initialValue: RssEditorModel(target: target, container: container))
        self.rss = rss
    }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(L(model.isEditing ? "rssManageTitle" : "rssAddSource"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { toolbarContent }
                .navigationDestination(isPresented: $pickingDirectory) {
                    RssDirectoryPicker(
                        initialPath: model.form.saveDir.trimmingCharacters(in: .whitespacesAndNewlines),
                        rootPath: container.isLocalHost ? LocalPaths.documents.path : nil
                    ) { path in
                        model.form.saveDir = path
                    }
                }
        }
        .task { await model.loadIfNeeded() }
        .interactiveDismissDisabled(model.isDirty || model.saving)
        .presentationDetents([.large])
        .presentationSizing(.form)
    }

    // MARK: 骨架

    @ViewBuilder
    private var content: some View {
        switch model.load {
        case .loading:
            ProgressView().controlSize(.large).frame(maxWidth: .infinity, maxHeight: .infinity)
        case let .failed(message):
            ContentUnavailableView {
                Label(L("rssEmptyError"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            } actions: {
                Button(L("mobileRetry")) { Task { await model.loadIfNeeded() } }
                    .buttonStyle(.bordered)
            }
        case .ready:
            Form {
                if let error = model.error, error.tab == model.visibleTab {
                    Section {
                        Label(error.message, systemImage: FluxSymbol.failure)
                            .font(.footnote)
                            .foregroundStyle(Color.fdStatusFailedText)
                            .accessibilityAddTraits(.isStaticText)
                    }
                }
                switch model.visibleTab {
                case .basic: basicTab
                case .filter: filterTab
                case .advanced: advancedTab
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .safeAreaBar(edge: .top, spacing: 0) {
                if model.showsDetails {
                    Picker(L("rssTabBasic"), selection: $model.tab) {
                        ForEach(RssEditorModel.Tab.allCases) { tab in
                            Text(L(tab.titleKey)).tag(tab)
                        }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                }
            }
            .fluxAnimation(.smooth, value: model.showsDetails)
        }
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button(L("cancel")) {
                if model.isDirty { confirmDiscard = true } else { dismiss() }
            }
            .disabled(model.saving)
            .alert(L("mobileRssDiscardTitle"), isPresented: $confirmDiscard) {
                Button(L("mobileDiscard"), role: .destructive) { dismiss() }
                Button(L("mobileRssKeepEditing"), role: .cancel) {}
            }
        }
        ToolbarItem(placement: .confirmationAction) {
            if model.saving {
                ProgressView()
            } else {
                Button(L(model.isEditing ? "confirm" : "rssWizardSubscribe")) { save() }
                    .disabled(!model.canSave)
            }
        }
    }

    private var displayName: String {
        let name = model.form.name.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? model.form.url : name
    }

    // MARK: 基本

    @ViewBuilder
    private var basicTab: some View {
        Section {
            HStack(spacing: 8) {
                TextField(L("rssUrlHint"), text: $model.form.url)
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .onSubmit { model.validate() }
                    .onChange(of: model.form.url) { model.requestFieldChanged() }
                PasteButton(payloadType: String.self) { strings in
                    guard let text = strings.first else { return }
                    Task { @MainActor in
                        model.form.url = text.trimmingCharacters(in: .whitespacesAndNewlines)
                    }
                }
                .labelStyle(.iconOnly)
                .buttonBorderShape(.capsule)
                .disabled(model.saving)
            }
            Button {
                model.validate()
            } label: {
                HStack(spacing: 8) {
                    if model.isValidating { ProgressView() }
                    Text(L(model.isValidating ? "rssWizardValidating" : "rssWizardValidate"))
                }
            }
            .disabled(!model.canValidate)
            validationRow
        } header: {
            Text(L("rssUrlLabel"))
        }

        // 新建验证前只露出会进入验证请求的字段（Cookie / UA / 代理）；通过后它们回到「高级」页签
        if !model.showsDetails {
            requestSection(titled: true)
        }

        if model.showsDetails {
            Section {
                TextField(L("rssNameHint"), text: $model.form.name)
                    .submitLabel(.next)
            } header: {
                Text(L("rssNameLabel"))
            }

            Section {
                Picker(L("rssIntervalLabel"), selection: $model.form.interval) {
                    ForEach(intervalChoices, id: \.self) { minutes in
                        Text(RssFormat.intervalText(minutes: minutes)).tag(minutes)
                    }
                }
                Picker(L("rssQueueLabel"), selection: $model.form.queueId) {
                    ForEach(queueChoices, id: \.id) { choice in
                        Text(choice.label).tag(choice.id)
                    }
                }
            } footer: {
                Text(L("rssIntervalHint"))
            }

            Section {
                HStack(spacing: 8) {
                    TextField(L("rssSaveDirHint"), text: $model.form.saveDir)
                        .font(.callout.monospaced())
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.next)
                    Button {
                        pickingDirectory = true
                    } label: {
                        Label(L("browse"), systemImage: FluxSymbol.folder)
                            .labelStyle(.iconOnly)
                    }
                    .buttonStyle(.borderless)
                }
                if !model.form.saveDir.isEmpty, !isValidSaveDir(model.form.saveDir) {
                    Label(L("mobileSaveDirInvalid"), systemImage: FluxSymbol.failure)
                        .font(.footnote)
                        .foregroundStyle(Color.fdStatusFailedText)
                }
            } header: {
                Text(L("rssSaveDirLabel"))
            }

            Section {
                toggleRow("rssEnabledLabel", "rssEnabledDesc", $model.form.enabled)
                toggleRow("rssAutoDownloadLabel", "rssAutoDownloadDesc", $model.form.autoDownload)
                toggleRow("rssStartPausedLabel", "rssStartPausedDesc", $model.form.startPaused)
            } footer: {
                Text(L("rssWizardSeedNote"))
            }

            if model.isEditing {
                Section {
                    Button(L("rssDeleteSource"), role: .destructive) { confirmDelete = true }
                        .disabled(model.saving)
                        .alert(
                            L("rssDeleteSource"),
                            isPresented: $confirmDelete
                        ) {
                            Button(L("rssDeleteSource"), role: .destructive) { delete() }
                            Button(L("cancel"), role: .cancel) {}
                        } message: {
                            Text(L("rssDeleteConfirmDesc", ["name": displayName]))
                        }
                }
            }
        }
    }

    @ViewBuilder
    private var validationRow: some View {
        switch model.validation {
        // 进行中只由「验证」按钮自身转圈 + 文案表达，这里不再重复一行
        case .idle, .running:
            EmptyView()
        case let .passed(title, itemCount):
            if model.isValidated {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        if !title.isEmpty { Text(title).font(.subheadline.weight(.medium)) }
                        Text(L("rssWizardFeedSummary", ["n": itemCount]))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: FluxSymbol.success).foregroundStyle(Color.fdStatusSeedingText)
                }
                .accessibilityElement(children: .combine)
            }
        case let .failed(message):
            Label(message, systemImage: FluxSymbol.warning)
                .font(.footnote)
                .foregroundStyle(Color.fdStatusFailedText)
                .textSelection(.enabled)
        }
    }

    // MARK: 过滤规则

    @ViewBuilder
    private var filterTab: some View {
        Section {
            labeledField("rssIncludeLabel") {
                TextField(L("rssIncludeHint"), text: $model.form.include)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
            }
            labeledField("rssExcludeLabel") {
                TextField(L("rssExcludeHint"), text: $model.form.exclude)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
            }
        }

        Section {
            labeledField("rssSizeMinLabel") {
                TextField("", text: $model.form.sizeMin, prompt: Text(verbatim: "200M"))
                    .keyboardType(.numbersAndPunctuation)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
            }
            labeledField("rssSizeMaxLabel") {
                TextField("", text: $model.form.sizeMax, prompt: Text(verbatim: "2G"))
                    .keyboardType(.numbersAndPunctuation)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
            }
            if let message = sizeProblem {
                Label(message, systemImage: FluxSymbol.failure)
                    .font(.footnote)
                    .foregroundStyle(Color.fdStatusFailedText)
            }
        }

        Section {
            toggleRow("rssUseRegexLabel", "rssUseRegexDesc", $model.form.useRegex)
            toggleRow("rssSmartEpisodeLabel", "rssSmartEpisodeDesc", $model.form.smartEpisode)
        }
    }

    /// 体积字段的即时提示（保存时同样校验）。
    private var sizeProblem: String? {
        func blank(_ text: String) -> Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        let min = blank(model.form.sizeMin) ? 0 : RssSizeLiteral.parse(model.form.sizeMin)
        let max = blank(model.form.sizeMax) ? 0 : RssSizeLiteral.parse(model.form.sizeMax)
        guard let min, let max else { return L("rssInvalidNumber") }
        if min > 0, max > 0, max < min { return L("rssInvalidSizeRange") }
        return nil
    }

    // MARK: 高级

    @ViewBuilder
    private var advancedTab: some View {
        requestSection(titled: false)

        Section {
            labeledField("rssMaxPerFetchLabel") {
                TextField(String(RssSourceDetail.defaultMaxPerFetch), text: $model.form.maxPerFetch)
                    .keyboardType(.numberPad)
            }
            if !maxPerFetchValid {
                Label(L("rssInvalidNumber"), systemImage: FluxSymbol.failure)
                    .font(.footnote)
                    .foregroundStyle(Color.fdStatusFailedText)
            }
        }

        Section {
            toggleRow("rssSendRefererLabel", "rssSendRefererDesc", $model.form.sendReferer)
            toggleRow("rssNotifyLabel", "rssNotifyDesc", $model.form.notifyOnDownload)
        }
    }

    /// 影响验证请求的字段：新建验证前在基本页签（带「高级」小标题），其余时候在高级页签。
    private func requestSection(titled: Bool) -> some View {
        Section {
            labeledField("rssCookiesLabel") {
                TextField(L("rssCookiesHint"), text: $model.form.cookies)
                    .font(.callout.monospaced())
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .onChange(of: model.form.cookies) { model.requestFieldChanged() }
            }
            labeledField("rssUserAgentLabel") {
                TextField(L("rssInheritGlobalHint"), text: $model.form.userAgent)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .onChange(of: model.form.userAgent) { model.requestFieldChanged() }
            }
            labeledField("rssProxyLabel") {
                TextField(L("rssInheritGlobalHint"), text: $model.form.proxyUrl)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .onChange(of: model.form.proxyUrl) { model.requestFieldChanged() }
            }
        } header: {
            if titled { Text(L("rssTabAdvanced")) }
        } footer: {
            Text(L("rssEditorAuthHint"))
        }
    }

    private var maxPerFetchValid: Bool {
        let text = model.form.maxPerFetch.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.allSatisfy({ $0 >= "0" && $0 <= "9" }), let value = Int32(text) else { return false }
        return RssSourceDetail.maxPerFetchRange.contains(value)
    }

    // MARK: 复用片段

    private func labeledField(_ labelKey: String, @ViewBuilder _ field: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L(labelKey)).font(.footnote).foregroundStyle(.secondary)
            field()
        }
        .padding(.vertical, 2)
    }

    private func toggleRow(_ titleKey: String, _ descriptionKey: String, _ isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(L(titleKey))
                Text(L(descriptionKey)).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .sensoryFeedback(.selection, trigger: isOn.wrappedValue)
    }

    private var intervalChoices: [Int32] {
        var values = RssFormat.intervalChoices
        if !values.contains(model.form.interval) { values.append(model.form.interval) }
        return values
    }

    private nonisolated struct QueueChoice: Identifiable {
        let id: String
        let label: String
    }

    private var queueChoices: [QueueChoice] {
        var choices = container.store.state.queues.map { QueueChoice(id: $0.queueId, label: RssFormat.queueLabel($0)) }
        if !choices.contains(where: { $0.id == model.form.queueId }) {
            choices.append(QueueChoice(id: model.form.queueId, label: RssFormat.queueLabel(id: model.form.queueId, name: "")))
        }
        return choices
    }

    // MARK: 动作

    private func save() {
        Task {
            let editing = model.isEditing
            if await model.save() {
                container.toasts.show(
                    text: L(editing ? "mobileRssSavedToast" : "mobileRssCreatedToast"),
                    tone: .success,
                    systemImage: FluxSymbol.done
                )
                dismiss()
            }
        }
    }

    private func delete() {
        guard case let .edit(sourceId) = model.target,
              let source = container.store.state.rssSources.first(where: { $0.sourceId == sourceId })
        else { return }
        rss.confirmDelete(source)
        dismiss()
    }
}

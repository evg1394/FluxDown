import FluxDomain
import FluxUI
import SwiftUI

/// N2 · 新建下载 · 高级选项（`02-downloads.md` §12）：在 N1 的 `NavigationStack` 内推入。
/// 只暴露 `CreateTaskRequest` 支持的字段；单条专属项（HTTP 认证 / 校验值）在多条时隐藏。
struct AdvancedOptionsView: View {
    let form: NewDownloadForm

    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var showPassword = false

    var body: some View {
        @Bindable var adv = form.advanced
        let entries = form.entries
        let single = entries.count == 1
        let singleHttp = single && NewDownloadForm.isHttpLike(entries[0].url)
        let manualProxy = manualProxyUrl(container.store.state.config)

        Form {
            if entries.count > 1 {
                Banner(text: L("mobileAdvancedBatchHint"), tone: .info, slim: true)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            }

            if singleHttp {
                Section {
                    TextField(L("taskHttpAuthUser"), text: $adv.httpUser)
                        .textContentType(.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    HStack {
                        Group {
                            if showPassword {
                                TextField(L("taskHttpAuthPassword"), text: $adv.httpPassword)
                                    .font(.body.monospaced())
                            } else {
                                SecureField(L("taskHttpAuthPassword"), text: $adv.httpPassword)
                            }
                        }
                        .textContentType(.password)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        Button(
                            showPassword ? L("mobilePasswordHide") : L("mobilePasswordShow"),
                            systemImage: showPassword ? "eye.slash" : "eye"
                        ) { showPassword.toggle() }
                            .labelStyle(.iconOnly)
                            .buttonStyle(.borderless)
                    }
                    Toggle(L("taskHttpAuthSaveForSite"), isOn: $adv.saveSiteAuth)
                } header: {
                    Text(L("taskHttpAuth"))
                } footer: {
                    Text(L("taskHttpAuthDesc"))
                }
            }

            Section {
                Picker(L("taskProxy"), selection: $adv.proxyChoice) {
                    ForEach(ProxyChoice.allCases, id: \.self) { choice in
                        if choice == .globalManual {
                            Text(manualProxy.isEmpty ? "\(proxyLabel(choice)) (\(L("proxyNotConfigured")))" : proxyLabel(choice))
                                .tag(choice)
                                .disabled(manualProxy.isEmpty)
                        } else {
                            Text(proxyLabel(choice)).tag(choice)
                        }
                    }
                }
                .pickerStyle(.menu)
                if adv.proxyChoice == .custom {
                    TextField(L("taskProxyPlaceholder"), text: $adv.proxyCustom)
                        .font(.callout.monospaced())
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
            } header: {
                Text(L("taskProxy"))
            } footer: {
                Text(L("taskProxyDesc"))
            }

            Section {
                Picker(L("userAgent"), selection: uaPresetBinding(adv)) {
                    ForEach(uaKeys, id: \.self) { key in Text(uaName(key)).tag(key) }
                }
                .pickerStyle(.menu)
                TextField(L("userAgentTaskPlaceholder"), text: uaTextBinding(adv), axis: .vertical)
                    .font(.callout.monospaced())
                    .lineLimit(2...4)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } header: {
                Text(L("userAgent"))
            }

            Section {
                TextField(L("taskCookiePlaceholder"), text: $adv.cookie, axis: .vertical)
                    .font(.callout.monospaced())
                    .lineLimit(3...6)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField(L("mobileReferrer"), text: $adv.referrer, prompt: Text(verbatim: "https://"))
                    .font(.callout.monospaced())
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            } header: {
                Text(L("taskCookie"))
            } footer: {
                Text(entries.count > 1 ? L("taskCookieBatchDesc") : L("taskCookieDesc"))
            }

            if single {
                Section {
                    Picker(L("taskChecksum"), selection: $adv.checksumAlgo) {
                        ForEach(hashAlgorithms, id: \.self) { Text($0.uppercased()).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    TextField(L("taskChecksumPlaceholder"), text: $adv.checksumHex)
                        .font(.callout.monospaced())
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onChange(of: adv.checksumHex) {
                            let trimmed = adv.checksumHex.trimmingCharacters(in: .whitespacesAndNewlines)
                            if trimmed != adv.checksumHex { adv.checksumHex = trimmed }
                        }
                    if !checksumHexValid(adv.checksumHex) {
                        Label(L("mobileChecksumInvalid"), systemImage: FluxSymbol.failure)
                            .font(.footnote)
                            .foregroundStyle(Color.fdStatusFailedText)
                    }
                } header: {
                    Text(L("taskChecksum"))
                } footer: {
                    Text(L("taskChecksumDesc"))
                }
            }

            Section {
                ForEach($adv.headers) { $header in
                    HStack(spacing: 8) {
                        TextField(L("taskHeadersKeyPlaceholder"), text: $header.key)
                        TextField(L("taskHeadersValuePlaceholder"), text: $header.value)
                    }
                    .font(.callout.monospaced())
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                }
                .onDelete { offsets in
                    adv.headers.remove(atOffsets: offsets)
                    FluxHaptic.light.play()
                }
                Button(L("taskHeadersAdd"), systemImage: FluxSymbol.add) {
                    adv.addHeader()
                    FluxHaptic.light.play()
                }
            } header: {
                Text(L("taskHeaders"))
            } footer: {
                Text(L("taskHeadersDesc"))
            }

            Section {
                Toggle(isOn: $adv.ignoreTls) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("taskIgnoreTlsErrors"))
                        Text(L("taskIgnoreTlsErrorsDesc")).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle(L("taskProxyAdvanced"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(L("confirm")) { dismiss() }
            }
            ToolbarItem(placement: .bottomBar) {
                Button(L("mobileReset"), role: .destructive) { form.advanced.reset() }
            }
        }
    }

    private var uaKeys: [String] { [taskUaDefault] + taskUaPresets.map(\.key) + [taskUaCustom] }

    private func uaName(_ key: String) -> String {
        switch key {
        case taskUaDefault: L("queueUaInheritGlobal")
        case "chrome": L("userAgentPresetChrome")
        case "firefox": L("userAgentPresetFirefox")
        case "edge": L("userAgentPresetEdge")
        case "safari": L("userAgentPresetSafari")
        default: L("userAgentPresetCustom")
        }
    }

    /// 选预设 = 填入对应 UA；选「自定义」保留当前文本。
    private func uaPresetBinding(_ adv: AdvancedState) -> Binding<String> {
        Binding(
            get: { adv.uaPreset },
            set: { key in
                adv.uaPreset = key
                if key != taskUaCustom { adv.userAgent = uaPresetValue(key) }
            }
        )
    }

    /// 手动编辑 UA 时反推当前预设。
    private func uaTextBinding(_ adv: AdvancedState) -> Binding<String> {
        Binding(
            get: { adv.userAgent },
            set: { text in
                adv.userAgent = text
                adv.uaPreset = detectUaPreset(text)
            }
        )
    }

    private func proxyLabel(_ choice: ProxyChoice) -> String {
        switch choice {
        case .follow: L("taskProxyChoiceFollow")
        case .direct: L("taskProxyChoiceDirect")
        case .system: L("taskProxyChoiceSystem")
        case .globalManual: L("taskProxyChoiceGlobalManual")
        case .custom: L("taskProxyChoiceCustom")
        }
    }
}

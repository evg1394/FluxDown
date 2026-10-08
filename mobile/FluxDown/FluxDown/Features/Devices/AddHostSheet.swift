import FluxDomain
import FluxUI
import SwiftUI

/// V2 添加远程主机（`fluxdown-agent --server`）：名称、地址、访问密钥 → 连接测试 → 保存并切换。
/// 系统表单 Sheet（玻璃由系统提供）；表单每次打开重建，访问密钥只存在于本 View 的 `@State`，
/// 持久化加密由 `AppContainer.addRemoteHost`（钥匙串）负责。
struct AddHostSheet: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss

    private nonisolated enum Field: Hashable { case name, address, key }

    @State private var name = ""
    @State private var address = ""
    @State private var key = ""
    @State private var revealKey = false
    /// 点过「连接」后才显示本地校验错误，避免输入中途报错。
    @State private var attempted = false
    @State private var busy = false
    /// 主机拒绝了这把密钥（Unauthorized）；改动密钥后清除。
    @State private var keyRejected = false
    /// 连接失败横幅文案（不可达 / 超时 / 版本不兼容 / 其它）。
    @State private var failure: String?
    @State private var failureCount = 0
    @FocusState private var focus: Field?

    var body: some View {
        let parsed = HostInput.parseEndpoint(address)
        let keyIssue = HostInput.validateAccessKey(key.trimmingCharacters(in: .whitespacesAndNewlines))
        let cleartext: Bool = if case .ok(_, _, true) = parsed { true } else { false }
        NavigationStack {
            Form {
                Section {
                    Text(L("mobileHostAddSub"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets(top: 0, leading: 4, bottom: 0, trailing: 4))
                }

                Section {
                    TextField(L("mobileHostName"), text: $name, prompt: Text(L("mobileHostNamePlaceholder")))
                        .textInputAutocapitalization(.words)
                        .submitLabel(.next)
                        .focused($focus, equals: .name)
                        .onSubmit { focus = .address }
                }

                Section {
                    TextField(L("mobileHostAddress"), text: $address, prompt: Text(verbatim: "192.168.1.20:17800"))
                        .font(.body.monospaced())
                        .keyboardType(.URL)
                        .textContentType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.next)
                        .focused($focus, equals: .address)
                        .onSubmit { focus = .key }
                        .onChange(of: address) { failure = nil }
                    if cleartext {
                        Banner(text: L("mobileHostCleartext"), tone: .warning, systemImage: FluxSymbol.warning, slim: true)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                    }
                } header: {
                    Text(L("mobileHostAddress"))
                } footer: {
                    FieldFooter(hint: L("mobileHostAddressHint"), error: attempted ? addressError(parsed) : nil)
                }

                Section {
                    HStack {
                        Group {
                            if revealKey {
                                TextField(L("webAccessKey"), text: $key, prompt: Text(L("webAccessKeyPlaceholder")))
                                    .font(.body.monospaced())
                            } else {
                                SecureField(L("webAccessKey"), text: $key, prompt: Text(L("webAccessKeyPlaceholder")))
                            }
                        }
                        .textContentType(.password)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.go)
                        .focused($focus, equals: .key)
                        .onSubmit(submit)
                        .onChange(of: key) {
                            keyRejected = false
                            failure = nil
                        }
                        Button {
                            revealKey.toggle()
                        } label: {
                            Image(systemName: revealKey ? "eye.slash" : "eye")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(L(revealKey ? "webHideKey" : "webShowKey"))
                    }
                } header: {
                    Text(L("webAccessKey"))
                } footer: {
                    FieldFooter(
                        hint: L("mobileHostKeyHint", ["min": HostInput.accessKeyMinLength, "max": HostInput.accessKeyMaxLength]),
                        error: keyError(keyIssue)
                    )
                }

                if let failure {
                    Section {
                        Banner(text: failure, tone: .error, systemImage: FluxSymbol.failure, slim: true)
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets())
                    }
                }
            }
            .disabled(busy)
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle(L("mobileHostAddTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("cancel"), role: .cancel) { dismiss() }
                        .disabled(busy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    if busy {
                        ProgressView().accessibilityLabel(L("mobileHostConnect"))
                    } else {
                        Button(L("mobileHostConnect"), action: submit)
                    }
                }
            }
            .interactiveDismissDisabled(busy)
            .sensoryFeedback(.error, trigger: failureCount)
            .onDisappear { key = "" }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    // MARK: 校验文案

    private func addressError(_ parsed: EndpointParse) -> String? {
        switch parsed {
        case .empty: L("localPairingHostRequired")
        case .badAddress: L("localPairingAddressInvalid")
        case .badPort: L("localPairingPortInvalid")
        case .ok: nil
        }
    }

    private func keyError(_ issue: KeyIssue?) -> String? {
        if keyRejected { return L("webLoginInvalidKey") }
        guard attempted, let issue else { return nil }
        switch issue {
        case .badChars: return L("webKeyBadChars")
        case .tooShort: return L("webKeyTooShort", ["min": HostInput.accessKeyMinLength])
        case .tooLong: return L("webKeyTooLong", ["max": HostInput.accessKeyMaxLength])
        case .needsMix: return L("webKeyNeedsMix")
        }
    }

    // MARK: 提交

    private func submit() {
        guard !busy else { return }
        attempted = true
        let accessKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard case let .ok(endpoint, host, _) = HostInput.parseEndpoint(address),
              HostInput.validateAccessKey(accessKey) == nil
        else {
            failureCount += 1
            return
        }
        busy = true
        failure = nil
        keyRejected = false
        focus = nil
        let displayName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            let result = await container.addRemoteHost(
                name: displayName.isEmpty ? host : displayName,
                endpoint: endpoint,
                accessKey: accessKey
            )
            switch result {
            case let .success(ref):
                // 主机已保存；无论切换是否成功都收起表单（切换结果由 Toast 回执）。
                await HostFlow.switchTo(ref, container: container)
                busy = false
                dismiss()
            case let .failure(error):
                failureCount += 1
                if error.code == .unauthorized {
                    keyRejected = true
                } else {
                    failure = HostFlow.errorText(error)
                }
                busy = false
            }
        }
    }
}

/// 字段下方的说明 + 校验错误（错误用「图标 + 文字」，不只靠颜色）。
private struct FieldFooter: View {
    let hint: String
    let error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(hint)
            if let error {
                Label(error, systemImage: FluxSymbol.failure)
                    .foregroundStyle(Color.fdStatusFailedText)
            }
        }
    }
}

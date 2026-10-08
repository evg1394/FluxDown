import FluxDomain
import FluxUI
import SwiftUI

// S2a 登录 / S2b 注册 / 重置密码（04-rss-devices §S2.2–S2.3）。`.sheet` + detents，系统 `Form`，取消 / 主操作在导航栏。
// 新设备验证、注册邮箱验证是 sheet 内 `NavigationStack` 的第二页（系统返回按钮即「上一步」）。

// MARK: - 登录

struct LoginSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var flow = LoginFlow()
    @State private var register = RegisterFlow()
    @State private var path: [Step] = []
    @State private var resetting = false

    fileprivate nonisolated enum Step: Hashable { case deviceVerify, registerVerify }

    var body: some View {
        NavigationStack(path: $path) {
            LoginCredentialsPage(flow: flow, register: register, path: $path, resetting: $resetting, close: { dismiss() })
                .navigationDestination(for: Step.self) { step in
                    switch step {
                    case .deviceVerify:
                        DeviceVerifyPage(flow: flow, close: { dismiss() })
                    case .registerVerify:
                        RegisterVerifyPage(flow: register, close: { dismiss() })
                    }
                }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .onChange(of: path) { _, now in
            if !now.contains(.deviceVerify) { flow.resetVerification() }
        }
        .sheet(isPresented: $resetting) {
            ResetPasswordSheet(initialEmail: flow.accountIsEmail ? flow.trimmedAccount : "") { email in
                flow.account = email
                flow.password = ""
                flow.method = .password
                flow.setError(nil)
                resetting = false
            }
        }
        .sensoryFeedback(.success, trigger: flow.successCount + register.successCount)
        .sensoryFeedback(.error, trigger: flow.failureCount + register.failureCount)
    }
}

private struct LoginCredentialsPage: View {
    @Environment(AppContainer.self) private var container
    @Bindable var flow: LoginFlow
    let register: RegisterFlow
    @Binding var path: [LoginSheet.Step]
    @Binding var resetting: Bool
    let close: () -> Void

    private var busy: Bool { flow.busy || register.busy }
    private var byCode: Bool { flow.method == .code }

    var body: some View {
        Form {
            Section {
                Picker(L("accountLoginDialogTitle"), selection: $flow.method) {
                    Text(L("accountLoginTabPassword")).tag(LoginFlow.Method.password)
                    Text(L("accountLoginTabCode")).tag(LoginFlow.Method.code)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .disabled(busy)
            }
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())

            Section {
                TextField(byCode ? L("accountEmailPlaceholder") : L("accountLoginAccountPlaceholder"), text: $flow.account)
                    .textContentType(.username)
                    .keyboardType(byCode ? .emailAddress : .asciiCapable)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .disabled(busy)
                    .accessibilityLabel(L("accountFieldAccount"))
                if byCode {
                    VerificationCodeRows(
                        code: $flow.code,
                        clock: flow.clock,
                        sending: flow.sending,
                        locked: busy,
                        canSend: !flow.trimmedAccount.isEmpty,
                        onSend: sendCode
                    )
                } else {
                    AccountPasswordField(title: L("accountPasswordPlaceholder"), text: $flow.password, contentType: .password, locked: busy)
                }
            } footer: {
                if let text = flow.errorText { AccountErrorLabel(text: text) }
            }

            if !byCode {
                Section {
                    Button(L("accountForgotPassword")) { resetting = true }
                        .disabled(busy)
                }
            }
        }
        .accountSheetToolbar(
            title: L("accountLoginDialogTitle"),
            confirmTitle: L("accountLogin"),
            canConfirm: flow.canSubmitCredentials,
            busy: busy,
            onCancel: close,
            onConfirm: submit
        )
        .onSubmit { if flow.canSubmitCredentials { submit() } }
        .onChange(of: flow.method) { flow.setError(nil) }
    }

    private func submit() {
        Task {
            let api = container.agent
            switch await flow.submitCredentials(api) {
            case .done: close()
            case .verify: if !byCode { path.append(.deviceVerify) }
            case .registrationIncomplete: await resumeRegistration(api)
            case .failed: break
            }
        }
    }

    private func sendCode() {
        Task { _ = await flow.resend(container.agent) }
    }

    /// 服务端报告「注册未完成」：带着登录时输入的邮箱 / 密码重发注册验证码并转入注册验证步骤。
    private func resumeRegistration(_ api: AgentAPI) async {
        register.resume(email: flow.trimmedAccount, password: flow.password)
        switch await register.submitForm(api) {
        case .done: close()
        case .verify: path.append(.registerVerify)
        case .registrationIncomplete, .failed: flow.setError(register.errorText)
        }
    }
}

private struct DeviceVerifyPage: View {
    @Environment(AppContainer.self) private var container
    @Bindable var flow: LoginFlow
    let close: () -> Void

    var body: some View {
        Form {
            Section {
                Text(
                    flow.accountIsEmail
                        ? L("accountDeviceVerifySubtitle", ["email": flow.trimmedAccount])
                        : L("accountDeviceVerifySubtitleGeneric")
                )
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
                if flow.willReplaceDevices {
                    Banner(text: L("accountDeviceVerifyReplacementNotice"), tone: .warning, slim: true)
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
            }
            Section {
                VerificationCodeRows(code: $flow.code, clock: flow.clock, sending: flow.sending, locked: flow.busy, onSend: resend)
            } footer: {
                if let text = flow.errorText { AccountErrorLabel(text: text) }
            }
        }
        .accountSheetToolbar(
            title: L("accountDeviceVerifyTitle"),
            confirmTitle: L("accountVerifySubmit"),
            canConfirm: flow.canSubmitVerification,
            busy: flow.busy,
            onCancel: close,
            onConfirm: submit
        )
        .navigationBarBackButtonHidden(flow.busy)
        .onSubmit { if flow.canSubmitVerification { submit() } }
    }

    private func submit() {
        Task {
            if await flow.submitDeviceVerification(container.agent) == .done { close() }
        }
    }

    private func resend() {
        Task { if await flow.resend(container.agent) == .done { close() } }
    }
}

// MARK: - 注册

struct RegisterSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var flow = RegisterFlow()
    @State private var path: [RegisterStep] = []

    var body: some View {
        NavigationStack(path: $path) {
            RegisterFormPage(flow: flow, path: $path, close: { dismiss() })
                .navigationDestination(for: RegisterStep.self) { _ in
                    RegisterVerifyPage(flow: flow, close: { dismiss() })
                }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .sensoryFeedback(.success, trigger: flow.successCount)
        .sensoryFeedback(.error, trigger: flow.failureCount)
    }
}

nonisolated enum RegisterStep: Hashable { case verify }

private struct RegisterFormPage: View {
    @Environment(AppContainer.self) private var container
    @Bindable var flow: RegisterFlow
    @Binding var path: [RegisterStep]
    let close: () -> Void

    var body: some View {
        Form {
            Section {
                TextField(L("accountEmailPlaceholder"), text: $flow.email)
                    .textContentType(.emailAddress)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .disabled(flow.busy)
                AccountPasswordField(title: L("accountPasswordPlaceholder"), text: $flow.password, contentType: .newPassword, locked: flow.busy)
            } footer: {
                Text(L("accountPasswordHint"))
            }
            Section {
                TextField(L("accountNicknamePlaceholder"), text: $flow.nickname)
                    .textContentType(.nickname)
                    .disabled(flow.busy)
                    .accessibilityLabel(L("accountFieldNickname"))
            } header: {
                Text(L("accountFieldNickname"))
            } footer: {
                if let text = flow.errorText { AccountErrorLabel(text: text) }
            }
        }
        .accountSheetToolbar(
            title: L("accountRegisterDialogTitle"),
            confirmTitle: L("accountRegister"),
            canConfirm: flow.canSubmitForm,
            busy: flow.busy,
            onCancel: close,
            onConfirm: submit
        )
        .onSubmit { if flow.canSubmitForm { submit() } }
    }

    private func submit() {
        Task {
            switch await flow.submitForm(container.agent) {
            case .done: close()
            case .verify: path.append(.verify)
            case .registrationIncomplete, .failed: break
            }
        }
    }
}

private struct RegisterVerifyPage: View {
    @Environment(AppContainer.self) private var container
    @Bindable var flow: RegisterFlow
    let close: () -> Void

    var body: some View {
        Form {
            Section {
                Text(L("accountRegisterVerifySubtitle", ["email": flow.trimmedEmail]))
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
            } header: {
                Text(L("accountRegisterVerifyTitle"))
            }
            Section {
                VerificationCodeRows(code: $flow.code, clock: flow.clock, sending: flow.sending, locked: flow.busy, onSend: resend)
            } footer: {
                if let text = flow.errorText { AccountErrorLabel(text: text) }
            }
        }
        .accountSheetToolbar(
            title: L("accountRegisterVerifyTitle"),
            confirmTitle: L("accountVerifySubmit"),
            canConfirm: flow.canSubmitVerification,
            busy: flow.busy,
            onCancel: close,
            onConfirm: submit
        )
        .navigationBarBackButtonHidden(flow.busy)
        .onSubmit { if flow.canSubmitVerification { submit() } }
    }

    private func submit() {
        Task { if await flow.submitVerification(container.agent) == .done { close() } }
    }

    /// 重发注册验证码 = 以同一组信息再次注册（服务端作废旧码并发新码）。
    private func resend() {
        Task { if await flow.submitForm(container.agent, resending: true) == .done { close() } }
    }
}

// MARK: - 重置密码

struct ResetPasswordSheet: View {
    @Environment(AppContainer.self) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var flow: ResetPasswordFlow
    let onDone: (String) -> Void

    init(initialEmail: String, onDone: @escaping (String) -> Void) {
        _flow = State(initialValue: ResetPasswordFlow(initialEmail: initialEmail))
        self.onDone = onDone
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L("accountEmailPlaceholder"), text: $flow.email)
                        .textContentType(.username)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(flow.busy || flow.sending)
                } header: {
                    Text(L("accountPasswordResetEmailHint")).textCase(nil)
                } footer: {
                    if let error = flow.emailError { AccountErrorLabel(text: error) }
                }
                Section {
                    VerificationCodeRows(
                        code: $flow.code,
                        clock: flow.activeClock,
                        sending: flow.sending,
                        locked: flow.busy,
                        canSend: flow.emailValid,
                        onSend: sendCode
                    )
                }
                Section {
                    AccountPasswordField(title: L("accountPasswordNewPlaceholder"), text: $flow.newPassword, contentType: .newPassword, locked: flow.busy)
                    AccountPasswordField(title: L("accountPasswordConfirmPlaceholder"), text: $flow.confirm, contentType: .newPassword, locked: flow.busy)
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L("accountPasswordHint"))
                        if let key = flow.visibleRuleKey { AccountErrorLabel(text: L(key)) }
                        if let text = flow.errorText { AccountErrorLabel(text: text) }
                    }
                }
            }
            .accountSheetToolbar(
                title: L("accountPasswordResetTitle"),
                confirmTitle: L("confirm"),
                canConfirm: flow.canConfirm,
                busy: flow.busy,
                onCancel: { dismiss() },
                onConfirm: confirm
            )
        }
        .presentationDetents([.large])
        .sensoryFeedback(.success, trigger: flow.successCount)
    }

    private func sendCode() {
        Task { await flow.sendCode(container.agent) }
    }

    private func confirm() {
        let email = flow.trimmedEmail
        Task {
            if await flow.submit(container.agent) {
                container.toasts.show(text: L("accountPasswordResetSuccess"), tone: .success)
                onDone(email)
            }
        }
    }
}

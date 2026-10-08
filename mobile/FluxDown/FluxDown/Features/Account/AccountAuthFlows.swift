import FluxDomain
import Foundation
import Observation

// 登录 / 注册 / 重置密码的状态机（SwiftUI 视图只渲染并转发）。规则与步骤镜像 Web `AuthDialogs.tsx` /
// `PasswordDialogs.tsx` 与 GPUI `dialogs/{login,register,password_reset}.rs`。所有网络调用在调用时取当前主机会话。

/// 一次提交的结果（视图据此推进导航）。
nonisolated enum AuthOutcome: Sendable {
    /// 已登录，关闭 sheet。
    case done
    /// 需要验证码步骤（设备验证 / 注册邮箱验证）。
    case verify
    /// 服务端报告「注册未完成」，应转入注册验证。
    case registrationIncomplete
    case failed
}

private func trimmed(_ value: String) -> String { AccountRules.trimmed(value) }

// MARK: - 登录

@MainActor
@Observable
final class LoginFlow {
    nonisolated enum Method: Hashable { case password, code }

    var method: Method = .password
    /// 邮箱或纯数字 Origin ID（验证码登录只能是邮箱）。
    var account = ""
    var password = ""
    var code = ""

    private(set) var busy = false
    /// 正在（重新）发码。
    private(set) var sending = false
    private(set) var errorText: String?
    private(set) var clock: CodeClock?
    private(set) var willReplaceDevices = false
    /// 成功 / 失败次数：驱动触感。
    private(set) var successCount = 0
    private(set) var failureCount = 0

    var trimmedAccount: String { trimmed(account) }
    var accountIsEmail: Bool { trimmedAccount.contains("@") }

    var canSubmitCredentials: Bool {
        switch method {
        case .password: !trimmedAccount.isEmpty && !password.isEmpty
        case .code: !trimmedAccount.isEmpty && !trimmed(code).isEmpty
        }
    }

    var canSubmitVerification: Bool { !trimmed(code).isEmpty }

    /// 回到凭据页（从验证页返回）：丢弃验证状态。
    func resetVerification() {
        code = ""
        clock = nil
        willReplaceDevices = false
        errorText = nil
    }

    func setError(_ text: String?) { errorText = text }

    /// 密码登录第一步 / 验证码登录第二步（`verifyCode`）。
    func submitCredentials(_ api: AgentAPI) async -> AuthOutcome {
        guard !busy else { return .failed }
        busy = true
        errorText = nil
        defer { busy = false }
        do throws(HostError) {
            switch method {
            case .password:
                return apply(try await api.login(LoginParams(account: trimmedAccount, password: password)))
            case .code:
                return apply(try await api.verifyCode(VerifyCodeParams(email: trimmedAccount, code: trimmed(code))))
            }
        } catch {
            return fail(error, context: method == .code ? .code : .login, allowResume: method == .password)
        }
    }

    /// 新设备验证步骤提交：`loginVerify`。
    func submitDeviceVerification(_ api: AgentAPI) async -> AuthOutcome {
        guard !busy else { return .failed }
        busy = true
        errorText = nil
        defer { busy = false }
        do throws(HostError) {
            return apply(try await api.loginVerify(LoginVerifyParams(account: trimmedAccount, password: password, code: trimmed(code))))
        } catch {
            return fail(error, context: .code, allowResume: false)
        }
    }

    /// 重发：密码登录重新走 `login`（新设备会再发码并可能更新「替换设备」提示），验证码登录重新 `sendCode`。
    func resend(_ api: AgentAPI) async -> AuthOutcome {
        guard !busy, !sending else { return .failed }
        sending = true
        errorText = nil
        defer { sending = false }
        do throws(HostError) {
            switch method {
            case .password:
                return apply(try await api.login(LoginParams(account: trimmedAccount, password: password)))
            case .code:
                let ttl = try await api.sendCode(email: trimmedAccount)
                clock = CodeClock(ttlSeconds: ttl.ttlSeconds)
                code = ""
                return .verify
            }
        } catch {
            return fail(error, context: .login, allowResume: false)
        }
    }

    private func apply(_ result: AgentLoginResult) -> AuthOutcome {
        switch result {
        case .ok:
            successCount += 1
            return .done
        case let .deviceVerificationRequired(ttl, replace):
            clock = CodeClock(ttlSeconds: ttl)
            willReplaceDevices = replace
            code = ""
            return .verify
        case .unknown:
            errorText = L("accountErrorUnknown")
            failureCount += 1
            return .failed
        }
    }

    private func fail(_ error: HostError, context: AccountErrorContext, allowResume: Bool) -> AuthOutcome {
        if allowResume, AccountRules.isRegistrationIncomplete(error) { return .registrationIncomplete }
        errorText = AccountText.error(error, context: context)
        failureCount += 1
        return .failed
    }
}

// MARK: - 注册

@MainActor
@Observable
final class RegisterFlow {
    var email = ""
    var password = ""
    var nickname = ""
    var code = ""

    private(set) var busy = false
    private(set) var sending = false
    private(set) var errorText: String?
    private(set) var clock: CodeClock?
    private(set) var successCount = 0
    private(set) var failureCount = 0

    var trimmedEmail: String { trimmed(email) }
    var canSubmitForm: Bool { !trimmedEmail.isEmpty && !password.isEmpty }
    var canSubmitVerification: Bool { !trimmed(code).isEmpty }

    func setError(_ text: String?) { errorText = text }

    /// 登录发现「注册未完成」后接力：带着登录时输入的邮箱 / 密码重发注册验证码并进入验证步骤。
    func resume(email: String, password: String) {
        self.email = email
        self.password = password
        code = ""
        errorText = nil
    }

    /// 表单提交 / 重发（服务端作废旧码并发新码）。
    func submitForm(_ api: AgentAPI, resending: Bool = false) async -> AuthOutcome {
        if resending {
            guard !busy, !sending else { return .failed }
            sending = true
        } else {
            guard !busy else { return .failed }
            busy = true
        }
        errorText = nil
        defer {
            sending = false
            busy = false
        }
        let name = trimmed(nickname)
        do throws(HostError) {
            let params = RegisterParams(email: trimmedEmail, password: password, nickname: name.isEmpty ? nil : name)
            return apply(try await api.register(params))
        } catch {
            errorText = AccountText.error(error, context: .register)
            failureCount += 1
            return .failed
        }
    }

    func submitVerification(_ api: AgentAPI) async -> AuthOutcome {
        guard !busy else { return .failed }
        busy = true
        errorText = nil
        defer { busy = false }
        do throws(HostError) {
            return apply(try await api.registerVerify(RegisterVerifyParams(email: trimmedEmail, code: trimmed(code))))
        } catch {
            errorText = AccountText.error(error, context: .code)
            failureCount += 1
            return .failed
        }
    }

    private func apply(_ result: AgentLoginResult) -> AuthOutcome {
        switch result {
        case .ok:
            successCount += 1
            return .done
        case let .deviceVerificationRequired(ttl, _):
            clock = CodeClock(ttlSeconds: ttl)
            code = ""
            return .verify
        case .unknown:
            errorText = L("accountErrorUnknown")
            failureCount += 1
            return .failed
        }
    }
}

// MARK: - 重置密码（未登录）

@MainActor
@Observable
final class ResetPasswordFlow {
    var email: String
    var code = ""
    var newPassword = ""
    var confirm = ""

    private(set) var busy = false
    private(set) var sending = false
    private(set) var errorText: String?
    private(set) var clock: CodeClock?
    /// 验证码发往的邮箱：改了邮箱后旧码与冷却不再适用。
    private(set) var sentEmail: String?
    private(set) var successCount = 0

    init(initialEmail: String) { email = initialEmail }

    var trimmedEmail: String { trimmed(email) }
    var emailValid: Bool { AccountRules.isValidEmail(trimmedEmail) }
    var emailError: String? { !trimmedEmail.isEmpty && !emailValid ? L("accountErrorInvalidEmail") : nil }
    /// 当前邮箱对应的验证码倒计时（邮箱改动后失效）。
    var activeClock: CodeClock? { sentEmail == trimmedEmail ? clock : nil }
    var ruleKey: String? { AccountRules.newPasswordErrorKey(new: newPassword, confirm: confirm, current: nil) }
    /// 密码规则只在用户已输入且（已填确认或已太短）时提示，避免一打开就满屏红字。
    var visibleRuleKey: String? {
        guard let ruleKey, !newPassword.isEmpty else { return nil }
        return (!confirm.isEmpty || ruleKey == "accountErrorPasswordTooShort") ? ruleKey : nil
    }

    /// 过期的码由服务端拒绝（`accountErrorInvalidCode`）；界面上的倒计时只做提示，避免按钮随时钟闪烁。
    var canConfirm: Bool {
        !sending && emailValid && clock != nil && sentEmail == trimmedEmail && ruleKey == nil && !trimmed(code).isEmpty
    }

    func sendCode(_ api: AgentAPI) async {
        guard emailValid, !sending, !busy else { return }
        let target = trimmedEmail
        sending = true
        errorText = nil
        defer { sending = false }
        do throws(HostError) {
            let ttl = try await api.sendPasswordResetCode(email: target)
            clock = CodeClock(ttlSeconds: ttl.ttlSeconds)
            sentEmail = target
            code = ""
        } catch {
            errorText = AccountText.error(error, context: .code)
        }
    }

    /// - Returns: 重置成功（所有设备已退出，需用新密码重新登录）。
    func submit(_ api: AgentAPI) async -> Bool {
        guard !busy else { return false }
        busy = true
        errorText = nil
        defer { busy = false }
        do throws(HostError) {
            try await api.resetPassword(ResetPasswordParams(email: trimmedEmail, code: trimmed(code), newPassword: newPassword))
            successCount += 1
            return true
        } catch {
            errorText = AccountText.error(error, context: .code)
            return false
        }
    }
}

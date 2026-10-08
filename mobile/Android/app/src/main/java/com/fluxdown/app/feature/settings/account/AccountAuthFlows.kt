package com.fluxdown.app.feature.settings.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fluxdown.app.R
import com.fluxdown.core.host.HostException
import com.fluxdown.core.protocol.AccountErrorContext
import com.fluxdown.core.protocol.AccountRules
import com.fluxdown.core.protocol.AgentLoginResult
import com.fluxdown.core.protocol.CodeClock
import com.fluxdown.core.protocol.LoginParams
import com.fluxdown.core.protocol.LoginVerifyParams
import com.fluxdown.core.protocol.RegisterParams
import com.fluxdown.core.protocol.RegisterVerifyParams
import com.fluxdown.core.protocol.ResetPasswordParams
import com.fluxdown.core.protocol.VerifyCodeParams

/*
 * 登录 / 注册 / 重置密码的状态机（Compose 只渲染并转发，同 iOS `AccountAuthFlows`）。规则与步骤镜像 Web `AuthDialogs.tsx` /
 * `PasswordDialogs.tsx` 与 GPUI `dialogs/{login,register,password_reset}.rs`。
 * 所有网络调用在调用时经 [AccountEnv.api] 取当前主机会话；`CancellationException` 不被捕获（自然向上传播）。
 */

/** 一次提交的结果（界面据此推进步骤）。 */
internal enum class AuthOutcome {
    /** 已登录，关闭 sheet。 */
    Done,

    /** 需要验证码步骤（设备验证 / 注册邮箱验证）。 */
    Verify,

    /** 服务端报告「注册未完成」，应转入注册验证。 */
    RegistrationIncomplete,

    Failed,
}

internal enum class LoginMethod { Password, Code }

private fun trimmed(value: String): String = AccountRules.trimmed(value)

// ───────────────────────────── 登录 ─────────────────────────────

internal class LoginFlow(private val env: AccountEnv) {
    var method by mutableStateOf(LoginMethod.Password)

    /** 邮箱或纯数字 Origin ID（验证码登录只能是邮箱）。 */
    var account by mutableStateOf("")
    var password by mutableStateOf("")
    var code by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set

    /** 正在（重新）发码。 */
    var sending by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    var clock by mutableStateOf<CodeClock?>(null)
        private set
    var willReplaceDevices by mutableStateOf(false)
        private set

    val trimmedAccount: String get() = trimmed(account)
    val accountIsEmail: Boolean get() = trimmedAccount.contains('@')

    val canSubmitCredentials: Boolean
        get() = when (method) {
            LoginMethod.Password -> trimmedAccount.isNotEmpty() && password.isNotEmpty()
            LoginMethod.Code -> trimmedAccount.isNotEmpty() && trimmed(code).isNotEmpty()
        }

    val canSubmitVerification: Boolean get() = trimmed(code).isNotEmpty()

    /** 回到凭据页（从验证页返回）：丢弃验证状态。 */
    fun resetVerification() {
        code = ""
        clock = null
        willReplaceDevices = false
        errorText = null
    }

    fun setError(text: String?) {
        errorText = text
    }

    /** 密码登录第一步 / 验证码登录第二步（`verifyCode`）。 */
    suspend fun submitCredentials(): AuthOutcome {
        if (busy) return AuthOutcome.Failed
        busy = true
        errorText = null
        try {
            val api = env.api()
            return when (method) {
                LoginMethod.Password -> apply(api.login(LoginParams(trimmedAccount, password)))
                LoginMethod.Code -> apply(api.verifyCode(VerifyCodeParams(trimmedAccount, trimmed(code))))
            }
        } catch (e: HostException) {
            return fail(
                e,
                scene = if (method == LoginMethod.Code) AccountErrorContext.Code else AccountErrorContext.Login,
                allowResume = method == LoginMethod.Password,
            )
        } finally {
            busy = false
        }
    }

    /** 新设备验证步骤提交：`loginVerify`。 */
    suspend fun submitDeviceVerification(): AuthOutcome {
        if (busy) return AuthOutcome.Failed
        busy = true
        errorText = null
        try {
            return apply(env.api().loginVerify(LoginVerifyParams(trimmedAccount, password, trimmed(code))))
        } catch (e: HostException) {
            return fail(e, scene = AccountErrorContext.Code, allowResume = false)
        } finally {
            busy = false
        }
    }

    /** 重发：密码登录重新走 `login`（新设备会再发码并可能更新「替换设备」提示），验证码登录重新 `sendCode`。 */
    suspend fun resend(): AuthOutcome {
        if (busy || sending) return AuthOutcome.Failed
        sending = true
        errorText = null
        try {
            val api = env.api()
            return when (method) {
                LoginMethod.Password -> apply(api.login(LoginParams(trimmedAccount, password)))
                LoginMethod.Code -> {
                    val ttl = api.sendCode(trimmedAccount)
                    clock = CodeClock(ttl.ttlSeconds, env.nowMs())
                    code = ""
                    AuthOutcome.Verify
                }
            }
        } catch (e: HostException) {
            return fail(e, scene = AccountErrorContext.Login, allowResume = false)
        } finally {
            sending = false
        }
    }

    private fun apply(result: AgentLoginResult): AuthOutcome = when (result) {
        is AgentLoginResult.Ok -> AuthOutcome.Done
        is AgentLoginResult.DeviceVerificationRequired -> {
            clock = CodeClock(result.ttlSeconds, env.nowMs())
            willReplaceDevices = result.willReplaceDevices
            code = ""
            AuthOutcome.Verify
        }
        is AgentLoginResult.Unknown -> {
            errorText = env.text(R.string.accountErrorUnknown)
            AuthOutcome.Failed
        }
    }

    private fun fail(e: HostException, scene: AccountErrorContext, allowResume: Boolean): AuthOutcome {
        if (allowResume && AccountRules.isRegistrationIncomplete(e)) return AuthOutcome.RegistrationIncomplete
        errorText = env.error(e, scene)
        return AuthOutcome.Failed
    }
}

// ───────────────────────────── 注册 ─────────────────────────────

internal class RegisterFlow(private val env: AccountEnv) {
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var nickname by mutableStateOf("")
    var code by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    var clock by mutableStateOf<CodeClock?>(null)
        private set

    val trimmedEmail: String get() = trimmed(email)
    val canSubmitForm: Boolean get() = trimmedEmail.isNotEmpty() && password.isNotEmpty()
    val canSubmitVerification: Boolean get() = trimmed(code).isNotEmpty()

    /** 昵称：超出 32 个字符时不能提交（服务端同样拒绝），空 = 不设置。 */
    val nicknameInvalid: Boolean get() = trimmed(nickname).isNotEmpty() && !AccountRules.isValidNickname(nickname)

    fun setError(text: String?) {
        errorText = text
    }

    /** 登录发现「注册未完成」后接力：带着登录时输入的邮箱 / 密码重发注册验证码并进入验证步骤。 */
    fun resume(email: String, password: String) {
        this.email = email
        this.password = password
        code = ""
        errorText = null
    }

    /** 表单提交 / 重发（服务端作废旧码并发新码）。 */
    suspend fun submitForm(resending: Boolean = false): AuthOutcome {
        if (resending) {
            if (busy || sending) return AuthOutcome.Failed
            sending = true
        } else {
            if (busy) return AuthOutcome.Failed
            busy = true
        }
        errorText = null
        try {
            val name = trimmed(nickname)
            val params = RegisterParams(trimmedEmail, password, name.ifEmpty { null })
            return apply(env.api().register(params))
        } catch (e: HostException) {
            errorText = env.error(e, AccountErrorContext.Register)
            return AuthOutcome.Failed
        } finally {
            sending = false
            busy = false
        }
    }

    suspend fun submitVerification(): AuthOutcome {
        if (busy) return AuthOutcome.Failed
        busy = true
        errorText = null
        try {
            return apply(env.api().registerVerify(RegisterVerifyParams(trimmedEmail, trimmed(code))))
        } catch (e: HostException) {
            errorText = env.error(e, AccountErrorContext.Code)
            return AuthOutcome.Failed
        } finally {
            busy = false
        }
    }

    private fun apply(result: AgentLoginResult): AuthOutcome = when (result) {
        is AgentLoginResult.Ok -> AuthOutcome.Done
        is AgentLoginResult.DeviceVerificationRequired -> {
            clock = CodeClock(result.ttlSeconds, env.nowMs())
            code = ""
            AuthOutcome.Verify
        }
        is AgentLoginResult.Unknown -> {
            errorText = env.text(R.string.accountErrorUnknown)
            AuthOutcome.Failed
        }
    }
}

// ───────────────────────────── 重置密码（未登录） ─────────────────────────────

internal class ResetPasswordFlow(private val env: AccountEnv, initialEmail: String) {
    var email by mutableStateOf(initialEmail)
    var code by mutableStateOf("")
    var newPassword by mutableStateOf("")
    var confirm by mutableStateOf("")

    var busy by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var errorText by mutableStateOf<String?>(null)
        private set
    var clock by mutableStateOf<CodeClock?>(null)
        private set

    /** 验证码发往的邮箱：改了邮箱后旧码与冷却不再适用。 */
    var sentEmail by mutableStateOf<String?>(null)
        private set

    val trimmedEmail: String get() = trimmed(email)
    val emailValid: Boolean get() = AccountRules.isValidEmail(trimmedEmail)

    /** 邮箱非空但格式不合法。 */
    val emailInvalid: Boolean get() = trimmedEmail.isNotEmpty() && !emailValid

    /** 当前邮箱对应的验证码倒计时（邮箱改动后失效）。 */
    val activeClock: CodeClock? get() = if (sentEmail == trimmedEmail) clock else null

    val ruleKey: String? get() = AccountRules.newPasswordErrorKey(newPassword, confirm, current = null)

    /** 密码规则只在用户已输入且（已填确认或已太短）时提示，避免一打开就满屏红字。 */
    val visibleRuleKey: String?
        get() {
            val key = ruleKey
            if (key == null || newPassword.isEmpty()) return null
            return if (confirm.isNotEmpty() || key == "accountErrorPasswordTooShort") key else null
        }

    /** 过期的码由服务端拒绝（`accountErrorInvalidCode`）；界面上的倒计时只做提示，避免按钮随时钟闪烁。 */
    val canConfirm: Boolean
        get() = !sending && emailValid && clock != null && sentEmail == trimmedEmail && ruleKey == null && trimmed(code).isNotEmpty()

    suspend fun sendCode() {
        if (!emailValid || sending || busy) return
        val target = trimmedEmail
        sending = true
        errorText = null
        try {
            val ttl = env.api().sendPasswordResetCode(target)
            clock = CodeClock(ttl.ttlSeconds, env.nowMs())
            sentEmail = target
            code = ""
        } catch (e: HostException) {
            errorText = env.error(e, AccountErrorContext.Code)
        } finally {
            sending = false
        }
    }

    /** @return 重置成功（所有设备已退出，需用新密码重新登录）。 */
    suspend fun submit(): Boolean {
        if (busy) return false
        busy = true
        errorText = null
        try {
            env.api().resetPassword(ResetPasswordParams(trimmedEmail, trimmed(code), newPassword))
            return true
        } catch (e: HostException) {
            errorText = env.error(e, AccountErrorContext.Code)
            return false
        } finally {
            busy = false
        }
    }
}

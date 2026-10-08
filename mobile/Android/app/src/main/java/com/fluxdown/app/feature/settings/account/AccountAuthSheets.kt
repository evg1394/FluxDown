package com.fluxdown.app.feature.settings.account

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.fluxdown.app.R
import com.fluxdown.app.i18n.str
import com.fluxdown.core.protocol.AccountRules
import com.fluxdown.fluxui.controls.ButtonSize
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxField
import com.fluxdown.fluxui.controls.FluxPasswordField
import com.fluxdown.fluxui.controls.FluxSegmented
import com.fluxdown.fluxui.controls.SegOption
import com.fluxdown.fluxui.overlay.FluxBanner
import com.fluxdown.fluxui.overlay.FluxBannerKind
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.launch

/*
 * 登录 / 注册 / 重置密码（同 iOS `AccountAuthSheets`）。Sheet 内多步：新设备验证、注册邮箱验证是同一 Sheet 的第二步
 * （底部「上一步」返回）；忘记密码是叠在登录之上的第二个 Sheet。
 */

private enum class LoginStep { Credentials, DeviceVerify, RegisterVerify }

// ───────────────────────────── 登录 ─────────────────────────────

@Composable
internal fun LoginSheet(visible: Boolean, onDismiss: () -> Unit, readOnly: Boolean) {
    val env = rememberAccountEnv()
    val scope = rememberCoroutineScope()
    val haptics = FluxTheme.haptics
    val flow = remember(env) { LoginFlow(env) }
    val register = remember(env) { RegisterFlow(env) }
    var step by remember { mutableStateOf(LoginStep.Credentials) }
    var resetOpened by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var resetNonce by remember { mutableIntStateOf(0) }

    val busy = flow.busy || register.busy
    val byCode = flow.method == LoginMethod.Code

    fun succeeded() {
        haptics.confirm()
        onDismiss()
    }

    /** 服务端报告「注册未完成」：带着登录时输入的邮箱 / 密码重发注册验证码并转入注册验证步骤。 */
    suspend fun resumeRegistration() {
        register.resume(flow.trimmedAccount, flow.password)
        when (register.submitForm()) {
            AuthOutcome.Done -> succeeded()
            AuthOutcome.Verify -> step = LoginStep.RegisterVerify
            AuthOutcome.RegistrationIncomplete, AuthOutcome.Failed -> {
                flow.setError(register.errorText)
                haptics.reject()
            }
        }
    }

    fun submit() {
        scope.launch {
            when (step) {
                LoginStep.Credentials -> when (flow.submitCredentials()) {
                    AuthOutcome.Done -> succeeded()
                    AuthOutcome.Verify -> if (!byCode) step = LoginStep.DeviceVerify
                    AuthOutcome.RegistrationIncomplete -> resumeRegistration()
                    AuthOutcome.Failed -> haptics.reject()
                }
                LoginStep.DeviceVerify -> {
                    if (flow.submitDeviceVerification() == AuthOutcome.Done) succeeded() else haptics.reject()
                }
                LoginStep.RegisterVerify -> {
                    if (register.submitVerification() == AuthOutcome.Done) succeeded() else haptics.reject()
                }
            }
        }
    }

    fun resend() {
        scope.launch {
            when (step) {
                LoginStep.RegisterVerify -> {
                    // 重发注册验证码 = 以同一组信息再次注册（服务端作废旧码并发新码）
                    if (register.submitForm(resending = true) == AuthOutcome.Done) succeeded()
                }
                else -> if (flow.resend() == AuthOutcome.Done) succeeded()
            }
        }
    }

    fun back() {
        if (step == LoginStep.DeviceVerify) flow.resetVerification()
        step = LoginStep.Credentials
    }

    val title = when (step) {
        LoginStep.Credentials -> str(R.string.accountLoginDialogTitle)
        LoginStep.DeviceVerify -> str(R.string.accountDeviceVerifyTitle)
        LoginStep.RegisterVerify -> str(R.string.accountRegisterVerifyTitle)
    }
    val canConfirm = when (step) {
        LoginStep.Credentials -> flow.canSubmitCredentials
        LoginStep.DeviceVerify -> flow.canSubmitVerification
        LoginStep.RegisterVerify -> register.canSubmitVerification
    }

    AccountSheet(
        visible = visible,
        title = title,
        onDismiss = onDismiss,
        confirmText = if (step == LoginStep.Credentials) str(R.string.accountLogin) else str(R.string.accountVerifySubmit),
        canConfirm = canConfirm,
        onConfirm = ::submit,
        busy = busy,
        readOnly = readOnly,
        secondaryText = if (step == LoginStep.Credentials) null else str(R.string.back),
        onSecondary = if (step == LoginStep.Credentials) null else ::back,
    ) {
        when (step) {
            LoginStep.Credentials -> {
                FluxSegmented(
                    options = listOf(
                        SegOption(LoginMethod.Password, str(R.string.accountLoginTabPassword)),
                        SegOption(LoginMethod.Code, str(R.string.accountLoginTabCode)),
                    ),
                    selected = flow.method,
                    onSelect = {
                        if (!busy) {
                            flow.method = it
                            flow.setError(null)
                        }
                    },
                )
                FluxField(
                    value = flow.account,
                    onValueChange = { flow.account = it },
                    label = str(R.string.accountFieldAccount),
                    placeholder = if (byCode) str(R.string.accountEmailPlaceholder) else str(R.string.accountLoginAccountPlaceholder),
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (byCode) KeyboardType.Email else KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                )
                if (byCode) {
                    VerificationCodeRows(
                        code = flow.code,
                        onCodeChange = { flow.code = it },
                        clock = flow.clock,
                        sending = flow.sending,
                        onSend = ::resend,
                        locked = busy,
                        canSend = flow.trimmedAccount.isNotEmpty(),
                        onDone = { if (flow.canSubmitCredentials) submit() },
                    )
                } else {
                    FluxPasswordField(
                        value = flow.password,
                        onValueChange = { flow.password = it },
                        label = str(R.string.accountPasswordPlaceholder),
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (flow.canSubmitCredentials) submit() }),
                    )
                }
                flow.errorText?.let { AccountErrorLabel(it) }
                if (!byCode) {
                    FluxButton(
                        text = str(R.string.accountForgotPassword),
                        onClick = {
                            resetNonce += 1
                            resetOpened = true
                            resetting = true
                        },
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                        enabled = !busy,
                    )
                }
            }
            LoginStep.DeviceVerify -> {
                AccountBody(
                    if (flow.accountIsEmail) {
                        str(R.string.accountDeviceVerifySubtitle, "email" to flow.trimmedAccount)
                    } else {
                        str(R.string.accountDeviceVerifySubtitleGeneric)
                    },
                )
                if (flow.willReplaceDevices) {
                    FluxBanner(str(R.string.accountDeviceVerifyReplacementNotice), kind = FluxBannerKind.Warn, slim = true)
                }
                VerificationCodeRows(
                    code = flow.code,
                    onCodeChange = { flow.code = it },
                    clock = flow.clock,
                    sending = flow.sending,
                    onSend = ::resend,
                    locked = flow.busy,
                    onDone = { if (flow.canSubmitVerification) submit() },
                )
                flow.errorText?.let { AccountErrorLabel(it) }
            }
            LoginStep.RegisterVerify -> {
                AccountBody(str(R.string.accountRegisterVerifySubtitle, "email" to register.trimmedEmail))
                VerificationCodeRows(
                    code = register.code,
                    onCodeChange = { register.code = it },
                    clock = register.clock,
                    sending = register.sending,
                    onSend = ::resend,
                    locked = register.busy,
                    onDone = { if (register.canSubmitVerification) submit() },
                )
                register.errorText?.let { AccountErrorLabel(it) }
            }
        }
    }

    if (resetOpened) {
        key(resetNonce) {
            ResetPasswordSheet(
                visible = resetting,
                initialEmail = if (flow.accountIsEmail) flow.trimmedAccount else "",
                readOnly = readOnly,
                onDismiss = { resetting = false },
                onDone = { email ->
                    flow.account = email
                    flow.password = ""
                    flow.method = LoginMethod.Password
                    flow.setError(null)
                    resetting = false
                },
            )
        }
    }
}

// ───────────────────────────── 注册 ─────────────────────────────

@Composable
internal fun RegisterSheet(visible: Boolean, onDismiss: () -> Unit, readOnly: Boolean) {
    val env = rememberAccountEnv()
    val scope = rememberCoroutineScope()
    val haptics = FluxTheme.haptics
    val flow = remember(env) { RegisterFlow(env) }
    var verifying by remember { mutableStateOf(false) }

    fun succeeded() {
        haptics.confirm()
        onDismiss()
    }

    fun submit() {
        scope.launch {
            if (!verifying) {
                when (flow.submitForm()) {
                    AuthOutcome.Done -> succeeded()
                    AuthOutcome.Verify -> verifying = true
                    AuthOutcome.RegistrationIncomplete, AuthOutcome.Failed -> haptics.reject()
                }
            } else if (flow.submitVerification() == AuthOutcome.Done) {
                succeeded()
            } else {
                haptics.reject()
            }
        }
    }

    /** 重发注册验证码 = 以同一组信息再次注册（服务端作废旧码并发新码）。 */
    fun resend() {
        scope.launch { if (flow.submitForm(resending = true) == AuthOutcome.Done) succeeded() }
    }

    AccountSheet(
        visible = visible,
        title = if (verifying) str(R.string.accountRegisterVerifyTitle) else str(R.string.accountRegisterDialogTitle),
        onDismiss = onDismiss,
        confirmText = if (verifying) str(R.string.accountVerifySubmit) else str(R.string.accountRegister),
        canConfirm = if (verifying) flow.canSubmitVerification else flow.canSubmitForm && !flow.nicknameInvalid,
        onConfirm = ::submit,
        busy = flow.busy,
        readOnly = readOnly,
        secondaryText = if (verifying) str(R.string.back) else null,
        onSecondary = if (verifying) ({ verifying = false; flow.setError(null) }) else null,
    ) {
        if (!verifying) {
            FluxField(
                value = flow.email,
                onValueChange = { flow.email = it },
                label = str(R.string.accountEmailPlaceholder),
                enabled = !flow.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            )
            FluxPasswordField(
                value = flow.password,
                onValueChange = { flow.password = it },
                label = str(R.string.accountPasswordPlaceholder),
                hint = str(R.string.accountPasswordHint),
                enabled = !flow.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            )
            FluxField(
                value = flow.nickname,
                onValueChange = { flow.nickname = it },
                label = str(R.string.accountFieldNickname),
                placeholder = str(R.string.accountNicknamePlaceholder),
                error = if (flow.nicknameInvalid) str(R.string.accountNicknameEditInvalid) else null,
                enabled = !flow.busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (flow.canSubmitForm && !flow.nicknameInvalid) submit() }),
            )
        } else {
            AccountBody(str(R.string.accountRegisterVerifySubtitle, "email" to flow.trimmedEmail))
            VerificationCodeRows(
                code = flow.code,
                onCodeChange = { flow.code = it },
                clock = flow.clock,
                sending = flow.sending,
                onSend = ::resend,
                locked = flow.busy,
                onDone = { if (flow.canSubmitVerification) submit() },
            )
        }
        flow.errorText?.let { AccountErrorLabel(it) }
    }
}

// ───────────────────────────── 重置密码 ─────────────────────────────

/** 未登录重置密码：邮箱 → 验证码 → 新密码；成功后所有设备已退出，[onDone] 回传邮箱供登录页回填。 */
@Composable
internal fun ResetPasswordSheet(
    visible: Boolean,
    initialEmail: String,
    readOnly: Boolean,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    val env = rememberAccountEnv()
    val scope = rememberCoroutineScope()
    val haptics = FluxTheme.haptics
    val overlays = LocalFluxOverlays.current
    val flow = remember(env) { ResetPasswordFlow(env, initialEmail) }
    val success = str(R.string.accountPasswordResetSuccess)

    fun confirm() {
        val email = flow.trimmedEmail
        scope.launch {
            if (flow.submit()) {
                haptics.confirm()
                overlays.toast(success, FluxToastKind.Success)
                onDone(email)
            } else {
                haptics.reject()
            }
        }
    }

    AccountSheet(
        visible = visible,
        title = str(R.string.accountPasswordResetTitle),
        onDismiss = onDismiss,
        confirmText = str(R.string.confirm),
        canConfirm = flow.canConfirm,
        onConfirm = ::confirm,
        busy = flow.busy,
        readOnly = readOnly,
        subtitle = str(R.string.accountPasswordResetEmailHint),
    ) {
        FluxField(
            value = flow.email,
            onValueChange = { flow.email = it },
            label = str(R.string.accountEmailPlaceholder),
            error = if (flow.emailInvalid) str(R.string.accountErrorInvalidEmail) else null,
            enabled = !flow.busy && !flow.sending,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        VerificationCodeRows(
            code = flow.code,
            onCodeChange = { flow.code = it },
            clock = flow.activeClock,
            sending = flow.sending,
            onSend = { scope.launch { flow.sendCode() } },
            locked = flow.busy,
            canSend = flow.emailValid,
        )
        FluxPasswordField(
            value = flow.newPassword,
            onValueChange = { flow.newPassword = it },
            label = str(R.string.accountPasswordNewPlaceholder),
            hint = str(R.string.accountPasswordHint),
            enabled = !flow.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        )
        FluxPasswordField(
            value = flow.confirm,
            onValueChange = { flow.confirm = it },
            label = str(R.string.accountPasswordConfirmPlaceholder),
            error = flow.visibleRuleKey?.let { accountKeyText(it) },
            enabled = !flow.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (flow.canConfirm) confirm() }),
        )
        flow.errorText?.let { AccountErrorLabel(it) }
    }
}

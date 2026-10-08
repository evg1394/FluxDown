package com.fluxdown.app.feature.settings.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fluxdown.app.R
import com.fluxdown.app.i18n.str
import com.fluxdown.core.host.HostException
import com.fluxdown.core.protocol.AccountRules
import com.fluxdown.core.protocol.AgentSessionDto
import com.fluxdown.fluxui.controls.ButtonSize
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxField
import com.fluxdown.fluxui.controls.FluxPasswordField
import com.fluxdown.fluxui.controls.FluxSwitchRow
import com.fluxdown.fluxui.controls.GlassSection
import com.fluxdown.fluxui.feedback.FluxSpinner
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* 资料 / 安全编辑 Sheet：昵称、Origin ID（一次性）、修改邮箱（两步验证码）、修改 / 设置密码（同 iOS `AccountProfileSheets`）。 */

// ───────────────────────────── 昵称 ─────────────────────────────

@Composable
internal fun NicknameSheet(visible: Boolean, current: String, readOnly: Boolean, onDismiss: () -> Unit) {
    val env = rememberAccountEnv()
    val scope = rememberCoroutineScope()
    val haptics = FluxTheme.haptics
    val overlays = LocalFluxOverlays.current
    val success = str(R.string.accountNicknameEditSuccess)
    var value by remember { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val valid = AccountRules.isValidNickname(value)
    val unchanged = AccountRules.trimmed(value) == AccountRules.trimmed(current)

    fun save() {
        if (busy || !valid || unchanged) return
        val nickname = AccountRules.trimmed(value)
        busy = true
        errorText = null
        scope.launch {
            try {
                env.api().changeNickname(nickname)
                haptics.confirm()
                overlays.toast(success, FluxToastKind.Success)
                onDismiss()
            } catch (e: HostException) {
                errorText = env.error(e)
                haptics.reject()
            } finally {
                busy = false
            }
        }
    }

    AccountSheet(
        visible = visible,
        title = str(R.string.accountNicknameEditTitle),
        onDismiss = onDismiss,
        confirmText = str(R.string.confirm),
        canConfirm = valid && !unchanged,
        onConfirm = ::save,
        busy = busy,
        readOnly = readOnly,
    ) {
        FluxField(
            value = value,
            onValueChange = {
                value = it
                errorText = null
            },
            label = str(R.string.accountFieldNickname),
            error = if (!valid && value.isNotEmpty()) str(R.string.accountNicknameEditInvalid) else errorText,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
    }
}

// ───────────────────────────── Origin ID ─────────────────────────────

/**
 * Origin ID 一次性修改：入口由权益 `originIdEdit` 与 `originIdChanged` 严格把关（页面与 [OriginIdEditor.acceptsSession]）；
 * 提交前先检查可用性（防抖 400ms）并要求勾选「只能修改一次」确认。会话消失 / 失去资格 / 断线时作废在途请求。
 */
@Composable
internal fun OriginIdSheet(
    visible: Boolean,
    current: Long?,
    session: AgentSessionDto?,
    readOnly: Boolean,
    onDismiss: () -> Unit,
) {
    val env = rememberAccountEnv()
    val scope = rememberCoroutineScope()
    val haptics = FluxTheme.haptics
    val overlays = LocalFluxOverlays.current
    val success = str(R.string.accountOriginIdEditSuccess)
    val editor = remember(env) { OriginIdEditor(env, current) }
    val allowed = session != null && editor.acceptsSession(session)

    fun dismiss() {
        editor.invalidate()
        onDismiss()
    }

    // 防抖：停止输入 400ms 后再检查可用性；断线期间不检查，重连后（epoch 已变）重新检查。
    LaunchedEffect(editor.value, editor.epoch, readOnly) {
        if (readOnly) return@LaunchedEffect
        delay(400)
        editor.runCheck()
    }
    // 断线：在途检查 / 随机 / 保存结果全部作废，重连后需重新确认。
    LaunchedEffect(readOnly) { if (readOnly) editor.invalidate() }
    // 会话消失或失去资格（已改过 / 权益撤销 / 换了账号）：直接关闭。
    LaunchedEffect(allowed) { if (!allowed) dismiss() }

    fun save() {
        scope.launch {
            if (editor.submit()) {
                haptics.confirm()
                overlays.toast(success, FluxToastKind.Success)
                onDismiss()
            } else if (editor.errorText != null) {
                haptics.reject()
            }
        }
    }

    AccountSheet(
        visible = visible,
        title = str(R.string.accountOriginIdEditTitle),
        onDismiss = ::dismiss,
        confirmText = str(R.string.accountOriginIdEditConfirm),
        canConfirm = editor.canSubmit,
        onConfirm = ::save,
        busy = editor.busy,
        readOnly = readOnly,
    ) {
        AccountBody(str(R.string.accountOriginIdEditDesc))
        FluxField(
            value = editor.value,
            onValueChange = editor::onInput,
            label = str(R.string.accountOriginIdEditPlaceholder),
            mono = true,
            enabled = !editor.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            trailing = {
                FluxButton(
                    text = str(R.string.accountOriginIdEditRoll),
                    onClick = { scope.launch { editor.roll() } },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    enabled = !editor.busy && !readOnly,
                )
            },
        )
        when (editor.check) {
            OriginIdCheck.Idle -> Unit
            OriginIdCheck.Checking -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FluxSpinner(size = 16.dp, label = str(R.string.mobileOriginIdChecking))
                FluxText(str(R.string.mobileOriginIdChecking), style = FluxTheme.type.sm, color = FluxTheme.colors.inkMuted)
            }
            OriginIdCheck.Available -> editor.parsed?.let { id ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FluxIcon(FluxIcons.CircleCheck, null, size = 16.dp, tint = FluxTheme.colors.mintText)
                    FluxText("#$id", style = FluxTheme.type.mono, color = FluxTheme.colors.mintText)
                }
            }
            OriginIdCheck.Taken -> AccountErrorLabel(str(R.string.accountOriginIdErrorTaken))
            OriginIdCheck.Invalid -> AccountErrorLabel(str(R.string.accountOriginIdInvalid))
        }
        GlassSection {
            row {
                FluxSwitchRow(
                    title = str(R.string.accountOriginIdEditWarning),
                    checked = editor.confirmed,
                    onCheckedChange = editor::acknowledge,
                    enabled = !editor.busy && !readOnly,
                )
            }
        }
        editor.errorText?.let { AccountErrorLabel(it) }
    }
}

// ───────────────────────────── 修改邮箱 ─────────────────────────────

/** 修改邮箱：先验证原邮箱（打开即自动发码，仍有效的码恢复倒计时），再向新邮箱发码；两个验证码都通过才提交。 */
@Composable
internal fun EmailChangeSheet(
    visible: Boolean,
    userId: String,
    currentEmail: String,
    readOnly: Boolean,
    onDismiss: () -> Unit,
) {
    val env = rememberAccountEnv()
    val scope = rememberCoroutineScope()
    val haptics = FluxTheme.haptics
    val overlays = LocalFluxOverlays.current
    val success = str(R.string.accountEmailChangeSuccess)
    val flow = remember(env) { EmailChangeFlow(env, userId, currentEmail) }

    LaunchedEffect(flow, readOnly) { if (!readOnly) flow.start() }

    fun advance() {
        scope.launch {
            flow.advance()
            if (flow.errorText != null) haptics.reject()
        }
    }

    fun confirm() {
        scope.launch {
            if (flow.confirm()) {
                haptics.confirm()
                overlays.toast(success, FluxToastKind.Success)
                onDismiss()
            } else {
                haptics.reject()
            }
        }
    }

    val old = flow.step == EmailStep.Old
    AccountSheet(
        visible = visible,
        title = str(R.string.accountEmailChangeTitle),
        onDismiss = onDismiss,
        confirmText = if (old) str(R.string.accountEmailChangeSendNewCode) else str(R.string.confirm),
        canConfirm = if (old) flow.canNext else flow.canConfirm,
        onConfirm = if (old) ::advance else ::confirm,
        busy = flow.submitting,
        readOnly = readOnly,
        secondaryText = if (old) null else str(R.string.back),
        onSecondary = if (old) null else flow::back,
    ) {
        if (old) {
            if (flow.oldClock != null) {
                AccountBody(str(R.string.accountEmailChangeOldSubtitle, "email" to flow.currentEmail))
            }
            VerificationCodeRows(
                code = flow.oldCode,
                onCodeChange = { flow.oldCode = it },
                clock = flow.oldClock,
                sending = flow.sending,
                onSend = { scope.launch { flow.sendOldCode() } },
                locked = flow.submitting,
                fieldTitle = str(R.string.accountEmailChangeOldCodePlaceholder),
            )
            AccountHint(str(R.string.accountEmailChangeOldCodeHint))
            FluxField(
                value = flow.newEmail,
                onValueChange = { flow.newEmail = it },
                label = str(R.string.accountEmailChangeNewPlaceholder),
                error = when (flow.emailError) {
                    EmailInputError.None -> null
                    EmailInputError.Invalid -> str(R.string.accountEmailChangeInvalid)
                    EmailInputError.Same -> str(R.string.accountEmailChangeSame)
                },
                enabled = !flow.submitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (flow.canNext) advance() }),
            )
        } else {
            AccountBody(str(R.string.accountEmailChangeCodeSubtitle, "email" to (flow.sentEmail ?: flow.email)))
            VerificationCodeRows(
                code = flow.newCode,
                onCodeChange = { flow.newCode = it },
                clock = flow.activeNewClock,
                sending = flow.sending,
                onSend = { scope.launch { flow.sendNewCode(locking = false) } },
                locked = flow.submitting,
                canSend = flow.canNext,
                onDone = { if (flow.canConfirm) confirm() },
            )
        }
        flow.errorText?.let { AccountErrorLabel(it) }
    }
}

// ───────────────────────────── 修改 / 设置密码 ─────────────────────────────

/** 修改 / 设置密码：已设置密码的账号可在「当前密码」与「邮箱验证码」之间切换；未设置密码只有验证码。 */
@Composable
internal fun PasswordChangeSheet(
    visible: Boolean,
    userId: String,
    email: String,
    hasPassword: Boolean?,
    readOnly: Boolean,
    onDismiss: () -> Unit,
) {
    val env = rememberAccountEnv()
    val scope = rememberCoroutineScope()
    val haptics = FluxTheme.haptics
    val overlays = LocalFluxOverlays.current
    val success = str(R.string.accountPasswordChangeSuccess)
    val flow = remember(env) { PasswordChangeFlow(env, userId, email, hasPassword) }

    LaunchedEffect(flow) { flow.restoreIfNeeded() }

    fun confirm() {
        scope.launch {
            if (flow.submit()) {
                haptics.confirm()
                overlays.toast(success, FluxToastKind.Success)
                onDismiss()
            } else {
                haptics.reject()
            }
        }
    }

    AccountSheet(
        visible = visible,
        title = if (flow.canUsePassword) str(R.string.accountPasswordChangeTitle) else str(R.string.accountPasswordSetTitle),
        onDismiss = onDismiss,
        confirmText = str(R.string.confirm),
        canConfirm = flow.canConfirm,
        onConfirm = ::confirm,
        busy = flow.submitting,
        readOnly = readOnly,
    ) {
        if (flow.byCode) {
            AccountBody(
                if (flow.clock != null) {
                    str(R.string.accountPasswordCodeSubtitle, "email" to flow.email)
                } else {
                    str(R.string.accountPasswordCodeHint, "email" to flow.email)
                },
            )
            VerificationCodeRows(
                code = flow.code,
                onCodeChange = { flow.code = it },
                clock = flow.clock,
                sending = flow.sending,
                onSend = { scope.launch { flow.sendCode() } },
                locked = flow.submitting,
            )
        } else {
            FluxPasswordField(
                value = flow.current,
                onValueChange = { flow.current = it },
                label = str(R.string.accountPasswordCurrentPlaceholder),
                enabled = !flow.submitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            )
        }
        FluxPasswordField(
            value = flow.newPassword,
            onValueChange = { flow.newPassword = it },
            label = str(R.string.accountPasswordNewPlaceholder),
            hint = str(R.string.accountPasswordHint),
            enabled = !flow.submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
        )
        FluxPasswordField(
            value = flow.confirm,
            onValueChange = { flow.confirm = it },
            label = str(R.string.accountPasswordConfirmPlaceholder),
            error = flow.visibleRuleKey?.let { accountKeyText(it) },
            enabled = !flow.submitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (flow.canConfirm) confirm() }),
        )
        flow.errorText?.let { AccountErrorLabel(it) }
        if (flow.canUsePassword) {
            FluxButton(
                text = if (flow.byCode) str(R.string.accountPasswordUseCurrentPassword) else str(R.string.accountPasswordUseEmailCode),
                onClick = { flow.switchMode(if (flow.byCode) PasswordMode.Password else PasswordMode.Code) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = !flow.submitting,
            )
        }
    }
}

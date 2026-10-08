package com.fluxdown.app.feature.settings.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fluxdown.app.R
import com.fluxdown.app.i18n.str
import com.fluxdown.app.shell.LocalAppContainer
import com.fluxdown.core.protocol.CodeClock
import com.fluxdown.fluxui.controls.ButtonSize
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxField
import com.fluxdown.fluxui.controls.FluxIconButton
import com.fluxdown.fluxui.controls.IconButtonSize
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.overlay.FluxBanner
import com.fluxdown.fluxui.overlay.FluxBannerKind
import com.fluxdown.fluxui.overlay.FluxPortal
import com.fluxdown.fluxui.overlay.FluxSheet
import com.fluxdown.fluxui.overlay.FluxSheetFooter
import com.fluxdown.fluxui.overlay.FluxSheetHeader
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.delay

/* 账户表单的共用构件：验证码倒计时、错误行、统一 Sheet 骨架（同 iOS `AccountFormKit`）。全部是 FluxUI 控件的拼装。 */

/** 账户流程的运行环境（当前主机会话 / 文案 / 时钟），随主机与上下文重建。 */
@Composable
internal fun rememberAccountEnv(): AccountEnv {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    return remember(container, context) { AccountEnv(container, context) }
}

/**
 * 当前时间（毫秒），[active] 时每秒刷新一次；不活动时不起任何计时。
 * 验证码倒计时只依赖它求值（[CodeClock]），重建界面不会重置倒计时。
 */
@Composable
internal fun rememberNowMs(active: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return now
}

/** 表单内联错误（coral，图标 + 文字）。 */
@Composable
internal fun AccountErrorLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        FluxIcon(FluxIcons.CircleAlert, null, size = 16.dp, tint = FluxTheme.colors.coralText, modifier = Modifier.padding(top = 2.dp))
        FluxText(text, style = FluxTheme.type.sm, color = FluxTheme.colors.coralText, modifier = Modifier.weight(1f))
    }
}

/** 表单说明（sm inkFaint）。 */
@Composable
internal fun AccountHint(text: String, modifier: Modifier = Modifier) {
    FluxText(text, modifier.fillMaxWidth(), style = FluxTheme.type.sm, color = FluxTheme.colors.inkFaint)
}

/** 表单正文（sm inkMuted，可换行）。 */
@Composable
internal fun AccountBody(text: String, modifier: Modifier = Modifier) {
    FluxText(text, modifier.fillMaxWidth(), style = FluxTheme.type.sm, color = FluxTheme.colors.inkMuted)
}

/**
 * 验证码输入行 + 有效期 / 重发行：有效期（[CodeClock.remaining]）与重发冷却（发码后前 60 秒）每秒刷新。
 * @param locked 整个表单被主操作锁定
 * @param sending 正在发码（只让发码按钮转圈，其余输入仍可用）
 * @param canSend 发码前置条件（如邮箱格式合法）
 * @param firstSendTitle 尚未发过码时发码按钮的文案
 */
@Composable
internal fun VerificationCodeRows(
    code: String,
    onCodeChange: (String) -> Unit,
    clock: CodeClock?,
    sending: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    canSend: Boolean = true,
    firstSendTitle: String = str(R.string.accountSendCode),
    fieldTitle: String = str(R.string.accountCodePlaceholder),
    onDone: (() -> Unit)? = null,
) {
    val now = rememberNowMs(active = clock != null)
    val remaining = clock?.remaining(now) ?: 0L
    val cooldown = clock?.cooldown(now) ?: 0L
    val warn = FluxTheme.colors.amberText
    val muted = FluxTheme.colors.inkMuted
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FluxField(
            value = code,
            onValueChange = onCodeChange,
            label = str(R.string.accountFieldCode),
            placeholder = fieldTitle,
            mono = true,
            enabled = !locked,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (clock != null) {
                FluxText(
                    if (remaining > 0) str(R.string.accountCodeExpireIn, "seconds" to remaining) else str(R.string.accountCodeExpired),
                    modifier = Modifier.weight(1f),
                    style = FluxTheme.type.sm,
                    color = if (remaining > 0) muted else warn,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            FluxButton(
                text = when {
                    cooldown > 0 -> str(R.string.accountResendCodeIn, "seconds" to cooldown)
                    clock == null -> firstSendTitle
                    else -> str(R.string.accountResendCode)
                },
                onClick = onSend,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = !sending && !locked && cooldown <= 0 && canSend,
                loading = sending,
            )
        }
    }
}

/**
 * 账户 Sheet 统一骨架：标题 + 关闭钮、可滚内容、吸底「取消 / 主操作」。
 * [busy] 时锁住取消与交互式关闭（点遮罩 / 下拖 / 返回），主按钮转圈；[readOnly]（断线）时顶部提示且主操作置灰。
 * [secondaryText] / [onSecondary] 用于多步流程的「上一步」（缺省 = 取消并关闭）。
 */
@Composable
internal fun AccountSheet(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    confirmText: String,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    busy: Boolean,
    readOnly: Boolean,
    subtitle: String? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val closeDescription = str(R.string.close)
    val offline = str(R.string.localServiceDisconnected)
    val cancel = secondaryText ?: str(R.string.cancel)
    FluxPortal {
        FluxSheet(
            visible = visible,
            onDismissRequest = { if (!busy) onDismiss() },
            dismissible = !busy,
            title = title,
            header = {
                FluxSheetHeader(
                    title = title,
                    subtitle = subtitle,
                    actions = {
                        FluxIconButton(FluxIcons.X, closeDescription, onClick = { if (!busy) onDismiss() }, size = IconButtonSize.Sm)
                    },
                )
            },
            footer = {
                FluxSheetFooter {
                    FluxButton(
                        text = cancel,
                        onClick = { if (!busy) (onSecondary ?: onDismiss)() },
                        modifier = Modifier.weight(1f),
                        variant = ButtonVariant.Secondary,
                        enabled = !busy,
                    )
                    FluxButton(
                        text = confirmText,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        variant = ButtonVariant.Primary,
                        enabled = canConfirm && !readOnly,
                        loading = busy,
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (readOnly) FluxBanner(offline, kind = FluxBannerKind.Warn, slim = true)
                content()
            }
        }
    }
}

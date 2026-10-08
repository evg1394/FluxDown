package com.fluxdown.app.feature.settings.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalResources
import com.fluxdown.app.R
import com.fluxdown.app.nav.AppTab
import com.fluxdown.app.nav.LocalNavigator
import com.fluxdown.app.nav.Route
import com.fluxdown.app.nav.SettingsPage
import com.fluxdown.app.shell.LocalAppContainer
import com.fluxdown.core.protocol.AccountRules
import com.fluxdown.core.protocol.HostNotice
import com.fluxdown.core.protocol.Json
import com.fluxdown.core.protocol.stringOrNull
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.overlay.FluxDialogButton
import com.fluxdown.fluxui.overlay.FluxDialogButtonStyle
import com.fluxdown.fluxui.overlay.FluxDialogSpec
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.theme.FluxTheme

/**
 * 全局「会话被撤销」一次性提示（同 iOS `SessionRevokedAlert`）：agent 的 `sessionRevoked` 通知
 * （设备被移出信任 / 账号被封禁 / 会话过期 / 密码已改；载荷 = `ErrorReason` wire 名字符串）在任意页面之上弹出对话框，
 * 按钮「登录」跳到账户页、「好」关闭。用户主动退出不会触发该通知。由壳层在根部挂一次。
 */
@Composable
internal fun AccountEffects() {
    val container = LocalAppContainer.current
    val overlays = LocalFluxOverlays.current
    val nav = LocalNavigator.current
    val resources = LocalResources.current
    val haptics = FluxTheme.haptics
    val latestNav by rememberUpdatedState(nav)
    val latestOverlays by rememberUpdatedState(overlays)
    val latestResources by rememberUpdatedState(resources)

    LaunchedEffect(container) {
        container.store.notices.collect { notice ->
            if (notice.name != HostNotice.sessionRevoked) return@collect
            val reason = Json.parseOrNull(notice.json).stringOrNull.orEmpty()
            haptics.reject()
            latestOverlays.showDialog(
                FluxDialogSpec(
                    title = latestResources.getString(R.string.mobileSessionRevokedTitle),
                    message = latestResources.getString(AccountText.res(AccountRules.sessionRevokedKey(reason))),
                    icon = FluxIcons.ShieldAlert,
                    buttons = listOf(
                        FluxDialogButton(latestResources.getString(R.string.confirm), FluxDialogButtonStyle.Secondary),
                        FluxDialogButton(latestResources.getString(R.string.accountLogin), FluxDialogButtonStyle.Primary) {
                            latestNav.selectTab(AppTab.Settings)
                            latestNav.push(Route.Settings(SettingsPage.Account))
                        },
                    ),
                ),
            )
        }
    }
}

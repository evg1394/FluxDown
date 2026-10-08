package com.fluxdown.app.feature.settings.account

import android.content.ClipData
import android.content.ClipboardManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxdown.app.R
import com.fluxdown.app.feature.settings.SettingsEntry
import com.fluxdown.app.feature.settings.SettingsPageFrame
import com.fluxdown.app.feature.settings.SettingsSearchContext
import com.fluxdown.app.feature.settings.flowItem
import com.fluxdown.app.feature.settings.rememberLastNonNull
import com.fluxdown.app.feature.settings.settingsFocus
import com.fluxdown.app.i18n.str
import com.fluxdown.app.nav.AppTab
import com.fluxdown.app.nav.LocalNavigator
import com.fluxdown.app.nav.SettingsPage
import com.fluxdown.app.shell.LocalAppContainer
import com.fluxdown.app.shell.hostState
import com.fluxdown.core.host.HostErrorCode
import com.fluxdown.core.host.HostException
import com.fluxdown.core.protocol.AccountErrorContext
import com.fluxdown.core.protocol.AccountRules
import com.fluxdown.core.protocol.AgentSessionDto
import com.fluxdown.core.protocol.CloudConnectionDto
import com.fluxdown.core.protocol.CloudEndpointDto
import com.fluxdown.core.protocol.CloudPresence
import com.fluxdown.core.protocol.HostCapability
import com.fluxdown.core.protocol.HostSection
import com.fluxdown.core.protocol.Json
import com.fluxdown.core.protocol.SyncGroup
import com.fluxdown.core.protocol.SyncGroupId
import com.fluxdown.core.protocol.SyncGroupState
import com.fluxdown.core.protocol.SyncPhase
import com.fluxdown.core.protocol.SyncRules
import com.fluxdown.core.protocol.SyncStatusDto
import com.fluxdown.core.protocol.has
import com.fluxdown.core.protocol.section
import com.fluxdown.fluxui.controls.ButtonSize
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxActionRow
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxField
import com.fluxdown.fluxui.controls.FluxListRow
import com.fluxdown.fluxui.controls.FluxSwitchRow
import com.fluxdown.fluxui.controls.GlassSection
import com.fluxdown.fluxui.controls.Tone
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.material.rememberFlowInGate
import com.fluxdown.fluxui.overlay.FluxBanner
import com.fluxdown.fluxui.overlay.FluxBannerKind
import com.fluxdown.fluxui.overlay.FluxDialogButton
import com.fluxdown.fluxui.overlay.FluxDialogButtonStyle
import com.fluxdown.fluxui.overlay.FluxDialogSpec
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.fluxui.theme.fluxPressable
import kotlinx.coroutines.launch

/*
 * S2 · 账户（设置 → 账户）。随登录态呈现：未登录 Hero → 登录 / 注册 Sheet；已登录资料卡 → 账号与安全 → 云功能。
 * 会话 / 同步 / 云连接状态全部来自 `agent.session` / `agent.sync` / `agent.cloudConnection` 分区（无本地副本）。
 * 断连只读：所有写操作置灰，顶部显示 `localServiceDisconnected`。没有套餐购买 / 订单 / 推荐入口（与 iOS 一致）；
 * 套餐徽标只读显示。
 */

private const val SYNC_KEY = "sync"

private fun scopeKey(group: SyncGroup): String = "scope.${group.id.wire}"

@StringRes
private fun SyncGroupId.label(): Int = when (this) {
    SyncGroupId.Appearance -> R.string.syncScopeAppearance
    SyncGroupId.General -> R.string.syncScopeGeneral
    SyncGroupId.Ui -> R.string.syncScopeUi
    SyncGroupId.Download -> R.string.syncScopeDownload
    SyncGroupId.Bt -> R.string.syncScopeBt
    SyncGroupId.Ed2k -> R.string.syncScopeEd2k
    SyncGroupId.Categories -> R.string.syncScopeCategories
}

// ───────────────────────────── Sheet 路由 ─────────────────────────────

private enum class AccountSheetKind { Login, Register, Nickname, OriginId, Email, Password }

/** 一次打开 = 一个路由实例（[id] 唯一）：每次打开都是全新的表单状态；关闭时内容保留到退场动画结束。 */
private class AccountRoute(
    val id: Long,
    val kind: AccountSheetKind,
    val nickname: String = "",
    val originId: Long? = null,
    val userId: String = "",
    val email: String = "",
    val hasPassword: Boolean? = null,
) {
    /** 编辑类 Sheet 依赖当前会话。 */
    val requiresSession: Boolean get() = kind != AccountSheetKind.Login && kind != AccountSheetKind.Register
}

// ───────────────────────────── 页面 ─────────────────────────────

@Composable
internal fun AccountPage() {
    val nav = LocalNavigator.current
    val container = LocalAppContainer.current
    val overlays = LocalFluxOverlays.current
    val haptics = FluxTheme.haptics
    val context = LocalContext.current
    val env = rememberAccountEnv()
    val scope = rememberCoroutineScope()
    val gate = rememberFlowInGate()
    val hostState = hostState()
    val hostRef by container.host.collectAsStateWithLifecycle()

    val sessionRaw by remember { derivedStateOf { hostState.value.sections[HostSection.agentSession] } }
    val syncRaw by remember { derivedStateOf { hostState.value.sections[HostSection.agentSync] } }
    val connectionRaw by remember { derivedStateOf { hostState.value.sections[HostSection.agentCloudConnection] } }
    val session = remember(sessionRaw) { AgentSessionDto.fromJson(Json.parseOrNull(sessionRaw)) }
    val sync = remember(syncRaw) { SyncStatusDto.fromJson(Json.parseOrNull(syncRaw)) }
    val connection = remember(connectionRaw) { CloudConnectionDto.fromJson(Json.parseOrNull(connectionRaw)) }
    val readOnly by remember { derivedStateOf { hostState.value.isReadOnly } }
    val infoLoaded by remember { derivedStateOf { hostState.value.info != null } }
    val hasAuth by remember { derivedStateOf { hostState.value.has(HostCapability.agentAuth) } }
    val hasSync by remember { derivedStateOf { hostState.value.has(HostCapability.agentSync) } }
    val hasRemote by remember { derivedStateOf { hostState.value.has(HostCapability.agentRemoteTasks) } }
    val onlineCount by remember { derivedStateOf { hostState.value.cloudDevices.count { !it.isCurrent && it.isOnline } } }

    var route by remember { mutableStateOf<AccountRoute?>(null) }
    var nextRouteId by remember { mutableLongStateOf(0L) }
    var refreshing by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    val optimistic = remember { OptimisticValues<String, Boolean>() }
    var syncingNow by remember { mutableStateOf(false) }
    var reconnecting by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf<String?>(null) }

    var endpoint by remember { mutableStateOf<CloudEndpointDto?>(null) }
    var endpointDraft by remember { mutableStateOf("") }
    var endpointBusy by remember { mutableStateOf(false) }
    var endpointError by remember { mutableStateOf<String?>(null) }

    val loggedIn = session != null
    val userId = session?.user?.id

    fun open(kind: AccountSheetKind, nickname: String = "", originId: Long? = null, userId: String = "", email: String = "", hasPassword: Boolean? = null) {
        nextRouteId += 1
        route = AccountRoute(nextRouteId, kind, nickname, originId, userId, email, hasPassword)
    }

    // 切换主机：所有表单 / 多步流程都属于旧主机，一律关闭，不续用旧编辑请求。
    LaunchedEffect(hostRef.id) { route = null }
    // 会话没了（退出 / 被撤销）或换了账号：依赖会话的编辑 Sheet 随之关闭。
    LaunchedEffect(userId) {
        if (route?.requiresSession == true) route = null
        errorText = null
    }
    // 状态推送到达：乐观开关放手。
    LaunchedEffect(sync.enabled) { optimistic.settle(SYNC_KEY) }
    LaunchedEffect(sync.localOnlyKeys) { SyncRules.groups.forEach { optimistic.settle(scopeKey(it)) } }
    // FluxCloud 服务地址（仅调试构建可编辑）：主机切换 / 重连后重新读取；旧主机没有该方法 / 暂时不可用 → 保持隐藏。
    LaunchedEffect(readOnly, hostRef.id, hasAuth) {
        endpoint = null
        endpointError = null
        if (readOnly || !hasAuth) return@LaunchedEffect
        try {
            val value = env.api().cloudEndpoint()
            endpoint = value
            endpointDraft = value.baseUrl
        } catch (e: HostException) {
            endpoint = null
        }
    }

    // ── 动作 ──

    fun refresh() {
        if (refreshing) return
        refreshing = true
        errorText = null
        scope.launch {
            try {
                val api = env.api()
                api.refreshProfile()
                api.deviceList()
                haptics.confirm()
                overlays.toast(env.text(R.string.accountCloudRefreshDone), FluxToastKind.Success)
            } catch (e: HostException) {
                val text = env.error(e)
                errorText = text
                haptics.reject()
                overlays.toast(text, FluxToastKind.Error)
            } finally {
                refreshing = false
            }
        }
    }

    fun logout() {
        if (loggingOut) return
        loggingOut = true
        errorText = null
        scope.launch {
            try {
                env.api().logout()
            } catch (e: HostException) {
                // 本机会话已清除（云端吊销失败）时不再显示错误：用户已经是退出状态。
                val stillSignedIn = AgentSessionDto.fromJson(
                    Json.parseOrNull(container.store.state.value.sections[HostSection.agentSession]),
                ) != null
                if (stillSignedIn) {
                    errorText = env.error(e)
                    haptics.reject()
                }
            } finally {
                loggingOut = false
            }
        }
    }

    val logoutText = str(R.string.accountLogout)
    val logoutTitle = str(R.string.accountLogoutConfirmTitle)
    val logoutMessage = str(R.string.accountLogoutConfirmMessage)
    val cancelText = str(R.string.cancel)
    fun confirmLogout() {
        overlays.showDialog(
            FluxDialogSpec(
                title = logoutTitle,
                message = logoutMessage,
                icon = FluxIcons.LogOut,
                buttons = listOf(
                    FluxDialogButton(cancelText),
                    FluxDialogButton(logoutText, FluxDialogButtonStyle.Destructive) { logout() },
                ),
            ),
        )
    }

    val copiedText = str(R.string.accountOriginIdCopied)
    fun copyOriginId(id: Long) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Origin ID", id.toString()))
        haptics.confirm()
        overlays.toast(copiedText, FluxToastKind.Success, FluxIcons.Copy)
    }

    fun setSync(enabled: Boolean) {
        syncError = null
        scope.launch {
            val failure = optimistic.commit(SYNC_KEY, enabled) {
                val api = env.api()
                if (enabled) api.syncEnable() else api.syncDisable()
            }
            if (failure != null) {
                syncError = env.error(failure, AccountErrorContext.Sync)
                haptics.reject()
            }
        }
    }

    fun syncNow() {
        if (syncingNow) return
        syncingNow = true
        syncError = null
        scope.launch {
            try {
                env.api().syncNow()
                haptics.confirm()
            } catch (e: HostException) {
                syncError = env.error(e, AccountErrorContext.Sync)
                haptics.reject()
            } finally {
                syncingNow = false
            }
        }
    }

    fun toggleScope(group: SyncGroup, from: SyncGroupState) {
        val params = group.toggleParams(from)
        syncError = null
        scope.launch {
            val failure = optimistic.commit(scopeKey(group), !params.localOnly) { env.api().syncSetLocalOnly(params) }
            if (failure != null) {
                syncError = env.error(failure, AccountErrorContext.Sync)
                haptics.reject()
            }
        }
    }

    /** 云连接未建立时重试：先请求立即重连，再刷新设备名册（同 Web `DevicesCard.refresh`）。 */
    fun reconnect() {
        if (reconnecting) return
        reconnecting = true
        scope.launch {
            try {
                val api = env.api()
                api.remoteReconnect()
                api.deviceList()
                overlays.toast(env.text(R.string.cloudConnectionRetryStarted), FluxToastKind.Success)
            } catch (e: HostException) {
                haptics.reject()
                overlays.toast(env.error(e), FluxToastKind.Error)
            } finally {
                reconnecting = false
            }
        }
    }

    fun saveEndpoint(value: String) {
        if (endpointBusy) return
        val text = AccountRules.trimmed(value)
        endpointBusy = true
        endpointError = null
        scope.launch {
            try {
                val api = env.api()
                api.setCloudEndpoint(text)
                val updated = api.cloudEndpoint()
                endpoint = updated
                endpointDraft = updated.baseUrl
                haptics.confirm()
                overlays.toast(env.text(R.string.accountServerAddressSaved), FluxToastKind.Success)
            } catch (e: HostException) {
                endpointError = if (e.code == HostErrorCode.InvalidArgument) env.text(R.string.accountServerAddressInvalid) else env.error(e)
                haptics.reject()
            } finally {
                endpointBusy = false
            }
        }
    }

    // ── 渲染 ──

    val editableEndpoint = endpoint?.takeIf { it.editable }
    val phase = SyncRules.phase(sync)
    val syncActive = loggedIn && sync.enabled
    val presenceKnown = CloudPresence.isKnown(connection, localReady = !readOnly)
    val unsupported = infoLoaded && !hasAuth

    Box(Modifier.fillMaxSize()) {
    SettingsPageFrame(title = str(R.string.settingsCatAccount), onBack = { nav.pop() }) {
        var index = 0
        if (readOnly) {
            flowItem(index++, gate, "banner") {
                FluxBanner(str(R.string.localServiceDisconnected), kind = FluxBannerKind.Warn, slim = true)
            }
        }
        if (unsupported) {
            // 主机不支持账户能力（`agent.auth`）：整页不可用（设置首页本就隐藏入口，这里兜住从通知等处直达的情况）。
            flowItem(index++, gate, "unsupported") {
                FluxBanner(str(R.string.settingsUnsupportedOnPlatform), kind = FluxBannerKind.Info)
            }
            return@SettingsPageFrame
        }

        if (session != null) {
            val user = session.user
            val hasPassword = user.hasPassword != false
            flowItem(index++, gate, "profile") {
                GlassSection {
                    custom(padded = true) {
                        ProfileHeader(session, onCopyOriginId = ::copyOriginId)
                    }
                    row(hasIcon = true) {
                        FluxListRow(
                            title = str(R.string.accountNicknameEditTitle),
                            icon = FluxIcons.Pen,
                            value = AccountRules.trimmed(user.nickname).ifEmpty { null },
                            chevron = true,
                            enabled = !readOnly,
                            onClick = { open(AccountSheetKind.Nickname, nickname = user.nickname) },
                        )
                    }
                    // Origin ID 入口：严格要求权益 originIdEdit == true 且尚未改过（一次性）。
                    if (AccountRules.canEditOriginId(session)) {
                        row(hasIcon = true) {
                            FluxListRow(
                                title = str(R.string.accountOriginIdEditTitle),
                                icon = FluxIcons.Hash,
                                chevron = true,
                                enabled = !readOnly,
                                onClick = { open(AccountSheetKind.OriginId, originId = user.originId) },
                            )
                        }
                    }
                    row(hasIcon = true) {
                        FluxActionRow(
                            title = str(R.string.accountCloudRefresh),
                            icon = FluxIcons.RefreshCw,
                            loading = refreshing,
                            enabled = !readOnly,
                            onClick = ::refresh,
                        )
                    }
                    errorText?.let { text -> custom(padded = true) { AccountErrorLabel(text) } }
                }
            }
            flowItem(index++, gate, "security") {
                GlassSection(title = str(R.string.accountSecurityGroup), footer = str(R.string.accountSecurityGroupDesc)) {
                    row(hasIcon = true) {
                        Box(Modifier.settingsFocus("account.security")) {
                            FluxListRow(
                                title = str(R.string.accountEmailPlaceholder),
                                subtitle = user.email,
                                icon = FluxIcons.Mail,
                                chevron = true,
                                enabled = !readOnly,
                                onClick = { open(AccountSheetKind.Email, userId = user.id, email = user.email) },
                            )
                        }
                    }
                    row(hasIcon = true) {
                        FluxListRow(
                            title = str(R.string.accountPasswordTitle),
                            subtitle = if (hasPassword) str(R.string.accountPasswordStatusSet) else str(R.string.accountPasswordStatusNotSet),
                            icon = FluxIcons.Lock,
                            chevron = true,
                            enabled = !readOnly,
                            onClick = { open(AccountSheetKind.Password, userId = user.id, email = user.email, hasPassword = user.hasPassword) },
                        )
                    }
                }
            }
        } else {
            flowItem(index++, gate, "hero") { HeroHeader() }
            flowItem(index++, gate, "auth") {
                GlassSection {
                    row(hasIcon = true) {
                        FluxListRow(
                            title = str(R.string.accountLogin),
                            icon = FluxIcons.User,
                            iconTone = Tone.Accent,
                            chevron = true,
                            enabled = !readOnly,
                            onClick = { open(AccountSheetKind.Login) },
                        )
                    }
                    row(hasIcon = true) {
                        FluxListRow(
                            title = str(R.string.accountRegister),
                            icon = FluxIcons.UserPlus,
                            iconTone = Tone.Accent,
                            chevron = true,
                            enabled = !readOnly,
                            onClick = { open(AccountSheetKind.Register) },
                        )
                    }
                }
            }
            flowItem(index++, gate, "features") {
                GlassSection {
                    row(hasIcon = true) {
                        FluxListRow(
                            title = str(R.string.accountFeatureConfigSync),
                            subtitle = str(R.string.accountFeatureConfigSyncDesc),
                            icon = FluxIcons.CloudSync,
                            iconTone = Tone.Accent,
                        )
                    }
                    row(hasIcon = true) {
                        FluxListRow(
                            title = str(R.string.accountFeatureMultiDevice),
                            subtitle = str(R.string.accountFeatureMultiDeviceDesc),
                            icon = FluxIcons.Network,
                            iconTone = Tone.Accent,
                        )
                    }
                }
            }
        }

        // ── 云功能：配置同步 ──
        if (hasSync) {
            flowItem(index++, gate, "cloud") {
                GlassSection(title = str(R.string.accountGroupCloudFeatures), footer = str(R.string.accountCloudFeaturesDesc)) {
                    row(hasIcon = true) {
                        val status = syncStatusLine(sync, phase, loggedIn)
                        Box(Modifier.settingsFocus("account.cloudSync")) {
                            FluxSwitchRow(
                                title = str(R.string.cloudSyncTitle),
                                subtitle = status.text,
                                icon = status.icon,
                                iconTone = status.tone,
                                checked = loggedIn && optimistic.value(SYNC_KEY, sync.enabled),
                                onCheckedChange = ::setSync,
                                enabled = !readOnly && loggedIn && !optimistic.isPending(SYNC_KEY),
                            )
                        }
                    }
                    if (syncActive) {
                        row(hasIcon = true) {
                            FluxActionRow(
                                title = str(R.string.cloudSyncNow),
                                icon = FluxIcons.RefreshCw,
                                loading = syncingNow,
                                enabled = !readOnly && !sync.halted,
                                onClick = ::syncNow,
                            )
                        }
                    }
                    syncError?.let { text -> custom(padded = true) { AccountErrorLabel(text) } }
                }
            }
            if (syncActive) {
                flowItem(index++, gate, "syncScope") {
                    GlassSection(title = str(R.string.syncScopeTitle), footer = str(R.string.syncScopeDesc)) {
                        SyncRules.groups.forEachIndexed { i, group ->
                            val state = group.state(sync.localOnlyKeys)
                            val key = scopeKey(group)
                            row {
                                Box(if (i == 0) Modifier.settingsFocus("account.syncScope") else Modifier) {
                                    FluxSwitchRow(
                                        title = str(group.id.label()),
                                        subtitle = if (state == SyncGroupState.Mixed) str(R.string.syncScopeMixed) else null,
                                        checked = optimistic.value(key, state == SyncGroupState.Sync),
                                        onCheckedChange = { toggleScope(group, state) },
                                        enabled = !readOnly && !optimistic.isPending(key),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 多设备协同 ──
        if (hasRemote) {
            flowItem(index++, gate, "multiDevice") {
                GlassSection(footer = if (loggedIn && !presenceKnown) str(R.string.cloudConnectionStatusHint) else null) {
                    row(hasIcon = true) {
                        Box(Modifier.settingsFocus("account.multiDevice")) {
                            FluxListRow(
                                title = str(R.string.multiDeviceTitle),
                                subtitle = str(R.string.multiDeviceDesc),
                                icon = FluxIcons.Network,
                                iconTone = Tone.Accent,
                                value = when {
                                    !loggedIn -> null
                                    presenceKnown -> str(R.string.devicesOnlineCount, "count" to onlineCount)
                                    else -> str(R.string.devicePresenceUnknown)
                                },
                                chevron = true,
                                onClick = { nav.selectTab(AppTab.Devices) },
                            )
                        }
                    }
                    if (loggedIn) {
                        row(hasIcon = true) {
                            ConnectionRow(
                                connection = connection,
                                presenceKnown = presenceKnown,
                                readOnly = readOnly,
                                reconnecting = reconnecting,
                                onRetry = ::reconnect,
                            )
                        }
                    }
                }
            }
        }

        // ── FluxCloud 服务地址（仅调试构建可编辑） ──
        editableEndpoint?.let { value ->
            flowItem(index++, gate, "endpoint") {
                GlassSection(title = str(R.string.accountServerAddress), footer = str(R.string.accountServerAddressDesc)) {
                    custom(padded = true) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FluxField(
                                value = endpointDraft,
                                onValueChange = {
                                    endpointDraft = it
                                    endpointError = null
                                },
                                placeholder = value.defaultBaseUrl,
                                mono = true,
                                enabled = !readOnly && !endpointBusy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { saveEndpoint(endpointDraft) }),
                            )
                            endpointError?.let { AccountErrorLabel(it) }
                        }
                    }
                    row {
                        FluxActionRow(
                            title = str(R.string.accountServerAddressReset),
                            loading = endpointBusy,
                            enabled = !readOnly && value.baseUrl != value.defaultBaseUrl,
                            onClick = { saveEndpoint("") },
                        )
                    }
                }
            }
        }

        // ── 退出登录 ──
        if (session != null) {
            flowItem(index++, gate, "logout") {
                GlassSection {
                    row(hasIcon = true) {
                        FluxActionRow(
                            title = logoutText,
                            tone = Tone.Coral,
                            icon = FluxIcons.LogOut,
                            loading = loggingOut,
                            enabled = !readOnly,
                            onClick = ::confirmLogout,
                        )
                    }
                }
            }
        }
    }

    // ── Sheet 宿主：每次打开一个新路由（新表单状态）；关闭后内容保留到退场动画结束 ──
    val shown = rememberLastNonNull(route)
    if (shown != null) {
        androidx.compose.runtime.key(shown.id) {
            val visible = route === shown
            val dismiss = { if (route === shown) route = null }
            when (shown.kind) {
                AccountSheetKind.Login -> LoginSheet(visible, dismiss, readOnly)
                AccountSheetKind.Register -> RegisterSheet(visible, dismiss, readOnly)
                AccountSheetKind.Nickname -> NicknameSheet(visible, shown.nickname, readOnly, dismiss)
                AccountSheetKind.OriginId -> OriginIdSheet(visible, shown.originId, session, readOnly, dismiss)
                AccountSheetKind.Email -> EmailChangeSheet(visible, shown.userId, shown.email, readOnly, dismiss)
                AccountSheetKind.Password -> PasswordChangeSheet(visible, shown.userId, shown.email, shown.hasPassword, readOnly, dismiss)
            }
        }
    }
    }
}

// ───────────────────────────── 构件 ─────────────────────────────

/** 未登录 Hero：账户图标 + 标题 + 说明。 */
@Composable
private fun HeroHeader() {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(c.accentLo), contentAlignment = Alignment.Center) {
            FluxIcon(FluxIcons.CircleUser, null, size = 36.dp, tint = c.accentHi)
        }
        FluxText(str(R.string.accountLoginDialogTitle), style = t.h1.copy(textAlign = TextAlign.Center), color = c.ink)
        FluxText(str(R.string.accountHeroSubtitle), style = t.sm.copy(textAlign = TextAlign.Center), color = c.inkMuted)
    }
}

/** 资料头：头像（昵称首字符）+ 显示名 + 套餐徽标 + Origin ID 胶囊（点按复制）。 */
@Composable
private fun ProfileHeader(session: AgentSessionDto, onCopyOriginId: (Long) -> Unit) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val user = session.user
    val initial = AccountRules.avatarInitial(user)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(c.accentLo), contentAlignment = Alignment.Center) {
            if (initial != null) {
                FluxText(initial, style = t.title, color = c.accentHi, maxLines = 1)
            } else {
                FluxIcon(FluxIcons.Cloud, null, size = 28.dp, tint = c.accentHi)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FluxText(AccountRules.displayName(user), style = t.h1, color = c.ink, maxLines = 2)
            session.currentPlan?.let { plan -> PlanBadge(plan, user.membershipOrdinal) }
            OriginIdChip(user.originId, onCopyOriginId)
        }
    }
}

/** Origin ID 胶囊：等宽数字，点按复制；无 ID 时灰色 `#—` 不可点。 */
@Composable
private fun OriginIdChip(originId: Long?, onCopy: (Long) -> Unit) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    if (originId == null) {
        Box(
            Modifier.heightIn(min = 28.dp).clip(CircleShape).background(c.glass2).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            FluxText("#—", style = t.mono, color = c.inkMuted)
        }
        return
    }
    val description = str(R.string.accountOriginIdCopied)
    Row(
        Modifier
            .heightIn(min = 28.dp)
            .clip(CircleShape)
            .background(c.accentLo)
            .fluxPressable(onClick = { onCopy(originId) })
            .semantics { contentDescription = "$description $originId" }
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluxText("#$originId", style = t.mono, color = c.accentHi, maxLines = 1)
        FluxIcon(FluxIcons.Copy, null, size = 14.dp, tint = c.accentHi)
    }
}

/** 云连接状态行：连接状态 +（未连上时）重试 +（有失败原因时）原因说明。 */
@Composable
private fun ConnectionRow(
    connection: CloudConnectionDto?,
    presenceKnown: Boolean,
    readOnly: Boolean,
    reconnecting: Boolean,
    onRetry: () -> Unit,
) {
    val reasonKey = connection?.lastErrorReason?.let { AccountRules.errorKeyForReason(it) }
    val reasonText = when {
        reasonKey != null -> str(AccountText.res(reasonKey))
        connection?.lastError != null -> str(R.string.accountErrorNetwork)
        else -> null
    }
    Column(Modifier.fillMaxWidth()) {
        FluxListRow(
            title = accountKeyText(CloudPresence.labelKey(connection, localReady = !readOnly)),
            icon = if (presenceKnown) FluxIcons.CloudCheck else FluxIcons.CloudOff,
            iconTone = if (presenceKnown) Tone.Mint else Tone.Neutral,
            trailing = if (presenceKnown) {
                null
            } else {
                {
                    FluxButton(
                        text = str(R.string.cloudConnectionRetry),
                        onClick = onRetry,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                        enabled = !readOnly && !reconnecting,
                        loading = reconnecting,
                    )
                }
            },
        )
        if (!readOnly && reasonText != null) {
            AccountErrorLabel(reasonText, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
    }
}

private class SyncStatusLine(val icon: ImageVector, val tone: Tone, val text: String)

/** 同步状态行（优先级：未登录 → 未启用 → 暂停 → 失败 → 连接中 → 同步中 → 已同步）。 */
@Composable
private fun syncStatusLine(sync: SyncStatusDto, phase: SyncPhase, loggedIn: Boolean): SyncStatusLine {
    if (!loggedIn) return SyncStatusLine(FluxIcons.CloudOff, Tone.Neutral, str(R.string.cloudSyncLoginRequired))
    val context = LocalContext.current
    val reason = str(AccountText.res(SyncRules.reasonKey(sync)))
    return when (phase) {
        SyncPhase.Off -> SyncStatusLine(FluxIcons.CloudOff, Tone.Neutral, str(R.string.cloudSyncDesc))
        SyncPhase.Halted -> SyncStatusLine(FluxIcons.CirclePause, Tone.Amber, str(R.string.cloudSyncStatusHalted, "reason" to reason))
        SyncPhase.Error -> SyncStatusLine(FluxIcons.CloudAlert, Tone.Coral, str(R.string.cloudSyncStatusError, "reason" to reason))
        SyncPhase.Connecting -> SyncStatusLine(FluxIcons.Cloud, Tone.Neutral, str(R.string.cloudSyncStatusConnecting))
        SyncPhase.Syncing -> SyncStatusLine(FluxIcons.CloudSync, Tone.Accent, str(R.string.cloudSyncStatusSyncing))
        SyncPhase.Synced -> {
            val at = sync.lastSyncedAtUnixMs
            val text = if (at == null) {
                str(R.string.cloudSyncStatusSynced)
            } else {
                val ago = AccountText.syncAgo(context, SyncRules.ago(at, System.currentTimeMillis()))
                str(R.string.cloudSyncStatusSyncedAt, "time" to ago)
            }
            SyncStatusLine(FluxIcons.CloudCheck, Tone.Mint, text)
        }
    }
}

// ───────────────────────────── 搜索 ─────────────────────────────

/**
 * 设置搜索条目（iOS 只有分类入口；这里补上账户页内的分组，可见性与页面渲染共用同一判定：
 * 未登录无「账号与安全」、未启用同步无「同步范围」、缺能力的分组不出现）。
 */
internal fun accountSearchEntries(ctx: SettingsSearchContext): List<SettingsEntry> {
    val session = AgentSessionDto.fromJson(ctx.state.section(HostSection.agentSession))
    val sync = SyncStatusDto.fromJson(ctx.state.section(HostSection.agentSync))
    val cloudCrumb = ctx.crumb(R.string.settingsCatAccount, R.string.accountGroupCloudFeatures)
    return buildList {
        if (session != null) {
            add(
                ctx.entry(
                    "account.security", SettingsPage.Account, "security",
                    R.string.accountSecurityGroup, R.string.accountSecurityGroupDesc,
                    ctx.crumb(R.string.settingsCatAccount), FluxIcons.ShieldCheck,
                ),
            )
        }
        if (ctx.has(HostCapability.agentSync)) {
            add(
                ctx.entry(
                    "account.cloudSync", SettingsPage.Account, "cloud",
                    R.string.cloudSyncTitle, R.string.cloudSyncDesc, cloudCrumb, FluxIcons.CloudSync,
                ),
            )
            if (session != null && sync.enabled) {
                add(
                    ctx.entry(
                        "account.syncScope", SettingsPage.Account, "syncScope",
                        R.string.syncScopeTitle, R.string.syncScopeDesc, cloudCrumb, FluxIcons.CloudSync,
                    ),
                )
            }
        }
        if (ctx.has(HostCapability.agentRemoteTasks)) {
            add(
                ctx.entry(
                    "account.multiDevice", SettingsPage.Account, "multiDevice",
                    R.string.multiDeviceTitle, R.string.multiDeviceDesc,
                    ctx.crumb(R.string.settingsCatAccount), FluxIcons.Network,
                ),
            )
        }
    }
}

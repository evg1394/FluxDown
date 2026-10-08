package com.fluxdown.app.feature.settings.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.fluxdown.app.AppContainer
import com.fluxdown.app.R
import com.fluxdown.app.feature.settings.openLink
import com.fluxdown.app.i18n.str
import com.fluxdown.app.shell.LocalAppContainer
import com.fluxdown.core.host.HostException
import com.fluxdown.core.protocol.HostMethod
import com.fluxdown.core.protocol.PluginAuth
import com.fluxdown.core.protocol.PluginAuthRequest
import com.fluxdown.core.protocol.PluginAuthResponse
import com.fluxdown.core.protocol.PluginAuthState
import com.fluxdown.core.protocol.PluginDto
import com.fluxdown.core.protocol.callJson
import com.fluxdown.core.protocol.callUnit
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxField
import com.fluxdown.fluxui.controls.FluxFieldRow
import com.fluxdown.fluxui.controls.FluxListRow
import com.fluxdown.fluxui.controls.GlassSection
import com.fluxdown.fluxui.controls.Tone
import com.fluxdown.fluxui.feedback.FluxSpinner
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.overlay.FluxPortal
import com.fluxdown.fluxui.overlay.FluxSheet
import com.fluxdown.fluxui.overlay.FluxSheetDetent
import com.fluxdown.fluxui.overlay.FluxSheetFooter
import com.fluxdown.fluxui.overlay.FluxSheetHeader
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S11.5 · 插件平台登录。流程与 GPUI `plugin_auth.rs` / Web `PluginAuthDialog` / iOS `PluginAuthModel` 一致：
 * 打开即 `status` → `begin` →（二维码挑战每 2s 自动 `poll`，仅前台；其它挑战手动「检查状态」）→ success；
 * 已登录可 `logout`；任何方式关闭都向引擎 `cancel` 当前会话。
 */
@Stable
private class PluginAuthSession(
    private val identity: String,
    private val container: AppContainer,
    private val env: ExtensionsEnv,
) {
    var auth by mutableStateOf(PluginAuthState())
        private set
    var busy by mutableStateOf(true)
        private set
    var site by mutableStateOf("")
    var input by mutableStateOf("")

    private var queriedSite = ""
    private var alive = true
    private var polling = false

    val qrPolling: Boolean get() = auth.sessionPending && auth.isQrChallenge

    suspend fun start() {
        alive = true
        busy = false
        run(ACTION_STATUS, site = "")
    }

    /** 站点失焦 / 回车：按新站点刷新登录状态（同一站点只查一次）。 */
    suspend fun querySite() {
        if (busy || site == queriedSite) return
        queriedSite = site
        run(ACTION_STATUS, site = site)
    }

    suspend fun run(action: String, site: String = this.site, notifySuccess: Boolean = false) {
        if (busy) return
        // 轮询是后台动作：不置 busy（否则取消 / 输入框每 2s 闪一次禁用），仅防重入。
        val isPoll = action == ACTION_POLL
        if (polling) return
        if (isPoll) polling = true else busy = true
        try {
            if (!isPoll) auth = auth.copy(message = null)
            val status = action == ACTION_STATUS
            val request = PluginAuthRequest(
                identity = identity,
                action = action,
                site = site,
                authRef = if (status) "" else auth.authRef,
                sessionId = if (status) "" else auth.sessionId,
                input = if (status) "" else input,
            )
            val response = PluginAuthResponse.fromJson(container.session.callJson(HostMethod.daemonPluginAuth, request.toJson()))
            if (!alive) return
            if (response == null) {
                auth = auth.copy(status = "error", message = env.text(R.string.pluginAuthInvalidResponse))
                return
            }
            auth = PluginAuth.apply(auth, response, wasLogout = action == ACTION_LOGOUT)
            if (notifySuccess && response.status == "success") {
                env.toast(env.text(R.string.pluginAuthSuccess), FluxToastKind.Success)
            }
        } catch (e: HostException) {
            if (!alive) return
            auth = auth.copy(status = "error", message = env.text(R.string.pluginAuthFailed, "message" to env.error(e)))
        } finally {
            if (isPoll) polling = false else busy = false
        }
    }

    /** 关闭（取消按钮 / 下拉 / 返回）：向引擎释放悬空会话（后台发出，与在途 poll 并存无害）。 */
    fun close() {
        alive = false
        if (auth.sessionId.isEmpty()) return
        val request = PluginAuthRequest(identity, ACTION_CANCEL, site = site, authRef = auth.authRef, sessionId = auth.sessionId)
        val session = container.session
        container.appScope.launch {
            try {
                session.callUnit(HostMethod.daemonPluginAuth, request.toJson())
            } catch (_: HostException) {
                // 会话已过期 / 主机已断开：引擎侧本就会回收悬空会话，无需打扰用户。
            }
        }
    }

    companion object {
        const val ACTION_BEGIN = "begin"
        const val ACTION_POLL = "poll"
        const val ACTION_LOGOUT = "logout"
        const val ACTION_STATUS = "status"
        const val ACTION_CANCEL = "cancel"
    }
}

@Composable
internal fun PluginAuthSheet(sheet: PluginSheet.Auth, visible: Boolean, plugin: PluginDto, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val env = rememberExtensionsEnv()
    val session = remember(sheet) { PluginAuthSession(plugin.identity, container, env) }
    val title = str(R.string.pluginAuthDialogTitle, "name" to plugin.name)

    FluxPortal {
        FluxSheet(
            visible = visible,
            onDismissRequest = onDismiss,
            detent = FluxSheetDetent.Full,
            title = title,
            header = { FluxSheetHeader(title = title, subtitle = str(R.string.pluginAuthDescription), onClose = onDismiss) },
            footer = {
                FluxSheetFooter {
                    FluxButton(str(R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f))
                    AuthPrimaryButton(session, Modifier.weight(1f))
                }
            },
        ) {
            AuthBody(session, env)
        }
    }
}

@Composable
private fun AuthPrimaryButton(session: PluginAuthSession, modifier: Modifier) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val auth = session.auth
    when {
        session.busy -> FluxButton(str(R.string.pluginProcessing), onClick = {}, modifier = modifier, loading = true)
        auth.loggedIn -> FluxButton(
            str(R.string.pluginAuthLogout),
            onClick = { scope.launch { session.run(PluginAuthSession.ACTION_LOGOUT) } },
            modifier = modifier,
            variant = ButtonVariant.Danger,
        )
        // 非二维码挑战没有自动轮询，提供手动「检查状态」。
        auth.sessionPending && !auth.isQrChallenge -> FluxButton(
            str(R.string.pluginAuthPoll),
            onClick = { scope.launch { session.run(PluginAuthSession.ACTION_POLL, notifySuccess = true) } },
            modifier = modifier,
            variant = ButtonVariant.Primary,
        )
        !auth.sessionPending -> FluxButton(
            str(R.string.pluginAuthBegin),
            onClick = { scope.launch { session.run(PluginAuthSession.ACTION_BEGIN, notifySuccess = true) } },
            modifier = modifier,
            variant = ButtonVariant.Primary,
        )
    }
}

@Composable
private fun AuthBody(session: PluginAuthSession, env: ExtensionsEnv) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focusManager = LocalFocusManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val c = FluxTheme.colors
    val auth = session.auth

    LaunchedEffect(session) { session.start() }
    DisposableEffect(session) { onDispose { session.close() } }

    // 二维码挑战：前台每 2s 自动 poll；回到前台立即补一次。
    LaunchedEffect(session, session.qrPolling) {
        if (!session.qrPolling) return@LaunchedEffect
        var first = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!first) session.run(PluginAuthSession.ACTION_POLL, notifySuccess = true)
            first = false
            while (true) {
                delay(PluginAuth.POLL_INTERVAL_MS)
                session.run(PluginAuthSession.ACTION_POLL, notifySuccess = true)
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassSection {
            row {
                FluxFieldRow(title = str(R.string.pluginAuthSiteLabel)) {
                    FluxField(
                        value = session.site,
                        onValueChange = { session.site = it },
                        placeholder = str(R.string.pluginAuthSitePlaceholder),
                        mono = true,
                        enabled = !session.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            focusManager.clearFocus()
                            scope.launch { session.querySite() }
                        }),
                        onFocusChange = { focused -> if (!focused) scope.launch { session.querySite() } },
                    )
                }
            }
            row {
                FluxFieldRow(title = str(R.string.pluginAuthInputLabel)) {
                    FluxField(
                        value = session.input,
                        onValueChange = { session.input = it },
                        placeholder = str(R.string.pluginAuthInputPlaceholder),
                        enabled = !session.busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                }
            }
        }

        if (auth.loggedIn) {
            GlassSection {
                row(hasIcon = true) {
                    FluxListRow(title = str(R.string.pluginAuthSuccess), icon = FluxIcons.CircleCheck, iconTone = Tone.Mint)
                }
            }
        } else if (auth.sessionPending) {
            GlassSection {
                row {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FluxSpinner(size = 18.dp, label = str(R.string.pluginAuthPending))
                        FluxText(str(R.string.pluginAuthPending), style = FluxTheme.type.body, color = c.ink)
                    }
                }
            }
        }

        auth.challenge?.let { challenge ->
            ChallengeSection(challenge, auth.challengeType.orEmpty(), env)
        }

        auth.message?.let { message ->
            GlassSection {
                custom(padded = true) {
                    FluxText(
                        message,
                        Modifier.fillMaxWidth(),
                        style = FluxTheme.type.body,
                        color = if (auth.status == "error") c.coralText else c.ink,
                    )
                }
            }
        }
    }
}

/** 挑战渲染：只使用安全的 `data:image` 或本地编码的二维码，绝不把挑战 URL 当图片请求；其余退化为截断文本 + 复制。 */
@Composable
private fun ChallengeSection(value: String, type: String, env: ExtensionsEnv) {
    val c = FluxTheme.colors
    val context = LocalContext.current
    val overlays = LocalFluxOverlays.current
    val challenge = value
    val visual by produceState<ChallengeVisual?>(null, challenge, type) {
        this.value = withContext(Dispatchers.Default) { renderChallenge(challenge, type) }
    }
    val description = type.ifEmpty { str(R.string.pluginAuthQr) }
    val link = remember(value) { PluginAuth.safeHttpUrl(value) }
    val noBrowser = str(R.string.mobileNoBrowser)

    GlassSection {
        custom(padded = true) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (type.isNotEmpty()) FluxText(type, style = FluxTheme.type.sm, color = c.inkMuted)
                val shown = visual
                if (shown != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        ChallengeImage(shown, description)
                    }
                } else {
                    SelectionContainer {
                        FluxText(PluginAuth.truncate(value), Modifier.fillMaxWidth(), style = FluxTheme.type.sm, color = c.ink)
                    }
                }
            }
        }
        row(hasIcon = true) {
            FluxListRow(
                title = str(R.string.apiServiceCopy),
                icon = FluxIcons.Copy,
                onClick = {
                    env.context.copyPlainText(value)
                    env.toast(env.text(R.string.apiServiceCopied), FluxToastKind.Success)
                },
            )
        }
        // data URL 图片的原文是巨大的 base64，不作为分享文本；二维码分享其原文。
        if (visual is ChallengeVisual.Qr) {
            row(hasIcon = true) {
                FluxListRow(
                    title = str(R.string.mobileNotifActionShare),
                    icon = FluxIcons.Share2,
                    onClick = { context.shareText(value, description) },
                )
            }
        }
        if (link != null) {
            row(hasIcon = true) {
                FluxListRow(
                    title = str(R.string.mobilePluginAuthOpenLink),
                    icon = FluxIcons.ExternalLink,
                    onClick = { openLink(context, overlays, link, noBrowser) },
                )
            }
        }
    }
}

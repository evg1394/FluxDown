package com.fluxdown.app.feature.settings.service

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxdown.app.AppContainer
import com.fluxdown.app.R
import com.fluxdown.app.actions.LocalTaskActions
import com.fluxdown.app.feature.devices.ACCESS_KEY_MAX_LEN
import com.fluxdown.app.feature.devices.ACCESS_KEY_MIN_LEN
import com.fluxdown.app.feature.devices.KeyIssue
import com.fluxdown.app.feature.devices.validateAccessKey
import com.fluxdown.app.feature.settings.SettingsEntry
import com.fluxdown.app.feature.settings.SettingsPageFrame
import com.fluxdown.app.feature.settings.SettingsSearchContext
import com.fluxdown.app.feature.settings.flowItem
import com.fluxdown.app.feature.settings.settingsFocus
import com.fluxdown.app.i18n.str
import com.fluxdown.app.nav.LocalNavigator
import com.fluxdown.app.nav.SettingsPage
import com.fluxdown.app.shell.LocalAppContainer
import com.fluxdown.app.shell.hostState
import com.fluxdown.core.host.HostException
import com.fluxdown.core.model.HostRef
import com.fluxdown.core.protocol.GatewayAddress
import com.fluxdown.core.protocol.GatewayFeature
import com.fluxdown.core.protocol.GatewayPatchParams
import com.fluxdown.core.protocol.GatewayRevealTokenResult
import com.fluxdown.core.protocol.GatewayStatusDto
import com.fluxdown.core.protocol.HostCapability
import com.fluxdown.core.protocol.HostMethod
import com.fluxdown.core.protocol.HostSection
import com.fluxdown.core.protocol.Json
import com.fluxdown.core.protocol.callJson
import com.fluxdown.core.protocol.has
import com.fluxdown.core.protocol.section
import com.fluxdown.fluxui.controls.ButtonSize
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxActionRow
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxField
import com.fluxdown.fluxui.controls.FluxFieldRow
import com.fluxdown.fluxui.controls.FluxIconButton
import com.fluxdown.fluxui.controls.FluxKeyValue
import com.fluxdown.fluxui.controls.FluxListRow
import com.fluxdown.fluxui.controls.FluxSwitchRow
import com.fluxdown.fluxui.controls.GlassSection
import com.fluxdown.fluxui.controls.IconButtonSize
import com.fluxdown.fluxui.controls.Tone
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.material.rememberFlowInGate
import com.fluxdown.fluxui.overlay.FluxBanner
import com.fluxdown.fluxui.overlay.FluxBannerKind
import com.fluxdown.fluxui.overlay.FluxDialogButton
import com.fluxdown.fluxui.overlay.FluxDialogButtonStyle
import com.fluxdown.fluxui.overlay.FluxDialogSpec
import com.fluxdown.fluxui.overlay.FluxOverlayState
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val KEY_BANNER = "banner"
private const val KEY_HOST = "host"
private const val KEY_ADDRESS = "address"
private const val KEY_LAN = "lan"
private const val KEY_TOKEN = "token"
private const val KEY_FEATURES = "features"
private const val KEY_FORGET = "forget"

private const val ID_PORT = "api.port"
private const val ID_LAN = "api.lan"
private const val ID_TOKEN = "api.token"
private const val ID_FEATURES = "api.features"

private fun GatewayFeature.titleRes(): Int = when (this) {
    GatewayFeature.Takeover -> R.string.apiServiceTakeover
    GatewayFeature.Jsonrpc -> R.string.apiServiceJsonrpc
    GatewayFeature.Api -> R.string.apiServiceApi
    GatewayFeature.Mcp -> R.string.apiServiceMcp
    GatewayFeature.Cors -> R.string.apiServiceCorsAllowAll
}

private fun GatewayFeature.detailRes(): Int = when (this) {
    GatewayFeature.Takeover -> R.string.apiServiceTakeoverDesc
    GatewayFeature.Jsonrpc -> R.string.apiServiceJsonrpcDesc
    GatewayFeature.Api -> R.string.apiServiceApiDesc
    GatewayFeature.Mcp -> R.string.apiServiceMcpDesc
    GatewayFeature.Cors -> R.string.apiServiceCorsAllowAllDesc
}

private fun parseGateway(raw: String?): GatewayStatusDto = GatewayStatusDto.fromJson(Json.parseOrNull(raw))

// ───────────────────────────── 控制器 ─────────────────────────────

/**
 * 管理的是**该主机**的网关：功能开关、端口、访问令牌。
 * 令牌 = 访问密钥（gateway user token）：文本只经 `agent.gateway.revealToken` 按需读取（显示 / 复制前过设备认证），
 * 修改后立即把新密钥保存为该主机的访问密钥并重连，保证会话不断。
 */
@Stable
private class ApiServiceController(
    private val container: AppContainer,
    private val context: Context,
    private val overlays: FluxOverlayState,
    private val errorText: (HostException) -> String,
    private val scope: CoroutineScope,
    private val authenticate: suspend (String) -> Boolean,
) {
    /** 开关的乐观值（等主机状态追上后丢弃）。 */
    val pending = mutableStateMapOf<GatewayFeature, Boolean>()
    var pendingLan by mutableStateOf<Boolean?>(null)
        private set
    var portBusy by mutableStateOf(false)
        private set
    var token by mutableStateOf<String?>(null)
        private set
    var tokenBusy by mutableStateOf(false)
        private set

    /** 网关写入错误：端口占用 / 重启失败有专门文案，其余按通用规则。 */
    private fun describe(e: HostException): String = when (e.reason) {
        "gatewayPortInUse" -> context.str(R.string.apiServicePortInUse)
        "gatewayRestartFailed" -> context.str(R.string.apiServiceRestartFailed)
        else -> errorText(e)
    }

    private fun fail(e: HostException) = overlays.toast(describe(e), FluxToastKind.Error)

    private suspend fun patch(params: GatewayPatchParams): GatewayStatusDto =
        GatewayStatusDto.fromJson(container.session.callJson(HostMethod.agentGatewayPatch, params.toJson()))

    // ── 开关 ──

    fun set(feature: GatewayFeature, on: Boolean) {
        pending[feature] = on
        scope.launch {
            try {
                patch(feature.patch(on))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostException) {
                pending.remove(feature)
                fail(e)
            }
        }
    }

    fun setLan(on: Boolean) {
        pendingLan = on
        scope.launch {
            try {
                patch(GatewayPatchParams(lanEnabled = on))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostException) {
                pendingLan = null
                fail(e)
            }
        }
    }

    /** 主机状态更新后丢弃已追上的乐观值。 */
    fun reconcile(status: GatewayStatusDto) {
        for ((feature, value) in pending.toMap()) if (feature.isOn(status) == value) pending.remove(feature)
        if (pendingLan == status.lanEnabled) pendingLan = null
    }

    // ── 端口 ──

    fun setPort(port: Int) {
        if (portBusy) return
        portBusy = true
        scope.launch {
            try {
                val status = patch(GatewayPatchParams(port = port))
                overlays.toast(context.str(R.string.apiServiceRestarted, "port" to status.port), FluxToastKind.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostException) {
                fail(e)
            } finally {
                portBusy = false
            }
        }
    }

    // ── 令牌 ──

    /** 认证后读取令牌明文（`agent.gateway.revealToken`）；成功返回 true。 */
    private suspend fun reveal(): Boolean {
        if (tokenBusy) return false
        if (!authenticate(context.str(R.string.apiServiceTokenRevealAuth))) return false
        return loadToken()
    }

    suspend fun loadToken(): Boolean {
        tokenBusy = true
        try {
            token = GatewayRevealTokenResult.fromJson(container.session.callJson(HostMethod.agentGatewayRevealToken)).userToken
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: HostException) {
            fail(e)
            return false
        } finally {
            tokenBusy = false
        }
    }

    fun hideToken() {
        token = null
    }

    fun toggleReveal() {
        if (token != null) hideToken() else scope.launch { reveal() }
    }

    /** 复制令牌（必要时先认证并读取）；剪贴板内容标记为敏感。 */
    fun copyToken(onCopied: () -> Unit) {
        scope.launch {
            if (token == null && !reveal()) return@launch
            val value = token?.takeIf { it.isNotEmpty() } ?: return@launch
            copyToClipboard(context, "gateway token", value, sensitive = true)
            overlays.toast(context.str(R.string.apiServiceCopied), FluxToastKind.Success)
            onCopied()
        }
    }

    /** 自定义令牌：写网关 → 保存为该主机的访问密钥并重连。 */
    fun saveToken(value: String) {
        if (tokenBusy) return
        tokenBusy = true
        scope.launch {
            try {
                patch(GatewayPatchParams(userToken = value))
                token = value
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostException) {
                fail(e)
                tokenBusy = false
                return@launch
            }
            try {
                adoptAccessKey(value)
            } finally {
                tokenBusy = false
            }
        }
    }

    /** 重新生成：写网关 → 读新令牌 → 保存为该主机的访问密钥并重连。 */
    fun regenerate() {
        if (tokenBusy) return
        tokenBusy = true
        scope.launch {
            val fresh: String
            try {
                patch(GatewayPatchParams(regenerateUserToken = true))
                fresh = GatewayRevealTokenResult.fromJson(container.session.callJson(HostMethod.agentGatewayRevealToken)).userToken
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostException) {
                fail(e)
                tokenBusy = false
                return@launch
            }
            token = fresh
            try {
                adoptAccessKey(fresh)
            } finally {
                tokenBusy = false
            }
        }
    }

    /** 访问密钥已变：原地保存新密钥并用它重开当前主机（旧会话握着旧密钥，断线重连会被拒）。 */
    private suspend fun adoptAccessKey(key: String) {
        container.updateRemoteAccessKey(key).onFailure { e -> if (e is HostException) fail(e) else throw e }
    }

    /** 忘记这台主机：删除已保存的主机与密钥并回到本机。 */
    fun forgetHost() {
        val id = container.host.value.id
        scope.launch { container.removeHost(id) }
    }
}

/**
 * 设备认证（锁屏 PIN / 图案 / 密码 / 生物识别）：没有设置锁屏时无从认证，直接放行。
 * 用户主动取消返回 false 且保持静默。
 */
@Composable
private fun rememberDeviceAuth(): suspend (String) -> Boolean {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        holder[0]?.complete(result.resultCode == Activity.RESULT_OK)
        holder[0] = null
    }
    return remember(context, launcher) {
        val auth: suspend (String) -> Boolean = { reason ->
            val km = context.getSystemService(KeyguardManager::class.java)
            val intent = if (km != null && km.isDeviceSecure) km.createConfirmDeviceCredentialIntent(reason, null) else null
            if (intent == null) {
                true
            } else {
                val done = CompletableDeferred<Boolean>()
                holder[0] = done
                try {
                    launcher.launch(intent)
                    done.await()
                } catch (e: ActivityNotFoundException) {
                    holder[0] = null
                    true
                }
            }
        }
        auth
    }
}

// ───────────────────────────── 页面 ─────────────────────────────

/** S9 · API 服务（网关）：只在远端 `--server` 主机且具备 `agent.gateway` 能力时出现（设置首页已门控）。 */
@Composable
internal fun ApiServicePage() {
    val nav = LocalNavigator.current
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val overlays = LocalFluxOverlays.current
    val actions = LocalTaskActions.current
    val scope = rememberCoroutineScope()
    val authenticate = rememberDeviceAuth()
    val controller = remember(container, context, overlays, actions, scope, authenticate) {
        ApiServiceController(container, context, overlays, actions::errorText, scope, authenticate)
    }
    val hostState = hostState()
    val hostRef by container.host.collectAsStateWithLifecycle()
    val rawGateway by remember { derivedStateOf { hostState.value.sections[HostSection.agentGateway] } }
    val status = remember(rawGateway) { parseGateway(rawGateway) }
    val loaded = rawGateway != null
    val connected by remember { derivedStateOf { !hostState.value.isReadOnly } }
    val readOnly = !connected || !loaded
    val gate = rememberFlowInGate()
    val endpoint = (hostRef as? HostRef.Remote)?.endpoint.orEmpty()

    LaunchedEffect(status) { controller.reconcile(status) }
    // 令牌被改动（如开启管理 API 时主机自动生成）：已显示的明文重新读取。
    LaunchedEffect(status.userTokenConfigured) {
        if (controller.token != null) controller.loadToken()
    }
    androidx.compose.runtime.DisposableEffect(controller) { onDispose { controller.hideToken() } }

    SettingsPageFrame(title = str(R.string.settingsCatApiService), onBack = { nav.pop() }) {
        if (!connected) {
            flowItem(0, gate, KEY_BANNER) {
                FluxBanner(str(R.string.localServiceDisconnected), kind = FluxBannerKind.Warn, slim = true)
            }
        }
        flowItem(1, gate, KEY_HOST) {
            GlassSection {
                row { FluxKeyValue(str(R.string.mobileHostSwitchTitle), hostRef.displayName) }
            }
        }
        flowItem(2, gate, KEY_ADDRESS) {
            AddressSection(controller, status, endpoint, connected)
        }
        if (status.portEditable) {
            flowItem(3, gate, KEY_LAN) {
                Box(Modifier.settingsFocus(ID_LAN)) {
                    GlassSection(footer = str(R.string.apiServiceLanRestartHint)) {
                        row {
                            FluxSwitchRow(
                                title = str(R.string.apiServiceLanEnable),
                                subtitle = str(R.string.apiServiceLanEnableDesc),
                                checked = controller.pendingLan ?: status.lanEnabled,
                                onCheckedChange = controller::setLan,
                                enabled = connected,
                            )
                        }
                    }
                }
            }
        }
        flowItem(4, gate, KEY_TOKEN) {
            TokenSection(controller, status.userTokenConfigured, readOnly)
        }
        flowItem(5, gate, KEY_FEATURES) {
            Box(Modifier.settingsFocus(ID_FEATURES)) {
                GlassSection(title = str(R.string.apiServiceFeaturesTitle), footer = str(R.string.apiServiceFeaturesDesc)) {
                    GatewayFeature.entries.forEach { feature ->
                        val on = controller.pending[feature] ?: feature.isOn(status)
                        row {
                            Column {
                                FluxSwitchRow(
                                    title = str(feature.titleRes()),
                                    subtitle = str(feature.detailRes()),
                                    checked = on,
                                    onCheckedChange = { controller.set(feature, it) },
                                    enabled = !readOnly,
                                )
                                if (feature == GatewayFeature.Cors && on) {
                                    FluxText(
                                        str(R.string.apiServiceCorsAllowAllHelp),
                                        style = FluxTheme.type.sm,
                                        color = FluxTheme.colors.amberText,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        flowItem(6, gate, KEY_FORGET) {
            val forgetTitle = str(R.string.mobileApiForgetHost)
            val forgetDesc = str(R.string.mobileApiForgetHostDesc)
            val cancelText = str(R.string.cancel)
            GlassSection(footer = str(R.string.mobileApiForgetHostDesc)) {
                row(hasIcon = true) {
                    FluxActionRow(
                        title = str(R.string.mobileApiForgetHost),
                        icon = FluxIcons.Trash,
                        tone = Tone.Coral,
                        onClick = {
                            overlays.showDialog(
                                FluxDialogSpec(
                                    title = forgetTitle,
                                    message = forgetDesc,
                                    icon = FluxIcons.Trash,
                                    buttons = listOf(
                                        FluxDialogButton(cancelText),
                                        FluxDialogButton(forgetTitle, FluxDialogButtonStyle.Destructive) { controller.forgetHost() },
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressSection(controller: ApiServiceController, status: GatewayStatusDto, endpoint: String, connected: Boolean) {
    val context = LocalContext.current
    val overlays = LocalFluxOverlays.current
    val base = GatewayAddress.base(endpoint)
    val copied = str(R.string.apiServiceCopied)
    val copyLabel = str(R.string.apiServiceCopy)
    val shareLabel = str(R.string.mobileShareLink)
    val noBrowser = str(R.string.mobileNoBrowser)

    @Composable
    fun addressRow(title: String, url: String) {
        FluxListRow(
            title = title,
            subtitle = url,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FluxIconButton(FluxIcons.Copy, copyLabel, onClick = {
                        copyToClipboard(context, title, url)
                        overlays.toast(copied, FluxToastKind.Success)
                    }, size = IconButtonSize.Sm, glass = false)
                    FluxIconButton(FluxIcons.Share2, shareLabel, onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                        try {
                            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: ActivityNotFoundException) {
                            overlays.toast(noBrowser, FluxToastKind.Error)
                        }
                    }, size = IconButtonSize.Sm, glass = false)
                }
            },
        )
    }

    GlassSection(
        title = str(R.string.settingsCatApiService),
        footer = str(if (status.portEditable) R.string.apiServicePortRestartHint else R.string.webApiBindFixed),
    ) {
        row {
            Box(Modifier.settingsFocus(ID_PORT)) {
                if (status.portEditable) {
                    PortRow(controller, status.port, connected)
                } else {
                    FluxKeyValue(str(R.string.apiServicePort), (GatewayAddress.port(endpoint) ?: status.port).toString(), mono = true)
                }
            }
        }
        if (base != null) {
            row { addressRow(str(R.string.apiServiceAddress), base) }
            row { addressRow(str(R.string.apiServiceJsonrpc), "$base/jsonrpc") }
            row { addressRow(str(R.string.apiServiceMcp), "$base/mcp") }
            row { addressRow(str(R.string.apiServiceApi), "$base/api/v1") }
        }
    }
}

/** 端口（仅 `portEditable`）：回车 / 失焦提交，1024…65535；无效输入还原并提示。 */
@Composable
private fun PortRow(controller: ApiServiceController, port: Int, connected: Boolean) {
    val overlays = LocalFluxOverlays.current
    val haptics = FluxTheme.haptics
    var draft by remember(port) { mutableStateOf(port.toString()) }
    val invalid = str(R.string.apiServicePortInvalid)

    fun commit() {
        val text = draft.trim()
        if (text == port.toString()) return
        val value = text.toIntOrNull()
        if (value == null || value !in GatewayStatusDto.PORT_RANGE) {
            draft = port.toString()
            haptics.reject()
            overlays.toast(invalid, FluxToastKind.Error)
            return
        }
        controller.setPort(value)
    }

    FluxFieldRow(title = str(R.string.apiServicePort)) {
        FluxField(
            value = draft,
            onValueChange = { draft = it.filter(Char::isDigit).take(5) },
            mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            onFocusChange = { focused -> if (!focused) commit() },
            enabled = connected && !controller.portBusy,
        )
    }
}

@Composable
private fun TokenSection(controller: ApiServiceController, configured: Boolean, readOnly: Boolean) {
    val overlays = LocalFluxOverlays.current
    val revealed = controller.token
    val busy = controller.tokenBusy
    var draft by remember(revealed) { mutableStateOf(revealed.orEmpty()) }
    var issue by remember(revealed) { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val dirty = revealed != null && draft != revealed
    val notConfigured = str(R.string.proxyNotConfigured)
    val badChars = str(R.string.webKeyBadChars)
    val tooShort = str(R.string.webKeyTooShort, "min" to ACCESS_KEY_MIN_LEN)
    val tooLong = str(R.string.webKeyTooLong, "max" to ACCESS_KEY_MAX_LEN)
    val needsMix = str(R.string.webKeyNeedsMix)
    val cancelText = str(R.string.cancel)
    val generateText = str(R.string.apiServiceTokenGenerate)
    val generateDesc = str(R.string.apiServiceTokenDesc)

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    fun save() {
        val value = draft.trim()
        if (value == controller.token) return
        val problem = validateAccessKey(value)
        if (problem != null) {
            issue = when (problem) {
                KeyIssue.BadChars -> badChars
                KeyIssue.TooShort -> tooShort
                KeyIssue.TooLong -> tooLong
                KeyIssue.NeedsMix -> needsMix
            }
            return
        }
        issue = null
        controller.saveToken(value)
    }

    Box(Modifier.settingsFocus(ID_TOKEN)) {
        GlassSection(title = str(R.string.apiServiceToken), footer = str(R.string.apiServiceTokenDesc)) {
            if (revealed != null) {
                custom(padded = true) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FluxField(
                            value = draft,
                            onValueChange = {
                                draft = it
                                issue = null
                            },
                            placeholder = notConfigured,
                            error = issue,
                            mono = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            enabled = !readOnly && !busy,
                        )
                        if (dirty) {
                            FluxButton(
                                str(R.string.webSetupSubmit),
                                onClick = ::save,
                                variant = ButtonVariant.Primary,
                                size = ButtonSize.Sm,
                                loading = busy,
                                enabled = !readOnly && !busy,
                            )
                        }
                    }
                }
            } else {
                row {
                    FluxListRow(
                        title = if (configured) "••••••••••••" else notConfigured,
                        icon = FluxIcons.Key,
                    )
                }
            }
            row(hasIcon = true) {
                FluxActionRow(
                    title = str(if (revealed == null) R.string.webShowKey else R.string.webHideKey),
                    icon = if (revealed == null) FluxIcons.Eye else FluxIcons.EyeOff,
                    tone = Tone.Neutral,
                    enabled = !busy && (revealed != null || configured),
                    onClick = controller::toggleReveal,
                )
            }
            row(hasIcon = true) {
                FluxActionRow(
                    title = str(if (copied) R.string.apiServiceCopied else R.string.apiServiceCopy),
                    icon = if (copied) FluxIcons.Check else FluxIcons.Copy,
                    tone = Tone.Neutral,
                    enabled = !busy && (revealed != null || configured),
                    onClick = { controller.copyToken { copied = true } },
                )
            }
            row(hasIcon = true) {
                FluxActionRow(
                    title = str(R.string.apiServiceTokenGenerate),
                    icon = FluxIcons.RefreshCw,
                    loading = busy,
                    enabled = !readOnly && !busy,
                    onClick = {
                        overlays.showDialog(
                            FluxDialogSpec(
                                title = generateText,
                                message = generateDesc,
                                icon = FluxIcons.RefreshCw,
                                buttons = listOf(
                                    FluxDialogButton(cancelText),
                                    FluxDialogButton(generateText, FluxDialogButtonStyle.Primary) {
                                        controller.regenerate()
                                    },
                                ),
                            ),
                        )
                    },
                )
            }
        }
    }
}

// ───────────────────────────── 搜索 ─────────────────────────────

/** 搜索条目：仅远端主机且具备 `agent.gateway` 能力时有（与首页分类可见性一致）。 */
internal fun apiServiceSearchEntries(ctx: SettingsSearchContext): List<SettingsEntry> {
    if (ctx.isLocalHost || !ctx.has(HostCapability.agentGateway)) return emptyList()
    val status = parseGateway(ctx.state.sections[HostSection.agentGateway])
    val crumb = ctx.crumb(R.string.settingsCatApiService)
    val page = SettingsPage.Api
    return buildList {
        add(ctx.entry(ID_PORT, page, KEY_ADDRESS, R.string.apiServicePort, if (status.portEditable) R.string.apiServicePortRestartHint else R.string.webApiBindFixed, crumb, FluxIcons.Code))
        if (status.portEditable) {
            add(ctx.entry(ID_LAN, page, KEY_LAN, R.string.apiServiceLanEnable, R.string.apiServiceLanEnableDesc, crumb, FluxIcons.Wifi))
        }
        add(ctx.entry(ID_TOKEN, page, KEY_TOKEN, R.string.apiServiceToken, R.string.apiServiceTokenDesc, crumb, FluxIcons.Key))
        add(ctx.entry(ID_FEATURES, page, KEY_FEATURES, R.string.apiServiceFeaturesTitle, R.string.apiServiceFeaturesDesc, crumb, FluxIcons.Braces))
    }
}

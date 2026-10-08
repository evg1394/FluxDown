package com.fluxdown.app.feature.settings.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxdown.app.AppContainer
import com.fluxdown.app.R
import com.fluxdown.app.feature.settings.CommitTextField
import com.fluxdown.app.feature.settings.PickerSpec
import com.fluxdown.app.feature.settings.SettingRowBox
import com.fluxdown.app.feature.settings.SettingsCtx
import com.fluxdown.app.feature.settings.flowItem
import com.fluxdown.app.feature.settings.settingText
import com.fluxdown.app.i18n.str
import com.fluxdown.app.shell.hostState
import com.fluxdown.core.format.Format
import com.fluxdown.core.host.HostException
import com.fluxdown.core.protocol.ComponentKind
import com.fluxdown.core.protocol.ComponentProgressNotice
import com.fluxdown.core.protocol.ComponentResultNotice
import com.fluxdown.core.protocol.ComponentStatus
import com.fluxdown.core.protocol.ComponentStatusDto
import com.fluxdown.core.protocol.ComponentVersions
import com.fluxdown.core.protocol.HostMethod
import com.fluxdown.core.protocol.callJson
import com.fluxdown.core.protocol.callUnit
import com.fluxdown.fluxui.controls.ButtonSize
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxActionRow
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxFieldRow
import com.fluxdown.fluxui.controls.FluxKeyValue
import com.fluxdown.fluxui.controls.FluxListRow
import com.fluxdown.fluxui.controls.FluxProgressLine
import com.fluxdown.fluxui.controls.FluxSelect
import com.fluxdown.fluxui.controls.FluxTag
import com.fluxdown.fluxui.controls.GlassSection
import com.fluxdown.fluxui.controls.SelectOption
import com.fluxdown.fluxui.controls.Tone
import com.fluxdown.fluxui.feedback.FluxSpinner
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.material.FlowInGate
import com.fluxdown.fluxui.overlay.FluxDialogButton
import com.fluxdown.fluxui.overlay.FluxDialogButtonStyle
import com.fluxdown.fluxui.overlay.FluxDialogSpec
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.launch

/**
 * S11.6 · 组件（ffmpeg / yt-dlp）：生效状态、系统 PATH、手动路径、托管安装（版本 / 安装 / 更新 / 卸载 / 进度）、
 * 下载镜像基址。仅在连接远端 `--server` 主机时由 [ExtensionsPage] 呈现（本机没有可执行文件）。
 */
internal fun LazyListScope.componentItems(
    startIndex: Int,
    gate: FlowInGate,
    ctx: SettingsCtx,
    model: ExtensionsModel,
    env: ExtensionsEnv,
) {
    var index = startIndex
    for (kind in ComponentKind.known) {
        flowItem(index++, gate, "component.${kind.wire}") {
            ComponentCard(kind, ctx, model, env)
        }
    }
    flowItem(index, gate, "component.mirror") {
        GlassSection {
            settingText(
                ctx,
                key = "component_mirror_base",
                title = R.string.mobileComponentMirrorBase,
                desc = R.string.mobileComponentMirrorBaseDesc,
                placeholder = R.string.mobileComponentMirrorBasePlaceholder,
                id = "extensions.componentMirror",
            )
        }
    }
}

/**
 * 单个组件的状态与操作。进度来自一次性通知 `componentProgress` / `componentResult`
 * （也包含其他客户端发起的安装），由 [ExtensionsModel] 的常驻收集器分发；离开页签 / 页面不丢状态。
 */
@Stable
internal class ComponentController(val kind: ComponentKind, private val container: AppContainer) {
    class Progress(val installing: Boolean = false, val downloaded: Long = 0, val total: Long = 0) {
        val fraction: Double? get() = if (total > 0) (downloaded.toDouble() / total).coerceIn(0.0, 1.0) else null
    }

    /** `daemon.component.get` 回流：仅在快照里的该条目仍是当时那个值时覆盖，快照一更新即让位。 */
    private class Probed(val base: ComponentStatusDto?, val status: ComponentStatusDto)

    private var probed by mutableStateOf<Probed?>(null)
    var versions by mutableStateOf<List<String>>(emptyList())
        private set
    var versionsLoading by mutableStateOf(false)
        private set
    var versionsError by mutableStateOf<String?>(null)
        private set
    var versionsRequested by mutableStateOf(false)
        private set
    var selected by mutableStateOf<String?>(null)
    var progress by mutableStateOf(Progress())
        private set
    var uninstalling by mutableStateOf(false)
        private set

    private var installPending = false
    private var lastResult: ComponentResultNotice? = null

    private val session get() = container.session

    fun effective(snapshot: ComponentStatusDto?): ComponentStatusDto? =
        probed?.takeIf { it.base == snapshot }?.status ?: snapshot

    // ───────────── 通知 ─────────────

    fun onProgress(payload: ComponentProgressNotice) {
        progress = Progress(installing = true, downloaded = payload.downloadedBytes, total = payload.totalBytes)
    }

    fun onResult(payload: ComponentResultNotice) {
        lastResult = payload
        // 非本页发起的安装（如另一个客户端）：结果到达即结束进度展示。
        if (!installPending) progress = Progress(installing = false, downloaded = progress.downloaded, total = progress.total)
    }

    // ───────────── 版本 ─────────────

    fun fetchVersions(env: ExtensionsEnv) {
        if (versionsLoading) return
        versionsRequested = true
        versionsLoading = true
        versionsError = null
        container.appScope.launch {
            try {
                val result = ComponentVersions.fromJson(session.callJson(HostMethod.daemonComponentListVersions, kind.params()))
                versions = result.versions
                selected = result.defaultSelection(selected)
            } catch (e: HostException) {
                versionsError = env.error(e)
            } finally {
                versionsLoading = false
            }
        }
    }

    // ───────────── 安装 / 卸载 ─────────────

    fun install(env: ExtensionsEnv) {
        if (installPending || uninstalling) return
        installPending = true
        lastResult = null
        progress = Progress(installing = true)
        val title = env.context.componentTitle(kind.wire)
        val version = selected
        container.appScope.launch {
            try {
                session.callUnit(HostMethod.daemonComponentInstall, kind.installParams(version))
                env.toast(env.text(R.string.componentsInstallSuccess, "name" to title), FluxToastKind.Success)
            } catch (e: HostException) {
                // 引擎推送的结果携带真实错误说明；RPC 错误只有错误码。
                val pushed = lastResult?.takeIf { !it.ok && it.message.isNotEmpty() }?.message
                env.toast(env.text(R.string.componentsInstallFailed, "message" to (pushed ?: env.error(e))), FluxToastKind.Error)
            } finally {
                installPending = false
                progress = Progress(installing = false, downloaded = progress.downloaded, total = progress.total)
            }
        }
    }

    fun uninstall(env: ExtensionsEnv) {
        if (uninstalling || installPending) return
        uninstalling = true
        val title = env.context.componentTitle(kind.wire)
        container.appScope.launch {
            try {
                session.callUnit(HostMethod.daemonComponentUninstall, kind.params())
                env.toast(env.text(R.string.componentsUninstallSuccess, "name" to title), FluxToastKind.Success)
            } catch (e: HostException) {
                env.toast(env.text(R.string.componentsUninstallFailed, "message" to env.error(e)), FluxToastKind.Error)
            } finally {
                uninstalling = false
            }
        }
    }

    // ───────────── 手动路径 ─────────────

    /** 手动路径写入主机后重新探测生效状态（`ConfigEditor` 负责冲突重放 / 回滚 / 失败提示）。 */
    fun reprobe(snapshot: ComponentStatusDto?) {
        container.appScope.launch {
            try {
                // `daemon.component.get` 返回相邻标记 DTO `{"component": ..., "status": {...}}`（同 `daemon.components` 分区条目）。
                val dto = ComponentStatusDto.fromJson(session.callJson(HostMethod.daemonComponentGet, kind.params()))
                if (dto != null && dto.component == kind) probed = Probed(snapshot, dto)
            } catch (_: HostException) {
                // 探测失败：快照的 `componentsChanged` 事件随后会带来最新状态。
            }
        }
    }
}

@Composable
private fun ComponentCard(kind: ComponentKind, ctx: SettingsCtx, model: ExtensionsModel, env: ExtensionsEnv) {
    val controller = remember(model, kind) { model.controller(kind) }
    val host = hostState()
    val snapshots by rememberComponentStatuses()
    val snapshot = snapshots.firstOrNull { it.component == kind }
    val dto = controller.effective(snapshot)
    val status = dto?.status
    val readOnly = ctx.readOnly
    val busy = readOnly || controller.progress.installing || controller.uninstalling
    val title = str(componentTitleRes(kind) ?: R.string.settingsCatComponents)
    val pathKey = kind.manualPathConfigKey
    val serverPath by remember(pathKey) { derivedStateOf { pathKey?.let { host.value.config[it] } } }
    val latestSnapshot by rememberUpdatedState(snapshot)
    var primed by remember { mutableStateOf(false) }

    // 手动路径在主机侧生效后（配置回流）重新探测一次。
    LaunchedEffect(serverPath) {
        if (primed) controller.reprobe(latestSnapshot) else primed = true
    }
    // 版本列表只在「受支持且连接就绪」首次成立时懒拉一次，之后靠刷新按钮。
    LaunchedEffect(status?.managedSupported == true, readOnly) {
        if (status?.managedSupported == true && !readOnly && !controller.versionsRequested) controller.fetchVersions(env)
    }

    val id = if (kind == ComponentKind.Ytdlp) "extensions.ytdlp" else "extensions.ffmpeg"
    SettingRowBox(ctx, id, null) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusSection(kind, title, status, readOnly)
            if (pathKey != null && ctx.form.has(pathKey)) ManualPathSection(kind, title, pathKey, ctx)
            InstallSection(kind, title, status, controller, ctx, busy, env)
        }
    }
}

@Composable
private fun StatusSection(kind: ComponentKind, title: String, status: ComponentStatus?, readOnly: Boolean) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    GlassSection(
        title = title,
        footer = str(if (kind == ComponentKind.Ytdlp) R.string.componentsYtdlpDesc else R.string.componentsFfmpegDesc),
    ) {
        row {
            StatusRow(status, title, readOnly)
        }
        if (status != null) {
            row {
                FluxKeyValue(
                    key = str(R.string.componentsSystemPathLabel).trim(' ', ':', '：'),
                    value = status.systemPath.ifEmpty { str(R.string.componentsSystemPathNotFound) },
                    mono = true,
                )
            }
        }
    }
}

@Composable
private fun StatusRow(status: ComponentStatus?, title: String, readOnly: Boolean) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val rowModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
    when {
        status == null && readOnly -> Row(rowModifier, Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
            FluxSpinner(size = 18.dp, label = str(R.string.componentsStatusLoading))
            FluxText(str(R.string.componentsStatusLoading), style = t.body, color = c.ink)
        }
        // 快照已到但没有该组件：主机未编译组件支持（或当前平台不可用）。
        status == null -> WarningText(str(R.string.settingsUnsupportedOnPlatform), rowModifier)
        status.source == "none" -> WarningText(
            str(
                if (status.managedSupported) R.string.componentsStatusNotFound else R.string.componentsStatusNotFoundUnsupported,
                "name" to title,
            ),
            rowModifier,
        )
        else -> Column(rowModifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FluxIcon(FluxIcons.CircleCheck, null, size = 18.dp, tint = c.mintText)
                FluxTag(
                    when (status.source) {
                        "manual" -> str(R.string.componentsSourceManual)
                        "managed" -> str(R.string.componentsSourceManaged)
                        else -> str(R.string.componentsSourceSystem)
                    },
                )
                if (status.version.isNotEmpty()) FluxText("v${status.version}", style = t.monoS, color = c.ink)
            }
            SelectionContainer { FluxText(status.path, style = t.mono, color = c.inkMuted) }
        }
    }
}

@Composable
private fun WarningText(text: String, modifier: Modifier) {
    Row(modifier, Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
        FluxIcon(FluxIcons.TriangleAlert, null, size = 18.dp, tint = FluxTheme.colors.amberText)
        FluxText(text, style = FluxTheme.type.sm, color = FluxTheme.colors.amberText)
    }
}

@Composable
private fun ManualPathSection(kind: ComponentKind, title: String, key: String, ctx: SettingsCtx) {
    GlassSection {
        row {
            FluxFieldRow(
                title = str(R.string.componentsManualPathLabel),
                subtitle = str(R.string.componentsManualPathDesc, "name" to title),
                cloud = ctx.synced(key),
            ) {
                CommitTextField(
                    value = ctx.form.string(key),
                    onCommit = { ctx.editor.set(key, it.trim(), immediate = true) },
                    enabled = !ctx.readOnly,
                    placeholder = str(
                        if (kind == ComponentKind.Ytdlp) R.string.componentsManualPathHintYtdlpLinux else R.string.componentsManualPathHintFfmpegLinux,
                    ),
                )
            }
        }
    }
}

@Composable
private fun InstallSection(
    kind: ComponentKind,
    title: String,
    status: ComponentStatus?,
    controller: ComponentController,
    ctx: SettingsCtx,
    busy: Boolean,
    env: ExtensionsEnv,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val overlays = LocalFluxOverlays.current
    val readOnly = ctx.readOnly
    if (status?.managedSupported == false) {
        GlassSection {
            custom(padded = true) {
                FluxText(str(R.string.componentsManagedUnsupported, "name" to title), style = t.sm, color = c.inkMuted)
            }
        }
        return
    }
    val hasManaged = status?.hasManagedInstall ?: false
    val progress = controller.progress
    val installTitle = str(R.string.componentsInstallSectionTitle)
    GlassSection(
        title = installTitle,
        footer = str(if (kind == ComponentKind.Ytdlp) R.string.componentsInstallSectionDescYtdlp else R.string.componentsInstallSectionDescFfmpeg),
        action = {
            FluxButton(
                str(R.string.componentsFetchVersionsButton),
                onClick = { controller.fetchVersions(env) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Xs,
                enabled = !readOnly && !controller.versionsLoading,
            )
        },
    ) {
        if (status != null && hasManaged) {
            row { FluxListRow(title = str(R.string.componentsManagedVersionLabel, "version" to status.managedVersion)) }
        }
        controller.versionsError?.let { error ->
            custom(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FluxText(str(R.string.componentsVersionsLoadFailed, "message" to error), style = t.sm, color = c.coralText)
                    FluxButton(
                        str(R.string.componentsRetryVersions),
                        onClick = { controller.fetchVersions(env) },
                        size = ButtonSize.Sm,
                        enabled = !readOnly && !controller.versionsLoading,
                    )
                }
            }
        }
        custom(padded = true) {
            FluxSelect(
                value = controller.selected,
                onClick = {
                    ctx.openPicker(
                        PickerSpec(
                            title = installTitle,
                            options = controller.versions.map { SelectOption(it, it) },
                            selected = controller.selected,
                        ) { controller.selected = it },
                    )
                },
                placeholder = str(if (controller.versionsLoading) R.string.componentsVersionsLoading else R.string.componentsVersionSelectPlaceholder),
                enabled = controller.versions.isNotEmpty() && !busy,
            )
        }
        row(hasIcon = true) {
            FluxActionRow(
                title = str(
                    when {
                        progress.installing -> R.string.componentsInstalling
                        hasManaged -> R.string.componentsReinstallButton
                        else -> R.string.componentsInstallButton
                    },
                ),
                icon = FluxIcons.Download,
                loading = progress.installing,
                enabled = !busy,
                onClick = { controller.install(env) },
            )
        }
        if (hasManaged) {
            row(hasIcon = true) {
                FluxActionRow(
                    title = str(R.string.componentsUninstallButton),
                    icon = FluxIcons.Trash,
                    tone = Tone.Coral,
                    enabled = !busy,
                    onClick = {
                        overlays.showDialog(
                            FluxDialogSpec(
                                title = env.text(R.string.componentsUninstallConfirmTitle, "name" to title),
                                message = env.text(R.string.componentsUninstallConfirmMsg, "name" to title),
                                icon = FluxIcons.Trash,
                                buttons = listOf(
                                    FluxDialogButton(env.text(R.string.cancel)),
                                    FluxDialogButton(env.text(R.string.componentsUninstallButton), FluxDialogButtonStyle.Destructive) {
                                        controller.uninstall(env)
                                    },
                                ),
                            ),
                        )
                    },
                )
            }
        }
        if (progress.installing) {
            custom(padded = true) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    progress.fraction?.let { FluxProgressLine(it.toFloat()) }
                    FluxText(progressText(progress), style = t.monoS, color = c.inkMuted)
                }
            }
        }
    }
}

@Composable
private fun progressText(progress: ComponentController.Progress): String {
    val downloaded = Format.bytes(progress.downloaded).toString()
    val fraction = progress.fraction ?: return "$downloaded · ${str(R.string.componentsInstallUnknownSize)}"
    return "${Format.percent(fraction.toFloat())}  $downloaded / ${Format.bytes(progress.total)}"
}

package com.fluxdown.app.feature.settings.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.fluxdown.app.R
import com.fluxdown.app.actions.LocalTaskActions
import com.fluxdown.app.feature.settings.SettingRowBox
import com.fluxdown.app.feature.settings.SettingsCtx
import com.fluxdown.app.feature.settings.SettingsEntry
import com.fluxdown.app.feature.settings.SettingsPageFrame
import com.fluxdown.app.feature.settings.SettingsSearchContext
import com.fluxdown.app.feature.settings.flowItem
import com.fluxdown.app.feature.settings.rememberSettingsCtx
import com.fluxdown.app.feature.settings.startSettingsIntent
import com.fluxdown.app.i18n.str
import com.fluxdown.app.nav.LocalNavigator
import com.fluxdown.app.nav.SettingsPage
import com.fluxdown.app.shell.LocalAppContainer
import com.fluxdown.app.shell.hostState
import com.fluxdown.core.protocol.DiagnosticCheckDto
import com.fluxdown.core.protocol.DiagnosticLevel
import com.fluxdown.core.protocol.DiagnosticsLogic
import com.fluxdown.core.protocol.MobileCheckId
import com.fluxdown.core.protocol.MobileFix
import com.fluxdown.fluxui.controls.ButtonSize
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxActionRow
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxKeyValue
import com.fluxdown.fluxui.controls.FluxTag
import com.fluxdown.fluxui.controls.GlassSection
import com.fluxdown.fluxui.controls.Tone
import com.fluxdown.fluxui.feedback.FluxSpinner
import com.fluxdown.fluxui.icons.FluxIcon
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.material.rememberFlowInGate
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import com.fluxdown.core.store.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** LazyColumn 项 key = 搜索条目的 itemKey。 */
private const val SUMMARY_KEY = "summary"
private const val DEVICE_KEY = "device"
private const val HOST_KEY = "host"
private const val ENV_KEY = "env"

/**
 * S13 · 诊断：汇总 + 运行诊断 / 复制 / 分享报告，「此设备」检查、「下载主机」检查（`agent.diagnostics.run`）、
 * 主机环境信息与日志工具。设备检查进页面 / 回到前台即自动运行（快，纯本地）；
 * 主机诊断慢（≈60 s）：行内进度，页面其余部分照常可用。
 */
@Composable
internal fun DiagnosticsPage() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val overlays = LocalFluxOverlays.current
    val actions = LocalTaskActions.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val hostState = hostState()
    val connection by remember { derivedStateOf { hostState.value.connection } }
    val live = connection == Connection.Live
    val hostRef by container.host.collectAsStateWithLifecycle()

    val ctx = rememberSettingsCtx { }
    val model = remember(context, container) { DiagnosticsModel(context, container) }
    val logModel = rememberLogExportModel()
    val ui = remember(overlays, actions) {
        DiagnosticsUi(toast = { text, kind -> overlays.toast(text, kind) }, errorText = { actions.errorText(it) })
    }
    val gate = rememberFlowInGate()
    val copiedText = str(R.string.doctorCopied)
    val shareTitle = str(R.string.mobileDiagShareReport)
    val noSettings = str(R.string.mobileNoSettingsApp)

    // 进入页面 / 从系统设置改完权限回来 / 引擎连接状态变化：设备检查自动刷新。
    LaunchedEffect(owner, connection) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { model.refreshDevice(ui) }
    }
    // 清掉上次日志导出遗留的旧临时文件。
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { cleanStaleExports(context) } }
    // 切换主机：旧主机的报告不再显示（标题已是新主机名），其修复目标也不能发给新会话。
    LaunchedEffect(hostRef.id) { model.resetForHost(hostRef.id) }

    Box(Modifier.fillMaxSize()) {
        SettingsPageFrame(title = str(R.string.settingsCatDoctor), onBack = { nav.pop() }) {
            var index = 0
            flowItem(index++, gate, SUMMARY_KEY) {
                GlassSection(title = str(R.string.doctorTitle), footer = str(R.string.mobileDiagDesc)) {
                    row {
                        SettingRowBox(ctx, "diagnostics.summary", null) { SummaryContent(model) }
                    }
                    row {
                        SettingRowBox(ctx, "diagnostics.run", null) {
                            FluxActionRow(
                                title = str(if (model.isRunning) R.string.doctorRunning else R.string.doctorRun),
                                onClick = { scope.launch { model.run(ui) } },
                                icon = FluxIcons.Stethoscope,
                                loading = model.isRunning,
                                enabled = !model.isBusy || model.isRunning,
                            )
                        }
                    }
                    row {
                        SettingRowBox(ctx, "diagnostics.copy", null) {
                            FluxActionRow(
                                title = str(R.string.doctorCopyReport),
                                onClick = {
                                    copyText(context, model.reportText())
                                    overlays.toast(copiedText, FluxToastKind.Success)
                                },
                                icon = FluxIcons.Copy,
                                tone = Tone.Neutral,
                                enabled = model.device != null,
                            )
                        }
                    }
                    row {
                        SettingRowBox(ctx, "diagnostics.share", null) {
                            FluxActionRow(
                                title = str(R.string.mobileDiagShareReport),
                                onClick = { shareText(context, model.reportText(), shareTitle) },
                                icon = FluxIcons.Share2,
                                tone = Tone.Neutral,
                                enabled = model.device != null,
                            )
                        }
                    }
                }
            }
            flowItem(index++, gate, DEVICE_KEY) {
                GlassSection(title = str(R.string.mobileDiagSectionDevice)) {
                    val checks = model.device
                    if (checks == null) {
                        custom(padded = true) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                FluxSpinner(size = 18.dp, label = str(R.string.doctorRunning))
                                FluxText(str(R.string.doctorRunning), style = FluxTheme.type.sm, color = FluxTheme.colors.inkMuted)
                            }
                        }
                    } else {
                        for (check in checks) {
                            row {
                                val resolved = context.resolve(check)
                                SettingRowBox(ctx, deviceRowId(check.id), null) {
                                    DiagnosticsCheckRow(
                                        title = resolved.title,
                                        subtitle = null,
                                        detail = resolved.detail,
                                        level = resolved.level,
                                        hint = resolved.hint,
                                        repairTitle = resolved.fix?.let { fixLabel(context, it) },
                                        repairBusy = false,
                                        repairEnabled = true,
                                        onRepair = {
                                            resolved.fix?.let { fix ->
                                                startSettingsIntent(context, overlays, fixIntent(context, fix, resolved.fixPackage), noSettings)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            flowItem(index++, gate, HOST_KEY) {
                GlassSection(title = str(R.string.mobileDiagSectionHost, "name" to hostRef.displayName)) {
                    val report = model.host
                    if (report != null) {
                        if (report.checks.isEmpty()) {
                            custom(padded = true) {
                                StatusLine(FluxIcons.CircleCheck, FluxTheme.colors.mintText, str(R.string.doctorAllHealthy))
                            }
                        }
                        report.checks.forEachIndexed { i, check ->
                            val tag = DiagnosticsLogic.repairTag(i, check)
                            row {
                                HostCheckRow(ctx, model, ui, check, tag, live, scope)
                            }
                        }
                    } else if (live) {
                        custom(padded = true) {
                            FluxText(str(R.string.mobileDiagHostNotRun), style = FluxTheme.type.sm, color = FluxTheme.colors.inkMuted)
                        }
                    }
                    if (!live) {
                        custom(padded = true) {
                            StatusLine(FluxIcons.WifiOff, FluxTheme.colors.amberText, str(R.string.localServiceDisconnected))
                        }
                    }
                }
            }
            model.host?.let { report ->
                flowItem(index++, gate, ENV_KEY) {
                    GlassSection(title = str(R.string.doctorEnvTitle)) {
                        row { FluxKeyValue(str(R.string.currentVersion), report.report.appVersion) }
                        row { FluxKeyValue(str(R.string.mobileDiagEnvPlatform), report.report.platform) }
                        row { FluxKeyValue(str(R.string.doctorCheckDataDir), report.report.agentDataDir, mono = true) }
                        report.env?.let { env ->
                            row {
                                FluxKeyValue(
                                    str(R.string.doctorCheckDaemon),
                                    "${env.service.serviceVersion} · ${model.counts(env)}",
                                )
                            }
                            row { FluxKeyValue(str(R.string.doctorCheckLogDir), env.logDir, mono = true) }
                        }
                    }
                }
            }
            flowItem(index, gate, LOGS_ITEM_KEY) { LogsSection(ctx, logModel, scope) }
        }
    }
}

@Composable
private fun HostCheckRow(
    ctx: SettingsCtx,
    model: DiagnosticsModel,
    ui: DiagnosticsUi,
    check: DiagnosticCheckDto,
    tag: String,
    live: Boolean,
    /** 页面级作用域：修复 + 重新诊断耗时长，行所在的 lazy item 滚出视口被销毁时不能随之取消。 */
    scope: CoroutineScope,
) {
    val context = LocalContext.current
    SettingRowBox(ctx, "diagnostics.host.$tag", null) {
        DiagnosticsCheckRow(
            title = context.checkLabel(check),
            subtitle = check.target.ifEmpty { null },
            detail = check.detail,
            level = check.level,
            hint = context.hintText(check).orEmpty(),
            repairTitle = context.repairLabel(check),
            repairBusy = model.repairingTag == tag,
            repairEnabled = !model.isBusy && live,
            onRepair = { scope.launch { model.repair(check, tag, ui) } },
        )
    }
}

// ───────────────────────────── 汇总 ─────────────────────────────

@Composable
private fun SummaryContent(model: DiagnosticsModel) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val issues = model.issueCount
    val icon = when {
        !model.hasRun -> FluxIcons.Stethoscope
        issues == 0 -> FluxIcons.BadgeCheck
        else -> FluxIcons.TriangleAlert
    }
    val tint = when {
        !model.hasRun -> c.inkMuted
        issues == 0 -> c.mintText
        else -> c.amberText
    }
    val text = when {
        !model.hasRun -> str(R.string.doctorNeverRun)
        issues == 0 -> str(R.string.doctorAllHealthy)
        else -> str(R.string.doctorIssuesFound, "n" to issues)
    }
    val lastRun = model.lastRunAtMs?.let { ms ->
        str(R.string.doctorLastRun, "time" to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(ms)))
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluxIcon(icon, null, size = 22.dp, tint = tint)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FluxText(text, style = t.weight(t.body, 600), color = c.ink)
            if (lastRun != null) FluxText(lastRun, style = t.sm, color = c.inkMuted)
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        FluxIcon(icon, null, size = 18.dp, tint = tint)
        FluxText(text, style = FluxTheme.type.sm, color = FluxTheme.colors.ink)
    }
}

// ───────────────────────────── 检查行 ─────────────────────────────

/** 一条检查：标题 + 级别标签（图标 + 文字）、详情、可展开处理建议、可选修复按钮。 */
@Composable
private fun DiagnosticsCheckRow(
    title: String,
    subtitle: String?,
    detail: String,
    level: DiagnosticLevel,
    hint: String,
    repairTitle: String?,
    repairBusy: Boolean,
    repairEnabled: Boolean,
    onRepair: () -> Unit,
) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    var hintExpanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FluxText(title, style = t.weight(t.body, 450), color = c.ink)
                if (subtitle != null) {
                    SelectionContainer { FluxText(subtitle, style = t.mono, color = c.inkMuted) }
                }
            }
            LevelTag(level)
        }
        if (detail.isNotEmpty()) {
            SelectionContainer { FluxText(detail, style = t.sm, color = c.inkMuted) }
        }
        if (hint.isNotEmpty()) {
            Row(
                Modifier
                    .clickable(role = Role.Button) { hintExpanded = !hintExpanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FluxText(str(R.string.mobileDiagHowToFix), style = t.weight(t.sm, 600), color = c.accentHi)
                FluxIcon(
                    if (hintExpanded) FluxIcons.ChevronUp else FluxIcons.ChevronDown,
                    null,
                    size = 16.dp,
                    tint = c.accentHi,
                )
            }
            if (hintExpanded) FluxText(hint, style = t.sm, color = c.ink)
        }
        if (repairTitle != null) {
            FluxButton(
                text = if (repairBusy) str(R.string.doctorRepairing) else repairTitle,
                onClick = onRepair,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                loading = repairBusy,
                enabled = repairEnabled && !repairBusy,
            )
        }
    }
}

/** 级别标签：图标 + 文字（状态不只靠颜色）。 */
@Composable
private fun LevelTag(level: DiagnosticLevel) {
    val (tone, icon) = when (level) {
        DiagnosticLevel.Ok -> Tone.Mint to FluxIcons.CircleCheck
        DiagnosticLevel.Warn -> Tone.Amber to FluxIcons.TriangleAlert
        DiagnosticLevel.Error -> Tone.Coral to FluxIcons.CircleX
        DiagnosticLevel.Info, is DiagnosticLevel.Unknown -> Tone.Neutral to FluxIcons.Info
    }
    FluxTag(str(level.labelRes()), tone = tone, icon = icon)
}

// ───────────────────────────── 系统入口 ─────────────────────────────

private fun fixLabel(context: Context, fix: MobileFix): String = when (fix) {
    MobileFix.BatteryOptimization, MobileFix.BatterySaver -> context.str(R.string.mobileDiagAndFixBattery)
    MobileFix.NotificationSettings, MobileFix.AppDetails, MobileFix.DataSaver -> context.str(R.string.doctorActionOpenSettings)
}

private fun fixIntent(context: Context, fix: MobileFix, otherPackage: String?): Intent {
    val pkg = Uri.fromParts("package", otherPackage ?: context.packageName, null)
    return when (fix) {
        MobileFix.NotificationSettings ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        MobileFix.BatteryOptimization -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        MobileFix.BatterySaver -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        MobileFix.AppDetails -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        MobileFix.DataSaver -> Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, pkg)
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("FluxDown diagnostics", text))
}

private fun shareText(context: Context, text: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

private fun deviceRowId(id: MobileCheckId): String = "diagnostics.device.${id.wire}"

// ───────────────────────────── 搜索 / 读数 ─────────────────────────────

/** 设置搜索索引：运行 / 复制报告、各设备检查、日志行（主机检查项取决于运行结果，不入索引）。 */
internal fun diagnosticsSearchEntries(ctx: SettingsSearchContext): List<SettingsEntry> {
    val page = SettingsPage.Diagnostics
    val name = ctx.str(R.string.settingsCatDoctor)
    val entries = ArrayList<SettingsEntry>()
    entries += ctx.entry("diagnostics.run", page, SUMMARY_KEY, R.string.doctorRun, R.string.mobileDiagDesc, name, FluxIcons.Stethoscope)
    entries += ctx.entry("diagnostics.copy", page, SUMMARY_KEY, R.string.doctorCopyReport, null, name, FluxIcons.Stethoscope)
    entries += ctx.entry("diagnostics.share", page, SUMMARY_KEY, R.string.mobileDiagShareReport, null, name, FluxIcons.Stethoscope)
    val device = "$name › ${ctx.str(R.string.mobileDiagSectionDevice)}"
    for (id in MobileCheckId.entries) {
        entries += ctx.entry(deviceRowId(id), page, DEVICE_KEY, id.titleRes(), null, device, FluxIcons.Smartphone)
    }
    entries += logSearchEntries(ctx, page, "$name › ${ctx.str(R.string.mobileLogsTitle)}")
    return entries
}

/** 设置首页读数：最近一次运行的结论；从未运行 / 已换主机为 null。 */
internal fun diagnosticsReadout(ctx: SettingsSearchContext): String? = DiagnosticsSummary.readout(ctx)

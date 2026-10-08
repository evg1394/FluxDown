package com.fluxdown.app.feature.settings.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fluxdown.app.AppContainer
import com.fluxdown.app.R
import com.fluxdown.app.feature.settings.appVersionName
import com.fluxdown.app.i18n.str
import com.fluxdown.core.host.HostException
import com.fluxdown.core.protocol.DaemonDiagnosticsDescribe
import com.fluxdown.core.protocol.DiagnosticCheckDto
import com.fluxdown.core.protocol.DiagnosticsLogic
import com.fluxdown.core.protocol.DiagnosticsReportDto
import com.fluxdown.core.protocol.HostMethod
import com.fluxdown.core.protocol.MobileCheck
import com.fluxdown.core.protocol.MobileChecks
import com.fluxdown.core.protocol.callJson
import com.fluxdown.core.protocol.callUnit
import com.fluxdown.core.store.Connection
import com.fluxdown.fluxui.overlay.FluxToastKind
import kotlinx.coroutines.CancellationException

private const val TAG = "FluxDiagnostics"

/** 诊断页向用户反馈的出口（toast 与 HostException 本地化由页面提供）。 */
internal class DiagnosticsUi(
    val toast: (String, FluxToastKind) -> Unit,
    val errorText: (HostException) -> String,
)

/** 主机诊断结果：已滤掉桌面专属项。 */
internal class HostReport(
    val report: DiagnosticsReportDto,
    val checks: List<DiagnosticCheckDto>,
    val env: DaemonDiagnosticsDescribe?,
)

/**
 * 诊断页状态：设备检查（快、本地）+ 主机诊断（`agent.diagnostics.run`，慢 ≈60 s）+ 修复。
 * 设备检查进入页面 / 回到前台时自动刷新；主机诊断只在用户点「运行诊断」或修复后运行。
 */
@Stable
internal class DiagnosticsModel(private val context: Context, private val container: AppContainer) {
    /** null = 尚未完成首次设备检查。 */
    var device by mutableStateOf<List<MobileCheck>?>(null)
        private set
    var host by mutableStateOf<HostReport?>(null)
        private set
    var isRunning by mutableStateOf(false)
        private set

    /** 正在修复的检查项（[DiagnosticsLogic.repairTag]）。 */
    var repairingTag by mutableStateOf<String?>(null)
        private set
    var lastRunAtMs by mutableStateOf<Long?>(null)
        private set

    /** 点过「运行诊断」（汇总据此区分「尚未运行」）。 */
    var hasRun by mutableStateOf(false)
        private set

    val isBusy: Boolean get() = isRunning || repairingTag != null

    /** [host] 报告所属主机：切换主机后旧报告不再显示，修复也不能把旧主机的目标发给新会话。 */
    private var reportHostId: String? = null

    /** 当前主机变化（页面按主机 id 调用）：丢弃属于其它主机的报告与汇总。 */
    fun resetForHost(hostId: String) {
        if (reportHostId == null || reportHostId == hostId) return
        host = null
        hasRun = false
        lastRunAtMs = null
        reportHostId = null
    }

    /** warn + error 的条数（设备 + 主机）。 */
    val issueCount: Int
        get() = MobileChecks.issueCount(device.orEmpty()) + (host?.let { DiagnosticsLogic.issueCount(it.checks) } ?: 0)

    suspend fun refreshDevice(ui: DiagnosticsUi) {
        device = MobileCheckRunner.run(context, container, ui.errorText)
    }

    suspend fun run(ui: DiagnosticsUi) {
        if (isBusy) return
        isRunning = true
        hasRun = true
        val startedOn = container.host.value.id
        try {
            // 设备检查先出结果；主机诊断随后（慢）。
            refreshDevice(ui)
            var hostFailed = false
            if (container.store.state.value.connection == Connection.Live) {
                try {
                    val fetched = fetchHost(previousEnv = null)
                    if (container.host.value.id != startedOn) return // 期间切换了主机：结果属于旧主机，丢弃
                    host = fetched
                    reportHostId = startedOn
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HostException) {
                    hostFailed = true
                    ui.toast(ui.errorText(e), FluxToastKind.Error)
                }
            } else {
                host = null
                reportHostId = null
                hostFailed = true
                ui.toast(context.str(R.string.localServiceDisconnected), FluxToastKind.Warn)
            }
            lastRunAtMs = System.currentTimeMillis()
            publish()
            if (hostFailed) return
            val issues = issueCount
            if (issues == 0) {
                ui.toast(context.str(R.string.doctorRunDoneHealthy), FluxToastKind.Success)
            } else {
                ui.toast(context.str(R.string.doctorRunDoneIssues, "n" to issues), FluxToastKind.Warn)
            }
        } finally {
            isRunning = false
        }
    }

    /** 执行修复；成功与否都重新诊断（可能只完成了一部分）。 */
    suspend fun repair(check: DiagnosticCheckDto, tag: String, ui: DiagnosticsUi) {
        if (isBusy) return
        val hostId = reportHostId ?: return
        if (hostId != container.host.value.id) return // 报告属于另一台主机：不发修复
        val action = DiagnosticsLogic.repair(check) { context.stringByKey(it) != null } ?: return
        repairingTag = tag
        try {
            try {
                container.session.callUnit(HostMethod.agentDiagnosticsRepair, action.toJson())
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostException) {
                ui.toast(repairErrorText(e, ui), FluxToastKind.Error)
            }
            try {
                val fetched = fetchHost(previousEnv = host?.env)
                if (container.host.value.id != hostId) return
                host = fetched
                lastRunAtMs = System.currentTimeMillis()
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostException) {
                ui.toast(ui.errorText(e), FluxToastKind.Error)
            }
        } finally {
            repairingTag = null
        }
    }

    private fun repairErrorText(e: HostException, ui: DiagnosticsUi): String {
        val localized = DiagnosticsLogic.repairErrorKey(e.reason)?.let { context.stringByKey(it) }
        return localized ?: context.str(R.string.doctorRepairFailed, "error" to ui.errorText(e))
    }

    /** `agent.diagnostics.run` + `daemon.diagnostics.describe`（后者失败不影响报告）。 */
    private suspend fun fetchHost(previousEnv: DaemonDiagnosticsDescribe?): HostReport {
        val report = DiagnosticsReportDto.fromJson(container.session.callJson(HostMethod.agentDiagnosticsRun))
        var env = previousEnv
        try {
            env = DaemonDiagnosticsDescribe.fromJson(container.session.callJson(HostMethod.daemonDiagnosticsDescribe))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HostException) {
            Log.i(TAG, "describe unavailable: ${e.code} ${e.message}")
        }
        return HostReport(report, DiagnosticsLogic.visibleChecks(report.checks), env)
    }

    private fun publish() {
        DiagnosticsSummary.record(container.host, issueCount)
    }

    // ───────────────────────────── 报告 ─────────────────────────────

    fun reportText(): String {
        val deviceLines = device.orEmpty().map { check ->
            val resolved = context.resolve(check)
            DiagnosticsLogic.ReportLine(resolved.level, resolved.title, "", resolved.detail, resolved.hint)
        }
        val hostPart = host?.let { h ->
            DiagnosticsLogic.HostPart(
                name = container.host.value.displayName,
                appVersion = h.report.appVersion,
                platform = h.report.platform,
                dataDir = h.report.agentDataDir,
                daemonConnected = h.report.daemonConnected,
                daemonSummary = h.env?.let { envSummary(it) },
                lines = h.checks.map { check ->
                    DiagnosticsLogic.ReportLine(
                        level = check.level,
                        label = context.checkLabel(check),
                        target = check.target,
                        detail = check.detail,
                        hint = context.hintText(check) ?: check.hint,
                    )
                },
            )
        }
        return DiagnosticsLogic.renderReport(
            DiagnosticsLogic.ReportInput(
                appVersion = context.appVersionName(),
                deviceDescription = "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}",
                generatedAtUnixMs = lastRunAtMs ?: System.currentTimeMillis(),
                device = deviceLines,
                host = hostPart,
            ),
        )
    }

    private fun envSummary(env: DaemonDiagnosticsDescribe): String = listOf(
        env.service.serviceVersion,
        counts(env),
        "log dir ${env.logDir}",
    ).joinToString(" · ")

    /** 「N 任务 · N 队列 · N 任务组」。 */
    fun counts(env: DaemonDiagnosticsDescribe): String =
        context.str(R.string.mobileDiagEnvCounts, "tasks" to env.tasks, "queues" to env.queues, "groups" to env.groups)
}

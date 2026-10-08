package com.fluxdown.app.feature.settings.diagnostics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.fluxdown.app.R
import com.fluxdown.app.feature.settings.SettingsSearchContext
import com.fluxdown.core.model.HostRef
import com.fluxdown.core.protocol.DiagnosticsRecord
import kotlinx.coroutines.flow.StateFlow

/**
 * 设置首页「诊断」读数：最近一次运行的问题数（进程内单例，按主机；换主机即丢弃）。
 * [version] 是 Compose 快照状态，记录变化时首页读数随之重组。
 */
internal object DiagnosticsSummary {
    private val record = DiagnosticsRecord()
    private var hostFlow: StateFlow<HostRef>? = null
    private var version by mutableIntStateOf(0)

    fun record(host: StateFlow<HostRef>, issues: Int) {
        hostFlow = host
        record.record(host.value.id, issues)
        version++
    }

    /** 首页副标题；从未运行 / 已换主机 → null（使用默认说明）。 */
    fun readout(ctx: SettingsSearchContext): String? {
        if (version < 0) return null // 读取快照状态：记录变化时重组（version 恒 ≥ 0）
        val flow = hostFlow ?: return null
        record.reset(ifHostIsNot = flow.value.id)
        val issues = record.issues ?: return null
        return if (issues == 0) ctx.str(R.string.doctorAllHealthy) else ctx.str(R.string.doctorIssuesFound, "n" to issues)
    }
}

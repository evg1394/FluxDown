package com.fluxdown.app.service

import com.fluxdown.core.model.RuntimeStats

/**
 * 本机引擎的聚合活动量（与当前选中的主机无关）：驱动前台服务的存续与通知文案。
 * 来源见 `AppContainer.localActivity`。
 */
data class LocalActivity(
    val active: Int,
    val pending: Int,
    val retryPending: Int,
    val downBps: Long,
    val upBps: Long,
) {
    /** 活跃 + 排队 + 待重试都算“还在工作”：进程必须保活。 */
    val busy: Boolean get() = active + pending + retryPending > 0

    /** 通知里的“等待中”数量（排队 + 待重试）。 */
    val waiting: Int get() = pending + retryPending

    companion object {
        val Idle = LocalActivity(active = 0, pending = 0, retryPending = 0, downBps = 0, upBps = 0)

        fun from(stats: RuntimeStats) = LocalActivity(
            active = stats.activeTasks,
            pending = stats.pendingTasks,
            retryPending = stats.retryPendingTasks,
            downBps = stats.totalDownloadBps,
            upBps = stats.totalUploadBps,
        )
    }
}

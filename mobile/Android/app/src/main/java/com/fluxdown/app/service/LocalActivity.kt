package com.fluxdown.app.service

import com.fluxdown.core.store.Connection
import com.fluxdown.core.store.HostState
import com.fluxdown.core.store.Transfer
import com.fluxdown.core.store.transfers

/**
 * 本机引擎的聚合活动量（与当前选中的主机无关）：驱动前台服务的存续与进度通知。
 * 来源见 `AppContainer.localState`。
 */
data class LocalActivity(
    val active: Int,
    val pending: Int,
    val retryPending: Int,
    val downBps: Long,
    val upBps: Long,
    /** 活跃任务（进度通知的进度条与明细行）。 */
    val transfers: List<Transfer> = emptyList(),
) {
    /** 活跃 + 排队 + 待重试都算“还在工作”：进程必须保活。 */
    val busy: Boolean get() = active + pending + retryPending > 0

    /** 通知里的“等待中”数量（排队 + 待重试）。 */
    val waiting: Int get() = pending + retryPending

    companion object {
        val Idle = LocalActivity(active = 0, pending = 0, retryPending = 0, downBps = 0, upBps = 0)

        /** 未连上（启动中 / 失联 / 失败）一律视为空闲，避免前台服务僵死。 */
        fun from(state: HostState): LocalActivity {
            if (state.connection != Connection.Live) return Idle
            val stats = state.stats
            return LocalActivity(
                active = stats.activeTasks,
                pending = stats.pendingTasks,
                retryPending = stats.retryPendingTasks,
                downBps = stats.totalDownloadBps,
                upBps = stats.totalUploadBps,
                transfers = state.transfers(),
            )
        }
    }
}

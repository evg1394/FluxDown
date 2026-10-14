package com.fluxdown.core.store

import com.fluxdown.core.format.Format
import com.fluxdown.core.model.TaskStatus

/** 一个正在下载的任务（进度通知的一行）。 */
data class Transfer(
    val taskId: String,
    val fileName: String,
    val downloadedBytes: Long,
    /** 0 = 未知大小。 */
    val totalBytes: Long,
    val speedBps: Long,
) {
    /** 0..1；大小未知为 null（进度条显示不确定态）。 */
    val fraction: Float? get() = fractionOf(downloadedBytes, totalBytes)

    val etaSeconds: Long? get() = Format.etaSeconds(downloadedBytes, totalBytes, speedBps)
}

/**
 * 多个下载的聚合进度（进度通知的标题行与进度条）。
 *
 * - 进度只统计**大小已知**的任务：混入大小未知的任务（HLS 直播、尚未拿到长度的链接）不把进度拉成不确定态，
 *   全部未知时 [fraction] 才为 null；
 * - 剩余时间要求每个任务大小都已知（否则无法给出“全部完成”的时间），速度 = 全部任务之和。
 */
data class TransferSummary(
    val count: Int,
    /** 大小已知任务的已下载 / 总大小之和。 */
    val knownDownloadedBytes: Long,
    val knownTotalBytes: Long,
    val speedBps: Long,
    val etaSeconds: Long?,
) {
    val fraction: Float? get() = fractionOf(knownDownloadedBytes, knownTotalBytes)

    companion object {
        val Empty = TransferSummary(count = 0, knownDownloadedBytes = 0, knownTotalBytes = 0, speedBps = 0, etaSeconds = null)

        fun of(transfers: List<Transfer>): TransferSummary {
            var down = 0L
            var total = 0L
            var speed = 0L
            var allKnown = true
            for (t in transfers) {
                speed += t.speedBps
                if (t.totalBytes > 0) {
                    down += t.downloadedBytes.coerceIn(0, t.totalBytes)
                    total += t.totalBytes
                } else {
                    allKnown = false
                }
            }
            val eta = if (allKnown) Format.etaSeconds(down, total, speed) else null
            return TransferSummary(transfers.size, down, total, speed, eta)
        }
    }
}

/** 主机上的活跃任务（下载中 / 准备中，按任务列表顺序），速度取自 TaskProgress 实时量；未连上时为空。 */
fun HostState.transfers(): List<Transfer> {
    if (connection != Connection.Live) return emptyList()
    return tasks.mapNotNull { t ->
        if (!t.status.isActive) return@mapNotNull null
        Transfer(t.taskId, t.fileName, t.downloadedBytes, t.totalBytes, speeds[t.taskId]?.down ?: 0L)
    }
}

private fun fractionOf(downloaded: Long, total: Long): Float? =
    if (total > 0) (downloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null

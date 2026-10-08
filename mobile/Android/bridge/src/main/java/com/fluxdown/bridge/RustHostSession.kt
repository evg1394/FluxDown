package com.fluxdown.bridge

import com.fluxdown.core.host.CreateTaskRequest
import com.fluxdown.core.host.HostSignal
import com.fluxdown.core.model.SelectionOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.fluxdown.core.host.HostSession as HostPort

/**
 * `:core` 的 [HostPort] 端口在 UniFFI 绑定上的实现：一个生成的 `HostSession`（Rust 侧已做握手、游标、重同步、
 * 重连与离线宽限）对应一台主机。信号流是“拉”模型，[signals] 只在被收集时逐个拉取；收集被取消即停止拉取，
 * 会话本身由 [close] 结束。
 */
internal class RustHostSession(private val session: HostSession) : HostPort {
    override val signals: Flow<HostSignal> = flow {
        while (true) {
            val dto = guarded { session.nextSignal() } ?: break
            emit(dto.toCore())
        }
    }

    override fun close() {
        session.disconnect()
        session.destroy()
    }

    override suspend fun createTask(request: CreateTaskRequest): String =
        guarded { session.createTask(request.toDto()) }

    override suspend fun pause(taskId: String) = guarded { session.pause(taskId) }
    override suspend fun resume(taskId: String) = guarded { session.resume(taskId) }
    override suspend fun delete(taskId: String, deleteFiles: Boolean) = guarded { session.delete(taskId, deleteFiles) }
    override suspend fun pauseMany(taskIds: List<String>) = guarded { session.pauseMany(taskIds) }
    override suspend fun resumeMany(taskIds: List<String>) = guarded { session.resumeMany(taskIds) }
    override suspend fun deleteMany(taskIds: List<String>, deleteFiles: Boolean) =
        guarded { session.deleteMany(taskIds, deleteFiles) }

    override suspend fun pauseAll() = guarded { session.pauseAll() }
    override suspend fun resumeAll() = guarded { session.resumeAll() }
    override suspend fun rename(taskId: String, fileName: String) = guarded { session.rename(taskId, fileName) }
    override suspend fun changeUrl(taskId: String, url: String) = guarded { session.changeUrl(taskId, url) }
    override suspend fun rescan() = guarded { session.rescan() }
    override suspend fun moveToQueue(taskId: String, queueId: String) =
        guarded { session.moveToQueue(taskId, queueId) }

    override suspend fun boost(taskId: String) = guarded { session.boost(taskId) }
    override suspend fun resolveSelection(requestId: String, outcome: SelectionOutcome) =
        guarded { session.resolveSelection(requestId, outcome.toDto()) }

    override suspend fun patchConfig(expectedRevision: Long, values: Map<String, String>) =
        guarded { session.patchConfig(expectedRevision.toULong(), values) }

    override suspend fun refreshRssSource(sourceId: String) = guarded { session.refreshRssSource(sourceId) }
    override suspend fun setRssSourceEnabled(sourceId: String, enabled: Boolean) =
        guarded { session.setRssSourceEnabled(sourceId, enabled) }

    override suspend fun call(method: String, paramsJson: String?): String =
        guarded { session.call(method, paramsJson) }
}

private fun CreateTaskRequest.toDto() = CreateTaskRequestDto(
    url = url,
    fileName = fileName,
    saveDir = saveDir,
    segments = segments,
    queueId = queueId,
    startPaused = startPaused,
    cookies = cookies,
    referrer = referrer,
    userAgent = userAgent,
    proxyUrl = proxyUrl,
    checksum = checksum,
    ignoreTlsErrors = ignoreTlsErrors,
    headers = headers,
    httpUser = httpUser,
    httpPassword = httpPassword,
    saveSiteAuth = saveSiteAuth,
)

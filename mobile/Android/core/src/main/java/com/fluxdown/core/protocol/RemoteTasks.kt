package com.fluxdown.core.protocol

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

// 跨设备任务（`agent.remote.*`，经 FluxCloud）。镜像 `native/protocol/src/agent.rs::RemoteTaskDto` 与
// iOS `FluxDomain/Protocol/RemoteTasks.swift`；控制矩阵镜像 Web `batchPlan.ts::remoteCan` ↔ GPUI
// `dispatch.rs::remote_action_applies`。

/** `agent.remote.command` 的动作。 */
enum class RemoteCommandAction(val wire: String) {
    Pause("pause"),
    Resume("resume"),

    /** 云端直接置 `canceled`（不依赖目标在线），目标设备删除其本地任务、保留已下载文件。 */
    Cancel("cancel"),

    /** 云端直接删除记录；目标在线时按 `deleteFiles` 决定是否同时删文件。 */
    Delete("delete"),
}

/** `RemoteTaskStatus`：云端新增、本端不认识的状态落入 [Unknown]（不参与控制）。 */
sealed interface RemoteTaskStatus {
    val wire: String

    data object Pending : RemoteTaskStatus {
        override val wire = "pending"
    }

    data object Accepted : RemoteTaskStatus {
        override val wire = "accepted"
    }

    data object Downloading : RemoteTaskStatus {
        override val wire = "downloading"
    }

    data object Paused : RemoteTaskStatus {
        override val wire = "paused"
    }

    data object Completed : RemoteTaskStatus {
        override val wire = "completed"
    }

    data object Failed : RemoteTaskStatus {
        override val wire = "failed"
    }

    data object Canceled : RemoteTaskStatus {
        override val wire = "canceled"
    }

    data class Unknown(override val wire: String) : RemoteTaskStatus

    /** 任务已结束（不再有速度 / 进度变化）。 */
    val isTerminal: Boolean get() = this == Completed || this == Failed || this == Canceled

    /**
     * 云端命令是否适用于该状态：未知一律不可控制（含删除）；暂停 / 取消只对进行中，继续只对已暂停（取消也适用），
     * 删除对任何已知状态成立。
     */
    fun allows(action: RemoteCommandAction): Boolean = when (this) {
        is Unknown -> false
        Pending, Accepted, Downloading ->
            action == RemoteCommandAction.Pause || action == RemoteCommandAction.Cancel || action == RemoteCommandAction.Delete
        Paused ->
            action == RemoteCommandAction.Resume || action == RemoteCommandAction.Cancel || action == RemoteCommandAction.Delete
        Completed, Failed, Canceled -> action == RemoteCommandAction.Delete
    }

    companion object {
        fun fromWire(wire: String): RemoteTaskStatus = when (wire) {
            "pending" -> Pending
            "accepted" -> Accepted
            "downloading" -> Downloading
            "paused" -> Paused
            "completed" -> Completed
            "failed" -> Failed
            "canceled" -> Canceled
            else -> Unknown(wire)
        }

        /** 暂停 / 继续需要目标设备在线才能送达（取消 / 删除在云端直接生效）。 */
        fun needsTargetOnline(action: RemoteCommandAction): Boolean =
            action == RemoteCommandAction.Pause || action == RemoteCommandAction.Resume
    }
}

/** 跨设备任务的 UI 投影（`RemoteTaskDto`）。 */
data class RemoteTaskDto(
    val id: String,
    val fromDevice: String = "",
    /** 目标设备的 `deviceId`。 */
    val toDevice: String = "",
    val url: String = "",
    val saveDir: String? = null,
    /** 云端对未指定文件名的任务回 `null`，按空串处理。 */
    val fileName: String = "",
    val status: RemoteTaskStatus = RemoteTaskStatus.Pending,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0,
    val speed: Long = 0,
    /** 0…1。 */
    val progress: Double = 0.0,
    val error: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    /** `createdAt`（ISO-8601，带 / 不带小数秒、`Z` 或偏移）→ Unix 秒；解析失败 0。 */
    val createdAtSeconds: Long
        get() = try {
            OffsetDateTime.parse(createdAt).toEpochSecond()
        } catch (_: DateTimeParseException) {
            0L
        }

    companion object {
        /** `id` 缺失视为非法（null）；其余字段宽松同 serde `#[serde(default)]`。 */
        fun fromJson(v: JsonValue?): RemoteTaskDto? {
            val id = v.strOrNull("id") ?: return null
            return RemoteTaskDto(
                id = id,
                fromDevice = v.str("fromDevice"),
                toDevice = v.str("toDevice"),
                url = v.str("url"),
                saveDir = v.strOrNull("saveDir"),
                fileName = v.str("fileName"),
                status = v.strOrNull("status")?.let(RemoteTaskStatus::fromWire) ?: RemoteTaskStatus.Pending,
                totalBytes = v.longOrNull("totalBytes"),
                downloadedBytes = v.long("downloadedBytes"),
                speed = v.long("speed"),
                progress = v.double("progress"),
                error = v.strOrNull("error"),
                createdAt = v.str("createdAt"),
                updatedAt = v.str("updatedAt"),
            )
        }

        /** `agent.remoteTasks` 分区（`Vec<RemoteTaskDto>`）；缺失 / 非数组为空，非法元素跳过。 */
        fun listFromJson(v: JsonValue?): List<RemoteTaskDto> = v.arrayOrNull.orEmpty().mapNotNull(::fromJson)
    }
}

/** `agent.remote.command` 参数；[commandId] 省略时 agent 生成唯一值。 */
data class RemoteCommandParams(
    val taskId: String,
    val action: RemoteCommandAction,
    val commandId: String? = null,
    /** 仅 [RemoteCommandAction.Delete]：目标设备同时删除已下载文件。 */
    val deleteFiles: Boolean = false,
) {
    fun toJson(): JsonValue = jsonObjectOmitNulls(
        "taskId" to taskId,
        "action" to action.wire,
        "commandId" to commandId,
        "deleteFiles" to deleteFiles,
    )
}

/** 远程任务列表规则。 */
object RemoteTaskRules {
    /** 目标为本机的远程任务在本地已有真实任务，不再作为镜像行显示。 */
    fun visible(tasks: List<RemoteTaskDto>, currentDeviceId: String?): List<RemoteTaskDto> =
        if (currentDeviceId == null) tasks else tasks.filter { it.toDevice != currentDeviceId }

    /** 命令是否应当显示为可点：状态矩阵 + （暂停 / 继续）目标在线。`targetOnline == null` = 在线状态未知，不据此禁用。 */
    fun canIssue(action: RemoteCommandAction, task: RemoteTaskDto, targetOnline: Boolean?): Boolean {
        if (!task.status.allows(action)) return false
        return !(RemoteTaskStatus.needsTargetOnline(action) && targetOnline == false)
    }

    /** 行尾单一主按钮：进行中 → 暂停，已暂停 → 继续，其余无。 */
    fun primaryAction(task: RemoteTaskDto): RemoteCommandAction? = when {
        task.status.allows(RemoteCommandAction.Pause) -> RemoteCommandAction.Pause
        task.status.allows(RemoteCommandAction.Resume) -> RemoteCommandAction.Resume
        else -> null
    }
}

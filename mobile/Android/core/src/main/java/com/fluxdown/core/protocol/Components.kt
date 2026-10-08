package com.fluxdown.core.protocol

// 托管组件（ffmpeg / yt-dlp，`daemon.component.*`）DTO：镜像 `native/protocol/src/daemon.rs` 的组件段
// （同 iOS `Components.swift` / `web/src/lib/rpc/protocol/plugin.ts` 末段）。解码宽松，编码字段名 = wire camelCase。

/** 组件标识（wire：`ffmpeg` / `ytdlp`）。宽松：未知组件落入 [Unknown]，不让整个分区解码失败。 */
sealed interface ComponentKind {
    val wire: String

    data object Ffmpeg : ComponentKind {
        override val wire: String = "ffmpeg"
    }

    data object Ytdlp : ComponentKind {
        override val wire: String = "ytdlp"
    }

    data class Unknown(override val wire: String) : ComponentKind

    /** 手动路径的 daemon 配置键（`component.ffmpeg.path` / `component.ytdlp.path`）；未知组件无。 */
    val manualPathConfigKey: String?
        get() = when (this) {
            Ffmpeg -> "component.ffmpeg.path"
            Ytdlp -> "component.ytdlp.path"
            is Unknown -> null
        }

    /** `daemon.component.{get,uninstall,listVersions}` 的参数。 */
    fun params(): JsonValue = jsonObject("component" to wire)

    /** `daemon.component.install`：[version] 为 null = 最新稳定版（不发送该字段）。 */
    fun installParams(version: String?): JsonValue = jsonObjectOmitNulls("component" to wire, "version" to version)

    companion object {
        val known: List<ComponentKind> = listOf(Ffmpeg, Ytdlp)

        fun fromWire(wire: String): ComponentKind = when (wire) {
            "ffmpeg" -> Ffmpeg
            "ytdlp" -> Ytdlp
            else -> Unknown(wire)
        }
    }
}

/** ffmpeg / yt-dlp 组件状态（两者字段相同）。 */
data class ComponentStatus(
    /** 生效路径来源：`manual` / `managed` / `system` / `none`。 */
    val source: String = "none",
    /** 生效的可执行文件路径（`none` 时为空）。 */
    val path: String = "",
    /** 探测到的版本串（失败 / 未找到为空）。 */
    val version: String = "",
    /** 托管安装记录的版本（空 = 未托管安装）。 */
    val managedVersion: String = "",
    /** 系统 PATH 中探测到的路径（空 = 无）。 */
    val systemPath: String = "",
    /** 当前平台是否提供托管安装。 */
    val managedSupported: Boolean = true,
) {
    val hasManagedInstall: Boolean get() = managedVersion.isNotEmpty()

    companion object {
        fun fromJson(v: JsonValue?): ComponentStatus = ComponentStatus(
            source = v.str("source", "none"),
            path = v.str("path"),
            version = v.str("version"),
            managedVersion = v.str("managedVersion"),
            systemPath = v.str("systemPath"),
            managedSupported = v.bool("managedSupported", true),
        )
    }
}

/** 受管组件类型化状态：serde 相邻标记 `{"component": "...", "status": {...}}`。 */
data class ComponentStatusDto(val component: ComponentKind, val status: ComponentStatus) {
    companion object {
        fun fromJson(v: JsonValue?): ComponentStatusDto? {
            val wire = v.strOrNull("component") ?: return null
            return ComponentStatusDto(ComponentKind.fromWire(wire), ComponentStatus.fromJson(v["status"]))
        }

        /** `daemon.components` 分区（`Vec<ComponentStatusDto>`）。 */
        fun listFromJson(v: JsonValue?): List<ComponentStatusDto> = v.arrayOrNull.orEmpty().mapNotNull { fromJson(it) }
    }
}

/** 组件可安装版本（`ComponentVersions`）。 */
data class ComponentVersions(
    /** 降序排列的稳定版本号。 */
    val versions: List<String> = emptyList(),
    /** 最新稳定版（空 = 解析失败）。 */
    val latestStable: String = "",
) {
    /** 默认选中版本：优先最新稳定版，其次列表首项；保留仍在列表里的现有选择。 */
    fun defaultSelection(current: String?): String? {
        if (current != null && current in versions) return current
        return if (latestStable.isEmpty()) versions.firstOrNull() else latestStable
    }

    companion object {
        fun fromJson(v: JsonValue?): ComponentVersions =
            ComponentVersions(versions = v.strings("versions"), latestStable = v.str("latestStable"))
    }
}

/** `WsServerMsg::ComponentProgress`（[HostNotice.componentProgress] 的载荷；`totalBytes == 0` = 未知）。 */
data class ComponentProgressNotice(val component: ComponentKind, val downloadedBytes: Long, val totalBytes: Long) {
    /** 0…1；总量未知为 null。 */
    val fraction: Double?
        get() = if (totalBytes > 0) (downloadedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0) else null

    companion object {
        fun fromJson(v: JsonValue?): ComponentProgressNotice? {
            val wire = v.strOrNull("component") ?: return null
            return ComponentProgressNotice(ComponentKind.fromWire(wire), v.long("downloadedBytes"), v.long("totalBytes"))
        }
    }
}

/** `WsServerMsg::ComponentResult`（[HostNotice.componentResult] 的载荷）。 */
data class ComponentResultNotice(val component: ComponentKind, val ok: Boolean, val message: String = "") {
    companion object {
        fun fromJson(v: JsonValue?): ComponentResultNotice? {
            val wire = v.strOrNull("component") ?: return null
            return ComponentResultNotice(ComponentKind.fromWire(wire), v.bool("ok"), v.str("message"))
        }
    }
}

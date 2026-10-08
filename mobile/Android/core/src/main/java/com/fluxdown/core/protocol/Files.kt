package com.fluxdown.core.protocol

/** `daemon.config.connPolicy` / `clearConnPolicy` 结果：引擎学习到的按域连接上限条数。 */
data class ConnPolicySummary(val domainCount: Long = 0) {
    companion object {
        fun fromJson(v: JsonValue?): ConnPolicySummary = ConnPolicySummary(v.long("domainCount", 0).coerceAtLeast(0))
    }
}

/** `daemon.fs.list` 参数：`path` 省略 / 空 = 默认保存目录（wire 上省略该键）。 */
data class FsListParams(val path: String? = null) {
    fun toJson(): JsonValue = jsonObjectOmitNulls("path" to path?.takeIf { it.isNotEmpty() })
}

/** 目录项（`FsListResponse.dirs` 元素）。 */
data class FsEntry(val name: String, val path: String) {
    companion object {
        /** `path` 缺失的项无法导航，返回 null。 */
        fun fromJson(v: JsonValue?): FsEntry? {
            val path = v.strOrNull("path") ?: return null
            return FsEntry(v.str("name"), path)
        }
    }
}

/**
 * `daemon.fs.list` 结果：服务端目录列举（仅子目录）。
 * [denied] = 服务进程对该目录无读取权限：[dirs] 必为空，语义是「看不到」而非「没有」。
 */
data class FsListResponse(
    /** 实际列举的目录（绝对路径）。 */
    val path: String,
    /** 上级目录（根目录为 null）。 */
    val parent: String? = null,
    val dirs: List<FsEntry> = emptyList(),
    val denied: Boolean = false,
) {
    companion object {
        /** `path` 缺失视为非法响应（null）；其余字段缺省宽松。 */
        fun fromJson(v: JsonValue?): FsListResponse? {
            val path = v.strOrNull("path") ?: return null
            return FsListResponse(
                path = path,
                parent = v.strOrNull("parent"),
                dirs = v.list("dirs").mapNotNull { FsEntry.fromJson(it) },
                denied = v.bool("denied", false),
            )
        }
    }
}

/** 远端主机保存目录的纯逻辑。 */
object SettingsSaveDirectory {
    /** 留空（使用主机默认）或绝对路径（POSIX `/…`、Windows `C:\…` / `C:/…`、UNC `\\…`）。 */
    fun isValid(path: String): Boolean {
        val p = path.trim()
        if (p.isEmpty() || p.startsWith("/") || p.startsWith("\\\\")) return true
        val drive = p.length >= 3 && (p[0] in 'A'..'Z' || p[0] in 'a'..'z')
        return drive && p[1] == ':' && (p[2] == '\\' || p[2] == '/')
    }

    /** 读数用末段目录名（`/a/b/` → `b`；`C:\x\y` → `y`）。 */
    fun lastComponent(path: String): String {
        val trimmed = path.trimEnd('/', '\\')
        val cut = trimmed.lastIndexOfAny(charArrayOf('/', '\\'))
        return if (cut < 0) trimmed else trimmed.substring(cut + 1)
    }
}

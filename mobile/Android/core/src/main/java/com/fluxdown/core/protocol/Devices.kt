package com.fluxdown.core.protocol

import com.fluxdown.core.model.CloudDevice
import com.fluxdown.core.model.LinkDevice

// 远程下发（新建下载「下载到」）。镜像 iOS `FluxDomain/Protocol/Devices.swift`、Web `pages/downloads/dialogs/target.ts`
// 与 GPUI `crates/downloads/src/model/{devices,dispatch}.rs`。

/** 设备本地文件路径的书写风格，决定远程下发时保存目录的合法形态；未知值原样保留。 */
sealed interface PathStyle {
    val wire: String

    /** `C:\dir` / `\\server\share`。 */
    data object Windows : PathStyle {
        override val wire = "windows"
    }

    /** `/dir`。 */
    data object Posix : PathStyle {
        override val wire = "posix"
    }

    /** 对端发送了本端不认识的风格。 */
    data class Unknown(override val wire: String) : PathStyle

    /** [path] 是否为该风格下的绝对路径（[Unknown] 恒 false）。 */
    fun isAbsolute(path: String): Boolean {
        val p = path.trim()
        return when (this) {
            Windows -> {
                val drive = p.length >= 3 && p[0].isAsciiLetter() && p[1] == ':' && (p[2] == '\\' || p[2] == '/')
                drive || p.startsWith("\\\\")
            }
            Posix -> p.startsWith("/")
            is Unknown -> false
        }
    }

    companion object {
        fun fromWire(wire: String): PathStyle = when (wire) {
            "windows" -> Windows
            "posix" -> Posix
            else -> Unknown(wire)
        }

        /** 按设备平台名推断（`windows` / `macos` / `linux` / `android` / `ios` …）；未知平台 null。 */
        fun fromPlatform(platform: String): PathStyle? = when (platform.trim().lowercase()) {
            "windows", "win32" -> Windows
            "macos", "darwin", "linux", "android", "ios", "freebsd", "openbsd", "netbsd" -> Posix
            else -> null
        }

        /** 设备自报的路径风格（wire 名）；缺失 / 不认识时按平台推断。 */
        fun effective(reported: String?, platform: String?): PathStyle? =
            when (val style = reported?.let(::fromWire)) {
                Windows, Posix -> style
                else -> platform?.let(::fromPlatform)
            }

        /** 校验失败提示里的路径示例。 */
        fun example(style: PathStyle?): String = if (style == Windows) "D:\\Downloads" else "/home/user/Downloads"
    }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/** 远程下发目标：云账号其他设备（经 FluxCloud）或局域网已配对设备（直连）。 */
data class DispatchTarget(
    val kind: Kind,
    /** 云设备 `deviceId`（[RemoteDispatchParams.toDevice]）/ 已配对设备指纹（[LinkDispatchParams.fingerprint]）。 */
    val deviceId: String,
    val name: String,
    /** 在线状态；null = 未知（云端 presence 不可信 / 本地服务未就绪）。 */
    val online: Boolean?,
    /** 目标自报的默认下载目录（已去首尾空白；空 = null）。 */
    val defaultSaveDir: String?,
    /** 目标的有效路径风格（自报 → 平台推断）；未知为 null。 */
    val pathStyle: PathStyle?,
) {
    enum class Kind { Cloud, Link }

    /** 选择键：`cloud:<deviceId>` / `link:<fingerprint>`（同 GPUI `DispatchTarget::to_pref`）。 */
    val id: String get() = (if (kind == Kind.Cloud) "cloud:" else "link:") + deviceId

    val isCloud: Boolean get() = kind == Kind.Cloud

    /** 已知离线：云设备由云端排队、上线后执行；局域网设备直连送不到。 */
    val isOffline: Boolean get() = online == false
}

/** 下发目标的纯规则。 */
object DeviceRules {
    /** 远端保存目录输入的校验结果（`dispatch.rs::check_remote_save_dir`）。 */
    sealed interface SaveDirCheck {
        /** 输入为空：提交时不带 `saveDir`，目标设备用它自己的默认目录。 */
        data object UseDefault : SaveDirCheck

        /** 合法目录（已去首尾空白）。 */
        data class Explicit(val dir: String) : SaveDirCheck

        /** 不是目标路径风格下的绝对路径。 */
        data object Invalid : SaveDirCheck
    }

    /** 风格未知（Web 端 / 新平台）时无法判断，交给目标设备回退默认目录，只要求非空即放行。 */
    fun checkSaveDir(input: String, style: PathStyle?): SaveDirCheck {
        val dir = input.trim()
        return when {
            dir.isEmpty() -> SaveDirCheck.UseDefault
            style == null || style is PathStyle.Unknown || style.isAbsolute(dir) -> SaveDirCheck.Explicit(dir)
            else -> SaveDirCheck.Invalid
        }
    }

    /**
     * 「下载到」候选（`crates/downloads/src/model/devices.rs::other_devices`）：云设备去掉本机与空 id、
     * 已配对设备去掉空指纹，各自按 id 去重；名称为空用短码，多台同名（忽略大小写与首尾空白）时全部追加 ` · 短码`。
     *
     * - [cloudPresenceKnown]：云端在线状态可信（[CloudPresence.isKnown]），否则云设备在线未知；
     * - [localReady]：本地服务已就绪，否则局域网设备在线未知。
     */
    fun dispatchTargets(
        cloud: List<CloudDevice>,
        link: List<LinkDevice>,
        cloudPresenceKnown: Boolean,
        localReady: Boolean,
    ): List<DispatchTarget> {
        val seenCloud = HashSet<String>()
        val seenLink = HashSet<String>()
        val targets = ArrayList<DispatchTarget>(cloud.size + link.size)
        for (d in cloud) {
            if (d.isCurrent || d.deviceId.isEmpty() || !seenCloud.add(d.deviceId)) continue
            targets += DispatchTarget(
                kind = DispatchTarget.Kind.Cloud,
                deviceId = d.deviceId,
                name = d.name.trim(),
                online = if (cloudPresenceKnown && localReady) d.isOnline else null,
                defaultSaveDir = d.defaultSaveDir?.trim()?.takeIf { it.isNotEmpty() },
                pathStyle = PathStyle.effective(d.pathStyle, d.platform),
            )
        }
        for (d in link) {
            if (d.fingerprint.isEmpty() || !seenLink.add(d.fingerprint)) continue
            targets += DispatchTarget(
                kind = DispatchTarget.Kind.Link,
                deviceId = d.fingerprint,
                name = d.name.trim(),
                online = if (localReady) d.online else null,
                defaultSaveDir = d.defaultSaveDir?.trim()?.takeIf { it.isNotEmpty() },
                pathStyle = PathStyle.effective(d.pathStyle, d.platform),
            )
        }
        val counts = targets.groupingBy { it.name.lowercase() }.eachCount()
        return targets.map { t ->
            when {
                t.name.isEmpty() -> t.copy(name = shortCode(t.deviceId))
                (counts[t.name.lowercase()] ?: 0) > 1 -> t.copy(name = "${t.name} · ${shortCode(t.deviceId)}")
                else -> t
            }
        }
    }

    /** 设备 id 的短码（末 4 位 ASCII 字母数字），用于同名设备消歧。 */
    fun shortCode(id: String): String = id.filter { it.isAsciiLetter() || it in '0'..'9' }.takeLast(4)
}

/** `agent.remote.dispatch` 参数：把下载经 FluxCloud 下发到本账号另一台受信任设备。 */
data class RemoteDispatchParams(
    /** 目标设备的 `deviceId`。 */
    val toDevice: String,
    val url: String,
    /** null = 由目标设备按 URL 推断。 */
    val fileName: String? = null,
    /** 目标设备上的保存目录；null = 目标设备默认下载目录。必须符合目标设备路径风格。 */
    val saveDir: String? = null,
) {
    fun toJson(): JsonValue =
        jsonObjectOmitNulls("toDevice" to toDevice, "url" to url, "fileName" to fileName, "saveDir" to saveDir)
}

/** `agent.link.dispatch` 参数：把下载直接下发到已配对的局域网设备。 */
data class LinkDispatchParams(
    val fingerprint: String,
    val url: String,
    val fileName: String? = null,
    /** 目标设备上的保存目录；null = 目标设备默认下载目录。 */
    val saveDir: String? = null,
) {
    fun toJson(): JsonValue =
        jsonObjectOmitNulls("fingerprint" to fingerprint, "url" to url, "fileName" to fileName, "saveDir" to saveDir)
}

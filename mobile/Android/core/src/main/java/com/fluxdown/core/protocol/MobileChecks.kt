package com.fluxdown.core.protocol

// 「此设备」诊断：只检查这台手机本身——通知、后台、存储、链接处理、网络、引擎连接。
// 级别判定是纯函数（本文件）；探测（写入 / 剩余空间 / ConnectivityManager / PackageManager）在 :app 的 MobileCheckRunner。
// 文案由 :app 按 [MobileCode] 解析为字符串资源（core 不依赖 Android 资源）。

enum class MobileCheckId(val wire: String) {
    Notifications("notifications"),
    Background("background"),
    Storage("storage"),
    Links("links"),
    Network("network"),
    Engine("engine"),
}

/** 可点击的修复：打开对应的系统设置页（由 :app 映射为 Intent）。 */
enum class MobileFix {
    /** 本应用的通知设置。 */
    NotificationSettings,

    /** 电池优化白名单页（可把本应用设为不受限）。 */
    BatteryOptimization,

    /** 系统省电模式设置。 */
    BatterySaver,

    /** 应用详情页（后台限制 / 默认打开方式）；[MobileCheck.fixPackage] 非空时指向那个应用。 */
    AppDetails,

    /** 数据节省模式下本应用的不受限数据开关。 */
    DataSaver,
}

/** 文案码：:app 映射到 `R.string`；[args] 为占位符取值（已格式化的文本）。 */
data class MobileText(val code: String, val args: Map<String, String> = emptyMap())

object MobileCode {
    // 通知
    const val NOTIF_ALLOWED = "notifAllowed"
    const val NOTIF_DENIED = "notifDenied"
    const val NOTIF_DENIED_HINT = "notifDeniedHint"
    const val NOTIF_CHANNEL_OFF = "notifChannelOff"
    const val NOTIF_CHANNEL_OFF_HINT = "notifChannelOffHint"

    // 后台
    const val BG_RESTRICTED = "bgRestricted"
    const val BG_RESTRICTED_HINT = "bgRestrictedHint"
    const val BG_UNRESTRICTED = "bgUnrestricted"
    const val BG_OPTIMIZED = "bgOptimized"
    const val BG_OPTIMIZED_HINT = "bgOptimizedHint"
    const val BG_POWER_SAVE = "bgPowerSave"
    const val BG_POWER_SAVE_HINT = "bgPowerSaveHint"

    // 存储
    const val STORAGE_NOT_WRITABLE = "storageNotWritable"
    const val STORAGE_NOT_WRITABLE_HINT = "storageNotWritableHint"
    const val STORAGE_WRITABLE_UNKNOWN = "storageWritableUnknown"
    const val STORAGE_WRITABLE_FREE = "storageWritableFree"
    const val STORAGE_LOW_HINT = "storageLowHint"

    // 链接（args: subject = magnet / ed2k / torrent）
    const val LINK_THIS = "linkThis"
    const val LINK_ASK = "linkAsk"
    const val LINK_OTHER = "linkOther"
    const val LINK_MISSING = "linkMissing"
    const val LINKS_HINT = "linksHint"

    // 网络
    const val NET_UNKNOWN = "netUnknown"
    const val NET_NONE = "netNone"
    const val NET_NONE_HINT = "netNoneHint"
    const val NET_NO_INTERNET = "netNoInternet"
    const val NET_NO_INTERNET_HINT = "netNoInternetHint"
    const val NET_WIFI = "netWifi"
    const val NET_CELLULAR = "netCellular"
    const val NET_WIRED = "netWired"
    const val NET_OTHER = "netOther"
    const val NET_METERED = "netMetered"
    const val NET_METERED_HINT = "netMeteredHint"
    const val NET_DATA_SAVER = "netDataSaver"
    const val NET_DATA_SAVER_HINT = "netDataSaverHint"

    // 引擎
    const val ENGINE_CONNECTED = "engineConnected"
    const val ENGINE_VERSION = "engineVersion"
    const val ENGINE_CONNECTING = "engineConnecting"
    const val ENGINE_STALE = "engineStale"
    const val ENGINE_STALE_HINT = "engineStaleHint"
    const val ENGINE_FAILED = "engineFailed"
    const val ENGINE_FAILED_HINT = "engineFailedHint"

    /** 原样文本（args: text）。 */
    const val RAW = "raw"
}

/** 一条设备检查结果；[detail] 各段用 [detailSeparator] 连接，[hint] 各段按行连接。 */
data class MobileCheck(
    val id: MobileCheckId,
    val level: DiagnosticLevel,
    val detail: List<MobileText>,
    val hint: List<MobileText> = emptyList(),
    val fix: MobileFix? = null,
    /** [MobileFix.AppDetails] 指向的其它应用包名（链接默认处理者）；null = 本应用。 */
    val fixPackage: String? = null,
    val detailSeparator: String = "\n",
)

/** 通知状态。 */
enum class NotificationState {
    /** 应用级通知开启且没有被关掉的渠道。 */
    Enabled,

    /** 应用级通知被关闭（含 Android 13+ 未授予 POST_NOTIFICATIONS）。 */
    Disabled,

    /** 应用级开启，但至少一个渠道被用户关闭（重要性 NONE）。 */
    ChannelsBlocked,
}

/** 某个链接类型当前由谁处理。 */
enum class LinkHandler {
    /** 本应用是默认处理者（或唯一处理者）。 */
    This,

    /** 本应用与其它应用并列，系统每次询问。 */
    Ask,

    /** 其它应用被设为默认处理者。 */
    Other,

    /** 本应用没有声明处理该链接。 */
    Missing,
}

/** 一条链接类型的检测结果。[otherPackage] = 默认处理者的包名（仅 [LinkHandler.Other]）。 */
data class LinkProbe(val handler: LinkHandler, val otherPackage: String? = null)

/** 一次性网络快照（`ConnectivityManager` 的纯数据投影）。 */
data class MobileNetworkSnapshot(
    val reachability: Reachability,
    val transport: Transport,
    /** 计费网络（蜂窝 / 按流量计费的 Wi-Fi 热点）。 */
    val isMetered: Boolean = false,
    /** 系统「流量节省程序」对本应用生效（后台数据受限）。 */
    val dataSaver: Boolean = false,
    val supportsIPv4: Boolean = false,
    val supportsIPv6: Boolean = false,
) {
    enum class Reachability {
        /** 系统验证过可访问互联网。 */
        Satisfied,

        /** 已连接但未验证（强制门户 / 无出口）。 */
        Unvalidated,

        /** 没有网络。 */
        Unsatisfied,
    }

    enum class Transport { Wifi, Cellular, Wired, Other, None }
}

enum class EngineState { Live, Connecting, Stale, Failed }

object MobileChecks {
    /** 剩余空间低于此值 = 警告 / 异常（字节）。 */
    const val LOW_STORAGE_WARNING: Long = 1L shl 30
    const val LOW_STORAGE_ERROR: Long = 100L shl 20

    /** warn + error 的条数。 */
    fun issueCount(checks: List<MobileCheck>): Int = checks.count { it.level.isIssue }

    // ───────────────────────────── 通知 ─────────────────────────────

    fun notifications(state: NotificationState): MobileCheck = when (state) {
        NotificationState.Enabled ->
            MobileCheck(MobileCheckId.Notifications, DiagnosticLevel.Ok, listOf(MobileText(MobileCode.NOTIF_ALLOWED)))
        NotificationState.Disabled -> MobileCheck(
            MobileCheckId.Notifications, DiagnosticLevel.Warn,
            detail = listOf(MobileText(MobileCode.NOTIF_DENIED)),
            hint = listOf(MobileText(MobileCode.NOTIF_DENIED_HINT)),
            fix = MobileFix.NotificationSettings,
        )
        NotificationState.ChannelsBlocked -> MobileCheck(
            MobileCheckId.Notifications, DiagnosticLevel.Warn,
            detail = listOf(MobileText(MobileCode.NOTIF_CHANNEL_OFF)),
            hint = listOf(MobileText(MobileCode.NOTIF_CHANNEL_OFF_HINT)),
            fix = MobileFix.NotificationSettings,
        )
    }

    // ───────────────────────────── 后台 ─────────────────────────────

    /**
     * @param backgroundRestricted 用户给本应用设了「受限」后台（`ActivityManager.isBackgroundRestricted`）。
     * @param batteryUnrestricted 已在电池优化白名单（`PowerManager.isIgnoringBatteryOptimizations`）。
     * @param powerSave 系统省电模式开启。
     */
    fun background(backgroundRestricted: Boolean, batteryUnrestricted: Boolean, powerSave: Boolean): MobileCheck {
        val levels = ArrayList<DiagnosticLevel>(3)
        val lines = ArrayList<MobileText>(3)
        val hints = ArrayList<MobileText>(3)
        if (backgroundRestricted) {
            lines += MobileText(MobileCode.BG_RESTRICTED)
            levels += DiagnosticLevel.Warn
            hints += MobileText(MobileCode.BG_RESTRICTED_HINT)
        }
        if (batteryUnrestricted) {
            lines += MobileText(MobileCode.BG_UNRESTRICTED)
            levels += DiagnosticLevel.Ok
        } else {
            lines += MobileText(MobileCode.BG_OPTIMIZED)
            levels += DiagnosticLevel.Info
            hints += MobileText(MobileCode.BG_OPTIMIZED_HINT)
        }
        if (powerSave) {
            lines += MobileText(MobileCode.BG_POWER_SAVE)
            levels += DiagnosticLevel.Warn
            hints += MobileText(MobileCode.BG_POWER_SAVE_HINT)
        }
        val fix = when {
            backgroundRestricted -> MobileFix.AppDetails
            !batteryUnrestricted -> MobileFix.BatteryOptimization
            powerSave -> MobileFix.BatterySaver
            else -> null
        }
        return MobileCheck(MobileCheckId.Background, DiagnosticsLogic.worst(levels), lines, hints, fix)
    }

    // ───────────────────────────── 存储 ─────────────────────────────

    /** [formatBytes]：字节数 → 展示文本（由 :app 提供，跟随用户习惯）。 */
    fun storage(writable: Boolean, freeBytes: Long?, formatBytes: (Long) -> String): MobileCheck {
        if (!writable) {
            return MobileCheck(
                MobileCheckId.Storage, DiagnosticLevel.Error,
                detail = listOf(MobileText(MobileCode.STORAGE_NOT_WRITABLE)),
                hint = listOf(MobileText(MobileCode.STORAGE_NOT_WRITABLE_HINT)),
            )
        }
        if (freeBytes == null) {
            return MobileCheck(MobileCheckId.Storage, DiagnosticLevel.Info, listOf(MobileText(MobileCode.STORAGE_WRITABLE_UNKNOWN)))
        }
        val detail = listOf(MobileText(MobileCode.STORAGE_WRITABLE_FREE, mapOf("free" to formatBytes(freeBytes))))
        val low = listOf(MobileText(MobileCode.STORAGE_LOW_HINT))
        return when {
            freeBytes < LOW_STORAGE_ERROR -> MobileCheck(MobileCheckId.Storage, DiagnosticLevel.Error, detail, low)
            freeBytes < LOW_STORAGE_WARNING -> MobileCheck(MobileCheckId.Storage, DiagnosticLevel.Warn, detail, low)
            else -> MobileCheck(MobileCheckId.Storage, DiagnosticLevel.Ok, detail)
        }
    }

    // ───────────────────────────── 链接 ─────────────────────────────

    /**
     * magnet / ed2k 必须由本应用处理（否则警告）；`.torrent` 只是锦上添花（缺失 / 被他人处理 = info）。
     * 一个应用被设为默认处理者而本应用没抢到 = warn，并指出那个应用以便清除其默认设置。
     */
    fun links(magnet: LinkProbe, ed2k: LinkProbe, torrent: LinkProbe): MobileCheck {
        val levels = ArrayList<DiagnosticLevel>(3)
        val lines = ArrayList<MobileText>(3)
        var otherPackage: String? = null
        for ((subject, probe, required) in listOf(
            Triple("magnet", magnet, true),
            Triple("ed2k", ed2k, true),
            Triple("torrent", torrent, false),
        )) {
            val code = when (probe.handler) {
                LinkHandler.This -> MobileCode.LINK_THIS
                LinkHandler.Ask -> MobileCode.LINK_ASK
                LinkHandler.Other -> MobileCode.LINK_OTHER
                LinkHandler.Missing -> MobileCode.LINK_MISSING
            }
            lines += MobileText(code, mapOf("subject" to subject))
            levels += when (probe.handler) {
                LinkHandler.This -> DiagnosticLevel.Ok
                LinkHandler.Ask -> DiagnosticLevel.Info
                LinkHandler.Other, LinkHandler.Missing -> if (required) DiagnosticLevel.Warn else DiagnosticLevel.Info
            }
            if (required && probe.handler == LinkHandler.Other && otherPackage == null) otherPackage = probe.otherPackage
        }
        val level = DiagnosticsLogic.worst(levels)
        val warn = level == DiagnosticLevel.Warn
        return MobileCheck(
            MobileCheckId.Links, level, lines,
            hint = if (warn) listOf(MobileText(MobileCode.LINKS_HINT)) else emptyList(),
            fix = if (warn) MobileFix.AppDetails else null,
            fixPackage = if (warn) otherPackage else null,
        )
    }

    // ───────────────────────────── 网络 ─────────────────────────────

    fun network(snapshot: MobileNetworkSnapshot?): MobileCheck {
        if (snapshot == null) {
            return MobileCheck(MobileCheckId.Network, DiagnosticLevel.Info, listOf(MobileText(MobileCode.NET_UNKNOWN)))
        }
        when (snapshot.reachability) {
            MobileNetworkSnapshot.Reachability.Unsatisfied -> return MobileCheck(
                MobileCheckId.Network, DiagnosticLevel.Error,
                detail = listOf(MobileText(MobileCode.NET_NONE)),
                hint = listOf(MobileText(MobileCode.NET_NONE_HINT)),
            )
            MobileNetworkSnapshot.Reachability.Unvalidated -> return MobileCheck(
                MobileCheckId.Network, DiagnosticLevel.Warn,
                detail = listOf(MobileText(MobileCode.NET_NO_INTERNET)),
                hint = listOf(MobileText(MobileCode.NET_NO_INTERNET_HINT)),
            )
            MobileNetworkSnapshot.Reachability.Satisfied -> Unit
        }
        val name = when (snapshot.transport) {
            MobileNetworkSnapshot.Transport.Wifi -> MobileCode.NET_WIFI
            MobileNetworkSnapshot.Transport.Cellular -> MobileCode.NET_CELLULAR
            MobileNetworkSnapshot.Transport.Wired -> MobileCode.NET_WIRED
            MobileNetworkSnapshot.Transport.Other -> MobileCode.NET_OTHER
            MobileNetworkSnapshot.Transport.None -> MobileCode.NET_UNKNOWN
        }
        val parts = ArrayList<MobileText>(4)
        parts += MobileText(name)
        val families = buildList {
            if (snapshot.supportsIPv4) add("IPv4")
            if (snapshot.supportsIPv6) add("IPv6")
        }
        if (families.isNotEmpty()) parts += MobileText(MobileCode.RAW, mapOf("text" to families.joinToString(" + ")))
        var level: DiagnosticLevel = DiagnosticLevel.Ok
        val hints = ArrayList<MobileText>(2)
        if (snapshot.isMetered) {
            parts += MobileText(MobileCode.NET_METERED)
            level = DiagnosticLevel.Info
            hints += MobileText(MobileCode.NET_METERED_HINT)
        }
        if (snapshot.dataSaver) {
            parts += MobileText(MobileCode.NET_DATA_SAVER)
            level = DiagnosticLevel.Warn
            hints += MobileText(MobileCode.NET_DATA_SAVER_HINT)
        }
        return MobileCheck(
            MobileCheckId.Network, level, parts, hints,
            fix = if (snapshot.dataSaver) MobileFix.DataSaver else null,
            detailSeparator = " · ",
        )
    }

    // ───────────────────────────── 引擎 ─────────────────────────────

    /** [failureText]：连接失败时的错误描述（`ErrorText`）；[version] / [protocol]：主机版本信息。 */
    fun engine(state: EngineState, version: String?, protocol: Int?, failureText: String?): MobileCheck = when (state) {
        EngineState.Live -> {
            val detail = ArrayList<MobileText>(2)
            detail += MobileText(MobileCode.ENGINE_CONNECTED)
            if (version != null && protocol != null) {
                detail += MobileText(MobileCode.ENGINE_VERSION, mapOf("version" to version, "protocol" to protocol.toString()))
            }
            MobileCheck(MobileCheckId.Engine, DiagnosticLevel.Ok, detail, detailSeparator = " · ")
        }
        EngineState.Connecting ->
            MobileCheck(MobileCheckId.Engine, DiagnosticLevel.Info, listOf(MobileText(MobileCode.ENGINE_CONNECTING)))
        EngineState.Stale -> MobileCheck(
            MobileCheckId.Engine, DiagnosticLevel.Warn,
            detail = listOf(MobileText(MobileCode.ENGINE_STALE)),
            hint = listOf(MobileText(MobileCode.ENGINE_STALE_HINT)),
        )
        EngineState.Failed -> MobileCheck(
            MobileCheckId.Engine, DiagnosticLevel.Error,
            detail = listOf(
                if (failureText.isNullOrEmpty()) MobileText(MobileCode.ENGINE_FAILED)
                else MobileText(MobileCode.RAW, mapOf("text" to failureText)),
            ),
            hint = listOf(MobileText(MobileCode.ENGINE_FAILED_HINT)),
        )
    }
}

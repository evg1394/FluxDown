package com.fluxdown.app.feature.settings.diagnostics

import android.content.Context
import androidx.annotation.StringRes
import com.fluxdown.app.R
import com.fluxdown.app.i18n.str
import com.fluxdown.core.protocol.DiagnosticCheckDto
import com.fluxdown.core.protocol.DiagnosticLevel
import com.fluxdown.core.protocol.DiagnosticsLogic
import com.fluxdown.core.protocol.MobileCheck
import com.fluxdown.core.protocol.MobileCheckId
import com.fluxdown.core.protocol.MobileCode
import com.fluxdown.core.protocol.MobileFix
import com.fluxdown.core.protocol.MobileText

/** 设备检查（已本地化）。 */
internal class ResolvedDeviceCheck(
    val id: MobileCheckId,
    val level: DiagnosticLevel,
    val title: String,
    val detail: String,
    val hint: String,
    val fix: MobileFix?,
    val fixPackage: String?,
)

@StringRes
internal fun MobileCheckId.titleRes(): Int = when (this) {
    MobileCheckId.Notifications -> R.string.mobileDiagCheckNotifications
    MobileCheckId.Background -> R.string.mobileDiagCheckBackground
    MobileCheckId.Storage -> R.string.mobileDiagCheckStorage
    MobileCheckId.Links -> R.string.mobileDiagCheckLinks
    MobileCheckId.Network -> R.string.mobileDiagCheckNetwork
    MobileCheckId.Engine -> R.string.mobileDiagCheckEngine
}

@StringRes
internal fun DiagnosticLevel.labelRes(): Int = when (this) {
    DiagnosticLevel.Ok -> R.string.doctorLevelOk
    DiagnosticLevel.Warn -> R.string.doctorLevelWarn
    DiagnosticLevel.Error -> R.string.doctorLevelError
    DiagnosticLevel.Info, is DiagnosticLevel.Unknown -> R.string.doctorLevelInfo
}

/** 文案码 → 本地化文本。未知码原样返回（不会静默吞掉）。 */
internal fun Context.resolve(text: MobileText): String {
    val a = text.args
    return when (text.code) {
        MobileCode.NOTIF_ALLOWED -> str(R.string.mobileDiagNotifAllowed)
        MobileCode.NOTIF_DENIED -> str(R.string.mobileDiagNotifDenied)
        MobileCode.NOTIF_DENIED_HINT -> str(R.string.mobileDiagNotifDeniedHint)
        MobileCode.NOTIF_CHANNEL_OFF -> str(R.string.mobileDiagAndNotifChannelOff)
        MobileCode.NOTIF_CHANNEL_OFF_HINT -> str(R.string.mobileDiagAndNotifChannelOffHint)

        MobileCode.BG_RESTRICTED -> str(R.string.mobileDiagAndBgRestricted)
        MobileCode.BG_RESTRICTED_HINT -> str(R.string.mobileDiagAndBgRestrictedHint)
        MobileCode.BG_UNRESTRICTED -> str(R.string.mobileDiagAndBgUnrestricted)
        MobileCode.BG_OPTIMIZED -> str(R.string.mobileDiagAndBgOptimized)
        MobileCode.BG_OPTIMIZED_HINT -> str(R.string.mobileDiagAndBgOptimizedHint)
        MobileCode.BG_POWER_SAVE -> str(R.string.mobileDiagAndBgPowerSave)
        MobileCode.BG_POWER_SAVE_HINT -> str(R.string.mobileDiagAndBgPowerSaveHint)

        MobileCode.STORAGE_NOT_WRITABLE -> str(R.string.mobileDiagStorageNotWritable)
        MobileCode.STORAGE_NOT_WRITABLE_HINT -> str(R.string.mobileDiagStorageNotWritableHint)
        MobileCode.STORAGE_WRITABLE_UNKNOWN -> str(R.string.mobileDiagStorageWritableUnknown)
        MobileCode.STORAGE_WRITABLE_FREE -> str(R.string.mobileDiagStorageWritableFree, "free" to a["free"].orEmpty())
        MobileCode.STORAGE_LOW_HINT -> str(R.string.mobileDiagAndStorageLowHint)

        MobileCode.LINK_THIS -> linkLine(a["subject"], R.string.mobileDiagLinkDeclared)
        MobileCode.LINK_ASK -> linkLine(a["subject"], R.string.mobileDiagAndLinkAsk)
        MobileCode.LINK_OTHER -> linkLine(a["subject"], R.string.mobileDiagAndLinkOther)
        MobileCode.LINK_MISSING -> linkLine(a["subject"], R.string.mobileDiagLinkMissing)
        MobileCode.LINKS_HINT -> str(R.string.mobileDiagAndLinksHint)

        MobileCode.NET_UNKNOWN -> str(R.string.mobileDiagNetUnknown)
        MobileCode.NET_NONE -> str(R.string.mobileDiagNetNone)
        MobileCode.NET_NONE_HINT -> str(R.string.mobileDiagNetNoneHint)
        MobileCode.NET_NO_INTERNET -> str(R.string.mobileDiagAndNetNoInternet)
        MobileCode.NET_NO_INTERNET_HINT -> str(R.string.mobileDiagAndNetNoInternetHint)
        MobileCode.NET_WIFI -> str(R.string.mobileDiagNetWifi)
        MobileCode.NET_CELLULAR -> str(R.string.mobileDiagNetCellular)
        MobileCode.NET_WIRED -> str(R.string.mobileDiagNetWired)
        MobileCode.NET_OTHER -> str(R.string.mobileDiagNetOther)
        MobileCode.NET_METERED -> str(R.string.mobileDiagNetMetered)
        MobileCode.NET_METERED_HINT -> str(R.string.mobileDiagNetMeteredHint)
        MobileCode.NET_DATA_SAVER -> str(R.string.mobileDiagAndNetDataSaver)
        MobileCode.NET_DATA_SAVER_HINT -> str(R.string.mobileDiagAndNetDataSaverHint)

        MobileCode.ENGINE_CONNECTED -> str(R.string.mobileDiagEngineConnected)
        MobileCode.ENGINE_VERSION ->
            str(R.string.mobileDiagEngineVersion, "version" to a["version"].orEmpty(), "protocol" to a["protocol"].orEmpty())
        MobileCode.ENGINE_CONNECTING -> str(R.string.mobileSettingsConnConnecting)
        MobileCode.ENGINE_STALE -> str(R.string.mobileSettingsConnStale)
        MobileCode.ENGINE_STALE_HINT -> str(R.string.mobileDiagEngineStaleHint)
        MobileCode.ENGINE_FAILED -> str(R.string.mobileSettingsConnFailed)
        MobileCode.ENGINE_FAILED_HINT -> str(R.string.mobileDiagEngineFailedHint)

        MobileCode.RAW -> a["text"].orEmpty()
        else -> text.code
    }
}

private fun Context.linkLine(subject: String?, @StringRes status: Int): String {
    val label = if (subject == "torrent") str(R.string.mobileDiagLinkTorrent) else "$subject:"
    return "$label  ${str(status)}"
}

internal fun Context.resolve(check: MobileCheck): ResolvedDeviceCheck = ResolvedDeviceCheck(
    id = check.id,
    level = check.level,
    title = str(check.id.titleRes()),
    detail = check.detail.joinToString(check.detailSeparator) { resolve(it) },
    hint = check.hint.joinToString("\n") { resolve(it) },
    fix = check.fix,
    fixPackage = check.fixPackage,
)

/** 按键名取字符串资源（`doctorCheck{Camel(id)}` 这类由主机下发的 id 拼出的动态键）；没有该键 → null。 */
@Suppress("DiscouragedApi")
internal fun Context.stringByKey(key: String): String? {
    val id = resources.getIdentifier(key, "string", packageName)
    return if (id == 0) null else getString(id)
}

/** 检查项标题（不含目标）：无文案的未知 id 回退为 id 本身。 */
internal fun Context.checkLabel(check: DiagnosticCheckDto): String =
    stringByKey(DiagnosticsLogic.checkKey(check.id)) ?: check.id

/** 提示文案；无该提示码的文案时 null。 */
internal fun Context.hintText(check: DiagnosticCheckDto): String? {
    if (check.hint.isEmpty()) return null
    return stringByKey(DiagnosticsLogic.hintKey(check.hint))
}

/** 修复按钮文案；没有该动作文案 / 桌面专属动作 → null（不提供按钮）。 */
internal fun Context.repairLabel(check: DiagnosticCheckDto): String? {
    val repair = DiagnosticsLogic.repair(check) { stringByKey(it) != null } ?: return null
    return stringByKey(DiagnosticsLogic.actionKey(repair.action))
}

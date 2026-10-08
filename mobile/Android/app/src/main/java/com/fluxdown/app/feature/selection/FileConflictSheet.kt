package com.fluxdown.app.feature.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fluxdown.app.R
import com.fluxdown.app.i18n.str
import com.fluxdown.app.nav.LocalNavigator
import com.fluxdown.app.shell.hostState
import com.fluxdown.core.format.Format
import com.fluxdown.core.model.FileConflicts
import com.fluxdown.core.model.FileExistsAction
import com.fluxdown.core.model.SelectionKind
import com.fluxdown.core.model.SelectionOutcome
import com.fluxdown.core.model.SelectionRequest
import com.fluxdown.fluxui.controls.ButtonSize
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxDivider
import com.fluxdown.fluxui.controls.FluxKeyValue
import com.fluxdown.fluxui.controls.GlassSection
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.overlay.FluxDialogButton
import com.fluxdown.fluxui.overlay.FluxDialogButtonStyle
import com.fluxdown.fluxui.overlay.FluxDialogSpec
import com.fluxdown.fluxui.overlay.FluxSheet
import com.fluxdown.fluxui.overlay.FluxSheetDetent
import com.fluxdown.fluxui.overlay.FluxSheetFooter
import com.fluxdown.fluxui.overlay.FluxSheetHeader
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import java.text.DateFormat
import java.util.Date

/** 退场动画期间保留最后一批请求；[visibleIds] = 对话框正显示着的请求（用来判断“被其它设备处理”）。 */
private class ConflictMemory {
    var last: List<Conflict> = emptyList()
    var visibleIds: Set<String> = emptySet()
}

@Immutable
private class Conflict(val request: SelectionRequest, val kind: SelectionKind.FileExists) {
    val id: String get() = request.requestId
}

private fun conflictsOf(requests: List<SelectionRequest>): List<Conflict> =
    requests.mapNotNull { r -> (r.kind as? SelectionKind.FileExists)?.let { Conflict(r, it) } }

/**
 * `file_exists_behavior = ask`：所有待选 fileExists 聚合成一个对话框（单条 = 卡片，多条 = 列表 + 批量按钮）。
 *
 * - 「稍后决定」（关闭钮 / 划走 / 返回 / 按钮）只隐藏，不答复主机；请求照常等到引擎超时（默认重命名）。
 *   新请求到达、回到前台或点任务行「待确认」角标都会重新弹出（[com.fluxdown.app.nav.AppNavigator.deferredFileConflicts]）。
 * - 只有明确点「取消下载」才答复 Cancelled。倒计时只是提示：到期由引擎按重命名处理，客户端不抢答。
 * - [blocked] = 有别的选择请求（BT / HLS / 变体）正在显示：让位，之后自动回来。
 */
@Composable
fun FileConflictHost(requests: List<SelectionRequest>, blocked: Boolean) {
    val nav = LocalNavigator.current
    val memory = remember { ConflictMemory() }
    val live = remember(requests) { conflictsOf(requests) }
    if (live.isNotEmpty()) memory.last = live
    val shown = live.ifEmpty { memory.last }
    if (shown.isEmpty()) return

    val resolver = rememberSelectionResolver()
    val overlays = LocalFluxOverlays.current
    val context = LocalContext.current
    val visible = !blocked && FileConflicts.shouldShow(requests, nav.deferredFileConflicts)

    // 正在显示的请求不是我们答复的就消失了 → 其它设备已处理（或已到期）
    LaunchedEffect(requests, visible) {
        val ids = requests.mapTo(HashSet()) { it.requestId }
        if (memory.visibleIds.any { it !in ids && !resolver.wasOurs(it) }) {
            overlays.toast(context.str(R.string.mobileSelectionResolvedElsewhere), FluxToastKind.Info, FluxIcons.CircleCheck)
        }
        memory.visibleIds = if (visible) ids else emptySet()
    }

    val deadline = remember(shown) { shown.minOf { it.request.deadlineUnixMs } }
    val countdown = rememberCountdown(deadline, deadline, active = visible)
    val later = { nav.deferFileConflicts(requests.mapTo(HashSet()) { it.requestId }) }
    val actions = remember(resolver, requests) { ConflictActions(resolver, requests) }
    val many = shown.size > 1
    val title = if (many) str(R.string.fileConflictTitleMany, "count" to shown.size) else str(R.string.fileConflictTitle)

    FluxSheet(
        visible = visible,
        onDismissRequest = later,
        detent = if (many) FluxSheetDetent.Full else FluxSheetDetent.Wrap,
        title = title,
        header = { FluxSheetHeader(title = title, subtitle = str(R.string.fileConflictDesc), onClose = later) },
        footer = {
            FluxSheetFooter {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { CountdownLine(countdown, R.string.fileConflictAutoRenameIn) }
                        FluxButton(str(R.string.fileConflictLater), later, variant = ButtonVariant.Ghost, size = ButtonSize.Sm)
                    }
                    if (many) BulkButtons(actions)
                }
            }
        },
    ) {
        if (many) ConflictList(shown, actions) else ConflictCard(shown.first(), actions)
    }
}

/** 答复入口：单条动作与批量动作（批量只作用于允许该动作的请求；整批只触发一次反馈）。 */
private class ConflictActions(private val resolver: SelectionResolver, private val requests: List<SelectionRequest>) {
    fun answer(request: SelectionRequest, action: FileExistsAction) {
        resolver.resolve(request, SelectionOutcome.FileExists(action), successToast = null, userInitiated = true)
    }

    fun cancel(request: SelectionRequest) = resolver.cancel(request)

    fun answerAll(action: FileExistsAction) {
        resolver.resolveAll(requests.map { it to SelectionOutcome.FileExists(action) }, successToast = null, userInitiated = true)
    }

    fun cancelAll(toast: String) {
        resolver.resolveAll(requests.map { it to SelectionOutcome.Cancelled }, toast, userInitiated = true)
    }

    fun canBulk(action: FileExistsAction): Boolean = FileConflicts.canBulk(requests, action)
}

// ── 单条：卡片 ────────────────────────────────────────────────────────────

@Composable
private fun ConflictCard(item: Conflict, actions: ConflictActions) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val host = hostState()
    val task by remember(item.request.taskId) { derivedStateOf { host.value.task(item.request.taskId) } }
    val kind = item.kind

    SelectionTaskRow(task)
    GlassSection(Modifier.padding(top = 8.dp), footer = kind.saveDir.ifEmpty { null }) {
        row { FluxKeyValue(str(R.string.fileConflictExisting), existingText(kind)) }
        row { FluxKeyValue(str(R.string.fileConflictIncoming), sizeText(kind.incomingSize, zeroIsUnknown = true)) }
    }

    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FluxButton(
            str(R.string.fileConflictRenameAs, "name" to kind.renamePreview),
            { actions.answer(item.request, FileExistsAction.Rename) },
            variant = ButtonVariant.Primary,
            fullWidth = true,
        )
        if (FileExistsAction.Overwrite in kind.actions) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FluxButton(
                    str(R.string.fileConflictOverwrite),
                    { actions.answer(item.request, FileExistsAction.Overwrite) },
                    variant = ButtonVariant.Danger,
                    icon = FluxIcons.TriangleAlert,
                    fullWidth = true,
                )
                FluxText(str(R.string.fileConflictOverwriteHint), Modifier.padding(horizontal = 8.dp), style = type.sm, color = c.amberText)
            }
        }
        if (FileExistsAction.Skip in kind.actions) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FluxButton(str(R.string.fileConflictSkip), { actions.answer(item.request, FileExistsAction.Skip) }, fullWidth = true)
                FluxText(str(R.string.fileConflictSkipHint), Modifier.padding(horizontal = 8.dp), style = type.sm, color = c.inkMuted)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FluxButton(str(R.string.fileConflictCancelDownload), { actions.cancel(item.request) }, variant = ButtonVariant.Ghost, fullWidth = true)
            FluxText(str(R.string.fileConflictCancelHint), Modifier.padding(horizontal = 8.dp), style = type.sm, color = c.inkMuted)
        }
    }
}

// ── 多条：列表 + 批量 ─────────────────────────────────────────────────────

@Composable
private fun ConflictList(items: List<Conflict>, actions: ConflictActions) {
    val c = FluxTheme.colors
    val listHeight = (LocalConfiguration.current.screenHeightDp - 420).coerceIn(220, 560).dp
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .height(listHeight)
            .clip(FluxTheme.shapes.card)
            .background(c.glass1),
    ) {
        items(items, key = { it.id }) { item ->
            ConflictRow(item, actions)
            FluxDivider(startInset = 16.dp)
        }
    }
}

@Composable
private fun ConflictRow(item: Conflict, actions: ConflictActions) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val kind = item.kind
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FluxText(kind.fileName, style = type.mono, color = c.ink, maxLines = 1)
        CompareLine(str(R.string.fileConflictExisting), existingText(kind))
        CompareLine(str(R.string.fileConflictIncoming), sizeText(kind.incomingSize, zeroIsUnknown = true))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FluxButton(
                str(R.string.fileConflictRename),
                { actions.answer(item.request, FileExistsAction.Rename) },
                Modifier.weight(1f),
                variant = ButtonVariant.Primary,
                size = ButtonSize.Xs,
            )
            if (FileExistsAction.Overwrite in kind.actions) {
                FluxButton(
                    str(R.string.fileConflictOverwrite),
                    { actions.answer(item.request, FileExistsAction.Overwrite) },
                    Modifier.weight(1f),
                    variant = ButtonVariant.Danger,
                    size = ButtonSize.Xs,
                )
            }
            if (FileExistsAction.Skip in kind.actions) {
                FluxButton(
                    str(R.string.fileConflictSkip),
                    { actions.answer(item.request, FileExistsAction.Skip) },
                    Modifier.weight(1f),
                    size = ButtonSize.Xs,
                )
            }
        }
        FluxButton(
            str(R.string.fileConflictCancelDownload),
            { actions.cancel(item.request) },
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Xs,
        )
    }
}

@Composable
private fun CompareLine(label: String, value: String) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        FluxText(label, style = type.sm, color = c.inkMuted, maxLines = 1)
        FluxText(value, Modifier.weight(1f), style = type.monoS, color = c.ink, maxLines = 1)
    }
}

private class BulkButton(val text: String, val variant: ButtonVariant, val onClick: () -> Unit)

/** 底部批量按钮（2 列）：全部重命名 / 全部覆盖（先确认）/ 全部跳过（仅有请求允许跳过时）/ 全部取消。 */
@Composable
private fun BulkButtons(actions: ConflictActions) {
    val overlays = LocalFluxOverlays.current
    val context = LocalContext.current
    val cancelledToast = str(R.string.mobileSelectionCancelled)
    val overwriteAll = str(R.string.fileConflictOverwriteAll)
    val buttons = buildList {
        add(BulkButton(str(R.string.fileConflictRenameAll), ButtonVariant.Primary) { actions.answerAll(FileExistsAction.Rename) })
        if (actions.canBulk(FileExistsAction.Overwrite)) {
            add(
                BulkButton(overwriteAll, ButtonVariant.Danger) {
                    overlays.showDialog(
                        FluxDialogSpec(
                            title = overwriteAll,
                            message = context.str(R.string.fileConflictOverwriteHint),
                            icon = FluxIcons.TriangleAlert,
                            buttons = listOf(
                                FluxDialogButton(context.str(R.string.cancel)),
                                FluxDialogButton(overwriteAll, FluxDialogButtonStyle.Destructive) { actions.answerAll(FileExistsAction.Overwrite) },
                            ),
                        ),
                    )
                },
            )
        }
        if (actions.canBulk(FileExistsAction.Skip)) {
            add(BulkButton(str(R.string.fileConflictSkipAll), ButtonVariant.Secondary) { actions.answerAll(FileExistsAction.Skip) })
        }
        add(BulkButton(str(R.string.fileConflictCancelAll), ButtonVariant.Ghost) { actions.cancelAll(cancelledToast) })
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (pair in buttons.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (b in pair) FluxButton(b.text, b.onClick, Modifier.weight(1f), variant = b.variant, size = ButtonSize.Sm)
            }
        }
    }
}

// ── 文案 ──────────────────────────────────────────────────────────────────

/** 已有文件：大小（0 字节也是有效值）+ 修改时间。 */
@Composable
private fun existingText(kind: SelectionKind.FileExists): String {
    val size = sizeText(kind.existingSize, zeroIsUnknown = false)
    val modified = kind.existingModifiedUnixMs?.let { ms ->
        val time = remember(ms) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms)) }
        str(R.string.fileConflictModified, "time" to time)
    }
    return if (modified == null) size else "$size · $modified"
}

/** [zeroIsUnknown]：新下载的 0 / 负值表示总大小未知；已有文件 0 字节是真实大小。 */
@Composable
private fun sizeText(bytes: Long?, zeroIsUnknown: Boolean): String =
    if (bytes == null || bytes < 0 || (zeroIsUnknown && bytes == 0L)) str(R.string.fileConflictSizeUnknown) else Format.bytes(bytes).toString()

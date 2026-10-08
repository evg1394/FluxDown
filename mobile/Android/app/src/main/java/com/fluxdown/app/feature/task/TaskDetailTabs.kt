package com.fluxdown.app.feature.task

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fluxdown.app.R
import com.fluxdown.app.actions.LocalTaskActions
import com.fluxdown.app.i18n.str
import com.fluxdown.app.shell.hostState
import com.fluxdown.app.ui.TaskVisualState
import com.fluxdown.app.ui.label
import com.fluxdown.core.format.Format
import com.fluxdown.core.model.Segment
import com.fluxdown.core.model.SeedingStatus
import com.fluxdown.core.model.Task
import com.fluxdown.core.model.TaskProtocol
import com.fluxdown.core.model.TaskStatus
import com.fluxdown.core.store.SpeedHistory
import com.fluxdown.fluxui.controls.ButtonSize
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxKeyValue
import com.fluxdown.fluxui.controls.FluxStatRow
import com.fluxdown.fluxui.controls.FluxTag
import com.fluxdown.fluxui.controls.GlassSection
import com.fluxdown.fluxui.controls.StatItem
import com.fluxdown.fluxui.controls.Tone
import com.fluxdown.fluxui.data.Donut
import com.fluxdown.fluxui.data.DonutSlice
import com.fluxdown.fluxui.data.WaveSeries
import com.fluxdown.fluxui.data.Waveform
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.material.fluxFlowIn
import com.fluxdown.fluxui.overlay.FluxOverlayState
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

// ── 常规 ──────────────────────────────────────────────────────────────────

@Composable
internal fun GeneralTab(modelOf: () -> DetailModel?) {
    val model = modelOf() ?: return
    val task = model.task
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val actions = LocalTaskActions.current
    val overlays = LocalFluxOverlays.current
    val context = LocalContext.current
    val errorCopied = str(R.string.detailErrorCopied)
    val pathCopied = str(R.string.mobilePathCopied)

    // 元信息行：协议 · 站点 · Boost
    Row(Modifier.fluxFlowIn(0), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FluxTag(task.protocol.tag())
        FluxText(task.siteLabel(), style = type.sm, color = c.inkMuted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        if (model.boosted) FluxTag(str(R.string.detailBoostActive), tone = Tone.Amber, icon = FluxIcons.Zap)
    }

    if (task.downloadedBytes > 0) SourcesCard(task, Modifier.fluxFlowIn(1))

    val unfinished = model.visual != TaskVisualState.Completed && model.visual != TaskVisualState.Seeding && model.visual != TaskVisualState.Missing
    val path = "${task.saveDir.trimEnd('/')}/${task.fileName}"
    GlassSection(Modifier.fluxFlowIn(2)) {
        row { FluxKeyValue(str(R.string.infoStatus), statusWord(model.visual, model.queuePosition)) }
        if (task.totalBytes > 0) row { FluxKeyValue(str(R.string.infoSize), Format.bytes(task.totalBytes).toString()) }
        if (unfinished) row {
            val pct = task.progress?.let { " · " + Format.percent(it) }.orEmpty()
            FluxKeyValue(str(R.string.infoDownloaded), Format.bytes(task.downloadedBytes).toString() + pct)
        }
        if (model.isTransferring) {
            row { FluxKeyValue(str(R.string.infoSpeed), Format.speed(model.speedDown)?.toString() ?: "—") }
            row { FluxKeyValue(str(R.string.infoRemaining), etaText(Format.etaSeconds(task.downloadedBytes, task.totalBytes, model.speedDown))) }
        }
        if (task.createdAt > 0) row { FluxKeyValue(str(R.string.infoStartedAt), formatDateTime(task.createdAt), mono = true) }
        if (task.completedAt > 0) row { FluxKeyValue(str(R.string.infoCompletedAt), formatDateTime(task.completedAt), mono = true) }
        row {
            FluxKeyValue(
                str(R.string.infoPath), path, mono = true,
                onClick = {
                    context.copyText(task.fileName, path)
                    overlays.toast(pathCopied, FluxToastKind.Success, FluxIcons.Copy)
                },
            )
        }
        row {
            val queueName = model.queue?.label() ?: str(R.string.mainQueue)
            FluxKeyValue(
                str(R.string.taskQueueLabel), queueName,
                onClick = if (task.status != TaskStatus.Completed) ({ actions.moveToQueue(listOf(task.taskId)) }) else null,
            )
        }
        if (task.autoRoute.isNotEmpty()) row { FluxKeyValue(str(R.string.taskRoute), routeLabel(task.autoRoute)) }
        if (task.status == TaskStatus.Failed && task.errorMessage.isNotEmpty()) row {
            FluxKeyValue(
                str(R.string.infoError), task.errorMessage, mono = true, tone = Tone.Coral,
                onClick = {
                    context.copyText(task.fileName, task.errorMessage)
                    overlays.toast(errorCopied, FluxToastKind.Success, FluxIcons.Copy)
                },
            )
        }
        model.group?.let { g -> row { FluxKeyValue(str(R.string.mobileGroupLabel), g.name) } }
    }

    FluxButton(
        str(R.string.copyUrl),
        { actions.copyLink(task) },
        modifier = Modifier.fluxFlowIn(3),
        icon = FluxIcons.Copy,
        fullWidth = true,
    )
}

/** 来源构成：源站 = 已下载 − (CDN + 代理 + 多网卡)；BT / eD2K 全部来自 P2P。 */
@Composable
private fun SourcesCard(task: Task, modifier: Modifier) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val downloaded = task.downloadedBytes
    val src = task.sourceBytes
    val p2p = task.protocol == TaskProtocol.Bt || task.protocol == TaskProtocol.Ed2k
    val rows = if (p2p) {
        listOf(Triple(str(R.string.sourceP2p), c.accentHi, downloaded))
    } else {
        listOf(
            Triple(str(R.string.sourceOrigin), c.accentHi, (downloaded - src.total).coerceAtLeast(0)),
            Triple(str(R.string.sourceCdn), c.mint, src.cdn),
            Triple(str(R.string.sourceProxy), c.amber, src.proxy),
            Triple(str(R.string.sourceNic), c.violet, src.nic),
        )
    }
    val slices = rows.map { DonutSlice(it.third.toFloat(), it.second, it.first) }
    val accelShare = if (downloaded > 0) (src.total.toDouble() / downloaded).coerceIn(0.0, 1.0) else 0.0
    val summary = when {
        p2p -> str(R.string.sourcesP2pHint)
        accelShare > 0 -> str(R.string.sourcesAccelShare, "percent" to "${(accelShare * 100).roundToInt()}%")
        else -> str(R.string.sourcesNoAccel)
    }
    val pctStyle = remember(type) { type.weight(type.mono, 600, mono = true) }
    GlassSection(modifier, title = str(R.string.detailSourcesTitle), footer = summary) {
        custom(padded = true) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Donut(
                    slices = slices,
                    size = 148.dp,
                    centerText = Format.bytes(downloaded).toString(),
                    sub = str(R.string.infoDownloaded),
                )
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rows.forEach { (label, color, bytes) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(8.dp).background(color, CircleShape))
                            FluxText(label, style = type.sm, color = c.ink, maxLines = 1, modifier = Modifier.weight(1f))
                            val share = if (downloaded > 0) bytes.toDouble() / downloaded else 0.0
                            FluxText(
                                "${(share * 100).roundToInt()}%",
                                style = pctStyle.copy(textAlign = TextAlign.End),
                                color = c.ink,
                                maxLines = 1,
                                modifier = Modifier.widthIn(min = 48.dp),
                            )
                            FluxText(
                                Format.bytes(bytes).toString(),
                                style = type.mono.copy(textAlign = TextAlign.End),
                                color = c.inkMuted,
                                maxLines = 1,
                                modifier = Modifier.widthIn(min = 62.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 速度 ──────────────────────────────────────────────────────────────────

private data class SpeedStats(val samples: Int, val avg: Long, val peak: Long)

private fun SpeedHistory?.stats(): SpeedStats {
    if (this == null || size == 0) return SpeedStats(0, 0, 0)
    var sum = 0L
    var peak = 0L
    for (i in 0 until size) {
        val v = downAt(i)
        sum += v
        peak = max(peak, v)
    }
    return SpeedStats(size, sum / size, peak)
}

@Composable
internal fun SpeedTab(taskId: String, modelOf: () -> DetailModel?) {
    val model = modelOf() ?: return
    val task = model.task
    val host = hostState()
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val stats by remember(taskId) { derivedStateOf { host.value.taskSpeedHistory[taskId].stats() } }

    val cur = Format.speedOrZero(model.speedDown)
    val avg = Format.speedOrZero(stats.avg)
    val peak = Format.speedOrZero(stats.peak)
    GlassSection(Modifier.fluxFlowIn(0)) {
        custom {
            FluxStatRow(
                listOf(
                    StatItem(cur.value, str(R.string.infoSpeed), cur.unit, if (model.isTransferring) Tone.Accent else Tone.Neutral),
                    StatItem(avg.value, str(R.string.mobileSpeedAvgRecent), avg.unit),
                    StatItem(peak.value, str(R.string.mobileSpeedPeakRecent), peak.unit),
                ),
            )
        }
    }

    // 实时曲线：采样读取放在绘制阶段，主机每次发布只重绘、不重组
    val buffer = remember { FloatArray(SpeedHistory.CAPACITY) }
    GlassSection(Modifier.fluxFlowIn(1)) {
        custom {
            Column(Modifier.padding(vertical = 12.dp)) {
                if (stats.samples < 2) {
                    FluxText(
                        str(R.string.speedChartEmpty),
                        style = type.sm,
                        color = c.inkMuted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 28.dp).fillMaxWidth(),
                    )
                } else {
                    Waveform(
                        samples = {
                            val h = host.value.taskSpeedHistory[taskId]
                            if (h == null || h.size < 2) {
                                WaveSeries.Idle
                            } else {
                                for (i in 0 until h.size) buffer[i] = h.downAt(i).toFloat()
                                WaveSeries(buffer, WaveSeries.EMPTY, h.size)
                            }
                        },
                        modifier = Modifier.height(120.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        FluxText(str(R.string.mobileSpeedAxisStart), style = type.monoS, color = c.inkFaint, maxLines = 1)
                        FluxText(str(R.string.mobileSpeedAxisNow), style = type.monoS, color = c.inkFaint, maxLines = 1)
                    }
                }
            }
        }
    }

    val runtime = model.runtime
    if (model.isTransferring && runtime != null) {
        GlassSection(Modifier.fluxFlowIn(2)) {
            row { FluxKeyValue(str(R.string.infoRemaining), etaText(Format.etaSeconds(task.downloadedBytes, task.totalBytes, model.speedDown))) }
            runtime.activeTransfers?.let { n -> row { FluxKeyValue(str(R.string.detailActiveTransfers), n.toString(), mono = true) } }
            if (task.protocol == TaskProtocol.Bt) {
                runtime.connectedPeers?.let { n -> row { FluxKeyValue(str(R.string.detailConnectedPeers), n.toString(), mono = true) } }
            }
        }
    }

    val segments = runtime?.segments.orEmpty()
    if (task.protocol != TaskProtocol.Bt && segments.size >= 2) {
        SegmentsCard(taskId, model, segments, Modifier.fluxFlowIn(3))
    }
}

@Composable
private fun SegmentsCard(taskId: String, model: DetailModel, segments: List<Segment>, modifier: Modifier) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    var expanded by rememberSaveable(taskId) { mutableStateOf(false) }
    val activeCount = if (model.isTransferring) segments.count { it.active == true } else 0
    GlassSection(modifier) {
        custom(padded = true) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FluxText(
                        str(R.string.segmentsDownloading, "active" to activeCount, "total" to segments.size),
                        style = type.sm,
                        color = c.ink,
                        modifier = Modifier.weight(1f),
                    )
                    FluxButton(
                        if (expanded) str(R.string.mobileSegHideDetails) else str(R.string.mobileSegShowDetails),
                        { expanded = !expanded },
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Xs,
                        trailingIcon = if (expanded) FluxIcons.ChevronUp else FluxIcons.ChevronDown,
                    )
                }
                SegmentMap(segments, live = model.isTransferring)
                if (expanded) SegmentTable(model, segments)
            }
        }
    }
}

/** 分段热图：每段一个 12dp 方格，已下载比例自左向右填充；活跃段高亮。 */
@Composable
private fun SegmentMap(segments: List<Segment>, live: Boolean) {
    val c = FluxTheme.colors
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = 12.dp
        val gap = 4.dp
        val cols = ((maxWidth + gap) / (cell + gap)).toInt().coerceAtLeast(1)
        val rows = (segments.size + cols - 1) / cols
        val h = (cell + gap) * rows - gap
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(h)
                .drawBehind {
                    val cellPx = cell.toPx()
                    val gapPx = gap.toPx()
                    val r = CornerRadius(3.dp.toPx())
                    segments.forEachIndexed { i, s ->
                        val x = (i % cols) * (cellPx + gapPx)
                        val y = (i / cols) * (cellPx + gapPx)
                        drawRoundRect(c.flowRemain, Offset(x, y), Size(cellPx, cellPx), r)
                        val len = (s.endByte - s.startByte + 1).coerceAtLeast(1)
                        val frac = (s.downloadedBytes.toFloat() / len).coerceIn(0f, 1f)
                        if (frac > 0f) {
                            val color = if (live && s.active == true) c.accentHi else c.flowDone
                            drawRoundRect(color, Offset(x, y), Size(max(cellPx * frac, 3.dp.toPx()), cellPx), r)
                        }
                    }
                },
        )
    }
}

@Composable
private fun SegmentTable(model: DetailModel, segments: List<Segment>) {
    val c = FluxTheme.colors
    val type = FluxTheme.type
    val micro = type.micro
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FluxText("#", style = micro, color = c.inkMuted, modifier = Modifier.widthIn(min = 30.dp))
            FluxText(str(R.string.infoStatus), style = micro, color = c.inkMuted, modifier = Modifier.widthIn(min = 62.dp))
            FluxText(str(R.string.infoDownloaded), style = micro, color = c.inkMuted, modifier = Modifier.weight(1f))
            FluxText(str(R.string.infoSize), style = micro, color = c.inkMuted, modifier = Modifier.widthIn(min = 64.dp), maxLines = 1)
        }
        Column(
            Modifier
                .heightIn(max = 296.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            segments.forEach { s ->
                val len = (s.endByte - s.startByte + 1).coerceAtLeast(1)
                val frac = (s.downloadedBytes.toFloat() / len).coerceIn(0f, 1f)
                val done = s.downloadedBytes >= len
                val (word, color) = when {
                    done -> str(R.string.mobileSegDone) to c.inkMuted
                    model.task.status == TaskStatus.Paused -> str(R.string.statusPaused) to c.inkMuted
                    model.isTransferring && s.active == true -> str(R.string.mobileSegActive) to c.accentHi
                    else -> str(R.string.mobileSegPending) to c.inkMuted
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FluxText("${s.index + 1}", style = type.mono, color = c.inkFaint, modifier = Modifier.widthIn(min = 30.dp), maxLines = 1)
                    FluxText(word, style = type.sm, color = color, modifier = Modifier.widthIn(min = 62.dp), maxLines = 1)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(c.flowRemain),
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(frac)
                                .background(if (model.isTransferring && s.active == true) c.accentHi else c.flowDone),
                        )
                    }
                    FluxText(Format.bytes(len).toString(), style = type.mono, color = c.inkMuted, modifier = Modifier.widthIn(min = 64.dp), maxLines = 1)
                }
            }
        }
    }
}

// ── 做种（仅 BT） ─────────────────────────────────────────────────────────

@Composable
internal fun SeedingTab(taskId: String, modelOf: () -> DetailModel?) {
    val model = modelOf() ?: return
    val task = model.task
    val host = hostState()
    val c = FluxTheme.colors
    val type = FluxTheme.type

    val seedWord = when (task.seedingStatus) {
        SeedingStatus.Seeding -> str(R.string.seedingStatusSeeding)
        SeedingStatus.RatioReached -> str(R.string.seedingStatusRatioReached)
        SeedingStatus.TimeReached -> str(R.string.seedingStatusTimeReached)
        SeedingStatus.UserStopped -> str(R.string.seedingStatusUserStopped)
        SeedingStatus.TaskDeleted -> str(R.string.seedingStatusDeleted)
        SeedingStatus.SessionReleased -> str(R.string.seedingStatusSessionReleased)
        SeedingStatus.InactiveStop -> str(R.string.seedingStatusInactiveReached)
        SeedingStatus.QueuedForSlot -> str(R.string.seedingStatusQueued)
        SeedingStatus.None, SeedingStatus.Unknown -> str(R.string.seedingStatusNone)
    }
    val basis = if (task.downloadedBytes > 0) task.downloadedBytes else task.totalBytes
    val ratio = if (basis > 0) task.uploadedBytes.toDouble() / basis else 0.0
    GlassSection(Modifier.fluxFlowIn(0)) {
        row {
            FluxKeyValue(
                str(R.string.seedingStatus), seedWord,
                tone = if (task.seedingStatus == SeedingStatus.Seeding) Tone.Mint else Tone.Neutral,
            )
        }
        if (task.seedingMessage.isNotEmpty()) row { FluxKeyValue(str(R.string.infoError), task.seedingMessage) }
        row { FluxKeyValue(str(R.string.uploadedTotal), Format.bytes(task.uploadedBytes).toString(), mono = true) }
        row { FluxKeyValue(str(R.string.seedRatio), String.format(Locale.ROOT, "%.2f", ratio), mono = true) }
        row { FluxKeyValue(str(R.string.seedTime), durationText(task.seedingTimeSecs)) }
        row { FluxKeyValue(str(R.string.mobileUploadChip), Format.speed(model.speedUp)?.toString() ?: "—", mono = true) }
    }

    val hasUp by remember(taskId) { derivedStateOf { (host.value.taskSpeedHistory[taskId]?.size ?: 0) >= 2 } }
    if (hasUp) {
        val buffer = remember { FloatArray(SpeedHistory.CAPACITY) }
        GlassSection(Modifier.fluxFlowIn(1), title = str(R.string.mobileUploadChip)) {
            custom {
                Waveform(
                    samples = {
                        val h = host.value.taskSpeedHistory[taskId]
                        if (h == null || h.size < 2) {
                            WaveSeries.Idle
                        } else {
                            for (i in 0 until h.size) buffer[i] = h.upAt(i).toFloat()
                            WaveSeries(buffer, WaveSeries.EMPTY, h.size)
                        }
                    },
                    modifier = Modifier.height(64.dp),
                )
            }
        }
    }
}

// ── 高级 ──────────────────────────────────────────────────────────────────

@Composable
internal fun AdvancedTab(modelOf: () -> DetailModel?) {
    val model = modelOf() ?: return
    val task = model.task
    val context = LocalContext.current
    val overlays = LocalFluxOverlays.current
    val copied = str(R.string.webCopied)
    fun copy(text: String) = copyWithToast(context, overlays, task.fileName, text, copied)

    GlassSection(Modifier.fluxFlowIn(0)) {
        row { FluxKeyValue(str(R.string.mobileTaskId), task.taskId, mono = true, onClick = { copy(task.taskId) }) }
        row { FluxKeyValue(str(R.string.mobileProtocol), task.protocol.tag() + " · " + task.siteLabel()) }
        if (!task.url.startsWith("torrent-file://")) {
            row { FluxKeyValue(str(R.string.infoUrl), task.url, mono = true, onClick = { copy(task.url) }) }
        }
        if (task.originUrl.isNotEmpty() && task.originUrl != task.url) {
            row { FluxKeyValue(str(R.string.mobileOriginUrl), task.originUrl, mono = true, onClick = { copy(task.originUrl) }) }
        }
        row { FluxKeyValue(str(R.string.saveDir), task.saveDir, mono = true, onClick = { copy(task.saveDir) }) }
        if (task.totalBytes > 0) {
            row { FluxKeyValue(str(R.string.infoSize), String.format(Locale.ROOT, "%,d B", task.totalBytes), mono = true) }
        }
        if (task.fileMissing) {
            row { FluxKeyValue(str(R.string.statusFileMissing), "✓", tone = Tone.Amber) }
        }
        model.group?.let { g -> row { FluxKeyValue(str(R.string.mobileGroupLabel), g.name) } }
        if (task.groupId.isNotEmpty()) {
            row { FluxKeyValue(str(R.string.mobileGroupId), task.groupId, mono = true, onClick = { copy(task.groupId) }) }
        }
    }
}

private fun copyWithToast(context: android.content.Context, overlays: FluxOverlayState, label: String, text: String, message: String) {
    context.copyText(label, text)
    overlays.toast(message, FluxToastKind.Success, FluxIcons.Copy)
}

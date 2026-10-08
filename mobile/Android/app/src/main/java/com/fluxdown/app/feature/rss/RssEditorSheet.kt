package com.fluxdown.app.feature.rss

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxdown.app.AppContainer
import com.fluxdown.app.R
import com.fluxdown.app.actions.LocalTaskActions
import com.fluxdown.app.feature.settings.PickerSheetHost
import com.fluxdown.app.feature.settings.PickerSpec
import com.fluxdown.app.feature.settings.RemoteDirectoryPickerSheet
import com.fluxdown.app.feature.settings.rememberLastNonNull
import com.fluxdown.app.i18n.str
import com.fluxdown.app.shell.LocalAppContainer
import com.fluxdown.app.shell.hostState
import com.fluxdown.app.ui.label
import com.fluxdown.core.host.HostErrorCode
import com.fluxdown.core.host.HostException
import com.fluxdown.core.model.Queue
import com.fluxdown.core.protocol.HostMethod
import com.fluxdown.core.protocol.RssSizeLiteral
import com.fluxdown.core.protocol.RssSourceDetail
import com.fluxdown.core.protocol.RssValidateRequest
import com.fluxdown.core.protocol.RssValidateResponse
import com.fluxdown.core.protocol.callJson
import com.fluxdown.core.protocol.callUnit
import com.fluxdown.core.protocol.jsonObject
import com.fluxdown.core.protocol.parseRssMaxPerFetch
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxField
import com.fluxdown.fluxui.controls.FluxFieldAction
import com.fluxdown.fluxui.controls.FluxIconButton
import com.fluxdown.fluxui.controls.FluxListRow
import com.fluxdown.fluxui.controls.FluxSegmented
import com.fluxdown.fluxui.controls.FluxSwitchRow
import com.fluxdown.fluxui.controls.GlassSection
import com.fluxdown.fluxui.controls.IconButtonSize
import com.fluxdown.fluxui.controls.SegOption
import com.fluxdown.fluxui.controls.SelectOption
import com.fluxdown.fluxui.feedback.FluxSpinner
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.overlay.FluxBanner
import com.fluxdown.fluxui.overlay.FluxBannerKind
import com.fluxdown.fluxui.overlay.FluxDialogButton
import com.fluxdown.fluxui.overlay.FluxDialogButtonStyle
import com.fluxdown.fluxui.overlay.FluxDialogSpec
import com.fluxdown.fluxui.overlay.FluxPortal
import com.fluxdown.fluxui.overlay.FluxSheet
import com.fluxdown.fluxui.overlay.FluxSheetDetent
import com.fluxdown.fluxui.overlay.FluxSheetFooter
import com.fluxdown.fluxui.overlay.FluxSheetHeader
import com.fluxdown.fluxui.overlay.FluxToastKind
import com.fluxdown.fluxui.overlay.LocalFluxOverlays
import com.fluxdown.fluxui.theme.FluxText
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** R3 编辑器目标：新建 / 编辑已有订阅。 */
sealed interface RssEditorTarget {
    /** [url] = 预填的订阅地址（剪贴板）。 */
    data class Create(val url: String = "") : RssEditorTarget
    data class Edit(val sourceId: String) : RssEditorTarget
}

private enum class RssTab(val title: Int) {
    Basic(R.string.rssTabBasic), Filter(R.string.rssTabFilter), Advanced(R.string.rssTabAdvanced)
}

/** 抓取间隔档位（分钟）。 */
private val IntervalChoices = listOf(15, 30, 60, 120, 360, 720, 1440)

/** 表单（文本框保持原始输入，保存时再校验 / 转换）。 */
private data class RssForm(
    val name: String = "",
    val url: String = "",
    val saveDir: String = "",
    val include: String = "",
    val exclude: String = "",
    val sizeMin: String = "",
    val sizeMax: String = "",
    val cookies: String = "",
    val userAgent: String = "",
    val proxyUrl: String = "",
    val maxPerFetch: String = RssSourceDetail.DEFAULT_MAX_PER_FETCH.toString(),
    val interval: Int = RssSourceDetail.DEFAULT_INTERVAL_MINUTES,
    val queueId: String = Queue.MAIN,
    val enabled: Boolean = true,
    val autoDownload: Boolean = true,
    val startPaused: Boolean = false,
    val useRegex: Boolean = false,
    val smartEpisode: Boolean = false,
    val sendReferer: Boolean = true,
    val notifyOnDownload: Boolean = true,
) {
    val request: RssValidateRequest
        get() = RssValidateRequest(url.trim(), cookies.trim(), userAgent.trim(), proxyUrl.trim())

    companion object {
        fun of(s: RssSourceDetail) = RssForm(
            name = s.name, url = s.url, saveDir = s.saveDir, include = s.includePattern, exclude = s.excludePattern,
            sizeMin = RssSizeLiteral.format(s.sizeMinBytes), sizeMax = RssSizeLiteral.format(s.sizeMaxBytes),
            cookies = s.cookies, userAgent = s.userAgent, proxyUrl = s.proxyUrl,
            maxPerFetch = s.effectiveMaxPerFetch.toString(), interval = s.effectiveIntervalMinutes,
            queueId = s.queueId.ifEmpty { Queue.MAIN }, enabled = s.enabled, autoDownload = s.autoDownload,
            startPaused = s.startPaused, useRegex = s.useRegex, smartEpisode = s.smartEpisode,
            sendReferer = s.sendReferer, notifyOnDownload = s.notifyOnDownload,
        )
    }
}

private sealed interface Validation {
    data object Idle : Validation
    data object Running : Validation
    data class Passed(val title: String, val itemCount: Int) : Validation
    data class Failed(val message: String) : Validation
}

/**
 * 编辑器状态与流程（同 iOS `RssEditorModel` / Web `SourceEditor` / GPUI `editor.rs`）：
 * 新建须先验证 feed（验证结果绑定其请求参数，URL / Cookie / UA / 代理变化即失效）；
 * 编辑先 `listSources` 取最新副本，只改表单字段后整体写回（`updateSource` 需要完整订阅）。
 */
@Stable
private class RssEditorModel(
    val target: RssEditorTarget,
    private val container: AppContainer,
    private val text: (Int) -> String,
    private val errorText: (HostException) -> String,
) {
    var form by mutableStateOf(RssForm(url = (target as? RssEditorTarget.Create)?.url.orEmpty()))
    var tab by mutableStateOf(RssTab.Basic)
    var loading by mutableStateOf(target is RssEditorTarget.Edit)
    var loadError by mutableStateOf<String?>(null)
    var validation by mutableStateOf<Validation>(Validation.Idle)
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var original: RssSourceDetail? = null
    private var initial = RssForm()
    private var validatedRequest: RssValidateRequest? = null
    private var validateJob: Job? = null

    val isEditing get() = target is RssEditorTarget.Edit
    val isDirty get() = form != initial
    val isValidated get() = validation is Validation.Passed && validatedRequest == form.request
    val showsDetails get() = isEditing || isValidated
    val canValidate get() = validation != Validation.Running && !saving && form.request.url.isNotEmpty()
    val canSave get() = !loading && loadError == null && !saving && validation != Validation.Running && (isEditing || isValidated)

    fun update(change: (RssForm) -> RssForm) {
        val before = form.request
        form = change(form)
        error = null
        if (form.request != before) {
            // 影响验证请求的字段变化：失败结果清除，通过结果随 isValidated 自动失效
            if (validation is Validation.Failed || (validation is Validation.Passed && !isValidated)) validation = Validation.Idle
        }
    }

    suspend fun load() {
        val id = (target as? RssEditorTarget.Edit)?.sourceId ?: return
        loading = true
        loadError = null
        try {
            val found = RssSourceDetail.listFromJson(container.session.callJson(HostMethod.daemonRssListSources))
                .firstOrNull { it.sourceId == id }
            if (found == null) {
                loadError = text(R.string.localServiceActionFailed)
            } else {
                original = found
                form = RssForm.of(found)
                initial = form
            }
        } catch (e: HostException) {
            loadError = errorText(e)
        } finally {
            loading = false
        }
    }

    fun validate() {
        if (!canValidate) return
        val sent = form.request
        validation = Validation.Running
        validatedRequest = null
        error = null
        validateJob?.cancel()
        validateJob = container.appScope.launch {
            try {
                val r = RssValidateResponse.fromJson(container.session.callJson(HostMethod.daemonRssValidate, sent.toJson()))
                // 迟到的结果不接受与当前输入不同的请求
                if (form.request != sent) {
                    validation = Validation.Idle
                } else if (r.error.isEmpty()) {
                    validatedRequest = sent
                    validation = Validation.Passed(r.feedTitle, r.itemCount)
                } else {
                    validation = Validation.Failed(r.error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostException) {
                validation = if (form.request == sent) Validation.Failed(errorText(e)) else Validation.Idle
            }
        }
    }

    fun cancel() {
        validateJob?.cancel()
    }

    private fun fail(tab: RssTab, id: Int): Boolean {
        this.tab = tab
        error = text(id)
        return false
    }

    /** @return true = 已写入。 */
    suspend fun save(): Boolean {
        if (!canSave) return false
        val f = form
        val sent = f.request
        if (sent.url.isEmpty()) return fail(RssTab.Basic, R.string.rssFeedRequired)
        if (!isEditing && !isValidated) return fail(RssTab.Basic, R.string.rssValidateBeforeSave)
        val min = RssSizeLiteral.field(f.sizeMin) ?: return fail(RssTab.Filter, R.string.rssInvalidNumber)
        val max = RssSizeLiteral.field(f.sizeMax) ?: return fail(RssTab.Filter, R.string.rssInvalidNumber)
        if (min > 0 && max > 0 && max < min) return fail(RssTab.Filter, R.string.rssInvalidSizeRange)
        val limit = parseRssMaxPerFetch(f.maxPerFetch) ?: return fail(RssTab.Advanced, R.string.rssInvalidNumber)
        val dir = f.saveDir.trim()
        if (dir.isNotEmpty() && !isAbsolutePath(dir)) return fail(RssTab.Basic, R.string.mobileSaveDirInvalid)

        val feedTitle = (validation as? Validation.Passed)?.title.orEmpty()
        val name = f.name.trim().ifEmpty { if (isEditing) "" else feedTitle }
        val input = (original ?: RssSourceDetail(url = sent.url)).copy(
            sourceId = (target as? RssEditorTarget.Edit)?.sourceId.orEmpty(),
            url = sent.url, name = name, enabled = f.enabled, autoDownload = f.autoDownload, startPaused = f.startPaused,
            intervalMinutes = f.interval, queueId = f.queueId, saveDir = dir,
            includePattern = f.include.trim(), excludePattern = f.exclude.trim(), useRegex = f.useRegex,
            smartEpisode = f.smartEpisode, sizeMinBytes = min, sizeMaxBytes = max,
            cookies = sent.cookies, userAgent = sent.userAgent, proxyUrl = sent.proxyUrl,
            maxPerFetch = limit, sendReferer = f.sendReferer, notifyOnDownload = f.notifyOnDownload,
        )
        error = null
        saving = true
        return try {
            if (isEditing) {
                container.session.callUnit(HostMethod.daemonRssUpdateSource, input.toJson())
            } else {
                container.session.callJson(HostMethod.daemonRssCreateSource, input.toJson())
            }
            true
        } catch (e: HostException) {
            val text = errorText(e)
            error = if (e.code == HostErrorCode.InvalidArgument && !e.message.isNullOrBlank()) "$text: ${e.message}" else text
            false
        } finally {
            saving = false
        }
    }
}

/** 本机 / 服务器的绝对路径（`/…`、`C:\…`、`\\server\…`）。 */
private fun isAbsolutePath(path: String): Boolean =
    path.startsWith("/") || path.startsWith("\\\\") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(path)

/**
 * R3 订阅编辑器 Sheet（新建 / 编辑）。页签：基本 · 过滤 · 高级。新建时先输入地址（以及影响验证请求的
 * Cookie / UA / 代理）并「验证」，通过后显示页签与其余字段并可「订阅」；编辑直接显示全部字段，可删除订阅。
 */
@Composable
internal fun RssEditorSheet(target: RssEditorTarget?, onDismiss: () -> Unit) {
    val shown = rememberLastNonNull(target)
    val container = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val actions = LocalTaskActions.current
    val overlays = LocalFluxOverlays.current
    val haptics = FluxTheme.haptics
    val model = remember(shown, context) { shown?.let { RssEditorModel(it, container, context::getString) { e -> actions.errorText(e) } } }
    var picker by remember { mutableStateOf<PickerSpec?>(null) }
    var showDirPicker by remember { mutableStateOf(false) }
    val scope = container.appScope

    LaunchedEffect(model) { model?.load() }

    val discardTitle = str(R.string.mobileRssDiscardTitle)
    val discard = str(R.string.mobileDiscard)
    val keep = str(R.string.mobileRssKeepEditing)
    val createdText = str(R.string.mobileRssCreatedToast)
    val savedText = str(R.string.mobileRssSavedToast)
    val deleteTitle = str(R.string.rssDeleteSource)
    val deleteDescTpl = str(R.string.rssDeleteConfirmDesc)
    val cancelText = str(R.string.cancel)
    val deleteText = str(R.string.delete)

    fun close() {
        model?.cancel()
        onDismiss()
    }

    fun requestClose() {
        val m = model
        if (m != null && m.isDirty && !m.saving) {
            overlays.showDialog(
                FluxDialogSpec(
                    title = discardTitle,
                    icon = FluxIcons.TriangleAlert,
                    buttons = listOf(
                        FluxDialogButton(keep, FluxDialogButtonStyle.Secondary),
                        FluxDialogButton(discard, FluxDialogButtonStyle.Destructive) { close() },
                    ),
                ),
            )
        } else {
            close()
        }
    }

    fun confirmDelete(m: RssEditorModel) {
        val id = (m.target as? RssEditorTarget.Edit)?.sourceId ?: return
        val name = m.form.name.ifBlank { m.form.url }
        overlays.showDialog(
            FluxDialogSpec(
                title = deleteTitle,
                message = deleteDescTpl.replace("{name}", name),
                icon = FluxIcons.Trash,
                buttons = listOf(
                    FluxDialogButton(cancelText, FluxDialogButtonStyle.Secondary),
                    FluxDialogButton(deleteText, FluxDialogButtonStyle.Destructive) {
                        scope.launch {
                            try {
                                container.session.callUnit(HostMethod.daemonRssDeleteSource, jsonObject("sourceId" to id))
                                haptics.confirm()
                                close()
                            } catch (e: HostException) {
                                haptics.reject()
                                overlays.toast(actions.errorText(e), FluxToastKind.Error)
                            }
                        }
                    },
                ),
            ),
        )
    }

    val title = str(if (shown is RssEditorTarget.Edit) R.string.rssManageTitle else R.string.rssAddSource)
    FluxPortal {
        FluxSheet(
            visible = target != null,
            onDismissRequest = ::requestClose,
            detent = FluxSheetDetent.Full,
            title = title,
            header = {
                FluxSheetHeader(
                    title = title,
                    actions = { FluxIconButton(FluxIcons.X, str(R.string.close), ::requestClose, size = IconButtonSize.Sm) },
                )
            },
            footer = {
                val m = model
                if (m != null) {
                    FluxSheetFooter {
                        if (m.isEditing) {
                            FluxButton(deleteText, { confirmDelete(m) }, Modifier.weight(1f), variant = ButtonVariant.Secondary, enabled = !m.saving)
                        } else {
                            FluxButton(
                                str(if (m.validation == Validation.Running) R.string.rssWizardValidating else R.string.rssWizardValidate),
                                m::validate,
                                Modifier.weight(1f),
                                variant = ButtonVariant.Secondary,
                                enabled = m.canValidate,
                                loading = m.validation == Validation.Running,
                            )
                        }
                        FluxButton(
                            str(if (m.isEditing) R.string.mobileRssSave else R.string.rssWizardSubscribe),
                            onClick = {
                                scope.launch {
                                    if (m.save()) {
                                        haptics.confirm()
                                        overlays.toast(if (m.isEditing) savedText else createdText, FluxToastKind.Success)
                                        close()
                                    } else {
                                        haptics.reject()
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            variant = ButtonVariant.Primary,
                            enabled = m.canSave,
                            loading = m.saving,
                        )
                    }
                }
            },
        ) {
            val m = model ?: return@FluxSheet
            when {
                m.loading -> Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), Alignment.Center) {
                    FluxSpinner(label = str(R.string.mobileLoading))
                }
                m.loadError != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FluxBanner(m.loadError.orEmpty(), kind = FluxBannerKind.Error)
                    FluxButton(str(R.string.mobileRetry), { scope.launch { m.load() } })
                }
                else -> EditorBody(m, openPicker = { picker = it }, onBrowse = { showDirPicker = true })
            }
        }
    }
    PickerSheetHost(picker = picker, onDismiss = { picker = null })
    RemoteDirectoryPickerSheet(
        visible = showDirPicker,
        startPath = model?.form?.saveDir.orEmpty(),
        onPick = { dir -> model?.update { it.copy(saveDir = dir) } },
        onDismiss = { showDirPicker = false },
    )
}

@Composable
private fun EditorBody(m: RssEditorModel, openPicker: (PickerSpec) -> Unit, onBrowse: () -> Unit) {
    val c = FluxTheme.colors
    val t = FluxTheme.type
    val host = hostState()
    val queues by remember { derivedStateOf { host.value.queues } }
    val f = m.form
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (m.showsDetails) {
            FluxSegmented(
                options = RssTab.entries.map { SegOption(it, str(it.title)) },
                selected = m.tab,
                onSelect = { m.tab = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        m.error?.let { FluxBanner(it, kind = FluxBannerKind.Error, slim = true) }
        when (if (m.showsDetails) m.tab else RssTab.Basic) {
            RssTab.Basic -> {
                FluxField(
                    value = f.url,
                    onValueChange = { v -> m.update { it.copy(url = v) } },
                    label = str(R.string.rssUrlLabel),
                    placeholder = str(R.string.rssUrlHint),
                    mono = true,
                )
                if (!m.isEditing) {
                    if (!m.showsDetails) {
                        // 验证前只露出会进入验证请求的字段；通过后它们回到「高级」页签
                        FluxText(str(R.string.rssTabAdvanced), style = t.micro, color = c.inkMuted, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                        RequestFields(m)
                    }
                    when (val v = m.validation) {
                        is Validation.Passed -> FluxBanner(
                            v.title.ifBlank { f.url } + " · " + str(R.string.rssWizardFeedSummary, "n" to v.itemCount),
                            kind = FluxBannerKind.Success, slim = true,
                        )
                        is Validation.Failed -> FluxBanner(str(R.string.rssEmptyError) + ": " + v.message, kind = FluxBannerKind.Error, slim = true)
                        else -> FluxText(str(R.string.rssEditorAuthHint), style = t.sm, color = c.inkMuted)
                    }
                    if (m.isValidated) FluxText(str(R.string.rssWizardSeedNote), style = t.sm, color = c.inkMuted)
                }
                if (m.showsDetails) {
                    FluxField(
                        value = f.name,
                        onValueChange = { v -> m.update { it.copy(name = v) } },
                        label = str(R.string.rssNameLabel),
                        placeholder = (m.validation as? Validation.Passed)?.title?.ifBlank { null } ?: str(R.string.rssNameHint),
                    )
                    FluxField(
                        value = f.saveDir,
                        onValueChange = { v -> m.update { it.copy(saveDir = v) } },
                        label = str(R.string.rssSaveDirLabel),
                        placeholder = str(R.string.rssSaveDirHint),
                        mono = true,
                        trailing = { FluxFieldAction(FluxIcons.FolderOpen, str(R.string.browse), onBrowse) },
                    )
                    val queueTitle = str(R.string.rssQueueLabel)
                    val queueOptions = queues.map { SelectOption(it.queueId, it.label()) }
                    val intervalTitle = str(R.string.rssIntervalLabel)
                    val intervalOptions = IntervalChoices.plus(f.interval).distinct().sorted()
                        .map { SelectOption(it.toString(), intervalLabel(it)) }
                    GlassSection {
                        row {
                            FluxListRow(
                                title = queueTitle,
                                value = queueOptions.firstOrNull { it.value == f.queueId }?.label ?: f.queueId,
                                chevron = true,
                                onClick = { openPicker(PickerSpec(queueTitle, queueOptions, f.queueId) { q -> m.update { it.copy(queueId = q) } }) },
                            )
                        }
                        row {
                            FluxListRow(
                                title = intervalTitle,
                                subtitle = str(R.string.rssIntervalHint),
                                value = intervalLabel(f.interval),
                                chevron = true,
                                onClick = {
                                    openPicker(
                                        PickerSpec(intervalTitle, intervalOptions, f.interval.toString()) { v ->
                                            m.update { it.copy(interval = v.toInt()) }
                                        },
                                    )
                                },
                            )
                        }
                        row {
                            FluxSwitchRow(str(R.string.rssEnabledLabel), f.enabled, { on -> m.update { it.copy(enabled = on) } }, subtitle = str(R.string.rssEnabledDesc))
                        }
                        row {
                            FluxSwitchRow(str(R.string.rssAutoDownloadLabel), f.autoDownload, { on -> m.update { it.copy(autoDownload = on) } }, subtitle = str(R.string.rssAutoDownloadDesc))
                        }
                        if (f.autoDownload) {
                            row {
                                FluxSwitchRow(str(R.string.rssStartPausedLabel), f.startPaused, { on -> m.update { it.copy(startPaused = on) } }, subtitle = str(R.string.rssStartPausedDesc))
                            }
                            row {
                                FluxSwitchRow(str(R.string.rssNotifyLabel), f.notifyOnDownload, { on -> m.update { it.copy(notifyOnDownload = on) } }, subtitle = str(R.string.rssNotifyDesc))
                            }
                        }
                    }
                }
            }
            RssTab.Filter -> {
                FluxField(f.include, { v -> m.update { it.copy(include = v) } }, label = str(R.string.rssIncludeLabel), hint = str(R.string.rssIncludeHint), mono = true)
                FluxField(f.exclude, { v -> m.update { it.copy(exclude = v) } }, label = str(R.string.rssExcludeLabel), hint = str(R.string.rssExcludeHint), mono = true)
                FluxField(f.sizeMin, { v -> m.update { it.copy(sizeMin = v) } }, label = str(R.string.rssSizeMinLabel), placeholder = "200M", mono = true)
                FluxField(f.sizeMax, { v -> m.update { it.copy(sizeMax = v) } }, label = str(R.string.rssSizeMaxLabel), placeholder = "2G", mono = true)
                GlassSection {
                    row {
                        FluxSwitchRow(str(R.string.rssUseRegexLabel), f.useRegex, { on -> m.update { it.copy(useRegex = on) } }, subtitle = str(R.string.rssUseRegexDesc))
                    }
                    row {
                        FluxSwitchRow(str(R.string.rssSmartEpisodeLabel), f.smartEpisode, { on -> m.update { it.copy(smartEpisode = on) } }, subtitle = str(R.string.rssSmartEpisodeDesc))
                    }
                }
            }
            RssTab.Advanced -> {
                RequestFields(m)
                FluxField(f.maxPerFetch, { v -> m.update { it.copy(maxPerFetch = v.filter(Char::isDigit).take(3)) } }, label = str(R.string.rssMaxPerFetchLabel), mono = true)
                GlassSection {
                    row {
                        FluxSwitchRow(str(R.string.rssSendRefererLabel), f.sendReferer, { on -> m.update { it.copy(sendReferer = on) } }, subtitle = str(R.string.rssSendRefererDesc))
                    }
                }
            }
        }
    }
}

/** 影响验证请求的字段（Cookie / UA / 代理）：新建验证前显示在基本页，其余时候在「高级」页签。 */
@Composable
private fun RequestFields(m: RssEditorModel) {
    val f = m.form
    FluxField(f.cookies, { v -> m.update { it.copy(cookies = v) } }, label = str(R.string.rssCookiesLabel), placeholder = str(R.string.rssCookiesHint), mono = true)
    FluxField(f.userAgent, { v -> m.update { it.copy(userAgent = v) } }, label = str(R.string.rssUserAgentLabel), placeholder = str(R.string.rssInheritGlobalHint), mono = true)
    FluxField(f.proxyUrl, { v -> m.update { it.copy(proxyUrl = v) } }, label = str(R.string.rssProxyLabel), placeholder = str(R.string.rssInheritGlobalHint), mono = true)
}

@Composable
private fun intervalLabel(minutes: Int): String =
    if (minutes >= 60 && minutes % 60 == 0) str(R.string.rssEveryHours, "n" to minutes / 60)
    else str(R.string.rssEveryMinutes, "n" to minutes)

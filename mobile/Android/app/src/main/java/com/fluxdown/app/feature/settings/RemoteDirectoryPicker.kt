package com.fluxdown.app.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxdown.app.R
import com.fluxdown.app.actions.LocalTaskActions
import com.fluxdown.app.i18n.str
import com.fluxdown.app.shell.LocalAppContainer
import com.fluxdown.core.host.HostErrorCode
import com.fluxdown.core.host.HostException
import com.fluxdown.core.protocol.FsListParams
import com.fluxdown.core.protocol.FsListResponse
import com.fluxdown.core.protocol.HostMethod
import com.fluxdown.core.protocol.callJson
import com.fluxdown.fluxui.controls.ButtonVariant
import com.fluxdown.fluxui.controls.FluxButton
import com.fluxdown.fluxui.controls.FluxIconButton
import com.fluxdown.fluxui.controls.FluxListRow
import com.fluxdown.fluxui.controls.GlassSection
import com.fluxdown.fluxui.controls.IconButtonSize
import com.fluxdown.fluxui.feedback.FluxEmpty
import com.fluxdown.fluxui.feedback.FluxGlyph
import com.fluxdown.fluxui.feedback.FluxSpinner
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.overlay.FluxPortal
import com.fluxdown.fluxui.overlay.FluxSheet
import com.fluxdown.fluxui.overlay.FluxSheetDetent
import com.fluxdown.fluxui.overlay.FluxSheetFooter
import com.fluxdown.fluxui.overlay.FluxSheetHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 服务器目录选择器（`daemon.fs.list`，仅子目录）：远端主机的路径在服务器上，手机无法用系统选择器浏览。
 * 点目录进入，「上一级」返回，「选择」确认当前目录。起始路径无效 / 不存在时回退到主机默认保存目录。
 * Windows 主机的盘符路径（`C:\`）按服务端返回的绝对路径原样显示与导航。
 */
@Composable
internal fun RemoteDirectoryPickerSheet(
    visible: Boolean,
    startPath: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val container = LocalAppContainer.current
    val actions = LocalTaskActions.current
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<FsListResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    // 只采纳最近一次请求的结果（快速连点目录时丢弃过期响应）
    val ticket = remember { IntArray(1) }

    suspend fun fetch(path: String?): FsListResponse {
        val raw = container.session.callJson(HostMethod.daemonFsList, FsListParams(path).toJson())
        return FsListResponse.fromJson(raw) ?: throw HostException(HostErrorCode.Internal, message = "daemon.fs.list: bad response")
    }

    fun load(path: String?) {
        val mine = ++ticket[0]
        loading = true
        scope.launch {
            try {
                val result = try {
                    fetch(path)
                } catch (e: HostException) {
                    // 起始路径可能已不存在：首次进入时回退到默认保存目录再试一次
                    if (path != null && listing == null) fetch(null) else throw e
                }
                if (mine == ticket[0]) {
                    listing = result
                    failure = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HostException) {
                if (mine == ticket[0]) failure = actions.errorText(e)
            } finally {
                if (mine == ticket[0]) loading = false
            }
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            listing = null
            failure = null
            load(startPath.trim().ifEmpty { null })
        } else {
            ticket[0]++
        }
    }

    val title = str(R.string.selectSaveDir)
    val close = str(R.string.close)
    val cancel = str(R.string.cancel)
    val pick = str(R.string.mobilePickThisFolder)
    val up = str(R.string.mobileFolderUp)
    val denied = str(R.string.mobileFolderDenied)
    val noSubfolders = str(R.string.mobileFolderNoSubfolders)
    val loadingText = str(R.string.mobileLoading)
    val retry = str(R.string.mobileRetry)

    FluxPortal {
        FluxSheet(
            visible = visible,
            onDismissRequest = onDismiss,
            detent = FluxSheetDetent.Full,
            title = title,
            header = {
                FluxSheetHeader(
                    title = title,
                    subtitle = listing?.path,
                    actions = { FluxIconButton(FluxIcons.X, close, onDismiss, size = IconButtonSize.Sm) },
                )
            },
            footer = {
                FluxSheetFooter {
                    FluxButton(cancel, onDismiss, Modifier.weight(1f), variant = ButtonVariant.Secondary)
                    FluxButton(
                        pick,
                        onClick = {
                            listing?.let { onPick(it.path) }
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        variant = ButtonVariant.Primary,
                        enabled = listing != null && !loading,
                    )
                }
            },
        ) {
            val current = listing
            when {
                current == null && loading -> Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), Alignment.Center) {
                    FluxSpinner(label = loadingText)
                }
                current == null -> FluxEmpty(
                    FluxGlyph.Inbox,
                    failure.orEmpty(),
                    Modifier.fillMaxWidth(),
                    action = { FluxButton(retry, { load(startPath.trim().ifEmpty { null }) }) },
                )
                else -> {
                    failure?.let {
                        // 已有列表时的后续失败：行内提示，保留当前目录
                        FluxListRow(title = it, icon = FluxIcons.TriangleAlert, danger = true)
                    }
                    if (current.parent != null || current.dirs.isNotEmpty()) GlassSection(Modifier.fillMaxWidth()) {
                        current.parent?.let { parent ->
                            row(hasIcon = true) {
                                FluxListRow(
                                    title = up,
                                    icon = FluxIcons.ArrowUp,
                                    enabled = !loading,
                                    onClick = { load(parent) },
                                )
                            }
                        }
                        for (entry in current.dirs) {
                            row(hasIcon = true) {
                                FluxListRow(
                                    title = entry.name.ifEmpty { entry.path },
                                    icon = FluxIcons.Folder,
                                    chevron = true,
                                    enabled = !loading,
                                    onClick = { load(entry.path) },
                                )
                            }
                        }
                    }
                    if (current.dirs.isEmpty()) {
                        FluxEmpty(
                            FluxGlyph.Inbox,
                            if (current.denied) denied else noSubfolders,
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

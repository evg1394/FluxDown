package com.fluxdown.app.feature.settings.general

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxdown.app.data.DeviceSettings
import com.fluxdown.app.service.DownloadNotifier
import com.fluxdown.app.shell.LocalAppContainer
import com.fluxdown.app.shell.hostState
import com.fluxdown.core.model.HostRef
import com.fluxdown.core.protocol.preferences

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 设置带来的根级副作用（AppShell 根部挂一次，与当前停留在哪个页面无关）：
 * - 下载期间保持屏幕常亮（`download.keep_awake`）：偏好开启、当前主机是本机（手机自己在下载）、界面在前台、
 *   且确有活跃下载 → `FLAG_KEEP_SCREEN_ON`，否则清除；
 * - 完成 / 失败 / 选择请求的本地通知观察（[DownloadNotifier]，幂等；本机下载期间由前台服务同样拉起），
 *   并跟踪界面前后台与系统通知授权状态。
 */
@Composable
internal fun GeneralSettingsEffects() {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }

    // 设备本地设置先于任何读数创建（首页读数用无 Context 的 peek）
    remember(context) { DeviceSettings.of(context) }

    LaunchedEffect(container) { DownloadNotifier.attach(container, context) }

    DisposableEffect(lifecycle) {
        DownloadNotifier.foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { DownloadNotifier.foreground = false }
    }
    // 前后台标记同步写入（后台时 Compose 不再出帧，不能指望重组）
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        foreground = true
        DownloadNotifier.foreground = true
        DownloadNotifier.refreshAuthorization(context)
    }
    // 系统权限框（含首个本机下载时 DownloadServiceEffect 弹出的 POST_NOTIFICATIONS）只 pause 不 stop：
    // 回到 resumed 时重读授权，首页读数 / 测试通知门控不会停留在陈旧状态。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { DownloadNotifier.refreshAuthorization(context) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        foreground = false
        DownloadNotifier.foreground = false
    }

    val host = hostState()
    val hostRef by container.host.collectAsStateWithLifecycle()
    val hasActiveDownloads by remember {
        derivedStateOf {
            val state = host.value
            state.preferences.bool("download.keep_awake", false) && state.tasks.any { it.status.isActive }
        }
    }
    val keepAwake = hasActiveDownloads && hostRef is HostRef.Local && foreground
    val window = remember(context) { context.findActivity()?.window }
    DisposableEffect(window, keepAwake) {
        if (keepAwake) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (keepAwake) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

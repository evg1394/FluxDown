package com.fluxdown.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.fluxdown.app.AppContainer
import com.fluxdown.app.FluxApplication
import com.fluxdown.app.HomeActivity
import com.fluxdown.app.R
import com.fluxdown.app.i18n.str
import com.fluxdown.core.format.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "FluxDownService"
private const val CHANNEL_ID = "fluxdown_downloads"
private const val NOTIFICATION_ID = 1001

/** 通知刷新最小间隔（≤ 1 Hz）。 */
private const val THROTTLE_MS = 1_000L

/** 空闲后保留前台的宽限（任务切换 / 队列接力时 active 会瞬间归零），随后自行停止。 */
private const val IDLE_GRACE_MS = 3_000L

/**
 * 本机下载前台服务（`dataSync`）：本机引擎有活跃 / 排队 / 待重试任务时存在，让进程在后台继续下载——
 * 与当前选中的主机无关（远端主机在前台时，进程内的本机下载照样在跑）。
 * 通知常驻、静默，展示聚合下行速度与活跃数，点按回到应用；本机空闲后自行停止。
 *
 * 事件驱动：只收集 [AppContainer.localActivity]（统计推送），无周期轮询；通知刷新按 [THROTTLE_MS] 节流，
 * 空闲宽限由新的活动量到来而取消。启动入口见 [DownloadServiceController.start]；
 * 停止由服务自己决定，因此 Activity 被销毁后也不会残留前台通知。
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastShownAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 之后必须在数秒内 startForeground：先用当前量发出，再进入观察
        val container = (application as FluxApplication).container
        show(container.localActivity.value)
        // 完成 / 失败通知的观察在应用作用域内：下载期间即使 Activity 已被划掉也不中断
        DownloadNotifier.attach(container, applicationContext)
        if (watcher?.isActive != true) {
            watcher = scope.launch { watch(container) }
        }
        return START_NOT_STICKY
    }

    private suspend fun watch(container: AppContainer) {
        container.localActivity.collectLatest { activity ->
            if (activity.busy) {
                val wait = THROTTLE_MS - (SystemClock.elapsedRealtime() - lastShownAtMs)
                if (wait > 0) delay(wait)
                show(activity)
            } else {
                delay(IDLE_GRACE_MS)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                watcher?.cancel()
            }
        }
    }

    /** Android 15+：dataSync 前台服务 6 小时上限；系统回调后必须立即停止，回到前台时由 [DownloadServiceEffect] 重新拉起。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timed out (type=$fgsType); stopping until the app returns to foreground")
        watcher?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 重复调用 `startForeground` 即更新通知，且不受通知权限影响。 */
    private fun show(activity: LocalActivity) {
        lastShownAtMs = SystemClock.elapsedRealtime()
        startForeground(NOTIFICATION_ID, buildNotification(this, activity), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

/** 渠道只创建一次（已存在则跳过）；LOW = 静默、不弹出。 */
private fun ensureChannel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(CHANNEL_ID, context.str(R.string.mobileDlNotifChannel), NotificationManager.IMPORTANCE_LOW).apply {
        description = context.str(R.string.mobileDlNotifChannelDesc)
        setShowBadge(false)
    }
    nm.createNotificationChannel(channel)
}

private fun buildNotification(context: Context, a: LocalActivity): android.app.Notification {
    val title = if (a.active > 0) {
        context.str(R.string.mobileDlNotifActive, "n" to a.active)
    } else {
        context.str(R.string.mobileDlNotifWaiting, "n" to a.waiting)
    }
    val text = if (a.active > 0) {
        val speed = Format.speedOrZero(a.downBps)
        val parts = buildList {
            add(context.str(R.string.mobileDlNotifSpeed, "speed" to "${speed.value} ${speed.unit}"))
            if (a.waiting > 0) add(context.str(R.string.mobileDlNotifWaiting, "n" to a.waiting))
        }
        parts.joinToString(" · ")
    } else {
        null
    }
    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, HomeActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_download)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()
}

/**
 * 前台服务启动入口与通知权限（Android 13+）。
 * 只在应用处于前台时调用（Android 12+ 禁止后台启动前台服务）。
 */
object DownloadServiceController {
    private const val PREFS = "fluxdown_service"
    private const val KEY_NOTIF_ASKED = "notif_permission_asked"

    /** 拉起（或刷新）前台服务。后台启动被系统拒绝时仅记录日志，服务若已在运行则不受影响。 */
    fun start(context: Context) {
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "foreground service start refused: ${e.message}")
        }
    }

    /** 需要请求通知权限：Android 13+、尚未授予、且从未请求过（避免每次冷启动打扰；拒绝后服务照常运行）。 */
    fun shouldRequestNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTIF_ASKED, false)
    }

    /** 记录已请求过（无论用户同意与否）。 */
    fun markNotificationPermissionAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
    }
}

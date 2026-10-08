package com.fluxdown.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 设备本地设置（`SharedPreferences`；永远是这台手机，不随主机切换、不上云）。
 * 键名同 iOS `DeviceSettings`（03-settings §14 的 `mobile.*`）。
 *
 * 值是 Compose 快照状态：设置页 / 首页读数在组合内读取即订阅；通知服务在后台协程里读取同一份最新值。
 * 写入即落盘（`apply`）。
 */
class DeviceSettings private constructor(private val prefs: SharedPreferences) {
    /** 任务失败时通知（默认开）。 */
    var notifyOnFailure by mutableStateOf(prefs.getBoolean(KEY_NOTIFY_ON_FAIL, true))
        private set

    /** 完成通知附「打开」「分享」操作按钮（默认开）。 */
    var notifyActions by mutableStateOf(prefs.getBoolean(KEY_NOTIFY_ACTIONS, true))
        private set

    fun updateNotifyOnFailure(on: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_ON_FAIL, on).apply()
        notifyOnFailure = on
    }

    fun updateNotifyActions(on: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_ACTIONS, on).apply()
        notifyActions = on
    }

    companion object {
        private const val FILE = "fluxdown_device"
        private const val KEY_NOTIFY_ON_FAIL = "mobile.notify_on_fail"
        private const val KEY_NOTIFY_ACTIONS = "mobile.notify_actions"

        @Volatile
        private var instance: DeviceSettings? = null

        /** 进程内单例（应用 Context）。 */
        fun of(context: Context): DeviceSettings =
            instance ?: synchronized(this) {
                instance ?: DeviceSettings(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))
                    .also { instance = it }
            }

        /** 已创建的单例（尚未创建为 null）；无 Context 的读数函数用。 */
        fun peek(): DeviceSettings? = instance
    }
}

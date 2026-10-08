package com.fluxdown.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fluxdown.app.nav.AppNavigator
import com.fluxdown.app.service.NotificationIntents
import com.fluxdown.app.shell.AppShell
import com.fluxdown.app.shell.FluxAppRoot

/**
 * 主界面（Compose 宿主）。不对外导出：桌面图标经 [MainActivity] 路由进来，系统通知 / 前台服务通知直接指向这里；
 * 外部下载唤起由透明的 [ExternalDownloadActivity] 承接。
 */
class HomeActivity : ComponentActivity() {
    private val navigator = AppNavigator()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as FluxApplication).container
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            FluxAppRoot(container, navigator) {
                AppShell()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** 点按系统通知 → 切到通知所属主机并打开任务详情；其余（桌面图标启动）无需处理。 */
    private fun handleIntent(intent: Intent?) {
        NotificationIntents.handle((application as FluxApplication).container, navigator, intent)
    }
}

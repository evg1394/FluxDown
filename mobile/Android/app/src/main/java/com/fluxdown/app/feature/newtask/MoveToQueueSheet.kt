package com.fluxdown.app.feature.newtask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.fluxdown.app.R
import com.fluxdown.app.actions.LocalTaskActions
import com.fluxdown.app.i18n.str
import com.fluxdown.app.nav.SheetRoute
import com.fluxdown.app.shell.hostState
import com.fluxdown.app.ui.label
import com.fluxdown.core.model.Queue
import com.fluxdown.fluxui.controls.GlassSection
import com.fluxdown.fluxui.controls.FluxRadioRow
import com.fluxdown.fluxui.icons.FluxIcons
import com.fluxdown.fluxui.overlay.FluxSheet
import com.fluxdown.fluxui.overlay.FluxSheetDetent
import com.fluxdown.fluxui.overlay.FluxSheetHeader

/** 内置主队列的任务 `queueId` 可能是 ""（隐式主队列）或 `main`，视为同一个。 */
private fun String.canonicalQueue(): String = ifEmpty { Queue.MAIN }

/**
 * D5#7 移动到队列（Wrap）：单选列表，点选即提交。副标题 = 运行状态 · 任务数；
 * 所选任务都在同一队列时，该队列打勾。
 */
@Composable
fun MoveToQueueSheet(route: SheetRoute.MoveToQueue?, onDismiss: () -> Unit) {
    // 退场动画期间保留最后一次的任务集合
    val last = remember { arrayOfNulls<SheetRoute.MoveToQueue>(1) }
    if (route != null) last[0] = route
    val current = route ?: last[0] ?: return
    val actions = LocalTaskActions.current
    val host = hostState()
    val ids = current.taskIds

    FluxSheet(
        visible = route != null,
        onDismissRequest = onDismiss,
        detent = FluxSheetDetent.Wrap,
        title = str(R.string.moveToQueueAction),
        header = {
            FluxSheetHeader(
                title = str(R.string.moveToQueueAction),
                subtitle = str(R.string.mobileSelectQueue),
                onClose = onDismiss,
            )
        },
    ) {
        val queues by remember { derivedStateOf { host.value.queues } }
        // 所选任务当前所在队列（全部相同才有值）与各队列任务数
        val currentQueue by remember(ids) {
            derivedStateOf {
                val tasks = host.value.tasks.filter { it.taskId in ids }
                tasks.map { it.queueId.canonicalQueue() }.distinct().singleOrNull()
            }
        }
        val counts by remember {
            derivedStateOf { host.value.tasks.groupingBy { it.queueId.canonicalQueue() }.eachCount() }
        }
        val running = str(R.string.queueRunningBadge)
        val stopped = str(R.string.queueStoppedBadge)
        GlassSection(Modifier.padding(top = 2.dp)) {
            queues.forEach { q ->
                row(hasIcon = true) {
                    val name = q.label()
                    FluxRadioRow(
                        title = name,
                        selected = q.queueId.canonicalQueue() == currentQueue,
                        onSelect = {
                            if (q.queueId.canonicalQueue() != currentQueue) {
                                actions.moveToQueueNow(ids, q.queueId, name)
                            }
                            onDismiss()
                        },
                        subtitle = (if (q.isRunning) running else stopped) + " · " + str(R.string.nTasks, "n" to (counts[q.queueId.canonicalQueue()] ?: 0)),
                        icon = FluxIcons.Rows3,
                    )
                }
            }
        }
    }
}

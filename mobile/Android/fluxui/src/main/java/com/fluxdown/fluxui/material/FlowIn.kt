package com.fluxdown.fluxui.material

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * 流入是否可播放。页面推入 / 返回 / Tab 切换的入场期间由舞台提供 false：整页随过渡一次到位、文字始终清晰，
 * 不在滑入的同时再逐项淡入（对齐 iOS 导航过渡）。过渡结束后新出现的节点（新任务、展开的分组）照常流入。
 */
val LocalFlowInEnabled = compositionLocalOf { true }

/**
 * 签名动效“流入”（§8.2）：`translationY 8dp→0`、`alpha 0→1`，`flow` 弹簧（临界阻尼）；
 * 第 i 个兄弟延迟 `min(i, 8) × 22 ms`。全程不做模糊：文字从第一帧起就是清晰的。
 *
 * - **每个节点首次进入组合时只播放一次**；动画结束后修饰符自动退化为空（不留 graphicsLayer），重组不重播。
 * - Reduce motion 或 [LocalFlowInEnabled] = false：直接终态。TalkBack（触摸探索）开启：无错落延迟，弹簧改用 `fluid`。
 * - 列表中“滚动回收的新项不播放”：用 [enabled] 或 [fluxFlowIn] 的 gate 重载控制；`enabled` 只在首次组合时取值。
 *
 * 读取动画值全部在 `graphicsLayer { }` 块内（不触发重组）。
 */
@Composable
fun Modifier.fluxFlowIn(index: Int = 0, enabled: Boolean = true): Modifier {
    val m = FluxTheme.motion
    val allowed = LocalFlowInEnabled.current
    var done by remember { mutableStateOf(!(enabled && allowed && !m.reduce)) }
    if (done) return this

    val density = LocalDensity.current
    val touchExploration = rememberTouchExploration()
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!touchExploration) delay(min(index, m.flowStaggerMax) * m.flowStaggerMs)
        p.animateTo(1f, m.of(if (touchExploration) m.fluid else m.flow))
        done = true
    }
    val ty = with(density) { m.flowTranslate.toPx() }
    return this.graphicsLayer {
        val t = p.value
        alpha = t
        translationY = (1f - t) * ty
    }
}

/**
 * 页面级“已入场”记录：同一 key 在 gate 生命周期内只播放一次（`rememberSaveable`，旋转屏幕不重播）。
 * 用法：页面持有 `val gate = rememberFlowInGate()`，列表项 `Modifier.fluxFlowIn(i, gate, key = task.id)`；
 * 滚动回收后再次进入的项（key 已记录）不再播放。key 以 `toString()` 记录。
 */
@Stable
class FlowInGate internal constructor(internal val seen: MutableSet<String>) {
    /** 该 key 首次出现返回 true（并记录）；之后恒为 false。 */
    fun firstTime(key: Any): Boolean = seen.add(key.toString())
}

@Composable
fun rememberFlowInGate(): FlowInGate = rememberSaveable(
    saver = listSaver(save = { it.seen.toList() }, restore = { FlowInGate(it.toHashSet()) }),
) { FlowInGate(HashSet()) }

/** [fluxFlowIn] 的 gate 重载：仅当 [key] 对 [gate] 是首次出现时播放。 */
@Composable
fun Modifier.fluxFlowIn(index: Int, gate: FlowInGate, key: Any): Modifier {
    val play = remember(key) { gate.firstTime(key) }
    return fluxFlowIn(index, play)
}

@Composable
private fun rememberTouchExploration(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) { ctx.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }
}

package com.fluxdown.fluxui.material

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.fluxdown.fluxui.theme.FluxGlassMode
import com.fluxdown.fluxui.theme.FluxTheme
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * 签名动效“流入”（§8.2）：`translationY 8dp→0`、`模糊 8→0`、`alpha 0→1`，`flow` 弹簧；
 * 第 i 个兄弟延迟 `min(i, 12) × 38 ms`。
 *
 * - **每个节点首次进入组合时只播放一次**；动画结束后修饰符自动退化为空（不留 graphicsLayer），重组不重播。
 * - 模糊半径量化为 {0,1,2,3,4,6,8}dp 七档，RenderEffect 全局预建、逐帧只查表；
 *   `perf.glassMode == Solid`（省电 / 过热 / 低内存）时跳过模糊，仅 translate + alpha。
 * - Reduce motion：直接终态。TalkBack（触摸探索）开启：无错落延迟，弹簧改用 `fluid`。
 * - 列表中“滚动回收的新项不播放”：用 [enabled] 或 [fluxFlowIn] 的 gate 重载控制；`enabled` 只在首次组合时取值。
 *
 * 读取动画值全部在 `graphicsLayer { }` 块内（不触发重组）。
 */
@Composable
fun Modifier.fluxFlowIn(index: Int = 0, enabled: Boolean = true): Modifier {
    val m = FluxTheme.motion
    var done by remember { mutableStateOf(!(enabled && !m.reduce)) }
    if (done) return this

    val density = LocalDensity.current
    val blurOn = FluxTheme.perf.glassMode != FluxGlassMode.Solid
    val touchExploration = rememberTouchExploration()
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (!touchExploration) delay(min(index, m.flowStaggerMax) * m.flowStaggerMs)
        p.animateTo(1f, m.of(if (touchExploration) m.fluid else m.flow))
        done = true
    }
    val ty = with(density) { m.flowTranslate.toPx() }
    val blurDp = m.flowBlur.value
    val dens = density.density
    return this.graphicsLayer {
        val t = p.value
        alpha = t
        translationY = (1f - t) * ty
        renderEffect = if (blurOn && t < 1f) flowBlurEffect(dens, blurDp * (1f - t)) else null
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

// ───────────── 模糊半径量化缓存（§8.2：不得每帧新建 RenderEffect） ─────────────

private class FlowBlurCache(val density: Float) {
    private val steps = floatArrayOf(0f, 1f, 2f, 3f, 4f, 6f, 8f)
    private val effects: Array<RenderEffect?> = Array(steps.size) { i ->
        if (steps[i] == 0f) null else BlurEffect(steps[i] * density, steps[i] * density, TileMode.Decal)
    }

    fun get(blurDp: Float): RenderEffect? {
        for (i in steps.indices) if (steps[i] >= blurDp - 0.01f) return effects[i]
        return effects[effects.lastIndex]
    }
}

@Volatile
private var flowBlurCache: FlowBlurCache? = null

private fun flowBlurEffect(density: Float, blurDp: Float): RenderEffect? {
    var c = flowBlurCache
    if (c == null || c.density != density) {
        c = FlowBlurCache(density)
        flowBlurCache = c
    }
    return c.get(blurDp)
}

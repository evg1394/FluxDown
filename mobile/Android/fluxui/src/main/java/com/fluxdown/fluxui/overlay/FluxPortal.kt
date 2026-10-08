package com.fluxdown.fluxui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 浮层传送门：页面深处声明的 Sheet 等浮层，实际在舞台的浮层层（坞 / 球之上、背景源之外）组合。
 * 这样浮层不被页面裁剪，也不会落进自己采样的背景源里形成 RenderNode 环。
 * 传送内容使用宿主处的 CompositionLocal（舞台级 Local 均可用；页面私有 Local 不可用）。
 */
@Stable
class FluxPortalState {
    internal val entries = mutableStateListOf<PortalEntry>()
}

internal class PortalEntry(val key: Any, val content: @Composable () -> Unit)

val LocalFluxPortal = staticCompositionLocalOf<FluxPortalState?> { null }

/** 把 [content] 传送到最近的 [FluxPortalHost]；没有宿主时原地组合。 */
@Composable
fun FluxPortal(content: @Composable () -> Unit) {
    val portal = LocalFluxPortal.current
    if (portal == null) {
        content()
        return
    }
    val latest by rememberUpdatedState(content)
    val id = remember { Any() }
    DisposableEffect(portal, id) {
        val entry = PortalEntry(id) { latest() }
        portal.entries.add(entry)
        onDispose { portal.entries.remove(entry) }
    }
}

/** 放在舞台浮层层（Sheet 层所在位置）。 */
@Composable
fun FluxPortalHost(state: FluxPortalState) {
    for (entry in state.entries) {
        key(entry.key) { entry.content() }
    }
}

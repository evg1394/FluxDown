package com.fluxdown.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxdown.app.AppContainer
import com.fluxdown.core.store.HostState

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer missing") }

/** 当前主机投影（只在 RESUMED 时收集：后台不为刷新 UI 消耗 CPU）。 */
@Composable
fun hostState(): State<HostState> = LocalAppContainer.current.store.state.collectAsStateWithLifecycle()

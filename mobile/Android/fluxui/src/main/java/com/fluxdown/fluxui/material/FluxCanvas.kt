package com.fluxdown.fluxui.material

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fluxdown.fluxui.theme.FluxTheme

/**
 * 页面背景（§5.6 z 序）：画布纯色 0 → 颗粒 1 → [content]（舞台 / 页面内容，z 2）。
 *
 * 根页应放在 `Modifier.fluxBackdropSource(...)` 的根内，使玻璃面能取到画布 + 颗粒作为背后内容。
 */
@Composable
fun FluxCanvas(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.background(FluxTheme.colors.canvas)) {
        Box(Modifier.fillMaxSize().fluxGrain())
        content()
    }
}

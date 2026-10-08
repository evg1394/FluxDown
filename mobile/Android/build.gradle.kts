// Top-level build file. 模块：:core（主机契约 / 状态仓库）→ :bridge（UniFFI native/mobile 适配）→ :fluxui（Flux Lumen 组件库）→ :app（页面与宿主装配）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

package com.fluxdown.app.i18n

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 文案来自仓库根 `assets/i18n/{en,zh}.json`（构建期生成 `R.string.<camelCaseKey>`）。
 * 占位符保持 `{name}` 原样，经 [fill] 运行期替换（与 GPUI / Web 同一份键与占位符）。
 */
fun String.fill(vararg args: Pair<String, Any?>): String {
    if (args.isEmpty() || indexOf('{') < 0) return this
    var out = this
    for ((k, v) in args) out = out.replace("{$k}", v.toString())
    return out
}

@Composable
fun str(@StringRes id: Int, vararg args: Pair<String, Any?>): String = stringResource(id).fill(*args)

fun Context.str(@StringRes id: Int, vararg args: Pair<String, Any?>): String = getString(id).fill(*args)

// 主题应用：把解析出的 CSS 变量写到 <html>，并维护首屏缓存。

import { APPEARANCE_KEYS, parseAppearance } from './appearance'
import type { ThemeModeName, ThemePreference } from './appearance'
import { themeVariables } from './tokens'
import { applyFontFamily, fontPreference } from './fontFamily'

const CACHE_KEY = 'fluxdown.web.appearance'

export function loadCache(): Record<string, unknown> {
  try {
    const raw = localStorage.getItem(CACHE_KEY)
    if (!raw) return {}
    const parsed: unknown = JSON.parse(raw)
    return typeof parsed === 'object' && parsed !== null ? (parsed as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

export function saveCache(values: Readonly<Record<string, unknown>>): void {
  const subset: Record<string, unknown> = {}
  for (const key of APPEARANCE_KEYS) {
    if (key in values) subset[key] = values[key]
  }
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(subset))
  } catch {
    // 存储不可用（隐私模式等）时忽略：仅失去首屏防闪烁。
  }
}

export function systemPrefersDark(): boolean {
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

export function resolveMode(preference: ThemePreference, systemDark: boolean): ThemeModeName {
  if (preference === 'system') return systemDark ? 'dark' : 'light'
  return preference
}

/** 把变量与模式写到 <html>。 */
export function applyToDocument(vars: Record<string, string>, mode: ThemeModeName, uiScalePercent: number): void {
  const root = document.documentElement
  for (const [name, value] of Object.entries(vars)) root.style.setProperty(name, value)
  root.dataset.theme = mode
  root.classList.toggle('dark', mode === 'dark')
  root.style.colorScheme = mode
  // rem 随界面缩放：Tailwind 的间距/字号（rem）与 token（px，已缩放）保持同一倍率。
  root.style.fontSize = `${(16 * uiScalePercent) / 100}px`
  const chrome = vars['--fx-colors-chrome']
  if (chrome) document.querySelector('meta[name="theme-color"]')?.setAttribute('content', chrome)
}

/** React 挂载前调用：用缓存偏好立即上色。 */
export function applyInitialTheme(): void {
  const prefs = parseAppearance(loadCache())
  const mode = resolveMode(prefs.themeMode, systemPrefersDark())
  applyToDocument(themeVariables(prefs, mode), mode, prefs.uiScalePercent)
  applyFontFamily(fontPreference.load(), document.documentElement.style)
}

// 主题提供者：外观偏好 → CSS 变量 + 明暗模式。偏好来自 agent（与 GPUI 同键），
// 首屏/登录页无快照时用 localStorage 缓存，避免闪烁。

import { useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { useAgentSnapshot, usePreferences } from '../lib/rpc/hooks'
import { applyToDocument, loadCache, resolveMode, saveCache, systemPrefersDark } from './apply'
import { parseAppearance } from './appearance'
import { ThemeContext } from './context'
import type { ThemeContextValue } from './context'
import { themeVariables } from './tokens'

export function ThemeProvider({ children }: { children: ReactNode }) {
  const ready = useAgentSnapshot() !== null
  const live = usePreferences()
  const [cached] = useState(loadCache)
  const values = ready ? live : cached
  const prefs = useMemo(() => parseAppearance(values), [values])

  const [systemDark, setSystemDark] = useState(systemPrefersDark)
  useEffect(() => {
    const query = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => setSystemDark(query.matches)
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])

  const mode = resolveMode(prefs.themeMode, systemDark)
  const vars = useMemo(() => themeVariables(prefs, mode), [prefs, mode])

  useEffect(() => {
    applyToDocument(vars, mode, prefs.uiScalePercent)
  }, [vars, mode, prefs.uiScalePercent])

  useEffect(() => {
    if (ready) saveCache(live)
  }, [ready, live])

  const value = useMemo<ThemeContextValue>(
    () => ({
      mode,
      preference: prefs.themeMode,
      prefs,
    }),
    [mode, prefs],
  )
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

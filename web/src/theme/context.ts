import { createContext, useContext } from 'react'
import type { AppearancePreferences, ThemeModeName, ThemePreference } from './appearance'

export interface ThemeContextValue {
  /** 生效的明暗模式（`system` 已解析）。 */
  mode: ThemeModeName
  preference: ThemePreference
  prefs: AppearancePreferences
}

export const ThemeContext = createContext<ThemeContextValue | null>(null)

export function useTheme(): ThemeContextValue {
  const value = useContext(ThemeContext)
  if (!value) throw new Error('useTheme must be used inside <ThemeProvider>')
  return value
}

// Browser-only preference: deliberately separate from agent preferences and theme caches.
export const FONT_FAMILY_STORAGE_KEY = 'fluxdown.web.fontFamily'
export const FONT_FAMILY_VARIABLE = '--fx-browser-font-sans'

export function normalizeFontFamily(value: string): string {
  return value.replaceAll('\0', '').trim()
}

// A family is one CSS string, never a comma-separated stack or executable CSS syntax.
export function quoteFontFamily(family: string): string {
  const escaped = Array.from(family, (character) => {
    const code = character.charCodeAt(0)
    return character === '"' || character === '\\' || code < 32 || code === 127
      ? `\\${code.toString(16)} ` : character
  }).join('')
  return `"${escaped}"`
}

export function applyFontFamily(family: string, style: Pick<CSSStyleDeclaration, 'setProperty' | 'removeProperty'>): void {
  const normalized = normalizeFontFamily(family)
  if (normalized) style.setProperty(FONT_FAMILY_VARIABLE, `${quoteFontFamily(normalized)}, var(--fx-typography-sans)`)
  else style.removeProperty(FONT_FAMILY_VARIABLE)
}

type FontStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export function createFontPreference(storage: () => FontStorage) {
  // Keep a session fallback when storage is blocked or full, including across page navigation.
  let current: string | undefined
  return {
    load(): string {
      if (current !== undefined) return current
      try {
        current = normalizeFontFamily(storage().getItem(FONT_FAMILY_STORAGE_KEY) ?? '')
      } catch {
        current = ''
      }
      return current
    },
    save(family: string): boolean {
      current = normalizeFontFamily(family)
      try {
        if (current) storage().setItem(FONT_FAMILY_STORAGE_KEY, current)
        else storage().removeItem(FONT_FAMILY_STORAGE_KEY)
        return true
      } catch {
        return false
      }
    },
  }
}

export const fontPreference = createFontPreference(() => localStorage)

export interface LocalFontBrowser {
  readonly isSecureContext: boolean
  queryLocalFonts?: () => Promise<readonly { family: string }[]>
}

export type FontListResult =
  | { status: 'ready'; families: string[] }
  | { status: 'unavailable' | 'denied' | 'failed' }

export function canQueryLocalFonts(browser: LocalFontBrowser): boolean {
  return browser.isSecureContext && typeof browser.queryLocalFonts === 'function'
}

export function localFontFamilies(fonts: readonly { family: string }[]): string[] {
  return [...new Set(fonts.map((font) => normalizeFontFamily(font.family)).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b) || (a < b ? -1 : a > b ? 1 : 0))
}

// Invoke directly inside the click handler: permission requests require transient user activation.
export async function queryFontFamilies(browser: LocalFontBrowser): Promise<FontListResult> {
  const query = browser.queryLocalFonts
  if (!browser.isSecureContext || typeof query !== 'function') return { status: 'unavailable' }
  try {
    const fonts = await query.call(browser)
    return { status: 'ready', families: localFontFamilies(fonts) }
  } catch (error) {
    const name = typeof error === 'object' && error !== null && 'name' in error ? error.name : ''
    return { status: name === 'NotAllowedError' || name === 'SecurityError' ? 'denied' : 'failed' }
  }
}

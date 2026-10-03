import { describe, expect, test } from 'bun:test'
import {
  FONT_FAMILY_STORAGE_KEY,
  FONT_FAMILY_VARIABLE,
  applyFontFamily,
  canQueryLocalFonts,
  createFontPreference,
  localFontFamilies,
  normalizeFontFamily,
  queryFontFamilies,
  quoteFontFamily,
} from './fontFamily'

function storageFixture() {
  const values = new Map<string, string>()
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { values.set(key, value) },
    removeItem: (key: string) => { values.delete(key) },
  }
  return { values, storage }
}

describe('browser-local font preference', () => {
  test('defaults to theme, persists only its own key, reloads and resets', () => {
    const { values, storage } = storageFixture()
    values.set('fluxdown.web.appearance', '{"theme":"dark"}')
    const preference = createFontPreference(() => storage)
    expect(preference.load()).toBe('')
    expect(preference.save('  Noto Sans CJK SC  ')).toBe(true)
    expect(preference.load()).toBe('Noto Sans CJK SC')
    expect(values.get(FONT_FAMILY_STORAGE_KEY)).toBe('Noto Sans CJK SC')
    expect(createFontPreference(() => storage).load()).toBe('Noto Sans CJK SC')
    expect(preference.save('')).toBe(true)
    expect(values.has(FONT_FAMILY_STORAGE_KEY)).toBe(false)
    expect(values.get('fluxdown.web.appearance')).toBe('{"theme":"dark"}')
  })

  test('blocked storage keeps an editable session preference and reports persistence failure', () => {
    const preference = createFontPreference(() => { throw new Error('blocked') })
    expect(preference.load()).toBe('')
    expect(preference.save('Example')).toBe(false)
    expect(preference.load()).toBe('Example')
    expect(preference.save('')).toBe(false)
    expect(preference.load()).toBe('')
  })

  test('quota failure does not resurrect the stale persisted choice during navigation', () => {
    const preference = createFontPreference(() => ({
      getItem: () => 'Old',
      setItem: () => { throw new Error('quota') },
      removeItem: () => { throw new Error('blocked') },
    }))
    expect(preference.load()).toBe('Old')
    expect(preference.save('New')).toBe(false)
    expect(preference.load()).toBe('New')
  })

  test('applies a separate CSS override and restores the current theme rather than an old stack', () => {
    const values = new Map<string, string>([['--fx-typography-sans', '"Theme A", sans-serif']])
    const style = {
      setProperty: (key: string, value: string | null) => { values.set(key, value ?? '') },
      removeProperty: (key: string) => { const previous = values.get(key) ?? ''; values.delete(key); return previous },
    }
    applyFontFamily('Local Font', style)
    expect(values.get(FONT_FAMILY_VARIABLE)).toBe('"Local Font", var(--fx-typography-sans)')
    style.setProperty('--fx-typography-sans', '"Theme B", sans-serif')
    expect(values.get(FONT_FAMILY_VARIABLE)).toBe('"Local Font", var(--fx-typography-sans)')
    applyFontFamily(' ', style)
    expect(values.has(FONT_FAMILY_VARIABLE)).toBe(false)
    expect(values.get('--fx-typography-sans')).toBe('"Theme B", sans-serif')
  })
})

describe('CSS font-family serialization', () => {
  test('quotes generic names, Unicode, commas and CSS-like input as a single literal family', () => {
    expect(quoteFontFamily('serif')).toBe('"serif"')
    expect(quoteFontFamily('微软雅黑')).toBe('"微软雅黑"')
    expect(quoteFontFamily('One, Two')).toBe('"One, Two"')
    expect(quoteFontFamily('var(--evil); color: red')).toBe('"var(--evil); color: red"')
    expect(quoteFontFamily('A"; } body { color: red; } /*')).toBe('"A\\22 ; } body { color: red; } /*"')
  })

  test('escapes quotes, backslashes and controls with terminated hex escapes', () => {
    expect(quoteFontFamily('A"B\\C\nD\rE\fF\t0\x7f')).toBe('"A\\22 B\\5c C\\a D\\d E\\c F\\9 0\\7f "')
    expect(normalizeFontFamily('  A\0B  ')).toBe('AB')
    expect(quoteFontFamily('\0')).toBe('"\\0 "')
  })
})

describe('Local Font Access', () => {
  test('extracts family (not fullName/PostScript/style), deduplicates and sorts', () => {
    const fonts = [
      { family: 'Zulu', fullName: 'Zulu Bold' },
      { family: 'Alpha', fullName: 'Alpha Regular' },
      { family: 'Zulu', fullName: 'Zulu Regular' },
      { family: ' Alpha ', fullName: 'Alpha Italic' },
      { family: ' ' },
    ]
    expect(localFontFamilies(fonts)).toEqual(['Alpha', 'Zulu'])
  })

  test('capability check never prompts; explicit query calls immediately with window receiver', async () => {
    let calls = 0
    const browser = {
      isSecureContext: true,
      async queryLocalFonts() {
        expect(this).toBe(browser)
        calls += 1
        return [{ family: 'Zulu' }, { family: 'Alpha' }, { family: 'Alpha' }]
      },
    }
    expect(canQueryLocalFonts(browser)).toBe(true)
    expect(calls).toBe(0)
    const pending = queryFontFamilies(browser)
    expect(calls).toBe(1)
    expect(await pending).toEqual({ status: 'ready', families: ['Alpha', 'Zulu'] })
  })

  test('insecure context and unsupported browsers never invoke the API', async () => {
    let calls = 0
    expect(await queryFontFamilies({ isSecureContext: false, queryLocalFonts: async () => { calls += 1; return [] } })).toEqual({ status: 'unavailable' })
    expect(await queryFontFamilies({ isSecureContext: true })).toEqual({ status: 'unavailable' })
    expect(calls).toBe(0)
  })

  for (const name of ['NotAllowedError', 'SecurityError']) {
    test(`classifies permission/policy error ${name}`, async () => {
      expect(await queryFontFamilies({ isSecureContext: true, queryLocalFonts: async () => { throw { name } } })).toEqual({ status: 'denied' })
    })
  }

  test('classifies synchronous and asynchronous failures without pretending to enumerate fallback fonts', async () => {
    expect(await queryFontFamilies({ isSecureContext: true, queryLocalFonts: () => { throw new Error('failure') } })).toEqual({ status: 'failed' })
    expect(await queryFontFamilies({ isSecureContext: true, queryLocalFonts: async () => { throw 'failure' } })).toEqual({ status: 'failed' })
    expect(await queryFontFamilies({ isSecureContext: true, queryLocalFonts: async () => [] })).toEqual({ status: 'ready', families: [] })
  })
})

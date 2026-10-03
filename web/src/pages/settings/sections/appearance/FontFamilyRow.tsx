import { useRef, useState } from 'react'
import { useT } from '../../../../i18n'
import { applyFontFamily, canQueryLocalFonts, fontPreference, localFontFamilies, queryFontFamilies } from '../../../../theme/fontFamily'
import type { FontListResult } from '../../../../theme/fontFamily'
import { Button } from '../../../../ui'
import { DropdownField, SettingsRow, TextField } from '../../kit'

export function FontFamilyRow() {
  const t = useT()
  const [family, setFamily] = useState(() => fontPreference.load())
  const [result, setResult] = useState<FontListResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [saveFailed, setSaveFailed] = useState(false)
  const querying = useRef(false)
  const available = canQueryLocalFonts(window)
  const families = result?.status === 'ready' ? result.families : []
  // Keep a saved/manual selection visible even before permission is granted or after a failed refresh.
  const options = [
    { value: 'default', label: t('fontFamilyDefault') },
    ...localFontFamilies([family, ...families].map((name) => ({ family: name })))
      .map((name) => ({ value: `family:${name}`, label: name })),
  ]
  const statusKey = !available ? 'fontFamilyUnavailable'
    : loading ? 'fontFamilyLoading'
      : result?.status === 'denied' ? 'fontFamilyDenied'
        : result?.status === 'failed' ? 'fontFamilyLoadFailed'
          : result?.status === 'unavailable' ? 'fontFamilyUnavailable'
            : result?.status === 'ready' && families.length === 0 ? 'fontFamilyEmpty' : null

  function select(next: string) {
    setSaveFailed(!fontPreference.save(next))
    const saved = fontPreference.load()
    setFamily(saved)
    applyFontFamily(saved, document.documentElement.style)
  }

  async function refresh() {
    if (querying.current) return
    querying.current = true
    setLoading(true)
    try {
      setResult(await queryFontFamilies(window))
    } finally {
      querying.current = false
      setLoading(false)
    }
  }

  return (
    <SettingsRow title={t('fontFamily')} description={t('fontFamilyHint')} vertical>
      <div className="flex w-full flex-col gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <DropdownField
            value={family ? `family:${family}` : 'default'}
            options={options}
            aria-label={t('fontFamily')}
            onValueChange={(value) => select(value === 'default' ? '' : value.slice('family:'.length))}
          />
          <Button disabled={!available} loading={loading} onClick={refresh}>
            {t(loading ? 'fontFamilyLoading' : 'fontFamilyRefresh')}
          </Button>
          <Button disabled={!family} onClick={() => select('')}>{t('fontFamilyDefault')}</Button>
        </div>
        <label className="flex flex-col gap-1 text-xs text-muted-foreground">
          <span>{t('fontFamilyManual')}</span>
          <TextField value={family} onCommit={select} aria-label={t('fontFamilyManual')} placeholder={t('fontFamilyDefault')} />
        </label>
        <div role="status" aria-live="polite" className="text-xs text-muted-foreground">
          {statusKey ? t(statusKey) : null}
        </div>
        {saveFailed ? <p role="alert" className="text-xs text-destructive">{t('fontFamilySaveFailed')}</p> : null}
      </div>
    </SettingsRow>
  )
}

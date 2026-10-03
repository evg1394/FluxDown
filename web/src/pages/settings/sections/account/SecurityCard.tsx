// 账号与安全：邮箱行 + 修改邮箱（/me/email 原邮箱验证码 → 新邮箱验证码）。

import { useCallback, useEffect, useRef, useState } from 'react'
import { useT } from '../../../../i18n'
import { rpc } from '../../../../lib/rpc'
import type { AgentSessionDto } from '../../../../lib/rpc'
import { Button, Card, ConfirmFooter, Dialog, FieldError, FieldHint, Form, FormField, Input, toast } from '../../../../ui'
import { accountErrorKey } from './errorText'
import { useCountdown } from './useCountdown'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function EmailChangeDialog({ current, onClose }: { current: string; onClose: () => void }) {
  const t = useT()
  const [oldCode, setOldCode] = useState('')
  const [newEmail, setNewEmail] = useState('')
  const [newCode, setNewCode] = useState('')
  const [step, setStep] = useState<'old' | 'new'>('old')
  const [busy, setBusy] = useState(false)
  const [errorKey, setErrorKey] = useState<string | null>(null)
  const oldCountdown = useCountdown()
  const startOldCountdown = oldCountdown.start
  const newCountdown = useCountdown()
  const [oldTtl, setOldTtl] = useState(0)
  const [newTtl, setNewTtl] = useState(0)
  const [sentEmail, setSentEmail] = useState<string | null>(null)
  const sent = useRef(false)
  const active = useRef(true)
  const pending = useRef(false)
  const oldDeadline = useRef(0)
  const newDeadline = useRef(0)
  useEffect(() => {
    active.current = true
    return () => { active.current = false }
  }, [])


  const email = newEmail.trim()
  const emailError = email === '' ? undefined : !EMAIL_PATTERN.test(email) ? t('accountEmailChangeInvalid') : email.toLowerCase() === current.toLowerCase() ? t('accountEmailChangeSame') : undefined
  const oldCooldown = Math.max(0, oldCountdown.remaining - Math.max(0, oldTtl - 60))
  const newCooldown = sentEmail === email ? Math.max(0, newCountdown.remaining - Math.max(0, newTtl - 60)) : 0

  const guarded = useCallback(async (action: () => Promise<void>) => {
    if (pending.current || !active.current) return
    pending.current = true
    setBusy(true)
    setErrorKey(null)
    try {
      await action()
    } catch (error) {
      if (active.current) setErrorKey(accountErrorKey(error, 'code'))
    } finally {
      pending.current = false
      if (active.current) setBusy(false)
    }
  }, [])
  const close = () => { if (!pending.current) onClose() }
  const sendOldCode = useCallback(() => {
    if (Date.now() < oldDeadline.current) return
    return guarded(async () => {
      const result = await rpc.agent.profile.sendEmailCode()
      if (!active.current) return
      oldDeadline.current = Date.now() + Math.min(60, result.ttlSeconds) * 1000
      setOldTtl(result.ttlSeconds)
      startOldCountdown(result.ttlSeconds)
      setOldCode('')
      setNewCode('')
    })
  }, [guarded, startOldCountdown])

  useEffect(() => {
    if (sent.current) return
    sent.current = true
    void sendOldCode()
  }, [sendOldCode])

  const sendNewCode = () => {
    if (!canNext || (sentEmail === email && Date.now() < newDeadline.current)) return
    return guarded(async () => {
      const result = await rpc.agent.profile.sendNewEmailCode({ email, code: oldCode.trim() })
      if (!active.current) return
      newDeadline.current = Date.now() + Math.min(60, result.ttlSeconds) * 1000
      setNewTtl(result.ttlSeconds)
      setSentEmail(email)
      newCountdown.start(result.ttlSeconds)
      setNewCode('')
      setStep('new')
    })
  }

  const confirm = () => {
    if (!canConfirm) return
    return guarded(async () => {
      await rpc.agent.profile.changeEmail({ email, oldCode: oldCode.trim(), newCode: newCode.trim() })
      if (!active.current) return
      toast.key('accountEmailChangeSuccess', 'success')
      onClose()
    })
  }

  const canNext = oldCountdown.remaining > 0 && oldCode.trim() !== '' && email !== '' && emailError === undefined
  const canConfirm = canNext && sentEmail === email && newCountdown.remaining > 0 && newCode.trim() !== ''

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && close()}
      title={t('accountEmailChangeTitle')}
      modalLocked={busy}
      footer={
        <ConfirmFooter
          okLabel={step === 'old' ? t('accountEmailChangeSendNewCode') : t('confirm')}
          cancelLabel={busy ? null : t('cancel')}
          onCancel={close}
          onOk={() => void (step === 'old' ? sendNewCode() : confirm())}
          okDisabled={step === 'old' ? !canNext || newCooldown > 0 : !canConfirm}
          loading={busy}
        />
      }
    >
      <Form onSubmit={() => void (step === 'old' ? canNext && sendNewCode() : canConfirm && confirm())}>
        {step === 'old' ? (
          <>
            <FieldHint>{oldTtl > 0 ? t('accountEmailChangeOldSubtitle', { email: current }) : t('accountEmailChangeOldCodeHint')}</FieldHint>
            <FormField label={t('accountEmailChangeOldCodePlaceholder')} htmlFor="account-email-old-code" hint={t('accountEmailChangeOldCodeHint')}>
              <Input id="account-email-old-code" value={oldCode} disabled={busy} onChange={(event) => setOldCode(event.target.value)} inputMode="numeric" autoComplete="one-time-code" autoFocus />
            </FormField>
            <FormField label={t('accountEmailChangeNewPlaceholder')} htmlFor="account-email-new" {...(emailError ? { error: emailError } : {})}>
              <Input id="account-email-new" type="email" value={newEmail} disabled={busy} onChange={(event) => setNewEmail(event.target.value)} invalid={emailError !== undefined} autoCapitalize="none" />
            </FormField>
            <div className="text-xs text-muted-foreground tabular">{oldTtl > 0 ? oldCountdown.remaining > 0 ? t('accountCodeExpireIn', { seconds: oldCountdown.remaining }) : t('accountCodeExpired') : ''}</div>
            <Button disabled={busy || oldCooldown > 0} onClick={() => void sendOldCode()}>
              {oldCooldown > 0 ? t('accountResendCodeIn', { seconds: oldCooldown }) : t('accountResendCode')}
            </Button>
          </>
        ) : (
          <>
            <FieldHint>{t('accountEmailChangeCodeSubtitle', { email })}</FieldHint>
            <FormField label={t('accountFieldCode')} htmlFor="account-email-new-code">
              <Input id="account-email-new-code" value={newCode} disabled={busy} onChange={(event) => setNewCode(event.target.value)} placeholder={t('accountCodePlaceholder')} inputMode="numeric" autoComplete="one-time-code" autoFocus />
            </FormField>
            <div className="text-xs text-muted-foreground tabular">{oldCountdown.remaining > 0 && newCountdown.remaining > 0 ? t('accountCodeExpireIn', { seconds: Math.min(oldCountdown.remaining, newCountdown.remaining) }) : t('accountCodeExpired')}</div>
            <div className="flex items-center gap-2">
              <Button disabled={busy || !canNext || newCooldown > 0} onClick={() => void sendNewCode()}>
                {newCooldown > 0 ? t('accountResendCodeIn', { seconds: newCooldown }) : t('accountResendCode')}
              </Button>
              <Button disabled={busy} onClick={() => {
                if (pending.current) return
                setStep('old')
                setNewCode('')
                setErrorKey(null)
              }}>{t('back')}</Button>
            </div>
          </>
        )}
        {errorKey ? <FieldError>{t(errorKey)}</FieldError> : null}
        <button type="submit" className="hidden" />
      </Form>
    </Dialog>
  )
}

export function SecurityCard({ session, disabled }: { session: AgentSessionDto; disabled: boolean }) {
  const t = useT()
  const [editingUser, setEditingUser] = useState<string | null>(null)
  useEffect(() => {
    if (disabled || editingUser !== session.user.id) setEditingUser(null)
  }, [disabled, editingUser, session.user.id])
  return (
    <section className="flex flex-col gap-1">
      <div className="flex flex-col gap-0.5">
        <div className="text-sm font-medium text-foreground">{t('accountSecurityGroup')}</div>
        <div className="text-xs text-muted-foreground">{t('accountSecurityGroupDesc')}</div>
      </div>
      <Card className="mt-1 flex w-full flex-wrap items-center justify-between gap-x-3 gap-y-2 p-3">
        <div className="text-sm font-medium text-foreground">{t('accountEmailPlaceholder')}</div>
        <div className="flex min-w-0 items-center gap-2">
          <span className="min-w-0 truncate text-sm text-muted-foreground">{session.user.email}</span>
          <Button disabled={disabled} onClick={() => setEditingUser(session.user.id)}>
            {t('accountEmailChangeTitle')}
          </Button>
        </div>
      </Card>
      {!disabled && editingUser === session.user.id ? <EmailChangeDialog key={session.user.id} current={session.user.email} onClose={() => setEditingUser(null)} /> : null}
    </section>
  )
}


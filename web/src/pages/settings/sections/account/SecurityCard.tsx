// 账号与安全：邮箱行 + 修改邮箱（/me/email 原邮箱验证码 → 新邮箱验证码）。

import { useEffect, useRef, useState } from 'react'
import { useT } from '../../../../i18n'
import { rpc } from '../../../../lib/rpc'
import type { AgentSessionDto } from '../../../../lib/rpc'
import { Button, Card, ConfirmFooter, Dialog, FieldError, FieldHint, Form, FormField, Input, toast } from '../../../../ui'
import { ChangePasswordDialog } from './PasswordDialogs'
import { emailOldCodeKey, forgetCode, recallCode, rememberCode } from './sentCodes'
import { useCodeTimer, useGuarded } from './useCodeForm'
import type { GuardedRunner } from './useCodeForm'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** 修改邮箱：原邮箱验证码 → 新邮箱验证码。发码（sending）只让发码按钮转圈；提交（submitting）才锁表单。 */
function EmailChangeDialog({ userId, current, onClose }: { userId: string; current: string; onClose: () => void }) {
  const t = useT()
  const [oldCode, setOldCode] = useState('')
  const [newEmail, setNewEmail] = useState('')
  const [newCode, setNewCode] = useState('')
  const [step, setStep] = useState<'old' | 'new'>('old')
  const [sentEmail, setSentEmail] = useState<string | null>(null)
  const { sending, submitting, errorKey, setErrorKey, send, submit, active, submitPending } = useGuarded()
  const oldTimer = useCodeTimer()
  const newTimer = useCodeTimer()
  const restoreOld = oldTimer.restore
  const beginOld = oldTimer.begin
  const oldKey = emailOldCodeKey(userId)
  const started = useRef(false)

  const email = newEmail.trim()
  const emailError = email === '' ? undefined : !EMAIL_PATTERN.test(email) ? t('accountEmailChangeInvalid') : email.toLowerCase() === current.toLowerCase() ? t('accountEmailChangeSame') : undefined
  const newCooldown = sentEmail === email ? newTimer.cooldown : 0
  const close = () => { if (!submitPending.current) onClose() }

  const sendOldCode = () => {
    if (oldTimer.cooldown > 0) return
    return send(async () => {
      const result = await rpc.agent.profile.sendEmailCode()
      rememberCode(oldKey, result.ttlSeconds)
      if (!active.current) return
      beginOld(result.ttlSeconds)
      setOldCode('')
      setNewCode('')
    })
  }

  // 打开时：此前发出的原邮箱验证码仍在有效期 / 冷却内则恢复倒计时，否则自动发码。
  useEffect(() => {
    if (started.current) return
    started.current = true
    const record = recallCode(oldKey)
    if (record) {
      restoreOld(record)
      return
    }
    void send(async () => {
      const result = await rpc.agent.profile.sendEmailCode()
      rememberCode(oldKey, result.ttlSeconds)
      if (!active.current) return
      beginOld(result.ttlSeconds)
    })
  }, [oldKey, restoreOld, beginOld, send, active])

  const canNext = oldTimer.remaining > 0 && oldCode.trim() !== '' && email !== '' && emailError === undefined
  const canConfirm = canNext && sentEmail === email && newTimer.remaining > 0 && newCode.trim() !== ''

  /** 向新邮箱发码；第一步的主按钮走 submit（锁表单），第二步的「重新发送」走 send（只转圈）。 */
  const sendNewCode = (runner: GuardedRunner['send']) => {
    if (!canNext || newCooldown > 0) return
    const target = email
    return runner(async () => {
      const result = await rpc.agent.profile.sendNewEmailCode({ email: target, code: oldCode.trim() })
      if (!active.current) return
      newTimer.begin(result.ttlSeconds)
      setSentEmail(target)
      setNewCode('')
      setStep('new')
    })
  }

  const confirm = () => {
    if (!canConfirm) return
    return submit(async () => {
      await rpc.agent.profile.changeEmail({ email, oldCode: oldCode.trim(), newCode: newCode.trim() })
      forgetCode(oldKey)
      if (!active.current) return
      toast.key('accountEmailChangeSuccess', 'success')
      onClose()
    })
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && close()}
      title={t('accountEmailChangeTitle')}
      modalLocked={submitting}
      persistent
      footer={
        <ConfirmFooter
          okLabel={step === 'old' ? t('accountEmailChangeSendNewCode') : t('confirm')}
          cancelLabel={submitting ? null : t('cancel')}
          onCancel={close}
          onOk={() => void (step === 'old' ? sendNewCode(submit) : confirm())}
          okDisabled={step === 'old' ? !canNext || newCooldown > 0 || sending : !canConfirm || sending}
          loading={submitting}
        />
      }
    >
      <Form onSubmit={() => void (step === 'old' ? canNext && sendNewCode(submit) : canConfirm && confirm())}>
        {step === 'old' ? (
          <>
            <FieldHint>{oldTimer.sent ? t('accountEmailChangeOldSubtitle', { email: current }) : t('accountEmailChangeOldCodeHint')}</FieldHint>
            <FormField label={t('accountEmailChangeOldCodePlaceholder')} htmlFor="account-email-old-code" hint={t('accountEmailChangeOldCodeHint')}>
              <Input id="account-email-old-code" value={oldCode} disabled={submitting} onChange={(event) => setOldCode(event.target.value)} inputMode="numeric" autoComplete="one-time-code" autoFocus />
            </FormField>
            <FormField label={t('accountEmailChangeNewPlaceholder')} htmlFor="account-email-new" {...(emailError ? { error: emailError } : {})}>
              <Input id="account-email-new" type="email" value={newEmail} disabled={submitting} onChange={(event) => setNewEmail(event.target.value)} invalid={emailError !== undefined} autoCapitalize="none" />
            </FormField>
            <div className="text-xs text-muted-foreground tabular">{oldTimer.sent ? oldTimer.remaining > 0 ? t('accountCodeExpireIn', { seconds: oldTimer.remaining }) : t('accountCodeExpired') : ''}</div>
            <Button loading={sending} disabled={submitting || oldTimer.cooldown > 0} onClick={() => void sendOldCode()}>
              {oldTimer.cooldown > 0 ? t('accountResendCodeIn', { seconds: oldTimer.cooldown }) : t('accountResendCode')}
            </Button>
          </>
        ) : (
          <>
            <FieldHint>{t('accountEmailChangeCodeSubtitle', { email: sentEmail ?? email })}</FieldHint>
            <FormField label={t('accountFieldCode')} htmlFor="account-email-new-code">
              <Input id="account-email-new-code" value={newCode} disabled={submitting} onChange={(event) => setNewCode(event.target.value)} placeholder={t('accountCodePlaceholder')} inputMode="numeric" autoComplete="one-time-code" autoFocus />
            </FormField>
            <div className="text-xs text-muted-foreground tabular">{oldTimer.remaining > 0 && newTimer.remaining > 0 ? t('accountCodeExpireIn', { seconds: Math.min(oldTimer.remaining, newTimer.remaining) }) : t('accountCodeExpired')}</div>
            <div className="flex items-center gap-2">
              <Button loading={sending} disabled={submitting || !canNext || newCooldown > 0} onClick={() => void sendNewCode(send)}>
                {newCooldown > 0 ? t('accountResendCodeIn', { seconds: newCooldown }) : t('accountResendCode')}
              </Button>
              <Button disabled={submitting || sending} onClick={() => {
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
  const [editingPassword, setEditingPassword] = useState<string | null>(null)
  useEffect(() => {
    if (disabled || editingPassword !== session.user.id) setEditingPassword(null)
  }, [disabled, editingPassword, session.user.id])
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
      <Card className="mt-1 flex w-full flex-wrap items-center justify-between gap-x-3 gap-y-2 p-3">
        <div className="text-sm font-medium text-foreground">{t('accountPasswordTitle')}</div>
        <div className="flex min-w-0 items-center gap-2">
          <span className="min-w-0 truncate text-sm text-muted-foreground">
            {session.user.hasPassword === false ? t('accountPasswordStatusNotSet') : t('accountPasswordStatusSet')}
          </span>
          <Button disabled={disabled} onClick={() => setEditingPassword(session.user.id)}>
            {session.user.hasPassword === false ? t('accountPasswordSetTitle') : t('accountPasswordChangeTitle')}
          </Button>
        </div>
      </Card>
      {!disabled && editingUser === session.user.id ? <EmailChangeDialog key={session.user.id} userId={session.user.id} current={session.user.email} onClose={() => setEditingUser(null)} /> : null}
      {!disabled && editingPassword === session.user.id ? (
        <ChangePasswordDialog key={session.user.id} userId={session.user.id} hasPassword={session.user.hasPassword} email={session.user.email} onClose={() => setEditingPassword(null)} />
      ) : null}
    </section>
  )
}


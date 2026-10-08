// 修改 / 设置密码（已登录）与重置密码（未登录）对话框。校验规则见 `passwordRules.ts`。
// 发码（sending）只让发码按钮转圈；提交（submitting）才锁整个表单。点遮罩不关闭。

import { useEffect, useRef, useState } from 'react'
import { useT } from '../../../../i18n'
import { rpc } from '../../../../lib/rpc'
import { Button, ConfirmFooter, Dialog, FieldError, FieldHint, Form, FormField, Input, toast } from '../../../../ui'
import { newPasswordErrorKey } from './passwordRules'
import { PasswordInput } from './PasswordInput'
import { forgetCode, passwordCodeKey, recallCode, rememberCode } from './sentCodes'
import { useCodeTimer, useGuarded } from './useCodeForm'
import type { CodeTimer } from './useCodeForm'

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function NewPasswordFields({
  idPrefix,
  newPassword,
  confirm,
  onNewPassword,
  onConfirm,
  disabled,
  ruleKey,
}: {
  idPrefix: string
  newPassword: string
  confirm: string
  onNewPassword: (value: string) => void
  onConfirm: (value: string) => void
  disabled: boolean
  ruleKey: string | null
}) {
  const t = useT()
  const showRule = ruleKey !== null && newPassword !== '' && (confirm !== '' || ruleKey === 'accountErrorPasswordTooShort')
  return (
    <>
      <FormField label={t('accountPasswordNewPlaceholder')} htmlFor={`${idPrefix}-new`} hint={t('accountPasswordHint')}>
        <PasswordInput id={`${idPrefix}-new`} value={newPassword} disabled={disabled} onChange={(event) => onNewPassword(event.target.value)} placeholder={t('accountPasswordNewPlaceholder')} autoComplete="new-password" />
      </FormField>
      <FormField label={t('accountPasswordConfirmPlaceholder')} htmlFor={`${idPrefix}-confirm`}>
        <PasswordInput id={`${idPrefix}-confirm`} value={confirm} disabled={disabled} onChange={(event) => onConfirm(event.target.value)} placeholder={t('accountPasswordConfirmPlaceholder')} autoComplete="new-password" />
      </FormField>
      {showRule ? <FieldError>{t(ruleKey)}</FieldError> : null}
    </>
  )
}

/** 验证码剩余有效期 + 发送 / 重新发送按钮（sending 时转圈）。 */
function CodeRow({ timer, sending, disabled, onSend }: { timer: CodeTimer; sending: boolean; disabled: boolean; onSend: () => void }) {
  const t = useT()
  return (
    <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
      <span className="tabular">{timer.sent ? timer.remaining > 0 ? t('accountCodeExpireIn', { seconds: timer.remaining }) : t('accountCodeExpired') : ''}</span>
      <Button loading={sending} disabled={disabled || timer.cooldown > 0} onClick={onSend}>
        {timer.cooldown > 0 ? t('accountResendCodeIn', { seconds: timer.cooldown }) : timer.sent ? t('accountResendCode') : t('accountSendCode')}
      </Button>
    </div>
  )
}

type ChangeMode = 'password' | 'code'

/** 修改密码；`hasPassword === false` 时只有验证码模式（设置密码）。`null`（旧云端未知）按已设置处理。 */
export function ChangePasswordDialog({ userId, hasPassword, email, onClose }: { userId: string; hasPassword: boolean | null; email: string; onClose: () => void }) {
  const t = useT()
  const canUsePassword = hasPassword !== false
  const [mode, setMode] = useState<ChangeMode>(canUsePassword ? 'password' : 'code')
  const [current, setCurrent] = useState('')
  const [code, setCode] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const { sending, submitting, errorKey, setErrorKey, send, submit, active, submitPending } = useGuarded()
  const timer = useCodeTimer()
  const restore = timer.restore
  const key = passwordCodeKey(userId)

  // 重开对话框时恢复此前发出的验证码倒计时。
  const restored = useRef(false)
  useEffect(() => {
    if (restored.current) return
    restored.current = true
    const record = recallCode(key)
    if (record) restore(record)
  }, [key, restore])

  const byCode = mode === 'code'
  const ruleKey = newPasswordErrorKey(newPassword, confirm, byCode ? null : current)
  const canConfirm = !sending && ruleKey === null && (byCode ? timer.remaining > 0 && code.trim() !== '' : current !== '')
  const close = () => { if (!submitPending.current) onClose() }

  const sendCode = () => {
    if (timer.cooldown > 0) return
    return send(async () => {
      const result = await rpc.agent.profile.sendPasswordCode()
      rememberCode(key, result.ttlSeconds)
      if (!active.current) return
      timer.begin(result.ttlSeconds)
      setCode('')
    })
  }

  const confirmChange = () => {
    if (!canConfirm) return
    return submit(async () => {
      await rpc.agent.profile.changePassword(byCode ? { newPassword, code: code.trim() } : { newPassword, currentPassword: current })
      forgetCode(key)
      if (!active.current) return
      toast.key('accountPasswordChangeSuccess', 'success')
      onClose()
    })
  }

  const switchMode = (next: ChangeMode) => {
    if (submitPending.current) return
    setMode(next)
    setErrorKey(null)
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && close()}
      title={canUsePassword ? t('accountPasswordChangeTitle') : t('accountPasswordSetTitle')}
      modalLocked={submitting}
      persistent
      footer={
        <ConfirmFooter
          okLabel={t('confirm')}
          cancelLabel={submitting ? null : t('cancel')}
          onCancel={close}
          onOk={() => void confirmChange()}
          okDisabled={!canConfirm}
          loading={submitting}
        />
      }
    >
      <Form onSubmit={() => void confirmChange()}>
        {byCode ? (
          <>
            <FieldHint>{timer.sent ? t('accountPasswordCodeSubtitle', { email }) : t('accountPasswordCodeHint', { email })}</FieldHint>
            <FormField label={t('accountFieldCode')} htmlFor="account-password-code">
              <Input id="account-password-code" value={code} disabled={submitting} onChange={(event) => setCode(event.target.value)} placeholder={t('accountCodePlaceholder')} inputMode="numeric" autoComplete="one-time-code" autoFocus />
            </FormField>
            <CodeRow timer={timer} sending={sending} disabled={submitting} onSend={() => void sendCode()} />
          </>
        ) : (
          <FormField label={t('accountPasswordCurrentPlaceholder')} htmlFor="account-password-current">
            <PasswordInput id="account-password-current" value={current} disabled={submitting} onChange={(event) => setCurrent(event.target.value)} placeholder={t('accountPasswordCurrentPlaceholder')} autoComplete="current-password" autoFocus />
          </FormField>
        )}
        <NewPasswordFields idPrefix="account-password" newPassword={newPassword} confirm={confirm} onNewPassword={setNewPassword} onConfirm={setConfirm} disabled={submitting} ruleKey={ruleKey} />
        {errorKey ? <FieldError>{t(errorKey)}</FieldError> : null}
        {byCode ? (
          canUsePassword ? (
            <button type="button" className="self-start text-xs text-accent-text disabled:opacity-50 coarse:min-h-touch" disabled={submitting} onClick={() => switchMode('password')}>
              {t('accountPasswordUseCurrentPassword')}
            </button>
          ) : null
        ) : (
          <button type="button" className="self-start text-xs text-accent-text disabled:opacity-50 coarse:min-h-touch" disabled={submitting} onClick={() => switchMode('code')}>
            {t('accountPasswordUseEmailCode')}
          </button>
        )}
        <button type="submit" className="hidden" />
      </Form>
    </Dialog>
  )
}

/** 重置密码（未登录）：成功后 `onDone(email)`，由登录对话框回到密码页签。 */
export function ResetPasswordDialog({ initialEmail, onClose, onDone }: { initialEmail: string; onClose: () => void; onDone: (email: string) => void }) {
  const t = useT()
  const [emailInput, setEmailInput] = useState(initialEmail)
  const [code, setCode] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [sentEmail, setSentEmail] = useState<string | null>(null)
  const { sending, submitting, errorKey, send, submit, active, submitPending } = useGuarded()
  const timer = useCodeTimer()

  const email = emailInput.trim()
  const emailValid = EMAIL_PATTERN.test(email)
  const emailError = email !== '' && !emailValid ? t('accountErrorInvalidEmail') : undefined
  const cooldown = sentEmail === email ? timer.cooldown : 0
  const ruleKey = newPasswordErrorKey(newPassword, confirm, null)
  const canConfirm = !sending && emailValid && sentEmail === email && timer.remaining > 0 && code.trim() !== '' && ruleKey === null
  const close = () => { if (!submitPending.current) onClose() }

  const sendCode = () => {
    if (!emailValid || cooldown > 0) return
    // 以请求时的邮箱为准，发码在途时邮箱框已锁定，回读输入框也不会变。
    const target = email
    return send(async () => {
      const result = await rpc.agent.auth.sendPasswordResetCode({ email: target })
      if (!active.current) return
      timer.begin(result.ttlSeconds)
      setSentEmail(target)
      setCode('')
    })
  }

  const confirmReset = () => {
    if (!canConfirm) return
    return submit(async () => {
      await rpc.agent.auth.resetPassword({ email, code: code.trim(), newPassword })
      if (!active.current) return
      toast.key('accountPasswordResetSuccess', 'success')
      onDone(email)
    })
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && close()}
      title={t('accountPasswordResetTitle')}
      modalLocked={submitting}
      persistent
      footer={
        <ConfirmFooter
          okLabel={t('confirm')}
          cancelLabel={submitting ? null : t('cancel')}
          onCancel={close}
          onOk={() => void confirmReset()}
          okDisabled={!canConfirm}
          loading={submitting}
        />
      }
    >
      <Form onSubmit={() => void confirmReset()}>
        <FieldHint>{t('accountPasswordResetEmailHint')}</FieldHint>
        <FormField label={t('accountEmailPlaceholder')} htmlFor="account-reset-email" {...(emailError ? { error: emailError } : {})}>
          <Input id="account-reset-email" type="email" value={emailInput} disabled={submitting || sending} onChange={(event) => setEmailInput(event.target.value)} invalid={emailError !== undefined} placeholder={t('accountEmailPlaceholder')} autoComplete="username" autoCapitalize="none" autoFocus />
        </FormField>
        <FormField label={t('accountFieldCode')} htmlFor="account-reset-code">
          <Input id="account-reset-code" value={code} disabled={submitting} onChange={(event) => setCode(event.target.value)} placeholder={t('accountCodePlaceholder')} inputMode="numeric" autoComplete="one-time-code" />
        </FormField>
        <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
          <span className="tabular">{sentEmail === email ? timer.remaining > 0 ? t('accountCodeExpireIn', { seconds: timer.remaining }) : t('accountCodeExpired') : ''}</span>
          <Button loading={sending} disabled={submitting || !emailValid || cooldown > 0} onClick={() => void sendCode()}>
            {cooldown > 0 ? t('accountResendCodeIn', { seconds: cooldown }) : sentEmail === email ? t('accountResendCode') : t('accountSendCode')}
          </Button>
        </div>
        <NewPasswordFields idPrefix="account-reset" newPassword={newPassword} confirm={confirm} onNewPassword={setNewPassword} onConfirm={setConfirm} disabled={submitting} ruleKey={ruleKey} />
        {errorKey ? <FieldError>{t(errorKey)}</FieldError> : null}
        <button type="submit" className="hidden" />
      </Form>
    </Dialog>
  )
}

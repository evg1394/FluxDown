// 登录 / 注册对话框（GPUI `crates/account/src/dialogs/{login,register}.rs`）：
// 第一步账号+密码；服务端返回 `deviceVerificationRequired` 后换成验证码步骤再提交。

import { useState } from 'react'
import { useT } from '../../../../i18n'
import { rpc } from '../../../../lib/rpc'
import type { AgentLoginResult } from '../../../../lib/rpc'
import { ConfirmFooter, Dialog, FieldError, FieldHint, Form, FormField, Input, SegmentedTabs } from '../../../../ui'
import { accountErrorKey } from './errorText'
import type { AccountErrorContext } from './errorText'
import { ResetPasswordDialog } from './PasswordDialogs'
import { PasswordInput } from './PasswordInput'
import { useCountdown } from './useCountdown'

function VerifyStep({
  title,
  subtitle,
  notice,
  code,
  onCode,
  remaining,
  onResend,
  resendIn = 0,
  busy,
}: {
  title: string
  subtitle: string
  notice?: string
  code: string
  onCode: (value: string) => void
  remaining: number
  onResend?: () => void
  /** 重发冷却剩余秒数；>0 时按钮禁用并显示倒计时。 */
  resendIn?: number
  busy: boolean
}) {
  const t = useT()
  return (
    <>
      <div className="flex flex-col gap-0.5">
        <div className="text-sm font-medium text-foreground">{title}</div>
        <FieldHint>{subtitle}</FieldHint>
        {notice ? <FieldHint className="text-warning">{notice}</FieldHint> : null}
      </div>
      <FormField label={t('accountFieldCode')} htmlFor="account-code">
        <Input
          id="account-code"
          value={code}
          onChange={(event) => onCode(event.target.value)}
          placeholder={t('accountCodePlaceholder')}
          inputMode="numeric"
          autoComplete="one-time-code"
          autoFocus
        />
      </FormField>
      <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
        <span className="tabular">{remaining > 0 ? t('accountCodeExpireIn', { seconds: remaining }) : ''}</span>
        {onResend ? (
          <button type="button" className="text-accent-text disabled:opacity-50 coarse:min-h-touch" disabled={busy || resendIn > 0} onClick={onResend}>
            {resendIn > 0 ? t('accountResendCodeIn', { seconds: resendIn }) : t('accountResendCode')}
          </button>
        ) : null}
      </div>
    </>
  )
}

/** 提交并解释 AgentLoginResult；返回 `'ok' | 'verify' | 'error'`。 */
async function run(
  action: () => Promise<AgentLoginResult>,
  context: AccountErrorContext,
  setError: (key: string | null) => void,
  setBusy: (busy: boolean) => void,
): Promise<{ kind: 'ok' } | { kind: 'verify'; ttlSeconds: number; willReplaceDevices: boolean } | { kind: 'error' }> {
  setBusy(true)
  setError(null)
  try {
    const result = await action()
    if (result.status === 'ok') return { kind: 'ok' }
    return { kind: 'verify', ttlSeconds: result.ttlSeconds, willReplaceDevices: result.willReplaceDevices }
  } catch (error) {
    setError(accountErrorKey(error, context))
    return { kind: 'error' }
  } finally {
    setBusy(false)
  }
}

/** 重发验证码的冷却秒数（与 GPUI 一致）。 */
const RESEND_COOLDOWN_SECS = 60

type LoginMethod = 'password' | 'code'

export function LoginDialog({ onClose }: { onClose: () => void }) {
  const t = useT()
  const [method, setMethod] = useState<LoginMethod>('password')
  const [account, setAccount] = useState('')
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const [verify, setVerify] = useState(false)
  const [replace, setReplace] = useState(false)
  const [busy, setBusy] = useState(false)
  const [errorKey, setErrorKey] = useState<string | null>(null)
  const [resetting, setResetting] = useState(false)
  const expiry = useCountdown()
  const cooldown = useCountdown()

  const byCode = method === 'code'
  const canSend = account.trim() !== '' && (byCode || password !== '')
  const okDisabled = verify ? code.trim() === '' : byCode ? account.trim() === '' : !canSend

  const changeMethod = (next: LoginMethod) => {
    setMethod(next)
    setErrorKey(null)
  }

  const started = (ttlSeconds: number, willReplaceDevices: boolean) => {
    setVerify(true)
    setReplace(willReplaceDevices)
    setCode('')
    expiry.start(ttlSeconds)
    cooldown.start(RESEND_COOLDOWN_SECS)
  }

  /** 验证码登录第一步：发码（`ttlSeconds` 为验证码有效期）。 */
  const sendCode = async () => {
    if (busy || account.trim() === '') return
    setBusy(true)
    setErrorKey(null)
    try {
      const result = await rpc.agent.auth.sendCode({ email: account.trim() })
      started(result.ttlSeconds, false)
    } catch (error) {
      setErrorKey(accountErrorKey(error, 'login'))
    } finally {
      setBusy(false)
    }
  }

  const submit = async () => {
    if (busy) return
    if (!verify) {
      if (byCode) return void sendCode()
      if (!canSend) return
    } else if (code.trim() === '') return
    const trimmed = account.trim()
    const outcome = await run(
      () => {
        if (byCode) return rpc.agent.auth.verifyCode({ email: trimmed, code: code.trim() })
        return verify ? rpc.agent.auth.loginVerify({ account: trimmed, password, code: code.trim() }) : rpc.agent.auth.login({ account: trimmed, password })
      },
      verify ? 'code' : 'login',
      setErrorKey,
      setBusy,
    )
    if (outcome.kind === 'ok') onClose()
    else if (outcome.kind === 'verify') started(outcome.ttlSeconds, outcome.willReplaceDevices)
  }

  /** 重发：密码登录重新走 login（新设备会再发码并可能更新「替换设备」提示），验证码登录重新发码。 */
  const resend = async () => {
    if (busy || cooldown.remaining > 0) return
    if (byCode) return void sendCode()
    const outcome = await run(() => rpc.agent.auth.login({ account: account.trim(), password }), 'login', setErrorKey, setBusy)
    if (outcome.kind === 'verify') started(outcome.ttlSeconds, outcome.willReplaceDevices)
    else if (outcome.kind === 'ok') onClose()
  }

  const back = () => {
    setVerify(false)
    setCode('')
    setErrorKey(null)
    expiry.start(0)
    cooldown.start(0)
  }

  const trimmedAccount = account.trim()
  const subtitle = trimmedAccount.includes('@') ? t('accountDeviceVerifySubtitle', { email: trimmedAccount }) : t('accountDeviceVerifySubtitleGeneric')

  if (resetting) {
    return (
      <ResetPasswordDialog
        initialEmail={trimmedAccount.includes('@') ? trimmedAccount : ''}
        onClose={() => setResetting(false)}
        onDone={(email) => {
          setAccount(email)
          setPassword('')
          setMethod('password')
          setErrorKey(null)
          setResetting(false)
        }}
      />
    )
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && !busy && onClose()}
      persistent
      title={t('accountLoginDialogTitle')}
      modalLocked={busy}
      footer={
        <ConfirmFooter
          okLabel={verify ? t('confirm') : byCode ? t('accountSendCode') : t('accountLogin')}
          onCancel={verify ? back : onClose}
          {...(verify ? { cancelLabel: t('back') } : {})}
          onOk={() => void submit()}
          okDisabled={okDisabled}
          loading={busy}
        />
      }
    >
      <Form onSubmit={() => void submit()}>
        {verify ? (
          <VerifyStep
            title={byCode ? t('accountLoginTabCode') : t('accountDeviceVerifyTitle')}
            subtitle={byCode ? t('accountRegisterVerifySubtitle', { email: trimmedAccount }) : subtitle}
            {...(replace ? { notice: t('accountDeviceVerifyReplacementNotice') } : {})}
            code={code}
            onCode={setCode}
            remaining={expiry.remaining}
            onResend={() => void resend()}
            resendIn={cooldown.remaining}
            busy={busy}
          />
        ) : (
          <>
            <SegmentedTabs
              value={method}
              onValueChange={changeMethod}
              aria-label={t('accountLoginDialogTitle')}
              items={[
                { value: 'password', label: t('accountLoginTabPassword') },
                { value: 'code', label: t('accountLoginTabCode') },
              ]}
            />
            <FormField label={byCode ? t('accountEmailPlaceholder') : t('accountFieldAccount')} htmlFor="account-login-account">
              <Input
                id="account-login-account"
                value={account}
                onChange={(event) => setAccount(event.target.value)}
                placeholder={byCode ? t('accountEmailPlaceholder') : t('accountLoginAccountPlaceholder')}
                autoComplete="username"
                autoCapitalize="none"
                autoFocus
              />
            </FormField>
            {byCode ? null : (
              <FormField label={t('accountPasswordPlaceholder')} htmlFor="account-login-password">
                <PasswordInput
                  id="account-login-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  placeholder={t('accountPasswordPlaceholder')}
                  autoComplete="current-password"
                />
              </FormField>
            )}
            {byCode ? null : (
              <button type="button" className="self-start text-xs text-accent-text disabled:opacity-50 coarse:min-h-touch" disabled={busy} onClick={() => setResetting(true)}>
                {t('accountForgotPassword')}
              </button>
            )}
          </>
        )}
        {errorKey ? <FieldError>{t(errorKey)}</FieldError> : null}
        <button type="submit" className="hidden" />
      </Form>
    </Dialog>
  )
}

export function RegisterDialog({ onClose }: { onClose: () => void }) {
  const t = useT()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [nickname, setNickname] = useState('')
  const [code, setCode] = useState('')
  const [verify, setVerify] = useState(false)
  const [busy, setBusy] = useState(false)
  const [errorKey, setErrorKey] = useState<string | null>(null)
  const countdown = useCountdown()
  const cooldown = useCountdown()

  const canSubmit = verify ? code.trim() !== '' : email.trim() !== '' && password !== ''

  /** 重发注册验证码：重新提交注册（服务端作废旧码并发新码）。 */
  const resend = async () => {
    if (busy || cooldown.remaining > 0) return
    const name = nickname.trim()
    const trimmed = email.trim()
    const outcome = await run(
      () => rpc.agent.auth.register(name ? { email: trimmed, password, nickname: name } : { email: trimmed, password }),
      'register',
      setErrorKey,
      setBusy,
    )
    if (outcome.kind === 'verify') {
      countdown.start(outcome.ttlSeconds)
      cooldown.start(RESEND_COOLDOWN_SECS)
    } else if (outcome.kind === 'ok') onClose()
  }

  const submit = async () => {
    if (busy || !canSubmit) return
    const trimmed = email.trim()
    const outcome = await run(
      () => {
        if (verify) return rpc.agent.auth.registerVerify({ email: trimmed, code: code.trim() })
        const name = nickname.trim()
        return rpc.agent.auth.register(name ? { email: trimmed, password, nickname: name } : { email: trimmed, password })
      },
      verify ? 'code' : 'register',
      setErrorKey,
      setBusy,
    )
    if (outcome.kind === 'ok') onClose()
    else if (outcome.kind === 'verify') {
      setVerify(true)
      countdown.start(outcome.ttlSeconds)
      cooldown.start(RESEND_COOLDOWN_SECS)
    }
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && !busy && onClose()}
      persistent
      title={t('accountRegisterDialogTitle')}
      modalLocked={busy}
      footer={<ConfirmFooter okLabel={verify ? t('accountVerifySubmit') : t('accountRegister')} onCancel={onClose} onOk={() => void submit()} okDisabled={!canSubmit} loading={busy} />}
    >
      <Form onSubmit={() => void submit()}>
        {verify ? (
          <VerifyStep
            title={t('accountRegisterVerifyTitle')}
            subtitle={t('accountRegisterVerifySubtitle', { email: email.trim() })}
            code={code}
            onCode={setCode}
            remaining={countdown.remaining}
            onResend={() => void resend()}
            resendIn={cooldown.remaining}
            busy={busy}
          />
        ) : (
          <>
            <FormField label={t('accountEmailPlaceholder')} htmlFor="account-register-email">
              <Input
                id="account-register-email"
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                autoComplete="email"
                autoCapitalize="none"
                autoFocus
              />
            </FormField>
            <FormField label={t('accountPasswordPlaceholder')} htmlFor="account-register-password" hint={t('accountPasswordHint')}>
              <PasswordInput
                id="account-register-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete="new-password"
              />
            </FormField>
            <FormField label={t('accountFieldNickname')} htmlFor="account-register-nickname">
              <Input id="account-register-nickname" value={nickname} onChange={(event) => setNickname(event.target.value)} placeholder={t('accountNicknamePlaceholder')} />
            </FormField>
          </>
        )}
        {errorKey ? <FieldError>{t(errorKey)}</FieldError> : null}
        <button type="submit" className="hidden" />
      </Form>
    </Dialog>
  )
}

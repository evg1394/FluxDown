// 验证码类对话框共用 hook：在途状态拆成 sending（发码）与 submitting（主按钮）、验证码倒计时。
//   - sending：只有发码按钮转圈，其余输入与取消仍可用；
//   - submitting：锁整个表单，主按钮转圈。
// 两者互斥：任一在途时忽略新的调用；对话框卸载后不再写状态。

import { useCallback, useEffect, useRef, useState } from 'react'
import { accountErrorKey } from './errorText'
import type { RestoredCode } from './sentCodes'
import { RESEND_COOLDOWN_SECS } from './sentCodes'
import { useCountdown } from './useCountdown'

export interface GuardedRunner {
  sending: boolean
  submitting: boolean
  errorKey: string | null
  setErrorKey: (key: string | null) => void
  /** 发码类操作。 */
  send: (action: () => Promise<void>) => Promise<void>
  /** 主按钮操作。 */
  submit: (action: () => Promise<void>) => Promise<void>
  /** 对话框仍挂载（异步回调里写状态前检查）。 */
  active: { readonly current: boolean }
  /** 主按钮操作在途（同步读取，用于拦截关闭）。 */
  submitPending: { readonly current: boolean }
}

export function useGuarded(): GuardedRunner {
  const [sending, setSending] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [errorKey, setErrorKey] = useState<string | null>(null)
  const active = useRef(true)
  const sendPending = useRef(false)
  const submitPending = useRef(false)
  useEffect(() => {
    active.current = true
    return () => { active.current = false }
  }, [])
  const run = useCallback(async (kind: 'send' | 'submit', action: () => Promise<void>) => {
    if (!active.current || sendPending.current || submitPending.current) return
    const flag = kind === 'send' ? sendPending : submitPending
    const setFlag = kind === 'send' ? setSending : setSubmitting
    flag.current = true
    setFlag(true)
    setErrorKey(null)
    try {
      await action()
    } catch (error) {
      if (active.current) setErrorKey(accountErrorKey(error, 'code'))
    } finally {
      flag.current = false
      if (active.current) setFlag(false)
    }
  }, [])
  const send = useCallback((action: () => Promise<void>) => run('send', action), [run])
  const submit = useCallback((action: () => Promise<void>) => run('submit', action), [run])
  return { sending, submitting, errorKey, setErrorKey, send, submit, active, submitPending }
}

export interface CodeTimer {
  /** 本实例发过或恢复过验证码。 */
  sent: boolean
  /** 验证码剩余有效秒数。 */
  remaining: number
  /** 重发冷却剩余秒数。 */
  cooldown: number
  /** 发码成功：开始倒计时。 */
  begin: (ttlSeconds: number) => void
  /** 恢复此前发出的验证码倒计时。 */
  restore: (restored: RestoredCode) => void
}

/** 验证码有效期倒计时 + 重发冷却（发码后前 60 秒内不可重发）。 */
export function useCodeTimer(): CodeTimer {
  const countdown = useCountdown()
  const [ttl, setTtl] = useState(0)
  const start = countdown.start
  const begin = useCallback((ttlSeconds: number) => {
    setTtl(ttlSeconds)
    start(ttlSeconds)
  }, [start])
  const restore = useCallback((restored: RestoredCode) => {
    setTtl(restored.ttlSeconds)
    start(restored.remaining)
  }, [start])
  const cooldown = Math.max(0, countdown.remaining - Math.max(0, ttl - RESEND_COOLDOWN_SECS))
  return { sent: ttl > 0, remaining: countdown.remaining, cooldown, begin, restore }
}

// 跨对话框实例记住已发出的验证码：关掉再打开对话框时恢复倒计时，不重复发码（云端 60s 限频、码仍有效）。
// 纯函数部分（计算/存取）与时间解耦，便于单测。

/** 发码冷却（秒）：云端对同一用途重发的最短间隔。 */
export const RESEND_COOLDOWN_SECS = 60

export interface SentCode {
  /** 发码时刻（毫秒时间戳）。 */
  sentAt: number
  ttlSeconds: number
}

export interface RestoredCode {
  ttlSeconds: number
  /** 验证码剩余有效秒数（>0）。 */
  remaining: number
  /** 重发冷却剩余秒数（0 = 可重发）。 */
  cooldown: number
}

/** 按记录恢复倒计时；有效期与冷却都已归零返回 `null`。 */
export function restoreCode(record: SentCode, now: number): RestoredCode | null {
  const elapsed = Math.max(0, (now - record.sentAt) / 1000)
  const remaining = Math.ceil(record.ttlSeconds - elapsed)
  const cooldown = Math.ceil(Math.min(RESEND_COOLDOWN_SECS, record.ttlSeconds) - elapsed)
  if (remaining <= 0 && cooldown <= 0) return null
  return { ttlSeconds: record.ttlSeconds, remaining: Math.max(0, remaining), cooldown: Math.max(0, cooldown) }
}

const records = new Map<string, SentCode>()

/** 修改邮箱：原邮箱验证码。 */
export const emailOldCodeKey = (userId: string) => `email-old:${userId}`
/** 修改 / 设置密码：绑定邮箱验证码。 */
export const passwordCodeKey = (userId: string) => `password:${userId}`

export function rememberCode(key: string, ttlSeconds: number, now: number = Date.now()): void {
  records.set(key, { sentAt: now, ttlSeconds })
}

/** 取出可恢复的记录；已失效的顺手清掉。 */
export function recallCode(key: string, now: number = Date.now()): RestoredCode | null {
  const record = records.get(key)
  if (!record) return null
  const restored = restoreCode(record, now)
  if (!restored) records.delete(key)
  return restored
}

export function forgetCode(key: string): void {
  records.delete(key)
}

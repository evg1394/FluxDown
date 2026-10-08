import { describe, expect, test } from 'bun:test'
import { emailOldCodeKey, forgetCode, passwordCodeKey, recallCode, rememberCode, restoreCode } from './sentCodes'

describe('验证码恢复计算', () => {
  test('刚发出：剩余 = ttl，冷却 = 60', () => {
    expect(restoreCode({ sentAt: 0, ttlSeconds: 600 }, 0)).toEqual({ ttlSeconds: 600, remaining: 600, cooldown: 60 })
  })

  test('过了冷却但码仍有效：冷却 0', () => {
    expect(restoreCode({ sentAt: 0, ttlSeconds: 600 }, 90_000)).toEqual({ ttlSeconds: 600, remaining: 510, cooldown: 0 })
  })

  test('ttl 短于 60 秒：冷却取 ttl', () => {
    expect(restoreCode({ sentAt: 0, ttlSeconds: 30 }, 10_000)).toEqual({ ttlSeconds: 30, remaining: 20, cooldown: 20 })
  })

  test('有效期与冷却都归零视为无记录', () => {
    expect(restoreCode({ sentAt: 0, ttlSeconds: 600 }, 600_000)).toBeNull()
    expect(restoreCode({ sentAt: 0, ttlSeconds: 30 }, 31_000)).toBeNull()
  })

  test('时钟回拨不产生超过 ttl 的剩余', () => {
    expect(restoreCode({ sentAt: 10_000, ttlSeconds: 600 }, 0)?.remaining).toBe(600)
  })
})

describe('验证码记录', () => {
  test('key 按用户与用途隔离', () => {
    expect(emailOldCodeKey('u1')).not.toBe(passwordCodeKey('u1'))
    expect(passwordCodeKey('u1')).not.toBe(passwordCodeKey('u2'))
    rememberCode(passwordCodeKey('u1'), 600, 1000)
    expect(recallCode(passwordCodeKey('u1'), 2000)?.remaining).toBe(599)
    expect(recallCode(passwordCodeKey('u2'), 2000)).toBeNull()
    expect(recallCode(emailOldCodeKey('u1'), 2000)).toBeNull()
    forgetCode(passwordCodeKey('u1'))
  })

  test('过期后失效并清除', () => {
    const key = emailOldCodeKey('expire')
    rememberCode(key, 600, 0)
    expect(recallCode(key, 599_000)?.remaining).toBe(1)
    expect(recallCode(key, 600_000)).toBeNull()
    expect(recallCode(key, 1_000)).toBeNull()
  })

  test('重新发码覆盖旧记录', () => {
    const key = passwordCodeKey('overwrite')
    rememberCode(key, 600, 0)
    rememberCode(key, 600, 500_000)
    expect(recallCode(key, 501_000)?.remaining).toBe(599)
    forgetCode(key)
  })
})

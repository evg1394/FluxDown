import { describe, expect, test } from 'bun:test'
import { sessionRevokedKey } from './sessionNotice'

describe('会话撤销提示', () => {
  test('按 reason 选文案：移出信任 / 账号停用 / 其余按会话失效', () => {
    expect(sessionRevokedKey('deviceUntrusted')).toBe('accountSessionRevokedUntrusted')
    expect(sessionRevokedKey('accountDisabled')).toBe('accountErrorAccountDisabled')
    expect(sessionRevokedKey('passwordChanged')).toBe('accountSessionRevokedPasswordChanged')
    expect(sessionRevokedKey('sessionExpired')).toBe('accountSessionRevokedExpired')
    expect(sessionRevokedKey('unknown')).toBe('accountSessionRevokedExpired')
  })
})

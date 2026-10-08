import { describe, expect, test } from 'bun:test'
import { newPasswordErrorKey, passwordLength } from './passwordRules'

describe('密码本地校验', () => {
  test('长度按码点计', () => {
    expect(passwordLength('😀😀😀😀')).toBe(4)
    expect(newPasswordErrorKey('😀😀😀😀😀😀😀', '😀😀😀😀😀😀😀', null)).toBe('accountErrorPasswordTooShort')
    expect(newPasswordErrorKey('😀😀😀😀😀😀😀😀', '😀😀😀😀😀😀😀😀', null)).toBeNull()
  })

  test('过短优先于不一致', () => {
    expect(newPasswordErrorKey('short', 'other', null)).toBe('accountErrorPasswordTooShort')
  })

  test('两次不一致', () => {
    expect(newPasswordErrorKey('password1', 'password2', null)).toBe('accountPasswordMismatch')
  })

  test('新旧相同仅在传入当前密码时拒绝', () => {
    expect(newPasswordErrorKey('password1', 'password1', 'password1')).toBe('accountPasswordSameAsCurrent')
    expect(newPasswordErrorKey('password1', 'password1', null)).toBeNull()
    expect(newPasswordErrorKey('password1', 'password1', 'old-password')).toBeNull()
  })
})

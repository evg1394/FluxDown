import { describe, expect, test } from 'bun:test'
import {
  INCOMPATIBLE_RELOAD_GUARD_MS,
  shouldReloadForVersionChange,
  shouldReloadOnIncompatible,
} from './reload'

describe('shouldReloadForVersionChange', () => {
  test('首次握手不刷新', () => {
    expect(shouldReloadForVersionChange(null, '1.2.0')).toBe(false)
  })
  test('版本相同不刷新', () => {
    expect(shouldReloadForVersionChange('1.2.0', '1.2.0')).toBe(false)
  })
  test('版本变化刷新', () => {
    expect(shouldReloadForVersionChange('1.2.0', '1.3.0')).toBe(true)
  })
  test('空版本串不触发', () => {
    expect(shouldReloadForVersionChange('1.2.0', '')).toBe(false)
    expect(shouldReloadForVersionChange('', '1.3.0')).toBe(false)
  })
})

describe('shouldReloadOnIncompatible', () => {
  const now = 1_000_000
  test('从未刷新过则允许', () => {
    expect(shouldReloadOnIncompatible(null, now)).toBe(true)
  })
  test('守卫窗口内拒绝', () => {
    expect(shouldReloadOnIncompatible(now - 1000, now)).toBe(false)
    expect(shouldReloadOnIncompatible(now - INCOMPATIBLE_RELOAD_GUARD_MS + 1, now)).toBe(false)
  })
  test('守卫窗口后允许', () => {
    expect(shouldReloadOnIncompatible(now - INCOMPATIBLE_RELOAD_GUARD_MS, now)).toBe(true)
  })
  test('时钟回拨拒绝', () => {
    expect(shouldReloadOnIncompatible(now + 5000, now)).toBe(false)
  })
})

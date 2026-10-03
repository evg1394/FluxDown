import { expect, test } from 'bun:test'
import type { AgentSessionDto, OriginIdCheckResult } from '../../../../lib/rpc'
import { canEditOriginId, OriginIdEditor, parseOriginId, trimmedNickname, validNickname } from './profileEditing'

interface Deferred<T> { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void }

function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function fixture() {
  const checks: Deferred<OriginIdCheckResult>[] = []
  const randoms: Deferred<{ originId: number }>[] = []
  const saves: Deferred<unknown>[] = []
  const submitted: number[] = []
  const editor = new OriginIdEditor(10000, {
    checkOriginId: () => { const request = deferred<OriginIdCheckResult>(); checks.push(request); return request.promise },
    randomOriginId: () => { const request = deferred<{ originId: number }>(); randoms.push(request); return request.promise },
    changeOriginId: ({ originId }) => { const request = deferred<unknown>(); saves.push(request); submitted.push(originId); return request.promise },
  })
  return { editor, checks, randoms, saves, submitted }
}

const available = { available: true, reason: null }

test('nickname uses trimmed Unicode scalar length and Rust Unicode whitespace', () => {
  expect(validNickname(' \t\n')).toBe(false)
  expect(validNickname('a')).toBe(true)
  expect(validNickname('𐐀'.repeat(32))).toBe(true)
  expect(validNickname('𐐀'.repeat(33))).toBe(false)
  expect(validNickname('a'.repeat(33))).toBe(false)
  expect(validNickname('\uD800')).toBe(false)
  expect(trimmedNickname('\u0085 Name \u0085')).toBe('Name')
  expect(trimmedNickname('\uFEFFName\uFEFF')).toBe('\uFEFFName\uFEFF')
})

test('origin edit requires literal entitlement true and an unused change', () => {
  for (const permission of [undefined, null, false, 1, 'true', true]) {
    for (const changed of [false, true]) {
      const session = { entitlements: { originIdEdit: permission }, user: { originId: null, originIdChanged: changed } } as unknown as AgentSessionDto
      expect(canEditOriginId(session)).toBe(permission === true && !changed)
    }
  }
})

test('origin IDs never round an unsafe integer and reject nonnumeric or small input', () => {
  for (const value of ['', '9999', '-10000', '1e5', '10000.1', '9007199254740992', '9223372036854775807']) expect(parseOriginId(value)).toBe(null)
  expect(parseOriginId(' 010000 ')).toBe(10000)
  expect(parseOriginId('9007199254740991')).toBe(Number.MAX_SAFE_INTEGER)
})

test('same ID, unchecked input, checking, and taken input cannot submit, including Enter', async () => {
  const { editor, checks, submitted } = fixture()
  editor.setValue('010000')
  await editor.check()
  expect(checks.length).toBe(0)
  expect(await editor.submit()).toBe(false)
  editor.setValue('10001')
  expect(await editor.submit()).toBe(false)
  const checking = editor.check()
  expect(await editor.submit()).toBe(false)
  checks[0]!.resolve({ available: false, reason: 'taken' })
  await checking
  expect(await editor.submit()).toBe(false)
  expect(submitted).toEqual([])
})

test('old checks cannot replace newer input or its availability', async () => {
  const { editor, checks } = fixture()
  editor.setValue('10001')
  const first = editor.check()
  editor.setValue('10002')
  const second = editor.check()
  checks[1]!.resolve(available)
  await second
  checks[0]!.resolve({ available: false, reason: 'taken' })
  await first
  expect(editor.snapshot().value).toBe('10002')
  expect(editor.canSubmit()).toBe(false)
  editor.setConfirmed(true)
  expect(editor.canSubmit()).toBe(true)
  editor.setValue('10003')
  expect(editor.canSubmit()).toBe(false)
})

test('preflight errors surface and stale errors do not replace new input', async () => {
  const { editor, checks } = fixture()
  const failure = new Error('preflight failed')
  editor.setValue('10001')
  const first = editor.check()
  checks[0]!.reject(failure)
  await first
  expect(editor.snapshot().error).toBe(failure)
  expect(await editor.submit()).toBe(false)
  editor.setValue('10002')
  const second = editor.check()
  editor.setValue('10003')
  checks[1]!.reject(failure)
  await second
  expect(editor.snapshot().error).toBe(null)
})

test('random locks fields and actions, supersedes checks, and requires fresh preflight', async () => {
  const { editor, checks, randoms } = fixture()
  editor.setValue('10001')
  const checking = editor.check()
  const rolling = editor.roll()
  editor.setValue('10002')
  await editor.roll()
  expect(randoms.length).toBe(1)
  expect(editor.snapshot().value).toBe('10001')
  expect(await editor.submit()).toBe(false)
  checks[0]!.resolve(available)
  await checking
  expect(editor.canSubmit()).toBe(false)
  randoms[0]!.resolve({ originId: 10003 })
  await rolling
  expect(editor.snapshot().value).toBe('10003')
  expect(editor.canSubmit()).toBe(false)
})

test('unmount on disconnect, identity or permission change invalidates random and check completions', async () => {
  const { editor, randoms, checks } = fixture()
  editor.setValue('10001')
  const checking = editor.check()
  editor.invalidate()
  editor.setValue('10002')
  checks[0]!.resolve(available)
  await checking
  expect(editor.canSubmit()).toBe(false)
  const rolling = editor.roll()
  editor.invalidate()
  randoms[0]!.resolve({ originId: 10003 })
  await rolling
  expect(editor.snapshot().value).toBe('10002')
})

test('save locks mutation and duplicate submissions; obsolete save cannot close a new dialog', async () => {
  const { editor, checks, saves, submitted, randoms } = fixture()
  editor.setValue('10001')
  const checking = editor.check()
  checks[0]!.resolve(available)
  await checking
  editor.setConfirmed(true)
  const saving = editor.submit()
  editor.setValue('10002')
  await editor.roll()
  expect(await editor.submit()).toBe(false)
  expect(editor.snapshot().value).toBe('10001')
  expect(randoms.length).toBe(0)
  expect(submitted).toEqual([10001])
  editor.invalidate()
  saves[0]!.resolve(undefined)
  expect(await saving).toBe(false)
})

test('save failure stays visible and releases the busy lock', async () => {
  const { editor, checks, saves } = fixture()
  editor.setValue('10001')
  const checking = editor.check()
  checks[0]!.resolve(available)
  await checking
  editor.setConfirmed(true)
  const saving = editor.submit()
  const failure = new Error('save failed')
  saves[0]!.reject(failure)
  expect(await saving).toBe(false)
  await editor.check()
  expect(editor.snapshot().error).toBe(failure)
  expect(editor.snapshot().busy).toBe(false)
  editor.setConfirmed(true)
  expect(await editor.submit()).toBe(false)
})

test('one-time acknowledgement resets on input and random changes', async () => {
  const { editor, randoms } = fixture()
  editor.setConfirmed(true)
  editor.setValue('10001')
  expect(editor.snapshot().confirmed).toBe(false)
  editor.setConfirmed(true)
  const rolling = editor.roll()
  expect(editor.snapshot().confirmed).toBe(false)
  editor.setConfirmed(true)
  expect(editor.snapshot().confirmed).toBe(false)
  randoms[0]!.resolve({ originId: 10002 })
  await rolling
  expect(editor.snapshot().confirmed).toBe(false)
})

test('matching committed session before RPC response preserves success; other changes revoke the editor', async () => {
  const { editor, checks, saves } = fixture()
  const session = { entitlements: { originIdEdit: true }, user: { originId: 10001, originIdChanged: true } } as unknown as AgentSessionDto
  expect(editor.acceptsSession(session)).toBe(false)
  editor.setValue('10001')
  const checking = editor.check()
  checks[0]!.resolve(available)
  await checking
  expect(await editor.submit()).toBe(false)
  editor.setConfirmed(true)
  const saving = editor.submit()
  expect(editor.acceptsSession(session)).toBe(true)
  expect(editor.acceptsSession({ ...session, user: { ...session.user, originId: 10002 } })).toBe(false)
  expect(editor.acceptsSession({ ...session, entitlements: { originIdEdit: false } })).toBe(false)
  saves[0]!.resolve(undefined)
  expect(await saving).toBe(true)
})

import { describe, expect, test } from 'bun:test'
import type { CloudNotifyChannelDto, CloudNotifyDeliveryDto, CloudNotifyKindDto, CloudNotifyOverviewDto } from '../../lib/rpc'
import {
  buildChannelCards,
  canSaveChannel,
  channelLimitReached,
  channelState,
  defaultPushTab,
  effectivePushTab,
  deliveryView,
  formatShortTime,
  isCloudEnabled,
  nextDeliveriesCursor,
  visibleDeliveries,
  addDisabledReason,
  addOptions,
  addEmail,
  canAddMoreEmails,
  canSendEmailCode,
  canVerifyEmail,
  channelGridClass,
  emailTargetLine,
  initialEmailList,
  isReaddingAccount,
  isValidEmailAddress,
  newEmailStatus,
  removeEmail,
  mergeDeliveries,
  notifyErrorText,
  pageDescKey,
  privacyFieldKeys,
  showKindSuffix,
  quotaStatus,
  usageMeter,
} from './cloudLogic'

const channel = (patch: Partial<CloudNotifyChannelDto> = {}): CloudNotifyChannelDto => ({
  id: 'c1',
  kind: 'email',
  name: 'iPhone',
  enabled: true,
  events: ['task.completed'],
  target: '****abcd',
  status: 'ok',
  createdAt: '2026-10-09T00:00:00Z',
  ...patch,
})

const KINDS = ['email', 'telegram']

const overview = (patch: Partial<CloudNotifyOverviewDto> = {}): CloudNotifyOverviewDto => ({
  enabled: true,
  usage: { dailyUsed: 0, dailyLimit: 20, monthlyUsed: 0, monthlyLimit: 300, dailyResetAt: '', monthlyResetAt: '' },
  maxChannels: 2,
  channels: [],
  accountEmail: 'a@b.c',
  ...patch,
})

const catalog = (available: (kind: string) => boolean = () => true): CloudNotifyKindDto[] =>
  KINDS.map((kind) => ({ kind, available: available(kind) }))

describe('default tab', () => {
  test('self-hosted endpoints win, otherwise cloud', () => {
    expect(defaultPushTab(0)).toBe('cloud')
    expect(defaultPushTab(1)).toBe('selfHosted')
  })
})

describe('effective tab', () => {
  test('cloud disabled forces self-hosted regardless of the pick', () => {
    expect(effectivePushTab(false, 'cloud', 0)).toBe('selfHosted')
    expect(effectivePushTab(false, null, 0)).toBe('selfHosted')
  })

  test('a manual pick wins and survives disable → enable; otherwise the default rule applies', () => {
    expect(effectivePushTab(true, 'cloud', 3)).toBe('cloud')
    expect(effectivePushTab(true, 'selfHosted', 0)).toBe('selfHosted')
    expect(effectivePushTab(true, null, 0)).toBe('cloud')
    expect(effectivePushTab(true, null, 2)).toBe('selfHosted')
  })

  test('page description follows cloud availability', () => {
    expect(pageDescKey(true)).toBe('pushNavDesc')
    expect(pageDescKey(false)).toBe('webhookEmptyDesc')
  })
})

describe('catalog gate', () => {
  test('null, undefined and empty catalog all hide cloud push', () => {
    expect(isCloudEnabled(null)).toBe(false)
    expect(isCloudEnabled(undefined)).toBe(false)
    expect(isCloudEnabled([])).toBe(false)
    expect(isCloudEnabled([{ kind: 'telegram', available: true }])).toBe(true)
    expect(isCloudEnabled([{ kind: 'email', available: false }])).toBe(true)
  })

  test('kind suffix is dropped when the channel name equals the kind name', () => {
    expect(showKindSuffix('邮件', '邮件')).toBe(false)
    expect(showKindSuffix(' 邮件 ', '邮件')).toBe(false)
    expect(showKindSuffix('我的手机', 'Telegram')).toBe(true)
    expect(showKindSuffix('', 'Telegram')).toBe(true)
    expect(showKindSuffix('Telegram', 'telegram')).toBe(true)
  })
})

describe('kind cards', () => {
  test('only added channels render; no placeholder cards for unconnected kinds', () => {
    expect(buildChannelCards(overview(), catalog())).toEqual([])
  })

  test('channels outside the catalog or of unknown legacy kinds are hidden', () => {
    const cards = buildChannelCards(
      overview({
        channels: [channel({ id: 'a' }), channel({ id: 'b', kind: 'telegram' }), channel({ id: 'c', kind: 'bark' })],
      }),
      [{ kind: 'email', available: true }],
    )
    expect(cards.map((card) => card.channel.id)).toEqual(['a'])
  })

  test('channels of an unavailable kind still render but are marked unavailable; same kind may repeat', () => {
    const cards = buildChannelCards(
      overview({ channels: [channel({ id: 'a' }), channel({ id: 'b' }), channel({ id: 't', kind: 'telegram' })] }),
      catalog((kind) => kind !== 'telegram'),
    )
    expect(cards.map((card) => [card.channel.id, card.state])).toEqual([
      ['a', 'connected'],
      ['b', 'connected'],
      ['t', 'unavailable'],
    ])
  })

  test('unavailable beats failing beats paused beats connected', () => {
    expect(channelState(channel({ status: 'failing', enabled: false }), false)).toBe('unavailable')
    expect(channelState(channel({ status: 'failing', enabled: false }), true)).toBe('failing')
    expect(channelState(channel({ enabled: false }), true)).toBe('paused')
    expect(channelState(channel(), true)).toBe('connected')
  })

  test('channel limit 0 means unlimited', () => {
    expect(channelLimitReached(5, 0)).toBe(false)
    expect(channelLimitReached(1, 2)).toBe(false)
    expect(channelLimitReached(2, 2)).toBe(true)
  })
})

describe('usage', () => {
  test('limit 0 is unlimited and never exhausted', () => {
    expect(usageMeter(999, 0)).toEqual({ used: 999, limit: 0, unlimited: true, ratio: 0, exhausted: false })
  })

  test('ratio is clamped and full means exhausted', () => {
    expect(usageMeter(5, 20).ratio).toBe(0.25)
    expect(usageMeter(20, 20).exhausted).toBe(true)
    expect(usageMeter(25, 20).ratio).toBe(1)
  })

  test('recover time prefers the monthly reset', () => {
    const usage = { dailyUsed: 20, dailyLimit: 20, monthlyUsed: 300, monthlyLimit: 300, dailyResetAt: 'D', monthlyResetAt: 'M' }
    expect(quotaStatus(usage).recoverAt).toBe('M')
    expect(quotaStatus({ ...usage, monthlyUsed: 1 }).recoverAt).toBe('D')
    expect(quotaStatus({ ...usage, dailyUsed: 1, monthlyUsed: 1 })).toMatchObject({ exhausted: false, recoverAt: null })
  })

  test('short time formatting tolerates garbage', () => {
    expect(formatShortTime('')).toBe('')
    expect(formatShortTime('nope')).toBe('')
    expect(/^\d\d-\d\d \d\d:\d\d$/.test(formatShortTime('2026-10-09T10:05:00Z'))).toBe(true)
  })
})

describe('privacy summary', () => {
  test('url then save dir are appended on top of the always-sent fields', () => {
    const base = ['cloudNotifyFieldFileName', 'cloudNotifyFieldSize', 'cloudNotifyFieldStatus']
    expect(privacyFieldKeys(false, false)).toEqual(base)
    expect(privacyFieldKeys(true, false)).toEqual([...base, 'cloudNotifyFieldUrl'])
    expect(privacyFieldKeys(false, true)).toEqual([...base, 'cloudNotifyFieldSaveDir'])
    expect(privacyFieldKeys(true, true)).toEqual([...base, 'cloudNotifyFieldUrl', 'cloudNotifyFieldSaveDir'])
  })
})

describe('channel save validation', () => {
  test('save needs a name and at least one event', () => {
    expect(canSaveChannel('x', ['task.completed'])).toBe(true)
    expect(canSaveChannel('  ', ['task.completed'])).toBe(false)
    expect(canSaveChannel('x', [])).toBe(false)
  })
})

describe('channel grid & add menu', () => {
  test('one channel stays a single capped column, 2+ use two equal columns', () => {
    expect(channelGridClass(1)).toBe('grid w-full grid-cols-1 gap-3 desktop:max-w-[calc(50%-0.375rem)]')
    expect(channelGridClass(2)).toBe('grid w-full grid-cols-2 gap-3 mobile:grid-cols-1')
    expect(channelGridClass(5)).toBe('grid w-full grid-cols-2 gap-3 mobile:grid-cols-1')
  })

  test('add options only list known catalog kinds; unavailable ones stay listed but flagged', () => {
    const options = addOptions([
      { kind: 'email', available: true },
      { kind: 'telegram', available: false },
      { kind: 'bark', available: true },
    ])
    expect(options.map((option) => [option.meta.kind, option.available])).toEqual([
      ['email', true],
      ['telegram', false],
    ])
  })

  test('add is disabled by the plan first, then by a full channel quota (0 = unlimited)', () => {
    expect(addDisabledReason(false, 9, 2)).toBe('planDisabled')
    expect(addDisabledReason(true, 2, 2)).toBe('limitReached')
    expect(addDisabledReason(true, 5, 0)).toBeNull()
    expect(addDisabledReason(true, 1, 2)).toBeNull()
  })
})

describe('helpers', () => {
  test('error reasons map to localized keys', () => {
    expect(notifyErrorText('notifyChannelLimit', '', 2)).toEqual({ key: 'cloudNotifyErrorChannelLimit', params: { limit: 2 } })
    expect(notifyErrorText('cloudUnreachable', '', 0).key).toBe('cloudNotifyErrorOffline')
    expect(notifyErrorText('notifyDisabled', '', 0).key).toBe('cloudNotifyPlanDisabled')
    expect(notifyErrorText(undefined, 'boom', 0)).toEqual({ key: 'cloudNotifyErrorGeneric', params: { error: 'boom' } })
  })

  test('delivery view and page merge', () => {
    const base: CloudNotifyDeliveryDto = {
      id: '1',
      event: 'task.failed',
      title: 'a.zip',
      channelId: 'c',
      channelKind: 'bark',
      channelName: 'n',
      deviceName: 'd',
      status: 'failed',
      attempts: 2,
      maxAttempts: 4,
      createdAt: '',
    }
    expect(deliveryView(base)).toEqual({ statusKey: 'cloudNotifyDeliveryFailed', tone: 'destructive', showRetry: true })
    expect(deliveryView({ ...base, status: 'dropped' }).statusKey).toBe('cloudNotifyDeliveryDropped')
    expect(deliveryView({ ...base, status: 'pending' }).tone).toBe('muted')
    expect(mergeDeliveries([base], [base, { ...base, id: '2' }]).map((item) => item.id)).toEqual(['1', '2'])
  })
})

describe('deliveries view', () => {
  const d = (id: string): CloudNotifyDeliveryDto => ({
    id,
    event: 'task.completed',
    title: id,
    channelId: 'c',
    channelKind: 'email',
    channelName: 'n',
    deviceName: 'd',
    status: 'sent',
    attempts: 1,
    maxAttempts: 4,
    createdAt: '',
  })

  test('snapshot first page wins over appended older rows with the same id', () => {
    const recent = [{ ...d('a'), status: 'failed' }, d('b')]
    const extra = [d('b'), d('c')]
    const shown = visibleDeliveries(recent, extra)
    expect(shown.map((item) => item.id)).toEqual(['a', 'b', 'c'])
    expect(shown[0]?.status).toBe('failed')
  })

  test('cursor comes from the snapshot until more rows have been loaded', () => {
    expect(nextDeliveriesCursor('k1', null)).toBe('k1')
    expect(nextDeliveriesCursor(undefined, null)).toBeNull()
    expect(nextDeliveriesCursor('k1', { cursor: 'k2' })).toBe('k2')
    expect(nextDeliveriesCursor('k1', { cursor: null })).toBeNull()
  })
})

describe('email recipients (list + verification)', () => {
  const ACCOUNT = 'me@a.com'

  test('address format', () => {
    expect(isValidEmailAddress('a@b.co')).toBe(true)
    expect(isValidEmailAddress('  a@b.co ')).toBe(true)
    expect(isValidEmailAddress('a@b')).toBe(false)
    expect(isValidEmailAddress('a b@c.com')).toBe(false)
    expect(isValidEmailAddress('')).toBe(false)
    expect(isValidEmailAddress(`${'a'.repeat(250)}@b.co`)).toBe(false)
  })

  test('initial list: new → account only; edit → channel addresses (fallback target)', () => {
    expect(initialEmailList(null, ACCOUNT)).toEqual([ACCOUNT])
    expect(initialEmailList(channel({ kind: 'email', addresses: [ACCOUNT, 'x@y.com'] }), ACCOUNT)).toEqual([ACCOUNT, 'x@y.com'])
    expect(initialEmailList(channel({ kind: 'email', target: 'x@y.com' }), ACCOUNT)).toEqual(['x@y.com'])
  })

  test('new address status: empty / invalid / exists (case-insensitive) / ok', () => {
    const list = [ACCOUNT, 'x@y.com']
    expect(newEmailStatus('  ', list)).toBe('empty')
    expect(newEmailStatus('bad', list)).toBe('invalid')
    expect(newEmailStatus('X@Y.com', list)).toBe('exists')
    expect(newEmailStatus('n@y.com', list)).toBe('ok')
  })

  test('sending a code / verifying needs a new non-account address (+ 6 digits)', () => {
    const list = [ACCOUNT]
    expect(canSendEmailCode('n@y.com', list, ACCOUNT)).toBe(true)
    expect(canSendEmailCode('bad', list, ACCOUNT)).toBe(false)
    expect(canSendEmailCode(ACCOUNT, [], ACCOUNT)).toBe(false)
    expect(canSendEmailCode('n@y.com', [ACCOUNT, 'n@y.com'], ACCOUNT)).toBe(false)
    expect(canVerifyEmail('n@y.com', '12345', list, ACCOUNT)).toBe(false)
    expect(canVerifyEmail('n@y.com', '123456', list, ACCOUNT)).toBe(true)
  })

  test('re-adding a removed account email needs no code and goes first', () => {
    const list = ['x@y.com']
    expect(isReaddingAccount('ME@a.com', list, ACCOUNT)).toBe(true)
    expect(isReaddingAccount('n@y.com', list, ACCOUNT)).toBe(false)
    expect(isReaddingAccount('me@a.com', [ACCOUNT], ACCOUNT)).toBe(false)
    expect(addEmail(list, 'ME@a.com', ACCOUNT)).toEqual(['ME@a.com', 'x@y.com'])
    expect(addEmail([ACCOUNT], ' n@y.com ', ACCOUNT)).toEqual([ACCOUNT, 'n@y.com'])
  })

  test('max 5 recipients; duplicates and overflow are ignored', () => {
    const five = [ACCOUNT, 'a@b.co', 'b@b.co', 'c@b.co', 'd@b.co']
    expect(canAddMoreEmails(five.slice(0, 4))).toBe(true)
    expect(canAddMoreEmails(five)).toBe(false)
    expect(addEmail(five, 'e@b.co', ACCOUNT)).toEqual(five)
    expect(addEmail(five.slice(0, 2), 'A@b.co', ACCOUNT)).toEqual(five.slice(0, 2))
  })

  test('the last recipient cannot be removed', () => {
    expect(removeEmail([ACCOUNT, 'x@y.com'], 'X@y.com')).toEqual([ACCOUNT])
    expect(removeEmail([ACCOUNT], ACCOUNT)).toEqual([ACCOUNT])
  })

  test('card target line: one address as-is, several → "x 等 n 个邮箱" key', () => {
    expect(emailTargetLine(channel({ kind: 'email', target: ACCOUNT, addresses: [ACCOUNT] }))).toEqual({ key: null, text: ACCOUNT })
    expect(emailTargetLine(channel({ kind: 'email', target: '@bot' }))).toEqual({ key: null, text: '@bot' })
    expect(emailTargetLine(channel({ kind: 'email', addresses: [ACCOUNT, 'x@y.com', 'z@y.com'] }))).toEqual({
      key: 'cloudNotifyEmailMore',
      params: { email: ACCOUNT, n: 3 },
      text: '',
    })
  })

  test('invalid verification code maps to its message', () => {
    expect(notifyErrorText('invalidVerificationCode', '', 0).key).toBe('cloudNotifyEmailCodeInvalid')
  })
})

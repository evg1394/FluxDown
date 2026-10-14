// 云端推送页纯逻辑：默认标签、卡片状态、用量格式化、隐私摘要、渠道配置校验、错误文案映射。
// 与 GPUI 同构（契约 §6）；校验样例与 GPUI 对齐，见 cloudLogic.test.ts。

import { CLOUD_NOTIFY_EMAIL_MAX_ADDRESSES } from '../../lib/rpc/protocol'
import type {
  CloudNotifyChannelDto,
  CloudNotifyDeliveryDto,
  CloudNotifyKindDto,
  CloudNotifyOverviewDto,
  CloudNotifyUsageDto,
  ErrorReason,
} from '../../lib/rpc'

export type PushTab = 'cloud' | 'selfHosted'

/** 默认标签：本机已配置自托管端点 → 自托管；否则 → 云端推送。 */
export function defaultPushTab(selfHostedEndpointCount: number): PushTab {
  return selfHostedEndpointCount > 0 ? 'selfHosted' : 'cloud'
}

/** 有效标签：云端关闭 → 自托管（用户选择无效但保留）；否则用户选择优先，未选过按默认规则。 */
export function effectivePushTab(cloudEnabled: boolean, picked: PushTab | null, selfHostedEndpointCount: number): PushTab {
  if (!cloudEnabled) return 'selfHosted'
  return picked ?? defaultPushTab(selfHostedEndpointCount)
}

/** 页头描述键：云端关闭时只剩自托管，用 `webhookEmptyDesc`。 */
export function pageDescKey(cloudEnabled: boolean): string {
  return cloudEnabled ? 'pushNavDesc' : 'webhookEmptyDesc'
}

export type CloudKind = 'email' | 'telegram'

export interface KindMeta {
  kind: CloudKind
  nameKey: string
  descKey: string
  /** 单色徽标首字母（没有合适图标时用首字母圆形徽标）。 */
  initial: string
}

/** 固定顺序，与云端 catalog 一致。 */
export const CLOUD_KINDS: readonly KindMeta[] = [
  { kind: 'email', nameKey: 'cloudNotifyKindEmail', descKey: 'cloudNotifyKindEmailDesc', initial: 'E' },
  { kind: 'telegram', nameKey: 'cloudNotifyKindTelegram', descKey: 'cloudNotifyKindTelegramDesc', initial: 'T' },
]

/** 渠道卡片网格：1 个 → 单列不撑满（宽度与两列时单卡一致），2+ → 两列等宽，移动端一列。 */
export function channelGridClass(channelCount: number): string {
  return channelCount <= 1
    ? 'grid w-full grid-cols-1 gap-3 desktop:max-w-[calc(50%-0.375rem)]'
    : 'grid w-full grid-cols-2 gap-3 mobile:grid-cols-1'
}

export function kindMeta(kind: string): KindMeta | undefined {
  return CLOUD_KINDS.find((entry) => entry.kind === kind)
}

/** 默认订阅事件：完成 + 失败。 */
export const DEFAULT_CLOUD_EVENTS: readonly string[] = ['task.completed', 'task.failed']

// ───────────────────────── 渠道卡片 ─────────────────────────

export type ChannelState = 'connected' | 'paused' | 'failing' | 'unavailable'

export interface ChannelCardModel {
  channel: CloudNotifyChannelDto
  meta: KindMeta
  state: ChannelState
}

/** 渠道卡片状态：unavailable（该种类当前发不出去）> failing（需修复）> paused > connected。 */
export function channelState(channel: CloudNotifyChannelDto, kindAvailable: boolean): ChannelState {
  if (!kindAvailable) return 'unavailable'
  if (channel.status === 'failing') return 'failing'
  if (!channel.enabled) return 'paused'
  return 'connected'
}

/** 云端推送是否对本机开放：catalog 非空（`null` 未知与空数组全部关闭都视为关闭）。 */
export function isCloudEnabled(catalog: readonly CloudNotifyKindDto[] | null | undefined): boolean {
  return catalog != null && catalog.length > 0
}

/** 卡片是否显示种类名（渠道名与种类显示名相同则不重复）。 */
export function showKindSuffix(channelName: string, kindName: string): boolean {
  return channelName.trim() !== kindName.trim()
}

/**
 * 只渲染已添加的渠道：种类须在 catalog 内且为已知种类（email / telegram），
 * catalog 外或历史遗留的未知种类一律不显示；同一种类可有多个。
 */
export function buildChannelCards(overview: CloudNotifyOverviewDto, catalog: readonly CloudNotifyKindDto[]): ChannelCardModel[] {
  const cards: ChannelCardModel[] = []
  for (const channel of overview.channels) {
    const entry = catalog.find((item) => item.kind === channel.kind)
    const meta = kindMeta(channel.kind)
    if (!entry || !meta) continue
    cards.push({ channel, meta, state: channelState(channel, entry.available) })
  }
  return cards
}

export interface AddOption {
  meta: KindMeta
  available: boolean
}

/** 「添加渠道」下拉：catalog 中的已知种类（固定顺序由 catalog 给出）。 */
export function addOptions(catalog: readonly CloudNotifyKindDto[]): AddOption[] {
  const options: AddOption[] = []
  for (const entry of catalog) {
    const meta = kindMeta(entry.kind)
    if (meta) options.push({ meta, available: entry.available })
  }
  return options
}

/** 是否已达渠道数上限（`0` = 不限）。 */
export function channelLimitReached(used: number, maxChannels: number): boolean {
  return maxChannels > 0 && used >= maxChannels
}

/** 「添加渠道」整体禁用原因（套餐未开通优先于渠道数已满）；可用则 `null`。 */
export function addDisabledReason(enabled: boolean, used: number, maxChannels: number): 'planDisabled' | 'limitReached' | null {
  if (!enabled) return 'planDisabled'
  return channelLimitReached(used, maxChannels) ? 'limitReached' : null
}

// ───────────────────────── 用量 ─────────────────────────

export interface UsageMeter {
  used: number
  limit: number
  unlimited: boolean
  /** 0..1，不限为 0。 */
  ratio: number
  exhausted: boolean
}

export function usageMeter(used: number, limit: number): UsageMeter {
  if (limit === 0) return { used, limit, unlimited: true, ratio: 0, exhausted: false }
  return { used, limit, unlimited: false, ratio: Math.min(1, used / limit), exhausted: used >= limit }
}

export interface QuotaStatus {
  daily: UsageMeter
  monthly: UsageMeter
  /** 日 / 月任一满额。 */
  exhausted: boolean
  /** 满额时给用户看的恢复时间（RFC 3339）：日满取日重置，月满取月重置（两者都满取更晚者）。 */
  recoverAt: string | null
}

export function quotaStatus(usage: CloudNotifyUsageDto): QuotaStatus {
  const daily = usageMeter(usage.dailyUsed, usage.dailyLimit)
  const monthly = usageMeter(usage.monthlyUsed, usage.monthlyLimit)
  let recoverAt: string | null = null
  if (monthly.exhausted) recoverAt = usage.monthlyResetAt
  else if (daily.exhausted) recoverAt = usage.dailyResetAt
  return { daily, monthly, exhausted: daily.exhausted || monthly.exhausted, recoverAt }
}

const pad = (value: number) => String(value).padStart(2, '0')

/** RFC 3339 → 本地 `MM-DD HH:mm`；无法解析返回空串。 */
export function formatShortTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

// ───────────────────────── 隐私 ─────────────────────────

/** 隐私摘要字段键（i18n 键）：文件名 / 大小 / 状态始终发送，下载地址与保存位置按开关追加。 */
export function privacyFieldKeys(includeUrl: boolean, includeSaveDir: boolean): string[] {
  const keys = ['cloudNotifyFieldFileName', 'cloudNotifyFieldSize', 'cloudNotifyFieldStatus']
  if (includeUrl) keys.push('cloudNotifyFieldUrl')
  if (includeSaveDir) keys.push('cloudNotifyFieldSaveDir')
  return keys
}

// ───────────────────────── 渠道保存校验 ─────────────────────────

/** 保存是否可用：名称非空且至少订阅一个事件。 */
export function canSaveChannel(name: string, events: readonly string[]): boolean {
  return name.trim() !== '' && events.length > 0
}

// ───────────────────────── 邮件收件邮箱列表 + 验证 ─────────────────────────

/** 单个邮件渠道的收件地址上限（= wire 常量 `CLOUD_NOTIFY_EMAIL_MAX_ADDRESSES`）。 */
export const EMAIL_MAX_ADDRESSES = CLOUD_NOTIFY_EMAIL_MAX_ADDRESSES

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const EMAIL_CODE_PATTERN = /^\d{6}$/

export function isValidEmailAddress(value: string): boolean {
  const address = value.trim()
  return address.length <= 254 && EMAIL_PATTERN.test(address)
}

/** 地址比较统一用 trim + 小写。 */
export function isSameEmail(a: string, b: string): boolean {
  return a.trim().toLowerCase() === b.trim().toLowerCase()
}

export function isValidEmailCode(value: string): boolean {
  return EMAIL_CODE_PATTERN.test(value.trim())
}

/** 对话框初始收件列表：新建 → 仅账号邮箱；编辑 → 渠道现有地址（缺省回退 target，再缺省账号邮箱），忽略大小写去重。 */
export function initialEmailList(channel: CloudNotifyChannelDto | null, accountEmail: string): string[] {
  const source =
    channel === null
      ? [accountEmail]
      : channel.addresses && channel.addresses.length > 0
        ? channel.addresses
        : [channel.target === '' ? accountEmail : channel.target]
  const list: string[] = []
  for (const address of source) {
    if (!list.some((item) => isSameEmail(item, address))) list.push(address)
  }
  return list
}

export const isAccountEmail = (address: string, accountEmail: string): boolean => isSameEmail(address, accountEmail)

export type NewEmailStatus = 'empty' | 'invalid' | 'exists' | 'ok'

/** 待添加地址的状态：格式不对 / 已在列表里 / 可添加。 */
export function newEmailStatus(input: string, list: readonly string[]): NewEmailStatus {
  if (input.trim() === '') return 'empty'
  if (!isValidEmailAddress(input)) return 'invalid'
  return list.some((item) => isSameEmail(item, input)) ? 'exists' : 'ok'
}

/** 列表是否还能再加（< 5）。 */
export function canAddMoreEmails(list: readonly string[]): boolean {
  return list.length < EMAIL_MAX_ADDRESSES
}

/** 添加的是账号邮箱（之前被移除）：不需要验证码，直接加回。 */
export function isReaddingAccount(input: string, list: readonly string[], accountEmail: string): boolean {
  return newEmailStatus(input, list) === 'ok' && isAccountEmail(input, accountEmail)
}

/** 可发验证码：地址可添加且不是账号邮箱（账号邮箱无需验证）。 */
export function canSendEmailCode(input: string, list: readonly string[], accountEmail: string): boolean {
  return newEmailStatus(input, list) === 'ok' && !isAccountEmail(input, accountEmail)
}

/** 可提交验证：可发码的地址 + 6 位验证码。 */
export function canVerifyEmail(input: string, code: string, list: readonly string[], accountEmail: string): boolean {
  return canSendEmailCode(input, list, accountEmail) && isValidEmailCode(code)
}

/** 加入列表（trim；账号邮箱放在最前；超过上限忽略）。 */
export function addEmail(list: readonly string[], input: string, accountEmail: string): string[] {
  if (newEmailStatus(input, list) !== 'ok' || !canAddMoreEmails(list)) return [...list]
  const address = input.trim()
  return isAccountEmail(address, accountEmail) ? [address, ...list] : [...list, address]
}

/** 移除一个收件地址；只剩 1 个时不允许移除。 */
export function removeEmail(list: readonly string[], address: string): string[] {
  if (list.length <= 1) return [...list]
  return list.filter((item) => !isSameEmail(item, address))
}

/** 卡片第二行：1 个地址 → 地址；多个 → `cloudNotifyEmailMore`。 */
export function emailTargetLine(channel: CloudNotifyChannelDto): { key: string | null; params?: Record<string, string | number>; text: string } {
  const addresses = channel.addresses ?? []
  if (addresses.length > 1) {
    return { key: 'cloudNotifyEmailMore', params: { email: addresses[0] ?? '', n: addresses.length }, text: '' }
  }
  return { key: null, text: addresses[0] ?? channel.target }
}

// ───────────────────────── 错误文案 ─────────────────────────

export interface ErrorText {
  key: string
  params?: Record<string, string | number>
}

/**
 * `ErrorReason` → 本地化文案键。`detail` 为兜底说明（RpcError.message）。
 * 渠道数上限 `maxChannels` 用于 `{limit}` 占位。
 */
export function notifyErrorText(reason: ErrorReason | undefined, detail: string, maxChannels: number): ErrorText {
  switch (reason) {
    case 'cloudUnreachable':
      return { key: 'cloudNotifyErrorOffline' }
    case 'notifyChannelLimit':
      return { key: 'cloudNotifyErrorChannelLimit', params: { limit: maxChannels } }
    case 'notifyDisabled':
      return { key: 'cloudNotifyPlanDisabled' }
    case 'rateLimited':
      return { key: 'accountErrorRateLimited' }
    case 'sessionExpired':
      return { key: 'errReasonSessionExpired' }
    case 'invalidVerificationCode':
      return { key: 'cloudNotifyEmailCodeInvalid' }
    default:
      return { key: 'cloudNotifyErrorGeneric', params: { error: detail } }
  }
}

// ───────────────────────── 投递记录 ─────────────────────────

export type DeliveryTone = 'muted' | 'success' | 'destructive' | 'warning'

export interface DeliveryView {
  statusKey: string
  tone: DeliveryTone
  /** failed 且仍可能重试时显示「重试 n/max」。 */
  showRetry: boolean
}

export function deliveryView(delivery: CloudNotifyDeliveryDto): DeliveryView {
  switch (delivery.status) {
    case 'sent':
      return { statusKey: 'cloudNotifyDeliverySent', tone: 'success', showRetry: false }
    case 'failed':
      return { statusKey: 'cloudNotifyDeliveryFailed', tone: 'destructive', showRetry: delivery.attempts > 0 }
    case 'dropped':
      return { statusKey: 'cloudNotifyDeliveryDropped', tone: 'warning', showRetry: false }
    default:
      return { statusKey: 'cloudNotifyDeliveryPending', tone: 'muted', showRetry: false }
  }
}

/** 追加一页并按 id 去重（实时刷新与分页重叠时不重复）。 */
export function mergeDeliveries(
  current: readonly CloudNotifyDeliveryDto[],
  page: readonly CloudNotifyDeliveryDto[],
): CloudNotifyDeliveryDto[] {
  const seen = new Set(current.map((item) => item.id))
  return [...current, ...page.filter((item) => !seen.has(item.id))]
}

/** 投递记录视图：快照第一页在前（以快照为准），「加载更多」追加的更早记录去重后接在后面。 */
export function visibleDeliveries(
  recent: readonly CloudNotifyDeliveryDto[],
  extra: readonly CloudNotifyDeliveryDto[],
): CloudNotifyDeliveryDto[] {
  return mergeDeliveries(recent, extra)
}

/** 下一页游标：已追加过更早记录则取追加后的游标，否则用快照携带的游标。 */
export function nextDeliveriesCursor(recentCursor: string | null | undefined, loaded: { cursor: string | null } | null): string | null {
  return loaded ? loaded.cursor : (recentCursor ?? null)
}

/** 已连接且启用的渠道（「发送测试通知」的目标）。 */
export function testTargets(channels: readonly CloudNotifyChannelDto[]): CloudNotifyChannelDto[] {
  return channels.filter((channel) => channel.enabled)
}

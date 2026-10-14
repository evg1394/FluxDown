// 云端推送页 · 通知渠道：只渲染已添加的渠道（同种类可多个），右上角「添加渠道」下拉按 catalog 列出种类；
// 没有渠道时显示空态。排列：1 个单列不撑满，2+ 两列等宽，移动端一列。

import { BellPlus, Ellipsis, Pause, Pencil, Play, Plus, Send, Unplug } from 'lucide-react'
import { useState } from 'react'
import { useT } from '../../i18n'
import { cn } from '../../lib/cn'
import { rpc } from '../../lib/rpc'
import type { CloudNotifyChannelDto, CloudNotifyKindDto, CloudNotifyOverviewDto } from '../../lib/rpc'
import { ActionMenu, Badge, Button, Card, EmptyState, Icon, Popover, confirmDialog, toast } from '../../ui'
import type { MenuEntry } from '../../ui'
import { CloudChannelDialog } from './CloudChannelDialog'
import type { DialogTarget } from './CloudChannelDialog'
import { addDisabledReason, addOptions, buildChannelCards, channelGridClass, channelLimitReached, emailTargetLine, showKindSuffix } from './cloudLogic'
import type { ChannelCardModel, ChannelState } from './cloudLogic'
import { cloudActionError } from './useCloudNotify'
import { WEBHOOK_EVENTS } from './endpoints'

const STATE_LABEL: Record<ChannelState, string> = {
  connected: 'cloudNotifyStatusConnected',
  paused: 'cloudNotifyStatusPaused',
  failing: 'cloudNotifyStatusFailing',
  unavailable: 'cloudNotifyStatusUnavailable',
}

const DOT: Record<ChannelState, string> = {
  connected: 'bg-success',
  paused: 'bg-text-tertiary',
  failing: 'bg-destructive',
  unavailable: 'bg-text-tertiary',
}

const EVENT_LABEL: Record<string, string> = Object.fromEntries(WEBHOOK_EVENTS.map((event) => [event.wire, event.labelKey]))

function KindBadge({ initial }: { initial: string }) {
  return (
    <div
      aria-hidden
      className="flex size-8 shrink-0 items-center justify-center rounded-full bg-accent text-sm font-semibold text-accent-text"
    >
      {initial}
    </div>
  )
}

function ChannelCard({
  card,
  disabled,
  testing,
  onEdit,
  onTest,
  onToggle,
  onDisconnect,
}: {
  card: ChannelCardModel
  disabled: boolean
  testing: boolean
  onEdit: () => void
  onTest: () => void
  onToggle: () => void
  onDisconnect: () => void
}) {
  const t = useT()
  const { channel, state, meta } = card
  const kindName = t(meta.nameKey)
  const title = channel.name || kindName
  const failing = state === 'failing'
  const targetLine = emailTargetLine(channel)
  const entries: MenuEntry[] = [
    { type: 'item', key: 'test', label: t('cloudNotifyTest'), icon: Send, onSelect: onTest, disabled: disabled || testing },
    { type: 'item', key: 'edit', label: t('cloudNotifyEdit'), icon: Pencil, onSelect: onEdit, disabled },
    {
      type: 'item',
      key: 'toggle',
      label: channel.enabled ? t('cloudNotifyPause') : t('cloudNotifyResume'),
      icon: channel.enabled ? Pause : Play,
      onSelect: onToggle,
      disabled,
    },
    { type: 'separator', key: 'sep' },
    { type: 'item', key: 'disconnect', label: t('cloudNotifyDisconnect'), icon: Unplug, onSelect: onDisconnect, destructive: true, disabled },
  ]

  return (
    <Card
      className={cn(
        'flex min-h-[8.5rem] min-w-0 flex-col gap-2 p-3 transition-colors hover:border-ring/40',
        state === 'paused' && 'opacity-80',
        state === 'unavailable' && 'opacity-60',
        failing && 'border-destructive/50',
      )}
    >
      <div className="flex min-w-0 items-center gap-2.5">
        <KindBadge initial={meta.initial} />
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-semibold text-foreground" title={title}>
            {title}
          </div>
          <div className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
            <span aria-hidden className={cn('size-1.5 shrink-0 rounded-full', DOT[state])} />
            <span className={cn('truncate', failing && 'text-destructive')}>{t(STATE_LABEL[state])}</span>
            {showKindSuffix(channel.name, kindName) ? <span className="shrink-0 text-text-tertiary">· {kindName}</span> : null}
          </div>
        </div>
        <ActionMenu
          title={title}
          entries={entries}
          trigger={
            <Button variant="ghost" iconOnly aria-label={title} title={title}>
              <Icon icon={Ellipsis} />
            </Button>
          }
        />
      </div>
      <div className="truncate text-xs text-foreground tabular" title={(channel.addresses ?? []).join(', ') || channel.target}>
        {targetLine.key ? t(targetLine.key, targetLine.params) : targetLine.text}
      </div>
      <div className="flex flex-wrap gap-1">
        {channel.events.map((event) => (
          <Badge key={event}>{EVENT_LABEL[event] ? t(EVENT_LABEL[event]) : event}</Badge>
        ))}
      </div>
      {failing ? (
        <div className="mt-auto flex min-w-0 items-center justify-between gap-2">
          <span className="min-w-0 truncate text-xs text-destructive" title={channel.lastError ?? ''}>
            {t('cloudNotifyLastError', { error: channel.lastError ?? '' })}
          </span>
          <Button variant="outline" className="shrink-0" disabled={disabled} onClick={onEdit}>
            {t('cloudNotifyFix')}
          </Button>
        </div>
      ) : null}
    </Card>
  )
}

/** 「添加渠道」下拉：每项 = 种类名 + 一句说明；不可用的种类置灰并标「暂不可用」。 */
function AddChannelMenu({
  catalog,
  disabledReason,
  offline,
  limitText,
  primary,
  onPick,
}: {
  catalog: readonly CloudNotifyKindDto[]
  disabledReason: 'planDisabled' | 'limitReached' | null
  offline: boolean
  limitText: string
  primary: boolean
  onPick: (kind: string) => void
}) {
  const t = useT()
  const options = addOptions(catalog)
  const disabled = offline || disabledReason !== null || options.length === 0
  const reason =
    disabledReason === 'planDisabled'
      ? t('cloudNotifyPlanDisabled')
      : disabledReason === 'limitReached'
        ? t('cloudNotifyErrorChannelLimit', { limit: limitText })
        : undefined
  const button = (
    <Button variant={primary ? 'primary' : 'outline'} icon={Plus} disabled={disabled}>
      {t('cloudNotifyAddChannel')}
    </Button>
  )
  if (disabled) return <span title={reason}>{button}</span>
  return (
    <Popover title={t('cloudNotifyAddChannel')} align="end" trigger={button} className="w-72 p-1">
      {(close) => (
        <div className="flex flex-col">
          {options.map((option) => (
            <button
              key={option.meta.kind}
              type="button"
              disabled={!option.available}
              onClick={() => {
                close()
                onPick(option.meta.kind)
              }}
              className="flex min-h-control items-start gap-2.5 rounded-sm px-2 py-2 text-left transition-colors hover:bg-nav-hover active:bg-nav-selected disabled:pointer-events-none disabled:opacity-50 coarse:min-h-touch"
            >
              <KindBadge initial={option.meta.initial} />
              <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                <span className="flex items-center gap-2 text-sm font-medium text-foreground">
                  {t(option.meta.nameKey)}
                  {!option.available ? <Badge>{t('cloudNotifyStatusUnavailable')}</Badge> : null}
                </span>
                <span className="text-xs text-muted-foreground">{t(option.meta.descKey)}</span>
              </span>
            </button>
          ))}
        </div>
      )}
    </Popover>
  )
}

export function CloudChannels({
  overview,
  catalog,
  disabled,
}: {
  overview: CloudNotifyOverviewDto
  catalog: readonly CloudNotifyKindDto[]
  disabled: boolean
}) {
  const t = useT()
  const [dialog, setDialog] = useState<DialogTarget | null>(null)
  const [testingId, setTestingId] = useState<string | null>(null)

  const cards = buildChannelCards(overview, catalog)
  const used = overview.channels.length
  const locked = disabled || !overview.enabled
  const limitReached = channelLimitReached(used, overview.maxChannels)
  const limitText = overview.maxChannels === 0 ? t('cloudNotifyUsageUnlimited') : String(overview.maxChannels)
  const disabledReason = addDisabledReason(overview.enabled, used, overview.maxChannels)
  const fail = (error: unknown) => toast.error(cloudActionError(error, t, overview.maxChannels))
  const addMenu = (primary: boolean) => (
    <AddChannelMenu
      catalog={catalog}
      disabledReason={disabledReason}
      offline={disabled}
      limitText={limitText}
      primary={primary}
      onPick={(kind) => setDialog({ kind, channel: null })}
    />
  )

  const test = async (channel: CloudNotifyChannelDto) => {
    if (testingId !== null) return
    setTestingId(channel.id)
    try {
      const result = await rpc.agent.cloudNotify.testChannel({ id: channel.id })
      if (result.success) toast.key('cloudNotifyTestOk', 'success')
      else toast.key('cloudNotifyTestFail', 'error', { error: result.error ?? '' })
    } catch (error) {
      fail(error)
    } finally {
      setTestingId(null)
    }
  }

  const toggle = async (channel: CloudNotifyChannelDto) => {
    try {
      await rpc.agent.cloudNotify.updateChannel({ id: channel.id, enabled: !channel.enabled })
    } catch (error) {
      fail(error)
    }
  }

  const disconnect = async (channel: CloudNotifyChannelDto) => {
    const ok = await confirmDialog({
      title: t('cloudNotifyDisconnect'),
      description: t('cloudNotifyDisconnectConfirm', { name: channel.name }),
      intent: 'destructive',
      okLabel: t('cloudNotifyDisconnect'),
    })
    if (!ok) return
    try {
      await rpc.agent.cloudNotify.deleteChannel({ id: channel.id })
    } catch (error) {
      fail(error)
    }
  }

  return (
    <section className="flex w-full min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-end justify-between gap-3 px-1">
        <div className="flex min-w-0 flex-col gap-0.5">
          <h2 className="text-sm font-semibold text-foreground">{t('cloudNotifyChannelsTitle')}</h2>
          <p className="text-xs text-muted-foreground">{t('cloudNotifyChannelsDesc')}</p>
        </div>
        <div className="flex shrink-0 items-center gap-3">
          <span className={cn('text-xs tabular', limitReached ? 'text-destructive' : 'text-muted-foreground')}>
            {t('cloudNotifyChannelLimit', { used, limit: limitText })}
          </span>
          {cards.length > 0 ? addMenu(false) : null}
        </div>
      </div>
      {cards.length === 0 ? (
        <Card className="w-full">
          <EmptyState
            icon={BellPlus}
            title={t('cloudNotifyChannelsEmptyTitle')}
            description={t('cloudNotifyChannelsEmptyDesc')}
            action={<div className="pt-1">{addMenu(true)}</div>}
          />
        </Card>
      ) : (
        <div className={channelGridClass(cards.length)}>
          {cards.map((card) => (
            <ChannelCard
              key={card.channel.id}
              card={card}
              disabled={locked}
              testing={testingId !== null}
              onEdit={() => setDialog({ kind: card.channel.kind, channel: card.channel })}
              onTest={() => void test(card.channel)}
              onToggle={() => void toggle(card.channel)}
              onDisconnect={() => void disconnect(card.channel)}
            />
          ))}
        </div>
      )}
      {dialog !== null ? (
        <CloudChannelDialog
          key={dialog.channel?.id ?? `new:${dialog.kind}`}
          target={dialog}
          overview={overview}
          onClose={() => setDialog(null)}
        />
      ) : null}
    </section>
  )
}

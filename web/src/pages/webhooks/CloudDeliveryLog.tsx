// 云端投递记录：第一页直接读快照 `cloudNotify.recentDeliveries`（agent 按云端 SSE 实时合并），
// 「加载更多」按游标追加更早记录并按 id 去重；pending 行脉冲点，状态变化颜色平滑过渡；对所有渠道发送测试。

import { BellOff, RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { useT } from '../../i18n'
import { cn } from '../../lib/cn'
import { rpc } from '../../lib/rpc'
import type { CloudNotifyChannelDto, CloudNotifyDeliveryDto } from '../../lib/rpc'
import { Button, Card, EmptyState, Spinner, toast } from '../../ui'
import { deliveryView, formatShortTime, nextDeliveriesCursor, testTargets, visibleDeliveries } from './cloudLogic'
import type { DeliveryTone } from './cloudLogic'
import { WEBHOOK_EVENTS } from './endpoints'
import { cloudActionError } from './useCloudNotify'

const PAGE_SIZE = 50

const TONE_DOT: Record<DeliveryTone, string> = {
  muted: 'bg-text-tertiary',
  success: 'bg-success',
  destructive: 'bg-destructive',
  warning: 'bg-warning',
}

const TONE_TEXT: Record<DeliveryTone, string> = {
  muted: 'text-muted-foreground',
  success: 'text-muted-foreground',
  destructive: 'text-destructive',
  warning: 'text-warning',
}

const EVENT_LABEL: Record<string, string> = Object.fromEntries(WEBHOOK_EVENTS.map((event) => [event.wire, event.labelKey]))

function DeliveryRow({ delivery }: { delivery: CloudNotifyDeliveryDto }) {
  const t = useT()
  const view = deliveryView(delivery)
  const event = EVENT_LABEL[delivery.event] ? t(EVENT_LABEL[delivery.event]) : delivery.event
  const status = t(view.statusKey)
  return (
    <div className="flex w-full min-w-0 items-center gap-3 border-b border-hairline px-2 py-2 last:border-b-0 narrow:flex-col narrow:items-stretch narrow:gap-1">
      <div className="flex min-w-0 flex-1 items-start gap-2.5">
        <span
          aria-hidden
          className={cn(
            'mt-1.5 size-1.5 shrink-0 rounded-full transition-colors duration-300',
            TONE_DOT[view.tone],
            delivery.status === 'pending' && 'animate-pulse',
          )}
        />
        <div className="flex min-w-0 flex-col gap-0.5">
          <div className="truncate text-sm text-foreground" title={delivery.title}>
            <span className="text-muted-foreground">{event}</span> · {delivery.title}
          </div>
          <div className="truncate text-xs text-muted-foreground">
            {t('cloudNotifyLogTarget', { channel: delivery.channelName, device: delivery.deviceName })}
          </div>
          {delivery.error ? (
            <div className="truncate text-xs text-destructive" title={delivery.error}>
              {delivery.error}
            </div>
          ) : null}
        </div>
      </div>
      <div className="flex shrink-0 items-center gap-3 pl-4 text-xs tabular narrow:justify-between narrow:pl-4">
        <span className={cn('transition-colors duration-300', TONE_TEXT[view.tone])}>
          {status}
          {view.showRetry ? ` · ${t('cloudNotifyLogRetry', { n: delivery.attempts, max: delivery.maxAttempts })}` : ''}
        </span>
        <span className="text-text-tertiary">{formatShortTime(delivery.createdAt)}</span>
      </div>
    </div>
  )
}

export function CloudDeliveryLog({
  channels,
  maxChannels,
  recent,
  recentCursor,
  loading,
  disabled,
}: {
  channels: readonly CloudNotifyChannelDto[]
  maxChannels: number
  /** 快照里的第一页（agent 实时维护）。 */
  recent: readonly CloudNotifyDeliveryDto[]
  recentCursor: string | null | undefined
  loading: boolean
  disabled: boolean
}) {
  const t = useT()
  // 「加载更多」追加的更早记录与其后续游标；与快照第一页重叠的以快照为准。
  const [loaded, setLoaded] = useState<{ items: readonly CloudNotifyDeliveryDto[]; cursor: string | null } | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [testing, setTesting] = useState(false)
  const [moreError, setMoreError] = useState<string | null>(null)

  const items = visibleDeliveries(recent, loaded?.items ?? [])
  const cursor = nextDeliveriesCursor(recentCursor, loaded)

  const more = async () => {
    if (cursor === null || loadingMore) return
    setLoadingMore(true)
    setMoreError(null)
    try {
      const page = await rpc.agent.cloudNotify.deliveries({ limit: PAGE_SIZE, before: cursor })
      setLoaded((current) => ({
        items: visibleDeliveries(current?.items ?? [], page.items),
        cursor: page.nextCursor ?? null,
      }))
    } catch (cause) {
      // 失败不推进游标：行内显示错误，再点「加载更多」即重试。
      setMoreError(cloudActionError(cause, t, maxChannels))
    } finally {
      setLoadingMore(false)
    }
  }

  const refresh = async () => {
    setRefreshing(true)
    try {
      await rpc.agent.cloudNotify.refresh()
    } catch (cause) {
      toast.error(cloudActionError(cause, t, maxChannels))
    } finally {
      setRefreshing(false)
    }
  }

  const targets = testTargets(channels)
  const sendTests = async () => {
    if (testing || targets.length === 0) return
    setTesting(true)
    const failures: string[] = []
    for (const channel of targets) {
      try {
        const result = await rpc.agent.cloudNotify.testChannel({ id: channel.id })
        if (!result.success) failures.push(`${channel.name}: ${result.error ?? ''}`)
      } catch (cause) {
        failures.push(`${channel.name}: ${cloudActionError(cause, t, maxChannels)}`)
      }
    }
    setTesting(false)
    if (failures.length === 0) toast.key('cloudNotifyTestOk', 'success')
    else toast.key('cloudNotifyTestFail', 'error', { error: failures.join('; ') })
  }

  return (
    <section className="flex w-full min-w-0 flex-col gap-2">
      <div className="flex flex-wrap items-end justify-between gap-2 px-1">
        <div className="flex min-w-0 flex-col gap-0.5">
          <h2 className="text-sm font-semibold text-foreground">{t('cloudNotifyLogTitle')}</h2>
          <p className="text-xs text-muted-foreground">{t('cloudNotifyLogSubtitle')}</p>
        </div>
        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            iconOnly
            icon={RefreshCw}
            aria-label={t('cloudNotifyRefresh')}
            title={t('cloudNotifyRefresh')}
            loading={refreshing}
            disabled={disabled || refreshing}
            onClick={() => void refresh()}
          />
          <Button
            loading={testing}
            disabled={disabled || testing || targets.length === 0}
            title={t('cloudNotifyLogSendTestHint')}
            onClick={() => void sendTests()}
          >
            {t('cloudNotifyLogSendTest')}
          </Button>
        </div>
      </div>
      <Card className="w-full min-w-0 p-2">
        {items.length === 0 ? (
          loading ? (
            <div className="flex justify-center py-8">
              <Spinner />
            </div>
          ) : (
            <EmptyState icon={BellOff} title={t('cloudNotifyLogEmpty')} />
          )
        ) : (
          <div className="flex w-full flex-col">
            {items.map((delivery) => (
              <DeliveryRow key={delivery.id} delivery={delivery} />
            ))}
            {moreError ? (
              <div role="alert" className="px-2 pt-2 text-center text-xs text-destructive">
                {moreError}
              </div>
            ) : null}
            {cursor !== null ? (
              <div className="flex justify-center pt-2">
                <Button loading={loadingMore} disabled={disabled || loadingMore} onClick={() => void more()}>
                  {t('cloudNotifyLogMore')}
                </Button>
              </div>
            ) : null}
          </div>
        )}
      </Card>
    </section>
  )
}

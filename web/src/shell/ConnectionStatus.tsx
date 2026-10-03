// 连接状态：标题栏右侧状态点 + 内容区顶部横幅（重连 / daemon 断开 / 服务已退出）。

import { CircleAlert, RefreshCw } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useT } from '../i18n'
import { cn } from '../lib/cn'
import { useConnection } from '../lib/rpc/hooks'
import { retryConnection } from '../lib/rpc/client'
import { Button, Icon, Tooltip } from '../ui'
import { useLinkHealth } from './useLinkHealth'
import type { LinkHealth } from './useLinkHealth'

const DOT: Record<LinkHealth, string> = {
  ok: 'bg-success',
  connecting: 'bg-warning animate-pulse',
  reconnecting: 'bg-warning animate-pulse',
  daemonOffline: 'bg-warning animate-pulse',
  stopped: 'bg-destructive',
  incompatible: 'bg-destructive',
}

function useCountdown(until: number | null): number | null {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (until === null) return
    setNow(Date.now())
    const id = setInterval(() => setNow(Date.now()), 500)
    return () => clearInterval(id)
  }, [until])
  return until === null ? null : Math.max(0, Math.ceil((until - now) / 1000))
}

function healthLabelKey(health: LinkHealth): string {
  switch (health) {
    case 'ok':
      return 'webConnected'
    case 'connecting':
      return 'webConnecting'
    case 'reconnecting':
      return 'webReconnecting'
    case 'daemonOffline':
      return 'webDaemonOffline'
    case 'stopped':
      return 'webServiceStopped'
    case 'incompatible':
      return 'webIncompatible'
  }
}

/** 标题栏右侧的小状态点（悬停显示原因）。 */
export function ConnectionDot() {
  const t = useT()
  const health = useLinkHealth()
  return (
    <Tooltip content={t(healthLabelKey(health))} side="bottom">
      <span className="inline-flex size-6 items-center justify-center" role="status" aria-label={t(healthLabelKey(health))}>
        <span className={cn('size-2 rounded-full', DOT[health])} />
      </span>
    </Tooltip>
  )
}

/** 内容区顶部横幅；健康时不渲染。 */
export function ConnectionBanner() {
  const t = useT()
  const health = useLinkHealth()
  const connection = useConnection()
  const seconds = useCountdown(health === 'reconnecting' ? connection.nextRetryAt : null)
  if (health === 'ok') return null
  const tone = health === 'stopped' || health === 'incompatible' ? 'bg-destructive/10 text-destructive' : 'bg-warning/10 text-warning'
  const message =
    health === 'reconnecting' && seconds !== null
      ? t('webReconnectingIn', { seconds, attempt: connection.attempt })
      : t(healthLabelKey(health))
  const canRetry = health === 'connecting' || health === 'reconnecting' || health === 'stopped'
  return (
    <div role="status" className={cn('flex shrink-0 items-center gap-2 border-b border-hairline px-3 py-1.5 text-xs', tone)}>
      <Icon icon={CircleAlert} size="md" />
      <span className="min-w-0 flex-1 truncate">{message}</span>
      {canRetry ? (
        <Button variant="ghost" icon={RefreshCw} className="h-6 px-2 text-xs" onClick={retryConnection}>
          {t('webRetry')}
        </Button>
      ) : null}
    </div>
  )
}

// 「云端推送」标签页：账号条 → 通知渠道 → 云端投递记录。
// 加载 / 未登录 / 拉取失败三种空态各自独立展示；渠道区在套餐未开通时禁用。

import { CloudAlert } from 'lucide-react'
import { useState } from 'react'
import { useT } from '../../i18n'
import { rpc } from '../../lib/rpc'
import type { CloudNotifyDeliveryDto } from '../../lib/rpc'
import { Button, Card, EmptyState } from '../../ui'
import { CloudAccountBar, CloudSignedOut } from './CloudAccountBar'
import { CloudChannels } from './CloudChannels'
import { CloudDeliveryLog } from './CloudDeliveryLog'
import { channelGridClass, notifyErrorText } from './cloudLogic'
import { useCloudNotifyState, useCloudSession, useLoadCloudNotify } from './useCloudNotify'

const NO_DELIVERIES: readonly CloudNotifyDeliveryDto[] = []

function CloudSkeleton() {
  return (
    <div aria-busy className="flex w-full flex-col gap-4">
      <Card className="h-24 w-full animate-pulse bg-nav-hover">{null}</Card>
      <div className={channelGridClass(2)}>
        {Array.from({ length: 2 }, (_, index) => (
          <Card key={index} className="h-[8.5rem] animate-pulse bg-nav-hover">{null}</Card>
        ))}
      </div>
    </div>
  )
}

export function CloudPushTab({ disabled }: { disabled: boolean }) {
  const t = useT()
  const session = useCloudSession()
  const state = useCloudNotifyState()
  const [refreshing, setRefreshing] = useState(false)
  useLoadCloudNotify(session !== null && !disabled)

  if (session === null) return <CloudSignedOut />

  const overview = state.overview ?? null

  const refresh = async () => {
    setRefreshing(true)
    try {
      await rpc.agent.cloudNotify.refresh()
    } catch {
      // 失败原因由 agent 经 `cloudNotifyChanged.lastErrorReason` 推送，这里无需重复展示。
    } finally {
      setRefreshing(false)
    }
  }

  let body
  if (overview !== null) {
    body = (
      <>
        <CloudChannels overview={overview} catalog={state.catalog ?? []} disabled={disabled} />
        <CloudDeliveryLog
          channels={overview.channels}
          maxChannels={overview.maxChannels}
          recent={state.recentDeliveries ?? NO_DELIVERIES}
          recentCursor={state.recentNextCursor}
          loading={state.loading ?? false}
          disabled={disabled}
        />
      </>
    )
  } else if (state.lastErrorReason && !state.loading) {
    const text = notifyErrorText(state.lastErrorReason, '', 0)
    body = (
      <Card className="w-full">
        <EmptyState
          icon={CloudAlert}
          title={t(text.key, text.params)}
          action={
            <Button loading={refreshing} disabled={disabled || refreshing} onClick={() => void refresh()}>
              {t('cloudNotifyRefresh')}
            </Button>
          }
        />
      </Card>
    )
  } else {
    body = <CloudSkeleton />
  }

  return (
    <>
      <CloudAccountBar session={session} state={state} disabled={disabled} />
      {body}
    </>
  )
}

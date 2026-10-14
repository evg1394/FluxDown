// 推送通知页：活动栏路由页（非设置分类）。对应 GPUI `crates/settings/src/webhook_view.rs`：
// 页头（pushNavTitle + pushNavDesc）+ 右侧分段标签 [云端推送 | 自托管 Webhook]，下方滚动区按标签切换——
// 云端推送（FluxCloud 代发）或自托管（端点 `webhook.endpoints` + 投递记录）。

import { useCallback, useEffect, useMemo, useState } from 'react'
import { useT } from '../../i18n'
import { rpc, useAgent, useConfigValue, useConnection } from '../../lib/rpc'
import { SegmentedTabs } from '../../ui'
import { CloudPushTab } from './CloudPushTab'
import { effectivePushTab, isCloudEnabled, pageDescKey } from './cloudLogic'
import type { PushTab } from './cloudLogic'
import { DeliveryLogGroup } from './DeliveryLogGroup'
import { ENDPOINTS_KEY, parseEndpoints } from './endpoints'
import { EndpointsGroup } from './EndpointsGroup'
import { useCloudNotifyState } from './useCloudNotify'
import { writeErrorText } from './write'
import type { RunWrite } from './write'

const selectDaemonConnected = (snapshot: { daemonConnected: boolean }) => snapshot.daemonConnected

export function WebhooksPage() {
  const t = useT()
  const phase = useConnection().phase
  const daemonConnected = useAgent(selectDaemonConnected, false)
  const [writeError, setWriteError] = useState<unknown>(null)
  const rawEndpoints = useConfigValue(ENDPOINTS_KEY)
  const endpointCount = useMemo(() => parseEndpoints(rawEndpoints).length, [rawEndpoints])
  // 用户没手动切换前跟随默认（已配置自托管端点 → 自托管，否则云端）。
  const [picked, setPicked] = useState<PushTab | null>(null)
  const catalog = useCloudNotifyState().catalog
  const cloudEnabled = isCloudEnabled(catalog)
  // catalog 关闭（未知 / 空）时云端推送整体隐藏，只渲染自托管内容。
  const tab: PushTab = effectivePushTab(cloudEnabled, picked, endpointCount)

  // 未连上 agent / daemon：数据只读（GPUI `daemon_connected()`）。
  const disconnected = phase !== 'ready' || !daemonConnected

  // 目录未知（None）且已连上 daemon：兜底触发一次读取，agent 会后台拉取公开 catalog。
  const catalogUnknown = catalog == null
  useEffect(() => {
    if (catalogUnknown && !disconnected) rpc.agent.cloudNotify.get().catch(() => undefined)
  }, [catalogUnknown, disconnected])

  const runWrite: RunWrite = useCallback(async (operation) => {
    setWriteError(null)
    try {
      await operation()
      return true
    } catch (error) {
      setWriteError(error)
      return false
    }
  }, [])

  const feedback = disconnected
    ? t('localServiceDisconnected')
    : tab === 'selfHosted' && writeError !== null
      ? writeErrorText(writeError, t)
      : null

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col bg-surface text-foreground">
      <header className="flex flex-none flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b border-hairline px-6 pt-4 pb-3 mobile:px-4">
        <div className="min-w-0">
          <h1 className="text-title font-semibold text-foreground">{t('pushNavTitle')}</h1>
          <p className="mt-0.5 text-xs text-muted-foreground">{t(pageDescKey(cloudEnabled))}</p>
        </div>
        {cloudEnabled ? (
          <SegmentedTabs
            aria-label={t('pushNavTitle')}
            value={tab}
            onValueChange={setPicked}
            items={[
              { value: 'cloud', label: t('pushTabCloud') },
              { value: 'selfHosted', label: t('pushTabSelfHosted') },
            ]}
          />
        ) : null}
      </header>
      {feedback ? (
        <div role="status" className="flex-none px-6 pt-3 text-xs text-destructive mobile:px-4">
          {feedback}
        </div>
      ) : null}
      <div className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden px-6 pt-5 pb-6 mobile:px-4 mobile:pt-4">
        <div className="mx-auto flex w-full min-w-0 max-w-4xl flex-col gap-4">
          {tab === 'cloud' ? (
            <CloudPushTab disabled={disconnected} />
          ) : (
            <>
              <EndpointsGroup
                disabled={disconnected}
                runWrite={runWrite}
                onTryCloud={cloudEnabled ? () => setPicked('cloud') : null}
              />
              <DeliveryLogGroup disabled={disconnected} runWrite={runWrite} />
            </>
          )}
        </div>
      </div>
    </div>
  )
}

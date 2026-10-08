// 配置同步行的状态说明（GPUI `cloud_features.rs::sync_subtitle`）：账户页云功能卡片与活动栏账户卡片共用。

import { useT } from '../../../../i18n'
import type { SyncStatusDto } from '../../../../lib/rpc'
import { REASON_KEYS } from './errorText'
import { syncPhase } from './syncGroups'

export function useSyncSubtitle(loggedIn: boolean, sync: SyncStatusDto): string {
  const t = useT()
  const phase = syncPhase(sync)
  // 同步失败 / 暂停原因：按 reason 本地化；未映射时回退通用文案，不显示服务端诊断原文。
  const reasonKey = sync.lastErrorReason ? REASON_KEYS[sync.lastErrorReason] : undefined
  const reasonText = t(reasonKey ?? 'cloudSyncErrorGeneric')
  if (!loggedIn || !sync.enabled) return t('cloudSyncDesc')
  if (phase === 'halted') return t('cloudSyncStatusHalted', { reason: reasonText })
  if (phase === 'error') return t('cloudSyncStatusError', { reason: reasonText })
  if (phase === 'connecting') return t('cloudSyncStatusConnecting')
  if (phase === 'syncing') return t('cloudSyncStatusSyncing')
  if (!sync.lastSyncedAtUnixMs) return t('cloudSyncStatusSynced')
  const date = new Date(sync.lastSyncedAtUnixMs)
  return t('cloudSyncStatusSyncedAt', { time: Number.isNaN(date.getTime()) ? '' : date.toLocaleString() })
}

// 云端推送状态 hook：快照 `cloudNotify` 投影 + 进入页面时让 agent 按需刷新概览。

import { useEffect } from 'react'
import type { TFunction } from '../../i18n'
import { RpcError, errorMessage, rpc, useAgent } from '../../lib/rpc'
import type { AgentSessionDto, AgentSnapshot, CloudNotifyStateDto } from '../../lib/rpc'
import { notifyErrorText } from './cloudLogic'

/** 旧快照缺失时视为默认（上报关、无概览）。 */
export const DEFAULT_CLOUD_STATE: CloudNotifyStateDto = {
  reporting: false,
  includeUrl: false,
  includeSaveDir: false,
  overview: null,
  loading: false,
  lastErrorReason: null,
  updatedAtUnixMs: null,
}

const selectState = (snapshot: AgentSnapshot) => snapshot.cloudNotify ?? DEFAULT_CLOUD_STATE
const selectSession = (snapshot: AgentSnapshot) => snapshot.session

export function useCloudNotifyState(): CloudNotifyStateDto {
  return useAgent(selectState, DEFAULT_CLOUD_STATE)
}

export function useCloudSession(): AgentSessionDto | null {
  return useAgent(selectSession, null)
}

/**
 * 已登录且连上本机服务时，挂载即读取一次（agent 缓存超过 30s 会后台刷新，
 * 结果经 `cloudNotifyChanged` 推到快照）。
 */
export function useLoadCloudNotify(active: boolean) {
  useEffect(() => {
    if (!active) return
    rpc.agent.cloudNotify.get().catch(() => undefined)
  }, [active])
}

/** RPC 失败 → 本地化文案（按 `ErrorReason`，缺省走通用文案）。 */
export function cloudActionError(error: unknown, t: TFunction, maxChannels: number): string {
  const reason = error instanceof RpcError ? error.reason : undefined
  if (error instanceof RpcError && (error.appCode === 'unavailable' || error.appCode === 'timeout')) {
    return t('localServiceDisconnected')
  }
  const text = notifyErrorText(reason, errorMessage(error), maxChannels)
  return t(text.key, text.params)
}

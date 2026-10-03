// 新建下载「下载到」目标：本服务器 / 云账号其他设备 / 已配对局域网设备的纯规则（校验、下发参数、汇总）。

import { effectivePathStyle, isAbsolutePath } from '../../../lib/rpc'
import type {
  CloudDevice,
  LinkDeviceInfo,
  LinkDispatchParams,
  PathStyle,
  RemoteDispatchParams,
} from '../../../lib/rpc'
import { otherDevices } from '../model/devices'

export const LOCAL_TARGET = 'local'

/** 下拉里的一个远端目标。`value` 形如 `cloud:<deviceId>` / `link:<fingerprint>`。 */
export interface RemoteTarget {
  value: string
  kind: 'cloud' | 'link'
  /** cloud = deviceId；link = 指纹。 */
  id: string
  name: string
  online: boolean | null
  /** 目标自报的默认下载目录；未上报为 null。 */
  defaultSaveDir: string | null
  /** 目标路径风格；无法判定为 null（此时只能交给 agent 校验）。 */
  pathStyle: PathStyle | null
}

export function buildRemoteTargets(
  cloudDevices: readonly CloudDevice[],
  linkedDevices: readonly LinkDeviceInfo[],
  presence: { cloud: boolean; local: boolean },
): RemoteTarget[] {
  const cloud = otherDevices(cloudDevices).map<RemoteTarget>((device) => ({
    value: `cloud:${device.deviceId}`,
    kind: 'cloud',
    id: device.deviceId,
    name: device.name || device.deviceId,
    online: presence.cloud ? device.isOnline : null,
    defaultSaveDir: device.defaultSaveDir?.trim() || null,
    pathStyle: effectivePathStyle(device),
  }))
  const linked = linkedDevices.map<RemoteTarget>((device) => ({
    value: `link:${device.fingerprint}`,
    kind: 'link',
    id: device.fingerprint,
    name: device.name || device.fingerprint,
    online: presence.local ? device.online : null,
    defaultSaveDir: device.defaultSaveDir?.trim() || null,
    pathStyle: effectivePathStyle(device),
  }))
  return [...cloud, ...linked]
}

export type SaveDirCheck = 'ok' | 'notAbsolute'

/** 空 = 使用目标默认目录；风格未知无法本地判定，放行交给 agent；否则必须是目标风格的绝对路径。 */
export function checkRemoteSaveDir(saveDir: string, style: PathStyle | null): SaveDirCheck {
  const dir = saveDir.trim()
  if (dir === '' || style === null || style === 'unknown') return 'ok'
  return isAbsolutePath(style, dir) ? 'ok' : 'notAbsolute'
}

/** 校验失败提示里的路径示例。 */
export function pathExample(style: PathStyle | null): string {
  return style === 'windows' ? 'D:\\Downloads' : '/home/user/Downloads'
}

interface DispatchInput {
  url: string
  fileName?: string
}

function optionalFields(input: DispatchInput, saveDir: string): { fileName?: string; saveDir?: string } {
  const fields: { fileName?: string; saveDir?: string } = {}
  const name = input.fileName?.trim()
  if (name) fields.fileName = name
  const dir = saveDir.trim()
  if (dir !== '') fields.saveDir = dir
  return fields
}

/** 目录为空则不带 `saveDir`（= 目标默认目录）。 */
export function remoteDispatchParams(toDevice: string, input: DispatchInput, saveDir: string): RemoteDispatchParams {
  return { toDevice, url: input.url, ...optionalFields(input, saveDir) }
}

export function linkDispatchParams(fingerprint: string, input: DispatchInput, saveDir: string): LinkDispatchParams {
  return { fingerprint, url: input.url, ...optionalFields(input, saveDir) }
}

export interface DispatchSummary {
  ok: number
  failed: number
}

export function summarizeDispatch(results: readonly PromiseSettledResult<unknown>[]): DispatchSummary {
  let ok = 0
  for (const result of results) if (result.status === 'fulfilled') ok += 1
  return { ok, failed: results.length - ok }
}

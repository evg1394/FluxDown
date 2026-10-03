import { describe, expect, test } from 'bun:test'
import type { CloudDevice, LinkDeviceInfo } from '../../../lib/rpc'
import {
  buildRemoteTargets,
  checkRemoteSaveDir,
  linkDispatchParams,
  remoteDispatchParams,
  summarizeDispatch,
} from './target'

function cloud(over: Partial<CloudDevice>): CloudDevice {
  return {
    id: 'row',
    deviceId: 'dev',
    name: 'n',
    platform: null,
    createdAt: '',
    lastSeenAt: '',
    lastIp: null,
    appVersion: null,
    isOnline: false,
    isCurrent: false,
    ...over,
  }
}

describe('远端保存目录校验', () => {
  test('空目录 = 目标默认目录，任何风格都合法', () => {
    expect(checkRemoteSaveDir('  ', 'windows')).toBe('ok')
    expect(checkRemoteSaveDir('', 'posix')).toBe('ok')
  })

  test('按目标路径风格判定绝对路径，而非本机风格', () => {
    expect(checkRemoteSaveDir('D:\\dl', 'windows')).toBe('ok')
    expect(checkRemoteSaveDir('\\\\nas\\share', 'windows')).toBe('ok')
    expect(checkRemoteSaveDir('/home/a', 'windows')).toBe('notAbsolute')
    expect(checkRemoteSaveDir('/home/a', 'posix')).toBe('ok')
    expect(checkRemoteSaveDir('D:\\dl', 'posix')).toBe('notAbsolute')
    expect(checkRemoteSaveDir('relative/dir', 'posix')).toBe('notAbsolute')
  })

  test('风格未知无法本地判定，放行给 agent', () => {
    expect(checkRemoteSaveDir('whatever', null)).toBe('ok')
    expect(checkRemoteSaveDir('whatever', 'unknown')).toBe('ok')
  })
})

describe('下发参数', () => {
  test('空目录省略 saveDir；空文件名省略 fileName', () => {
    expect(remoteDispatchParams('d', { url: 'http://x/a', fileName: ' ' }, '')).toEqual({ toDevice: 'd', url: 'http://x/a' })
    expect(remoteDispatchParams('d', { url: 'u', fileName: 'a.bin' }, ' /dl ')).toEqual({
      toDevice: 'd',
      url: 'u',
      fileName: 'a.bin',
      saveDir: '/dl',
    })
    expect(linkDispatchParams('fp', { url: 'u' }, '')).toEqual({ fingerprint: 'fp', url: 'u' })
  })
})

describe('目标列表', () => {
  test('云设备去掉本机，路径风格未上报时按平台推断', () => {
    const linked: LinkDeviceInfo[] = [
      { fingerprint: 'fp', name: 'Phone', online: true, pairedAt: 0, lastSeenAt: 0, platform: 'android', defaultSaveDir: '/sdcard' },
    ]
    const targets = buildRemoteTargets(
      [
        cloud({ deviceId: 'me', isCurrent: true }),
        cloud({ deviceId: 'pc', name: 'PC', platform: 'windows', isOnline: true, defaultSaveDir: 'C:\\Dl' }),
        cloud({ deviceId: 'x', platform: 'weird', pathStyle: 'posix' }),
      ],
      linked,
      { cloud: true, local: true },
    )
    expect(targets.map((target) => target.value)).toEqual(['cloud:pc', 'cloud:x', 'link:fp'])
    expect([targets[0]?.pathStyle, targets[0]?.defaultSaveDir, targets[0]?.online]).toEqual(['windows', 'C:\\Dl', true])
    expect(targets[1]?.pathStyle).toBe('posix')
    expect([targets[2]?.pathStyle, targets[2]?.defaultSaveDir]).toEqual(['posix', '/sdcard'])
  })

  test('cloud disconnection hides stale presence without hiding dispatch targets or LAN presence', () => {
    const linked: LinkDeviceInfo[] = [
      { fingerprint: 'fp', name: 'Phone', online: true, pairedAt: 0, lastSeenAt: 0 },
    ]
    const cloudDevices = [cloud({ isOnline: true })]
    const targets = buildRemoteTargets(cloudDevices, linked, { cloud: false, local: true })
    expect(targets.map((target) => target.online)).toEqual([null, true])
    expect(targets.map((target) => target.value)).toEqual(['cloud:dev', 'link:fp'])
    expect(buildRemoteTargets(cloudDevices, linked, { cloud: false, local: false }).map((target) => target.online))
      .toEqual([null, null])
  })

  test('汇总成功 / 失败条数', () => {
    expect(
      summarizeDispatch([
        { status: 'fulfilled', value: 1 },
        { status: 'rejected', reason: new Error('x') },
        { status: 'fulfilled', value: 2 },
      ]),
    ).toEqual({ ok: 2, failed: 1 })
  })
})

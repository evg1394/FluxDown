import { describe, expect, test } from 'bun:test'
import type { PluginAuthResponse } from '../../../../lib/rpc/protocol'
import { encodeQrChallengeImage } from './challengeImage'
import { applyPluginAuthResponse, isQrcodeChallenge } from './logic'
import type { PluginAuthState } from './logic'

const LOGIN_URL = 'https://passport.bilibili.com/h5-app/passport/login/scan?qrcode_key=test-key'
const pending: PluginAuthState = {
  status: 'pending',
  sessionId: 'session',
  authRef: '',
  challenge: LOGIN_URL,
  challengeType: 'QrCoDe',
  message: 'Waiting for confirmation',
}

function response(patch: Partial<PluginAuthResponse> = {}): PluginAuthResponse {
  return {
    status: 'pending',
    sessionId: 'session',
    authRef: null,
    challenge: null,
    challengeType: null,
    message: '',
    ...patch,
  }
}

describe('plugin auth transitions', () => {
  test('pending poll 缺失或 null 挑战时保留原文与二维码类型', () => {
    const omitted = { status: 'pending', sessionId: 'session', authRef: null, message: 'Waiting' } as PluginAuthResponse
    for (const poll of [omitted, response()]) {
      const next = applyPluginAuthResponse(pending, poll, false)
      expect(next.challenge).toBe(LOGIN_URL)
      expect(next.challengeType).toBe('QrCoDe')
      expect(isQrcodeChallenge(next.challengeType)).toBe(true)
      expect(next.status).toBe('pending')
    }
  })

  test('替换挑战使用新原文，显式空挑战不恢复旧二维码', () => {
    const replaced = applyPluginAuthResponse(pending, response({ challenge: 'new-key', challengeType: 'text' }), false)
    expect(replaced.challenge).toBe('new-key')
    expect(isQrcodeChallenge(replaced.challengeType)).toBe(false)
    const empty = applyPluginAuthResponse(pending, response({ challenge: '' }), false)
    expect(empty.challenge).toBe('')
  })

  test('success/error 终态清除未返回的挑战并保留对应提示语义', () => {
    const success = applyPluginAuthResponse(pending, response({ status: 'success', authRef: 'account', message: 'Logged in' }), false)
    expect(success.status).toBe('success')
    expect(success.authRef).toBe('account')
    expect(success.challenge).toBeNull()
    expect(success.challengeType).toBeNull()
    expect(success.message).toBe('Logged in')
    const error = applyPluginAuthResponse(pending, response({ status: 'error', message: 'Expired' }), false)
    expect(error.status).toBe('error')
    expect(error.challenge).toBeNull()
    expect(error.challengeType).toBeNull()
    expect(error.message).toBe('Expired')
  })

  test('logout 即使插件失败且返回旧档案仍清空本地登录态与挑战', () => {
    const next = applyPluginAuthResponse(pending, response({
      status: 'error',
      authRef: 'old-account',
      challenge: LOGIN_URL,
      challengeType: 'qrcode',
      message: 'Logout failed',
    }), true)
    expect(next.authRef).toBe('')
    expect(next.sessionId).toBe('')
    expect(next.challenge).toBeNull()
    expect(next.challengeType).toBeNull()
    expect(next.message).toBe('Logout failed')
  })
})

describe('local QR challenge image', () => {
  test('普通登录 URL 生成 240px PNG 而不是远端图片 URL', async () => {
    const src = await encodeQrChallengeImage(LOGIN_URL)
    expect(src?.startsWith('data:image/png;base64,')).toBe(true)
    const png = Buffer.from(src!.slice('data:image/png;base64,'.length), 'base64')
    expect(png.subarray(0, 8)).toEqual(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
    expect(png.readUInt32BE(16)).toBe(240)
    expect(png.readUInt32BE(20)).toBe(240)
  })

  test('空文本、编码容量溢出与不可信超大文本均安全回退', async () => {
    expect(await encodeQrChallengeImage('')).toBeNull()
    // M 纠错级别 version 40 最多容纳 2331 bytes；UTF-8 多字节文本同样必须按字节容量处理。
    expect(await encodeQrChallengeImage('a'.repeat(2332))).toBeNull()
    expect(await encodeQrChallengeImage('😀'.repeat(583))).toBeNull()
    expect(await encodeQrChallengeImage('9'.repeat(7090))).toBeNull()
  })
})

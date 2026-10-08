// agent `/rpc` WebSocket JSON-RPC 客户端（单例连接）。
//
// 流程：WebSocket 子协议鉴权 → system.hello → system.snapshot → 按 service.event 增量应用。
// - 事件序号断档 / epoch 变化 / 关闭码 4009：重新 snapshot（同一连接内先缓冲后回放）；
// - 关闭原因 `service-quit`：不再重连；
// - 其他断线：指数退避重连，网络恢复/页面回到前台立即重试；
// - 升级前失败（浏览器拿不到 HTTP 状态）：探测 `/api/v1/info` 区分密钥无效 / 需初始化 / 网络问题。

import { applyAgentEvent } from './apply'
import { judgeFrame, type EventCursor } from './cursor'
import { RpcError, TRANSPORT_ERROR_CODE, errorMessage, transportError } from './error'
import {
  CAPABILITY_CLIENT_SELECTIONS,
  CLOSE_REASON_SERVICE_QUIT,
  CLOSE_REASON_SERVICE_RESTART,
  EVENT_GAP_CLOSE_CODE,
  MIN_PROTOCOL_VERSION,
  PROTOCOL_VERSION,
} from './protocol'
import { claimIncompatibleReload, shouldReloadForVersionChange } from './reload'
import type { AgentEvent, ClientHello, EventFrame, RpcErrorData, ServiceHello, Snapshot } from './protocol'
import { probeToken } from '../access/setup'
import { rpcStore, setConnection } from './store'

const SUBPROTOCOL = 'fluxdown.rpc.v1'
const TOKEN_PREFIX = 'fluxdown.token.'
const CLIENT_VERSION: string = import.meta.env.VITE_APP_VERSION ?? 'dev'

const DEFAULT_TIMEOUT_MS = 30_000
const PING_INTERVAL_MS = 25_000
const PING_TIMEOUT_MS = 10_000
const BACKOFF_BASE_MS = 500
const BACKOFF_MAX_MS = 15_000
/** 同一次连接内连续 resync 的上限，超过则断开重连。 */
const MAX_RESYNC = 5
/** 快照期间事件缓冲上限，溢出视为断档。 */
const MAX_BUFFERED = 20_000

export interface CallOptions {
  /** 请求超时（ms），默认 30s。安装类长任务请调大。 */
  timeoutMs?: number
}

interface Pending {
  method: string
  resolve: (value: unknown) => void
  reject: (error: RpcError) => void
  timer: ReturnType<typeof setTimeout>
}

type EventListener = (frame: EventFrame) => void

function base64Url(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '')
}

function wsUrl(): string {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${scheme}//${location.host}/rpc`
}

class RpcConnection {
  private ws: WebSocket | null = null
  private generation = 0
  private token = ''
  private wanted = false
  private helloDone = false
  private everReady = false
  /** 最近一次成功握手的 serviceVersion；重启后版本变化即刷新页面。 */
  private lastServiceVersion: string | null = null
  private attempt = 0
  private nextId = 1
  private readonly pending = new Map<number, Pending>()
  private cursor: EventCursor | null = null
  private syncing = false
  private buffered: EventFrame[] = []
  private resyncs = 0
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  private pingTimer: ReturnType<typeof setInterval> | null = null
  private readonly listeners = new Set<EventListener>()
  private envHooked = false

  /** 以访问密钥启动（幂等：相同密钥且已在运行则忽略）。 */
  start(token: string): void {
    if (this.wanted && this.token === token) return
    this.teardown()
    this.token = token
    this.wanted = true
    this.everReady = false
    this.attempt = 0
    this.hookEnvironment()
    this.open()
  }

  /** 就地替换访问密钥（服务端轮换后）：不断开、不重拉快照，下次重连才用新值。 */
  setToken(token: string): void {
    if (this.token !== '' && token !== '') this.token = token
  }

  /** 当前连接使用的密钥（unauthorized 时用来与存储值比较）。 */
  currentToken(): string {
    return this.token
  }

  /** 停止并清空状态（登出）。 */
  stop(): void {
    this.wanted = false
    this.token = ''
    this.lastServiceVersion = null
    this.teardown()
    rpcStore.reset()
  }

  /** 手动立即重试（用户点「重试」）；服务已退出（stopped）时也允许重新拉起。 */
  retryNow(): void {
    if (this.token === '') return
    if (this.ws?.readyState === WebSocket.OPEN && this.helloDone && !this.syncing && this.cursor !== null) return
    this.teardown()
    this.wanted = true
    this.attempt = 0
    this.open()
  }

  /** 环境事件（网络恢复/回到前台）触发的重试：只在仍处于自动重连态时生效。 */
  private poke(): void {
    if (!this.wanted || this.retryTimer === null) return
    this.clearRetry()
    this.open()
  }

  subscribeEvents(listener: EventListener): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  call<R>(method: string, params?: object, options?: CallOptions): Promise<R> {
    return this.request<R>(method, params, options?.timeoutMs ?? DEFAULT_TIMEOUT_MS, false)
  }

  // ---- 连接生命周期 -------------------------------------------------------

  private hookEnvironment(): void {
    if (this.envHooked) return
    this.envHooked = true
    window.addEventListener('online', () => this.poke())
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') this.poke()
    })
  }

  private open(): void {
    const generation = ++this.generation
    setConnection({
      phase: this.everReady || this.attempt > 0 ? 'reconnecting' : 'connecting',
      nextRetryAt: null,
    })
    let ws: WebSocket
    try {
      ws = new WebSocket(wsUrl(), [SUBPROTOCOL, TOKEN_PREFIX + base64Url(this.token)])
    } catch (error) {
      this.scheduleRetry(errorMessage(error))
      return
    }
    this.ws = ws
    let opened = false
    ws.onopen = () => {
      if (generation !== this.generation) return
      if (ws.protocol !== SUBPROTOCOL) {
        ws.close(4000, 'subprotocol-mismatch')
        return
      }
      opened = true
      void this.handshake(generation).catch((error: unknown) => this.onHandshakeFailure(generation, error))
    }
    ws.onmessage = (event) => {
      if (generation !== this.generation) return
      if (typeof event.data === 'string') this.onMessage(event.data)
    }
    ws.onclose = (event) => {
      if (generation !== this.generation) return
      void this.onClose(generation, event, opened)
    }
    ws.onerror = () => {
      // 错误后必有 close，统一在 onClose 处理。
    }
  }

  private async handshake(generation: number): Promise<void> {
    const hello: ClientHello = {
      clientName: 'fluxdown-web',
      clientVersion: CLIENT_VERSION,
      minProtocolVersion: MIN_PROTOCOL_VERSION,
      maxProtocolVersion: PROTOCOL_VERSION,
      requestedRole: 'agent',
      capabilities: [CAPABILITY_CLIENT_SELECTIONS],
    }
    // 握手前的 call 必须绕过 helloDone 检查。
    const service = await this.request<ServiceHello>('system.hello', hello, DEFAULT_TIMEOUT_MS, true)
    if (generation !== this.generation) return
    this.helloDone = true
    const serviceVersion = service.serviceVersion ?? ''
    if (shouldReloadForVersionChange(this.lastServiceVersion, serviceVersion)) {
      location.reload()
      return
    }
    this.lastServiceVersion = serviceVersion
    rpcStore.update((state) => ({ ...state, hello: service }), true)
    this.resyncs = 0
    await this.sync(generation)
  }

  private onHandshakeFailure(generation: number, error: unknown): void {
    if (generation !== this.generation) return
    if (error instanceof RpcError) {
      if (error.is('protocolIncompatible')) {
        // Docker / NAS 升级跨协议版本：新 SPA 在新服务里，刷新一次取新资源（限频，避免死循环）。
        if (claimIncompatibleReload()) {
          location.reload()
          return
        }
        this.fatal('incompatible', error.message)
        return
      }
      if (error.is('unauthorized')) {
        this.fatal('unauthorized', error.message)
        return
      }
    }
    // 请求已失败，不等待 WebSocket 关闭握手才退出当前连接。
    this.teardown()
    this.scheduleRetry(errorMessage(error))
  }

  /** 拉取快照并回放缓冲事件。 */
  private async sync(generation: number): Promise<void> {
    this.syncing = true
    this.buffered = []
    this.cursor = null
    setConnection({ phase: 'syncing' })
    const snapshot = await this.request<Snapshot>('system.snapshot', undefined, DEFAULT_TIMEOUT_MS, true)
    if (generation !== this.generation) return
    if (snapshot.body.role !== 'agent') throw new Error('unexpected snapshot role')
    const agent = snapshot.body.snapshot
    this.cursor = { epoch: snapshot.epoch, sequence: snapshot.sequence }
    rpcStore.update((state) => ({ ...state, snapshot: agent }), true)

    const backlog = this.buffered
    this.buffered = []
    this.syncing = false
    for (const frame of backlog) {
      if (!this.handleFrame(frame, generation)) return
    }
    this.everReady = true
    this.attempt = 0
    setConnection({ phase: 'ready', attempt: 0, nextRetryAt: null, lastError: null, restarting: false })
    this.startPing()
  }

  private onMessage(text: string): void {
    let message: unknown
    try {
      message = JSON.parse(text)
    } catch {
      return
    }
    if (typeof message !== 'object' || message === null) return
    const record = message as Record<string, unknown>
    if ('id' in record && ('result' in record || 'error' in record)) {
      this.onResponse(record)
      return
    }
    if (record.method === 'service.event' && typeof record.params === 'object' && record.params !== null) {
      const frame = record.params as EventFrame
      if (this.syncing) {
        if (this.buffered.length >= MAX_BUFFERED) {
          this.buffered = []
          void this.resync(this.generation)
          return
        }
        this.buffered.push(frame)
        return
      }
      this.handleFrame(frame, this.generation)
    }
  }

  /** 处理一个事件帧；返回 false 表示已触发重新同步/断线，调用方应停止回放。 */
  private handleFrame(frame: EventFrame, generation: number): boolean {
    const cursor = this.cursor
    if (!cursor) return false
    const verdict = judgeFrame(cursor, frame)
    if (verdict === 'skip') return true
    if (verdict === 'resync') {
      void this.resync(generation)
      return false
    }
    cursor.sequence = frame.sequence
    if (frame.event.service === 'agent') {
      const event: AgentEvent = frame.event.event
      rpcStore.update((state) => (state.snapshot ? { ...state, snapshot: applyAgentEvent(state.snapshot, event) } : state))
    }
    for (const listener of this.listeners) listener(frame)
    return true
  }

  private async resync(generation: number): Promise<void> {
    if (generation !== this.generation || this.syncing) return
    this.resyncs += 1
    if (this.resyncs > MAX_RESYNC) {
      this.ws?.close(4000, 'resync-loop')
      return
    }
    try {
      await this.sync(generation)
    } catch (error) {
      this.onHandshakeFailure(generation, error)
    }
  }

  private async onClose(generation: number, event: CloseEvent, opened: boolean): Promise<void> {
    this.ws = null
    // 先隔离该连接的异步握手/快照 continuation，再拒绝在途请求。
    generation = ++this.generation
    this.stopPing()
    this.helloDone = false
    this.syncing = false
    this.cursor = null
    this.buffered = []
    this.rejectAll(transportError('connection closed', 'unavailable'))
    if (!this.wanted) return

    if (event.reason === CLOSE_REASON_SERVICE_RESTART) {
      // server 模式自更新重启：保持重连（含退避），握手发现 serviceVersion 变化后刷新页面。
      setConnection({ restarting: true })
      this.scheduleRetry(event.reason)
      return
    }
    if (event.reason === CLOSE_REASON_SERVICE_QUIT) {
      this.fatal('stopped', event.reason)
      return
    }
    if (event.code === EVENT_GAP_CLOSE_CODE) {
      // 事件落后：立即重连并重新 snapshot，不计退避。
      this.open()
      return
    }
    if (!opened) {
      const probe = await probeToken(this.token)
      if (generation !== this.generation || !this.wanted) return
      if (probe === 'unauthorized') return this.fatal('unauthorized', 'unauthorized')
      if (probe === 'setupRequired') return this.fatal('setupRequired', 'setup required')
      if (probe === 'unknown' && !this.everReady) return this.fatal('unauthorized', 'rejected')
    }
    this.scheduleRetry(event.reason || `closed (${event.code})`)
  }

  private fatal(phase: 'unauthorized' | 'setupRequired' | 'stopped' | 'incompatible', message: string): void {
    this.teardown()
    this.wanted = false
    setConnection({ phase, nextRetryAt: null, lastError: message, restarting: false })
  }

  private scheduleRetry(reason: string): void {
    this.clearRetry()
    const exponent = Math.min(this.attempt, 10)
    const base = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2 ** exponent)
    const delay = Math.round(base * (0.8 + Math.random() * 0.4))
    this.attempt += 1
    setConnection({
      phase: 'reconnecting',
      attempt: this.attempt,
      nextRetryAt: Date.now() + delay,
      lastError: reason,
    })
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      if (this.wanted) this.open()
    }, delay)
  }

  private clearRetry(): void {
    if (this.retryTimer !== null) clearTimeout(this.retryTimer)
    this.retryTimer = null
  }

  private teardown(): void {
    this.clearRetry()
    this.stopPing()
    this.generation += 1
    const ws = this.ws
    this.ws = null
    if (ws) {
      ws.onopen = ws.onmessage = ws.onclose = ws.onerror = null
      ws.close(1000, 'client-stop')
    }
    this.helloDone = false
    this.syncing = false
    this.cursor = null
    this.buffered = []
    this.rejectAll(transportError('connection closed', 'unavailable'))
  }

  // ---- keepalive ---------------------------------------------------------

  private startPing(): void {
    this.stopPing()
    this.pingTimer = setInterval(() => {
      const generation = this.generation
      this.request('system.ping', undefined, PING_TIMEOUT_MS, false).catch(() => {
        if (generation !== this.generation) return
        // 服务端同通道串行处理：有长耗时请求在途时 ping 排队属正常，不据此断线。
        for (const pending of this.pending.values()) {
          if (pending.method !== 'system.ping') return
        }
        this.ws?.close(4000, 'ping-timeout')
      })
    }, PING_INTERVAL_MS)
  }

  private stopPing(): void {
    if (this.pingTimer !== null) clearInterval(this.pingTimer)
    this.pingTimer = null
  }

  // ---- 请求/响应 ---------------------------------------------------------

  private request<R>(method: string, params: object | undefined, timeoutMs: number, handshake: boolean): Promise<R> {
    const ws = this.ws
    if (!ws || ws.readyState !== WebSocket.OPEN || (!handshake && !this.helloDone)) {
      return Promise.reject(transportError('not connected', 'unavailable', method))
    }
    const id = this.nextId++
    const frame = { jsonrpc: '2.0', id, method, ...(params === undefined ? {} : { params }) }
    return new Promise<R>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(transportError(`request timed out: ${method}`, 'timeout', method))
      }, timeoutMs)
      this.pending.set(id, { method, resolve: resolve as (value: unknown) => void, reject, timer })
      try {
        ws.send(JSON.stringify(frame))
      } catch (error) {
        clearTimeout(timer)
        this.pending.delete(id)
        reject(transportError(errorMessage(error), 'unavailable', method))
      }
    })
  }

  private onResponse(record: Record<string, unknown>): void {
    const id = record.id
    if (typeof id !== 'number') return
    const pending = this.pending.get(id)
    if (!pending) return
    this.pending.delete(id)
    clearTimeout(pending.timer)
    if ('error' in record && typeof record.error === 'object' && record.error !== null) {
      const error = record.error as { code?: number; message?: string; data?: RpcErrorData }
      pending.reject(
        new RpcError({
          code: error.code ?? TRANSPORT_ERROR_CODE,
          message: error.message ?? 'rpc error',
          data: error.data,
          method: pending.method,
        }),
      )
      return
    }
    pending.resolve(record.result)
  }

  private rejectAll(error: RpcError): void {
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer)
      pending.reject(error)
      this.pending.delete(id)
    }
  }
}

const connection = new RpcConnection()

export const startConnection = (token: string): void => connection.start(token)
export const setConnectionToken = (token: string): void => connection.setToken(token)
export const connectionToken = (): string => connection.currentToken()
export const stopConnection = (): void => connection.stop()
export const retryConnection = (): void => connection.retryNow()
export const subscribeServiceEvents = (listener: EventListener): (() => void) => connection.subscribeEvents(listener)

/** 通用 RPC 调用（方法包装器 `rpc.*` 的底层入口）。 */
export function call<R = unknown>(method: string, params?: object, options?: CallOptions): Promise<R> {
  return connection.call<R>(method, params, options)
}

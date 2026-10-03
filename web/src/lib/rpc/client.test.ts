import { afterEach, beforeEach, describe, expect, jest, spyOn, test } from 'bun:test'
import { call, retryConnection, startConnection, stopConnection } from './client'
import { rpcStore } from './store'

type Request = { id: number; method: string }

class MockWebSocket {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSING = 2
  static readonly CLOSED = 3
  static instances: MockWebSocket[] = []
  readyState = MockWebSocket.CONNECTING
  protocol = 'fluxdown.rpc.v1'
  onopen: (() => void) | null = null
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: ((event: { code: number; reason: string }) => void) | null = null
  onerror: (() => void) | null = null
  sent: Request[] = []

  constructor(readonly url: string, readonly protocols: string[]) {
    MockWebSocket.instances.push(this)
  }

  open(): void {
    this.readyState = MockWebSocket.OPEN
    this.onopen?.()
  }

  send(text: string): void {
    this.sent.push(JSON.parse(text) as Request)
  }

  // A browser close handshake need not deliver onclose immediately.
  close(): void {
    this.readyState = MockWebSocket.CLOSING
  }

  closed(code = 1006, reason = ''): void {
    this.readyState = MockWebSocket.CLOSED
    this.onclose?.({ code, reason })
  }

  reply(method: string, result: unknown): void {
    const request = this.sent.findLast((item) => item.method === method)
    if (!request) throw new Error(`missing request: ${method}`)
    this.onmessage?.({ data: JSON.stringify({ jsonrpc: '2.0', id: request.id, result }) })
  }

  fail(method: string, code: string): void {
    const request = this.sent.findLast((item) => item.method === method)
    if (!request) throw new Error(`missing request: ${method}`)
    this.onmessage?.({
      data: JSON.stringify({ id: request.id, error: { code: -32000, message: code, data: { code } } }),
    })
  }
}

const globals = ['WebSocket', 'window', 'document', 'location'] as const
const descriptors = new Map(globals.map((key) => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
const flush = async () => {
  for (let i = 0; i < 10; i++) await Promise.resolve()
}
const snapshot = { epoch: 'epoch', sequence: 0, body: { role: 'agent', snapshot: { daemonConnected: true } } }

function start(): MockWebSocket {
  startConnection('test-token')
  return MockWebSocket.instances[MockWebSocket.instances.length - 1]!
}

async function hello(ws: MockWebSocket): Promise<void> {
  ws.open()
  ws.reply('system.hello', { protocolVersion: 1 })
  await flush()
}

async function ready(ws: MockWebSocket): Promise<void> {
  await hello(ws)
  ws.reply('system.snapshot', snapshot)
  await flush()
}

beforeEach(() => {
  jest.useFakeTimers()
  MockWebSocket.instances = []
  Object.defineProperties(globalThis, {
    WebSocket: { configurable: true, writable: true, value: MockWebSocket },
    window: { configurable: true, value: new EventTarget() },
    document: { configurable: true, value: new EventTarget() },
    location: { configurable: true, value: { protocol: 'http:', host: 'localhost:17800' } },
  })
})

afterEach(async () => {
  stopConnection()
  await flush()
  jest.restoreAllMocks()
  jest.useRealTimers()
  for (const key of globals) {
    const descriptor = descriptors.get(key)
    if (descriptor) Object.defineProperty(globalThis, key, descriptor)
    else Reflect.deleteProperty(globalThis, key)
  }
})

describe('RPC connection lifecycle', () => {
  test('manual retry replaces CONNECTING socket; queued old callbacks are isolated', async () => {
    const old = start()
    const oldOpen = old.onopen!
    const oldMessage = old.onmessage!
    const oldClose = old.onclose!
    retryConnection()
    expect(MockWebSocket.instances).toHaveLength(2)
    expect(old.readyState).toBe(MockWebSocket.CLOSING)
    const current = MockWebSocket.instances[1]!
    await ready(current)
    const pending = call('test.read')
    const id = current.sent.findLast((item) => item.method === 'test.read')!.id
    oldOpen()
    oldMessage({ data: JSON.stringify({ id, result: 'stale' }) })
    oldClose({ code: 1000, reason: 'service-quit' })
    current.reply('test.read', 'current')
    expect(await pending).toBe('current')
    expect(old.sent).toHaveLength(0)
    expect(rpcStore.peek().connection.phase).toBe('ready')
  })

  test('manual retry does not replace a healthy OPEN socket', async () => {
    const ws = start()
    await ready(ws)
    retryConnection()
    expect(MockWebSocket.instances).toHaveLength(1)
    expect(rpcStore.peek().connection.phase).toBe('ready')
  })

  for (const stage of ['hello', 'snapshot'] as const) {
    test(`manual retry replaces an OPEN socket stalled during ${stage}`, async () => {
      const ws = start()
      if (stage === 'hello') ws.open()
      else await hello(ws)
      retryConnection()
      expect(ws.readyState).toBe(MockWebSocket.CLOSING)
      expect(MockWebSocket.instances).toHaveLength(2)
      await ready(MockWebSocket.instances[1]!)
      expect(rpcStore.peek().connection.phase).toBe('ready')
    })

    test(`${stage} timeout retries without waiting for close and rejects pending calls`, async () => {
      const ws = start()
      if (stage === 'hello') ws.open()
      else await hello(ws)
      let outcome: unknown
      if (stage === 'snapshot') {
        void call('test.long', undefined, { timeoutMs: 120_000 }).catch((error: unknown) => { outcome = error })
      }
      jest.advanceTimersByTime(30_000)
      await flush()
      expect(ws.readyState).toBe(MockWebSocket.CLOSING)
      expect(rpcStore.peek().connection.phase).toBe('reconnecting')
      expect(rpcStore.peek().connection.attempt).toBe(1)
      if (stage === 'snapshot') expect(outcome).toMatchObject({ data: { code: 'unavailable' } })
      jest.advanceTimersByTime(1_000)
      expect(MockWebSocket.instances).toHaveLength(2)
    })

    test(`closing during ${stage} schedules exactly one retry`, async () => {
      const ws = start()
      if (stage === 'hello') ws.open()
      else await hello(ws)
      ws.closed()
      await flush()
      expect(rpcStore.peek().connection.attempt).toBe(1)
      jest.advanceTimersByTime(1_000)
      expect(MockWebSocket.instances).toHaveLength(2)
    })
  }

  test('fatal snapshot error immediately rejects other pending RPCs', async () => {
    const ws = start()
    await hello(ws)
    let outcome: unknown
    void call('test.long').catch((error: unknown) => { outcome = error })
    ws.fail('system.snapshot', 'unauthorized')
    await flush()
    expect(rpcStore.peek().connection.phase).toBe('unauthorized')
    expect(outcome).toMatchObject({ data: { code: 'unavailable' } })
    jest.advanceTimersByTime(60_000)
    expect(MockWebSocket.instances).toHaveLength(1)
  })

  test('stop rejects pending RPCs and ignores already queued responses', async () => {
    const ws = start()
    await ready(ws)
    const message = ws.onmessage!
    const pending = call('test.read').catch((error: unknown) => error)
    stopConnection()
    message({ data: JSON.stringify({ id: ws.sent.at(-1)!.id, result: 'late' }) })
    expect(await pending).toMatchObject({ data: { code: 'unavailable' } })
    expect(rpcStore.peek().connection.phase).toBe('idle')
    jest.advanceTimersByTime(60_000)
    expect(MockWebSocket.instances).toHaveLength(1)
  })

  test('service quit rejects pending RPCs, stays stopped, and permits manual retry', async () => {
    const ws = start()
    await ready(ws)
    const pending = call('test.read').catch((error: unknown) => error)
    ws.closed(1000, 'service-quit')
    expect(await pending).toMatchObject({ data: { code: 'unavailable' } })
    expect(rpcStore.peek().connection.phase).toBe('stopped')
    jest.advanceTimersByTime(60_000)
    expect(MockWebSocket.instances).toHaveLength(1)
    retryConnection()
    expect(MockWebSocket.instances).toHaveLength(2)
  })

  test('ordinary RPC timeout rejects only that request and ignores a late response', async () => {
    const ws = start()
    await ready(ws)
    const pending = call('test.slow', undefined, { timeoutMs: 100 }).catch((error: unknown) => error)
    jest.advanceTimersByTime(100)
    expect(await pending).toMatchObject({ data: { code: 'timeout' }, method: 'test.slow' })
    ws.reply('test.slow', 'late')
    const next = call('test.read')
    ws.reply('test.read', 'ok')
    expect(await next).toBe('ok')
    expect(rpcStore.peek().connection.phase).toBe('ready')
  })

  test('a queued snapshot continuation cannot publish ready after socket close', async () => {
    const ws = start()
    await hello(ws)
    ws.reply('system.snapshot', snapshot)
    ws.closed()
    await flush()
    expect(rpcStore.peek().connection.phase).toBe('reconnecting')
    expect(rpcStore.peek().snapshot).toBeNull()
    expect(rpcStore.peek().connection.attempt).toBe(1)
  })

  test('a retained snapshot stays stale until the reconnect snapshot arrives', async () => {
    const first = start()
    await ready(first)
    const retained = rpcStore.peek().snapshot
    first.closed()
    await flush()
    jest.advanceTimersByTime(1_000)
    const next = MockWebSocket.instances[1]!
    await hello(next)
    expect(rpcStore.peek().snapshot).toBe(retained)
    expect(rpcStore.peek().connection.phase).toBe('syncing')
    next.reply('system.snapshot', snapshot)
    await flush()
    expect(rpcStore.peek().connection.phase).toBe('ready')
  })

  test('event gaps make the retained snapshot stale during resync, and allow retry', async () => {
    const ws = start()
    await ready(ws)
    ws.onmessage?.({ data: JSON.stringify({
      method: 'service.event',
      params: { epoch: 'epoch', sequence: 2, event: { service: 'agent', event: { type: 'daemonConnectionChanged', data: false } } },
    }) })
    expect(rpcStore.peek().connection.phase).toBe('syncing')
    expect(rpcStore.peek().snapshot).not.toBeNull()
    retryConnection()
    expect(MockWebSocket.instances).toHaveLength(2)
    await ready(MockWebSocket.instances[1]!)
    expect(rpcStore.peek().connection.phase).toBe('ready')
  })

  test('an old upgrade probe cannot mark a replacement socket unauthorized', async () => {
    let resolveProbe!: (response: Response) => void
    spyOn(globalThis, 'fetch').mockImplementation(() => new Promise<Response>((resolve) => { resolveProbe = resolve }))
    const ws = start()
    ws.closed()
    retryConnection()
    const current = MockWebSocket.instances[1]!
    await ready(current)
    resolveProbe(new Response('', { status: 401 }))
    await flush()
    expect(rpcStore.peek().connection.phase).toBe('ready')
    expect(current.readyState).toBe(MockWebSocket.OPEN)
  })
})

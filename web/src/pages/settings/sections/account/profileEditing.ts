import type { AgentSessionDto, OriginIdCheckResult } from '../../../../lib/rpc'

export function canEditOriginId(session: AgentSessionDto): boolean {
  return session.entitlements.originIdEdit === true && !session.user.originIdChanged
}

// Rust str::trim uses Unicode White_Space (JS trim differs for U+0085 / U+FEFF).
export function trimmedNickname(value: string): string {
  return value.replace(/^\p{White_Space}+|\p{White_Space}+$/gu, '')
}

export function validNickname(value: string): boolean {
  const length = [...trimmedNickname(value)].length
  return length >= 1 && length <= 32 && !/[\uD800-\uDFFF]/u.test(trimmedNickname(value))
}

export function parseOriginId(value: string): number | null {
  const parsed = /^\d+$/.test(value.trim()) ? Number(value.trim()) : NaN
  return Number.isSafeInteger(parsed) && parsed >= 10000 ? parsed : null
}

type Check = 'idle' | 'checking' | 'available' | 'taken' | 'invalid'
interface Draft {
  value: string
  check: Check
  busy: boolean
  error: unknown | null
  confirmed: boolean
  submittingId: number | null
}
interface ProfileRpc {
  checkOriginId(params: { value: number }): Promise<OriginIdCheckResult>
  randomOriginId(): Promise<{ originId: number }>
  changeOriginId(params: { originId: number }): Promise<unknown>
}

// One revision owns every asynchronous result, including random suggestions and save.
export class OriginIdEditor {
  private revision = 0
  private listeners = new Set<() => void>()
  private state: Draft = { value: '', check: 'idle', busy: false, error: null, confirmed: false, submittingId: null }

  constructor(private current: number | null, private api: ProfileRpc) {}

  snapshot = (): Draft => this.state
  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => { this.listeners.delete(listener) }
  }
  private update(patch: Partial<Draft>) {
    this.state = { ...this.state, ...patch }
    for (const listener of this.listeners) listener()
  }
  invalidate = () => { this.revision++ }

  setValue(value: string) {
    if (this.state.busy) return
    this.invalidate()
    const parsed = parseOriginId(value)
    this.update({ value, confirmed: false, error: null, check: !value.trim() || parsed === this.current ? 'idle' : parsed === null ? 'invalid' : 'checking' })
  }
  setConfirmed(confirmed: boolean) {
    if (!this.state.busy) this.update({ confirmed })
  }

  acceptsSession(session: AgentSessionDto): boolean {
    return session.entitlements.originIdEdit === true && (!session.user.originIdChanged || (this.state.submittingId !== null && this.state.submittingId === session.user.originId))
  }

  canSubmit(): boolean {
    const parsed = parseOriginId(this.state.value)
    return !this.state.busy && this.state.confirmed && parsed !== null && parsed !== this.current && this.state.check === 'available'
  }

  async check() {
    const parsed = parseOriginId(this.state.value)
    if (this.state.busy || this.state.check !== 'checking' || parsed === null || parsed === this.current) return
    const revision = ++this.revision
    this.update({ check: 'checking', error: null })
    try {
      const result = await this.api.checkOriginId({ value: parsed })
      if (revision !== this.revision) return
      this.update({ check: result.available ? 'available' : result.reason === 'invalid' ? 'invalid' : 'taken' })
    } catch (error) {
      if (revision === this.revision) this.update({ check: 'idle', error })
    }
  }

  async roll() {
    if (this.state.busy) return
    const revision = ++this.revision
    this.update({ busy: true, confirmed: false, check: 'idle', error: null })
    try {
      const { originId } = await this.api.randomOriginId()
      if (revision !== this.revision) return
      this.update({ busy: false })
      this.setValue(String(originId))
    } catch (error) {
      if (revision === this.revision) this.update({ busy: false, error })
    }
  }

  async submit(): Promise<boolean> {
    if (!this.canSubmit()) return false
    const originId = parseOriginId(this.state.value)!
    const revision = ++this.revision
    this.update({ busy: true, submittingId: originId, error: null })
    try {
      await this.api.changeOriginId({ originId })
      return revision === this.revision
    } catch (error) {
      if (revision === this.revision) this.update({ busy: false, submittingId: null, check: 'idle', confirmed: false, error })
      return false
    }
  }
}

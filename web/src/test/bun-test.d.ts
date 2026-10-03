// bun:test 的最小类型声明（未引入 @types/bun；仅覆盖本项目单测用到的 API）。
declare module 'bun:test' {
  interface Matchers {
    toBe(expected: unknown): void
    toEqual(expected: unknown): void
    toBeNull(): void
    toBeUndefined(): void
    toBeTruthy(): void
    toBeFalsy(): void
    toHaveLength(expected: number): void
    toMatchObject(expected: unknown): void
    toThrow(expected?: unknown): void
    not: Matchers
  }
  export function describe(name: string, body: () => void): void
  export function beforeAll(body: () => void | Promise<void>): void
  export function beforeEach(body: () => void | Promise<void>): void
  export function afterEach(body: () => void | Promise<void>): void
  export const jest: {
    useFakeTimers(): void
    useRealTimers(): void
    advanceTimersByTime(milliseconds: number): void
    restoreAllMocks(): void
  }
  export function spyOn<T extends object, K extends keyof T>(object: T, method: K): {
    mockImplementation(implementation: T[K]): void
  }
  export function test(name: string, body: () => void | Promise<void>): void
  export function it(name: string, body: () => void | Promise<void>): void
  export function expect(actual: unknown): Matchers
}

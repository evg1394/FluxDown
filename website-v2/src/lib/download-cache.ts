import { DownloadLookupError } from "./download-policy";

export interface LookupResult<T> { value: T; stale: boolean }
interface Entry<T> { value: T; at: number }

// 一个共享 api-cache 槽位容纳有界下载缓存；webhook 清槽后旧在途请求只写旧实例。
export class DownloadLookupCache<T> {
  private readonly entries = new Map<string, Entry<T>>();
  private readonly inflight = new Map<string, Promise<LookupResult<T>>>();

  constructor(
    private readonly freshMs = 120_000,
    private readonly maxAgeMs = 600_000,
    private readonly capacity = 256,
    private readonly now = Date.now,
  ) {}

  async lookup(key: string, load: () => Promise<T>): Promise<LookupResult<T>> {
    const entry = this.entries.get(key);
    if (entry && this.now() - entry.at < this.freshMs) {
      return { value: entry.value, stale: false };
    }
    const pending = this.inflight.get(key);
    if (pending) return pending;
    if (this.inflight.size >= this.capacity) {
      throw new DownloadLookupError("Download lookup capacity exceeded", false);
    }
    const work = Promise.resolve().then(load).then((value) => {
      this.entries.delete(key);
      while (this.entries.size >= this.capacity) {
        this.entries.delete(this.entries.keys().next().value!);
      }
      this.entries.set(key, { value, at: this.now() });
      return { value, stale: false };
    }).catch((error: unknown) => {
      if (error instanceof DownloadLookupError && error.transient && entry &&
          this.now() - entry.at < this.maxAgeMs) {
        // 不刷新时间戳：持续故障不能无限延长旧数据的生命。
        return { value: entry.value, stale: true };
      }
      this.entries.delete(key);
      throw error;
    }).finally(() => this.inflight.delete(key));
    this.inflight.set(key, work);
    return work;
  }
}

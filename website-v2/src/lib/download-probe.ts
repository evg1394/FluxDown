import { matchesAssetSize } from "./download-policy";

export function createOssProbe(
  signHead: (key: string) => string,
  request: (input: string, init: RequestInit) => Promise<Response> =
    (input, init) => fetch(input, init),
  now = Date.now,
  capacity = 256,
) {
  const cache = new Map<string, { until: number; present: boolean }>();
  const inflight = new Map<string, Promise<boolean>>();
  return async (key: string, expectedSize: number): Promise<boolean> => {
    if (!Number.isSafeInteger(expectedSize) || expectedSize < 0) return false;
    const cacheKey = JSON.stringify([key, expectedSize]);
    const hit = cache.get(cacheKey);
    if (hit && now() < hit.until) return hit.present;
    const pending = inflight.get(cacheKey);
    if (pending) return pending;
    // 过载视作备用不可用，不再发起无界 HEAD 或保存无界 Promise。
    if (inflight.size >= capacity) return false;
    const work = Promise.resolve().then(async () => {
      let present = false;
      try {
        const response = await request(signHead(key), {
          method: "HEAD",
          signal: AbortSignal.timeout(2500),
          redirect: "error",
        });
        present = matchesAssetSize(response, expectedSize);
      } catch {
        // 签名、超时、网络错误均按未验证对象处理。
      }
      cache.delete(cacheKey);
      while (cache.size >= capacity) cache.delete(cache.keys().next().value!);
      cache.set(cacheKey, {
        until: now() + (present ? 3_600_000 : 60_000), present,
      });
      return present;
    }).finally(() => inflight.delete(cacheKey));
    inflight.set(cacheKey, work);
    return work;
  };
}

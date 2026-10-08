/**
 * dl-fluxdown.zerx.dev：Cloudflare 边缘缓存 + 私有阿里云 OSS 回源。
 *
 * 官网 /api/download 把旧客户端 / 应用内更新 302 到这里（取代直出 OSS 预签名 URL），
 * 命中边缘缓存的流量不再计 OSS 外网流出费用。
 *
 * - 只放行 GET / HEAD 与 `${PREFIX}/` 下的对象键；桶保持私有。
 * - 回源 URL 用固定过期时间签名（V1 query 签名）：同一对象的回源 URL 恒定，
 *   CF 缓存键稳定；签名 URL 只在 CF 与 OSS 之间出现，不下发给客户端。
 * - 发布资产按版本目录不可变，边缘缓存 30 天；Range 由 CF 缓存层切片。
 *
 * 部署：cd website-v2/cdn && bunx wrangler deploy（见同目录 wrangler.toml），密钥
 * OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET 用 `bunx wrangler secret put` 注入。
 */

// 2100-01-01T00:00:00Z：回源签名恒定，保证缓存键稳定。
const SIGN_EXPIRES = 4102444800;
const EDGE_TTL = 30 * 24 * 3600;
const BROWSER_TTL = 24 * 3600;
const PASS_HEADERS = [
  "content-type",
  "content-length",
  "content-range",
  "content-disposition",
  "accept-ranges",
  "etag",
  "last-modified",
];

export default {
  async fetch(request, env) {
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method Not Allowed", { status: 405, headers: { Allow: "GET, HEAD" } });
    }
    const url = new URL(request.url);
    let key;
    try {
      key = decodeURIComponent(url.pathname.slice(1));
    } catch {
      return new Response("Bad Request", { status: 400 });
    }
    const segments = key.split("/");
    if (
      segments.length < 2 ||
      segments[0] !== env.OSS_RELEASE_PREFIX ||
      segments.some((s) => s === "" || s === "." || s === "..")
    ) {
      return new Response("Not Found", { status: 404 });
    }

    const origin = await presign(env, key);
    const headers = new Headers();
    const range = request.headers.get("range");
    if (range) headers.set("range", range);
    const upstream = await fetch(origin, {
      method: "GET",
      headers,
      cf: { cacheEverything: true, cacheTtlByStatus: { "200-299": EDGE_TTL, "400-599": 0 } },
    });

    if (!upstream.ok) {
      // 不透出 OSS 错误体（含 bucket / request id）。
      const status = upstream.status === 403 || upstream.status === 404 ? 404 : 502;
      return new Response(status === 404 ? "Not Found" : "Bad Gateway", {
        status,
        headers: { "cache-control": "no-store" },
      });
    }
    const out = new Headers();
    for (const name of PASS_HEADERS) {
      const value = upstream.headers.get(name);
      if (value !== null) out.set(name, value);
    }
    out.set("cache-control", `public, max-age=${BROWSER_TTL}, immutable`);
    const cacheStatus = upstream.headers.get("cf-cache-status");
    if (cacheStatus) out.set("x-edge-cache", cacheStatus);
    return new Response(request.method === "HEAD" ? null : upstream.body, {
      status: upstream.status,
      headers: out,
    });
  },
};

async function presign(env, key) {
  const stringToSign = `GET\n\n\n${SIGN_EXPIRES}\n/${env.OSS_BUCKET}/${key}`;
  const hmacKey = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(env.OSS_ACCESS_KEY_SECRET),
    { name: "HMAC", hash: "SHA-1" },
    false,
    ["sign"],
  );
  const mac = await crypto.subtle.sign("HMAC", hmacKey, new TextEncoder().encode(stringToSign));
  const signature = btoa(String.fromCharCode(...new Uint8Array(mac)));
  const path = key.split("/").map(encodeURIComponent).join("/");
  const query = new URLSearchParams({
    OSSAccessKeyId: env.OSS_ACCESS_KEY_ID,
    Expires: String(SIGN_EXPIRES),
    Signature: signature,
  });
  return `https://${env.OSS_BUCKET}.${env.OSS_ENDPOINT}/${path}?${query}`;
}

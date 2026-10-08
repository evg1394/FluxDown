/**
 * 阿里云 OSS 发布资产源。
 *
 * 发布资产由 .github/actions/oss-upload 上传到 `oss://<bucket>/<prefix>/<tag>/<file>`，
 * bucket 保持私有（对象不可公共读）。下载 302 目标：配置了 `OSS_CDN_BASE` 时走
 * Cloudflare 边缘缓存（website-v2/cdn/oss-proxy.js 回源私有桶，命中不计 OSS 外网流出），
 * 否则官网签出短期预签名 URL 直出 OSS。
 * 签名算法：https://help.aliyun.com/zh/oss/developer-reference/signature-version-1
 */

import { createHmac } from "node:crypto";
import {
  OSS_ACCESS_KEY_ID,
  OSS_ACCESS_KEY_SECRET,
  OSS_BUCKET,
  OSS_CDN_BASE,
  OSS_ENDPOINT,
  OSS_RELEASE_PREFIX,
} from "astro:env/server";

/** 未配置 AK/SK 时整条 OSS 路径关闭（下载路由回退 GitHub）。 */
export const ossConfigured = !!(OSS_ACCESS_KEY_ID && OSS_ACCESS_KEY_SECRET);

/**
 * 发布资产的对象键（无前导斜杠）：`<prefix>/<版本>/<组件>/<file>`。
 * 目录由 release tag 推导：`v0.4.8` → `v0.4.8/app/`（历史目录名；统一 release 起
 * 该目录承载这个 vX.Y.Z release 的全部组件资产），历史组件 release `extension-v…` /
 * `server-v…` / `cli-v…` / `mobile-v…` → 同名组件目录；版本目录保留 `v` 前缀。
 * 同一套规则在 .github/actions/oss-upload/action.yml 的 bash 里复刻，改一处须同步另一处。
 */
export function releaseObjectKey(tag: string, filename: string): string {
  const m = /^(?:([a-z]+)-)?(v.+)$/.exec(tag);
  const component = m?.[1] ?? "app";
  const version = m?.[2] ?? tag;
  return `${OSS_RELEASE_PREFIX}/${version}/${component}/${filename}`;
}

/**
 * 生成 `ttlSec` 秒内有效的预签名 URL。签名串用原始 key，URL 路径按段编码
 * （OSS 用解码后的资源路径校验签名）。调用前须保证 `ossConfigured`。
 */
export function presignOssUrl(
  method: "GET" | "HEAD",
  key: string,
  ttlSec: number,
): string {
  const expires = Math.floor(Date.now() / 1000) + ttlSec;
  const stringToSign = `${method}\n\n\n${expires}\n/${OSS_BUCKET}/${key}`;
  const signature = createHmac("sha1", OSS_ACCESS_KEY_SECRET ?? "")
    .update(stringToSign)
    .digest("base64");
  const path = key.split("/").map(encodeURIComponent).join("/");
  const query = new URLSearchParams({
    OSSAccessKeyId: OSS_ACCESS_KEY_ID ?? "",
    Expires: String(expires),
    Signature: signature,
  });
  return `https://${OSS_BUCKET}.${OSS_ENDPOINT}/${path}?${query}`;
}

/**
 * 发布资产的下载 URL：优先 CDN（URL 恒定，便于多分段 Range 与边缘缓存），
 * 未配置 `OSS_CDN_BASE` 时回退 1h 预签名直链。调用前须保证 `ossConfigured`。
 */
export function ossDownloadUrl(key: string): string {
  const base = (OSS_CDN_BASE ?? "").replace(/\/+$/, "");
  if (!base) return presignOssUrl("GET", key, 3600);
  return `${base}/${key.split("/").map(encodeURIComponent).join("/")}`;
}

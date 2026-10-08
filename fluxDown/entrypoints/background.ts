/**
 * FluxDown Background Service Worker
 *
 * 职责：
 * 1. 拦截浏览器下载事件，转发给 FluxDown 桌面应用
 * 2. 注册右键菜单（发送链接到 FluxDown）
 * 3. 管理与 Native Host 的通信
 * 4. 响应 popup 的消息
 * 5. 维护拦截统计数据
 * 6. 多语言支持
 *
 * === 下载拦截三层防线 ===
 *
 * 第一层（HTTP 响应感知）: webRequest.onHeadersReceived
 *   - 监听所有请求的响应头
 *   - 当响应包含 Content-Disposition: attachment 或 下载类 Content-Type 时，
 *     将该 URL 标记为"已知下载"，缓存 Content-Type / Content-Length / 文件名等
 *   - 为后续 onCreated 兜底提供可靠的元数据来源
 *
 * 第二层（主拦截）: downloads.onDeterminingFilename
 *   - 浏览器弹出「另存为」之前触发，先请求取消，再 suggest() 释放文件名管线
 *   - 最优先、最干净的拦截方式
 *   - 但对 JS location.href / meta refresh 触发的"导航转下载"存在 MV3 时序问题
 *
 * 第三层（兜底拦截）: downloads.onCreated + onChanged
 *   - onCreated 始终可靠触发，配合 onChanged 等待元数据就绪后再判断
 *   - 如果 onDeterminingFilename 在限定时间内未处理，由此层接管
 *   - 利用第一层缓存的 HTTP 响应信息来补全 downloadItem 中缺失的元数据
 */

import { browser } from 'wxt/browser';
import { installConnectionDiagnostics } from "@/utils/connection-diagnostics-background";
import { defineBackground } from 'wxt/utils/define-background';
import {
  sendDownloadRequest,
  sendBatchDownloadRequest,
  checkFluxDownAvailable,
  checkFluxDownAvailableWithRetry,
  warmupNativeHost,
  isUnreachableFailure,
} from "@/utils/download-dispatch";
import {
  nmhListTasks,
  nmhTaskOp,
  nmhOpenFile,
  nmhRevealFile,
  nmhWarmupNativeHost,
} from "@/utils/native-messaging";
import type {
  DownloadRequest,
  BatchDownloadItem,
  TaskBrief,
} from "@/utils/native-messaging";
import { REMOTE_SETTINGS_KEY } from "@/utils/remote-settings";
import { loadSettings, persistSettings, shouldIntercept } from "@/utils/settings";
import type { DownloadItemInfo } from "@/utils/settings";
import { cancelBeforeFilenameResolution } from "@/utils/download-cancellation";
import { initI18n, t } from "@/utils/i18n";
import {
  dispositionNeedsPageCharset,
  extractCleanFilename,
  parseContentDispositionFilename,
  readDispositionHeader,
  resolveDownloadFilename,
} from "@/utils/filename";
import {
  matchSniffRule,
  classifyResource,
  extractFilenameFromUrl,
  normalizeUrlForDedup,
} from "@/utils/resource-types";
import type { ResourceMessagePayload } from "@/utils/resource-types";
import { normalizeDashManifest } from "@/utils/dash-manifest";
import type { DashManifest } from "@/utils/dash-manifest";
import {
  buildMediaCandidates,
  countMediaCandidateRows,
} from "@/utils/media-candidates";
import {
  addResources,
  addSniffedResource,
  getResourcesForTab,
  clearResourcesForTab,
  updateBadgeForTab,
  getActionApi,
  initTabLifecycleListeners,
} from "@/utils/resource-store";

// ===== 统计相关 =====
interface DailyStats {
  sent: number;
  failed: number;
  date: string;
}

// Bug 4 修复：用序列化 Promise 链防止 incrementStat 并发读写竞态
// chrome.storage 不提供事务，多个并发 get→modify→set 会导致写入丢失
let _statChain: Promise<void> = Promise.resolve();

function incrementStat(field: "sent" | "failed"): Promise<void> {
  _statChain = _statChain.then(async () => {
    try {
      const today = new Date().toDateString();
      const result = await browser.storage.local.get("stats");
      let stats: DailyStats = result.stats || { sent: 0, failed: 0, date: "" };
      if (stats.date !== today) {
        stats = { sent: 0, failed: 0, date: today };
      }
      stats[field]++;
      await browser.storage.local.set({ stats });
    } catch {
      /* storage 失败不影响主流程 */
    }
  });
  return _statChain;
}

/**
 * 从选中文本中提取一个可下载的 magnet:/ed2k:/http(s) 纯文本链接（#348）。
 * 仅接受完整且合法的链接格式，避免把选区里夹带的其他文字误判为下载源。
 */
function extractSelectionDownloadUrl(raw: string | undefined): string | null {
  if (!raw) return null;
  const text = raw.trim();
  if (!text) return null;
  // magnet 链接：xt=urn:<hash-algo>:<hash> 是必需字段
  if (/^magnet:\?xt=urn:[a-zA-Z0-9]+:[a-zA-Z0-9]+/i.test(text)) return text;
  // ed2k 链接：ed2k://|file|<name>|<size>|<32位十六进制 hash>|/
  if (/^ed2k:\/\/\|file\|[^|]+\|\d+\|[a-fA-F0-9]{32}\|\/?$/i.test(text)) {
    return text;
  }
  // 纯文本形式的完整 http(s) 直链
  if (/^https?:\/\/\S+$/i.test(text)) return text;
  return null;
}

export default defineBackground(() => {
  const handleDiagnostics = installConnectionDiagnostics();
  console.log("[FluxDown] Background service worker started");

  // ===== P3: settings 内存缓存 =====
  // 避免每次拦截都 await chrome.storage.sync.get（热路径去异步化）
  // storage.onChanged 保证跨标签页/窗口的实时同步
  let _settingsCache: import("@/utils/settings").FluxDownSettings | null = null;
  let _settingsCacheTs = 0;
  // 缓存永不主动过期，完全依赖 storage.onChanged 事件失效。
  // 这确保同步路径（防止下载 UI 闪现的关键）覆盖率接近 100%，
  // 仅在 Service Worker 冷启动的第一个下载时缓存为 null。
  const SETTINGS_CACHE_TTL = Number.MAX_SAFE_INTEGER;
  // Bug 8 修复：用 inflight Promise 防止并发调用时发起多次 loadSettings
  let _settingsInflight: Promise<
    import("@/utils/settings").FluxDownSettings
  > | null = null;
  // Bug R3-1/R3-9 修复：用版本序号防止 inflight 竞态
  // storage.onChanged 可能在 inflight 期间触发，导致旧值写回覆盖新设置。
  // 每次 storage 变化时递增版本号，inflight 完成时检查版本是否已变，已变则丢弃。
  let _settingsVersion = 0;

  async function getCachedSettings() {
    const now = Date.now();
    if (_settingsCache && now - _settingsCacheTs < SETTINGS_CACHE_TTL) {
      return _settingsCache;
    }
    // 已有 inflight 请求则复用，避免并发发起多次 storage.sync.get
    if (_settingsInflight) return _settingsInflight;
    const versionAtStart = _settingsVersion;
    _settingsInflight = loadSettings()
      .then((s) => {
        // 版本已变说明 storage.onChanged 在 loadSettings() 期间触发，
        // 当前结果是旧版本的设置，丢弃缓存写入并强制下次重新加载
        if (_settingsVersion === versionAtStart) {
          _settingsCache = s;
          _settingsCacheTs = Date.now();
        }
        _settingsInflight = null;
        return s;
      })
      .catch((e) => {
        _settingsInflight = null;
        throw e;
      });
    return _settingsInflight;
  }

  // 监听 storage 变化，立即失效缓存
  browser.storage.onChanged.addListener((changes, area) => {
    if (
      (area === "sync" && changes.settings) ||
      (area === "local" && changes[REMOTE_SETTINGS_KEY])
    ) {
      _settingsCache = null;
      _settingsCacheTs = 0;
      _settingsInflight = null;
      _settingsVersion++; // 使任何进行中的 inflight 结果失效
      // 设置变更时同步更新图标和下载 UI 隐藏状态
      getCachedSettings()
        .then((s) => {
          updateIcon(s.enabled);
          syncDownloadShelfState(s.enabled);
          setDeterminingFilenameListenerActive(s.enabled);
        })
        .catch(() => {});
    }
  });

  // #527/#609/#199/#293：onDeterminingFilename 的实际注册状态。拦截关闭后
  // 必须真正 removeListener 把该事件让给其他扩展（脚本猫/PDM/IDM 等）——
  // 仅在回调里 suggest() 放行不够，Chrome 只要本扩展仍注册着 listener 就
  // 视为参与"确定文件名"的竞争。初次注册在下方 handleDeterminingFilename
  // 定义处完成（保留 MV3 冷启动唤醒可靠性 + 现有冷启动预防拦截逻辑），
  // 这里只声明状态位与切换函数，供 warmup / storage.onChanged 回调调用。
  let _determiningFilenameListenerActive = false;
  function setDeterminingFilenameListenerActive(active: boolean) {
    if (!browser.downloads.onDeterminingFilename) return;
    if (active === _determiningFilenameListenerActive) return;
    if (active) {
      browser.downloads.onDeterminingFilename.addListener(
        handleDeterminingFilename,
      );
    } else {
      browser.downloads.onDeterminingFilename.removeListener(
        handleDeterminingFilename,
      );
    }
    _determiningFilenameListenerActive = active;
  }

  // ===== 同类产品核心策略：隐藏浏览器下载 UI =====
  // IDM / Motrix / FDM 等下载管理器均通过此 API 全局禁用浏览器下载栏，
  // 防止 cancel/erase 下载时下载条/下载栏短暂闪现。
  // Chrome 116+ 使用 setUiOptions，旧版使用 setShelfEnabled（Chrome 117+ 已弃用）。
  // Firefox 不支持这些 API，但 Firefox 下使用 webRequest blocking 方案无此问题。
  function syncDownloadShelfState(enabled: boolean) {
    const noop = () => {};
    try {
      const downloads = browser.downloads as any;
      const uiEnabled = !enabled; // 拦截启用时隐藏 UI，拦截禁用时恢复 UI
      if (downloads.setShelfEnabled) {
        // 旧版 Chrome（< 117），同步 API，不返回 Promise
        downloads.setShelfEnabled(uiEnabled);
      }
      if (downloads.setUiOptions) {
        // Chrome 116+，返回 Promise，需要 .catch() 捕获异步权限错误
        Promise.resolve(downloads.setUiOptions({ enabled: uiEnabled })).catch(
          noop,
        );
      }
    } catch {
      // Firefox 等不支持这些 API 的浏览器，静默忽略
    }
  }

  // 启动时预热缓存
  // R5-8 修复：加 .catch 防止 loadSettings 失败产生未捕获 rejection 警告
  getCachedSettings()
    .then((s) => {
      updateIcon(s.enabled);
      // 同类产品（IDM/Motrix/FDM）共同使用的策略：启动时立即隐藏下载 UI
      syncDownloadShelfState(s.enabled);
      setDeterminingFilenameListenerActive(s.enabled);
      console.log("[FluxDown] Settings cache warmed up");
    })
    .catch((e) => {
      console.warn("[FluxDown] Settings cache warmup failed (non-fatal):", e);
    });

  // 初始化 i18n
  // R7-2 修复：加 .catch() 防止意外异常成为未捕获 rejection 噪音
  initI18n()
    .then(() => {
      console.log("[FluxDown] i18n initialized");
      // i18n 就绪后创建右键菜单（菜单标题需要翻译）
      createContextMenus();
    })
    .catch((e) => {
      console.warn("[FluxDown] i18n init failed (non-fatal):", e);
    });

  // 初始化 tab 生命周期监听器（自动清理关闭/导航的 tab 资源）
  initTabLifecycleListeners();

  // ===== DASH manifest tab 级存储（权威清晰度 + 轨道 URL，仿 resource-store）=====
  // 同一页面可能同时存在多个播放器/播放会话，按规范化后的 manifest URL 保留
  // 一个有界集合，由 UI 再投影为多个视频候选。
  const tabDashManifests = new Map<number, Map<string, DashManifest>>();
  const MAX_DASH_MANIFESTS_PER_TAB = 8;
  const tabPageUrls = new Map<number, string>();
  const tabResourceVersions = new Map<number, number>();
  const tabManifestVersions = new Map<number, number>();
  const tabCandidateCache = new Map<number, {
    resourceVersion: number;
    manifestVersion: number;
    candidates: ReturnType<typeof buildMediaCandidates>;
  }>();

  function bumpTabVersion(versions: Map<number, number>, tabId: number): void {
    versions.set(tabId, (versions.get(tabId) || 0) + 1);
    tabCandidateCache.delete(tabId);
  }

  function clearTabProjection(tabId: number): void {
    tabDashManifests.delete(tabId);
    clearResourcesForTab(tabId);
    bumpTabVersion(tabResourceVersions, tabId);
    bumpTabVersion(tabManifestVersions, tabId);
  }

  /**
   * origin+pathname+search（忽略 hash）。Chrome 的 `tabs.onUpdated` 对
   * fragment 导航（`#comment-123`）和 `history.pushState`/`replaceState`
   * 都会发出带 `changeInfo.url`、不带 `status` 的事件；同页面播放器点锚点/
   * 用 replaceState 写回状态时 hash 常变而内容不变，若按原始 URL 整串比较
   * 会把已嗅探到的分片/清单/认证上下文误判为"换页"而整体清空。
   */
  function pageUrlKey(url: string): string {
    try {
      const u = new URL(url);
      return `${u.origin}${u.pathname}${u.search}`;
    } catch {
      return url;
    }
  }

  /** Also catches history.pushState navigations reported by content scripts. */
  function syncTabPageUrl(tabId: number, pageUrl: string): void {
    if (!pageUrl) return;
    const previous = tabPageUrls.get(tabId);
    if (previous && pageUrlKey(previous) !== pageUrlKey(pageUrl)) clearTabProjection(tabId);
    tabPageUrls.set(tabId, pageUrl);
  }

  function tabCandidates(tabId: number): ReturnType<typeof buildMediaCandidates> {
    const resourceVersion = tabResourceVersions.get(tabId) || 0;
    const manifestVersion = tabManifestVersions.get(tabId) || 0;
    const cached = tabCandidateCache.get(tabId);
    if (
      cached &&
      cached.resourceVersion === resourceVersion &&
      cached.manifestVersion === manifestVersion
    ) {
      return cached.candidates;
    }
    const resources = getResourcesForTab(tabId);
    const stored = tabDashManifests.get(tabId);
    const manifests = stored
      ? Array.from(stored.entries()).map(([url, manifest]) => ({ url, manifest }))
      : [];
    const candidates = buildMediaCandidates(resources, {
      fallbackTitle: "Video",
      pageUrl: resources.find((resource) => resource.pageUrl)?.pageUrl,
      manifests,
    });
    tabCandidateCache.set(tabId, { resourceVersion, manifestVersion, candidates });
    return candidates;
  }

  /**
   * 计算资源面板实际展示的行数：媒体按候选/清晰度聚合，已被候选代表的
   * 原始视频、音频和分片不重复计数；非媒体资源各占一行。
   */
  function displayedResourceCount(tabId: number): number {
    const resources = getResourcesForTab(tabId);
    const candidates = tabCandidates(tabId);
    const representedIds = new Set(
      candidates.flatMap((candidate) => candidate.rawResourceIds),
    );
    const rawCount = resources.filter(
      (resource) =>
        resource.type !== "video" &&
        resource.type !== "stream" &&
        !representedIds.has(resource.id),
    ).length;
    return countMediaCandidateRows(candidates) + rawCount;
  }

  const pendingBadgeUpdates = new Map<number, {
    timer: ReturnType<typeof setTimeout>;
    waiters: Array<() => void>;
  }>();

  function updateDisplayedBadgeForTab(tabId: number): Promise<void> {
    return new Promise((resolve) => {
      const pending = pendingBadgeUpdates.get(tabId);
      if (pending) {
        pending.waiters.push(resolve);
        return;
      }
      const timer = setTimeout(() => {
        const current = pendingBadgeUpdates.get(tabId);
        pendingBadgeUpdates.delete(tabId);
        try {
          void updateBadgeForTab(tabId, displayedResourceCount(tabId)).catch((error) => {
            console.warn("[FluxDown] failed to update displayed badge:", error);
          });
        } catch (error) {
          console.warn("[FluxDown] failed to compute displayed badge count:", error);
        }
        for (const waiter of current?.waiters || [resolve]) waiter();
      }, 50);
      pendingBadgeUpdates.set(tabId, { timer, waiters: [resolve] });
    });
  }

  browser.tabs.onRemoved.addListener((tabId) => {
    tabDashManifests.delete(tabId);
    tabPageUrls.delete(tabId);
    tabResourceVersions.delete(tabId);
    tabManifestVersions.delete(tabId);
    tabCandidateCache.delete(tabId);
    const pending = pendingBadgeUpdates.get(tabId);
    if (pending) {
      clearTimeout(pending.timer);
      pendingBadgeUpdates.delete(tabId);
      for (const resolve of pending.waiters) resolve();
    }
  });
  browser.tabs.onUpdated.addListener((tabId, changeInfo) => {
    if (changeInfo.url) syncTabPageUrl(tabId, changeInfo.url);
  });

  // ===== 右键菜单：即使关闭自动拦截也可以手动发送链接到 FluxDown 下载 =====

  /**
   * 创建右键菜单项（需在 i18n 初始化后调用以获取正确的翻译文本）
   *
   * 设计要点：
   * - 右键菜单不受"下载拦截开关"影响，关闭自动拦截时仍可手动右键发送
   * - 覆盖 link / image / video+audio / page 四种上下文
   * - MV3 下 contextMenus 在 Service Worker 重启后仍持久保留，
   *   但每次启动重建可确保翻译文本跟随语言切换更新
   */
  function createContextMenus() {
    if (!browser.contextMenus) {
      console.warn("[FluxDown] contextMenus API not available");
      return;
    }
    browser.contextMenus
      .removeAll()
      .then(() => {
        browser.contextMenus.create({
          id: "fluxdown-send-link",
          title: t("contextMenu.sendToFluxDown"),
          contexts: ["link"],
        });
        browser.contextMenus.create({
          id: "fluxdown-send-image",
          title: t("contextMenu.sendImageToFluxDown"),
          contexts: ["image"],
        });
        browser.contextMenus.create({
          id: "fluxdown-send-video",
          title: t("contextMenu.sendVideoToFluxDown"),
          contexts: ["video", "audio"],
        });
        browser.contextMenus.create({
          id: "fluxdown-send-page",
          title: t("contextMenu.sendPageToFluxDown"),
          contexts: ["page"],
        });
        browser.contextMenus.create({
          id: "fluxdown-send-selection",
          title: t("contextMenu.sendSelectionToFluxDown"),
          contexts: ["selection"],
        });
        console.log("[FluxDown] Context menus created");
      })
      .catch((e: unknown) => {
        console.warn("[FluxDown] Failed to create context menus:", e);
      });
  }

  // 右键菜单点击处理（同步注册，MV3 要求事件监听器在 Service Worker 首次执行时注册）
  if (browser.contextMenus?.onClicked) {
    browser.contextMenus.onClicked.addListener(
      async (info: chrome.contextMenus.OnClickData, tab?: chrome.tabs.Tab) => {
        let downloadUrl: string | undefined;

        switch (info.menuItemId) {
          case "fluxdown-send-link":
            downloadUrl = info.linkUrl;
            break;
          case "fluxdown-send-image":
          case "fluxdown-send-video":
            downloadUrl = info.srcUrl;
            break;
          case "fluxdown-send-page":
            downloadUrl = info.pageUrl;
            break;
          case "fluxdown-send-selection": {
            // #348 修复：选中的磁力/ed2k/http(s) 纯文本链接右键发送到 FluxDown。
            const selectionUrl = extractSelectionDownloadUrl(info.selectionText);
            if (!selectionUrl) {
              notify(
                t("notify.selectionInvalidTitle"),
                t("notify.selectionInvalidDetail"),
              );
              return;
            }
            console.log(
              "[FluxDown] Context menu download (selection):",
              selectionUrl,
            );
            const selectionReferrer = tab?.url || info.pageUrl || "";
            const selectionSendOk = await sendToFluxDown(
              selectionUrl,
              selectionReferrer,
            );
            if (!selectionSendOk) {
              // magnet:/ed2k: 无法回退浏览器原生下载（浏览器不识别这些协议），
              // 只提示失败；http(s) 纯文本链接可正常回退。
              if (/^https?:/i.test(selectionUrl)) {
                await fallbackAfterSendFailure(selectionUrl);
              } else {
                notify(t("notify.sendFailed"), selectionUrl);
              }
            }
            return;
          }
          default:
            return; // 非 FluxDown 菜单项，忽略
        }

        if (!downloadUrl) {
          // #407 修复：视频/音频右键项常见 blob:/MSE 源（无 srcUrl），点击后
          // 之前静默无反应；显式提示改走资源嗅探面板。
          if (info.menuItemId === "fluxdown-send-video") {
            notify(
              t("notify.videoSourceUnavailableTitle"),
              t("notify.videoSourceUnavailableDetail"),
            );
          }
          return;
        }

        // 过滤非 HTTP(S)/FTP 协议（javascript: / mailto: / data: 等不可下载）
        try {
          const protocol = new URL(downloadUrl).protocol;
          if (!["http:", "https:", "ftp:"].includes(protocol)) {
            // #407 修复：视频/音频常见 blob:/MSE 源，协议过滤在此挡下，
            // 之前静默无反应；显式提示改走资源嗅探面板。
            if (info.menuItemId === "fluxdown-send-video") {
              notify(
                t("notify.videoSourceUnavailableTitle"),
                t("notify.videoSourceUnavailableDetail"),
              );
            }
            return;
          }
        } catch {
          if (info.menuItemId === "fluxdown-send-video") {
            notify(
              t("notify.videoSourceUnavailableTitle"),
              t("notify.videoSourceUnavailableDetail"),
            );
          }
          return;
        }

        const referrer = tab?.url || info.pageUrl || "";

        console.log(
          "[FluxDown] Context menu download:",
          info.menuItemId,
          downloadUrl,
        );

        const sendOk = await sendToFluxDown(downloadUrl, referrer);
        // 成功提示已收敛到 sendToFluxDown 内部统一发送，此处只处理失败回退。
        if (!sendOk) {
          await fallbackAfterSendFailure(downloadUrl);
        }
      },
    );
  }

  // ===== 快捷键切换拦截开关 =====
  // 用户按 Alt+Shift+D 切换拦截状态，替代原来的 Alt+Click 绕过机制
  browser.commands.onCommand.addListener(async (command) => {
    if (command !== "toggle-intercept") return;
    const settings = await loadSettings();
    const newEnabled = !settings.enabled;
    await persistSettings({ ...settings, enabled: newEnabled }, settings);
    updateIcon(newEnabled);
    syncDownloadShelfState(newEnabled);
    // 通知用户当前状态
    notify(
      t("shortcut.toggleTitle"),
      newEnabled ? t("shortcut.interceptOn") : t("shortcut.interceptOff"),
    );
    console.log("[FluxDown] Intercept toggled via shortcut:", newEnabled);
  });

  // ==========================================
  // 第一层：HTTP 响应感知（webRequest 缓存）
  // ==========================================

  // 请求头缓存（Cookie / Authorization）
  const requestHeaderCache = new Map<
    string,
    { cookies: string; headers: Record<string, string>; ts: number }
  >();

  // 响应头缓存 —— 当 HTTP 响应指示"这是一个下载"时，缓存其元数据
  // 这是第三层兜底拦截的关键数据来源
  interface ResponseDownloadInfo {
    url: string;
    contentType: string; // Content-Type
    contentLength: number; // Content-Length（-1 = 未知）
    dispositionFilename: string; // 从 Content-Disposition 解析出的文件名（含 host 先验）
    /** 原始 Content-Disposition（供页面字符集先验到位后重解码）。 */
    disposition?: { text: string; byteCarrier: boolean };
    tabId: number; // 发起导航的 tab（-1 = 未知）
    ts: number;
  }
  const responseDownloadCache = new Map<string, ResponseDownloadInfo>();

  /** 下载 URL 的主机名（TLD 先验）；非法 URL 返回 undefined。 */
  function urlHost(url: string): string | undefined {
    try {
      return new URL(url).hostname || undefined;
    } catch {
      return undefined;
    }
  }

  /** 询问发起页字符集的超时：失败/超时视为无先验。 */
  const PAGE_CHARSET_TIMEOUT_MS = 300;

  /**
   * 向 tab 顶层框架的内容脚本询问 `document.characterSet`
   * （消息 `{ action: "getPageCharset" }` → `{ charset: string }`）。
   * 无内容脚本、tab 已关闭、超时均返回 undefined。
   */
  async function getPageCharset(tabId: number): Promise<string | undefined> {
    if (tabId < 0) return undefined;
    try {
      const reply: unknown = await Promise.race([
        browser.tabs.sendMessage(tabId, { action: "getPageCharset" }, {
          frameId: 0,
        }),
        new Promise<undefined>((resolve) =>
          setTimeout(resolve, PAGE_CHARSET_TIMEOUT_MS),
        ),
      ]);
      if (reply && typeof reply === "object" && "charset" in reply) {
        return typeof reply.charset === "string" && reply.charset
          ? reply.charset
          : undefined;
      }
    } catch {
      // 内容脚本不存在（受限页面/尚未注入）：无先验
    }
    return undefined;
  }

  /**
   * 重新解码 Content-Disposition：仅当原始字节需要页面字符集先验
   * （字节载体、非 ASCII、非合法 UTF-8）时才询问发起页；否则或无先验时
   * 返回 `fallback`（已带 host 先验的解码结果）。
   */
  async function decodeDispositionWithPageCharset(
    text: string,
    byteCarrier: boolean,
    tabId: number,
    url: string,
    fallback: string,
  ): Promise<string> {
    if (!dispositionNeedsPageCharset(text, byteCarrier)) return fallback;
    const pageCharset = await getPageCharset(tabId);
    if (!pageCharset) return fallback;
    return (
      parseContentDispositionFilename(text, byteCarrier, {
        host: urlHost(url),
        pageCharset,
      }) || fallback
    );
  }

  function resolveResponseDispositionFilename(
    info: ResponseDownloadInfo,
  ): Promise<string> {
    if (!info.disposition) return Promise.resolve(info.dispositionFilename);
    return decodeDispositionWithPageCharset(
      info.disposition.text,
      info.disposition.byteCarrier,
      info.tabId,
      info.url,
      info.dispositionFilename,
    );
  }

  // ──────────────────────────────────────────────────────────────
  // 完整请求事务捕获（method + body）—— 修复 form-POST 触发的下载
  // ──────────────────────────────────────────────────────────────
  //
  // 问题背景：chrome.downloads.onCreated 仅暴露 URL/filename/mime，**不暴露**
  // 浏览器实际发出的 method 与 requestBody。对 uupdump.net 这类 form POST
  // 触发下载的站点，FluxDown 拿到 URL 后用 GET 重发会拿到 HTML 页面而非
  // 真实文件，导致 ".zip" 文件落盘成 "<!DOCTYPE html>"。
  //
  // 解决方案：在 webRequest.onBeforeRequest 抓取浏览器原始 method 与 body，
  // 按 URL 索引缓存。downloads.onCreated 命中时按 URL 反查这张表，把
  // method/body 一起塞进发送给 FluxDown App 的 payload。Rust 端用此
  // 一比一重建浏览器请求事务。
  //
  // 局限性：requestRecordCache 按 URL 串接 onBeforeRequest 与 onCreated，
  // 同一 URL 在 60 秒内的多次请求只保留最新一次（覆盖式写入）。这是因为
  // chrome.downloads.onCreated 不暴露 webRequest 的 requestId，无法精确配对。
  type CapturedBody =
    | { kind: "formData"; fields: Record<string, string[]> }
    | { kind: "raw"; bytesB64: string; contentType?: string };

  interface RequestRecord {
    url: string;
    method: string;
    body?: CapturedBody;
    ts: number;
  }
  const requestRecordCache = new Map<string, RequestRecord>();

  // ArrayBuffer → base64 字符串（chrome.webRequest 抓到的 raw body 是 ArrayBuffer）。
  function arrayBufferToBase64(buffer: ArrayBuffer): string {
    const bytes = new Uint8Array(buffer);
    let binary = "";
    // 分块处理，避免对超大 body 触发 String.fromCharCode 栈溢出
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
      const slice = bytes.subarray(i, Math.min(i + chunk, bytes.length));
      binary += String.fromCharCode(...slice);
    }
    return btoa(binary);
  }

  // 仅当 method 非 GET 或带有 body 时才有捕获价值——单纯的 GET 请求与现有
  // URL 重发逻辑等价，缓存它会无谓占用内存。
  //
  // OPTIONS 必须排除：跨域下载 API（飞书云盘等）的浏览器 CORS 预检会先对
  // 同一 URL 发一个 OPTIONS，紧随其后的真实 GET 因"无捕获价值"不会写缓存，
  // 于是 OPTIONS 记录留存下来被 lookupRequestRecord 命中，App 端一比一回放
  // OPTIONS → 服务器返回 404/HTML，且非 GET 请求被引擎强制单线程下载。
  // 预检请求本身永远不可能触发下载，捕获它只有害处。
  function shouldCaptureRequest(
    details: chrome.webRequest.WebRequestBodyDetails,
  ): boolean {
    if (details.method === "OPTIONS") return false;
    if (details.method !== "GET" && details.method !== "HEAD") return true;
    if (details.requestBody && (details.requestBody.formData || details.requestBody.raw)) {
      return true;
    }
    return false;
  }

  // 表单 POST 体通常只有几 KB；超过此上限的 raw 体不缓存。
  const MAX_CAPTURED_RAW_BODY_BYTES = 1024 * 1024;

  function captureBody(
    details: chrome.webRequest.WebRequestBodyDetails,
  ): CapturedBody | undefined {
    const body = details.requestBody;
    if (!body) return undefined;
    if (body.formData && Object.keys(body.formData).length > 0) {
      return { kind: "formData", fields: body.formData };
    }
    if (body.raw && body.raw.length > 0) {
      // 只取首块 raw bytes——多块拼接对常见 form-urlencoded / JSON POST 不必要
      const part = body.raw[0];
      if (part?.bytes && part.bytes.byteLength <= MAX_CAPTURED_RAW_BODY_BYTES) {
        return {
          kind: "raw",
          bytesB64: arrayBufferToBase64(part.bytes),
          // chrome.webRequest 不直接暴露 Content-Type，留给后续 header 配对
        };
      }
    }
    return undefined;
  }

  function onBeforeRequestHandler(
    details: chrome.webRequest.WebRequestBodyDetails,
  ): chrome.webRequest.BlockingResponse | undefined {
    if (!shouldCaptureRequest(details)) {
      // 无 body 的 GET 是该 URL 的最新真实事务——清除同 URL 的陈旧非 GET
      // 记录（如先前的 CORS 预检 OPTIONS 或已过期的 form POST），确保
      // lookupRequestRecord 不会把旧事务误配给这次 GET 触发的下载。
      if (details.method === "GET" && requestRecordCache.has(details.url)) {
        requestRecordCache.delete(details.url);
      }
      return undefined;
    }
    const body = captureBody(details);
    requestRecordCache.set(details.url, {
      url: details.url,
      method: details.method,
      body,
      ts: Date.now(),
    });
    return undefined;
  }

  try {
    // 只有导航（表单 POST 触发下载）才可能产生下载项；XHR/fetch 的请求体
    // （上传分块、埋点）捕获后永远用不上，却要做 O(body) 的复制与 base64。
    browser.webRequest.onBeforeRequest.addListener(
      onBeforeRequestHandler as any,
      { urls: ["<all_urls>"], types: ["main_frame", "sub_frame"] },
      ["requestBody"] as any,
    );
    console.log(
      "[FluxDown] webRequest.onBeforeRequest listener registered (with requestBody)",
    );
  } catch (e) {
    console.warn(
      "[FluxDown] Failed to register onBeforeRequest listener:",
      e,
    );
  }

  // requestRecordCache 周期清理（与 requestHeaderCache 同步策略）
  setInterval(() => {
    const now = Date.now();
    for (const [k, rec] of requestRecordCache) {
      if (now - rec.ts > 60_000) {
        requestRecordCache.delete(k);
      }
    }
    if (requestRecordCache.size > 500) {
      const excess = requestRecordCache.size - 400;
      let deleted = 0;
      for (const k of requestRecordCache.keys()) {
        if (deleted >= excess) break;
        requestRecordCache.delete(k);
        deleted++;
      }
    }
  }, 30_000);

  // 取出 (method, body) 作为附加 payload —— onCreated / onDeterminingFilename
  // 路径在调用 sendDownloadRequest 之前调用此函数。
  // 优先匹配 finalUrl（重定向后的真实下载 URL），fallback 到原始 url。
  function lookupRequestRecord(
    url: string,
    fallbackUrl?: string,
  ): { method?: string; body?: CapturedBody } {
    const rec =
      requestRecordCache.get(url) ||
      (fallbackUrl && fallbackUrl !== url
        ? requestRecordCache.get(fallbackUrl)
        : undefined);
    if (!rec) return {};
    // GET 是默认值——不传也罢，节省 NM 协议字节数
    const method = rec.method && rec.method !== "GET" ? rec.method : undefined;
    return { method, body: rec.body };
  }

  // Chrome MV3 需要 'extraHeaders' 才能看到 Cookie 等敏感头，Firefox 不需要也不识别此选项
  const sendHeadersOpts: string[] = ["requestHeaders"];
  try {
    // 先尝试带 extraHeaders（Chrome MV3），失败则降级（Firefox）
    browser.webRequest.onSendHeaders.addListener(
      onSendHeadersHandler,
      { urls: ["<all_urls>"] },
      [...sendHeadersOpts, "extraHeaders"] as any,
    );
    console.log(
      "[FluxDown] webRequest.onSendHeaders listener registered (with extraHeaders)",
    );
  } catch {
    try {
      browser.webRequest.onSendHeaders.addListener(
        onSendHeadersHandler,
        { urls: ["<all_urls>"] },
        sendHeadersOpts,
      );
      console.log(
        "[FluxDown] webRequest.onSendHeaders listener registered (without extraHeaders)",
      );
    } catch (e) {
      console.warn(
        "[FluxDown] Failed to register webRequest.onSendHeaders listener:",
        e,
      );
    }
  }

  // Bug R2-8 修复：将 requestHeaderCache 清理从"每次请求时全量遍历"改为周期性清理。
  // 高流量页面（如视频网站）每秒可能触发数百次 onSendHeaders，之前每次都 O(n) 遍历会造成性能问题。
  setInterval(() => {
    const now = Date.now();
    for (const [cachedUrl, entry] of requestHeaderCache) {
      if (now - entry.ts > 60_000) {
        requestHeaderCache.delete(cachedUrl);
      }
    }
    // 强制大小上限（防止突发积累过多条目）
    if (requestHeaderCache.size > 1000) {
      const excess = requestHeaderCache.size - 800;
      let deleted = 0;
      for (const key of requestHeaderCache.keys()) {
        if (deleted >= excess) break;
        requestHeaderCache.delete(key);
        deleted++;
      }
    }
  }, 30_000); // 每 30 秒清理一次，远低于 60 秒有效期

  function onSendHeadersHandler(
    details: chrome.webRequest.WebRequestHeadersDetails,
  ) {
    if (!details.requestHeaders) return;
    const headers: Record<string, string> = {};
    let cookies = "";
    for (const h of details.requestHeaders) {
      if (h.name && h.value) {
        headers[h.name] = h.value;
        if (h.name.toLowerCase() === "cookie") {
          cookies = h.value;
        }
      }
    }
    requestHeaderCache.set(details.url, { cookies, headers, ts: Date.now() });
  }

  // === 认证信息提取辅助 ===

  /**
   * 浏览器内部头黑名单 — IDM/NDM 策略：只跳过连接管理和已单独处理的头，
   * 其余（Accept、Sec-Fetch-*、Accept-Language 等）全部原样传递给 FluxDown。
   *
   * 许多站点（如 ctbpsp.com）的服务器会检查 Sec-Fetch-Dest / Sec-Fetch-Mode /
   * Accept / Accept-Language 等头来判断请求是否来自真实的浏览器导航。
   * 缺少这些头会导致服务器返回 HTML 错误页面而非实际文件。
   */
  const SKIP_HEADERS = new Set([
    "cookie", // 已单独提取为 cookieString 字段
    "host", // 由 URL 决定，reqwest 自动设置
    "connection", // HTTP 连接管理，reqwest 自动处理
    "content-length", // 请求体相关，GET 请求无需
    "accept-encoding", // reqwest 自动处理压缩（gzip/deflate/brotli），传了会重复解压
    "range", // 播放器 seek 产生的分段 Range，透传会与下载引擎自管的分段/续传冲突（B站 .m4s 音频轨 416）
    "if-range", // 与 Range 配套的条件头，同样不应透传
  ]);

  /** 缺少 User-Agent 时补上浏览器真实 UA；所有下载路径共用。 */
  function withBrowserUserAgent(
    headers: Record<string, string>,
  ): Record<string, string> | undefined {
    const merged = Object.keys(headers).some(
      (k) => k.toLowerCase() === "user-agent",
    )
      ? headers
      : { ...headers, "User-Agent": navigator.userAgent };
    return Object.keys(merged).length > 0 ? merged : undefined;
  }

  /**
   * 从 requestHeaderCache 提取认证信息（Cookie + 自定义头）。
   * 不会删除缓存条目——调用方按需自行清理。
   */
  function extractAuthFromCache(
    url: string,
    fallbackUrl?: string,
  ): {
    cookies: string | undefined;
    headers: Record<string, string> | undefined;
  } {
    const cached =
      requestHeaderCache.get(url) ||
      (fallbackUrl && fallbackUrl !== url
        ? requestHeaderCache.get(fallbackUrl)
        : undefined);
    if (!cached) return { cookies: undefined, headers: undefined };

    const cookies = cached.cookies || undefined;
    const filtered: Record<string, string> = {};
    for (const [name, value] of Object.entries(cached.headers)) {
      if (!SKIP_HEADERS.has(name.toLowerCase())) {
        filtered[name] = value;
      }
    }
    const headers = Object.keys(filtered).length > 0 ? filtered : undefined;
    return { cookies, headers };
  }

  // === 响应头监听：检测"导航转下载"场景 ===
  // 当浏览器主框架导航的响应带有 Content-Disposition: attachment 或
  // 下载类 Content-Type 时，说明这是一个"导航转下载"的请求。
  // 缓存其信息，供 onCreated 兜底拦截使用。
  try {
    browser.webRequest.onHeadersReceived.addListener(
      (details) => {
        // 只关注主框架导航（sub_frame、xhr 等交给正常 download 流程处理）
        if (details.type !== "main_frame") return;
        if (!details.responseHeaders) return;

        let contentType = "";
        let contentLength = -1;
        let contentDisposition = "";
        let dispositionByteCarrier = false;

        for (const h of details.responseHeaders) {
          const name = h.name.toLowerCase();
          if (name === "content-type" && h.value) {
            contentType = h.value.split(";")[0].trim().toLowerCase();
          } else if (name === "content-length" && h.value) {
            const parsed = parseInt(h.value, 10);
            if (!isNaN(parsed)) contentLength = parsed;
          } else if (name === "content-disposition") {
            const d = readDispositionHeader(h, import.meta.env.FIREFOX);
            if (d) {
              contentDisposition = d.text;
              dispositionByteCarrier = d.byteCarrier;
            }
          }
        }

        // 判断该响应是否会触发下载
        const isAttachment = contentDisposition
          .toLowerCase()
          .startsWith("attachment");
        const isDownloadMime = isDownloadContentType(contentType);

        if (!isAttachment && !isDownloadMime) return;

        // 从 Content-Disposition 提取文件名
        const dispositionFilename = parseContentDispositionFilename(
          contentDisposition,
          dispositionByteCarrier,
          { host: urlHost(details.url) },
        );

        const info: ResponseDownloadInfo = {
          url: details.url,
          contentType,
          contentLength,
          dispositionFilename,
          disposition: contentDisposition
            ? { text: contentDisposition, byteCarrier: dispositionByteCarrier }
            : undefined,
          tabId: details.tabId,
          ts: Date.now(),
        };

        responseDownloadCache.set(details.url, info);
        console.log(
          "[FluxDown] Detected download-triggering response (onHeadersReceived):",
          info,
        );

        // 60 秒后自动清理
        setTimeout(() => responseDownloadCache.delete(details.url), 60_000);

        // 同时将 main_frame 下载资源加入嗅探面板
        // （资源嗅探层只监听 media/xhr/object/other，main_frame 会绕过它；
        //  responseDownloadCache 服务于下载拦截，不受嗅探开关影响，仅面板入口受控）
        if (
          details.tabId >= 0 &&
          !(_settingsCache && !_settingsCache.resourceSniffing)
        ) {
          // 从 requestHeaderCache 提取该请求的认证信息，随资源一起持久存储
          const { cookies: mainCookies, headers: mainHeaders } =
            extractAuthFromCache(details.url);
          const added = addSniffedResource(
            details.tabId,
            details.url,
            contentType,
            contentLength,
            dispositionFilename,
            isAttachment,
            mainCookies,
            mainHeaders,
          );
          if (added > 0) {
            bumpTabVersion(tabResourceVersions, details.tabId);
            updateDisplayedBadgeForTab(details.tabId);
            notifyContentScript(details.tabId);
          }
        }
      },
      { urls: ["<all_urls>"] },
      ["responseHeaders"],
    );
    console.log("[FluxDown] webRequest.onHeadersReceived listener registered");
  } catch (e) {
    console.warn(
      "[FluxDown] Failed to register webRequest.onHeadersReceived listener:",
      e,
    );
  }

  // ==========================================
  // 资源嗅探层：监听所有 media / XHR 类型请求的响应头
  // 检测可下载的媒体资源，加入资源列表供 UI 展示
  // ==========================================
  try {
    browser.webRequest.onHeadersReceived.addListener(
      (details) => {
        // 跳过无效或非 tab 请求
        if (details.tabId < 0 || !details.responseHeaders) return;

        // 资源嗅探开关：关闭时丢弃（缓存冷启动为 null 时放行，避免同步路径丢事件）
        if (_settingsCache && !_settingsCache.resourceSniffing) return;

        // 跳过非成功响应（重定向、客户端/服务器错误）
        if (details.statusCode < 200 || details.statusCode >= 400) return;

        let contentType = "";
        let contentLength = -1;
        let contentDisposition = "";
        let dispositionByteCarrier = false;

        for (const h of details.responseHeaders) {
          const name = h.name.toLowerCase();
          if (name === "content-type" && h.value) {
            contentType = h.value.split(";")[0].trim().toLowerCase();
          } else if (name === "content-length" && h.value) {
            const parsed = parseInt(h.value, 10);
            if (!isNaN(parsed)) contentLength = parsed;
          } else if (name === "content-disposition") {
            const d = readDispositionHeader(h, import.meta.env.FIREFOX);
            if (d) {
              contentDisposition = d.text;
              dispositionByteCarrier = d.byteCarrier;
            }
          }
        }

        // 判断是否是有价值的资源
        const isAttachment = contentDisposition
          .toLowerCase()
          .startsWith("attachment");
        const ruleMatch = matchSniffRule(
          details.url,
          contentType,
          contentLength,
        );

        // 规则显式拦截（禁用/黑名单/小于最小大小）→ 丢弃
        if (ruleMatch.blocked) return;
        // 既非规则命中、又非附件 → 丢弃
        if (!ruleMatch.hit && !isAttachment) return;

        // 提取文件名
        let filename = "";
        if (contentDisposition) {
          filename = parseContentDispositionFilename(
            contentDisposition,
            dispositionByteCarrier,
            { host: urlHost(details.url) },
          );
        }
        if (!filename) {
          filename = extractFilenameFromUrl(details.url);
        }

        // 从 requestHeaderCache 提取该请求的认证信息（Cookie / Authorization 等），
        // 随资源一起持久存储到 resource-store。
        // 确保用户稍后从资源面板点击下载时，即使 requestHeaderCache 已过期，
        // 仍能携带正确的认证头发送给 FluxDown——这是 IDM 能成功而普通插件失败的关键。
        const { cookies: sniffCookies, headers: sniffHeaders } =
          extractAuthFromCache(details.url);

        // 添加到资源存储（传递 isAttachment + cookies/headers 用于后续下载）
        const added = addSniffedResource(
          details.tabId,
          details.url,
          contentType,
          contentLength,
          filename,
          isAttachment,
          sniffCookies,
          sniffHeaders,
        );

        if (added > 0) {
          // 更新 Badge
          bumpTabVersion(tabResourceVersions, details.tabId);
          updateDisplayedBadgeForTab(details.tabId);
          // 推送给 Content Script UI
          notifyContentScript(details.tabId);
        }
      },
      {
        urls: ["<all_urls>"],
        types: ["media", "xmlhttprequest", "object", "other", "sub_frame"],
      },
      ["responseHeaders"],
    );
    console.log(
      "[FluxDown] Resource sniffer (onHeadersReceived for media) registered",
    );
  } catch (e) {
    console.warn("[FluxDown] Failed to register resource sniffer:", e);
  }

  /**
   * 向指定 tab 的 Content Script 推送最新资源列表
   */
  async function notifyContentScript(tabId: number): Promise<void> {
    const resources = getResourcesForTab(tabId);
    try {
      await browser.tabs.sendMessage(tabId, {
        action: "resourcesUpdated",
        resources,
      });
    } catch {
      // Content script 可能还未注入
    }
  }

  /**
   * 向指定 tab 的 Content Script 推送最新 DASH manifest（权威清晰度 + 轨道 URL）
   */
  async function notifyDashManifest(tabId: number): Promise<void> {
    const stored = tabDashManifests.get(tabId);
    const dashManifests = stored
      ? Array.from(stored.entries()).map(([url, manifest]) => ({ url, manifest }))
      : [];
    try {
      await browser.tabs.sendMessage(tabId, {
        action: "dashManifestUpdated",
        // `manifest` 保留给旧版 content script；新 UI 使用有界数组。
        manifest: dashManifests[dashManifests.length - 1]?.manifest,
        dashManifests,
      });
    } catch {
      // Content script 可能还未注入
    }
  }

  // ==========================================
  // 第二层 + 第三层：下载事件拦截
  // ==========================================

  const downloadItemCache = new Map<number, chrome.downloads.DownloadItem>();
  const handledDownloads = new Map<number, "primary" | "fallback">();
  // Alt+Click 绕过令牌：URL → 过期时间戳，15 秒内有效
  const bypassTokens = new Map<string, number>();
  // 预抢占 URL 表：URL → {expiry: 过期时间戳, ruleId: DNR 规则 ID}
  // 当 AJAX 拦截器在浏览器发起 CDN GET 之前检测到一次性下载 URL 时填入。
  // onDeterminingFilename 检查此表，避免重复发送给 FluxDown。
  const preemptedUrls = new Map<string, { expiry: number }>();

  // Bug R3-2 修复：周期性清理 bypassTokens 中的过期条目，防止长期积累内存泄漏
  setInterval(() => {
    const now = Date.now();
    for (const [tokenUrl, expiry] of bypassTokens) {
      if (expiry <= now) bypassTokens.delete(tokenUrl);
    }
  }, 60_000); // 每 60 秒清理一次

  /**
   * 绕过令牌检查（基于时间，不消费）。
   *
   * 关键修复（#308 #346 弹窗风暴根因）：bypassTokens 必须按"过期时间"语义工作，
   * 读取时**不删除**。原实现"读取即删除"导致回退重发起的下载被第二层
   * (onDeterminingFilename) 放行并消费令牌后，第三层 (onCreated 兜底) 再次查不到
   * 令牌 → 重新拦截 → 再次发送失败 → 再次回退 → 无限弹窗循环。
   * 改为时间令牌后，同一次回退产生的下载在所有拦截层都被一致放行。
   */
  function hasActiveBypass(url: string): boolean {
    const expiry = bypassTokens.get(url);
    return expiry !== undefined && expiry > Date.now();
  }

  // ── App 可用性熔断器（#308 #346 弹窗风暴修复）────────────────────────
  // 当发送失败且**重连重试 ping 仍确认 App 不可达**时，进入冷却期：冷却期内**跳过拦截**，
  // 让浏览器原生下载，避免"拦截→发送失败→回退→再拦截"的反复弹窗。
  // 恢复路径（缩短"App 已恢复却仍被旁路"的死区，review 发现 #1/#4/#6）：
  //   1) 冷却期到期后下一次下载尝试发送，成功即解除（兜底）；
  //   2) popup 打开时 ping 成功立即解除（用户启动 App 后常见操作）；
  //   3) 待发队列 flush 成功时解除。
  // 冷却期取 30s（短于 60s 以缩小误熔断/陈旧冷却的影响半径，review 发现 #3/#5）。
  const APP_DOWN_COOLDOWN_MS = 30_000;
  // storage.session 持久化键：让熔断状态在 MV3 Service Worker 重启后仍生效，
  // 否则每次 SW 冷启动都会重新拦截一次造成跨生命周期的弹窗。
  const APP_DOWN_STATE_KEY = "fluxdownAppDownUntil";
  let _appDownUntil = 0;
  // 熔断期内"未检测到 App"提示只弹一次，解除熔断时复位。
  let _appDownNotified = false;
  // 标记熔断状态是否已被一次实时的 markAppDown/markAppUp 改写过。
  // 用于防止 SW 冷启动时异步恢复的旧持久值，覆盖掉启动期间刚到达的实时恢复信号
  // （Chrome MV3：appConfirmedUp 消息可能正是唤醒 SW 的那条，markAppUp 先跑、
  //  随后 storage.session.get 才 resolve 并把旧 until 写回，造成已恢复却又被熔断）。
  // review round2 发现 #1。
  let _breakerStateTouched = false;

  function isAppKnownDown(): boolean {
    return Date.now() < _appDownUntil;
  }

  function persistAppDownState(until: number): void {
    // storage.session 在部分旧版浏览器不可用；best-effort，失败不影响内存态熔断。
    try {
      (browser.storage as any).session
        ?.set({ [APP_DOWN_STATE_KEY]: until })
        ?.catch(() => {});
    } catch {
      /* ignore */
    }
  }

  function markAppDown(): void {
    // 无条件置位：即使本次是 no-op，也代表"有过实时熔断/恢复判定"，
    // 启动恢复必须让位（见 _breakerStateTouched 说明）。
    _breakerStateTouched = true;
    _appDownUntil = Date.now() + APP_DOWN_COOLDOWN_MS;
    persistAppDownState(_appDownUntil);
  }

  function markAppUp(): void {
    // 无条件置位：即便内存态已是"up"（guard 跳过），一条实时的"App 可达"信号
    // 也应阻止后续异步恢复把旧的 down 状态写回（review round2 发现 #1）。
    _breakerStateTouched = true;
    if (_appDownUntil !== 0 || _appDownNotified) {
      _appDownUntil = 0;
      _appDownNotified = false;
      persistAppDownState(0);
    }
  }

  // 熔断确认探测的单航班守卫：并发下载在 cooldown 到期同时失败时，
  // 共享同一次重连重试 ping，避免各自独立 ping 造成的连接抖动（review round2 发现 #4）。
  let _appProbe: Promise<boolean> | null = null;
  function probeAppReachable(): Promise<boolean> {
    if (!_appProbe) {
      _appProbe = checkFluxDownAvailableWithRetry()
        .catch(() => false)
        .finally(() => {
          _appProbe = null;
        });
    }
    return _appProbe;
  }

  // SW 启动时从 storage.session 恢复熔断状态（best-effort）。
  (async () => {
    try {
      const s = await (browser.storage as any).session?.get(APP_DOWN_STATE_KEY);
      const until = s?.[APP_DOWN_STATE_KEY];
      // 仅当启动期间没有任何实时熔断/恢复发生时才采用持久值，避免覆盖刚到达的
      // markAppUp/markAppDown（review round2 发现 #1）。
      if (
        !_breakerStateTouched &&
        typeof until === "number" &&
        until > Date.now()
      ) {
        _appDownUntil = until;
      }
    } catch {
      /* ignore */
    }
  })();

  // Firefox 不支持 onDeterminingFilename，兜底层是唯一拦截路径，
  // 需要更长等待让浏览器填充 downloadItem 元数据
  const hasDeterminingFilename = !!browser.downloads.onDeterminingFilename;

  // ──────────────────────────────────────────────────────────────
  // Firefox 专用：blocking webRequest 从源头拦截下载（issue #21）
  // ──────────────────────────────────────────────────────────────
  // Firefox 无 onDeterminingFilename，只能靠 onCreated 兜底层在下载项已创建后
  // cancel+erase。但 Firefox 的 downloads.erase 对主动取消的记录删除不可靠，
  // 导致库/下载窗口残留一条"已取消"记录。
  //
  // 根治：Firefox MV3 仍支持 blocking webRequest。对判定为下载的导航响应
  // 直接返回 {cancel:true}，浏览器根本不创建下载项 → 无任何残留记录。
  // 同步做拦截决策，异步 fire-and-forget 发送到 FluxDown（不阻塞响应管线）。
  //
  // 仅在 Firefox（无 onDeterminingFilename）启用；Chrome MV3 无 blocking
  // webRequest，且 onDeterminingFilename 的 suggest+cancel 已是干净路径。
  if (!hasDeterminingFilename) {
    const onDownloadHeadersBlocking = (
      details: any,
    ): { cancel: boolean } | undefined => {
      // 只拦截导航类请求（点击下载链接 / 导航转下载）；xhr/fetch 取 blob 不碰
      if (details.type !== "main_frame" && details.type !== "sub_frame") {
        return undefined;
      }
      if (!details.responseHeaders) return undefined;
      // 仅成功响应（重定向/错误交给浏览器）
      if (details.statusCode < 200 || details.statusCode >= 300) {
        return undefined;
      }

      let contentType = "";
      let contentLength = -1;
      let contentDisposition = "";
      let dispositionByteCarrier = false;
      for (const h of details.responseHeaders) {
        const name = h.name.toLowerCase();
        if (name === "content-type" && h.value) {
          contentType = h.value.split(";")[0].trim().toLowerCase();
        } else if (name === "content-length" && h.value) {
          const parsed = parseInt(h.value, 10);
          if (!isNaN(parsed)) contentLength = parsed;
        } else if (name === "content-disposition") {
          const d = readDispositionHeader(h, import.meta.env.FIREFOX);
          if (d) {
            contentDisposition = d.text;
            dispositionByteCarrier = d.byteCarrier;
          }
        }
      }

      const isAttachment = contentDisposition
        .toLowerCase()
        .startsWith("attachment");
      const isDownloadMime = isDownloadContentType(contentType);
      // Firefox 可内联展示的类型（PDF/常见音视频）仅在 attachment 时才是下载，
      // main_frame 与 sub_frame 同规则；其余下载类 MIME 照旧。
      if (!isAttachment && (!isDownloadMime || isFirefoxInlineViewableType(contentType))) {
        return undefined;
      }

      // 冷启动首个下载时 settings 缓存可能为 null：保守放行，异步预热缓存
      const settings = _settingsCache;
      if (!settings) {
        getCachedSettings().catch(() => {});
        return undefined;
      }
      if (!settings.enabled) return undefined;

      if (hasActiveBypass(details.url)) return undefined;
      // App 熔断期内放行浏览器原生下载，避免用户完全无法下载
      if (isAppKnownDown()) return undefined;

      const dispositionFilename = parseContentDispositionFilename(
        contentDisposition,
        dispositionByteCarrier,
        { host: urlHost(details.url) },
      );
      const referrer: string | undefined =
        details.originUrl || details.documentUrl || undefined;
      const itemInfo: DownloadItemInfo = {
        url: details.url,
        fileSize: contentLength > 0 ? contentLength : -1,
        mime: contentType || undefined,
        filename: dispositionFilename || undefined,
        referrerUrl: referrer,
      };
      if (!shouldIntercept(itemInfo, settings)) return undefined;

      // 命中：同步取消该请求（浏览器不创建下载项），异步发送到 FluxDown
      console.log("[FluxDown] Firefox blocking intercept (cancel at source):", {
        url: details.url,
        contentType,
        contentLength,
        dispositionFilename,
      });

      // fire-and-forget：blocking 回调必须尽快返回（cancel 同步返回）；原始字节
      // 文件名需要发起页字符集先验时，异步取回后再重解码并发送。
      // 发送失败时回退浏览器下载。
      void (async () => {
        const decodedFilename = await decodeDispositionWithPageCharset(
          contentDisposition,
          dispositionByteCarrier,
          details.tabId,
          details.url,
          dispositionFilename,
        );
        const cleanFilename = resolveDownloadFilename(
          decodedFilename,
          undefined,
          details.url,
          contentType,
        );
        try {
          const ok = await sendToFluxDown(
            details.url,
            referrer,
            cleanFilename,
            itemInfo.fileSize,
            itemInfo.mime,
          );
          if (!ok) {
            console.warn(
              "[FluxDown] Firefox blocking: send failed, falling back to browser download:",
              details.url,
            );
            await fallbackAfterSendFailure(details.url, cleanFilename);
          }
        } catch (e) {
          console.error(
            "[FluxDown] Firefox blocking: sendToFluxDown threw:",
            e,
          );
          await fallbackAfterSendFailure(details.url, cleanFilename).catch(
            () => {},
          );
        }
      })();

      return { cancel: true };
    };

    try {
      browser.webRequest.onHeadersReceived.addListener(
        onDownloadHeadersBlocking as any,
        { urls: ["<all_urls>"], types: ["main_frame", "sub_frame"] },
        ["blocking", "responseHeaders"] as any,
      );
      console.log(
        "[FluxDown] Firefox blocking download interceptor registered",
      );
    } catch (e) {
      console.warn(
        "[FluxDown] Failed to register Firefox blocking interceptor:",
        e,
      );
    }
  }

  // ──────────────────────────────────────────────────────────────
  // 弱网可靠性：统一等待元数据就绪
  // ──────────────────────────────────────────────────────────────
  // 设计原则：
  //   - 路径A（main_frame 导航下载）：等待 responseDownloadCache 填充
  //   - 路径B（XHR / JS 触发下载）：等待 downloadItem.mime/filename 填充
  //   - 两条路径共享同一个 deadline，总等待上限为 META_MAX_WAIT（5s）
  //   - 两路并发轮询：responseCache 先到就走路径A，否则走路径B
  //
  // Bug 1 修复：之前路径A的 waitForResponseCache 耗尽 5s 后，路径B才开始
  //             等待，导致总等待 10s，且路径B内不再检查缓存（死路）。
  // Bug 6 修复：现在两路并发，5s 是总上限，不是每路各 5s。
  const POLL_INTERVAL = 60; // 统一轮询间隔 ms
  const META_MAX_WAIT = 5000; // 总等待上限（弱网场景覆盖范围）

  /**
   * 并发等待两种元数据来源，哪个先到用哪个，共享 deadline。
   * 返回：
   *   { source: 'responseCache', data: ResponseDownloadInfo } — main_frame 导航下载
   *   { source: 'downloadMeta', data: DownloadItem }          — XHR/JS 触发下载
   *   null — deadline 到达或下载已被其他层处理
   */
  type MetaResult =
    | { source: "responseCache"; data: ResponseDownloadInfo }
    | { source: "downloadMeta"; data: chrome.downloads.DownloadItem };

  async function waitForMeta(
    url: string,
    downloadId: number,
    originalItem: chrome.downloads.DownloadItem,
    deadlineMs: number,
  ): Promise<MetaResult | null> {
    let bestItem = originalItem;
    while (Date.now() < deadlineMs) {
      if (handledDownloads.has(downloadId)) return null;

      // 路径A：responseDownloadCache 到达
      const rc = responseDownloadCache.get(url);
      if (rc) return { source: "responseCache", data: rc };

      // 路径B：downloadItem 元数据就绪
      try {
        const results = await browser.downloads.search({ id: downloadId });
        if (results && results.length > 0) {
          const item = results[0];
          if (
            item.state === "complete" ||
            (item as any).state === "interrupted"
          ) {
            // 下载已结束（可能极快完成），用最新状态返回
            return { source: "downloadMeta", data: item };
          }
          if (item.mime || item.filename) {
            return { source: "downloadMeta", data: item };
          }
          bestItem = item;
        } else if (results && results.length === 0) {
          // Bug R4-4 修复：search 返回空数组说明该下载项已不存在（被第二层 erase，或已被删除）。
          // 视为已被其他路径处理，退出，避免误用过期的 originalItem 重复发送。
          return null;
        }
      } catch {
        // search 失败（Firefox 偶发），继续等待
      }

      await sleep(POLL_INTERVAL);
    }
    // deadline 到达，用 bestItem 兜底（宁可用不完整数据判断也不放弃拦截）
    // 再最后检查一次缓存
    const rc = responseDownloadCache.get(url);
    if (rc) return { source: "responseCache", data: rc };
    return { source: "downloadMeta", data: bestItem };
  }

  // === 第三层：onCreated 兜底 + onChanged 元数据补全 ===
  browser.downloads.onCreated.addListener((downloadItem) => {
    const downloadId = downloadItem.id;
    const url = downloadItem.url;

    console.log("[FluxDown] onCreated:", {
      id: downloadId,
      url,
      mime: downloadItem.mime,
      filename: downloadItem.filename,
    });

    // 缓存 downloadItem 信息，onDeterminingFilename 会用到
    downloadItemCache.set(downloadId, downloadItem);

    // 跳过 blob 和 data URL
    if (url.startsWith("blob:") || url.startsWith("data:")) return;

    // 启动兜底计时器
    // 给 onDeterminingFilename 一个处理窗口，超时后由 onCreated 兜底
    //
    // 关键点：不使用固定的"猜测"超时，而是利用 onChanged 获取完整元数据后再判断。
    // 这里只是一个启动延迟，等 onDeterminingFilename 有机会先处理。
    // 如果 onDeterminingFilename 已处理，兜底逻辑会跳过。
    //
    // 注意：我们注册一个 onChanged 监听器，一旦 downloadItem 的 filename 或 mime
    // 字段被填充（说明浏览器已解析完响应头），就可以做出更准确的判断。
    //
    // R6-8 修复：加 .catch() 防止内部未捕获异常成为 unhandled rejection 噪音
    startFallbackInterception(downloadId, downloadItem).catch((e) => {
      console.error(
        "[FluxDown] Unexpected error in startFallbackInterception:",
        e,
      );
    });

    // 30 秒后全面清理
    setTimeout(() => {
      downloadItemCache.delete(downloadId);
      handledDownloads.delete(downloadId);
    }, 30_000);
  });

  /**
   * 兜底拦截入口。
   *
   * 策略：
   * 1. 给第二层（onDeterminingFilename）一个短窗口优先处理（Chrome）。
   * 2. 并发等待两种元数据来源（responseDownloadCache / downloadItem），
   *    共享 META_MAX_WAIT(5s) 总上限，哪个先到走哪条路径。
   * 3. 拿到元数据后做 shouldIntercept 判断，命中则 cancel+erase+发送。
   */
  async function startFallbackInterception(
    downloadId: number,
    originalItem: chrome.downloads.DownloadItem,
  ) {
    const url = originalItem.url;

    console.log("[FluxDown] startFallbackInterception:", {
      id: downloadId,
      url,
      cacheHit: responseDownloadCache.has(url),
    });

    // 给第二层一个处理窗口（Chrome 有 onDeterminingFilename，suggest cancel 更干净）
    // Firefox 无此 API，10ms 仅用于让事件循环稳定
    await sleep(hasDeterminingFilename ? 150 : 10);
    if (handledDownloads.has(downloadId)) return;

    // 快速路径：缓存已就绪，直接处理，不进入轮询
    const immediateCached = responseDownloadCache.get(url);
    if (immediateCached) {
      await handleResponseCacheHit(
        downloadId,
        url,
        originalItem,
        immediateCached,
      );
      return;
    }

    // 慢速路径：两路并发轮询，共享 deadline（Bug 1+6 修复）
    const deadline = Date.now() + META_MAX_WAIT;
    const metaResult = await waitForMeta(
      url,
      downloadId,
      originalItem,
      deadline,
    );
    if (!metaResult) return; // 被其他层处理或 deadline 到达且结果为 null

    if (handledDownloads.has(downloadId)) return;

    if (metaResult.source === "responseCache") {
      await handleResponseCacheHit(
        downloadId,
        url,
        originalItem,
        metaResult.data,
      );
    } else {
      await handleDownloadMetaHit(
        downloadId,
        url,
        originalItem,
        metaResult.data,
      );
    }
  }

  /** 路径A：基于 responseDownloadCache 的响应头数据做拦截判断 */
  async function handleResponseCacheHit(
    downloadId: number,
    url: string,
    originalItem: chrome.downloads.DownloadItem,
    rc: ResponseDownloadInfo,
  ) {
    if (handledDownloads.has(downloadId)) return;
    console.log("[FluxDown] Fallback path A (response cache):", {
      id: downloadId,
      url,
      contentType: rc.contentType,
      contentLength: rc.contentLength,
      dispositionFilename: rc.dispositionFilename,
    });

    const settings = await getCachedSettings();
    if (!settings.enabled) return;
    if (handledDownloads.has(downloadId)) return;

    if (hasActiveBypass(url)) return;

    // App 熔断期内跳过拦截，让浏览器原生下载，避免反复"发送失败→回退"弹窗。
    if (isAppKnownDown()) {
      responseDownloadCache.delete(url);
      return;
    }

    const dispositionFilename = await resolveResponseDispositionFilename(rc);
    const itemInfo: DownloadItemInfo = {
      url,
      fileSize: rc.contentLength > 0 ? rc.contentLength : -1,
      mime: rc.contentType || undefined,
      filename:
        resolveDownloadFilename(
          dispositionFilename,
          originalItem.filename,
          url,
          rc.contentType,
        ) || undefined,
      referrerUrl: originalItem.referrer || undefined,
    };

    const intercept = shouldIntercept(itemInfo, settings);
    console.log("[FluxDown] Path A shouldIntercept:", intercept, itemInfo);
    if (!intercept) return;
    if (handledDownloads.has(downloadId)) return;

    // Bug R2-5 修复：用 try/finally 确保无论 executeFallbackIntercept 是否抛出，
    // responseDownloadCache 中对应 URL 的条目都会被清理，防止再次下载同 URL 命中旧缓存
    // 优先使用 finalUrl（重定向后的真实 URL）
    const fallbackAUrl = (originalItem as any).finalUrl || url;
    try {
      await executeFallbackIntercept(
        downloadId,
        fallbackAUrl,
        originalItem.referrer,
        itemInfo,
        // 重定向场景：传入原始 URL，让 sendToFluxDown 可回退查找 headers 缓存
        fallbackAUrl !== url ? url : undefined,
      );
    } finally {
      responseDownloadCache.delete(url);
    }
  }

  /** 路径B：基于 downloadItem 元数据做拦截判断 */
  async function handleDownloadMetaHit(
    downloadId: number,
    url: string,
    originalItem: chrome.downloads.DownloadItem,
    freshItem: chrome.downloads.DownloadItem,
  ) {
    if (handledDownloads.has(downloadId)) return;
    console.log("[FluxDown] Fallback path B (download meta):", {
      id: downloadId,
      state: freshItem.state,
      mime: freshItem.mime,
      filename: freshItem.filename,
      fileSize: freshItem.fileSize,
    });

    // 下载已完成或被中断，无需处理
    if (
      freshItem.state === "complete" ||
      (freshItem as any).state === "interrupted"
    ) {
      console.log(
        "[FluxDown] Path B: download already complete/interrupted, skip",
      );
      return;
    }

    const settings = await getCachedSettings();
    if (!settings.enabled) return;
    if (handledDownloads.has(downloadId)) return;

    const mime = freshItem.mime || originalItem.mime || undefined;
    const fileSize =
      (freshItem.fileSize > 0 ? freshItem.fileSize : undefined) ??
      (originalItem.fileSize > 0 ? originalItem.fileSize : undefined) ??
      -1;
    const filename = freshItem.filename || originalItem.filename || undefined;

    const itemInfo: DownloadItemInfo = {
      url: freshItem.url || url,
      fileSize,
      mime,
      filename,
      referrerUrl: freshItem.referrer || originalItem.referrer || undefined,
    };

    if (hasActiveBypass(url)) return;

    // App 熔断期内跳过拦截，让浏览器原生下载，避免反复"发送失败→回退"弹窗。
    if (isAppKnownDown()) return;

    const intercept = shouldIntercept(itemInfo, settings);
    console.log("[FluxDown] Path B shouldIntercept:", intercept, itemInfo);
    if (!intercept) return;
    if (handledDownloads.has(downloadId)) return;

    // 优先使用 finalUrl（重定向后的真实 URL）
    const fallbackDownloadUrl =
      (freshItem as any).finalUrl ||
      (originalItem as any).finalUrl ||
      itemInfo.url;
    await executeFallbackIntercept(
      downloadId,
      fallbackDownloadUrl,
      freshItem.referrer || originalItem.referrer,
      itemInfo,
      // 重定向场景：传入原始 URL，让 sendToFluxDown 可回退查找 headers 缓存
      fallbackDownloadUrl !== url ? url : undefined,
    );
  }

  /**
   * 执行兜底拦截：cancel + erase + 发送到 FluxDown
   *
   * 策略：先取消浏览器下载，再发送到 FluxDown。
   * 原因：如果先发送再取消，异步等待期间浏览器下载持续进行，
   * 小文件可能在 cancel 前已完成，导致"双下载"（用户同时看到
   * FluxDown 下载和浏览器已完成的下载）。Firefox 尤为严重，
   * 因为它没有 onDeterminingFilename，兜底层是唯一拦截路径。
   * 若发送失败，通过 fallbackToBrowserDownload 重新发起浏览器下载。
   */
  async function executeFallbackIntercept(
    downloadId: number,
    url: string,
    referrer: string | undefined,
    itemInfo: DownloadItemInfo,
    originalUrl?: string,
  ) {
    // 标记为 fallback 已处理，阻止其他层重复拦截
    handledDownloads.set(downloadId, "fallback");

    const cleanFilename = extractCleanFilename(
      itemInfo.filename,
      url,
      itemInfo.mime,
    );

    // 取消浏览器下载并抹除记录，防止双下载 + 残留失败/已取消记录（见 issue #21）。
    // 详见 cancelAndErase：Firefox 需等记录落终态再 erase 并重试确认。
    await cancelAndErase(downloadId);

    // 再发送到 FluxDown
    let sendOk = false;
    try {
      sendOk = await sendToFluxDown(
        url,
        referrer,
        cleanFilename,
        itemInfo.fileSize,
        itemInfo.mime,
        originalUrl,
      );
    } catch (e) {
      console.error(
        "[FluxDown] executeFallbackIntercept: sendToFluxDown threw:",
        e,
      );
    }

    if (!sendOk) {
      // 发送失败，先 ping 确认 App 是否在线再决定是否回退
      console.warn(
        "[FluxDown] executeFallbackIntercept: send failed, checking app status before fallback:",
        url,
      );
      handledDownloads.delete(downloadId);
      await fallbackAfterSendFailure(url, cleanFilename);
    }
  }

  // === 第二层：onDeterminingFilename（主拦截） ===
  // 在浏览器弹出「另存为」对话框之前触发，
  // suggest() 释放文件名管线 + downloads.cancel() 取消下载，不弹出任何浏览器下载 UI。
  // Firefox 不支持此 API，完全依赖第三层兜底拦截
  // #527/#609/#199/#293 修复：
  // 1) 拦截关闭（或未启用）时必须真正 removeListener 把该事件让给其他扩展
  //    （脚本猫/PDM/IDM 等）——仅在回调里 suggest() 放行不够，Chrome 只要
  //    本扩展仍注册着 listener 就视为参与"确定文件名"的竞争，见上方
  //    setDeterminingFilenameListenerActive()（由 warmup / storage.onChanged 驱动）。
  // 2) 所有"放行"分支一律改为不带参数的 suggest()，不再重复回填
  //    { filename: downloadItem.filename }——哪怕值与浏览器当前默认值一致，
  //    显式传参也会被 Chrome 视为本扩展"确定了文件名"，与其他扩展的建议冲突
  //    （典型现象：脚本猫报"另一扩展已确定其他文件名"，PDM/IDM 自定义文件名丢失）。
  type DeterminingFilenameListener = Parameters<
    NonNullable<typeof browser.downloads.onDeterminingFilename>["addListener"]
  >[0];
  const handleDeterminingFilename: DeterminingFilenameListener = (
    downloadItem,
    suggest,
  ) => {
    const url = downloadItem.url;
    // finalUrl covers every ordinary href redirected through an API/CDN, not just
    // a site-specific preempted URL.
    const downloadUrl = (downloadItem as any).finalUrl || url;
    let filenameResolved = false;

    const resolveFilename = () => {
      if (filenameResolved) return;
      filenameResolved = true;
      suggest();
    };
    const releaseToBrowser = () => {
      handledDownloads.delete(downloadItem.id);
      resolveFilename();
    };
    const cancelBeforeResolvingFilename = () =>
      cancelBeforeFilenameResolution(
        downloadItem.id,
        (id) => browser.downloads.cancel(id),
        (query) => browser.downloads.erase(query),
        resolveFilename,
      );
    const collectItemInfo = (): {
      itemInfo: DownloadItemInfo;
      referrer: string | undefined;
    } => {
      const cached = downloadItemCache.get(downloadItem.id);
      const mime = downloadItem.mime || cached?.mime || undefined;
      const fileSize =
        (downloadItem.fileSize > 0 ? downloadItem.fileSize : undefined) ??
        (cached && cached.fileSize > 0 ? cached.fileSize : undefined) ??
        -1;
      const filename = downloadItem.filename || cached?.filename || undefined;
      const referrer = cached?.referrer || undefined;
      return {
        itemInfo: { url, fileSize, mime, filename, referrerUrl: referrer },
        referrer,
      };
    };

    const interceptAfterCancellation = async (
      itemInfo: DownloadItemInfo,
      referrer: string | undefined,
    ) => {
      let cancellationSucceeded = false;
      let cleanFilename: string | undefined;
      try {
        // Chrome only settles downloads.cancel after this listener advances. The
        // helper requests cancel first, then releases suggest immediately.
        cancellationSucceeded = await cancelBeforeResolvingFilename();
        if (!cancellationSucceeded) {
          // Browser cancellation did not happen: retain its native download and
          // do not also create a FluxDown capture window.
          console.debug(
            "[FluxDown] onDeterminingFilename: cancellation did not complete; leaving browser download intact:",
            downloadItem.id,
          );
          return;
        }

        const cachedResponse =
          [downloadUrl, url]
            .map((u) => responseDownloadCache.get(u))
            .find((r) => r?.dispositionFilename) ?? undefined;
        const dispositionFilename = cachedResponse
          ? await resolveResponseDispositionFilename(cachedResponse)
          : "";
        cleanFilename = resolveDownloadFilename(
          dispositionFilename,
          itemInfo.filename,
          downloadUrl,
          itemInfo.mime,
        );
        const sendOk = await sendToFluxDown(
          downloadUrl,
          referrer,
          cleanFilename,
          itemInfo.fileSize,
          itemInfo.mime,
          // finalUrl is the engine target; original url remains a cache fallback
          // for request headers and captured form transactions.
          downloadUrl !== url ? url : undefined,
        );
        if (!sendOk) {
          handledDownloads.delete(downloadItem.id);
          await fallbackAfterSendFailure(downloadUrl, cleanFilename).catch(
            (error) =>
              console.error(
                "[FluxDown] failed to restore browser download after send failure:",
                error,
              ),
          );
        }
      } catch (error) {
        console.error(
          "[FluxDown] onDeterminingFilename interception failed:",
          error,
        );
        if (cancellationSucceeded) {
          handledDownloads.delete(downloadItem.id);
          await fallbackAfterSendFailure(downloadUrl, cleanFilename).catch(
            (fallbackError) =>
              console.error(
                "[FluxDown] failed to restore browser download after interception error:",
                fallbackError,
              ),
          );
        } else if (!filenameResolved) {
          releaseToBrowser();
        }
      }
    };

    const resolveWithLoadedSettings = async () => {
      try {
        const settings = await getCachedSettings();
        if (!settings.enabled || hasActiveBypass(url) || isAppKnownDown()) {
          releaseToBrowser();
          return;
        }

        const { itemInfo, referrer } = collectItemInfo();
        if (!shouldIntercept(itemInfo, settings)) {
          releaseToBrowser();
          return;
        }

        console.log("[FluxDown] Intercepting download (onDeterminingFilename):", {
          url,
          downloadUrl,
          mime: itemInfo.mime,
          filename: itemInfo.filename,
          fileSize: itemInfo.fileSize,
          mode: settings.interceptMode,
        });
        await interceptAfterCancellation(itemInfo, referrer);
      } catch (error) {
        console.error(
          "[FluxDown] failed while resolving interception settings:",
          error,
        );
        if (!filenameResolved) releaseToBrowser();
      } finally {
        downloadItemCache.delete(downloadItem.id);
      }
    };

    // FluxDown cannot replay blob/data sources; do not compete with other
    // download extensions for their filename decision.
    if (url.startsWith("blob:") || url.startsWith("data:")) {
      resolveFilename();
      return;
    }

    // The onCreated fallback already owns this item. Cancel first here as well,
    // otherwise suggest could launch Save As while that capture is in flight.
    if (handledDownloads.get(downloadItem.id) === "fallback") {
      console.log(
        "[FluxDown] onDeterminingFilename: already handled by fallback, cancelling:",
        downloadItem.id,
      );
      void cancelBeforeResolvingFilename().then((cancelled) => {
        if (!cancelled) {
          console.debug(
            "[FluxDown] fallback download had already ended before filename cancellation:",
            downloadItem.id,
          );
        }
      });
      return true;
    }

    // One-time transit URLs must first execute their page script. Direct URLs and
    // all ordinary redirects take the generic cancellation path below.
    const preemptEntry = preemptedUrls.get(url);
    if (preemptEntry && preemptEntry.expiry > Date.now()) {
      if (downloadUrl === url) {
        console.log(
          "[FluxDown] onDeterminingFilename: preempted URL has not redirected; letting browser handle:",
          url,
        );
        resolveFilename();
        return;
      }
      console.log(
        "[FluxDown] onDeterminingFilename: preempted URL redirected, intercepting finalUrl:",
        downloadUrl,
      );
    }

    // Claim before any await so onCreated cannot send a second capture while
    // Chrome waits for the filename decision.
    handledDownloads.set(downloadItem.id, "primary");

    const cachedSettings = _settingsCache;
    if (cachedSettings !== null) {
      if (
        !cachedSettings.enabled ||
        hasActiveBypass(url) ||
        isAppKnownDown()
      ) {
        releaseToBrowser();
        downloadItemCache.delete(downloadItem.id);
        return;
      }

      const { itemInfo, referrer } = collectItemInfo();
      if (shouldIntercept(itemInfo, cachedSettings)) {
        console.log("[FluxDown] Intercepting download (sync settings):", {
          url,
          downloadUrl,
          mime: itemInfo.mime,
          filename: itemInfo.filename,
          fileSize: itemInfo.fileSize,
          mode: cachedSettings.interceptMode,
        });
        void interceptAfterCancellation(itemInfo, referrer).finally(() => {
          downloadItemCache.delete(downloadItem.id);
        });
        return true;
      }

      // Complete metadata lets us immediately hand non-matching downloads back.
      // Otherwise wait for the same metadata/settings path used during cold start.
      if (itemInfo.mime || itemInfo.filename) {
        releaseToBrowser();
        downloadItemCache.delete(downloadItem.id);
        return;
      }
    }

    // Chrome may surface Save As before onCreated. Keep the filename pipeline
    // pending for cold-start/metadata-empty decisions; it is the only path that
    // can cancel before the chooser is scheduled.
    void resolveWithLoadedSettings();
    return true;
  };

  if (browser.downloads.onDeterminingFilename) {
    browser.downloads.onDeterminingFilename.addListener(
      handleDeterminingFilename,
    );
    _determiningFilenameListenerActive = true;
  }

  // ===== 消息处理（Popup + Content Script） =====
  //
  // Firefox：直接返回 Promise。Firefox MV2 中 "return true + 异步 sendResponse"
  // 不可靠——sendResponse 被调用后响应值经常被丢弃，popup 收到 undefined。
  //
  // Chromium：Chrome 148 之前 onMessage 监听器不能返回 Promise（返回值被忽略，
  // 发送方收到 undefined），必须 "return true + sendResponse"。该写法在所有
  // Chromium 版本上都成立。
  if (import.meta.env.FIREFOX) {
    browser.runtime.onMessage.addListener((message, sender) => {
      const reply = handleMessage(message, sender).catch((err) => ({
        error: String(err),
      }));
      // 类型声明只允许 boolean 返回；Firefox 运行时接受 Promise。
      return reply as unknown as true;
    });
  } else {
    browser.runtime.onMessage.addListener(
      (message, sender, sendResponse: (response?: unknown) => void) => {
        handleMessage(message, sender).then(sendResponse, (err) =>
          sendResponse({ error: String(err) }),
        );
        return true;
      },
    );
  }

  // ──────────────────────────────────────────────────────────────
  // 弱网可靠性：发送失败重队列
  // ──────────────────────────────────────────────────────────────
  // 当 App 未运行或网络瞬断导致发送失败时，将请求入队。
  // Service Worker 保活期间持续重试；下次 background 激活时也会恢复。
  // 队列持久化到 chrome.storage.local，防止 SW 被回收时数据丢失。
  interface PendingRequest {
    request: DownloadRequest;
    failedAt: number;
    retries: number;
  }

  const PENDING_QUEUE_KEY = "pendingDownloadQueue";
  const MAX_PENDING_RETRIES = 5;
  // 指数退避：2^retry * 1000ms，上限 30s
  function retryDelay(retries: number): number {
    return Math.min(Math.pow(2, retries) * 1000, 30_000);
  }

  // Bug 3 修复：用串行 Promise 链保证 enqueuePending / flushPendingQueue
  // 的 read-modify-write 是原子的，杜绝并发竞态导致队列数据丢失
  let _queueChain: Promise<void> = Promise.resolve();

  function enqueuePending(request: DownloadRequest): Promise<void> {
    _queueChain = _queueChain.then(async () => {
      try {
        const stored = await browser.storage.local.get(PENDING_QUEUE_KEY);
        const queue: PendingRequest[] = stored?.[PENDING_QUEUE_KEY] ?? [];
        // Bug 7 修复：去重以 url+filename 联合键，允许同 URL 不同文件名重试
        // 避免用户两次手动发送同一 URL 但第二次被错误去重
        const key = `${request.url}|${request.filename ?? ""}`;
        const isDup = queue.some(
          (p) => `${p.request.url}|${p.request.filename ?? ""}` === key,
        );
        if (!isDup) {
          queue.push({ request, failedAt: Date.now(), retries: 0 });
          await browser.storage.local.set({ [PENDING_QUEUE_KEY]: queue });
          console.log("[FluxDown] Enqueued pending request:", request.url);
        }
      } catch (e) {
        console.warn("[FluxDown] Failed to enqueue pending request:", e);
      }
    });
    return _queueChain;
  }

  function flushPendingQueue(): Promise<void> {
    _queueChain = _queueChain.then(async () => {
      let stored: Record<string, any>;
      try {
        stored = await browser.storage.local.get(PENDING_QUEUE_KEY);
      } catch {
        return;
      }
      const queue: PendingRequest[] = stored[PENDING_QUEUE_KEY] ?? [];
      if (queue.length === 0) return;

      // Bug R2-4 修复：将队列分成"本次需重试"和"暂不需要重试"两组，
      // 对"本次需重试"的条目并发执行（避免串行等待 5 轮 x 10s 的超时），
      // 完成后合并结果，一次写回 storage。
      const now = Date.now();
      const toRetry: PendingRequest[] = [];
      const notYet: PendingRequest[] = [];

      for (const item of queue) {
        if (item.retries >= MAX_PENDING_RETRIES) {
          console.warn(
            "[FluxDown] Pending request exceeded max retries, dropping:",
            item.request.url,
          );
          continue; // 丢弃
        }
        if (now - item.failedAt < retryDelay(item.retries)) {
          notYet.push(item); // 还没到重试时间
          continue;
        }
        toRetry.push(item);
      }

      if (toRetry.length === 0) {
        // 无需写回（队列内容未变）
        return;
      }

      // 并发重试所有到期条目
      const retryResults = await Promise.allSettled(
        toRetry.map((item) => sendDownloadRequest(item.request)),
      );

      const remaining: PendingRequest[] = [...notYet];
      for (let i = 0; i < toRetry.length; i++) {
        const result = retryResults[i];
        const item = toRetry[i];
        if (result.status === "fulfilled" && result.value.success) {
          console.log("[FluxDown] Pending request flushed:", item.request.url);
          // 队列重试成功 → App 已恢复，解除熔断（review 发现 #6）。
          markAppUp();
          incrementStat("sent").catch(() => {});
          // 成功则不再放回 remaining
        } else {
          remaining.push({
            ...item,
            retries: item.retries + 1,
            failedAt: Date.now(),
          });
        }
      }

      try {
        await browser.storage.local.set({ [PENDING_QUEUE_KEY]: remaining });
      } catch {
        /* ignore */
      }
    });
    return _queueChain;
  }

  // 启动时尝试刷新上次未发送的队列
  flushPendingQueue().catch(() => {});
  // 每 30 秒轮询一次
  setInterval(() => {
    flushPendingQueue().catch(() => {});
  }, 30_000);

  // ──────────────────────────────────────────────────────────────
  // 任务面板：完成通知轮询（chrome.alarms）
  // ──────────────────────────────────────────────────────────────
  // 仅当"已知存在活跃任务"（pending/downloading/preparing）时才创建低频
  // periodic alarm 轮询任务列表；无活跃任务时清除 alarm，避免长期空转。
  // 活跃感知来源：
  //   a) 本地下载请求发送成功后（sendToFluxDown / batchDownload），乐观地
  //      认为"可能有活跃任务"，启动 alarm；
  //   b) 每次拿到任务列表（无论来自 alarm 轮询还是 popup 的 nmh-tasks 消息）
  //      时按其中是否存在活跃状态任务续期或停止。
  // 远程模式下投递到远程服务器的任务不经过本地 NMH，nmhListTasks 天然拿不
  // 到它们，故本轮询自动不参与远程任务的完成通知。

  const TASK_POLL_ALARM_NAME = "fluxdown-task-poll";
  // Chrome 对 periodInMinutes < 0.5 不予兑现（会被夹到 30s 一次；未打包的
  // 开发者模式下不受限）。这里按需求写 0.1（6s）表达"尽可能快"的意图，
  // 生产环境实际以 Chrome 的 30s 下限为准，属预期内的可接受降级。
  const TASK_POLL_ALARM_PERIOD_MIN = 0.1;
  // 判定"活跃"的任务状态：0=pending, 1=downloading, 5=preparing。
  const ACTIVE_TASK_STATUSES = new Set([0, 1, 5]);
  const TASK_STATUS_COMPLETED = 3;

  async function ensureTaskPollAlarm(): Promise<void> {
    try {
      const existing = await browser.alarms.get(TASK_POLL_ALARM_NAME);
      if (existing) return;
      await browser.alarms.create(TASK_POLL_ALARM_NAME, {
        periodInMinutes: TASK_POLL_ALARM_PERIOD_MIN,
      });
    } catch (e) {
      console.warn("[FluxDown] ensureTaskPollAlarm failed:", e);
    }
  }

  async function clearTaskPollAlarm(): Promise<void> {
    try {
      await browser.alarms.clear(TASK_POLL_ALARM_NAME);
    } catch {
      /* ignore */
    }
  }

  /** download/batchDownload 成功发送后调用：远程通道不参与本地完成通知。 */
  function maybeArmTaskPollForChannel(channel?: "local" | "remote"): void {
    if (channel === "remote") return;
    ensureTaskPollAlarm().catch(() => {});
  }

  // 任务状态快照（taskId → 上次已知 status），用于 diff 出"上次非完成 →
  // 本次完成"的任务。MV3 Service Worker 会被随时终止，快照必须落 storage
  // 才能跨重启存活；storage.session 不可用时（极旧浏览器）退化到
  // storage.local。
  const TASK_SNAPSHOT_SESSION_KEY = "fluxdownTaskSnapshot";
  const TASK_SNAPSHOT_LOCAL_KEY = "fluxdownTaskSnapshotFallback";
  let _taskSnapshotCache: Record<string, number> | null = null;

  async function loadTaskSnapshot(): Promise<Record<string, number>> {
    if (_taskSnapshotCache) return _taskSnapshotCache;
    try {
      const session = (browser.storage as any).session;
      if (session) {
        const stored = await session.get(TASK_SNAPSHOT_SESSION_KEY);
        _taskSnapshotCache = stored?.[TASK_SNAPSHOT_SESSION_KEY] ?? {};
        return _taskSnapshotCache!;
      }
    } catch {
      /* 落到 storage.local 兜底 */
    }
    try {
      const stored = await browser.storage.local.get(TASK_SNAPSHOT_LOCAL_KEY);
      _taskSnapshotCache =
        (stored?.[TASK_SNAPSHOT_LOCAL_KEY] as Record<string, number> | undefined) ??
        {};
    } catch {
      _taskSnapshotCache = {};
    }
    return _taskSnapshotCache!;
  }

  function persistTaskSnapshot(snapshot: Record<string, number>): void {
    _taskSnapshotCache = snapshot;
    try {
      const session = (browser.storage as any).session;
      if (session?.set) {
        session.set({ [TASK_SNAPSHOT_SESSION_KEY]: snapshot }).catch(() => {});
        return;
      }
    } catch {
      /* 落到 storage.local 兜底 */
    }
    browser.storage.local
      .set({ [TASK_SNAPSHOT_LOCAL_KEY]: snapshot })
      .catch(() => {});
  }

  const TASK_NOTIFICATION_PREFIX = "fluxdown-task-";

  function extractTaskIdFromNotificationId(
    notificationId: string,
  ): string | null {
    return notificationId.startsWith(TASK_NOTIFICATION_PREFIX)
      ? notificationId.slice(TASK_NOTIFICATION_PREFIX.length)
      : null;
  }

  /**
   * 发出"任务已完成"桌面通知。
   * Chrome：带「打开文件」「打开文件夹」两个按钮（onButtonClicked 处理）。
   * Firefox（MV2 不支持 buttons）：降级为点击通知主体 = 打开文件夹
   * （onClicked 处理，见下方监听器）。
   */
  function notifyTaskCompleted(task: TaskBrief): void {
    if (!browser.notifications?.create) return;
    const notificationId = `${TASK_NOTIFICATION_PREFIX}${task.taskId}`;
    const opts: Record<string, any> = {
      type: "basic",
      iconUrl: "/icon/128.png",
      title: t("notify.taskCompletedTitle"),
      message: t("notify.taskCompletedDetail", {
        name: task.fileName || task.taskId,
      }),
    };
    if (!import.meta.env.FIREFOX) {
      opts.buttons = [
        { title: t("notify.openFile") },
        { title: t("notify.openFolder") },
      ];
    }
    try {
      Promise.resolve(
        browser.notifications.create(notificationId, opts as any),
      ).catch(() => {});
    } catch (e) {
      console.warn("[FluxDown] notifyTaskCompleted: failed to create:", e);
    }
  }

  if (browser.notifications?.onButtonClicked) {
    browser.notifications.onButtonClicked.addListener(
      (notificationId, buttonIndex) => {
        const taskId = extractTaskIdFromNotificationId(notificationId);
        if (!taskId) return;
        const op = buttonIndex === 0 ? nmhOpenFile : nmhRevealFile;
        op(taskId).catch(() => {});
        Promise.resolve(browser.notifications.clear(notificationId)).catch(
          () => {},
        );
      },
    );
  }

  if (browser.notifications?.onClicked) {
    browser.notifications.onClicked.addListener((notificationId) => {
      // Chrome 下点击通知主体不做动作（两个按钮已覆盖打开文件/文件夹诉求）；
      // Firefox MV2 不支持 buttons，点击主体降级为"打开文件夹"。
      if (!import.meta.env.FIREFOX) return;
      const taskId = extractTaskIdFromNotificationId(notificationId);
      if (!taskId) return;
      nmhRevealFile(taskId).catch(() => {});
      Promise.resolve(browser.notifications.clear(notificationId)).catch(
        () => {},
      );
    });
  }

  // 串行 Promise 链：防止 alarm 轮询与 popup nmh-tasks 轮询并发触发时对
  // 快照的 read-modify-write 产生竞态（同一次"完成"被两条路径各通知一次）。
  // 与文件顶部 _statChain / _queueChain 是同一 Bug 4 修复模式。
  let _taskSnapshotChain: Promise<void> = Promise.resolve();

  async function processTasksPollResultInner(
    tasks: TaskBrief[],
  ): Promise<void> {
    const prevSnapshot = await loadTaskSnapshot();
    const nextSnapshot: Record<string, number> = {};
    const newlyCompleted: TaskBrief[] = [];

    for (const task of tasks) {
      nextSnapshot[task.taskId] = task.status;
      const prevStatus = prevSnapshot[task.taskId];
      if (
        prevStatus !== undefined &&
        prevStatus !== TASK_STATUS_COMPLETED &&
        task.status === TASK_STATUS_COMPLETED
      ) {
        newlyCompleted.push(task);
      }
    }
    persistTaskSnapshot(nextSnapshot);

    if (newlyCompleted.length > 0) {
      const settings = await loadSettings();
      if (settings.notifyLocalTask === true) {
        for (const task of newlyCompleted) {
          notifyTaskCompleted(task);
        }
      }
    }

    const hasActive = tasks.some((task) =>
      ACTIVE_TASK_STATUSES.has(task.status),
    );
    if (hasActive) {
      await ensureTaskPollAlarm();
    } else {
      await clearTaskPollAlarm();
    }
  }

  /**
   * 处理一批任务快照：diff 出新完成的任务并按设置弹通知，同时按是否还有
   * 活跃任务决定续期还是清除轮询 alarm。alarm 触发与 popup 的 nmh-tasks
   * 轮询共用此函数，保证两条路径下的通知/alarm 状态完全一致。
   */
  function processTasksPollResult(tasks: TaskBrief[]): Promise<void> {
    _taskSnapshotChain = _taskSnapshotChain.then(() =>
      processTasksPollResultInner(tasks),
    );
    return _taskSnapshotChain;
  }

  browser.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name !== TASK_POLL_ALARM_NAME) return;
    nmhListTasks()
      .then((result) => {
        if (!result.success) return; // App 暂不可达，等下一轮
        return processTasksPollResult(result.tasks);
      })
      .catch((e) => {
        console.warn("[FluxDown] task poll alarm handler failed:", e);
      });
  });

  // ===== fluxdown:// 自定义协议投递 =====
  // Android 浏览器（Edge/Firefox/Kiwi 等支持扩展的内核）没有 NMH，
  // 协议 URL 触发系统 VIEW intent 是唯一可行链路。桌面客户端同样注册了
  // fluxdown:// 协议（Windows 注册表 / Linux x-scheme-handler / macOS
  // CFBundleURLTypes），开关开启后桌面也走协议唤起 —— 但 NMH 能携带
  // Cookie/Headers/method/body，功能是协议模式的严格超集，桌面默认更推荐
  // 保持关闭；协议模式在桌面的价值是浏览器策略禁用 NMH 等受限环境。

  /** 协议唤起复用的标签页 id（避免每次下载都泄漏一个空白标签页）。 */
  let protocolTabId: number | null = null;
  let protocolTabCloseTimer: ReturnType<typeof setTimeout> | undefined;

  /**
   * 打开 fluxdown://download?url=...&filename=... 协议 URL，触发系统唤起
   * 已注册该协议的应用。复用单个标签页（连续下载不开一排标签），并在最后
   * 一次唤起 4s 后自动回收标签页。
   */
  async function openProtocolUrl(
    url: string,
    filename?: string,
  ): Promise<boolean> {
    const params = new URLSearchParams({ url });
    if (filename) params.set("filename", filename);
    const protocolUrl = `fluxdown://download?${params.toString()}`;
    console.log("[FluxDown] protocol mode — opening:", protocolUrl);
    try {
      if (protocolTabId !== null) {
        try {
          await browser.tabs.update(protocolTabId, {
            url: protocolUrl,
            active: true,
          });
        } catch {
          // 标签页已被用户关闭 — 重新创建
          protocolTabId = null;
        }
      }
      if (protocolTabId === null) {
        const tab = await browser.tabs.create({
          url: protocolUrl,
          active: true,
        });
        protocolTabId = tab.id ?? null;
      }
      // 每次唤起重置回收定时器：4s 无新请求则关闭遗留的空白/错误标签页
      if (protocolTabCloseTimer) clearTimeout(protocolTabCloseTimer);
      protocolTabCloseTimer = setTimeout(() => {
        const id = protocolTabId;
        protocolTabId = null;
        if (id !== null) {
          browser.tabs.remove(id).catch(() => {});
        }
      }, 4000);
      return true;
    } catch (e) {
      console.warn("[FluxDown] failed to open fluxdown:// URL:", e);
      return false;
    }
  }

  // 投递失败的表单 POST 下载：原下载已被取消，回退时需要原 method/body 重放。
  const failedPostRecords = new Map<string, { body?: CapturedBody }>();

  // 被 App/远端明确拒绝（而非不可达）的发送，按 URL 记录供 fallbackAfterSendFailure
  // 区分处理；条目在下次发送同一 URL 或回退消费时清除，数量封顶防泄漏。
  const rejectedSends = new Map<
    string,
    { message?: string; notified: boolean }
  >();
  const REJECT_NOTIFY_INTERVAL_MS = 30_000;
  let _lastRejectNotifyAt = 0;

  // ===== 核心：发送下载请求到 FluxDown App =====
  async function sendToFluxDown(
    url: string,
    referrer?: string,
    filename?: string,
    fileSize?: number,
    mimeType?: string,
    originalUrl?: string,
    storedCookies?: string,
    storedHeaders?: Record<string, string>,
    // 离散音视频轨对的音频轨 URL（通用语义）。非空 = 视频轨 + 音频轨配对，
    // 引擎收到后分别下载 + mux 合并。追加为末位可选参数，不影响既有调用方。
    audioUrl?: string,
  ): Promise<boolean> {
    // === fluxdown:// 自定义协议模式 ===
    // 开启后跳过 NMH/远端投递，经协议 URL 唤起 FluxDown 客户端
    // （Android: 系统 VIEW intent；桌面: 系统协议处理器）。
    // 限制：不经过 NMH，Cookie/Headers/method/body 无法携带，适用于公开文件。
    {
      const protocolSettings = await getCachedSettings();
      // fluxdown:// 只承载单 URL；音视频分轨对必须继续走 NMH/远程，
      // 否则音频轨会被静默丢弃，最终得到无声视频。
      if (protocolSettings.enableFluxdownProtocol && !audioUrl) {
        const ok = await openProtocolUrl(url, filename);
        await incrementStat(ok ? "sent" : "failed");
        if (ok && (await shouldNotifyChannel("local"))) {
          const shownName =
            filename || extractCleanFilename(undefined, url) || url;
          notify(
            t("notify.downloadSent"),
            t("notify.sentToFluxDown", { name: shownName }),
          );
        }
        return ok;
      }
    }

    // === 预热 NMH 链路（fire-and-forget） ===
    // App 冷启动 ~0.7-1s，下方 cookie/认证收集最多 ~500ms。先发 warmup
    // 让两者并行；App 已运行时 warmup 是 ~1ms 本地应答，无副作用。
    warmupNativeHost();

    // === 提取认证信息（Cookie / Authorization 等） ===
    // 策略 1：从 webRequest 缓存获取（最可靠 — 浏览器真正发出的请求头）
    // 重定向修复：onSendHeaders 以浏览器原始请求 URL 为 key 缓存 headers，
    // 但此处传入的 url 可能是 302 重定向后的 finalUrl，导致缓存 miss。
    // 若主 URL 查不到，回退用 originalUrl（重定向前的 URL）再查一次。
    // 策略 1：从 webRequest 缓存获取（最可靠 — 浏览器真正发出的请求头）
    const authFromCache = extractAuthFromCache(url, originalUrl);
    let cookieString = authFromCache.cookies || "";
    let extraHeaders: Record<string, string> = authFromCache.headers || {};
    if (authFromCache.cookies || authFromCache.headers) {
      console.log(
        "[FluxDown] Cookies from webRequest cache:",
        cookieString.length,
        "chars",
      );
      console.log(
        "[FluxDown] Extra headers from webRequest cache:",
        Object.keys(extraHeaders).length,
      );
      // 使用后清理：同时清理 url 和 originalUrl 对应的缓存条目
      requestHeaderCache.delete(url);
      if (originalUrl && originalUrl !== url) {
        requestHeaderCache.delete(originalUrl);
      }
    }

    // 策略 2：通过 chrome.cookies API 提取（兜底）
    // 加超时保护：弱网下 cookies API 偶发阻塞，500ms 内未返回则跳过
    if (!cookieString) {
      try {
        const cookiesResult = await Promise.race([
          browser.cookies.getAll({ url }),
          new Promise<chrome.cookies.Cookie[]>((_, reject) =>
            setTimeout(() => reject(new Error("cookies timeout")), 500),
          ),
        ]);
        cookieString = cookiesResult
          .map((c) => `${c.name}=${c.value}`)
          .join("; ");
        console.log(
          "[FluxDown] Cookies from cookies API:",
          cookiesResult.length,
          "cookies",
        );
      } catch (e) {
        console.warn(
          "[FluxDown] Failed/timeout to extract cookies via API:",
          e,
        );
      }
    }

    // 策略 3：使用资源存储中保存的请求头信息（最终兜底）
    // 资源嗅探时从 webRequest 捕获的 Cookie/Authorization 等认证信息，
    // 即使 requestHeaderCache 已过期（60s）、cookies API 也未能提取到，
    // 仍可从 resource-store 持久存储中恢复。
    // 这是解决"PDF 无权限"等认证丢失问题的关键路径。
    if (!cookieString && storedCookies) {
      cookieString = storedCookies;
      console.log(
        "[FluxDown] Cookies from stored resource:",
        cookieString.length,
        "chars",
      );
    }
    if (
      Object.keys(extraHeaders).length === 0 &&
      storedHeaders &&
      Object.keys(storedHeaders).length > 0
    ) {
      extraHeaders = storedHeaders;
      console.log(
        "[FluxDown] Extra headers from stored resource:",
        Object.keys(extraHeaders).length,
      );
    }

    // #610 修复：确保发给引擎的 headers 携带浏览器真实 UA。
    // 引擎已支持通过 extra_headers 的 User-Agent 键覆盖默认 UA（native/engine
    // downloader.rs 有专门的 UA 降级重试逻辑，注释明确写着"浏览器扩展捕获
    // 下载时通过 extra_headers 传入真实 UA"）——这是现有的、唯一的 UA 传递
    // 通道，并非新增协议字段。webRequest 捕获的头通常已包含 User-Agent，
    // 但右键菜单/快捷下载/资源面板手动下载等路径没有捕获记录，这里统一
    // 兜底补上 navigator.userAgent，确保所有下载路径都带上浏览器真实 UA
    // （不少反爬站点仅传 Cookie 而 UA 不一致仍会拦截，见 #610）。
    extraHeaders = withBrowserUserAgent(extraHeaders) ?? extraHeaders;

    // 反查浏览器原始 method 与 body —— 修复 form-POST 触发的下载（uupdump 等）。
    // 优先以下载发起的真实 url 查找；命中不到时回退到重定向前的 originalUrl。
    const reqRecord = lookupRequestRecord(url, originalUrl);
    if (reqRecord.method || reqRecord.body) {
      console.log(
        "[FluxDown] Captured original request transaction: method=",
        reqRecord.method ?? "GET",
        "has_body=",
        !!reqRecord.body,
      );
      // 用过即清——避免下次同 URL 复用旧 record
      requestRecordCache.delete(url);
      if (originalUrl && originalUrl !== url) {
        requestRecordCache.delete(originalUrl);
      }
    }

    const request: DownloadRequest = {
      url,
      filename: filename || "",
      referrer: referrer || "",
      cookies: cookieString,
      headers: Object.keys(extraHeaders).length > 0 ? extraHeaders : undefined,
      fileSize,
      mimeType,
      method: reqRecord.method,
      body: reqRecord.body,
      audioUrl: audioUrl || undefined,
    };

    console.log("[FluxDown] Sending to FluxDown app:", request);

    rejectedSends.delete(url);
    failedPostRecords.delete(url);
    const response = await sendDownloadRequest(request);
    const notifyOk = await shouldNotifyChannel(response.channel);

    if (response.success) {
      // 发送成功 → App 在线，立即解除可用性熔断。
      markAppUp();
      await incrementStat("sent");
      maybeArmTaskPollForChannel(response.channel);
      // 任务已创建提示（本地 NMH / 远程 server 分别受配置页两个开关控制；
      // notify 内置 5s 同文去重，密集拦截同一文件不会弹窗风暴）。
      if (notifyOk) {
        const shownName =
          filename || extractCleanFilename(undefined, url) || url;
        notify(
          t("notify.downloadSent"),
          t("notify.sentToFluxDown", { name: shownName }),
        );
      }
      return true;
    } else {
      await incrementStat("failed");
      console.warn(
        "[FluxDown] sendToFluxDown failed:",
        response.message,
        "url:",
        url,
      );
      if (notifyOk) {
        notify(t("notify.sendFailed"), describeSendError(response.message));
      }
      if (!isUnreachableFailure(response)) {
        if (rejectedSends.size >= 64) rejectedSends.clear();
        rejectedSends.set(url, {
          message: response.message,
          notified: notifyOk,
        });
      }
      if (reqRecord.method) {
        if (failedPostRecords.size >= 32) failedPostRecords.clear();
        failedPostRecords.set(url, { body: reqRecord.body });
      }
      return false;
    }
  }

  /**
   * 把发送失败的原始 message 转成用户可读文案：
   * 远程通道的稳定前缀（remote_*）映射到与 popup「测试连接」一致的 i18n 文案，
   * 其余（NMH 超时/端口断开/服务端业务错误）原样带入通用连接失败模板。
   */
  function describeSendError(message?: string): string {
    if (message === "remote_auth_failed") return t("remote.testAuthFailed");
    if (message === "remote_not_configured")
      return t("remote.testNotConfigured");
    if (message?.startsWith("remote_unreachable"))
      return t("remote.testUnreachable");
    return t("notify.connectionFailed", { message: message || "unknown" });
  }

  /**
   * 任务发送通知的按通道开关（配置页「通用」分类）：
   * "remote" → notifyRemoteTask，"local"/未标记（直连 NMH 旧路径）→ notifyLocalTask。
   */
  async function shouldNotifyChannel(
    channel?: "local" | "remote",
  ): Promise<boolean> {
    const settings = await loadSettings();
    return channel === "remote"
      ? settings.notifyRemoteTask !== false
      : settings.notifyLocalTask === true;
  }

  /** 把捕获的 POST 请求体还原为 downloads.download 可用的字符串 body；无法还原返回 null。 */
  function buildPostReplay(
    url: string,
    body?: CapturedBody,
  ): { body: string; headers: { name: string; value: string }[] } | null {
    if (!body) return { body: "", headers: [] };
    if (body.kind === "formData") {
      const params = new URLSearchParams();
      for (const [field, values] of Object.entries(body.fields)) {
        for (const value of values) params.append(field, value);
      }
      return {
        body: params.toString(),
        headers: [
          {
            name: "Content-Type",
            value: "application/x-www-form-urlencoded",
          },
        ],
      };
    }
    try {
      const binary = atob(body.bytesB64);
      const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
      const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
      const cached = extractAuthFromCache(url).headers ?? {};
      const contentType = Object.entries(cached).find(
        ([name]) => name.toLowerCase() === "content-type",
      )?.[1];
      return {
        body: text,
        headers: contentType ? [{ name: "Content-Type", value: contentType }] : [],
      };
    } catch {
      return null;
    }
  }

  /**
   * 回退到浏览器下载：当发送到 FluxDown 失败时，重新发起浏览器下载。
   * 用于 onDeterminingFilename 同步路径中，下载已被 cancel+erase 后需要恢复的场景。
   * 设置 bypassToken 防止新下载被再次拦截。
   *
   * @param silent - 静默模式，不弹出通知。默认 true：所有现有调用方都显式静默，
   *                 失败通知统一由 fallbackAfterSendFailure 的一次性提示接管。
   *                 默认静默可避免未来新增调用方误触发逐次回退弹窗（#308 #346）。
   */
  async function fallbackToBrowserDownload(
    url: string,
    filename?: string,
    silent = true,
  ) {
    // 设置 bypass token，15 秒内对该 URL 的下载不拦截
    bypassTokens.set(url, Date.now() + 15_000);
    // 表单 POST 触发的下载必须以原 method/body 重放，GET 会拿到错误页。
    const post = failedPostRecords.get(url);
    failedPostRecords.delete(url);
    try {
      const opts: chrome.downloads.DownloadOptions = { url };
      if (filename) opts.filename = filename;
      if (post) {
        const replay = buildPostReplay(url, post.body);
        if (!replay) {
          // 多部分/二进制请求体无法无损重建，GET 重发只会落盘一个损坏文件。
          notify(t("notify.sendFailed"), url);
          return;
        }
        opts.method = "POST";
        opts.body = replay.body;
        opts.headers = replay.headers;
      }
      await browser.downloads.download(opts);
      console.log("[FluxDown] Fallback: re-initiated browser download:", url);
    } catch (e) {
      console.error(
        "[FluxDown] Fallback: failed to re-initiate browser download:",
        e,
      );
    }
    if (!silent) {
      notify(
        t("notify.fallbackBrowser"),
        t("notify.fallbackBrowserDetail", { url }),
      );
    }
  }

  /**
   * sendToFluxDown 失败后的智能回退。
   *
   * 失败分两类：
   * - 业务拒绝（App/远端明确回了失败：队列满、鉴权失败、建任务失败…）：请求没有
   *   被处理，直接回退浏览器下载，不探活、不动熔断器（App 明明在线）。
   * - 不可达/超时/未知：NMH 通信存在瞬态失败，消息可能已送达 App 但响应丢失，
   *   直接回退会导致"双下载"。此时先 ping：在线 → 视为已送达，跳过回退；
   *   不可达 → 回退并启动熔断。
   */
  async function fallbackAfterSendFailure(
    url: string,
    filename?: string,
    silent = false,
  ): Promise<void> {
    const rejection = rejectedSends.get(url);
    rejectedSends.delete(url);
    if (rejection) {
      console.warn(
        "[FluxDown] Send rejected by FluxDown — falling back to browser download:",
        rejection.message,
        url,
      );
      const now = Date.now();
      if (
        !silent &&
        !rejection.notified &&
        now - _lastRejectNotifyAt > REJECT_NOTIFY_INTERVAL_MS
      ) {
        _lastRejectNotifyAt = now;
        notify(t("notify.sendFailed"), describeSendError(rejection.message));
      }
      await fallbackToBrowserDownload(url, filename, true);
      return;
    }

    try {
      // 用带重连重试的探测确认 App 状态：给 App 冷启动/瞬时繁忙第二次机会，
      // 避免单次瞬态 ping 失败把已安装的 App 误判为不可用而误熔断（review 发现 #3）。
      // 经单航班守卫复用并发探测（review round2 发现 #4）。
      const appAlive = await probeAppReachable();
      if (appAlive) {
        // App 在线（消息大概率已送达）→ 解除熔断，跳过回退避免双下载。
        markAppUp();
        console.log(
          "[FluxDown] App is alive after send failure — skipping browser fallback (message likely delivered):",
          url,
        );
        return;
      }
    } catch {
      // ping 本身异常，视为 App 不可达，继续回退
    }

    // App 二次确认仍不可达 → 启动熔断：冷却期内后续下载直接放行给浏览器，
    // 不再反复"拦截→失败→回退"。同时"未检测到 App"提示每轮熔断只弹一次，
    // 替代过去逐次回退都弹"已回退到浏览器下载"造成的弹窗风暴（#308 #346）。
    markAppDown();
    if (!silent && !_appDownNotified) {
      _appDownNotified = true;
      notify(t("notify.appUnavailable"), t("notify.appUnavailableDetail"));
    }
    // 内层回退强制 silent：通知已由上面的一次性提示统一接管。
    await fallbackToBrowserDownload(url, filename, true);
  }

  // ===== 统一消息处理（Popup + Content Script） =====
  /**
   * 查找资源存储中的原始请求。
   *
   * Popup 发来的 DASH 轨道 URL 通常与 resource-store 中的 URL 相同，
   * 但签名参数顺序或已知的缓存参数可能不同。resource-store 使用的也是
   * normalizeUrlForDedup，因此下载兜底查找必须使用同一套归一化规则，
   * 否则嗅探到的 Cookie/Referer 等认证上下文会在 Popup 路径丢失。
   */
  function findStoredResource(
    stored: ReturnType<typeof getResourcesForTab>,
    url: string,
  ): ReturnType<typeof getResourcesForTab>[number] | undefined {
    const exact = stored.find((resource) => resource.url === url);
    if (exact) return exact;
    const normalizedUrl = normalizeUrlForDedup(url);
    return stored.find(
      (resource) => normalizeUrlForDedup(resource.url) === normalizedUrl,
    );
  }

  function mergeStoredHeaders(
    primary: Record<string, string> | undefined,
    secondary: Record<string, string> | undefined,
  ): Record<string, string> | undefined {
    if (!primary && !secondary) return undefined;
    const merged = { ...(secondary || {}), ...(primary || {}) };
    return Object.keys(merged).length > 0 ? merged : undefined;
  }

  function sameOrigin(firstUrl: string, secondUrl: string): boolean {
    try {
      return new URL(firstUrl).origin === new URL(secondUrl).origin;
    } catch {
      return false;
    }
  }

  async function handleMessage(
    message: any,
    sender: chrome.runtime.MessageSender,
  ): Promise<any> {
    if (message.type === "diagnostics-record" || message.type === "diagnostics-export") {
      return handleDiagnostics(message, sender);
    }
    // --- 任务面板（popup ↔ background）：消息用 `type` 字段区分，与其余
    //     历史消息的 `action` 字段并存，互不冲突。---
    switch (message.type) {
      case "nmh-tasks": {
        const result = await nmhListTasks();
        if (!result.success) {
          return { ok: false, connected: false, tasks: [] };
        }
        await processTasksPollResult(result.tasks);
        return { ok: true, connected: true, tasks: result.tasks };
      }
      case "nmh-task-op": {
        const op = message.op;
        const taskId = message.taskId;
        if (
          typeof taskId !== "string" ||
          !taskId ||
          (op !== "pause" && op !== "resume" && op !== "remove")
        ) {
          return { ok: false, message: "invalid_request" };
        }
        const result = await nmhTaskOp(op, taskId);
        return { ok: result.success, message: result.message };
      }
      case "nmh-open-file": {
        const taskId = message.taskId;
        if (typeof taskId !== "string" || !taskId) {
          return { ok: false, message: "invalid_request" };
        }
        const result = await nmhOpenFile(taskId);
        return { ok: result.success, message: result.message };
      }
      case "nmh-reveal-file": {
        const taskId = message.taskId;
        if (typeof taskId !== "string" || !taskId) {
          return { ok: false, message: "invalid_request" };
        }
        const result = await nmhRevealFile(taskId);
        return { ok: result.success, message: result.message };
      }
      case "nmh-warmup": {
        nmhWarmupNativeHost();
        return { ok: true };
      }
      default:
        break; // 非任务面板消息，落入下方按 action 分发
    }

    switch (message.action) {
      // --- Popup 消息 ---
      case "getStatus": {
        const available = await checkFluxDownAvailable();
        // ping 成功证明 App 已恢复 → 立即解除熔断，避免 popup 显示"已连接"
        // 但下载仍被静默旁路到浏览器的自相矛盾（review 发现 #1/#4/#6）。
        if (available) markAppUp();
        const settings = await loadSettings();
        return { connected: available, settings };
      }

      case "toggleEnabled": {
        const currentSettings = await loadSettings();
        const newEnabled = !currentSettings.enabled;
        await persistSettings(
          { ...currentSettings, enabled: newEnabled },
          currentSettings,
        );
        updateIcon(newEnabled);
        return { enabled: newEnabled };
      }

      case "updateSettings": {
        const currentSettings = await loadSettings();
        const merged = { ...currentSettings, ...message.settings };
        await persistSettings(merged, currentSettings);
        return { success: true, settings: merged };
      }

      case "checkConnection": {
        const isAvailable = await checkFluxDownAvailable();
        // ping 成功 → 解除熔断，让接管与连接状态保持一致（review 发现 #6）。
        if (isAvailable) markAppUp();
        return { connected: isAvailable };
      }

      // --- Popup 直连 ping 成功后的熔断恢复信号 ---
      // popup 在自己的上下文直接 ping NMH（不经 background），成功后发来此信号，
      // 让 background 的可用性熔断器及时解除，使"接管"与 popup 显示的"已连接"
      // 保持一致（review 发现 #1/#4/#6）。fire-and-forget，无需返回值。
      case "appConfirmedUp": {
        markAppUp();
        return { success: true };
      }

      // --- Alt+Click 绕过令牌写入（保留向后兼容） ---
      case "addBypassToken": {
        const bypassUrl = message.url as string;
        if (bypassUrl) {
          bypassTokens.set(bypassUrl, Date.now() + 15_000);
        }
        return { success: true };
      }

      // --- Content Script: 资源检测上报 ---
      case "resourceDetected": {
        const tabId = sender.tab?.id;
        if (!tabId || tabId < 0) return { success: false };

        const pageUrl = sender.tab?.url || sender.url || "";
        const payloads: ResourceMessagePayload[] = message.resources || [];
        const reportedPageUrl = payloads.find((payload) => payload.pageUrl)?.pageUrl || pageUrl;
        syncTabPageUrl(tabId, reportedPageUrl);

        if (payloads.length === 0) return { success: true, added: 0 };

        // 资源嗅探开关：关闭时丢弃上报（旧页面的 content script 可能仍在运行）
        const rdSettings = await getCachedSettings();
        if (!rdSettings.resourceSniffing) return { success: true, added: 0 };

        const added = addResources(tabId, reportedPageUrl, payloads);
        if (added > 0) {
          bumpTabVersion(tabResourceVersions, tabId);
          await updateDisplayedBadgeForTab(tabId);
          await notifyContentScript(tabId);
        }
        return { success: true, added };
      }

      case "pageUrlChanged": {
        const tabId = sender.tab?.id;
        const pageUrl = typeof message.pageUrl === "string" ? message.pageUrl : "";
        if (!tabId || tabId < 0 || !pageUrl) return { success: false };
        syncTabPageUrl(tabId, pageUrl);
        await updateDisplayedBadgeForTab(tabId);
        await notifyContentScript(tabId);
        await notifyDashManifest(tabId);
        return { success: true };
      }

      // --- Content Script: DASH manifest 检测上报（权威清晰度 + 轨道 URL）---
      case "dashManifestDetected": {
        const tabId = sender.tab?.id;
        if (!tabId || tabId < 0) return { success: false };

        // 资源嗅探开关：关闭时丢弃（旧页面的 fetch 拦截脚本可能仍在运行）
        const dmSettings = await getCachedSettings();
        if (!dmSettings.resourceSniffing) return { success: false };
        const manifest = normalizeDashManifest(message.manifest);
        if (!manifest) {
          return { success: false };
        }
        syncTabPageUrl(tabId, typeof message.pageUrl === "string" ? message.pageUrl : sender.tab?.url || "");
        const manifestUrl =
          typeof message.manifestUrl === "string" && message.manifestUrl
            ? normalizeUrlForDedup(message.manifestUrl)
            : "__legacy__";
        let stored = tabDashManifests.get(tabId);
        if (!stored) {
          stored = new Map();
          tabDashManifests.set(tabId, stored);
        }
        stored.delete(manifestUrl);
        stored.set(manifestUrl, manifest);
        while (stored.size > MAX_DASH_MANIFESTS_PER_TAB) {
          const oldest = stored.keys().next().value;
          if (typeof oldest !== "string") break;
          stored.delete(oldest);
        }
        bumpTabVersion(tabManifestVersions, tabId);
        await updateDisplayedBadgeForTab(tabId);
        await notifyDashManifest(tabId);
        return { success: true };
      }

      // --- Content Script UI / Popup: 请求指定 tab 的资源列表（含权威 DASH manifest，若已嗅探到）---
      // content script 有 sender.tab 且优先生效；popup/options 等扩展页无 sender.tab，
      // 显式传 message.tabId（自查 tabs.query 的活跃 tab）。
      case "getResources": {
        const tabId =
          sender.tab?.id ??
          (typeof message.tabId === "number" ? message.tabId : -1);
        if (!tabId || tabId < 0) {
          return { resources: [], dashManifest: null, dashManifests: [] };
        }
        const stored = tabDashManifests.get(tabId);
        const dashManifests = stored
          ? Array.from(stored.entries()).map(([url, manifest]) => ({ url, manifest }))
          : [];
        return {
          resources: getResourcesForTab(tabId),
          dashManifest: dashManifests[dashManifests.length - 1]?.manifest ?? null,
          dashManifests,
        };
      }

      // --- Content Script UI / Popup: 清空指定 tab 的嗅探资源列表（#559）---
      // 长会话 SPA（抖音等）不断切换播放会话会持续累积嗅探资源，提供
      // 一键清空入口。清空后同步刷新 badge 并推送空列表给页内面板，
      // 保持 popup / 页内面板两处 UI 与 store 状态一致；后续嗅探到的
      // 新资源会正常重新加入。
      case "clearResources": {
        const tabId =
          sender.tab?.id ??
          (typeof message.tabId === "number" ? message.tabId : -1);
        if (!tabId || tabId < 0) {
          return { success: false, message: "No tab" };
        }
        clearTabProjection(tabId);
        await updateDisplayedBadgeForTab(tabId);
        await notifyContentScript(tabId);
        await notifyDashManifest(tabId);
        return { success: true };
      }

      // --- Content Script UI / Popup: 触发单个资源下载 ---
      case "downloadResource": {
        const url = message.url as string;
        if (!url) return { success: false, message: "No URL" };
        const dlSettings = await getCachedSettings();
        if (!dlSettings.enabled)
          return { success: false, message: "Extension disabled" };
        // 从资源存储中查找匹配的资源，获取嗅探时保存的 cookies/headers/fileSize。
        // 用户从资源面板点击下载时，原始请求的 requestHeaderCache 可能已过期，
        // 必须依赖持久存储的认证信息才能成功下载需要认证的资源（如政务站点 PDF）。
        // Popup 没有 sender.tab；它会把打开资源面板时的活跃 tabId 一并带回。
        // Content Script 仍优先使用 sender.tab，避免信任页面脚本自报的 tabId。
        const dlTabId =
          sender.tab?.id ??
          (typeof message.tabId === "number" ? message.tabId : undefined);
        let resCookies: string | undefined;
        let resHeaders: Record<string, string> | undefined;
        let resFileSize: number | undefined;
        if (typeof dlTabId === "number" && dlTabId >= 0) {
          const tabRes = getResourcesForTab(dlTabId);
          const matched = findStoredResource(tabRes, url);
          const audioMatched =
            typeof message.audioUrl === "string" && message.audioUrl
              && sameOrigin(url, message.audioUrl)
              ? findStoredResource(tabRes, message.audioUrl)
              : undefined;
          resCookies = matched?.cookies || audioMatched?.cookies;
          resHeaders = mergeStoredHeaders(matched?.headers, audioMatched?.headers);
          resFileSize =
            matched && matched.size > 0
              ? matched.size
              : audioMatched && audioMatched.size > 0
                ? audioMatched.size
                : undefined;
        }
        // IDM/NDM 策略：对于从资源面板 / 嗅探触发的下载，必须跳过 probe。
        // 一次性 token URL（如 ctbpsp.com）的 token 已被浏览器消费，
        // probe（HEAD + GET Range:0-0）会再次请求导致 token 失效返回 HTML。
        // fileSize > 0 → 已知大小，跳过 probe
        // fileSize = -1 → 大小未知但确认是下载资源，跳过 probe
        // fileSize = 0/undefined → 正常 probe（仅限手动添加的 URL）
        const effectiveFileSize = message.fileSize || resFileSize || -1;
        const sent = await sendToFluxDown(
          url,
          message.referrer,
          extractCleanFilename(message.filename, url, message.mimeType),
          effectiveFileSize,
          message.mimeType,
          undefined,
          resCookies,
          resHeaders,
          // 离散音视频轨对：内容脚本清晰度选择小窗传来的音频轨 URL（可选）。
          message.audioUrl as string | undefined,
        );
        return {
          success: sent,
          message: sent ? undefined : t("notify.sendFailed"),
        };
      }

      // --- Content Script UI: 批量下载多个资源 ---
      // 本地通道（NMH）：单条 batch_download 消息携带全部条目一次性发给桌面
      // 应用（见 nmhSendBatchDownloadRequest）；超大批量按 ~700KB 分块顺序
      // 发送，避免撞上 NMH/hub 双向强制的单帧 1MB 上限。per-item 的
      // cookies/headers/fileSize 等认证元数据随消息一并送达，由 Rust 侧按
      // URL 缓存，在用户于快速下载对话框确认后逐条恢复；旧版不识别
      // batch_download action 的桌面应用会自动回退为逐条 download 循环。
      // 远程通道（fluxdown_server）：单次 POST /download/batch 发送全部条目。
      case "batchDownload": {
        const rawItems = message.items as Array<{
          url: string;
          audioUrl?: string;
          referrer?: string;
          filename?: string;
          fileSize?: number;
          mimeType?: string;
        }>;
        if (!Array.isArray(rawItems) || rawItems.length === 0) {
          return { success: false, message: "No items" };
        }

        // 同一个候选可能同时由弹窗和页内面板触发，或因快速双击被重复
        // 放进同一批次。相同视频轨 + 音频轨只允许创建一个任务；不同
        // 清晰度/编码的 URL 仍然保留，用户明确选中多档时可以批量下载。
        const seenBatchItems = new Set<string>();
        const items = rawItems.filter((item) => {
          if (!item?.url) return false;
          const key = `${item.url}\u0000${item.audioUrl || ""}`;
          if (seenBatchItems.has(key)) return false;
          seenBatchItems.add(key);
          return true;
        }).map((item) => ({
          ...item,
          // 与单项下载同一规则：extractCleanFilename 处理浏览器路径/脚本端点/
          // 无扩展名（按 mime 补扩展名）；无法确定则留空交引擎探测。
          filename:
            extractCleanFilename(item.filename, item.url, item.mimeType) ||
            undefined,
        }));
        if (items.length === 0) {
          return { success: false, message: "No valid items" };
        }

        // === fluxdown:// 协议模式：批量走逐条协议唤起 ===
        // 复用单标签页顺序导航，每条间隔 800ms 让系统逐条派发
        // （Android: VIEW intent；桌面: 协议处理器 → 第二实例转发）。
        // 认证信息无法携带。
        {
          const protoSettings = await getCachedSettings();
          // fluxdown:// 只承载单 URL；带 audioUrl 的聚合视频必须走 NMH/远程，
          // 避免批量下载时静默丢失音频轨。
          if (
            protoSettings.enableFluxdownProtocol &&
            !items.some((item) => item.audioUrl)
          ) {
            let batchProtoSent = 0;
            for (const item of items) {
              const ok = await openProtocolUrl(item.url, item.filename);
              if (ok) batchProtoSent++;
              await new Promise((r) => setTimeout(r, 800));
            }
            await incrementStat(batchProtoSent > 0 ? "sent" : "failed");
            if (batchProtoSent > 0 && (await shouldNotifyChannel("local"))) {
              notify(
                t("notify.downloadSent"),
                t("notify.batchSentDetail", {
                  count: String(batchProtoSent),
                }),
              );
            }
            return { success: batchProtoSent > 0, sent: batchProtoSent };
          }
        }

        // 预热 NMH：App 冷启动与下方逐项 cookie 收集（各 500ms 上限）并行
        warmupNativeHost();

        // 为每个 URL 提取 cookies，构建 BatchDownloadItem 列表
        // Bug 9 修复：cookies API 加 500ms 超时，与 sendToFluxDown 保持一致
        // Bug R4-6 修复：并发提取所有 URL 的 cookies，避免串行 N×500ms 超时
        // 需要排除的浏览器内部头（Cookie 已单独处理）
        // 预加载当前 tab 的资源列表，用于 cookies/headers 兜底查找
        const batchTabId =
          sender.tab?.id ??
          (typeof message.tabId === "number" ? message.tabId : undefined);
        const batchTabResources =
          typeof batchTabId === "number" && batchTabId >= 0
            ? getResourcesForTab(batchTabId)
            : [];

        const batchItems: BatchDownloadItem[] = await Promise.all(
          items.map(async (item) => {
            // 策略 1：从 webRequest 缓存获取认证信息
            const itemAuth = extractAuthFromCache(item.url);
            let cookieString = itemAuth.cookies || "";
            let extraHeaders: Record<string, string> = itemAuth.headers || {};
            if (itemAuth.cookies || itemAuth.headers) {
              requestHeaderCache.delete(item.url);
            }
            if (!cookieString) {
              try {
                const cookiesResult = await Promise.race([
                  browser.cookies.getAll({ url: item.url }),
                  new Promise<chrome.cookies.Cookie[]>((_, reject) =>
                    setTimeout(() => reject(new Error("cookies timeout")), 500),
                  ),
                ]);
                cookieString = cookiesResult
                  .map((c) => `${c.name}=${c.value}`)
                  .join("; ");
              } catch {
                /* timeout 或权限不足，跳过 */
              }
            }
            // 策略 3：从资源存储中恢复认证信息（兜底）
            if (!cookieString || Object.keys(extraHeaders).length === 0) {
              const matchedRes = findStoredResource(batchTabResources, item.url);
              const audioMatchedRes = item.audioUrl
                && sameOrigin(item.url, item.audioUrl)
                ? findStoredResource(batchTabResources, item.audioUrl)
                : undefined;
              if (!cookieString) {
                cookieString = matchedRes?.cookies || audioMatchedRes?.cookies || "";
              }
              if (Object.keys(extraHeaders).length === 0) {
                extraHeaders =
                  mergeStoredHeaders(matchedRes?.headers, audioMatchedRes?.headers) ||
                  {};
              }
            }
            return {
              url: item.url,
              audioUrl: item.audioUrl,
              referrer: item.referrer || "",
              filename: item.filename,
              cookies: cookieString,
              headers: withBrowserUserAgent(extraHeaders),
              fileSize: item.fileSize,
              mimeType: item.mimeType,
            };
          }),
        );

        // NMH 的 batch_download 条目与 DownloadRequest 同构并携带逐条
        // audioUrl；hub 侧按 URL 缓存该字段，并在快速下载对话框的单条确认
        // （ConfirmExternalDownload）与多条确认（BatchCreateTask）两条路径下
        // 都按 URL 回填，故本地批量始终保持一次往返、且不丢音轨。只有远程
        // 旧版 /download/batch 不支持该字段，路由层才将带音频条目降级为
        // 远程单条请求（见 remoteSendBatchPreservingAudio）。
        const response = await sendBatchDownloadRequest(batchItems);
        const batchNotifyOk = await shouldNotifyChannel(response.channel);
        if (response.success) {
          await incrementStat("sent");
          maybeArmTaskPollForChannel(response.channel);
          if (batchNotifyOk) {
            notify(
              t("notify.downloadSent"),
              t("notify.batchSentDetail", { count: String(items.length) }),
            );
          }
        } else {
          await incrementStat("failed");
          if (batchNotifyOk) {
            notify(t("notify.sendFailed"), describeSendError(response.message));
          }
        }
        return { success: response.success, sent: items.length };
      }

      // --- Popup: 切换资源面板显示（发消息给当前活跃 tab 的 Content Script） ---
      case "toggleResourcePanel": {
        try {
          const [activeTab] = await browser.tabs.query({
            active: true,
            currentWindow: true,
          });
          if (activeTab?.id) {
            await browser.tabs.sendMessage(activeTab.id, {
              action: "toggleResourcePanel",
            });
          }
        } catch {
          // tab 可能未注入 content script
        }
        return { success: true };
      }

      // --- Content Script: 预抢占一次性 CDN 下载 URL ---
      // 蓝奏云等网站的 CDN URL 实际是 HTML 中转页，需要浏览器加载执行 JS
      // 才能获取真正的下载 URL。因此不再发送给 FluxDown，仅记录 URL 做去重。
      // 当中转页 JS 触发真正的文件下载时，常规下载拦截机制会自动捕获。
      case "preemptDownload": {
        const preemptUrl = message.url as string;
        if (!preemptUrl || typeof preemptUrl !== "string")
          return { error: "invalid url" };

        // 记录预抢占 URL，防止 onDeterminingFilename 重复发送
        preemptedUrls.set(preemptUrl, { expiry: Date.now() + 30_000 });

        console.log(
          "[FluxDown] preemptDownload: recorded URL (not sent to FluxDown, browser will load transit page):",
          preemptUrl,
        );

        // 30 秒后清理预抢占记录
        setTimeout(() => {
          preemptedUrls.delete(preemptUrl);
        }, 30_000);

        return { success: true };
      }

      default:
        return { error: "Unknown action" };
    }
  }

  // ===== 工具函数 =====

  function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  /**
   * 健壮地取消并抹除一条浏览器下载记录（Firefox 关键路径，见 issue #21）。
   *
   * Firefox 的 downloads.cancel() 返回的 Promise 会在状态真正落到 interrupted
   * 之前 resolve，此时立刻 erase 常常删不掉记录，浏览器下载器残留一条"已取消"
   * 记录。正确做法：cancel 后轮询 search 等记录进入终态（interrupted/complete）
   * 再 erase，并在 erase 后确认记录确实消失，未消失则重试。
   */
  async function cancelAndErase(downloadId: number): Promise<void> {
    try {
      await browser.downloads.cancel(downloadId);
    } catch (e) {
      console.warn("[FluxDown] cancelAndErase: cancel failed:", e);
    }

    // 等记录落到终态（interrupted/complete），最多等 ~1s；
    // 记录已消失（length===0）说明已被抹除，直接返回。
    const settleDeadline = Date.now() + 1000;
    while (Date.now() < settleDeadline) {
      try {
        const results = await browser.downloads.search({ id: downloadId });
        if (!results || results.length === 0) return;
        const state = (results[0] as any).state;
        if (state === "interrupted" || state === "complete") break;
      } catch {
        break;
      }
      await sleep(50);
    }

    // erase 并确认删除，未删掉则重试（Firefox 偶发时序松弛）
    for (let attempt = 0; attempt < 4; attempt++) {
      try {
        await browser.downloads.erase({ id: downloadId });
      } catch (e) {
        console.warn("[FluxDown] cancelAndErase: erase failed:", e);
      }
      try {
        const remain = await browser.downloads.search({ id: downloadId });
        if (!remain || remain.length === 0) return;
      } catch {
        return;
      }
      await sleep(100);
    }
    console.warn(
      "[FluxDown] cancelAndErase: record still present after retries:",
      downloadId,
    );
  }

  /**
   * Firefox 会在标签页/iframe 内联展示（pdf.js、原生播放器）的类型。
   * 这类响应只有带 Content-Disposition: attachment 才是真正的下载，
   * 否则取消导航会把「查看」误当成下载。
   */
  function isFirefoxInlineViewableType(contentType: string): boolean {
    const ct = contentType.toLowerCase();
    return (
      ct === "application/pdf" ||
      ["video/mp4", "video/webm", "video/ogg"].includes(ct) ||
      [
        "audio/mpeg",
        "audio/mp3",
        "audio/ogg",
        "audio/wav",
        "audio/x-wav",
        "audio/flac",
        "audio/mp4",
        "audio/webm",
        "audio/aac",
        "audio/opus",
      ].includes(ct)
    );
  }

  /**
   * 判断 Content-Type 是否为"下载类型"（即浏览器会将导航转为下载的类型）
   */
  function isDownloadContentType(contentType: string): boolean {
    const ct = contentType.toLowerCase();
    const downloadTypes = [
      "application/octet-stream",
      "application/x-download",
      "application/force-download",
      "application/zip",
      "application/x-rar-compressed",
      "application/x-7z-compressed",
      "application/gzip",
      "application/x-tar",
      "application/x-bzip2",
      "application/x-xz",
      "application/x-msdownload",
      "application/x-msi",
      "application/x-apple-diskimage",
      "application/vnd.debian.binary-package",
      "application/x-iso9660-image",
      "application/x-raw-disk-image",
      "application/pdf",
      "application/vnd.android.package-archive",
      "application/x-bittorrent",
    ];
    // 精确匹配 + 前缀匹配
    if (downloadTypes.includes(ct)) return true;
    if (ct.startsWith("video/") || ct.startsWith("audio/")) return true;
    if (ct.startsWith("application/vnd.openxmlformats-officedocument"))
      return true;
    if (ct.startsWith("application/vnd.ms-")) return true;
    return false;
  }

  // 通知去重表：title+message → 上次展示时间戳。
  // 安全网：即便存在残余重入，也能防止同一条通知在短时间内反复弹出（#308 #346）。
  const _notifyDedup = new Map<string, number>();
  const NOTIFY_DEDUP_MS = 5000;

  function notify(title: string, message: string) {
    // R5-7 修复：Firefox 下 notifications 可能不存在或受权限限制，
    // fire-and-forget 的未捕获 rejection 会产生控制台错误噪音。
    if (!browser.notifications?.create) return;

    // 去重/限流：相同内容的通知在 NOTIFY_DEDUP_MS 内只展示一次。
    const now = Date.now();
    const key = `${title} ${message}`;
    const last = _notifyDedup.get(key);
    if (last !== undefined && now - last < NOTIFY_DEDUP_MS) return;
    _notifyDedup.set(key, now);
    // 机会式清理过期条目，避免长期累积。
    if (_notifyDedup.size > 50) {
      for (const [k, ts] of _notifyDedup) {
        if (now - ts >= NOTIFY_DEDUP_MS) _notifyDedup.delete(k);
      }
    }

    try {
      browser.notifications.create({
        type: "basic",
        iconUrl: "/icon/128.png",
        title: `FluxDown - ${title}`,
        message,
      });
    } catch (e) {
      console.warn("[FluxDown] notify: failed to create notification:", e);
    }
  }

  function updateIcon(enabled: boolean) {
    const suffix = enabled ? "" : "-disabled";
    const iconPath = {
      16: `/icon/16${suffix}.png`,
      32: `/icon/32${suffix}.png`,
      48: `/icon/48${suffix}.png`,
      128: `/icon/128${suffix}.png`,
    };
    getActionApi()
      ?.setIcon({ path: iconPath })
      ?.catch(() => {
        /* 权限不足时静默忽略 */
      });
  }

  // 启动时更新图标（settings 已在上方 getCachedSettings 预热，此处复用缓存）
  // 注意：updateIcon 已在 getCachedSettings 预热回调中调用，此行保留为显式确保
});

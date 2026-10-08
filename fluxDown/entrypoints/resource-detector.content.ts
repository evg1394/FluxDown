/**
 * 资源检测 Content Script
 *
 * 运行在 Isolated World（与页面 JS 隔离，但共享 DOM）。
 *
 * 职责：
 * 1. 扫描页面 DOM 中的 video/audio/source/a[href] 等媒体元素
 * 2. 通过 MutationObserver 持续监听动态添加的元素
 * 3. 注入 Main World 脚本拦截 fetch/XHR（检测 HLS/DASH 流媒体）
 * 4. 将检测到的资源通过 runtime.sendMessage 转发给 Background
 */

import { browser } from 'wxt/browser';
import { injectScript } from 'wxt/utils/inject-script';
import { defineContentScript } from 'wxt/utils/define-content-script';
import { loadSettings } from "@/utils/settings";
import type {
  ResourceMessagePayload,
  FetchInterceptDetail,
  ResourceType,
} from "@/utils/resource-types";
import { normalizeDashManifest, parseDashManifestText } from "@/utils/dash-manifest";
import type { DashManifest } from "@/utils/dash-manifest";
import { classifyByExtension, classifyByMime } from "@/utils/resource-types";

export default defineContentScript({
  matches: ["<all_urls>"],
  runAt: "document_idle",

  async main(ctx) {
    // ===== 页面字符集应答（文件名解码先验） =====
    // 本脚本只注入顶层框架（allFrames 缺省为 false）。background 在无法确定
    // Content-Disposition 原始字节编码时发 `getPageCharset` 询问发起页的
    // document.characterSet。不受嗅探/拦截开关门控，且先于任何 await 注册。
    const handlePageCharsetRequest = (
      msg: { action?: string } | undefined,
      _sender: unknown,
      sendResponse: (response: { charset: string }) => void,
    ): true | undefined => {
      if (msg?.action !== "getPageCharset") return undefined;
      sendResponse({ charset: document.characterSet });
      return true;
    };
    browser.runtime.onMessage.addListener(handlePageCharsetRequest);
    ctx.onInvalidated(() =>
      browser.runtime.onMessage.removeListener(handlePageCharsetRequest),
    );

    // ===== 0. 资源嗅探 / 磁力接管开关 =====
    // 嗅探关闭后跳过 DOM 扫描 / MutationObserver / Main World fetch 拦截注入，
    // 消除重资源页面（如 B 站首页）的嗅探性能开销；更改后新加载的页面生效。
    // 磁力链接点击接管不属于嗅探，由独立开关 interceptMagnet 控制，
    // 且监听设置变更即时生效（用户关掉后点磁力链接应立即交还系统处理程序）。
    let sniffingEnabled = true;
    let magnetEnabled = true;
    let interceptEnabled = true;
    try {
      const settings = await loadSettings();
      sniffingEnabled = settings.resourceSniffing !== false;
      magnetEnabled = settings.interceptMagnet !== false;
      interceptEnabled = settings.enabled !== false;
    } catch {
      // 设置读取失败时按默认开启处理
    }
    const handleSettingsChanged = (
      changes: Record<string, { newValue?: unknown }>,
      area: string,
    ) => {
      if (area !== "sync" || !changes.settings) return;
      const next = changes.settings.newValue as
        | { interceptMagnet?: boolean; enabled?: boolean }
        | undefined;
      if (!next) return;
      magnetEnabled = next.interceptMagnet !== false;
      interceptEnabled = next.enabled !== false;
    };
    browser.storage.onChanged.addListener(handleSettingsChanged);
    ctx.onInvalidated(() =>
      browser.storage.onChanged.removeListener(handleSettingsChanged),
    );

    /** 已报告的 URL 集合（防止重复上报） */
    const reportedUrls = new Set<string>();
    let inlineDashSignature = "";
    let inlineDashScanTimer: number | undefined;
    const scannedInlineScripts = new WeakSet<HTMLScriptElement>();

    // #288：单页面已上报资源数上限——B 站首页等长列表页可产生数百个可下载
    // 资源，无节制上报/扫描会导致页面卡顿。达到上限后停止统计新资源、断开
    // MutationObserver；SPA 路由切换（fluxdown-page-url-changed）时重置并重新
    // 连接观察。
    const MAX_RESOURCES_PER_PAGE = 200;
    let reportedResourceCount = 0;
    let resourceCapReached = false;

    function reportPageUrlChange(): void {
      browser.runtime.sendMessage({
        action: "pageUrlChanged",
        pageUrl: location.href,
      }).catch(() => {
        // 扩展可能已失效
      });
    }

    const handlePageUrlChange = (): void => reportPageUrlChange();
    document.addEventListener("fluxdown-page-url-changed", handlePageUrlChange);
    ctx.onInvalidated(() => {
      document.removeEventListener("fluxdown-page-url-changed", handlePageUrlChange);
    });

    /**
     * 补扫页面已经存在的内嵌 JSON 状态。
     * Main World 注入发生在 document_idle，播放器可能已经完成首个请求，
     * 这一步让「页面有清单但扩展没赶上请求」仍能得到可下载候选。
     */
    function scanInlineDashManifest(): void {
      if (!sniffingEnabled) return;

      for (const script of Array.from(document.scripts)) {
        if (scannedInlineScripts.has(script)) continue;
        scannedInlineScripts.add(script);
        const text = script.textContent || "";
        if (!text) continue;
        const manifest = parseDashManifestText(text, location.href);
        if (!manifest || manifest.video.length === 0) continue;

        const signature = [
          ...manifest.video.map((track) => `${track.url}|${track.bandwidth ?? 0}|${track.codecs ?? ""}`),
          ...manifest.audio.map((track) => `${track.url}|${track.bandwidth ?? 0}|${track.codecs ?? ""}`),
        ].join("\n");
        if (signature === inlineDashSignature) continue;
        inlineDashSignature = signature;

        browser.runtime
          .sendMessage({
            action: "dashManifestDetected",
            manifest,
            manifestUrl: `${location.href}#fluxdown-inline-dash`,
            pageUrl: location.href,
          })
          .catch(() => {
            // 扩展可能已失效
          });
        return;
      }
    }

    function scheduleInlineDashScan(): void {
      if (!sniffingEnabled || inlineDashScanTimer !== undefined) return;
      inlineDashScanTimer = window.setTimeout(() => {
        inlineDashScanTimer = undefined;
        scanInlineDashManifest();
      }, 0);
    }

    // ===== 1. 初始 DOM 扫描 =====
    if (sniffingEnabled) {
      const initialResources = scanPageResources();
      if (initialResources.length > 0) {
        reportResources(initialResources);
      }
    }

    // ===== 2. MutationObserver 持续监听（合批 + 空闲期处理，#288） =====
    // B 站首页等长列表页一次性/持续插入大量节点时，若每条 mutation record 都
    // 同步扫描，会阻塞主线程导致页面卡顿。这里把新增节点 / 属性变化目标先缓冲，
    // 用 requestIdleCallback（不支持时退化为 200ms 防抖 setTimeout）合并成一批
    // 再统一扫描上报。
    const MUTATION_FLUSH_DELAY_MS = 200;
    const MUTATION_OBSERVER_OPTIONS: MutationObserverInit = {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ["src", "href", "data"],
    };
    const pendingAddedNodes = new Set<HTMLElement>();
    const pendingAttributeTargets = new Set<HTMLElement>();
    let mutationFlushHandle: number | undefined;
    let mutationFlushIsIdleCallback = false;

    function flushPendingMutations(): void {
      mutationFlushHandle = undefined;
      mutationFlushIsIdleCallback = false;

      if (resourceCapReached) {
        pendingAddedNodes.clear();
        pendingAttributeTargets.clear();
        return;
      }

      const found: ResourceMessagePayload[] = [];
      let scriptAdded = false;

      for (const node of pendingAddedNodes) {
        if (node instanceof HTMLScriptElement) scriptAdded = true;
        found.push(...checkElement(node));
        const children = node.querySelectorAll(
          "video, audio, source, track[src], a[href], embed, object",
        );
        for (const child of children) {
          found.push(...checkElement(child as HTMLElement));
        }
      }
      pendingAddedNodes.clear();

      for (const target of pendingAttributeTargets) {
        found.push(...checkElement(target));
      }
      pendingAttributeTargets.clear();

      if (scriptAdded) scheduleInlineDashScan();

      if (found.length > 0) {
        reportResources(found);
      }

      if (resourceCapReached) {
        observer.disconnect();
      }
    }

    function scheduleMutationFlush(): void {
      if (mutationFlushHandle !== undefined) return;
      if (typeof requestIdleCallback === "function") {
        mutationFlushIsIdleCallback = true;
        mutationFlushHandle = requestIdleCallback(flushPendingMutations, {
          timeout: MUTATION_FLUSH_DELAY_MS,
        });
      } else {
        mutationFlushIsIdleCallback = false;
        mutationFlushHandle = window.setTimeout(
          flushPendingMutations,
          MUTATION_FLUSH_DELAY_MS,
        );
      }
    }

    function cancelScheduledMutationFlush(): void {
      if (mutationFlushHandle === undefined) return;
      if (mutationFlushIsIdleCallback && typeof cancelIdleCallback === "function") {
        cancelIdleCallback(mutationFlushHandle);
      } else if (!mutationFlushIsIdleCallback) {
        window.clearTimeout(mutationFlushHandle);
      }
      mutationFlushHandle = undefined;
      mutationFlushIsIdleCallback = false;
    }

    // 属性变化仅在目标是媒体类/可下载类元素时才有意义（其余元素的 src/href/data
    // 变化与资源探测无关），缩小 MutationObserver 属性分支的处理面。
    function isMediaishElement(el: HTMLElement): boolean {
      const tag = el.tagName.toLowerCase();
      if (
        tag === "video" ||
        tag === "audio" ||
        tag === "source" ||
        tag === "track" ||
        tag === "embed" ||
        tag === "object"
      ) {
        return true;
      }
      return tag === "a" && el.hasAttribute("href");
    }

    const observer = new MutationObserver((mutations) => {
      if (resourceCapReached) return;

      for (const mutation of mutations) {
        for (const node of mutation.addedNodes) {
          if (node instanceof HTMLElement) pendingAddedNodes.add(node);
        }

        // 属性变化（如 video.src 被 JS 修改）
        if (
          mutation.type === "attributes" &&
          mutation.target instanceof HTMLElement &&
          isMediaishElement(mutation.target)
        ) {
          pendingAttributeTargets.add(mutation.target);
        }
      }

      if (pendingAddedNodes.size > 0 || pendingAttributeTargets.size > 0) {
        scheduleMutationFlush();
      }
    });

    if (sniffingEnabled && !resourceCapReached) {
      observer.observe(document.documentElement, MUTATION_OBSERVER_OPTIONS);

      // 扩展失效时断开观察
      ctx.onInvalidated(() => {
        observer.disconnect();
        cancelScheduledMutationFlush();
      });
      ctx.onInvalidated(() => {
        if (inlineDashScanTimer !== undefined) {
          window.clearTimeout(inlineDashScanTimer);
          inlineDashScanTimer = undefined;
        }
      });
    }

    // SPA 路由切换时重置单页资源上限计数器；若此前因触顶而断开了观察，
    // 重新连接 MutationObserver（新页面重新计数）。
    const handleResourceCapReset = (): void => {
      reportedResourceCount = 0;
      if (resourceCapReached) {
        resourceCapReached = false;
        if (sniffingEnabled) {
          observer.observe(document.documentElement, MUTATION_OBSERVER_OPTIONS);
        }
      }
    };
    document.addEventListener(
      "fluxdown-page-url-changed",
      handleResourceCapReset,
    );
    ctx.onInvalidated(() => {
      document.removeEventListener(
        "fluxdown-page-url-changed",
        handleResourceCapReset,
      );
    });

    // ===== 3. 注入 Main World 拦截脚本 =====
    if (sniffingEnabled) {
      try {
        await injectScript("/fetch-interceptor.js", { keepInDom: true });
      } catch (e) {
        console.warn("[FluxDown] Failed to inject fetch interceptor:", e);
      }
    }

    // ===== 4. 监听 Main World 的 CustomEvent =====
    const handleFetchEvent = (event: Event) => {
      const detail = (event as CustomEvent).detail as
        | FetchInterceptDetail
        | undefined;
      if (!detail || !detail.url) return;

      const mappedType = mapFetchEventType(
        detail.type,
        detail.contentType,
        detail.url,
      );

      // MSE is a page-level capability signal (URL = page URL), not a downloadable resource
      if (detail.type === "mse-detected") return;

      const payload: ResourceMessagePayload = {
        url: detail.url,
        type: mappedType,
        mimeType: detail.contentType,
        size: detail.size,
        detectedBy: detail.type.startsWith("xhr")
          ? "xhr-intercept"
          : detail.type.startsWith("blob")
            ? "blob-intercept"
            : "fetch-intercept",
      };

      reportResources([payload]);
    };

    document.addEventListener("fluxdown-resource-detected", handleFetchEvent);
    ctx.onInvalidated(() => {
      document.removeEventListener(
        "fluxdown-resource-detected",
        handleFetchEvent,
      );
    });

    // ===== 4b. 监听 Main World 拦到的标准 DASH manifest（权威清晰度 + 轨道 URL）=====
    const handleDashManifestEvent = (event: Event) => {
      const detail = (event as CustomEvent).detail as
        | { manifest: DashManifest; manifestUrl?: string; pageUrl: string }
        | undefined;
      const manifest = normalizeDashManifest(detail?.manifest);
      if (!manifest) return;
      browser.runtime
        .sendMessage({
          action: "dashManifestDetected",
          manifest,
          manifestUrl: detail.manifestUrl || "",
          pageUrl: detail.pageUrl || location.href,
        })
        .catch(() => {
          // 扩展可能已失效
        });
    };

    document.addEventListener("fluxdown-dash-manifest", handleDashManifestEvent);
    ctx.onInvalidated(() => {
      document.removeEventListener(
        "fluxdown-dash-manifest",
        handleDashManifestEvent,
      );
    });

    if (sniffingEnabled) scanInlineDashManifest();

    // ===== 5. 一次性 CDN 下载 URL 预识别 =====
    // Main World 脚本将 AJAX 生成的一次性 URL 转发给 background，用于标记
    // 需要页面继续执行的中转链接；实际下载仍由通用下载事件链路决定。
    const handlePreemptEvent = (event: Event) => {
      const detail = (event as CustomEvent).detail as
        | { url: string; filename: string; referrer: string }
        | undefined;
      if (!detail?.url) return;
      browser.runtime
        .sendMessage({
          action: "preemptDownload",
          url: detail.url,
          filename: detail.filename || "",
          referrer: detail.referrer || "",
        })
        .catch(() => {});
    };
    document.addEventListener("fluxdown-preempt-download", handlePreemptEvent);
    ctx.onInvalidated(() =>
      document.removeEventListener(
        "fluxdown-preempt-download",
        handlePreemptEvent,
      ),
    );

    // ===== 6. 磁力链接点击拦截 =====
    // 用户直接点击 <a href="magnet:..."> 时，阻止浏览器弹出 OS 应用选择框，
    // 改由 FluxDown 接管。使用捕获阶段，早于页面自身的 click 处理器执行。
    // interceptMagnet 关闭时放行，交还系统默认磁力处理程序（如 qBittorrent）。
    const handleMagnetClick = (e: MouseEvent) => {
      if (!magnetEnabled || !interceptEnabled) return;
      const target = e.target;
      if (!(target instanceof Element)) return;
      const link = target.closest("a[href]") as HTMLAnchorElement | null;
      if (!link) return;
      const href = link.href;
      if (!href || !href.toLowerCase().startsWith("magnet:")) return;
      e.preventDefault();
      e.stopPropagation();
      browser.runtime
        .sendMessage({
          action: "downloadResource",
          url: href,
          filename: parseMagnetDisplayName(href),
        })
        .then((res: { success?: boolean; error?: string } | undefined) => {
          // 未被 FluxDown 接管（被拒绝/后台无响应）时交还系统处理程序，避免点击变死链。
          if (!res || res.success === false || res.error) location.href = href;
        })
        .catch(() => {
          location.href = href;
        });
    };
    document.addEventListener("click", handleMagnetClick, true);
    ctx.onInvalidated(() => {
      document.removeEventListener("click", handleMagnetClick, true);
    });

    // ===== 扫描函数 =====

    function scanPageResources(): ResourceMessagePayload[] {
      const resources: ResourceMessagePayload[] = [];

      // <video> 元素
      for (const video of document.querySelectorAll("video")) {
        if (
          video.src &&
          !video.src.startsWith("blob:") &&
          !video.src.startsWith("data:")
        ) {
          resources.push({
            url: video.src,
            type: "video",
            quality: detectQuality(video),
            detectedBy: "dom-scan",
          });
        }
        if (
          video.currentSrc &&
          video.currentSrc !== video.src &&
          !video.currentSrc.startsWith("blob:") &&
          !video.currentSrc.startsWith("data:")
        ) {
          resources.push({
            url: video.currentSrc,
            type: "video",
            quality: detectQuality(video),
            detectedBy: "dom-scan",
          });
        }
      }

      // <audio> 元素
      for (const audio of document.querySelectorAll("audio")) {
        if (
          audio.src &&
          !audio.src.startsWith("blob:") &&
          !audio.src.startsWith("data:")
        ) {
          resources.push({
            url: audio.src,
            type: "audio",
            detectedBy: "dom-scan",
          });
        }
      }

      // <source> 元素
      for (const source of document.querySelectorAll("source")) {
        if (
          source.src &&
          !source.src.startsWith("blob:") &&
          !source.src.startsWith("data:")
        ) {
          const type: ResourceType = source.type?.startsWith("video/")
            ? "video"
            : source.type?.startsWith("audio/")
              ? "audio"
              : "other";
          resources.push({
            url: source.src,
            type,
            mimeType: source.type || undefined,
            detectedBy: "dom-scan",
          });
        }
      }

      // <track> 字幕元素（视频播放器的字幕轨道）
      for (const track of document.querySelectorAll<HTMLTrackElement>(
        "track[src]",
      )) {
        if (track.src && track.src.startsWith("http")) {
          resources.push({
            url: track.src,
            type: "subtitle",
            filename: track.label
              ? `${track.label}${track.srclang ? `.${track.srclang}` : ""}.vtt`
              : undefined,
            detectedBy: "dom-scan",
          });
        }
      }

      // <a> 标签中的下载链接 + 磁力链接
      for (const a of document.querySelectorAll<HTMLAnchorElement>("a[href]")) {
        const href = a.href;
        if (
          !href ||
          href.startsWith("blob:") ||
          href.startsWith("data:") ||
          href.startsWith("javascript:") ||
          href.startsWith("#")
        )
          continue;

        // 磁力链接
        if (href.toLowerCase().startsWith("magnet:")) {
          resources.push({
            url: href,
            type: "magnet",
            filename: parseMagnetDisplayName(href),
            detectedBy: "dom-scan",
          });
          continue;
        }

        if (
          !href.startsWith("http://") &&
          !href.startsWith("https://") &&
          !href.startsWith("ftp://")
        )
          continue;

        if (a.download || isDownloadableUrl(href)) {
          const filename = a.download || undefined;
          const type = classifyByUrlExtension(href);
          resources.push({
            url: href,
            type,
            filename,
            detectedBy: "dom-scan",
          });
        }
      }

      // <embed> / <object>
      for (const el of document.querySelectorAll<
        HTMLEmbedElement | HTMLObjectElement
      >("embed[src], object[data]")) {
        const url =
          (el as HTMLEmbedElement).src || (el as HTMLObjectElement).data;
        if (url && url.startsWith("http")) {
          resources.push({
            url,
            type: "other",
            detectedBy: "dom-scan",
          });
        }
      }

      return resources;
    }

    function checkElement(el: HTMLElement): ResourceMessagePayload[] {
      const results: ResourceMessagePayload[] = [];
      const tag = el.tagName.toLowerCase();

      if (tag === "video" || tag === "audio") {
        const media = el as HTMLMediaElement;
        if (
          media.src &&
          !media.src.startsWith("blob:") &&
          !media.src.startsWith("data:")
        ) {
          results.push({
            url: media.src,
            type: tag === "video" ? "video" : "audio",
            quality:
              tag === "video"
                ? detectQuality(media as HTMLVideoElement)
                : undefined,
            detectedBy: "mutation-observer",
          });
        }
      } else if (tag === "source") {
        const source = el as HTMLSourceElement;
        if (
          source.src &&
          !source.src.startsWith("blob:") &&
          !source.src.startsWith("data:")
        ) {
          results.push({
            url: source.src,
            type: source.type?.startsWith("video/")
              ? "video"
              : source.type?.startsWith("audio/")
                ? "audio"
                : "other",
            mimeType: source.type || undefined,
            detectedBy: "mutation-observer",
          });
        }
      } else if (tag === "track") {
        const track = el as HTMLTrackElement;
        if (track.src && track.src.startsWith("http")) {
          results.push({
            url: track.src,
            type: "subtitle",
            filename: track.label
              ? `${track.label}${track.srclang ? `.${track.srclang}` : ""}.vtt`
              : undefined,
            detectedBy: "mutation-observer",
          });
        }
      } else if (tag === "a") {
        const a = el as HTMLAnchorElement;
        if (a.href?.toLowerCase().startsWith("magnet:")) {
          results.push({
            url: a.href,
            type: "magnet",
            filename: parseMagnetDisplayName(a.href),
            detectedBy: "mutation-observer",
          });
        } else if (
          a.href &&
          (a.href.startsWith("http://") ||
            a.href.startsWith("https://") ||
            a.href.startsWith("ftp://")) &&
          (a.download || isDownloadableUrl(a.href))
        ) {
          results.push({
            url: a.href,
            type: classifyByUrlExtension(a.href),
            filename: a.download || undefined,
            detectedBy: "mutation-observer",
          });
        }
      }

      return results;
    }

    /**
     * 上报资源给 Background Service Worker
     */
    function reportResources(resources: ResourceMessagePayload[]): void {
      if (resourceCapReached) return;

      // 去重
      const fresh = resources.filter((r) => {
        if (reportedUrls.has(r.url)) return false;
        reportedUrls.add(r.url);
        return true;
      });

      // R7-1 修复：SPA 长时间运行时 reportedUrls 可能无限增长，整体清空防内存泄漏。
      // 下游 resource-store 仍有基于归一化 URL 的去重兜底，清空不会导致功能问题。
      if (reportedUrls.size > 500) {
        reportedUrls.clear();
      }

      if (fresh.length === 0) return;

      // #288：单页面上报资源数达到上限后停止统计，MutationObserver 由调用方
      // （flushPendingMutations）在检测到 resourceCapReached 后断开。
      reportedResourceCount += fresh.length;
      if (reportedResourceCount >= MAX_RESOURCES_PER_PAGE) {
        resourceCapReached = true;
      }

      // 补充 pageUrl
      for (const r of fresh) {
        if (!r.pageUrl) {
          r.pageUrl = location.href;
        }
      }

      browser.runtime
        .sendMessage({
          action: "resourceDetected",
          resources: fresh,
        })
        .catch(() => {
          // 扩展可能已失效
        });
    }

    // ===== 辅助函数 =====

    function detectQuality(video: HTMLVideoElement): string | undefined {
      const h = video.videoHeight;
      if (h > 0) return `${h}p`;
      return undefined;
    }

    const DOWNLOADABLE_EXTS = new Set([
      "zip",
      "rar",
      "7z",
      "tar",
      "gz",
      "bz2",
      "xz",
      "zst",
      "exe",
      "msi",
      "dmg",
      "deb",
      "rpm",
      "appimage",
      "apk",
      "ipa",
      "iso",
      "img",
      "mp4",
      "mkv",
      "avi",
      "mov",
      "wmv",
      "flv",
      "webm",
      "ts",
      "m4v",
      "mp3",
      "flac",
      "wav",
      "aac",
      "ogg",
      "wma",
      "m4a",
      "opus",
      "pdf",
      "doc",
      "docx",
      "xls",
      "xlsx",
      "ppt",
      "pptx",
      "bin",
      "torrent",
      "m3u8",
      "mpd",
      "vtt",
      "srt",
      "ass",
      "ssa",
      "sub",
      "idx",
      "sup",
      "lrc",
    ]);

    /** 常见下载路径关键词（无扩展名的下载链接识别） */
    const DOWNLOAD_PATH_KEYWORDS = [
      "/download",
      "/get/",
      "/fetch/",
      "/file/",
      "/files/",
      "/attachment",
      "/export",
      "/dl/",
      "/release/",
    ];

    /**
     * 从 URL 中提取文件扩展名（多策略）
     *
     * 策略顺序：
     * 1. 从 pathname 末尾提取 — 覆盖 /path/file.pdf 场景
     * 2. 从查询参数值中提取 — 覆盖 /download?file=report.pdf 场景
     * 3. 从 pathname 任意段提取 — 覆盖 /file.pdf/download 场景
     */
    function extractExtFromUrl(url: string): string {
      try {
        const u = new URL(url);
        const pathname = u.pathname.toLowerCase();

        // 策略 1: pathname 末尾的扩展名
        const lastSegment = pathname.split("/").pop() || "";
        const dotIndex = lastSegment.lastIndexOf(".");
        if (dotIndex > 0 && dotIndex < lastSegment.length - 1) {
          const ext = lastSegment.substring(dotIndex + 1);
          if (DOWNLOADABLE_EXTS.has(ext)) return ext;
        }

        // 策略 2: 查询参数值中的扩展名（如 ?file=report.pdf&type=doc）
        for (const val of u.searchParams.values()) {
          const valLower = val.toLowerCase();
          const valDot = valLower.lastIndexOf(".");
          if (valDot > 0 && valDot < valLower.length - 1) {
            const ext = valLower.substring(valDot + 1);
            if (DOWNLOADABLE_EXTS.has(ext)) return ext;
          }
        }

        // 策略 3: pathname 任意段含已知扩展名（如 /file.pdf/download）
        const segments = pathname.split("/");
        for (const seg of segments) {
          const segDot = seg.lastIndexOf(".");
          if (segDot > 0 && segDot < seg.length - 1) {
            const ext = seg.substring(segDot + 1);
            if (DOWNLOADABLE_EXTS.has(ext)) return ext;
          }
        }
      } catch {
        // ignore
      }
      return "";
    }

    function isDownloadableUrl(url: string): boolean {
      // 1. 扩展名匹配（多策略）
      if (extractExtFromUrl(url)) return true;

      // 2. 路径关键词匹配（无扩展名的下载链接）
      try {
        const pathname = new URL(url).pathname.toLowerCase();
        for (const keyword of DOWNLOAD_PATH_KEYWORDS) {
          if (pathname.includes(keyword)) return true;
        }
      } catch {
        // ignore
      }

      return false;
    }

    /**
     * 分类 URL 所指资源的类型。
     * 复用 resource-types.ts 中统一的 EXTENSION_CATEGORIES 映射，
     * 通过增强的 extractExtFromUrl 先提取扩展名再查表。
     */
    function classifyByUrlExtension(url: string): ResourceType {
      const ext = extractExtFromUrl(url);
      if (!ext) return "other";
      // classifyByExtension 内部使用 extractExtension(url) 只看 pathname 末尾，
      // 这里我们已经确认 ext 存在，构造一个简单的 fake URL 让它查表
      return classifyByExtension(`https://x/f.${ext}`);
    }

    function mapFetchEventType(
      type: string,
      contentType?: string,
      url?: string,
    ): ResourceType {
      if (type === "hls-manifest" || type === "dash-manifest") return "stream";
      // Also classify by contentType for fetch-detected events that carry stream info
      if (contentType === "hls-manifest" || contentType === "dash-manifest")
        return "stream";
      // MSE detection is a page-level signal, not a downloadable resource — skip it
      if (type === "mse-detected") return "other";
      // 利用 MIME 类型分类 fetch/XHR 检测到的非流媒体资源（如 PDF、压缩包等）
      if (contentType) {
        const byMime = classifyByMime(contentType);
        if (byMime !== "other") return byMime;
      }
      // 深扫命中的裸媒体 URL（无 contentType）按 URL 扩展名回退分类（mp4/mp3/m3u8/zip 等），
      // 避免落入 other → computeConfidence 给 low。见集成计划组 3.2。
      if (url) {
        const byUrlExt = classifyByUrlExtension(url);
        if (byUrlExt !== "other") return byUrlExt;
      }
      return "other";
    }

    function parseMagnetDisplayName(magnetUri: string): string | undefined {
      try {
        const params = new URLSearchParams(magnetUri.split("?")[1] || "");
        const dn = params.get("dn");
        // URLSearchParams.get() already percent-decodes; no double decode needed
        return dn || undefined;
      } catch {
        return undefined;
      }
    }
  },
});

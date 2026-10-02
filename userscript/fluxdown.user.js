// ==UserScript==
// @name              FluxDown 下载接管 / FluxDown Download Capture
// @name:zh-CN        FluxDown 下载接管
// @namespace         https://github.com/zerx-lab/FluxDown
// @version           1.0.0
// @description       拦截浏览器下载与流媒体资源，一键发送到 FluxDown 桌面下载器（通过本地 RPC）。Intercept downloads & media, send them to the FluxDown desktop app via local RPC.
// @description:zh-CN 拦截浏览器下载与流媒体资源（HLS/DASH/视频/音频/压缩包/安装包等），一键发送到 FluxDown 桌面下载器。
// @author            zerx-lab
// @license           MIT
// @match             *://*/*
// @grant             GM_xmlhttpRequest
// @grant             GM_setValue
// @grant             GM_getValue
// @grant             GM_registerMenuCommand
// @grant             GM_unregisterMenuCommand
// @grant             GM_notification
// @grant             GM_addStyle
// @grant             unsafeWindow
// @connect           127.0.0.1
// @connect           localhost
// @run-at            document-start
// @noframes
// @homepageURL       https://github.com/zerx-lab/FluxDown
// @supportURL        https://github.com/zerx-lab/FluxDown/issues
// ==/UserScript==

/*
 * ============================================================================
 * FluxDown 下载接管用户脚本
 * ============================================================================
 *
 * 工作原理
 * --------
 * 油猴脚本运行在页面上下文，无法使用浏览器扩展专属的 chrome.downloads /
 * webRequest / cookies API，只能用 GM_xmlhttpRequest 与本机程序通信。本脚本：
 *
 *   1. 在 DOM 层拦截下载（点击下载链接、a[download]、程序化 .click()、window.open）；
 *   2. 在页面 JS 层 hook fetch / XMLHttpRequest / MediaSource 嗅探流媒体清单
 *      （HLS .m3u8 / DASH .mpd）与 AJAX 加载的可下载资源；
 *   3. 通过 GM_xmlhttpRequest POST 到 FluxDown 的本地 HTTP 接管服务
 *      （默认 http://127.0.0.1:17800/download），进入桌面端的外部下载流程。
 *
 * 与浏览器扩展的能力差异（务必知悉）
 * ----------------------------------
 *   - 无法拦截「浏览器内核直接发起」的下载（非页面 JS 触发的、点击后直接由
 *     Content-Disposition 触发的下载）——这类请在 FluxDown 浏览器扩展中完成；
 *     本脚本覆盖「页面内可见的下载链接 / 媒体资源」，对大多数站点已足够。
 *   - 只能读取非 httpOnly 的 Cookie（document.cookie）。需要 httpOnly 鉴权的
 *     下载（如部分网盘）建议使用浏览器扩展。
 *
 * 安全
 * ----
 *   - 仅连接 127.0.0.1 / localhost；
 *   - 每个请求携带 X-FluxDown-Client 头（FluxDown 据此拦截恶意网页的跨域伪造请求）；
 *   - 设置页复制的脚本自带当前端口和 Token；也可在油猴菜单里修改；
 *   - 凭据仅保存在脚本沙箱，请勿分享含 Token 的脚本；无需打开 CORS；
 *   - 下载遵从桌面端的确认 / 免打扰设置，不绕过现有接管策略。
 *
 * 配置（点击油猴菜单 → FluxDown ...）
 * -----------------------------------
 *   - 接管开关 / 端口 / Token / 媒体嗅探面板 / 测试连接 / 下载本页全部链接
 * ============================================================================
 */

(function () {
  'use strict';

  // 避免在同一页重复注入（某些管理器可能重复执行）。
  const W = typeof unsafeWindow !== 'undefined' ? unsafeWindow : window;
  if (W.__fluxdown_userscript__) return;
  W.__fluxdown_userscript__ = true;

  // ==========================================================================
  // 配置
  // ==========================================================================

  // 设置页复制时只替换此安装配置；凭据留在脚本沙箱，不挂到 unsafeWindow / DOM。
  const INSTALL_CONFIG = { port: 17800, token: '' };
  const installation = JSON.stringify(INSTALL_CONFIG);
  if (GM_getValue('installationConfig', null) !== installation) {
    GM_setValue('port', INSTALL_CONFIG.port);
    GM_setValue('token', INSTALL_CONFIG.token);
    GM_setValue('installationConfig', installation);
  }

  const CFG = {
    get port() { return GM_getValue('port', INSTALL_CONFIG.port); },
    set port(v) { GM_setValue('port', v); },
    get token() { return GM_getValue('token', INSTALL_CONFIG.token); },
    set token(v) { GM_setValue('token', v); },
    get enabled() { return GM_getValue('enabled', true); },
    set enabled(v) { GM_setValue('enabled', v); },
    get sniffer() { return GM_getValue('sniffer', true); },
    set sniffer(v) { GM_setValue('sniffer', v); },
    // 点击拦截：按住该修饰键点击则放行给浏览器（默认 Alt）。
    get bypassKey() { return GM_getValue('bypassKey', 'alt'); },
  };

  function base() { return `http://127.0.0.1:${CFG.port}`; }

  // ==========================================================================
  // 多语言（按浏览器语言在中英间切换）
  // ==========================================================================

  const LANG = /^zh/i.test((typeof navigator !== 'undefined' && navigator.language) || '') ? 'zh' : 'en';
  const STR = {
    zh: {
      sent: '已发送到 FluxDown：{0}',
      errToken: 'FluxDown 拒绝请求：Token 缺失或错误',
      errTakeoverOff: 'FluxDown 未开启浏览器脚本接管',
      errQueueFull: 'FluxDown 待确认队列已满',
      errHttp: 'FluxDown 返回异常状态 {0}',
      errNoResponse: 'FluxDown 响应超时，请查看桌面端确认是否已收到',
      errOffline: 'FluxDown 未运行',
      sfxFallback: '，已交回浏览器下载',
      sfxNoFallback: '，下载未发送',
      fabTitle: 'FluxDown 资源面板',
      panelTitle: 'FluxDown 嗅探资源',
      refresh: '刷新',
      clear: '清空',
      sendAll: '全部发送',
      pageLinks: '本页链接',
      emptyList: '暂无嗅探到的资源<br>播放视频或刷新页面以重新嗅探',
      download: '下载',
      noSniffed: '没有可发送的资源',
      sentN: '已发送 {0} 个资源到 FluxDown',
      noLinks: '本页未发现可下载链接',
      sentLinks: '已发送本页 {0} 个链接到 FluxDown',
      sendFail: '发送失败，请确认 FluxDown 在运行',
      batchFull: 'FluxDown 待确认队列已满，已发送 {0}/{1} 个，请先处理桌面端确认框后重试',
      batchPartial: '发送中断，已发送 {0}/{1} 个',
      menuTakeoverOn: '✅ 下载接管：开（点击切换）',
      menuTakeoverOff: '⛔ 下载接管：关（点击切换）',
      takeoverOn: '下载接管已开启',
      takeoverOff: '下载接管已关闭',
      menuSnifferOn: '🎬 媒体嗅探：开（点击切换）',
      menuSnifferOff: '⛔ 媒体嗅探：关（点击切换）',
      snifferOn: '媒体嗅探已开启',
      snifferOff: '媒体嗅探已关闭',
      menuPanel: '📂 显示/隐藏 资源面板',
      menuAllLinks: '⬇ 下载本页全部链接',
      menuPort: '🔌 设置端口（当前 {0}）',
      promptPort: 'FluxDown RPC 端口（与设置页一致，默认 17800）：',
      portSet: '端口已设为 {0}',
      portBad: '端口无效',
      menuToken: '🔑 设置 Token（可选）',
      promptToken: 'FluxDown RPC 授权密钥（在 FluxDown 设置页生成，可留空）：',
      tokenSaved: 'Token 已保存',
      menuTest: '🩺 测试连接',
      testing: '正在测试…',
      connected: '已连接 FluxDown（{0}）',
      cannotConnect: '无法连接 {0}，请确认 FluxDown 已启动且 RPC 服务已开启',
    },
    en: {
      sent: 'Sent to FluxDown: {0}',
      errToken: 'FluxDown rejected the request: missing or wrong token',
      errTakeoverOff: 'FluxDown browser-script capture is turned off',
      errQueueFull: 'FluxDown confirmation queue is full',
      errHttp: 'FluxDown returned status {0}',
      errNoResponse: 'FluxDown did not respond in time; check the desktop app to see if it was received',
      errOffline: 'FluxDown is not running',
      sfxFallback: '; handed back to the browser',
      sfxNoFallback: '; download not sent',
      fabTitle: 'FluxDown resource panel',
      panelTitle: 'FluxDown sniffed resources',
      refresh: 'Refresh',
      clear: 'Clear',
      sendAll: 'Send all',
      pageLinks: 'Page links',
      emptyList: 'No resources sniffed yet<br>Play a video or reload the page to sniff again',
      download: 'Download',
      noSniffed: 'No resources to send',
      sentN: 'Sent {0} resources to FluxDown',
      noLinks: 'No downloadable links found on this page',
      sentLinks: 'Sent {0} links from this page to FluxDown',
      sendFail: 'Send failed; make sure FluxDown is running',
      batchFull: 'FluxDown confirmation queue is full; sent {0}/{1}. Handle the pending prompts in the desktop app and retry',
      batchPartial: 'Sending interrupted; sent {0}/{1}',
      menuTakeoverOn: '✅ Download capture: ON (click to toggle)',
      menuTakeoverOff: '⛔ Download capture: OFF (click to toggle)',
      takeoverOn: 'Download capture enabled',
      takeoverOff: 'Download capture disabled',
      menuSnifferOn: '🎬 Media sniffer: ON (click to toggle)',
      menuSnifferOff: '⛔ Media sniffer: OFF (click to toggle)',
      snifferOn: 'Media sniffer enabled',
      snifferOff: 'Media sniffer disabled',
      menuPanel: '📂 Show/hide resource panel',
      menuAllLinks: '⬇ Download all links on this page',
      menuPort: '🔌 Set port (current {0})',
      promptPort: 'FluxDown RPC port (same as in Settings, default 17800):',
      portSet: 'Port set to {0}',
      portBad: 'Invalid port',
      menuToken: '🔑 Set token (optional)',
      promptToken: 'FluxDown RPC token (generated in FluxDown Settings, may be empty):',
      tokenSaved: 'Token saved',
      menuTest: '🩺 Test connection',
      testing: 'Testing…',
      connected: 'Connected to FluxDown ({0})',
      cannotConnect: 'Cannot reach {0}; make sure FluxDown is running with the RPC service enabled',
    },
  };
  function t(key) {
    const args = arguments;
    const s = STR[LANG][key] || STR.en[key] || key;
    return s.replace(/\{(\d)\}/g, (_m, i) => String(args[Number(i) + 1]));
  }

  // 只接管 http(s) 与 magnet；blob:/data:/javascript: 等一律交还浏览器。
  function isTakeoverScheme(url) {
    try {
      const p = new URL(url, location.href).protocol;
      return p === 'http:' || p === 'https:' || p === 'magnet:';
    } catch (_) {
      return false;
    }
  }

  // FluxDown 自带 Web UI 的页面（同端口）不接管，否则会劫持它自己的文件下载/日志导出。
  function isSelfPage() {
    try {
      return (location.hostname === '127.0.0.1' || location.hostname === 'localhost') &&
        String(location.port) === String(CFG.port);
    } catch (_) {
      return false;
    }
  }
  function active() { return CFG.enabled && !isSelfPage(); }

  // document.cookie 只附带给与当前页面同站（同主机或其子域）的目标，避免泄露给第三方主机。
  function cookiesFor(url) {
    try {
      const u = new URL(url, location.href);
      if (u.protocol !== 'http:' && u.protocol !== 'https:') return '';
      const h = u.hostname.toLowerCase();
      const p = location.hostname.toLowerCase();
      if (h === p || h.endsWith('.' + p)) return document.cookie || '';
    } catch (_) { /* */ }
    return '';
  }
  function cookiesForAll(urls) {
    const first = cookiesFor(urls[0]);
    return first && urls.every((u) => cookiesFor(u)) ? first : '';
  }

  // ==========================================================================
  // 可下载资源识别
  // ==========================================================================

  // 点击拦截的目标扩展名（视频/音频/压缩包/安装包/文档/镜像/种子等大文件）。
  // 刻意不含 ts/img/bin/dat/csv：它们常是源码页（如 GitHub 的 index.ts）或普通数据页，
  // 会误拦截页面导航。
  const DOWNLOADABLE_EXTS = new Set([
    // 视频
    'mp4', 'mkv', 'avi', 'mov', 'wmv', 'flv', 'webm', 'm4v', 'mpg', 'mpeg', 'rmvb',
    // 音频
    'mp3', 'flac', 'aac', 'wav', 'ogg', 'wma', 'ape', 'm4a', 'opus',
    // 压缩包
    'zip', 'rar', '7z', 'tar', 'gz', 'bz2', 'xz', 'zst', 'tgz', 'cab',
    // 安装包/可执行
    'exe', 'msi', 'dmg', 'pkg', 'deb', 'rpm', 'appimage', 'apk', 'xapk',
    // 文档
    'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'epub',
    // 镜像
    'iso', 'vmdk',
    // 其它大文件
    'torrent',
  ]);

  // 明确排除的网页资源扩展名（避免误拦截）。
  const EXCLUDE_EXTS = new Set([
    'html', 'htm', 'php', 'asp', 'aspx', 'jsp', 'json', 'xml',
    'js', 'mjs', 'css', 'woff', 'woff2', 'eot', 'svg', 'ico', 'map',
  ]);

  // 去重时剥离的「缓存破坏 / 追踪」参数。
  const STRIP_PARAMS = new Set([
    't', 'ts', 'time', 'timestamp', '_', 'rand', 'random', 'nonce',
    'sig', 'signature', 'token', 'expire', 'expires', 'e',
    'utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content',
  ]);

  function extOf(url) {
    try {
      const pathname = new URL(url, location.href).pathname;
      const last = pathname.split('/').pop() || '';
      const dot = last.lastIndexOf('.');
      if (dot > 0 && dot < last.length - 1) return last.substring(dot + 1).toLowerCase();
    } catch (_) {
      const m = url.match(/\.([a-zA-Z0-9]{1,10})(?:[?#]|$)/);
      if (m) return m[1].toLowerCase();
    }
    return '';
  }

  function filenameOf(url) {
    try {
      const pathname = new URL(url, location.href).pathname;
      const last = decodeURIComponent(pathname.split('/').pop() || '');
      if (last && /\.[a-zA-Z0-9]{1,10}$/.test(last)) return last;
    } catch (_) { /* */ }
    return '';
  }

  function isStreamingUrl(url) {
    const l = url.toLowerCase();
    return l.includes('.m3u8') || l.includes('.mpd') || l.includes('/manifest') || l.includes('/playlist');
  }

  // 是否为流媒体分片（不单独展示，避免 HLS 分片刷屏）。
  function isStreamSegment(url) {
    const ext = extOf(url);
    if (ext === 'm4s') return true;
    if (ext === 'ts') {
      const l = url.toLowerCase();
      if (l.includes('/seg') || l.includes('/chunk') || l.includes('/fragment') ||
          l.includes('/hls') || l.includes('/ts/') || l.includes('/segments/')) return true;
      if (/[_-]\d{2,}\.ts/i.test(url) || /seg\d+/i.test(url)) return true;
      return false;
    }
    return false;
  }

  function isMediaContentType(ct) {
    const l = ct.toLowerCase();
    return l.startsWith('video/') || l.startsWith('audio/') ||
      l === 'application/vnd.apple.mpegurl' || l === 'application/x-mpegurl' ||
      l === 'application/dash+xml';
  }

  function isDownloadableContentType(ct) {
    if (isMediaContentType(ct)) return true;
    const l = ct.toLowerCase().split(';')[0].trim();
    const set = new Set([
      'application/pdf', 'application/msword', 'application/epub+zip', 'text/csv',
      'application/octet-stream', 'application/x-download', 'application/force-download',
      'application/zip', 'application/x-rar-compressed', 'application/x-7z-compressed',
      'application/gzip', 'application/x-tar', 'application/x-bzip2', 'application/x-xz',
      'application/zstd', 'application/x-msdownload', 'application/x-msi',
      'application/x-apple-diskimage', 'application/vnd.debian.binary-package',
      'application/vnd.android.package-archive', 'application/x-iso9660-image',
      'application/x-bittorrent',
    ]);
    if (set.has(l)) return true;
    if (l.startsWith('application/vnd.openxmlformats-officedocument')) return true;
    if (l.startsWith('application/vnd.ms-')) return true;
    return false;
  }

  function classifyStreamUrl(url) {
    const l = url.toLowerCase();
    if (l.includes('.m3u8')) return 'HLS';
    if (l.includes('.mpd')) return 'DASH';
    return 'stream';
  }

  // 用于去重的归一化 URL。
  function normalizeForDedup(url) {
    try {
      const u = new URL(url, location.href);
      u.hash = '';
      const del = [];
      u.searchParams.forEach((_v, k) => { if (STRIP_PARAMS.has(k.toLowerCase())) del.push(k); });
      for (const k of del) u.searchParams.delete(k);
      u.searchParams.sort();
      return u.toString();
    } catch (_) {
      return url;
    }
  }

  // 链接是否「看起来可下载」（用于点击拦截判断）。
  function looksDownloadable(url) {
    if (!isTakeoverScheme(url)) return false;
    if (/^magnet:/i.test(url)) return true;
    const ext = extOf(url);
    if (!ext) return false;
    if (EXCLUDE_EXTS.has(ext)) return false;
    return DOWNLOADABLE_EXTS.has(ext);
  }

  // ==========================================================================
  // 传输：发送到 FluxDown 本地服务
  // ==========================================================================

  function gmRequest(opts) {
    return new Promise((resolve, reject) => {
      try {
        GM_xmlhttpRequest({
          method: opts.method || 'GET',
          url: opts.url,
          headers: opts.headers || {},
          data: opts.data,
          timeout: opts.timeout || 8000,
          anonymous: true,
          onload: (r) => resolve(r),
          onerror: (e) => reject(e),
          ontimeout: () => reject(new Error('timeout')),
        });
      } catch (e) {
        reject(e);
      }
    });
  }

  function authHeaders() {
    const h = {
      'Content-Type': 'application/json',
      'X-FluxDown-Client': 'userscript',
    };
    const tk = CFG.token;
    if (tk) h['X-FluxDown-Token'] = tk;
    return h;
  }

  // 最近一次 /ping 结果：已知离线时程序化 click/open 直接放行原生行为。
  let lastPing = { alive: null, ts: 0 };
  async function ping() {
    let alive = false;
    try {
      const r = await gmRequest({ method: 'GET', url: `${base()}/ping`, timeout: 1500 });
      alive = r.status >= 200 && r.status < 300;
    } catch (_) {
      alive = false;
    }
    lastPing = { alive, ts: Date.now() };
    return alive;
  }
  function knownOffline() {
    return lastPing.alive === false && Date.now() - lastPing.ts < 30000;
  }

  // 把单个下载请求发给 FluxDown。status>0 表示收到了明确的 HTTP 响应；0 表示网络错误/超时。
  async function sendDownload(payload) {
    try {
      const r = await gmRequest({
        method: 'POST',
        url: `${base()}/download`,
        headers: authHeaders(),
        data: JSON.stringify(payload),
      });
      const ok = r.status >= 200 && r.status < 300;
      if (!ok) console.warn('[FluxDown] send failed:', r.status, r.responseText);
      else lastPing = { alive: true, ts: Date.now() };
      return { ok, status: r.status };
    } catch (e) {
      console.warn('[FluxDown] send error:', e);
      return { ok: false, status: 0 };
    }
  }

  // 服务端待确认队列上限为 64，单批不超过它以免整批被拒。
  const BATCH_CHUNK = 50;

  async function sendBatch(urls) {
    try {
      const r = await gmRequest({
        method: 'POST',
        url: `${base()}/download/batch`,
        headers: authHeaders(),
        data: JSON.stringify({
          urls,
          referrer: location.href,
          cookies: cookiesForAll(urls),
        }),
      });
      return { ok: r.status >= 200 && r.status < 300, status: r.status };
    } catch (e) {
      console.warn('[FluxDown] batch error:', e);
      return { ok: false, status: 0 };
    }
  }

  // 分批发送；遇到失败即停止。返回已成功发送的条数与最后一次失败状态码。
  async function sendBatchChunked(urls) {
    let sent = 0;
    for (let i = 0; i < urls.length; i += BATCH_CHUNK) {
      const chunk = urls.slice(i, i + BATCH_CHUNK);
      const r = await sendBatch(chunk);
      if (!r.ok) return { sent, status: r.status };
      sent += chunk.length;
    }
    return { sent, status: 200 };
  }

  // 构造一次下载的标准 payload。
  function buildPayload(url, opts) {
    opts = opts || {};
    return {
      url,
      filename: opts.filename || '',
      referrer: opts.referrer || location.href,
      // 仅能拿到非 httpOnly Cookie，且只附带给同站目标。
      cookies: cookiesFor(url),
      fileSize: typeof opts.fileSize === 'number' ? opts.fileSize : undefined,
      mimeType: opts.mimeType || undefined,
    };
  }

  // 发送 + 用户反馈 + 失败回退。
  async function takeover(url, opts) {
    opts = opts || {};
    const res = await sendDownload(buildPayload(url, opts));
    if (res.ok) {
      toast(t('sent', opts.filename || filenameOf(url) || url));
      return true;
    }
    let reason;
    let fallback;
    if (res.status > 0) {
      // 明确的非 2xx：服务端一定没有受理，直接回退。
      fallback = true;
      if (res.status === 401 || res.status === 403) reason = t('errToken');
      else if (res.status === 404) reason = t('errTakeoverOff');
      else if (res.status === 503) reason = t('errQueueFull');
      else reason = t('errHttp', res.status);
    } else if (await ping()) {
      // 超时但服务在线：可能稍后才受理，只提示不回退以免重复下载。
      toast(t('errNoResponse'), true);
      return false;
    } else {
      fallback = true;
      reason = t('errOffline');
    }
    const doFallback = fallback && opts.allowFallback;
    toast(reason + t(doFallback ? 'sfxFallback' : 'sfxNoFallback'), true);
    if (doFallback) {
      if (opts.fallback) opts.fallback();
      else browserFallback(url, opts.filename);
    }
    return false;
  }

  // ==========================================================================
  // 点击 / 程序化下载拦截（DOM 层）
  // ==========================================================================

  // 放行集合：回退浏览器下载时短暂跳过拦截，防止环回。
  const bypassUrls = new Map(); // url -> expiry ts
  function markBypass(url) { bypassUrls.set(url, Date.now() + 15000); }
  function isBypassed(url) {
    const e = bypassUrls.get(url);
    if (!e) return false;
    if (e < Date.now()) { bypassUrls.delete(url); return false; }
    return true;
  }
  setInterval(() => {
    const now = Date.now();
    for (const [u, e] of bypassUrls) if (e < now) bypassUrls.delete(u);
  }, 30000);

  function browserFallback(url, filename) {
    markBypass(url);
    try {
      const a = document.createElement('a');
      a.href = url;
      if (filename) a.download = filename;
      a.setAttribute('data-fluxdown-skip', '1');
      (document.body || document.documentElement).appendChild(a);
      a.click();
      a.remove();
    } catch (e) {
      try { W.location.href = url; } catch (_) { /* */ }
    }
  }

  function bypassPressed(ev) {
    switch (CFG.bypassKey) {
      case 'alt': return ev && ev.altKey;
      case 'ctrl': return ev && (ev.ctrlKey || ev.metaKey);
      case 'shift': return ev && ev.shiftKey;
      default: return false;
    }
  }

  // 用户点击（捕获阶段，先于浏览器默认行为）。
  document.addEventListener('click', function (ev) {
    if (!active()) return;
    if (bypassPressed(ev)) return; // 修饰键放行
    const a = ev.target && ev.target.closest
      ? ev.target.closest('a[href]')
      : null;
    if (!a) return;
    if (a.hasAttribute('data-fluxdown-skip')) return;

    const href = a.href;
    if (!href || !isTakeoverScheme(href) || isBypassed(href)) return;

    const hasDownloadAttr = a.hasAttribute('download');
    if (!hasDownloadAttr && !looksDownloadable(href)) return;

    // 命中：接管。必须在任何 await 之前同步阻断默认行为。
    ev.preventDefault();
    ev.stopPropagation();
    takeover(href, {
      filename: a.getAttribute('download') || '',
      referrer: location.href,
      allowFallback: true,
    });
  }, true);

  // 程序化 .click()（页面 JS 创建隐藏 a 并 .click() 触发下载）。
  try {
    const origClick = W.HTMLAnchorElement.prototype.click;
    W.HTMLAnchorElement.prototype.click = function () {
      try {
        if (active() && !knownOffline() && !this.hasAttribute('data-fluxdown-skip')) {
          const href = this.href;
          // blob:/data: 等协议必须在此同步放行：页面常在 click() 后立即 revoke。
          if (href && isTakeoverScheme(href) && !isBypassed(href) &&
              (this.hasAttribute('download') || looksDownloadable(href))) {
            takeover(href, { filename: this.getAttribute('download') || '', allowFallback: true });
            return; // 拦截，不执行原生 click；失败时由 browserFallback 重放
          }
        }
      } catch (_) { /* */ }
      return origClick.apply(this, arguments);
    };
  } catch (_) { /* */ }

  // window.open(下载型 URL)。
  try {
    const origOpen = W.open;
    W.open = function (url) {
      try {
        if (active() && !knownOffline() && typeof url === 'string' && url && !isBypassed(url) &&
            isTakeoverScheme(url) && looksDownloadable(url)) {
          const args = arguments;
          const self = this;
          // 失败时重放原 window.open（用户手势可能已过期，浏览器可能拦截弹窗）。
          takeover(url, { allowFallback: true, fallback: () => origOpen.apply(self, args) });
          return null;
        }
      } catch (_) { /* */ }
      return origOpen.apply(this, arguments);
    };
  } catch (_) { /* */ }

  // ==========================================================================
  // 媒体嗅探（hook fetch / XHR / MediaSource，document-start 注入）
  // ==========================================================================

  // 嗅探到的资源（去重后）。{ url, kind, contentType, size, ts }
  const sniffed = [];
  const notified = new Set();

  function recordResource(kind, url, contentType, size) {
    if (!url) return;
    try {
      const abs = new URL(url, location.href).href;
      if (!/^https?:/i.test(abs)) return;
      if (isStreamSegment(abs)) return; // 过滤分片
      const key = `${kind}:${normalizeForDedup(abs)}`;
      if (notified.has(key)) return;
      notified.add(key);
      if (notified.size > 800) notified.clear();

      sniffed.unshift({ url: abs, kind, contentType: contentType || '', size: size || 0, ts: Date.now() });
      if (sniffed.length > 60) sniffed.length = 60;
      updateFab();
    } catch (_) { /* */ }
  }

  // ---- hook fetch ----
  try {
    const origFetch = W.fetch;
    if (origFetch) {
      W.fetch = function () {
        const args = arguments;
        let url = '';
        try {
          const a0 = args[0];
          if (typeof a0 === 'string') url = a0;
          else if (a0 instanceof W.Request) url = a0.url;
          else if (a0 instanceof W.URL) url = a0.href;
          else if (a0 && a0.url) url = a0.url;
        } catch (_) { /* */ }

        if (CFG.sniffer && url && isStreamingUrl(url)) {
          recordResource(classifyStreamUrl(url), url);
        }

        return origFetch.apply(this, args).then((resp) => {
          try {
            if (CFG.sniffer && resp) {
              const ct = resp.headers.get('content-type') || '';
              const cl = resp.headers.get('content-length');
              const finalUrl = resp.url || url;
              if (ct && isDownloadableContentType(ct)) {
                recordResource(ct.split('/')[0] || 'file', finalUrl, ct, cl ? parseInt(cl, 10) : 0);
              }
              if (finalUrl && finalUrl !== url && isStreamingUrl(finalUrl)) {
                recordResource(classifyStreamUrl(finalUrl), finalUrl, ct);
              }
            }
          } catch (_) { /* */ }
          return resp;
        });
      };
    }
  } catch (_) { /* */ }

  // ---- hook XHR ----
  try {
    const origXOpen = W.XMLHttpRequest.prototype.open;
    const origXSend = W.XMLHttpRequest.prototype.send;
    W.XMLHttpRequest.prototype.open = function (method, url) {
      try {
        this.__fd_url = typeof url === 'string' ? url : (url && url.href) || '';
        if (CFG.sniffer && this.__fd_url && isStreamingUrl(this.__fd_url)) {
          recordResource(classifyStreamUrl(this.__fd_url), this.__fd_url);
        }
      } catch (_) { /* */ }
      return origXOpen.apply(this, arguments);
    };
    W.XMLHttpRequest.prototype.send = function () {
      try {
        this.addEventListener('load', function () {
          try {
            if (!CFG.sniffer) return;
            const url = this.__fd_url;
            if (!url) return;
            const ct = this.getResponseHeader('content-type') || '';
            const cl = this.getResponseHeader('content-length');
            const responseUrl = this.responseURL || url;
            if (ct && isDownloadableContentType(ct)) {
              recordResource(ct.split('/')[0] || 'file', responseUrl, ct, cl ? parseInt(cl, 10) : 0);
            }
            if (responseUrl !== url && isStreamingUrl(responseUrl)) {
              recordResource(classifyStreamUrl(responseUrl), responseUrl, ct);
            }
          } catch (_) { /* */ }
        });
      } catch (_) { /* */ }
      return origXSend.apply(this, arguments);
    };
  } catch (_) { /* */ }

  // ---- hook MediaSource（辅助信号：触发一次 performance 扫描找清单）----
  try {
    const MS = W.MediaSource;
    if (MS && MS.prototype && MS.prototype.addSourceBuffer) {
      const origASB = MS.prototype.addSourceBuffer;
      MS.prototype.addSourceBuffer = function (mime) {
        try {
          if (CFG.sniffer && mime && (mime.startsWith('video/') || mime.startsWith('audio/'))) {
            scanPerformanceForStreams();
          }
        } catch (_) { /* */ }
        return origASB.apply(this, arguments);
      };
    }
  } catch (_) { /* */ }

  // performance 条目里找已加载的 m3u8/mpd（被动补充）。
  function scanPerformanceForStreams() {
    try {
      const entries = W.performance && W.performance.getEntriesByType
        ? W.performance.getEntriesByType('resource') : [];
      for (const e of entries) {
        if (e && e.name && isStreamingUrl(e.name) && !isStreamSegment(e.name)) {
          recordResource(classifyStreamUrl(e.name), e.name);
        }
      }
    } catch (_) { /* */ }
  }

  // ---- DOM 扫描 + MutationObserver（静态 <video>/<audio>/<source>/<a download>）----
  function scanDom(root) {
    try {
      const nodes = (root || document).querySelectorAll('video[src],audio[src],source[src],a[download][href]');
      for (const n of nodes) {
        const src = n.getAttribute('src') || n.getAttribute('href') || '';
        if (!src || src.startsWith('blob:') || src.startsWith('data:')) continue;
        const abs = new URL(src, location.href).href;
        if (n.tagName === 'A') {
          if (looksDownloadable(abs) || n.hasAttribute('download')) {
            recordResource('file', abs, '', 0);
          }
        } else {
          recordResource(n.tagName.toLowerCase(), abs, '', 0);
        }
      }
    } catch (_) { /* */ }
  }

  function startDomScan() {
    scanDom(document);
    scanPerformanceForStreams();
    try {
      const obs = new MutationObserver((muts) => {
        if (!CFG.sniffer) return;
        for (const m of muts) {
          if (m.type === 'childList') {
            for (const node of m.addedNodes) {
              if (node.nodeType === 1) scanDom(node);
            }
          } else if (m.type === 'attributes' && m.target && m.target.nodeType === 1) {
            scanDom(m.target.parentNode || m.target);
          }
        }
      });
      obs.observe(document.documentElement, {
        childList: true, subtree: true, attributes: true,
        attributeFilter: ['src', 'href'],
      });
    } catch (_) { /* */ }
  }

  // ==========================================================================
  // 悬浮 UI（Shadow DOM 隔离样式）
  // ==========================================================================

  let shadow = null, fabEl = null, panelEl = null, listEl = null, badgeEl = null, toastWrap = null;

  function buildUI() {
    if (shadow) return;
    const host = document.createElement('div');
    host.id = 'fluxdown-userscript-root';
    host.style.cssText = 'all:initial;position:fixed;z-index:2147483647;right:0;bottom:0;';
    (document.body || document.documentElement).appendChild(host);
    shadow = host.attachShadow({ mode: 'open' });

    const style = document.createElement('style');
    style.textContent = `
      :host { all: initial; }
      * { box-sizing: border-box; font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; }
      .fab {
        position: fixed; right: 18px; bottom: 18px; width: 46px; height: 46px;
        border-radius: 50%; background: #2563eb; color: #fff; cursor: pointer;
        display: flex; align-items: center; justify-content: center;
        box-shadow: 0 4px 16px rgba(0,0,0,.28); font-size: 20px; user-select: none;
        transition: transform .15s ease, background .15s ease;
      }
      .fab:hover { transform: scale(1.08); background: #1d4ed8; }
      .badge {
        position: absolute; top: -4px; right: -4px; min-width: 18px; height: 18px;
        padding: 0 5px; border-radius: 9px; background: #ef4444; color: #fff;
        font-size: 11px; line-height: 18px; text-align: center; display: none;
      }
      .badge.show { display: block; }
      .panel {
        position: fixed; right: 18px; bottom: 74px; width: 360px; max-height: 60vh;
        background: #fff; color: #111; border-radius: 12px; overflow: hidden;
        box-shadow: 0 8px 32px rgba(0,0,0,.3); display: none; flex-direction: column;
        border: 1px solid rgba(0,0,0,.08);
      }
      .panel.show { display: flex; }
      .hd { padding: 12px 14px; background: #2563eb; color: #fff; font-size: 14px; font-weight: 600;
            display: flex; align-items: center; justify-content: space-between; }
      .hd .acts { display: flex; gap: 8px; }
      .hd button { background: rgba(255,255,255,.18); border: 0; color: #fff; border-radius: 6px;
                   padding: 4px 8px; font-size: 12px; cursor: pointer; }
      .hd button:hover { background: rgba(255,255,255,.32); }
      .list { overflow-y: auto; flex: 1; }
      .empty { padding: 28px 14px; text-align: center; color: #888; font-size: 13px; }
      .item { padding: 10px 14px; border-bottom: 1px solid #f0f0f0; display: flex; gap: 10px; align-items: center; }
      .item:hover { background: #f7f8fa; }
      .ic { flex: 0 0 34px; height: 34px; border-radius: 8px; background: #eef2ff; color: #2563eb;
            display: flex; align-items: center; justify-content: center; font-size: 11px; font-weight: 700; }
      .meta { flex: 1; min-width: 0; }
      .nm { font-size: 13px; color: #111; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .sub { font-size: 11px; color: #999; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .dl { flex: 0 0 auto; background: #2563eb; color: #fff; border: 0; border-radius: 6px;
            padding: 6px 10px; font-size: 12px; cursor: pointer; }
      .dl:hover { background: #1d4ed8; }
      .ft { padding: 8px 14px; border-top: 1px solid #f0f0f0; display: flex; gap: 8px; }
      .ft button { flex: 1; border: 1px solid #d0d5dd; background: #fff; color: #111; border-radius: 8px;
                   padding: 7px; font-size: 12px; cursor: pointer; }
      .ft button:hover { background: #f2f4f7; }
      .ft .primary { background: #2563eb; color: #fff; border-color: #2563eb; }
      .ft .primary:hover { background: #1d4ed8; }
      .toasts { position: fixed; right: 18px; bottom: 78px; display: flex; flex-direction: column; gap: 8px; align-items: flex-end; }
      .toast { background: #111827; color: #fff; padding: 9px 13px; border-radius: 8px; font-size: 12px;
               max-width: 320px; box-shadow: 0 4px 16px rgba(0,0,0,.25); animation: fadein .2s ease; }
      .toast.warn { background: #b45309; }
      @keyframes fadein { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
    `;
    shadow.appendChild(style);

    toastWrap = document.createElement('div');
    toastWrap.className = 'toasts';
    shadow.appendChild(toastWrap);

    fabEl = document.createElement('div');
    fabEl.className = 'fab';
    fabEl.title = t('fabTitle');
    fabEl.innerHTML = '⬇<span class="badge"></span>';
    badgeEl = fabEl.querySelector('.badge');
    fabEl.addEventListener('click', togglePanel);
    shadow.appendChild(fabEl);

    panelEl = document.createElement('div');
    panelEl.className = 'panel';
    panelEl.innerHTML = `
      <div class="hd">
        <span>${t('panelTitle')}</span>
        <span class="acts">
          <button data-act="refresh">${t('refresh')}</button>
          <button data-act="clear">${t('clear')}</button>
          <button data-act="close">×</button>
        </span>
      </div>
      <div class="list"></div>
      <div class="ft">
        <button data-act="all" class="primary">${t('sendAll')}</button>
        <button data-act="links">${t('pageLinks')}</button>
      </div>`;
    listEl = panelEl.querySelector('.list');
    panelEl.querySelector('[data-act="close"]').addEventListener('click', togglePanel);
    panelEl.querySelector('[data-act="clear"]').addEventListener('click', () => { sniffed.length = 0; notified.clear(); renderList(); updateFab(); });
    panelEl.querySelector('[data-act="refresh"]').addEventListener('click', () => { scanDom(document); scanPerformanceForStreams(); renderList(); });
    panelEl.querySelector('[data-act="all"]').addEventListener('click', sendAllSniffed);
    panelEl.querySelector('[data-act="links"]').addEventListener('click', downloadAllLinks);
    shadow.appendChild(panelEl);
  }

  function updateFab() {
    if (!badgeEl) return;
    const n = sniffed.length;
    if (n > 0) { badgeEl.textContent = n > 99 ? '99+' : String(n); badgeEl.classList.add('show'); }
    else badgeEl.classList.remove('show');
    if (panelEl && panelEl.classList.contains('show')) renderList();
  }

  function fmtSize(b) {
    if (!b || b <= 0) return '';
    const u = ['B', 'KB', 'MB', 'GB']; let i = 0; let n = b;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return `${n.toFixed(n < 10 && i > 0 ? 1 : 0)}${u[i]}`;
  }

  function renderList() {
    if (!listEl) return;
    if (!sniffed.length) {
      listEl.innerHTML = `<div class="empty">${t('emptyList')}</div>`;
      return;
    }
    listEl.innerHTML = '';
    for (const r of sniffed) {
      const item = document.createElement('div');
      item.className = 'item';
      const label = (r.kind || 'file').toUpperCase().slice(0, 4);
      const name = filenameOf(r.url) || r.url.split('/').pop().split('?')[0] || r.url;
      const sub = [r.contentType, fmtSize(r.size)].filter(Boolean).join(' · ') || r.url;
      item.innerHTML = `
        <div class="ic">${label}</div>
        <div class="meta"><div class="nm"></div><div class="sub"></div></div>
        <button class="dl">${t('download')}</button>`;
      item.querySelector('.nm').textContent = name;
      item.querySelector('.sub').textContent = sub;
      item.querySelector('.dl').addEventListener('click', () => {
        takeover(r.url, { fileSize: r.size || undefined, mimeType: r.contentType || undefined, allowFallback: false });
      });
      listEl.appendChild(item);
    }
  }

  function togglePanel() {
    buildUI();
    const showing = panelEl.classList.toggle('show');
    if (showing) { scanDom(document); scanPerformanceForStreams(); renderList(); }
  }

  async function sendAllSniffed() {
    if (!sniffed.length) { toast(t('noSniffed'), true); return; }
    const urls = sniffed.map((r) => r.url);
    const res = await sendBatchChunked(urls);
    reportBatch(res, urls.length, 'sentN');
  }

  function reportBatch(res, total, okKey) {
    if (res.sent === total) toast(t(okKey, total));
    else if (res.status === 503) toast(t('batchFull', res.sent, total), true);
    else if (res.sent > 0) toast(t('batchPartial', res.sent, total), true);
    else toast(t('sendFail'), true);
  }

  async function downloadAllLinks() {
    const set = new Set();
    document.querySelectorAll('a[href]').forEach((a) => {
      const href = a.href;
      if (href && isTakeoverScheme(href) && (looksDownloadable(href) || a.hasAttribute('download'))) set.add(href);
    });
    const urls = [...set];
    if (!urls.length) { toast(t('noLinks'), true); return; }
    const res = await sendBatchChunked(urls);
    reportBatch(res, urls.length, 'sentLinks');
  }

  // 轻量 toast（优先页面内 Shadow DOM，失败回退 GM_notification）。
  function toast(msg, warn) {
    try {
      buildUI();
      const t = document.createElement('div');
      t.className = 'toast' + (warn ? ' warn' : '');
      t.textContent = msg;
      toastWrap.appendChild(t);
      setTimeout(() => { t.style.transition = 'opacity .3s'; t.style.opacity = '0'; setTimeout(() => t.remove(), 300); }, 2600);
    } catch (_) {
      try { GM_notification({ text: msg, title: 'FluxDown', timeout: 3000 }); } catch (__) { /* */ }
    }
  }

  // ==========================================================================
  // 油猴菜单命令
  // ==========================================================================

  let menuIds = [];
  function registerMenu() {
    // 幂等：先注销上一轮的命令（支持 GM_unregisterMenuCommand 的管理器），
    // 避免切换状态时累积重复菜单项。
    if (typeof GM_unregisterMenuCommand === 'function') {
      for (const id of menuIds) { try { GM_unregisterMenuCommand(id); } catch (_) { /* */ } }
    }
    menuIds = [];
    const add = (caption, fn) => {
      try { menuIds.push(GM_registerMenuCommand(caption, fn)); } catch (_) { /* */ }
    };

    add(t(CFG.enabled ? 'menuTakeoverOn' : 'menuTakeoverOff'), () => {
      CFG.enabled = !CFG.enabled;
      toast(t(CFG.enabled ? 'takeoverOn' : 'takeoverOff'));
      registerMenu();
    });
    add(t(CFG.sniffer ? 'menuSnifferOn' : 'menuSnifferOff'), () => {
      CFG.sniffer = !CFG.sniffer;
      toast(t(CFG.sniffer ? 'snifferOn' : 'snifferOff'));
      registerMenu();
    });
    add(t('menuPanel'), () => togglePanel());
    add(t('menuAllLinks'), () => downloadAllLinks());
    add(t('menuPort', CFG.port), () => {
      const p = prompt(t('promptPort'), String(CFG.port));
      if (p !== null) {
        const n = Number(p.trim());
        if (Number.isInteger(n) && n >= 1024 && n <= 65535) { CFG.port = n; toast(t('portSet', n)); registerMenu(); }
        else toast(t('portBad'), true);
      }
    });
    add(t('menuToken'), () => {
      const tk = prompt(t('promptToken'), CFG.token);
      if (tk !== null) { CFG.token = tk.trim(); toast(t('tokenSaved')); }
    });
    add(t('menuTest'), async () => {
      toast(t('testing'));
      const ok = await ping();
      toast(ok ? t('connected', base()) : t('cannotConnect', base()), !ok);
    });
  }

  // ==========================================================================
  // 启动
  // ==========================================================================

  registerMenu();

  function onReady() {
    buildUI();
    if (CFG.sniffer) startDomScan();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', onReady, { once: true });
  } else {
    onReady();
  }

  console.log('[FluxDown] userscript loaded; target =', base());
})();

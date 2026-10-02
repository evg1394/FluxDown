# FluxDown 油猴脚本（下载接管）

通过 **Tampermonkey / Violentmonkey** 用户脚本，把浏览器里的下载与流媒体资源
一键发送到 **FluxDown** 桌面下载器——无需安装浏览器扩展。

> API 鉴权与来源门禁见 [宿主与 API 契约](../.omp/knowledge/hosts-and-api.md)。

## 安装

1. 在浏览器安装 [Tampermonkey](https://www.tampermonkey.net/) 或
   [Violentmonkey](https://violentmonkey.github.io/)。
2. 安装本脚本 `fluxdown.user.js`，任选其一：
   - **从 GPUI 桌面客户端**：打开 FluxDown → 设置 → API 服务 → 「复制油猴脚本」，
     在 Tampermonkey「添加新脚本」中粘贴保存。脚本已带当前生效端口与访问令牌；
   - **从文件**：用脚本管理器打开本仓库的 `userscript/fluxdown.user.js`。
3. 确保 FluxDown 桌面端正在运行，且「设置 → API 服务 → 浏览器脚本接管」已开启（默认开启）。
4. （Chrome）Tampermonkey 需开启浏览器「开发者模式」，并在脚本权限中允许「所有网站」，
   `GM_xmlhttpRequest` 才能访问本机服务。

## 使用

- **点击下载**：点击下载链接 / 带 `download` 的链接 / 下载型扩展名链接时，自动接管并
  发送到 FluxDown，遵从桌面端的确认 / 免打扰设置。按住 **Alt** 点击则放行给浏览器。
- **媒体嗅探**：播放视频的页面右下角会出现 ⬇ 悬浮按钮，点开「资源面板」可看到嗅探到的
  HLS/DASH/视频/音频等资源，逐个或「全部发送」到 FluxDown。
- **油猴菜单**（点击 Tampermonkey 图标 → 本脚本）：
  - 下载接管 开/关
  - 媒体嗅探 开/关
  - 显示/隐藏 资源面板
  - 下载本页全部链接
  - 设置端口 / 设置 Token / 测试连接

## 配置

| 项 | 说明 |
|---|---|
| 端口 | 须与「设置 → API 服务」显示的当前生效地址一致（默认 17800）。输入回车或失焦后自动重启 API/RPC 监听，验证成功才显示新地址与成功提示；端口占用或启动失败会恢复原端口，原服务继续运行 |
| Token | 应用内复制的脚本自动带入访问令牌；从仓库文件安装时，若已配置访问令牌，需在油猴菜单 → 设置 Token 中填写 |

端口切换成功或访问令牌变更后，重新复制脚本并替换已安装的脚本，或在油猴菜单修改对应配置；复制操作始终使用当前已验证可用的监听端口，不使用输入草稿或失败的端口。

## 安全

- 脚本通过管理器的 `GM_xmlhttpRequest` 调用 `/download` 与 `/download/batch`，携带 `X-FluxDown-Client` 和已配置的 `X-FluxDown-Token`；不使用仅供官方客户端访问的 `/rpc`，不需要把官方 UI 的 `agent.token` 放入脚本。
- **无需开启「允许任意网页跨域访问（CORS）」**，也不需要开放局域网监听。来源门禁、访问令牌校验保持生效。
- 应用内复制的脚本包含访问令牌，**不要分享或上传**；凭据仅在脚本沙箱和脚本管理器存储中使用，不挂到网页对象或 DOM。
- `/ping` 的「测试连接」仅确认服务在线，不代表当前令牌有效或接管开关已开启。

## 能力边界（请知悉）

- **无法**接管「浏览器内核直接发起、非页面 JS 触发」的下载——这类请改用 FluxDown 浏览器扩展。
- 仅能携带非 httpOnly 的 Cookie（`document.cookie`）；需 httpOnly 鉴权的下载建议用扩展。
- HLS/MSE 站点需在播放器初始化时嗅到清单；若播放开始后才装脚本，请**刷新页面**重新嗅探。
- 默认 `@noframes`，不嗅探跨域 iframe 内的媒体。

## 与 aria2 脚本的兼容

FluxDown 额外暴露 `POST /jsonrpc` 的 **aria2 JSON-RPC 兼容端点**（`aria2.addUri` 等）。
「发送到 aria2」类脚本需开启 aria2 RPC 兼容，把地址指向当前生效端口的
`http://127.0.0.1:<端口>/jsonrpc`，并在已配置访问令牌时携带对应令牌。
普通网页直接调用受来源门禁限制，不等同于油猴管理器请求；aria2 接口直接建任务，不走接管确认流程。

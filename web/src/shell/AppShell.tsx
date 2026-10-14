// 应用外壳（对齐 GPUI `crates/shell/src/view.rs`）：
//   桌面：标题栏(40) / [活动栏(48) | 内容区] ；移动端：标题栏 / 内容区 / 底部标签栏。
// 内容区背景 surface；活动栏、标题栏、（页面自己的）侧栏与状态栏为 chrome。

import { Outlet, useLocation } from '@tanstack/react-router'
import { useT } from '../i18n'
import { AccountGuards } from './AccountGuards'
import { ActivityRail } from './ActivityRail'
import { BottomTabBar } from './BottomTabBar'
import { ConnectionBanner, ConnectionDot } from './ConnectionStatus'
import { TitleBar, TitleBarSlotProvider } from './TitleBar'
import { UpdateBanner, UpdateRestartOverlay } from './UpdateStatus'

function useDefaultTitle(): string {
  const t = useT()
  const { pathname } = useLocation()
  if (pathname.startsWith('/rss')) return t('sidebarRss')
  if (pathname.startsWith('/webhooks')) return t('pushNavTitle')
  if (pathname.startsWith('/settings')) return t('settings')
  return t('mobileNavDownloads')
}

export function AppShell() {
  const defaultTitle = useDefaultTitle()
  return (
    <TitleBarSlotProvider>
      <div className="flex h-dvh w-full flex-col overflow-hidden bg-background text-foreground">
        <TitleBar defaultTitle={defaultTitle} trailing={<ConnectionDot />} />
        <ConnectionBanner />
        <UpdateBanner />
        <div className="flex min-h-0 flex-1">
          <ActivityRail />
          <main className="relative flex min-w-0 flex-1 flex-col overflow-hidden bg-surface">
            <Outlet />
          </main>
        </div>
        <BottomTabBar />
        <AccountGuards />
        <UpdateRestartOverlay />
      </div>
    </TitleBarSlotProvider>
  )
}

export type TabKey = 'forum' | 'search' | 'ai' | 'notifications' | 'contact'
export const TABS = [
  { key: 'forum', label: '首页', icon: 'house', url: '/pages/forum/index' },
  { key: 'search', label: '搜索', icon: 'search', url: '/pages/search/index' },
  { key: 'notifications', label: '通知', icon: 'bell', url: '/pages/notifications/index' },
  { key: 'contact', label: '私信', icon: 'message-square', url: '/pages/contact/index' },
] as const
export function defineTabPage(activeKey: TabKey): void {
  Page({ onShow() { this.getTabBar()?.setData({ activeKey, publishVisible: false }) }, onHide() { this.getTabBar()?.setData({ publishVisible: false }) } })
}

export type TabKey = 'forum' | 'market' | 'contact' | 'profile'

export const TABS = [
  { key: 'forum', label: '论坛', icon: 'messages-square', url: '/pages/forum/index' },
  { key: 'market', label: '交易', icon: 'shopping-bag', url: '/pages/market/index' },
  { key: 'contact', label: '联系', icon: 'message-square-text', url: '/pages/contact/index' },
  { key: 'profile', label: '我的', icon: 'user-round', url: '/pages/profile/index' },
] as const

export function defineTabPage(activeKey: TabKey): void {
  Page({
    onShow() {
      this.getTabBar()?.setData({ activeKey, publishVisible: false })
    },
    onHide() {
      this.getTabBar()?.setData({ publishVisible: false })
    },
  })
}

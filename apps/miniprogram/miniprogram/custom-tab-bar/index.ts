import { TABS } from '../utils/navigation'
import { unreadTotal } from '../features/contact/badges'
import { client, Runtime } from '../backend/client'
import { onIdentityChange } from '../features/profile/identity-events'
import { getViewerRole } from '../features/profile/viewer'

const selectionRenders = new WeakMap<object, object>()
const identityUnsubs = new WeakMap<object, Array<() => void>>()

Component({
  data: {
    tabs: TABS,
    activeKey: 'forum',
    publishVisible: false, drawerVisible: false,
    unreadCount: 0, messageUnread: 0, notificationUnread: 0,
    selectionMotion: false,
  },
  // 自定义导航的页面显示回调不总会触发，以页面写入的选中项为准。
  observers: { activeKey() { this.playSelection() } },
  lifetimes: {
    attached() {
      const pages = getCurrentPages()
      const route = pages[pages.length - 1]?.route
      const current = TABS.find((tab) => tab.url === '/' + route)
      if (current) this.setData({ activeKey: current.key })
      this.refreshUnread()
      // 登录/退出发生在页面内时不会触发 pageLifetimes.show，未读角标要跟着身份重算。
      const disposers: Array<() => void> = [onIdentityChange(() => this.refreshUnread())]
      try { disposers.push(client(wx).onSessionChange(() => this.refreshUnread())) } catch { /* 演示模式下会话不可用。 */ }
      identityUnsubs.set(this, disposers)
    },
    detached() { selectionRenders.delete(this); identityUnsubs.get(this)?.forEach(dispose => dispose()); identityUnsubs.delete(this) },
  },
  pageLifetimes: {
    show() { this.refreshUnread(); this.playSelection() },
    hide() { selectionRenders.delete(this); this.setData({ selectionMotion: false }) },
  },
  methods: {
    playSelection() {
      const render = {}
      selectionRenders.set(this, render)
      this.setData({ selectionMotion: false })
      // 每个页面有独立导航实例，返回缓存页时也要重新触发；离开后忽略旧回调。
      wx.nextTick(() => {
        if (selectionRenders.get(this) === render) this.setData({ selectionMotion: true })
      })
    },
    async refreshUnread() {
      try {
        if ((wx as Runtime).__fixtureMode === true) {
          // 访客不显示上个登录账号的未读角标（与后端分支按会话判断的行为对齐）。
          if (getViewerRole(wx) === 'guest') { this.setData({ unreadCount: 0, messageUnread: 0, notificationUnread: 0 }); return }
          this.setData({ unreadCount: unreadTotal(wx) }); return
        }
        const api = client(wx)
        const unread = api.loggedIn ? await api.request('/me/unread') : null
        this.setData({ unreadCount: unread?.badgeTotal || 0, messageUnread: unread?.raw?.messages || 0, notificationUnread: (unread?.raw?.comments || 0) + (unread?.raw?.reactions || 0) + (unread?.raw?.announcements || 0) })
      } catch { /* Keep the last known badge on a read failure. */ }
    },
    onTabTap(event: WechatMiniprogram.TouchEvent) {
      const tab = TABS.find((item) => item.key === event.currentTarget.dataset.key)
      if (!tab || tab.key === this.data.activeKey) return
      wx.switchTab({
        url: tab.url,
        fail() {
          wx.showToast({ title: '切换失败，请重试', icon: 'none' })
        },
      })
    },
    openPublish() { wx.navigateTo({ url:'/pages/forum-publish/index', fail() { wx.showToast({title:'发布页暂时无法打开',icon:'none'}) } }) },
    // 同一个悬浮按钮在不同 tab 下做不同的事：私信页是「发起新对话」，其余是「发帖」。
    onFab() { if (this.data.activeKey === 'contact') this.openChat(); else this.openPublish() },
    openChat() { wx.navigateTo({ url:'/pages/new-chat/index' }) },
  },
})

import { TABS } from '../utils/navigation'

Component({
  data: {
    tabs: TABS,
    activeKey: 'forum',
    publishVisible: false,
  },
  lifetimes: {
    attached() {
      const pages = getCurrentPages()
      const route = pages[pages.length - 1]?.route
      const current = TABS.find((tab) => tab.url === '/' + route)
      if (current) this.setData({ activeKey: current.key })
    },
  },
  methods: {
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
    openPublish() {
      this.setData({ publishVisible: true })
    },
    closePublish() {
      this.setData({ publishVisible: false })
    },
    onPublishSelect(event: WechatMiniprogram.CustomEvent<{ type: string }>) {
      const type = event.detail.type
      if (type !== 'post' && type !== 'product') return
      this.setData({ publishVisible: false })
      this.triggerEvent('publishselect', { type })
      wx.showToast({
        title: type === 'post' ? '已选择发布帖子' : '已选择发布闲置',
        icon: 'none',
      })
    },
  },
})

import { menuRightSpace } from '../../utils/layout'
Component({
  properties: { title: { type: String, value: '' }, interceptBack: { type: Boolean, value: false }, fallback: { type: String, value: '/pages/forum/index' }, actionLabel: { type: String, value: '' }, busy: { type: Boolean, value: false } },
  data: { statusHeight: 20, navigationHeight: 44, rightSpace: 104 },
  lifetimes: {
    attached() {
      const window = wx.getWindowInfo()
      const menu = wx.getMenuButtonBoundingClientRect()
      const statusHeight = window.statusBarHeight || 20
      const valid = menu.height > 0 && menu.top >= statusHeight
      const navigationHeight = valid ? (menu.top - statusHeight) * 2 + menu.height : 44
      this.setData({ statusHeight, navigationHeight, rightSpace: valid ? menuRightSpace(window.windowWidth, menu.left) : 104 })
      this.triggerEvent('layout', { height: statusHeight + navigationHeight, rightSpace: valid ? menuRightSpace(window.windowWidth, menu.left) : 104 })
    },
  },
  methods: { onAction() { if (!this.data.busy) this.triggerEvent('action') }, onBack() {
    if (this.data.interceptBack) { this.triggerEvent('requestback'); return }
    const fallback = this.data.fallback || '/pages/forum/index'
    wx.navigateBack({ delta: 1, fail() { if (['/pages/forum/index','/pages/search/index','/pages/ai/index','/pages/notifications/index','/pages/contact/index'].includes(fallback)) wx.switchTab({ url: fallback }); else wx.navigateTo({ url: fallback }) } })
  } },
})

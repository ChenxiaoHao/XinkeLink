Component({
  properties: {
    title: { type: String, value: '信科Link' },
    subtitle: { type: String, value: '' },
    profile: { type: Boolean, value: false },
    logoSrc: { type: String, value: '' },
  },
  data: { statusHeight: 20, navigationHeight: 44, rightSpace: 104 },
  lifetimes: {
    attached() {
      const window = wx.getWindowInfo()
      const menu = wx.getMenuButtonBoundingClientRect()
      const statusHeight = window.statusBarHeight || 20
      const validMenu = menu.height > 0 && menu.top >= statusHeight
      this.setData({
        statusHeight,
        navigationHeight: validMenu ? (menu.top - statusHeight) * 2 + menu.height : 44,
        rightSpace: validMenu ? window.windowWidth - menu.left + 12 : 104,
      })
    },
  },
})

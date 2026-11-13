import { registerPage } from '../../backend/pages'
import { Runtime } from '../../backend/client'

registerPage(wx, Page, 'beta-access')({
  data: {},
  onLoad() {
    // 本地演示模式没有后端可问，门禁直接放行到首页。
    // 首页是 tabBar 页面，switchTab 才是可靠入口；reLaunch 只作兜底。
    if ((wx as Runtime).__fixtureMode !== true) return
    wx.switchTab({ url: '/pages/forum/index', fail() { wx.reLaunch({ url: '/pages/forum/index' }) } })
  },
})

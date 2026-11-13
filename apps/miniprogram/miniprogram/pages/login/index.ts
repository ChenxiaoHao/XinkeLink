import { registerPage } from '../../backend/pages'
import { readLocalSession, setLocalSession } from '../../features/profile/repository'
registerPage(wx, Page, 'login')({
  data: { busy: false, loadFailed: false },
  onShow() { this.reload() },
  reload() { try { readLocalSession(wx); this.setData({ loadFailed: false }) } catch { this.setData({ loadFailed: true }) } },
  onLoginTap() {
    if (this.data.busy) return
    this.setData({ busy: true })
    try {
      setLocalSession(wx, true); this.setData({ loadFailed: false })
      wx.showToast({ title: '已进入模拟登录', icon: 'success' })
      wx.navigateTo({ url: '/pages/profile/index', fail() { wx.showToast({ title: '登录已保存，请返回我的', icon: 'none' }) } })
    } catch { wx.showToast({ title: '模拟登录未保存，请重试', icon: 'none' }) }
    finally { this.setData({ busy: false }) }
  },
  onGuestTap() {
    if (this.data.busy) return
    try { setLocalSession(wx, false); wx.switchTab({ url: '/pages/forum/index' }) }
    catch { wx.showToast({ title: '访客状态未保存，请重试', icon: 'none' }) }
  },
})

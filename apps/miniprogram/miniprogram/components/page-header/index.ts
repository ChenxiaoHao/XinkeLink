import { menuRightSpace } from '../../utils/layout'
import { client, Runtime } from '../../backend/client'
import { profileView } from '../../backend/adapters'
import { localIdentity, readLocalSession } from '../../features/profile/repository'
import { onIdentityChange } from '../../features/profile/identity-events'
import { avatarInitial } from '../../utils/presentation'

// 取消订阅的函数放在模块级 WeakMap，不往组件实例上挂自定义字段（避免与 setData 混淆）。
const subscriptions = new WeakMap<object, Array<() => void>>()

Component({
  // 允许页面往顶栏右侧插内容（私信页的「全部/未读」胶囊就挂在这里）。
  options: { multipleSlots: true },
  properties: {
    searchMode: { type: Boolean, value: false }, query: { type: String, value: '' },
    title: { type: String, value: '' }, badge: { type: String, value: '' },
    home: { type: Boolean, value: false }, search: { type: Boolean, value: false },
    action: { type: String, value: '' }, compact: { type: Boolean, value: false },
    // left：标题靠左、不显示头像按钮（Link AI 这类「品牌页」顶栏的口径）。
    left: { type: Boolean, value: false },
    monochrome: { type: Boolean, value: false }, logoSrc: { type: String, value: '' },
    textOnly: { type: Boolean, value: false },
  },
  data: { statusHeight: 47, navigationHeight: 44, rightSpace: 104, avatar: '', initial: '我', loggedIn: true },
  lifetimes: {
    attached() {
      try {
        const info = wx.getWindowInfo(), menu = wx.getMenuButtonBoundingClientRect()
        const statusHeight = info.statusBarHeight || 47
        const valid = menu.height > 0 && menu.top >= statusHeight
        this.setData({
          statusHeight,
          navigationHeight: valid ? Math.max(44, (menu.top - statusHeight) * 2 + menu.height) : 44,
          rightSpace: valid ? menuRightSpace(info.windowWidth, menu.left) : 104,
        })
      } catch { /* 取不到系统几何时保持默认值。 */ }
      // 挂载时立即读一次身份：登录/退出常发生在当前页面内（账号抽屉、访客弹窗），
      // 不会触发 pageLifetimes.show，只靠 show 会让顶栏一直停留在旧的访客占位。
      this.refresh()
      const disposers: Array<() => void> = [onIdentityChange(() => this.refresh())]
      // 真机模式由 ApiClient 的会话变更（setMe / clear）驱动刷新。
      try { disposers.push(client(wx).onSessionChange(() => this.refresh())) } catch { /* 演示模式下会话不可用。 */ }
      subscriptions.set(this, disposers)
    },
    detached() {
      subscriptions.get(this)?.forEach(dispose => dispose())
      subscriptions.delete(this)
    },
  },
  pageLifetimes: {
    show() { this.refresh() },
  },
  methods: {
    /** 头像是当前用户标识：后端会话优先，其次本地身份。两者都没有才用「首字 + 品牌红底」。 */
    refresh() {
      let avatar = '', nickname = '我', loggedIn = true
      // 本地演示模式不认后端会话的旧头像地址（服务未启动时会拉成破图）。
      if ((wx as Runtime).__fixtureMode !== true) {
        try {
          const api = client(wx)
          if (api.me) { const view = profileView(api.me); avatar = view.avatar || ''; nickname = view.nickname || nickname }
          loggedIn = !!api.loggedIn
        } catch { /* 无会话时继续读本地身份。 */ }
      }
      if (!avatar) {
        try { const local = localIdentity(wx); avatar = local.avatar || ''; nickname = local.nickname || nickname }
        catch { /* 读取失败时保留占位字。 */ }
      }
      // 本地演示模式按登录开关判断：访客在页头显示灰色人形占位。
      if ((wx as Runtime).__fixtureMode === true) {
        try { loggedIn = readLocalSession(wx) } catch { loggedIn = true }
      }
      this.setData({ avatar: loggedIn ? avatar : '', loggedIn, initial: avatarInitial(nickname) })
    },
    openDrawer() { this.triggerEvent('profilemenu', {}, { bubbles: true, composed: true }) },
    onInput(e: WechatMiniprogram.CustomEvent<{ value: string }>) { this.triggerEvent('input', e.detail) },
    onSubmit() { this.triggerEvent('submit') },
    onFocus() { this.triggerEvent('focus') },
    onSearch() { this.triggerEvent('search') },
    onAction() { this.triggerEvent('action') },
    // 头像地址失效时退回「首字 + 品牌红底」，与设计稿的红色圆形头像一致。
    onAvatarError() { this.setData({ avatar: '' }) },
  },
})

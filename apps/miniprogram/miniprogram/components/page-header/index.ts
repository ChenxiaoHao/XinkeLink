import { getViewerRole } from '../../features/profile/viewer'
import { menuRightSpace } from '../../utils/layout'
import { client, Runtime } from '../../backend/client'
import { sessionAvatar } from '../../backend/preview'
import { imageDue } from '../../backend/image-urls'
import { localIdentity } from '../../features/profile/repository'
import { onIdentityChange } from '../../features/profile/identity-events'
import { avatarInitial } from '../../utils/presentation'

// 取消订阅的函数放在模块级 WeakMap，不往组件实例上挂自定义字段（避免与 setData 混淆）。
const subscriptions = new WeakMap<object, Array<() => void>>()
const avatarLoads = new WeakMap<object, object>()

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
  data: { statusHeight: 47, navigationHeight: 44, rightSpace: 104, avatar: '', initial: '我', loggedIn: false },
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
      avatarLoads.delete(this)
    },
  },
  pageLifetimes: {
    show() { this.refresh() },
  },
  methods: {
    /** 头像是当前用户标识：后端会话优先，其次本地身份。两者都没有才用「首字 + 品牌红底」。 */
    refresh() {
      const load = {}
      avatarLoads.set(this, load)
      const loggedIn = getViewerRole(wx) !== 'guest'
      let avatar = '', nickname = '我', renewal: Promise<string> | undefined
      if (loggedIn) {
        try {
          if ((wx as Runtime).__fixtureMode === true) {
            const identity = localIdentity(wx)
            avatar = identity.avatar || ''; nickname = identity.nickname || nickname
          } else {
            const api = client(wx), prepared = sessionAvatar(api)
            avatar = prepared.url; renewal = prepared.renewal; nickname = api.me?.nickname || nickname
          }
        } catch { /* 身份资料不可读时保留占位。 */ }
      }
      this.setData({ avatar: loggedIn ? avatar : '', loggedIn, initial: avatarInitial(nickname) })
      return renewal?.then(url => {
        if (url && avatarLoads.get(this) === load) this.setData({ avatar: url })
      })
    },
    openDrawer() { this.triggerEvent('profilemenu', {}, { bubbles: true, composed: true }) },
    onInput(e: WechatMiniprogram.CustomEvent<{ value: string }>) { this.triggerEvent('input', e.detail) },
    onSubmit() { this.triggerEvent('submit') },
    onFocus() { this.triggerEvent('focus') },
    onSearch() { this.triggerEvent('search') },
    onAction() { this.triggerEvent('action') },
    // 头像地址失效时退回「首字 + 品牌红底」，与设计稿的红色圆形头像一致。
    onAvatarError(event?: WechatMiniprogram.CustomEvent) {
      const source = event?.currentTarget.dataset.source
      if (source && source !== this.data.avatar) return
      if (imageDue(this.data.avatar)) { void this.refresh(); return }
      this.setData({ avatar: '' })
    },
  },
})

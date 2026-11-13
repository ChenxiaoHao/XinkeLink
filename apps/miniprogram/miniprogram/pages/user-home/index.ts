import { registerPage } from '../../backend/pages'
import { getPublicProfile, PublicProfile } from '../../features/profile/content'
import { readLocalSession, requireLocalSession } from '../../features/profile/repository'
import { togglePostInteraction } from '../../features/forum/repository'
import { PostInteraction } from '../../features/forum/types'
import { menuRightSpace } from '../../utils/layout'
registerPage(wx, Page, 'user-home')({
  data: { profile: null as PublicProfile | null, loadFailed: false, loggedIn: true, statusHeight: 20, rightSpace: 104 },
  _id: 'local-student',
  onLoad(options: Record<string, string | undefined>) { this._id = options.id || 'local-student'; this.readLayout(); this.reload() },
  onShow() { this.reload() },
  // 封面是整屏底色，导航浮层要自己避让状态栏与微信胶囊。
  readLayout() {
    try {
      const info = wx.getWindowInfo(), menu = wx.getMenuButtonBoundingClientRect(), statusHeight = info.statusBarHeight || 20
      const valid = menu.height > 0 && menu.top >= statusHeight
      this.setData({ statusHeight, rightSpace: valid ? menuRightSpace(info.windowWidth, menu.left) : 104 })
    } catch { /* 取不到系统几何时保持默认值。 */ }
  },
  reload() {
    try { this.setData({ profile: getPublicProfile(wx, this._id), loggedIn: readLocalSession(wx), loadFailed: false }) }
    catch { this.setData({ profile: null, loadFailed: true }) }
  },
  onPullDownRefresh() { try { this.reload() } finally { wx.stopPullDownRefresh() } },
  onBack() { wx.navigateBack({ delta: 1, fail() { wx.switchTab({ url: '/pages/forum/index' }) } }) },
  onRemoteAvatarError() {
    const profile = this.data.profile
    if (profile && profile.avatar !== '/assets/icons/user-round-muted.png') this.setData({ 'profile.avatar': '/assets/icons/user-round-muted.png' })
  },
  onMessageTap() {
    const profile = this.data.profile
    if (profile && !profile.isSelf && requireLocalSession(wx)) wx.navigateTo({ url: '/pages/contact-chat/index?peerId=' + encodeURIComponent(profile.id) })
  },
  // 只有登录的自己才能编辑资料；访客即使进了这个页面也会被弹窗拦下。
  onEditTap() { if (this.data.profile?.isSelf && requireLocalSession(wx)) wx.navigateTo({ url: '/pages/profile-edit/index' }) },
  onOpenPost(event: WechatMiniprogram.CustomEvent<{ id: string }>) {
    if (this.data.profile?.posts.some(post => post.id === event.detail.id)) wx.navigateTo({ url: '/pages/forum-detail/index?id=' + encodeURIComponent(event.detail.id) })
  },
  saveInteraction(id: string, field: keyof PostInteraction) {
    if (!requireLocalSession(wx) || !this.data.profile?.posts.some(post => post.id === id)) return
    try { togglePostInteraction(wx, id, field); this.reload() }
    catch { wx.showToast({ title: '操作未保存，请重试', icon: 'none' }) }
  },
  onLikePost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { this.saveInteraction(event.detail.id, 'liked') },
  onRepostPost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { this.saveInteraction(event.detail.id, 'reposted') },
  onCollectPost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { this.saveInteraction(event.detail.id, 'collected') },
  onShareAppMessage(event: WechatMiniprogram.Page.IShareAppMessageOption) {
    const post = this.data.profile?.posts.find(item => item.id === event.target?.dataset.postId)
    return { title: post?.content.slice(0, 50) ?? '信科 Link · 个人主页', path: post ? '/pages/forum-detail/index?id=' + encodeURIComponent(post.id) : '/pages/user-home/index?id=' + encodeURIComponent(this._id) }
  },
})

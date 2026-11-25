import { registerPage } from '../../backend/pages'
import { getLikedPosts, getProfileSummary, getPublicPosts } from '../../features/profile/content'
import { requireLocalSession, setLocalSession } from '../../features/profile/repository'
import { getViewerRole } from '../../features/profile/viewer'
import { togglePostInteraction } from '../../features/forum/repository'
import { menuRightSpace } from '../../utils/layout'
import { ForumPostView } from '../../features/forum/types'

const guestProfile = () => ({ version: 1 as const, nickname: '访客', avatar: '/assets/icons/user-round-muted.png', bio: '', major: '', className: '', studentId: '', year: '' })

registerPage(wx, Page, 'profile')({
  data: {
    profile: guestProfile(),
    posts: [] as ForumPostView[], likedPosts: [] as ForumPostView[], activeTab: 'posts' as 'posts' | 'liked',
    postCount: 0, collectionCount: 0, likeCount: 0,
    loadFailed: false, loggedIn: false, loggingIn: false,
    statusHeight: 20, rightSpace: 104,
  },
  onShow() {
    this.getTabBar()?.setData({ activeKey: 'profile', publishVisible: false })
    this.readLayout()
    this.reload()
  },
  // 封面是整屏底色，导航浮层要自己避让状态栏与微信胶囊。
  readLayout() {
    try {
      const info = wx.getWindowInfo(), menu = wx.getMenuButtonBoundingClientRect(), statusHeight = info.statusBarHeight || 20
      const valid = menu.height > 0 && menu.top >= statusHeight
      this.setData({ statusHeight, rightSpace: valid ? menuRightSpace(info.windowWidth, menu.left) : 104 })
    } catch { /* 取不到系统几何时保持默认值。 */ }
  },
  reload() {
    if (getViewerRole(wx) === 'guest') {
      this.setData({ profile: guestProfile(), posts: [], likedPosts: [], activeTab: 'posts', postCount: 0, collectionCount: 0, likeCount: 0, loggedIn: false, loadFailed: false })
      wx.redirectTo?.({ url: '/pages/user-home/index?id=local-student' })
      return
    }
    try {
      const summary = getProfileSummary(wx)
      this.setData({ ...summary, posts: getPublicPosts(wx), likedPosts: getLikedPosts(wx), loggedIn: true, loadFailed: false })
    } catch { this.setData({ profile: guestProfile(), posts: [], likedPosts: [], postCount: 0, collectionCount: 0, likeCount: 0, loadFailed: true }) }
  },
  onHide() { this.getTabBar()?.setData({ publishVisible: false }) },
  onRemoteAvatarError() {
    const profile = this.data.profile
    if (profile.avatar !== '/assets/icons/user-round-muted.png') this.setData({ 'profile.avatar': '/assets/icons/user-round-muted.png' })
  },
  onTabTap(event: WechatMiniprogram.TouchEvent) {
    if (!requireLocalSession(wx)) return
    const tab = event.currentTarget.dataset.tab
    if (tab === 'posts' || tab === 'liked') this.setData({ activeTab: tab })
  },
  onOpenPost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { wx.navigateTo({ url: '/pages/forum-detail/index?id=' + encodeURIComponent(event.detail.id) }) },
  onLikePost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { this.saveInteraction(event.detail.id, 'liked') },
  onRepostPost() { wx.showToast({ title: '转发功能尚未开放', icon: 'none' }) },
  onCollectPost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { this.saveInteraction(event.detail.id, 'collected') },
  saveInteraction(id: string, field: 'liked' | 'collected' | 'reposted') {
    if (!requireLocalSession(wx)) return
    togglePostInteraction(wx, id, field)
    this.reload()
  },
  onBack() { wx.navigateBack({ delta: 1, fail() { wx.switchTab({ url: '/pages/forum/index' }) } }) },
  onMoreTap() {
    if (!requireLocalSession(wx)) return
    const items = ['编辑个人资料', '我的帖子', '我的点赞', '意见反馈', '关于信科 Link']
    wx.showActionSheet({ itemList: items, success: result => {
      if (result.tapIndex === 0) this.onEditTap()
      if (result.tapIndex === 1) this.onMyPostsTap()
      if (result.tapIndex === 2) this.onCollectionsTap()
      if (result.tapIndex === 3) this.onSettingsTap()
      if (result.tapIndex === 4) this.onAboutTap()
    }, fail: () => { /* 取消不提示 */ } })
  },
  onMyPostsTap() { if (requireLocalSession(wx)) wx.navigateTo({ url: '/pages/my-posts/index' }) },
  onCollectionsTap() { if (requireLocalSession(wx)) wx.navigateTo({ url: '/pages/my-collections/index' }) },
  onEditTap() { if (requireLocalSession(wx)) wx.navigateTo({ url: '/pages/profile-edit/index' }) },
  onSettingsTap() { wx.navigateTo({ url: '/pages/feedback/index' }) },
  onHomeTap() { if (!this.data.loadFailed) wx.navigateTo({ url: '/pages/user-home/index?id=local-student' }) },
  onLoginTap() { setLocalSession(wx, true); this.reload() },
  onShareAppMessage() { return { title: (this.data.profile.nickname || '我') + '的主页 · 信科 Link', path: '/pages/user-home/index?id=local-student' } },
  onAboutTap() { wx.showModal({ title: '关于信科 Link', content: '连接校园里的每一种生活。当前为 V1 本地演示，资料、发布和收藏保存在当前设备。专业、班级和入学年份由用户自填，未经平台核实。学号仅保存在本机，不展示在公开主页。', showCancel: false, confirmText: '知道了' }) },
})

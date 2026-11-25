import { registerPage } from '../../backend/pages'
import { queryFeed, normalizeInteractions } from '../../features/forum/feed'
import { MOCK_POSTS } from '../../features/forum/mock-posts'
import { readForum, togglePostInteraction } from '../../features/forum/repository'
import { requireLocalSession } from '../../features/profile/repository'
import { FORUM_CATEGORIES, FeedCategory, ForumPostView, MockPost, PostInteraction } from '../../features/forum/types'

const PAGE_SIZE = 2
const ANNOUNCEMENT = '信科 Link 1.2 版本上线通知，支持同学们分享校园生活、交流学习与互助。欢迎来到属于我们的校园社区。'

registerPage(wx, Page, 'forum')({
  data: {
    categories: FORUM_CATEGORIES, activeCategory: 'recommended' as FeedCategory, activeLabel: FORUM_CATEGORIES[0].label,
    posts: [] as ForumPostView[], nextOffset: 0, hasMore: true,
    searchKeyword: '', focusPostId: '', announcement: ANNOUNCEMENT, loadFailed: false,
  },
  _interactions: normalizeInteractions(undefined),
  _posts: [...MOCK_POSTS] as MockPost[],
  readSnapshot(): boolean {
    try {
      const snapshot = readForum(wx)
      this._posts = snapshot.posts; this._interactions = snapshot.interactions
      this.setData({ loadFailed: false }); return true
    } catch { this.setData({ loadFailed: true }); return false }
  },
  onLoad(options: Record<string, string | undefined>) {
    this.readSnapshot()
    const shared = this._posts.find((post: MockPost) => post.id === options.postId)
    this.setData({ activeCategory: 'recommended', searchKeyword: '', focusPostId: shared?.id ?? '' })
    if (shared) {
      const all = queryFeed(this._posts, { category: 'recommended', offset: 0, limit: this._posts.length, keyword: '' }, this._interactions)
      const index = all.items.findIndex(post => post.id === shared.id)
      this.replaceFeed(Math.max(PAGE_SIZE, Math.ceil((index + 1) / PAGE_SIZE) * PAGE_SIZE))
    } else {
      this.replaceFeed()
    }
  },
  onReady() {
    if (!this.data.focusPostId) return
    this.createSelectorQuery().select('.forum-navigation').boundingClientRect(rect => {
      if (!rect || Array.isArray(rect)) return
      wx.pageScrollTo({ selector: '#feed-' + this.data.focusPostId, offsetTop: -rect.height, duration: 0 })
    }).exec()
  },
  onShow() {
    this.getTabBar()?.setData({ activeKey: 'forum', publishVisible: false })
    if (this.readSnapshot()) this.replaceFeed(Math.max(PAGE_SIZE, this.data.posts.length))
  },
  onHide() { this.getTabBar()?.setData({ publishVisible: false }) },
  replaceFeed(limit = PAGE_SIZE) {
    const result = queryFeed(this._posts, {
      category: this.data.activeCategory, offset: 0, limit, keyword: this.data.searchKeyword,
    }, this._interactions)
    this.setData({ posts: result.items, nextOffset: result.nextOffset, hasMore: result.hasMore })
  },
  onCategoryTap(event: { currentTarget: { dataset: { category?: string } } }) {
    const category = event.currentTarget.dataset.category as FeedCategory
    const target = FORUM_CATEGORIES.find(item => item.key === category)
    if (!target || category === this.data.activeCategory) return
    this.setData({ activeCategory: category, activeLabel: target.label })
    this.replaceFeed()
    wx.pageScrollTo?.({ scrollTop: 0, duration: 0 })
  },
  onReachBottom() {
    if (!this.data.hasMore) return
    const result = queryFeed(this._posts, {
      category: this.data.activeCategory, offset: this.data.nextOffset, limit: PAGE_SIZE, keyword: this.data.searchKeyword,
    }, this._interactions)
    this.setData({ posts: [...this.data.posts, ...result.items], nextOffset: result.nextOffset, hasMore: result.hasMore })
  },
  onPullDownRefresh() {
    try { if (this.readSnapshot()) this.replaceFeed() } finally { wx.stopPullDownRefresh() }
  },
  saveInteraction(id: string, field: keyof PostInteraction) {
    if (!requireLocalSession(wx)) return
    try {
      togglePostInteraction(wx, id, field)
      this.readSnapshot()
      this.replaceFeed(Math.max(PAGE_SIZE, this.data.posts.length))
    } catch {
      wx.showToast({ title: '操作未保存，请重试', icon: 'none' })
    }
  },
  onLikePost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { this.saveInteraction(event.detail.id, 'liked') },
  onRepostPost() { wx.showToast({ title: '转发功能尚未开放', icon: 'none' }) },
  onCollectPost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { this.saveInteraction(event.detail.id, 'collected') },
  onOpenPost(event: WechatMiniprogram.CustomEvent<{ id: string }>) {
    if (!this._posts.some((post: MockPost) => post.id === event.detail.id)) return
    wx.navigateTo({ url: '/pages/forum-detail/index?id=' + encodeURIComponent(event.detail.id), fail() { wx.showToast({ title: '详情暂时无法打开，请重试', icon: 'none' }) } })
  },
  onSearchTap() {
    this.setData({ searchKeyword: '' })
    this.replaceFeed()
    wx.switchTab({ url: '/pages/search/index', fail() { wx.showToast({ title: '搜索暂时无法打开，请重试', icon: 'none' }) } })
  },
  onForumFilterTap() {
    wx.showActionSheet({
      itemList: this.data.categories.map(item => item.label),
      success: result => {
        const category = this.data.categories[result.tapIndex]
        if (category) this.onCategoryTap({ currentTarget: { dataset: { category: category.key } } })
      },
      fail: result => { if (!result.errMsg.includes('cancel')) wx.showToast({ title: '暂时无法打开筛选，请重试', icon: 'none' }) },
    })
  },
  onAnnouncementTap() {
    wx.showModal({ title: '平台公告', content: ANNOUNCEMENT, showCancel: false, confirmText: '知道了' })
  },
  onShareAppMessage(event: WechatMiniprogram.Page.IShareAppMessageOption) {
    const post = this._posts.find((item: MockPost) => item.id === event.target?.dataset.postId)
    return {
      title: post ? post.content.slice(0, 50) : '信科 Link · 发现校园动态',
      path: post ? '/pages/forum-detail/index?id=' + encodeURIComponent(post.id) : '/pages/forum/index',
    }
  },
})

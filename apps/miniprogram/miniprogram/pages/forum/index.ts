import { queryFeed, normalizeInteractions, toggleInteraction } from '../../features/forum/feed'
import { MOCK_POSTS } from '../../features/forum/mock-posts'
import { FORUM_CATEGORIES, FeedCategory, ForumPostView, PostInteraction } from '../../features/forum/types'

const PAGE_SIZE = 2
const STORAGE_KEY = 'xinkelink.forum.interactions.v1'
const ANNOUNCEMENT = '信科 Link 1.2 版本上线通知，支持同学们分享校园生活、交流学习与互助。欢迎来到属于我们的校园社区。'

Page({
  data: {
    categories: FORUM_CATEGORIES, activeCategory: 'recommended' as FeedCategory,
    posts: [] as ForumPostView[], nextOffset: 0, hasMore: true,
    searchKeyword: '', focusPostId: '', announcement: ANNOUNCEMENT,
  },
  _interactions: normalizeInteractions(undefined),
  onLoad(options: Record<string, string | undefined>) {
    try {
      this._interactions = normalizeInteractions(wx.getStorageSync(STORAGE_KEY))
    } catch {
      this._interactions = normalizeInteractions(undefined)
    }
    const shared = MOCK_POSTS.find(post => post.id === options.postId)
    this.setData({ activeCategory: 'recommended', searchKeyword: '', focusPostId: shared?.id ?? '' })
    if (shared) {
      const all = queryFeed(MOCK_POSTS, { category: 'recommended', offset: 0, limit: MOCK_POSTS.length, keyword: '' }, this._interactions)
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
  onShow() { this.getTabBar()?.setData({ activeKey: 'forum', publishVisible: false }) },
  onHide() { this.getTabBar()?.setData({ publishVisible: false }) },
  replaceFeed(limit = PAGE_SIZE) {
    const result = queryFeed(MOCK_POSTS, {
      category: this.data.activeCategory, offset: 0, limit, keyword: this.data.searchKeyword,
    }, this._interactions)
    this.setData({ posts: result.items, nextOffset: result.nextOffset, hasMore: result.hasMore })
  },
  onCategoryTap(event: WechatMiniprogram.TouchEvent) {
    const category = event.currentTarget.dataset.category as FeedCategory
    if (!FORUM_CATEGORIES.some(item => item.key === category) || category === this.data.activeCategory) return
    this.setData({ activeCategory: category })
    this.replaceFeed()
    wx.pageScrollTo?.({ scrollTop: 0, duration: 0 })
  },
  onReachBottom() {
    if (!this.data.hasMore) return
    const result = queryFeed(MOCK_POSTS, {
      category: this.data.activeCategory, offset: this.data.nextOffset, limit: PAGE_SIZE, keyword: this.data.searchKeyword,
    }, this._interactions)
    this.setData({ posts: [...this.data.posts, ...result.items], nextOffset: result.nextOffset, hasMore: result.hasMore })
  },
  onPullDownRefresh() {
    try { this.replaceFeed() } finally { wx.stopPullDownRefresh() }
  },
  saveInteraction(id: string, field: keyof PostInteraction) {
    const next = toggleInteraction(MOCK_POSTS, this._interactions, id, field)
    if (next === this._interactions) return
    try {
      wx.setStorageSync(STORAGE_KEY, next)
      this._interactions = next
      this.replaceFeed(Math.max(PAGE_SIZE, this.data.posts.length))
    } catch {
      wx.showToast({ title: '操作未保存，请重试', icon: 'none' })
    }
  },
  onLikePost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { this.saveInteraction(event.detail.id, 'liked') },
  onCollectPost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { this.saveInteraction(event.detail.id, 'collected') },
  onSearchTap() {
    wx.showModal({
      title: '搜索校园动态', editable: true, placeholderText: '输入关键词，留空显示全部',
      content: this.data.searchKeyword, confirmText: '搜索',
      success: result => {
        if (!result.confirm) return
        this.setData({ searchKeyword: (result.content ?? '').trim(), activeCategory: 'recommended' })
        this.replaceFeed()
      },
    })
  },
  onAnnouncementTap() {
    wx.showModal({ title: '平台公告', content: ANNOUNCEMENT, showCancel: false, confirmText: '知道了' })
  },
  onShareAppMessage(event: WechatMiniprogram.Page.IShareAppMessageOption) {
    const post = MOCK_POSTS.find(item => item.id === event.target?.dataset.postId)
    return {
      title: post ? post.content.slice(0, 50) : '信科 Link · 发现校园动态',
      path: '/pages/forum/index' + (post ? '?postId=' + post.id : ''),
    }
  },
})

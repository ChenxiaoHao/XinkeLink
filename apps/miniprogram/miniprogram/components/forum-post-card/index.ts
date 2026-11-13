import { ForumPostView } from '../../features/forum/types'
import { previewForumImages } from '../../features/forum/media'
import { imagePadding } from '../../features/media/layout'
import { loadAuthorizedLocalImage, needsPreviewRefresh } from '../../backend/preview'
import { client, Runtime } from '../../backend/client'
import { readLocalSession } from '../../features/profile/repository'

// 访客没有「我的主页」：本地演示读登录开关，真机读会话状态。读不到时按登录处理，保持原有行为。
function loggedInNow(): boolean {
  try {
    if ((wx as Runtime).__fixtureMode === true) return readLocalSession(wx)
    return !!client(wx).loggedIn
  } catch { return true }
}

Component({
  properties: { post: { type: Object, value: null }, detail: { type: Boolean, value: false }, home: { type: Boolean, value: false }, secondary: { type: Boolean, value: false } },
  data: { singleImagePadding: 75, imageKey: '', postId: '', imageFailures: {} as Record<string, boolean>, imageRenewalAt: {} as Record<string, number> },
  observers: {
    post(post: ForumPostView | null) {
      const imageKey = post ? post.id + ':' + post.images.join('|') : ''
      if (imageKey !== this.data.imageKey) {
        const changedPost = (post?.id || '') !== this.data.postId
        this.setData({ imageKey, postId: post?.id || '', singleImagePadding: post?.singleImagePadding || 75,
          ...(changedPost ? { imageFailures: {}, imageRenewalAt: {} } : {}) })
      }
    },
  },
  methods: {
    async onAvatarError(event: WechatMiniprogram.CustomEvent) {
      const post = this.data.post as ForumPostView | null, source = event.currentTarget.dataset.source
      if (!post || post.avatar !== source) return
      if (source === '/assets/icons/user-round-muted.png') return
      try {
        const avatar = needsPreviewRefresh(wx, [source]) ? await loadAuthorizedLocalImage(wx, source) : '/assets/icons/user-round-muted.png'
        const current = this.data.post as ForumPostView | null
        if (current?.id === post.id && current.avatar === source) this.setData({ post: { ...current, avatar } })
      } catch {
        const current = this.data.post as ForumPostView | null
        if (current?.id === post.id && current.avatar === source) this.setData({ post: { ...current, avatar: '/assets/icons/user-round-muted.png' } })
      }
    },
    onImageLoad(event: WechatMiniprogram.CustomEvent<{ width: number; height: number }>) {
      const post = this.data.post as ForumPostView | null, source = event.currentTarget.dataset.source
      if (!post?.images.includes(source)) return
      this.setData({ imageFailures: { ...this.data.imageFailures, [source]: false },
        ...(this.data.home && post.images.length === 1 ? { singleImagePadding: imagePadding(event.detail.width, event.detail.height) } : {}) })
    },
    async onImageError(event: WechatMiniprogram.CustomEvent) {
      const post = this.data.post as ForumPostView | null, source = event.currentTarget.dataset.source
      const index = Number(event.currentTarget.dataset.index ?? post?.images.indexOf(source))
      if (!post?.images.includes(source)) return
      this.setData({ imageFailures: { ...this.data.imageFailures, [source]: true } })
      if (!Number.isInteger(index) || index < 0 || !needsPreviewRefresh(wx, [source])) return
      const retryKey = post.id + ':' + index + ':' + source, now = Date.now()
      if (now - (this.data.imageRenewalAt[retryKey] || 0) < 30000) return
      this.setData({ imageRenewalAt: { ...this.data.imageRenewalAt, [retryKey]: now } })
      try {
        const localPath = await loadAuthorizedLocalImage(wx, source)
        const current = this.data.post as ForumPostView | null
        if (current?.id !== post.id || current.images[index] !== source) return
        const images = [...current.images]; images[index] = localPath
        this.setData({ post: { ...current, images }, imageFailures: { ...this.data.imageFailures, [localPath]: false } })
      } catch { /* 续期失败时保留占位，不伪装成图片已加载。 */ }
    },
    stopTap() {},
    onAuthorTap() {
      const post = this.data.post as ForumPostView | null
      if (!post?.authorId || post.isAnonymous) { this.onOpenTap(); return }
      // 登录时自己的帖子点头像 → 我的主页（动态/获赞 + 帖子·喜欢双 tab）；
      // 访客与别人的帖子都走「公开主页」：干净的公开视图，只有帖子一个 tab。
      if (post.isMine && loggedInNow()) {
        const stack = typeof getCurrentPages === 'function' ? getCurrentPages() : []
        if (stack[stack.length - 1]?.route !== 'pages/profile/index') wx.navigateTo({ url: '/pages/profile/index' })
        return
      }
      wx.navigateTo({ url: '/pages/user-home/index?id=' + encodeURIComponent(post.authorId) })
    },
    onOpenTap() {
      const post = this.data.post as ForumPostView | null
      if (post && !this.data.detail) this.triggerEvent('open', { id: post.id })
    },
    onReplyTap() {
      const post = this.data.post as ForumPostView | null
      if (this.data.detail) { this.triggerEvent('reply'); return }
      if (post) wx.navigateTo({ url: '/pages/forum-detail/index?id=' + encodeURIComponent(post.id) + '&reply=1' })
    },
    onRepostTap() {
      const post = this.data.post as ForumPostView | null
      if (post) this.triggerEvent('repost', { id: post.id })
    },
    onLikeTap() {
      const post = this.data.post as ForumPostView | null
      if (post) this.triggerEvent('like', { id: post.id })
    },
    onCollectTap() {
      const post = this.data.post as ForumPostView | null
      if (post) this.triggerEvent('collect', { id: post.id })
    },
    onMoreTap() {
      const post = this.data.post as ForumPostView | null
      if (!post) return
      // 书签从动作行收进这里：卡片只保留评论 / 转发 / 点赞三项。
      const bookmark = post.collected ? '移除书签' : '加入书签'
      if (post.isMine) {
        wx.showActionSheet({ itemList: ['编辑帖子', bookmark, '举报这条内容'], success: result => {
          if (result.tapIndex === 1) { this.onCollectTap(); return }
          wx.navigateTo({ url: result.tapIndex === 0 ? '/pages/forum-publish/index?editId=' + encodeURIComponent(post.id) : '/pages/report/index?kind=post&id=' + encodeURIComponent(post.id) })
        } })
        return
      }
      wx.showActionSheet({ itemList: [bookmark, '举报这条内容'], success: result => {
        if (result.tapIndex === 0) { this.onCollectTap(); return }
        wx.navigateTo({ url: '/pages/report/index?kind=post&id=' + encodeURIComponent(post.id), fail() { wx.showToast({ title: '举报页面暂时无法打开，请重试', icon: 'none' }) } })
      }, fail: () => { /* 取消不提示 */ } })
    },
    onImageTap(event: WechatMiniprogram.TouchEvent) {
      const post = this.data.post as ForumPostView | null
      if (post) previewForumImages(wx, post.images, Number(event.currentTarget.dataset.index))
    },
  },
})

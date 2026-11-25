import { ForumPostView } from '../../features/forum/types'
import { previewForumImages } from '../../features/forum/media'
import { imagePadding, naturalImagePadding } from '../../features/media/layout'
import { imageForDisplay, loadAuthorizedLocalImage, needsPreviewRefresh } from '../../backend/preview'
import { backendImageId } from '../../backend/image-urls'

import { getViewerRole } from '../../features/profile/viewer'
import { client, Runtime } from '../../backend/client'
import { deleteOwnPost } from '../../features/forum/repository'

type ImagePreparation = { owner?: string; observer?: WechatMiniprogram.IntersectionObserver; started?: boolean }
const imagePreparations = new WeakMap<object, ImagePreparation>()

Component({
  properties: { post: { type: Object, value: null }, detail: { type: Boolean, value: false }, home: { type: Boolean, value: false }, secondary: { type: Boolean, value: false } },
  data: { deleting: false, singleImagePadding: 75, imageKey: '', postId: '', imageSources: null as string[] | null,
    imageFailures: {} as Record<string, boolean>, imageRenewalAt: {} as Record<string, number> },
  lifetimes: {
    ready() { this.watchPostImages() },
    detached() { this.cancelPostImages() },
  },
  observers: {
    post(post: ForumPostView | null) {
      const imageKey = post ? post.id + ':' + post.images.join('|') : ''
      if (imageKey !== this.data.imageKey) {
        this.cancelPostImages()
        const changedPost = (post?.id || '') !== this.data.postId
        let imageSources: string[] | null = null
        if (post && needsPreviewRefresh(wx, post.images)) {
          imagePreparations.set(this, { owner: client(wx).me?.id })
          imageSources = post.images.map(source => backendImageId(source) ? '' : source)
        }
        this.setData({ imageKey, postId: post?.id || '', singleImagePadding: post?.singleImagePadding || 75,
          imageSources, ...(changedPost ? { imageFailures: {}, imageRenewalAt: {} } : {}) }, () => this.watchPostImages())
      }
    },
  },
  methods: {
    cancelPostImages() {
      imagePreparations.get(this)?.observer?.disconnect()
      imagePreparations.delete(this)
    },
    watchPostImages() {
      const preparation = imagePreparations.get(this)
      if (!preparation || preparation.observer || preparation.started) return
      const start = () => {
        preparation.observer?.disconnect()
        preparation.observer = undefined
        void this.preparePostImages(preparation)
      }
      if (typeof this.createIntersectionObserver === 'function') {
        for (const options of [{ nativeMode: true }, {}]) {
          try {
            preparation.observer = this.createIntersectionObserver(options)
            preparation.observer.relativeToViewport({ top: 160, bottom: 160 }).observe('.post-images', result => {
              if (result.intersectionRatio > 0) start()
            })
            return
          } catch {
            preparation.observer?.disconnect()
            preparation.observer = undefined
          }
        }
      }
      start()
    },
    async preparePostImages(preparation: ImagePreparation) {
      const current = () => imagePreparations.get(this) === preparation && client(wx).me?.id === preparation.owner
      const post = this.data.post as ForumPostView | null
      if (!post || preparation.started || !current()) return
      preparation.started = true
      const imageSources = await Promise.all(post.images.map(source => imageForDisplay(wx, source).catch(() => '')))
      if (!current()) return
      const imageFailures = { ...this.data.imageFailures }
      imageSources.forEach((source, index) => { if (!source) imageFailures[post.images[index]] = true })
      this.setData({ imageSources, imageFailures })
    },
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
      const images = this.data.imageSources ?? post?.images
      if (!source || !images?.includes(source)) return
      const patch: Partial<typeof this.data> = {}
      if (this.data.imageFailures[source]) patch.imageFailures = { ...this.data.imageFailures, [source]: false }
      if (images.length === 1 && (this.data.home || this.data.detail)) {
        const padding = this.data.home ? imagePadding(event.detail.width, event.detail.height)
          : naturalImagePadding(event.detail.width, event.detail.height)
        if (padding !== this.data.singleImagePadding) patch.singleImagePadding = padding
      }
      if (Object.keys(patch).length) this.setData(patch)
    },
    async onImageError(event: WechatMiniprogram.CustomEvent) {
      const post = this.data.post as ForumPostView | null, source = event.currentTarget.dataset.source
      const images = this.data.imageSources ?? post?.images
      const index = Number(event.currentTarget.dataset.index ?? images?.indexOf(source))
      if (!post || !source || !images?.includes(source)) return
      this.setData({ imageFailures: { ...this.data.imageFailures, [source]: true } })
      if (!Number.isInteger(index) || index < 0 || !needsPreviewRefresh(wx, [source])) return
      const retryKey = post.id + ':' + index + ':' + source, now = Date.now()
      if (now - (this.data.imageRenewalAt[retryKey] || 0) < 30000) return
      this.setData({ imageRenewalAt: { ...this.data.imageRenewalAt, [retryKey]: now } })
      const preparation = imagePreparations.get(this)
      const owner = client(wx).me?.id
      try {
        const localPath = await loadAuthorizedLocalImage(wx, source)
        const current = this.data.post as ForumPostView | null
        if (current?.id !== post.id || imagePreparations.get(this) !== preparation || client(wx).me?.id !== owner) return
        const displayed = this.data.imageSources ?? current.images
        if (displayed[index] !== source) return
        const updated = [...displayed]; updated[index] = localPath
        const imageFailures = { ...this.data.imageFailures, [localPath]: false }
        if (this.data.imageSources) this.setData({ imageSources: updated, imageFailures })
        else this.setData({ post: { ...current, images: updated }, imageFailures })
      } catch { /* 续期失败时保留占位，不伪装成图片已加载。 */ }
    },
    stopTap() {},
    onAuthorTap() {
      const post = this.data.post as ForumPostView | null
      if (!post?.authorId || post.isAnonymous) { this.onOpenTap(); return }
      // 登录时自己的帖子点头像 → 我的主页（动态/获赞 + 帖子·喜欢双 tab）；
      // 访客与别人的帖子都走「公开主页」：干净的公开视图，只有帖子一个 tab。
      if (getViewerRole(wx, post.authorId) === 'self') {
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
      wx.showToast({ title: '转发功能尚未开放', icon: 'none' })
    },
    onLikeTap() {
      const post = this.data.post as ForumPostView | null
      if (post) this.triggerEvent('like', { id: post.id })
    },
    deletePost(post: ForumPostView, owner: string | undefined, fixture: boolean) {
      const api = client(wx)
      const current = () => {
        const selected = this.data.post as ForumPostView | null
        return selected?.id === post.id && selected.isMine && getViewerRole(wx) !== 'guest'
          && ((wx as Runtime).__fixtureMode === true) === fixture && (fixture || api.me?.id === owner)
      }
      if (this.data.deleting || !current()) return
      this.setData({ deleting: true })
      wx.showModal({ title: '删除这条帖子？', content: '删除后会从论坛和个人主页移除，无法恢复。', confirmText: '确认删除', confirmColor: '#C65353',
        success: async result => {
          try {
            if (!result.confirm || !current()) return
            if (fixture) deleteOwnPost(wx, post.id)
            else await api.request('/posts/' + encodeURIComponent(post.id), 'DELETE', undefined, { 'If-Match': '"' + post.version + '"' })
            if (!current()) return
            this.setData({ post: null })
            wx.showToast({ title: '帖子已删除', icon: 'success' })
            const page = getCurrentPages().slice(-1)[0]
            if (this.data.detail) wx.navigateBack({ delta: 1, fail: () => wx.switchTab({ url: '/pages/forum/index' }) })
            else {
              const refresh = page?.reload || page?.onSubmit || page?.onShow
              if (refresh) Promise.resolve(refresh.call(page)).catch(() => wx.showToast({ title: '帖子已删除，请刷新列表', icon: 'none' }))
            }
          } catch { if (current()) wx.showToast({ title: '删除未完成，请刷新后重试', icon: 'none' }) }
          finally { this.setData({ deleting: false }) }
        }, fail: () => this.setData({ deleting: false }),
      })
    },
    onMoreTap() {
      const post = this.data.post as ForumPostView | null
      if (!post) return
      if (post.isMine && getViewerRole(wx) !== 'guest') {
        const owner = client(wx).me?.id, fixture = (wx as Runtime).__fixtureMode === true
        wx.showActionSheet({ itemList: ['编辑帖子', '删除帖子'], success: result => {
          if (getViewerRole(wx) === 'guest') return
          if (result.tapIndex === 1) { this.deletePost(post, owner, fixture); return }
          if (fixture !== ((wx as Runtime).__fixtureMode === true) || (!fixture && client(wx).me?.id !== owner)) return
          wx.navigateTo({ url: '/pages/forum-publish/index?editId=' + encodeURIComponent(post.id) })
        } })
        return
      }
      wx.showActionSheet({ itemList: ['举报这条内容'], success: () => {
        wx.navigateTo({ url: '/pages/report/index?kind=post&id=' + encodeURIComponent(post.id), fail() { wx.showToast({ title: '举报页面暂时无法打开，请重试', icon: 'none' }) } })
      }, fail: () => { /* 取消不提示 */ } })
    },
    onImageTap(event: WechatMiniprogram.TouchEvent) {
      const post = this.data.post as ForumPostView | null
      if (post) previewForumImages(wx, post.images, Number(event.currentTarget.dataset.index))
    },
  },
})

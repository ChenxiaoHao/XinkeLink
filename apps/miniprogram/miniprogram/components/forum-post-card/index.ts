import { ForumPostView } from '../../features/forum/types'

Component({
  properties: { post: { type: Object, value: null } },
  methods: {
    onLikeTap() {
      const post = this.data.post as ForumPostView | null
      if (post) this.triggerEvent('like', { id: post.id })
    },
    onCollectTap() {
      const post = this.data.post as ForumPostView | null
      if (post) this.triggerEvent('collect', { id: post.id })
    },
    onImageTap(event: WechatMiniprogram.TouchEvent) {
      const post = this.data.post as ForumPostView | null
      const index = Number(event.currentTarget.dataset.index)
      if (!post || !post.images[index]) return
      const onFailure = (): void => { wx.showToast({ title: '图片暂时无法预览，请重试', icon: 'none' }) }
      try {
        // The native viewer cannot resolve code-package URLs. Keep a bounded
        // local copy of the six mock photos; remote URLs remain unchanged.
        const urls = post.images.map(source => {
          if (!source.startsWith('/assets/mock/forum/')) return source
          const destination = wx.env.USER_DATA_PATH + '/xlk-forum-preview-' + source.split('/').pop()
          wx.getFileSystemManager().copyFileSync(source, destination)
          return destination
        })
        wx.previewImage({ current: urls[index], urls, fail: onFailure })
      } catch {
        onFailure()
      }
    },
  },
})

import { registerPage } from '../../backend/pages'
import { getMyPosts } from '../../features/profile/content'
import { requireLocalSession } from '../../features/profile/repository'
import { deleteOwnPost, togglePostInteraction } from '../../features/forum/repository'
import { ForumPostView, PostInteraction } from '../../features/forum/types'
registerPage(wx, Page, 'my-posts')({
  data: { posts: [] as ForumPostView[], loadFailed: false, busyId: '', deleting: '' },
  onShow() { this.reload() },
  reload() {
    try { this.setData({ posts: getMyPosts(wx), loadFailed: false }) }
    catch { this.setData({ loadFailed: true }) }
  },
  onPullDownRefresh() { try { this.reload() } finally { wx.stopPullDownRefresh() } },
  onOpenPost(event: WechatMiniprogram.CustomEvent<{ id: string }>) {
    if (!this.data.loadFailed && this.data.posts.some(post => post.id === event.detail.id)) wx.navigateTo({ url: '/pages/forum-detail/index?id=' + encodeURIComponent(event.detail.id) })
  },
  saveInteraction(id: string, field: keyof PostInteraction) {
    if (!requireLocalSession(wx) || this.data.loadFailed || !this.data.posts.some(post => post.id === id)) return
    try { togglePostInteraction(wx, id, field); this.reload() }
    catch { wx.showToast({ title: '操作未保存，请重试', icon: 'none' }) }
  },
  onLikePost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { this.saveInteraction(event.detail.id, 'liked') },
  onRepostPost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { this.saveInteraction(event.detail.id, 'reposted') },
  onCollectPost(event: WechatMiniprogram.CustomEvent<{ id: string }>) { this.saveInteraction(event.detail.id, 'collected') },
  onDeleteTap(event: WechatMiniprogram.TouchEvent) {
    if (!requireLocalSession(wx) || this.data.busyId || this.data.loadFailed) return
    const id = event.currentTarget.dataset.id
    if (!this.data.posts.some(post => post.id === id)) return
    this.setData({ busyId: id })
    wx.showModal({ title: '删除这条帖子？', content: '删除后会从论坛和个人主页移除，无法恢复。', confirmText: '确认删除', confirmColor: '#C65353', success: result => {
      try {
        if (!result.confirm) return
        deleteOwnPost(wx, id); this.reload(); wx.showToast({ title: '帖子已删除', icon: 'success' })
      } catch { wx.showToast({ title: '删除未完成，请重试', icon: 'none' }) }
      finally { this.setData({ busyId: '' }) }
    }, fail: () => { this.setData({ busyId: '' }) } })
  },
  // wxml 的编辑按钮原先在演示模式下没有实现（真机侧才有），点了没反应。
  onEditTap(event: WechatMiniprogram.TouchEvent) {
    const id = event.currentTarget.dataset.id
    if (!id || this.data.loadFailed || !this.data.posts.some(post => post.id === id)) return
    wx.navigateTo({ url: '/pages/forum-publish/index?editId=' + encodeURIComponent(id) })
  },
  onPublishTap() { wx.navigateTo({ url: '/pages/forum-publish/index' }) },
  onShareAppMessage(event: WechatMiniprogram.Page.IShareAppMessageOption) {
    const post = this.data.posts.find(item => item.id === event.target?.dataset.postId)
    return { title: post?.content.slice(0, 50) ?? '信科 Link · 校园动态', path: post ? '/pages/forum-detail/index?id=' + encodeURIComponent(post.id) : '/pages/forum/index' }
  },
})

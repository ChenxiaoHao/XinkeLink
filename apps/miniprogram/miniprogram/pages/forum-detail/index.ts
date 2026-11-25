import { registerPage } from '../../backend/pages'
import { toPostView } from '../../features/forum/feed'
import { previewForumImages } from '../../features/forum/media'
import { addForumComment, deleteOwnForumComment, readForum, toggleForumCommentLike, togglePostInteraction } from '../../features/forum/repository'
import { commentAuthor, commentThreads, CommentThread, CommentView, visibleComments } from '../../features/forum/comments'
import { ForumPostView, PostInteraction } from '../../features/forum/types'
import { requireLocalSession, localIdentity } from '../../features/profile/repository'
import { avatarInitial } from '../../utils/presentation'
import { detailVisit } from '../../features/statistics/views'
registerPage(wx, Page, 'forum-detail')({
  data: { post: null as ForumPostView | null, loadFailed: false, comments: [] as CommentThread[],
    commentDraft: '', composerOpen: false, replyTarget: null as { id: string; name: string } | null,
    // Shared UI state: live overrides only the submission operation below.
    sending: false, sendFailed: false, deleting: false, keyboardHeight: 0, selfAvatar: '', selfInitial: '我',
    // 回复区默认按热度排（设计稿口径），点右侧标签可切回时间序。
    commentOrder: 'hot' as 'hot' | 'time' },
  _postId: '',
  _detailVisible:false,
  _viewVisit:null as ReturnType<typeof detailVisit>|null,
  _expanded: [] as string[], _openedReplies: [] as string[],
  onLoad(options: Record<string, string | undefined>) {
    this._postId = options.id ?? ''
    this._viewVisit=detailVisit(wx,this._postId)
    try { const self = localIdentity(wx); this.setData({ selfAvatar: self.avatar, selfInitial: avatarInitial(self.nickname) }) } catch { /* 头像读不到时留空占位。 */ }
    this.reload()
    if (options.reply === '1' && this.data.post) this.setData({ composerOpen: true })
  },
  onShow() { this._detailVisible=true;this.reload() },
  onHide() {this._detailVisible=false},
  onUnload() {this._detailVisible=false},
  reload() {
    try {
      const snapshot = readForum(wx), post = snapshot.posts.find(item => item.id === this._postId)
      const targetExists = post && visibleComments(post, snapshot.commentState).some(comment => comment.id === this.data.replyTarget?.id)
      const threads = post ? commentThreads(post, snapshot.commentState, this._expanded, this._openedReplies) : []
      if (this.data.commentOrder === 'hot') {
        // 热度 = 本层点赞 + 下层点赞，同分保持原有时间序（stable）。
        const score = (thread: CommentThread) => thread.likeCount + thread.replies.reduce((sum, reply) => sum + reply.likeCount, 0)
        threads.sort((a, b) => score(b) - score(a))
      }
      this.setData({ post: post ? toPostView(post, snapshot.interactions) : null, loadFailed: false,
        comments: threads,
        replyTarget: targetExists ? this.data.replyTarget : null })
      if(this._detailVisible&&post)this._viewVisit?.displayed(true)
    } catch { this.setData({ loadFailed: true }) }
  },
  onToggleOrder() {
    this.setData({ commentOrder: this.data.commentOrder === 'hot' ? 'time' : 'hot' })
    this.reload()
  },
  saveInteraction(field: keyof PostInteraction) {
    if (!requireLocalSession(wx)) return
    if (!this.data.post) return
    try { togglePostInteraction(wx, this._postId, field); this.reload() }
    catch { wx.showToast({ title: '操作未保存，请重试', icon: 'none' }) }
  },
  onLikeTap() { this.saveInteraction('liked') },
  onRepostPost() { wx.showToast({ title: '转发功能尚未开放', icon: 'none' }) },
  onCollectTap() { this.saveInteraction('collected') },
  onImageTap(event: WechatMiniprogram.TouchEvent) {
    if (this.data.post) previewForumImages(wx, this.data.post.images, Number(event.currentTarget.dataset.index))
  },
  onOpenComposer() { if (this.data.post && !this.data.loadFailed) this.setData({ composerOpen: true, sendFailed: false }) },
  onCloseComposer() { this.setData({ composerOpen: false, keyboardHeight: 0 }); wx.hideKeyboard?.() },
  onCancelReply() { this.setData({ replyTarget: null, sendFailed: false }) },
  onKeyboardHeightChange(event: WechatMiniprogram.CustomEvent<{ height: number }>) {
    this.setData({ keyboardHeight: Math.max(0, event.detail.height || 0) })
  },
  onCommentInput(event: WechatMiniprogram.CustomEvent<{ value: string }>) { this.setData({ commentDraft: event.detail.value.slice(0, 1000), sendFailed: false }) },
  onReplyTap(event: WechatMiniprogram.CustomEvent<{ id?: string }>) {
    const id = event.detail?.id ?? event.currentTarget.dataset.id
    try {
      const snapshot = readForum(wx), post = snapshot.posts.find(item => item.id === this._postId)
      const target = post && visibleComments(post, snapshot.commentState).find(comment => comment.id === id)
      if (post && target) this.setData({ composerOpen: true, sendFailed: false, replyTarget: { id: target.id, name: commentAuthor(post, target).name } })
    } catch { wx.showToast({ title: '评论暂时无法读取，请重试', icon: 'none' }) }
  },
  onSubmitComment() {
    if (!requireLocalSession(wx)) return
    if (!this.data.post || this.data.sending) return
    if (!this.data.commentDraft.trim()) { wx.showToast({ title: '请先写点内容吧', icon: 'none' }); return }
    if (this.data.commentDraft.trim().length > 1000) { wx.showToast({ title: '评论需为1至1000字', icon: 'none' }); return }
    this.setData({ sending: true })
    try {
      addForumComment(wx, this._postId, this.data.commentDraft, this.data.replyTarget?.id)
      this.setData({ commentDraft: '', replyTarget: null, composerOpen: false, keyboardHeight: 0, sendFailed: false })
      wx.hideKeyboard?.(); this.reload(); wx.showToast({ title: '评论已发布', icon: 'success' })
    } catch { this.setData({ sendFailed: true }); wx.showToast({ title: '评论未发送，请刷新后重试', icon: 'none' }) }
    finally { this.setData({ sending: false }) }
  },
  onCommentLike(event: WechatMiniprogram.CustomEvent<{ id?: string }>) {
    if (!requireLocalSession(wx)) return
    try { toggleForumCommentLike(wx, this._postId, event.detail?.id ?? event.currentTarget.dataset.id); this.reload() }
    catch { wx.showToast({ title: '点赞未保存，请重试', icon: 'none' }) }
  },
  onExpandComment(event: WechatMiniprogram.CustomEvent<{ id?: string }>) {
    const id = event.detail?.id ?? event.currentTarget.dataset.id
    this._expanded = this._expanded.includes(id) ? this._expanded.filter((value: string) => value !== id) : [...this._expanded, id]
    this.reload()
  },
  onExpandReplies(event: WechatMiniprogram.TouchEvent) {
    const id = event.currentTarget.dataset.id
    this._openedReplies = this._openedReplies.includes(id) ? this._openedReplies.filter((value: string) => value !== id) : [...this._openedReplies, id]
    this.reload()
  },
  onDeleteComment(event: WechatMiniprogram.CustomEvent<{ id?: string }>) {
    if (!requireLocalSession(wx)) return
    if (this.data.deleting) return
    const id = event.detail?.id ?? event.currentTarget.dataset.id
    const all = this.data.comments.flatMap((thread: CommentThread) => [thread, ...thread.replies])
    const target = all.find((comment: CommentView) => comment.id === id)
    if (!target?.isMine) return
    this.setData({ deleting: true })
    wx.showModal({ title: '删除这条评论？', content: '删除后无法恢复；一级评论下的回复也会一起移除。', confirmText: '确认删除', success: result => {
      try { if (result.confirm) { deleteOwnForumComment(wx, this._postId, id); this.reload(); wx.showToast({ title: '评论已删除', icon: 'success' }) } }
      catch { wx.showToast({ title: '删除未完成，请重试', icon: 'none' }) }
      finally { this.setData({ deleting: false }) }
    }, fail: () => this.setData({ deleting: false }) })
  },
  onReturnForum() { wx.switchTab({ url: '/pages/forum/index' }) },
  onShareAppMessage() { return { title: this.data.post?.content.slice(0, 50) ?? '信科 Link · 校园动态', path: this.data.post ? '/pages/forum-detail/index?id=' + encodeURIComponent(this._postId) : '/pages/forum/index' } },
})

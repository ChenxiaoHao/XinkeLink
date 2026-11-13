import { CommentView } from '../../features/forum/comments'
Component({
  properties: { comment: { type: Object, value: null }, compact: { type: Boolean, value: false } },
  methods: {
    emitAction(name: string) { const comment = this.data.comment as CommentView | null; if (comment) this.triggerEvent(name, { id: comment.id }) },
    onReply() { this.emitAction('reply') }, onLike() { this.emitAction('like') },
    onExpand() { this.emitAction('expand') }, onDelete() { this.emitAction('delete') },
    onReport() {
      const comment = this.data.comment as CommentView | null
      if (comment) wx.showActionSheet({ itemList: ['举报评论'], success: () => wx.navigateTo({ url: '/pages/report/index?kind=comment&id=' + encodeURIComponent(comment.id) }) })
    },
  },
})

import { registerPage } from '../../backend/pages'
registerPage(wx, Page, 'drafts')({ data: { postDraft: '', loadFailed: false },
 onShow() { try { const post = wx.getStorageSync('xinkelink.post.draft.v1'); this.setData({ postDraft:post?.content || '', loadFailed:false }) } catch { this.setData({ loadFailed:true }) } },
 onOpenPost() { wx.navigateTo({url:'/pages/forum-publish/index?draft=1'}) },
})
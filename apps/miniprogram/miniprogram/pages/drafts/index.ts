import { registerPage } from '../../backend/pages'
import { readPostDraft } from '../../features/forum/drafts'
import { requireLocalSession } from '../../features/profile/repository'
registerPage(wx, Page, 'drafts')({ data: { postDraft: '', loadFailed: false },
 onShow() { try { const post = readPostDraft(wx); this.setData({ postDraft: post?.content || '', loadFailed: false }) } catch { this.setData({ postDraft: '', loadFailed: true }) } },
 onOpenPost() { if (requireLocalSession(wx)) wx.navigateTo({url:'/pages/forum-publish/index?draft=1'}) },
})

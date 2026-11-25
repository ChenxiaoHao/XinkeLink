import { registerPage } from '../../backend/pages'
import { AiController } from '../../features/ai/controller'
import { AiRepository, AiMessage, AiSession } from '../../features/ai/repository'
import { getViewerRole } from '../../features/profile/viewer'

registerPage(wx, Page, 'ai')({
  data: {
    messages: [] as AiMessage[], sessions: [] as AiSession[], sessionId: '', prompt: '', pendingQuestion: '',
    suggestions: [
      { title: '请假流程', sub: '体验请假条款与例外检索', prompt: '测试样例中的请假流程是什么？' },
      { title: '最近一周热门帖子', sub: '汇总校园动态与失物招领', prompt: '查找最近一周的校园帖子' },
      { title: '奖学金评定', sub: '体验申请条件与材料检索', prompt: '测试样例中的奖学金评定标准是什么？' },
      { title: '图书馆开放时间', sub: '体验平时与期末安排检索', prompt: '测试样例中图书馆期末周几点闭馆？' },
    ],
    keyboardHeight: 0, historyVisible: false, drawerVisible: false, loading: false, busy: false, error: '', guest: true, demo: false,
  },
  _ai: null as AiController | null,
  async onShow() {
    this.getTabBar()?.setData({ activeKey: 'ai', publishVisible: false })
    const repo = new AiRepository(wx)
    this.setData({demo:repo.fixture, guest:getViewerRole(wx)==='guest', drawerVisible: false})
    this._ai ||= new AiController(repo, patch => this.setData(patch))
    await this._ai.start()
  },
  onHide() { this.setData({drawerVisible: false}); this._ai?.pause() },
  onUnload() { this._ai?.stop() },
  onDrawerChange(event: WechatMiniprogram.CustomEvent<{opened?:boolean}>) { this.setData({drawerVisible: Boolean(event.detail?.opened)}) },
  onInput(event: WechatMiniprogram.CustomEvent<{value:string}>) { this.setData({prompt:event.detail.value.slice(0,1000)}) },
  onKeyboardHeightChange(event: WechatMiniprogram.CustomEvent<{height:number}>) { this.setData({keyboardHeight:Math.max(0,event.detail.height||0)}) },
  onSuggestion(event: WechatMiniprogram.TouchEvent) { this.setData({prompt:String(event.currentTarget.dataset.prompt||'')}) },
  onLogin() { return new AiRepository(wx).requireSession() },
  async onSend() { if(getViewerRole(wx)==='guest') { await this.onLogin(); return }; await this._ai?.send(this.data.prompt); try { wx.pageScrollTo({selector:'#ai-bottom',duration:0}) } catch {} },
  onNewChat() { return this._ai?.newSession() },
  async onHistory() { this.setData({historyVisible:!this.data.historyVisible}); if(this.data.historyVisible) await this._ai?.list() },
  onOpenSession(event: WechatMiniprogram.TouchEvent) { return this._ai?.open(String(event.currentTarget.dataset.id||'')) },
  async onDeleteSession(event: WechatMiniprogram.TouchEvent) {
    const id = String(event.currentTarget.dataset.id||''), owner = new AiRepository(wx).identity()
    wx.showModal({title:'删除会话',content:'删除后无法恢复。',success:result=>{if(result.confirm&&owner===new AiRepository(wx).identity()) void this._ai?.remove(id)}})
  },
  onAiRetry() { return this._ai?.retry() },
  onOpenPost(event: WechatMiniprogram.CustomEvent<{id:string}>) {
    if(getViewerRole(wx)==='guest') return
    const id=event.detail.id
    if(this.data.messages.some(m=>m.posts.some(p=>p.id===id))) wx.navigateTo({url:'/pages/forum-detail/index?id='+encodeURIComponent(id)})
  },
  onSource(event: WechatMiniprogram.TouchEvent) {
    if(getViewerRole(wx)==='guest') return
    const url=String(event.currentTarget.dataset.url||'')
    if(/^https:\/\//.test(url)&&this.data.messages.some(m=>m.sources.some(s=>s.url===url))) wx.setClipboardData({data:url})
  },
})

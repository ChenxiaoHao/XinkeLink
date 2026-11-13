import { registerPage } from '../../backend/pages'
import { boardFor, replyTo } from '../../features/ai/draft'

const WELCOME = '我是 Link AI，可以帮你写帖子、把长讨论缩成三句话、润色文案。免费使用，不限次数。'
const SUGGESTIONS = ['总结这条帖子', '帮我把这段话变短', '它适合发到哪个板块', '帮我写个标题']
interface Message { id: string; role: 'ai' | 'me'; text: string; actions: boolean }

registerPage(wx, Page, 'ai')({
  data: { messages: [{ id: 'ai-0', role: 'ai', text: WELCOME, actions: false }] as Message[], prompt: '', suggestions: SUGGESTIONS, keyboardHeight: 0 },
  _seq: 0,
  onShow() { this.getTabBar()?.setData({ activeKey: 'ai', publishVisible: false }) },
  onInput(event: WechatMiniprogram.CustomEvent<{ value: string }>) { this.setData({ prompt: event.detail.value.slice(0, 2000) }) },
  onKeyboardHeightChange(event: WechatMiniprogram.CustomEvent<{ height: number }>) { this.setData({ keyboardHeight: Math.max(0, event.detail.height || 0) }) },
  onSuggestion(event: WechatMiniprogram.TouchEvent) { this.setData({ prompt: String(event.currentTarget.dataset.prompt || '') }) },
  onSend() {
    const prompt = this.data.prompt.trim()
    if (!prompt) return
    const reply = replyTo(prompt), seq = ++this._seq
    const messages: Message[] = [
      ...this.data.messages,
      { id: 'me-' + seq, role: 'me', text: prompt, actions: false },
      { id: 'ai-' + seq, role: 'ai', text: reply.text, actions: reply.postable },
    ]
    this.setData({ messages, prompt: '' })
    this.scrollToBottom()
  },
  // 页面本身滚动，不依赖固定高度的 scroll-view，避免输入栏与底栏互相压住。
  scrollToBottom() {
    try { wx.pageScrollTo({ selector: '#ai-bottom', duration: 0 }) } catch { /* 旧基础库不支持时忽略。 */ }
  },
  onNewChat() {
    if (this.data.messages.length === 1) { wx.showToast({ title: '已经是新对话', icon: 'none' }); return }
    this._seq = 0
    this.setData({ messages: [{ id: 'ai-0', role: 'ai', text: WELCOME, actions: false }], prompt: '' })
  },
  onCopy(event: WechatMiniprogram.TouchEvent) {
    const text = String(event.currentTarget.dataset.text || '')
    if (!text) return
    wx.setClipboardData({ data: text, success() { wx.showToast({ title: '已复制', icon: 'success' }) } })
  },
  onPostTap(event: WechatMiniprogram.TouchEvent) {
    const content = String(event.currentTarget.dataset.text || '')
    if (!content) return
    try { wx.setStorageSync('xinkelink.post.draft.v1', { content, category: boardFor(content).key, anonymous: false, images: [] }) }
    catch { wx.showToast({ title: '草稿未保存，请重试', icon: 'none' }); return }
    wx.navigateTo({ url: '/pages/forum-publish/index?draft=1', fail() { wx.showToast({ title: '发布页暂时无法打开', icon: 'none' }) } })
  },
})

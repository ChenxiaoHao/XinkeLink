import { client, requestId, Runtime } from '../../backend/client'
import { registerPage } from '../../backend/pages'
const api = client(wx)
let pending: { content: string; id: string } | null = null
const FEEDBACK_STORAGE_KEY = 'xinkelink.feedback.v1'
const FEEDBACK_TYPES = [
  { key: 'problem', label: '问题反馈' },
  { key: 'suggestion', label: '功能建议' },
  { key: 'other', label: '其他' },
] as const
registerPage(wx, Page, 'feedback')({
  data: { types: FEEDBACK_TYPES, type: 'problem' as string, content: '', contact: '', busy: false },
  onTypeTap(event: WechatMiniprogram.TouchEvent) {
    const key = event.currentTarget.dataset.key
    if (FEEDBACK_TYPES.some(item => item.key === key)) this.setData({ type: key })
  },
  onInput(event: WechatMiniprogram.TextareaInput) { this.setData({ content: event.detail.value }) },
  onContactInput(event: WechatMiniprogram.CustomEvent<{ value: string }>) { this.setData({ contact: event.detail.value.slice(0, 60) }) },
  async onSubmit() {
    const body = this.data.content.trim()
    if (this.data.busy) return
    if (!body) { wx.showToast({ title: '请填写意见或建议', icon: 'none' }); return }
    const contact = this.data.contact.trim()
    const typeLabel = FEEDBACK_TYPES.find(item => item.key === this.data.type)?.label || '问题反馈'
    const content = contact ? `【${typeLabel}】${body}\n联系：${contact}` : `【${typeLabel}】${body}`
    if ((wx as Runtime).__fixtureMode === true) {
      // 演示模式没有 Java 服务：原来的 requireSession 会走 wx.login 再请求 /auth/wechat 必然失败。
      // 反馈只留在本机，保证开发者工具里这条链路可用。
      this.setData({ busy: true })
      try {
        const stored = wx.getStorageSync(FEEDBACK_STORAGE_KEY)
        const items = Array.isArray(stored) ? stored : []
        wx.setStorageSync(FEEDBACK_STORAGE_KEY, [...items, { content, createdAt: Date.now() }].slice(-50))
        this.setData({ content: '', contact: '' })
        wx.showToast({ title: '反馈已保存在本机', icon: 'success' })
      } catch { wx.showToast({ title: '反馈未保存，请重试', icon: 'none' }) }
      finally { this.setData({ busy: false }) }
      return
    }
    if (!await api.requireSession()) return
    if (!pending || pending.content !== content) pending = { content, id: requestId() }
    this.setData({ busy: true })
    try {
      await api.request('/feedback', 'POST', { content, clientRequestId: pending.id })
      pending = null; this.setData({ content: '', contact: '' }); wx.showToast({ title: '感谢你的反馈', icon: 'success' })
      setTimeout(() => wx.navigateBack(), 800)
    } catch (error) { wx.showToast({ title: error instanceof Error ? error.message : '提交失败，请重试', icon: 'none' }) }
    finally { this.setData({ busy: false }) }
  },
})

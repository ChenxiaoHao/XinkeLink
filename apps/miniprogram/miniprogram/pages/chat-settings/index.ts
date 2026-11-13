import { registerPage } from '../../backend/pages'
import { clearConversationHistory, readConversation, Conversation } from '../../features/contact/repository'
import { readPeerChatSettings, updatePeerChatSettings, PeerChatSettings } from '../../features/contact/settings'

registerPage(wx, Page, 'chat-settings')({
  data: { peer: null as Conversation['peer'] | null, preferences: { muted: false, pinned: false, blocked: false } as PeerChatSettings,
    loadFailed: false, historyOpen: false, historyQuery: '', historyMatches: [] as Conversation['messages'], busy: false },
  _peerId: '',
  onLoad(options: Record<string, string | undefined> = {}) { this._peerId = options.peerId ?? ''; this.reload() },
  onShow() { this.reload() },
  reload() {
    try {
      const conversation = readConversation(wx, this._peerId)
      const preferences = readPeerChatSettings(wx, this._peerId)
      this.setData({ peer: conversation.peer, preferences,
        historyMatches: this.filterHistory(conversation.messages, this.data.historyQuery), loadFailed: false })
    } catch { this.setData({ loadFailed: true, peer: null }) }
  },
  onPreferenceChange(event: WechatMiniprogram.CustomEvent<{ value: boolean }>) {
    const key = event.currentTarget.dataset.key as keyof PeerChatSettings
    const value = event.detail.value
    if (!['muted', 'pinned', 'blocked'].includes(key) || this.data.busy) return
    const save = () => {
      try { const preferences = updatePeerChatSettings(wx, this._peerId, { [key]: value }); this.setData({ preferences }); wx.showToast({ title: '设置已更新', icon: 'success' }) }
      catch { wx.showToast({ title: '设置未保存，请重试', icon: 'none' }) }
    }
    if (key === 'blocked' && value) {
      wx.showModal({ title: '屏蔽这位同学？', content: '屏蔽后将无法继续向对方发送私信。', confirmText: '确认屏蔽', success: result => { if (result.confirm) save() } })
      return
    }
    save()
  },
  onFindHistoryTap() { this.setData({ historyOpen: !this.data.historyOpen, historyQuery: '', historyMatches: [] }) },
  filterHistory(messages: Conversation['messages'], query: string) {
    const value = query.trim().toLocaleLowerCase()
    return value ? messages.filter(message => message.text.toLocaleLowerCase().includes(value)) : []
  },
  onHistoryInput(event: WechatMiniprogram.CustomEvent<{ value: string }>) {
    const historyQuery = event.detail.value.slice(0, 60)
    try { const conversation = readConversation(wx, this._peerId); this.setData({ historyQuery, historyMatches: this.filterHistory(conversation.messages, historyQuery) }) }
    catch { this.setData({ historyQuery, historyMatches: [] }) }
  },
  onHistoryMessageTap(event: WechatMiniprogram.TouchEvent) {
    const id = event.currentTarget.dataset.id as string
    if (!this.data.historyMatches.some(message => message.id === id)) return
    wx.navigateTo({ url: '/pages/contact-chat/index?peerId=' + encodeURIComponent(this._peerId) + '&messageId=' + encodeURIComponent(id) })
  },
  onClearHistoryTap() {
    if (this.data.busy) return
    wx.showModal({ title: '清空聊天记录？', content: '仅清除本设备保存的这段模拟私信记录。', confirmText: '清空记录', success: result => {
      if (!result.confirm) return
      this.setData({ busy: true })
      try { clearConversationHistory(wx, this._peerId); this.reload(); wx.showToast({ title: '聊天记录已清空', icon: 'success' }) }
      catch { wx.showToast({ title: '聊天记录未清空，请重试', icon: 'none' }) }
      finally { this.setData({ busy: false }) }
    } })
  },
  onReportTap() {
    if (!this.data.peer) return
    wx.navigateTo({ url: '/pages/report/index?kind=user&id=' + encodeURIComponent(this.data.peer.id) })
  },
  onRetryTap() { this.reload() },
})

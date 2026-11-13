import { registerPage } from '../../backend/pages'
import { listConversations } from '../../features/contact/repository'
import { requireLocalSession } from '../../features/profile/repository'

interface Peer { id: string; nickname: string; avatar: string }

registerPage(wx, Page, 'new-chat')({
  data: { peers: [] as Peer[], visiblePeers: [] as Peer[], query: '', loadFailed: false, authVisible: false, authSaving: false, authError: '' },
  // 访客进入发起对话页：拦截并列出会话之前先弹一键登录，避免看到上个登录账号的会话列表。
  onShow() { if (!requireLocalSession(wx)) return; this.reload() },
  // 演示模式原来既没有 reload 也没有 onShow，联系人恒空、重试按钮是死链。
  reload() {
    try {
      const peers = listConversations(wx).map(item => ({ id: item.peer.id, nickname: item.peer.nickname, avatar: item.peer.avatar }))
      this.setData({ peers, loadFailed: false })
      this.filterPeers()
    } catch { this.setData({ peers: [], visiblePeers: [], loadFailed: true }) }
  },
  onInput(event: WechatMiniprogram.Input) {
    this.setData({ query: (event.detail.value || '').slice(0, 30) })
    this.filterPeers()
  },
  filterPeers() {
    const query = this.data.query.trim()
    const peers = this.data.peers || []
    const visiblePeers = query ? peers.filter((peer) => !!peer.nickname && peer.nickname.indexOf(query) >= 0) : peers
    this.setData({ visiblePeers })
  },
  onStartChat(event: WechatMiniprogram.TouchEvent) {
    const id = event.currentTarget.dataset.peerId
    if (typeof id !== 'string' || !id) return
    wx.navigateTo({ url: '/pages/contact-chat/index?peerId=' + encodeURIComponent(id), fail() { wx.showToast({ title: '聊天页暂时无法打开', icon: 'none' }) } })
  },
  onBrowseFeed() {
    wx.switchTab({ url: '/pages/forum/index', fail() { wx.navigateBack() } })
  },
})

import { registerPage } from '../../backend/pages'
import { ContactMessage } from '../../features/contact/storage'
import { openConversation, Peer, readConversation, resolvePeer, sendText } from '../../features/contact/repository'
import { localIdentity, requireLocalSession } from '../../features/profile/repository'
import { readPeerChatSettings } from '../../features/contact/settings'

interface ChatMessage extends ContactMessage { showTime: boolean; timeText: string }
function messageViews(messages: ContactMessage[]): ChatMessage[] {
  return messages.map((message, index) => {
    const date = new Date(message.createdAt), today = new Date()
    const day = date.toDateString() === today.toDateString() ? '今天' : `${date.getMonth() + 1}月${date.getDate()}日`
    return { ...message, showTime: index === 0 || message.createdAt - messages[index - 1].createdAt >= 300_000,
      timeText: `${day} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}` }
  })
}

registerPage(wx, Page, 'contact-chat')({
  data: {
    peer: null as Peer | null,
    messages: [] as ChatMessage[],
    selfAvatar: '',
    selfNickname: '',
    input: '',
    failedText: '',
    scrollTo: '',
    headerHeight: 88,
    keyboardHeight: 0,
    loadFailed: false,
    readStateFailed: false,
    missing: false,
    canSend: false,
    blockedReason: '',
    sending: false,
  },
  _peerId: '',
  _targetMessageId: '',
  onLoad(options: Record<string, string | undefined>) {
    this._peerId = options.peerId ?? ''
    this._targetMessageId = options.messageId ?? ''
    this.refreshConversation()
  },
  onShow() { this.refreshConversation() },
  refreshConversation(markRead = true) {
    try {
      if (!this._peerId || !resolvePeer(wx,this._peerId)) { this.setData({missing:true,loadFailed:false,canSend:false});return }
      let conversation = readConversation(wx, this._peerId)
      let readStateFailed = this.data.readStateFailed
      if (markRead) {
        try {
          openConversation(wx, this._peerId)
          conversation = readConversation(wx, this._peerId)
          readStateFailed = false
        } catch { readStateFailed = true }
      }
      const messages = messageViews(conversation.messages)
      this.setData({ peer: conversation.peer, messages,
        selfAvatar: localIdentity(wx).avatar, selfNickname: localIdentity(wx).nickname, scrollTo: this.targetScrollId(messages),
        missing: false, loadFailed: false, readStateFailed, canSend: !readPeerChatSettings(wx,this._peerId).blocked, blockedReason: readPeerChatSettings(wx,this._peerId).blocked ? '你已屏蔽这位同学' : '' })
    } catch { this.setData({ loadFailed: true, missing: false, canSend: false }) }
  },
  onHeaderLayout(event:WechatMiniprogram.CustomEvent<{height:number;rightSpace?:number}>) {this.setData({headerHeight:event.detail.height,headerRightSpace:event.detail.rightSpace||104})},
  onKeyboardHeightChange(event:WechatMiniprogram.CustomEvent<{height:number}>) {this.setData({keyboardHeight:Math.max(0,event.detail.height||0)})},
  onMessageInput(event:WechatMiniprogram.CustomEvent<{value:string}>) {this.setData({input:event.detail.value,failedText:''})},
  onSendTap() {const text=this.data.input.trim();if(!text||this.data.loadFailed||!this._peerId||this.data.sending||!requireLocalSession(wx))return;if(readPeerChatSettings(wx,this._peerId).blocked){this.setData({canSend:false,blockedReason:'你已屏蔽这位同学'});wx.showToast({title:'你已屏蔽这位同学',icon:'none'});return}this.setData({sending:true});try{sendText(wx,this._peerId,text);this.setData({input:'',failedText:''});this.refreshConversation(false)}catch{this.setData({failedText:this.data.input});wx.showToast({title:'发送失败，请重试',icon:'none'})}finally{this.setData({sending:false})}},
  onRetrySendTap(){this.onSendTap()},onRetryTap(){this.refreshConversation()},onScrollToUpper(){},
  // wxml 三处头像都绑了 binderror，但演示模式侧一直缺这个 handler，破图时没有兜底。
  onChatAvatarError(event:WechatMiniprogram.TouchEvent){
   const fallback='/assets/icons/user-round-muted.png',owner=event.currentTarget.dataset.owner
   if(owner==='self'){if(this.data.selfAvatar!==fallback)this.setData({selfAvatar:fallback})}
   else if(this.data.peer&&this.data.peer.avatar!==fallback)this.setData({'peer.avatar':fallback})
  },
  onPeerTap(){if(this.data.peer)wx.navigateTo({url:'/pages/user-home/index?id='+encodeURIComponent(this.data.peer.id)})},
  targetScrollId(messages: ContactMessage[]) {
    const target = this._targetMessageId && messages.some(message => message.id === this._targetMessageId)
      ? this._targetMessageId : messages[messages.length - 1]?.id
    return target ? 'message-' + target : ''
  },
  onSettingsTap() {
    if (!this._peerId || !this.data.peer) return
    wx.navigateTo({ url: '/pages/chat-settings/index?peerId=' + encodeURIComponent(this._peerId) })
  },
  onContactTap() { wx.switchTab({ url: '/pages/contact/index' }) },
})

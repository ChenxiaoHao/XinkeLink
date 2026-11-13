import {Conversation,listConversations,markAllConversationsRead} from '../../features/contact/repository'
import {Notice,readNotifications,markNotificationRead,markAllNotificationsRead} from '../../features/contact/notifications'
import {unreadTotal} from '../../features/contact/badges'
import {readLocalSession,setLocalSession} from '../../features/profile/repository'
import {client,Runtime} from '../../backend/client'
type InboxData = { activeTab:'messages'|'notifications';noticeFilter:string;conversationFilter:string;query:string;conversations:Conversation[];visibleConversations:Conversation[];notifications:Notice[];visibleNotifications:Notice[];messageUnread:number;notificationUnread:number;messagesFailed:boolean;notificationsFailed:boolean;loadFailed:boolean;guest:boolean }
interface InboxInstance {data:InboxData;setData(patch:Partial<InboxData>):void;getTabBar():{setData(patch:Record<string,unknown>):void}|null;refreshInbox():void;updateTabBadge():void;updateVisibleNotifications():void;filterConversations():void}
// 访客不进收件箱：通知与私信都要求登录态，未登录显示引导空态。
function loggedInNow(runtime: typeof wx): boolean {
  try {
    if ((runtime as Runtime).__fixtureMode === true) { try { return readLocalSession(runtime) } catch { return true } }
    return !!client(runtime).loggedIn
  } catch { return true }
}
export function inbox(mode:'messages'|'notifications', runtime: typeof wx = (globalThis as unknown as {wx: typeof wx}).wx): WechatMiniprogram.Page.Options<InboxData, InboxInstance> {
 // 显式接收运行时，避免抽出的控制器依赖隐式全局，页面与测试都能注入同一份 wx。
 const wx = runtime
 const definition: Record<string,unknown> & ThisType<InboxInstance> = {
 data:{activeTab:mode,noticeFilter:'all',conversationFilter:'all',query:'',conversations:[] as Conversation[],visibleConversations:[] as Conversation[],notifications:[] as Notice[],visibleNotifications:[] as Notice[],messageUnread:0,notificationUnread:0,messagesFailed:false,notificationsFailed:false,loadFailed:false,guest:false},
 onShow(){this.getTabBar()?.setData({activeKey:mode==='messages'?'contact':'notifications',publishVisible:false});if(loggedInNow(wx)){this.setData({guest:false});this.refreshInbox()}else this.setData({guest:true,conversations:[],visibleConversations:[],notifications:[],visibleNotifications:[],messageUnread:0,notificationUnread:0,loadFailed:false});},
 // 访客引导页的登录入口：本地演示模式弹「一键登录」；后端模式登录成功后刷新，失败则提示走侧栏授权。
 onGuestLogin(){wx.showModal({title:'当前为访客浏览',content:'登录后即可查看通知与私信，之后无需再次登录。',confirmText:'一键登录',success:result=>{if(!result.confirm)return;try{if((wx as Runtime).__fixtureMode===true){setLocalSession(wx,true);this.setData({guest:false});this.refreshInbox()}else{client(wx).login(true);this.setData({guest:false});this.refreshInbox()}}catch{wx.showToast({title:'请在侧边栏完成微信授权登录',icon:'none'})}}})},
 refreshInbox(){try{const conversations=listConversations(wx),notifications=readNotifications(wx);this.setData({conversations,notifications,messageUnread:conversations.reduce((n,c)=>n+c.unread,0),notificationUnread:notifications.filter(n=>n.unread).length,loadFailed:false});this.updateVisibleNotifications();this.updateTabBadge()}catch{this.setData({loadFailed:true})}},
 updateTabBadge(){try{this.getTabBar()?.setData({unreadCount:unreadTotal(wx),messageUnread:this.data.messageUnread,notificationUnread:this.data.notificationUnread})}catch{}},
 updateVisibleNotifications(){const filter=this.data.noticeFilter;this.setData({visibleNotifications:this.data.notifications.filter((n:Notice&{isMention?:boolean})=>filter==='all'||filter==='unread'&&n.unread||filter==='mention'&&n.isMention||n.type===filter)});this.filterConversations()},
 filterConversations(){const q=this.data.query.trim().toLocaleLowerCase();this.setData({visibleConversations:this.data.conversations.filter(c=>(this.data.conversationFilter!=='unread'||c.unread>0)&&(!q||(c.peer.nickname+' '+c.lastMessage).toLocaleLowerCase().includes(q)))})},
 onQueryInput(e:WechatMiniprogram.CustomEvent<{value:string}>){this.setData({query:e.detail.value});this.filterConversations()},
 onConversationFilter(){wx.showActionSheet({itemList:['全部','未读'],success:r=>{this.setData({conversationFilter:r.tapIndex===1?'unread':'all'});this.filterConversations()}})},
 onTabTap(e:WechatMiniprogram.TouchEvent){const tab=e.currentTarget.dataset.tab;if(tab==='messages'||tab==='notifications')this.setData({activeTab:tab,loadFailed:tab==='messages'?this.data.messagesFailed:this.data.notificationsFailed})},
 onFilterTap(e:WechatMiniprogram.TouchEvent){const filter=e.currentTarget.dataset.filter;if(['all','unread','mention','comment','reaction'].includes(filter)){this.setData({noticeFilter:filter});this.updateVisibleNotifications()}},
 onConversationTap(e:WechatMiniprogram.TouchEvent){const id=e.currentTarget.dataset.id;if(this.data.conversations.some(c=>c.id===id))wx.navigateTo({url:'/pages/contact-chat/index?peerId='+encodeURIComponent(id)})},
 onNotificationTap(e:WechatMiniprogram.TouchEvent){const notice=this.data.notifications.find(n=>n.id===e.currentTarget.dataset.id);if(!notice)return;try{markNotificationRead(wx,notice.id)}catch{wx.showToast({title:'已读状态未保存，请重试',icon:'none'})}this.refreshInbox();if(notice.targetKind==='announcement'){wx.showModal({title:notice.summary,content:notice.detail||notice.quote,showCancel:false});return}wx.navigateTo({url:'/pages/forum-detail/index?id='+encodeURIComponent(notice.targetId)})},
 onMarkAllRead(){if(this.data.activeTab==='messages')markAllConversationsRead(wx);else markAllNotificationsRead(wx);this.refreshInbox()},
 onRetryTap(){this.refreshInbox()},onPullDownRefresh(){try{this.refreshInbox()}finally{wx.stopPullDownRefresh()}},
 onNewChat(){wx.navigateTo({url:'/pages/new-chat/index'})},
 onNoticeMore(e:WechatMiniprogram.TouchEvent){const id=e.currentTarget.dataset.id;wx.showActionSheet({itemList:['标为已读'],success:()=>{markNotificationRead(wx,id);this.refreshInbox()}})},
 }; return definition as unknown as WechatMiniprogram.Page.Options<InboxData, InboxInstance>
}

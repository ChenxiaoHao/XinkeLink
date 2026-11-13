const test=require('node:test'),assert=require('node:assert/strict');const {loadPage}=require('./page-harness.cjs');
function runtime(){const saved=new Map(),calls={routes:[],toasts:[]};const wx={getStorageSync:k=>saved.get(k),setStorageSync:(k,v)=>saved.set(k,v),showToast:o=>calls.toasts.push(o.title),navigateTo:o=>calls.routes.push(o.url),stopPullDownRefresh(){}};return{saved,calls,wx};}
test('notifications are readable and local comment activity follows creation and deletion without self-unread',()=>{
 const r=runtime(),n=require('../.test-dist/features/contact/notifications.js'),f=require('../.test-dist/features/forum/repository.js');
 const before=n.readNotifications(r.wx);assert.ok(before.some(x=>x.type==='comment'));assert.ok(before.some(x=>x.type==='reaction'));
 const id=f.addForumComment(r.wx,'post-001','本地评论联动');const added=n.readNotifications(r.wx).find(x=>x.id==='activity-'+id);assert.ok(added);assert.equal(added.unread,false);
 f.deleteOwnForumComment(r.wx,'post-001',id);assert.equal(n.readNotifications(r.wx).some(x=>x.id==='activity-'+id),false);
 n.markAllNotificationsRead(r.wx);assert.equal(n.readNotifications(r.wx).filter(x=>x.unread).length,0);
});
test('contact tab switches views, marks all read, and opens the same peer thread',()=>{
 const r=runtime(),p=loadPage('pages/contact/index.js',r);p.onShow();assert.equal(p.data.conversations.length,3);
 p.onConversationTap({currentTarget:{dataset:{id:'mock-ajie'}}});assert.equal(r.calls.routes[0],'/pages/contact-chat/index?peerId=mock-ajie');
 p.onMarkAllRead();assert.equal(p.data.messageUnread,0);p.onTabTap({currentTarget:{dataset:{tab:'notifications'}}});p.onMarkAllRead();assert.equal(p.data.notificationUnread,0);
});
test('new chat sends, reflects unread in contact, preserves failed draft ',()=>{
 const r=runtime(),p=loadPage('pages/contact-chat/index.js',r);p.onLoad({peerId:'mock-ajie'});
 p.onMessageInput({detail:{value:'新会话消息'}});p.onSendTap();assert.equal(p.data.messages.at(-1).text,'新会话消息');assert.equal(p.data.input,'');
 const c=loadPage('pages/contact/index.js',r);c.onShow();assert.equal(c.data.conversations.find(x=>x.id==='mock-ajie').lastMessage,'新会话消息');
 r.wx.setStorageSync=()=>{throw Error('full')};p.onMessageInput({detail:{value:'保留待重试'}});p.onSendTap();assert.equal(p.data.input,'保留待重试');assert.ok(p.data.failedText);
});

test('readable chat history remains visible when persisting read status fails',()=>{
 const r=runtime(),repo=require('../.test-dist/features/contact/repository.js');
 repo.sendText(r.wx,'mock-ajie','原有的可读历史');
 const before=structuredClone(r.saved.get('xinkelink.contact.v1'));
 r.wx.setStorageSync=()=>{throw Error('quota')};
 const p=loadPage('pages/contact-chat/index.js',r);p.onLoad({peerId:'mock-ajie'});
 assert.equal(p.data.loadFailed,false);assert.equal(p.data.messages.at(-1).text,'原有的可读历史');
 assert.equal(p.data.readStateFailed,true);assert.deepEqual(r.saved.get('xinkelink.contact.v1'),before);
 p.onMessageInput({detail:{value:'稍后重试'}});p.onSendTap();assert.equal(p.data.input,'稍后重试');
 r.wx.setStorageSync=(k,v)=>r.saved.set(k,v);p.onRetryTap();assert.equal(p.data.readStateFailed,false);
});

test('notification content can open even when read status cannot be persisted',()=>{
 const r=runtime(),p=loadPage('pages/contact/index.js',r);p.onShow();
 r.wx.setStorageSync=()=>{throw Error('quota')};
 p.onNotificationTap({currentTarget:{dataset:{id:'notice-comment'}}});
 assert.equal(r.calls.routes.at(-1),'/pages/forum-detail/index?id=post-001');
 assert.ok(p.data.notifications.find(n=>n.id==='notice-comment').unread);
 assert.ok(r.calls.toasts.includes('已读状态未保存，请重试'));
});

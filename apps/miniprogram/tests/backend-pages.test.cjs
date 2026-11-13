const { BACKEND_ORIGIN: configuredOrigin } = require('../.test-dist/backend/config.js');
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');
const { loadComponent } = require('./native-harness.cjs');
const author = { id: null, nickname: '匿名同学 123', avatar: null, isAnonymous: true };
function runtime(routes = {}) {
 const calls = [], saved = new Map(), wx = { getStorageSync:k=>saved.get(k), setStorageSync:(k,v)=>saved.set(k,v),
 request:o=>{ calls.push(o); const route = routes[o.url.replace(configuredOrigin + "/api/v1",'').split('?')[0]];
 if (route instanceof Error) return o.fail(route);
 if (typeof route === 'function') return route(o);
 o.success({statusCode:route ? 200 : 404,data:{code:route?'OK':'NOT_FOUND',data:route || null,message:'请求失败'}}); },
 showToast(){}, navigateTo(){}, stopPullDownRefresh(){}, pageScrollTo(){} };
 return { wx, calls, saved };
}
function signed(r){r.saved.set('xinkelink.backend.session.v1',{accessToken:'a',refreshToken:'r',expiresAt:Date.now()+600000,me:{id:'17',nickname:'同学',avatar:null}});return r;}
function ok(o,data){o.success({statusCode:200,data:{code:'OK',data}})}

test('new chat uses existing server peers and safely encodes the selected peer route', async()=>{
 const r=signed(runtime({'/conversations':{items:[{id:'31',peer:{id:'18',nickname:'同学',avatar:null},updatedAt:'2026-11-11T00:00:00Z',unreadCount:0}],hasMore:false}}));
 const routes=[];r.wx.navigateTo=o=>routes.push(o.url);
 const p=loadPage('pages/new-chat/index.js',{...r,fixture:false,betaPhase:'open'});await p.reload();
 assert.deepEqual(p.data.peers.map(peer=>peer.id),['18']);
 await p.onStartChat({currentTarget:{dataset:{peerId:'18'}}});assert.deepEqual(routes,['/pages/contact-chat/index?peerId=18']);
 await p.onStartChat({currentTarget:{dataset:{peerId:'17'}}});assert.equal(routes.length,1);
});

test('profile reads its public post projection and clears it when the session is cleared', async()=>{
 const dto={id:'51',category:'campus',content:'公开校园动态',images:[],author:{id:'17',nickname:'同学',avatar:null,isAnonymous:false},createdAt:'2026-11-11T00:00:00Z'};
 const r=signed(runtime({'/me':{id:'17',nickname:'同学',avatar:null},'/me/summary':{postCount:2,likedPostCount:1,receivedLikeCount:3},'/users/17/posts':{items:[dto],hasMore:false}}));
 const p=loadPage('pages/profile/index.js',{...r,fixture:false,betaPhase:'open'});await p.onShow();assert.equal(p.data.posts[0].id,'51');assert.equal(p.data.postCount,2);
 require('../.test-dist/backend/client.js').client(r.wx).clear();assert.deepEqual(p.data.posts,[]);assert.equal(p.data.loggedIn,false);p.onUnload();
});

test('forum fetches their first feed only once when a page is opened',async()=>{
 const response=(items=[])=>({items,hasMore:false,nextCursor:null});
 const r=signed(runtime({'/posts':o=>ok(o,response()),'/announcements':o=>ok(o,response())}));
 const forum=loadPage('pages/forum/index.js',{...r,fixture:false,betaPhase:'open'});
 await forum.onLoad({});await forum.onShow();
 const paths=r.calls.map(call=>new URL(call.url).pathname);
 assert.equal(paths.filter(path=>path==='/api/v1/posts').length,1);
 assert.equal(paths.filter(path=>path==='/api/v1/announcements').length,1);
 forum.onHide();forum.onUnload();
});

test('editing an existing post exposes edit mode without replacing the original content', async () => {
 const r=signed(runtime({'/posts/73':{id:'73',isMine:true,content:'保留原动态内容',category:'campus',author:{isAnonymous:false},images:[],version:2}}));
 const p=loadPage('pages/forum-publish/index.js',{...r,fixture:false,betaPhase:'open'});
 await p.onLoad({editId:'73'});
 assert.equal(p.data.editId,'73');
 assert.equal(p.data.form.content,'保留原动态内容');
 assert.equal(p._editId,'73');
 assert.equal(r.calls.length,1);
});
test('chat retries the failed message without changing its request id',async()=>{
 let attempts=0;const r=signed(runtime({'/conversations/31/messages':o=>{if(++attempts===1)o.fail({errMsg:'offline'});else ok(o,{id:'message'});}}));
 const p=loadPage('pages/contact-chat/index.js',{...r,fixture:false,betaPhase:'open'});p._conversationId='31';p.setData({input:'你好',canSend:true});p.reload=async()=>{};
 await p.onSendTap();assert.equal(p.data.failedText,'你好');assert.equal(typeof p.onRetrySendTap,'function');await p.onRetrySendTap();
 assert.equal(p.data.failedText,'');assert.equal(r.calls.length,2);assert.equal(r.calls[0].data.clientMessageId,r.calls[1].data.clientMessageId);
});
test('forum reactions update the selected post without reloading the feed or losing later pages',async()=>{
 const r=signed(runtime({'/posts/73/like':o=>ok(o,{liked:true,likeCount:12}),'/posts/73/favorite':o=>ok(o,{favorited:true})}));
 const p=loadPage('pages/forum/index.js',{...r,fixture:false,betaPhase:'open'});p.setData({posts:[{id:'73',liked:false,collected:false,likeCount:11},{id:'74',liked:false,likeCount:1}],hasMore:true});p._cursor='next';
 await p.saveInteraction('73','liked');await p.saveInteraction('73','collected');
 assert.equal(p.data.posts[0].liked,true);assert.equal(p.data.posts[0].likeCount,12);assert.equal(p.data.posts[0].collected,true);assert.equal(p.data.posts.length,2);assert.equal(p._cursor,'next');assert.equal(r.calls.length,2);
});
test('reaction failure keeps the previous state and does not reload',async()=>{
 const r=signed(runtime({'/posts/73/like':new Error('offline')}));let message='';r.wx.showToast=o=>message=o.title;
 const p=loadPage('pages/forum/index.js',{...r,fixture:false,betaPhase:'open'});p.setData({posts:[{id:'73',liked:false,collected:false,likeCount:11}]});await p.saveInteraction('73','liked');
 assert.equal(p.data.posts[0].liked,false);assert.equal(p.data.posts[0].likeCount,11);assert.ok(message);assert.equal(r.calls.length,1);
});
test('comment like updates only the selected reply without reloading expanded comments',async()=>{
 const r=signed(runtime({'/comments/42/like':o=>ok(o,{liked:true,likeCount:3})}));const p=loadPage('pages/forum-detail/index.js',{...r,fixture:false,betaPhase:'open'});
 p.setData({comments:[{id:'41',liked:false,likeCount:1,repliesExpanded:true,replies:[{id:'42',liked:false,likeCount:2}]}]});await p.onCommentLike({detail:{id:'42'},currentTarget:{dataset:{}}});
 assert.equal(p.data.comments[0].replies[0].liked,true);assert.equal(p.data.comments[0].replies[0].likeCount,3);assert.equal(p.data.comments[0].repliesExpanded,true);assert.equal(r.calls.length,1);
});
test('my likes reads only like endpoints and post unlike removes only its row',async()=>{
 const dto={id:'51',category:'campus',content:'喜欢的动态',images:[],author,createdAt:'2026-10-30T00:00:00Z',liked:true,likeCount:1};
 const r=signed(runtime({'/me/likes':o=>ok(o,{items:o.url.includes('type=post')?[{id:'51',item:dto}]:[],hasMore:false}),'/posts/51/like':o=>ok(o,{liked:false,likeCount:0})}));
 const p=loadPage('pages/my-collections/index.js',{...r,fixture:false,betaPhase:'open'});await p.reload();assert.equal(p.data.posts[0].id,'51');await p.saveInteraction('51','liked');assert.equal(p.data.posts.length,0);assert.equal(r.calls.some(o=>o.url.includes('favorite')),false);
});
test('production forum reads the API and never displays seed posts after a network failure', async()=>{
 const r=runtime({'/posts':new Error('offline')}); const p=loadPage('pages/forum/index.js',{...r,fixture:false,betaPhase:'open'});
 await p.onLoad({}); await p.onShow(); assert.equal(p.data.posts.length,0); assert.equal(p.data.loadFailed,true); assert.equal(r.calls.length,1);
});
test('production forum adapts anonymous DTOs and cursor pages',async()=>{
 const r=runtime({'/posts':{items:[{id:'9007199254740993',category:'lost_found',content:'失物',author,images:[],createdAt:'2026-10-25T00:00:00.000Z',likeCount:1,commentCount:0,liked:false,favorited:false,version:0}],hasMore:false,nextCursor:null},'/announcements':{items:[],hasMore:false,nextCursor:null}});
 const p=loadPage('pages/forum/index.js',{...r,fixture:false,betaPhase:'open'});await p.onLoad({});await p.onShow();
 assert.equal(p.data.posts[0].id,'9007199254740993');assert.equal(p.data.posts[0].category,'lost-found');assert.equal(p.data.posts[0].authorId,undefined);assert.equal(p.data.hasMore,false);
});
test('production tab badge reads the server badge count and never seed conversations',async()=>{
 const r=runtime({'/me/unread':{raw:{messages:2,comments:1,reactions:0,announcements:0},badgeTotal:3}});
 r.saved.set('xinkelink.backend.session.v1',{accessToken:'a',refreshToken:'r',expiresAt:Date.now()+600000,me:{id:'1'}});
 const {instance}=loadComponent('custom-tab-bar/index.js',{...r,fixture:false,betaPhase:'open'});await instance.refreshUnread();assert.equal(instance.data.unreadCount,3);assert.equal(r.calls.length,1);
});
test('profile editor saves server fields with version and keeps student ID only on this device',async()=>{
 const me={id:'17',nickname:'同学',avatar:null,bio:'',major:'',className:'',enrollmentYear:null,version:4};
 const r=signed(runtime({'/me':o=>ok(o,o.method==='PATCH'?{...me,...o.data,version:5}:me)}));r.wx.navigateBack=()=>{};
 const p=loadPage('pages/profile-edit/index.js',{...r,fixture:false,betaPhase:'open'});await p.onLoad();p.setData({form:{...p.data.form,nickname:'新昵称',studentId:'PRIVATE-123',year:'2025'}});await p.onSaveTap();
 const patch=r.calls.find(o=>o.method==='PATCH');assert.equal(patch.data.expectedVersion,4);assert.equal(patch.data.enrollmentYear,'2025');assert.equal('studentId' in patch.data,false);assert.equal(JSON.stringify(r.calls).includes('PRIVATE-123'),false);assert.equal(r.saved.get('xinkelink.private.student-id.v1'),'PRIVATE-123');
});
test('forum publishing edits the same post with PATCH and keeps anonymous identity immutable',async()=>{
 const dto={id:'73',category:'help',content:'旧内容',author,images:[],createdAt:'2026-10-25T00:00:00.000Z',isMine:true,version:3};
 const r=signed(runtime({'/posts/73':o=>ok(o,o.method==='PATCH'?{...dto,...o.data,version:4}:dto)}));r.wx.redirectTo=()=>{};
 const p=loadPage('pages/forum-publish/index.js',{...r,fixture:false,betaPhase:'open'});await p.onLoad({editId:'73'});p.setData({form:{...p.data.form,content:'修改内容'}});await p.onPublishTap();
 const patch=r.calls.find(o=>o.method==='PATCH');assert.equal(patch.data.expectedVersion,3);assert.equal(patch.data.content,'修改内容');assert.equal('anonymous' in patch.data,false);assert.equal('author' in patch.data,false);
});
test('chat reads use decimal string watermarks and HTTP clear removes already displayed messages',async()=>{
 let cleared=false;const peer={id:'18',nickname:'对方',avatar:null};
 const r=signed(runtime({'/conversations/31':{id:'31',peer,lastMessage:null,updatedAt:'2026-10-25T00:00:00.000Z',unreadCount:1,version:0},
 '/conversations/31/settings':{pinned:false,muted:false,blocked:false,version:0},
 '/conversations/31/messages':o=>ok(o,{items:cleared?[]:[{id:'41',sequence:'9007199254740993',text:'服务端消息',isMine:false,createdAt:'2026-10-25T00:00:00.000Z',visibility:'normal',persistenceStatus:'sent'}],hasMore:false,nextBeforeSequence:null,nextAfterSequence:null}),
 '/conversations/31/read':o=>ok(o,{readThroughSequence:o.data.readUpToSequence,unreadCount:0})}));
 const p=loadPage('pages/contact-chat/index.js',{...r,fixture:false,betaPhase:'open'});await p.onLoad({conversationId:'31'});assert.equal(p.data.messages.length,1);assert.equal(r.calls.find(o=>o.method==='PUT').data.readUpToSequence,'9007199254740993');
 cleared=true;await p.reload();assert.equal(p.data.messages.length,0,'HTTP is authoritative after clear');
});
test('existing post more menu exposes editing for the owner without exposing anonymous author IDs',()=>{
 const r=runtime();let menu;const routes=[];r.wx.showActionSheet=o=>menu=o;r.wx.navigateTo=o=>routes.push(o.url);
 const {instance}=loadComponent('components/forum-post-card/index.js',{...r,fixture:false,betaPhase:'open'});instance.data.post={id:'73',isMine:true,isAnonymous:true};instance.onMoreTap();
 assert.ok(menu.itemList.includes('编辑帖子'));menu.success({tapIndex:0});assert.deepEqual(routes,['/pages/forum-publish/index?editId=73']);
});

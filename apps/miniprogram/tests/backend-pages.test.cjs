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
 const p=loadPage('pages/new-chat/index.js',{...r,fixture:false});await p.reload();
 assert.deepEqual(p.data.peers.map(peer=>peer.id),['18']);
 await p.onStartChat({currentTarget:{dataset:{peerId:'18'}}});assert.deepEqual(routes,['/pages/contact-chat/index?peerId=18']);
 await p.onStartChat({currentTarget:{dataset:{peerId:'17'}}});assert.equal(routes.length,1);
});

test('profile reads its public post projection and clears it when the session is cleared', async()=>{
 const dto={id:'51',category:'campus',content:'公开校园动态',images:[],author:{id:'17',nickname:'同学',avatar:null,isAnonymous:false},createdAt:'2026-11-11T00:00:00Z'};
 const r=signed(runtime({'/me':{id:'17',nickname:'同学',avatar:null},'/me/summary':{postCount:2,likedPostCount:1,receivedLikeCount:3},'/users/17/posts':{items:[dto],hasMore:false}}));
 const p=loadPage('pages/profile/index.js',{...r,fixture:false});await p.onShow();assert.equal(p.data.posts[0].id,'51');assert.equal(p.data.postCount,2);
 require('../.test-dist/backend/client.js').client(r.wx).clear();assert.deepEqual(p.data.posts,[]);assert.equal(p.data.loggedIn,false);p.onUnload();
});

test('forum fetches their first feed only once when a page is opened',async()=>{
 const response=(items=[])=>({items,hasMore:false,nextCursor:null});
 const r=signed(runtime({'/posts':o=>ok(o,response()),'/announcements':o=>ok(o,response())}));
 const forum=loadPage('pages/forum/index.js',{...r,fixture:false});
 await forum.onLoad({});await forum.onShow();
 const paths=r.calls.map(call=>new URL(call.url).pathname);
 assert.equal(paths.filter(path=>path==='/api/v1/posts').length,1);
 assert.equal(paths.filter(path=>path==='/api/v1/announcements').length,1);
 forum.onHide();forum.onUnload();
});

test('feed pagination ignores duplicate triggers and updates only new post positions', async () => {
  let completePage;
  const dto = id => ({ id, category: 'campus', content: id, author, images: [], createdAt: '2026-11-25T00:00:00Z' });
  const r = runtime({ '/posts': request => { completePage = request; } });
  const page = loadPage('pages/forum/index.js', { ...r, fixture: false });
  page.setData({ posts: [{ id: '1', liked: true }, { id: '2' }], hasMore: true });
  page._cursor = 'next';
  const patches = [];
  const write = page.setData;
  page.setData = patch => { patches.push(patch); write.call(page, patch); };
  const first = page.onReachBottom();
  const repeat = page.onReachBottom();
  assert.equal(r.calls.length, 1);
  ok(completePage, { items: [dto('2'), dto('3'), dto('3'), dto('4')], hasMore: false, nextCursor: null });
  await Promise.all([first, repeat]);
  assert.deepEqual(page.data.posts.map(post => post.id), ['1', '2', '3', '4']);
  assert.equal(page.data.posts[0].liked, true);
  assert.ok(patches.every(patch => !Object.hasOwn(patch, 'posts')));
  assert.equal(patches.find(patch => Object.hasOwn(patch, 'posts[2]'))['posts[3]'].id, '4');
});

test('a category reload ignores the pending previous page and protects the new cursor', async () => {
  const dto = id => ({ id, category: 'study', content: id, author, images: [], createdAt: '2026-11-25T00:00:00Z' });
  const pending = [];
  const r = runtime({ '/posts': request => pending.push(request), '/announcements': { items: [] } });
  const page = loadPage('pages/forum/index.js', { ...r, fixture: false });
  page.setData({ posts: [{ id: '1' }], hasMore: true });
  page._cursor = 'old';
  const oldPage = page.onReachBottom();
  page.setData({ activeCategory: 'study' });
  const newFeed = page.replaceFeed();
  await page.onReachBottom();
  assert.equal(pending.length, 2);
  ok(pending[0], { items: [dto('2')], hasMore: true, nextCursor: 'stale' });
  await oldPage;
  assert.deepEqual(page.data.posts.map(post => post.id), ['1']);
  ok(pending[1], { items: [dto('3')], hasMore: true, nextCursor: 'study-next' });
  await newFeed;
  assert.deepEqual(page.data.posts.map(post => post.id), ['3']);
  assert.equal(page._cursor, 'study-next');
});

test('an incomplete next page keeps existing posts and can retry the same cursor', async () => {
  const dto = { id: '2', category: 'campus', content: '下一页', author, images: [], createdAt: '2026-11-25T00:00:00Z' };
  let calls = 0;
  const r = runtime({ '/posts': request => {
    if (++calls === 1) ok(request, { items: [dto], hasMore: true, nextCursor: 'next' });
    else ok(request, { items: [dto], hasMore: false, nextCursor: null });
  } });
  const page = loadPage('pages/forum/index.js', { ...r, fixture: false });
  page.setData({ posts: [{ id: '1', liked: true }], hasMore: true });
  page._cursor = 'next';
  await page.onReachBottom();
  assert.deepEqual(page.data.posts.map(post => post.id), ['1']);
  assert.equal(page.data.feedPageFailed, true);
  assert.equal(page.data.loadFailed, false);
  await page.onReachBottom();
  assert.deepEqual(page.data.posts.map(post => post.id), ['1', '2']);
  assert.equal(page.data.feedPageFailed, false);
  assert.deepEqual(r.calls.map(request => new URL(request.url).searchParams.get('cursor')), ['next', 'next']);
});

test('detail entry shares the first request pair and later appearances refresh', async () => {
  const dto = { id: '73', category: 'campus', content: '详情', author, images: [], createdAt: '2026-11-25T00:00:00Z' };
  const r = runtime({ '/posts/73': dto, '/posts/73/comments': { items: [], hasMore: false } });
  const page = loadPage('pages/forum-detail/index.js', { ...r, fixture: false });
  await Promise.all([page.onLoad({ id: '73', reply: '1' }), page.onShow()]);
  assert.equal(page.data.post.id, '73');
  assert.equal(page.data.composerOpen, true);
  assert.equal(r.calls.length, 2);
  page.onHide();
  await page.onShow();
  assert.equal(r.calls.length, 4);
});

test('editing an existing post exposes edit mode without replacing the original content', async () => {
 const r=signed(runtime({'/posts/73':{id:'73',isMine:true,content:'保留原动态内容',category:'campus',author:{isAnonymous:false},images:[],version:2}}));
 const p=loadPage('pages/forum-publish/index.js',{...r,fixture:false});
 await p.onLoad({editId:'73'});
 assert.equal(p.data.editId,'73');
 assert.equal(p.data.form.content,'保留原动态内容');
 assert.equal(p._editId,'73');
 assert.equal(r.calls.length,1);
});
test('chat retries the failed message without changing its request id',async()=>{
 let attempts=0;const r=signed(runtime({'/conversations/31/messages':o=>{if(++attempts===1)o.fail({errMsg:'offline'});else ok(o,{id:'message'});}}));
 const p=loadPage('pages/contact-chat/index.js',{...r,fixture:false});p._conversationId='31';p.setData({input:'你好',canSend:true});p.reload=async()=>{};
 await p.onSendTap();assert.equal(p.data.failedText,'你好');assert.equal(typeof p.onRetrySendTap,'function');await p.onRetrySendTap();
 assert.equal(p.data.failedText,'');assert.equal(r.calls.length,2);assert.equal(r.calls[0].data.clientMessageId,r.calls[1].data.clientMessageId);
});
test('forum reactions update the selected post without reloading the feed or losing later pages',async()=>{
 const r=signed(runtime({'/posts/73/like':o=>ok(o,{liked:true,likeCount:12}),'/posts/73/favorite':o=>ok(o,{favorited:true})}));
 const p=loadPage('pages/forum/index.js',{...r,fixture:false});p.setData({posts:[{id:'73',liked:false,collected:false,likeCount:11},{id:'74',liked:false,likeCount:1}],hasMore:true});p._cursor='next';
 await p.saveInteraction('73','liked');await p.saveInteraction('73','collected');
 assert.equal(p.data.posts[0].liked,true);assert.equal(p.data.posts[0].likeCount,12);assert.equal(p.data.posts[0].collected,true);assert.equal(p.data.posts.length,2);assert.equal(p._cursor,'next');assert.equal(r.calls.length,2);
});
test('reaction failure keeps the previous state and does not reload',async()=>{
 const r=signed(runtime({'/posts/73/like':new Error('offline')}));let message='';r.wx.showToast=o=>message=o.title;
 const p=loadPage('pages/forum/index.js',{...r,fixture:false});p.setData({posts:[{id:'73',liked:false,collected:false,likeCount:11}]});await p.saveInteraction('73','liked');
 assert.equal(p.data.posts[0].liked,false);assert.equal(p.data.posts[0].likeCount,11);assert.ok(message);assert.equal(r.calls.length,1);
});
test('comment like updates only the selected reply without reloading expanded comments',async()=>{
 const r=signed(runtime({'/comments/42/like':o=>ok(o,{liked:true,likeCount:3})}));const p=loadPage('pages/forum-detail/index.js',{...r,fixture:false});
 p.setData({comments:[{id:'41',liked:false,likeCount:1,repliesExpanded:true,replies:[{id:'42',liked:false,likeCount:2}]}]});await p.onCommentLike({detail:{id:'42'},currentTarget:{dataset:{}}});
 assert.equal(p.data.comments[0].replies[0].liked,true);assert.equal(p.data.comments[0].replies[0].likeCount,3);assert.equal(p.data.comments[0].repliesExpanded,true);assert.equal(r.calls.length,1);
});
test('my likes reads only like endpoints and post unlike removes only its row',async()=>{
 const dto={id:'51',category:'campus',content:'喜欢的动态',images:[],author,createdAt:'2026-10-30T00:00:00Z',liked:true,likeCount:1};
 const r=signed(runtime({'/me/likes':o=>ok(o,{items:o.url.includes('type=post')?[{id:'51',item:dto}]:[],hasMore:false}),'/posts/51/like':o=>ok(o,{liked:false,likeCount:0})}));
 const p=loadPage('pages/my-collections/index.js',{...r,fixture:false});await p.reload();assert.equal(p.data.posts[0].id,'51');await p.saveInteraction('51','liked');assert.equal(p.data.posts.length,0);assert.equal(r.calls.some(o=>o.url.includes('favorite')),false);
});
test('production forum reads the API and never displays seed posts after a network failure', async()=>{
 const r=runtime({'/posts':new Error('offline')}); const p=loadPage('pages/forum/index.js',{...r,fixture:false});
 await p.onLoad({}); await p.onShow(); assert.equal(p.data.posts.length,0); assert.equal(p.data.loadFailed,true); assert.equal(r.calls.length,1);
});
test('production forum adapts anonymous DTOs and cursor pages',async()=>{
 const r=runtime({'/posts':{items:[{id:'9007199254740993',category:'lost_found',content:'失物',author,images:[],createdAt:'2026-10-25T00:00:00.000Z',likeCount:1,commentCount:0,liked:false,favorited:false,version:0}],hasMore:false,nextCursor:null},'/announcements':{items:[],hasMore:false,nextCursor:null}});
 const p=loadPage('pages/forum/index.js',{...r,fixture:false});await p.onLoad({});await p.onShow();
 assert.equal(p.data.posts[0].id,'9007199254740993');assert.equal(p.data.posts[0].category,'lost-found');assert.equal(p.data.posts[0].authorId,undefined);assert.equal(p.data.hasMore,false);
});
test('production tab badge reads the server badge count and never seed conversations',async()=>{
 const r=runtime({'/me/unread':{raw:{messages:2,comments:1,reactions:0,announcements:0},badgeTotal:3}});
 r.saved.set('xinkelink.backend.session.v1',{accessToken:'a',refreshToken:'r',expiresAt:Date.now()+600000,me:{id:'1'}});
 const {instance}=loadComponent('custom-tab-bar/index.js',{...r,fixture:false});await instance.refreshUnread();assert.equal(instance.data.unreadCount,3);assert.equal(r.calls.length,1);
});
test('profile editor saves server fields with version and keeps student ID only on this device',async()=>{
 const me={id:'17',nickname:'同学',avatar:null,bio:'',major:'',className:'',enrollmentYear:null,version:4};
 const r=signed(runtime({'/me':o=>ok(o,o.method==='PATCH'?{...me,...o.data,version:5}:me)}));r.wx.navigateBack=()=>{};
 const p=loadPage('pages/profile-edit/index.js',{...r,fixture:false});await p.onLoad();p.setData({form:{...p.data.form,nickname:'新昵称',studentId:'PRIVATE-123',year:'2025'}});await p.onSaveTap();
 const patch=r.calls.find(o=>o.method==='PATCH');assert.equal(patch.data.expectedVersion,4);assert.equal(patch.data.enrollmentYear,'2025');assert.equal('studentId' in patch.data,false);assert.equal(JSON.stringify(r.calls).includes('PRIVATE-123'),false);assert.equal(r.saved.get('xinkelink.private.student-id.v1'),'PRIVATE-123');
});
test('profile editor saves a renewed avatar by image ID without uploading it again',async()=>{
 const me={id:'17',nickname:'同学',avatar:{imageId:'95',url:'/api/v1/images/95/content?grant=old'},bio:'',major:'',className:'',enrollmentYear:null,version:4};
 const r=signed(runtime({'/me':o=>ok(o,me)}));r.wx.navigateBack=()=>{};
 const p=loadPage('pages/profile-edit/index.js',{...r,fixture:false});await p.onLoad();
 p.setData({form:{...p.data.form,avatar:configuredOrigin+'/api/v1/images/95/content?grant=new'}});
 await p.onSaveTap();
 assert.equal(r.calls.find(o=>o.method==='PATCH').data.avatarImageId,'95');
});
test('forum publishing edits the same post with PATCH and keeps anonymous identity immutable',async()=>{
 const dto={id:'73',category:'help',content:'旧内容',author,images:[],createdAt:'2026-10-25T00:00:00.000Z',isMine:true,version:3};
 const r=signed(runtime({'/posts/73':o=>ok(o,o.method==='PATCH'?{...dto,...o.data,version:4}:dto)}));r.wx.redirectTo=()=>{};
 const p=loadPage('pages/forum-publish/index.js',{...r,fixture:false});await p.onLoad({editId:'73'});p.setData({form:{...p.data.form,content:'修改内容'}});await p.onPublishTap();
 const patch=r.calls.find(o=>o.method==='PATCH');assert.equal(patch.data.expectedVersion,3);assert.equal(patch.data.content,'修改内容');assert.equal('anonymous' in patch.data,false);assert.equal('author' in patch.data,false);
});
test('chat reads use decimal string watermarks and HTTP clear removes already displayed messages',async()=>{
 let cleared=false;const peer={id:'18',nickname:'对方',avatar:null};
 const r=signed(runtime({'/conversations/31':{id:'31',peer,lastMessage:null,updatedAt:'2026-10-25T00:00:00.000Z',unreadCount:1,version:0},
 '/conversations/31/settings':{pinned:false,muted:false,blocked:false,version:0},
 '/conversations/31/messages':o=>ok(o,{items:cleared?[]:[{id:'41',sequence:'9007199254740993',text:'服务端消息',isMine:false,createdAt:'2026-10-25T00:00:00.000Z',visibility:'normal',persistenceStatus:'sent'}],hasMore:false,nextBeforeSequence:null,nextAfterSequence:null}),
 '/conversations/31/read':o=>ok(o,{readThroughSequence:o.data.readUpToSequence,unreadCount:0})}));
 const p=loadPage('pages/contact-chat/index.js',{...r,fixture:false});await p.onLoad({conversationId:'31'});assert.equal(p.data.messages.length,1);assert.equal(r.calls.find(o=>o.method==='PUT').data.readUpToSequence,'9007199254740993');
 cleared=true;await p.reload();assert.equal(p.data.messages.length,0,'HTTP is authoritative after clear');
});
test('existing post more menu exposes editing for the owner without exposing anonymous author IDs',()=>{
 const r=signed(runtime());let menu;const routes=[];r.wx.showActionSheet=o=>menu=o;r.wx.navigateTo=o=>routes.push(o.url);
 const {instance}=loadComponent('components/forum-post-card/index.js',{...r,fixture:false});instance.data.post={id:'73',isMine:true,isAnonymous:true};instance.onMoreTap();
 assert.ok(menu.itemList.includes('编辑帖子'));menu.success({tapIndex:0});assert.deepEqual(routes,['/pages/forum-publish/index?editId=73']);
});

test('B11 search rejects absent targets before auth and ignores an interaction response after query changes', async()=>{
 let finish; const r=signed(runtime({'/posts/73/like':o=>{finish=()=>ok(o,{liked:true,likeCount:9})}}));
 const p=loadPage('pages/search/index.js',{...r,fixture:false});
 p.setData({postResults:[{id:'73',liked:false,likeCount:1}]});
 await p.savePostInteraction('missing','liked'); assert.equal(r.calls.length,0);
 const pending=p.savePostInteraction('73','liked'); await new Promise(resolve=>setImmediate(resolve));
 p.resetSearch();p.setData({postResults:[{id:'73',liked:false,likeCount:2}]});finish();await pending;
 assert.equal(p.data.postResults[0].liked,false);assert.equal(p.data.postResults[0].likeCount,2);
});
test('B08 inbox waits for existing authorization and cancellation keeps guest state',async()=>{
 const r=runtime();const p=loadPage('pages/contact/index.js',{...r,fixture:false});
 const api=require('../.test-dist/backend/client.js').client(r.wx);let finish;
 api.requireSession=()=>new Promise(resolve=>finish=resolve);p.setData({guest:true});let refreshed=0;p.refreshInbox=()=>refreshed++;
 const pending=p.onGuestLogin();assert.equal(p.data.guest,true);assert.equal(refreshed,0);finish(false);await pending;assert.equal(p.data.guest,true);assert.equal(refreshed,0);
 api.requireSession=()=>Promise.reject(new Error('offline'));await p.onGuestLogin();assert.equal(p.data.guest,true);assert.equal(refreshed,0);
});

test('live own-post publish entry uses backend session without creating a fixture session',async()=>{const r=signed(runtime({'/me/posts':{items:[],hasMore:false}})),routes=[];r.wx.navigateTo=o=>routes.push(o.url);const p=loadPage('pages/my-posts/index.js',{...r,fixture:false});await p.onPublishTap();assert.deepEqual(routes,['/pages/forum-publish/index']);assert.equal(r.saved.has('xinkelink.session.v1'),false);});

test('live private reload discards owner data that arrives after logout',async()=>{let finish;const r=signed(runtime({'/me/posts':o=>finish=()=>ok(o,{items:[{id:'private',author:{isAnonymous:true},content:'匿名管理'}],hasMore:false})}));const p=loadPage('pages/my-posts/index.js',{...r,fixture:false});const pending=p.reload();await new Promise(resolve=>setImmediate(resolve));require('../.test-dist/backend/client.js').client(r.wx).clear();finish();await pending;assert.equal(p.data.loggedIn,false);assert.equal(p.data.posts.length,0);});

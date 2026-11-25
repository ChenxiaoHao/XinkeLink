const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');
const { BACKEND_ORIGIN } = require('../.test-dist/backend/config.js');
const stats = () => require('../.test-dist/features/statistics/views.js');
const tick = () => new Promise(resolve => setImmediate(resolve));
const post = (id='73', extra={}) => ({id,category:'campus',content:'公开帖子',author:{id:null,nickname:'匿名同学',isAnonymous:true},images:[],createdAt:'2026-10-01T00:00:00Z',permissions:{canComment:true},viewReportingEnabled:true,...extra});
function runtime(routes={}) {
 const saved=new Map(),calls=[];
 const wx={__fixtureMode:false,getStorageSync:k=>saved.get(k),setStorageSync:(k,v)=>saved.set(k,v),removeStorageSync:k=>saved.delete(k),showToast(){},stopPullDownRefresh(){},request:o=>{
 calls.push(o);const path=o.url.replace(BACKEND_ORIGIN+'/api/v1','').split('?')[0],route=routes[path];if(typeof route==='function')return route(o);if(route instanceof Error)return o.fail(route);o.success({statusCode:route?200:404,data:{code:route?'OK':'NOT_FOUND',data:route}})}};
 return {wx,saved,calls};
}
function ok(o,data){o.success({statusCode:200,data:{code:'OK',data}})}
function sign(r,id='17'){r.saved.set('xinkelink.backend.session.v1',{accessToken:'token-'+id,refreshToken:'refresh-'+id,expiresAt:Date.now()+600000,me:{id}});return r}
function viewCalls(r){return r.calls.filter(o=>o.url.endsWith('/statistics/post-views'))}
const credential={credential:'signed-anonymous-statistics-only',expiresAt:'2099-01-01T00:00:00Z'};

test('detail sends one event after its first public display; refresh/show/comments do not count again',async()=>{
 const r=sign(runtime({'/posts/73':post(),'/posts/73/comments':{items:[],hasMore:false},'/statistics/post-views':o=>ok(o,{accepted:true,eventId:o.data.eventId})}));
 const p=loadPage('pages/forum-detail/index.js',{...r,fixture:false});
 await p.onLoad({id:'73'});await tick();assert.equal(viewCalls(r).length,0);
 await p.onShow();await tick();assert.equal(viewCalls(r).length,1);assert.match(viewCalls(r)[0].data.eventId,/^[0-9a-f-]{36}$/);
 await p.reload();await p.onShow();await p.onToggleOrder();await tick();assert.equal(viewCalls(r).length,1);p.onUnload();
 const q=loadPage('pages/forum-detail/index.js',{...r,fixture:false});await q.onLoad({id:'73'});await q.onShow();await tick();assert.equal(viewCalls(r).length,2);assert.notEqual(viewCalls(r)[0].data.eventId,viewCalls(r)[1].data.eventId);q.onUnload();
});
test('failed or private details never generate a public view',async()=>{
 for(const dto of [new Error('offline'),post('73',{permissions:{canComment:false},availability:'hidden'})]) {
 const r=runtime({'/posts/73':dto,'/posts/73/comments':{items:[],hasMore:false}}),p=loadPage('pages/forum-detail/index.js',{...r,fixture:false});
 await p.onLoad({id:'73'});await p.onShow();await tick();assert.equal(viewCalls(r).length,0);p.onUnload();}
});
test('disabled or unadvertised view reporting does not make any statistics requests',async()=>{
 for(const viewReportingEnabled of [false,undefined]){
  const r=sign(runtime({'/posts/73':post('73',{viewReportingEnabled}),'/posts/73/comments':{items:[],hasMore:false}}));
  const p=loadPage('pages/forum-detail/index.js',{...r,fixture:false});
  try{
   await p.onLoad({id:'73'});await p.onShow();await p.reload();await tick();
   assert.equal(p.data.post.id,'73');assert.equal(r.calls.filter(o=>o.url.includes('/statistics/')).length,0);
  }finally{p.onUnload();stats().viewStatistics(r.wx).dispose();}
 }
});
test('guest retries unknown outcomes with the same eventId and signed anonymous credential',async()=>{
 let attempts=0;const r=runtime({'/statistics/anonymous-credential':credential,'/statistics/post-views':o=>{if(++attempts===1)o.fail({});else ok(o,{accepted:true,eventId:o.data.eventId})}});
 const queue=stats().viewStatistics(r.wx);queue.enqueue('73');await queue.flush();assert.equal(queue.pendingCount,1);await queue.flush();assert.equal(queue.pendingCount,0);
 assert.equal(viewCalls(r).length,2);assert.equal(viewCalls(r)[0].data.eventId,viewCalls(r)[1].data.eventId);assert.equal(viewCalls(r)[0].data.anonymousCredential,credential.credential);assert.equal('userId' in viewCalls(r)[0].data,false);assert.equal('occurredAt' in viewCalls(r)[0].data,false);queue.dispose();
});
test('queue is bounded to twenty and expires unconfirmed visits after ten minutes',async()=>{
 let now=1000;const r=runtime({'/statistics/anonymous-credential':new Error('offline')}),queue=new (stats().ViewStatistics)(r.wx,{now:()=>now,setTimer:()=>0,clearTimer(){}});
 for(let i=0;i<30;i++)queue.enqueue('73');await queue.flush();assert.equal(queue.pendingCount,20);now+=600001;await queue.flush();assert.equal(queue.pendingCount,0);queue.dispose();
});
test('the twenty-event limit also holds across different page wx wrappers',async()=>{
 const a=runtime({'/statistics/anonymous-credential':new Error('offline')}),b=runtime({'/statistics/anonymous-credential':new Error('offline')}),one=new (stats().ViewStatistics)(a.wx,{setTimer:()=>0,clearTimer(){}}),two=new (stats().ViewStatistics)(b.wx,{setTimer:()=>0,clearTimer(){}});
 try{for(let i=0;i<30;i++){one.enqueue('73');two.enqueue('74')}await Promise.all([one.flush(),two.flush()]);assert.equal(one.pendingCount+two.pendingCount,20)}finally{one.dispose();two.dispose()}
});
test('a credential response arriving after login cannot send guest work under the new bearer',async()=>{
 let finish;const r=runtime({'/statistics/anonymous-credential':o=>{finish=()=>ok(o,credential)},'/auth/wechat':{accessToken:'token-A',refreshToken:'rA',expiresIn:600,me:{id:'17'}},'/statistics/post-views':o=>ok(o,{accepted:true,eventId:o.data.eventId})});r.wx.login=o=>o.success({code:'wechat-code'});
 const queue=stats().viewStatistics(r.wx);queue.enqueue('73');const pending=queue.flush();await tick();const api=require('../.test-dist/backend/client.js').client(r.wx);await api.login();finish();await pending;
 assert.equal(viewCalls(r).length,0);assert.equal(queue.pendingCount,0);queue.enqueue('73');await queue.flush();assert.equal(viewCalls(r).length,1);assert.equal(viewCalls(r)[0].header.Authorization,'Bearer token-A');assert.equal('anonymousCredential' in viewCalls(r)[0].data,false);queue.dispose();
});
test('a login switch during refresh cannot resend an A event as B',async()=>{
 let finish;const r=sign(runtime({'/auth/refresh':o=>{finish=()=>ok(o,{accessToken:'fresh-A',refreshToken:'rA',expiresIn:600,me:{id:'17'}})},'/auth/wechat':{accessToken:'token-B',refreshToken:'rB',expiresIn:600,me:{id:'18'}}}));r.saved.get('xinkelink.backend.session.v1').expiresAt=1;r.wx.login=o=>o.success({code:'B'});
 const queue=stats().viewStatistics(r.wx);queue.enqueue('73');const pending=queue.flush();await tick();const api=require('../.test-dist/backend/client.js').client(r.wx);api.clear();await api.login();finish();await pending;assert.equal(viewCalls(r).length,0);assert.equal(queue.pendingCount,0);queue.dispose();
});
test('a detail response for A arriving after a switch to B cannot count as a B display',async()=>{
 let finish;const r=sign(runtime({'/posts/73':o=>{finish=()=>ok(o,post())},'/posts/73/comments':{items:[],hasMore:false},'/auth/wechat':{accessToken:'token-B',refreshToken:'rB',expiresIn:600,me:{id:'18'}},'/statistics/post-views':o=>ok(o,{accepted:true,eventId:o.data.eventId})}));r.wx.login=o=>o.success({code:'B'});
 const p=loadPage('pages/forum-detail/index.js',{...r,fixture:false});const loading=p.onLoad({id:'73'});const showing=p.onShow();await tick();const api=require('../.test-dist/backend/client.js').client(r.wx);api.clear();await api.login();finish();await Promise.all([loading,showing]);await tick();assert.equal(viewCalls(r).length,0);p.onUnload();
});
test('a late detail response after onHide or unload cannot produce a display event',async()=>{
 let finish;const r=sign(runtime({'/posts/73':o=>{finish=()=>ok(o,post())},'/posts/73/comments':{items:[],hasMore:false},'/statistics/post-views':o=>ok(o,{accepted:true,eventId:o.data.eventId})}));
 const p=loadPage('pages/forum-detail/index.js',{...r,fixture:false});const loading=p.onLoad({id:'73'});const showing=p.onShow();await tick();p.onHide();finish();await Promise.all([loading,showing]);await tick();assert.equal(viewCalls(r).length,0);p.onUnload();
});
test('anonymous credential storage failure preserves its identity across unknown-outcome retries',async()=>{
 let issued=0,attempts=0;const r=runtime({'/statistics/anonymous-credential':o=>ok(o,{...credential,credential:'credential-'+(++issued)}),'/statistics/post-views':o=>{if(++attempts===1)o.fail({});else ok(o,{accepted:true,eventId:o.data.eventId})}});r.wx.setStorageSync=()=>{throw Error('storage full')};
 const queue=stats().viewStatistics(r.wx);queue.enqueue('73');await queue.flush();await queue.flush();assert.equal(issued,1);assert.equal(viewCalls(r)[0].data.anonymousCredential,viewCalls(r)[1].data.anonymousCredential);queue.dispose();
});
test('switching to fixture discards a pending live guest event before its credential returns',async()=>{
 let finish;const r=runtime({'/statistics/anonymous-credential':o=>{finish=()=>ok(o,credential)},'/statistics/post-views':o=>ok(o,{accepted:true,eventId:o.data.eventId})}),queue=stats().viewStatistics(r.wx);queue.enqueue('73');const pending=queue.flush();await tick();r.wx.__fixtureMode=true;finish();await pending;assert.equal(viewCalls(r).length,0);assert.equal(queue.pendingCount,0);queue.dispose();
});
test('first search entry only fetches the ranking once',async()=>{
 const r=runtime({'/posts/view-ranking':{items:[],available:false}});
 const page=loadPage('pages/search/index.js',{wx:r.wx,fixture:false});
 await page.onLoad({});
 await page.onShow();
 assert.equal(r.calls.filter(o=>o.url.endsWith('/posts/view-ranking')).length,1);
});
test('live ranking uses service Top5 and never requests the fifty-post approximation',async()=>{
 const r=runtime({'/posts/view-ranking':{items:[{post:post('901'),pv:12,uv:4}],window:{startDate:'2026-11-10',endDate:'2026-11-16',zone:'Asia/Shanghai',days:7},updatedAt:'2026-11-16T12:00:00Z',available:true}}),p=loadPage('pages/search/index.js',{...r,fixture:false});
 await p.readHot();assert.equal(p.data.hot[0].id,'901');assert.equal(p.data.hot[0].meta,'4 位访客 · 12 次浏览');assert.equal(p.data.hotFailed,false);assert.equal(r.calls.some(o=>o.url.includes('size=50')),false);assert.equal(p.data.hotUpdatedAt,'2026-11-16T12:00:00Z');p.onUnload();
});
test('empty/failure ranking states are honest and old responses cannot replace new ranks',async()=>{
 let first,count=0;const r=runtime({'/posts/view-ranking':o=>{if(++count===1){first=()=>ok(o,{items:[{post:post('old'),pv:4,uv:2}],window:{},available:true});return}ok(o,{items:[],window:{},updatedAt:null,available:false})}}),p=loadPage('pages/search/index.js',{...r,fixture:false});
 const old=p.readHot();await p.readHot();first();await old;assert.equal(p.data.hot.length,0);assert.equal(p.data.hotAvailable,false);assert.equal(p.data.hotFailed,false);p.onUnload();
 const fail=runtime({'/posts/view-ranking':new Error('offline')}),q=loadPage('pages/search/index.js',{...fail,fixture:false});await q.readHot();assert.equal(q.data.hot.length,0);assert.equal(q.data.hotFailed,true);q.onUnload();
});
for(const outcome of ['success','failure'])test('a late live ranking '+outcome+' cannot mutate fixture state after a mode switch',async()=>{
 let finish;const r=runtime({'/posts/view-ranking':o=>{finish=()=>outcome==='success'?ok(o,{items:[{post:post('901'),pv:12,uv:4}],window:{},updatedAt:null,available:true}):o.fail({})}}),p=loadPage('pages/search/index.js',{...r,fixture:false});
 const pending=p.readHot();r.wx.__fixtureMode=true;p.setData({hot:[{id:'fixture-sentinel'}],hotFailed:false,hotLoading:true,hotAvailable:true});finish();await pending;
 assert.equal(p.data.hot[0]?.id,'fixture-sentinel');assert.equal(p.data.hotFailed,false);assert.equal(p.data.hotLoading,true);assert.equal(p.data.hotAvailable,true);p.onUnload();
});
test('fixture exact cross-day UV uses seven Beijing calendar dates and includes old posts',()=>{
 const now=Date.parse('2026-11-16T16:01:00Z'),posts=[{id:'post-001',createdAt:1},{id:'post-002',createdAt:1},{id:'post-003',createdAt:1}];
 const event=(eventId,postId,visitor,time)=>({eventId,postId,visitor,occurredAt:Date.parse(time)}),events=[event('a','post-001','one','2026-11-10T16:00:00Z'),event('b','post-001','one','2026-11-16T15:59:00Z'),event('c','post-001','two','2026-11-16T16:00:00Z'),event('c','post-001','two','2026-11-16T16:00:00Z'),event('d','post-002','one','2026-11-10T15:59:59Z'),event('e','post-002','two','2026-11-16T16:01:00Z'),event('f','post-003','one','2026-11-16T16:01:00Z')];
 assert.deepEqual(stats().rankFixtureViews(posts,events,now).map(x=>[x.post.id,x.pv,x.uv]),[['post-001',3,2],['post-002',1,1],['post-003',1,1]]);assert.equal(stats().rankFixtureViews(posts,events,now+7*86400000).length,0);
});
test('equal fixture UV uses numeric post ID order without PV as an extra weight',()=>{
 const now=Date.now(),posts=[{id:'10'},{id:'2'}],events=[{eventId:'a',postId:'10',visitor:'one',occurredAt:now},{eventId:'b',postId:'10',visitor:'one',occurredAt:now},{eventId:'c',postId:'2',visitor:'one',occurredAt:now}];assert.deepEqual(stats().rankFixtureViews(posts,events,now).map(x=>x.post.id),['2','10']);
});
test('fixture demonstration browsing facts are stable when posts are reordered or unrelated posts are removed',()=>{
 const r=runtime();r.wx.__fixtureMode=true;const now=Date.now(),posts=[{id:'post-001'},{id:'post-002'},{id:'post-003'}],summary=list=>stats().fixtureRanking(r.wx,list,now).map(x=>[x.post.id,x.pv,x.uv]).sort();
 assert.deepEqual(summary(posts),summary([...posts].reverse()));assert.deepEqual(summary(posts).filter(x=>x[0]!=='post-002'),summary(posts.filter(x=>x.id!=='post-002')));
});
test('fixture detail and search share app-lifetime statistics even with separate wx wrappers',()=>{
 const app={},original=global.getApp;global.getApp=()=>app;
 try {const a=runtime(),b=runtime();a.wx.__fixtureMode=b.wx.__fixtureMode=true;const posts=[{id:'post-001'}],before=stats().fixtureRanking(b.wx,posts)[0];stats().detailVisit(a.wx,'post-001').displayed(true);const after=stats().fixtureRanking(b.wx,posts)[0];assert.equal(after.pv,before.pv+1);assert.equal(after.uv,before.uv+1)}finally{if(original===undefined)delete global.getApp;else global.getApp=original}
});
test('fixture detail/ranking work without live requests or private statistics storage',()=>{
 const r=runtime();r.wx.request=()=>{throw Error('fixture must not call live')};const p=loadPage('pages/forum-detail/index.js',r);p.onLoad({id:'post-001'});p.onShow();p.onShow();const q=loadPage('pages/search/index.js',r);q.readHot();assert.ok(q.data.hot.length);assert.ok(q.data.hot.every(item=>item.meta.includes('次浏览')));assert.equal([...r.saved.keys()].some(key=>key.includes('statistics')),false);
});

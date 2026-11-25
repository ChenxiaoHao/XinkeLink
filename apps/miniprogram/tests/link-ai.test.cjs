const {test}=require('node:test'),assert=require('node:assert/strict');
const {AiRepository}=require('../.test-dist/features/ai/repository');
const {AiController}=require('../.test-dist/features/ai/controller');
const {emitIdentityChange,onIdentityChange}=require('../.test-dist/features/profile/identity-events');
function runtime(live=false){const values=new Map([['xinkelink.session.v1',true]]);return {__fixtureMode:!live,getStorageSync:k=>values.get(k),setStorageSync:(k,v)=>values.set(k,v),values};}
const flush=()=>new Promise(r=>setImmediate(r));
test('demo sessions persist, retry deduplicates, changed content rejects, deletion removes history',async()=>{const r=new AiRepository(runtime());const s=await r.create();const m=await r.send(s.id,'request-1','学校请假规定');assert.match(m.answer,/【测试回答】.*虚构样例/);assert.ok(m.sources.length);assert.deepEqual(await r.send(s.id,'request-1','学校请假规定'),m);await assert.rejects(r.send(s.id,'request-1','不同问题'),/内容/);assert.equal((await r.read(s.id)).messages.length,1);assert.equal((await r.list()).length,1);await r.remove(s.id);await assert.rejects(r.read(s.id),/不存在/)});
test('demo covers post, empty, error and scope without writing posts',async()=>{const wx=runtime(),r=new AiRepository(wx),s=await r.create();assert.ok((await r.send(s.id,'p','找校园帖子')).posts.length);assert.match((await r.send(s.id,'e','找不存在的帖子')).answer,/当前未找到/);await assert.rejects(r.send(s.id,'x','演示错误'),/演示/);assert.match((await r.send(s.id,'o','帮我写帖子')).answer,/学校规章制度|公开帖子/);assert.equal([...wx.values.keys()].filter(k=>k.includes('forum')).length,0)});
test('guest cannot read or write AI sessions',async()=>{const wx=runtime(),r=new AiRepository(wx);wx.values.set('xinkelink.session.v1',false);await assert.rejects(r.list(),/登录/);await assert.rejects(r.create(),/登录/)});
test('live HTTP paths are encoded, stable requestId is sent, failures never demo fallback',async()=>{const wx=runtime(true),calls=[];wx.values.set('xinkelink.backend.session.v1',{accessToken:'x',refreshToken:'x',expiresAt:Date.now()+999999,me:{id:'a'}});wx.request=o=>{calls.push(o);o.success({statusCode:200,data:{code:'OK',data:new URL(o.url).pathname.endsWith('/messages')?{posts:[]}:o.method==='DELETE'?null:new URL(o.url).pathname.endsWith('/sessions')?[]:{messages:[]}}})};const r=new AiRepository(wx);await r.list();await r.create();await r.read('a/b');await r.send('a/b','stable','请假');await r.remove('a/b');assert.deepEqual(calls.map(c=>new URL(c.url).pathname),['/api/v1/ai/sessions','/api/v1/ai/sessions','/api/v1/ai/sessions/a%2Fb','/api/v1/ai/sessions/a%2Fb/messages','/api/v1/ai/sessions/a%2Fb']);assert.deepEqual(calls[3].data,{requestId:'stable',text:'请假'});wx.request=o=>o.fail();await assert.rejects(r.list(),/网络/);assert.equal([...wx.values.keys()].some(k=>k.includes('ai.demo')),false)});
function controlled(){let owner='alice';const data={},repo={fixture:false,identity:()=>owner,onIdentityChange,list:async()=>[],create:async()=>({id:'s',title:'新会话',updatedAt:''}),read:async id=>({id,title:'会话',messages:[]}),remove:async()=>{},send:async(id,r,q)=>({id:'m',requestId:r,question:q,answer:'答复',sources:[],posts:[],status:'completed'})};const c=new AiController(repo,p=>Object.assign(data,p));return {c,repo,data,owner:v=>owner=v};}
test('failed send retries same requestId, blocks concurrent send, then restores history',async()=>{const x=controlled(),ids=[];let fail=true;x.repo.send=async(s,id,q)=>{ids.push(id);if(fail)throw Error('timeout');return {id:'m',requestId:id,question:q,answer:'完成',sources:[],posts:[]}};await x.c.start();await x.c.send('问题');assert.match(x.data.error,/timeout/);fail=false;await x.c.retry();assert.equal(ids.length,2);assert.equal(ids[0],ids[1]);assert.equal(x.data.messages.length,1);assert.equal(x.data.error,'');x.c.stop()});
test('account switch clears immediately and discards late send',async()=>{const x=controlled();let finish;x.repo.send=()=>new Promise(r=>finish=r);await x.c.start();const pending=x.c.send('私密问题');await flush();x.owner('bob');emitIdentityChange();assert.equal(x.data.messages.length,0);finish({id:'secret',answer:'alice秘密'});await pending;assert.equal(x.data.messages.length,0);x.c.stop()});
test('delete current session invalidates pending response',async()=>{const x=controlled();let finish;x.repo.send=()=>new Promise(r=>finish=r);await x.c.start();const p=x.c.send('问题');await flush();await x.c.remove('s');finish({id:'late',answer:'不能回填'});await p;assert.equal(x.data.messages.length,0);assert.equal(x.data.sessionId,'');x.c.stop()});
test('late session read cannot overwrite a newer selected session',async()=>{const x=controlled();let finish;await x.c.start();x.repo.read=id=>id==='old'?new Promise(r=>finish=r):Promise.resolve({id,messages:[{id:'new-message'}]});const p=x.c.open('old');await x.c.open('new');finish({id:'old',messages:[{id:'secret'}]});await p;assert.equal(x.data.sessionId,'new');assert.equal(x.data.messages[0].id,'new-message');x.c.stop()});
test('old account 401 does not replay AI request under new account token',async()=>{const wx=runtime(true),{client}=require('../.test-dist/backend/client');wx.values.set('xinkelink.backend.session.v1',{accessToken:'alice-token',refreshToken:'x',expiresAt:Date.now()+999999,me:{id:'alice'}});let fail,calls=0;wx.request=o=>{calls++;const reject=()=>o.success({statusCode:401,data:{code:'UNAUTHENTICATED',message:'expired'}});if(calls>1)reject();else fail=reject};const r=new AiRepository(wx),p=r.list();const api=client(wx);api.setMe({id:'bob'});api.session.accessToken='bob-token';fail();await assert.rejects(p);assert.equal(calls,1)});
test('deleting a historical session rejects a late pre-delete list',async()=>{const x=controlled();await x.c.start();let finish,reads=0;x.repo.list=()=>++reads===1?new Promise(r=>finish=r):Promise.resolve([]);const p=x.c.list();await x.c.remove('historical');finish([{id:'historical',title:'deleted'}]);await p;assert.deepEqual(x.data.sessions,[]);x.c.stop()});
test('concurrent sending is locked and failed message status stays retryable',async()=>{const x=controlled();let finish,calls=0;x.repo.send=()=>{calls++;return new Promise(r=>finish=r)};await x.c.start();const p=x.c.send('问题');await flush();await x.c.send('另一个问题');assert.equal(calls,1);finish({id:'m',status:'failed',answer:'限流，请重试'});await p;assert.match(x.data.error,/限流/);assert.equal(x.data.messages.length,0);assert.equal(x.data.busy,false);x.c.stop()});
test('stop discards a pending history response and owner metadata',async()=>{const x=controlled();await x.c.start();let finish;x.repo.list=()=>new Promise(r=>finish=r);const p=x.c.list();x.c.stop();finish([{id:'secret'}]);await p;assert.deepEqual(x.data.sessions,[]);assert.equal(x.data.pendingQuestion,'');assert.equal(x.data.busy,false)});
test('backend uppercase ERROR is retryable with the original requestId',async()=>{const x=controlled(),ids=[];x.repo.read=async()=>({messages:[{id:'m',requestId:'original-id',question:'问题',answer:null,status:'ERROR',sources:[],posts:[]}]});x.repo.send=async(s,id)=>{ids.push(id);return {id:'m',requestId:id,question:'问题',status:'ERROR',answer:null,sources:[],posts:[]}};await x.c.start();await x.c.open('s');assert.match(x.data.error,/重试/);await x.c.retry();assert.deepEqual(ids,['original-id']);assert.equal(x.data.messages.length,0);assert.match(x.data.error,/重试/);x.c.stop()});
test('input bound matches server default before any model request',async()=>{const r=new AiRepository(runtime()),s=await r.create();await assert.rejects(r.send(s.id,'too-long','字'.repeat(1001)),/1000/);assert.equal((await r.read(s.id)).messages.length,0)});
test('live unconfigured service error is shown without any fixture persistence',async()=>{const wx=runtime(true);wx.values.set('xinkelink.backend.session.v1',{accessToken:'x',refreshToken:'x',expiresAt:Date.now()+999999,me:{id:'a'}});wx.request=o=>o.success({statusCode:503,data:{code:'AI_NOT_CONFIGURED',message:'AI 尚未配置'}});const r=new AiRepository(wx);await assert.rejects(r.send('s','stable','学校制度'),/AI 尚未配置/);assert.equal([...wx.values.keys()].some(k=>k.includes('ai.demo')),false)});
test('AI transport timeout can cover the multi-call pipeline without changing other requests',async()=>{const wx=runtime(true),calls=[];wx.values.set('xinkelink.backend.session.v1',{accessToken:'x',refreshToken:'x',expiresAt:Date.now()+999999,me:{id:'a'}});wx.request=o=>{calls.push(o);o.success({statusCode:200,data:{code:'OK',data:[]}})};await new AiRepository(wx,90000).list();await require('../.test-dist/backend/client').client(wx).request('/posts');assert.equal(calls[0].timeout,90000);assert.equal(calls[1].timeout,15000)});
test('logout during fixture send cannot persist a message under a guest storage key',async()=>{const wx=runtime(),r=new AiRepository(wx),s=await r.create(),p=r.send(s.id,'stable','学校规定');wx.values.set('xinkelink.session.v1',false);await assert.rejects(p,/登录/);assert.equal(wx.values.has('xinkelink.ai.demo.sessions:'),false);wx.values.set('xinkelink.session.v1',true);assert.equal((await r.read(s.id)).messages.length,0)});

const { loadPage } = require('./page-harness.cjs');
const { client } = require('../.test-dist/backend/client');
const backendSessionKey = 'xinkelink.backend.session.v1';
const liveSession = { accessToken: 'alice-token', refreshToken: 'alice-refresh', expiresAt: Date.now() + 999999, me: { id: 'alice' } };
const answer = { id: 'answer', requestId: 'original', question: '查找校园帖子', answer: '找到公开帖子', status: 'DONE', sources: [], posts: [] };
const sessionEvent = id => ({ currentTarget: { dataset: { id } } });
const success = (request, data) => request.success({ statusCode: 200, data: { code: 'OK', data } });

test('returning from a quoted post restores the same AI session and sends the follow-up there', async () => {
  const wx = runtime(true);
  wx.values.set(backendSessionKey, liveSession);
  const quoted = {
    ...answer,
    posts: [{
      id: 'public-post', category: 'campus', content: '公开帖子', images: [],
      author: { id: 'student', nickname: '同学', isAnonymous: false },
      createdAt: '2026-11-21T12:00:00Z', liked: false, favorited: false, likeCount: 0, commentCount: 0,
    }],
  };
  const detail = { id: 'thread', title: '校园帖子', messages: [quoted] };
  const visited = [];
  const sent = [];
  wx.navigateTo = options => visited.push(options.url);
  wx.request = request => {
    const pathname = new URL(request.url).pathname;
    if (request.method === 'POST') {
      sent.push(pathname);
      success(request, { ...answer, id: 'followup', requestId: request.data.requestId, question: request.data.text });
    } else {
      success(request, pathname.endsWith('/sessions') ? [detail] : detail);
    }
  };
  const page = loadPage('pages/ai/index.js', { wx, fixture: false });
  await page.onShow();
  await page.onOpenSession(sessionEvent('thread'));
  assert.equal(page.data.messages[0].posts[0].id, 'public-post');
  page.onOpenPost({ detail: { id: 'public-post' } });
  assert.deepEqual(visited, ['/pages/forum-detail/index?id=public-post']);
  page.onHide();
  assert.deepEqual(page.data.messages, []);

  await page.onShow();
  assert.equal(page.data.sessionId, 'thread');
  assert.equal(page.data.messages[0].id, 'answer');
  page.onInput({ detail: { value: '第二条呢' } });
  await page.onSend();
  assert.deepEqual(sent, ['/api/v1/ai/sessions/thread/messages']);
  page.onUnload();
});

test('live logout clears the rendered AI thread and history before navigation', async () => {
  const wx = runtime(true);
  wx.values.set(backendSessionKey, liveSession);
  const detail = { id: 'thread', title: '私人主题', messages: [answer] };
  wx.request = request => success(request, new URL(request.url).pathname.endsWith('/sessions') ? [detail] : detail);
  const page = loadPage('pages/ai/index.js', { wx, fixture: false });
  await page.onShow();
  await page.onOpenSession(sessionEvent('thread'));
  await page.onHistory();

  client(wx).clear();
  assert.equal(page.data.guest, true);
  assert.equal(page.data.historyVisible, false);
  assert.equal(page.data.sessionId, '');
  assert.deepEqual(page.data.messages, []);
  assert.deepEqual(page.data.sessions, []);
  page.onUnload();
});

const pendingAnswer = { ...answer, answer: null, status: 'PENDING' };

test('opening a pending answer refreshes history without resending the question', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { c, repo, data } = controlled();
  let reads = 0;
  let sends = 0;
  repo.read = async () => ({ messages: [++reads === 1 ? pendingAnswer : answer] });
  repo.send = async () => { sends++; return answer; };
  await c.start();
  await c.open('thread');
  assert.equal(data.busy, true);
  assert.equal(data.error, '');
  await c.retry();
  t.mock.timers.runAll();
  await flush();
  assert.equal(sends, 0);
  assert.equal(data.busy, false);
  assert.equal(data.pendingQuestion, '');
  assert.deepEqual(data.messages, [answer]);
  c.stop();
});

test('hiding the AI page discards an in-flight pending refresh and stops scheduling reads', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { c, repo, data } = controlled();
  let reads = 0;
  let respond;
  repo.read = () => ++reads === 1
    ? Promise.resolve({ messages: [pendingAnswer] })
    : new Promise(resolve => { respond = resolve; });
  await c.start();
  await c.open('thread');
  t.mock.timers.runAll();
  await flush();
  assert.equal(reads, 2);

  c.pause();
  respond({ messages: [answer] });
  await flush();
  t.mock.timers.runAll();
  await flush();
  assert.equal(reads, 2);
  assert.deepEqual(data.messages, []);
  assert.equal(data.pendingQuestion, '');
  c.stop();
});

test('a persistently pending message stops polling and retries with its original requestId', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const { c, repo, data } = controlled();
  let reads = 0;
  const sent = [];
  repo.read = async () => { reads++; return { messages: [pendingAnswer] }; };
  repo.send = async (sessionId, stableId) => { sent.push([sessionId, stableId]); return answer; };
  await c.start();
  await c.open('thread');
  for (let tick = 0; tick < 15; tick++) {
    t.mock.timers.runAll();
    await flush();
  }
  assert.ok(reads > 1 && reads < 15, `expected bounded history polling, got ${reads} reads`);
  assert.equal(data.busy, false);
  assert.match(data.error, /重试/);
  await c.retry();
  assert.deepEqual(sent, [['thread', 'original']]);
  c.stop();
});

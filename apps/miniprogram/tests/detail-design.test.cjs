const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const { loadPage } = require('./page-harness.cjs');
function runtime(live = false) {
  const saved = new Map(), requests = [], toasts = [];
  saved.set(live ? 'xinkelink.backend.session.v1' : 'xinkelink.session.v1', live
    ? { accessToken: 'a', refreshToken: 'r', expiresAt: Date.now() + 600000, me: { id: '17', nickname: '我' } } : true);
  const wx = { getStorageSync: k => saved.get(k), setStorageSync: (k,v) => saved.set(k,v),
    showToast: o => toasts.push(o.title), hideKeyboard(){}, showModal(o){o.success({confirm:false});},
    request(o){ requests.push(o); o.fail({errMsg:'offline'}); } };
  return { wx, saved, requests, toasts };
}
test('live server validation is reported without the network failure state',async()=>{
  const r=runtime(true),p=loadPage('pages/forum-detail/index.js',{...r,fixture:false});
  p._postId='73';p.setData({post:{id:'73'},commentDraft:'有效草稿',replyTarget:{id:'42',name:'同学'}});
  r.wx.request=o=>o.success({statusCode:400,data:{code:'VALIDATION_ERROR',message:'回复参数不合法'}});
  await p.onSubmitComment();assert.equal(p.data.sendFailed,false);assert.equal(p.data.commentDraft,'有效草稿');assert.equal(p.data.replyTarget.id,'42');
  assert.ok(r.toasts.includes('回复参数不合法'));assert.equal(p.data.sending,false);
});
test('live comment submission lock prevents a duplicate request until failure completes',async()=>{
  const r=runtime(true),p=loadPage('pages/forum-detail/index.js',{...r,fixture:false});let finish;
  p._postId='73';p.setData({post:{id:'73'},commentDraft:'只提交一次'});
  r.wx.request=o=>{r.requests.push(o);finish=()=>o.fail({errMsg:'offline'});};
  const first=p.onSubmitComment();await new Promise(resolve=>setImmediate(resolve));
  assert.equal(p.data.sending,true);await p.onSubmitComment();assert.equal(r.requests.length,1);
  finish();await first;assert.equal(p.data.sending,false);assert.equal(p.data.sendFailed,true);assert.equal(p.data.commentDraft,'只提交一次');
});
for (const live of [false, true]) {
  test(`${live ? 'live' : 'fixture'} comment failure retains draft and target, retries, and resets success`, async () => {
    const r = runtime(live), p = loadPage('pages/forum-detail/index.js', {...r, fixture: !live});
    if (live) { p._postId='73'; p.setData({post:{id:'73'},comments:[]}); p.reload=async()=>{}; }
    else p.onLoad({id:'post-001'});
    const target = live ? {id:'42',name:'同学'} : {id:p.data.comments[0].id,name:p.data.comments[0].authorName};
    p.setData({composerOpen:true,commentDraft:'保留这条回复',replyTarget:target});
    const write=r.wx.setStorageSync;
    if (!live) r.wx.setStorageSync=()=>{throw Error('quota');};
    await p.onSubmitComment();
    assert.equal(p.data.sendFailed,true); assert.equal(p.data.commentDraft,'保留这条回复');
    assert.deepEqual(p.data.replyTarget,target); assert.equal(p.data.sending,false); assert.equal(p.data.composerOpen,true);
    if (live) r.wx.request=o=>{r.requests.push(o);o.success({statusCode:200,data:{code:'OK',data:{id:'new-comment'}}});};
    else r.wx.setStorageSync=write;
    await p.onSubmitComment();
    assert.equal(p.data.sendFailed,false); assert.equal(p.data.commentDraft,''); assert.equal(p.data.replyTarget,null);
    if (live) { assert.equal(r.requests.length,2); assert.equal(r.requests[0].data.clientRequestId,r.requests[1].data.clientRequestId); }
  });
  test(`${live ? 'live' : 'fixture'} composer resets failure on editing, reopening and changing reply`, async () => {
    const r=runtime(live),p=loadPage('pages/forum-detail/index.js',{...r,fixture:!live});
    if(live)p.setData({post:{id:'73'},comments:[{id:'42',authorName:'同学',replies:[]}]});else p.onLoad({id:'post-001'});
    for(const action of [()=>p.onCommentInput({detail:{value:'修改'}}),()=>p.onOpenComposer(),()=>p.onCancelReply(),()=>p.onReplyTap({detail:{id:live?'42':p.data.comments[0].id}})]){
      p.setData({sendFailed:true});action();assert.equal(p.data.sendFailed,false);
    }
  });
  test(`${live ? 'live' : 'fixture'} cancelled login and invalid draft never become a send failure`, async () => {
    const r=runtime(live),p=loadPage('pages/forum-detail/index.js',{...r,fixture:!live});
    p._postId='73';p.setData({post:{id:'73'},commentDraft:'  '});await p.onSubmitComment();assert.equal(p.data.sendFailed,false);
    p.setData({commentDraft:'x'.repeat(1001)});await p.onSubmitComment();assert.equal(p.data.sendFailed,false);assert.equal(r.requests.length,0);
    r.saved.clear();p.setData({commentDraft:'取消登录'});
    if(live)require('../.test-dist/backend/client.js').client(r.wx).requireSession=async()=>false;
    await p.onSubmitComment();assert.equal(p.data.sendFailed,false);assert.equal(r.requests.length,0);
  });
}
test('image rendering preserves feed crop while detail uses natural ratio and retains image lifecycle bindings',()=>{
  const source=readFileSync('miniprogram/components/forum-post-card/index.wxml','utf8');
  const tag=source.match(/<image class="post-image[\s"][^>]+>/)[0];
  const mode=tag.match(/mode="{{(.*?)}}"/)[1];
  assert.equal(vm.runInNewContext(mode,{detail:true}),'widthFix');
  assert.equal(vm.runInNewContext(mode,{detail:false}),'aspectFill');
  for(const binding of ['bindload="onImageLoad"','binderror="onImageError"','data-source="{{item}}"'])assert.ok(tag.includes(binding));
});

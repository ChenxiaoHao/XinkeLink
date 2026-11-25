const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');
const { loadComponent } = require('./native-harness.cjs');
const repo = () => require('../.test-dist/features/forum/repository.js');
function runtime(overrides = {}) {
  const saved = new Map([['xinkelink.session.v1', true]]), calls = { toasts: [], previews: [], routes: [] };
  const wx = { getStorageSync: k => saved.get(k), setStorageSync: (k,v) => saved.set(k,v),
    showToast: x => calls.toasts.push(x.title), navigateTo: x => calls.routes.push(x.url),
    previewImage: x => calls.previews.push(x), env: { USER_DATA_PATH: 'wxfile://usr' },
    getFileSystemManager: () => ({ copyFileSync() {} }), ...overrides };
  return { saved, calls, wx };
}
test('detail projects anonymous identity, includes complete content and shares the exact public ID', () => {
  const r = runtime(); const p = loadPage('pages/forum-detail/index.js', r); p.onLoad({id:'post-002'});
  assert.equal(p.data.post.authorName, '匿名同学 023');
  assert.equal(JSON.stringify(p.data).includes('private-author'), false);
  assert.equal(p.onShareAppMessage().path, '/pages/forum-detail/index?id=post-002');
  assert.match(p.data.post.content, /核对特征/);
  p.onImageTap({currentTarget:{dataset:{index:0}}});
  assert.equal(r.calls.previews.length, 1);
});
test('detail and home reload the same saved interactions in both directions', () => {
  const r = runtime(); const home = loadPage('pages/forum/index.js', r); home.onLoad({});
  const p = loadPage('pages/forum-detail/index.js', r); p.onLoad({id:'post-001'});
  p.onLikeTap(); p.onCollectTap(); home.onShow();
  assert.equal(home.data.posts[0].likeCount, 29); assert.equal(home.data.posts[0].collected, false);
  home.onLikePost({detail:{id:'post-001'}}); p.onShow(); assert.equal(p.data.post.liked,false);
});
test('unreadable interaction storage blocks writes and retains the visible detail', () => {
  const r = runtime(); const p = loadPage('pages/forum-detail/index.js', r); p.onLoad({id:'post-001'});
  r.wx.getStorageSync = () => { throw new Error('read'); };
  p.onLikeTap(); assert.equal(r.saved.size, 1); assert.equal(p.data.post.likeCount, 28);
  assert.equal(r.calls.toasts.length,1);
  assert.throws(() => repo().readForum(r.wx));
});
test('missing detail is empty and cannot write interactions', () => {
  const r = runtime(); const p = loadPage('pages/forum-detail/index.js',r);p.onLoad({id:'missing'});p.onLikeTap();
  assert.equal(p.data.post,null); assert.equal(r.saved.size,1);
});
test('feed card opens its public ID while buttons keep their own events', () => {
  const {instance,events}=loadComponent('components/forum-post-card/index.js'); instance.data.post={id:'post-002'};
  instance.onOpenTap();instance.onLikeTap(); assert.equal(events[0].name,'open');assert.equal(events[0].detail.id,'post-002');
  assert.equal(events[1].name,'like');
});

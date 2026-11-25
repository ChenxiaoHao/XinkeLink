const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');
const profile = require('../.test-dist/features/profile/repository.js');
const forum = require('../.test-dist/features/forum/repository.js');

function runtime(loggedIn = false) {
  const saved = new Map([[profile.SESSION_KEY, loggedIn]]), reads = [], writes = [], routes = [], modals = [];
  const wx = { __fixtureMode: true,
    getStorageSync(key) { reads.push(key); return saved.get(key); },
    setStorageSync(key, value) { writes.push(key); saved.set(key, value); },
    showModal(options) { modals.push(options); }, showActionSheet(options) { modals.push(options); }, showToast() {}, stopPullDownRefresh() {},
    navigateTo(options) { routes.push(options.url); }, redirectTo(options) { routes.push(options.url); },
  };
  return { wx, saved, reads, writes, routes, modals };
}
const page = (name, r) => loadPage(`pages/${name}/index.js`, { wx: r.wx });
const touch = id => ({ currentTarget: { dataset: { id } } });
const preference = (key, value) => ({ currentTarget: { dataset: { key } }, detail: { value } });

for (const name of ['chat-settings', 'profile', 'my-posts', 'my-collections']) {
  for (const sessionState of ['guest', 'missing', 'malformed', 'unreadable']) {
    test(`${name} deep-link reload isolates private reads with ${sessionState} session`, () => {
      const r = runtime();
      if (sessionState === 'missing') r.saved.delete(profile.SESSION_KEY);
      if (sessionState === 'malformed') r.saved.set(profile.SESSION_KEY, 'true');
      if (sessionState === 'unreadable') r.wx.getStorageSync = key => { r.reads.push(key); throw Error('disk'); };
      const p = page(name, r);
      if (name === 'chat-settings') p.onLoad({ peerId: 'mock-lin' }); else p.onShow();
      p.reload();
      assert.equal(p.data.loggedIn, false);
      assert.ok(r.reads.every(key => key === profile.SESSION_KEY), `private keys read: ${r.reads}`);
      assert.equal(r.writes.length, 0);
      if (name === 'profile') {
        assert.equal(p.data.profile.studentId, ''); assert.equal(p.data.profile.nickname, '访客');
        assert.equal(p.data.likedPosts.length, 0); assert.equal(p.data.postCount, 0);
        assert.ok(r.routes.includes('/pages/user-home/index?id=local-student'));
      } else if (name === 'chat-settings') {
        assert.equal(p.data.peer, null); assert.equal(p.data.historyMatches.length, 0);
      } else assert.equal(p.data.posts.length, 0);
    });
  }
  test(`${name} logout refresh removes previously displayed private data`, () => {
    const r = runtime(true);
    profile.saveProfile(r.wx, { ...profile.defaultProfile(), studentId: 'private-id' });
    forum.publishForumPost(r.wx, { content: 'private anonymous post', images: [], category: 'campus', anonymous: true }, []);
    forum.togglePostInteraction(r.wx, 'post-003', 'liked');
    const p = page(name, r);
    if (name === 'chat-settings') p.onLoad({ peerId: 'mock-lin' }); else p.onShow();
    r.saved.set(profile.SESSION_KEY, false); r.reads.length = 0;
    p.onShow();
    assert.equal(p.data.loggedIn, false);
    assert.ok(r.reads.every(key => key === profile.SESSION_KEY));
    if (name === 'profile') { assert.equal(p.data.profile.studentId, ''); assert.equal(p.data.likedPosts.length, 0); }
    else if (name === 'chat-settings') { assert.equal(p.data.peer, null); assert.equal(p.data.historyMatches.length, 0); }
    else assert.equal(p.data.posts.length, 0);
  });
}

test('chat settings guest handlers cannot read search history, navigate or mutate', () => {
  const r = runtime(), p = page('chat-settings', r);
  p._peerId = 'mock-lin'; p.setData({ peer: { id: 'mock-lin' }, historyMatches: [{ id: 'private-message', text: 'secret' }] });
  p.onHistoryInput({ detail: { value: 'secret' } }); p.onFindHistoryTap();
  p.onHistoryMessageTap(touch('private-message')); p.onReportTap();
  p.onPreferenceChange(preference('pinned', true)); p.onClearHistoryTap();
  assert.ok(r.reads.every(key => key === profile.SESSION_KEY));
  assert.equal(r.writes.length, 0); assert.equal(r.routes.length, 0);
  assert.equal(p.data.historyOpen, false); assert.equal(p.data.historyMatches.length, 0);
  assert.ok(r.modals.every(modal => modal.confirmText === '一键登录'));
});

for (const action of ['block', 'clear']) {
  test(`chat settings rechecks identity after ${action} confirmation`, () => {
    const r = runtime(true), p = page('chat-settings', r); p.onLoad({ peerId: 'mock-lin' });
    if (action === 'block') p.onPreferenceChange(preference('blocked', true)); else p.onClearHistoryTap();
    const confirm = r.modals.at(-1); assert.ok(confirm);
    r.saved.set(profile.SESSION_KEY, false); r.writes.length = 0; r.reads.length = 0;
    confirm.success({ confirm: true });
    assert.equal(r.writes.length, 0); assert.ok(r.reads.every(key => key === profile.SESSION_KEY));
    assert.equal(p.data.peer, null);
  });
}

test('private profile menu and stale edit and publish actions require a current session', () => {
  const r = runtime(), p = page('profile', r);
  p.onMyPostsTap(); p.onCollectionsTap(); p.onEditTap(); p.onMoreTap();
  const posts = page('my-posts', r); posts.setData({ posts: [{ id: 'private' }] });
  posts.onEditTap(touch('private')); posts.onPublishTap();
  assert.equal(r.routes.length, 0); assert.ok(r.modals.length > 0);
  assert.ok(r.modals.every(modal => modal.confirmText === '一键登录'));
});

test('my posts deletion confirmation cannot mutate after logout', () => {
  const r = runtime(true);
  const own = forum.publishForumPost(r.wx, { content: 'private', images: [], category: 'campus', anonymous: true }, []);
  const p = page('my-posts', r); p.onShow(); p.onDeleteTap(touch(own.id));
  const confirm = r.modals.at(-1); r.saved.set(profile.SESSION_KEY, false); r.writes.length = 0;
  confirm.success({ confirm: true });
  assert.equal(r.writes.length, 0); assert.equal(p.data.posts.length, 0); assert.equal(p.data.busyId, '');
});

const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');
const { loadComponent } = require('./native-harness.cjs');
const { client } = require('../.test-dist/backend/client.js');

function runtime(handlers = {}) {
  const me = { id: '17', nickname: '旧账号私密昵称', avatar: null, version: 1 };
  const saved = new Map([['xinkelink.backend.session.v1', { accessToken: 'a', refreshToken: 'r', expiresAt: Date.now() + 600000, me }]]);
  const calls = [], modals = [];
  const wx = { getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value),
    showToast() {}, showModal(options) { modals.push(options); },
    request(options) {
      calls.push(options);
      const path = new URL(options.url).pathname.replace('/api/v1', '');
      if (handlers[path]) return handlers[path](options);
      ok(options, path === '/me' ? me : { items: [], hasMore: false });
    },
  };
  return { wx, saved, calls, modals, me };
}
function ok(options, data) { options.success({ statusCode: 200, data: { code: 'OK', data } }); }
const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };

for (const action of ['block', 'clear', 'delete', 'report']) {
  test(`live ${action} confirmation stops if the account logs out while the modal is open`, async () => {
    const r = runtime();
    const name = action === 'delete' ? 'my-posts' : action === 'report' ? 'report' : 'chat-settings';
    const p = loadPage(`pages/${name}/index.js`, { wx: r.wx, fixture: false });
    p._conversationId = '31'; p._peerId = '18';
    p.setData({ posts: [{ id: '51', version: 1 }], preferences: { version: 1 }, targetId: '51', targetKind: 'post', targetMissing: false, reason: '其他违规行为', detail: '请核查', evidence: [] });
    let pending;
    if (action === 'block') pending = p.onPreferenceChange({ currentTarget: { dataset: { key: 'blocked' } }, detail: { value: true } });
    if (action === 'clear') pending = p.onClearHistoryTap();
    if (action === 'delete') pending = p.onDeleteTap({ currentTarget: { dataset: { id: '51' } } });
    if (action === 'report') pending = p.onSubmitReport();
    await flush();
    assert.equal(r.modals.length, 1, 'the real confirmation must open before logout');
    client(r.wx).clear(); const before = r.calls.length;
    r.modals[0].success({ confirm: true }); await pending;
    assert.equal(r.calls.length, before, 'a stale confirmation must not issue a read or write with the changed identity');
  });
}

test('a late drawer profile response cannot restore the signed-out nickname or statistics', async () => {
  let summary;
  const r = runtime({ '/me/summary': options => { summary = options; } });
  const { instance } = loadComponent('components/app-shell/index.js', { wx: r.wx, fixture: false });
  const pending = instance.refreshIdentity(); await flush(); assert.ok(summary);
  client(r.wx).clear(); await instance.refreshIdentity();
  ok(summary, { postCount: 42, receivedLikeCount: 99 }); await pending;
  assert.equal(instance.data.loggedIn, false);
  assert.equal(instance.data.identity.nickname, '访客');
  assert.equal(instance.data.identity.dynamicCount, 0);
  assert.equal(instance.data.identity.receivedLikeCount, 0);
});

test('late drawer preferences cannot restore signed-out account preferences', async () => {
  let response;
  const r = runtime({ '/me/preferences': options => { response = options; } });
  const { instance } = loadComponent('components/app-shell/index.js', { wx: r.wx, fixture: false });
  const pending = instance.loadPreferences(); assert.ok(response);
  client(r.wx).clear(); await instance.loadPreferences();
  ok(response, { version: 7, messages: false, interactions: false, announcements: false, sound: true }); await pending;
  assert.equal(instance.data.preferences.version, 1);
  assert.equal(instance.data.preferences.messages, true);
  assert.equal(instance.data.preferences.sound, false);
});

test('fixture chat logout refresh clears all previously visible private message data', () => {
  const saved = new Map([['xinkelink.session.v1', true]]);
  const wx = { getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value), showModal() {}, showToast() {} };
  const p = loadPage('pages/contact-chat/index.js', { wx }); p.onLoad({ peerId: 'mock-lin' });
  assert.ok(p.data.messages.length > 0);
  p.setData({ input: '未发送私密消息', failedText: '失败的私密消息' });
  saved.set('xinkelink.session.v1', false); p.onShow();
  assert.equal(p.data.messages.length, 0); assert.equal(p.data.peer, null);
  assert.equal(p.data.selfAvatar, ''); assert.equal(p.data.selfNickname, '');
  assert.equal(p.data.input, ''); assert.equal(p.data.failedText, ''); assert.equal(p.data.canSend, false);
});

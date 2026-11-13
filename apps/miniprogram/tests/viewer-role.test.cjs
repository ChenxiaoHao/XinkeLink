const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');
const { loadComponent } = require('./native-harness.cjs');

const { getViewerRole } = require('../.test-dist/features/profile/viewer.js');
const SESSION_KEY = 'xinkelink.session.v1';
const CONTACT_KEY = 'xinkelink.contact.v1';

function storageStub(session) {
  const saved = new Map();
  const wxStub = {
    __fixtureMode: true,
    getStorageSync: k => saved.get(k),
    setStorageSync: (k, v) => saved.set(k, v),
    navigateTo() {}, navigateBack() {}, switchTab() {}, stopPullDownRefresh() {},
    showToast() {}, showModal: options => { wxStub.modals.push(options); },
    nextTick(callback) { callback(); },
    getWindowInfo() { return { statusBarHeight: 20, windowWidth: 375 } },
    getMenuButtonBoundingClientRect() { return { height: 32, top: 24, left: 300 } },
  };
  wxStub.modals = [];
  if (session !== undefined) saved.set(SESSION_KEY, session);
  return { saved, wxStub };
}

test('getViewerRole distinguishes guest, self and other', () => {
  const guest = storageStub(false).wxStub;
  assert.equal(getViewerRole(guest), 'guest', '未登录且无 authorId → guest');
  assert.equal(getViewerRole(guest, 'local-student'), 'guest', '未登录即使 authorId 是本人也 → guest');
  const self = storageStub(true).wxStub;
  assert.equal(getViewerRole(self, 'local-student'), 'self');
  assert.equal(getViewerRole(self, 'mock-lin'), 'other');
  assert.equal(getViewerRole(self), 'other', '已登录但未提供 authorId → other');
});

test('getViewerRole falls back to guest when the session read fails or is unavailable', () => {
  const broken = storageStub(undefined).wxStub;
  broken.setStorageSync(SESSION_KEY, 'corrupted');
  assert.equal(getViewerRole(broken), 'guest', '会话数据损坏（读取抛错）→ guest');
  assert.equal(getViewerRole(broken, 'local-student'), 'guest', '读取抛错时即使 authorId 匹配也 → guest');
  const noFixture = storageStub(true).wxStub;
  delete noFixture.__fixtureMode;
  assert.equal(getViewerRole(noFixture), 'guest', '后端会话不可用（client 抛错）→ guest');
});

test('guest tab bar hides the unread badge and writes nothing', async () => {
  const { saved, wxStub } = storageStub(false);
  const { instance } = loadComponent('custom-tab-bar/index.js', { wx: wxStub });
  await instance.refreshUnread();
  assert.equal(instance.data.unreadCount, 0, '访客角标应为 0');
  assert.equal(instance.data.messageUnread, 0);
  assert.equal(instance.data.notificationUnread, 0);
  assert.equal(saved.get(CONTACT_KEY), undefined, '访客不应产生任何写入');
});

test('logged-in tab bar still shows the unread badge', async () => {
  const { wxStub } = storageStub(true);
  const { instance } = loadComponent('custom-tab-bar/index.js', { wx: wxStub });
  await instance.refreshUnread();
  assert.ok(instance.data.unreadCount > 0, '演示种子数据下登录用户应有未读角标');
});

test('guest is blocked from new-chat with one login prompt and zero writes', () => {
  const { saved, wxStub } = storageStub(false);
  const page = loadPage('pages/new-chat/index.js', { wx: wxStub });
  page.onShow();
  assert.equal(wxStub.modals.length, 1, '访客进入应弹一键登录');
  assert.match(wxStub.modals[0].content, /登录/);
  assert.equal(page.data.peers.length, 0, '访客不应看到会话列表');
  assert.equal(saved.get(CONTACT_KEY), undefined, '访客进入不产生写入');
});

test('logged-in new-chat lists the conversations normally', () => {
  const { wxStub } = storageStub(true);
  const page = loadPage('pages/new-chat/index.js', { wx: wxStub });
  page.onShow();
  assert.equal(wxStub.modals.length, 0);
  assert.equal(page.data.peers.length, 3, '演示种子应有三位可发起对话的同学');
});

test('guest cannot read chat history and no read marks are written', () => {
  const { saved, wxStub } = storageStub(false);
  const page = loadPage('pages/contact-chat/index.js', { wx: wxStub });
  page.onLoad({ peerId: 'mock-lin' });
  page.onShow();
  assert.equal(wxStub.modals.length, 1, 'onLoad + onShow 双触发只弹一次登录');
  assert.equal(page.data.messages.length, 0, '访客不读历史消息');
  assert.equal(saved.get(CONTACT_KEY), undefined, '访客不产生已读标记写入');
});

test('logged-in chat loads history and records read marks normally', () => {
  const { saved, wxStub } = storageStub(true);
  const page = loadPage('pages/contact-chat/index.js', { wx: wxStub });
  page.onLoad({ peerId: 'mock-lin' });
  assert.equal(wxStub.modals.length, 0);
  assert.equal(page.data.messages.length, 1, '种子消息正常加载');
  assert.equal(page.data.peer.nickname, '林知夏');
  const thread = saved.get(CONTACT_KEY).threads['mock-lin'];
  assert.deepEqual(thread.readIds, ['seed-lin-1'], '登录后正常写入已读标记');
});

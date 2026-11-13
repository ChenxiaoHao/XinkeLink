const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');

const FALLBACK = '/assets/icons/user-round-muted.png';

function storageStub() {
  const saved = new Map();
  const wxStub = {
    __fixtureMode: true,
    getStorageSync: k => saved.get(k),
    setStorageSync: (k, v) => saved.set(k, v),
    navigateTo() {}, navigateBack() {}, switchTab() {}, stopPullDownRefresh() {},
    showToast() {}, showModal() {},
  };
  return { saved, wxStub };
}

test('demo chat send flips sending to true during the send and back to false afterwards', () => {
  const { saved, wxStub } = storageStub();
  const page = loadPage('pages/contact-chat/index.js', { wx: wxStub });
  saved.set('xinkelink.session.v1', true);
  page.onLoad({ peerId: 'mock-lin' });
  assert.equal(page.data.sending, false);
  page.setData({ input: '演示模式发送测试' });

  let duringSend = null;
  const rawSet = wxStub.setStorageSync;
  wxStub.setStorageSync = (k, v) => {
    if (k === 'xinkelink.contact.v1' && duringSend === null) duringSend = page.data.sending;
    return rawSet(k, v);
  };
  page.onSendTap();
  assert.equal(duringSend, true, 'sendText 执行期间 sending 应为 true');
  assert.equal(page.data.sending, false, '发送结束（finally）后 sending 应复位');
  assert.equal(page.data.input, '', '原有发送后清空输入的行为不变');
  const thread = saved.get('xinkelink.contact.v1').threads['mock-lin'];
  assert.equal(thread.messages.length, 1, '消息确实写入本地会话');
});

test('demo chat send refuses to run while sending is still true', () => {
  const { saved, wxStub } = storageStub();
  const page = loadPage('pages/contact-chat/index.js', { wx: wxStub });
  saved.set('xinkelink.session.v1', true);
  page.onLoad({ peerId: 'mock-lin' });
  page.setData({ input: '不应重复发送', sending: true });
  const threadBefore = JSON.stringify(saved.get('xinkelink.contact.v1'));
  page.onSendTap();
  assert.equal(page.data.input, '不应重复发送', 'sending 期间不应清空输入');
  assert.equal(JSON.stringify(saved.get('xinkelink.contact.v1')), threadBefore, 'sending 期间不应写入消息');
});

test('contact conversation avatar falls back to the default icon on image error', () => {
  const { saved, wxStub } = storageStub();
  const page = loadPage('pages/contact/index.js', { wx: wxStub });
  saved.set('xinkelink.session.v1', true);
  page.onShow();
  const conv = page.data.conversations[0];
  assert.ok(conv && conv.peer.avatar && conv.peer.avatar !== FALLBACK, '演示模式应加载带头像的会话');
  page.onRemoteAvatarError({ currentTarget: { dataset: { kind: 'conversation', id: conv.id, source: conv.peer.avatar } } });
  assert.equal(page.data.conversations[0].peer.avatar, FALLBACK);
  // source 已是默认头像时不再重复写（与真机实现的守卫一致）。
  page.onRemoteAvatarError({ currentTarget: { dataset: { kind: 'conversation', id: conv.id, source: FALLBACK } } });
  assert.equal(page.data.conversations[0].peer.avatar, FALLBACK);
});

test('notification avatar falls back to the default icon on image error', () => {
  const { saved, wxStub } = storageStub();
  const page = loadPage('pages/notifications/index.js', { wx: wxStub });
  saved.set('xinkelink.session.v1', true);
  page.onShow();
  const notice = page.data.notifications.find(item => item.type !== 'announcement' && item.avatar);
  assert.ok(notice, '演示模式应有带头像的通知种子');
  page.onRemoteAvatarError({ currentTarget: { dataset: { kind: 'notice', id: notice.id, source: notice.avatar } } });
  assert.equal(page.data.notifications.find(item => item.id === notice.id).avatar, FALLBACK);
  assert.equal(page.data.visibleNotifications.find(item => item.id === notice.id).avatar, FALLBACK);
});

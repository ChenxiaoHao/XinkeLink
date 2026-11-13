const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../miniprogram');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');

test('P1 screens for shared search, content reports and chat settings are registered', () => {
  const pages = JSON.parse(read('app.json')).pages;
  for (const page of ['pages/search/index', 'pages/report/index', 'pages/chat-settings/index']) {
    assert.ok(pages.includes(page), `missing ${page}`);
    for (const extension of ['json', 'ts', 'wxml', 'wxss']) assert.ok(fs.existsSync(path.join(root, `${page}.${extension}`)), `missing ${page}.${extension}`);
  }
});

test('search keeps local history and opens post results', () => {
  const { loadPage } = require('./page-harness.cjs');
  const saved = new Map(), routes = [];
  const wx = { getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value),
    navigateTo: options => routes.push(options.url), navigateBack() {}, switchTab() {}, showToast() {} };
  const page = loadPage('pages/search/index.js', { wx });
  page.onLoad({ type: 'posts', from: 'forum' });
  page.onInput({ detail: { value: '高数' } });
  assert.equal(page.data.hasSearched, false);
  assert.equal(page.data.postResults.length, 0, 'editing the input must wait for an explicit search confirmation');
  page.onSubmit();
  assert.equal(page.data.hasSearched, true);
  assert.ok(page.data.postResults.some(post => post.id === 'post-003'));
  assert.equal(page.data.history[0].query, '高数');
  assert.equal(page.data.history[0].type, 'posts');
  page.onOpenPost({ detail: { id: 'post-003' } });
  assert.equal(routes[0], '/pages/forum-detail/index?id=post-003');
});

test('search clear and cancel return to discovery, and unmatched queries show empty results', () => {
  const { loadPage } = require('./page-harness.cjs');
  const saved = new Map(); let back = 0, clearHistory;
  const wx = { getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value), navigateTo() {},
    navigateBack() {}, switchTab(options) { assert.equal(options.url, '/pages/forum/index'); back++ }, showToast() {}, showModal: options => { clearHistory = options; } };
  const page = loadPage('pages/search/index.js', { wx });
  page.onLoad({ type: 'posts', from: 'forum' });
  page.onInput({ detail: { value: 'no such campus item' } });
  assert.equal(page.data.postResults.length, 0);
  page.onSubmit();
  assert.equal(page.data.postResults.length, 0);
  page.onClearInput();
  assert.equal(page.data.query, '');
  assert.equal(page.data.postResults.length, 0);
  page.onCancelTap();
  assert.equal(back, 0);
  assert.equal(page.data.hasSearched, false);
  page.onClearHistory();
  assert.equal(page.data.history.length, 0);
});

test('local report validates required fields and records the public target', () => {
  const { submitLocalReport, readLocalReports, REPORT_REASONS } = require('../.test-dist/features/moderation/repository.js');
  const saved = new Map(), storage = { getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value) };
  assert.throws(() => submitLocalReport(storage, { targetKind: 'post', targetId: 'post-001', reason: '', detail: '', evidence: [] }));
  const report = submitLocalReport(storage, { targetKind: 'post', targetId: 'post-001', reason: REPORT_REASONS[0], detail: '请核实这条内容', evidence: [] });
  assert.equal(report.status, 'local-only');
  assert.equal(readLocalReports(storage)[0].targetId, 'post-001');
});

test('report page validates its target and only shows local-record feedback after save', () => {
  const { loadPage } = require('./page-harness.cjs');
  const saved = new Map(), modals = [], toasts = [];
  const wx = { getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value),
    showToast: options => toasts.push(options.title), showModal: options => { modals.push(options); }, navigateBack() {} };
  const page = loadPage('pages/report/index.js', { wx });
  page.onLoad({ kind: 'post', id: 'post-001' });
  assert.equal(page.data.targetMissing, false);
  page.onSubmitReport();
  assert.ok(toasts.some(message => message.includes('请选择举报原因')));
  page.onReasonTap({ currentTarget: { dataset: { reason: '其他违规行为' } } });
  page.onDetailInput({ detail: { value: '请核实此内容' } });
  page.onSubmitReport();
  assert.equal(modals[0].title, '举报已记录');
  assert.match(modals[0].content, /本地模拟记录/);
});

test('chat settings persist mute and pin, block sending, and clear local history', () => {
  const contact = require('../.test-dist/features/contact/repository.js');
  const settings = require('../.test-dist/features/contact/settings.js');
  const saved = new Map(), storage = { getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value) };
  settings.updatePeerChatSettings(storage, 'mock-su', { muted: true, pinned: true });
  assert.equal(contact.listConversations(storage)[0].id, 'mock-su');
  settings.updatePeerChatSettings(storage, 'mock-lin', { muted: true, pinned: true });
  assert.equal(settings.readPeerChatSettings(storage, 'mock-lin').pinned, true);
  assert.equal(contact.readConversation(storage, 'mock-lin').messages.length, 1);
  contact.clearConversationHistory(storage, 'mock-lin');
  assert.equal(contact.readConversation(storage, 'mock-lin').messages.length, 0);
  settings.updatePeerChatSettings(storage, 'mock-lin', { blocked: true });
  assert.throws(() => contact.sendText(storage, 'mock-lin', '你好'));
});

test('chat detail exposes its settings route for the current peer', () => {
  const { loadPage } = require('./page-harness.cjs');
  const saved = new Map(), routes = [];
  const wx = { getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value),
    navigateTo: options => routes.push(options.url) };
  const page = loadPage('pages/contact-chat/index.js', { wx });
  page.onLoad({ peerId: 'mock-lin' });
  page.onSettingsTap();
  assert.deepEqual(routes, ['/pages/chat-settings/index?peerId=mock-lin']);
});

test('chat detail reflects a saved block and keeps unsent text without writing it', () => {
  const { loadPage } = require('./page-harness.cjs');
  const saved = new Map(), toasts = [];
  const wx = { getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value),
    navigateTo() {}, showToast: options => toasts.push(options.title) };
  const settings = require('../.test-dist/features/contact/settings.js');
  settings.updatePeerChatSettings(wx, 'mock-lin', { blocked: true });
  const page = loadPage('pages/contact-chat/index.js', { wx });
  page.onLoad({ peerId: 'mock-lin' });
  assert.equal(page.data.canSend, false);
  page.onMessageInput({ detail: { value: '稍后再联系' } });
  page.onSendTap();
  assert.equal(page.data.input, '稍后再联系');
  assert.equal(saved.has('xinkelink.contact.v1'), true);
  assert.ok(toasts.some(message => message.includes('屏蔽')));
});

test('chat settings page opens a searchable message history and navigates back to the selected message', () => {
  const { loadPage } = require('./page-harness.cjs');
  const saved = new Map(), routes = [];
  const wx = { getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value),
    navigateTo: options => routes.push(options.url), showToast() {}, showModal() {} };
  const page = loadPage('pages/chat-settings/index.js', { wx });
  page.onLoad({ peerId: 'mock-lin' });
  assert.equal(page.data.loadFailed, false);
  page.onFindHistoryTap();
  page.onHistoryInput({ detail: { value: '明天下午' } });
  assert.equal(page.data.historyMatches.length, 1);
  page.onHistoryMessageTap({ currentTarget: { dataset: { id: page.data.historyMatches[0].id } } });
  assert.match(routes[0], /contact-chat\/index\?peerId=mock-lin&messageId=/);
});


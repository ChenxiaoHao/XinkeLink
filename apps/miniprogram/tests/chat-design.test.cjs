const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');
const fs = require('node:fs');
const path = require('node:path');

// Evaluate the real message-list WXML bindings; this is a small template harness,
// not a second implementation of the sending/failed visibility rules.
function renderedMessages(data) {
  const source = fs.readFileSync(path.join(__dirname, '../miniprogram/pages/contact-chat/index.wxml'), 'utf8');
  const fragment = source.slice(source.indexOf('<scroll-view'), source.indexOf('</scroll-view>') + 14);
  const root = { children: [] }, stack = [root];
  for (const token of fragment.match(/<[^>]+>|[^<]+/g)) {
    if (token.startsWith('</')) { stack.pop(); continue; }
    if (!token.startsWith('<')) { stack.at(-1).children.push({ text: token }); continue; }
    const tag = token.match(/^<([\w-]+)/)[1], attrs = {};
    for (const attr of token.slice(tag.length + 1).matchAll(/([\w:-]+)(?:="([^"]*)")?/g)) attrs[attr[1]] = attr[2] ?? '';
    const node = { tag, attrs, children: [] }; stack.at(-1).children.push(node);
    if (!token.endsWith('/>')) stack.push(node);
  }
  const evaluate = (expression, scope) => Function('scope', `with(scope) { return (${expression}); }`)(new Proxy(scope, { has: () => true, get: (target, key) => key === Symbol.unscopables ? undefined : target[key] }));
  const value = (text, scope) => text.replace(/{{(.*?)}}/g, (_, expression) => String(evaluate(expression, scope) ?? ''));
  function render(nodes, scope) {
    const result = []; let matched = false;
    for (const node of nodes) {
      if ('text' in node) { if (node.text.trim()) result.push({ text: value(node.text, scope) }); continue; }
      const a = node.attrs;
      if ('wx:if' in a) { matched = !!evaluate(a['wx:if'].slice(2, -2), scope); if (!matched) continue; }
      else if ('wx:else' in a) { if (matched) continue; matched = true; }
      else matched = false;
      const scopes = 'wx:for' in a ? evaluate(a['wx:for'].slice(2, -2), scope).map((item, index) => ({ ...scope, item, index })) : [scope];
      for (const inner of scopes) result.push({ tag: node.tag, attrs: Object.fromEntries(Object.entries(a).map(([k, v]) => [k, value(v, inner)])), children: render(node.children, inner) });
    }
    return result;
  }
  const flat = [];
  function visit(nodes) { for (const node of nodes) { flat.push(node); if (node.children) visit(node.children); } }
  visit(render(root.children, { peer: { nickname: '林知夏', avatar: '' }, selfNickname: '我', selfAvatar: '', scrollTo: '', ...data }));
  return flat;
}

test('real WXML renders one transient bubble, never changes a sent bubble to pending, and shows one latest-own status', () => {
  const messages = [{ id: 'one', text: '已发原文', fromSelf: true }, { id: 'two', text: '对方回复', fromSelf: false }];
  for (const state of [
    { sending: false, input: '', failedText: '', expected: ['已发原文', '对方回复'], statuses: ['已发送'] },
    { sending: true, input: '新原文', failedText: '', expected: ['已发原文', '对方回复', '新原文'], statuses: ['发送中…'] },
    { sending: false, input: '新原文', failedText: '新原文', expected: ['已发原文', '对方回复', '新原文'], statuses: ['未保存 · 点击重试'] },
    { sending: true, input: '新原文', failedText: '新原文', expected: ['已发原文', '对方回复', '新原文'], statuses: ['发送中…'] },
  ]) {
    const rendered = renderedMessages({ messages, lastSelfMessageId: 'one', ...state });
    const text = node => (node.children || []).map(child => child.text || '').join('');
    const hasClass = (node, name) => (node.attrs?.class || '').split(/\s+/).includes(name);
    assert.deepEqual(rendered.filter(n => hasClass(n, 'chat-bubble')).map(text), state.expected);
    assert.deepEqual(rendered.filter(n => hasClass(n, 'chat-send-status') || hasClass(n, 'chat-retry-send')).map(text), state.statuses);
    assert.equal(rendered.filter(n => n.attrs?.id === 'failed-message').length, state.expected.length === 3 ? 1 : 0);
    const first = rendered.find(n => n.attrs?.id === 'message-one');
    assert.ok(!first.attrs.class.includes('failed'));
  }
  const empty = renderedMessages({ messages: [], sending: false, failedText: '', input: '' });
  assert.equal(empty.filter(n => n.attrs?.class === 'chat-empty').length, 1);
  const firstSend = renderedMessages({ messages: [], sending: true, failedText: '', input: '第一句' });
  assert.equal(firstSend.filter(n => n.attrs?.class === 'chat-empty').length, 0);
  assert.equal(firstSend.filter(n => n.attrs?.class === 'chat-bubble').length, 1);
});

function runtime(blocked = false) {
  const me = { id: '17', nickname: '我', avatar: null, bio: '' };
  const store = new Map([['xinkelink.backend.session.v1', { accessToken: 'access', refreshToken: 'refresh', expiresAt: Date.now() + 600000, me }]]);
  const pending = [], sent = [], extraItems = [];
  const wx = { getStorageSync: k => store.get(k), setStorageSync: (k, v) => store.set(k, v), showToast() {}, request(o) {
    const route = new URL(o.url).pathname.replace('/api/v1', '');
    if (route.endsWith('/messages') && o.method === 'POST') { pending.push(o); sent.push(o.data); return; }
    const data = route === '/me' ? me : route.endsWith('/settings') ? { blocked, pinned: false, muted: false, version: 0 }
      : route.endsWith('/messages') ? { items: [
        { id: '41', sequence: '1', text: '原来的消息', isMine: true, createdAt: '2026-11-14T00:00:00Z', visibility: 'normal', persistenceStatus: 'sent' },
        { id: '42', sequence: '2', text: '对方回复', isMine: false, createdAt: '2026-11-14T00:01:00Z', visibility: 'normal', persistenceStatus: 'sent' },
        ...extraItems,
      ], hasMore: false, nextBeforeSequence: null }
      : route.endsWith('/read') ? { readThroughSequence: '2' } : { id: '31', peer: { id: '18', nickname: '林知夏', avatar: null } };
    o.success({ statusCode: 200, data: { code: 'OK', data } });
  } };
  return { wx, pending, sent, extraItems };
}
const tick = () => new Promise(resolve => setImmediate(resolve));

test('live history pagination still fetches decimal beforeSequence and preserves both old and current messages',async()=>{
  const r=runtime(),original=r.wx.request,queries=[];
  r.wx.request=o=>{
    const url=new URL(o.url);
    if(url.pathname.endsWith('/messages') && o.method!=='POST'){
      queries.push(url.searchParams.get('beforeSequence'));
      const older=url.searchParams.has('beforeSequence');
      return o.success({statusCode:200,data:{code:'OK',data:{items:older
        ? [{id:'40',sequence:'0',text:'更早的消息',isMine:false,createdAt:'2026-11-13T00:00:00Z',visibility:'normal',persistenceStatus:'sent'}]
        : [{id:'41',sequence:'1',text:'当前的消息',isMine:true,createdAt:'2026-11-14T00:00:00Z',visibility:'normal',persistenceStatus:'sent'}],hasMore:!older,nextBeforeSequence:older?null:'1'}}});
    }
    return original(o);
  };
  const p=loadPage('pages/contact-chat/index.js',{wx:r.wx,fixture:false});
  await p.onLoad({conversationId:'31'});assert.deepEqual(p.data.messages.map(m=>m.id),['41']);
  await p.onScrollToUpper();assert.ok(queries.includes('1'));assert.deepEqual(p.data.messages.map(m=>m.id),['40','41']);
  const count=queries.length;require('../.test-dist/backend/client.js').client(r.wx).requireSession=async()=>false;
  await p.onScrollToUpper();assert.equal(queries.length,count);p.onUnload();
});

test('authoritative GET acknowledges only the current client ID while POST is pending or its response is lost', async () => {
  for (const match of [true, false]) {
    const r = runtime(), p = loadPage('pages/contact-chat/index.js', { wx: r.wx, fixture: false });
    await p.onLoad({ conversationId: '31' });
    p.setData({ input: '同样的正文' }); const post = p.onSendTap(); await tick();
    r.extraItems.push({ id: '43', sequence: '3', text: '同样的正文', isMine: true, clientMessageId: match ? r.sent[0].clientMessageId : 'different-id', createdAt: '2026-11-14T00:02:00Z', visibility: 'normal', persistenceStatus: 'sent' });
    // Preserve a separately edited draft when acknowledging the previous submission.
    if (match) p.setData({ input: '下一份草稿' });
    await p.reload();
    assert.equal(p.data.sendAcknowledged, match);
    const transientCount = () => renderedMessages(p.data).filter(n => n.attrs?.id === 'failed-message').length;
    assert.equal(transientCount(), match ? 0 : 1);
    assert.equal(p.data.sending, true);
    r.pending.shift().fail({ errMsg: 'POST response lost' }); await post;
    assert.equal(p.data.failedText, match ? '' : '同样的正文');
    assert.equal(transientCount(), match ? 0 : 1);
    if (match) assert.equal(p.data.input, '下一份草稿');
    p.onUnload();
  }
});

test('a committed GET clears a prior failure and a fresh outgoing attempt resets acknowledgement', async () => {
  const r = runtime(), p = loadPage('pages/contact-chat/index.js', { wx: r.wx, fixture: false });
  await p.onLoad({ conversationId: '31' });
  p.setData({ input: '已保存但响应丢失' }); const first = p.onSendTap(); await tick();
  r.pending.shift().fail({ errMsg: 'offline' }); await first;
  assert.equal(p.data.failedText, '已保存但响应丢失');
  r.extraItems.push({ id: '43', sequence: '3', text: '已保存但响应丢失', isMine: true, clientMessageId: r.sent[0].clientMessageId, createdAt: '2026-11-14T00:02:00Z', visibility: 'normal', persistenceStatus: 'sent' });
  await p.reload();
  assert.equal(p.data.failedText, ''); assert.equal(p.data.input, '');
  p.setData({ input: '新的发送' }); const second = p.onSendTap(); await tick();
  assert.equal(p.data.sendAcknowledged, false);
  assert.notEqual(r.sent[1].clientMessageId, r.sent[0].clientMessageId);
  r.pending.shift().fail({ errMsg: 'offline' }); await second;
  assert.equal(p.data.failedText, '新的发送'); p.onUnload();
});

test('latest own message remains identifiable when a peer replies', async () => {
  const r = runtime(), p = loadPage('pages/contact-chat/index.js', { wx: r.wx, fixture: false });
  await p.onLoad({ conversationId: '31' });
  assert.equal(p.data.lastSelfMessageId, '41');
  assert.equal(p.data.ownBlocked, false);
  p.onUnload();
});

test('failed retry sends the failed text with the same client ID and preserves persisted history during sending', async () => {
  const r = runtime(), p = loadPage('pages/contact-chat/index.js', { wx: r.wx, fixture: false });
  await p.onLoad({ conversationId: '31' });
  const history = JSON.stringify(p.data.messages);
  p.setData({ input: '失败的原文' });
  const first = p.onSendTap(); await tick();
  assert.equal(p.data.sending, true);
  assert.equal(JSON.stringify(p.data.messages), history);
  r.pending.shift().fail({ errMsg: 'offline' }); await first;
  assert.equal(p.data.failedText, '失败的原文');
  // A retained failed bubble must retry itself even if the composer was restored separately.
  p.setData({ input: '另一份草稿' });
  const retry = p.onRetrySendTap(); await tick();
  assert.equal(r.sent[1].text, '失败的原文');
  assert.equal(r.sent[1].clientMessageId, r.sent[0].clientMessageId);
  const duplicate = p.onRetrySendTap(); await tick();
  assert.equal(r.sent.length, 2);
  r.pending.shift().success({ statusCode: 200, data: { code: 'OK', data: {} } });
  await Promise.all([retry, duplicate]);
  assert.equal(p.data.sending, false); assert.equal(p.data.failedText, ''); assert.equal(p.data.input, '');
  p.onUnload();
});

test('settings link is enabled only by an explicit own block', async () => {
  for (const blocked of [true, false, undefined]) {
    const r = runtime(blocked), p = loadPage('pages/contact-chat/index.js', { wx: r.wx, fixture: false });
    await p.onLoad({ conversationId: '31' });
    assert.equal(p.data.ownBlocked, blocked === true);
    if (blocked) assert.equal(p.data.blockedReason, '你已屏蔽这位同学，解除后才能发送消息');
    p.onUnload();
  }
});

test('fixture send rechecks a newly applied own block and retry uses its retained failed text', () => {
  const store = new Map([['xinkelink.session.v1', true]]);
  const wx = { getStorageSync: k => store.get(k), setStorageSync: (k, v) => store.set(k, v), showToast() {} };
  const p = loadPage('pages/contact-chat/index.js', { wx }); p.onLoad({ peerId: 'mock-ajie' });
  store.set('xinkelink.contact.settings.v1', { version: 1, peers: { 'mock-ajie': { pinned: false, muted: false, blocked: true } } });
  p.setData({ input: '不能发送' }); p.onSendTap();
  assert.equal(p.data.ownBlocked, true);
  assert.equal(p.data.blockedReason, '你已屏蔽这位同学，解除后才能发送消息');
  assert.equal(p.data.messages.length, 0);
  store.delete('xinkelink.contact.settings.v1'); p.refreshConversation();
  wx.setStorageSync = () => { throw Error('quota'); };
  p.setData({ input: '失败的原文' }); p.onSendTap();
  assert.equal(p.data.failedText, '失败的原文');
  p.setData({ input: '另一份草稿' });
  wx.setStorageSync = (k, v) => store.set(k, v);
  p.onRetrySendTap();
  assert.equal(p.data.messages.at(-1).text, '失败的原文');
  assert.equal(p.data.failedText, '');
});

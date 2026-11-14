const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');
const { client } = require('../.test-dist/backend/client.js');
const sessionKey = 'xinkelink.backend.session.v1';
test('registered pages are safe beta return destinations',()=>{
 const {safeBetaTarget}=require('../.test-dist/backend/beta.js');
 for(const route of ['/pages/new-chat/index','/pages/drafts/index','/pages/feedback/index'])assert.equal(safeBetaTarget(route),route);
 assert.equal(safeBetaTarget('/pages/new-chat/index#injected'),'/pages/forum/index');
 // account-feature 页面已下线，不再是合法的登录后回跳目标。
 assert.equal(safeBetaTarget('/pages/account-feature/index'),'/pages/forum/index');
});
const ok = (o, data) => o.success({ statusCode: 200, data: { code: 'OK', data } });
const failure = (o, statusCode, code, message = '请求未完成') => o.success({ statusCode, data: { code, message } });
function runtime({ authenticated = false, activated = false, phase = 'beta', handlers = {} } = {}) {
  const saved = new Map(), calls = [], routes = [], feedback = [];
  let me = { id: '91', nickname: '内测同学', avatar: null, version: 0 };
  if (authenticated) saved.set(sessionKey, { accessToken: 'access', refreshToken: 'refresh', expiresAt: Date.now() + 600000, me });
  const wx = {
    getStorageSync: k => saved.get(k), setStorageSync: (k, v) => saved.set(k, v), removeStorageSync: k => saved.delete(k),
    login: o => o.success({ code: 'native-login-code' }),
    request(o) {
      const path = new URL(o.url).pathname.replace('/api/v1', ''); calls.push({ path, ...o });
      if (handlers[path]) return handlers[path](o);
      if (path === '/beta/access') return ok(o, { phase, authenticated: !!o.header.Authorization, activated });
      if (path === '/auth/wechat') return ok(o, { accessToken: 'access', refreshToken: 'refresh', expiresIn: 900, me });
      if (path === '/me') { if (o.method === 'PATCH') me = { ...me, nickname: o.data.nickname, avatar: { imageId: o.data.avatarImageId, url: '/avatar' }, version: 1 }; return ok(o, me); }
      if (path === '/auth/logout') return ok(o, { revoked: true });
      if (path === '/posts' || path === '/announcements') return ok(o, { items: [], hasMore: false, nextCursor: null });
      return failure(o, 404, 'NOT_FOUND');
    },
    uploadFile(o) { calls.push({ path: '/images', ...o }); o.success({ statusCode: 201, data: JSON.stringify({ code: 'OK', data: { id: '101' } }) }); },
    showToast: o => feedback.push(o.title), reLaunch: o => { routes.push(o.url); o.success?.({}); },
    redirectTo: o => { routes.push(o.url); o.success?.({}); }, switchTab: o => { routes.push(o.url); o.success?.({}); },
    stopPullDownRefresh() {}, pageScrollTo() {},
  };
  return { wx, calls, routes, saved, feedback };
}
const page = (name, r) => loadPage(`pages/${name}/index.js`, { wx: r.wx, fixture: false });
const turn = () => new Promise(resolve => setImmediate(resolve));

test('a shared business page checks live beta access before reading its post or comments', async () => {
  const r = runtime(); const p = page('forum-detail', r);
  await p.onLoad({ id: '9007199254740993' });
  assert.equal(r.calls.some(c => c.path.startsWith('/posts')), false, 'unactivated users must not read business content');
  assert.equal(r.calls[0].path, '/beta/access');
  assert.equal(r.routes.length, 1);
  assert.equal(decodeURIComponent(r.routes[0].split('?redirect=')[1]), '/pages/forum-detail/index?id=9007199254740993');
});
test('a failed access check cannot fall through to business content and concurrent lifecycles redirect only once', async () => {
  const r = runtime({ handlers: { '/beta/access': o => o.fail({ errMsg: 'offline' }) } });
  const p = page('forum', r); await Promise.all([p.onLoad({}), p.onShow()]);
  assert.equal(r.calls.some(c => c.path === '/posts'), false);
  assert.equal(r.routes.length, 1);
});

test('cold start is a dedicated native gate with all existing routes retained', () => {
  const app = require('../miniprogram/app.json');
  assert.equal(app.pages[0], 'pages/beta-access/index');
  assert.ok(app.pages.includes('pages/feedback/index'));
  assert.ok(app.pages.includes('pages/forum-detail/index'));
  assert.equal(app.tabBar.list.length, 5);
});
test('an anonymous visitor sees login first and cannot submit a code before native profile confirmation', async () => {
  const r = runtime(), p = page('beta-access', r); await p.onLoad({});
  assert.equal(p.data.stage, 'login'); await p.onActivateTap();
  assert.equal(r.calls.some(c => c.path === '/beta/redeem'), false);
  const login = p.onLoginTap(); await turn();
  assert.equal(p.data.authVisible, true);
  assert.equal(r.calls.some(c => c.path === '/auth/wechat'), false);
  p.onProfileAuthCancel(); await login;
  assert.equal(p.data.stage, 'login'); assert.equal(client(r.wx).loggedIn, false);
  assert.ok(p.data.error.includes('取消'));
});
test('native avatar and nickname save completes before code entry and redemption restores the shared destination', async () => {
  const r = runtime({ handlers: { '/beta/redeem': o => ok(o, { phase: 'beta', authenticated: true, activated: true }) } });
  const p = page('beta-access', r); await p.onLoad({ redirect: '/pages/forum-detail/index?id=73' });
  const login = p.onLoginTap(); await turn();
  await p.onProfileAuthConfirm({ detail: { nickname: '用户确认昵称', avatarPath: '/tmp/native-avatar.png' } }); await login;
  assert.equal(p.data.stage, 'code'); assert.equal(client(r.wx).loggedIn, true);
  const patch = r.calls.find(c => c.path === '/me' && c.method === 'PATCH');
  assert.deepEqual(patch.data, { nickname: '用户确认昵称', avatarImageId: '101', expectedVersion: 0 });
  assert.equal(r.calls.find(c => c.path === '/images').formData.purpose, 'avatar');
  assert.equal(r.calls.some(c => c.path === '/beta/redeem'), false);
  p.onCodeInput({ detail: { value: 'test-only-code' } }); await p.onActivateTap();
  assert.deepEqual(r.calls.find(c => c.path === '/beta/redeem').data, { code: 'test-only-code' });
  assert.deepEqual(r.routes, ['/pages/forum-detail/index?id=73']);
  assert.equal('activated' in r.saved.get(sessionKey), false);
});
test('a failed native profile save retains consent and cannot expose code entry', async () => {
  const r = runtime({ handlers: { '/me': o => o.method === 'PATCH' ? failure(o, 503, 'SERVICE_UNAVAILABLE') : ok(o, { id: '91', version: 0 }) } });
  const p = page('beta-access', r); await p.onLoad({}); const login = p.onLoginTap(); await turn();
  await p.onProfileAuthConfirm({ detail: { nickname: '确认昵称', avatarPath: '/tmp/avatar.png' } });
  assert.equal(p.data.authVisible, true); assert.equal(p.data.stage, 'login'); assert.equal(client(r.wx).loggedIn, false);
  await p.onActivateTap(); assert.equal(r.calls.some(c => c.path === '/beta/redeem'), false);
  p.onProfileAuthCancel(); await login;
});
test('code validation errors and throttling remain actionable without changing account access', async () => {
  let response = 'empty'; const r = runtime({ authenticated: true, handlers: { '/beta/redeem': o => response === 'bad' ? failure(o, 400, 'BETA_CODE_UNAVAILABLE') : failure(o, 429, 'RATE_LIMITED') } });
  const p = page('beta-access', r); await p.onLoad({}); await p.onActivateTap();
  assert.equal(r.calls.some(c => c.path === '/beta/redeem'), false); assert.ok(p.data.error);
  p.onCodeInput({ detail: { value: 'wrong-code' } }); response = 'bad'; await p.onActivateTap();
  assert.equal(p.data.stage, 'code'); assert.ok(p.data.error.includes('不可用')); assert.equal(p.data.submitting, false);
  response = 'limited'; await p.onActivateTap(); assert.ok(p.data.error.includes('频繁')); assert.deepEqual(r.routes, []);
});
test('repeated activation taps issue one request and a lost response permits a safe retry', async () => {
  let pending; const r = runtime({ authenticated: true, handlers: { '/beta/redeem': o => { pending = o; } } });
  const p = page('beta-access', r); await p.onLoad({}); p.onCodeInput({ detail: { value: 'test-only-code' } });
  const first = p.onActivateTap(), second = p.onActivateTap();
  assert.equal(r.calls.filter(c => c.path === '/beta/redeem').length, 1); assert.equal(p.data.submitting, true);
  pending.fail({ errMsg: 'offline' }); await Promise.all([first, second]);
  assert.equal(p.data.submitting, false); assert.equal(p.data.stage, 'code'); assert.ok(p.data.error.includes('网络')); assert.deepEqual(r.routes, []);
  const retry = p.onActivateTap(); ok(pending, { phase: 'beta', authenticated: true, activated: true }); await retry;
  assert.deepEqual(r.routes, ['/pages/forum/index']);
});
test('returning activated accounts and open phase enter without consuming another code', async () => {
  for (const state of [{ authenticated: true, activated: true }, { phase: 'open' }]) {
    const r = runtime(state), p = page('beta-access', r); await Promise.all([p.onLoad({}), p.onShow()]);
    assert.deepEqual(r.routes, ['/pages/forum/index']);
    assert.equal(r.calls.filter(c => c.path === '/beta/access').length, 1);
    assert.equal(r.calls.some(c => c.path === '/beta/redeem' || c.path === '/auth/wechat'), false);
  }
});
test('local activation flags never bypass a live inactive account', async () => {
  const r = runtime({ authenticated: true }); r.saved.get(sessionKey).activated = true; r.saved.set('xinkelink.beta.activated', true);
  const p = page('beta-access', r); await p.onLoad({}); assert.equal(p.data.stage, 'code'); assert.deepEqual(r.routes, []);
});
test('failed or malformed access status stays on the gate and explicit retry fetches the server again', async () => {
  let attempt = 0; const r = runtime({ handlers: { '/beta/access': o => {
    attempt++; if (attempt === 1) return o.fail({ errMsg: 'offline' });
    return ok(o, attempt === 2 ? {} : { phase: 'open', authenticated: false, activated: false });
  } } }); const p = page('beta-access', r); await p.onLoad({});
  assert.equal(p.data.stage, 'error'); assert.ok(p.data.error); assert.deepEqual(r.routes, []);
  await p.onRetryTap(); assert.equal(p.data.stage, 'error'); assert.deepEqual(r.routes, []);
  await p.onRetryTap(); assert.deepEqual(r.routes, ['/pages/forum/index']);
});
test('external, recursive, unregistered and malformed redirects all restore the forum', async () => {
  for (const redirect of ['https://example.com', '//example.com', '/pages/beta-access/index', '/pages/login/index', '/pages/forum/index%0a', '/pages/forum/index?bad=%', '/pages/forum/index#evil']) {
    const r = runtime({ phase: 'open' }), p = page('beta-access', r); await p.onLoad({ redirect });
    assert.deepEqual(r.routes, ['/pages/forum/index'], redirect);
  }
});
test('a server beta denial after an open-phase check routes to the gate once and the gate does not loop on denial', async () => {
  const r = runtime({ phase: 'open', handlers: { '/posts': o => failure(o, 403, 'BETA_ACCESS_REQUIRED') } });
  const p = page('forum', r); await p.onLoad({}); await p.onShow();
  assert.equal(r.routes.length, 1); assert.ok(r.routes[0].startsWith('/pages/beta-access/index?'));
  const r2 = runtime({ authenticated: true, handlers: { '/beta/redeem': o => failure(o, 403, 'BETA_ACCESS_REQUIRED') } });
  const gate = page('beta-access', r2); await gate.onLoad({}); gate.onCodeInput({ detail: { value: 'test-only' } }); await gate.onActivateTap();
  assert.deepEqual(r2.routes, []); assert.equal(gate.data.submitting, false); assert.ok(gate.data.error);
});
test('expired session refresh rejection returns to login without trying to redeem anonymously', async () => {
  const r = runtime({ authenticated: true, handlers: { '/auth/refresh': o => failure(o, 401, 'REFRESH_INVALID') } });
  r.saved.get(sessionKey).expiresAt = Date.now() - 1;
  const p = page('beta-access', r); await p.onLoad({}); assert.equal(p.data.stage, 'login'); await p.onActivateTap();
  assert.equal(client(r.wx).loggedIn, false); assert.equal(r.calls.some(c => c.path === '/beta/redeem'), false);
});
test('leaving a shared page during its access check cannot later fetch content or redirect over the new page', async () => {
  for (const phase of ['beta', 'open']) {
    let pending; const r = runtime({ handlers: { '/beta/access': o => { pending = o; } } });
    const p = page('forum-detail', r), loading = p.onLoad({ id: '73' }); p.onUnload();
    ok(pending, { phase, authenticated: false, activated: false }); await loading;
    assert.equal(r.calls.some(c => c.path.startsWith('/posts')), false); assert.deepEqual(r.routes, []);
  }
});
test('leaving the gate during a pending redemption cannot navigate after a late success', async () => {
  let pending; const r = runtime({ authenticated: true, handlers: { '/beta/redeem': o => { pending = o; } } });
  const p = page('beta-access', r); await p.onLoad({}); p.onCodeInput({ detail: { value: 'test-only' } });
  const saving = p.onActivateTap(); p.onUnload(); ok(pending, { phase: 'beta', authenticated: true, activated: true }); await saving;
  assert.deepEqual(r.routes, []);
});
test('an unfinished profile PATCH cannot restore a confirmed local session after the gate is unloaded', async () => {
  let patch; const r = runtime({ handlers: { '/me': o => o.method === 'PATCH' ? patch = o : ok(o, { id: '91', version: 0 }) } });
  const p = page('beta-access', r); await p.onLoad({}); const login = p.onLoginTap(); await turn();
  const consent = p.onProfileAuthConfirm({ detail: { nickname: '用户确认', avatarPath: '/tmp/avatar.png' } }); await turn();
  p.onUnload(); ok(patch, { id: '91', nickname: '用户确认', version: 1 }); await consent; await login;
  assert.equal(client(r.wx).loggedIn, false); assert.deepEqual(r.routes, []);
});
test('a restored activated session enters the originally requested tab with its query intact', async () => {
  const r = runtime({ authenticated: true, activated: true }), p = page('beta-access', r);
  await p.onLoad({ redirect: '/pages/forum/index?postId=73' });
  assert.deepEqual(r.routes, ['/pages/forum/index?postId=73']);
  assert.equal(r.calls.some(c => c.path === '/beta/redeem'), false);
});
test('returning to a cached business page rechecks a reopened beta phase before reading content', async () => {
  let phase = 'open'; const r = runtime({ handlers: { '/beta/access': o => ok(o, { phase, authenticated: false, activated: false }) } });
  const p = page('forum', r); await p.onLoad({}); p.onHide(); phase = 'beta';
  r.calls.length = 0; await p.onShow();
  assert.equal(r.calls.filter(c => c.path === '/beta/access').length, 1);
  assert.equal(r.calls.some(c => c.path === '/posts'), false); assert.equal(r.routes.length, 1);
});
test('native login failure keeps code entry hidden and supports a later successful confirmation', async () => {
  const r = runtime(), p = page('beta-access', r); await p.onLoad({});
  r.wx.login = o => o.fail({ errMsg: 'cancel' }); const login = p.onLoginTap(); await turn();
  await p.onProfileAuthConfirm({ detail: { nickname: '用户确认', avatarPath: '/tmp/avatar.png' } });
  assert.equal(p.data.stage, 'login'); assert.ok(p.data.authError); assert.equal(p.data.authSaving, false);
  r.wx.login = o => o.success({ code: 'retry-native-code' });
  await p.onProfileAuthConfirm({ detail: { nickname: '用户确认', avatarPath: '/tmp/avatar.png' } }); await login;
  assert.equal(p.data.stage, 'code'); assert.deepEqual(r.routes, []);
});
test('an unconfirmed local profile never uses a server activation response to bypass native consent', async () => {
  const r = runtime({ authenticated: true, activated: true }); r.saved.get(sessionKey).profileConfirmed = false;
  const p = page('beta-access', r); await p.onLoad({});
  assert.equal(p.data.stage, 'login'); assert.deepEqual(r.routes, []);
});
test('a failed destination navigation stays on the gate and retry rechecks access', async () => {
  const r = runtime({ phase: 'open' }); let fail = true;
  r.wx.reLaunch = o => fail ? o.fail({ errMsg: 'navigation failed' }) : r.routes.push(o.url);
  const p = page('beta-access', r); await p.onLoad({}); assert.equal(p.data.stage, 'error'); assert.ok(p.data.error);
  fail = false; await p.onRetryTap(); assert.deepEqual(r.routes, ['/pages/forum/index']);
  assert.equal(r.calls.filter(c => c.path === '/beta/access').length, 2);
});
test('a beta denial from content upload returns to the gate without swallowing the upload error', async () => {
  const r = runtime({ authenticated: true, activated: true }); const p = page('forum', r); await p.onLoad({});
  r.wx.uploadFile = o => o.success({ statusCode: 403, data: JSON.stringify({ code: 'BETA_ACCESS_REQUIRED', message: '请先激活内测资格' }) });
  await assert.rejects(client(r.wx).upload('/tmp/post.png', 'post'), e => e.code === 'BETA_ACCESS_REQUIRED');
  assert.equal(r.routes.length, 1); assert.ok(r.routes[0].startsWith('/pages/beta-access/index?'));
});
test('a hidden gate cannot replace a newly opened shared page when their access check resolves', async () => {
  let access; const r = runtime({ handlers: { '/beta/access': o => { access = o; } } });
  const gate = page('beta-access', r), loadingGate = gate.onLoad({});
  gate.onHide();
  const detail = page('forum-detail', r), loadingDetail = detail.onLoad({ id: '73' });
  ok(access, { phase: 'open', authenticated: false, activated: false });
  await Promise.all([loadingGate, loadingDetail]);
  assert.deepEqual(r.routes, [], 'hidden gate must not reLaunch its old forum target');
  assert.ok(r.calls.some(c => c.path === '/posts/73'));
});
test('late redemption success while the gate is hidden waits for a fresh status check on return', async () => {
  let redeem, activated = false;
  const r = runtime({ authenticated: true, handlers: {
    '/beta/access': o => ok(o, { phase: 'beta', authenticated: true, activated }),
    '/beta/redeem': o => { redeem = o; },
  } });
  const gate = page('beta-access', r); await gate.onLoad({ redirect: '/pages/forum-detail/index?id=73' });
  gate.onCodeInput({ detail: { value: 'test-only' } }); const saving = gate.onActivateTap(); gate.onHide();
  const hiddenState = JSON.parse(JSON.stringify(gate.data));
  activated = true; ok(redeem, { phase: 'beta', authenticated: true, activated: true }); await saving;
  assert.deepEqual(r.routes, []); assert.deepEqual(gate.data, hiddenState);
  await gate.onShow();
  assert.equal(r.calls.filter(c => c.path === '/beta/access').length, 2);
  assert.deepEqual(r.routes, ['/pages/forum-detail/index?id=73']);
});
test('a resumed gate starts a new status request and old failures cannot clear its loading state', async () => {
  const access = []; const r = runtime({ handlers: { '/beta/access': o => access.push(o) } });
  const gate = page('beta-access', r), first = gate.onLoad({}); gate.onHide(); const resumed = gate.onShow();
  assert.equal(access.length, 2, 'resume must revalidate instead of reusing the pre-hide request');
  access[0].fail({ errMsg: 'old network error' }); await first;
  assert.equal(gate.data.checking, true); assert.equal(gate.data.error, ''); assert.equal(gate.data.stage, 'checking');
  const retry = gate.onRetryTap(); assert.equal(access.length, 2, 'old finally must not discard the new pending request');
  ok(access[1], { phase: 'beta', authenticated: false, activated: false }); await Promise.all([resumed, retry]);
  assert.equal(gate.data.stage, 'login'); assert.equal(gate.data.checking, false); assert.deepEqual(r.routes, []);
});
test('an older redemption finally cannot unlock a resumed submission or display an old error', async () => {
  const redeems = []; const r = runtime({ authenticated: true, handlers: { '/beta/redeem': o => redeems.push(o) } });
  const gate = page('beta-access', r); await gate.onLoad({}); gate.onCodeInput({ detail: { value: 'first-test-only' } });
  const first = gate.onActivateTap(); gate.onHide(); await gate.onShow();
  assert.equal(gate.data.stage, 'code'); assert.equal(gate.data.submitting, false);
  gate.onCodeInput({ detail: { value: 'second-test-only' } }); const second = gate.onActivateTap();
  assert.equal(redeems.length, 2); redeems[0].fail({ errMsg: 'old redeem error' }); await first;
  assert.equal(gate.data.submitting, true); assert.equal(gate.data.error, '');
  await gate.onActivateTap(); assert.equal(redeems.length, 2);
  failure(redeems[1], 400, 'BETA_CODE_UNAVAILABLE'); await second;
  assert.equal(gate.data.submitting, false); assert.ok(gate.data.error.includes('不可用'));
});
test('hiding the gate cancels its pending native profile prompt and resume offers a new confirmation', async () => {
  const r = runtime(), gate = page('beta-access', r); await gate.onLoad({});
  const login = gate.onLoginTap(); await turn(); assert.equal(gate.data.authVisible, true);
  gate.onHide(); await turn();
  assert.equal(gate.data.authVisible, false); assert.equal(gate.data.loggingIn, false);
  await login; await gate.onShow();
  const resumedLogin = gate.onLoginTap(); await turn(); assert.equal(gate.data.authVisible, true);
  gate.onProfileAuthCancel(); await resumedLogin;
  assert.equal(client(r.wx).loggedIn, false); assert.deepEqual(r.routes, []);
});
test('hiding during native profile saving cannot restore consent or start another login before it finishes', async () => {
  let patch; const r = runtime({ handlers: { '/me': o => o.method === 'PATCH' ? patch = o : ok(o, { id: '91', version: 0 }) } });
  const gate = page('beta-access', r); await gate.onLoad({}); const login = gate.onLoginTap(); await turn();
  const consent = gate.onProfileAuthConfirm({ detail: { nickname: '用户确认', avatarPath: '/tmp/avatar.png' } }); await turn();
  gate.onHide(); await login; await gate.onShow();
  assert.equal(gate.data.stage, 'login'); assert.equal(gate.data.authSaving, true);
  await gate.onLoginTap(); assert.equal(gate.data.authVisible, false);
  assert.equal(r.calls.filter(c => c.path === '/auth/wechat').length, 1);
  ok(patch, { id: '91', nickname: '用户确认', version: 1 }); await consent;
  assert.equal(client(r.wx).loggedIn, false); assert.equal(gate.data.authSaving, false); assert.deepEqual(r.routes, []);
  const retry = gate.onLoginTap(); await turn(); assert.equal(gate.data.authVisible, true);
  gate.onProfileAuthCancel(); await retry;
});

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { loadPage } = require('./page-harness.cjs');
const { loadComponent } = require('./native-harness.cjs');

test('packOptions excludes mock assets folder from WeChat upload bundle', () => {
  const config = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../project.config.json'), 'utf8'));
  const ignored = config.packOptions?.ignore ?? [];
  assert.ok(ignored.some(item => item.value === 'miniprogram/assets/mock' && item.type === 'folder'));
});

test('legal page supports terms/privacy tabs and opens WeChat privacy contract', () => {
  let openedContract = 0;
  const wx = {
    openPrivacyContract() { openedContract += 1; },
    showToast() {},
  };
  const page = loadPage('pages/legal/index.js', { wx, fixture: true });
  page.onLoad({ tab: 'terms' });
  assert.equal(page.data.tab, 'terms');
  page.onTabTap({ currentTarget: { dataset: { tab: 'privacy' } } });
  assert.equal(page.data.tab, 'privacy');
  page.onOpenWechatPrivacy();
  assert.equal(openedContract, 1);
});

test('app-shell privacy entry and profile-auth-sheet legal links navigate to legal page', () => {
  const routes = [];
  const wx = {
    navigateTo(options) { routes.push(options.url); },
    showToast() {},
    showModal() {},
  };
  const { instance: shell } = loadComponent('components/app-shell/index.js', { wx });
  shell.onPrivacyTap();
  assert.equal(routes[0], '/pages/legal/index?tab=privacy');

  const { instance: sheet } = loadComponent('components/profile-auth-sheet/index.js', { wx });
  sheet.onLegalTap({ currentTarget: { dataset: { tab: 'terms' } } });
  sheet.onLegalTap({ currentTarget: { dataset: { tab: 'privacy' } } });
  assert.deepEqual(routes.slice(1), ['/pages/legal/index?tab=terms', '/pages/legal/index?tab=privacy']);
});

test('app-shell deactivate requests /me/deactivate after confirmation and clears session', async () => {
  const storage = new Map([['xinkelink.backend.session.v1', {
    accessToken: 'access', refreshToken: 'refresh', expiresAt: Date.now() + 600000,
    me: { id: '29', nickname: '测试同学' },
  }]]);
  const calls = [];
  const toasts = [];
  const wx = {
    getStorageSync: k => storage.get(k),
    setStorageSync: (k, v) => storage.set(k, v),
    removeStorageSync: k => storage.delete(k),
    showModal(options) { options.success({ confirm: true }); },
    showToast(options) { toasts.push(options.title); },
    request(options) {
      calls.push({ url: new URL(options.url).pathname, method: options.method, data: options.data });
      options.success({ statusCode: 200, data: { code: 'OK', data: { deactivated: true } } });
    },
  };
  const { instance } = loadComponent('components/app-shell/index.js', { fixture: false, wx });
  instance.setData({ loggedIn: true });
  instance.onDeactivateTap();
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(calls.some(c => c.url.endsWith('/me/deactivate') && c.method === 'POST' && c.data?.confirmed === true), true);
  assert.equal(instance.data.loggedIn, false);
  assert.ok(!storage.get('xinkelink.backend.session.v1'));
  assert.ok(toasts.includes('账号已注销'));
});

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

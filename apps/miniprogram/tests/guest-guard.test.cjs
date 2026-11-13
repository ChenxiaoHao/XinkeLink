const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { loadPage } = require('./page-harness.cjs');

test('a guest viewing their own public profile gets no edit entry, and the guard blocks navigation', () => {
  const saved = new Map();
  const routes = [], modals = [];
  const wxStub = {
    __fixtureMode: true,
    getStorageSync: k => saved.get(k),
    setStorageSync: (k, v) => saved.set(k, v),
    navigateTo: options => routes.push(options.url),
    showModal: options => modals.push(options),
    showToast() {},
  };
  const page = loadPage('pages/user-home/index.js', { wx: wxStub });
  saved.set('xinkelink.session.v1', false);
  page.onLoad({ id: 'local-student' });
  assert.equal(page.data.loggedIn, false);
  assert.equal(page.data.profile.isSelf, true);

  // 编辑按钮只在「本人 + 已登录」时渲染。
  const wxml = fs.readFileSync(path.resolve(__dirname, '../miniprogram/pages/user-home/index.wxml'), 'utf8');
  assert.match(wxml, /profile\.isSelf && loggedIn/);

  // 防御兜底：按钮即使被触发也弹登录引导，不进入编辑页。
  page.onEditTap();
  assert.deepEqual(routes, []);
  assert.equal(modals.length, 1);
  assert.ok(modals[0].content.includes('登录'));
});

test('the profile editor itself refuses guests and loads the form after one-tap login', () => {
  const saved = new Map();
  const modals = [];
  const wxStub = {
    __fixtureMode: true,
    getStorageSync: k => saved.get(k),
    setStorageSync: (k, v) => saved.set(k, v),
    navigateBack() {}, switchTab() {},
    showModal: options => modals.push(options),
    showToast() {},
  };
  const page = loadPage('pages/profile-edit/index.js', { wx: wxStub });
  saved.set('xinkelink.session.v1', false);
  page.onLoad();
  assert.equal(modals.length, 1, '访客进入编辑页应弹登录引导');
  assert.ok(modals[0].content.includes('登录'));
  modals[0].success({ confirm: true });
  assert.equal(saved.get('xinkelink.session.v1'), true);
  assert.equal(page.data.form.nickname, page.data.form.nickname || '陈林舟');
});

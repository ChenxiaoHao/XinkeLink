const test = require('node:test');
const assert = require('node:assert/strict');
const { loadComponent } = require('./native-harness.cjs');

const { setLocalSession, saveProfile } = require('../.test-dist/features/profile/repository.js');
const SESSION_KEY = 'xinkelink.session.v1';
const PROFILE_KEY = 'xinkelink.profile.v1';
const GUEST_AVATAR = '/assets/icons/user-round-muted.png';

function storageStub(session) {
  const saved = new Map();
  const relaunches = [];
  const wxStub = {
    __fixtureMode: true,
    getStorageSync: k => saved.get(k),
    setStorageSync: (k, v) => saved.set(k, v),
    reLaunch: options => { relaunches.push(options.url); },
    showModal: options => { wxStub.modals.push(options); },
    showToast() {},
  };
  wxStub.modals = [];
  if (session !== undefined) saved.set(SESSION_KEY, session);
  return { saved, wxStub, relaunches };
}

test('logging in from guest triggers exactly one reLaunch to the home feed', () => {
  const { wxStub, relaunches } = storageStub(false);
  setLocalSession(wxStub, true);
  assert.deepEqual(relaunches, ['/pages/forum/index'], '登录成功应立即 reLaunch 首页一次');
});

test('logging out from a session triggers exactly one reLaunch to the home feed', () => {
  const { wxStub, relaunches } = storageStub(true);
  setLocalSession(wxStub, false);
  assert.deepEqual(relaunches, ['/pages/forum/index'], '退出登录应立即 reLaunch 首页一次');
});

test('saving profile or avatar does not reLaunch', () => {
  const { saved, wxStub, relaunches } = storageStub(true);
  saveProfile(wxStub, { version: 1, nickname: '改个昵称', avatar: '/assets/mock/forum/avatar-lin.jpg', bio: '', major: '', className: '', studentId: '', year: '' });
  assert.equal(relaunches.length, 0, '资料保存触发身份广播但不应跳转');
  assert.equal(saved.get(PROFILE_KEY).nickname, '改个昵称', '资料确实已保存');
});

test('setLocalSession without a login-state change does not reLaunch', () => {
  const loggedIn = storageStub(true);
  setLocalSession(loggedIn.wxStub, true);
  assert.equal(loggedIn.relaunches.length, 0, '重复确认登录态不跳转');
  const guest = storageStub(false);
  setLocalSession(guest.wxStub, false);
  setLocalSession(guest.wxStub, false);
  assert.equal(guest.relaunches.length, 0, '访客态重复确认不跳转');
});

test('guest drawer shows the default avatar and 访客 instead of the previous user', () => {
  const { saved, wxStub } = storageStub(false);
  saved.set(PROFILE_KEY, { version: 1, nickname: '上个用户', avatar: '/assets/mock/forum/avatar-zhou.jpg', bio: '', major: '', className: '', studentId: '', year: '' });
  const { instance, definition } = loadComponent('components/app-shell/index.js', { wx: wxStub });
  definition.pageLifetimes.show.call(instance);
  assert.equal(instance.data.identity.nickname, '访客');
  assert.equal(instance.data.identity.avatar, GUEST_AVATAR);
  assert.equal(instance.data.loggedIn, false);
});

test('logging out inside the drawer resets the identity card to the guest view', () => {
  const { wxStub, relaunches } = storageStub(true);
  const { instance, definition } = loadComponent('components/app-shell/index.js', { wx: wxStub });
  definition.pageLifetimes.show.call(instance);
  assert.equal(instance.data.identity.nickname, '陈林舟', '登录态抽屉先显示本人昵称');
  instance.onLogoutTap();
  assert.equal(wxStub.modals.length, 1);
  wxStub.modals[0].success({ confirm: true });
  assert.deepEqual(relaunches, ['/pages/forum/index'], '退出触发一次首页 reLaunch');
  assert.equal(instance.data.loggedIn, false);
  assert.equal(instance.data.identity.nickname, '访客', '退出后抽屉立即变为访客');
  assert.equal(instance.data.identity.avatar, GUEST_AVATAR, '退出后抽屉不再显示上个用户头像');
});

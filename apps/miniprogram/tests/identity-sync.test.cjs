const test = require('node:test');
const assert = require('node:assert/strict');
const { loadComponent } = require('./native-harness.cjs');
const repo = require('../.test-dist/features/profile/repository.js');
const events = require('../.test-dist/features/profile/identity-events.js');
const avatar = require('../.test-dist/features/profile/avatar.js');

const storage = () => {
  const saved = new Map();
  return { saved, getStorageSync: k => saved.get(k), setStorageSync: (k, v) => saved.set(k, v) };
};
const fileApi = () => {
  const copied = [];
  return {
    copied,
    env: { USER_DATA_PATH: 'http://usr' },
    getFileSystemManager: () => ({ copyFileSync: (src, dest) => copied.push([src, dest]), unlinkSync() {} }),
  };
};

test('profile and session writes broadcast identity changes to subscribers', () => {
  const s = storage(), seen = [];
  const off = events.onIdentityChange(() => seen.push(1));
  repo.setLocalSession(s, false);
  assert.equal(seen.length, 1);
  repo.saveProfile(s, { ...repo.defaultProfile(), nickname: '小浩' });
  assert.equal(seen.length, 2);
  off();
  repo.setLocalSession(s, true);
  assert.equal(seen.length, 2);
});

test('page header reads identity on attach and follows in-page login/logout without onShow', () => {
  const s = storage(), f = fileApi();
  const wx = {
    __fixtureMode: true,
    getStorageSync: s.getStorageSync, setStorageSync: s.setStorageSync,
    env: f.env, getFileSystemManager: f.getFileSystemManager,
    getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375 }),
    getMenuButtonBoundingClientRect: () => ({ top: 26, height: 32, left: 270 }),
  };
  const { definition, instance } = loadComponent('components/page-header/index.js', { wx });
  // 首次挂载就读身份（不依赖页面 show），否则同一页面内登录会一直停在访客占位。
  definition.lifetimes.attached.call(instance);
  assert.equal(instance.data.loggedIn, true);
  assert.equal(instance.data.avatar, repo.defaultProfile().avatar);

  repo.setLocalSession(wx, false);
  assert.equal(instance.data.loggedIn, false);
  assert.equal(instance.data.avatar, '');

  repo.saveProfile(wx, { ...repo.defaultProfile(), nickname: '跃进春风', avatar: 'http://usr/avatar.png' });
  repo.setLocalSession(wx, true);
  assert.equal(instance.data.loggedIn, true);
  assert.equal(instance.data.avatar, 'http://usr/avatar.png');
  assert.equal(instance.data.initial, '风');

  definition.lifetimes.detached.call(instance);
  repo.setLocalSession(wx, false);
  assert.equal(instance.data.loggedIn, true, 'detached 后不应继续刷新');
});

test('login avatars are persisted and legacy temporary paths are repaired on launch', () => {
  const s = storage(), f = fileApi();
  const api = { ...s, env: f.env, getFileSystemManager: f.getFileSystemManager };
  const result = avatar.persistProfileAvatar(api, 'http://tmp/abc.jpeg');
  assert.match(result.avatar, /^http:\/\/usr\/xlk-profile-avatar-/);
  assert.equal(f.copied.length, 1);

  repo.saveProfile(api, { ...repo.defaultProfile(), avatar: 'http://tmp/old.jpeg' });
  avatar.repairTransientAvatar(api);
  const repaired = repo.readProfile(api).avatar;
  assert.match(repaired, /^http:\/\/usr\/xlk-profile-avatar-/);
  avatar.repairTransientAvatar(api);
  assert.equal(repo.readProfile(api).avatar, repaired, '已迁移的头像不应重复复制');

  // 临时文件已被清理时回退内置头像，不留破图。
  const broken = { ...api, getFileSystemManager: () => ({ copyFileSync() { throw new Error('gone') }, unlinkSync() {} }) };
  repo.saveProfile(broken, { ...repo.defaultProfile(), avatar: 'http://tmp/missing.jpeg' });
  avatar.repairTransientAvatar(broken);
  assert.equal(repo.readProfile(broken).avatar, repo.defaultProfile().avatar);
});

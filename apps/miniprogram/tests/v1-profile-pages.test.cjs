const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');
const { loadComponent } = require('./native-harness.cjs');
const p = require('../.test-dist/features/profile/repository.js');
const f = require('../.test-dist/features/forum/repository.js');

function api() {
  const saved = new Map([[p.SESSION_KEY, true]]), navigation = [], toasts = [], copied = [], removed = [];
  return { saved, navigation, toasts, copied, removed,
    getStorageSync: key => saved.get(key), setStorageSync: (key, value) => saved.set(key, value),
    navigateTo: options => navigation.push(options.url), redirectTo: options => navigation.push(options.url),
    switchTab: options => navigation.push(options.url), navigateBack: () => navigation.push('back'),
    showToast: options => toasts.push(options.title), showModal(options) { this.modal = options; },
    env: { USER_DATA_PATH: '/user' }, getFileSystemManager: () => ({
      copyFileSync: (source, target) => copied.push({ source, target }), unlinkSync: target => removed.push(target),
    }),
  };
}
const page = (name, wx) => loadPage(`pages/${name}/index.js`, { wx });
const ownPost = (wx, anonymous = false) => f.publishForumPost(wx, { content: anonymous ? '我的匿名动态' : '我的校园动态', images: [], category: 'campus', anonymous }, []);
const touch = id => ({ currentTarget: { dataset: { id } } });
const detail = id => ({ detail: { id } });

test('profile tab derives own counts, refreshes identity and retains every menu route', () => {
  const wx = api(); ownPost(wx); ownPost(wx, true);
  f.togglePostInteraction(wx, 'post-003', 'collected');
  const subject = page('profile', wx); let tab;
  subject.getTabBar = () => ({ setData: value => { tab = value; } }); subject.onShow();
  assert.equal(subject.data.postCount, 2); assert.equal(subject.data.collectionCount, 2);
  assert.equal(subject.data.profile.nickname, '陈林舟'); 
  p.saveProfile(wx, { ...p.readProfile(wx), nickname: '新的昵称' }); subject.onShow(); assert.equal(subject.data.profile.nickname, '新的昵称');
  subject.onMyPostsTap(); subject.onCollectionsTap(); subject.onEditTap(); subject.onSettingsTap(); subject.onHomeTap();
  assert.deepEqual(wx.navigation, ['/pages/my-posts/index', '/pages/my-collections/index', '/pages/profile-edit/index', '/pages/feedback/index', '/pages/user-home/index?id=local-student']);
});

test('profile storage failures show a retry state instead of claiming empty data', () => {
  const wx = api(); wx.getStorageSync = key => { if (key === p.SESSION_KEY) return true; throw Error('disk'); };
  const subject = page('profile', wx); subject.onShow(); assert.equal(subject.data.loadFailed, true);
  wx.getStorageSync = key => wx.saved.get(key); subject.reload(); assert.equal(subject.data.loadFailed, false);
});

test('my posts include anonymous records and deletion requires confirmation before one persistent write', () => {
  const wx = api(), first = ownPost(wx), second = ownPost(wx, true), subject = page('my-posts', wx);
  subject.onShow(); assert.equal(subject.data.posts.length, 2);
  subject.onOpenPost(detail(first.id)); assert.equal(wx.navigation[0], '/pages/forum-detail/index?id=' + first.id);
  subject.onDeleteTap(touch(second.id)); assert.equal(f.readLocalPosts(wx).length, 2);
  wx.modal.success({ confirm: false }); assert.equal(f.readLocalPosts(wx).length, 2);
  subject.onDeleteTap(touch(second.id)); wx.modal.success({ confirm: true });
  assert.equal(f.readLocalPosts(wx).length, 1); assert.equal(subject.data.posts[0].id, first.id);
});

test('my posts deletion failure preserves the record and releases its busy state', () => {
  const wx = api(), record = ownPost(wx), subject = page('my-posts', wx); subject.onShow();
  wx.setStorageSync = () => { throw Error('full'); };
  subject.onDeleteTap(touch(record.id)); wx.modal.success({ confirm: true });
  assert.equal(subject.data.posts.length, 1); assert.equal(subject.data.busyId, ''); assert.match(wx.toasts.at(-1), /未|失败/);
});

test('profile editor persists all form fields and rejects invalid names before navigating back', () => {
  const wx = api(), subject = page('profile-edit', wx); subject.onLoad();
  for (const [field, value] of Object.entries({ nickname: '新同学', bio: '一起记录校园', major: '计算机', className: '计科25-1班', studentId: '2025001234', year: '2025' })) subject.onFieldInput({ currentTarget: { dataset: { field } }, detail: { value } });
  subject.onSaveTap(); assert.equal(p.readProfile(wx).nickname, '新同学'); assert.equal(p.readProfile(wx).major, '计算机'); assert.equal(p.readProfile(wx).className, '计科25-1班'); assert.equal(p.readProfile(wx).studentId, '2025001234'); assert.equal(p.readProfile(wx).year, '2025'); assert.equal('school' in p.readProfile(wx), false); assert.equal(wx.navigation.at(-1), 'back');
  const invalid = page('profile-edit', wx); invalid.onLoad(); invalid.onFieldInput({ currentTarget: { dataset: { field: 'nickname' } }, detail: { value: ' ' } }); invalid.onSaveTap();
  assert.equal(p.readProfile(wx).nickname, '新同学'); assert.match(wx.toasts.at(-1), /检查|昵称/);
});

test('new profile avatar survives temporary files and failed save rolls back only this copy', () => {
  const wx = api(), subject = page('profile-edit', wx); subject.onLoad();
  subject.setData({ form: { ...subject.data.form, avatar: '/temp/new-avatar.jpg' } }); subject.onSaveTap();
  assert.equal(wx.copied.length, 1); assert.match(p.readProfile(wx).avatar, /^\/user\/xlk-profile-avatar-/); assert.equal(wx.removed.length, 0);
  const previous = p.readProfile(wx).avatar, next = page('profile-edit', wx); next.onLoad(); next.setData({ form: { ...next.data.form, avatar: '/temp/retry.png' } });
  wx.setStorageSync = () => { throw Error('full'); }; next.onSaveTap();
  assert.deepEqual(wx.removed, [wx.copied[1].target]); assert.equal(p.readProfile(wx).avatar, previous); assert.equal(next.data.saving, false);
});

test('profile editor failed read cannot overwrite corrupt saved data', () => {
  const wx = api(); wx.saved.set(p.PROFILE_KEY, { nickname: 123 }); const subject = page('profile-edit', wx); subject.onLoad(); subject.onSaveTap();
  assert.equal(subject.data.loadFailed, true); assert.deepEqual(wx.saved.get(p.PROFILE_KEY), { nickname: 123 }); assert.equal(wx.navigation.length, 0);
});

test('drawer preferences persist and a failed write restores displayed values', () => {
  const wx = api();
  const { instance: subject } = loadComponent('components/app-shell/index.js', { wx });
  subject.onPreferenceChange({ currentTarget: { dataset: { key: 'messages' } }, detail: { value: false } });
  assert.equal(p.readPreferences(wx).messages, false); assert.equal(subject.data.preferences.messages, false);
  wx.setStorageSync = () => { throw Error('full'); };
  subject.onPreferenceChange({ currentTarget: { dataset: { key: 'sound' } }, detail: { value: true } });
  assert.equal(subject.data.preferences.sound, false); assert.match(wx.toasts.at(-1), /未|失败/);
});

test('drawer logout confirms and preserves all user data', () => {
  const wx = api(); const record = ownPost(wx);
  const { instance: subject } = loadComponent('components/app-shell/index.js', { wx });
  subject.onLogoutTap();
  assert.equal(p.readLocalSession(wx), true); wx.modal.success({ confirm: false }); assert.equal(p.readLocalSession(wx), true);
  subject.onLogoutTap(); wx.modal.success({ confirm: true });
  assert.equal(p.readLocalSession(wx), false); assert.equal(f.readLocalPosts(wx)[0].id, record.id); assert.equal(wx.navigation.length, 0);
});

test('simulated login and visitor browsing never invoke real authorization or erase content', () => {
  const wx = api(); p.setLocalSession(wx, false); ownPost(wx); let realLogin = false; wx.login = () => { realLogin = true; };
  const subject = page('login', wx); subject.onLoginTap(); assert.equal(p.readLocalSession(wx), true); assert.equal(realLogin, false); assert.equal(wx.navigation.at(-1), '/pages/profile/index');
  subject.onGuestTap(); assert.equal(p.readLocalSession(wx), false); assert.equal(wx.navigation.at(-1), '/pages/forum/index'); assert.equal(f.readLocalPosts(wx).length, 1);
});

test('avatar copy failure never deletes files that this operation did not successfully create', () => {
  const wx = api(), subject = page('profile-edit', wx); subject.onLoad();
  wx.getFileSystemManager = () => ({ copyFileSync() { throw Error('copy failed'); }, unlinkSync: target => wx.removed.push(target) });
  subject.setData({ form: { ...subject.data.form, avatar: '/temp/missing.jpg' } }); subject.onSaveTap();
  assert.equal(wx.removed.length, 0); assert.equal(p.readProfile(wx).avatar, '/assets/mock/forum/avatar-lin.jpg'); assert.equal(subject.data.saving, false);
});

test('failed local login and logout writes retain the previous session and expose feedback', () => {
  const wx = api(); p.setLocalSession(wx, false); wx.setStorageSync = () => { throw Error('full'); };
  const login = page('login', wx); login.onLoginTap(); assert.equal(p.readLocalSession(wx), false); assert.equal(login.data.busy, false); assert.equal(wx.navigation.length, 0); assert.match(wx.toasts.at(-1), /未|失败/);
  wx.saved.set(p.SESSION_KEY, true); const { instance: drawer } = loadComponent('components/app-shell/index.js', { wx }); drawer.onLogoutTap(); wx.modal.success({ confirm: true });
  assert.equal(p.readLocalSession(wx), true); assert.equal(drawer.data.busy, false); assert.equal(wx.navigation.length, 0); assert.match(wx.toasts.at(-1), /未|失败/);
});

test('public self preview excludes anonymous content and peer messaging uses the shared identity route', () => {
  const wx = api(), publicPost = ownPost(wx); ownPost(wx, true);
  p.saveProfile(wx, { ...p.readProfile(wx), className: '计科22-1班', studentId: '2022001234' });
  const self = page('user-home', wx); self.onLoad({ id: 'local-student' }); self.onShow();
  assert.equal(self.data.profile.isSelf, true); assert.equal(self.data.profile.posts.length, 1); assert.equal(self.data.profile.posts[0].id, publicPost.id); assert.equal(self.data.profile.className, '计科22-1班'); assert.equal('studentId' in self.data.profile, false);
  self.onMessageTap(); assert.equal(wx.navigation.length, 0);
  const peerId = require('../.test-dist/features/forum/mock-posts.js').MOCK_POSTS.find(item => !item.anonymousNumber).author.id;
  const peer = page('user-home', wx); peer.onLoad({ id: peerId }); peer.onShow(); peer.onMessageTap();
  assert.equal(wx.navigation.at(-1), '/pages/contact-chat/index?peerId=' + encodeURIComponent(peerId));
});

test('guest mutation guards preserve likes and profile edits until local simulation login', () => {
  // 「我的收藏」已拆成独立的书签页，本页现为「我的点赞」，因此断言改用点赞状态。
  const wx = api(); f.togglePostInteraction(wx, 'post-003', 'liked'); p.setLocalSession(wx, false);
  const subject = page('my-collections', wx); subject.onShow(); subject.onLikePost(detail('post-003'));
  // 访客是干净的旁观视角：不显示上一个登录账号的点赞（存储保留），互动入口弹一键登录。
  assert.equal(subject.data.posts.some(item => item.id === 'post-003'), false); assert.match(wx.modal.content, /登录/);
  const edit = page('profile-edit', wx); edit.onLoad(); edit.setData({ form: { ...edit.data.form, nickname: '不应保存' } }); edit.onSaveTap(); assert.equal(p.readProfile(wx).nickname, '陈林舟');
  // 重新登录后，之前的点赞从存储里恢复——证明守卫只是隐藏展示、没有销毁数据。
  p.setLocalSession(wx, true); subject.onShow();
  assert.equal(subject.data.posts.some(item => item.id === 'post-003'), true);
});

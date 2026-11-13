const test = require('node:test');
const assert = require('node:assert/strict');
const { loadComponent } = require('./native-harness.cjs');
const { loadPage } = require('./page-harness.cjs');
const { BACKEND_ORIGIN } = require('../.test-dist/backend/config.js');

function clock() {
  let next = 0; const jobs = new Map();
  return { set(fn, delay) { const id = ++next; jobs.set(id, { fn, delay }); return id; }, clear(id) { jobs.delete(id); }, run() { const pending = [...jobs.values()]; jobs.clear(); pending.forEach(job => job.fn()); }, get size() { return jobs.size; } };
}

test('sheet closes visually before unmounting, and reopening cancels the stale close', () => {
  const { SheetTransition } = require('../.test-dist/utils/sheet-transition.js');
  const timer = clock(), state = { mounted: false, opened: false };
  const sheet = new SheetTransition(patch => Object.assign(state, patch), timer);
  sheet.setVisible(true); assert.equal(state.mounted, true); assert.equal(state.opened, false);
  timer.run(); assert.equal(state.opened, true);
  sheet.setVisible(false); assert.equal(state.mounted, true); assert.equal(state.opened, false);
  sheet.setVisible(true); timer.run(); assert.equal(state.mounted, true); assert.equal(state.opened, true);
  sheet.setVisible(false); timer.run(); assert.equal(state.mounted, false);
  sheet.dispose();
});

test('closing during entry and detaching cannot reopen a sheet later', () => {
  const { SheetTransition } = require('../.test-dist/utils/sheet-transition.js');
  const timer = clock(), updates = [];
  const sheet = new SheetTransition(patch => updates.push(patch), timer);
  sheet.setVisible(true); sheet.setVisible(false); timer.run();
  assert.equal(updates.at(-1).mounted, false);
  sheet.setVisible(true); sheet.dispose(); const count = updates.length;
  timer.run(); assert.equal(updates.length, count); assert.equal(timer.size, 0);
});

test('compact header search and editor action emit the existing page events', () => {
  const search = loadComponent('components/page-header/index.js');
  search.instance.onSearch(); assert.equal(search.events[0].name, 'search');
  const editor = loadComponent('components/back-header/index.js');
  editor.instance.onAction(); assert.equal(editor.events[0].name, 'action');
  editor.instance.data.busy = true; editor.instance.onAction(); assert.equal(editor.events.length, 1);
});

test('non-home cards show image failures without accepting stale image callbacks', () => {
  const post = loadComponent('components/forum-post-card/index.js').instance;
  post.data.home = false; post.data.post = { id: 'post', images: ['/new.png'] };
  post.onImageError({ currentTarget: { dataset: { source: '/old.png' } } });
  assert.equal(post.data.imageFailures['/old.png'], undefined);
  post.onImageError({ currentTarget: { dataset: { source: '/new.png' } } });
  assert.equal(post.data.imageFailures['/new.png'], true);
  post.onImageLoad({ currentTarget: { dataset: { source: '/new.png' } }, detail: { width: 600, height: 800 } });
  assert.equal(post.data.imageFailures['/new.png'], false);
});

test('feed cards load authorized backend images to local files when tablet native image loading fails', async () => {
  const calls = [], writes = [];
  const bytes = Uint8Array.from([137, 80, 78, 71]).buffer;
  const runtime = {
    getStorageSync() { return { accessToken: 'access', refreshToken: 'refresh', expiresAt: Date.now() + 3600000, me: { id: '7' } }; },
    setStorageSync() {},
    env: { USER_DATA_PATH: 'wxfile://usr' },
    getFileSystemManager() { return { writeFile(options) { writes.push(options); options.success(); } }; },
    request(options) {
      calls.push(options);
      if (options.url.endsWith('/images/42/access')) options.success({ statusCode: 200, data: { code: 'OK', data: { imageId: '42', url: '/api/v1/images/42/content?grant=private-grant' } } });
      else options.success({ statusCode: 200, data: bytes });
    },
  };
  const post = loadComponent('components/forum-post-card/index.js', { wx: runtime, fixture: false }).instance;
  post.data.post = { id: 'post-1', images: [BACKEND_ORIGIN + '/api/v1/images/42/content?grant=stale'] };
  await post.onImageError({ currentTarget: { dataset: { source: post.data.post.images[0], index: 0 } } });
  assert.match(post.data.post.images[0], /^wxfile:\/\/usr\/xinkelink-image-42-.+\.png$/);
  assert.equal(calls.length, 2);
  assert.equal(calls[1].responseType, 'arraybuffer');
  assert.equal(writes[0].filePath, post.data.post.images[0]);
  assert.equal(writes[0].data, bytes);
  assert.notEqual(post.data.imageFailures[post.data.post.images[0]], true);
});

test('tablet image fallback uses the native file downloader when available', async () => {
  const downloads = [];
  const runtime = {
    getStorageSync() { return { accessToken: 'access', refreshToken: 'refresh', expiresAt: Date.now() + 3600000, me: { id: '7' } }; },
    setStorageSync() {}, env: { USER_DATA_PATH: 'wxfile://usr' },
    downloadFile(options) {
      downloads.push(options);
      options.success({ statusCode: 200, tempFilePath: 'wxfile://tmp/xinkelink.png', filePath: options.filePath });
    },
    request(options) {
      if (options.url.endsWith('/images/42/access')) options.success({ statusCode: 200, data: { code: 'OK', data: { imageId: '42', url: '/api/v1/images/42/content?grant=fresh' } } });
      else assert.fail('原生下载器可用时不应再走二进制请求');
    },
  };
  const post = loadComponent('components/forum-post-card/index.js', { wx: runtime, fixture: false }).instance;
  post.data.post = { id: 'post-1', images: [BACKEND_ORIGIN + '/api/v1/images/42/content?grant=stale'] };
  await post.onImageError({ currentTarget: { dataset: { source: post.data.post.images[0], index: 0 } } });
  assert.equal(downloads.length, 1);
  assert.match(downloads[0].url, /grant=fresh$/);
  assert.equal(downloads[0].filePath, undefined);
  assert.equal(post.data.post.images[0], 'wxfile://tmp/xinkelink.png');
});

test('when the native downloader fails the image can still be loaded through request', async () => {
  const { loadAuthorizedLocalImage } = require('../.test-dist/backend/preview.js');
  let wrote = false;
  const runtime = {
    getStorageSync() { return { accessToken: 'access', refreshToken: 'refresh', expiresAt: Date.now() + 3600000, me: { id: '7' } }; },
    setStorageSync() {}, env: { USER_DATA_PATH: 'wxfile://usr' },
    downloadFile(options) { options.fail({ errMsg: 'network error' }); },
    getFileSystemManager() { return { writeFile(options) { wrote = true; options.success(); } }; },
    request(options) {
      if (options.url.endsWith('/images/42/access')) options.success({ statusCode: 200, data: { code: 'OK', data: { imageId: '42', url: '/api/v1/images/42/content?grant=fresh' } } });
      else options.success({ statusCode: 200, data: new ArrayBuffer(8) });
    },
  };
  const path = await loadAuthorizedLocalImage(runtime, BACKEND_ORIGIN + '/api/v1/images/42/content?grant=old');
  assert.equal(wrote, true); assert.match(path, /^wxfile:\/\/usr\//);
});

test('a refreshed post retries its new image URL and loads the author avatar', async () => {
  let downloads = 0;
  const runtime = {
    getStorageSync() { return { accessToken: 'access', refreshToken: 'refresh', expiresAt: Date.now() + 3600000, me: { id: '7' } }; },
    setStorageSync() {}, env: { USER_DATA_PATH: 'wxfile://usr' },
    downloadFile(options) { downloads++; options.success({ statusCode: 200, tempFilePath: 'wxfile://tmp/' + downloads + '.png' }); },
    request(options) { const id = options.url.match(/images\/(\d+)\/access/)[1]; options.success({ statusCode: 200, data: { code: 'OK', data: { imageId: id, url: '/api/v1/images/' + id + '/content?grant=fresh' } } }); },
  };
  const post = loadComponent('components/forum-post-card/index.js', { wx: runtime, fixture: false }).instance;
  const origin = BACKEND_ORIGIN + '/api/v1/images/';
  post.data.post = { id: 'post-1', images: [origin + '42/content?grant=one'], avatar: origin + '43/content?grant=one' };
  await post.onImageError({ currentTarget: { dataset: { source: post.data.post.images[0], index: 0 } } });
  post.data.post.images = [origin + '42/content?grant=two'];
  await post.onImageError({ currentTarget: { dataset: { source: post.data.post.images[0], index: 0 } } });
  assert.equal(post.data.post.images[0], 'wxfile://tmp/2.png');
  await post.onAvatarError({ currentTarget: { dataset: { source: post.data.post.avatar } } });
  assert.equal(post.data.post.avatar, 'wxfile://tmp/3.png');
});

test('profile, conversations and chat recover authorized avatars without replacing other users', async () => {
  let downloads = 0;
  const runtime = {
    getStorageSync() { return { accessToken: 'access', refreshToken: 'refresh', expiresAt: Date.now() + 3600000, me: { id: '7' } }; },
    setStorageSync() {}, env: { USER_DATA_PATH: 'wxfile://usr' },
    downloadFile(options) { options.success({ statusCode: 200, tempFilePath: 'wxfile://tmp/avatar-' + (++downloads) + '.png' }); },
    request(options) { const id = options.url.match(/images\/(\d+)\/access/)[1]; options.success({ statusCode: 200, data: { code: 'OK', data: { imageId: id, url: '/api/v1/images/' + id + '/content?grant=fresh' } } }); },
  };
  const avatar = BACKEND_ORIGIN + '/api/v1/images/42/content?grant=old';
  for (const name of ['profile', 'user-home']) {
    const page = loadPage('pages/' + name + '/index.js', { wx: runtime, fixture: false });
    page.data.profile = { avatar };
    await page.onRemoteAvatarError({ currentTarget: { dataset: { kind: 'profile', source: avatar } } });
    assert.match(page.data.profile.avatar, /^wxfile:\/\/tmp\/avatar-/);
  }
  const contact = loadPage('pages/contact/index.js', { wx: runtime, fixture: false });
  contact.data.conversations = [{ id: 'a', peer: { avatar } }, { id: 'b', peer: { avatar } }];
  await contact.onRemoteAvatarError({ currentTarget: { dataset: { kind: 'conversation', id: 'a', source: avatar } } });
  assert.match(contact.data.conversations[0].peer.avatar, /^wxfile:\/\/tmp\/avatar-/);
  assert.equal(contact.data.conversations[1].peer.avatar, avatar);
  const chat = loadPage('pages/contact-chat/index.js', { wx: runtime, fixture: false });
  chat.data.peer = { avatar }; chat.data.selfAvatar = avatar;
  await chat.onChatAvatarError({ currentTarget: { dataset: { owner: 'peer', source: avatar } } });
  await chat.onChatAvatarError({ currentTarget: { dataset: { owner: 'self', source: avatar } } });
  assert.match(chat.data.peer.avatar, /^wxfile:\/\/tmp\/avatar-/);
  assert.match(chat.data.selfAvatar, /^wxfile:\/\/tmp\/avatar-/);
});

test('chat time labels are readable and independent of runtime locale', () => {
  const { chatTimeLabel } = require('../.test-dist/utils/presentation.js');
  const now = new Date(2026, 9, 31, 12).getTime();
  assert.equal(chatTimeLabel(new Date(2026, 9, 31, 8, 5).getTime(), now), '08:05');
  assert.equal(chatTimeLabel(new Date(2026, 9, 30, 21, 4).getTime(), now), '昨天 21:04');
  assert.equal(chatTimeLabel(new Date(2026, 9, 26, 14, 35).getTime(), now), '10月26日 14:35');
  assert.equal(chatTimeLabel(new Date(2025, 9, 26, 14, 35).getTime(), now), '2025年10月26日 14:35');
  assert.equal(chatTimeLabel(NaN, now), '');
});

const test = require('node:test');
const assert = require('node:assert/strict');
const { loadComponent } = require('./native-harness.cjs');

test('card like and collection events identify only the public post', () => {
  const { instance, events } = loadComponent('components/forum-post-card/index.js');
  instance.data.post = { id: 'post-001' };
  instance.onLikeTap();
  instance.onCollectTap();
  assert.equal(events[0].name, 'like');
  assert.equal(events[0].detail.id, 'post-001');
  assert.equal(events[1].name, 'collect');
  assert.equal(events[1].detail.id, 'post-001');
});
test('the detail more affordance offers bookmark and report for that post', () => {
  const routes = [];
  let items = null;
  const { instance } = loadComponent('components/forum-post-card/index.js', { wx: {
    showActionSheet: options => { items = options.itemList; options.success({ tapIndex: 1 }) },
    navigateTo: options => routes.push(options.url),
  } });
  instance.data.post = { id: 'post-001', collected: false };
  instance.onMoreTap();
  assert.equal(items.join('|'), '加入书签|举报这条内容');
  assert.deepEqual(routes, ['/pages/report/index?kind=post&id=post-001']);
});

test('tapping an image previews the selected item in its own image group', () => {
  const calls = [];
  const { instance } = loadComponent('components/forum-post-card/index.js', {
    wx: { previewImage: options => calls.push(options) },
  });
  instance.data.post = { id: 'post-001', images: ['/one.jpg', '/two.jpg'] };
  instance.onImageTap({ currentTarget: { dataset: { index: 1 } } });
  assert.equal(calls[0].current, '/two.jpg');
  assert.deepEqual(Array.from(calls[0].urls), ['/one.jpg', '/two.jpg']);
});

test('packaged mock images are copied to readable local paths before preview', () => {
  const copied = [];
  const calls = [];
  const { instance } = loadComponent('components/forum-post-card/index.js', {
    wx: {
      env: { USER_DATA_PATH: 'wxfile://usr' },
      getFileSystemManager: () => ({ copyFileSync: (src, dest) => copied.push({ src, dest }) }),
      previewImage: options => calls.push(options),
    },
  });
  instance.data.post = { images: ['/assets/mock/forum/desk.jpg', '/assets/mock/forum/book.jpg'] };
  instance.onImageTap({ currentTarget: { dataset: { index: 1 } } });
  assert.equal(copied.length, 2);
  assert.equal(calls[0].current, 'wxfile://usr/xlk-forum-preview-book.jpg');
  assert.deepEqual(Array.from(calls[0].urls), [
    'wxfile://usr/xlk-forum-preview-desk.jpg', 'wxfile://usr/xlk-forum-preview-book.jpg',
  ]);
});

test('an unreadable preview file shows feedback and does not open an empty viewer', () => {
  const messages = [];
  let previewed = false;
  const { instance } = loadComponent('components/forum-post-card/index.js', {
    wx: {
      env: { USER_DATA_PATH: 'wxfile://usr' },
      getFileSystemManager: () => ({ copyFileSync() { throw new Error('full'); } }),
      showToast: options => messages.push(options.title),
      previewImage: () => { previewed = true; },
    },
  });
  instance.data.post = { images: ['/assets/mock/forum/desk.jpg'] };
  instance.onImageTap({ currentTarget: { dataset: { index: 0 } } });
  assert.equal(previewed, false);
  assert.equal(messages.length, 1);
});

test('tapping your own post avatar opens your own profile instead of the public peer view', () => {
  const routes = [];
  const { instance } = loadComponent('components/forum-post-card/index.js', {
    wx: { navigateTo: options => routes.push(options.url) },
    pages: [{ route: 'pages/forum/index' }],
  });
  instance.data.post = { id: 'local-post-1', authorId: 'local-student', isAnonymous: false, isMine: true };
  instance.onAuthorTap();
  assert.deepEqual(routes, ['/pages/profile/index']);
});

test("tapping someone else's post avatar still opens their public profile", () => {
  const routes = [];
  const { instance } = loadComponent('components/forum-post-card/index.js', {
    wx: { navigateTo: options => routes.push(options.url) },
    pages: [{ route: 'pages/forum/index' }],
  });
  instance.data.post = { id: 'post-001', authorId: 'peer-7', isAnonymous: false, isMine: false };
  instance.onAuthorTap();
  assert.deepEqual(routes, ['/pages/user-home/index?id=peer-7']);
});

test('own avatar on the profile page does not push a duplicate profile', () => {
  const routes = [];
  const { instance } = loadComponent('components/forum-post-card/index.js', {
    wx: { navigateTo: options => routes.push(options.url) },
    pages: [{ route: 'pages/profile/index' }],
  });
  instance.data.post = { id: 'local-post-1', authorId: 'local-student', isAnonymous: false, isMine: true };
  instance.onAuthorTap();
  assert.deepEqual(routes, []);
});

test('local demo posts carry isMine so the avatar and the more menu agree', () => {
  const feed = require('../.test-dist/features/forum/feed.js');
  const repo = require('../.test-dist/features/forum/repository.js');
  const saved = new Map();
  const store = { getStorageSync: k => saved.get(k), setStorageSync: (k, v) => saved.set(k, v) };
  const post = repo.publishForumPost(store, { content: '我的动态', images: [], category: 'campus', anonymous: false }, []);
  const view = feed.toPostView(post, { version: 1, posts: {} });
  assert.equal(view.isMine, true);
  assert.equal(view.authorId, repo.LOCAL_AUTHOR_ID);
  const snapshot = repo.readForum(store);
  const peer = snapshot.posts.find(item => item.author.id !== repo.LOCAL_AUTHOR_ID);
  assert.ok(peer);
  assert.equal(feed.toPostView(peer, snapshot.interactions).isMine, false);
});

test('a guest tapping their own post avatar sees the public profile view', () => {
  const saved = new Map();
  const routes = [];
  const { instance, events } = loadComponent('components/forum-post-card/index.js', {
    wx: {
      __fixtureMode: true,
      getStorageSync: k => saved.get(k),
      setStorageSync: (k, v) => saved.set(k, v),
      navigateTo: options => routes.push(options.url),
    },
    pages: [{ route: 'pages/forum/index' }],
  });
  saved.set('xinkelink.session.v1', false);
  instance.data.post = { id: 'local-post-1', authorId: 'local-student', isAnonymous: false, isMine: true };
  instance.onAuthorTap();
  assert.deepEqual(routes, ['/pages/user-home/index?id=local-student']);
  assert.deepEqual(events, []);
});

test('a guest tapping someone else\'s avatar still opens the public profile', () => {
  const saved = new Map();
  const routes = [];
  const { instance, events } = loadComponent('components/forum-post-card/index.js', {
    wx: {
      __fixtureMode: true,
      getStorageSync: k => saved.get(k),
      setStorageSync: (k, v) => saved.set(k, v),
      navigateTo: options => routes.push(options.url),
    },
    pages: [{ route: 'pages/forum/index' }],
  });
  saved.set('xinkelink.session.v1', false);
  instance.data.post = { id: 'post-001', authorId: 'peer-7', isAnonymous: false, isMine: false };
  instance.onAuthorTap();
  assert.deepEqual(routes, ['/pages/user-home/index?id=peer-7']);
  assert.deepEqual(events, []);
});

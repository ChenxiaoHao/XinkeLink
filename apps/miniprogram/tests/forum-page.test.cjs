const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');

function forum(overrides = {}) {
  const saved = new Map();
  const calls = { stopped: 0, messages: [] };
  const wx = {
    getStorageSync: key => saved.get(key),
    setStorageSync: (key, value) => saved.set(key, value),
    stopPullDownRefresh: () => calls.stopped++,
    showToast: options => calls.messages.push(options.title),
    ...overrides,
  };
  const page = loadPage('pages/forum/index.js', { wx });
  page.onLoad({});
  return { page, saved, calls };
}

test('reaching the bottom appends records and switching category resets pagination', () => {
  const { page } = forum();
  assert.equal(page.data.posts.length, 2);
  page.onReachBottom();
  assert.equal(page.data.posts.length, 4);
  page.onCategoryTap({ currentTarget: { dataset: { category: 'lost-found' } } });
  assert.equal(page.data.posts.length, 2);
  assert.ok(page.data.posts.every(p => p.category === 'lost-found'));
});

test('pull-down refresh resets the page and preserves a local like', () => {
  const { page, calls, saved } = forum();
  page.onLikePost({ detail: { id: 'post-001' } });
  page.onReachBottom();
  page.onPullDownRefresh();
  assert.equal(page.data.posts.length, 2);
  assert.equal(page.data.posts[0].likeCount, 29);
  assert.equal(calls.stopped, 1);
  assert.equal(saved.size, 1);
});

test('a failed local save leaves the previous interaction visible', () => {
  const { page, calls } = forum({ setStorageSync: () => { throw new Error('quota'); } });
  page.onLikePost({ detail: { id: 'post-001' } });
  assert.equal(page.data.posts[0].liked, false);
  assert.equal(page.data.posts[0].likeCount, 28);
  assert.equal(calls.messages.length, 1);
});

test('the search entry filters current mock posts without navigating', () => {
  const { page } = forum({ showModal: options => options.success({ confirm: true, content: 'iPad' }) });
  page.onSearchTap();
  assert.equal(page.data.posts.length, 1);
  assert.equal(page.data.posts[0].id, 'post-002');
});

test('anonymous sharing returns to the home feed without identifying its author', () => {
  const { page } = forum();
  const share = page.onShareAppMessage({ from: 'button', target: { dataset: { postId: 'post-002' } } });
  assert.equal(share.path, '/pages/forum/index?postId=post-002');
  assert.ok(share.title.includes('iPad'));
  assert.equal(JSON.stringify(share).includes('private-author'), false);
});

test('opening a shared later record loads it in the home feed', () => {
  const { page } = forum();
  page.onLoad({ postId: 'post-007' });
  assert.ok(page.data.posts.some(p => p.id === 'post-007'));
});

test('saved likes and collections survive a new page instance', () => {
  const first = forum();
  first.page.onLikePost({ detail: { id: 'post-001' } });
  first.page.onCollectPost({ detail: { id: 'post-002' } });
  const restored = forum({ getStorageSync: key => first.saved.get(key) });
  assert.equal(restored.page.data.posts[0].likeCount, 29);
  assert.equal(restored.page.data.posts[1].collected, true);
});

test('a shared post scrolls below the measured sticky header', () => {
  const calls = [];
  const { page } = forum({ pageScrollTo: options => calls.push(options) });
  page.onLoad({ postId: 'post-007' });
  page.createSelectorQuery = () => ({
    select: () => ({ boundingClientRect: callback => {
      callback({ height: 98 });
      return { exec() {} };
    } }),
  });
  page.onReady();
  assert.equal(calls[0].selector, '#feed-post-007');
  assert.equal(calls[0].offsetTop, -98);
});

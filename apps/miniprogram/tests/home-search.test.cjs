const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');

function forum() {
  let sheet;
  const toasts = [];
  const page = loadPage('pages/forum/index.js', { wx: {
    getStorageSync() {}, setStorageSync() {}, pageScrollTo() {},
    showActionSheet(options) { sheet = options; },
    showToast(options) { toasts.push(options.title); },
  } });
  page.onLoad({});
  return { page, toasts, sheet: () => sheet };
}

test('forum search-bar filter delegates to the existing category and resets the list correctly', () => {
  const context = forum(), { page } = context;
  page.onReachBottom();
  page.onForumFilterTap();
  const index = page.data.categories.findIndex(item => item.key === 'lost-found');
  assert.deepEqual([...context.sheet().itemList], [...page.data.categories].map(item => item.label));
  context.sheet().success({ tapIndex: index });
  assert.equal(page.data.activeCategory, 'lost-found');
  assert.ok(page.data.posts.every(post => post.category === 'lost-found'));
});

test('invalid filter results and cancellation preserve the current forum category', () => {
  const context = forum(), { page } = context;
  const initialPosts = page.data.posts;
  page.onForumFilterTap();
  context.sheet().success({ tapIndex: 99 });
  context.sheet().fail({ errMsg: 'showActionSheet:fail cancel' });
  assert.equal(page.data.activeCategory, 'recommended');
  assert.equal(page.data.posts, initialPosts);
  assert.equal(context.toasts.length, 0);
});

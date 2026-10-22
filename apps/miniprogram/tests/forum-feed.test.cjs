const test = require('node:test');
const assert = require('node:assert/strict');
const { MOCK_POSTS } = require('../.test-dist/features/forum/mock-posts.js');
const { queryFeed, toPostView, normalizeInteractions, toggleInteraction } = require('../.test-dist/features/forum/feed.js');

const empty = () => normalizeInteractions(undefined);
const query = (options = {}, state = empty()) => queryFeed(MOCK_POSTS, {
  category: 'recommended', offset: 0, limit: 2, keyword: '', ...options,
}, state);

test('recommended pages are ordered and append without duplicates through the end', () => {
  const first = query();
  assert.deepEqual(first.items.map(p => p.id), ['post-001', 'post-002']);
  const ids = [...first.items.map(p => p.id)];
  let offset = first.nextOffset;
  let hasMore = first.hasMore;
  while (hasMore) {
    const page = query({ offset });
    ids.push(...page.items.map(p => p.id));
    offset = page.nextOffset;
    hasMore = page.hasMore;
  }
  assert.equal(ids.length, MOCK_POSTS.length);
  assert.equal(new Set(ids).size, ids.length);
});

test('latest uses publication time instead of recommendation rank', () => {
  const page = query({ category: 'latest' });
  assert.equal(page.items[0].id, 'post-009');
  assert.notEqual(page.items[0].id, query().items[0].id);
});

for (const category of ['campus', 'lost-found', 'study', 'help']) {
  test('category ' + category + ' contains only matching posts', () => {
    const page = query({ category, limit: 30 });
    assert.ok(page.items.length >= 2);
    assert.ok(page.items.every(p => p.category === category));
  });
}

test('anonymous public data does not expose the raw author', () => {
  const raw = MOCK_POSTS.find(p => p.id === 'post-002');
  const view = toPostView(raw, empty());
  assert.equal(view.authorName, '匿名同学 023');
  assert.equal(view.avatar, '/assets/icons/user-round-muted.png');
  assert.equal(view.isAnonymous, true);
  assert.equal('author' in view, false);
  assert.equal('authorId' in view, false);
  assert.equal(JSON.stringify(view).includes(raw.author.nickname), false);
  assert.equal(JSON.stringify(view).includes(raw.author.id), false);
});

test('single, double, four-image and multi-image records choose the intended grids', () => {
  const layouts = Object.fromEntries(MOCK_POSTS.map(p => [p.id, toPostView(p, empty()).imageLayout]));
  assert.equal(layouts['post-001'], 'quad');
  assert.equal(layouts['post-002'], 'single');
  assert.equal(layouts['post-003'], 'double');
  assert.equal(layouts['post-005'], 'grid');
  assert.equal(layouts['post-004'], 'none');
});

test('liking twice returns to the original count and does not affect collection', () => {
  let state = empty();
  state = toggleInteraction(MOCK_POSTS, state, 'post-001', 'liked');
  let post = query({}, state).items[0];
  assert.equal(post.liked, true);
  assert.equal(post.likeCount, 29);
  assert.equal(post.collected, true);
  state = toggleInteraction(MOCK_POSTS, state, 'post-001', 'liked');
  post = query({}, state).items[0];
  assert.equal(post.likeCount, 28);
  assert.equal(post.liked, false);
});

test('collection survives serialization and a different category query', () => {
  const state = toggleInteraction(MOCK_POSTS, empty(), 'post-002', 'collected');
  const restored = normalizeInteractions(JSON.parse(JSON.stringify(state)));
  const post = query({ category: 'lost-found' }, restored).items.find(p => p.id === 'post-002');
  assert.equal(post.collected, true);
  assert.equal(post.likeCount, 8);
});

test('malformed or unsupported local storage resets safely', () => {
  for (const value of [null, 'broken', { version: 2, posts: {} }, { version: 1, posts: null }]) {
    assert.deepEqual(normalizeInteractions(value), { version: 1, posts: {} });
  }
  const restored = normalizeInteractions({ version: 1, posts: {
    'post-001': { liked: true, collected: false },
    'post-002': { liked: 'true', collected: false },
  } });
  assert.deepEqual(Object.keys(restored.posts), ['post-001']);
});

test('keyword matching and an exhausted page do not invent results', () => {
  const page = query({ keyword: 'iPad', limit: 20 });
  assert.equal(page.items.length, 1);
  assert.equal(page.items[0].id, 'post-002');
  assert.equal(query({ offset: 999 }).hasMore, false);
  assert.equal(query({ offset: 999 }).items.length, 0);
  assert.equal(query({ keyword: '不存在的测试关键词' }).items.length, 0);
});

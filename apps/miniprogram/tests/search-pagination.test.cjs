const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');
const post = id => ({ id, author: { nickname: '同学', isAnonymous: true }, content: '校园日常', images: [], createdAt: new Date().toISOString() });
function setup(handler) {
  const calls = [], toasts = [];
  const wx = { getStorageSync() {}, setStorageSync() {}, showToast: o => toasts.push(o.title), request(o) {
    const url = new URL(o.url); calls.push(url);
    handler(url, value => o.success({ statusCode: 200, data: { code: 'OK', data: value } }), () => o.fail({ errMsg: 'offline' }));
  } };
  return { page: loadPage('pages/search/index.js', { wx, fixture: false, betaPhase: 'open' }), calls, toasts };
}
const response = (items, cursor = null) => ({ result: { items, hasMore: !!cursor, nextCursor: cursor } });

test('search loads beyond fifty results using the server cursor and keeps earlier likes', async () => {
  const { page, calls } = setup((url, ok) => {
    const offset = Number(url.searchParams.get('cursor') || 0);
    ok(response(Array.from({ length: 20 }, (_, i) => post(String(offset + i + 1))), offset < 40 ? String(offset + 20) : null));
  });
  page.setData({ query: '校园' }); await page.runSearch();
  page.data.postResults[0].liked = true;
  await page.onReachBottom(); await page.onReachBottom();
  assert.equal(page.data.postResults.length, 60);
  assert.equal(page.data.postResults[0].liked, true);
  assert.equal(page.data.postHasMore, false);
  assert.deepEqual(calls.map(url => url.searchParams.get('cursor')), [null, '20', '40']);
  await page.onReachBottom(); assert.equal(calls.length, 3);
});

test('typing or clearing invalidates an in-flight search instead of resurrecting old results', async () => {
  for (const clear of [false, true]) {
    const finish = [];
    const { page } = setup((url, ok) => finish.push(() => ok(response([post('old')]))));
    page.setData({ query: '旧关键词' }); const pending = page.runSearch();
    if (clear) page.onClearInput(); else page.onInput({ detail: { value: '新关键词' } });
    finish.forEach(done => done()); await pending;
    assert.equal(page.data.hasSearched, false); assert.equal(page.data.postResults.length, 0);
    assert.equal(page.data.query, clear ? '' : '新关键词');
  }
});

test('load-more failure preserves results and cursor, and a retry appends without duplicates', async () => {
  let failNext = true;
  const { page } = setup((url, ok, fail) => {
    if (!url.searchParams.get('cursor')) return ok(response([post('1')], 'next'));
    if (failNext) { failNext = false; return fail(); }
    ok(response([post('1'), post('2')]));
  });
  page.setData({ query: '校园' }); await page.runSearch(); await page.onReachBottom();
  assert.equal(page.data.searchFailed, false); assert.equal(page.data.postResults.length, 1);
  assert.equal(page.data.postPageFailed, true);
  await page.onLoadMoreTap();
  assert.deepEqual(page.data.postResults.map(item => item.id), ['1', '2']);
  assert.equal(page.data.postPageFailed, false);
});

test('repeated bottom events share one pending page request', async () => {
  let finish;
  const { page, calls } = setup((url, ok) => {
    if (!url.searchParams.get('cursor')) return ok(response([post('1')], 'next'));
    finish = () => ok(response([post('2')]));
  });
  page.setData({ query: '校园' }); await page.runSearch();
  const pending = page.onReachBottom(); await page.onReachBottom();
  assert.equal(calls.length, 2); finish(); await pending;
  assert.equal(page.data.postResults.length, 2);
});

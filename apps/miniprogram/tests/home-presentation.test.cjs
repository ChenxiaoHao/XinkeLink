const { BACKEND_ORIGIN: configuredOrigin } = require('../.test-dist/backend/config.js');
const test = require('node:test');
const assert = require('node:assert/strict');
const { postView } = require('../.test-dist/backend/adapters.js');
const { loadComponent } = require('./native-harness.cjs');
const { loadPage } = require('./page-harness.cjs');
const author = { id: null, nickname: '匿名同学', avatar: null, isAnonymous: true };
const post = images => ({ id: 'post-1', content: '校园里的小事', category: 'campus', author, images, createdAt: '2026-10-29T01:00:00.000Z' });

test('post image metadata gives portrait and landscape frames without exposing an anonymous author', () => {
  const portrait = postView(post([{ width: 600, height: 800, url: '/portrait.png' }]));
  const landscape = postView(post([{ width: 1200, height: 800, url: '/landscape.png' }]));
  assert.equal(portrait.singleImagePadding, 75);
  assert.equal(landscape.singleImagePadding, 100 / 1.5);
  assert.equal(portrait.authorId, undefined);
  assert.deepEqual(portrait.images, [configuredOrigin + "/portrait.png"]);
});

test('home image events ignore stale requests and leave detail presentation untouched', () => {
  const { instance } = loadComponent('components/forum-post-card/index.js');
  instance.data.home = true;
  instance.data.post = { id: 'p-1', images: ['/new.png'] };
  instance.onImageLoad({ currentTarget: { dataset: { source: '/old.png', index: 0 } }, detail: { width: 600, height: 800 } });
  assert.equal(instance.data.singleImagePadding, 75);
  instance.onImageLoad({ currentTarget: { dataset: { source: '/new.png', index: 0 } }, detail: { width: 600, height: 800 } });
  assert.equal(instance.data.singleImagePadding, 75);
  instance.data.home = false;
  // 非 home（详情等形态）的 onImageLoad 不改动列表比例；此断言验证 padding 保持上一个值不变。
  instance.onImageLoad({ currentTarget: { dataset: { source: '/new.png', index: 0 } }, detail: { width: 1200, height: 600 } });
  assert.equal(instance.data.singleImagePadding, 75);
});

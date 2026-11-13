const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');

const INTERACTIONS_KEY = 'xinkelink.forum.interactions.v1';
const COMMENTS_KEY = 'xinkelink.forum.comments.v1';
const LIKED_SEED = { version: 1, posts: { 'post-001': { liked: true, collected: false, reposted: false } } };

function storageStub(session) {
  const saved = new Map();
  const wxStub = {
    __fixtureMode: true,
    getStorageSync: k => saved.get(k),
    setStorageSync: (k, v) => saved.set(k, v),
    navigateTo() {}, navigateBack() {}, switchTab() {}, stopPullDownRefresh() {},
    showToast() {}, showModal: options => { wxStub.modals.push(options); },
    pageScrollTo() {},
  };
  wxStub.modals = [];
  saved.set('xinkelink.session.v1', session);
  saved.set(INTERACTIONS_KEY, LIKED_SEED);
  saved.set(COMMENTS_KEY, { version: 1, added: [], deleted: [], likes: { 'post-001-comment-0': true } });
  return { saved, wxStub };
}

test('a guest sees neutral like states even though the previous user liked posts', () => {
  const { wxStub } = storageStub(false);
  const page = loadPage('pages/forum/index.js', { wx: wxStub });
  page.onLoad({});
  const liked = page.data.posts.find(post => post.id === 'post-001');
  assert.ok(liked, '演示模式首页应包含 post-001');
  assert.equal(liked.liked, false, '访客不应看到上一个用户的点赞状态');
  const baseLikes = require('../.test-dist/features/forum/mock-posts.js').MOCK_POSTS.find(post => post.id === 'post-001').likes;
  assert.equal(liked.likeCount, baseLikes, '访客看到的点赞数应为基准值');
  const repository = require('../.test-dist/features/forum/repository.js');
  const snapshot = repository.readForum(wxStub);
  assert.deepEqual(snapshot.interactions.posts, {}, '访客读取互动状态应为空');
  assert.deepEqual(snapshot.commentState.likes, {}, '访客不应继承上一个用户的评论点赞');
});

test('a logged-in user still sees their own likes after the fix', () => {
  const { wxStub } = storageStub(true);
  const page = loadPage('pages/forum/index.js', { wx: wxStub });
  page.onLoad({});
  const liked = page.data.posts.find(post => post.id === 'post-001');
  assert.equal(liked.liked, true, '登录用户应看到自己的点赞');
  const repository = require('../.test-dist/features/forum/repository.js');
  assert.deepEqual(repository.readForum(wxStub).commentState.likes, { 'post-001-comment-0': true });
});

test('a guest tapping like gets the login prompt and nothing is written', () => {
  const { saved, wxStub } = storageStub(false);
  const repository = require('../.test-dist/features/forum/repository.js');
  repository.togglePostInteraction(wxStub, 'post-001', 'liked');
  assert.equal(wxStub.modals.length, 1, '访客点赞应弹一键登录');
  assert.deepEqual(saved.get(INTERACTIONS_KEY), LIKED_SEED, '互动存储不应被访客写入');
  repository.toggleForumCommentLike(wxStub, 'post-001', 'post-001-comment-0');
  assert.equal(wxStub.modals.length, 2, '访客评论点赞同样弹登录');
  const comments = saved.get(COMMENTS_KEY);
  assert.deepEqual(comments.likes, { 'post-001-comment-0': true }, '评论点赞存储不应被访客改动');
});

test('guest state in the forum feed stays neutral across page reloads', () => {
  const { wxStub } = storageStub(false);
  const page = loadPage('pages/forum/index.js', { wx: wxStub });
  page.onLoad({});
  page.onShow();
  const liked = page.data.posts.find(post => post.id === 'post-001');
  assert.equal(liked.liked, false);
  page.onPullDownRefresh();
  assert.equal(page.data.posts.find(post => post.id === 'post-001').liked, false);
});

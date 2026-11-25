const test = require('node:test');
const assert = require('node:assert/strict');
const { loadComponent } = require('./native-harness.cjs');
const { BACKEND_ORIGIN } = require('../.test-dist/backend/config.js');
const { rememberImage } = require('../.test-dist/backend/image-urls.js');
const { client } = require('../.test-dist/backend/client.js');

function imageCard(request) {
  const calls = [];
  const wx = {
    getStorageSync: () => ({ accessToken: 'a', refreshToken: 'r', expiresAt: Date.now() + 600000, me: { id: '17' } }),
    setStorageSync() {},
    request(options) { calls.push(options); request(options); },
  };
  const { instance, definition } = loadComponent('components/forum-post-card/index.js', { wx, fixture: false });
  const observers = [];
  instance.createIntersectionObserver = options => {
    const observer = {
      options, disconnected: false, relativeToViewport() { return this; },
      observe(selector, callback) { this.selector = selector; this.callback = callback; },
      disconnect() { this.disconnected = true; },
    };
    observers.push(observer);
    return observer;
  };
  instance.setData = (patch, callback) => { Object.assign(instance.data, patch); callback?.(); };
  function render(post) {
    instance.data.post = post;
    definition.observers.post.call(instance, post);
  }
  return { calls, instance, observers, render, wx };
}

const signedImage = (id, grant = 'old') => BACKEND_ORIGIN + '/api/v1/images/' + id + '/content?grant=' + grant;
const imageReply = (request, id) => request.success({
  statusCode: 200,
  data: { code: 'OK', data: { url: signedImage(id, 'renewed'), expiresAt: new Date(Date.now() + 60000).toISOString() } },
});
const settleImage = () => new Promise(resolve => setImmediate(resolve));

test('an offscreen card with an expired signed image checks authorization before binding a thumbnail', async () => {
  const card = imageCard(request => imageReply(request, '401'));
  const source = signedImage('401');
  rememberImage(source, new Date(Date.now() - 1000).toISOString());
  card.render({ id: 'post-1', images: [source, '/static.png'] });
  assert.deepEqual(Array.from(card.instance.data.imageSources), ['', '/static.png']);
  assert.equal(card.observers.length, 1);
  assert.equal(card.observers[0].options.nativeMode, true);
  assert.equal(card.calls.length, 0);
  card.observers[0].callback({ intersectionRatio: 0 });
  assert.equal(card.calls.length, 0);
  card.observers[0].callback({ intersectionRatio: 0.1 });
  card.observers[0].callback({ intersectionRatio: 0.2 });
  await settleImage();
  assert.equal(card.calls.length, 1);
  assert.match(card.calls[0].url, /\/images\/401\/access$/);
  assert.equal(card.instance.data.imageSources[0], signedImage('401', 'renewed'));
  assert.equal(card.instance.data.post.images[0], source);
  assert.equal(card.observers[0].disconnected, true);
});

test('older runtimes still defer authorization when the native observer is unavailable', async () => {
  const card = imageCard(request => imageReply(request, '405'));
  const createObserver = card.instance.createIntersectionObserver;
  card.instance.createIntersectionObserver = options => {
    if (options.nativeMode) throw Error('Unsupported observer mode');
    return createObserver(options);
  };
  card.render({ id: 'legacy-post', images: [signedImage('405')] });
  assert.equal(card.observers.length, 1);
  assert.equal(card.calls.length, 0);
  card.observers[0].callback({ intersectionRatio: 0.5 });
  await settleImage();
  assert.equal(card.calls.length, 1);
  assert.equal(card.instance.data.imageSources[0], signedImage('405', 'renewed'));
});

test('visible cards reuse an unexpired image and keep a denied image unbound', async () => {
  const card = imageCard(request => request.success({ statusCode: 403, data: { code: 'IMAGE_FORBIDDEN' } }));
  const fresh = signedImage('402'), denied = signedImage('403');
  rememberImage(fresh, new Date(Date.now() + 60000).toISOString());
  card.render({ id: 'post-2', images: [fresh, denied] });
  card.observers[0].callback({ intersectionRatio: 0.1 });
  await settleImage();
  assert.deepEqual(Array.from(card.instance.data.imageSources), [fresh, '']);
  assert.equal(card.instance.data.imageFailures[denied], true);
  assert.equal(card.calls.length, 1);
  assert.match(card.calls[0].url, /\/images\/403\/access$/);
});

test('a pending thumbnail renewal cannot paint after the card or account changes', async () => {
  for (const change of ['post', 'account']) {
    let response;
    const card = imageCard(request => { response = request; });
    card.render({ id: 'post-3', images: [signedImage('404')] });
    card.observers[0].callback({ intersectionRatio: 0.1 });
    if (change === 'post') card.render({ id: 'post-4', images: ['/replacement.png'] });
    else client(card.wx).clear();
    imageReply(response, '404');
    await settleImage();
    assert.notEqual(card.instance.data.imageSources?.[0], signedImage('404', 'renewed'));
  }
});

test('own post menu exposes edit and delete',()=>{
 let items;const {instance}=loadComponent('components/forum-post-card/index.js',{wx:{getStorageSync:k=>k==='xinkelink.session.v1'?true:undefined,showActionSheet:o=>items=o.itemList}});
 instance.data.post={id:'local-post-own',isMine:true};instance.onMoreTap();assert.deepEqual(Array.from(items),['编辑帖子','删除帖子']);
});
test('confirmed fixture deletion removes own anonymous post and reloads feed',async()=>{
 const saved=new Map([['xinkelink.session.v1',true]]);let menu,modal,reloaded=0;
 const wx={getStorageSync:k=>saved.get(k),setStorageSync:(k,v)=>saved.set(k,v),showActionSheet:o=>menu=o,showModal:o=>modal=o,showToast(){}};
 const repo=require('../.test-dist/features/forum/repository');const post=repo.publishForumPost(wx,{content:'delete regression',category:'campus',anonymous:true,images:[]},[]);
 const {instance}=loadComponent('components/forum-post-card/index.js',{wx,pages:[{route:'pages/forum/index',onShow(){reloaded++}}]});instance.data.post={id:post.id,isMine:true};instance.onMoreTap();menu.success({tapIndex:1});await modal.success({confirm:true});assert.equal(repo.readLocalPosts(wx).length,0);assert.equal(reloaded,1);
});
test('live deletion respects version and HTTP failure',async()=>{
 for(const status of [200,409]){let menu,modal,reloaded=0;const calls=[],toasts=[];const saved=new Map([['xinkelink.backend.session.v1',{accessToken:'valid',refreshToken:'refresh',expiresAt:Date.now()+3600000,me:{id:'alice'}}]]);
 const wx={getStorageSync:k=>saved.get(k),showActionSheet:o=>menu=o,showModal:o=>modal=o,showToast:o=>toasts.push(o.title),request:o=>{calls.push(o);o.success({statusCode:status,data:{code:status===200?'OK':'VERSION_CONFLICT',data:{}}})}};
 const {instance}=loadComponent('components/forum-post-card/index.js',{wx,fixture:false,pages:[{route:'pages/forum/index',reload(){reloaded++}}]});instance.data.post={id:'42',isMine:true,version:7};instance.onMoreTap();menu.success({tapIndex:1});await modal.success({confirm:true});assert.equal(calls.length,1);assert.equal(calls[0].method,'DELETE');assert.match(calls[0].url,/\/posts\/42$/);assert.equal(calls[0].header['If-Match'],'"7"');assert.equal(reloaded,status===200?1:0);assert.equal(toasts.includes('帖子已删除'),status===200);}
});
test('logout during confirmation prevents deletion',async()=>{
 let logged=true,menu,modal,writes=0;const wx={getStorageSync:k=>k==='xinkelink.session.v1'?logged:undefined,setStorageSync(){writes++},showActionSheet:o=>menu=o,showModal:o=>modal=o,showToast(){}};
 const{instance}=loadComponent('components/forum-post-card/index.js',{wx});instance.data.post={id:'local-post-own',isMine:true};instance.onMoreTap();menu.success({tapIndex:1});logged=false;await modal.success({confirm:true});assert.equal(writes,0);
});
test('switching live account while the menu is open prevents deleting the previous account post',()=>{
 let menu,writes=0;const wx={getStorageSync:()=>({accessToken:'a',refreshToken:'r',expiresAt:Date.now()+3600000,me:{id:'alice'}}),showActionSheet:o=>menu=o,showModal(){writes++}};
 const {instance}=loadComponent('components/forum-post-card/index.js',{wx,fixture:false});instance.data.post={id:'42',isMine:true,version:0};instance.onMoreTap();
 require('../.test-dist/backend/client').client(wx).session={accessToken:'b',me:{id:'bob'}};
 menu.success({tapIndex:1});assert.equal(writes,0);
});

test('card like identifies the public post and bookmark is unavailable', () => {
  const { instance, events } = loadComponent('components/forum-post-card/index.js');
  instance.data.post = { id: 'post-001' };
  instance.onLikeTap();
  assert.equal(events[0].name, 'like');
  assert.equal(events[0].detail.id, 'post-001');
  assert.equal(instance.onCollectTap, undefined);
  assert.equal(events.length, 1);
});
test('unavailable repost shows feedback without emitting an event or reading a session', () => {
  const toasts = [];
  const { instance, events } = loadComponent('components/forum-post-card/index.js', { wx: {
    getStorageSync() { throw Error('repost must not need identity'); },
    showToast(options) { toasts.push(options.title); },
  } });
  instance.data.post = { id: 'post-001' };
  instance.onRepostTap();
  assert.equal(events.length, 0);
  assert.match(toasts[0], /转发.*(未|即将)/);
});
test('the detail more affordance offers only report for that post', () => {
  const routes = [];
  let items = null;
  const { instance } = loadComponent('components/forum-post-card/index.js', { wx: {
    showActionSheet: options => { items = options.itemList; options.success({ tapIndex: 0 }) },
    navigateTo: options => routes.push(options.url),
  } });
  instance.data.post = { id: 'post-001', collected: false };
  instance.onMoreTap();
  assert.equal(items.join('|'), '举报这条内容');
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
    wx: { __fixtureMode: true, getStorageSync: key => key === 'xinkelink.session.v1' ? true : undefined, navigateTo: options => routes.push(options.url) },
    pages: [{ route: 'pages/forum/index' }],
  });
  instance.data.post = { id: 'local-post-1', authorId: 'local-student', isAnonymous: false, isMine: true };
  instance.onAuthorTap();
  assert.deepEqual(routes, ['/pages/profile/index']);
});

test("tapping someone else's post avatar still opens their public profile", () => {
  const routes = [];
  const { instance } = loadComponent('components/forum-post-card/index.js', {
    wx: { __fixtureMode: true, getStorageSync: key => key === 'xinkelink.session.v1' ? true : undefined, navigateTo: options => routes.push(options.url) },
    pages: [{ route: 'pages/forum/index' }],
  });
  instance.data.post = { id: 'post-001', authorId: 'peer-7', isAnonymous: false, isMine: false };
  instance.onAuthorTap();
  assert.deepEqual(routes, ['/pages/user-home/index?id=peer-7']);
});

test('own avatar on the profile page does not push a duplicate profile', () => {
  const routes = [];
  const { instance } = loadComponent('components/forum-post-card/index.js', {
    wx: { getStorageSync: key => key === 'xinkelink.session.v1' ? true : undefined, navigateTo: options => routes.push(options.url) },
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

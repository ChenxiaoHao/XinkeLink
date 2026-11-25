const { BACKEND_ORIGIN: configuredOrigin } = require('../.test-dist/backend/config.js');
const test = require('node:test');
const assert = require('node:assert/strict');
const { media } = require('../.test-dist/backend/adapters.js');
const { BackendService } = require('../.test-dist/backend/service.js');
const { PageImageRenewal } = require('../.test-dist/backend/preview.js');
const { loadPage } = require('./page-harness.cjs');
const { loadComponent } = require('./native-harness.cjs');
const origin = configuredOrigin;
const grant = (id, token, expiresAt) => ({ imageId: id, url: '/api/v1/images/' + id + '/content?grant=' + token, expiresAt });
function setup(handler) {
  const calls = [], toasts = [], saved = new Map([['xinkelink.backend.session.v1',{accessToken:'a',refreshToken:'r',expiresAt:Date.now()+600000,me:{id:'17',nickname:'同学'}}]]);
  const wx = { getStorageSync: k => saved.get(k), setStorageSync: (k, v) => saved.set(k, v), showToast: o => toasts.push(o.title), request(o) {
    calls.push(o); handler(o);
  } };
  return { wx, calls, saved, toasts };
}

test('visible image URLs are not proactively replaced on a timer', async () => {
  const r = setup(() => {}), original = global.setTimeout; let scheduled = 0;
  global.setTimeout = (...args) => { scheduled++; return original(...args); };
  try {
    const renewal = new PageImageRenewal(r.wx, { data: { avatar: '' }, setData() {} });
    await renewal.start(); renewal.stop();
    assert.equal(scheduled, 0);
  } finally { global.setTimeout = original; }
});
const ok = (o, value) => o.success({ statusCode: 200, data: { code: 'OK', data: value } });

test('header renews the stored avatar before rendering and shares the fresh address with the drawer', async () => {
  const old = grant('98', 'old', '2020-01-01T00:00:00Z');
  const fresh = grant('98', 'fresh', new Date(Date.now() + 60000).toISOString());
  let renewal;
  const r = setup(o => {
    if (o.url.endsWith('/images/98/access')) renewal = o;
    else o.success({statusCode: 404, data: {code: 'NOT_FOUND'}});
  });
  r.saved.get('xinkelink.backend.session.v1').me.avatar = old;
  const header = loadComponent('components/page-header/index.js', { wx: r.wx, fixture: false }).instance;
  const drawer = loadComponent('components/app-shell/index.js', { wx: r.wx, fixture: false }).instance;
  const showing = header.refresh();
  const opening = drawer.refreshIdentity();
  assert.equal(header.data.avatar, '');
  assert.equal(drawer.data.identity.avatar, '/assets/icons/user-round-muted.png');
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(renewal);
  ok(renewal, fresh);
  await Promise.all([showing, opening]);
  assert.equal(header.data.avatar, origin + fresh.url);
  assert.equal(drawer.data.identity.avatar, origin + fresh.url);
  await header.refresh();
  assert.equal(r.calls.filter(o => o.url.endsWith('/images/98/access')).length, 1);
});

test('a stored avatar renewal arriving after logout is discarded', async () => {
  let renewal;
  const r = setup(o => { renewal = o; });
  r.saved.get('xinkelink.backend.session.v1').me.avatar = grant('99', 'old', '2020-01-01T00:00:00Z');
  const { definition, instance } = loadComponent('components/page-header/index.js', { wx: r.wx, fixture: false });
  definition.lifetimes.attached.call(instance);
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(renewal);
  require('../.test-dist/backend/client.js').client(r.wx).clear();
  ok(renewal, grant('99', 'fresh', new Date(Date.now() + 60000).toISOString()));
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(instance.data.loggedIn, false);
  assert.equal(instance.data.avatar, '');
  definition.lifetimes.detached.call(instance);
});

test('a visible page renews expired avatars and images without a business reload or draft loss', async () => {
  const old = media(grant('91', 'old', '2020-01-01T00:00:00Z'));
  const r = setup(o => ok(o, grant('91', 'fresh', new Date(Date.now() + 60000).toISOString())));
  const p = loadPage('pages/forum-publish/index.js', { ...r, fixture: false });
  p.setData({ form: { content: '尚未发布的内容', images: [old, 'wxfile://photo'] }, avatar: old });
  let reloads = 0; p.reload = () => { reloads++; };
  await p.onShow();
  assert.equal(p.data.form.images[0], origin + '/api/v1/images/91/content?grant=fresh');
  assert.equal(p.data.avatar, p.data.form.images[0]); assert.equal(p.data.form.content, '尚未发布的内容');
  assert.equal(p.data.form.images[1], 'wxfile://photo'); assert.equal(reloads, 0); assert.equal(r.calls.length, 1);
  p.onHide(); p.onUnload();
});

test('an image removed while renewal is pending cannot be written into a reordered form', async () => {
  let pending;
  const old = media(grant('92', 'old', '2020-01-01T00:00:00Z'));
  const r = setup(o => { pending = o; });
  const p = loadPage('pages/forum-publish/index.js', { ...r, fixture: false });
  p.setData({ form: { content: '草稿', images: [old] } });
  const showing = p.onShow(); await new Promise(resolve => setImmediate(resolve));
  p.setData({ form: { content: '修改后的草稿', images: ['wxfile://replacement'] } });
  if (pending) ok(pending, grant('92', 'fresh', new Date(Date.now() + 60000).toISOString()));
  await showing;
  assert.equal(r.calls.length, 1); assert.equal(p.data.form.images[0], 'wxfile://replacement');
  p.onHide(); p.onUnload();
});

test('permission denial clears the inaccessible image and never falls back to its expired grant', async () => {
  const old = media(grant('93', 'old', '2020-01-01T00:00:00Z'));
  const r = setup(o => o.success({ statusCode: 403, data: { code: 'IMAGE_FORBIDDEN', message: '无权查看' } }));
  const p = loadPage('pages/forum-publish/index.js', { ...r, fixture: false });
  p.setData({ avatar: old }); await p.onShow();
  assert.equal(r.calls.length, 1); assert.equal(p.data.avatar, ''); assert.ok(r.toasts.length);
  p.onHide(); p.onUnload();
});

test('saving an edited item recognizes renewed URLs as existing images instead of uploading them again', async () => {
  let uploaded = false;
  const service = new BackendService({ upload: async () => { uploaded = true; throw new Error('must not upload remote URL'); } });
  const ids = await service.images([origin + '/api/v1/images/94/content?grant=new'], 'post', [grant('94', 'old', '')]);
  assert.deepEqual(ids, ['94']); assert.equal(uploaded, false);
});

test('multiple pending renewals recheck the latest form at commit time', async () => {
  const pending = [];
  const oldA = media(grant('95', 'old', '2020-01-01T00:00:00Z'));
  const oldB = media(grant('96', 'old', '2020-01-01T00:00:00Z'));
  const r = setup(o => pending.push(o));
  const p = loadPage('pages/forum-publish/index.js', { ...r, fixture: false });
  p.setData({ form: { content: '草稿', images: [oldA, oldB] } });
  const showing = p.onShow(); await new Promise(resolve => setImmediate(resolve));
  ok(pending[0], grant('95', 'fresh', new Date(Date.now() + 60000).toISOString()));
  await new Promise(resolve => setImmediate(resolve));
  p.setData({ form: { content: '草稿', images: ['wxfile://replacement', oldB] } });
  ok(pending[1], grant('96', 'fresh', new Date(Date.now() + 60000).toISOString()));
  await showing;
  assert.equal(p.data.form.images[0], 'wxfile://replacement');
  assert.equal(p.data.form.images[1], origin + '/api/v1/images/96/content?grant=fresh');
  p.onHide(); p.onUnload();
});

test('image renewal never rewrites user-authored text containing an image URL', async () => {
  const old = media(grant('97', 'old', '2020-01-01T00:00:00Z'));
  const r = setup(o => ok(o, grant('97', 'fresh', new Date(Date.now() + 60000).toISOString())));
  const p = loadPage('pages/forum-publish/index.js', { ...r, fixture: false });
  p.setData({ query: old, form: { title: old, content: old, images: [old] } });
  await p.onShow();
  assert.equal(p.data.form.content, old); assert.equal(p.data.form.title, old); assert.equal(p.data.query, old);
  assert.notEqual(p.data.form.images[0], old);
  p.onHide(); p.onUnload();
});

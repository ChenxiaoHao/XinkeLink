const test = require('node:test');
const assert = require('node:assert/strict');
const { loadComponent } = require('./native-harness.cjs');

test('a regular tab requests the correct native route', () => {
  const calls = [];
  const { instance } = loadComponent('custom-tab-bar/index.js', {
    wx: { switchTab: (options) => calls.push(options) },
  });
  instance.onTabTap({ currentTarget: { dataset: { key: 'search' } } });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/pages/search/index');
});

test('failed navigation keeps the current selected tab', () => {
  const messages = [];
  const { instance } = loadComponent('custom-tab-bar/index.js', {
    wx: {
      switchTab: (options) => options.fail({ errMsg: 'failed' }),
      showToast: (options) => messages.push(options.title),
    },
  });
  instance.onTabTap({ currentTarget: { dataset: { key: 'notifications' } } });
  assert.equal(instance.data.activeKey, 'forum');
  assert.equal(messages.length, 1);
});

test('a tab instance reads the actual page when attached', () => {
  const { definition, instance } = loadComponent('custom-tab-bar/index.js', {
    pages: [{ route: 'pages/contact/index' }],
  });
  definition.lifetimes.attached.call(instance);
  assert.equal(instance.data.activeKey, 'contact');
});

test('invalid tabs and the already selected tab do not navigate', () => {
  let switches = 0;
  const { instance } = loadComponent('custom-tab-bar/index.js', {
    wx: { switchTab: () => switches++ },
  });
  instance.onTabTap({ currentTarget: { dataset: { key: 'missing' } } });
  instance.onTabTap({ currentTarget: { dataset: { key: 'forum' } } });
  assert.equal(switches, 0);
});

test('publish action opens the post composer directly without changing the active tab',()=>{const routes=[];const {instance}=loadComponent('custom-tab-bar/index.js',{wx:{navigateTo:o=>routes.push(o.url),switchTab(){throw Error('unexpected switch')}}});instance.openPublish();assert.deepEqual(routes,['/pages/forum-publish/index']);assert.equal(instance.data.activeKey,'forum');assert.equal(instance.data.publishVisible,false);});
test('publish navigation failure exposes retry feedback',()=>{const messages=[];const {instance}=loadComponent('custom-tab-bar/index.js',{wx:{navigateTo:o=>o.fail(),showToast:o=>messages.push(o.title)}});instance.openPublish();assert.equal(messages.length,1);});

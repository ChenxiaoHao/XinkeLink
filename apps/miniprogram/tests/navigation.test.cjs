const test = require('node:test');
const assert = require('node:assert/strict');
const { loadComponent } = require('./native-harness.cjs');

test('a regular tab requests the correct native route', () => {
  const calls = [];
  const { instance } = loadComponent('custom-tab-bar/index.js', {
    wx: { switchTab: (options) => calls.push(options) },
  });
  instance.onTabTap({ currentTarget: { dataset: { key: 'market' } } });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/pages/market/index');
});

test('the central action opens the sheet without switching page', () => {
  let switches = 0;
  const { instance } = loadComponent('custom-tab-bar/index.js', {
    wx: { switchTab: () => switches++ },
  });
  instance.openPublish();
  instance.openPublish();
  assert.equal(instance.data.publishVisible, true);
  assert.equal(switches, 0);
  instance.closePublish();
  assert.equal(instance.data.publishVisible, false);
  assert.equal(instance.data.activeKey, 'forum');
});

test('failed navigation keeps the current selected tab', () => {
  const messages = [];
  const { instance } = loadComponent('custom-tab-bar/index.js', {
    wx: {
      switchTab: (options) => options.fail({ errMsg: 'failed' }),
      showToast: (options) => messages.push(options.title),
    },
  });
  instance.onTabTap({ currentTarget: { dataset: { key: 'profile' } } });
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

for (const [type, feedback] of [['post', '已选择发布帖子'], ['product', '已选择发布闲置']]) {
  test('selecting ' + type + ' closes the sheet and acknowledges the correct choice', () => {
    const messages = [];
    const { instance, events } = loadComponent('custom-tab-bar/index.js', {
      wx: { showToast: (options) => messages.push(options.title) },
    });
    instance.openPublish();
    instance.onPublishSelect({ detail: { type } });
    assert.equal(instance.data.publishVisible, false);
    assert.equal(instance.data.activeKey, 'forum');
    assert.equal(events[0].name, 'publishselect');
    assert.equal(events[0].detail.type, type);
    assert.equal(messages[0], feedback);
  });
}

test('an invalid publish selection leaves the panel open', () => {
  const { instance, events } = loadComponent('custom-tab-bar/index.js');
  instance.openPublish();
  instance.onPublishSelect({ detail: { type: 'payment' } });
  assert.equal(instance.data.publishVisible, true);
  assert.equal(events.length, 0);
});

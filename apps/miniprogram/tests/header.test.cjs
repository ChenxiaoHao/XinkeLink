const test = require('node:test');
const assert = require('node:assert/strict');
const { loadComponent } = require('./native-harness.cjs');

test('the custom header reserves the live WeChat capsule area', () => {
  const { definition, instance } = loadComponent('components/page-header/index.js', {
    wx: {
      getWindowInfo: () => ({ statusBarHeight: 20, windowWidth: 375 }),
      getMenuButtonBoundingClientRect: () => ({ top: 26, height: 32, left: 270 }),
    },
  });
  definition.lifetimes.attached.call(instance);
  assert.equal(instance.data.statusHeight, 20);
  assert.equal(instance.data.navigationHeight, 44);
  assert.equal(instance.data.rightSpace, 117);
});

test('an unavailable capsule uses a usable fallback header height', () => {
  const { definition, instance } = loadComponent('components/page-header/index.js', {
    wx: {
      getWindowInfo: () => ({ statusBarHeight: 24, windowWidth: 375 }),
      getMenuButtonBoundingClientRect: () => ({ top: 0, height: 0, left: 0 }),
    },
  });
  definition.lifetimes.attached.call(instance);
  assert.equal(instance.data.navigationHeight, 44);
  assert.equal(instance.data.rightSpace, 104);
});

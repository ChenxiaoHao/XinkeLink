const test = require('node:test');
const assert = require('node:assert/strict');
const { loadComponent } = require('./native-harness.cjs');

function tabBar() {
  const ticks = [];
  const loaded = loadComponent('custom-tab-bar/index.js', {
    wx: { nextTick: callback => ticks.push(callback) },
  });
  return { ...loaded, flush() { ticks.splice(0).forEach(callback => callback()); }, ticks };
}

test('returning to a cached tab replays selection feedback after rendering', () => {
  const { instance, definition, ticks, flush } = tabBar();
  definition.pageLifetimes.show.call(instance);
  assert.equal(instance.data.selectionMotion, false);
  assert.equal(ticks.length, 1);
  flush();
  assert.equal(instance.data.selectionMotion, true);
  definition.pageLifetimes.hide.call(instance);
  assert.equal(instance.data.selectionMotion, false);
  definition.pageLifetimes.show.call(instance);
  flush();
  assert.equal(instance.data.selectionMotion, true);
  assert.equal(instance.data.activeKey, 'forum');
});

test('a hidden or detached tab cannot be animated by a stale render callback', () => {
  const { instance, definition, flush } = tabBar();
  definition.pageLifetimes.show.call(instance);
  definition.pageLifetimes.hide.call(instance);
  flush();
  assert.equal(instance.data.selectionMotion, false);
  definition.pageLifetimes.show.call(instance);
  definition.lifetimes.detached.call(instance);
  flush();
  assert.equal(instance.data.selectionMotion, false);
});

test('rapid repeated selection feedback keeps only the latest render callback', () => {
  const { instance, definition, ticks } = tabBar();
  definition.pageLifetimes.show.call(instance);
  definition.pageLifetimes.show.call(instance);
  ticks.shift()();
  assert.equal(instance.data.selectionMotion, false);
  ticks.shift()();
  assert.equal(instance.data.selectionMotion, true);
  assert.equal(instance.data.publishVisible, false);
});

test('the page selection update triggers feedback even when the tab is cached', () => {
  const { instance, definition, ticks, flush } = tabBar();
  instance.data.activeKey = 'search';
  definition.observers.activeKey.call(instance);
  assert.equal(ticks.length, 1);
  flush();
  assert.equal(instance.data.selectionMotion, true);
  assert.equal(instance.data.activeKey, 'search');
});

const test = require('node:test');
const assert = require('node:assert/strict');
const { loadComponent } = require('./native-harness.cjs');

test('dismissal sends a close event', () => {
  const { instance, events } = loadComponent('components/publish-sheet/index.js');
  instance.close();
  assert.equal(events[0].name, 'close');
});

for (const type of ['post', 'product']) {
  test('the ' + type + ' card emits its own publish type', () => {
    const { instance, events } = loadComponent('components/publish-sheet/index.js');
    instance.choose({ currentTarget: { dataset: { type } } });
    assert.equal(events[0].name, 'select');
    assert.equal(events[0].detail.type, type);
  });
}

test('an invalid publish type produces no event', () => {
  const { instance, events } = loadComponent('components/publish-sheet/index.js');
  instance.choose({ currentTarget: { dataset: { type: 'payment' } } });
  assert.equal(events.length, 0);
});


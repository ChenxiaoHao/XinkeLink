const test = require('node:test');
const assert = require('node:assert/strict');
const { loadComponent } = require('./native-harness.cjs');

test('card like and collection events identify only the public post', () => {
  const { instance, events } = loadComponent('components/forum-post-card/index.js');
  instance.data.post = { id: 'post-001' };
  instance.onLikeTap();
  instance.onCollectTap();
  assert.equal(events[0].name, 'like');
  assert.equal(events[0].detail.id, 'post-001');
  assert.equal(events[1].name, 'collect');
  assert.equal(events[1].detail.id, 'post-001');
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

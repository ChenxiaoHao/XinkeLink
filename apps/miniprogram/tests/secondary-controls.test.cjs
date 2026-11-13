const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');

test('post category picker preserves draft, rejects invalid choices and respects publishing lock', () => {
  const page = loadPage('pages/forum-publish/index.js', { wx: {} });
  page.setData({ form: { ...page.data.form, content: '保留正文', images: ['/tmp/photo.jpg'], anonymous: true } });
  page.onCategoryPickerChange({ detail: { value: '1' } });
  assert.equal(page.data.form.category, page.data.categories[1].key);
  assert.equal(page.data.form.content, '保留正文');
  assert.deepEqual(page.data.form.images, ['/tmp/photo.jpg']);
  const category = page.data.form.category;
  page.onCategoryPickerChange({ detail: { value: '99' } });
  assert.equal(page.data.form.category, category);
  page.setData({ publishing: true });
  page.onCategoryPickerChange({ detail: { value: '0' } });
  assert.equal(page.data.form.category, category);
});

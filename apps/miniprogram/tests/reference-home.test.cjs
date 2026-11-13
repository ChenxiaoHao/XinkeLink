const test = require('node:test');
const assert = require('node:assert/strict');
const { loadPage } = require('./page-harness.cjs');


test('reference author metadata never exposes the major of an anonymous post', () => {
  const { postView } = require('../.test-dist/backend/adapters.js');
  const dto = { id: 'one', content: '匿名动态', category: 'campus', images: [], createdAt: '2026-11-05T00:00:00Z', author: { id: 'private-author', nickname: '匿名同学', major: '真实专业', isAnonymous: true } };
  assert.equal(postView(dto).authorMajor, '');
  assert.equal(postView(dto).authorId, undefined);
  assert.equal(postView({ ...dto, author: { ...dto.author, isAnonymous: false } }).authorMajor, '真实专业');
});

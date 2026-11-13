const test = require('node:test'), assert = require('node:assert/strict')
const { noticeView } = require('../.test-dist/backend/adapters.js')
test('announcement and system notification with the same title have distinct origins', () => {
  const base = { id: '1', type: 'announcement', title: '社区消息', actor: { kind: 'platform', displayName: '信科 Link 平台通知' }, createdAt: new Date().toISOString() }
  assert.equal(noticeView({ ...base, eventKind: 'announcement' }).actorName, '信科 Link 公告')
  assert.equal(noticeView({ ...base, eventKind: 'system' }).actorName, '信科 Link 系统通知')
})

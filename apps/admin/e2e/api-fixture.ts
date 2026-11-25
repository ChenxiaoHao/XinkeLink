import { test as base, expect, type Page } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import ts from 'typescript'
import { computed, reactive, ref, shallowRef } from 'vue'
import { createPinia, defineStore, setActivePinia } from 'pinia'
import { maskAuditActor, sanitizeAuditText, sanitizeChangedFields, recordAdminAuditSafely, toAuditCsv } from '../src/utils/admin-audit'

type Row = Record<string, any>

// Explicit Playwright-only HTTP fixture. Production stores never import these modules.
// Reuse the preserved feature fixtures so the original UI regression scenarios remain useful.
function loadFixture(filename: string, exported: string, dependencies: Row) {
  let source = fs.readFileSync(path.resolve('tests/fixtures', filename), 'utf8')
  source = source.replace(/^import (\w+) from '([^']+\.(?:jpg|png))'\r?$/gm, (_line, name, asset) => `const ${name} = '/src/assets/moderation/${path.basename(asset)}'`)
  source = source.replace(/^import .*\r?\n/gm, '').replace(/\bexport\s+/g, '')
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  return new Function(...Object.keys(dependencies), `${code}\nreturn ${exported}`)(...Object.values(dependencies))
}

async function installApiFixture(page: Page) {
  setActivePinia(createPinia())
  const common = { computed, ref, shallowRef, defineStore, maskAuditActor, sanitizeAuditText, sanitizeChangedFields, recordAdminAuditSafely, useAdminAuthStore: () => reactive({ currentAccount: 'demo-admin' }) }
  const audit = loadFixture('admin-audit.ts', 'useAdminAuditStore', common)()
  const moderation = loadFixture('content-moderation.ts', 'useContentModerationStore', { ...common, useAdminAuditStore: () => audit })()
  const operations = loadFixture('admin-operations.ts', 'useAdminOperationsStore', { ...common, useAdminAuditStore: () => audit, useContentModerationStore: () => moderation })()
  const settings = loadFixture('admin-settings.ts', 'useAdminSettingsStore', { ...common, useAdminAuditStore: () => audit })()
  const versions = new Map<string, number>()
  let settingsVersion = 0
  const version = (id: string) => versions.get(id) ?? 0
  const bump = (id: string) => versions.set(id, version(id) + 1)
  const admin = { id: 'admin-fixture', loginName: 'demo-admin', displayName: '测试管理员', role: 'ADMIN' }
  let authenticated = false
  const image = (url: string) => ({ imageId: url, url, width: 200, height: 150, expiresAt: null })
  const person = (id: string, name: string, avatar = '') => ({ id, name, nickname: name, avatar: avatar ? image(avatar) : null, major: '', className: '', selfReported: true })
  const categories: Row = { 校园生活: 'campus', 失物招领: 'lost_found', 学习交流: 'study', 校园互助: 'help',  }
  const post = (row: Row) => ({ ...row, version: version(row.id), category: categories[row.category], author: person(row.authorId, row.authorName, row.authorAvatar), images: row.images.map(image), likeCount: row.likes, viewCount: null, visibleCommentCount: moderation.visibleCommentCount(row.id) })
  const comment = (row: Row) => ({ ...row, version: version(row.id), targetType: 'post', targetId: row.postId, linkedTarget: { type: 'post', id: row.postId, title: row.linkedTitle, availability: 'available' }, rootId: row.parentId, author: person(row.authorId, row.authorName, row.authorAvatar), likeCount: row.likes })
  const user = (row: Row) => ({ ...row, version: version(row.id), avatar: row.avatar ? image(row.avatar) : null, selfReported: true, manualRestrictions: row.restrictions, contentCounts: { posts: moderation.userActivity(row.id).posts.length }, reportCount: moderation.reportsForUser(row.id).filter((r: Row) => r.targetUserId === row.id).length })
  const report = (row: Row) => {
    const target = row.type === 'post' ? moderation.postById(row.targetId) : row.type === 'comment' ? moderation.commentById(row.targetId) : moderation.userById(row.targetUserId)
    return { ...row, version: version(row.id), reasonCode: row.reason, reporter: person(row.reporterId, row.reporterName), targetUser: { ...person(row.targetUserId, row.targetName), status: moderation.userById(row.targetUserId)?.status }, target: { type: row.type, id: row.targetId, title: row.targetTitle, availability: target?.status ?? 'unavailable' }, targetSnapshot: { title: row.targetTitle, content: target?.content ?? target?.description ?? '', images: (target?.images ?? []).map(image) }, evidence: row.evidence.map(image) }
  }
  const announcement = (row: Row) => ({ ...row, version: version(row.id), lastPublishedAt: row.publishedAt, firstPublishedAt: row.publishedAt, creator: admin, updater: admin })
  const notification = (row: Row) => ({ ...row, version: version(row.id), recipientCount: row.simulatedRecipientCount, deliveryMode: 'in_app', externalPush: false })
  const settingsDto = () => ({ version: settingsVersion, effectMode: 'configuration_only', platform: { name: '信科 Link', school: '河南信息科技学院', description: '校园学生社区' }, administrator: admin, system: { architecture: '模块化单体', externalPush: false, automaticReview: false }, community: { controlsAvailable: false, description: '遵循当前社区规则' }, contentReview: settings.settings.contentReview })
  const pageResult = (result: Row, adapter: (row: Row) => Row, stats?: Row) => ({ ...result, items: result.items.map(adapter), ...(stats ? { stats } : {}) })

  await page.route('**/api/v1/admin/**', async route => {
    const request = route.request(), url = new URL(request.url()), parts = decodeURIComponent(url.pathname.replace('/api/v1/admin/', '')).split('/'), [module, id, action] = parts
    const body: Row = request.postDataJSON() ?? {}, method = request.method(), q = Object.fromEntries(url.searchParams), current = Number(q.page ?? 1), size = Number(q.pageSize ?? 10)
    const send = (data: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 'OK', message: '测试响应', data, requestId: 'controlled-api-fixture', timestamp: new Date().toISOString() }) })
    const reject = (status: number, code: string, message = code) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code, message, data: null, requestId: 'controlled-api-fixture' }) })
    if (module === 'auth') { if (id === 'logout') { authenticated = false; return send({ revoked: true }) } authenticated = true; return send({ admin, accessToken: 'fixture-access', csrfToken: 'fixture-csrf', expiresIn: 900 }) }
    if (!authenticated) return reject(401, 'UNAUTHENTICATED')
    if (module === 'me') return send(admin)
    if (module === 'dashboard') return send({ asOf: new Date().toISOString(), range: q.range, rangeStart: new Date().toISOString(), rangeEnd: new Date().toISOString(), metrics: { totalUsers: 3842, todayPosts: 146, pendingReports: 7 }, trends: [{ date: '2026-10-24', postCount: 2, dau: null }, { date: '2026-10-25', postCount: 5, dau: null }], todos: [], unavailableMetrics: [], recentActivity: ['post', 'comment', 'report', 'announcement'].map((kind, index) => ({ id: String(index), kind, summary: '测试业务动态', createdAt: new Date().toISOString(), target: { kind, id: String(index) } })) })
    try {
      const expected = body.expectedVersion ?? (request.headers()['if-match'] ? Number(request.headers()['if-match']!.replaceAll('"', '')) : undefined)
      if (module !== 'settings' && id && !['status', 'delete', 'audience-preview', 'export'].includes(id) && method !== 'GET' && expected !== version(id)) return reject(409, 'VERSION_CONFLICT')
      for (const item of body.items ?? []) if (item.expectedVersion !== version(item.id)) return reject(409, 'VERSION_CONFLICT')
      if (module === 'posts') {
        if (id === 'status') { moderation.setPostStatus(body.items.map((x: Row) => x.id), body.targetStatus); body.items.forEach((x: Row) => bump(x.id)); return send({ changedCount: body.items.length, items: body.items.map((x: Row) => ({ id: x.id, newVersion: version(x.id) })) }) }
        if (method === 'DELETE') { moderation.deletePosts([id]); return send({ deleted: true }) }
        if (action === 'comments') return send(pageResult({ items: moderation.commentsForPost(id).filter((x: Row) => !x.parentId), total: moderation.commentsForPost(id).filter((x: Row) => !x.parentId).length, page: 1, pageSize: 50 }, (x: Row) => ({ ...comment(x), replies: moderation.repliesFor(x.id).map(comment) })))
        if (id) return send({ post: post(moderation.postById(id)), author: {}, commentSummary: {} })
        return send(pageResult(moderation.queryPosts({ ...q, category: Object.keys(categories).find(k => categories[k] === q.category), period: 'all' }, current, size), post, moderation.postStats))
      }
      if (module === 'comments') {
        if (id === 'status' || id === 'delete') { const ids = body.items.map((x: Row) => x.id); id === 'delete' ? moderation.deleteComments(ids) : moderation.setCommentStatus(ids, body.targetStatus); ids.forEach(bump); return send({ changedCount: ids.length, items: ids.map((id: string) => ({ id, newVersion: version(id) })) }) }
        if (id) { const selected = moderation.commentById(id), root = selected.parentId ? moderation.commentById(selected.parentId) : selected; return send({ root: comment(root), selected: comment(selected), replies: { items: moderation.repliesFor(root.id).map(comment), hasMore: false, nextCursor: null }, target: {}, visibility: {} }) }
        return send(pageResult(moderation.queryComments(q, current, size), comment, { ...moderation.commentStats, interactions: 0, today: 2 }))
      }
      if (module === 'reports') {
        if (action === 'resolution') { if (!moderation.processReport(id, body.action, body.reason)) return reject(409, 'ALREADY_PROCESSED'); bump(id); return send({ report: report(moderation.reportById(id)), effects: { changedCount: 1 } }) }
        if (id) return send(report(moderation.reportById(id)))
        return send(pageResult(moderation.queryReports(q, current, size), report, moderation.reportStats))
      }
      if (module === 'users') {
        if (method === 'POST') { const ok = action === 'restrictions' ? moderation.setUserRestriction(id, body.channel, body.enabled, body.reason) : action === 'ban' ? moderation.banUser(id, body.mode === 'permanent' ? 'permanent' : body.durationDays, body.reason) : moderation.liftUserRestrictions(id, body.reason); if (!ok) return reject(409, 'STATE_CONFLICT'); bump(id); return send(user(moderation.userById(id))) }
        if (action === 'content') { const key = q.type === 'post' ? 'posts' : 'comments'; const items = moderation.userActivity(id)[key]; return send(pageResult({ items, total: items.length, page: current, pageSize: size }, q.type === 'post' ? post : comment)) }
        if (action === 'reports') { const items = moderation.reportsForUser(id); return send(pageResult({ items, total: items.length, page: current, pageSize: size }, report)) }
        if (action === 'moderation-history') { const items = moderation.userById(id).history; return send({ items, total: items.length, page: current, pageSize: size }) }
        if (id) return send(user(moderation.userById(id)))
        return send(pageResult(moderation.queryUsers(q, current, size), user, moderation.userStats))
      }
      if (module === 'announcements') {
        if (method === 'DELETE') { operations.deleteAnnouncement(id); return send({ deleted: true }) }
        if (method !== 'GET') { const item = action ? (action === 'publish' ? operations.publishAnnouncement(id) : operations.withdrawAnnouncement(id)) && operations.announcementById(id) : operations.saveAnnouncementDraft(body, id); if (!item) return reject(409, 'STATE_CONFLICT'); bump(item.id); return send(announcement(item)) }
        if (id) return send(announcement(operations.announcementById(id)))
        return send(pageResult(operations.queryAnnouncements(q, current, size), announcement, operations.announcementStats))
      }
      if (module === 'notifications') {
        if (id === 'audience-preview') return send({ recipientCount: operations.resolveNotificationAudience(body.audience).length, audienceFingerprint: 'a'.repeat(64), computedAt: new Date().toISOString() })
        if (method !== 'GET') { const item = action === 'send' ? operations.sendNotification(id) && operations.notificationById(id) : operations.saveNotificationDraft(body, id); if (!item) return reject(409, 'STATE_CONFLICT'); bump(item.id); return send(notification(item)) }
        if (id) return send(notification(operations.notificationById(id)))
        return send(pageResult(operations.queryNotifications(q, current, size), notification, { ...operations.notificationStats, inAppRecipientCount: 0 }))
      }
      if (module === 'settings') {
        if (method !== 'GET') { const result = action === 'reset' ? settings.resetToDefaults() : settings.saveSettings({ ...settings.settings, contentReview: body }); if (result.ok === false) return reject(400, 'VALIDATION_FAILED', '请检查标出的配置项'); if (result.changedFields.length) settingsVersion++; return send({ ...settingsDto(), changedFields: result.changedFields }) }
        return send(settingsDto())
      }
      if (module === 'audit-events') {
        if (id === 'export') return route.fulfill({ contentType: 'text/csv;charset=utf-8', body: toAuditCsv(audit.query({ ...q, actor: q.actorKeyword ?? '', keyword: q.keyword ?? '', module: q.module ?? 'all', action: q.action ?? '', outcome: q.outcome ?? 'all' }, 1, 100).items) })
        if (id) return send(audit.events.find((x: Row) => x.id === id))
        return send({ ...audit.query({ ...q, actor: q.actorKeyword ?? '', keyword: q.keyword ?? '', module: q.module ?? 'all', action: q.action ?? '', outcome: q.outcome ?? 'all' }, current, size), stats: { ...audit.stats, today: audit.events.length } })
      }
      return reject(404, 'NOT_FOUND')
    } catch (error) { return reject(500, 'FIXTURE_ERROR', String(error)) }
  })
}

export const test = base.extend({ page: async ({ page }, use) => { await installApiFixture(page); await use(page) } })
export { expect }

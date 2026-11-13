import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAdminOperationsStore } from '../src/stores/admin-operations'
import { useAdminAuditStore } from '../src/stores/admin-audit'
import { useAdminSettingsStore } from '../src/stores/admin-settings'

const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 })
beforeEach(() => setActivePinia(createPinia()))
afterEach(() => vi.unstubAllGlobals())

it('sends an unchanged server notification draft without a redundant edit command', async () => {
  const methods: string[] = []
  const draft = { id: '71', title: '通知', body: '草稿正文', audience: 'active', status: 'draft', version: 4 }
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    methods.push(init?.method || 'GET')
    if (init?.method === 'PATCH') return new Response(JSON.stringify({ code: 'STATE_UNCHANGED', message: '状态未改变' }), { status: 409 })
    if (url.endsWith('/audience-preview')) return ok({ recipientCount: 2, audienceFingerprint: 'b'.repeat(64), computedAt: '2026-10-26T00:00:00Z' })
    return ok(url.endsWith('/send') ? { ...draft, status: 'sent', version: 5 } : draft)
  })
  const store = useAdminOperationsStore()
  await store.loadNotificationDetail('71')
  await store.previewAudience('active')
  expect((await store.saveNotificationDraft({ title: '通知', body: '草稿正文', audience: 'active' }, '71')).version).toBe(4)
  expect(await store.sendNotification('71')).toBe(true)
  expect(methods).not.toContain('PATCH')
  expect(store.notificationById('71')?.status).toBe('sent')
})

it('publishes an unchanged server announcement draft without a redundant edit command', async () => {
  const methods: string[] = []
  const draft = { id: '72', title: '公告', body: '公告正文', category: 'community', scope: 'forum', status: 'draft', version: 3 }
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    methods.push(init?.method || 'GET')
    if (init?.method === 'PATCH') return new Response(JSON.stringify({ code: 'STATE_UNCHANGED', message: '状态未改变' }), { status: 409 })
    return ok(url.endsWith('/publish') ? { ...draft, status: 'published', version: 4 } : draft)
  })
  const store = useAdminOperationsStore()
  await store.loadAnnouncementDetail('72')
  await store.saveAnnouncementDraft({ title: '公告', body: '公告正文', category: 'community', scope: 'forum' }, '72')
  expect(await store.publishAnnouncement('72')).toBe(true)
  expect(methods).not.toContain('PATCH')
  expect(store.announcementById('72')?.status).toBe('published')
})

it('creates and publishes announcement drafts using server IDs and versions', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = []
  const saved = { id: '32', title: '校园提示', body: '请核对', category: 'community', scope: 'all', status: 'draft', version: 3, createdAt: '2026-10-25T00:00:00Z', updatedAt: '2026-10-25T00:00:00Z' }
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => { requests.push({ url, init }); return ok(url.includes('/publish') ? { ...saved, status: 'published', version: 4 } : saved) })
  const store = useAdminOperationsStore()
  const item = await store.saveAnnouncementDraft({ title: '校园提示', body: '请核对', category: 'community', scope: 'all' })
  expect(item).toMatchObject({ id: '32', version: 3 })
  expect(await store.publishAnnouncement('32')).toBe(true)
  expect(JSON.parse(String(requests[1]?.init?.body))).toEqual({ expectedVersion: 3, confirmed: true })
  expect(JSON.parse(String(requests[0]?.init?.body)).clientRequestId).toMatch(/^[!-~]{1,64}$/)
})

it('uses the server audience fingerprint and records actual in-app recipients', async () => {
  const sentBodies: unknown[] = []
  const task = { id: '12', title: '通知', body: '真实站内通知', audience: 'all', status: 'draft', version: 2, recipientCount: null, deliveryMode: 'in_app', externalPush: false }
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    if (url.endsWith('/audience-preview')) return ok({ recipientCount: 7, audienceFingerprint: 'a'.repeat(64), computedAt: '2026-10-25T00:00:00Z' })
    if (url.endsWith('/send')) { sentBodies.push(JSON.parse(String(init?.body))); return ok({ ...task, status: 'sent', recipientCount: 7, version: 3 }) }
    return ok(task)
  })
  const store = useAdminOperationsStore()
  await store.previewAudience('all')
  await store.saveNotificationDraft({ title: '通知', body: '真实站内通知', audience: 'all' })
  expect(await store.sendNotification('12')).toBe(true)
  expect(sentBodies).toEqual([{ expectedVersion: 2, audienceFingerprint: 'a'.repeat(64), confirmed: true }])
  expect(store.notificationById('12')).toMatchObject({ recipientCount: 7, deliveryMode: 'in_app', externalPush: false })
})

it('loads audit events from the server with inclusive Shanghai start and exclusive next-day end', async () => {
  const urls: string[] = []
  vi.stubGlobal('fetch', async (url: string) => { urls.push(url); return ok({ items: [{ id: '21', actor: { id: '7', displayName: '管理员甲' }, module: 'posts', action: 'hide', outcome: 'success', targetType: 'post', targetRef: 'post:operation-1', changedFields: [], createdAt: '2026-10-25T00:00:00Z' }], total: 5, page: 1, pageSize: 10, stats: { total: 20, success: 17, businessRejected: 2, executionErrors: 1, today: 3 } }) })
  const store = useAdminAuditStore()
  await store.load({ keyword: '', actor: '主管', module: 'all', action: '', outcome: 'all', from: '2026-10-25', to: '2026-10-25' }, 1, 10)
  expect(new URL(urls[0]!, 'https://localhost').searchParams.get('from')).toBe('2026-10-24T16:00:00.000Z')
  expect(new URL(urls[0]!, 'https://localhost').searchParams.get('to')).toBe('2026-10-25T16:00:00.000Z')
  expect(new URL(urls[0]!, 'https://localhost').searchParams.get('actorKeyword')).toBe('主管')
  expect(store.stats.total).toBe(20)
  expect(store.events[0]?.actor).toBe('管理员甲')
})

it('saves only editable content-review fields with the server settings version', async () => {
  const requests: Array<RequestInit | undefined> = []
  const settings = { platform: { name: '信科 Link', school: '河南信息科技学院', description: '学生社区' }, administrator: { id: '7', loginName: 'server-admin', displayName: '管理员甲', role: 'ADMIN' }, system: { architecture: '模块化单体', externalPush: false, automaticReview: false }, community: { controlsAvailable: false, description: '仅保留当前规则' }, contentReview: { sensitiveWordIntercept: true, nightQueueMode: true, imageSensitivity: 'standard', sensitiveWords: [] }, effectMode: 'configuration_only', version: 8 }
  vi.stubGlobal('fetch', async (_url: string, init?: RequestInit) => { requests.push(init); return ok(settings) })
  const store = useAdminSettingsStore()
  await store.loadSettings()
  expect(store.settings.platform.brandName).toBe('信科 Link')
  expect(store.settings.administrator.account).toBe('server-admin')
  await store.saveSettings({ ...store.settings, contentReview: { ...store.settings.contentReview, nightQueueMode: false } })
  expect(JSON.parse(String(requests[1]?.body))).toEqual({ sensitiveWordIntercept: true, nightQueueMode: false, imageSensitivity: 'standard', sensitiveWords: [], expectedVersion: 8 })
})

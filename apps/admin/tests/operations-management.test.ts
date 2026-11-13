import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAdminOperationsStore } from './fixtures/admin-operations'
import { useContentModerationStore } from './fixtures/content-moderation'
import { useAdminAuditStore } from './fixtures/admin-audit'
import { toAuditCsv } from '../src/utils/admin-audit'
import { vi } from 'vitest'

describe('announcement and notification operations', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('creates, edits, publishes, withdraws, republishes, and deletes announcements with derived counts', () => {
    const store = useAdminOperationsStore()
    const initialTotal = store.announcementStats.total
    const draft = store.saveAnnouncementDraft({
      title: '图书馆自习交流提示', body: '请在校内公共区域友好交流。', category: 'community', scope: 'forum',
    })!

    expect(store.announcementStats.total).toBe(initialTotal + 1)
    expect(store.announcementById(draft.id)?.status).toBe('draft')
    expect(store.saveAnnouncementDraft({ ...draft, title: '期末复习交流提示' }, draft.id)?.title).toBe('期末复习交流提示')
    expect(store.publishAnnouncement(draft.id)).toBe(true)
    expect(store.announcementById(draft.id)?.status).toBe('published')
    expect(store.withdrawAnnouncement(draft.id)).toBe(true)
    expect(store.announcementById(draft.id)?.status).toBe('withdrawn')
    expect(store.publishAnnouncement(draft.id)).toBe(true)
    expect(store.announcementById(draft.id)?.status).toBe('published')
    expect(store.deleteAnnouncement(draft.id)).toBe(true)
    expect(store.announcementById(draft.id)).toBeUndefined()
  })

  it('filters and paginates announcements by keyword, category, and status', () => {
    const store = useAdminOperationsStore()
    const page = store.queryAnnouncements({ keyword: '期末', category: 'community', status: 'published' }, 1, 5)
    expect(page.items.length).toBeGreaterThan(0)
    expect(page.items.every(item => item.title.includes('期末') && item.category === 'community' && item.status === 'published')).toBe(true)
  })

  it('resolves each notification audience from the shared mock users', () => {
    const operations = useAdminOperationsStore()
    const moderation = useContentModerationStore()
    expect(operations.resolveNotificationAudience('all').map(user => user.id).sort()).toEqual(moderation.users.map(user => user.id).sort())
    expect(operations.resolveNotificationAudience('active').every(user => user.status === 'active')).toBe(true)
    expect(operations.resolveNotificationAudience('recent').every(user => Date.now() - Date.parse(user.lastActiveAt) <= 30 * 24 * 60 * 60 * 1000)).toBe(true)
  })

  it('saves drafts and records a simulated send once, then blocks edits and repeat sends', () => {
    const store = useAdminOperationsStore()
    const notification = store.saveNotificationDraft({
      title: '社区互动提醒', body: '请友好参与校园交流。', audience: 'active',
    })!
    const recipients = store.resolveNotificationAudience('active')
    expect(notification.status).toBe('draft')
    expect(store.notificationStats.draft).toBeGreaterThan(0)
    expect(store.sendNotification(notification.id)).toBe(true)
    const sent = store.notificationById(notification.id)!
    expect(sent.status).toBe('sent')
    expect(sent.simulatedRecipientIds).toEqual(recipients.map(user => user.id))
    expect(sent.simulatedRecipientCount).toBe(recipients.length)
    expect(store.sendNotification(notification.id)).toBe(false)
    expect(store.saveNotificationDraft({ ...notification, title: '不能覆盖已发送通知' }, notification.id)).toBeNull()
  })

  it('filters notifications and refuses to simulate a send when the audience has no users', () => {
    const store = useAdminOperationsStore()
    const moderation = useContentModerationStore()
    const draft = store.saveNotificationDraft({ title: '仅示例', body: '测试内容', audience: 'active' })!
    const matching = store.queryNotifications({ keyword: '仅示例', status: 'draft' }, 1, 10)
    expect(matching.items.map(item => item.id)).toContain(draft.id)
    expect(store.sendNotification(draft.id)).toBe(true)

    const emptyAudience = store.saveNotificationDraft({ title: '无接收用户', body: '测试内容', audience: 'active' })!
    moderation.users.forEach(user => { user.status = 'permanently_banned' })
    expect(store.resolveNotificationAudience('active')).toHaveLength(0)
    expect(store.sendNotification(emptyAudience.id)).toBe(false)
  })

  it('records one event for each announcement and notification mutation without content or recipient IDs', () => {
    const operations = useAdminOperationsStore()
    const moderation = useContentModerationStore()
    const audit = useAdminAuditStore()
    const draft = operations.saveAnnouncementDraft({ title: '审计正文标题', body: '公告正文私密内容', category: 'community', scope: 'forum' })!
    expect(operations.updateAnnouncement(draft.id, { title: '编辑后的标题', body: '编辑后正文', category: 'community', scope: 'forum' })).not.toBeNull()
    expect(operations.publishAnnouncement(draft.id)).toBe(true)
    expect(operations.withdrawAnnouncement(draft.id)).toBe(true)
    expect(operations.deleteAnnouncement(draft.id)).toBe(true)
    expect(operations.publishAnnouncement('missing-announcement')).toBe(false)

    const notification = operations.saveNotificationDraft({ title: '通知标题', body: '通知正文不进入审计', audience: 'active' })!
    const recipientIds = operations.resolveNotificationAudience('active').map(user => user.id)
    expect(operations.sendNotification(notification.id)).toBe(true)
    expect(operations.sendNotification(notification.id)).toBe(false)

    expect(audit.events).toHaveLength(9)
    expect(audit.events.filter(event => event.module === 'announcements')).toHaveLength(6)
    expect(audit.events.filter(event => event.module === 'notifications')).toHaveLength(3)
    expect(audit.events.find(event => event.action === 'send' && event.outcome === 'success')?.targetCount).toBe(recipientIds.length)
    const serialized = `${JSON.stringify(audit.events)}\n${toAuditCsv(audit.events)}`
    expect(serialized).not.toContain('公告正文私密内容')
    expect(serialized).not.toContain('通知正文不进入审计')
    for (const id of recipientIds) expect(serialized).not.toContain(id)
    expect(serialized).not.toContain(moderation.users[0].id)
  })

  it('keeps business results when the audit writer fails', () => {
    const operations = useAdminOperationsStore()
    const audit = useAdminAuditStore()
    vi.spyOn(audit, 'record').mockImplementation(() => { throw new Error('audit unavailable') })
    const draft = operations.saveAnnouncementDraft({ title: '本地草稿', body: '仅供演示', category: 'community', scope: 'forum' })

    expect(draft).not.toBeNull()
    expect(operations.announcementById(draft!.id)?.status).toBe('draft')
  })

  it('separates rejected operations from mutation exceptions', () => {
    const operations = useAdminOperationsStore()
    const audit = useAdminAuditStore()
    expect(operations.deleteAnnouncement('missing-announcement')).toBe(false)
    const announcement = operations.announcements[0]
    Object.freeze(announcement)
    expect(() => operations.withdrawAnnouncement(announcement.id)).toThrow()
    expect(audit.events.map(event => event.outcome)).toEqual(['execution_error', 'business_rejected'])
  })
})

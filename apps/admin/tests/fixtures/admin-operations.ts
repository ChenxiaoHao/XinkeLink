import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { useContentModerationStore, type AdminUser } from './content-moderation'
import { useAdminAuditStore, type AuditEventInput, type AuditOutcome } from './admin-audit'
import { recordAdminAuditSafely } from '../../src/utils/admin-audit'

export type AnnouncementCategory = 'community' | 'activity' | 'safety'
export type AnnouncementScope = 'all' | 'forum'
export type AnnouncementStatus = 'draft' | 'published' | 'withdrawn'
export type NotificationAudience = 'all' | 'active' | 'recent'
export type NotificationStatus = 'draft' | 'sent'

export interface AdminAnnouncement {
  id: string
  title: string
  body: string
  category: AnnouncementCategory
  scope: AnnouncementScope
  status: AnnouncementStatus
  createdAt: string
  updatedAt: string
  publishedAt: string | null
  withdrawnAt: string | null
}

export interface AdminNotification {
  id: string
  title: string
  body: string
  audience: NotificationAudience
  status: NotificationStatus
  createdAt: string
  updatedAt: string
  sentAt: string | null
  simulatedRecipientIds: string[]
  simulatedRecipientCount: number | null
}

export interface AnnouncementInput {
  title: string
  body: string
  category: AnnouncementCategory
  scope: AnnouncementScope
}

export interface NotificationInput {
  title: string
  body: string
  audience: NotificationAudience
}

export interface AnnouncementFilters {
  keyword?: string
  category?: AnnouncementCategory | 'all'
  status?: AnnouncementStatus | 'all'
}

export interface NotificationFilters {
  keyword?: string
  audience?: NotificationAudience | 'all'
  status?: NotificationStatus | 'all'
}

export interface OperationsPage<T> { items: T[]; total: number; page: number; pageSize: number }

const ago = (days: number, hour = 10) => {
  const value = new Date()
  value.setDate(value.getDate() - days)
  value.setHours(hour, 15, 0, 0)
  return value.toISOString()
}

const initialAnnouncements: AdminAnnouncement[] = [
  { id: 'A-2026102301', title: '期末周图书馆自习交流提示', body: '期末复习期间请共同维护安静、整洁的学习环境。校园信息请以相关服务点现场说明为准。', category: 'community', scope: 'all', status: 'published', createdAt: ago(0, 9), updatedAt: ago(0, 9), publishedAt: ago(0, 9), withdrawnAt: null },
  { id: 'A-2026102103', title: '校园生活分享征集', body: '欢迎分享校园日常、学习经验和互助信息，共同维护友善交流氛围。', category: 'activity', scope: 'forum', status: 'published', createdAt: ago(2, 14), updatedAt: ago(2, 14), publishedAt: ago(2, 14), withdrawnAt: null },
  { id: 'A-2026102004', title: '失物招领信息发布建议', body: '发布失物招领时请描述物品特征与大致地点，避免公开敏感个人信息。', category: 'community', scope: 'forum', status: 'draft', createdAt: ago(3, 10), updatedAt: ago(3, 10), publishedAt: null, withdrawnAt: null },
  { id: 'A-2026101806', title: '社区互助内容说明', body: '交流内容应遵守平台社区规范，避免发布广告或不实信息。', category: 'community', scope: 'all', status: 'withdrawn', createdAt: ago(5, 12), updatedAt: ago(1, 12), publishedAt: ago(5, 12), withdrawnAt: ago(1, 12) },
  { id: 'A-2026101608', title: '校园互助信息发布指南', body: '发布求助信息时请写明需要协助的事项与方便沟通的时间。', category: 'community', scope: 'forum', status: 'published', createdAt: ago(7, 10), updatedAt: ago(7, 10), publishedAt: ago(7, 10), withdrawnAt: null },
  { id: 'A-2026101410', title: '社区内容友善交流提醒', body: '请尊重不同观点，保持理性、友善的交流方式。', category: 'safety', scope: 'all', status: 'draft', createdAt: ago(9, 13), updatedAt: ago(9, 13), publishedAt: null, withdrawnAt: null },
  { id: 'A-2026101311', title: '校园互助活动信息草稿', body: '欢迎分享互助信息，发布前请确认内容完整。', category: 'activity', scope: 'forum', status: 'draft', createdAt: ago(10, 15), updatedAt: ago(10, 15), publishedAt: null, withdrawnAt: null },
  { id: 'A-2026101212', title: '私信沟通与隐私提示', body: '请勿在公开内容中发布个人联系方式或敏感信息。', category: 'safety', scope: 'all', status: 'withdrawn', createdAt: ago(11, 9), updatedAt: ago(4, 9), publishedAt: ago(11, 9), withdrawnAt: ago(4, 9) },
]

const initialNotifications: AdminNotification[] = [
  { id: 'N-2026102301', title: '期末周社区交流提示', body: '期末周将至，欢迎同学们分享复习经验并保持友好交流。', audience: 'all', status: 'sent', createdAt: ago(2, 9), updatedAt: ago(2, 9), sentAt: ago(2, 9), simulatedRecipientIds: [], simulatedRecipientCount: null },
  { id: 'N-2026102103', title: '完善个人资料提示', body: '可以在个人主页补充专业与班级信息，资料由用户自行填写。', audience: 'active', status: 'draft', createdAt: ago(2, 10), updatedAt: ago(2, 10), sentAt: null, simulatedRecipientIds: [], simulatedRecipientCount: null },
  { id: 'N-2026101905', title: '社区内容维护说明', body: '请遵守社区规范，遇到不友善内容可使用举报入口。', audience: 'recent', status: 'draft', createdAt: ago(4, 11), updatedAt: ago(4, 11), sentAt: null, simulatedRecipientIds: [], simulatedRecipientCount: null },
  { id: 'N-2026101806', title: '校园生活内容分享提示', body: '欢迎发布校园日常，交流请保持友善和尊重。', audience: 'all', status: 'draft', createdAt: ago(5, 9), updatedAt: ago(5, 9), sentAt: null, simulatedRecipientIds: [], simulatedRecipientCount: null },
  { id: 'N-2026101707', title: '失物招领信息更新提示', body: '物品已找回后，可及时更新原有信息。', audience: 'active', status: 'draft', createdAt: ago(6, 10), updatedAt: ago(6, 10), sentAt: null, simulatedRecipientIds: [], simulatedRecipientCount: null },
  { id: 'N-2026101509', title: '社区私信沟通说明', body: '请通过平台私信交流校园生活，注意保护个人隐私。', audience: 'recent', status: 'draft', createdAt: ago(8, 12), updatedAt: ago(8, 12), sentAt: null, simulatedRecipientIds: [], simulatedRecipientCount: null },
  { id: 'N-2026101410', title: '校园社区规范温馨提示', body: '请遵守信科 Link 社区规范，共同维护友善交流氛围。', audience: 'active', status: 'draft', createdAt: ago(9, 15), updatedAt: ago(9, 15), sentAt: null, simulatedRecipientIds: [], simulatedRecipientCount: null },
  { id: 'N-2026101212', title: '公告阅读方式说明', body: '平台公告用于展示信科 Link 社区服务信息，不代表学校官方通知。', audience: 'all', status: 'draft', createdAt: ago(11, 17), updatedAt: ago(11, 17), sentAt: null, simulatedRecipientIds: [], simulatedRecipientCount: null },
]

function copyAnnouncement(item: AdminAnnouncement): AdminAnnouncement { return { ...item } }
function copyNotification(item: AdminNotification): AdminNotification { return { ...item, simulatedRecipientIds: [...item.simulatedRecipientIds] } }
function paginate<T>(items: T[], page: number, pageSize: number): OperationsPage<T> {
  const safePage = Math.max(1, page)
  const safeSize = Math.max(1, pageSize)
  const start = (safePage - 1) * safeSize
  return { items: items.slice(start, start + safeSize), total: items.length, page: safePage, pageSize: safeSize }
}

export const useAdminOperationsStore = defineStore('admin-operations', () => {
  const moderation = useContentModerationStore()
  const auditStore = useAdminAuditStore()
  const announcements = ref(initialAnnouncements.map(copyAnnouncement))
  const notifications = ref(initialNotifications.map(copyNotification))
  function appendAudit(event: AuditEventInput) {
    try { recordAdminAuditSafely(payload => auditStore.record(payload), event) } catch { /* Keep audit failures outside business operations. */ }
  }
  function auditedMutation<T>(
    createEvent: (result: T | undefined, outcome: AuditOutcome) => AuditEventInput,
    mutation: () => T,
    isSuccess: (result: T) => boolean,
  ): T {
    try {
      const result = mutation()
      try { appendAudit(createEvent(result, isSuccess(result) ? 'success' : 'business_rejected')) } catch { /* Event construction is best-effort. */ }
      return result
    } catch (error) {
      try { appendAudit(createEvent(undefined, 'execution_error')) } catch { /* Preserve the original mutation exception. */ }
      throw error
    }
  }
  const announcementStats = computed(() => ({
    total: announcements.value.length,
    published: announcements.value.filter(item => item.status === 'published').length,
    draft: announcements.value.filter(item => item.status === 'draft').length,
    withdrawn: announcements.value.filter(item => item.status === 'withdrawn').length,
  }))
  const notificationStats = computed(() => ({
    total: notifications.value.length,
    sent: notifications.value.filter(item => item.status === 'sent').length,
    draft: notifications.value.filter(item => item.status === 'draft').length,
    simulatedSends: notifications.value.filter(item => item.status === 'sent').length,
  }))

  function announcementById(id: string) { return announcements.value.find(item => item.id === id) }
  function notificationById(id: string) { return notifications.value.find(item => item.id === id) }

  function queryAnnouncements(filters: AnnouncementFilters, page: number, pageSize: number): OperationsPage<AdminAnnouncement> {
    const keyword = filters.keyword?.trim().toLocaleLowerCase() ?? ''
    const filtered = announcements.value.filter(item => {
      const matchesKeyword = !keyword || `${item.id} ${item.title} ${item.body}`.toLocaleLowerCase().includes(keyword)
      const matchesCategory = !filters.category || filters.category === 'all' || item.category === filters.category
      const matchesStatus = !filters.status || filters.status === 'all' || item.status === filters.status
      return matchesKeyword && matchesCategory && matchesStatus
    }).sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
    return paginate(filtered, page, pageSize)
  }

  function saveAnnouncementDraft(input: AnnouncementInput, id?: string): AdminAnnouncement | null {
    return auditedMutation((result, outcome) => ({ module: 'announcements', action: id ? 'update_draft' : 'save_draft', targetType: 'announcement', targetRef: id ?? result?.id, targetCount: result ? 1 : undefined, outcome, summary: outcome === 'success' ? '公告草稿已保存' : '公告草稿未保存' }), () => {
      if (!input.title.trim() || !input.body.trim()) return null
      const now = new Date().toISOString()
      if (id) {
        const existing = announcementById(id)
        if (!existing) return null
        Object.assign(existing, { ...input, title: input.title.trim(), body: input.body.trim(), status: 'draft' as const, updatedAt: now, publishedAt: null, withdrawnAt: null })
        return existing
      }
      const item: AdminAnnouncement = { id: `A-${Date.now()}`, ...input, title: input.title.trim(), body: input.body.trim(), status: 'draft', createdAt: now, updatedAt: now, publishedAt: null, withdrawnAt: null }
      announcements.value.unshift(item)
      return item
    }, Boolean)
  }

  function updateAnnouncement(id: string, input: AnnouncementInput): AdminAnnouncement | null {
    return auditedMutation((result, outcome) => ({ module: 'announcements', action: 'update', targetType: 'announcement', targetRef: id, targetCount: result ? 1 : undefined, outcome, summary: outcome === 'success' ? '公告内容已更新' : '公告更新未执行' }), () => {
      if (!input.title.trim() || !input.body.trim()) return null
      const existing = announcementById(id)
      if (!existing) return null
      Object.assign(existing, { ...input, title: input.title.trim(), body: input.body.trim(), updatedAt: new Date().toISOString() })
      return existing
    }, Boolean)
  }

  function publishAnnouncement(id: string): boolean {
    return auditedMutation((_result, outcome) => ({ module: 'announcements', action: 'publish', targetType: 'announcement', targetRef: id, targetCount: outcome === 'success' ? 1 : undefined, outcome, summary: outcome === 'success' ? '公告已发布' : '公告发布未执行' }), () => {
      const item = announcementById(id)
      if (!item || !item.title.trim() || !item.body.trim()) return false
      const now = new Date().toISOString()
      item.status = 'published'; item.publishedAt = now; item.withdrawnAt = null; item.updatedAt = now
      return true
    }, Boolean)
  }

  function withdrawAnnouncement(id: string): boolean {
    return auditedMutation((_result, outcome) => ({ module: 'announcements', action: 'withdraw', targetType: 'announcement', targetRef: id, targetCount: outcome === 'success' ? 1 : undefined, outcome, summary: outcome === 'success' ? '公告已撤回' : '公告撤回未执行' }), () => {
      const item = announcementById(id)
      if (!item || item.status !== 'published') return false
      const now = new Date().toISOString()
      item.status = 'withdrawn'; item.withdrawnAt = now; item.updatedAt = now
      return true
    }, Boolean)
  }

  function deleteAnnouncement(id: string): boolean {
    return auditedMutation((_result, outcome) => ({ module: 'announcements', action: 'delete', targetType: 'announcement', targetRef: id, targetCount: outcome === 'success' ? 1 : undefined, outcome, summary: outcome === 'success' ? '公告已删除' : '未找到可删除公告' }), () => {
      const index = announcements.value.findIndex(item => item.id === id)
      if (index < 0) return false
      announcements.value.splice(index, 1)
      return true
    }, Boolean)
  }

  function resolveNotificationAudience(audience: NotificationAudience): AdminUser[] {
    const users = moderation.users
    if (audience === 'all') return users
    if (audience === 'active') return users.filter(user => user.status === 'active')
    if (audience === 'recent') {
      const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000
      return users.filter(user => user.status === 'active' && Date.parse(user.lastActiveAt) >= cutoff)
    }
    return []
  }

  function queryNotifications(filters: NotificationFilters, page: number, pageSize: number): OperationsPage<AdminNotification> {
    const keyword = filters.keyword?.trim().toLocaleLowerCase() ?? ''
    const filtered = notifications.value.filter(item => {
      const matchesKeyword = !keyword || `${item.id} ${item.title} ${item.body}`.toLocaleLowerCase().includes(keyword)
      const matchesAudience = !filters.audience || filters.audience === 'all' || item.audience === filters.audience
      const matchesStatus = !filters.status || filters.status === 'all' || item.status === filters.status
      return matchesKeyword && matchesAudience && matchesStatus
    }).sort((left, right) => right.updatedAt.localeCompare(left.updatedAt))
    return paginate(filtered, page, pageSize)
  }

  function saveNotificationDraft(input: NotificationInput, id?: string): AdminNotification | null {
    return auditedMutation((result, outcome) => ({ module: 'notifications', action: id ? 'update_draft' : 'save_draft', targetType: 'notification', targetRef: id ?? result?.id, targetCount: result ? 1 : undefined, outcome, summary: outcome === 'success' ? '通知草稿已保存' : '通知草稿未保存' }), () => {
      if (!input.title.trim() || !input.body.trim()) return null
      const now = new Date().toISOString()
      if (id) {
        const existing = notificationById(id)
        if (!existing || existing.status === 'sent') return null
        Object.assign(existing, { ...input, title: input.title.trim(), body: input.body.trim(), updatedAt: now })
        return existing
      }
      const item: AdminNotification = { id: `N-${Date.now()}`, ...input, title: input.title.trim(), body: input.body.trim(), status: 'draft', createdAt: now, updatedAt: now, sentAt: null, simulatedRecipientIds: [], simulatedRecipientCount: null }
      notifications.value.unshift(item)
      return item
    }, Boolean)
  }

  function sendNotification(id: string): boolean {
    const knownItem = notificationById(id)
    const result = auditedMutation((operation, outcome) => ({
      module: 'notifications', action: 'send', targetType: 'notification', targetRef: id,
      targetCount: operation?.count ?? 0, outcome,
      summaryCode: knownItem ? `audience_${knownItem.audience}` : 'audience_unavailable',
      resultCode: outcome === 'success' ? 'simulated_send' : 'send_not_completed',
      summary: outcome === 'success' ? '模拟发送记录已生成' : '未生成模拟发送记录',
    }), () => {
      const item = notificationById(id)
      if (!item || item.status !== 'draft' || !item.title.trim() || !item.body.trim()) return { sent: false, count: 0 }
      const recipients = resolveNotificationAudience(item.audience)
      if (!recipients.length) return { sent: false, count: 0 }
      const now = new Date().toISOString()
      item.status = 'sent'; item.sentAt = now; item.updatedAt = now
      item.simulatedRecipientIds = recipients.map(user => user.id)
      item.simulatedRecipientCount = recipients.length
      return { sent: true, count: recipients.length }
    }, operation => operation.sent)
    return result.sent
  }

  return {
    announcements, notifications, announcementStats, notificationStats,
    announcementById, notificationById, queryAnnouncements, saveAnnouncementDraft, updateAnnouncement,
    publishAnnouncement, withdrawAnnouncement, deleteAnnouncement, resolveNotificationAudience,
    queryNotifications, saveNotificationDraft, sendNotification,
  }
})

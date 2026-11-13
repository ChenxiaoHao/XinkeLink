import { ref } from 'vue'
import { defineStore } from 'pinia'
import { adminHttp, ApiError, queryString } from '../services/admin-http'
export type AnnouncementCategory = 'community' | 'activity' | 'safety'
export type AnnouncementScope = 'all' | 'forum'
export type AnnouncementStatus = 'draft' | 'published' | 'withdrawn'
export type NotificationAudience = 'all' | 'active' | 'recent'
export type NotificationStatus = 'draft' | 'sent'

export interface AdminAnnouncement {
  version: number
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
  version: number
  id: string
  title: string
  body: string
  audience: NotificationAudience
  status: NotificationStatus
  createdAt: string
  updatedAt: string
  sentAt: string | null
  recipientCount: number | null
  deliveryMode: 'in_app'
  externalPush: false
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


const emptyPage = <T>(): OperationsPage<T> => ({ items: [], total: 0, page: 1, pageSize: 10 })
function upsert<T extends { id: string }>(items: T[], next: T) { return [...items.filter(item => item.id !== next.id), next] }
export const useAdminOperationsStore = defineStore('admin-operations', () => {
  const announcements = ref<AdminAnnouncement[]>([]), notifications = ref<AdminNotification[]>([])
  const announcementPage = ref(emptyPage<AdminAnnouncement>()), notificationPage = ref(emptyPage<AdminNotification>())
  const announcementStats = ref({ total: 0, published: 0, draft: 0, withdrawn: 0 })
  const notificationStats = ref({ total: 0, sent: 0, draft: 0, inAppRecipientCount: 0 })
  const audiencePreview = ref<{ audience: NotificationAudience; recipientCount: number; audienceFingerprint: string; computedAt: string } | null>(null)
  let announcementRequest = 0, notificationRequest = 0, previewRequest = 0
  let reloadAnnouncements = async () => {}, reloadNotifications = async () => {}
  const creationIds = new Map<string, string>()
  function clientId(kind: string, input: unknown) { const key = kind + JSON.stringify(input); if (!creationIds.has(key)) creationIds.set(key, crypto.randomUUID()); return { key, id: creationIds.get(key)! } }
  function adaptAnnouncement(dto: AdminAnnouncement & { lastPublishedAt?: string | null }) { return { ...dto, publishedAt: dto.lastPublishedAt ?? dto.publishedAt ?? null } }
  const announcementById = (id: string) => announcements.value.find(item => item.id === id)
  const notificationById = (id: string) => notifications.value.find(item => item.id === id)
  const queryAnnouncements = (_filters: AnnouncementFilters, _page: number, _pageSize: number) => announcementPage.value
  const queryNotifications = (_filters: NotificationFilters, _page: number, _pageSize: number) => notificationPage.value
  async function loadAnnouncements(filters: AnnouncementFilters, page: number, pageSize: number) {
    const request = ++announcementRequest
    reloadAnnouncements = () => loadAnnouncements({ ...filters }, page, pageSize)
    const dto = await adminHttp.request<OperationsPage<AdminAnnouncement> & { stats: typeof announcementStats.value }>('/announcements' + queryString({ ...filters, page, pageSize }))
    if (request !== announcementRequest) return
    const items = dto.items.map(adaptAnnouncement); announcementPage.value = { ...dto, items }; announcementStats.value = dto.stats
    items.forEach(item => { announcements.value = upsert(announcements.value, item) })
  }
  async function loadNotifications(filters: NotificationFilters, page: number, pageSize: number) {
    const request = ++notificationRequest
    reloadNotifications = () => loadNotifications({ ...filters }, page, pageSize)
    // The current UI's all option means no filter, while audience=all is an exact API audience.
    const dto = await adminHttp.request<OperationsPage<AdminNotification> & { stats: typeof notificationStats.value }>('/notifications' + queryString({ ...filters, page, pageSize }))
    if (request !== notificationRequest) return
    notificationPage.value = dto; notificationStats.value = dto.stats; dto.items.forEach(item => { notifications.value = upsert(notifications.value, item) })
  }
  async function loadAnnouncementDetail(id: string) { const dto = adaptAnnouncement(await adminHttp.request<AdminAnnouncement>(`/announcements/${encodeURIComponent(id)}`)); announcements.value = upsert(announcements.value, dto); return dto }
  async function loadNotificationDetail(id: string) { const dto = await adminHttp.request<AdminNotification>(`/notifications/${encodeURIComponent(id)}`); notifications.value = upsert(notifications.value, dto); return dto }
  async function saveAnnouncementDraft(input: AnnouncementInput, id?: string) {
    const current = id ? announcementById(id) : undefined
    // 未编辑的草稿直接进入发布步骤，避免无变化PATCH阻断后续命令。
    if (current?.status === 'draft' && current.title === input.title.trim() && current.body === input.body.trim() && current.category === input.category && current.scope === input.scope) return current
    const request = clientId('announcement', input)
    const dto = adaptAnnouncement(await adminHttp.request<AdminAnnouncement>('/announcements' + (id ? `/${encodeURIComponent(id)}` : ''), { method: id ? 'PATCH' : 'POST', body: JSON.stringify({ ...input, ...(id ? { expectedVersion: announcementById(id)?.version } : { clientRequestId: request.id }) }) }))
    creationIds.delete(request.key); announcements.value = upsert(announcements.value, dto); await reloadAnnouncements(); return dto
  }
  async function announcementCommand(id: string, action: string) {
    const dto = adaptAnnouncement(await adminHttp.request<AdminAnnouncement>(`/announcements/${encodeURIComponent(id)}/${action}`, { method: 'POST', body: JSON.stringify({ expectedVersion: announcementById(id)?.version, confirmed: true }) }))
    announcements.value = upsert(announcements.value, dto); await reloadAnnouncements(); return true
  }
  const publishAnnouncement = (id: string) => announcementCommand(id, 'publish')
  const withdrawAnnouncement = (id: string) => announcementCommand(id, 'withdraw')
  async function deleteAnnouncement(id: string) { await adminHttp.request(`/announcements/${encodeURIComponent(id)}`, { method: 'DELETE', headers: { 'If-Match': `"${announcementById(id)?.version}"` } }); announcements.value = announcements.value.filter(item => item.id !== id); await reloadAnnouncements(); return true }
  async function previewAudience(audience: NotificationAudience) {
    const request = ++previewRequest
    audiencePreview.value = null
    const dto = await adminHttp.request<{ recipientCount: number; audienceFingerprint: string; computedAt: string }>('/notifications/audience-preview', { method: 'POST', body: JSON.stringify({ audience }) })
    if (request === previewRequest) audiencePreview.value = { audience, ...dto }
    return dto
  }
  async function saveNotificationDraft(input: NotificationInput, id?: string) {
    const current = id ? notificationById(id) : undefined
    if (current?.status === 'draft' && current.title === input.title.trim() && current.body === input.body.trim() && current.audience === input.audience) return current
    const request = clientId('notification', input)
    const dto = await adminHttp.request<AdminNotification>('/notifications' + (id ? `/${encodeURIComponent(id)}` : ''), { method: id ? 'PATCH' : 'POST', body: JSON.stringify({ ...input, ...(id ? { expectedVersion: notificationById(id)?.version } : { clientRequestId: request.id }) }) })
    creationIds.delete(request.key); notifications.value = upsert(notifications.value, dto); await reloadNotifications(); return dto
  }
  async function sendNotification(id: string) {
    const task = notificationById(id), preview = audiencePreview.value
    if (!task || !preview || task.audience !== preview.audience) throw new ApiError(409, 'AUDIENCE_PREVIEW_REQUIRED', '请先核对当前接收范围')
    const dto = await adminHttp.request<AdminNotification>(`/notifications/${encodeURIComponent(id)}/send`, { method: 'POST', body: JSON.stringify({ expectedVersion: task.version, audienceFingerprint: preview.audienceFingerprint, confirmed: true }) })
    notifications.value = upsert(notifications.value, dto); audiencePreview.value = null; await reloadNotifications(); return true
  }
  return { announcements, notifications, announcementStats, notificationStats, audiencePreview, queryAnnouncements, queryNotifications, loadAnnouncements, loadNotifications, announcementById, notificationById, loadAnnouncementDetail, loadNotificationDetail, saveAnnouncementDraft, publishAnnouncement, withdrawAnnouncement, deleteAnnouncement, previewAudience, saveNotificationDraft, sendNotification }
})

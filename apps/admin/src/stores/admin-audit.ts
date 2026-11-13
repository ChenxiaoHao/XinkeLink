import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { Wire } from '../services/admin-adapters'
import { adminHttp, queryString, shanghaiBoundary } from '../services/admin-http'
export type AuditOutcome = 'success' | 'business_rejected' | 'execution_error'
export type AuditModule = 'posts' | 'comments' | 'reports' | 'users' | 'announcements' | 'notifications' | 'settings'

export interface AuditEventInput {
  module: AuditModule
  action: string
  targetType: string
  targetRef?: string
  targetCount?: number
  outcome: AuditOutcome
  summary?: string
  summaryCode?: string
  changedFields?: string[]
  resultCode?: string
}

export interface AuditEvent extends AuditEventInput {
  id: string
  createdAt: string
  actor: string
  targetRef?: string
  changedFields: string[]
}

export interface AuditFilters {
  keyword: string
  actor: string
  module: AuditModule | 'all'
  action: string
  outcome: AuditOutcome | 'all'
  from?: string
  to?: string
}

export interface AuditQueryResult {
  items: AuditEvent[]
  total: number
  page: number
  pageSize: number
}


export const useAdminAuditStore = defineStore('admin-audit', () => {
  const events = ref<AuditEvent[]>([])
  const stats = ref({ total: 0, success: 0, businessRejected: 0, executionErrors: 0, today: 0 })
  const result = ref<AuditQueryResult>({ items: [], total: 0, page: 1, pageSize: 10 })
  let sequence = 0
  const adapt = (dto: Wire): AuditEvent => ({ ...dto, actor: typeof dto.actor === 'string' ? dto.actor : dto.actor?.displayName ?? '', changedFields: dto.changedFields ?? [] }) as AuditEvent
  function parameters(filters: AuditFilters) { const { actor, from, to, ...rest } = filters; return { ...rest, actorKeyword: actor, from: shanghaiBoundary(from), to: shanghaiBoundary(to, true) } }
  async function load(filters: AuditFilters, page: number, pageSize: number) {
    const request = ++sequence
    const dto = await adminHttp.request<AuditQueryResult & { stats: typeof stats.value }>('/audit-events' + queryString({ ...parameters(filters), page, pageSize }))
    if (request !== sequence) return
    const items = dto.items.map(adapt); result.value = { ...dto, items }; events.value = items; stats.value = dto.stats
  }
  const query = (_filters: AuditFilters, _page: number, _pageSize: number) => result.value
  async function detail(id: string) { return adapt(await adminHttp.request<Wire>(`/audit-events/${encodeURIComponent(id)}`)) }
  const exportCsv = (filters: AuditFilters) => adminHttp.download('/audit-events/export' + queryString(parameters(filters)))
  return { events, stats, query, load, detail, exportCsv }
})

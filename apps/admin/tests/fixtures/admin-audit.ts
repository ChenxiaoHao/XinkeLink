import { computed, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { useAdminAuthStore } from './admin-auth'
import { maskAuditActor, sanitizeAuditText, sanitizeChangedFields } from '../../src/utils/admin-audit'

export type AuditOutcome = 'success' | 'business_rejected' | 'execution_error'
export type AuditModule = 'posts' | 'comments' | 'reports' | 'users' | 'announcements' | 'notifications' | 'settings' | 'lucky_bags' | 'ai_knowledge'

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

const modules = new Set<AuditModule>(['posts', 'comments', 'reports', 'users', 'announcements', 'notifications', 'settings'])
const outcomes = new Set<AuditOutcome>(['success', 'business_rejected', 'execution_error'])

function validInput(value: AuditEventInput): boolean {
  return Boolean(value && typeof value === 'object'
    && modules.has(value.module)
    && outcomes.has(value.outcome)
    && typeof value.action === 'string'
    && typeof value.targetType === 'string')
}

function dateBoundary(value: string | undefined, end: boolean): number | undefined {
  if (!value) return undefined
  const normalized = /^\d{4}-\d{2}-\d{2}$/.test(value)
    ? `${value}T${end ? '23:59:59.999' : '00:00:00.000'}`
    : value
  const timestamp = Date.parse(normalized)
  return Number.isFinite(timestamp) ? timestamp : undefined
}

function safeEvent(input: AuditEventInput, actor: string, id: string, createdAt: string): AuditEvent {
  const targetType = sanitizeAuditText(input.targetType, 40) || 'item'
  const targetRef = targetType === 'user'
    ? '用户（已脱敏）'
    : sanitizeAuditText(input.targetRef ?? '', 80) || undefined
  const targetCount = Number.isFinite(input.targetCount) && Number(input.targetCount) >= 0
    ? Math.floor(Number(input.targetCount))
    : undefined

  return Object.freeze({
    id,
    createdAt,
    actor: maskAuditActor(actor),
    module: input.module,
    action: sanitizeAuditText(input.action, 48) || 'operation',
    targetType,
    ...(targetRef ? { targetRef } : {}),
    ...(targetCount !== undefined ? { targetCount } : {}),
    outcome: input.outcome,
    ...(input.summaryCode ? { summaryCode: sanitizeAuditText(input.summaryCode, 48) } : {}),
    ...(input.summary ? { summary: sanitizeAuditText(input.summary, 180) } : {}),
    changedFields: Object.freeze(sanitizeChangedFields(input.changedFields ?? [])) as unknown as string[],
    ...(input.resultCode ? { resultCode: sanitizeAuditText(input.resultCode, 48) } : {}),
  }) as AuditEvent
}

export const useAdminAuditStore = defineStore('admin-audit', () => {
  const ledger = shallowRef<AuditEvent[]>([])
  let sequence = 0

  const events = computed<readonly AuditEvent[]>(() => Object.freeze([...ledger.value]))
  const stats = computed(() => ({
    total: ledger.value.length,
    success: ledger.value.filter(event => event.outcome === 'success').length,
    businessRejected: ledger.value.filter(event => event.outcome === 'business_rejected').length,
    executionErrors: ledger.value.filter(event => event.outcome === 'execution_error').length,
  }))

  function record(input: AuditEventInput): void {
    if (!validInput(input)) return
    try {
      const auth = useAdminAuthStore()
      const account = Reflect.get(auth, 'currentAccount')
      const actor = typeof account === 'string' && account.trim() ? account : '本地演示管理员'
      sequence += 1
      const event = safeEvent(input, actor, `audit-${Date.now()}-${sequence}`, new Date().toISOString())
      ledger.value = [event, ...ledger.value]
    } catch {
      // The ledger is a local demo aid; a logging failure must never affect its caller.
    }
  }

  function query(filters: AuditFilters, requestedPage: number, requestedPageSize: number): AuditQueryResult {
    const pageSize = Number.isFinite(requestedPageSize) && requestedPageSize > 0
      ? Math.min(100, Math.floor(requestedPageSize))
      : 10
    const normalizedFilters = filters ?? { keyword: '', actor: '', module: 'all', action: '', outcome: 'all' }
    const from = dateBoundary(normalizedFilters.from, false)
    const to = dateBoundary(normalizedFilters.to, true)
    const keyword = normalizedFilters.keyword.trim().toLocaleLowerCase()
    const actor = normalizedFilters.actor.trim().toLocaleLowerCase()
    const matching = ledger.value.filter((event) => {
      const timestamp = Date.parse(event.createdAt)
      if (normalizedFilters.module !== 'all' && event.module !== normalizedFilters.module) return false
      if (normalizedFilters.outcome !== 'all' && event.outcome !== normalizedFilters.outcome) return false
      if (normalizedFilters.action && event.action !== normalizedFilters.action) return false
      if (actor && !event.actor.toLocaleLowerCase().includes(actor)) return false
      if (from !== undefined && timestamp < from) return false
      if (to !== undefined && timestamp > to) return false
      if (keyword && !JSON.stringify(event).toLocaleLowerCase().includes(keyword)) return false
      return true
    })
    const maxPage = Math.max(1, Math.ceil(matching.length / pageSize))
    const page = Number.isFinite(requestedPage) && requestedPage > 0
      ? Math.min(Math.floor(requestedPage), maxPage)
      : 1
    const start = (page - 1) * pageSize
    return {
      items: matching.slice(start, start + pageSize).map(event => ({ ...event, changedFields: [...event.changedFields] })),
      total: matching.length,
      page,
      pageSize,
    }
  }

  return { events, stats, query, record }
})

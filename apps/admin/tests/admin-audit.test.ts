import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAdminAuditStore, type AuditEventInput, type AuditFilters } from './fixtures/admin-audit'
import { recordAdminAuditSafely, toAuditCsv, toSafeAuditRow } from '../src/utils/admin-audit'

function input(overrides: Partial<AuditEventInput> = {}): AuditEventInput {
  return {
    module: 'posts',
    action: 'hide',
    targetType: 'post',
    targetRef: 'P-2026-001',
    outcome: 'success',
    summary: '帖子状态已更新',
    changedFields: ['status'],
    ...overrides,
  }
}

describe('admin audit demo ledger', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('accepts only supported outcomes and projects the explicit safe field allowlist', () => {
    const store = useAdminAuditStore()
    store.record({ ...input({
      outcome: 'business_rejected',
      summary: '目标状态不允许重复处理',
    }),
      password: 'do-not-store',
      token: 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.signature',
      rawPayload: { body: 'private post content' },
    } as unknown as AuditEventInput)
    store.record(input({ outcome: 'execution_error', action: 'failed_update', summary: '本地操作执行异常' }))

    expect(store.events).toHaveLength(2)
    const rejected = store.events.find(event => event.outcome === 'business_rejected')!
    expect(rejected).toMatchObject({ outcome: 'business_rejected', module: 'posts' })
    expect(rejected).not.toHaveProperty('password')
    expect(rejected).not.toHaveProperty('token')
    expect(rejected).not.toHaveProperty('rawPayload')
    expect(JSON.stringify(store.events)).not.toContain('do-not-store')
    expect(JSON.stringify(store.events)).not.toContain('private post content')
  })

  it('redacts credential, JWT, phone, student-number, and user-reference patterns', () => {
    const store = useAdminAuditStore()
    store.record(input({
      targetType: 'user',
      targetRef: '202210123456',
      summary: '账号 13812345678，学号 202210123456，令牌 abc.def.ghi',
    }))
    const row = toSafeAuditRow(store.events[0])
    const serialized = JSON.stringify(row)

    expect(serialized).not.toContain('13812345678')
    expect(serialized).not.toContain('202210123456')
    expect(serialized).not.toContain('abc.def.ghi')
    expect(serialized).toContain('[已脱敏]')
  })

  it('uses a safe fallback actor before an authenticated administrator is available', () => {
    const store = useAdminAuditStore()
    store.record(input())
    expect(store.events[0].actor).toBe('本地演示管理员')
    expect(store.events[0].actor).not.toContain('@')
  })

  it('computes result counts and filters by actor, module, action, outcome, keyword, and dates', () => {
    const store = useAdminAuditStore()
    store.record(input({ action: 'hide', outcome: 'success', summary: '隐藏帖子' }))
    store.record(input({ module: 'comments', action: 'delete', targetType: 'comment', outcome: 'business_rejected', summary: '评论拒绝删除' }))
    store.record(input({ module: 'users', action: 'update', targetType: 'user', outcome: 'execution_error', summary: '更新失败' }))

    expect(store.stats).toEqual({ total: 3, success: 1, businessRejected: 1, executionErrors: 1 })
    const filters: AuditFilters = {
      keyword: '隐藏', actor: '', module: 'posts', action: 'hide', outcome: 'success',
      from: '2000-01-01', to: '2999-12-31',
    }
    const result = store.query(filters, 1, 10)
    expect(result.total).toBe(1)
    expect(result.items[0].summary).toBe('隐藏帖子')
  })

  it('returns safe pagination for invalid sizes and out-of-range pages', () => {
    const store = useAdminAuditStore()
    store.record(input({ targetRef: 'one' }))
    store.record(input({ targetRef: 'two' }))

    expect(store.query({ keyword: '', actor: '', module: 'all', action: '', outcome: 'all' }, 0, 0)).toMatchObject({ page: 1, pageSize: 10, total: 2 })
    expect(store.query({ keyword: '', actor: '', module: 'all', action: '', outcome: 'all' }, 99, 1)).toMatchObject({ page: 2, pageSize: 1, total: 2, items: [{ targetRef: 'one' }] })
    expect(store.query({ keyword: 'no-match', actor: '', module: 'all', action: '', outcome: 'all' }, 5, 10)).toMatchObject({ page: 1, total: 0, items: [] })
  })

  it('builds a BOM CSV with RFC escaping and formula-safe cells from the same projection', () => {
    const store = useAdminAuditStore()
    store.record(input({
      action: 'export, "audit"', targetRef: '=HYPERLINK("https://bad")',
      summary: 'first line\nsecond line',
    }))
    const csv = toAuditCsv(store.events)

    expect(csv.startsWith('\uFEFF')).toBe(true)
    expect(csv).toContain('"export, ""audit"""')
    expect(csv).toContain("'=HYPERLINK(")
    expect(csv).toContain('first line\nsecond line')
  })

  it('keeps audit writer failures inside the logging boundary', () => {
    const writer = vi.fn(() => { throw new Error('storage unavailable') })
    expect(() => recordAdminAuditSafely(writer, input())).not.toThrow()
    expect(writer).toHaveBeenCalledOnce()
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { adminHttp } from '../src/services/admin-http'
import { useAdminBetaStore } from '../src/stores/admin-beta'
import { adminBeta, type BetaManagement } from '../src/services/admin-beta'

const empty: BetaManagement = { phase: 'open', version: 0, total: 0, used: 0, remaining: 0, codes: [] }
const initialized: BetaManagement = {
  phase: 'beta', version: 1, total: 30, used: 2, remaining: 28,
  codes: Array.from({ length: 30 }, (_, i) => ({
    id: String(i + 1), code: `XK-TESTAA-${String(i + 1).padStart(6, '0')}`, status: i < 2 ? 'used' : 'unused',
    user: i < 2 ? { id: String(101 + i), nickname: ['验证甲', '验证乙'][i]! } : null,
    usedAt: i < 2 ? '2026-11-08T01:00:00Z' : null, createdAt: '2026-11-08T00:00:00Z',
  })),
}
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 })
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }

describe('beta management server state', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.restoreAllMocks(); vi.unstubAllGlobals(); adminHttp.clear() })

  it('loads the real batch and searches codes, account IDs and nicknames with status filters', async () => {
    vi.stubGlobal('fetch', async () => ok(initialized))
    const store = useAdminBetaStore()
    expect(store.snapshot).toBeNull()
    expect(store.phaseLabel).toBe('阶段未知')
    await store.load()
    expect(store.snapshot).toMatchObject({ total: 30, used: 2, remaining: 28 })
    expect(store.phaseLabel).toBe('内测中')
    expect(store.filterCodes('验证甲', 'used').map(row => row.id)).toEqual(['1'])
    expect(store.filterCodes('102', 'all').map(row => row.id)).toEqual(['2'])
    expect(store.filterCodes('testaa-000003', 'unused').map(row => row.id)).toEqual(['3'])
    expect(store.filterCodes('', 'unused')).toHaveLength(28)
  })

  it('initializes with empty JSON and changes phase using the displayed version', async () => {
    const requests: Array<{ url: string; method: string; body: unknown }> = []
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      requests.push({ url, method: init.method ?? 'GET', body: init.body ? JSON.parse(String(init.body)) : null })
      return ok(init.method === 'PATCH' ? { ...initialized, phase: 'open', version: 2 } : initialized)
    })
    const store = useAdminBetaStore()
    await store.initialize()
    await store.setPhase('open', 1)
    expect(requests).toEqual([
      { url: '/api/v1/admin/beta/initialize', method: 'POST', body: {} },
      { url: '/api/v1/admin/beta', method: 'PATCH', body: { phase: 'open', expectedVersion: 1 } },
    ])
    expect(store.phaseLabel).toBe('正式开放')
    expect(store.snapshot?.codes[0]?.user?.id).toBe('101')
  })

  it('keeps counts unknown on load failure and marks a previous snapshot stale on refresh failure', async () => {
    vi.stubGlobal('fetch', async () => { throw new Error('offline') })
    const store = useAdminBetaStore()
    await store.load()
    expect(store.snapshot).toBeNull()
    expect(store.error).toContain('网络连接失败')
    expect(store.phaseLabel).toBe('阶段获取失败')
    vi.stubGlobal('fetch', async () => ok(empty))
    await store.load()
    expect(store.snapshot?.total).toBe(0)
    vi.stubGlobal('fetch', async () => { throw new Error('offline') })
    await store.load()
    expect(store.phaseLabel).toBe('阶段获取失败')
    expect(store.error).not.toBe('')
  })

  it('rejects malformed totals or phases rather than displaying fabricated availability', async () => {
    for (const data of [{ ...initialized, phase: 'unknown' }, { ...initialized, remaining: 29 }, { ...initialized, codes: [] }]) {
      vi.stubGlobal('fetch', async () => ok(data))
      await expect(adminBeta.get()).rejects.toThrow('内测管理响应格式异常')
    }
  })

  it('coalesces duplicate reads and mutations and ignores a read arriving after initialization', async () => {
    const oldRead = deferred<Response>(), creation = deferred<Response>()
    const calls: string[] = []
    vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => { calls.push(init.method ?? 'GET'); return init.method === 'POST' ? creation.promise : oldRead.promise })
    const store = useAdminBetaStore()
    const read1 = store.load(), read2 = store.load()
    const create1 = store.initialize(), create2 = store.initialize()
    expect(calls).toEqual(['GET', 'POST'])
    creation.resolve(ok(initialized))
    await Promise.all([create1, create2])
    oldRead.resolve(ok(empty))
    await Promise.all([read1, read2])
    expect(store.snapshot?.total).toBe(30)
    expect(store.phaseLabel).toBe('内测中')
    expect(store.loading).toBe(false)
    expect(store.mutating).toBe(false)
  })

  it('reloads after a version conflict and does not overwrite the server phase with an optimistic value', async () => {
    let reads = 0
    vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
      if (init.method === 'PATCH') return new Response(JSON.stringify({ code: 'VERSION_CONFLICT', message: '阶段已被修改' }), { status: 409 })
      return ok(reads++ ? { ...initialized, phase: 'open', version: 2 } : initialized)
    })
    const store = useAdminBetaStore()
    await store.load()
    await expect(store.setPhase('open', 1)).rejects.toThrow('阶段已被修改')
    expect(store.snapshot).toMatchObject({ phase: 'open', version: 2, remaining: 28 })
    expect(store.error).toBe('')
  })

  it('discards sensitive state on sign-out and ignores a previous session response', async () => {
    const lateRead = deferred<Response>()
    vi.stubGlobal('fetch', async () => lateRead.promise)
    const store = useAdminBetaStore()
    const pending = store.load()
    store.reset()
    lateRead.resolve(ok(initialized))
    await pending
    expect(store.snapshot).toBeNull()
    expect(store.phaseLabel).toBe('阶段未知')
    expect(store.filterCodes('', 'all')).toEqual([])
  })

  it('allows a fresh reload after a mutation even while the superseded read is pending', async () => {
    const oldRead = deferred<Response>()
    let reads = 0
    vi.stubGlobal('fetch', async (_url: string, init: RequestInit) => {
      if (init.method === 'POST') return ok(initialized)
      return reads++ === 0 ? oldRead.promise : ok({ ...initialized, phase: 'open', version: 2 })
    })
    const store = useAdminBetaStore()
    const pending = store.load()
    await store.initialize()
    const reload = store.load()
    expect(reads).toBe(2)
    await reload
    oldRead.resolve(ok(empty))
    await pending
    expect(store.snapshot).toMatchObject({ phase: 'open', version: 2, total: 30 })
  })
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { adminHttp } from '../src/services/admin-http'
import { adminKnowledge } from '../src/services/admin-knowledge'
import { useKnowledgeStore } from '../src/stores/admin-knowledge'

const draft = { title: '学生管理规定', source: 'https://hbcet.hpu.edu.cn/info/1/2.htm', version: '2026版', publishedDate: '2026-09-01', scope: 'all', validity: '本学年有效', body: '第一条 学生应遵守学校管理规定。', reviewed: false }
const row = { ...draft, id: '15', status: 'draft', activeVersionId: '', filename: 'rules.txt', warnings: [] }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }))

describe('administrator knowledge maintenance', () => {
  beforeEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); adminHttp.clear(); setActivePinia(createPinia()) })
  it('uploads multipart extraction without a JSON header and never publishes the extraction', async () => {
    const requests: Array<{ url: string; init: RequestInit }> = []
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push({ url, init }); return ok({ body: draft.body, filename: 'rules.txt', warnings: [], originalBase64: 'dGV4dA==' }) })
    const extracted = await adminKnowledge.extract(new File(['text'], 'rules.txt'))
    expect(extracted.body).toBe(draft.body)
    expect(requests).toHaveLength(1)
    expect(requests[0]?.url).toBe('/api/v1/admin/ai/knowledge/extract')
    expect(new Headers(requests[0]?.init.headers).has('Content-Type')).toBe(false)
    expect((requests[0]?.init.body as FormData).get('file')).toBeInstanceOf(File)
  })
  it('blocks empty extraction and unsupported upload types instead of accepting an empty document', async () => {
    const fetch = vi.fn(async () => ok({ body: '  ', filename: 'scan.pdf', warnings: [], originalBase64: '' })); vi.stubGlobal('fetch', fetch)
    await expect(adminKnowledge.extract(new File(['image'], 'scan.pdf'))).rejects.toThrow('文字')
    await expect(adminKnowledge.extract(new File(['zip'], 'rules.zip'))).rejects.toThrow('PDF')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('allows five MiB and blocks a larger file before upload', async () => {
    const fetch = vi.fn(async () => ok({ body: draft.body, filename: 'rules.txt', warnings: [], originalBase64: '' })); vi.stubGlobal('fetch', fetch)
    await expect(adminKnowledge.extract(new File([new Uint8Array(5 * 1024 * 1024)], 'rules.txt'))).resolves.toMatchObject({ body: draft.body })
    await expect(adminKnowledge.extract(new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'rules.txt'))).rejects.toThrow('5 MiB')
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('distinguishes an unavailable index, a ready empty index, and an unknown state after a failed load', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(ok({ items: [], ready: false }))
      .mockResolvedValueOnce(ok({ items: [], ready: true }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 'UNAVAILABLE', message: '读取失败' }), { status: 503 }))
    vi.stubGlobal('fetch', fetch)
    const store = useKnowledgeStore()
    expect(store.ready).toBeNull()
    await store.load()
    expect(store.ready).toBe(false)
    await store.load()
    expect(store.ready).toBe(true)
    expect(store.items).toEqual([])
    await store.load()
    expect(store.ready).toBeNull()
    expect(store.error).toBe('读取失败')
  })
  it('accepts only explicit school HTTPS URLs and returns imported text for review', async () => {
    const requests: unknown[] = []
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { requests.push({ url, body: JSON.parse(String(init.body)) }); return ok({ body: draft.body, title: draft.title, source: draft.source, filename: 'article.html', warnings: ['请核对附件'], originalBase64: '' }) })
    for (const url of ['http://hbcet.hpu.edu.cn/a', 'https://hbcet.hpu.edu.cn.evil.test/a', 'https://127.0.0.1/a', 'https://user@hbcet.hpu.edu.cn/a']) await expect(adminKnowledge.importUrl(url)).rejects.toThrow('学校官网')
    expect((await adminKnowledge.importUrl(draft.source)).warnings).toEqual(['请核对附件'])
    expect(requests).toEqual([{ url: '/api/v1/admin/ai/knowledge/import-url', body: { url: draft.source } }])
  })
  it('saves reviewed content as draft and publishes only in a separate explicit action', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => { calls.push(`${init.method ?? 'GET'} ${url}`); return ok(init.method === 'POST' ? { ...row, reviewed: true } : { items: [{ ...row, reviewed: true }], ready: true }) })
    const store = useKnowledgeStore()
    await store.save({ ...draft, reviewed: true })
    expect(calls).toEqual(['POST /api/v1/admin/ai/knowledge', 'GET /api/v1/admin/ai/knowledge'])
    await store.publish({ ...row, reviewed: true } as any)
    expect(calls[2]).toBe('POST /api/v1/admin/ai/knowledge/15/publish')
  })
  it('rejects unreviewed publication and empty drafts before any network write', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    const store = useKnowledgeStore()
    await expect(store.publish(row as any)).rejects.toThrow('校对')
    await expect(store.save({ ...draft, body: ' ' })).rejects.toThrow('正文')
    expect(fetch).not.toHaveBeenCalled()
  })
  it('updates with PUT and disables separately while retaining the old active version on publish failure', async () => {
    const calls: string[] = [], active = { ...row, reviewed: true, activeVersionId: '14' }
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      calls.push(`${init.method ?? 'GET'} ${url}`)
      if (url.endsWith('/publish')) return new Response(JSON.stringify({ code: 'AI_UNAVAILABLE', message: '索引准备失败' }), { status: 503 })
      return ok(init.method ? active : { items: [active], ready: true })
    })
    const store = useKnowledgeStore(); await store.load(); await store.save(draft, '15')
    await expect(store.publish(active as any)).rejects.toThrow('索引准备失败')
    expect(store.items[0]?.activeVersionId).toBe('14')
    await store.disable(active as any)
    expect(calls).toContain('PUT /api/v1/admin/ai/knowledge/15')
    expect(calls).toContain('POST /api/v1/admin/ai/knowledge/15/disable')
  })
  it('discards a late list response after administrator logout', async () => {
    adminHttp.admin.value = { id: '7', loginName: 'admin', displayName: '管理员', role: 'ADMIN' }; await nextTick()
    let respond!: (value: Response) => void
    vi.stubGlobal('fetch', () => new Promise<Response>(resolve => { respond = resolve }))
    const store = useKnowledgeStore(), request = store.load()
    adminHttp.clear(); await nextTick(); respond(ok({ items: [row], ready: true })); await request
    expect(store.items).toEqual([]); expect(store.loaded).toBe(false); expect(store.ready).toBeNull()
  })
  it('keeps permission failures visible and blocks repeated writes until login changes', async () => {
    vi.stubGlobal('fetch', async () => new Response(JSON.stringify({ code: 'FORBIDDEN', message: '没有权限' }), { status: 403 }))
    const store = useKnowledgeStore()
    await expect(store.save(draft)).rejects.toThrow('没有权限')
    expect(store.writeForbidden).toBe(true)
    await expect(store.save(draft)).rejects.toThrow('权限')
  })
})

import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { AdminHttpClient, ApiError } from '../src/services/admin-http'
import { useContentModerationStore } from '../src/stores/content-moderation'
import { adaptReport } from '../src/services/admin-adapters'

const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data, message: '成功', requestId: 'req-test', timestamp: '2026-10-25T00:00:00.000Z' }), { status: 200 })
const failure = (status: number, code: string) => new Response(JSON.stringify({ code, data: null, message: '记录已更新，请刷新后重试', requestId: 'req-conflict' }), { status })
const admin = { id: '7', loginName: 'operator', displayName: '管理员甲', role: 'ADMIN' }
const tokens = { accessToken: 'memory-access', expiresIn: 900, csrfToken: 'csrf-one', admin }
const otherTokens = { ...tokens, accessToken: 'other-access', csrfToken: 'csrf-other', admin: { ...admin, id: '8', loginName: 'other' } }
function deferredResponse() {
  let resolve!: (response: Response) => void
  const response = new Promise<Response>(done => { resolve = done })
  return { response, resolve }
}
const post = { id: '41', title: '真实帖子', content: '后端内容', category: 'lost_found', status: 'visible', reviewState: 'normal', createdAt: '2026-10-25T00:00:00.000Z', author: { id: '9', name: '真实作者', avatar: null, selfReported: true }, images: [{ imageId: '5', url: '/api/v1/images/5/content?grant=x', width: 200, height: 100, expiresAt: null }], likeCount: 3, visibleCommentCount: 2, version: 4 }

beforeEach(() => {
  setActivePinia(createPinia())
  const memory = new Map<string, string>()
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => memory.get(key) ?? null, setItem: (key: string, value: string) => memory.set(key, value), removeItem: (key: string) => memory.delete(key) })
})
afterEach(() => vi.unstubAllGlobals())

describe('real admin HTTP boundary', () => {
  it('keeps the bearer in memory and sends refresh credentials with CSRF', async () => {
    const requests: Array<{ url: string; init?: RequestInit }> = []
    const client = new AdminHttpClient(async (url, init) => { requests.push({ url: String(url), init }); return ok(tokens) })
    await client.login('operator', 'secret')
    expect(client.accessToken).toBe('memory-access')
    await client.refresh()
    expect(requests[1]?.init?.credentials).toBe('same-origin')
    expect(new Headers(requests[1]?.init?.headers).get('X-CSRF-Token')).toBe('csrf-one')
    expect(sessionStorage.getItem('xinkelink.admin.access-token')).toBeNull()
    expect(sessionStorage.getItem('xinkelink.admin.password')).toBeNull()
  })

  it('refreshes once for concurrent expired requests and retries both with the rotated access token', async () => {
    let refreshCount = 0
    const client = new AdminHttpClient(async (url, init) => {
      if (String(url).endsWith('/login')) return ok(tokens)
      if (String(url).endsWith('/refresh')) { refreshCount++; await Promise.resolve(); return ok({ ...tokens, accessToken: 'rotated', csrfToken: 'csrf-two' }) }
      return new Headers(init?.headers).get('Authorization') === 'Bearer rotated' ? ok({ live: true }) : failure(401, 'TOKEN_EXPIRED')
    })
    await client.login('operator', 'secret')
    expect(await Promise.all([client.request('/posts'), client.request('/users')])).toEqual([{ live: true }, { live: true }])
    expect(refreshCount).toBe(1)
  })

  it('preserves server failures and request IDs without returning demo success', async () => {
    const client = new AdminHttpClient(async () => failure(409, 'VERSION_CONFLICT'))
    await expect(client.request('/posts')).rejects.toMatchObject({ code: 'VERSION_CONFLICT', requestId: 'req-conflict', status: 409 })
    expect(ApiError).toBeDefined()
  })

  it('closes a session when a retried bearer is also rejected', async () => {
    const client = new AdminHttpClient(async url => String(url).includes('/auth/') ? ok(tokens) : failure(401, 'UNAUTHENTICATED'))
    await client.login('operator', 'secret')
    await expect(client.request('/posts')).rejects.toMatchObject({ status: 401 })
    expect(client.admin.value).toBeNull()
    expect(client.accessToken).toBe('')
  })

  it('retries an expired CSV download at most once', async () => {
    let downloads = 0
    const client = new AdminHttpClient(async url => {
      if (String(url).includes('/auth/')) return ok(tokens)
      downloads++
      return downloads < 3 ? failure(401, 'UNAUTHENTICATED') : new Response('should-never-be-returned')
    })
    await client.login('operator', 'secret')
    await expect(client.download('/audit-events/export')).rejects.toMatchObject({ status: 401 })
    expect(downloads).toBe(2)
  })

  it.each(['request', 'download'] as const)('discards a late 401 for %s after logging in as another admin', async method => {
    const original = deferredResponse()
    const uses: Array<string | null> = []
    const client = new AdminHttpClient(async (url, init) => {
      if (String(url).endsWith('/auth/login')) return ok(String(init?.body).includes('other') ? otherTokens : tokens)
      uses.push(new Headers(init?.headers).get('Authorization'))
      return uses.length === 1 ? original.response : ok({})
    })
    await client.login('operator', 'secret')
    const operation = method === 'request' ? client.request('/posts', { method: 'POST', body: '{}' }) : client.download('/audit-events/export')
    const rejected = expect(operation).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
    await client.login('other', 'secret')
    original.resolve(failure(401, 'TOKEN_EXPIRED'))

    await rejected
    expect(uses).toEqual(['Bearer memory-access'])
    expect(client.admin.value?.id).toBe('8')
  })

  it('ignores an old refresh after logout and revokes the rotated cookie using its CSRF token', async () => {
    const rotation = deferredResponse()
    const csrf: Array<string | null> = []
    const client = new AdminHttpClient(async (url, init) => {
      if (String(url).endsWith('/auth/login')) return ok(tokens)
      if (String(url).endsWith('/auth/refresh')) return rotation.response
      csrf.push(new Headers(init?.headers).get('X-CSRF-Token'))
      return ok({ loggedOut: true })
    })
    await client.login('operator', 'secret')
    const refreshing = client.refresh()
    const rejected = expect(refreshing).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
    await Promise.resolve()
    const loggingOut = client.logout()
    expect(client.admin.value).toBeNull()
    expect(csrf).toEqual([])
    rotation.resolve(ok({ ...tokens, accessToken: 'rotated', csrfToken: 'csrf-rotated' }))

    await rejected
    await loggingOut
    expect(csrf).toEqual(['csrf-rotated'])
    expect(client.admin.value).toBeNull()
  })

  it('does not send an expired bearer to the cookie refresh endpoint', async () => {
    const client = new AdminHttpClient(async (url, init) => {
      const bearer = new Headers(init?.headers).get('Authorization')
      if (String(url).endsWith('/auth/login')) return ok(tokens)
      if (String(url).endsWith('/auth/refresh')) return bearer ? failure(401, 'TOKEN_EXPIRED') : ok({ ...tokens, accessToken: 'rotated' })
      return bearer === 'Bearer rotated' ? ok({ live: true }) : failure(401, 'TOKEN_EXPIRED')
    })
    await client.login('operator', 'secret')
    await expect(client.request('/posts')).resolves.toEqual({ live: true })
  })

  it('does not let a failed old sign-in clear a newer administrator', async () => {
    const original = deferredResponse()
    const client = new AdminHttpClient(async (_url, init) => String(init?.body).includes('other') ? ok(otherTokens) : original.response)
    const outdated = client.login('operator', 'secret')
    const rejected = expect(outdated).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
    await Promise.resolve()
    const current = client.login('other', 'secret')
    original.resolve(failure(401, 'INVALID_CREDENTIALS'))

    await rejected
    await current
    expect(client.admin.value?.id).toBe('8')
  })

  it('blocks a protected operation started during a pending account change', async () => {
    const login = deferredResponse()
    const operation = deferredResponse()
    const uses: Array<string | null> = []
    const client = new AdminHttpClient(async (url, init) => {
      if (String(url).endsWith('/auth/login')) return String(init?.body).includes('other') ? login.response : ok(tokens)
      uses.push(new Headers(init?.headers).get('Authorization'))
      return uses.length === 1 ? operation.response : ok({})
    })
    await client.login('operator', 'secret')
    const changingAccount = client.login('other', 'secret')
    const pending = client.request('/posts', { method: 'POST', body: '{}' }).then(() => 'sent', error => error.code)
    login.resolve(ok(otherTokens))
    await changingAccount
    operation.resolve(failure(401, 'TOKEN_EXPIRED'))

    expect(await pending).toBe('SESSION_CHANGED')
    expect(uses).toEqual([])
  })
})

describe('admin content DTO and commands', () => {
  it('uses the current reported user state without treating public visibility as account status', () => {
    expect(adaptReport({ id: '5', type: 'user', target: { id: '9', availability: 'available' }, targetUser: { id: '9', nickname: '同学', status: 'permanently_banned' } }).targetAvailability).toBe('permanently_banned')
  })
  it('loads backend pagination and global stats, maps category and media, and sends the displayed version', async () => {
    const calls: Array<{ url: string; init?: RequestInit }> = []
    vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
      calls.push({ url: String(url), init })
      if (init?.method === 'POST') return ok({ changedCount: 1, items: [{ id: '41', newVersion: 5, outcome: 'changed' }] })
      return ok({ items: [post], total: 11, page: 2, pageSize: 10, stats: { total: 27, visible: 20, hidden: 7, today: 2, pending: 1 } })
    })
    const store = useContentModerationStore()
    await store.loadPosts({ category: '失物招领', status: 'all' }, 2, 10)
    expect(store.queryPosts({}, 2, 10)).toMatchObject({ total: 11, page: 2, items: [{ id: '41', authorId: '9', category: '失物招领', images: ['/api/v1/images/5/content?grant=x'], version: 4 }] })
    expect(store.postStats.total).toBe(27)
    expect(calls[0]?.url).toContain('category=lost_found')
    expect(calls[0]?.url).not.toContain('status=all')
    await store.setPostStatus(['41'], 'hidden')
    expect(JSON.parse(String(calls.find(call => call.init?.method === 'POST')?.init?.body))).toEqual({ items: [{ id: '41', expectedVersion: 4 }], targetStatus: 'hidden' })
  })

  it('starts empty and rejects failed requests without inserting fixture data', async () => {
    vi.stubGlobal('fetch', async () => failure(503, 'SERVICE_UNAVAILABLE'))
    const store = useContentModerationStore()
    expect(store.posts).toEqual([])
    await expect(store.loadPosts({}, 1, 10)).rejects.toMatchObject({ code: 'SERVICE_UNAVAILABLE' })
    expect(store.posts).toEqual([])
    expect(store.postStats.total).toBe(0)
  })
})

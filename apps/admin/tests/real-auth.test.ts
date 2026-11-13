import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAdminAuthStore } from '../src/stores/admin-auth'
import { adminHttp, AdminHttpClient, CSRF_KEY } from '../src/services/admin-http'

const admin = { id: '7', loginName: 'operator', displayName: '管理员甲', role: 'ADMIN' }
const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 })
beforeEach(() => { setActivePinia(createPinia()); adminHttp.clear(); vi.stubGlobal('sessionStorage', { getItem: (key: string) => key === 'xinkelink.admin.demo.session' ? '1' : null, setItem() {}, removeItem() {} }); vi.stubGlobal('localStorage', { getItem: () => null, setItem() {}, removeItem() {} }) })
afterEach(() => vi.unstubAllGlobals())

it('ignores the old demo session and uses the authenticated server identity', async () => {
  const auth = useAdminAuthStore()
  expect(auth.isAuthenticated).toBe(false)
  vi.stubGlobal('fetch', async (url: string) => url.endsWith('/me') ? ok(admin) : ok({ admin, accessToken: 'real-access', csrfToken: 'real-csrf', expiresIn: 900 }))
  expect(await auth.signIn('operator', 'password', false)).toBe(true)
  expect(auth.currentAccount).toBe('operator')
  expect(auth.displayName).toBe('管理员甲')
})

it('does not authenticate invalid credentials and exposes the server request ID', async () => {
  vi.stubGlobal('fetch', async () => new Response(JSON.stringify({ code: 'INVALID_CREDENTIALS', message: '账号或密码错误', requestId: 'auth-42' }), { status: 401 }))
  const auth = useAdminAuthStore()
  expect(await auth.signIn('operator', 'bad-password', false)).toBe(false)
  expect(auth.isAuthenticated).toBe(false)
  expect(auth.loginError).toContain('auth-42')
})

it('restores a refresh-cookie session using only its persisted CSRF token', async () => {
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => key === CSRF_KEY ? 'saved-csrf' : null, setItem() {}, removeItem() {} })
  const client = new AdminHttpClient(async (_url, init) => new Headers(init?.headers).get('X-CSRF-Token') === 'saved-csrf' ? ok({ admin, accessToken: 'restored', csrfToken: 'rotated', expiresIn: 900 }) : new Response('{}', { status: 403 }))
  expect(await client.restore()).toBe(true)
  expect(client.accessToken).toBe('restored')
})

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory } from 'vue-router'
import { useAdminAuthStore } from '../src/stores/admin-auth'
import { adminHttp } from '../src/services/admin-http'
import { createAdminRouter } from '../src/router'

describe('protected administrator routes', () => {
  beforeEach(() => {
    adminHttp.clear()
    const admin = { id: '7', loginName: 'manager', displayName: '管理员', role: 'ADMIN' }
    vi.stubGlobal('fetch', async (url: string) => new Response(JSON.stringify({ code: 'OK', data: url.endsWith('/me') ? admin : { admin, accessToken: 'test-access', csrfToken: 'test-csrf', expiresIn: 900 } }), { status: 200 }))
    vi.stubGlobal('sessionStorage', { getItem: () => null, setItem() {}, removeItem() {} })
    vi.stubGlobal('localStorage', { getItem: () => null, setItem() {}, removeItem() {} })
    setActivePinia(createPinia())
  })

  it('redirects guests to login, then keeps signed-in users on the dashboard', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = createAdminRouter(pinia, createMemoryHistory())
    await router.push('/admin/dashboard')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/dashboard')

    await useAdminAuthStore(pinia).signIn('manager', 'demo-password', false)
    await router.push('/admin/dashboard')
    expect(router.currentRoute.value.name).toBe('dashboard')
    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  it('routes announcements and notifications to their management views instead of a developing placeholder', async () => {
    const pinia = createPinia()
    const router = createAdminRouter(pinia, createMemoryHistory())
    const announcement = router.getRoutes().find(route => route.name === 'announcements')
    const notification = router.getRoutes().find(route => route.name === 'notifications')
    expect(announcement?.meta.development).toBe(false)
    expect(notification?.meta.development).toBe(false)
    expect(announcement?.components?.default).not.toBe(notification?.components?.default)
  })

  it('keeps the logs route protected and maps it to the operation log page', async () => {
    const pinia = createPinia()
    const router = createAdminRouter(pinia, createMemoryHistory())
    await router.push('/admin/logs')
    expect(router.currentRoute.value.name).toBe('login')

    await useAdminAuthStore(pinia).signIn('manager', 'demo-password', false)
    await router.push('/admin/logs')
    const logs = router.getRoutes().find(route => route.name === 'logs')
    expect(router.currentRoute.value.name).toBe('logs')
    expect(logs?.meta.development).toBe(false)
    expect(logs?.components?.default).not.toBeUndefined()
  })

  it('keeps the settings route protected and removes its developing placeholder only after implementation', async () => {
    const pinia = createPinia()
    const router = createAdminRouter(pinia, createMemoryHistory())
    await router.push('/admin/settings')
    expect(router.currentRoute.value.name).toBe('login')
    await useAdminAuthStore(pinia).signIn('manager', 'demo-password', false)
    await router.push('/admin/settings')
    const settings = router.getRoutes().find(route => route.name === 'settings')
    expect(router.currentRoute.value.name).toBe('settings')
    expect(settings?.meta.development).toBe(false)
    expect(settings?.components?.default).not.toBeUndefined()
  })

  it('protects beta management and gives it a real management view', async () => {
    const pinia = createPinia()
    const router = createAdminRouter(pinia, createMemoryHistory())
    await router.push('/admin/beta')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/beta')
    await useAdminAuthStore(pinia).signIn('manager', 'demo-password', false)
    await router.push('/admin/beta')
    expect(router.currentRoute.value.name).toBe('beta')
    expect(router.currentRoute.value.meta.development).toBe(false)
  })
})

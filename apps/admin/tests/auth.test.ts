import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ADMIN_SESSION_KEY, REMEMBERED_ACCOUNT_KEY, useAdminAuthStore } from './fixtures/admin-auth'

function memoryStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key),
    clear: () => values.clear(),
    key: (index: number) => [...values.keys()][index] ?? null,
    get length() { return values.size },
  }
}

describe('simulated administrator session', () => {
  beforeEach(() => {
    vi.stubGlobal('sessionStorage', memoryStorage())
    vi.stubGlobal('localStorage', memoryStorage())
    setActivePinia(createPinia())
  })

  it('rejects empty credentials without opening the protected session', async () => {
    const auth = useAdminAuthStore()
    await expect(auth.signIn('  ', '  ', false)).resolves.toBe(false)
    expect(auth.isAuthenticated).toBe(false)
    expect(sessionStorage.getItem(ADMIN_SESSION_KEY)).toBeNull()
  })

  it('starts a local demo session and remembers only the account, never the password', async () => {
    const auth = useAdminAuthStore()
    await expect(auth.signIn('ops-manager', 'not-a-real-password', true)).resolves.toBe(true)
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.currentAccount).toBe('ops-manager')
    expect(localStorage.getItem(REMEMBERED_ACCOUNT_KEY)).toBe('ops-manager')
    expect(sessionStorage.getItem(ADMIN_SESSION_KEY)).toBe('1')
    expect(localStorage.getItem('password')).toBeNull()
    auth.signOut()
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.currentAccount).toBe('')
    expect(sessionStorage.getItem(ADMIN_SESSION_KEY)).toBeNull()
  })

  it('keeps the current account in memory instead of session or local storage', async () => {
    const auth = useAdminAuthStore()
    await auth.signIn('admin@example.edu', 'demo-password', false)

    expect(auth.currentAccount).toBe('admin@example.edu')
    expect(localStorage.getItem('xinkelink.admin.current-account')).toBeNull()
    expect(sessionStorage.getItem('xinkelink.admin.current-account')).toBeNull()
  })

  it('keeps the user signed out and exposes an error when browser session storage is unavailable', async () => {
    vi.stubGlobal('sessionStorage', {
      getItem: () => null,
      setItem: () => { throw new Error('storage unavailable') },
      removeItem: () => undefined,
    })
    const auth = useAdminAuthStore()
    await expect(auth.signIn('ops-manager', 'demo-password', false)).resolves.toBe(false)
    expect(auth.isAuthenticated).toBe(false)
    expect(auth.loginError).toBe('本地演示登录失败，请检查浏览器存储后重试')
  })
})

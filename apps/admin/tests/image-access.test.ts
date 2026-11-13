import { afterEach, describe, expect, it, vi } from 'vitest'
import { adminHttp, AdminHttpClient } from '../src/services/admin-http'
import { adminImageDue, rememberAdminImage, renewAdminImage } from '../src/services/admin-images'

const ok = (data: unknown) => new Response(JSON.stringify({ code: 'OK', data }), { status: 200 })
afterEach(() => vi.restoreAllMocks())

describe('admin image renewal', () => {
  it('renews through the shared image endpoint with the administrator bearer', async () => {
    const calls: { url: string; authorization: string | null }[] = []
    const client = new AdminHttpClient(async (url, init) => {
      calls.push({ url: String(url), authorization: new Headers(init?.headers).get('Authorization') })
      return ok(String(url).endsWith('/login')
        ? { accessToken: 'access', csrfToken: 'csrf', admin: { id: '7', role: 'ADMIN' } }
        : { imageId: '91', url: '/api/v1/images/91/content?grant=fresh' })
    })
    await client.login('demo', 'not-a-real-password')
    const result = await client.imageAccess('91')
    expect(result).toMatchObject({ imageId: '91' })
    expect(calls[1]).toEqual({ url: '/api/v1/images/91/access', authorization: 'Bearer access' })
  })
  it('uses the server expiration and ignores local assets or external URLs', () => {
    const url = rememberAdminImage({ url: '/api/v1/images/92/content?grant=old', expiresAt: '2020-01-01T00:00:00Z' })
    expect(adminImageDue(url)).toBe(true)
    const fresh = rememberAdminImage({ url: '/api/v1/images/92/content?grant=fresh', expiresAt: new Date(Date.now() + 60000).toISOString() })
    expect(adminImageDue(fresh)).toBe(false)
    expect(adminImageDue('/assets/brand.png')).toBe(false)
    expect(adminImageDue('https://example.invalid/api/v1/images/92/content')).toBe(false)
  })
  it('deduplicates concurrent renewals of one image', async () => {
    const request = vi.spyOn(adminHttp, 'imageAccess').mockResolvedValue({ imageId: '93', url: '/api/v1/images/93/content?grant=fresh', expiresAt: new Date(Date.now() + 60000).toISOString() })
    const result = await Promise.all([renewAdminImage('/api/v1/images/93/content?grant=a'), renewAdminImage('/api/v1/images/93/content?grant=b')])
    expect(request).toHaveBeenCalledTimes(1)
    expect(result).toEqual(['/api/v1/images/93/content?grant=fresh', '/api/v1/images/93/content?grant=fresh'])
  })
  it('propagates access failures without returning an expired fallback', async () => {
    vi.spyOn(adminHttp, 'imageAccess').mockRejectedValue(new Error('无权查看'))
    await expect(renewAdminImage('/api/v1/images/94/content?grant=old')).rejects.toThrow('无权查看')
  })
})

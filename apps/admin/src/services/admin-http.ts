import { ref } from 'vue'

export interface AdminMe { id: string; loginName: string; displayName: string; role: 'ADMIN' }
interface AdminTokens { accessToken: string; expiresIn: number; csrfToken: string; admin: AdminMe }
export const CSRF_KEY = 'xinkelink.admin.csrf'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public requestId = '', public errors: Array<{ field: string; code: string; message?: string }> = []) { super(message); this.name = 'ApiError' }
}

function readCsrf() { try { return globalThis.sessionStorage?.getItem(CSRF_KEY) ?? '' } catch { return '' } }
function saveCsrf(value: string) { try { value ? globalThis.sessionStorage?.setItem(CSRF_KEY, value) : globalThis.sessionStorage?.removeItem(CSRF_KEY) } catch { /* The current tab can still use its in-memory CSRF token. */ } }

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.message}${error.requestId ? `（请求编号：${error.requestId}）` : ''}`
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

export class AdminHttpClient {
  readonly admin = ref<AdminMe | null>(null)
  private token = ''
  private csrf = readCsrf()
  private refreshFlight: Promise<AdminTokens> | null = null
  private restoreFlight: Promise<boolean> | null = null
  private restored = false
  constructor(private transport: typeof fetch = (input, init) => globalThis.fetch(input, init)) {}
  get accessToken() { return this.token }

  private accept(tokens: AdminTokens) {
    if (!tokens?.accessToken || !tokens.csrfToken || tokens.admin?.role !== 'ADMIN') throw new ApiError(502, 'INVALID_RESPONSE', '登录响应格式异常')
    this.token = tokens.accessToken
    this.csrf = tokens.csrfToken
    this.admin.value = tokens.admin
    this.restored = true
    saveCsrf(this.csrf)
    return tokens
  }
  clear() { this.token = ''; this.csrf = ''; this.admin.value = null; saveCsrf(''); this.restored = true }

  async login(account: string, password: string) { return this.accept(await this.send<AdminTokens>('/auth/login', { method: 'POST', body: JSON.stringify({ account, password }) }, false)) }

  refresh(): Promise<AdminTokens> {
    if (this.refreshFlight) return this.refreshFlight
    // Read storage here too: singleton creation can precede browser initialization in tests.
    this.csrf ||= readCsrf()
    this.refreshFlight = this.send<AdminTokens>('/auth/refresh', { method: 'POST', headers: { 'X-CSRF-Token': this.csrf } }, false)
      .then(value => this.accept(value))
      .catch(error => { this.token = ''; this.admin.value = null; if (error instanceof ApiError && [401, 403].includes(error.status)) this.clear(); throw error })
      .finally(() => { this.refreshFlight = null })
    return this.refreshFlight
  }

  async restore() {
    if (this.token && this.admin.value) return true
    if (this.restored) return false
    if (this.restoreFlight) return this.restoreFlight
    if (!(this.csrf || readCsrf())) { this.restored = true; return false }
    this.restoreFlight = this.refresh().then(() => true).catch(() => false).finally(() => { this.restored = true; this.restoreFlight = null })
    return this.restoreFlight
  }
  async logout() {
    try { await this.send('/auth/logout', { method: 'POST', headers: { 'X-CSRF-Token': this.csrf } }, false) }
    finally { this.clear() }
  }

  request<T = unknown>(path: string, init: RequestInit = {}): Promise<T> { return this.send<T>(path, init, true) }

  imageAccess(imageId: string): Promise<{ imageId: string; url: string; expiresAt: string }> {
    if (!/^[1-9]\d*$/.test(imageId)) return Promise.reject(new Error('图片编号无效'))
    return this.send(`/images/${imageId}/access`, {}, true, '/api/v1')
  }

  private async send<T>(path: string, init: RequestInit, retry: boolean, base = '/api/v1/admin'): Promise<T> {
    const usedToken = this.token
    const headers = new Headers(init.headers)
    headers.set('Accept', 'application/json')
    if (init.body) headers.set('Content-Type', 'application/json')
    if (usedToken) headers.set('Authorization', `Bearer ${usedToken}`)
    let response: Response
    try { response = await this.transport(`${base}${path}`, { ...init, headers, credentials: 'same-origin' }) }
    catch { throw new ApiError(0, 'NETWORK_ERROR', '网络连接失败，请检查服务后重试') }
    if (response.status === 401 && retry && (this.csrf || readCsrf())) {
      // A request started with the old bearer may arrive after another request rotated it.
      if (!this.token || usedToken === this.token) await this.refresh()
      return this.send<T>(path, init, false, base)
    }
    let payload: { code?: string; message?: string; data?: T; requestId?: string; errors?: ApiError['errors'] }
    try { payload = await response.json() }
    catch { throw new ApiError(response.status, 'INVALID_RESPONSE', '服务返回了无效响应') }
    if (!response.ok || payload.code !== 'OK') {
      if (response.status === 401) this.clear()
      throw new ApiError(response.status, payload.code ?? 'HTTP_ERROR', payload.message ?? '请求失败，请稍后重试', payload.requestId, payload.errors)
    }
    return payload.data as T
  }

  async download(path: string, retry = true): Promise<Blob> {
    const usedToken = this.token
    let response: Response
    try { response = await this.transport(`/api/v1/admin${path}`, { headers: { Authorization: `Bearer ${usedToken}` }, credentials: 'same-origin' }) }
    catch { throw new ApiError(0, 'NETWORK_ERROR', '网络连接失败，请检查服务后重试') }
    if (response.status === 401 && retry && this.csrf) {
      if (!this.token || usedToken === this.token) await this.refresh()
      return this.download(path, false)
    }
    if (!response.ok) {
      if (response.status === 401) this.clear()
      const payload = await response.json(); throw new ApiError(response.status, payload.code, payload.message, payload.requestId)
    }
    return response.blob()
  }
}

export const adminHttp = new AdminHttpClient()

export function queryString(values: Record<string, unknown>) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) if (value !== undefined && value !== null && value !== '' && value !== 'all') params.set(key, String(value))
  return params.size ? `?${params}` : ''
}

export function periodRange(period?: string) {
  if (period !== '7d' && period !== '30d') return {}
  const now = new Date()
  return { from: new Date(now.getTime() - (period === '7d' ? 7 : 30) * 86400000).toISOString(), to: now.toISOString() }
}

export function shanghaiBoundary(value: string | undefined, nextDay = false) {
  if (!value) return undefined
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return new Date(value).toISOString()
  return new Date(Date.parse(`${value}T00:00:00+08:00`) + (nextDay ? 86400000 : 0)).toISOString()
}

import { ref } from 'vue'

export interface AdminMe { id: string; loginName: string; displayName: string; role: 'ADMIN' }
interface AdminTokens { accessToken: string; expiresIn: number; csrfToken: string; admin: AdminMe }
export const CSRF_KEY = 'xinkelink.admin.csrf'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public requestId = '', public errors: Array<{ field: string; code: string; message?: string }> = []) { super(message); this.name = 'ApiError' }
}

function readCsrf() { try { return globalThis.sessionStorage?.getItem(CSRF_KEY) ?? '' } catch { return '' } }
function saveCsrf(value: string) { try { value ? globalThis.sessionStorage?.setItem(CSRF_KEY, value) : globalThis.sessionStorage?.removeItem(CSRF_KEY) } catch { /* The current tab can still use its in-memory CSRF token. */ } }
function sessionChanged() { return new ApiError(0, 'SESSION_CHANGED', '登录状态已变化，请重新操作') }

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return `${error.message}${error.requestId ? `（请求编号：${error.requestId}）` : ''}`
  return error instanceof Error ? error.message : '请求失败，请稍后重试'
}

export class AdminHttpClient {
  readonly admin = ref<AdminMe | null>(null)
  private token = ''
  private csrf = readCsrf()
  private identity?: string
  private generation = 0
  private cookieQueue = Promise.resolve()
  private cookieCsrf = ''
  private loginPending = false
  private refreshFlight: Promise<AdminTokens> | null = null
  private restoreFlight: Promise<boolean> | null = null
  private restored = false
  constructor(private transport: typeof fetch = (input, init) => globalThis.fetch(input, init)) {}
  get accessToken() { return this.token }
  get sessionGeneration() { return this.generation }

  private accept(tokens: AdminTokens) {
    this.token = tokens.accessToken
    this.csrf = tokens.csrfToken
    this.identity = tokens.admin.id
    this.admin.value = tokens.admin
    this.restored = true
    saveCsrf(this.csrf)
    return tokens
  }
  clear() { this.resetSession() }

  private resetSession(loginPending = false) {
    const generation = ++this.generation
    this.token = ''; this.csrf = ''; this.identity = undefined
    this.loginPending = loginPending; this.refreshFlight = null; this.restoreFlight = null; this.restored = true
    saveCsrf(''); this.admin.value = null
    return generation
  }

  private ensureSession(generation: number) {
    if (generation !== this.generation) throw sessionChanged()
  }

  private queueCookie<T>(operation: () => Promise<T>) {
    // The browser applies Set-Cookie before application code sees the response.
    const result = this.cookieQueue.then(operation)
    this.cookieQueue = result.then(() => undefined, () => undefined)
    return result
  }

  private cookieTokens(path: string, init: RequestInit, generation: number) {
    return this.queueCookie(async () => {
      this.ensureSession(generation)
      const tokens = await this.readResponse<AdminTokens>(await this.fetchResponse(`/api/v1/admin${path}`, init))
      if (!tokens?.accessToken || !tokens.csrfToken || !tokens.admin?.id || tokens.admin.role !== 'ADMIN') throw new ApiError(502, 'INVALID_RESPONSE', '登录响应格式异常')
      this.cookieCsrf = tokens.csrfToken
      return tokens
    })
  }

  async login(account: string, password: string) {
    const generation = this.resetSession(true)
    try {
      const tokens = await this.cookieTokens('/auth/login', { method: 'POST', body: JSON.stringify({ account, password }) }, generation)
      this.ensureSession(generation)
      return this.accept(tokens)
    } catch (error) { this.ensureSession(generation); throw error }
    finally { if (generation === this.generation) this.loginPending = false }
  }

  refresh(): Promise<AdminTokens> {
    if (this.loginPending && !this.token) return Promise.reject(sessionChanged())
    if (this.refreshFlight) return this.refreshFlight
    const generation = this.generation
    // Read storage here too: singleton creation can precede browser initialization in tests.
    this.csrf ||= readCsrf()
    this.refreshFlight = this.cookieTokens('/auth/refresh', { method: 'POST', headers: { 'X-CSRF-Token': this.csrf } }, generation)
      .then(tokens => {
        this.ensureSession(generation)
        if (this.identity && this.identity !== tokens.admin.id) { this.clear(); throw sessionChanged() }
        return this.accept(tokens)
      })
      .catch(error => {
        this.ensureSession(generation)
        if (error instanceof ApiError && [401, 403].includes(error.status)) this.clear()
        else { this.token = ''; this.admin.value = null }
        throw error
      })
      .finally(() => { if (generation === this.generation) this.refreshFlight = null })
    return this.refreshFlight
  }

  async restore() {
    if (this.token && this.admin.value) return true
    if (this.restored) return false
    if (this.restoreFlight) return this.restoreFlight
    if (!(this.csrf || readCsrf())) { this.restored = true; return false }
    const generation = this.generation
    this.restoreFlight = this.refresh().then(() => generation === this.generation).catch(() => false).finally(() => {
      if (generation === this.generation) { this.restored = true; this.restoreFlight = null }
    })
    return this.restoreFlight
  }
  async logout() {
    const csrf = this.csrf || readCsrf()
    this.clear()
    await this.queueCookie(async () => {
      const headers = { 'X-CSRF-Token': this.cookieCsrf || csrf }
      try { await this.readResponse(await this.fetchResponse('/api/v1/admin/auth/logout', { method: 'POST', headers })) }
      finally { this.cookieCsrf = '' }
    })
  }

  request<T = unknown>(path: string, init: RequestInit = {}): Promise<T> { return this.send<T>(path, init) }

  imageAccess(imageId: string): Promise<{ imageId: string; url: string; expiresAt: string }> {
    if (!/^[1-9]\d*$/.test(imageId)) return Promise.reject(new Error('图片编号无效'))
    return this.send(`/images/${imageId}/access`, {}, '/api/v1')
  }

  private async send<T>(path: string, init: RequestInit, base = '/api/v1/admin'): Promise<T> {
    const generation = this.generation
    const response = await this.authorizedResponse(`${base}${path}`, init, true, generation)
    try {
      const value = await this.readResponse<T>(response)
      this.ensureSession(generation)
      return value
    } catch (error) { this.ensureSession(generation); throw error }
  }

  private async fetchResponse(path: string, init: RequestInit, token = '') {
    const headers = new Headers(init.headers)
    if (!headers.has('Accept')) headers.set('Accept', 'application/json')
    if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
    if (token) headers.set('Authorization', `Bearer ${token}`)
    try { return await this.transport(path, { ...init, headers, credentials: 'same-origin' }) }
    catch { throw new ApiError(0, 'NETWORK_ERROR', '网络连接失败，请检查服务后重试') }
  }

  private async authorizedResponse(path: string, init: RequestInit, retry: boolean, generation: number): Promise<Response> {
    this.ensureSession(generation)
    if (this.loginPending && !this.token) throw sessionChanged()
    const usedToken = this.token
    let response: Response
    try { response = await this.fetchResponse(path, init, usedToken) }
    catch (error) { this.ensureSession(generation); throw error }
    this.ensureSession(generation)
    if (response.status !== 401) return response
    if (retry && (this.csrf || readCsrf())) {
      // A request started with the old bearer may arrive after another request rotated it in this session.
      if (!this.token || usedToken === this.token) await this.refresh()
      return this.authorizedResponse(path, init, false, generation)
    }
    // Read the error before clearing, so a delayed body cannot clear a replacement session.
    try { return await this.readResponse<never>(response) }
    catch (error) {
      this.ensureSession(generation)
      if (usedToken === this.token) this.clear()
      throw error
    }
  }

  private async readResponse<T>(response: Response): Promise<T> {
    let payload: { code?: string; message?: string; data?: T; requestId?: string; errors?: ApiError['errors'] }
    try { payload = await response.json() }
    catch { throw new ApiError(response.status, 'INVALID_RESPONSE', '服务返回了无效响应') }
    if (!response.ok || payload.code !== 'OK') {
      throw new ApiError(response.status, payload.code ?? 'HTTP_ERROR', payload.message ?? '请求失败，请稍后重试', payload.requestId, payload.errors)
    }
    return payload.data as T
  }

  async download(path: string, retry = true): Promise<Blob> {
    const generation = this.generation
    const response = await this.authorizedResponse(`/api/v1/admin${path}`, { headers: { Accept: 'text/csv, application/json' } }, retry, generation)
    try {
      const blob = response.ok ? await response.blob() : await this.readResponse<never>(response)
      this.ensureSession(generation)
      return blob
    } catch (error) { this.ensureSession(generation); throw error }
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

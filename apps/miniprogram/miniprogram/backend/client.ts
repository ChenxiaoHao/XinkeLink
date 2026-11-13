import { API_PREFIX, BACKEND_ORIGIN, SESSION_STORAGE_KEY } from './config'

export type Json = Record<string, any>
export interface Runtime {
  request(options: any): any
  downloadFile?(options: any): any
  getStorageSync(key: string): unknown
  setStorageSync(key: string, value: unknown): void
  removeStorageSync?(key: string): void
  login?(options: any): void
  uploadFile?(options: any): any
  connectSocket?(options: any): any
  showToast?(options: any): void
  navigateTo?(options: any): void
  redirectTo?(options: any): void
  reLaunch?(options: any): void
  /** Set by the unit-test harness, and by app.ts for local demo mode in DevTools. Never inferred from errors or missing APIs. */
  __fixtureMode?: boolean
}
export class ApiError extends Error {
  constructor(public code: string, message: string, public status = 0, public requestId?: string) { super(message) }
}
interface Session { accessToken: string; refreshToken: string; expiresAt: number; me: Json; profileConfirmed?: boolean }
export class ApiClient {
  private session: Session | null = null
  private refreshing: Promise<void> | null = null
  private signingIn: Promise<void> | null = null
  private profilePrompt: (() => Promise<boolean>) | null = null
  private profilePromptPending: Promise<boolean> | null = null
  private betaAccessRequired: (() => void) | null = null
  private listeners = new Set<() => void>()
  private sessionGeneration = 0
  private lastNotifiedLoggedIn: boolean | null = null
  constructor(public runtime: Runtime, public origin = BACKEND_ORIGIN) {
    const stored = runtime.getStorageSync(SESSION_STORAGE_KEY) as Session | undefined
    if (stored && typeof stored.accessToken === 'string' && typeof stored.refreshToken === 'string'
      && typeof stored.expiresAt === 'number' && stored.me && typeof stored.me.id === 'string') this.session = stored
    this.lastNotifiedLoggedIn = this.loggedIn
  }
  get me(): Json | null { return this.session?.me ?? null }
  get loggedIn(): boolean { return !!this.session && this.session.profileConfirmed !== false }
  get token(): string | undefined { return this.session?.accessToken }
  onSessionChange(listener: () => void): () => void { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  private changed(): void {
    this.listeners.forEach(listener => listener())
    // 退出登录（已登录→访客）的后端汇合点：reLaunch 首页让所有页面按访客身份重载。
    // 登录侧（访客→已登录）不在这里跳：登录流程有自己的目的地（beta 门禁兑换后 reLaunch
    // 到 redirect 目标），中途跳转会摧毁进行中的流程页；抽屉登录由 refreshIdentity 就地刷新。
    // token 静默刷新（save）、资料更新（setMe）登录态不变，不触发跳转。
    const now = this.loggedIn
    if (this.lastNotifiedLoggedIn === true && now === false) this.runtime.reLaunch?.({ url: '/pages/forum/index' })
    this.lastNotifiedLoggedIn = now
  }
  clear(): void {
    this.sessionGeneration++
    this.signingIn = null
    this.session = null
    try { this.runtime.removeStorageSync?.(SESSION_STORAGE_KEY); this.runtime.setStorageSync(SESSION_STORAGE_KEY, null) } finally { this.changed() }
  }
  private save(tokens: Json, profileConfirmed = this.session?.profileConfirmed !== false): void {
    if (!tokens.accessToken || !tokens.refreshToken || !tokens.me?.id) throw new ApiError('INVALID_RESPONSE', '登录响应格式有误')
    const value = { accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, expiresAt: Date.now() + tokens.expiresIn * 1000, me: tokens.me, profileConfirmed }
    this.runtime.setStorageSync(SESSION_STORAGE_KEY, value)
    this.session = value; this.changed()
  }
  setMe(me: Json, profileConfirmed = this.session?.profileConfirmed !== false): void { if (this.session) { this.session = { ...this.session, me, profileConfirmed }; this.runtime.setStorageSync(SESSION_STORAGE_KEY, this.session); this.changed() } }
  setProfilePrompt(prompt: () => Promise<boolean>): void { this.profilePrompt = prompt }
  setBetaAccessRequired(handler: () => void): void { this.betaAccessRequired = handler }
  async login(requireProfile = false): Promise<void> {
    if (this.signingIn) return this.signingIn
    const generation = this.sessionGeneration
    // 同时点击登录或多个受保护操作，只换取一次code和会话。
    const pending = (async () => {
      const result: Json = await new Promise((resolve, reject) => {
        if (!this.runtime.login) { reject(new ApiError('WECHAT_UNAVAILABLE', '当前环境不支持微信登录')); return }
        this.runtime.login({ success: resolve, fail: () => reject(new ApiError('WECHAT_LOGIN_FAILED', '微信登录未完成，请重试')) })
      })
      if (!result.code) throw new ApiError('WECHAT_LOGIN_FAILED', '微信未返回登录凭据')
      if (generation !== this.sessionGeneration) throw new ApiError('UNAUTHENTICATED', '登录操作已取消', 401)
      const tokens = await this.raw('/auth/wechat', 'POST', { code: result.code }, undefined)
      if (generation !== this.sessionGeneration) throw new ApiError('UNAUTHENTICATED', '登录操作已取消', 401)
      this.save(tokens, !requireProfile)
    })()
    this.signingIn = pending
    try { await pending } finally { if (this.signingIn === pending) this.signingIn = null }
  }
  async logout(): Promise<void> { try { if (this.session) await this.request('/auth/logout', 'POST', {}) } finally { this.clear() } }
  async requireSession(): Promise<boolean> {
    if (this.profilePromptPending) return this.profilePromptPending
    if (this.loggedIn) return true
    try {
      if (this.profilePrompt) {
        this.profilePromptPending = this.profilePrompt()
        try { return await this.profilePromptPending && this.loggedIn } finally { this.profilePromptPending = null }
      }
      await this.login(); return true
    }
    catch (error) {
      const title = error instanceof ApiError && error.code === 'WECHAT_LOGIN_FAILED' ? '微信登录暂不可用，请稍后重试' : error instanceof Error ? error.message : '登录未完成，请重试'
      this.runtime.showToast?.({ title, icon: 'none' }); return false
    }
  }
  private raw(path: string, method: string, data?: Json, token?: string, headers: Json = {}): Promise<any> {
    return new Promise((resolve, reject) => {
      this.runtime.request({ url: this.origin + API_PREFIX + path, method, data,
        timeout: 15000, header: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}), ...headers },
        success: (result: Json) => {
          const body = result.data
          if (result.statusCode >= 200 && result.statusCode < 300 && body?.code === 'OK') resolve(body.data)
          else {
            if (result.statusCode === 403 && body?.code === 'BETA_ACCESS_REQUIRED') this.betaAccessRequired?.()
            reject(new ApiError(body?.code || 'HTTP_ERROR', body?.message || '服务暂不可用，请重试', result.statusCode, body?.requestId))
          }
        }, fail: () => reject(new ApiError('NETWORK_ERROR', '网络连接失败，请检查网络后重试')) })
    })
  }
  async refresh(): Promise<void> {
    if (this.refreshing) return this.refreshing
    if (!this.session) throw new ApiError('UNAUTHENTICATED', '请先微信登录', 401)
    const refreshToken = this.session.refreshToken, generation = this.sessionGeneration
    this.refreshing = (async () => {
      try {
        const tokens = await this.raw('/auth/refresh', 'POST', { refreshToken })
        if (generation !== this.sessionGeneration) throw new ApiError('UNAUTHENTICATED', '登录状态已退出', 401)
        this.save(tokens)
      } catch (error) {
        if (generation === this.sessionGeneration && error instanceof ApiError && error.status === 401) this.clear()
        throw error
      } finally { this.refreshing = null }
    })()
    return this.refreshing
  }
  async request(path: string, method = 'GET', data?: Json, headers?: Json): Promise<any> {
    if (this.session && this.session.expiresAt <= Date.now() + 30000) await this.refresh()
    const token = this.token
    try { return await this.raw(path, method, data, token, headers) }
    catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401 || !token) throw error
      if (this.token === token) await this.refresh()
      if (!this.session) throw error
      try { return await this.raw(path, method, data, this.token, headers) }
      catch (retryError) { if (retryError instanceof ApiError && retryError.status === 401) this.clear(); throw retryError }
    }
  }
  async upload(filePath: string, purpose: string): Promise<Json> {
    if (!this.session) throw new ApiError('UNAUTHENTICATED', '请先微信登录', 401)
    if (this.session.expiresAt <= Date.now() + 30000) await this.refresh()
    const send = (): Promise<Json> => new Promise((resolve, reject) => {
      if (!this.runtime.uploadFile) { reject(new ApiError('UPLOAD_UNAVAILABLE', '当前环境不支持图片上传')); return }
      this.runtime.uploadFile({ url: this.origin + API_PREFIX + '/images', filePath, name: 'file', formData: { purpose },
        header: { Authorization: 'Bearer ' + this.token },
        success: (result: Json) => {
          let body: Json
          try { body = typeof result.data === 'string' ? JSON.parse(result.data) : result.data } catch { reject(new ApiError('INVALID_RESPONSE', '图片服务响应有误')); return }
          if (result.statusCode >= 200 && result.statusCode < 300 && body.code === 'OK') resolve(body.data)
          else {
            if (result.statusCode === 403 && body.code === 'BETA_ACCESS_REQUIRED') this.betaAccessRequired?.()
            reject(new ApiError(body.code || 'UPLOAD_ERROR', body.message || '图片上传失败', result.statusCode))
          }
        }, fail: () => reject(new ApiError('NETWORK_ERROR', '图片上传失败，请重试')) })
    })
    try { return await send() } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 401) throw error
      await this.refresh()
      try { return await send() } catch (retryError) { if (retryError instanceof ApiError && retryError.status === 401) this.clear(); throw retryError }
    }
  }
}
const clients = new WeakMap<object, ApiClient>()
export function client(runtime: Runtime): ApiClient { let value = clients.get(runtime); if (!value) { value = new ApiClient(runtime); clients.set(runtime, value) } return value }
export function query(values: Json): string {
  const fields = Object.keys(values).filter(key => values[key] !== undefined && values[key] !== null && values[key] !== '')
  return fields.length ? '?' + fields.map(key => encodeURIComponent(key) + '=' + encodeURIComponent(String(values[key]))).join('&') : ''
}
export function requestId(): string { return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2) }
export function sequence(value: unknown): string {
  if (typeof value !== 'string' || !/^(0|[1-9][0-9]{0,19})$/.test(value) || value.length === 20 && value > '18446744073709551615') throw new ApiError('INVALID_SEQUENCE', '消息序号格式有误')
  return value
}
export function compareSequence(a: string, b: string): number { sequence(a); sequence(b); return a.length - b.length || (a < b ? -1 : a > b ? 1 : 0) }

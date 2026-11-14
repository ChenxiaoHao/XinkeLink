import { ApiClient, ApiError, Json } from './client'

export interface BetaStatus { phase: 'beta' | 'open'; authenticated: boolean; activated: boolean }
export const BETA_PAGE = '/pages/beta-access/index'
export const DEFAULT_BETA_TARGET = '/pages/forum/index'
// Only registered business pages may be restored after login. The gate cannot target itself.
const destinations = new Set(['forum', 'contact', 'profile', 'forum-detail', 'forum-publish', 'contact-chat', 'my-posts',
  'my-collections', 'profile-edit', 'user-home', 'search', 'report', 'chat-settings', 'feedback', 'notifications', 'ai', 'drafts', 'new-chat'])
export function safeBetaTarget(value: unknown): string {
  if (typeof value !== 'string' || value.length > 2048 || /[\\#\u0000-\u001f\u007f]/.test(value)) return DEFAULT_BETA_TARGET
  const match = /^\/pages\/([a-z-]+)\/index(?:\?([^?]*))?$/.exec(value)
  if (!match || !destinations.has(match[1])) return DEFAULT_BETA_TARGET
  try { if (/[\\\u0000-\u001f\u007f]/.test(decodeURIComponent(value))) return DEFAULT_BETA_TARGET } catch { return DEFAULT_BETA_TARGET }
  return value
}
export function betaTarget(name: string, options: Json = {}): string {
  const fields = Object.keys(options).filter(key => typeof options[key] === 'string')
  const suffix = fields.length ? '?' + fields.map(key => encodeURIComponent(key) + '=' + encodeURIComponent(options[key])).join('&') : ''
  return safeBetaTarget('/pages/' + name + '/index' + suffix)
}
export function betaStatus(value: any): BetaStatus {
  if (!value || !['beta', 'open'].includes(value.phase) || typeof value.authenticated !== 'boolean' || typeof value.activated !== 'boolean') throw new ApiError('INVALID_RESPONSE', '内测状态响应有误，请重试')
  return value
}
export class BetaAccess {
  private pending: { token?: string; promise: Promise<BetaStatus> } | null = null
  private redirecting = false
  private target = DEFAULT_BETA_TARGET
  private atGate = false
  constructor(private api: ApiClient) {
    api.setBetaAccessRequired(() => this.redirect())
  }
  enterBusiness(target: string): void { this.atGate = false; this.redirecting = false; this.target = safeBetaTarget(target) }
  enterGate(): void { this.atGate = true; this.redirecting = false }
  async check(fresh = false): Promise<BetaStatus> {
    const token = this.api.token
    if (!fresh && this.pending && this.pending.token === token) return this.pending.promise
    const promise = (async () => {
      try { return betaStatus(await this.api.request('/beta/access')) }
      catch (error) {
        // A revoked refresh clears the session; the public status can then be read as a guest.
        if (error instanceof ApiError && error.status === 401 && !this.api.token) return betaStatus(await this.api.request('/beta/access'))
        throw error
      }
    })()
    const pending = { token, promise }; this.pending = pending
    try { return await promise } finally { if (this.pending === pending) this.pending = null }
  }
  allowed(status: BetaStatus): boolean { return status.phase === 'open' || status.authenticated && status.activated && this.api.loggedIn }
  async guard(stillActive: () => boolean = () => true): Promise<boolean> {
    if (this.redirecting || this.atGate) return false
    try { if (this.allowed(await this.check())) return stillActive() && !this.redirecting && !this.atGate } catch { /* Unknown access never grants entry. The gate offers retry. */ }
    if (!stillActive()) return false
    this.redirect(); return false
  }
  redirect(): void {
    if (this.atGate || this.redirecting) return
    this.redirecting = true
    const runtime = this.api.runtime
    const navigate = runtime.reLaunch || runtime.redirectTo
    if (!navigate) { this.redirecting = false; runtime.showToast?.({ title: '请重新打开小程序完成内测准入', icon: 'none' }); return }
    navigate.call(runtime, { url: BETA_PAGE + '?redirect=' + encodeURIComponent(this.target), fail: () => {
      this.redirecting = false; runtime.showToast?.({ title: '内测准入页打开失败，请重试', icon: 'none' })
    } })
  }
  leave(target: string, failed: () => void): void {
    const runtime = this.api.runtime, navigate = runtime.reLaunch || runtime.redirectTo
    if (!navigate) { failed(); return }
    navigate.call(runtime, { url: safeBetaTarget(target), fail: failed })
  }
}
const gates = new WeakMap<ApiClient, BetaAccess>()
export function betaAccess(api: ApiClient): BetaAccess {
  let gate = gates.get(api)
  if (!gate) { gate = new BetaAccess(api); gates.set(api, gate) }
  return gate
}

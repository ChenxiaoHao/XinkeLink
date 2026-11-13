import { ApiClient, ApiError, Json } from './client'
import { BetaAccess, betaStatus, safeBetaTarget } from './beta'

function active(page: any, epoch: number): boolean {
  return !page._betaStopped && !page._betaHidden && page._betaEpoch === epoch
}

export function betaPage(api: ApiClient, gate: BetaAccess): Json {
  return {
    _betaEpoch: 0, _betaHidden: false,
    data: { stage: 'checking', code: '', error: '', checking: false, submitting: false },
    onLoad(options: Json = {}) {
      gate.enterGate(); this._betaStopped = false; this._betaHidden = false
      this._betaDestination = safeBetaTarget(options.redirect)
      return this.reload()
    },
    onShow() {
      if (this._betaStopped) return
      this._betaHidden = false; gate.enterGate(); return this.reload()
    },
    onHide() {
      this._betaHidden = true; this._betaEpoch++
      this._betaLoading = null; this._betaEntering = false
      // Leave the shared hide lifecycle in place; only this gate's pending consent is cancelled.
      this._authCancelled = true; this._authConsentResolve?.(false); this._authConsentResolve = null
      this.setData({ checking: false, submitting: false, loggingIn: false, authVisible: false })
    },
    onUnload() { this._betaStopped = true; this._betaEpoch++; this._betaLoading = null },
    async reload() {
      const epoch = this._betaEpoch
      if (!active(this, epoch)) return
      if (this._betaLoading || this._betaEntering || this.data.loggingIn || this.data.submitting) return this._betaLoading
      this.setData({ checking: true, error: '', stage: 'checking' })
      const pending = (async () => {
        try {
          // A resumed gate must not reuse a request made before it was hidden.
          const state = await gate.check(true)
          if (!active(this, epoch)) return
          if (gate.allowed(state)) { this.enterBetaDestination(); return }
          this.setData({ stage: state.authenticated && api.loggedIn ? 'code' : 'login' })
        } catch (error) {
          if (active(this, epoch)) this.setData({ stage: 'error', error: error instanceof Error ? error.message : '内测状态读取失败，请重试' })
        } finally { if (active(this, epoch)) this.setData({ checking: false }) }
      })()
      this._betaLoading = pending
      try { await pending } finally { if (this._betaLoading === pending) this._betaLoading = null }
    },
    enterBetaDestination() {
      const epoch = this._betaEpoch
      if (this._betaEntering || !active(this, epoch)) return
      this._betaEntering = true; this.setData({ stage: 'entering', error: '' })
      gate.leave(this._betaDestination, () => {
        if (!active(this, epoch)) return
        this._betaEntering = false
        this.setData({ stage: 'error', error: '页面打开失败，请重试' })
      })
    },
    async onLoginTap() {
      const epoch = this._betaEpoch
      if (!active(this, epoch) || this.data.authSaving || this.data.loggingIn || this.data.checking || this.data.submitting || this.data.stage !== 'login') return
      this.setData({ loggingIn: true, error: '' })
      const completed = await api.requireSession()
      if (!active(this, epoch)) return
      this.setData({ loggingIn: false })
      if (!completed) { this.setData({ error: '登录已取消，可重新登录后继续' }); return }
      await this.reload()
    },
    onCodeInput(event: Json) {
      if (!active(this, this._betaEpoch) || this.data.submitting) return
      this.setData({ code: typeof event.detail?.value === 'string' ? event.detail.value.slice(0, 64) : '', error: '' })
    },
    async onActivateTap() {
      const epoch = this._betaEpoch
      if (!active(this, epoch) || this.data.stage !== 'code' || this.data.checking || this.data.loggingIn || this.data.submitting || !api.loggedIn) return
      const code = this.data.code.trim()
      if (!code) { this.setData({ error: '请填写内测码' }); return }
      this.setData({ submitting: true, error: '' })
      try {
        const state = betaStatus(await api.request('/beta/redeem', 'POST', { code }))
        if (!active(this, epoch)) return
        if (gate.allowed(state)) this.enterBetaDestination()
        else this.setData({ stage: state.authenticated && api.loggedIn ? 'code' : 'login', error: '账号尚未激活，请重试' })
      } catch (error) {
        if (!active(this, epoch)) return
        const message = error instanceof ApiError && error.code === 'BETA_CODE_UNAVAILABLE' ? '内测码不可用，请检查后重试'
          : error instanceof ApiError && error.status === 429 ? '操作过于频繁，请稍后重试'
          : error instanceof Error ? error.message : '激活未完成，请重试'
        this.setData({ stage: api.loggedIn ? 'code' : 'login', error: message })
      } finally { if (active(this, epoch)) this.setData({ submitting: false }) }
    },
  }
}

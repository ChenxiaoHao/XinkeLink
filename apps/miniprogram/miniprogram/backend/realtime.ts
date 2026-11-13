import { ApiClient, Json } from './client'
/** Events only trigger HTTP reconciliation. They never become local persisted facts. */
export class Realtime {
  private socket: any = null
  private timer: ReturnType<typeof setTimeout> | null = null
  private stopped = true
  private failures = 0
  private dispose: (() => void) | null = null
  constructor(private api: ApiClient, private reconcile: () => void) {}
  start(): void {
    if (!this.stopped) return
    this.stopped = false
    this.dispose = this.api.onSessionChange(() => {
      if (!this.api.loggedIn) this.stop()
      else { const previous = this.socket; this.socket = null; previous?.close({}); this.connect() }
    })
    this.connect()
  }
  private connect(): void {
    if (this.stopped || this.socket || !this.api.loggedIn || !this.api.runtime.connectSocket) return
    const socket = this.api.runtime.connectSocket({ url: this.api.origin.replace(/^http/, 'ws') + '/api/v1/ws', header: { Authorization: 'Bearer ' + this.api.token } })
    this.socket = socket
    socket.onOpen(() => { this.failures = 0; this.reconcile() })
    socket.onMessage((event: Json) => {
      try { const value = JSON.parse(event.data); if (typeof value.eventId === 'string') this.reconcile() } catch { /* Invalid frames never mutate business state. */ }
    })
    const reconnect = () => {
      if (this.stopped || this.socket !== socket || this.timer) return
      this.socket = null
      this.timer = setTimeout(async () => {
        this.timer = null
        try { await this.api.refresh(); this.reconcile(); this.connect() } catch { if (this.api.loggedIn && !this.stopped) { this.socket = socket; reconnect() } }
      }, Math.min(30000, 1000 * Math.pow(2, this.failures++)))
    }
    socket.onClose(reconnect); socket.onError(reconnect)
  }
  stop(): void { this.stopped = true; if (this.timer) clearTimeout(this.timer); this.timer = null; this.dispose?.(); this.dispose = null; this.socket?.close({}); this.socket = null }
}

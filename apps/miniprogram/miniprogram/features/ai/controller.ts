import { requestId } from '../../backend/client'
import { AiMessage, AiRepository, AiSession } from './repository'

const pendingRefreshDelays = [1000, 2000, 4000, 8000, 12000, 18000, 25000]
const messageStatus = (message: AiMessage) => String(message.status).toLowerCase()

/** Retain the selected session ID while hidden. Reload its contents for the same owner on return. */
export class AiController {
  private generation = 0
  private owner = ''
  private active = false
  private unsubscribe?: () => void
  private pending?: { requestId: string; text: string }
  private sessionId = ''
  private resumeSessionId = ''
  private busy = false
  private polling = false
  private refreshTimer?: ReturnType<typeof setTimeout>
  private messages: AiMessage[] = []
  private retryAction?: () => Promise<void>
  constructor(private repo: AiRepository, private update: (patch: Record<string, unknown>) => void) {}
  private clear() {
    this.stopPolling()
    this.generation++; this.pending = undefined; this.sessionId = ''; this.messages = []; this.busy = false; this.retryAction = undefined
    this.update({ sessionId: '', messages: [], sessions: [], prompt: '', error: '', loading: false, busy: false, pendingQuestion: '', historyVisible: false, guest: !this.repo.identity() })
  }
  private syncOwner() {
    const owner = this.repo.identity()
    if (owner === this.owner) return false
    this.owner = owner
    this.resumeSessionId = ''
    this.clear()
    return true
  }
  async start() {
    this.active = true
    this.unsubscribe ||= this.repo.onIdentityChange(() => {
      if (this.syncOwner() && this.active && this.owner) void this.list()
    })
    this.syncOwner()
    if (this.resumeSessionId) await this.open(this.resumeSessionId)
    else await this.list()
  }
  pause() {
    this.active = false
    this.clear()
  }
  stop() {
    this.unsubscribe?.()
    this.unsubscribe = undefined
    this.resumeSessionId = ''
    this.owner = ''
    this.pause()
  }
  private valid(g: number, owner: string) { return this.active && g === this.generation && owner === this.repo.identity() && !!owner }
  private fail(error: unknown) { this.update({ error: error instanceof Error ? error.message : '请求失败，请重试' }) }
  private stopPolling() {
    if (this.refreshTimer !== undefined) clearTimeout(this.refreshTimer)
    this.refreshTimer = undefined
    this.polling = false
  }
  private pollPending(id: string, pendingId: string, generation: number, owner: string, attempt = 0) {
    this.polling = true
    this.refreshTimer = setTimeout(async () => {
      this.refreshTimer = undefined
      if (!this.valid(generation, owner)) return
      try {
        const session = await this.repo.read(id, 10000)
        if (!this.valid(generation, owner)) return
        const messages = session.messages || []
        const message = messages.find(item => item.requestId === pendingId)
        if (message && messageStatus(message) !== 'pending') {
          this.stopPolling()
          this.showHistory(id, messages, generation, owner)
          return
        }
      } catch {
        if (!this.valid(generation, owner)) return
      }
      if (attempt + 1 < pendingRefreshDelays.length) {
        this.pollPending(id, pendingId, generation, owner, attempt + 1)
      } else {
        this.stopPolling()
        this.update({ busy: false, error: '请求尚未完成，请稍后重试' })
      }
    }, pendingRefreshDelays[attempt])
  }
  private showHistory(id: string, messages: AiMessage[], generation: number, owner: string) {
    this.sessionId = id
    this.messages = messages
    this.pending = undefined
    this.retryAction = undefined
    let error = ''
    const last = messages[messages.length - 1]
    if (last && ['pending', 'error', 'failed'].includes(messageStatus(last))) {
      this.pending = { requestId: last.requestId, text: last.question }
      this.messages = messages.slice(0, -1)
      this.retryAction = () => this.send(last.question)
      if (messageStatus(last) === 'pending') this.pollPending(id, last.requestId, generation, owner)
      else error = last.answer || '上次请求未完成，请重试'
    }
    this.update({ sessionId: id, messages: this.messages, historyVisible: false, pendingQuestion: this.pending?.text || '', busy: this.polling, error })
  }
  async list() {
    if (!this.active) return
    if (!this.repo.identity()) { this.clear(); return }
    const g = this.generation, owner = this.repo.identity()
    this.update({ loading: true, guest: false, ...(this.pending ? {} : { error: '' }) })
    try { const sessions = await this.repo.list(); if (this.valid(g, owner)) this.update({ sessions }) }
    catch (e) { if (this.valid(g, owner) && !this.pending) { this.fail(e); this.retryAction = () => this.list() } }
    finally { if (this.valid(g, owner)) this.update({ loading: false }) }
  }
  async open(id: string) {
    if (!this.active || !id || this.busy || !this.repo.identity()) return
    this.stopPolling()
    const g = ++this.generation, owner = this.repo.identity()
    this.resumeSessionId = id
    this.pending = undefined; this.sessionId = ''; this.messages = []
    this.update({ messages: [], pendingQuestion: '', sessionId: '', loading: true, busy: false, error: '' })
    try {
      const session = await this.repo.read(id)
      if (!this.valid(g, owner)) return
      this.showHistory(id, session.messages || [], g, owner)
    }
    catch(e) { if(this.valid(g,owner)) { this.fail(e); this.retryAction = () => this.open(id) } }
    finally { if (this.valid(g,owner)) this.update({ loading: false }) }
  }
  async newSession() {
    if (!this.active || this.busy || !this.repo.identity()) return
    this.stopPolling()
    ++this.generation; this.sessionId = ''; this.resumeSessionId = ''; this.pending = undefined; this.messages = []; this.retryAction = undefined
    this.update({ sessionId: '', messages: [], prompt: '', pendingQuestion: '', error: '', loading: false, busy: false, historyVisible: false })
  }
  async remove(id: string) {
    if (!this.active || !id || !this.repo.identity() || ((this.busy || this.polling) && id !== this.resumeSessionId)) return
    // Invalidate before the DELETE: even an already in-flight send must not refill this UI.
    if (id === this.resumeSessionId) { this.resumeSessionId = ''; this.clear() }
    else this.generation++
    const g = this.generation, owner = this.repo.identity()
    try { await this.repo.remove(id); if(this.valid(g,owner)) await this.list() }
    catch(e) { if(this.valid(g,owner)) { this.fail(e); this.retryAction = () => this.remove(id) } }
  }
  async send(text: string) {
    text = text.trim()
    if (!this.active || this.busy || this.polling || !text || !this.repo.identity()) return
    if (this.pending && this.pending.text !== text) { this.update({error:'请先重试未完成的问题，或新建会话'}); return }
    this.pending ||= { requestId: requestId(), text }
    const pending = this.pending, g = this.generation, owner = this.repo.identity()
    this.busy = true; this.update({ busy: true, error: '', pendingQuestion: text, prompt: '' })
    this.retryAction = () => this.send(text)
    try {
      if (!this.sessionId) {
        const session: AiSession = await this.repo.create()
        if (!this.valid(g, owner)) return
        this.sessionId = this.resumeSessionId = session.id
        this.update({ sessionId: session.id })
      }
      const message = await this.repo.send(this.sessionId, pending.requestId, text)
      if (!this.valid(g,owner)) return
      if (messageStatus(message) === 'pending') {
        this.pollPending(this.sessionId, pending.requestId, g, owner)
        return
      }
      if (['failed', 'error'].includes(messageStatus(message))) throw Error(message.answer || '请求未完成，请重试')
      this.messages = [...this.messages.filter(m => m.requestId !== pending.requestId), message]
      this.pending = undefined; this.retryAction = undefined
      this.update({ messages: this.messages, pendingQuestion: '' }); await this.list()
    } catch(e) { if(this.valid(g,owner)) this.fail(e) }
    finally { if(this.valid(g,owner)) { this.busy = false; this.update({ busy: this.polling }) } }
  }
  async retry() { if (!this.busy && !this.polling) await this.retryAction?.() }
}

import { ApiError, client, Json, Runtime } from '../../backend/client'
import { getViewerRole } from '../profile/viewer'
import { localIdentity } from '../profile/repository'

const ANONYMOUS_KEY = 'xinkelink.statistics.anonymous.v1'
const MAX_PENDING = 20
const MAX_AGE = 10 * 60 * 1000
const DAY = 24 * 60 * 60 * 1000
const BEIJING = 8 * 60 * 60 * 1000
interface Credential { credential: string; expiresAt: string }
interface Pending { eventId: string; postId: string; createdAt: number; generation: number; identity: string; anonymousCredential?: string }
const pendingAcrossPages = new Set<Pending>()
interface Timing { now(): number; setTimer(action: () => void, delay: number): ReturnType<typeof setTimeout>; clearTimer(timer: ReturnType<typeof setTimeout>): void }
export interface FixtureView { eventId: string; postId: string; visitor: string; occurredAt: number }

function identity(runtime: Runtime): string {
  const fixture = runtime.__fixtureMode === true
  const mode = fixture ? 'fixture:' : 'live:'
  if (getViewerRole(runtime as typeof wx) === 'guest') return mode + 'guest'
  return mode + 'user:' + (fixture ? localIdentity(runtime).id : String(client(runtime).me?.id))
}
function uuid(): string {
  // Event IDs carry no authority; the server authenticates identity and the immutable event separately.
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, ch => {
    const value = Math.floor(Math.random() * 16)
    return (ch === 'x' ? value : value & 3 | 8).toString(16)
  })
}

/** Bounded, memory-only retry queue. Process exit drops unconfirmed work; server-confirmed work is durable. */
export class ViewStatistics {
  private items: Pending[] = []
  private generation = 0
  private owner: string
  private running: Promise<void> | null = null
  private timer: ReturnType<typeof setTimeout> | null = null
  private stopped = false
  private retryDelay = 1000
  private unsubscribe: () => void
  private timing: Timing
  constructor(private runtime: Runtime, timing: Partial<Timing> = {}) {
    this.timing = { now: Date.now, setTimer: (action, delay) => setTimeout(action, delay), clearTimer: timer => clearTimeout(timer), ...timing }
    this.owner = identity(runtime)
    this.unsubscribe = client(runtime).onSessionChange(() => this.syncIdentity())
  }
  private syncIdentity(): void {
    const current = identity(this.runtime)
    if (current === this.owner) return
    this.owner = current; this.generation++; this.dropAll(); this.retryDelay = 1000
    if (this.timer !== null) this.timing.clearTimer(this.timer)
    this.timer = null
  }
  private remove(item: Pending): void { pendingAcrossPages.delete(item); this.items = this.items.filter(entry => entry !== item) }
  private dropAll(): void { this.items.forEach(item => pendingAcrossPages.delete(item)); this.items = [] }
  private prune(): void { this.items.filter(item => item.createdAt + MAX_AGE <= this.timing.now()).forEach(item => this.remove(item)) }
  get pendingCount(): number { this.syncIdentity(); this.prune(); return this.items.length }
  enqueue(postId: string, eventId = uuid()): void {
    if (this.stopped || this.runtime.__fixtureMode === true) return
    this.syncIdentity(); this.prune()
    if (pendingAcrossPages.size >= MAX_PENDING) return
    const item = { eventId, postId, createdAt: this.timing.now(), generation: this.generation, identity: this.owner }
    this.items.push(item); pendingAcrossPages.add(item)
    void this.flush()
  }
  private current(item: Pending): boolean {
    this.syncIdentity()
    return !this.stopped && item.generation === this.generation && item.identity === this.owner && item.createdAt + MAX_AGE > this.timing.now()
  }
  private async credential(item: Pending): Promise<string> {
    let stored: Credential | undefined
    try { stored = this.runtime.getStorageSync(ANONYMOUS_KEY) as Credential | undefined } catch { /* May still collect with an in-memory credential. */ }
    if (stored && typeof stored.credential === 'string' && Date.parse(stored.expiresAt) > this.timing.now() + MAX_AGE) return stored.credential
    const fresh: Credential = await client(this.runtime).requestBound('/statistics/anonymous-credential', 'POST', {}, () => this.current(item), false)
    if (!this.current(item)) throw new ApiError('STATISTICS_IDENTITY_CHANGED', '统计会话已变化')
    if (typeof fresh?.credential !== 'string' || !fresh.credential || !(Date.parse(fresh.expiresAt) > this.timing.now())) throw new ApiError('INVALID_RESPONSE', '统计凭证响应有误')
    try { this.runtime.setStorageSync(ANONYMOUS_KEY, fresh) } catch { /* Storage failure cannot block the detail page. */ }
    return fresh.credential
  }
  flush(): Promise<void> {
    if (this.running) return this.running
    if (this.stopped) return Promise.resolve()
    if (this.timer !== null) this.timing.clearTimer(this.timer)
    this.timer = null
    const run = async (): Promise<void> => {
      this.syncIdentity(); this.prune()
      while (!this.stopped && this.items.length) {
        const item = this.items[0]
        if (!this.current(item)) { this.remove(item); continue }
        try {
          const body: Json = { version: 1, eventId: item.eventId, postId: item.postId }
          if (item.identity === 'live:guest') {
            item.anonymousCredential ||= await this.credential(item)
            body.anonymousCredential = item.anonymousCredential
          }
          if (!this.current(item)) continue
          const result = await client(this.runtime).requestBound('/statistics/post-views', 'POST', body, () => this.current(item), item.identity !== 'live:guest')
          if (!this.current(item)) continue
          if (result?.accepted !== true || result.eventId !== item.eventId) throw new ApiError('INVALID_RESPONSE', '统计接收结果未知')
          this.remove(item); this.retryDelay = 1000
        } catch (error) {
          if (!this.current(item)) continue
          // Rejections cannot become a new event. Unknown/transport outcomes retain the exact ID.
          if (error instanceof ApiError && error.status >= 400 && error.status < 500 && ![408, 429].includes(error.status)) {
            this.remove(item); continue
          }
          break
        }
      }
    }
    this.running = run().finally(() => {
      this.running = null; this.prune()
      if (!this.stopped && this.items.length) {
        this.timer = this.timing.setTimer(() => { this.timer = null; void this.flush() }, this.retryDelay)
        // Node regression tests must not remain alive for an app-lifetime retry timer.
        ;(this.timer as unknown as { unref?: () => void }).unref?.()
        this.retryDelay = Math.min(this.retryDelay * 2, 60000)
      }
    })
    return this.running
  }
  dispose(): void {
    this.stopped = true; this.generation++; this.dropAll(); this.unsubscribe()
    if (this.timer !== null) this.timing.clearTimer(this.timer)
    this.timer = null
  }
}
const queues = new WeakMap<object, ViewStatistics>()
export function viewStatistics(runtime: Runtime): ViewStatistics {
  let queue = queues.get(runtime)
  if (!queue) { queue = new ViewStatistics(runtime); queues.set(runtime, queue) }
  return queue
}

interface FixtureState { events: FixtureView[] }
const fixtureStates = new WeakMap<object, FixtureState>()
function fixtureState(runtime: Runtime): FixtureState {
  // DevTools can hand page modules different wx wrappers. App state is shared across those pages.
  if (typeof getApp === 'function') {
    try {
      const app = getApp() as { __viewStatisticsFixture?: FixtureState } | undefined
      if (app) { app.__viewStatisticsFixture ||= { events: [] }; return app.__viewStatisticsFixture }
    } catch { /* Before App startup, use the isolated runtime state. */ }
  }
  let state = fixtureStates.get(runtime)
  if (!state) { state = { events: [] }; fixtureStates.set(runtime, state) }
  return state
}
/** A tracker belongs to one page instance, and consumes its display only once across onShow and refresh. */
export function detailVisit(runtime: Runtime, postId: string): { displayed(isPublic: boolean): void } {
  let recorded = false
  const eventId = uuid()
  return { displayed(isPublic) {
    if (recorded || !isPublic || !postId) return
    recorded = true
    if (runtime.__fixtureMode === true) {
      const state = fixtureState(runtime)
      // Fixture currently has one explicit local identity; it does not invent account IDs from profile text.
      const visitor = getViewerRole(runtime as typeof wx) === 'guest' ? 'fixture:guest' : 'fixture:user:' + localIdentity(runtime).id
      state.events.push({ eventId, postId, visitor, occurredAt: Date.now() })
      state.events = state.events.slice(-2000)
    } else viewStatistics(runtime).enqueue(postId, eventId)
  } }
}
export function beijingWindow(now = Date.now()): { startDate: string; endDate: string; zone: string; days: number } {
  const today = Math.floor((now + BEIJING) / DAY) * DAY
  return { startDate: new Date(today - 6 * DAY).toISOString().slice(0, 10), endDate: new Date(today).toISOString().slice(0, 10), zone: 'Asia/Shanghai', days: 7 }
}
export function rankFixtureViews<T extends { id: string }>(posts: T[], events: FixtureView[], now = Date.now()): { post: T; pv: number; uv: number }[] {
  const start = Math.floor((now + BEIJING) / DAY) * DAY - BEIJING - 6 * DAY
  const end = start + 7 * DAY
  const seen = new Set<string>(), counts = new Map<string, { pv: number; visitors: Set<string> }>()
  for (const event of events) {
    if (seen.has(event.eventId) || event.occurredAt < start || event.occurredAt >= end || event.occurredAt > now) continue
    seen.add(event.eventId)
    const count = counts.get(event.postId) || { pv: 0, visitors: new Set<string>() }
    count.pv++; count.visitors.add(event.visitor); counts.set(event.postId, count)
  }
  const byId = (a: string, b: string): number => {
    if (/^[1-9][0-9]*$/.test(a) && /^[1-9][0-9]*$/.test(b) && a.length !== b.length) return a.length - b.length
    return a < b ? -1 : a > b ? 1 : 0
  }
  return posts.filter(post => counts.has(post.id)).map(post => ({ post, pv: counts.get(post.id)!.pv, uv: counts.get(post.id)!.visitors.size }))
    .sort((a, b) => b.uv - a.uv || byId(a.post.id, b.post.id))
}
export function fixtureRanking<T extends { id: string }>(runtime: Runtime, posts: T[], now = Date.now()): { post: T; pv: number; uv: number }[] {
  const today = Math.floor((now + BEIJING) / DAY) * DAY - BEIJING
  // Deterministic demonstration browsing facts, independent of likes and post creation dates.
  const baseline: FixtureView[] = []
  posts.forEach(post => {
    const seed = [...post.id].reduce((hash, character) => (hash * 31 + character.charCodeAt(0)) % 6, 0)
    for (let visitor = 0; visitor < seed + 1; visitor++) {
      for (const day of [0, 1]) baseline.push({ eventId: 'demo:' + post.id + ':' + visitor + ':' + day, postId: post.id, visitor: 'demo:' + visitor, occurredAt: today - day * DAY })
    }
  })
  return rankFixtureViews(posts, [...baseline, ...fixtureState(runtime).events], now).slice(0, 5)
}

import { client, Json, requestId, Runtime } from '../../backend/client'
import { postView } from '../../backend/adapters'
import { getViewerRole } from '../profile/viewer'
import { onIdentityChange } from '../profile/identity-events'
import { requireLocalSession } from '../profile/repository'
import { readForum } from '../forum/repository'
import { toPostView } from '../forum/feed'

export interface AiMessage { id: string; requestId: string; question: string; answer: string; status: string; sources: Json[]; posts: Json[]; createdAt: string }
export interface AiSession { id: string; title: string; updatedAt: string; messages?: AiMessage[] }

const demoKnowledge = [
  { topic: /请假|销假/, title: '校园请假流程', excerpt: '请假一天以内由辅导员确认。急诊等情况可先电话联系，在返校后两个工作日内补交申请。' },
  { topic: /奖学金|绩点/, title: '进步奖学金申请', excerpt: '最近一学期平均绩点需达到 3.2，且较上一学期提高至少 0.3。示例申报窗口为 10 月 8 日至 10 月 15 日。' },
  { topic: /图书馆|借阅|闭馆/, title: '图书馆开放与借阅', excerpt: '常规工作日 21:30 闭馆。期末周工作日延长至 22:30，周末仍在 18:00 闭馆。' },
]

export class AiRepository {
  constructor(public runtime: typeof wx, private timeoutMs = 120000) {}
  get fixture() { return (this.runtime as Runtime).__fixtureMode === true }
  identity() { return getViewerRole(this.runtime) === 'guest' ? '' : this.fixture ? 'fixture:local-student' : 'live:' + client(this.runtime).me?.id }
  onIdentityChange(listener: () => void) {
    return this.fixture ? onIdentityChange(listener) : client(this.runtime).onSessionChange(listener)
  }
  async requireSession() { return this.fixture ? requireLocalSession(this.runtime) : client(this.runtime).requireSession() }
  private guard() { if (!this.identity()) throw Error('请先登录后使用 Link AI') }
  private request(path: string, method = 'GET', data: Json = {}, timeoutMs = this.timeoutMs) {
    const owner = this.identity()
    return client(this.runtime).requestBound(path, method, data, () => owner === this.identity() && !!owner, true, timeoutMs)
  }
  private key() { return 'xinkelink.ai.demo.sessions:' + encodeURIComponent(this.identity()) }
  private stored(): AiSession[] { const value = this.runtime.getStorageSync(this.key()); return Array.isArray(value) ? value : [] }
  private save(items: AiSession[]) { this.runtime.setStorageSync(this.key(), items) }
  async list(): Promise<AiSession[]> {
    this.guard()
    if (!this.fixture) return this.request('/ai/sessions')
    return this.stored().map(({ messages: _, ...session }) => session).sort((a,b) => b.updatedAt.localeCompare(a.updatedAt))
  }
  async create(): Promise<AiSession> {
    this.guard()
    if (!this.fixture) return this.request('/ai/sessions', 'POST', {})
    const session = { id: 'demo-' + requestId(), title: '新会话（演示）', updatedAt: new Date().toISOString(), messages: [] }
    this.save([session, ...this.stored()]); return session
  }
  async read(id: string, timeoutMs = this.timeoutMs): Promise<AiSession> {
    this.guard()
    if (!this.fixture) { const result = await this.request('/ai/sessions/' + encodeURIComponent(id), 'GET', {}, timeoutMs); return { ...result, messages: result.messages.map((m: AiMessage) => ({...m, posts: (m.posts || []).map(postView)})) } }
    const session = this.stored().find(s => s.id === id)
    if (!session) throw Error('会话不存在或已删除')
    return { ...session, messages: (session.messages || []).map(m => ({ ...m, posts: this.demoPosts(m.question) })) }
  }
  async remove(id: string): Promise<void> {
    this.guard()
    if (!this.fixture) { await this.request('/ai/sessions/' + encodeURIComponent(id), 'DELETE'); return }
    this.save(this.stored().filter(s => s.id !== id))
  }
  private demoPosts(text: string): Json[] {
    if (!/找.*帖|查.*帖/.test(text) || /不存在|无结果/.test(text)) return []
    const state = readForum(this.runtime)
    return state.posts.slice(0, 2).map(p => toPostView(p, state.interactions))
  }
  async send(id: string, stableId: string, text: string): Promise<AiMessage> {
    this.guard()
    if (!text.trim() || text.length > 1000) throw Error('请输入 1—1000 字的问题')
    if (!this.fixture) { const result = await this.request('/ai/sessions/' + encodeURIComponent(id) + '/messages', 'POST', { requestId: stableId, text }); return { ...result, posts: (result.posts || []).map(postView) } }
    const owner = this.identity(), session = await this.read(id)
    if (owner !== this.identity() || !this.fixture) throw Error('登录状态已变化，请重新打开会话')
    const messages = session.messages || [], old = messages.find(m => m.requestId === stableId)
    if (old) { if (old.question !== text) throw Error('相同请求的内容不能更改'); return old }
    if (text.includes('演示错误')) throw Error('演示：服务暂不可用，请重试')
    const posts = this.demoPosts(text)
    const missing = /不存在|无结果/.test(text)
    const knowledge = !missing && !posts.length ? demoKnowledge.find(item => item.topic.test(text)) : undefined
    const answer = missing ? '当前未找到符合条件的帖子（演示）。' : posts.length ? '以下是演示公开帖子，点击可查看详情。' : knowledge ? '【测试回答】在虚构样例中，' + knowledge.excerpt + '实际办理请以学校正式通知为准。' : 'Link AI 仅支持学校规章制度知识库问答和查找公开帖子，请说明你想查询的内容。'
    const message: AiMessage = { id: 'demo-message-' + requestId(), requestId: stableId, question: text, answer, status: 'completed', posts, sources: knowledge ? [{ title: '【测试样例】' + knowledge.title, version: 'demo-v1', section: '', page: '', url: 'urn:xinkelink:demo-knowledge' }] : [], createdAt: new Date().toISOString() }
    const updated = { ...session, title: text.slice(0, 24), updatedAt: message.createdAt, messages: [...messages, message] }
    this.save(this.stored().map(s => s.id === id ? updated : s)); return message
  }
}

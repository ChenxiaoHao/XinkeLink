import { LocalStorage } from '../profile/repository'
import { CONTACT_KEY, ContactMessage, emptyThread, readContactState } from './storage'
import { MOCK_POSTS } from '../forum/mock-posts'
import { readPeerChatSettings } from './settings'
const startedAt = Date.UTC(2026, 9, 22, 6, 28)
export interface Peer { id: string; nickname: string; avatar: string; bio: string }
export interface Conversation { id: string; peer: Peer; messages: ContactMessage[]; unread: number; lastMessage: string; updatedAt: number; timeLabel: string }
const PEERS: Peer[] = [
  { id: 'mock-ajie', nickname: '大三计科阿杰', avatar: '/assets/mock/forum/avatar-zhou.jpg', bio: '分享校园里的新鲜事。' },
  { id: 'mock-lin', nickname: '林知夏', avatar: '/assets/mock/forum/avatar-lin.jpg', bio: '记录校园生活，分享学习日常。' },
  { id: 'mock-su', nickname: '苏小禾', avatar: '/assets/mock/forum/avatar-lin.jpg', bio: '分享复习笔记与校园生活。' },
]
const SEEDS: Record<string, ContactMessage[]> = {
  'mock-lin': [{ id: 'seed-lin-1', text: '好的，那我们明天下午图书馆一楼大厅见。', fromSelf: false, createdAt: startedAt - 86_400_000, status: 'sent' }],
  'mock-su': [{ id: 'seed-su-1', text: '这周末一起整理复习笔记吗？', fromSelf: false, createdAt: startedAt - 172_800_000, status: 'sent' }],
}
export function timeLabel(time: number): string {
  const date = new Date(time), today = new Date(), gap = today.getTime() - time
  if (date.toDateString() === today.toDateString()) return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  if (gap < 172_800_000) return '昨天'
  return `${date.getMonth() + 1}/${date.getDate()}`
}
export function resolvePeer(_storage: LocalStorage, id: string): Peer | undefined {
  const author = MOCK_POSTS.find(p => p.author.id === id && !p.anonymousNumber)?.author
  return PEERS.find(peer => peer.id === id) ?? (author ? { ...author, bio: '记录校园生活与学习日常' } : undefined)
}
export function readConversation(storage: LocalStorage, id: string): Conversation {
  const state = readContactState(storage), peer = resolvePeer(storage, id)
  if (!peer) throw new Error('会话不存在')
  const thread = state.threads[id] ?? emptyThread()
  const merged = [...(SEEDS[id] ?? []), ...thread.messages]
  const messages = merged.filter((m, i) => m.createdAt > (thread.clearedAt ?? 0) && merged.findIndex(other => other.id === m.id) === i).sort((a, b) => a.createdAt - b.createdAt)
  const last = messages[messages.length - 1], updatedAt = last?.createdAt ?? 0
  return { id, peer, messages, unread: messages.filter(m => !m.fromSelf && !thread.readIds.includes(m.id)).length,
    lastMessage: last?.text ?? '还没有消息', updatedAt, timeLabel: updatedAt ? timeLabel(updatedAt) : '' }
}
export function listConversations(storage: LocalStorage): Conversation[] {
  const state = readContactState(storage)
  const ids = [...new Set([...PEERS.map(peer=>peer.id),...Object.keys(state.threads)])]
  return ids.filter(id => resolvePeer(storage, id)).map(id => readConversation(storage, id)).sort((a, b) => {
    const aPinned = readPeerChatSettings(storage, a.id).pinned, bPinned = readPeerChatSettings(storage, b.id).pinned
    return Number(bPinned) - Number(aPinned) || b.updatedAt - a.updatedAt
  })
}
export function clearConversationHistory(storage: LocalStorage, id: string): void {
  const state = readContactState(storage)
  if (!resolvePeer(storage, id)) throw new Error('会话不存在')
  const thread = state.threads[id] ?? emptyThread()
  storage.setStorageSync(CONTACT_KEY, { version: 1, threads: { ...state.threads, [id]: { ...thread, clearedAt: Date.now() } } })
}
export function openConversation(storage:LocalStorage,id:string):void {
 const state=readContactState(storage),conversation=readConversation(storage,id),thread=state.threads[id]??emptyThread()
 storage.setStorageSync(CONTACT_KEY,{version:1,threads:{...state.threads,[id]:{...thread,readIds:[...new Set([...thread.readIds,...conversation.messages.filter(m=>!m.fromSelf).map(m=>m.id)])]}}})
}
export function markAllConversationsRead(storage: LocalStorage): void {
  const state = readContactState(storage), threads = { ...state.threads }
  for (const c of listConversations(storage)) { const t = threads[c.id] ?? emptyThread(); threads[c.id] = { ...t, readIds: [...new Set([...t.readIds, ...c.messages.filter(m => !m.fromSelf).map(m => m.id)])] } }
  storage.setStorageSync(CONTACT_KEY, { version: 1, threads })
}
export function sendText(storage: LocalStorage, id: string, body: string): void {
  const text = body.trim()
  if (!text || text.length > 1000) throw new Error('消息需为 1 至 1000 字')
  if (readPeerChatSettings(storage, id).blocked) throw new Error('已屏蔽此用户，暂时无法发送消息')
  const state = readContactState(storage), c = readConversation(storage, id), thread = state.threads[id] ?? emptyThread()
  const message: ContactMessage = { id: 'contact-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10), text, fromSelf: true, createdAt: Date.now(), status: 'sent' }
  storage.setStorageSync(CONTACT_KEY, { version: 1, threads: { ...state.threads, [id]: { ...thread,
    messages: [...thread.messages, message],
    readIds: [...new Set([...thread.readIds, ...c.messages.filter(m => !m.fromSelf).map(m => m.id)])] } } })
}

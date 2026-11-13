import { LocalStorage } from '../profile/repository'
export interface ContactMessage { id: string; text: string; fromSelf: boolean; createdAt: number; status: 'sent' }
export interface ThreadState { messages: ContactMessage[]; readIds: string[]; clearedAt?: number }
export interface ContactState { version: 1; threads: Record<string, ThreadState> }
export const CONTACT_KEY = 'xinkelink.contact.v1'
export function readContactState(storage: LocalStorage): ContactState {
  const state = storage.getStorageSync(CONTACT_KEY) as ContactState
  if (state === undefined || state === null || (state as unknown) === '') return { version: 1, threads: {} }
  if (!state || state.version !== 1 || !state.threads || typeof state.threads !== 'object' || Array.isArray(state.threads)
    || !Object.entries(state.threads).every(([id, t]) => /^mock-[a-z0-9-]+$/.test(id) && t && Array.isArray(t.messages) && Array.isArray(t.readIds)
      && t.readIds.every(x => typeof x === 'string')
      && (t.clearedAt === undefined || Number.isFinite(t.clearedAt))
      && t.messages.every(m => m && typeof m.id === 'string' && m.id.startsWith('contact-') && typeof m.text === 'string' && !!m.text.trim() && m.text.length <= 1000
        && typeof m.fromSelf === 'boolean' && Number.isFinite(m.createdAt) && m.status === 'sent')
      && new Set(t.messages.map(m => m.id)).size === t.messages.length)) throw new Error('会话读取失败')
  return state
}
export const emptyThread = (): ThreadState => ({ messages: [], readIds: [] })

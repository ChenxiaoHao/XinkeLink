import { LocalStorage } from '../profile/repository'

export const CHAT_SETTINGS_KEY = 'xinkelink.contact.settings.v1'
export interface PeerChatSettings { muted: boolean; pinned: boolean; blocked: boolean }
interface StoredChatSettings { version: 1; peers: Record<string, PeerChatSettings> }
export const emptyPeerChatSettings = (): PeerChatSettings => ({ muted: false, pinned: false, blocked: false })

function readAll(storage: LocalStorage): StoredChatSettings {
  const raw = storage.getStorageSync(CHAT_SETTINGS_KEY) as unknown
  if (raw === undefined || raw === null || raw === '') return { version: 1, peers: {} }
  const value = raw as StoredChatSettings
  if (value.version !== 1 || !value.peers || typeof value.peers !== 'object' || Array.isArray(value.peers)
    || !Object.entries(value.peers).every(([id, setting]) => /^mock-[a-z0-9-]+$/.test(id) && setting
      && typeof setting.muted === 'boolean' && typeof setting.pinned === 'boolean' && typeof setting.blocked === 'boolean')) throw new Error('聊天设置读取失败')
  return value
}

export function readPeerChatSettings(storage: LocalStorage, peerId: string): PeerChatSettings {
  return { ...emptyPeerChatSettings(), ...(readAll(storage).peers[peerId] ?? {}) }
}

export function updatePeerChatSettings(storage: LocalStorage, peerId: string, patch: Partial<PeerChatSettings>): PeerChatSettings {
  if (!/^mock-[a-z0-9-]+$/.test(peerId)) throw new Error('会话对象无效')
  const state = readAll(storage), current = readPeerChatSettings(storage, peerId)
  for (const key of ['muted', 'pinned', 'blocked'] as const) if (patch[key] !== undefined) {
    if (typeof patch[key] !== 'boolean') throw new Error('聊天设置无效')
    current[key] = patch[key]!
  }
  storage.setStorageSync(CHAT_SETTINGS_KEY, { version: 1, peers: { ...state.peers, [peerId]: current } })
  return current
}

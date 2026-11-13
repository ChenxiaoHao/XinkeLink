import { LocalStorage, readPreferences } from '../profile/repository'
import { listConversations } from './repository'
import { readNotifications } from './notifications'
export function unreadTotal(storage: LocalStorage): number {
  const p = readPreferences(storage)
  return (p.messages ? listConversations(storage).reduce((n, c) => n + c.unread, 0) : 0)
    + readNotifications(storage).filter(n => n.unread && (n.type === 'announcement' ? p.announcements : p.interactions)).length
}

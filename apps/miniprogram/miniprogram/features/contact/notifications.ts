import { LocalStorage, localIdentity } from '../profile/repository'
import { readForum } from '../forum/repository'
import { commentAuthor, visibleComments } from '../forum/comments'
import { relativeNoticeLabel } from '../../utils/presentation'
export type NoticeType = 'comment' | 'reaction' | 'announcement'
export interface Notice { id: string; type: NoticeType; eventKind?:string; actorName: string; avatar: string; summary: string; quote: string; image: string; createdAt: number; timeLabel: string; unread: boolean; targetKind: 'post' | 'announcement'; targetId: string; detail: string; local: boolean }
export const NOTICE_KEY = 'xinkelink.notifications.v1'
// 种子通知以「打开时刻」为基准往前推，演示时才能看到「12分钟 / 1小时 / 昨天」这样的相对时间。
const seedBase = () => Date.now()
const SEEDS: Omit<Notice, 'timeLabel' | 'unread'>[] = [
  { id: 'notice-comment', type: 'comment', actorName: '林知夏', avatar: '/assets/mock/forum/avatar-lin.jpg', summary: '回复了你的动态', quote: '好的，晚点整理好笔记再分享给大家～', image: '/assets/mock/forum/campus.jpg', createdAt: seedBase() - 600_000, targetKind: 'post', targetId: 'post-001', detail: '', local: false },
  { id: 'notice-like', type: 'reaction', actorName: '大三计科阿杰', avatar: '/assets/mock/forum/avatar-zhou.jpg', summary: '赞了你的动态', quote: '觉得这份复习分享很有帮助', image: '/assets/mock/forum/desk.jpg', createdAt: seedBase() - 2_100_000, targetKind: 'post', targetId: 'post-003', detail: '', local: false },
  { id: 'notice-platform', type: 'announcement', actorName: '信科 Link 平台通知', avatar: '/assets/icons/megaphone-primary.png', summary: '校园社区交流提醒', quote: '请文明交流，保护个人隐私，不点击陌生链接。', image: '', createdAt: seedBase() - 86_400_000, targetKind: 'announcement', targetId: 'safety', detail: '分享校园生活，尊重同学并保护个人隐私。平台通知仅代表社区服务公告，非学校教务通知。', local: false },
]
function readIds(storage: LocalStorage): string[] {
  const s = storage.getStorageSync(NOTICE_KEY) as { version: number; readIds: string[] }
  if (s === undefined || s === null || (s as unknown) === '') return []
  if (!s || s.version !== 1 || !Array.isArray(s.readIds) || !s.readIds.every(id => typeof id === 'string')) throw new Error('通知读取失败')
  return s.readIds
}
export function readNotifications(storage: LocalStorage): Notice[] {
  const ids = readIds(storage), snapshot = readForum(storage), identity = localIdentity(storage)
  const local = snapshot.posts.flatMap(post => visibleComments(post, snapshot.commentState).filter(c => c.author.id === identity.id).map(c => ({
    id: 'activity-' + c.id, type: 'comment' as const, actorName: commentAuthor(post, c).name, avatar: commentAuthor(post, c).avatar,
    summary: c.parentId ? '你回复了评论' : '你发表了评论', quote: c.content, image: post.images[0] ?? '', createdAt: c.createdAt,
    targetKind: 'post' as const, targetId: post.id, detail: '', local: true,
  })))
  return [...SEEDS, ...local].map(n => ({ ...n, timeLabel: relativeNoticeLabel(n.createdAt), unread: !n.local && !ids.includes(n.id) })).sort((a, b) => b.createdAt - a.createdAt)
}
export function markNotificationRead(storage: LocalStorage, id: string): void {
  if (!readNotifications(storage).some(n => n.id === id)) throw new Error('通知不存在')
  storage.setStorageSync(NOTICE_KEY, { version: 1, readIds: [...new Set([...readIds(storage), id])] })
}
export function markAllNotificationsRead(storage: LocalStorage): void {
  storage.setStorageSync(NOTICE_KEY, { version: 1, readIds: [...new Set([...readIds(storage), ...readNotifications(storage).map(n => n.id)])] })
}

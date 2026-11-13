import { Json, compareSequence } from './client'
import { BACKEND_ORIGIN } from './config'
import { imagePadding } from '../features/media/layout'
import { detailStamp } from '../utils/presentation'
import { rememberImage } from './image-urls'
const DEFAULT_AVATAR = '/assets/icons/user-round-muted.png'
export function media(value: Json | null | undefined, origin = BACKEND_ORIGIN): string {
  if (!value?.url) return ''
  const url = value.url.startsWith('/') ? origin + value.url : value.url
  rememberImage(url, value.expiresAt)
  return url
}
export function dateLabel(value: string): string {
  const timestamp = Date.parse(value)
  if (!Number.isFinite(timestamp)) return ''
  const minutes = Math.max(0, Math.floor((Date.now() - timestamp) / 60000))
  return minutes < 1 ? '刚刚' : minutes < 60 ? minutes + '分钟前' : minutes < 1440 ? Math.floor(minutes / 60) + '小时前' : Math.floor(minutes / 1440) + '天前'
}
export function postView(dto: Json): Json {
  const images = (dto.images || []).map((item: Json) => media(item)), anonymous = dto.author.isAnonymous
  return { id: dto.id, category: dto.category === 'lost_found' ? 'lost-found' : dto.category,
    authorName: dto.author.nickname, ...(anonymous || !dto.author.id ? {} : { authorId: dto.author.id }),
    avatar: anonymous ? DEFAULT_AVATAR : media(dto.author.avatar) || DEFAULT_AVATAR, isAnonymous: anonymous,
    authorMajor: anonymous ? '' : dto.author.major || '',
    content: dto.content, images, singleImagePadding: imagePadding(dto.images?.[0]?.width, dto.images?.[0]?.height),
    imageLayout: images.length === 0 ? 'none' : images.length === 1 ? 'single' : images.length === 2 ? 'double' : images.length === 4 ? 'quad' : 'grid',
    timeLabel: dateLabel(dto.createdAt), stamp: detailStamp(Date.parse(dto.createdAt)), liked: dto.liked, collected: dto.favorited, likeCount: dto.likeCount, commentCount: dto.commentCount,
    // 后端暂无转发与浏览量，补默认值让真机视图与本地 toPostView 的字段契约一致（0 会被 wx:if 隐藏）。
    reposted: !!dto.reposted, repostCount: Number(dto.repostCount) || 0, viewCount: Number(dto.viewCount) || 0,
    version: dto.version, isMine: dto.isMine, availability: dto.availability, permissions: dto.permissions }
}
export function commentView(dto: Json, expanded = false): Json {
  return { id: dto.id, authorName: dto.author.nickname, avatar: dto.author.isAnonymous ? DEFAULT_AVATAR : media(dto.author.avatar) || DEFAULT_AVATAR,
    isAuthor: dto.isPostAuthor, isMine: dto.isMine, content: dto.content.length > 120 && !expanded ? dto.content.slice(0, 120) + '…' : dto.content,
    canExpand: dto.content.length > 120, expanded, timeLabel: dateLabel(dto.createdAt), likeCount: dto.likeCount, liked: dto.liked,
    replyToName: dto.replyTo?.name || '', version: dto.version }
}
export function messageView(dto: Json): Json {
  return { id: dto.id, sequence: dto.sequence, text: dto.visibility === 'hidden' ? '消息已不可用' : dto.text, fromSelf: dto.isMine,
    createdAt: Date.parse(dto.createdAt), status: dto.persistenceStatus, hidden: dto.visibility === 'hidden' }
}
export function mergeMessages(current: Json[], incoming: Json[]): Json[] {
  const items = new Map<string, Json>(); current.forEach(item => items.set(item.id, item)); incoming.forEach(item => items.set(item.id, item))
  return [...items.values()].sort((a, b) => compareSequence(a.sequence, b.sequence))
}
export function profileView(dto: Json, studentId = ''): Json {
  return { version: 1, nickname: dto.nickname, avatar: media(dto.avatar) || DEFAULT_AVATAR, bio: dto.bio || '', major: dto.major || '', className: dto.className || '', year: dto.enrollmentYear || '', studentId }
}
export function noticeView(dto: Json): Json {
  const actor = dto.actor?.user || dto.actor
  const reactions: Record<string, string> = { post_like: '赞了你的校园动态', comment_like: '赞了你的评论', post_favorite: '收藏了你的校园动态（历史记录）' }
  return { id: dto.id, type: dto.type, actorName: dto.eventKind === 'announcement' ? '信科 Link 公告' : dto.eventKind === 'system' ? '信科 Link 系统通知' : dto.actor?.displayName || actor?.nickname || '平台通知', avatar: media(actor?.avatar) || (dto.actor?.kind === 'user' ? DEFAULT_AVATAR : '/assets/icons/megaphone-primary.png'),
    summary: dto.target?.availability === 'unavailable' ? dto.title : reactions[dto.eventKind] || dto.title || dto.summary, quote: dto.quote || '', image: media(dto.image), createdAt: Date.parse(dto.createdAt), timeLabel: dateLabel(dto.createdAt),
    unread: !dto.readAt, targetKind: dto.target?.kind || 'announcement', targetId: dto.target?.id || '', availability: dto.target?.availability,
    detail: dto.content || dto.summary || '', local: false }
}

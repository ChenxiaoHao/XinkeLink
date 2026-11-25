import type { AdminComment, AdminPost, AdminReport, AdminUser, PostCategory, UserModerationHistoryEntry } from './admin-models'
import { rememberAdminImage } from './admin-images'

// These projections isolate the existing screen models from the documented REST DTOs.
export type Wire = Record<string, any>
export const postCategories: Record<string, PostCategory> = { campus: '校园生活', lost_found: '失物招领', study: '学习交流', help: '校园互助', secondhand: '二手闲置', complain: '吐槽', buddy: '找搭子' }
export const imageUrl = (image: Wire | null | undefined): string => rememberAdminImage(image)
const images = (values: Wire[] | undefined): string[] => (values ?? []).map(imageUrl).filter(Boolean)
const personName = (person: Wire | undefined) => person?.name ?? person?.nickname ?? person?.displayName ?? ''

export function adaptPost(dto: Wire): AdminPost {
  return { visibleCommentCount: dto.visibleCommentCount ?? 0, id: dto.id, version: dto.version, title: dto.title ?? dto.content?.slice(0, 40) ?? '', content: dto.content ?? '', category: postCategories[dto.category] ?? dto.category, status: dto.status, reviewState: dto.reviewState, createdAt: dto.createdAt, authorName: personName(dto.author), authorId: dto.author?.id ?? '', authorAvatar: imageUrl(dto.author?.avatar), images: images(dto.images), likes: dto.likeCount ?? 0 }
}
export function adaptComment(dto: Wire): AdminComment {
  return { id: dto.id, version: dto.version, postId: dto.targetType === 'post' || dto.linkedTarget?.type === 'post' ? dto.targetId ?? dto.linkedTarget?.id : null, business: 'forum', linkedTitle: dto.linkedTarget?.title ?? '', authorName: personName(dto.author), authorId: dto.author?.id ?? '', authorAvatar: imageUrl(dto.author?.avatar), content: dto.content ?? '', createdAt: dto.createdAt, status: dto.status, parentId: dto.rootId ?? null, likes: dto.likeCount ?? 0 }
}
export function adaptReport(dto: Wire): AdminReport {
  return { targetAvailability: dto.type === 'user' && dto.targetUser?.status ? dto.targetUser.status : dto.target?.availability ?? 'unavailable', snapshot: { title: dto.targetSnapshot?.title ?? dto.target?.title ?? '', content: dto.targetSnapshot?.content ?? dto.targetSnapshot?.description ?? dto.targetSnapshot?.text ?? dto.targetSnapshot?.bio ?? '', images: images(dto.targetSnapshot?.images) }, id: dto.id, version: dto.version, type: dto.type, priority: dto.priority, status: dto.status, reason: dto.reasonCode, description: dto.description ?? '', createdAt: dto.createdAt, reporterName: personName(dto.reporter), reporterId: dto.reporter?.id ?? '', targetId: dto.target?.id ?? '', targetName: personName(dto.targetUser), targetUserId: dto.targetUser?.id ?? '', targetTitle: dto.target?.title ?? '', evidence: images(dto.evidence), resolution: dto.resolution ?? null, resolutionReason: dto.resolutionReason ?? '', processedAt: dto.processedAt ?? null, history: (dto.history ?? []).map((h: Wire) => ({ action: h.action, reason: h.reason ?? '', processedAt: h.createdAt ?? h.processedAt, operator: typeof h.operator === 'string' ? h.operator : personName(h.operator) })) }
}
export function adaptHistory(h: Wire): UserModerationHistoryEntry { return { action: h.action, reason: h.reason ?? '', createdAt: h.createdAt, operator: typeof h.operator === 'string' ? h.operator : personName(h.operator), source: h.source } }
export function adaptUser(dto: Wire): AdminUser {
  return { contentCounts: dto.contentCounts ?? dto.counts ?? null, reportCount: dto.reportCount ?? null, id: dto.id, version: dto.version, name: dto.name ?? dto.nickname, avatar: imageUrl(dto.avatar), school: '', major: dto.major ?? '', className: dto.className ?? '', enrollmentYear: dto.enrollmentYear ?? '', joinedAt: dto.joinedAt, lastActiveAt: dto.lastActiveAt ?? null, status: dto.status, restrictions: dto.restrictions ?? { publish: false, comment: false, message: false }, banUntil: dto.banUntil ?? null, history: (dto.history ?? []).map(adaptHistory) }
}


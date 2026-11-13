export type ModerationStatus = 'visible' | 'hidden'
export type PostCategory = '校园生活' | '失物招领' | '学习交流' | '校园互助'
export type PostPeriod = 'all' | '7d' | '30d'
export type CommentLevel = 'all' | 'root' | 'reply'
export type ModeratedBusiness = 'all' | 'forum'

export interface AdminPost {
  visibleCommentCount: number
  version: number
  id: string
  title: string
  content: string
  category: PostCategory
  status: ModerationStatus
  reviewState: 'normal' | 'pending'
  createdAt: string
  authorName: string
  authorId: string
  authorAvatar: string
  images: string[]
  likes: number
}

export interface AdminComment {
  version: number
  id: string
  postId: string | null
  business: 'forum'
  linkedTitle: string
  authorName: string
  authorId: string
  authorAvatar: string
  content: string
  createdAt: string
  status: ModerationStatus
  parentId: string | null
  likes: number
}

export type ReportType = 'post' | 'comment' | 'user' | 'message'
export type ReportPriority = 'high' | 'medium' | 'low'
export type ReportStatus = 'pending' | 'processed'
export type ReportResolution = 'reject' | 'hide_post' | 'hide_comment' | 'restrict_user' | 'warn_user' | 'hide_message'


export interface ReportHistoryEntry {
  action: ReportResolution
  reason: string
  processedAt: string
  operator: string
}

export interface AdminReport {
  targetAvailability: string
  snapshot: { title: string; content: string; images: string[] }
  version: number
  id: string
  type: ReportType
  priority: ReportPriority
  status: ReportStatus
  reason: string
  description: string
  createdAt: string
  reporterName: string
  reporterId: string
  targetId: string
  targetName: string
  targetUserId: string
  targetTitle: string
  evidence: string[]
  resolution: ReportResolution | null
  resolutionReason: string
  processedAt: string | null
  history: ReportHistoryEntry[]
}

export type UserRestrictionChannel = 'publish' | 'comment' | 'message'
export type UserStatus = 'active' | 'restricted' | 'banned' | 'permanently_banned'
export type UserModerationAction = 'restrict_publish' | 'restrict_comment' | 'restrict_message' | 'release_publish' | 'release_comment' | 'release_message' | 'lift_restrictions' | 'temporary_ban' | 'temporary_ban_expired' | 'permanent_ban' | 'report_restriction' | 'report_warning'

export interface UserModerationHistoryEntry {
  action: UserModerationAction
  reason: string
  createdAt: string
  operator: string
  source: 'user_management' | 'report' | 'system'
}

export interface AdminUser {
  contentCounts?: { posts: number; comments: number } | null
  reportCount?: number | null
  version: number
  id: string
  name: string
  avatar: string
  school: string
  major: string
  className: string
  enrollmentYear: string
  joinedAt: string
  lastActiveAt: string | null
  status: UserStatus
  restrictions: Record<UserRestrictionChannel, boolean>
  banUntil: string | null
  history: UserModerationHistoryEntry[]
}

export interface QueryPage<T> { items: T[]; total: number; page: number; pageSize: number }
export interface PostFilters { keyword?: string; category?: PostCategory | 'all'; status?: ModerationStatus | 'all'; period?: PostPeriod }
export interface CommentFilters { keyword?: string; level?: CommentLevel; status?: ModerationStatus | 'all'; business?: ModeratedBusiness }
export interface ReportFilters { keyword?: string; type?: ReportType | 'all'; priority?: ReportPriority | 'all'; status?: ReportStatus | 'all'; period?: 'all' | '7d' | '30d' }
export interface UserFilters { keyword?: string; status?: UserStatus | 'all'; restriction?: UserRestrictionChannel | 'all' }


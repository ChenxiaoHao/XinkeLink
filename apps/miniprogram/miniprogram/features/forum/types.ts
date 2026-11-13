export type PostCategory = 'campus' | 'lost-found' | 'secondhand' | 'complain' | 'help' | 'buddy' | 'study'
export type FeedCategory = 'recommended' | 'latest' | PostCategory

// 本地演示模式下「我」的作者 id。放在无依赖的 types 里，避免 feed / repository 互相 import。
export const LOCAL_AUTHOR_ID = 'local-student'

// label 用于筛选器、选择器与无障碍文案；short 用于首页顶部的横滑 tab。
// 八个板块按用户口径统一：推荐 / 最新 / 失物认领 / 二手闲置 / 校园生活 / 吐槽 / 互帮互助 / 找搭子。
export const FORUM_CATEGORIES: { key: FeedCategory; label: string; short: string }[] = [
  { key: 'recommended', label: '推荐', short: '推荐' },
  { key: 'latest', label: '最新', short: '最新' },
  { key: 'lost-found', label: '失物认领', short: '失物认领' },
  { key: 'secondhand', label: '二手闲置', short: '二手闲置' },
  { key: 'campus', label: '校园生活', short: '校园生活' },
  { key: 'complain', label: '吐槽', short: '吐槽' },
  { key: 'help', label: '互帮互助', short: '互帮互助' },
  { key: 'buddy', label: '找搭子', short: '找搭子' },
]

// Raw mock records stay outside page data. Anonymous authors are projected out.
export interface MockPost {
  id: string
  category: PostCategory
  author: { id: string; nickname: string; avatar: string }
  anonymousNumber?: string
  createdAt: number
  recommendation: number
  content: string
  images: string[]
  likes: number
  comments: number
  /** 被转发次数基线；本地互动只在此之上做 +/-1。 */
  reposts?: number
  initiallyCollected?: boolean
}

export interface PostInteraction { liked: boolean; collected: boolean; reposted: boolean }
export interface ForumInteractions {
  version: 1
  posts: Record<string, PostInteraction>
}

export interface ForumPostView {
  id: string
  category: PostCategory
  authorName: string
  authorMajor?: string
  authorId?: string
  avatar: string
  isAnonymous: boolean
  timeLabel: string
  /** 详情页时间行：下午 2:31 · 2026年11月12日。列表页用 timeLabel 的相对口径。 */
  stamp?: string
  content: string
  images: string[]
  imageLayout: 'none' | 'single' | 'double' | 'quad' | 'grid'
  singleImagePadding?: number
  liked: boolean
  collected: boolean
  reposted: boolean
  likeCount: number
  commentCount: number
  repostCount: number
  isMine?: boolean
  version?: number
}

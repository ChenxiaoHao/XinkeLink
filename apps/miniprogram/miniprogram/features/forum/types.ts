export type PostCategory = 'campus' | 'lost-found' | 'study' | 'help'
export type FeedCategory = 'recommended' | 'latest' | PostCategory

export const FORUM_CATEGORIES: { key: FeedCategory; label: string }[] = [
  { key: 'recommended', label: '推荐' },
  { key: 'latest', label: '最新' },
  { key: 'campus', label: '校园生活' },
  { key: 'lost-found', label: '失物招领' },
  { key: 'study', label: '学习交流' },
  { key: 'help', label: '校园互助' },
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
  initiallyCollected?: boolean
}

export interface PostInteraction { liked: boolean; collected: boolean }
export interface ForumInteractions {
  version: 1
  posts: Record<string, PostInteraction>
}

export interface ForumPostView {
  id: string
  category: PostCategory
  authorName: string
  avatar: string
  isAnonymous: boolean
  timeLabel: string
  content: string
  images: string[]
  imageLayout: 'none' | 'single' | 'double' | 'quad' | 'grid'
  liked: boolean
  collected: boolean
  likeCount: number
  commentCount: number
}

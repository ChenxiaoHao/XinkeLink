import { FeedCategory, ForumInteractions, ForumPostView, MockPost, PostInteraction } from './types'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function normalizeInteractions(value: unknown): ForumInteractions {
  const result: ForumInteractions = { version: 1, posts: {} }
  if (!isRecord(value) || value.version !== 1 || !isRecord(value.posts)) return result
  for (const [id, entry] of Object.entries(value.posts)) {
    if (!/^post-\d{3}$/.test(id) || !isRecord(entry)) continue
    if (typeof entry.liked === 'boolean' && typeof entry.collected === 'boolean') {
      result.posts[id] = { liked: entry.liked, collected: entry.collected }
    }
  }
  return result
}

function interactionFor(post: MockPost, state: ForumInteractions): PostInteraction {
  return state.posts[post.id] ?? { liked: false, collected: post.initiallyCollected ?? false }
}

export function toggleInteraction(
  posts: readonly MockPost[], state: ForumInteractions, id: string, field: keyof PostInteraction,
): ForumInteractions {
  const post = posts.find(item => item.id === id)
  if (!post) return state
  const current = interactionFor(post, state)
  return { version: 1, posts: { ...state.posts, [id]: { ...current, [field]: !current[field] } } }
}

function timeLabel(createdAt: number): string {
  const minutes = Math.max(0, Math.floor((Date.now() - createdAt) / 60_000))
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return minutes + '分钟前'
  if (minutes < 1440) return Math.floor(minutes / 60) + '小时前'
  return Math.floor(minutes / 1440) + '天前'
}

export function toPostView(post: MockPost, state: ForumInteractions): ForumPostView {
  const current = interactionFor(post, state)
  const count = post.images.length
  // Explicit projection prevents private author fields leaking into setData/share.
  return {
    id: post.id, category: post.category,
    authorName: post.anonymousNumber ? '匿名同学 ' + post.anonymousNumber : post.author.nickname,
    avatar: post.anonymousNumber ? '/assets/icons/user-round-muted.png' : post.author.avatar,
    isAnonymous: Boolean(post.anonymousNumber), timeLabel: timeLabel(post.createdAt),
    content: post.content, images: [...post.images],
    imageLayout: count === 0 ? 'none' : count === 1 ? 'single' : count === 2 ? 'double' : count === 4 ? 'quad' : 'grid',
    liked: current.liked, collected: current.collected,
    likeCount: post.likes + (current.liked ? 1 : 0), commentCount: post.comments,
  }
}

export function queryFeed(
  posts: readonly MockPost[],
  options: { category: FeedCategory; offset: number; limit: number; keyword: string },
  state: ForumInteractions,
): { items: ForumPostView[]; nextOffset: number; hasMore: boolean } {
  const keyword = options.keyword.trim().toLowerCase()
  const filtered = posts.filter(post => {
    if (options.category !== 'recommended' && options.category !== 'latest' && post.category !== options.category) return false
    const name = post.anonymousNumber ? '匿名同学 ' + post.anonymousNumber : post.author.nickname
    return !keyword || (post.content + name).toLowerCase().includes(keyword)
  }).sort((a, b) => options.category === 'latest'
    ? b.createdAt - a.createdAt : b.recommendation - a.recommendation)
  const offset = Math.max(0, options.offset)
  const items = filtered.slice(offset, offset + Math.max(0, options.limit))
  const nextOffset = Math.min(offset + items.length, filtered.length)
  return { items: items.map(post => toPostView(post, state)), nextOffset, hasMore: nextOffset < filtered.length }
}

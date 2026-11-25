import { normalizeInteractions, toggleInteraction } from './feed'
import { MOCK_POSTS } from './mock-posts'
import { ForumInteractions, MockPost, PostInteraction, LOCAL_AUTHOR_ID } from './types'
import { COMMENT_STORAGE_KEY, CommentState, ForumComment, parseCommentState, visibleComments } from './comments'
import { PostDraft, validatePostDraft } from './publish'
import { localIdentity, readLocalSession, requireLocalSession } from '../profile/repository'

export const POST_STORAGE_KEY = 'xinkelink.forum.posts.v2'
export const INTERACTION_STORAGE_KEY = 'xinkelink.forum.interactions.v1'
export { LOCAL_AUTHOR_ID } from './types'
export interface ForumStorage { getStorageSync(key: string): unknown; setStorageSync(key: string, value: unknown): void }
const unset = (value: unknown): boolean => value === undefined || value === null || value === ''
function isLocalPost(value: unknown): value is MockPost {
  if (!value || typeof value !== 'object') return false
  const post = value as MockPost
  return /^local-post-[a-z0-9-]+$/.test(post.id) && ['campus', 'lost-found', 'secondhand', 'complain', 'help', 'buddy', 'study'].includes(post.category)
    && typeof post.content === 'string' && !!post.content.trim() && post.content.length <= 2000
    && Array.isArray(post.images) && post.images.length <= 9 && post.images.every(image => typeof image === 'string' && !!image)
    && Number.isFinite(post.createdAt) && Number.isFinite(post.recommendation)
    && Number.isInteger(post.likes) && post.likes >= 0 && Number.isInteger(post.comments) && post.comments >= 0
    && !!post.author && post.author.id === LOCAL_AUTHOR_ID && typeof post.author.nickname === 'string' && typeof post.author.avatar === 'string'
    && (post.anonymousNumber === undefined || (typeof post.anonymousNumber === 'string' && /^\d{3,}$/.test(post.anonymousNumber)))
}
export function readLocalPosts(storage: ForumStorage): MockPost[] {
  const value = storage.getStorageSync(POST_STORAGE_KEY)
  if (unset(value)) return []
  if (!Array.isArray(value) || !value.every(isLocalPost) || new Set(value.map(post => post.id)).size !== value.length) throw new Error('帖子读取失败')
  return value
}
export function readForum(storage: ForumStorage): { posts: MockPost[]; interactions: ForumInteractions; commentState: CommentState } {
  const savedComments = parseCommentState(storage.getStorageSync(COMMENT_STORAGE_KEY)), identity = localIdentity(storage)
  // 访客是干净的旁观视角：不展示上一个登录账号的点赞/收藏/评论点赞（与真机模式按 JWT 会话隔离一致）。
  const loggedIn = readLocalSession(storage)
  const commentState = { ...savedComments, added: savedComments.added.map(comment => comment.author.id === LOCAL_AUTHOR_ID ? { ...comment, author: identity } : comment), ...(loggedIn ? {} : { likes: {} }) }
  const posts = [...readLocalPosts(storage), ...MOCK_POSTS].map(post => ({ ...post, author: post.author.id === LOCAL_AUTHOR_ID ? identity : post.author, comments: visibleComments(post, commentState).length }))
  const raw = loggedIn ? storage.getStorageSync(INTERACTION_STORAGE_KEY) : undefined
  const interactions = normalizeInteractions(raw)
  if (!unset(raw)) {
    if (!raw || typeof raw !== 'object' || (raw as ForumInteractions).version !== 1 || !(raw as ForumInteractions).posts
      || Array.isArray((raw as ForumInteractions).posts) || typeof (raw as ForumInteractions).posts !== 'object'
      || Object.keys((raw as ForumInteractions).posts).length !== Object.keys(interactions.posts).length) throw new Error('互动读取失败')
  }
  return { posts, interactions, commentState }
}
export function togglePostInteraction(storage: ForumStorage, id: string, field: keyof PostInteraction): void {
  if (field === 'reposted') throw new Error('转发功能即将上线')
  // 访客不能互动：弹一键登录（各页面的点赞入口统一在这里拦截）。
  if (!requireLocalSession(storage)) return
  const snapshot = readForum(storage)
  if (!snapshot.posts.some(post => post.id === id)) throw new Error('帖子不存在')
  storage.setStorageSync(INTERACTION_STORAGE_KEY, toggleInteraction(snapshot.posts, snapshot.interactions, id, field))
}
function commentContext(storage: ForumStorage, postId: string) {
  const snapshot = readForum(storage), post = snapshot.posts.find(item => item.id === postId)
  if (!post) throw new Error('帖子不存在')
  return { post, state: snapshot.commentState, comments: visibleComments(post, snapshot.commentState) }
}
export function addForumComment(storage: ForumStorage, postId: string, body: string, replyToId?: string): string {
  const content = body.trim()
  if (!content || content.length > 1000) throw new Error('请输入 1 至 1000 字评论')
  const { state, comments } = commentContext(storage, postId), target = comments.find(comment => comment.id === replyToId)
  if (replyToId && !target) throw new Error('回复已不存在')
  const id = 'local-comment-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
  if (comments.some(comment => comment.id === id)) throw new Error('请重试')
  const comment: ForumComment = { id, postId, content, createdAt: Date.now(), likes: 0,
    author: localIdentity(storage),
    ...(target ? { parentId: target.parentId ?? target.id, replyToId: target.id } : {}) }
  storage.setStorageSync(COMMENT_STORAGE_KEY, { ...state, added: [...state.added, comment] })
  return id
}
export function toggleForumCommentLike(storage: ForumStorage, postId: string, id: string): void {
  if (!requireLocalSession(storage)) return
  const { state, comments } = commentContext(storage, postId)
  if (!comments.some(comment => comment.id === id)) throw new Error('评论不存在')
  storage.setStorageSync(COMMENT_STORAGE_KEY, { ...state, likes: { ...state.likes, [id]: !state.likes[id] } })
}
export function deleteOwnForumComment(storage: ForumStorage, postId: string, id: string): void {
  const { state, comments } = commentContext(storage, postId), target = comments.find(comment => comment.id === id)
  if (!target || target.author.id !== LOCAL_AUTHOR_ID) throw new Error('不能删除此评论')
  const ids = comments.filter(comment => comment.id === id || comment.parentId === id).map(comment => comment.id)
  storage.setStorageSync(COMMENT_STORAGE_KEY, { ...state, deleted: [...new Set([...state.deleted, ...ids])] })
}
export function publishForumPost(storage: ForumStorage, form: PostDraft, images: string[]): MockPost {
  const error = validatePostDraft({ ...form, images })
  if (error) throw new Error(error)
  const existing = readLocalPosts(storage), now = Date.now()
  const id = 'local-post-' + now.toString(36) + '-' + Math.random().toString(36).slice(2, 10)
  if (existing.some(post => post.id === id)) throw new Error('请重试')
  const post: MockPost = { id, category: form.category, content: form.content.trim(), images: [...images],
    author: localIdentity(storage),
    ...(form.anonymous ? { anonymousNumber: String(Math.floor(Math.random() * 900) + 100) } : {}),
    createdAt: now, recommendation: now, likes: 0, comments: 0 }
  storage.setStorageSync(POST_STORAGE_KEY, [post, ...existing])
  return post
}
/** 保留原 id、匿名身份、评论与点赞，只更新正文/分类/图片。 */
export function updateOwnPost(storage: ForumStorage, id: string, form: PostDraft, images: string[]): MockPost {
  const error = validatePostDraft({ ...form, images })
  if (error) throw new Error(error)
  const posts = readLocalPosts(storage), index = posts.findIndex(item => item.id === id)
  if (index < 0 || posts[index].author.id !== LOCAL_AUTHOR_ID) throw new Error('不能编辑此帖子')
  const previous = posts[index]
  const updated: MockPost = {
    ...previous, category: form.category, content: form.content.trim(), images: [...images],
    anonymousNumber: previous.anonymousNumber,
  }
  const next = [...posts]; next[index] = updated
  storage.setStorageSync(POST_STORAGE_KEY, next)
  return updated
}
export function deleteOwnPost(storage: ForumStorage, id: string): void {
  const posts = readLocalPosts(storage), post = posts.find(item => item.id === id)
  if (!post || post.author.id !== LOCAL_AUTHOR_ID) throw new Error('不能删除此帖子')
  storage.setStorageSync(POST_STORAGE_KEY, posts.filter(item => item.id !== id))
}

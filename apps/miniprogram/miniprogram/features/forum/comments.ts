import { MOCK_POSTS } from './mock-posts'
import { MockPost } from './types'

export interface ForumComment {
  id: string; postId: string; author: MockPost['author']; content: string; createdAt: number; likes: number
  parentId?: string; replyToId?: string
}
export interface CommentState { version: 1; added: ForumComment[]; deleted: string[]; likes: Record<string, boolean> }
export interface CommentView {
  id: string; authorName: string; avatar: string; isAuthor: boolean; isMine: boolean; content: string
  canExpand: boolean; expanded: boolean; timeLabel: string; likeCount: number; liked: boolean; replyToName: string
}
export interface CommentThread extends CommentView { replies: CommentView[]; replyCount: number; repliesExpanded: boolean }
export const COMMENT_STORAGE_KEY = 'xinkelink.forum.comments.v1'
const localId = 'local-student'
const participants = [
  { id: 'mock-zhou', nickname: '周同学', avatar: '/assets/mock/forum/avatar-zhou.jpg' },
  { id: 'mock-ajie', nickname: '大三计科阿杰', avatar: '/assets/mock/forum/avatar-lin.jpg' },
  { id: 'mock-su', nickname: '苏小禾', avatar: '/assets/mock/forum/avatar-lin.jpg' },
]
const messages = [
  '五楼靠窗位置确实永远的神，夕阳太治愈了！',
  '求一个复习思维导图！一起加油呀。',
  '好的，晚点整理好笔记再分享给大家～',
  '谢谢分享，复习加油！',
  '期待笔记，也祝大家考试顺利。',
  '大家都要顺顺利利通过考试呀，加油冲冲冲！',
  '我一般会先按章节整理知识点，再把不熟悉的例题放在一起复习。每天留一点时间回看前一天的内容，遇到不懂的地方及时和同学讨论。图书馆的学习氛围很好，记得带上水杯，坐久了也要起身活动一下。希望大家都能按自己的节奏完成复习，我们一起慢慢积累，互相鼓励。',
  '已记下，感谢同学分享。',
]
// Complete records preserve the homepage's existing mock comment totals.
const MOCK_COMMENTS: ForumComment[] = MOCK_POSTS.flatMap(post => Array.from({ length: post.comments }, (_, index) => ({
  id: post.id + '-comment-' + index, postId: post.id,
  author: index === 2 ? post.author : participants[index % participants.length],
  content: messages[index % messages.length], createdAt: post.createdAt + Math.min(index, 2) * 60_000,
  likes: index % 5, ...(index >= 2 && index <= 4 ? { parentId: post.id + '-comment-1', replyToId: post.id + '-comment-1' } : {}),
})))
export function emptyCommentState(): CommentState { return { version: 1, added: [], deleted: [], likes: {} } }
export function parseCommentState(value: unknown): CommentState {
  if (value === undefined || value === null || value === '') return emptyCommentState()
  const state = value as CommentState
  if (!state || typeof state !== 'object' || state.version !== 1 || !Array.isArray(state.added) || !Array.isArray(state.deleted)
    || !state.likes || typeof state.likes !== 'object' || Array.isArray(state.likes)
    || !Object.values(state.likes).every(liked => typeof liked === 'boolean')
    || !state.deleted.every(id => typeof id === 'string')
    || !state.added.every(comment => comment && typeof comment === 'object' && /^local-comment-[a-z0-9-]+$/.test(comment.id)
      && typeof comment.postId === 'string' && typeof comment.content === 'string' && !!comment.content.trim() && comment.content.length <= 1000
      && Number.isFinite(comment.createdAt) && comment.likes === 0 && comment.author?.id === localId
      && typeof comment.author.nickname === 'string' && typeof comment.author.avatar === 'string'
      && (comment.parentId === undefined || typeof comment.parentId === 'string')
      && (comment.replyToId === undefined || typeof comment.replyToId === 'string'))
    || new Set(state.added.map(comment => comment.id)).size !== state.added.length) throw new Error('评论读取失败')
  return state
}
export function visibleComments(post: MockPost, state: CommentState): ForumComment[] {
  const records = [...MOCK_COMMENTS.filter(comment => comment.postId === post.id), ...state.added.filter(comment => comment.postId === post.id)]
    .filter(comment => !state.deleted.includes(comment.id))
  const roots = new Set(records.filter(comment => !comment.parentId).map(comment => comment.id))
  return records.filter(comment => !comment.parentId || roots.has(comment.parentId))
}
export function commentAuthor(post: MockPost, comment: ForumComment): { name: string; avatar: string } {
  return post.anonymousNumber && post.author.id === comment.author.id
    ? { name: '匿名同学 ' + post.anonymousNumber, avatar: '/assets/icons/user-round-muted.png' }
    : { name: comment.author.nickname, avatar: comment.author.avatar }
}
function commentView(post: MockPost, comment: ForumComment, all: ForumComment[], state: CommentState, expanded: string[]): CommentView {
  const author = commentAuthor(post, comment), target = all.find(item => item.id === comment.replyToId)
  const minutes = Math.max(0, Math.floor((Date.now() - comment.createdAt) / 60_000)), isExpanded = expanded.includes(comment.id)
  return {
    id: comment.id, authorName: author.name, avatar: author.avatar, isAuthor: comment.author.id === post.author.id,
    isMine: comment.author.id === localId, content: comment.content.length > 120 && !isExpanded ? comment.content.slice(0, 120) + '…' : comment.content,
    canExpand: comment.content.length > 120, expanded: isExpanded,
    timeLabel: minutes < 1 ? '刚刚' : minutes < 60 ? minutes + '分钟前' : minutes < 1440 ? Math.floor(minutes / 60) + '小时前' : Math.floor(minutes / 1440) + '天前',
    liked: state.likes[comment.id] ?? false, likeCount: comment.likes + (state.likes[comment.id] ? 1 : 0),
    replyToName: target ? commentAuthor(post, target).name : '',
  }
}
export function commentThreads(post: MockPost, state: CommentState, expanded: string[], openedReplies: string[]): CommentThread[] {
  const all = visibleComments(post, state)
  return all.filter(comment => !comment.parentId).sort((a, b) => b.createdAt - a.createdAt || b.id.localeCompare(a.id)).map(comment => {
    const replies = all.filter(item => item.parentId === comment.id).sort((a, b) => a.createdAt - b.createdAt)
    const isOpen = openedReplies.includes(comment.id)
    return { ...commentView(post, comment, all, state, expanded), replyCount: replies.length, repliesExpanded: isOpen,
      replies: (isOpen ? replies : replies.slice(0, 1)).map(item => commentView(post, item, all, state, expanded)) }
  })
}

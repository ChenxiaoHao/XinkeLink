import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useContentModerationStore } from './fixtures/content-moderation'
import { useAdminAuditStore } from './fixtures/admin-audit'
import { vi } from 'vitest'

describe('content moderation demo data', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('keeps post and two-level comment context linked by IDs', () => {
    const store = useContentModerationStore()
    const post = store.posts[0]
    const thread = store.commentsForPost(post.id)

    expect(post.id).toBeTruthy()
    expect(thread.length).toBeGreaterThan(0)
    expect(thread.every(comment => comment.postId === post.id)).toBe(true)
    expect(thread.some(comment => comment.parentId !== null)).toBe(true)
  })

  it('filters and paginates posts using keyword, category, status, and date range', () => {
    const store = useContentModerationStore()
    const result = store.queryPosts({ keyword: '图书馆', category: '学习交流', status: 'visible', period: '30d' }, 1, 10)

    expect(result.total).toBeGreaterThan(0)
    expect(result.items.every(post => post.title.includes('图书馆') || post.content.includes('图书馆'))).toBe(true)
    expect(result.items.every(post => post.category === '学习交流' && post.status === 'visible')).toBe(true)
  })

  it('hides and restores posts while derived statistics follow the state', () => {
    const store = useContentModerationStore()
    const post = store.posts.find(item => item.status === 'visible')!
    const visibleBefore = store.postStats.visible

    store.setPostStatus([post.id], 'hidden')
    expect(store.postById(post.id)?.status).toBe('hidden')
    expect(store.postStats.visible).toBe(visibleBefore - 1)
    expect(store.postStats.hidden).toBeGreaterThan(0)

    store.setPostStatus([post.id], 'visible')
    expect(store.postStats.visible).toBe(visibleBefore)
  })

  it('removes a deleted post and its comments without leaving orphaned context', () => {
    const store = useContentModerationStore()
    const post = store.posts[0]
    const commentIds = store.commentsForPost(post.id).map(comment => comment.id)

    store.deletePosts([post.id])

    expect(store.postById(post.id)).toBeUndefined()
    expect(store.commentsForPost(post.id)).toHaveLength(0)
    expect(store.comments.some(comment => commentIds.includes(comment.id))).toBe(false)
  })

  it('filters comments by hierarchy, state, linked business, and keyword', () => {
    const store = useContentModerationStore()
    const result = store.queryComments({ keyword: '复习', level: 'reply', status: 'visible', business: 'forum' }, 1, 20)

    expect(result.total).toBeGreaterThan(0)
    expect(result.items.every(comment => comment.parentId !== null && comment.status === 'visible' && comment.business === 'forum')).toBe(true)
  })

  it('updates comment context and counts when comments are hidden, restored, or deleted', () => {
    const store = useContentModerationStore()
    const comment = store.comments.find(item => item.status === 'visible' && store.repliesFor(item.id).length === 0)!
    const postId = comment.postId!
    const before = store.visibleCommentCount(postId)

    store.setCommentStatus([comment.id], 'hidden')
    expect(store.visibleCommentCount(postId)).toBe(before - 1)
    expect(store.commentById(comment.id)?.status).toBe('hidden')

    store.setCommentStatus([comment.id], 'visible')
    expect(store.visibleCommentCount(postId)).toBe(before)

    store.deleteComments([comment.id])
    expect(store.commentById(comment.id)).toBeUndefined()
    expect(store.visibleCommentCount(postId)).toBe(before - 1)
  })

  it('records one safe event for an actual content mutation and a business rejection for a no-op', () => {
    const moderation = useContentModerationStore()
    const audit = useAdminAuditStore()
    const post = moderation.posts.find(item => item.status === 'visible')!

    moderation.setPostStatus([post.id], 'hidden')
    moderation.setPostStatus([post.id], 'hidden')

    expect(audit.events).toHaveLength(2)
    expect(audit.events.map(event => event.outcome)).toEqual(['business_rejected', 'success'])
    expect(audit.events.every(event => event.module === 'posts')).toBe(true)
    expect(JSON.stringify(audit.events)).not.toContain(post.content)
    expect(JSON.stringify(audit.events)).not.toContain(post.authorId)
  })

  it('records a report action once even when it changes linked post state', () => {
    const moderation = useContentModerationStore()
    const audit = useAdminAuditStore()
    const report = moderation.reports.find(item => item.type === 'post' && item.status === 'pending')!

    expect(moderation.processReport(report.id, 'hide_post', '内部处理原因，不应写入审计')).toBe(true)
    expect(moderation.postById(report.targetId)?.status).toBe('hidden')
    expect(moderation.reportById(report.id)?.status).toBe('processed')
    expect(audit.events).toHaveLength(1)
    expect(audit.events[0]).toMatchObject({ module: 'reports', action: 'hide_post', outcome: 'success' })
    expect(JSON.stringify(audit.events)).not.toContain('内部处理原因')
    expect(JSON.stringify(audit.events)).not.toContain(report.targetUserId)
  })

  it('does not let a failed audit writer change a successful business result', () => {
    const moderation = useContentModerationStore()
    const audit = useAdminAuditStore()
    const post = moderation.posts.find(item => item.status === 'visible')!
    vi.spyOn(audit, 'record').mockImplementation(() => { throw new Error('audit unavailable') })

    moderation.setPostStatus([post.id], 'hidden')
    expect(moderation.postById(post.id)?.status).toBe('hidden')
  })

  it('records business refusal and execution error as different outcomes', () => {
    const moderation = useContentModerationStore()
    const audit = useAdminAuditStore()
    moderation.setPostStatus(['missing-post'], 'hidden')

    const post = moderation.posts.find(item => item.status === 'visible')!
    Object.freeze(post)
    expect(() => moderation.setPostStatus([post.id], 'hidden')).toThrow()
    expect(audit.events.map(event => event.outcome)).toEqual(['execution_error', 'business_rejected'])
  })
})

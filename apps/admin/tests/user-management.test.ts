import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useContentModerationStore } from './fixtures/content-moderation'

describe('user management and shared moderation state', () => {
  beforeEach(() => setActivePinia(createPinia()))
  afterEach(() => vi.useRealTimers())

  it('queries users with activity counts derived from posts and comments', () => {
    const store = useContentModerationStore()
    const user = store.queryUsers({ keyword: '林知夏' }, 1, 10).items[0]
    const activity = store.userActivity(user.id)

    expect(user).toBeTruthy()
    expect(activity.posts.length).toBeGreaterThan(0)
    expect(activity.comments.length).toBeGreaterThan(0)
    expect(store.queryUsers({ keyword: 'not-a-real-user' }, 1, 10).total).toBe(0)
    expect(store.queryUsers({}, 2, 5).items.length).toBeGreaterThan(0)
  })

  it('requires a reason and records independently controlled restrictions', () => {
    const store = useContentModerationStore()
    const userId = '2022****312'

    expect(store.setUserRestriction(userId, 'publish', true, '  ')).toBe(false)
    expect(store.setUserRestriction(userId, 'publish', true, '发布内容需复核')).toBe(true)
    expect(store.userById(userId)?.restrictions.publish).toBe(true)
    expect(store.userById(userId)?.status).toBe('restricted')
    expect(store.setUserRestriction(userId, 'publish', false, '已完成复核')).toBe(true)
    expect(store.userById(userId)?.status).toBe('active')
    expect(store.userById(userId)?.history).toHaveLength(2)
    expect(store.userById(userId)?.history[0].action).toBe('release_publish')
  })

  it('synchronizes report restrictions to the same user record', () => {
    const store = useContentModerationStore()
    const report = store.reportById('R-2026102204')!

    expect(store.processReport(report.id, 'restrict_user', '重复发布推广内容')).toBe(true)
    expect(store.userById(report.targetUserId)?.status).toBe('restricted')
    expect(store.restrictedUsers).toContain(report.targetUserId)
    expect(store.userById(report.targetUserId)?.history[0].source).toBe('report')
  })

  it('does not let restriction or report handling accidentally restore a temporarily banned user', () => {
    const store = useContentModerationStore()
    const userId = '2024****183'

    expect(store.banUser(userId, 7, '多次违反社区规范')).toBe(true)
    expect(store.userById(userId)?.status).toBe('banned')
    expect(store.setUserRestriction(userId, 'publish', false, '误点解除')).toBe(false)
    expect(store.processReport('R-2026102204', 'restrict_user', '举报复核后限制')).toBe(true)
    expect(store.userById(userId)?.status).toBe('banned')
  })

  it('allows a deliberate, reasoned release of a temporary ban', () => {
    const store = useContentModerationStore()
    const userId = '2022****312'

    expect(store.banUser(userId, 7, '短期封禁测试')).toBe(true)
    expect(store.liftUserRestrictions(userId, '复核后提前解除')).toBe(true)
    expect(store.userById(userId)?.status).toBe('active')
    expect(store.userById(userId)?.banUntil).toBeNull()
  })

  it('automatically releases a temporary ban after its deadline and records the expiry', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-23T12:00:00.000Z'))
    const store = useContentModerationStore()
    const userId = '2022****312'

    expect(store.banUser(userId, 7, '限期封禁测试')).toBe(true)
    vi.advanceTimersByTime(7 * 24 * 60 * 60 * 1000 + 1)
    expect(store.userById(userId)?.status).toBe('active')
    expect(store.userById(userId)?.restrictions).toEqual({ publish: false, comment: false, message: false })
    expect(store.userById(userId)?.history[0].action).toBe('temporary_ban_expired')
    vi.useRealTimers()
  })

  it('keeps permanent bans irreversible and prevents report handling from downgrading them', () => {
    const store = useContentModerationStore()
    const userId = '2024****183'

    expect(store.banUser(userId, 'permanent', '严重违规')).toBe(true)
    expect(store.liftUserRestrictions(userId, '不应解除')).toBe(false)
    expect(store.setUserRestriction(userId, 'message', false, '不应恢复私信')).toBe(false)
    expect(store.userById(userId)?.status).toBe('permanently_banned')

    expect(store.processReport('R-2026102204', 'restrict_user', '举报处理同步')).toBe(true)
    expect(store.userById(userId)?.status).toBe('permanently_banned')
    expect(store.userById(userId)?.restrictions).toEqual({ publish: true, comment: true, message: true })
  })
})

import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useContentModerationStore } from './fixtures/content-moderation'

describe('report management demo data', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('synchronizes report decisions with shared post and comment moderation state', () => {
    const store = useContentModerationStore()
    const postReport = store.reports.find(item => item.type === 'post' && item.status === 'pending')!
    const commentReport = store.reports.find(item => item.type === 'comment' && item.status === 'pending')!

    expect(store.processReport(postReport.id, 'hide_post', '包含不适宜内容')).toBe(true)
    expect(store.postById(postReport.targetId)?.status).toBe('hidden')
    expect(store.processReport(commentReport.id, 'hide_comment', '评论涉及人身攻击')).toBe(true)
    expect(store.commentById(commentReport.targetId)?.status).toBe('hidden')
  })

  it('requires a handling reason and supports report filters and pagination', () => {
    const store = useContentModerationStore()
    const report = store.reports.find(item => item.type === 'post' && item.status === 'pending')!
    expect(store.processReport(report.id, 'hide_post', '   ')).toBe(false)
    expect(store.reportById(report.id)?.status).toBe('pending')

    const result = store.queryReports({ type: 'post', priority: 'high', status: 'pending' }, 1, 2)
    expect(result.items.length).toBeLessThanOrEqual(2)
    expect(result.items.every(item => item.type === 'post' && item.priority === 'high' && item.status === 'pending')).toBe(true)
  })
})

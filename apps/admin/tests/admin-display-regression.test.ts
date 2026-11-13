import { describe, expect, it } from 'vitest'
import { adaptUser } from '../src/services/admin-adapters'

describe('管理列表真实计数', () => {
  it('直接保留服务端聚合的发布数量，不依赖打开用户档案', () => {
    const user = adaptUser({ id: '9', name: '同学', contentCounts: { posts: 12, comments: 18 }, reportCount: 2 })
    expect(user).toMatchObject({ contentCounts: { posts: 12, comments: 18 }, reportCount: 2 })
  })

  it('没有计数字段时保留未知状态，不将尚未加载的数据当作零', () => {
    expect(adaptUser({ id: '9' })).toMatchObject({ contentCounts: null, reportCount: null })
  })

  it('真实零值仍然保留', () => {
    expect(adaptUser({ id: '9', contentCounts: { posts: 0, comments: 0 }, reportCount: 0 }))
      .toMatchObject({ contentCounts: { posts: 0, comments: 0 }, reportCount: 0 })
  })
})

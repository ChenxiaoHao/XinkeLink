import { describe, expect, it } from 'vitest'
import { adaptPost, postCategories } from '../src/services/admin-adapters'

describe('forum category contract', () => {
  it.each([['secondhand', '二手闲置'], ['complain', '吐槽'], ['buddy', '找搭子']])('maps %s for moderation and filtering', (key, label) => {
    expect(postCategories[key]).toBe(label)
    expect(adaptPost({ id: '1', category: key }).category).toBe(label)
    expect(Object.keys(postCategories).find(candidate => postCategories[candidate] === label)).toBe(key)
  })
  it('retains historical study without exposing sort orders as categories', () => {
    expect(postCategories.study).toBe('学习交流')
    expect(postCategories.recommended).toBeUndefined()
    expect(postCategories.latest).toBeUndefined()
  })
})

import { LocalStorage } from '../profile/repository'
import { readPrivateValue, writePrivateValue } from '../profile/private-storage'

export type SearchType = 'posts'
export interface SearchHistoryItem { query: string; type: SearchType; searchedAt: number }
export const SEARCH_HISTORY_KEY = 'xinkelink.search.history.v1'
const HISTORY_LIMIT = 8

function validType(value: unknown): value is SearchType { return value === 'posts' }

export function readSearchHistory(storage: LocalStorage): SearchHistoryItem[] {
  try {
    const raw = readPrivateValue(storage, SEARCH_HISTORY_KEY)
    if (raw === '') return []
    const value = raw as { version?: unknown; items?: unknown } | undefined
    if (value === undefined || value === null) return []
    if (value.version !== 1 || !Array.isArray(value.items)) throw new Error('搜索历史读取失败')
    return value.items.filter((item): item is SearchHistoryItem => !!item && typeof item.query === 'string'
      && item.query.trim().length > 0 && validType(item.type) && Number.isFinite(item.searchedAt))
      .slice(0, HISTORY_LIMIT)
  } catch { return [] }
}

export function addSearchHistory(storage: LocalStorage, query: string, type: SearchType): SearchHistoryItem[] {
  const normalized = query.trim().slice(0, 60)
  if (!normalized) return readSearchHistory(storage)
  const items = [{ query: normalized, type, searchedAt: Date.now() },
    ...readSearchHistory(storage).filter(item => item.query !== normalized || item.type !== type)].slice(0, HISTORY_LIMIT)
  return writePrivateValue(storage, SEARCH_HISTORY_KEY, { version: 1, items }) ? items : []
}

export function removeSearchHistory(storage: LocalStorage, query: string, type: SearchType): SearchHistoryItem[] {
  const target = query.trim()
  if (!target) return readSearchHistory(storage)
  const items = readSearchHistory(storage).filter(item => item.query !== target || item.type !== type)
  return writePrivateValue(storage, SEARCH_HISTORY_KEY, { version: 1, items }) ? items : []
}

export function clearSearchHistory(storage: LocalStorage): void {
  writePrivateValue(storage, SEARCH_HISTORY_KEY, { version: 1, items: [] })
}

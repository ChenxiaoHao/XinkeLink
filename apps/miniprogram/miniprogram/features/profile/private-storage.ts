import { client, Runtime } from '../../backend/client'
import { LocalStorage } from './repository'
import { getViewerRole } from './viewer'

/** Legacy unowned keys belong only to the single fixture identity, never a live account. */
export function privateStorageKey(storage: LocalStorage, key: string): string | null {
  const runtime = storage as unknown as typeof wx
  if (getViewerRole(runtime) === 'guest') return null
  if ((runtime as Runtime).__fixtureMode === true) return key
  const id = client(runtime).me?.id
  return typeof id === 'string' && id ? key + ':user:' + encodeURIComponent(id) : null
}
export function readPrivateValue(storage: LocalStorage, key: string): unknown {
  const scoped = privateStorageKey(storage, key)
  return scoped === null ? undefined : storage.getStorageSync(scoped)
}
export function writePrivateValue(storage: LocalStorage, key: string, value: unknown): boolean {
  const scoped = privateStorageKey(storage, key)
  if (scoped === null) return false
  storage.setStorageSync(scoped, value)
  return true
}

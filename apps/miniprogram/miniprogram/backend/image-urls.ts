import { API_PREFIX, BACKEND_ORIGIN } from './config'

const expirations = new Map<string, number>()

export function backendImageId(source: string): string | undefined {
  const prefix = BACKEND_ORIGIN + API_PREFIX + '/images/'
  return source.startsWith(prefix) ? source.slice(prefix.length).match(/^([1-9]\d*)\/content(?:\?|$)/)?.[1] : undefined
}

export function rememberImage(source: string, expiresAt?: string): void {
  if (!backendImageId(source)) return
  const expiry = expiresAt ? Date.parse(expiresAt) : NaN
  if (Number.isFinite(expiry)) expirations.set(source, expiry)
  if (expirations.size > 2000) expirations.delete(expirations.keys().next().value!)
}

export function imageDue(source: string, now = Date.now()): boolean {
  return !!backendImageId(source) && (expirations.get(source) ?? 0) <= now + 15000
}

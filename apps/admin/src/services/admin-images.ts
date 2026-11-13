import { adminHttp } from './admin-http'

const expirations = new Map<string, number>()
const pending = new Map<string, Promise<string>>()
const imageId = (source: string) => source.match(/^\/api\/v1\/images\/([1-9]\d*)\/content(?:\?|$)/)?.[1]

export function rememberAdminImage(image: { url?: string; expiresAt?: string } | null | undefined): string {
  const url = image?.url ?? '', expiry = Date.parse(image?.expiresAt ?? '')
  if (imageId(url) && Number.isFinite(expiry)) expirations.set(url, expiry)
  if (expirations.size > 2000) expirations.delete(expirations.keys().next().value!)
  return url
}

export function adminImageDue(source: string, now = Date.now()): boolean {
  return !!imageId(source) && (expirations.get(source) ?? 0) <= now + 15000
}

export function renewAdminImage(source: string): Promise<string> {
  const id = imageId(source)
  if (!id) return Promise.resolve(source)
  const key = `${adminHttp.admin.value?.id ?? ''}:${id}`
  if (!pending.has(key)) pending.set(key, adminHttp.imageAccess(id).then(image => {
    if (imageId(image.url) !== id) throw new Error('图片地址无效')
    return rememberAdminImage(image)
  }).finally(() => pending.delete(key)))
  return pending.get(key)!
}

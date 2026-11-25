import { ApiClient, ApiError, client, Json, Runtime } from './client'
import { media, profileView } from './adapters'
import { backendImageId, imageDue } from './image-urls'

const flights = new WeakMap<Runtime, Map<string, Promise<string>>>()

function renewImage(runtime: Runtime, id: string): Promise<string> {
  const api = client(runtime)
  const key = JSON.stringify([api.me?.id ?? null, id])
  let pending = flights.get(runtime)
  if (!pending) { pending = new Map(); flights.set(runtime, pending) }
  if (!pending.has(key)) pending.set(key, api.request('/images/' + id + '/access').then(value => {
    const url = media(value)
    if (backendImageId(url) !== id) throw new Error('图片地址无效')
    return url
  }).finally(() => pending!.delete(key)))
  return pending.get(key)!
}

type SessionAvatar = { owner: Json; imageId: string; url: string; renewal?: Promise<string> }
const sessionAvatars = new WeakMap<ApiClient, SessionAvatar>()

export function sessionAvatar(api: ApiClient): { url: string; renewal?: Promise<string> } {
  const owner = api.me
  if (!api.loggedIn || !owner) return { url: '' }
  const source = profileView(owner).avatar
  const imageId = backendImageId(source)
  if (!imageId || !imageDue(source)) return { url: source }
  let avatar = sessionAvatars.get(api)
  if (!avatar || avatar.owner !== owner || avatar.imageId !== imageId) {
    avatar = { owner, imageId, url: '' }
    sessionAvatars.set(api, avatar)
  }
  if (avatar.url && !imageDue(avatar.url)) return { url: avatar.url }
  const current = avatar
  current.renewal ??= renewImage(api.runtime, imageId).then(url => {
    if (api.me !== owner) {
      const latest = media(api.me?.avatar)
      return api.loggedIn && api.me?.id === owner.id && backendImageId(latest) === imageId && !imageDue(latest) ? latest : ''
    }
    if (sessionAvatars.get(api) !== current || imageDue(url)) return ''
    current.url = url
    return url
  }).catch(() => '').finally(() => { current.renewal = undefined })
  return { url: '', renewal: current.renewal }
}

export function needsPreviewRefresh(runtime: unknown, sources: string[]): boolean {
  return (runtime as Runtime).__fixtureMode !== true && sources.some(source => backendImageId(source))
}

export function imageForDisplay(runtime: Runtime, source: string): Promise<string> {
  const id = backendImageId(source)
  return id && imageDue(source) ? renewImage(runtime, id) : Promise.resolve(source)
}

/** 预览会重新下载图片，先续取权限地址；拒绝访问时不能回退旧签名。 */
export async function freshPreviewImages(runtime: unknown, sources: string[]): Promise<string[]> {
  const renewed = new Map<string, Promise<string>>()
  return Promise.all(sources.map(source => {
    const id = backendImageId(source)
    if (!id) return Promise.resolve(source)
    if (!renewed.has(id)) renewed.set(id, renewImage(runtime as Runtime, id))
    return renewed.get(id)!
  }))
}

const localImageFlights = new WeakMap<Runtime, Map<string, Promise<string>>>()

/** 平板等设备无法用原生 image 读取内网地址时，重新鉴权后通过 request 保存到小程序本地目录。 */
export function loadAuthorizedLocalImage(value: unknown, source: string): Promise<string> {
  const runtime = value as Runtime & {
    env?: { USER_DATA_PATH?: string }
    getFileSystemManager?: () => { writeFile(options: { filePath: string; data: ArrayBuffer; success(): void; fail(error: unknown): void }): void }
  }
  const id = backendImageId(source)
  if (!id || !runtime?.request || (!runtime.downloadFile && !runtime.getFileSystemManager) || !runtime.env?.USER_DATA_PATH) {
    return Promise.reject(new ApiError('IMAGE_LOAD_FAILED', '图片暂时无法加载'))
  }
  const api = client(runtime), owner = api.me?.id
  const key = JSON.stringify([owner ?? null, id])
  const current = () => api.me?.id === owner
  let pending = localImageFlights.get(runtime)
  if (!pending) { pending = new Map(); localImageFlights.set(runtime, pending) }
  if (!pending.has(key)) pending.set(key, (async () => {
    try {
      // 每次回退都先向服务端核验当前用户仍有权查看这张图片。
      const [url] = await freshPreviewImages(runtime, [source])
      if (!current()) throw new Error('图片查看账号已变化')
      const filePath = runtime.env!.USER_DATA_PATH + '/xinkelink-image-' + id + '-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8) + '.png'
      // 真机优先使用微信下载器，避免不同设备对二进制 request 的兼容差异。
      if (runtime.downloadFile) {
        try {
          const local = await new Promise<string>((resolve, reject) => runtime.downloadFile!({
            url, timeout: 15000,
            success: (result: { statusCode: number; filePath?: string; tempFilePath?: string }) => {
              const path = result.filePath || result.tempFilePath
              if (result.statusCode === 200 && path) resolve(path)
              else reject(new Error('图片下载响应无效'))
            },
            fail: () => reject(new Error('图片下载失败')),
          }))
          if (!current()) throw new Error('图片查看账号已变化')
          return local
        } catch (error) { if (!current() || !runtime.getFileSystemManager) throw error }
      }
      const bytes = await new Promise<ArrayBuffer>((resolve, reject) => runtime.request({
        url, method: 'GET', responseType: 'arraybuffer', timeout: 15000,
        success: (result: { statusCode: number; data: unknown }) => {
          if (result.statusCode === 200 && result.data instanceof ArrayBuffer) resolve(result.data)
          else reject(new Error('图片响应无效'))
        },
        fail: () => reject(new Error('图片请求失败')),
      }))
      if (!current()) throw new Error('图片查看账号已变化')
      await new Promise<void>((resolve, reject) => runtime.getFileSystemManager!().writeFile({
        filePath, data: bytes, success: resolve, fail: reject,
      }))
      if (!current()) throw new Error('图片查看账号已变化')
      return filePath
    } catch {
      // 不把临时 grant 或底层网络错误带到日志和界面中。
      throw new ApiError('IMAGE_LOAD_FAILED', '图片暂时无法加载')
    }
  })().finally(() => pending!.delete(key)))
  return pending.get(key)!
}

/** 页面显示时检查一次即将过期的图片地址；图片报错时仍可按需续期。 */
export class PageImageRenewal {
  private stopped = true
  private generation = 0
  private warned = false
  constructor(private runtime: Runtime, private page: { data: Json; setData(patch: Json): void }) {}

  async start(): Promise<void> {
    this.stop(); this.stopped = false
    await this.refresh(this.generation)
  }
  stop(): void {
    this.stopped = true; this.generation++
  }
  private async refresh(generation: number): Promise<void> {
    const api = client(this.runtime), userId = api.me?.id
    const targets = new Map<string, { path: string; source: string }[]>()
    const scan = (value: any, path: string): void => {
      if (typeof value === 'string') {
        // 正文、标题、搜索词即使恰好是图片链接，也属于用户内容，不能改写。
        if (!/(^|\.)(avatar|selfAvatar|peerAvatar|authorAvatar|sellerAvatar|avatarPath|image|cover|images\[\d+\]|evidence\[\d+\])$/.test(path)) return
        const id = backendImageId(value)
        if (id && imageDue(value)) {
          if (!targets.has(id)) targets.set(id, [])
          targets.get(id)!.push({ path, source: value })
        }
      } else if (Array.isArray(value)) value.forEach((item, index) => scan(item, path + '[' + index + ']'))
      else if (value && typeof value === 'object') Object.keys(value).forEach(key => scan(value[key], path ? path + '.' + key : key))
    }
    scan(this.page.data, '')
    const changes: { path: string; source: string; renewed: string }[] = []
    await Promise.all([...targets].map(async ([id, values]) => {
      let renewed: string
      try { renewed = await renewImage(this.runtime, id) }
      catch (error) {
        if (this.stopped || generation !== this.generation || api.me?.id !== userId) return
        if (!this.warned) { this.runtime.showToast?.({ title: '部分图片暂时无法加载，请稍后重试', icon: 'none' }); this.warned = true }
        if (!(error instanceof ApiError) || ![401, 403, 404].includes(error.status)) return
        renewed = '' // 权限已撤回时清除图片，不继续展示旧授权地址。
      }
      for (const { path, source } of values) changes.push({ path, source, renewed })
    }))
    if (this.stopped || generation !== this.generation || api.me?.id !== userId) return
    const patch: Json = {}
    for (const { path, source, renewed } of changes) {
      const current = path.replace(/\[(\d+)\]/g, '.$1').split('.').reduce((value: any, key) => value?.[key], this.page.data)
      if (current === source) patch[path] = renewed
    }
    if (Object.keys(patch).length) this.page.setData(patch)
  }
}

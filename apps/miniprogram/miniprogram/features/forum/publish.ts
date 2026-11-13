import { FORUM_CATEGORIES, PostCategory } from './types'
export interface PostDraft { content: string; category: PostCategory; anonymous: boolean; images: string[] }
export const emptyPostDraft = (): PostDraft => ({ content: '', category: 'campus', anonymous: false, images: [] })
export function validatePostDraft(form: PostDraft): string | null {
  if (typeof form.content !== 'string' || !form.content.trim() || form.content.length > 2000) return '请填写 1 至 2000 字正文'
  // 'study' 是历史分类，仅兼容旧数据校验；发布选择器不再提供。
  if (!FORUM_CATEGORIES.some(item => !['recommended', 'latest'].includes(item.key) && item.key === form.category) && form.category !== 'study') return '请选择帖子分类'
  if (typeof form.anonymous !== 'boolean') return '请确认匿名设置'
  if (!Array.isArray(form.images) || form.images.length > 9 || form.images.some(image => typeof image !== 'string' || !image)) return '最多选择 9 张图片'
  return null
}
type FileApi = Pick<typeof wx, 'env' | 'getFileSystemManager'>
export function releaseForumImages(api: FileApi, paths: string[]): void {
  for (const path of paths) {
    if (!path.startsWith(api.env.USER_DATA_PATH + '/xlk-forum-upload-')) continue
    try { api.getFileSystemManager().unlinkSync(path) } catch { /* Keep original save failure. */ }
  }
}
export function persistForumImages(api: FileApi, images: string[]): { images: string[]; created: string[] } {
  const created: string[] = [], prefix = api.env.USER_DATA_PATH + '/xlk-forum-upload-'
  const batch = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
  try {
    return { images: images.map((source, index) => {
      if (source.startsWith(prefix)) return source
      const extension = source.match(/\.(png|jpe?g|webp|gif)$/i)?.[1] ?? 'jpg'
      const destination = prefix + batch + '-' + index + '.' + extension
      api.getFileSystemManager().copyFileSync(source, destination); created.push(destination); return destination
    }), created }
  } catch (error) { releaseForumImages(api, created); throw error }
}

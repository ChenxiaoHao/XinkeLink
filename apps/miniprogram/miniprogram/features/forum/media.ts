import { freshPreviewImages, needsPreviewRefresh } from '../../backend/preview'
export async function previewForumImages(api: Pick<typeof wx, 'previewImage' | 'env' | 'getFileSystemManager' | 'showToast'>, images: string[], index: number): Promise<void> {
  if (!Number.isInteger(index) || !images[index]) return
  const failure = (): void => { api.showToast({ title: '图片暂时无法预览，请重试', icon: 'none' }) }
  try {
    const sources = needsPreviewRefresh(api, images) ? await freshPreviewImages(api, images) : images
    const urls = sources.map(source => {
      if (!source.startsWith('/assets/mock/forum/')) return source
      const destination = api.env.USER_DATA_PATH + '/xlk-forum-preview-' + source.split('/').pop()
      api.getFileSystemManager().copyFileSync(source, destination)
      return destination
    })
    api.previewImage({ current: urls[index], urls, fail: failure })
  } catch { failure() }
}

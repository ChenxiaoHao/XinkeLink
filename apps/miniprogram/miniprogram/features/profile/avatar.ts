import { defaultProfile, readProfile, saveProfile, LocalStorage } from './repository'

type FileApi = Pick<typeof wx, 'env' | 'getFileSystemManager'>
type RepairApi = FileApi & LocalStorage

export function releaseProfileAvatar(api: FileApi, path: string): void {
  if (!path.startsWith(api.env.USER_DATA_PATH + '/xlk-profile-avatar-')) return
  try { api.getFileSystemManager().unlinkSync(path) } catch { /* Preserve the original save failure. */ }
}
export function persistProfileAvatar(api: FileApi, source: string): { avatar: string; created: string } {
  const prefix = api.env.USER_DATA_PATH + '/xlk-profile-avatar-'
  if (source.startsWith('/assets/') || source.startsWith(prefix)) return { avatar: source, created: '' }
  const extension = source.match(/\.(png|jpe?g|webp|gif)$/i)?.[1] ?? 'jpg'
  const destination = prefix + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10) + '.' + extension
  api.getFileSystemManager().copyFileSync(source, destination)
  return { avatar: destination, created: destination }
}

/**
 * 自愈历史数据：早期抽屉登录把 chooseAvatar 的临时路径（http://tmp/... / wxfile://...）
 * 直接写进了 profile，临时目录被清理后头像就破图了。启动时把这类地址迁移到永久目录；
 * 临时文件已不存在时回退到内置头像，保证页头/抽屉/个人页都不会出现破图。
 */
export function repairTransientAvatar(api: RepairApi): void {
  try {
    const profile = readProfile(api)
    const prefix = api.env.USER_DATA_PATH + '/xlk-profile-avatar-'
    if (profile.avatar.startsWith('/assets/') || profile.avatar.startsWith(prefix)) return
    let avatar: string
    try { avatar = persistProfileAvatar(api, profile.avatar).avatar }
    catch { avatar = defaultProfile().avatar }
    saveProfile(api, { ...profile, avatar })
  } catch { /* 存储或文件系统不可用时保持原状，交给 image 的 binderror 兜底。 */ }
}

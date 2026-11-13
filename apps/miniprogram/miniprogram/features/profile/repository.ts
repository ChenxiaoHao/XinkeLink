import { emitIdentityChange } from './identity-events'

export interface LocalStorage { getStorageSync(key: string): unknown; setStorageSync(key: string, value: unknown): void }
export interface UserProfile { version: 1; nickname: string; avatar: string; bio: string; major: string; className: string; studentId: string; year: string }
export interface Preferences { version: 1; messages: boolean; interactions: boolean; announcements: boolean; sound: boolean }
export const PROFILE_KEY = 'xinkelink.profile.v1'
export const PREFERENCES_KEY = 'xinkelink.preferences.v1'
export const SESSION_KEY = 'xinkelink.session.v1'
const unset = (v: unknown): boolean => v === undefined || v === null || v === ''
export const defaultProfile = (): UserProfile => ({ version: 1, nickname: '陈林舟', avatar: '/assets/mock/forum/avatar-lin.jpg', bio: '写代码，拍晚霞，分享校园生活', major: '软件工程', className: '', studentId: '', year: '2022' })
function validProfile(value: unknown): value is UserProfile {
  if (!value || typeof value !== 'object') return false
  const p = value as UserProfile
  return p.version === 1 && typeof p.nickname === 'string' && !!p.nickname.trim() && p.nickname.length <= 20
    && typeof p.avatar === 'string' && !!p.avatar && typeof p.bio === 'string' && p.bio.length <= 40
    && typeof p.major === 'string' && p.major.length <= 60 && typeof p.className === 'string' && p.className.length <= 40
    && typeof p.studentId === 'string' && p.studentId.length <= 32
    && typeof p.year === 'string' && (p.year === '' || /^(19|20)\d{2}$/.test(p.year))
}
function isLegacyProfile(value: unknown): value is { version: 1; nickname: string; avatar: string; bio: string; school: string; major: string; year: string } {
  if (!value || typeof value !== 'object') return false
  const p = value as Record<string, unknown>
  return p.version === 1 && typeof p.nickname === 'string' && !!p.nickname.trim() && p.nickname.length <= 20
    && typeof p.avatar === 'string' && !!p.avatar && typeof p.bio === 'string' && p.bio.length <= 40
    && typeof p.school === 'string' && p.school.length <= 40 && typeof p.major === 'string' && p.major.length <= 60
    && typeof p.year === 'string' && (p.year === '' || /^(19|20)\d{2}$/.test(p.year))
}
export function readProfile(storage: LocalStorage): UserProfile {
  const p = storage.getStorageSync(PROFILE_KEY)
  if (unset(p)) return defaultProfile()
  if (isLegacyProfile(p)) return { version: 1, nickname: p.nickname, avatar: p.avatar, bio: p.bio, major: p.major, className: '', studentId: '', year: p.year }
  if (!validProfile(p)) throw new Error('资料读取失败')
  return { ...p }
}
export function saveProfile(storage: LocalStorage, profile: UserProfile): void {
  readProfile(storage)
  const p = { version: 1 as const, nickname: profile.nickname.trim(), avatar: profile.avatar, bio: profile.bio.trim(), major: profile.major.trim(), className: profile.className.trim(), studentId: profile.studentId.trim(), year: profile.year.trim() }
  if (!validProfile(p)) throw new Error('请检查昵称、简介、专业、班级、学号和入学年份')
  storage.setStorageSync(PROFILE_KEY, p)
  // 资料（含头像）变更后通知页头等展示组件，不依赖页面 onShow。
  emitIdentityChange()
}
export function localIdentity(storage: LocalStorage): { id: string; nickname: string; avatar: string } {
  let p: UserProfile
  try { p = readProfile(storage) } catch { p = defaultProfile() }
  return { id: 'local-student', nickname: p.nickname, avatar: p.avatar }
}
export function readPreferences(storage: LocalStorage): Preferences {
  const p = storage.getStorageSync(PREFERENCES_KEY) as Preferences
  if (unset(p)) return { version: 1, messages: true, interactions: true, announcements: true, sound: false }
  if (!p || p.version !== 1 || !['messages', 'interactions', 'announcements', 'sound'].every(k => typeof p[k as keyof Preferences] === 'boolean')) throw new Error('设置读取失败')
  return { ...p }
}
export function updatePreferences(storage: LocalStorage, patch: Partial<Omit<Preferences, 'version'>>): void {
  const p = readPreferences(storage)
  for (const k of ['messages', 'interactions', 'announcements', 'sound'] as const) if (patch[k] !== undefined) {
    if (typeof patch[k] !== 'boolean') throw new Error('设置无效')
    p[k] = patch[k]!
  }
  storage.setStorageSync(PREFERENCES_KEY, p)
}
export function readLocalSession(storage: LocalStorage): boolean {
  const value = storage.getStorageSync(SESSION_KEY)
  if (unset(value)) return true
  if (typeof value !== 'boolean') throw new Error('本地会话读取失败')
  return value
}
export function setLocalSession(storage: LocalStorage, loggedIn: boolean): void {
  readLocalSession(storage)
  storage.setStorageSync(SESSION_KEY, loggedIn)
  // 抽屉登录、访客一键登录、退出登录都收敛到这里，统一广播一次。
  emitIdentityChange()
}
export function requireLocalSession(api: LocalStorage & Partial<Pick<typeof wx, 'showModal' | 'showToast' | 'navigateTo'>>): boolean {
  try {
    if (readLocalSession(api)) return true
    api.showModal?.({ title: '当前为访客浏览', content: '登录后可发帖、私信与互动，之后无需再次登录。', confirmText: '一键登录', success: result => {
      if (result.confirm) setLocalSession(api, true)
    } })
  } catch { api.showToast?.({ title: '本地会话读取失败，请重试', icon: 'none' }) }
  return false
}

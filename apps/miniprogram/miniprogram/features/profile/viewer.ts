import { client, Runtime } from '../../backend/client'
import { LOCAL_AUTHOR_ID } from '../forum/types'
import { readLocalSession } from './repository'

export type ViewerRole = 'guest' | 'self' | 'other'
/**
 * 统一视角判定（规则见 IDENTITY-RULES.md §0）：
 * - guest：未登录（authorId 不参与判定，访客永远不是 self）。
 * - self：已登录且 authorId 是本机身份（后端模式为当前会话用户）。
 * - other：已登录但不是本人，或未提供 authorId。
 * 任何读取失败或不确定时一律按访客处理（安全侧回退）。
 */
export function getViewerRole(runtime: typeof wx, authorId?: string): ViewerRole {
  try {
    if ((runtime as Runtime).__fixtureMode === true) {
      if (!readLocalSession(runtime)) return 'guest'
      return authorId !== undefined && authorId === LOCAL_AUTHOR_ID ? 'self' : 'other'
    }
    const api = client(runtime)
    if (!api.loggedIn) return 'guest'
    return authorId !== undefined && authorId === api.me?.id ? 'self' : 'other'
  } catch { return 'guest' }
}

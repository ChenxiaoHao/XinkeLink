import { Runtime } from './client'

/**
 * 本地演示模式判定。
 *
 * 每个页面的 index.ts 都保留一份完整的本地实现（MOCK_POSTS + 本地 storage），
 * backend/pages.ts 的 registerPage 只在 __fixtureMode 不为 true 时才替换成后端版本。
 * 因此只要在开发者工具里打开这个开关，不启动 Java 服务也能浏览全部界面。
 *
 * 关键：判定必须发生在**任何页面模块执行 registerPage 之前**。
 * 微信开发者工具有时会先求值入口页模块再执行 app.ts，所以这段逻辑挂在
 * backend/pages.ts 的导入链上（每个页面都 import 它），而不是只写在 app.ts。
 */
const MODE_STORAGE_KEY = 'xinkelink.mode'

function platforms(): string[] {
  const seen: string[] = []
  try {
    if (typeof wx.getDeviceInfo === 'function') seen.push(wx.getDeviceInfo()?.platform || '')
  } catch { /* 旧基础库可能没有该接口。 */ }
  try { seen.push(wx.getSystemInfoSync()?.platform || '') } catch { /* 忽略 */ }
  return seen
}

export function localDemoEnabled(): boolean {
  try {
    const mode = wx.getStorageSync(MODE_STORAGE_KEY)
    if (mode === 'live') return false
    if (mode === 'local') return true
  } catch { /* 存储不可用时按平台判断。 */ }
  return platforms().indexOf('devtools') >= 0
}

// 模块顶层执行：导入即生效，且只跑一次。
// 单元测试在 Node 里跑，没有 wx 全局对象，这里必须先用 typeof 探一下再碰。
if (typeof wx !== 'undefined' && (wx as Runtime).__fixtureMode !== true && localDemoEnabled()) {
  (wx as Runtime).__fixtureMode = true
}

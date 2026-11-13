/**
 * 身份变更广播（演示模式 / 真机模式通用）。
 *
 * 登录、退出、改资料经常发生在「当前页面内」（账号抽屉、访客引导弹窗、资料编辑页），
 * 不会触发页面 onShow，因此只靠 pageLifetimes.show 读身份的组件会一直停留在旧状态，
 * 表现为「登录成功后个人页有头像、其它页面左上角还是默认占位」。
 *
 * 小程序里模块是单例，这里用模块级订阅表把「写入方」和「展示方」解耦：
 * 写入集中在 features/profile/repository.ts，展示方（page-header 等）自行订阅。
 */
type Listener = () => void

const listeners = new Set<Listener>()

/** 订阅身份变更，返回取消订阅函数。 */
export function onIdentityChange(listener: Listener): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

/** 身份写入完成后调用；单个订阅者异常不影响其它组件刷新。 */
export function emitIdentityChange(): void {
  listeners.forEach(listener => {
    try { listener() } catch { /* 单个组件刷新失败不影响其它订阅者。 */ }
  })
}

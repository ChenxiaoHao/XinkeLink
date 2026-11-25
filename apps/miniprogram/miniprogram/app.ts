// 本地演示模式的判定在 backend/demo-mode.ts，这里导入即生效，
// 保证即使入口页比 app.ts 先求值，页面也已经走本地实现。
import './backend/demo-mode'
import { repairTransientAvatar } from './features/profile/avatar'

App({
  onLaunch() {
    // 老版本抽屉登录会把 chooseAvatar 的临时路径直接存档，重启后头像必破图，这里迁移一次。
    try { if (typeof wx !== 'undefined') repairTransientAvatar(wx) } catch { /* 迁移失败不影响启动。 */ }
    // 微信基础库隐私授权回调：触发相册、头像、剪贴板等隐私接口时弹出明确确认。
    try {
      const runtime = wx as unknown as {
        onNeedPrivacyAuthorization?: (cb: (resolve: (result: { event: 'agree' | 'disagree' }) => void) => void) => void
      }
      if (typeof wx !== 'undefined' && typeof runtime.onNeedPrivacyAuthorization === 'function') {
        runtime.onNeedPrivacyAuthorization((resolve) => {
          wx.showModal({
            title: '隐私保护提示',
            content: '在使用头像选择、图片上传或复制功能前，请阅读并同意《信科 Link 隐私政策》与微信隐私保护指引。',
            confirmText: '同意',
            cancelText: '暂不同意',
            success(res) {
              resolve({ event: res.confirm ? 'agree' : 'disagree' })
            },
            fail() {
              resolve({ event: 'disagree' })
            },
          })
        })
      }
    } catch { /* 旧版基础库或测试环境忽略。 */ }
  },
});

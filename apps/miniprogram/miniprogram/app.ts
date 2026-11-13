// 本地演示模式的判定在 backend/demo-mode.ts，这里导入即生效，
// 保证即使入口页比 app.ts 先求值，页面也已经走本地实现。
import './backend/demo-mode'
import { repairTransientAvatar } from './features/profile/avatar'

App({
  onLaunch() {
    // 老版本抽屉登录会把 chooseAvatar 的临时路径直接存档，重启后头像必破图，这里迁移一次。
    try { if (typeof wx !== 'undefined') repairTransientAvatar(wx) } catch { /* 迁移失败不影响启动。 */ }
  },
});

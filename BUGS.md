# 演示模式（wx.__fixtureMode === true）只读审查报告

- 日期：2026-11-13；范围：`apps/miniprogram/miniprogram/pages/` 下 17 个已注册页面（仅演示模式，不涉及后端）。
- 方法：脚本机械比对每页 wxml 全部事件绑定（含 `bind:event` 组件事件）与页面本地实现（index.ts + 同目录 controller.ts），再人工核对 `backend/pages.ts`、共享控制器与空态/守卫代码。
- 结论：无高危死链；双提交守卫（forum-publish/report/feedback/profile-edit/my-posts/chat-settings）齐全；主要问题集中在"wxml 是两套模式共用、演示模式缺 handler/状态字段"这一已知模式。
- **修复状态（2026-11-13）**：第 1、2、3 条已修复（contact-chat 发送锁 + contact/notifications 头像兜底，见 commit "fix: demo-mode sending lock and avatar error fallback"）；第 4、5、6 条未修复。
- 已排除的误报：notifications 的 `onFilterTap/onGuestLogin/onRetryTap/onNotificationTap` 位于共享的 `pages/contact/controller.ts`（notifications/index.ts 经 `inbox()` 工厂复用），非死链；search 的 `onLoadMoreTap` 为注释说明的刻意空实现且 `postHasMore` 恒 false、按钮不可达。

| 编号 | 页面/文件 | 问题描述 | 证据（文件+行号） | 严重程度 | 把握 |
|---|---|---|---|---|---|
| 1 | contact-chat ✅已修复 | 演示模式发送流程缺少 `sending` 状态：wxml 的"发送中…"进度条、placeholder 分支、输入框/按钮的 `disabled={{… \|\| sending}}` 全部失效（`sending` 恒 undefined）；发送也没有在途锁，目前仅靠"发送后清空 input"防止连发 | `pages/contact-chat/index.wxml:23-24`（绑定 `{{sending}}`）；`pages/contact-chat/index.ts` 全文无 `sending` 字段/赋值；`backend/pages.ts:210,477`（仅真机模式设置） | 中 | 确定 |
| 2 | contact ✅已修复 | 会话头像 `binderror="onRemoteAvatarError"` 是演示模式死链：该方法只存在于真机桥接层，contact 的本地实现（index.ts + controller.ts）都没有；头像加载失败时无兜底 | `pages/contact/index.wxml:19`（绑定）；`pages/contact/controller.ts` 全文无该方法；`backend/pages.ts:75`（真机实现） | 低 | 确定（绑定缺失） |
| 3 | notifications ✅已修复 | 同上：通知头像 `binderror="onRemoteAvatarError"` 在演示模式无对应实现，破图无兜底 | `pages/notifications/index.wxml:21`（绑定）；`pages/notifications/index.ts` 仅 3 行转发；`backend/pages.ts:75`（真机实现） | 低 | 确定（绑定缺失） |
| 4 | 全部 17 页 | `profile-auth-sheet` 的 `bind:confirm="onProfileAuthConfirm"`、`bind:cancel="onProfileAuthCancel"` 在所有页面 wxml 出现，但全部 fixture 均未实现这两个方法（只在 backend/pages.ts），且没有任何 fixture 设置过 `authVisible: true` → 演示模式下该弹层永不出现（访客资料确认走 wx.showModal 替代）。当前不可达所以不报错，但任何一处未来 setData `authVisible` 或组件误触发即运行时 "handler not found" | 例：`pages/forum-publish/index.wxml:42`、`pages/report/index.wxml:15`、`pages/ai/index.wxml:41` 等 17 处；`backend/pages.ts`（common 注入）；pages 目录 grep `authVisible: true` 零命中 | 低 | 确定（缺失）；影响为猜测 |
| 5 | contact-chat | 聊天消息 scroll-view 绑定 `bindscrolltoupper="onScrollToUpper"`，fixture 是空方法 `{}`：上滑加载更早历史消息的逻辑不存在（演示模式消息一次性全量返回、量小，实际影响有限，但按钮语义上"历史分页"是空壳） | `pages/contact-chat/index.wxml:9`（绑定）；`pages/contact-chat/index.ts:64` `onScrollToUpper(){}` | 低 | 确定 |
| 6 | search | "显示更多"按钮绑定 `onLoadMoreTap`，fixture 为刻意空实现（注释已说明演示模式一次返回全部、`postHasMore` 恒 false，按钮不可渲染）。若日后真机逻辑回灌演示模式（或 postHasMore 被置 true），点击将无响应 | `pages/search/index.ts:34-35`（空方法+注释）；`pages/search/index.wxml:3`（`wx:if="{{postHasMore}}"` 按钮） | 信息 | 确定 |

## 顺带确认无问题的项（供参考）

- 空态/失败兜底齐全：forum（wxml:10-12）、forum-detail（wxml:3-5,21）、user-home（wxml:8-11,43）、profile（wxml:11,46）、my-posts（wxml:3-20）、my-collections（wxml:4-8）、search（searchFailed/无结果/热榜空态 wxml:3,24）、contact/notifications（访客空态 + loadFailed）。
- 防重复提交守卫齐全：forum-publish `publishing`（ts:106,110,123）、report `submitting`（ts:39-49）、feedback `busy`（ts:21-48）、profile-edit `saving`（ts:39-51）、my-posts `busyId`（ts:26-36）、chat-settings `busy`（ts:22,49-55）。
- 访客守卫：user-home 编辑按钮 `isSelf && loggedIn`、profile-edit onLoad 一键登录弹窗、forum-publish/profile-edit/my-posts 的 `requireLocalSession`、contact/notifications 访客空态，均已在演示模式生效；访客深链直开 contact-chat 会走 catch → loadFailed 兜底（ts:58），不崩。

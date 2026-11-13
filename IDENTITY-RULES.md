# 演示模式身份判定审查（只读，未改任何代码）

- 日期：2026-11-13。范围：`apps/miniprogram/miniprogram/`（fixture 演示模式为主，后端模式对照）。
- 说明：用户消息中的规则表是占位符（实际未粘贴），本文件的规则表按本会话已确认的行为整理；如有出入，以你补发的规则表为准再对照修正。

## 0. 规则表（按会话确认整理）

| 视角 | 判定 | 点自己帖子头像 | ⋯菜单 | 主页 | 编辑资料 | 互动（赞/收藏/评论赞） | 私信/通知 | 个人态数据（点赞/历史/草稿/未读） |
|---|---|---|---|---|---|---|---|---|
| 访客 guest | 未登录 | 公开主页 user-home（单 tab、无编辑钮） | 无"编辑帖子" | user-home | 不可（入口隐藏+守卫） | 一键登录弹窗，不落盘 | 引导空态，不可达 | 不可见（干净旁观视角） |
| 本人 self | isMine && 已登录 | 我的主页 profile | 含"编辑帖子" | profile（双 tab） | 可 | 正常 | 可用 | 可见（自己的） |
| 他人 other | 其余 | 公开主页 user-home | 无"编辑帖子" | user-home | 不可 | 正常 | 登录后可发起私信 | 不适用 |

## 1. 判定点清单

### 1.1 "是否已登录"

| # | 位置 | 判断条件 | 异常回退 | 一致性 |
|---|---|---|---|---|
| 1 | `features/profile/repository.ts:62` | `readLocalSession`（唯一真源，读 SESSION_KEY） | 读失败抛错 | ✅ 真源 |
| 2 | `features/profile/repository.ts:74-82` | `requireLocalSession` = 真源 + 一键登录弹窗（标准封装） | catch→toast | ✅ 标准用法 |
| 3 | `components/forum-post-card/index.ts:9-13` | 私有 `loggedInNow()`：fixture→readLocalSession，否则 client.loggedIn | **catch→true（当作已登录）** | ⚠️ 重复实现，回退不一致 |
| 4 | `pages/contact/controller.ts:9-14` | 私有 `loggedInNow(runtime)`：同上 | **catch→true** | ⚠️ 重复实现，回退不一致 |
| 5 | `components/page-header/index.ts:71` | `try { loggedIn = readLocalSession(wx) } catch { loggedIn = true }` | **catch→true** | ⚠️ 第三份变体 |
| 6 | `components/app-shell/index.ts:27` | `try{loggedIn=readLocalSession(wx)}catch{}` | **catch→维持旧值** | ⚠️ 回退语义又不同 |
| 7 | `custom-tab-bar/index.ts:47-53` | fixture 分支 `unreadTotal(wx)` **不判登录**；后端分支判 `api.loggedIn`（:51） | catch→保留旧角标 | ❌ 两种模式不对称，fixture 漏判 |
| 8 | `features/forum/repository.ts:33,47,71` | `readLocalSession`（读隔离）+ `requireLocalSession`（写守卫） | — | ✅ 2026-11-13 新增 |
| 9 | 后端模式各处 | `api.requireSession()` / `client(wx).loggedIn`（pages.ts 多处） | — | ✅ 后端侧标准 |

**结论**：真源唯一（readLocalSession / requireSession），但"读一下登没登录"这件事有 **5 种实现形态**（真源、requireLocalSession、forum-post-card 的 loggedInNow、contact/controller 的 loggedInNow、page-header/app-shell 的内联 try/catch），且读失败时的回退值互相矛盾（true / 维持旧值 / 抛错）。

### 1.2 "是否本人"

**产生侧（给数据打标）**：

| 位置 | 条件 | 登录维度 |
|---|---|---|
| `features/forum/feed.ts:50` | `isMine = post.author.id === LOCAL_AUTHOR_ID` | ❌ 无 |
| `features/forum/comments.ts:71` | `isMine = comment.author.id === localId` | ❌ 无 |
| `features/profile/content.ts:15,20` | `isSelf = (id === LOCAL_AUTHOR_ID)` | ❌ 无 |
| `backend/service.ts:52` | `isSelf = user.id === this.api.me?.id`（由会话推导） | ✅ 隐含 |
| `backend/adapters.ts:30,34,39` | 服务端下发 `dto.isMine` | ✅ 隐含 |

**消费侧（用标记做路由/入口）**：

| 位置 | 条件 | 评价 |
|---|---|---|
| `components/forum-post-card/index.ts:72` | `post.isMine && loggedInNow()` → profile 页 | ✅ 唯一同时判两者的正确示范 |
| `components/forum-post-card/index.ts:105` | `if (post.isMine)` → ⋯菜单"编辑帖子" | ❌ 未判登录 |
| `pages/forum-detail/index.ts:103` | `if (!target?.isMine) return`（删除评论） | ✅ 外层 ：98 已有 requireLocalSession |
| `pages/user-home/index.ts:32` | `!profile.isSelf && requireLocalSession(wx)` → 私信 | ✅ |
| `pages/user-home/index.ts:35` | `profile?.isSelf && requireLocalSession(wx)` → 编辑 | ✅ |
| `backend/pages.ts:231,359` | isMine/isSelf + api.requireSession | ✅ |

**结论**："本人"标记全部是**纯作者 ID 比对**（不含登录态），语义实为"本机身份的作者"；只有一处消费点正确组合了登录维度。访客视角下所有 isMine/isSelf 入口都可能把"本人操作"暴露出来（见 §3）。

## 2. 退出登录后未被清理或隔离的 storage

`setLocalSession(false)`（repository.ts:68-73）只写 `xinkelink.session.v1=false` 并广播，**不清除、不隔离任何其他 key**：

| storage key | 内容 | 退出后状态 | 是否违规 |
|---|---|---|---|
| `xinkelink.forum.interactions.v1` | 帖子点赞/收藏/转发 | 数据保留；读已隔离、写已拦截（repository.ts:33-35,47） | ✅ 已修（2026-11-13） |
| `xinkelink.forum.comments.v1` 的 `likes` | 评论点赞 | 同上（:35） | ✅ 已修 |
| `xinkelink.contact.v1` | **私信内容** + 每会话未读数 + 已读标记 | 数据保留；**访客可打开会话读历史、且 openConversation 会写入已读**（contact-chat 无守卫） | ❌ 违规（见 §3-A） |
| `xinkelink.notifications.v1` | 通知已读标记 | 页面有访客空态守卫 ✅，但 **tab-bar 未读角标泄露**（§3-B） | ❌ 角标违规 |
| `xinkelink.search.*`（历史） | 搜索历史 | 访客可见且可写入（search onLoad/onSubmit 无守卫） | ❌ 违规 |
| `xinkelink.post.draft.v1` | 发帖草稿 | 访客可见（forum-publish onLoad:31-36 draft、:38-52 editId 回填均无守卫） | ❌ 违规（低） |
| `xinkelink.feedback.v1` | 反馈记录 | 访客态未隔离 | ❌ 违规（低） |
| `xinkelink.contact.settings`* | 屏蔽/置顶/免打扰 | 保留；访客不可达该页 | ⚪ 无实际影响 |
| `xinkelink.preferences.v1` / `xinkelink.profile.v1` | 通知偏好 / 资料 | 设备级数据，保留合理 | ⚪ 合理 |
| 统计数字（动态数/获赞） | 来自公开帖子 | 访客可见 | ⚪ 公开内容，合理 |

## 3. 违反规则表的地方（按严重度，附证据）

| 编号 | 规则条目 | 问题 | 证据 | 严重度 |
|---|---|---|---|---|
| A | 访客不可进私信 | **访客能读上一个用户的私信**：tab-bar 悬浮球（contact tab）→ new-chat 无守卫列出会话 → contact-chat onLoad/refreshConversation 无 requireLocalSession，读历史消息且 openConversation 写已读标记（污染数据） | `custom-tab-bar/index.ts:67-68`（onFab→openChat 无守卫）；`pages/new-chat/index.ts:8-16`（reload 无守卫）；`pages/contact-chat/index.ts:36-58`（onLoad/refreshConversation 无守卫，:49 openConversation 写入）；对照后端 ：470 onSendTap 才有 requireSession | 高 |
| B | 访客个人态不可见 | **未读角标泄露**：退出登录后 tab-bar 仍显示上个用户的未读数（fixture 分支不判登录；后端分支判了 api.loggedIn） | `custom-tab-bar/index.ts:49` vs `:51` | 高 |
| C | 访客不可编辑 | **访客可进编辑器**：⋯菜单对 isMine 帖子显示"编辑帖子"（不判登录）→ forum-publish onLoad editId 分支回填内容无登录守卫 → 访客可修改表单（仅保存被 ：106 拦） | `components/forum-post-card/index.ts:105`；`pages/forum-publish/index.ts:38-52`；对照 ：106 onPublishTap 守卫 | 中 |
| D | 访客不见"本人"入口 | 评论的 `isMine` 不含登录维度 → 访客在详情页能看到"删除"入口（点击被 forum-detail:98 拦，UI 违规） | `features/forum/comments.ts:71`；`pages/forum-detail/index.ts:98,103` | 中 |
| E | 访客个人态不可见 | 搜索历史：访客可见上个用户的搜索记录，且访客搜索会写入历史 | `pages/search/index.ts:32`（onLoad readSearchHistory）、`:62`（onSubmit addSearchHistory） | 中 |
| F | 访客个人态不可见 | 发帖草稿全局：`draft=1` 或 `editId` 进入发布页会带出上个用户的草稿/帖子内容 | `pages/forum-publish/index.ts:31-36,38-52` | 低 |
| G | 访客个人态不可见 | 反馈记录全局存储，访客态未隔离 | `pages/feedback/index.ts`（fixture 写 `xinkelink.feedback.v1`） | 低 |
| H | 一致性 | "已登录"判定 5 种实现形态、读失败回退值互相矛盾（true / 维持旧值 / 抛错），同一条规则在不同页面可能判定不同 | §1.1 表 #3-#6 | 中（隐患） |
| I | my-posts 列表访客可达（onShow 无守卫），匿名管理/编辑/删除按钮 UI 可见（点击有 ：18,:26 守卫拦住）——UI 违规、行为不违规 | `pages/my-posts/index.ts:18,26` | 低 |

**已符合规则的项**（供对照）：帖子头像路由三态（forum-post-card:67-78）；user-home 编辑钮 `isSelf && loggedIn`（wxml:26-28）+ onEditTap 双守卫（:32,35）；profile 页编辑/点赞守卫（:49,66）；forum 页互动守卫（:75）；forum-detail 全部互动/评论守卫（:44,71,83,98）；点赞/收藏/评论赞的读隔离+写拦截（repository.ts:33-35,47,71，2026-11-13 修复）；contact/notifications 访客空态（controller.ts:20）。

## 4. 统一方案建议（只写方案，不改代码）

**建议抽公共函数**，结论是"应该抽"，理由与形态：

```ts
// 建议位置：features/profile/viewer.ts（新文件，被 repository/feed/comments 之外的 UI 层引用）
export type ViewerRole = 'guest' | 'self' | 'other'
export function getViewerRole(runtime: typeof wx, authorId?: string): ViewerRole
```

- **双轨内部实现**：fixture → `readLocalSession(runtime)` + `authorId === LOCAL_AUTHOR_ID` 比对；后端 → `client(runtime).loggedIn` + `authorId === client(runtime).me?.id`。两个模式的分支收敛在一处，消灭 `loggedInNow` 的 3 份重复实现和 page-header/app-shell 的内联 try/catch。
- **统一 catch 语义**：目前三处读失败回退值互相矛盾（forum-post-card/contact/page-header → true，app-shell → 维持旧值）。建议明确**安全侧回退 = 'guest'**（宁严勿漏：失败时少显示个人入口比多显示更安全），把决定写进函数注释，一处生效。
- **"归属"与"入口"分层**：数据层字段 `isMine/isSelf`（纯作者比对）保留——它们语义是"内容属于本机身份"，公开主页、测试都依赖；但**所有操作入口**（⋯菜单编辑、评论删除、头像路由、聊天发起）改用 `getViewerRole() === 'self'`，"guest 不可编辑/互动"就再也不依赖各页面自觉加守卫。
- **守卫收敛**：写操作继续走 `requireLocalSession`（弹窗），读/展示走 `getViewerRole()`；两者都是 viewer.ts 的薄封装，弹窗文案改一处即可。
- **迁移点清单（按收益排序）**：
  1. `custom-tab-bar:49` fixture 分支补登录判断（堵角标泄露，一行）
  2. `contact-chat onLoad/refreshConversation` + `new-chat reload` 补访客守卫（堵私信泄露，最重要）
  3. `forum-post-card:105` 与 `forum-publish onLoad editId/draft` 补 guest 拦截（堵编辑器泄露）
  4. `comments.ts:71`/`feed.ts:50` 消费点改用 viewerRole（评论删除/⋯菜单 UI 收敛）
  5. 删除 `loggedInNow` 重复实现 ×2、page-header/app-shell 内联变体，全部换 getViewerRole
  6. 搜索历史/草稿/反馈的访客可见性（规则表 §2 遗留三项）
- **测试策略**：规则表每行一条测试（现有 guest-guard / guest-interactions 已覆盖互动与编辑，缺私信可达、角标、编辑器三块的守卫测试）。
- **不建议**的做法：退出登录时物理清除 storage——会破坏"重新登录后数据恢复"的演示效果（v1-profile-pages 测试正依赖此语义），隔离读取 + 入口拦截才是与后端模式语义一致的做法。

（审查完毕，未修改任何代码。）

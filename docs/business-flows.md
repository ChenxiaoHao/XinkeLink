# 信科 Link · 业务流程逐条走查（交接用）

> 生成时间：2026-11-14。范围：`apps/miniprogram/miniprogram/` 的 **17 条业务主流程**（另有 4 条辅助流程附在 §3.18–3.21）。
> 方法：每条流程按「入口 → 数据源 → 写操作 → 存储 → 刷新时机 → 兜底 → 双模式差异」逐环节核对源码，行号为实际行号。演示模式（fixture）与后端模式（live）分别标注。
> 配套：[project-handover.md](./project-handover.md)（架构/接口/数据库）、`../BUGS.md`（演示模式审查）、`../IDENTITY-RULES.md`（身份规则）。

**读前须知**：
- 本项目每个页面有**两套实现**。wxml 共用，行为可能不同。凡标注「⚠️ 两模式不一致」的地方，都是潜在的产品/权限不一致点。
- 演示模式**不注入 `backend/pages.ts` 的 `common`**，所以每个演示页面要自备下拉刷新、头像兜底、访客守卫等能力。
- 演示模式全部数据在**本机 storage，设备级单账号**；真机模式按 JWT 会话隔离。

---

## 目录

| # | 流程 | 一句话 |
|---|---|---|
| 3.1 | 启动与内测门禁 | 首屏 → 判定阶段 → 放行/兑换码 |
| 3.2 | 登录 | 7 个入口，演示用本地 flag、真机走微信 code |
| 3.3 | 退出登录 | 只吊销会话，不清 storage |
| 3.4 | 浏览 feed / 分类 / 刷新 / 分页 | 首页主入口 |
| 3.5 | 发帖 / 编辑 / 匿名 / 草稿 | 写路径最长的一条 |
| 3.6 | 帖子详情 / 评论 / 回复 / 评论赞 | 两级评论 |
| 3.7 | 点赞 / 收藏 / 转发 | **访客隔离核心区** |
| 3.8 | 我的主页（双 tab） | 统计 + 帖子/喜欢 |
| 3.9 | 公开主页 user-home | 本人/他人/访客三视角 |
| 3.10 | 私信会话列表 | contact + notifications 共享 controller |
| 3.11 | 聊天页 | 发送锁、已读、合并 |
| 3.12 | 聊天设置 | 免打扰/置顶/屏蔽/清空 |
| 3.13 | 未读角标 | tabBar badge |
| 3.14 | 搜索（历史/热榜/分页） | 演示无分页 |
| 3.15 | 意见反馈 | 两模式守卫不一致 ⚠️ |
| 3.16 | 举报 | 两模式守卫不一致 ⚠️ |
| 3.17 | AI 助手 | 纯本地，不调模型 |
| 3.18 | 我的帖子 / 我的喜欢 | 访客可达 ⚠️ |
| 3.19 | 资料编辑 | 头像持久化 |
| 3.20 | 图片上传与授权续期 | 60s 签名 URL |
| 3.21 | WebSocket 实时 | 只做对账触发 |
| §4 | 缺陷清单（23 条，按严重度） | |
| §5 | 存储 key 清单 | |
| §6 | 身份判定点清单 | |
| §7 | 交接检查清单 | |

---

## 3.1 启动与内测门禁（beta-access）

**入口**：`app.json` 首页 = `pages/beta-access/index`。

| 环节 | 演示模式 | 真机模式 |
|---|---|---|
| 判定 | `pages/beta-access/index.ts:9-11` → `__fixtureMode===true` → `switchTab('/pages/forum/index')`，失败兜底 `reLaunch` | `backend/pages.ts:527-545` 每个业务页 onLoad/onShow 先 `gate.enterBusiness(target)` 再 `gate.guard(...)` |
| 查询 | — | `backend/beta.ts:35-48` `GET /beta/access`；`allowed()` = `phase==='open' \|\| (authenticated && activated && api.loggedIn)` |
| 门禁页状态机 | — | `backend/beta-page.ts:29-47` checking→login（未登录）/code（已登录未激活）/error；`onActivateTap` → `POST /beta/redeem`（`:72-90`） |
| 兑换码 | — | `beta.ts:9-15` 正则校验（`XK-XXXXXX-XXXXXX` 格式） |
| 刷新 | — | `onShow` 强制 `gate.check(true)` 绕过 pending 缓存（`beta-page.ts:17-20,37`） |
| 兜底 | switchTab 失败 → reLaunch → 再失败无提示 | `guard` 捕获所有异常 → 一律不放行（`beta.ts:52` 注释「Unknown access never grants entry」）；无 navigate API → toast |
| 越权防护 | — | `safeBetaTarget` 只允许 `/pages/{白名单}/index`（19 页），拒绝 `\\ # 控制字符`，长度 ≤2048，解码后再检一次（`beta.ts:7-20`） |

**⚠️ 已知成本**：真机模式**每页 onShow 都发一次 `/beta/access`**（`backend/pages.ts:534,536`；pending 缓存仅同 token 内生效）。5 个 tab 来回切 = 5 次请求。

---

## 3.2 登录（7 个入口）

| # | 入口 | 演示模式 | 真机模式 |
|---|---|---|---|
| 1 | 抽屉「一键登录」 | `components/app-shell/index.ts:83` → 弹 `profile-auth-sheet` → `:85-113` `onAuthConfirm` → `persistProfileAvatar` → `saveProfile` → `setLocalSession(wx,true)` | `app-shell/index.ts:105-110`：`api.login(true)` → `api.upload(avatar,'avatar')` → `PATCH /me`（expectedVersion）→ `api.setMe(updated,true)` |
| 2 | 访客引导条「微信登录」 | `pages/profile/index.ts:69` `setLocalSession(wx,true); this.reload()` | `backend/pages.ts:323` → `onLoginTap` → `loginHere`（requireSession） |
| 3 | 访客一键登录弹窗 | `features/profile/repository.ts:80-88` `requireLocalSession` → `wx.showModal('当前为访客浏览','一键登录')` → 确认后 `setLocalSession(api,true)` | `backend/client.ts:88-102` `requireSession` → 有 profilePrompt 走**原地**授权流程（`backend/pages.ts:108-140`），否则 `this.login()` |
| 4 | 收件箱访客引导 | `pages/contact/controller.ts:22` `onGuestLogin` → fixture `setLocalSession(wx,true)` | `client(wx).login(true)` —— **⚠️ 未 await、未 catch**（见 §4-23） |
| 5 | 内测门禁登录 | — | `backend/beta-page.ts:58-67` `onLoginTap` → `api.requireSession()` |
| 6 | profile-edit 访客 | `pages/profile-edit/index.ts:7-14` 弹窗 → 确认 `setLocalSession(wx,true)` 后原地 reload | `common.onProfileAuthConfirm` |
| 7 | 登录页 | `pages/login/index.ts:7-16` —— **已注销的孤儿代码** | `backend/pages.ts:376-380` 同名实现，页面未注册 → 不可达 |

**存储**：演示 `xinkelink.session.v1`（boolean）；真机 `xinkelink.backend.session.v1`（Session 对象）。
**刷新**：`setLocalSession` → `emitIdentityChange()` → page-header / custom-tab-bar 订阅刷新；真机 `ApiClient.changed()` → `listeners.forEach`。
**跳转**：见 §6.3 的身份切换 reLaunch 规则表。
**兜底**：演示 `readLocalSession` 抛错 → `requireLocalSession` catch → toast「本地会话读取失败，请重试」；真机 `requireSession` catch → toast（WECHAT_LOGIN_FAILED 单独文案）。

**⚠️ `readLocalSession` 默认值**：`features/profile/repository.ts:62-67` 在 key 缺失时**返回 true**（默认已登录）→ 新设备上 `getViewerRole` 返回 `self`、`requireLocalSession` 放行所有写操作。需明确语义。

---

## 3.3 退出登录

| 环节 | 演示模式 | 真机模式 |
|---|---|---|
| 入口 | `app-shell/index.ts:114-125` 确认弹窗 | 同左 |
| 动作 | `setLocalSession(wx,false)` | `client(wx).logout()` → `POST /auth/logout`（**只吊销当前这一个会话**，其它设备不受影响）→ `finally { this.clear() }` |
| 副作用 | **不清除任何 storage**，只写 `session.v1=false` + 广播 | 清 `xinkelink.backend.session.v1` |
| 跳转 | reLaunch `/pages/forum/index` | reLaunch `/pages/forum/index`（`client.ts:44-53`，**只在 true→false 方向跳**） |
| 抽屉复位 | `app-shell/index.ts` fixture 退出分支补 `this.refreshIdentity()` → 身份卡复位为默认头像 + "访客" | `client.clear()` → `onSessionChange` 广播 |
| 角标清理 | `custom-tab-bar/index.ts:52` 访客分支清零 | `backend/pages.ts:295` 退出时清 tabBar 角标 |

**设计决策（重要）**：**不要在退出登录时物理清除 storage**（`IDENTITY-RULES.md:112`）。理由：会破坏"重新登录后数据恢复"的演示语义，`tests/v1-profile-pages.test.cjs` 依赖该语义。正确做法是「**读隔离 + 入口拦截**」。

---

## 3.4 浏览 feed / 分类切换 / 下拉刷新 / 加载更多

| 环节 | 演示模式 | 真机模式 |
|---|---|---|
| 数据源 | `MOCK_POSTS`（12 条，`features/forum/mock-posts.ts`）+ 本地发帖（`xinkelink.forum.posts.v2`），合并见 `features/forum/repository.ts:35` | `GET /posts` |
| 首屏 | `pages/forum/index.ts:26-37` `onLoad` → `readSnapshot()` → `replaceFeed()`（PAGE_SIZE=2，`:8`） | `backend/pages.ts:151` `_initialLoadPromise = this.reload()`；`:152` onShow 先 await 首屏再 reload |
| 分类切换 | `pages/forum/index.ts:56-63` 校验 key → `setData` → `replaceFeed()`（offset=0）→ `pageScrollTo(0)`；`:97-106` `onForumFilterTap` ActionSheet 兜底 | 沿用 fixture 的 `onCategoryTap`；`reload` 用 `this.data.activeCategory` 映射：`recommended/latest` 不传 category，其余 `replace('-','_')`，`latest` 额外 `sort=latest`（`backend/pages.ts:158`） |
| 下拉刷新 | `pages/forum/index.ts:71-73` `readSnapshot()` → `replaceFeed()` → `stopPullDownRefresh()` | `backend/pages.ts:104` `common.onPullDownRefresh` → `reload()` |
| 加载更多 | `pages/forum/index.ts:64-70` `onReachBottom` → `queryFeed(offset=nextOffset, limit=2)` → 追加 | `backend/pages.ts:171` → `reload(true)` → 携带 `cursor=this._cursor` |
| 排序 | `features/forum/feed.ts:70-71`：recommended 按 `recommendation` 倒序，latest 按 `createdAt` 倒序 | 服务端排序 |
| 竞态 | 同步，无竞态 | `_requestGeneration` 递增 + 比对丢弃过期响应（`backend/pages.ts:154,159`） |
| 兜底 | `readSnapshot` catch → `loadFailed: true`（`:24`） | `backend/pages.ts:164` → `loadFailed:true` + toast |
| 公告 | 写死常量 `pages/forum/index.ts:9,107-109` | `GET /announcements?scope=forum&size=1`（`:166`），失败 → 「公告暂时无法加载」 |

**⚠️ 分类口径不一致**：前端 8 板块 vs 后端 V9 CHECK 只有 4 类（`campus/lost_found/study/help`）。真机模式发新分类帖子会 400。

---

## 3.5 发帖 / 编辑帖子 / 匿名帖 / 草稿

| 环节 | 演示模式 | 真机模式 |
|---|---|---|
| 入口 | `custom-tab-bar/index.ts:70,72` FAB（非私信 tab）→ `/pages/forum-publish/index`；`pages/my-posts/index.ts:44` `onPublishTap` | 同左（fixture 的 `openPublish` 保留） |
| 表单 | `pages/forum-publish/index.ts:14-21`；分类 = `FORUM_CATEGORIES` 去掉 recommended/latest（`:10`） | `backend/pages.ts:234-247` 加载后按服务端分类同步 `categoryIndex/categoryLabel` |
| 校验 | `features/forum/publish.ts:4-11`：正文 1-2000 字、分类合法、anonymous 为 boolean、图片 ≤9 | 同左 + `backend/pages.ts:250` `!content.trim()` |
| 匿名 | `features/forum/repository.ts:90` 随机 3 位数 `anonymousNumber`；`feed.ts:47-49` 匿名帖不暴露 authorId、头像换默认、作者名「匿名同学 N」 | `backend/pages.ts:256` 仅**新建**带 `anonymous`；**编辑不回传**（匿名身份不可变） |
| 匿名可见性 | `features/profile/content.ts:8` `getPublicPosts` 过滤匿名帖 → 公开主页不展示；`getMyPosts`（`:6`）保留 → 「我的帖子」可见 | 服务端投影 |
| 草稿 | `pages/forum-publish/index.ts:57` `onSaveDraft` → 写 `xinkelink.post.draft.v1`（**不含图片**）；`:31-36` `draft=1` 读取 | `backend/pages.ts:243` 读；`:257` **仅新建态**发布成功后 removeStorageSync |
| 发布 | `features/forum/repository.ts:82-94` 写 `[post, ...existing]`；图片复制到 `USER_DATA_PATH/xlk-forum-upload-` | `backend/pages.ts:249-258`：上传图片 → `POST /posts`（`clientRequestId` 幂等）；`service.images` 按 imageId 复用已有图 |
| 编辑 | `pages/forum-publish/index.ts:38-52`（仅 `wx.__fixtureMode===true && options.editId`）→ `readLocalPosts` 回填；`repository.ts:96-112` `updateOwnPost` 保留 id/评论/点赞，重新开启匿名时沿用旧编号 | `backend/pages.ts:243` `GET /posts/{id}` → `if (!this._detail.isMine) throw` → `PATCH /posts/{id}` 带 expectedVersion |
| 防重复提交 | `pages/forum-publish/index.ts:107` `if (publishing \|\| _choosing \|\| _published) return`；`:110,123` 置位/复位 | `safe(this, ..., 'publishing')` + `_publishSignature`/`_publishRequestId` 幂等键 |
| 兜底 | `:122` catch → `releaseForumImages(wx, copied)` + toast「发布未完成」；`:119-121` redirectTo 失败 → 弹窗「发布成功」引导回论坛 | `redirectTo` 到详情；错误由 `safe` 的 toast 统一兜底 |
| 离开确认 | `:97-104` `onCancelTap`：有内容 → 确认弹窗；`:96` `leave()` → `navigateBack` 失败 `switchTab` 兜底 | 同左 |

**⚠️ 遗留**：`pages/forum-publish/index.ts` 的 `editId` 分支**不判访客**（可回填原正文/图片到 UI）；编辑成功后草稿不清。

---

## 3.6 帖子详情 / 评论 / 回复 / 评论点赞

| 环节 | 演示模式 | 真机模式 |
|---|---|---|
| 入口 | `pages/forum/index.ts:87-90` `onOpenPost`（校验 id 存在）→ `navigateTo forum-detail?id=` | `backend/pages.ts:145` `common.onOpenPost` |
| 帖子数据 | `pages/forum-detail/index.ts:24-38` `readForum` → `snapshot.posts.find(id)` | `backend/pages.ts:187` `GET /posts/{id}` + `service.comments` 并行 |
| 评论树 | `features/forum/comments.ts:78-86` `commentThreads`：一级按 createdAt 倒序；回复默认只显示 1 条，`_openedReplies` 展开 | `backend/service.ts:34-40`：一级 `all('/posts/{id}/comments')`，展开时才拉 `/comments/{id}/replies` |
| 排序 | `:29-33` commentOrder='hot' → 热度 = 本层赞 + 下层赞，同分保持时间序 | `backend/pages.ts:190-194` 同口径 |
| 回复 | `pages/forum-detail/index.ts:62-69` `onReplyTap` → 校验目标可见 → `composerOpen + replyTarget` | `backend/pages.ts:201` 从 comments 树里找 |
| 提交评论 | `:70-81` `onSubmitComment`：requireLocalSession → `sending` 锁 → `addForumComment`（`repository.ts:57-69`，1-1000 字、目标存在、id 唯一）→ 清空 + reload + toast | `backend/pages.ts:202-210` `POST /posts/{id}/comments`（`replyToCommentId` + `clientRequestId` 幂等） |
| 评论点赞 | `:82-86` `onCommentLike` → `toggleForumCommentLike`（`repository.ts:70-75`） | `backend/pages.ts:212-226` `PUT/DELETE /comments/{id}/like` → 精确更新 `comments[i].replies[j]` |
| 删除评论 | `:97-110` requireLocalSession → `target?.isMine` → 确认 → `deleteOwnForumComment`（`repository.ts:76-81`：非本人抛错；删一级连带其回复） | `backend/pages.ts:227-232` `target?.isMine` → confirm → `DELETE /comments/{id}` 带 `If-Match: "version"` |
| 存储 | `xinkelink.forum.comments.v1`（`comments.ts:14`）：`{version, added[], deleted[], likes{}}`；`parseCommentState` 强校验 `:39-54`；`added` 只允许 `local-comment-*` 且 author.id==='local-student'（`:46-51`） | 服务端 |
| 兜底 | `:37` catch → `loadFailed:true`；`:68` toast「评论暂时无法读取」 | `backend/pages.ts:196` → `post:null, loadFailed:true` + toast |

---

## 3.7 点赞 / 收藏 / 转发（**访客隔离核心区**）

| 环节 | 演示模式 | 真机模式 |
|---|---|---|
| 入口 | `components/forum-post-card/index.ts:97-104` 触发 `like`/`collect`/`repost` → 页面 `saveInteraction` | `backend/pages.ts:142-144` `common.onLikePost/onCollectPost/onRepostPost` → `interaction()` |
| **访客读隔离** | `features/forum/repository.ts:36-37`：`loggedIn` 为假 → **不读** `INTERACTION_STORAGE_KEY` → 空；`:34` 评论点赞同样清空 `likes` | 服务端按 JWT 会话隔离 |
| **访客写拦截** | `features/forum/repository.ts:47` `togglePostInteraction` 首行 `requireLocalSession` → 弹「一键登录」且**不落盘**；`:71` 评论点赞同 | `backend/pages.ts:30` `if (!await api.requireSession()) return` |
| 点赞计数 | `features/forum/feed.ts:55` `likeCount = post.likes + (liked?1:0)`；`:30` toggle 用 `!current[field]` | `backend/service.ts:31-33` `togglePost` → `PUT/DELETE /posts/{id}/like`，用服务端返回值更新 |
| 收藏 | `feed.ts:21` `collected = post.initiallyCollected ?? false` | `backend/service.ts:32` → `/posts/{id}/favorite` |
| 转发 | **本地可用**：`feed.ts:30` toggle；`:56` `repostCount = (post.reposts??0)+(reposted?1:0)` | **不可用**：`backend/pages.ts:32` toast「转发功能即将上线」（注释：服务端无 repost 接口，不能退回成收藏写请求） |
| 视图更新 | 页面 `reload()` 整表刷新 | `backend/pages.ts:37-60` **就地更新**单个字段（按页面名分支），不重置分页与滚动位置 |
| 访客个人态 | `readForum` 读隔离 + `getViewerRole` | `custom-tab-bar/index.ts:56` 判 `api.loggedIn` |

**测试覆盖**：`tests/guest-interactions.test.cjs`（4 例）——访客看到中立点赞态、已登录仍看到自己的赞、访客点赞弹登录且零写入、访客态跨重载保持中立。

**⚠️ 两模式不一致**：转发能力演示可用、真机 toast；访客点转发会先弹登录再看「即将上线」（`backend/pages.ts:32` 先 `requireSession()` 再 toast）。

---

## 3.8 我的主页（双 tab）

**页面**：`pages/profile/index.ts`（demo）/ `backend/pages.ts:282-330`（真机）。**非 tabBar 页**，入口三处：抽屉「个人资料」（`app-shell/index.wxml:10` → `app-shell/index.ts:131`）、帖子头像（`forum-post-card/index.ts:77-80`）、`pages/profile/index.ts:68` `onHomeTap`。

| 环节 | 演示模式 | 真机模式 |
|---|---|---|
| 双 tab | `data.activeTab: 'posts' \| 'liked'`（`pages/profile/index.ts:11`），切换 `:40-43`；WXML `:36-39` | 同左 |
| 数据源 | `features/profile/content.ts`：`getMyPosts`(`:6`, 含匿名) / `getPublicPosts`(`:8`, 过滤匿名) / `getLikedPosts`(`:10`) / `getProfileSummary`(`:12`) | `backend/pages.ts:299-310` `/me/summary` + `/users/{me}/posts` + `/me/likes`；每次 reload 比对 `api.me?.id === userId` 防串号（`:302,304-308`） |
| 刷新 | `pages/profile/index.ts:16-20` onShow → `reload()` | `backend/pages.ts:284-298` onShow + `api.onSessionChange` 订阅（会话失效/退出立即切 guestProfile `:293-296`） |
| 兜底 | `:33` catch → `loadFailed:true` | `:303` catch → `loadFailed:true` + toast |
| 访客 | `:32` `loggedIn: readLocalSession(wx)`；WXML `:31-34` 显示「当前为访客浏览」+「微信登录」→ `onLoginTap` `:69` | `backend/pages.ts:358` `loggedIn: api.loggedIn` |
| 菜单 | `:54-63` ActionSheet 5 项（编辑个人资料 / 我的帖子 / 我的点赞 / 意见反馈 / 关于）→ `:64-71` | `backend/pages.ts:328` 同 |

**⚠️ 遗留**：抽屉「个人资料」不判登录；`onMyPostsTap` / `onCollectionsTap` 不判登录 → 访客可达「我的帖子」（含匿名帖列表）。

---

## 3.9 公开主页 user-home（本人 / 他人 / 访客三视角）

| 视角 | 演示模式 | 真机模式 |
|---|---|---|
| 本人 | `getPublicProfile(wx,'local-student')` → `isSelf:true`（`content.ts:15,20`），显示「这是其他同学可见的主页」（wxml:32）；**编辑按钮需 `isSelf && loggedIn`**（wxml:27） | `backend/service.ts:52` `isSelf = user.id===api.me?.id`；`backend/pages.ts:358` 同时 setData `loggedIn` |
| 他人 | `content.ts:14-21`：`id!=='local-student'` → 从 `resolvePeer`（`features/contact/repository.ts:23-26`）取昵称头像，bio/major/className/year 空 | `GET /users/{id}` + `/users/{id}/posts` |
| 访客 | `loggedIn: readLocalSession(wx)`；编辑按钮隐藏（wxml:27）；`onEditTap` `:35` 双重守卫（`isSelf && requireLocalSession`） | `backend/pages.ts:569` 把 `user-home.onEditTap` 包进 `requireSession` |
| 私信按钮 | wxml:28 `wx:if="{{!profile.isSelf}}"` → `onMessageTap` `:30-33`：`!isSelf && requireLocalSession(wx)` → `/pages/contact-chat/index?peerId=` | `backend/pages.ts:359` 同样 `!isSelf && await api.requireSession()` |
| 帖子列表 | `content.ts:19` 过滤匿名帖；`pages/user-home/index.ts:36-38` `onOpenPost` 校验 id 在 `profile.posts` 内 | 服务端投影 |
| tab | **只有「帖子」一个 tab**（wxml:34-36），与 profile 的双 tab 不同 | 同 |
| 兜底 | `:22` catch → `profile:null, loadFailed:true`；wxml:8-12 失败态 + 重试按钮（wxml:12 绑 `reload`，demo 的 `pages/user-home/index.ts:20-23` 有实现 → 可用） | 服务端 |

---

## 3.10 私信会话列表 / 通知列表（contact + notifications 共享 controller）

**结构**：`pages/contact/index.ts` 与 `pages/notifications/index.ts` 都走 `pages/contact/controller.ts` 的 `inbox(mode)` 工厂（notifications 的 index.ts 只有 3 行转发）。

| 环节 | 演示模式 | 真机模式 |
|---|---|---|
| 访客 | `controller.ts:20` onShow → `loggedInNow(wx)` 为假 → `guest:true` + 清空列表；WXML `contact/index.wxml:10` / `notifications/index.wxml:10` 显示空态 | 同左 |
| 访客一键登录 | `controller.ts:22` `onGuestLogin` | `client(wx).login(true)` —— ⚠️ 未 await（§4-23） |
| 刷新 | `controller.ts:23` `refreshInbox()` → `listConversations` + `readNotifications` + 未读数 → `updateVisibleNotifications` → `updateTabBadge`（`:24`） | `backend/pages.ts:382-404`：`/conversations` + `/notifications` + `/me/unread` 三者并发（`Promise.allSettled` 语义 `:386`），各自分别标记 `messagesFailed/notificationsFailed`；`Realtime` 实时刷新 `:383-384` |
| 筛选 | `controller.ts:26` `filterConversations`（全部/未读 + 关键字）；`:25` `updateVisibleNotifications`（all/unread/mention/comment/reaction） | 服务端分页 |
| 已读 | `:32` `onNotificationTap` → `markNotificationRead`（失败 toast 但仍继续跳转）；`:33` `onMarkAllRead` | `backend/pages.ts:398` `PUT /notifications/{id}/read`；`:403` `POST /me/{conversations\|notifications}/read` |
| 存储 | `xinkelink.contact.v1`（`features/contact/storage.ts:5`：messages/readIds/clearedAt）、`xinkelink.notifications.v1`（`features/contact/notifications.ts:7`：只存 readIds） | 服务端 |
| 头像兜底 | `pages/contact/controller.ts` 的 `onRemoteAvatarError`（2026-11-13 新增，与真机 `backend/pages.ts:75` 行为一致）：conversation 分支写 `conversations[i].peer.avatar`，notice 分支写 `visibleNotifications`/`notifications`，fallback `/assets/icons/user-round-muted.png`，已是默认图标则不再重复写 | `backend/pages.ts:75-103` |

---

## 3.11 聊天页（contact-chat）

| 环节 | 演示模式 | 真机模式 |
|---|---|---|
| 访客拦截 | `pages/contact-chat/index.ts:44-49` `refreshConversation` 首行读 `readLocalSession` → 访客**不读历史、不写已读**，只弹一次登录（`_guestPrompted` 标志 `:37,49`） | `backend/pages.ts:428-482` 会话接口本身需鉴权；`backend/pages.ts:557-576` 的 `requireSession` 包装列表覆盖写动作 |
| 已读 | `features/contact/repository.ts:51-54` `openConversation` 把对方消息 id 写进 `readIds` | `backend/pages.ts:454` `PUT /conversations/{id}/read`（`readUpToSequence`），失败仅置 `readStateFailed` 不阻断 |
| 发送 | `pages/contact-chat/index.ts:69` `onSendTap` → 校验 `input`/`loadFailed`/`_peerId`/**`sending`**/`requireLocalSession` → 屏蔽检查 → `sendText` → **清空 input** → `refreshConversation(false)`；失败 → `failedText` 保留输入 | `backend/pages.ts:470-478` `safe(..., 'sending')` + `_sendText`/`_sendRequestId` 幂等；失败保留 `failedText` `:475` |
| **发送锁** | `data.sending: false` + `onSendTap` 开头 `if (this.data.sending) return` → 置 true → finally 复位（2026-11-13 新增，配套 `tests/demo-mode-fixes.test.cjs`） | `safe()` 框架统一处理 |
| 消息合并 | 本地一次性全量 | `backend/pages.ts:445-451` 按 sequence 回补 + 合并，保留旧页 |
| 屏蔽 | 本地 `features/contact/settings.ts` | `backend/pages.ts:459` `canSend: !settings.blocked` + `blockedReason` 文案 |
| 序号精度 | — | `backend/client.ts:176-180` `sequence/compareSequence` 用十进制字符串保 uint64 精度 |

**⚠️ 遗留**：`pages/contact-chat/index.ts:70` `onScrollToUpper(){}` 是空实现（演示模式无历史分页）。

---

## 3.12 聊天设置（chat-settings）

| 环节 | 演示模式 | 真机模式 |
|---|---|---|
| 免打扰/置顶/屏蔽 | `features/contact/settings.ts:22-31`，peerId 必须匹配 `/^mock-[a-z0-9-]+$/`（`:23`）；屏蔽需确认弹窗（`pages/chat-settings/index.ts:27-30`） | `backend/pages.ts:498` 屏蔽走 `PUT/DELETE /me/blocks/{peerId}`；`:499` 置顶/免打扰走 `PATCH /conversations/{id}/settings`（带 expectedVersion） |
| 清空记录 | `pages/chat-settings/index.ts:48-57` 确认 → `clearConversationHistory` → `clearedAt=Date.now()`（`features/contact/repository.ts:45-50`），**仅本设备可见**（modal 文案明确说明） | `backend/pages.ts:509` `POST /conversations/{id}/clear`（`clearThroughSequence` + `confirmed`） |
| 历史搜索 | `:38-42` `onHistoryInput` → `filterHistory`（本地过滤 message.text） | `backend/pages.ts:505` `service.messages(id, {q})` |
| 举报对方 | `:58-61` → `/pages/report/index?kind=user&id=peer.id` | 同左 |

**🔴 遗留（P0）**：`pages/chat-settings/index.ts:9-13` `onLoad` → `reload()` → `readConversation` **全程不判登录**；`:38-42` `onHistoryInput` 会把上一账号的消息正文渲染到 `historyMatches`。正常入口被 contact-chat 的守卫挡住，但**深链 `/pages/chat-settings/index?peerId=mock-lin` 可直接命中**。真机侧已有对应守卫（`backend/pages.ts:568` `protectedActions['chat-settings']`）。

---

## 3.13 未读角标（tabBar badge）

- **计算**：`features/contact/badges.ts:4-8` `unreadTotal` = 私信未读（受 `preferences.messages` 开关）+ 通知未读（announcement 受 `announcements`，其余受 `interactions`）。
- **通知数据**：`features/contact/notifications.ts:10-14` 3 条种子（comment/reaction/announcement），时间基准是**打开时刻**往前推（`:9` `seedBase = () => Date.now()`）；本地评论活动转成通知 `:23-27`（`local:true`，`unread` 恒 false `:28`）；未读判定 `:28` `unread = !n.local && !ids.includes(n.id)`。
- **tabBar**：`custom-tab-bar/index.ts:48-59` `refreshUnread` —— **fixture 分支 `:50-53`：`getViewerRole(wx)==='guest'` → 三项清零**（2026-11-13 新增）；后端分支 `:55-57`：`api.loggedIn ? GET /me/unread : null`。订阅刷新 `:28` `onIdentityChange` + `:29` `client(wx).onSessionChange`。
- **写入**：`pages/contact/controller.ts:24` `updateTabBadge` → `getTabBar().setData({unreadCount, messageUnread, notificationUnread})`；触发点 `:20` onShow、`custom-tab-bar/index.ts:35` pageLifetimes.show。
- **真机**：`backend/pages.ts:390` 用 `/me/unread` 的 `badgeTotal` 与 `raw.{messages,comments,reactions,announcements}`；`:395-402` `onNotificationTap` 先 `PUT /notifications/{id}/read` 再按 `targetKind` 分流（announcement 弹 modal；`availability!=='available'` toast「内容已不可用」）。
- **兜底**：`custom-tab-bar/index.ts:58` catch → 保留上一次角标（注释「Keep the last known badge on a read failure.」）。

---

## 3.14 搜索（历史 / 热榜 / 结果分页）

- **历史**：`features/search/history.ts:5` `xinkelink.search.history.v1`，上限 8 条（`:6`）；`readSearchHistory` 全程 try/catch → 失败返回空 `:20`；`addSearchHistory` 去重后插头 `:23-30`；`removeSearchHistory` `:32-38`；`clearSearchHistory` `:40-42`。
- **demo 入口**：`pages/search/index.ts:32` onLoad 读历史；`:62` onSubmit → addSearchHistory → runSearch；`:65` 历史点击回填并重搜；`:66` 删除单条；`:67` 清空。
- **demo 结果**：`pages/search/index.ts:63` `runSearch` → `queryFeed(..., limit:100)` → **一次全量，无分页**；`postHasMore` 恒 false，`onLoadMoreTap(){}` 空实现（`:35`，注释已说明）。
- **demo 热榜**：`pages/search/index.ts:38-59` `readHot` —— fixture 本地算（`:42-46`），后端 `GET /posts?sort=recommended&size=50`（`:48`）。口径：最近 7 天点赞最多前 5（`:54-56`），不足 5 条用全量补齐。
- **真机分页**：`backend/pages.ts:261-281`：`GET /search?q=&type=posts&size=20` → `result.nextCursor`；`loadMoreSearch` `:272-277` 带 cursor，校验 `hasMore && (!nextCursor || nextCursor===state.nextCursor)` 抛错，并按 id 去重合并；`onReachBottom` + `onLoadMoreTap` 双入口 `:278`。
- **竞态**：真机 `_requestGeneration`（`:263,268,269`）；demo `resetSearch()` 同步清空。
- **入口来源**：首页搜索条 `pages/forum/index.ts:91-96` 写 `xinkelink.search.entry='posts'` 后 switchTab（该 key **只写不读 = 死 key**）。
- **兜底**：`pages/search/index.ts:58` catch → `hot:[], hotFailed:true`；`backend/pages.ts:270` → `searchFailed:true` + toast。

**⚠️ 遗留**：访客可见并可写搜索历史（`:32,62` 无守卫）；`pages/search/index.ts:69` `savePostInteraction` 无 try/catch 且未校验 id 是否在当前结果内。

---

## 3.15 意见反馈

- **入口**：抽屉「意见反馈」`components/app-shell/index.wxml:20` → `app-shell/index.ts:81` `onFeedbackTap` → `/pages/feedback/index`（**无登录守卫**）；profile 菜单 `pages/profile/index.ts:60,67`（demo 走 `onSettingsTap`，真机走 `onFeedbackTap` `backend/pages.ts:328`，真机版用 `openProtected` 需 session）。
- **表单**：`pages/feedback/index.ts:6-10` 三类（问题反馈 / 功能建议 / 其他）；`:25` content 拼装为 `【类型】正文\n联系：xxx`。
- **demo 提交**：`:26-39` → 写 `xinkelink.feedback.v1`（数组，最多 50 条 `slice(-50)`），**不检查登录态**。
- **真机提交**：`:40-48` → `api.requireSession()` → `POST /feedback`（带 `clientRequestId` 幂等 `pending` 变量 `:41`）→ 800ms 后 `navigateBack`。
- **防重复提交**：`:21` `if (this.data.busy) return`，`:42,48` 置位/复位。
- **兜底**：`:36` / `:47` toast。

**⚠️ 两模式不一致**：demo 访客可写，真机需登录。

---

## 3.16 举报

- **入口三处**：`components/forum-post-card/index.ts:113,119`（帖子）、`components/forum-comment/index.ts:10`（评论）、`pages/chat-settings/index.ts:60`（用户）。
- **理由**：`features/moderation/repository.ts:4-7` 5 项 → 提交时映射为 `commercial_ad / fraud_or_offside_payment / harassment / impersonation / other`（`backend/pages.ts:415`）。
- **demo 校验**：`features/moderation/repository.ts:31-42` kind 合法、targetId 非空、reason 合法、说明 1-200 字、截图 ≤3 张。
- **demo 目标解析**：`pages/report/index.ts:12-18` —— comment kind 不拉详情（固定标题「举报评论」），post kind 取正文前 40 字，user kind 取昵称；取不到 → `targetMissing:true`。
- **真机目标解析**：`backend/pages.ts:406-410` —— post/user 走 `GET /posts/{id}` 或 `/users/{id}`，comment 固定标题。
- **demo 提交**：`:38-50` 写 `xinkelink.reports.v1`（`features/moderation/repository.ts:3`，status 固定 `'local-only'`），**无登录守卫**。
- **真机提交**：`backend/pages.ts:412-424` `requireSession` → 原因/说明校验 → `confirm` → 上传证据图 → `POST /reports`（`clientRequestId` 幂等）→ modal → `navigateBack`。
- **防重复**：demo `submitting` 锁 `:39,49`；真机 `safe(..., 'submitting')` + `_reportSignature`。

**⚠️ 两模式不一致**：同上。

---

## 3.17 AI 助手（Link AI）

- **页面**：`pages/ai/index.ts`（demo 与真机共用同一实现；真机侧 `backend/pages.ts:513` 只补 `onShow` 的 tabBar 高亮）。
- **能力**：`features/ai/draft.ts` —— 纯本地确定性文本整理，**不调任何模型、零费用**（文件头注释 `:3`）。
  - `tidy` `:13-15`；`sentences` `:17-26`；`clip` `:28-32`
  - `boardFor` `:34-39`：关键词 → 板块（失物招领 / 学习交流 / 校园互助 / 校园生活）
  - `replyTo` `:63-89` 分支意图识别 —— 「板块/发到哪/分类」→ 板块建议（`postable:false`）；「标题」→ 3 个备选；「变短/缩写/精简/总结/提炼/三句话」→ 压成两句；「润色/改一改/通顺」→ 润色；否则 → 整理成帖子
- **会话状态**：仅在内存 `this.data.messages`（`pages/ai/index.ts:9`），**不落盘**，退出即丢。
- **直接发帖**：`:41-47` `onPostTap` → 写 `xinkelink.post.draft.v1`（content + category + anonymous:false + images:[]）→ `navigateTo /pages/forum-publish/index?draft=1`；`:44` 写盘失败 toast 阻断。
- **复制**：`:36-40` `setClipboardData`。**新对话**：`:31-35` 重置 messages，`_seq` 归零；仅 1 条消息时 toast「已经是新对话」。
- **兜底**：`:29` `pageScrollTo` try/catch（兼容旧基础库）。

---

## 3.18 我的帖子 / 我的喜欢

**我的帖子**（`pages/my-posts/index.ts` / `backend/pages.ts:362-371`）：

- demo 数据：`features/profile/content.ts:6` `getMyPosts`（**含匿名帖**）。
- 删除：demo `:25-37` requireLocalSession + `busyId` 锁 + 确认 → `deleteOwnForumPost`（`features/forum/repository.ts:113-117`，非本人抛错）。
- 真机：`:365-369` `DELETE /posts/{id}` 带 `If-Match: "version"`。
- 编辑：demo `:39-43` → `/pages/forum-publish/index?editId=`；真机 `backend/pages.ts:370` 同。

**我的喜欢**（`pages/my-collections/index.ts` / `backend/pages.ts:372-375`）：

- demo `:21-26` `readForum` → `toPostView` → `filter(p=>p.liked)`（访客态因读隔离为空）。
- 真机 `service.likes()` → `/me/likes?type=post`（`backend/service.ts:58-61`，item 缺失时降级为「帖子已不可用」占位）。
- demo 缺 `onPullDownRefresh`（json 里也没开 `enablePullDownRefresh`）。

**⚠️ 遗留**：`pages/my-posts/index.ts:8` onShow 无访客守卫。

---

## 3.19 资料编辑

- **入口**：`pages/user-home/index.ts:35` `onEditTap`（双重守卫）、抽屉 / profile 菜单。
- **demo**：`pages/profile-edit/index.ts:7-14` 访客弹窗 → 确认后登录；表单读写 `features/profile/repository.ts` 的 `readProfile`/`saveProfile`。
- **真机**：`backend/pages.ts:331-355` `GET /me` + `PATCH /me`（带 expectedVersion）；**学号单独存 `LOCAL_STUDENT_ID_KEY`**（`:351`，仅本机，`service.ts:43` 读出、`:51` 解构掉不进公开主页）。
- **头像持久化**：`features/profile/avatar.ts` `persistProfileAvatar` 复制临时路径到 `USER_DATA_PATH/xlk-profile-avatar-*`；`repairTransientAvatar` 在 `app.ts onLaunch` 自愈。
- **防重复**：`pages/profile-edit/index.ts:39-51` `saving` 锁。

---

## 3.20 图片上传与授权续期（真机专属）

1. 上传：`POST /api/v1/images`（multipart，`purpose=avatar|post|report`）→ 返回 `{id, width, height, state, expiresAt}`。
2. 展示：`backend/adapters.ts` `media()` 拼 `/api/v1/images/{id}/content?grant=`；grant 为 **HMAC + AES-GCM，60 秒有效**。
3. 续期：`backend/preview.ts` 的 `PageImageRenewal` 在页面 onShow 时扫描将过期图片路径续期（`downloadFile` 优先、`request arraybuffer` 兜底），**不改写用户正文**（`:105`）；图片 `binderror` 触发重取。
4. 过期登记：`backend/image-urls.ts` 维护 imageId ↔ URL 与到期时间（LRU 2000 条）。
5. 演示模式：图片复制到 `USER_DATA_PATH/xlk-forum-upload-`（发帖）、`xlk-profile-avatar-*`（头像）。

**⚠️ 上传上限不一致**：multipart 上限 11MB，业务上限 10MB（`ImageSanitizer.MAX_BYTES`）→ 10–11MB 的文件会先被接收再被拒。

---

## 3.21 WebSocket 实时

- `backend/realtime.ts` **只做 HTTP 对账触发**（`reconcile`），事件不落本地状态；指数退避重连（`:27-35`）。
- 服务端 `realtime/WebSocketConfiguration.java:24` 注册 `/api/v1/ws`（auth profile）。握手必须 `Authorization: Bearer`，URL 不允许 query；服务端**单向**推送 `{eventId, type, occurredAt, payload[, conversationId, sequence]}`；客户端发消息即被关闭；**每秒校验会话有效性**（`RealtimeEvents.checkSessions` → 每连接 2 次 DB 查询）；推送失败仅记日志由 HTTP 补取。
- 页面订阅：`backend/pages.ts:383-384` contact/notifications 页面在 `onShow` 挂 `Realtime`。

---

## 4. 缺陷清单（按严重度）

> 完整后端清单见 [project-handover.md](./project-handover.md) §7.1–7.2。以下为前端与流程侧。

### P0 — 高

| # | 问题 | 证据 |
|---|---|---|
| 1 | **访客可深链读上一账号私信**：`chat-settings` 的 `onLoad`/`reload`/`onHistoryInput` 全程不判登录，历史搜索会把上一账号消息正文渲染出来 | `pages/chat-settings/index.ts:9-13, 38-42`；真机侧对照 `backend/pages.ts:568` |
| 2 | **访客可读上一账号资料与「我的帖子」**：抽屉「个人资料」不判登录；`onMyPostsTap`/`onCollectionsTap` 无守卫；`my-posts` onShow 无守卫 | `components/app-shell/index.ts:131`；`pages/profile/index.ts:64,65`；`pages/my-posts/index.ts:8` |
| 3 | **"已登录"判定有 5 套实现、回退值矛盾**：三处 catch→true、一处 catch→维持旧值、只有 `viewer.ts` catch→guest → 同一屏三种身份结论 | `components/forum-post-card/index.ts:9-14`；`pages/contact/controller.ts:9-14`；`components/page-header/index.ts:71`；`components/app-shell/index.ts:27`；`features/profile/viewer.ts:22` |

### P1 — 中

| # | 问题 | 证据 |
|---|---|---|
| 4 | 「编辑帖子」入口不判登录 → 访客可回填帖子内容到 UI | `components/forum-post-card/index.ts:110-116`；`pages/forum-publish/index.ts:38-52` |
| 5 | 访客可见并可写搜索历史 | `pages/search/index.ts:32,62` |
| 6 | 演示模式举报/反馈**无登录守卫**，真机模式有 → 同一入口两模式权限不同 | `pages/report/index.ts:38-50`；`pages/feedback/index.ts:26-39`；真机对照 `backend/pages.ts:413`、`pages/feedback/index.ts:40` |
| 7 | 「转发」两模式能力不一致（演示真 toggle vs 真机 toast「即将上线」） | `features/forum/feed.ts:30,56`；`backend/pages.ts:32` |
| 8 | `readLocalSession` key 缺失时返回 true（默认已登录）→ 新设备上 `getViewerRole` 返回 self、写操作全放行 | `features/profile/repository.ts:62-67` |
| 9 | 真机访客登录未 await → 失败被吞且 UI 已切「已登录」 | `pages/contact/controller.ts:22` |
| 10 | 搜索页互动写入无 try/catch 且未校验 id 合法性 | `pages/search/index.ts:69`；对比 `pages/forum/index.ts:76-82` |
| 11 | `profile-auth-sheet` 的 `bind:confirm/bind:cancel` 在演示模式全 17 页未实现 | 17 处 wxml；只在 `backend/pages.ts:119-140`；`BUGS.md` 第 4 条 |
| 12 | 帖子分类前后端不一致（前端 8 类 vs 后端 4 类）→ 真机发新分类 400 | `xl_post.ck_post_category`（V9 收窄）；前端 `FORUM_CATEGORIES` |

### P2 — 低

| # | 问题 | 证据 |
|---|---|---|
| 13 | 访客可见发帖草稿与草稿页（`pages/drafts` 已注册但无入口，仅深链可达） | `pages/forum-publish/index.ts:31-36`；`pages/drafts/index.ts:3` |
| 14 | 编辑帖子成功后不清理草稿 | `backend/pages.ts:257` |
| 15 | 评论删除未做 URL 编码 | `backend/pages.ts:231`（同文件 `:218` 等处都做了） |
| 16 | `pages/login/` 是已注销的孤儿代码，`definitions.login` 仍在 | `pages/login/index.ts:3`；`backend/pages.ts:376-380` |
| 17 | `xinkelink.search.entry` 只写不读（死 key） | `pages/forum/index.ts:94` |
| 18 | `onScrollToUpper(){}` 空实现（演示模式无历史分页） | `pages/contact-chat/index.ts:70`；`BUGS.md` 第 5 条 |
| 19 | `onLoadMoreTap(){}` 空实现（演示模式 `postHasMore` 恒 false，按钮不可渲染） | `pages/search/index.ts:35`；`BUGS.md` 第 6 条 |
| 20 | 真机每页 onShow 都发一次 `/beta/access` | `backend/pages.ts:534,536`；`backend/beta.ts:37` |
| 21 | 访客点转发会先弹登录再看「即将上线」 | `backend/pages.ts:32` |
| 22 | profile 菜单 handler 名 `onSettingsTap` 实际跳反馈页（真机侧叫 `onFeedbackTap`） | `pages/profile/index.ts:60,67` vs `backend/pages.ts:328` |
| 23 | 演示模式改通知偏好不判登录（真机有 `requireSession`） | `components/app-shell/index.ts:66` vs `:71` |
| 24 | `__shot.cjs:18` 引用了已下线的 `/pages/settings/index` | `apps/miniprogram/__shot.cjs:18` |

---

## 5. 存储 key 清单

见 [project-handover.md](./project-handover.md) §1.9（19 个 key，含隔离性与访客可读可写标注）。

---

## 6. 身份判定点清单

### 6.1 "是否已登录"

| # | 位置 | 判断条件 | 异常回退 | 一致性 |
|---|---|---|---|---|
| 1 | `features/profile/repository.ts:62` | `readLocalSession`（**唯一真源**） | 读失败抛错 | ✅ |
| 2 | `features/profile/repository.ts:74-82` | `requireLocalSession` = 真源 + 一键登录弹窗 | catch→toast | ✅ 标准封装 |
| 3 | `components/forum-post-card/index.ts:9-14` | 私有 `loggedInNow()` | **catch→true** | ⚠️ |
| 4 | `pages/contact/controller.ts:9-14` | 私有 `loggedInNow()` | **catch→true** | ⚠️ |
| 5 | `components/page-header/index.ts:71` | 内联 try/catch | **catch→true** | ⚠️ |
| 6 | `components/app-shell/index.ts:27` | 内联 try/catch | **catch→维持旧值** | ⚠️ |
| 7 | `features/profile/viewer.ts:16,22` | `getViewerRole` | **catch→guest** | ✅ 安全侧（唯一推荐） |
| 8 | `features/forum/repository.ts:34,47,71` | 读隔离 + 写守卫 | — | ✅ |
| 9 | `custom-tab-bar/index.ts:50-57` | fixture→`getViewerRole`；后端→`api.loggedIn` | catch→保留旧角标 | ✅ |
| 10 | 后端模式各处 | `api.requireSession()` / `client(wx).loggedIn` | — | ✅ |

### 6.2 "是否本人"

**产生侧**：`features/forum/feed.ts:50`（`isMine`）、`features/forum/comments.ts:71`（`isMine`）、`features/profile/content.ts:15,20`（`isSelf`）—— **全部是纯作者 ID 比对，无登录维度**；`backend/service.ts:52`、`backend/adapters.ts:30,34,39` —— 隐含登录维度。

**消费侧**：`forum-post-card/index.ts:77`（`isMine && loggedInNow()` ✅ 唯一正确示范）、`:110`（`if (post.isMine)` ❌ 不判登录）、`forum-detail/index.ts:98,103` ✅、`user-home/index.ts:32,35` ✅、`backend/pages.ts:231,359` ✅。

### 6.3 身份切换 reLaunch 规则

| 场景 | 演示模式 | 真机模式 |
|---|---|---|
| 访客 → 已登录 | **reLaunch 首页** | **不跳**，原地刷新 |
| 已登录 → 访客 | **reLaunch 首页** | **reLaunch 首页** |
| 资料/头像保存 | 不跳 | 不跳 |
| 静默续期 | — | 不跳 |

**真机登录侧不跳的理由**（`backend/client.ts:46-49` 注释）：登录流程有自己的目的地（beta 门禁兑换后要 reLaunch 到 redirect 目标），中途跳转会摧毁进行中的流程页。

---

## 7. 交接检查清单

接手后建议按此顺序验证（每条都有对应测试或命令）：

| # | 检查项 | 验证方式 |
|---|---|---|
| 1 | 小程序工程编译通过 | `cd apps/miniprogram && npm run check`（应 260 条全绿） |
| 2 | 演示模式能跑通主流程 | 微信开发者工具导入 `apps/miniprogram`，devtools 自动开演示模式 |
| 3 | 真机模式后端可启动 | 补齐 pwsh 7.4 / MySQL 13306 / Redis 16379 / `XINKELINK_JWT_SECRET_BASE64`，跑 `scripts/start-backend-dev.ps1`，`curl http://127.0.0.1:18081/api/v1/health` 应返回 `application=xinkelink-server` |
| 4 | 后端单测 | `cd apps/server && mvn test`（~130 用例，不需要 Docker） |
| 5 | 后端集成测试（可选，需 Docker） | `mvn -Pdb-integration verify`（~160 用例，Testcontainers MySQL） |
| 6 | 数据库迁移验收 | `mvn -Pdb-integration verify -Dtest=DatabaseMigrationIT`（断言恰好 25 张表、9 个版本） |
| 7 | 管理后台可连后端 | `cd apps/admin && npm run check && npm run dev`（需本地 HTTPS 证书，`XINKELINK_DEV_CERT`/`KEY`） |
| 8 | 端口四方一致 | `grep -rn "18081\|18086\|8080\|18080" apps/miniprogram/miniprogram/backend/config.ts apps/server/src/main/resources/application.yml apps/admin/vite.config.ts scripts/start-backend-dev.ps1 README.md` |
| 9 | 身份模型一致性 | 读 `features/profile/viewer.ts` + `IDENTITY-RULES.md`；确认新增判断都走 `getViewerRole` |
| 10 | 访客隔离现状 | 读 §4 的 P0/P1 三条 + `tests/guest-guard.test.cjs`、`tests/guest-interactions.test.cjs` |
| 11 | 分类口径决策 | 前后端 8 类 vs 4 类，确认要扩 CHECK 还是收缩前端板块 |
| 12 | 交接文档同步 | 本文件 + `project-handover.md` + `BUGS.md` + `IDENTITY-RULES.md` 四份需一起读 |

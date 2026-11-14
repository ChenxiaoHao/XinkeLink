# 信科 Link · 项目全貌说明（交接用）

> 生成时间：2026-11-14（刷新版）。事实来源：2026-11-13/14 对 `apps/miniprogram`、`apps/server`、`apps/admin`、Flyway 迁移、配置与脚本的**全量只读代码审查**（前端 41 个测试文件 / 后端 50 个测试类），不是凭记忆写的。
> 用途：交给任何 AI / 新人，读完即可接手开发与排查。
> 配套文档：**业务流程逐条走查见 [business-flows.md](./business-flows.md)**；演示模式缺陷清单见 `../BUGS.md`；身份规则审查见 `../IDENTITY-RULES.md`。

---

## 0. 一句话与仓库结构

**信科 Link** 是一个校园社区小程序：发帖 / 评论 / 点赞 / 收藏 / 私信 / 通知 / 搜索 / AI 写作助手，界面按 X（Twitter）风格黑白化复刻。三端组成：

```
D:/CODE/XinKeLink
├─ apps/miniprogram   微信小程序（用户端，原生小程序 + TypeScript）
├─ apps/admin         管理后台（Vue 3 + Element Plus）
├─ apps/server        服务端（Spring Boot 4.1.1 / Java 21 / MySQL 8.4 / Redis）
├─ docs/              设计稿、方案、验收记录、本交接文档
├─ scripts/           后端隔离环境初始化与启动脚本（PowerShell）
├─ BUGS.md            演示模式只读审查报告（6 条，1/2/3 已修复）
├─ IDENTITY-RULES.md  身份判定审查报告与规则表
└─ .workbuddy/memory/ 工作日志（memory/*.md，含历次踩坑记录）
```

**当前 git 状态**：11 个提交，最新 `bd4bf88 style: cap feed single-image height to 4:3`，工作区干净。**仓库无 remote**（`git remote -v` 为空），所有提交只在本地。

---

## 1. 微信小程序前端（apps/miniprogram）

### 1.1 技术栈与工程约定

- **原生微信小程序 + TypeScript**（无 uni-app/Taro），`project.config.json` 开启 typescript 编译插件，基础库 3.15.0，appid `wxda2ce56da7fced57`。
- 样式统一 **px@375 基准**（已废弃 rpx），主色 `#0F1419`（墨黑），旧蓝 `#1d9bf0` 仅存于个别功能色（爱心粉红 `#f91880` 保留）。`styles/tokens.wxss:1-4` 注释："数值口径是 375 逻辑宽（设计稿 px），WXSS 里统一 ×2 写成 rpx；≥500px 的窄平板按 px 还原"。
- 图标全部由 `scripts/generate-x-icons.cjs` + `generate-icons.cjs` 生成到 `miniprogram/assets/icons/`（lucide-static SVG → @resvg/resvg-js 渲染 72px PNG），**不要手改 PNG**，改 variants/aliases 表后重新生成。
- 命令：`npm run typecheck`（tsc --noEmit）、`npm test`（先 tsc 到 `.test-dist` 再跑 node:test，**260 条全绿，41 个测试文件**）、`npm run check:project`（工程一致性静态断言）、`npm run check`（三者串联）。
- `scripts/check-project.cjs` 只做静态 JSON 断言（compileType、typescript 插件、tabBar.custom、tabBar 5 项、每个 page 的 ts/json/wxml/wxss 四件套存在等），**不编译 WXML/WXSS**（脚本自述 `:35-36`）。

### 1.2 双轨运行模式（理解本项目的钥匙）

每个页面文件 `index.ts` 保留一份**完整的本地实现**（MOCK_POSTS + 本地 storage），由 `backend/pages.ts` 的 `registerPage(wx, Page, name)`（`:17-21`）决定走哪套：

- **演示模式**（`wx.__fixtureMode === true`）：`backend/demo-mode.ts:36-38` 在模块顶层自动开启（`platform === 'devtools'` 时默认开；`wx.setStorageSync('xinkelink.mode', 'live'|'local')` 可强制切换）。`registerPage` 直接 `nativePage(fixture)`，**不注入 `common`** → **不启动 Java 服务也能跑全功能**，数据存本地 storage。
- **后端模式**（真机 / mode=live）：`definition = { ...fixture, ...common, ...implementation }`（`backend/pages.ts:522`）合并 —— `common`（`:73-146`）提供 onShow/reload/资料确认弹层/图片续期/头像 error 兜底等公共行为，`definitions`（`:147-426`）覆盖业务方法，`data` 合并顺序 `fixture → pageData → implementation`。合并后若没有 `reload` 会自动补空实现（`:523-525`，防止 `common.onShow` 抛 TypeError）。onLoad/onShow 还会被包一层 beta 门禁 + 图片授权续期（`:527-556`）。

**两个必须记住的坑**：

1. **wxml 是两套模式共用的**。改 wxml 绑定的方法时，必须确认 fixture 与 implementation 两边都有（历史上多次出现"演示模式死链"，见 `BUGS.md`）。
2. **演示模式不注入 `common`**，所以每个演示页面必须**自备**：`onPullDownRefresh` / onShow 重载 / 头像 error 兜底 / 访客守卫 / `profile-auth-sheet` 的两个 handler（当前仍缺，见 §7.3）。

### 1.3 页面清单（app.json 注册 20 个，`app.json:2-23`）

| 页面 | 功能 | 备注 |
|---|---|---|
| `pages/beta-access/index` | 内测门禁/激活码 | app.json 首页（测试有断言，勿改顺序）；演示模式直接 switchTab 进论坛 |
| `pages/forum/index` | 首页 feed | 8 板块 tab（推荐/最新/失物认领/二手闲置/校园生活/吐槽/互帮互助/找搭子），PAGE_SIZE=2 本地分页 |
| `pages/contact/index` | 私信 + 通知双 tab | 访客显示引导空态 |
| `pages/profile/index` | **我的主页** | 动态/获赞统计 + 帖子/喜欢双 tab；访客态显示登录引导条；**非 tabBar 页** |
| `pages/forum-detail/index` | 帖子详情 | 两级评论、按热度/按时间排序、底部回复栏 |
| `pages/forum-publish/index` | 发帖/编辑 | 支持草稿、匿名 chip、editId 编辑 |
| `pages/contact-chat/index` | 聊天页 | 消息序号、已读、清空历史、发送锁 |
| `pages/my-posts/index` | 我的帖子 | 含匿名帖管理、编辑、删除 |
| `pages/my-collections/index` | 喜欢（点赞列表） | 页面名叫"喜欢"，走的是点赞接口 |
| `pages/profile-edit/index` | 编辑资料 | 头像持久化到 USER_DATA_PATH；有访客守卫 |
| `pages/user-home/index` | **公开主页** | 他人视角：简介 + 公开帖子数/获赞 + 分享·私信 + 帖子单 tab；本人+登录才有"编辑"按钮 |
| `pages/search/index` | 搜索 | 搜索历史 + 当周热榜（点赞前 5） |
| `pages/report/index` | 举报 | 支持 post/comment/user（演示模式本地存储） |
| `pages/chat-settings/index` | 聊天设置 | 置顶/免打扰/清空/屏蔽 |
| `pages/feedback/index` | 意见反馈 | 演示模式存本机，真机走后端 |
| `pages/notifications/index` | 通知 | 与 contact 共享 controller |
| `pages/ai/index` | Link AI | **本地写作助手**（`features/ai/draft.ts`），确定性整理不联网，免费不限次 |
| `pages/drafts/index` | 草稿箱 | 已注册、**无任何入口** |
| `pages/new-chat/index` | 发起私信 | 聊过的同学列表 |
| `pages/account-feature/index` | 通用说明页 | 抽屉"+"跳转 |
| `pages/login/index` | — | **未注册 = 死代码**（`pages/login/index.ts:3` 仍调 `registerPage(...,'login')`，但 app.json 无此页；`tests/inline-login.test.cjs` 断言"old login route is unregistered"） |

**tabBar**（`app.json:30-57`）：`custom: true`，5 项 = 首页 / 搜索 / Link AI / 通知 / 私信，与 `custom-tab-bar/index.ts` + `utils/navigation.ts:2-8` 的 `TABS` 一致。

### 1.4 组件清单（6 个）

| 组件 | 职责 |
|---|---|
| `app-shell` | 右滑抽屉 = 设置中心：账号卡、通知偏好开关、反馈、关于、退出登录；内嵌 `profile-auth-sheet` 登录弹层；管理 tab 位移与滑动手势 |
| `page-header` | 各页左上角顶栏：头像按钮（打开抽屉）、标题、搜索态、右插槽（避让微信胶囊）；**订阅身份广播**实时刷新头像/访客态 |
| `forum-post-card` | 帖子卡片（列表/详情/主页三种形态）：头像点击分流、点赞/转发/收藏、"⋯"菜单（本人有编辑/书签） |
| `forum-comment` | 评论行：赞/回复/删除/展开 |
| `profile-auth-sheet` | 微信头像昵称授权弹层（chooseAvatar + nickname input） |
| `back-header` | 二级页返回头 |

`custom-tab-bar/` 为 tabBar 组件（含发布 FAB + 未读角标；**聊天 tab 的 FAB 已于 2026-11-13 移除**）。

### 1.5 核心机制（改代码前必读）

1. **身份事件广播**：`features/profile/identity-events.ts`（模块级订阅表）。`repository.ts` 的 `saveProfile` / `setLocalSession` 写入后自动 `emitIdentityChange()`，覆盖抽屉登录、访客一键登录、退出、资料编辑全部入口。`page-header` 与 `custom-tab-bar` 在 `attached` 订阅 + 立即刷新；真机侧另订阅 `ApiClient.onSessionChange`（`setMe`/`clear` 触发）。**没有它，页面内登录后顶栏不刷新**。
2. **头像持久化**：`features/profile/avatar.ts`。`persistProfileAvatar` 把 chooseAvatar 的临时路径复制到 `USER_DATA_PATH/xlk-profile-avatar-*`；`repairTransientAvatar` 在 `app.ts onLaunch` 自愈历史临时路径（失效则回退内置头像）。
3. **帖子头像跳转口径**（`forum-post-card.onAuthorTap` `:77`）：匿名/无 id → 打开帖子；**本人 + 已登录 → `/pages/profile/index`（我的主页）**；访客或别人 → `/pages/user-home/index?id=xxx`（公开主页）。公开主页的编辑按钮要求 `isSelf && loggedIn`（wxml:27）。
4. **图片授权链**（真机）：上传 `POST /images` → 展示走 `media()` 拼接 `/images/{id}/content?grant=`（grant 为 HMAC+AES-GCM 签名，**60 秒有效**）→ `PageImageRenewal` 在页面可见时自动续期，图片 `binderror` 触发重取。`backend/preview.ts:105` 明确**不改写用户正文**里的图片 URL。
5. **转发功能**：后端无 `/posts/{id}/repost` 接口，真机侧 `backend/pages.ts:32` 对 `reposted` 显式拦截并提示"转发功能即将上线"，**不要把它退回成收藏写请求**。
6. **乐观锁**：写操作带 `expectedVersion`（body）或 `If-Match: "N"`（header），409 冲突需刷新重试。
7. **幂等键**：发帖/评论/发消息/举报/反馈都带 `clientRequestId` / `clientMessageId`。

### 1.6 图片尺寸机制（2026-11-14 新增规则）

- 容器高 = 宽 × `singleImagePadding%`，由 CSS 变量 `--post-image-padding` + `::before padding-top` 实现（`features/media/layout.ts`）。
- **列表形态（home）**：`boundedImageRatio(w/h)` 把比例夹取到 **[4/3, 1.5]**，即高 ≤ 0.75×宽；超出部分 `aspectFill` **居中裁剪**。
- **详情形态（detail）**：用 `naturalImagePadding` 完全按原图比例，**不裁剪**，可点击预览。
- 多图宫格不受影响；圆角/边框/间距未改。
- 测试断言同步在 `tests/home-presentation.test.cjs`（期望值 125 → 75）。

### 1.7 身份模型（2026-11-13 起，核心改造）

**唯一判定源**：`features/profile/viewer.ts` 的

```ts
getViewerRole(runtime, authorId?) => 'guest' | 'self' | 'other'
```

- fixture 分支：`readLocalSession` 为假 → `guest`；否则 `authorId === LOCAL_AUTHOR_ID('local-student')` → `self`，否则 `other`。
- 后端分支：`api.loggedIn` 为假 → `guest`；否则 `authorId === api.me?.id` → `self`，否则 `other`。
- **任何读取失败一律 `guest`**（安全侧回退）。
- `authorId` 为 undefined：guest 分支 → guest；已登录 → other（`viewer.ts:34` 注释）。

**访客 = 干净旁观视角**（读隔离 + 写拦截）：

| 侧 | 实现 |
|---|---|
| 读隔离 | `features/forum/repository.ts:34-37`：访客 `readForum` 返回空 interactions + 空 `commentState.likes`；`custom-tab-bar/index.ts:52` 访客角标三项清零 |
| 写拦截 | `features/forum/repository.ts:47,71`：`togglePostInteraction` / `toggleForumCommentLike` 首行 `requireLocalSession` → 弹"一键登录"且**不落盘** |
| 入口拦截 | `pages/new-chat/index.ts` onShow 访客拦截并弹登录；`pages/contact-chat/index.ts` `refreshConversation` 首行访客拦截（**不读历史、不写已读标记**，用 `_guestPrompted` 只弹一次） |

**身份切换 reLaunch 规则**（用户明确要求的业务规则）：

| 场景 | 演示模式（`features/profile/repository.ts:68-79`） | 真机模式（`backend/client.ts:44-53`） |
|---|---|---|
| 访客 → 已登录 | **reLaunch `/pages/forum/index`** | **不跳**，原地刷新（登录流程有各自目的地，跳转会摧毁进行中的 beta 门禁流程页） |
| 已登录 → 访客（退出） | **reLaunch `/pages/forum/index`** | **reLaunch `/pages/forum/index`** |
| 资料保存 / 头像更新 | 不跳（`saveProfile` 只 `emitIdentityChange`） | 不跳（`setMe` 广播，`lastNotifiedLoggedIn` 不变） |
| 静默续期 | — | 不跳（`refresh()` → `save()`，登录态不变） |

实现方式：`setLocalSession` 比较**切换前后**登录态，**只有变化才 reLaunch**（已在首页也要 reLaunch）；真机侧 `client.changed()` 用 `lastNotifiedLoggedIn` 比较，**只在 true→false（退出）方向跳**。

**访客抽屉身份**：`components/app-shell/index.ts` fixture 退出分支补 `this.refreshIdentity()`，抽屉身份卡复位为默认头像 + "访客"。

### 1.8 前端调用的后端接口

`backend/client.ts` 的 `ApiClient` 负责：微信 code 登录、refresh 轮换、401 自动重试、multipart 上传、会话持久化（storage key `xinkelink.backend.session.v1`）。基址 `backend/config.ts:2` 的 `BACKEND_ORIGIN = 'http://127.0.0.1:18081'`。

小程序端调用（`/api/v1` 前缀省略）：`/auth/wechat|refresh|logout`、`/me`(GET/PATCH)、`/me/preferences`、`/me/summary`、`/me/posts`、`/me/favorites`、`/me/likes`、`/me/unread`、`/me/conversations/read`、`/me/notifications/read`、`/me/blocks/{userId}`、`/posts`(GET/POST)、`/posts/{id}`(GET/PATCH/DELETE)、`/posts/{id}/like|favorite|comments`、`/comments/{id}`(DELETE)、`/comments/{id}/like|replies`、`/search`、`/users/{id}`(+`/posts`)、`/conversations`(GET/POST)、`/conversations/{id}`(+`/messages|read|settings|clear`)、`/notifications`(+`/{id}/read`)、`/announcements`(+`/{id}`)、`/images`(POST)、`/images/{id}/access|content`、`/reports`、`/beta/access|redeem`、`/feedback`、WS `/ws`。

### 1.9 本地 storage key 清单（19 个）

| key | 内容 | 按用户隔离？ | 访客可读/可写 |
|---|---|---|---|
| `xinkelink.mode` | 演示模式开关 | 否 | 可见（公开读） |
| `xinkelink.session.v1` | 演示登录态（boolean） | 否（设备级单账号） | 可见；可写 |
| `xinkelink.profile.v1` | 昵称/头像/简介/专业/班级/学号/年份 | 否 | **可见**（兜底返回默认"陈林舟"）；可写 |
| `xinkelink.preferences.v1` | 四类提醒开关 | 否 | 可见；可写（**fixture 分支不判登录**） |
| `xinkelink.forum.posts.v2` | 本机发布的帖子 | 否 | **可见**（合并进所有 feed） |
| `xinkelink.forum.interactions.v1` | 帖子赞/藏/转 | **读隔离**（访客读为 `{}`） | 访客读不到；写被拦 |
| `xinkelink.forum.comments.v1` | 评论新增/删除/赞 | 部分（`likes` 访客读为空） | `added`/`deleted` 访客可见 |
| `xinkelink.contact.v1` | 会话消息/已读/清空水位 | 否 | **访客读隔离**（chat 页直接 return） |
| `xinkelink.contact.settings.v1` | 免打扰/置顶/屏蔽 | 否 | 正常入口不可达，**深链可达** ⚠️ |
| `xinkelink.notifications.v1` | 已读通知 id | 否 | 访客不可达通知页 |
| `xinkelink.search.history.v1` | 搜索历史（≤8 条） | 否 | **访客可见 + 可写** ⚠️ |
| `xinkelink.search.entry` | `'posts'` | 否 | **只写不读（死 key）** |
| `xinkelink.post.draft.v1` | 发帖草稿 | 否 | **访客可见** ⚠️ |
| `xinkelink.feedback.v1` | 本机反馈（≤50 条） | 否 | **访客可写** ⚠️ |
| `xinkelink.reports.v1` | 本机举报 | 否 | **访客可写** ⚠️ |
| `xinkelink.backend.session.v1` | JWT 会话对象 | ✅ 按 JWT | 真机模式 |
| `xinkelink.private.student-id.v1` | 学号（**仅本机**） | ✅ 设备级私有 | 真机模式，不进公开主页 |

⚠️ 标记项 = 尚未按访客隔离的遗留（见 §7.3）。

### 1.10 前端测试

`tests/*.test.cjs` 共 **41 个文件 / 260 条用例**（node:test）。两个自研 harness：

- `tests/page-harness.cjs:7-46` `loadPage(relativePath, {wx, fixture=true, betaPhase})` —— 从 `.test-dist/` 读编译产物，`vm.runInNewContext` 注入 `Page()`/`wx`/`getCurrentPages`；`betaPhase:'open'` 注入假的 `/api/v1/beta/access`。
- `tests/native-harness.cjs:6-27` `loadComponent(relativePath, {wx, pages, fixture})` —— 捕获 `Component()`。

用例数最多：`beta-access.test.cjs` 30、`inline-login` 16、`backend-pages` 16、`v1-profile-pages` 14。2026-11-13/14 新增 5 个：`viewer-role`(8)、`identity-relaunch`(6)、`guest-interactions`(4)、`demo-mode-fixes`(4)、`guest-guard`(2)。

---

## 2. 服务端（apps/server）

### 2.1 技术栈与模块

Maven 单体，Java 21，Spring Boot 4.1.1，MyBatis-Plus 3.5.17，Flyway（V1–V9），Spring Security + OAuth2 Resource Server（JWT），spring-data-redis（Lettuce），WebSocket，springdoc（dev 才开）。图片存储默认本地落盘（`LocalImageStorage`），可切阿里云 OSS（`xinkelink.storage.provider=oss`）。

模块包（`cn.xinkelink.*`）：`auth`（登录/JWT/限流）、`forum`（帖子评论点赞收藏）、`content`（发现/搜索/公共鉴权分页工具）、`messaging`、`notification`、`profile`、`media`、`beta`、`operations`（公告/通知任务）、`moderation`（举报/处置/封禁）、`feedback`、`admin`（后台聚合）、`audit`、`settings`、`realtime`、`storage`、`health`、`common`（ApiResponse/错误码/游标/全局异常）。

**除 HealthController 外所有 Controller 都标 `@Profile("auth")`** —— 不激活 auth profile 时业务接口全部不存在。

### 2.2 接口全集（16 个 Controller）

统一响应 `ApiResponse{ code, message, data, errors[], requestId, timestamp }`；错误码 39 个（`common/api/ErrorCode.java:5-52`），如 `UNAUTHENTICATED / TOKEN_EXPIRED / REFRESH_INVALID / VERSION_CONFLICT / PRECONDITION_REQUIRED / DUPLICATE_REQUEST_CONFLICT / BETA_ACCESS_REQUIRED / RATE_LIMITED / ACCOUNT_BANNED / ACTION_RESTRICTED`。

**认证（公开）**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/wechat` | 微信 code 换 token 并 upsert 用户；限流 10/60s/IP |
| POST | `/api/v1/auth/refresh` | 刷新令牌（轮换，旧票据重放 → REFRESH_INVALID）；30/60s/IP |
| POST | `/api/v1/auth/logout` | 吊销**当前这一个会话**（其它设备不受影响） |

**管理员认证（公开，Cookie + CSRF + Origin 白名单）**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/admin/auth/login` | BCrypt(12) 校验；refresh 走 `xinkelink_admin_refresh` HttpOnly Cookie（path=/api/v1/admin/auth, secure, sameSite=Strict）+ `X-CSRF-Token`；5/60s（IP+账号双键） |
| POST | `/api/v1/admin/auth/refresh` / `logout` | 轮换（同时轮换 CSRF）/吊销 |
| GET | `/api/v1/admin/me` | 当前管理员 |

**论坛**（`forum/ForumController.java`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/posts` | feed（category/sort/keyword/cursor/size），公开，签名游标 |
| POST | `/api/v1/posts` | 发帖 `{content≤2000, category, imageIds≤9, anonymous, clientRequestId}`；**幂等**（`uk_post_request` + `request_hash`） |
| GET/PATCH/DELETE | `/api/v1/posts/{id}` | 详情（公开）/ 编辑（body expectedVersion）/ 删除（header `If-Match: "<version>"`） |
| GET | `/api/v1/me/posts` | 我的帖子 |
| PUT/DELETE | `/api/v1/posts/{id}/like`、`/favorite` | 点赞/收藏切换 |
| GET/POST | `/api/v1/posts/{id}/comments` | 一级评论；POST **幂等**（`uk_comment_request`） |
| GET | `/api/v1/comments/{id}/replies` | 二级回复 |
| DELETE | `/api/v1/comments/{id}` | 删评论（If-Match，删一级连带其回复） |
| PUT/DELETE | `/api/v1/comments/{id}/like` | 评论点赞 |

**发现**（`content/DiscoveryController.java`）：`GET /search`（type 只接受 `posts`、filter 必须为空）、`GET /me/favorites`（type 只接受 `post`）、`GET /me/summary`、`GET /me/likes`、`GET /users/{id}`、`GET /users/{id}/posts`。

**私信**（`messaging/MessagingController.java`）：`GET/POST /conversations`、`GET /conversations/{id}`、`GET/POST /conversations/{id}/messages`（`beforeSequence/afterSequence/aroundMessageId/q` 三种模式互斥；POST **幂等** `uk_message_client`）、`PUT /conversations/{id}/read`、`POST /me/conversations/read`、`GET/PATCH /conversations/{id}/settings`（PATCH 带 expectedVersion）、`POST /conversations/{id}/clear`（`clearThroughSequence` + `confirmed`）。

**通知**：`GET /notifications`（type ∈ comment/reaction/announcement）、`PUT /notifications/{id}/read`、`POST /me/notifications/read`、`GET /me/unread`。

**资料**：`PATCH /me`、`GET/PATCH /me/preferences`、`PUT/DELETE /me/blocks/{userId}`。

**媒体**：`POST /images`（multipart，purpose=avatar/post/report，≤10MB 业务上限 / 11MB multipart 上限，临时资源 24h 过期）、`GET /images/{id}/access`（换 60s 签名 URL）、`GET /images/{id}/content?grant=`（签名校验返回字节，`private, no-store`）、`DELETE /images/{id}`。

**内测门禁**：`GET /beta/access`、`POST /beta/redeem`（5/60s）、管理端 `GET/PATCH /admin/beta`、`POST /admin/beta/initialize`（生成 `XK-XXXXXX-XXXXXX` 码并切 beta）。

**运营**：公告 `GET /announcements?scope=`(+`/{id}`) 公开；管理端 `/admin/announcements`（草稿→发布→撤回→删除，全 expectedVersion）；通知任务 `/admin/notifications`（草稿→受众预览→发送，发送需 `audienceFingerprint` 一致防受众漂移）。

**治理**：`POST /reports`（`{targetType∈post|comment|user, reasonCode, description≤200, evidenceImageIds≤3, clientRequestId}`，同目标待处理唯一）；管理端 `/admin/reports`(+`/{id}/resolution` 一次性处置)、`/admin/users/{id}/restrictions|ban|release`。

**后台聚合/设置/审计/反馈**：`/admin/dashboard|posts|comments|users`（列表/详情/批量隐藏/删除）、`/admin/settings/content-review`(+`/reset`)、`/admin/audit-events`(+`/{id}`/`/export` CSV)、`POST /feedback`（用户）+ `GET /admin/feedback`。

**健康**：`GET /api/v1/health`（**无 profile 常驻**）、`GET /actuator/health`。

**WebSocket**：`/api/v1/ws`（auth profile）。握手必须 `Authorization: Bearer`，URL 不允许 query；服务端**单向**推送 `{eventId, type, occurredAt, payload[, conversationId, sequence]}`；客户端发消息即被关闭；每秒校验会话有效性；推送失败仅记日志由 HTTP 补取。

### 2.3 鉴权机制

- JWT HS256，密钥 `XINKELINK_JWT_SECRET_BASE64`（Base64 解码后 **≥32 字节**否则启动失败）；issuer `xinkelink`，aud：用户 `xinkelink-mini` / 管理员 `xinkelink-admin`；claims 白名单严格校验（`iss/sub/sid/actorType/aud/iat/exp/jti`，多一个或少一个都判定失败）。
- **access token 900 秒**；refresh token 只存 SHA-256 摘要于 `xl_auth_session`（用户 30 天，管理员 8 小时），刷新走 `SELECT ... FOR UPDATE` + `version` 乐观锁轮换，**轮换不延长 expires_at**（30 天绝对过期，无滑动续期）。
- **每次请求**都回库查 `xl_auth_session` 复核：会话吊销/过期、用户封禁（`ACCOUNT_BANNED`）、软删 —— JWT 有效但会话吊销同样 401。
- 乐观锁两形态：body `expectedVersion`（PATCH/命令类，冲突 → `VERSION_CONFLICT`）与 header `If-Match: "N"`（DELETE 类，缺失 → `PRECONDITION_REQUIRED`，格式错 → `VALIDATION_FAILED`）。
- 通道限制：发布/评论/私信分别检查 `xl_user` 的 `restrict_*` 列 → `ACTION_RESTRICTED`。
- 管理接口**三层鉴权**：路由 `hasRole("ADMIN")` → 会话回查 → service 层 `support.requireAdmin(principal)`。
- **服务端无 CORS 配置**（全仓库零 `@CrossOrigin`）；`XINKELINK_ADMIN_ALLOWED_ORIGINS` 是 **CSRF Origin 白名单**，不是 CORS 白名单。管理后台依赖 Vite 开发代理。

### 2.4 Beta 门禁

`xl_beta_stage` 单例（缺省 open）。管理员 initialize 后切 `beta` 阶段：此时除白名单（auth、health、`GET /beta/access`、`POST /beta/redeem`、`GET/PATCH /me`、`POST /images` 仅限头像、图片 content、admin）外的**一切** `/api/v1/**` 都要求已激活用户，否则 403 `BETA_ACCESS_REQUIRED`。`BetaAccessFilter` 在授权过滤器前运行；阶段切换通过 WebSocket 即时复检在线会话。

---

## 3. 数据库（MySQL 8.4，Flyway V1–V9，25 张表）

连接：隔离开发环境 `127.0.0.1:13306/xinkelink_dev`（由 `initialize-backend-dev.ps1` 拉起的本地 mysqld）。会话时区 UTC，业务日切按上海时区。

| 迁移 | 内容 |
|---|---|
| V1 | 身份：`xl_user`、`xl_user_preference`、`xl_user_block`、`xl_admin`、`xl_auth_session` |
| V2 | 内容：`xl_post`、`xl_comment`、`xl_like`、`xl_favorite`（原含 `xl_product`） |
| V3 | 私信与治理：`xl_conversation`、`xl_conversation_member`、`xl_message`、`xl_report`、`xl_moderation_action`（原含 `xl_conversation_product`） |
| V4 | 运营与媒体：`xl_announcement`、`xl_notification_task`、`xl_user_notification`、`xl_audit_log`、`xl_system_setting`、`xl_image_asset`、`xl_media_ref`、`xl_user_activity_day` |
| V5 | 统一补齐 24 条物理外键（**全部 ON DELETE RESTRICT**）；多态目标/认证主体/最近消息保持逻辑关联 |
| V6 | 放开 product 相关 CHECK |
| V7 | 内测门禁：`xl_beta_stage`（单例）、`xl_beta_code` |
| V8 | `xl_feedback` |
| V9 | **下线二手市场**：建 5 张临时归档表 → 按序删表/解绑 → `DROP TABLE xl_conversation_product`、`xl_product` → `xl_message` 删商品三列 → 收窄全部 CHECK → 删临时表 |

**25 张表逐表速查**：

| # | 表 | 关键列与约束 |
|---|---|---|
| 1 | `xl_user` | UNIQUE `uk_user_wechat(wechat_app_id, wechat_open_id)`；`nickname/bio/major/class_name/enrollment_year`；`ban_state/ban_until`；`restrict_publish|comment|message`；`avatar_image_id`→FK；软删 + `version`；4 个索引 |
| 2 | `xl_user_preference` | PK `user_id`；`messages/interactions/announcements/sound` 四布尔；`version` |
| 3 | `xl_user_block` | PK `(owner_id, blocked_user_id)`；CHECK 不同人；解除时物理删行 |
| 4 | `xl_admin` | UNIQUE `uk_admin_login`；`password_hash` BCrypt(12)；`role='ADMIN'`；`status=active/disabled`；`version` |
| 5 | `xl_auth_session` | `subject_type/subject_id`（逻辑关联）；UNIQUE `uk_session_refresh(refresh_hash BINARY(32))`；`refresh_generation`；`csrf_hash`；`expires_at/revoked_at/last_used_at`；`version` |
| 6 | `xl_post` | FK `author_id`；UNIQUE `uk_post_request(author_id, client_request_id)` + `request_hash`；`category` CHECK ∈ (`campus,lost_found,study,help`)；`is_anonymous/anonymous_label`；`status/review_state`；软删 + `version`；3 个 feed 索引 |
| 7 | `xl_comment` | 两级：`root_id`/`reply_to_id` 自引用 FK；`target_type` V9 后 CHECK = 'post'；UNIQUE `uk_comment_request`；`content≤1000`；软删 + `version` |
| 8 | `xl_like` | PK `(user_id, target_type, target_id)`；target CHECK ∈ (post, comment)；取消时物理删行 |
| 9 | `xl_favorite` | PK 同构；target CHECK = 'post' |
| 10 | `xl_conversation` | UNIQUE `uk_conversation_pair(low_user_id, high_user_id)`（CHECK low<high）；`next_sequence`；`last_message_id`（逻辑指针，无 FK）；`version` |
| 11 | `xl_conversation_member` | PK `(conversation_id, user_id)`；`read_through_seq/cleared_through_seq`（CHECK read≥cleared）；`muted/pinned`；`version` |
| 12 | `xl_message` | UNIQUE `uk_message_sequence(conversation_id, sequence_no)` + `uk_message_client(sender_id, client_message_id)`；`kind` CHECK 恒 = 'text'；`text≤1000`；`moderation_state`；**隐藏后保留序号占位** |
| 13 | `xl_report` | `reporter_id`/`target_user_id`/`resolved_by` FK；`target_type` ∈ (post, comment, user, **message**——但用户侧禁 message)；`target_snapshot JSON`；UNIQUE `uk_report_request`；**`pending_guard` 生成列 + `uk_report_pending`**（同目标只允许一条待处理）；8 个 CHECK |
| 14 | `xl_moderation_action` | UNIQUE `uk_moderation_operation`；`before_state/after_state JSON`；只追加 |
| 15 | `xl_announcement` | UNIQUE `uk_announcement_request`；`category` ∈ (community, activity, safety)；`scope` ∈ (all, forum)；`status` draft→published→withdrawn；`version` |
| 16 | `xl_notification_task` | UNIQUE `uk_task_request` + `uk_task_send(send_operation_id)`；`audience` ∈ (all, active, recent)；`audience_fingerprint CHAR(64)`；`version` |
| 17 | `xl_user_notification` | UNIQUE `uk_notice_event(recipient_id, event_key)`；`type` ∈ (comment, reaction, announcement)；`event_kind` 8 种；`read_at/active`；**运行账号仅 SELECT+INSERT** |
| 18 | `xl_audit_log` | UNIQUE `uk_audit_operation`；`actor_type=admin/system`；`module` 6 种；`outcome` 3 种；`changed_fields JSON ARRAY`；**只追加，运行账号不可 UPDATE/DELETE** |
| 19 | `xl_system_setting` | PK `setting_key` CHECK = 'content_review'；`value_json JSON OBJECT`；`effect_mode` 恒 'configuration_only'；`version` |
| 20 | `xl_image_asset` | UNIQUE `uk_asset_key(storage_key)`；`purpose` ∈ (avatar, post, report)；`sha256 BINARY(32)`；`state` temporary→ready→deleting→deleted；临时 24h；`version` |
| 21 | `xl_media_ref` | PK `(owner_type, owner_id, position)`；UNIQUE `uk_media_owner_image`；`owner_type` ∈ (user_avatar, post, report_evidence, report_snapshot)；CHECK `user_avatar` 必须 position=0、`report_evidence` position<3 |
| 22 | `xl_user_activity_day` | PK `(user_id, activity_date)`；**当前无任何生产写入**（管理端显式声明 dau 不可用） |
| 23 | `xl_beta_stage` | 单例 `id=1` CHECK；`phase` ∈ (open, beta)；`initialized`；`version BIGINT`（**与其他表 INT 不一致**） |
| 24 | `xl_beta_code` | UNIQUE `code` + UNIQUE `user_id`；`used_at`；**无 version/updated_at/deleted_at** |
| 25 | `xl_feedback` | `content≤1000`；UNIQUE `uk_feedback_request(user_id, client_request_id)`；**无 version/updated_at/deleted_at，无 CHECK** |

**已删除**：`xl_product`（V2）、`xl_conversation_product`（V3）。

通用约定：软删 `deleted_at`；可变表都有 `version INT UNSIGNED`；幂等键唯一约束；枚举用 CHECK 兜底；所有外键 `ON DELETE RESTRICT`（禁止物理级联）。

**改 SQL 后的快速自检**：`apps/server/src/test/resources/schema-contract.ps1`（静态文本契约，不连库）+ `test/java/cn/xinkelink/database/DatabaseMigrationIT.java`（真实 MySQL 8.4 验收，断言恰好 25 张表、9 个版本、READ-COMMITTED、UTC、迁移/运行账号不同、外键 RESTRICT、运行账号无 DDL/审计写权限）。

---

## 4. 缓存（Redis）

**唯一的业务用途是登录限流**（`auth/ratelimit/RedisAuthRateLimiter.java`，StringRedisTemplate）。全仓库只有 3 处生产引用：

- key：`xinkelink:auth:rate:{operation}:{SHA-256(key) 的 hex}`，operation ∈ `wechat|admin|refresh|beta`（其它值直接抛 `VALIDATION_FAILED`）。SHA-256 摘要化避免 IP/账号明文进 Redis。
- Lua 脚本原子 INCR + `EXPIRE 60` 固定窗口；后续请求不延长窗口。
- 限额：微信登录 10/60s(IP)、刷新 30/60s(IP)、管理员登录 5/60s(IP+账号双键)、beta 兑换 5/60s(IP+账号)；超限 429 `RATE_LIMITED`。
- **fail-closed**：Redis 抛任何 `RuntimeException` → 503 `SERVICE_UNAVAILABLE`（**不放行**，异常被吞不保留 cause，因为可能含连接凭据）。
- **没有** `@Cacheable`/`CacheManager`、会话不存 Redis（存 MySQL）、无分布式锁（并发靠 MySQL 行锁 + 唯一键）、无缓存/计数/排行榜（帖子计数靠 `COUNT(*)` 子查询）。
- 配置：默认 `127.0.0.1:16379`，无密码，connect-timeout 2s。

---

## 5. 管理后台（apps/admin）

Vue 3.5 + TS 5.9 + Vite 8 + Pinia + Element Plus + vue-router；测试 Vitest + Playwright；要求 node ≥ 24。

- 路由：`/login` + `/admin/dashboard|posts|comments|reports|users|announcements|notifications|logs|settings|beta|feedback`（未列出的导航项落到 DevelopingView）。
- 认证：accessToken 存内存 + csrfToken 存 sessionStorage；refresh token 由服务端 HttpOnly Cookie 承载；401 自动刷新并重放；所有请求带 `X-CSRF-Token` 与 `credentials: 'same-origin'`。
- 后端代理：`apps/admin/vite.config.ts:16` 把 `/api` 代理到 `http://127.0.0.1:18081`。
- 调用的接口：`/admin/` 下 dashboard、posts/comments（列表/详情/批量 status/delete）、reports(+resolution)、users(+restrictions/ban/release)、announcements、notifications（含 audience-preview/send）、settings/content-review、audit-events(+export)、beta、feedback；图片预览走 `/api/v1/images/{id}/access`。
- **开发时需 HTTPS**：`XINKELINK_DEV_CERT` / `XINKELINK_DEV_KEY` 指定本地证书与私钥，访问 `https://localhost:5173`（管理员刷新 Cookie 要求 secure）。

---

## 6. 配置与启动

### 环境变量（`XINKELINK_*`）

| 变量 | 默认 | 必填 | 说明 |
|---|---|---|---|
| `XINKELINK_DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 无 | **是**（db profile） | JDBC 与运行账号 |
| `XINKELINK_MIGRATION_USERNAME` / `MIGRATION_PASSWORD` | 无 | **是** | 迁移账号（**须与运行账号不同**） |
| `XINKELINK_JWT_SECRET_BASE64` | 无 | **是**（auth profile） | HS256 密钥，Base64 解码 ≥32 字节；**无默认值，缺失直接启动失败** |
| `XINKELINK_WECHAT_APP_ID` / `SECRET` | 空 | 否 | 未配 → `WECHAT_NOT_CONFIGURED` |
| `XINKELINK_ADMIN_ALLOWED_ORIGINS` | 空 | 否 | 空 → 后台登录必失败；必须是 https 或 127.0.0.1/localhost/[::1] |
| `XINKELINK_REDIS_HOST` / `PORT` / `PASSWORD` | 127.0.0.1 / 16379 / 空 | 否 | |
| `XINKELINK_IMAGE_STORAGE` | `local` | 否 | `local`/`oss` |
| `XINKELINK_IMAGE_DIRECTORY` | `.local/backend-images` | 否 | local 模式落盘目录 |
| `XINKELINK_OSS_BUCKET` / `REGION` / `ENDPOINT` / `ACCESS_KEY_ID` / `ACCESS_KEY_SECRET` | — | oss 模式必填 | endpoint 强制 https 且 host 必须匹配 `oss-<region>.aliyuncs.com` |
| `XINKELINK_DEV_CERT` / `XINKELINK_DEV_KEY` | 无 | 否 | 仅 admin 前端本地 HTTPS |

`.env.example`（`apps/server/.env.example`）**不会自动加载**，仅作变量名清单。

### Profile 组合

| profile | 作用 |
|---|---|
| （无） | 仅注册 `HealthController`；`/api/**` 被 `denyAll` |
| `db` | 启用 DataSource + Flyway（`application-db.yml` 通过整体重定义 `spring.autoconfigure.exclude` 列表来重新启用 DataSource） |
| `auth` | **必需**：注册全部业务 Controller/Service/Mapper、JWT、BCrypt、Redis 限流、微信换码、存储、实时推送 |
| `dev` | 打开 OpenAPI / Swagger UI |
| `redis` | 仅打开 actuator 的 redis 健康检查（auth profile 已含 Redis 连接配置） |

**默认 profile 组合**：`scripts/start-backend-dev.ps1:4` → `'db,dev,auth'`（**不含 `redis`**）。
测试用组合：`db,auth`（多数 IT）、`db,auth,redis`（`Day5IntegrationServer.java:17`）。

### 启动

1. `scripts/initialize-backend-dev.ps1` —— 拉起隔离 MySQL 13306、生成 `.local/backend-day1/private/runtime.json`。
2. `scripts/start-backend-dev.ps1` —— 默认 `-Port 18081 -Profiles db,dev,auth`，第 104 行显式传 `--server.port=$Port`（yml 仅影响手动启动）。
3. 健康检查：`/actuator/health` = UP 且 `/api/v1/health` 返回 `application=xinkelink-server`。

小程序端演示模式不需要后端；后端模式才需要以上全部。

---

## 7. 已知问题与待办（接手前先看）

> 完整清单见 `docs/business-flows.md` §4（前端）与本节（后端）。以下按优先级。

### 7.1 🔴 后端高危

1. **无 `spring.profiles.active` 默认值** —— `application.yml` 全文 36 行没有这个键。直接 `java -jar` 启动 → 不激活 auth → 业务 Controller 全不注册 → `/api/**` 全 403，只剩 health。**必须用 `scripts/start-backend-dev.ps1` 或显式传 `--spring.profiles.active=db,auth`**。
2. **profile `auth` 隐式强依赖 `db`** —— `application.yml:4-7` 把 `DataSourceAutoConfiguration` 放进 `spring.autoconfigure.exclude`，`application-db.yml:1-4` 靠**整体替换同一个 List** 重新启用它。只激活 `auth` → "Failed to configure a DataSource"，报错信息与真实原因无关。
3. **Redis 健康检查默认关闭，但 Redis 是登录链路强依赖** —— `management.health.redis.enabled: false`，且默认 profile 不含 `redis`；Redis 挂时登录全 503 但 `/actuator/health` 仍报 UP，启动脚本也察觉不到。**建议**：把该开关移到 `application-auth.yml`。
4. **每次登录 `version = version + 1`，与资料乐观锁令牌冲突** —— `AuthMapper.touchUser` 每次登录递增 `xl_user.version`，而 `ProfileService` 用 `version` 做资料更新令牌 → 用户在编辑页停留期间重新登录，保存资料必然 409。

### 7.2 🟠 后端中危

5. `xl_user_notification` **缺 `event_key` 前导索引** → 取消点赞/收藏时全表扫描。建议 `KEY idx_notice_event_key (event_key, active)`。
6. `xl_report` / `xl_moderation_action` 的 CHECK 允许 `target_type='message'`，但用户侧创建接口硬拒 message（`ReportModerationService.java:232`）→ "预留未开放"还是遗漏，需明确。
7. `xl_auth_session.refresh_generation` / `last_used_at` 是**死列**（只写不读）。
8. `xl_message.request_hash` 存了但从不比对（实际用 `conversationId + text` 比对）。
9. `xl_user_activity_day` **无任何生产写入**（25 张表里 1 张是纯占位）。
10. `BetaAccessFilter` 对每个非白名单 `/api/v1/` 请求都多打一次 DB（`SELECT phase FROM xl_beta_stage WHERE id=1`）。
11. `RealtimeEvents.checkSessions` **每秒对每个连接做两次 DB 查询** → N 个在线客户端 = 每秒 2N 次查询。
12. 服务端**完全无 CORS 配置**；管理后台一旦脱离 Vite 代理独立部署会全部失败。
13. `xl_beta_stage.version` 是 `BIGINT UNSIGNED`，其他表统一 `INT UNSIGNED`。
14. `xl_beta_code` / `xl_feedback` 缺 `version` / `updated_at` / `deleted_at`，`xl_feedback` 无任何 CHECK。

### 7.3 🟠 前端高危（演示模式访客越权）

> 详见 `docs/business-flows.md` §4。

15. **访客可深链读上一账号私信** —— `pages/chat-settings/index.ts:9-13` `onLoad` → `reload()` → `readConversation` **不判登录**；`:38-42` `onHistoryInput` 会把上一账号的消息正文渲染出来。真机侧已有守卫（`backend/pages.ts:568` `protectedActions`）。
16. **访客可读上一账号资料与「我的帖子」** —— 抽屉 `onNavigate` 对 `profile` 路由不判登录（`components/app-shell/index.ts:131`）；`pages/profile/index.ts:64,65` 的 `onMyPostsTap` / `onCollectionsTap` 无 `requireLocalSession`；`pages/my-posts/index.ts:8` onShow 同样无守卫。
17. **"已登录"判定有 5 套实现，回退值互相矛盾** —— `forum-post-card/index.ts:9-14`、`contact/controller.ts:9-14`、`page-header/index.ts:71` catch→**true**；`app-shell/index.ts:27` catch→维持旧值；只有 `viewer.ts:22` catch→guest。存储损坏时同一屏会出现三种身份结论。**修复方向**：全部改用 `getViewerRole(wx) === 'guest'`。

### 7.4 🟡 前端中低危

18. 「编辑帖子」入口不判登录（`forum-post-card/index.ts:110`）→ 访客可回填帖子内容到 UI。
19. 访客可见并可写**搜索历史**（`pages/search/index.ts:32,62`）。
20. 演示模式的**举报 / 反馈**无登录守卫，真机模式有 → 两模式权限不一致。
21. 「转发」两模式能力不一致：演示真 toggle，真机 toast「即将上线」。
22. `readLocalSession` 在 key 缺失时**返回 true**（默认已登录）→ 新设备上 `getViewerRole` 返回 `self`、所有写操作放行。与「访客=未登录」规则冲突，需明确写死语义。
23. `pages/contact/controller.ts:22` 真机访客登录**未 await** → 失败被吞且 UI 已切「已登录」。
24. `pages/search/index.ts:69` 互动写入无 try/catch 且未校验 id 是否在当前结果内 → 未捕获异常。
25. `profile-auth-sheet` 的 `bind:confirm/bind:cancel` 在**演示模式全 17 页未实现**（只在 `backend/pages.ts`），目前不可达但未来 setData `authVisible` 即 "handler not found"。→ `BUGS.md` 第 4 条。
26. 访客可见发帖草稿与草稿页（`pages/drafts/index` 已注册但无入口，仅深链可达）。
27. 编辑帖子成功后**不清理草稿**（`backend/pages.ts:257` 仅新建态清）。
28. `backend/pages.ts:231` 评论删除 **未做 URL 编码**（同文件其它地方都做了）。
29. `pages/login/` 是已注销的孤儿代码（app.json 未注册，但 `registerPage(...,'login')` 与 `definitions.login` 都还在）。
30. `xinkelink.search.entry` 只写不读（死 key）。
31. 真机模式**每页 onShow 都发一次 `/beta/access`**（pending 缓存仅同 token 内生效）。

### 7.5 🟡 其它值得注意

- **帖子分类前后端不一致**：前端 8 板块（含 `secondhand/complain/buddy`），V9 后端 CHECK 收窄为 `campus/lost_found/study/help` → **真机模式发新分类帖子会被 400**。要么后端扩 CHECK，要么前端收缩板块。**这是当前最容易踩到的功能级问题。**
- **真机侧小缺口**：编辑帖子不带 `anonymous`（后端 `UpdatePost` 无该字段，硬加会 400）；`ForumService.author()` 不返回 `major`（前端 `authorMajor` 恒空但 UI 未消费）。
- **WXSS @import 位置违规**：`pages/report|chat-settings|profile-edit/index.wxss` 的 `@import "../../styles/page-polish.wxss"` 写在规则之后被忽略；移到首行会改变层叠顺序，需重新目验这三个旧排版页。
- **前端死代码**：`pages/login/`、`pages/drafts/`、`service.collections()` 无调用点。
- **端口四方已统一到 18081**（2026-11-13，`3d7f937`）：`backend/config.ts:2`、`application.yml:17`、`apps/admin/vite.config.ts:16`、`README.md` 全部一致；`scripts/start-backend-dev.ps1` 默认 `-Port 18081`。**注意**：`backend/config.ts:1` 的注释仍写"手机热点局域网地址"，但值是回环地址 `127.0.0.1` —— **真机联调必须手改此文件**（容易被误提交）。
- **后端启动至今未在本机验证成功**：缺 pwsh 7.4（脚本 `#Requires -Version 7.4`，系统仅 PS 5.1）、隔离 MySQL 13306 未运行、Redis 16379 未运行、`XINKELINK_JWT_SECRET_BASE64` 未设置。**Codex 接手后第一件事：在补齐上述环境后跑通 `/api/v1/health` 与 `/api/v1/posts`。**
- **验证/自测习惯**：`apps/miniprogram` 改动后跑 `npm run check`（260 条）；微信开发者工具对新建 .ts 文件的编译缓存不敏感，验证运行时行为前先手动点「编译」；`cli.bat open --project` 会重启模拟器导致自动化读不到页面数据。
- 历次踩坑与决策记录在 `.workbuddy/memory/2026-11-12.md`、`2026-11-13.md`，排查前值得先读。

---

## 8. 交接后第一周的建议顺序

1. 补齐本机后端环境（pwsh 7.4 / MySQL 13306 / Redis 16379 / JWT 密钥），跑通 `mvn test`（~130 单测，不需要 Docker）与 `/api/v1/health`。
2. 修 §7.3 的 3 条前端高危（chat-settings 守卫、profile/my-posts 守卫、统一 `getViewerRole`），补测试。
3. 决策分类口径（§7.5 第一条），这是发布前的阻塞项。
4. 决策 `readLocalSession` 默认值语义（§7.4 第 22 条），并同步 `IDENTITY-RULES.md` 的规则表。
5. 若要跑集成测试：`mvn -Pdb-integration verify`（~160 用例，需 Docker + MySQL 8.4）。

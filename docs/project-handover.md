# 信科 Link · 项目全貌说明（交接用）

> 生成时间：2026-11-13。事实来源：代码通读（前端、后端、迁移 SQL、配置、脚本），不是凭记忆写的。
> 用途：交给任何 AI / 新人，读完即可接手开发与排查。

---

## 0. 一句话与仓库结构

**信科 Link** 是一个校园社区小程序：发帖 / 评论 / 点赞 / 收藏 / 私信 / 通知 / 搜索 / AI 写作助手，界面按 X（Twitter）风格黑白化复刻。三端组成：

```
D:/CODE/XinKeLink
├─ apps/miniprogram   微信小程序（用户端，原生小程序 + TypeScript）
├─ apps/admin         管理后台（Vue 3 + Element Plus）
├─ apps/server        服务端（Spring Boot 4.1.1 / Java 21 / MySQL 8.4 / Redis）
├─ docs/              设计稿、方案、验收记录
├─ scripts/           后端隔离环境初始化与启动脚本（PowerShell）
└─ .workbuddy/        工作日志（memory/*.md，含历次踩坑记录）
```

---

## 1. 微信小程序前端（apps/miniprogram）

### 1.1 技术栈与工程约定

- **原生微信小程序 + TypeScript**（无 uni-app/Taro），`project.config.json` 开启 typescript 编译插件，基础库 3.15.0。
- 样式统一 **px@375 基准**（已废弃 rpx），主色 `#0f1419`（墨黑），旧蓝 `#1d9bf0` 仅存于个别功能色（爱心粉红 `#f91880` 保留）。
- 图标全部由 `scripts/generate-x-icons.cjs` + `generate-icons.cjs` 生成到 `miniprogram/assets/icons/`（58 个），**不要手改 PNG**，改 `variants/aliases` 表后重新生成。
- 命令：`npm run typecheck`（tsc）、`npm test`（先 tsc 到 `.test-dist` 再跑 node:test，**238 条全绿**）、`npm run check:project`（工程一致性）、`npm run check`（三者串联）。
- 目录：
  - `miniprogram/pages/` 20 个注册页（app.json）+ 未注册的 `login/`（死代码）与 `drafts/`（注册但无入口）
  - `miniprogram/components/` 6 个组件
  - `miniprogram/features/` 业务域（forum/profile/contact/search/ai/media/moderation）
  - `miniprogram/backend/` **后端桥接层**（client/service/adapters/pages/config/preview/beta/demo-mode/realtime/image-urls）
  - `custom-tab-bar/`、`utils/`、`styles/`

### 1.2 双轨运行模式（理解本项目的钥匙）

每个页面文件 `index.ts` 保留一份**完整的本地实现**（MOCK_POSTS + 本地 storage），由 `backend/pages.ts` 的 `registerPage(wx, Page, name)` 决定走哪套：

- **演示模式**（`wx.__fixtureMode === true`）：开发者工具里自动开启（`demo-mode.ts` 按 `platform === 'devtools'` 判定，可用 `wx.setStorageSync('xinkelink.mode', 'live'|'local')` 强制切换）。`registerPage` 直接 `Page(fixture)`，**不启动 Java 服务也能跑全功能**，数据存本地 storage。
- **后端模式**（真机 / mode=live）：`definition = { ...fixture, ...common, ...implementation }` 合并 —— `common` 提供 onShow/reload/资料确认弹层/图片续期等公共行为，`definitions[name]` 覆盖业务方法。合并后若没有 `reload` 会自动补空实现（防止 common.onShow 抛 TypeError）。onLoad/onShow 还会被包一层 beta 门禁 + 图片授权续期。

**坑**：wxml 是两套模式共用的，改 wxml 绑定的方法时要确认 fixture 与 implementation 两边都有（历史上多次出现"演示模式死链"）。

### 1.3 页面清单（app.json 注册顺序）

| 页面 | 功能 | 备注 |
|---|---|---|
| `pages/beta-access/index` | 内测门禁/激活码 | app.json 首页（测试有断言，勿改顺序）；演示模式直接 reLaunch 进论坛 |
| `pages/forum/index` | 首页 feed | 8 板块 tab（推荐/最新/失物认领/二手闲置/校园生活/吐槽/互帮互助/找搭子），PAGE_SIZE=2 本地分页 |
| `pages/contact/index` | 私信 + 通知双 tab | 访客显示引导空态 |
| `pages/profile/index` | **我的主页** | 动态/获赞统计 + 帖子/喜欢双 tab；访客态显示登录引导条 |
| `pages/forum-detail/index` | 帖子详情 | 两级评论、按热度/按时间排序、底部回复栏 |
| `pages/forum-publish/index` | 发帖/编辑 | 支持草稿、匿名 chip、editId 编辑（演示+真机都支持） |
| `pages/contact-chat/index` | 聊天页 | 消息序号、已读、清空历史 |
| `pages/my-posts/index` | 我的帖子 | 含匿名帖管理、编辑、删除 |
| `pages/my-collections/index` | 喜欢（点赞列表） | 注意：页面名叫"喜欢"，走的是点赞接口 |
| `pages/profile-edit/index` | 编辑资料 | 头像持久化到 USER_DATA_PATH；有访客守卫 |
| `pages/user-home/index` | **公开主页** | 他人视角：简介 + 公开帖子数/获赞 + 分享·私信 + 帖子单 tab；本人+登录才有"编辑"按钮 |
| `pages/search/index` | 搜索 | 搜索历史 + 当周热榜（点赞前 5） |
| `pages/report/index` | 举报 | 支持 post/comment/user（演示模式本地存储） |
| `pages/chat-settings/index` | 聊天设置 | 置顶/免打扰/清空/屏蔽 |
| `pages/feedback/index` | 意见反馈 | 演示模式存本机，真机走后端 |
| `pages/notifications/index` | 通知 | 与 contact 共享 controller |
| `pages/ai/index` | Link AI | **本地写作助手**（features/ai/draft.ts），确定性整理不联网，免费不限次 |
| `pages/drafts/index` | 草稿箱 | 已注册、无入口 |
| `pages/new-chat/index` | 发起私信 | 聊过的同学列表 |
| `pages/account-feature/index` | 通用说明页 | 抽屉"+"跳转 |

### 1.4 组件清单

| 组件 | 职责 |
|---|---|
| `app-shell` | 右滑抽屉 = 设置中心：账号卡、通知偏好开关、反馈、关于、退出登录；内嵌 `profile-auth-sheet` 登录弹层；管理 tab 位移与滑动手势 |
| `page-header` | 各页左上角顶栏：头像按钮（打开抽屉）、标题、搜索态、右插槽（避让微信胶囊）；**订阅身份广播**实时刷新头像/访客态 |
| `forum-post-card` | 帖子卡片（列表/详情/主页三种形态）：头像点击分流（见 1.5）、点赞/转发/收藏、"⋯"菜单（本人有编辑/书签） |
| `forum-comment` | 评论行：赞/回复/删除/展开 |
| `profile-auth-sheet` | 微信头像昵称授权弹层（chooseAvatar + nickname input） |
| `back-header` | 二级页返回头 |
| `custom-tab-bar` | 5 tab（首页/搜索/AI/通知/私信）+ 发布 FAB + 未读角标；订阅身份广播刷新角标 |

### 1.5 核心机制（改代码前必读）

1. **身份事件广播**：`features/profile/identity-events.ts`（模块级订阅表）。`repository.ts` 的 `saveProfile` / `setLocalSession` 写入后自动 `emitIdentityChange()`，覆盖抽屉登录、访客一键登录、退出、资料编辑全部入口。`page-header` 与 `custom-tab-bar` 在 `attached` 订阅 + 立即刷新；真机侧另订阅 `ApiClient.onSessionChange`（`setMe`/`clear` 触发）。**没有它，页面内登录后顶栏不刷新**。
2. **头像持久化**：`features/profile/avatar.ts`。`persistProfileAvatar` 把 chooseAvatar 的临时路径复制到 `USER_DATA_PATH/xlk-profile-avatar-*`；`repairTransientAvatar` 在 `app.ts onLaunch` 自愈历史临时路径（失效则回退内置头像）。
3. **帖子头像跳转口径**（`forum-post-card.onAuthorTap`）：匿名/无 id → 打开帖子；**本人 + 已登录 → `/pages/profile/index`（我的主页）**；访客或别人 → `/pages/user-home/index?id=xxx`（公开主页）。公开主页的编辑按钮要求 `isSelf && loggedIn`。
4. **访客守卫**：通知/私信页访客显示引导空态；编辑资料三处入口全部拦截（user-home 按钮 `isSelf && loggedIn`、onEditTap requireLocalSession、profile-edit onLoad 弹一键登录）。
5. **图片授权链**（真机）：上传 `POST /images` → 展示走 `media()` 拼接 `/images/{id}/content?grant=`（grant 为 HMAC+AES-GCM 签名，**60 秒有效**）→ `PageImageRenewal` 在页面可见时自动续期，图片 `binderror` 触发重取。
6. **转发功能**：后端无 `/posts/{id}/repost` 接口，前端 `interaction()` 对 `reposted` 显式拦截并提示"转发功能即将上线"，**不要把它退回成收藏写请求**。
7. **乐观锁**：写操作带 `expectedVersion`（body）或 `If-Match: "N"`（header），409 冲突需刷新重试。
8. **幂等键**：发帖/评论/发消息/举报/反馈都带 `clientRequestId` / `clientMessageId`。

### 1.6 前端调用的后端接口（47 条，全部有实现）

`backend/client.ts` 的 `ApiClient` 负责：微信 code 登录、refresh 轮换、401 自动重试、multipart 上传、会话持久化（storage key `xinkelink.session.v1`）。基址 `backend/config.ts` 的 `BACKEND_ORIGIN`（见 §7 已知问题）。

小程序端调用（`/api/v1` 前缀省略）：`/auth/wechat|refresh|logout`、`/me`(GET/PATCH)、`/me/preferences`、`/me/summary`、`/me/posts`、`/me/favorites`、`/me/likes`、`/me/unread`、`/me/conversations/read`、`/me/notifications/read`、`/me/blocks/{userId}`、`/posts`(GET/POST)、`/posts/{id}`(GET/PATCH/DELETE)、`/posts/{id}/like|favorite|comments`、`/comments/{id}`(DELETE)、`/comments/{id}/like|replies`、`/search`、`/users/{id}`(+`/posts`)、`/conversations`(GET/POST)、`/conversations/{id}`(+`/messages|read|settings|clear`)、`/notifications`(+`/{id}/read`)、`/announcements`(+`/{id}`)、`/images`(POST)、`/images/{id}/access|content`、`/reports`、`/beta/access|redeem`、`/feedback`、WS `/ws`。

### 1.7 测试

`tests/*.test.cjs` 共 238 条（node:test），通过 `native-harness.cjs` / `page-harness.cjs` 在 Node 里直接执行 `.test-dist` 编译产物，可测组件 data/methods 与页面行为。改组件/页面务必同步补测试，`npm run check` 必须全绿再交付。

---

## 2. 服务端（apps/server）

### 2.1 技术栈与模块

Maven 单体，Java 21，Spring Boot 4.1.1，MyBatis-Plus 3.5.17，Flyway（V1–V9），Spring Security + JWT（资源服务器），spring-data-redis，WebSocket，springdoc（dev 才开）。图片存储默认本地落盘（`LocalImageStorage`），可切阿里云 OSS（`xinkelink.storage.provider=oss`）。

模块包（`cn.xinkelink.*`）：`auth`（登录/JWT/限流）、`forum`（帖子评论点赞收藏）、`content`（发现/搜索/公共鉴权分页工具）、`messaging`、`notification`、`profile`、`media`、`beta`、`operations`（公告/通知任务）、`moderation`（举报/处置/封禁）、`feedback`、`admin`（后台聚合）、`audit`、`settings`、`realtime`、`storage`、`health`、`common`（ApiResponse/错误码/游标/全局异常）。

**除 HealthController 外所有 Controller 都标 `@Profile("auth")`** —— 不激活 auth profile 时业务接口全部不存在。

### 2.2 接口全集

统一响应 `ApiResponse{ code, message, data, requestId, errors }`；错误码 40 个（`common/api/ErrorCode.java`），如 `UNAUTHENTICATED / TOKEN_EXPIRED / REFRESH_INVALID / VERSION_CONFLICT / BETA_ACCESS_REQUIRED / RATE_LIMITED / ACCOUNT_BANNED / ACTION_RESTRICTED`。

**认证（公开）**
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/wechat` | 微信 code 换 token 并 upsert 用户；限流 10/60s/IP |
| POST | `/api/v1/auth/refresh` | 刷新令牌（轮换，旧票据重放 → REFRESH_INVALID）；30/60s/IP |
| POST | `/api/v1/auth/logout` | 吊销当前会话 |

**管理员认证（公开，Cookie + CSRF）**
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/admin/auth/login` | BCrypt 校验；refresh 走 `xinkelink_admin_refresh` HttpOnly Cookie + `X-CSRF-Token`；Origin 白名单校验；5/60s |
| POST | `/api/v1/admin/auth/refresh` / `logout` | 轮换/吊销 |
| GET | `/api/v1/admin/me` | 当前管理员 |

**论坛**
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/posts` | feed（category/sort/keyword/cursor/size），公开 |
| POST | `/api/v1/posts` | 发帖 `{content≤2000, category, imageIds≤9, anonymous, clientRequestId}` |
| GET/PATCH/DELETE | `/api/v1/posts/{id}` | 详情（公开）/ 编辑（expectedVersion）/ 删除（If-Match） |
| GET | `/api/v1/me/posts` | 我的帖子 |
| PUT/DELETE | `/api/v1/posts/{id}/like`、`/favorite` | 点赞/收藏切换 |
| GET/POST | `/api/v1/posts/{id}/comments` | 一级评论 |
| GET | `/api/v1/comments/{id}/replies` | 二级回复 |
| DELETE | `/api/v1/comments/{id}` | 删评论（If-Match） |
| PUT/DELETE | `/api/v1/comments/{id}/like` | 评论点赞 |

**发现**
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/search` | 全局搜索（q/type/category/sort/cursor） |
| GET | `/api/v1/me/favorites`、`/me/likes`、`/me/summary` | 收藏/点赞/统计 |
| GET | `/api/v1/users/{id}`、`/users/{id}/posts` | 他人公开主页 |

**私信**：`GET/POST /conversations`、`GET /conversations/{id}`、`GET/POST /conversations/{id}/messages`（`beforeSequence/afterSequence/aroundMessageId/q`）、`PUT /conversations/{id}/read`、`POST /me/conversations/read`、`GET/PATCH /conversations/{id}/settings`、`POST /conversations/{id}/clear`。

**通知**：`GET /notifications`、`PUT /notifications/{id}/read`、`POST /me/notifications/read`、`GET /me/unread`。

**资料**：`PATCH /me`、`GET/PATCH /me/preferences`、`PUT/DELETE /me/blocks/{userId}`。

**媒体**：`POST /images`（multipart，purpose=avatar/post/report，≤11MB，临时资源 24h 过期）、`GET /images/{id}/access`（换 60s 签名 URL）、`GET /images/{id}/content?grant=`（签名校验返回字节）、`DELETE /images/{id}`。

**内测门禁**：`GET /beta/access`、`POST /beta/redeem`（5/60s）、管理端 `GET/PATCH /admin/beta`、`POST /admin/beta/initialize`（生成 30 个 `XK-XXXXXX-XXXXXX` 码并切 beta）。

**运营**：公告 `GET /announcements`(+`/{id}`) 公开；管理端 `/admin/announcements`（草稿→发布→撤回→删除，全 expectedVersion）；通知任务 `/admin/notifications`（草稿→受众预览→发送，发送需 `audienceFingerprint` 一致防受众漂移）。

**治理**：`POST /reports`（`{targetType∈post|comment|user|message, reasonCode, description≤200, evidenceImageIds, clientRequestId}`，同目标待处理唯一）；管理端 `/admin/reports`(+`/{id}/resolution` 一次性处置)、`/admin/users/{id}/restrictions|ban|release`。

**后台聚合/设置/审计/反馈**：`/admin/dashboard|posts|comments|users`（列表/详情/批量隐藏/删除）、`/admin/settings/content-review`(+`/reset`)、`/admin/audit-events`(+`/export` CSV)、`POST /feedback`（用户）+ `GET /admin/feedback`。

**健康**：`GET /api/v1/health`（无 profile 常驻）、`GET /actuator/health`。

**WebSocket**：`/api/v1/ws`（auth profile）。握手必须 `Authorization: Bearer`，URL 不允许 query；服务端单向推送 `{eventId, type, occurredAt, payload[, conversationId, sequence]}`；客户端发消息即被关闭；每秒校验会话有效性；推送失败仅记日志由 HTTP 补取。

### 2.3 鉴权机制

- JWT HS256，密钥 `XINKELINK_JWT_SECRET_BASE64`（Base64 解码后 **≥32 字节**否则启动失败）；issuer `xinkelink`，aud：用户 `xinkelink-mini` / 管理员 `xinkelink-admin`；claims 白名单严格校验（iss/sub/sid/actorType/aud/iat/exp/jti）。
- **access token 900 秒**；refresh token 只存 SHA-256 摘要于 `xl_auth_session`（用户 30 天，管理员 8 小时），刷新走 `FOR UPDATE` + `version` 乐观锁轮换。
- **每次请求**都查 `xl_auth_session` 复核：会话吊销/过期、用户封禁（`ACCOUNT_BANNED`）、软删 —— JWT 有效但会话吊销同样 401。
- 乐观锁两形态：body `expectedVersion`（PATCH/命令类）与 header `If-Match: "N"`（DELETE 类，缺省 428）；所有可变表都有 `version` 列。
- 通道限制：发布/评论/私信分别检查 `xl_user` 的 `restrict_*` 列 → `ACTION_RESTRICTED`。

### 2.4 Beta 门禁

`xl_beta_stage` 单例（缺省 open）。管理员 initialize 后切 `beta` 阶段：此时除白名单（auth、health、`GET /beta/access`、`POST /beta/redeem`、`GET/PATCH /me`、`POST /images` 仅限头像、图片 content、admin）外的**一切** `/api/v1/**` 都要求已激活用户，否则 403 `BETA_ACCESS_REQUIRED`。`BetaAccessFilter` 在授权过滤器前运行；阶段切换通过 WebSocket 即时复检在线会话。

---

## 3. 数据库（MySQL 8.4，Flyway V1–V9）

连接：隔离开发环境 `127.0.0.1:13306/xinkelink_dev`（由 `initialize-backend-dev.ps1` 拉起的本地 mysqld）。会话时区 UTC，业务日切按上海时区。

| 迁移 | 内容 |
|---|---|
| V1 | 身份：`xl_user`、`xl_user_preference`、`xl_user_block`、`xl_admin`、`xl_auth_session` |
| V2 | 内容：`xl_post`、`xl_comment`、`xl_like`、`xl_favorite`（原含 `xl_product`） |
| V3 | 私信与治理：`xl_conversation`、`xl_conversation_member`、`xl_message`、`xl_report`、`xl_moderation_action` |
| V4 | 运营与媒体：`xl_announcement`、`xl_notification_task`、`xl_user_notification`、`xl_audit_log`、`xl_system_setting`、`xl_image_asset`、`xl_media_ref`、`xl_user_activity_day` |
| V5 | 统一补齐物理外键（ON DELETE RESTRICT） |
| V6 | 放开 product 相关 CHECK |
| V7 | 内测门禁：`xl_beta_stage`（单例）、`xl_beta_code` |
| V8 | `xl_feedback` |
| V9 | **下线二手市场**：归档后 DROP `xl_product`、`xl_conversation_product`，收窄全部 CHECK（举报目标仅 post/comment/user/message、like 仅 post/comment、favorite 仅 post、图片 purpose 去 product 等） |

最终表速查：

- **用户域**：`xl_user`（唯一 `(wechat_app_id, wechat_open_id)`；`ban_state`/`ban_until`/`restrict_publish|comment|message`；软删；version）、`xl_user_preference`（四类提醒布尔）、`xl_user_block`（复合 PK，物理删行）、`xl_admin`（BCrypt(12)、role、status）
- **认证**：`xl_auth_session`（唯一 `refresh_hash` BINARY(32)、`subject_type`、`refresh_generation`、`csrf_hash`、`revoked_at`）
- **内容**：`xl_post`（唯一 `(author_id, client_request_id)`；category/is_anonymous/anonymous_label；status/review_state；软删；version）、`xl_comment`（root_id/reply_to_id 两级）、`xl_like`（复合 PK `(user_id,target_type,target_id)`）、`xl_favorite`
- **私信**：`xl_conversation`（唯一 `(low_user_id, high_user_id)`、next_sequence）、`xl_conversation_member`（read_through_seq/cleared_through_seq/muted/pinned）、`xl_message`（唯一 `(conversation_id, sequence_no)` 与 `(sender_id, client_message_id)`；隐藏后保留序号占位）
- **通知/公告**：`xl_user_notification`（唯一 `(recipient_id, event_key)`；type/event_kind/target/read_at/active）、`xl_notification_task`（audience/status/audience_fingerprint）、`xl_announcement`（scope/category/status draft→published→withdrawn）
- **治理**：`xl_report`（唯一 pending 守卫 `(reporter_id,target_type,target_id,pending_guard)` 生成列 + 幂等键；target_snapshot JSON）、`xl_moderation_action`（只追加）
- **媒体**：`xl_image_asset`（storage_key/sha256/state temporary→ready→deleting→deleted/临时 24h）、`xl_media_ref`（owner_type/position）
- **其他**：`xl_audit_log`（只追加）、`xl_system_setting`（JSON + version）、`xl_user_activity_day`、`xl_beta_stage`/`xl_beta_code`、`xl_feedback`

通用约定：软删 `deleted_at`；可变表都有 `version INT UNSIGNED`；幂等键唯一约束；枚举用 CHECK 约束兜底。

---

## 4. 缓存（Redis）

**唯一的业务用途是登录限流**（`auth/ratelimit/RedisAuthRateLimiter.java`，StringRedisTemplate）：

- key：`xinkelink:auth:rate:{operation}:{sha256hex(key)}`，operation ∈ `wechat|admin|refresh|beta`；Lua 原子 INCR + 60s 固定窗口。
- 限额：微信登录 10/60s（IP）、刷新 30/60s（IP）、管理员登录 5/60s（IP+账号）、beta 兑换 5/60s（IP+账号）；超限 429 `RATE_LIMITED`。
- **refresh token / 会话不进 Redis**，全部在 MySQL `xl_auth_session`。没有验证码、黑名单等其他用途。
- **fail-closed**：Redis 挂了这 4 个登录入口直接 503 `SERVICE_UNAVAILABLE`（故意不放行）；其余业务接口不依赖 Redis，照常工作。

---

## 5. 管理后台（apps/admin）

Vue 3.5 + TS 5.9 + Vite 8 + Pinia + Element Plus + vue-router；测试 Vitest + Playwright；要求 node ≥ 24。

- 路由：`/login` + `/admin/dashboard|posts|comments|reports|users|announcements|notifications|logs|settings|beta|feedback`（未列出的导航项落到 DevelopingView）。
- 认证：登录拿 accessToken（内存）+ csrfToken（sessionStorage）；refresh token 由服务端 HttpOnly Cookie 承载；401 自动刷新并重放；所有请求带 `X-CSRF-Token` 与 `credentials: 'same-origin'`。
- 后端代理：vite dev 把 `/api` 代理到 `127.0.0.1:18081`（见 §7 端口问题）。
- 调用的接口：`/admin/` 下 dashboard、posts/comments（列表/详情/批量 status/delete）、reports(+resolution)、users(+restrictions/ban/release)、announcements、notifications（含 audience-preview/send）、settings/content-review、audit-events(+export)、beta、feedback；图片预览走 `/api/v1/images/{id}/access`。

---

## 6. 配置与启动

### 环境变量

- **必需**：`XINKELINK_DB_URL / DB_USERNAME / DB_PASSWORD / MIGRATION_USERNAME / MIGRATION_PASSWORD`、`XINKELINK_JWT_SECRET_BASE64`（≥32 字节）
- **条件**：`XINKELINK_WECHAT_APP_ID/SECRET`（未配 → `WECHAT_NOT_CONFIGURED`）、`XINKELINK_ADMIN_ALLOWED_ORIGINS`（管理端登录 Origin 白名单）、`XINKELINK_IMAGE_STORAGE`（local/oss）、`XINKELINK_REDIS_HOST/PORT/PASSWORD`（默认 127.0.0.1:16379）、OSS 四件套（`XINKELINK_OSS_ENDPOINT/REGION/ACCESS_KEY_ID/ACCESS_KEY_SECRET`）

### 启动

- 开发隔离环境：先 `scripts/initialize-backend-dev.ps1`（拉起隔离 MySQL 13306、生成 `.local/backend-day1/private/runtime.json`），再 `scripts/start-backend-dev.ps1 -Port <port> -Profiles <profiles>`。
- **profiles 默认是 `db,dev,auth`**（2026-11-13 起，原为 `db,dev` 导致业务 Controller 不注册）—— 显式传 `-Profiles` 可覆盖。
- 健康检查：`/actuator/health` = UP 且 `/api/v1/health` 返回 `application=xinkelink-server`。
- 小程序端演示模式不需要后端；后端模式才需要以上全部。

---

## 7. 已知问题与待办（接手前先看）

1. **端口四方不一致（最优先）**：小程序 `backend/config.ts` 写死 `http://127.0.0.1:18086`（文档标注"临时·已停止"）；README 与 admin vite 代理是 `18081`；`application.yml` 默认 `8080`；启动脚本默认 `-Port 18080`。接后端前必须先统一（建议 18081 或做成可配置），否则小程序后端模式全量 `NETWORK_ERROR`。
2. ~~**启动脚本 profiles 默认值坑**~~：已修复（2026-11-13），默认改为 `db,dev,auth`，业务接口默认注册。
3. **帖子分类前后端不一致**：前端 8 板块（含 `secondhand/complain/buddy`，V9 后端把分类收窄为 `campus/lost_found/study/help`）—— 真机模式发新分类帖子会被后端 400。要么后端扩 CHECK，要么前端收缩板块。
4. **真机侧小缺口**：编辑帖子不带 `anonymous`（后端 `UpdatePost` 无该字段，硬加会 400）；`contact` 页访客态两套口径并存；`ForumService.author()` 不返回 `major`（前端 `authorMajor` 恒空但 UI 未消费）。
5. **WXSS @import 位置违规**：`pages/report|chat-settings|profile-edit/index.wxss` 的 `@import "../../styles/page-polish.wxss"` 写在规则之后被忽略；移到首行会改变层叠顺序，需重新目验这三个旧排版页。
6. **前端死代码**：`pages/login/`（未注册且无入口）、`pages/drafts/`（注册但无入口）、`service.collections()` 无调用点（收藏功能无查看入口，与"卡片只有评论/转发/点赞三动作"的设计一致）。
7. **验证/自测习惯**：`apps/miniprogram` 改动后跑 `npm run check`（238 条）；微信开发者工具对新建 .ts 文件的编译缓存不敏感，验证运行时行为前先手动点「编译」；`cli.bat open --project` 会重启模拟器导致自动化读不到页面数据。
8. 历次踩坑与决策记录在 `.workbuddy/memory/2026-11-12.md`、`2026-11-13.md`，排查前值得先读。

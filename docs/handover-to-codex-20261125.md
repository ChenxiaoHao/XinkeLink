# 信科 Link V1.0 上线收尾交接报告（致 Codex）

- **日期**：2026-11-25
- **执行方**：Antigravity (Gemini)
- **当前状态**：三端自动化测试 **865 / 865 全部通过**（0 失败 / 0 跳过），已同步推送至 GitHub `main` 分支（`https://github.com/ChenxiaoHao/XinkeLink`）。

---

## 一、本次已完成内容总览

### 1. 仓库临时文件清理与敏感信息扫描
- 删除 10 个残留的本地临时文件：
  - `dump.rdb`
  - `apps/admin/.business-admin-check.log`
  - `apps/miniprogram/.business-check.log`、`.cache-round-check.log`、`.live-check.log`、`.live-red.log`
  - `apps/server/.business-mvn-test.log`、`.cache-projection-green.log`、`.cache-projection-red.log`、`.cache-round-mvn.log`
- 确认 `.gitignore` 已覆盖 `*.log`、`dump.rdb`、`.env`、`.env.*`、`*.pem`、`*.key`、`private/`、`.local/`。
- 全量扫描 `apps/server/src/main`、`apps/admin/src`、`apps/miniprogram/miniprogram`，未发现硬编码密钥或私钥。

### 2. 后端内容安全：微信 `msgSecCheck` + 后台敏感词拦截生效
- **新增错误码**：`apps/server/src/main/java/cn/xinkelink/common/api/ErrorCode.java`
  - `CONTENT_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "内容包含违规或敏感信息，请修改后重试")`（HTTP 422）
- **新增内容安全模块** (`apps/server/src/main/java/cn/xinkelink/content/security/`):
  - `ContentScene.java`：枚举 `PROFILE(1)`、`COMMENT(2)`、`FORUM(3)`、`MESSAGE(4)`。
  - `ContentSecurityProperties.java`：绑定 `xinkelink.content-security.wechat-enabled`（默认 `false`）与 `fail-open`（默认 `false`）。
  - `WechatContentSecurityClient.java` / `OfficialWechatContentSecurityClient.java`：
    - 通过 `POST https://api.weixin.qq.com/cgi-bin/stable_token` 获取并缓存 `access_token`（Redis key `xinkelink:wechat:stable_token:{appId}`）；
    - 调用 `POST https://api.weixin.qq.com/wxa/msg_sec_check`（`version=2`，带 `openid`、`scene`、`content`）；
    - 遇 `errcode` 40001/42001 自动强制刷新 token 重试一次；`result.suggest != "pass"` 抛 `CONTENT_REJECTED`；上游故障默认 fail-closed 抛 `SERVICE_UNAVAILABLE`。
  - `ContentSecurityService.java`：
    - 实时读取 `xl_system_setting(content_review)`（30 秒本地缓存，后台保存/重置时立即失效），去除零宽字符并忽略大小写匹配敏感词；
    - 开启 `wechat-enabled=true` 时查询 `xl_user.wechat_open_id` 并调用微信 `msg_sec_check`。
  - `ContentSecurityConfiguration.java`：在 `@Profile("auth")` 下注册默认 `WechatContentSecurityClient`（`@ConditionalOnMissingBean`，便于测试替换）。
- **已接入的 5 个用户文本写入入口**（均在 DB 行锁之前执行外部校验，避免持锁等待网络）：
  - `ForumService.create`（`FORUM`）
  - `ForumService.update`（`FORUM`）
  - `ForumService.createComment`（`COMMENT`）
  - `MessagingService.send`（`MESSAGE`）
  - `ProfileService.update`（`PROFILE`：校验 `nickname`/`bio`/`major`/`className`）
  - 全部保留原有构造函数并用 `@Autowired` 标注带 `ObjectProvider<ContentSecurityService>` 的新构造函数，向后兼容既有单测。
- **系统设置状态更新**：`SettingsService.java`
  - 保存/重置配置时调用 `contentSecurity.invalidateSettingsCache()`；
  - `effectMode` 根据开关返回 `active_text_review` 或 `configuration_only`，`system.automaticReview` 同步反映实时生效状态。
- **配置项**：`apps/server/src/main/resources/application-auth.yml` 新增：
  - `XINKELINK_WECHAT_CONTENT_CHECK_ENABLED`（默认 `false`，生产设 `true`）
  - `XINKELINK_WECHAT_CONTENT_CHECK_FAIL_OPEN`（默认 `false`）
- **新增单元测试**：`apps/server/src/test/java/cn/xinkelink/content/security/ContentSecurityServiceTest.java`（4 项测试覆盖敏感词、零宽绕过、开关与缓存失效、微信 `pass/risky/40001刷新/fail-closed`）。

### 3. 小程序合规改造、隐藏 Link AI 与打包瘦身
- **打包瘦身**：`apps/miniprogram/project.config.json` 的 `packOptions.ignore` 排除 `miniprogram/assets/mock` 目录（减少约 500+ KB 主包体积，不影响本地测试）。
- **首版隐藏 Link AI 入口（改为 4 栏导航，保留全部代码以便 V1.1 恢复）**：
  - `apps/miniprogram/miniprogram/app.json`：`tabBar.list` 移除 `pages/ai/index`（保留为 首页 / 搜索 / 通知 / 私信），并注册新页面 `pages/legal/index`。
  - `apps/miniprogram/miniprogram/utils/navigation.ts`：`TABS` 调整为 4 栏。
  - `apps/miniprogram/scripts/check-project.cjs` 与 `apps/miniprogram/tests/x-shell.test.cjs` 同步更新为 4 栏断言。
- **用户协议、隐私政策与微信隐私授权适配**：
  - 新增 `apps/miniprogram/miniprogram/pages/legal/index.{ts,json,wxml,wxss}`：提供「用户协议」/「隐私政策」双 Tab 查阅及「查看微信官方隐私保护指引」（调用 `wx.openPrivacyContract`）。
  - `apps/miniprogram/miniprogram/components/app-shell/index.{wxml,ts}`：抽屉入口升级为「用户协议与隐私政策」，点击跳转 `/pages/legal/index?tab=privacy`。
  - `apps/miniprogram/miniprogram/components/profile-auth-sheet/index.{wxml,ts,wxss}`：登录确认弹层增加协议说明与《用户协议》《隐私政策》可点击跳转。
  - `apps/miniprogram/miniprogram/app.ts`：启动时注册 `wx.onNeedPrivacyAuthorization` 回调。
- **新增单元测试**：`apps/miniprogram/tests/legal-compliance.test.cjs`（3 项测试）。

### 4. 生产部署套装 (`deploy/`)
- `deploy/nginx/xinkelink.conf`：HTTP→HTTPS 301、TLS 1.2/1.3、`/api/v1/ws` WSS 代理、`/api/` 反代 `127.0.0.1:18081`、`client_max_body_size 15m`、禁止公网访问 `/actuator/`、管理后台 SPA 托管。
- `deploy/systemd/xinkelink-server.service`：非 root `xinkelink` 用户、4GB 服务器 JVM 内存参数（`-Xms256m -Xmx512m`）、优雅停机、`db,auth,redis` profiles。
- `deploy/env/xinkelink.env.example`：完整生产环境变量清单（MySQL 双账号 UTC、Redis 7、JWT、WeChat、内容安全、阿里云 OSS、关闭 Kafka/RocketMQ/Canal）。
- `deploy/DEPLOY.md`：从零到上线的逐条命令操作手册（含 MySQL 最小权限与每日备份 cron、日志 logrotate、微信域名配置、真机验收清单与 V1.1 恢复 Link AI 说明）。

---

## 二、全量测试验证结果

| 模块 | 命令 | 结果 |
| --- | --- | --- |
| 后端 (`apps/server`) | `mvn test` | **✅ 394 / 394 通过**（0 失败 / 0 错误 / 0 跳过） |
| 管理后台 (`apps/admin`) | `npm run check` | **✅ 100 / 100 通过** + `vue-tsc` + Vite 生产构建成功 |
| 小程序 (`apps/miniprogram`) | `npm run check` | **✅ 371 / 371 通过** + `tsc` + `check:project` 通过 |
| **合计** | — | **865 / 865 全部通过** |

> 注：未在隔离 MySQL 上重跑 `mvn -Pdb-integration verify`；Link AI Tab 已按用户要求临时恢复为 5 栏以便真机测试（提审前若不开 AI 可按 `app.json` + `utils/navigation.ts` 再隐藏）。

---

## 三、新增完成：自助账号软注销 + 微信图片异步审核 (`mediaCheckAsync`)

### 1. 自助账号软注销
- **后端接口**：`POST /api/v1/me/deactivate`，请求体 `{ "confirmed": true }`，响应 `{ "deactivated": true }`。
  - 文件：`ProfileController.java`、`ProfileService.java`
  - 实现要点：
    1. 校验登录用户与 `confirmed === true`，解绑用户头像（`media.bindAvatar(id, null)`）。
    2. 软删除并脱敏 `xl_user`：`nickname='已注销用户'`、清空个人资料、`deleted_at=UTC_TIMESTAMP(3)`，同时将 `wechat_open_id` 改写为 `CONCAT('deactivated:', id, ':', LEFT(SHA2(CONCAT(wechat_open_id, ':', id), 256), 24))` 以释放原 `(wechat_app_id, wechat_open_id)` 唯一键占位，保留历史内容外键引用。
    3. 一次性吊销 `xl_auth_session` 中该用户全部未吊销会话（`revoked_at=UTC_TIMESTAMP(3)`）。
  - 单测：`ProfileDeactivateTest.java`（2/2 通过）。
- **小程序端**：`components/app-shell/index.{wxml,ts}` 新增「注销账号」入口与二次确认弹窗，成功后调用 `api.clear()` 清空本地会话并刷新访客态（`tests/legal-compliance.test.cjs` 已覆盖）。

### 2. 微信图片异步审核 (`media_check_async` v2 + 回调自动隐藏)
- **后端发起与回调**：
  - `OfficialWechatContentSecurityClient.checkImageAsync`：复用 `stable_token` 缓存与 40001/42001 刷新重试，调用 `wxa/media_check_async` 返回 `trace_id`。
  - `WechatMediaReviewService.java`：
    - `submitImageCheck`：在 `MediaService.upload` 保存 `post`/`avatar` 图片后异步提交检测，将 `trace_id -> imageId` 存入 Redis（`xinkelink:wechat:media_trace:{traceId}`，TTL 24h）。
    - `verifySignature`：使用 `XINKELINK_WECHAT_CALLBACK_TOKEN` 做 SHA-1 常量时间签名校验。
    - `handleCallback`：收到 `wxa_media_check` 且 `result.suggest != "pass"` 时，按 `xl_image_ref` 自动隐藏关联帖子（`status='hidden', review_state='pending'` 进入管理后台待复核队列）或清除违规用户头像（`avatar_image_id=NULL`）。
  - `WechatMediaCallbackController.java`：提供 `GET`（URL 验证）与 `POST`（回调接收）`/api/v1/webhooks/wechat/media-check`，已在 `SecurityConfiguration.java` 放行并豁免 CSRF（依赖微信 SHA-1 签名验权）。
  - 新增配置项（`application-auth.yml` & `deploy/env/xinkelink.env.example`）：
    - `XINKELINK_WECHAT_MEDIA_CHECK_ENABLED`（默认 `false`，生产设 `true`）
    - `XINKELINK_WECHAT_CALLBACK_TOKEN`
    - `XINKELINK_PUBLIC_BASE_URL`
  - 单测：`WechatMediaReviewServiceTest.java`（3/3 通过）。

---

## 四、请 Codex 重点复核的后端清单（Review Checklist）

请 Codex 重点审查以下 6 个后端风险点：
1. **并发与锁序**：`ContentSecurityService.checkText` 放在 `ForumService` / `MessagingService` DB 行锁之前，确认不会引发幂等重试逻辑顺序问题（例如同 `clientRequestId` 重试时若敏感词表已变更的行为）。
2. **`xl_user` 软注销 SQL 与 MySQL 8.4 兼容性**：确认 `ProfileService.deactivate` 中 `SHA2(CONCAT(wechat_open_id, ':', id), 256)` 与 `ascii_bin` 字符集/CHECK 约束及运行账号最小权限（`UPDATE xl_user`、`UPDATE xl_auth_session`）完全兼容。
3. **微信图片审核回调时序**：如果用户在 `MediaService.upload` 后尚未点击「发布帖子」（图片还处于 `temporary` 未写入 `xl_image_ref`），微信极快回调 `risky` 时当前 `handleCallback` 查不到 `xl_image_ref`；如要求“先审后公开”强约束，可由 Codex 决定是否需要增加 `xl_image` 审核状态列（需同步更新 `database-table-contract.json` 与 Flyway V21）。
4. **Redis 不可用降级**：`OfficialWechatContentSecurityClient` 和 `WechatMediaReviewService` 在 Redis 故障时的降级行为是否符合预期；`stable_token` 高并发下的缓存击穿是否需要互斥锁。
5. **隔离数据库集成测试**：建议在隔离库跑一次 `mvn -Pdb-integration verify`，确认 `SettingsService` 默认敏感词不误伤既有集成测试用例，且新增路由符合既有 API 契约测试（若有路由总数断言需同步更新）。
6. **备案通过后上线步骤**：按 `deploy/DEPLOY.md` 部署 MySQL / Redis / OSS / Nginx HTTPS & WSS / systemd，在微信后台配置合法域名、隐私指引与消息推送回调 URL（`/api/v1/webhooks/wechat/media-check`），切 `BACKEND_ORIGIN` 正式域名后真机验收提审。

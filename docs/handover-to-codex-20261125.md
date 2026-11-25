# 信科 Link V1.0 上线收尾交接报告（致 Codex）

- **日期**：2026-11-25
- **执行方**：Antigravity (Gemini)
- **当前状态**：三端自动化测试 **859 / 859 全部通过**（0 失败 / 0 跳过），未执行 `git commit` / `git push`，所有变更保留在工作区等待分批提交。

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
| 后端 (`apps/server`) | `mvn test` | **✅ 389 / 389 通过**（0 失败 / 0 错误 / 0 跳过） |
| 管理后台 (`apps/admin`) | `npm run check` | **✅ 100 / 100 通过** + `vue-tsc` + Vite 生产构建成功 |
| 小程序 (`apps/miniprogram`) | `npm run check` | **✅ 370 / 370 通过** + `tsc` + `check:project` 通过 |
| **合计** | — | **859 / 859 全部通过** |

> 注：本次未在隔离 MySQL 上重跑 `mvn -Pdb-integration verify`，也未在微信开发者工具执行真机网络联调。

---

## 三、建议的分批 Git 提交命令（请人工复核后执行）

```bash
# 1. 绑定私有远程仓库（如尚未绑定）
# git remote add origin <你的私有仓库地址>

# 2. 第 1 批：后端历史功能与本次内容安全
git add apps/server/src/main/java/cn/xinkelink/content/security \
        apps/server/src/test/java/cn/xinkelink/content/security \
        apps/server/src/main/java/cn/xinkelink/common/api/ErrorCode.java \
        apps/server/src/main/java/cn/xinkelink/forum/ForumService.java \
        apps/server/src/main/java/cn/xinkelink/messaging/MessagingService.java \
        apps/server/src/main/java/cn/xinkelink/profile/ProfileService.java \
        apps/server/src/main/java/cn/xinkelink/settings/SettingsService.java \
        apps/server/src/main/resources/application-auth.yml
git commit -m "feat(server): add real sensitive word filter and WeChat msgSecCheck"

# 3. 第 2 批：小程序 V1.0 提审合规、4 栏导航与打包瘦身
git add apps/miniprogram/project.config.json \
        apps/miniprogram/scripts/check-project.cjs \
        apps/miniprogram/miniprogram/app.json \
        apps/miniprogram/miniprogram/app.ts \
        apps/miniprogram/miniprogram/utils/navigation.ts \
        apps/miniprogram/miniprogram/components/app-shell \
        apps/miniprogram/miniprogram/components/profile-auth-sheet \
        apps/miniprogram/miniprogram/pages/legal \
        apps/miniprogram/tests/x-shell.test.cjs \
        apps/miniprogram/tests/legal-compliance.test.cjs
git commit -m "feat(miniprogram): add legal/privacy pages, hide Link AI tab, exclude mock assets"

# 4. 第 3 批：生产部署套装与交接文档
git add deploy/ docs/handover-to-codex-20261125.md
git commit -m "chore(deploy): add production Nginx/systemd/env kit and handover report"

# 5. 第 4 批：其余历史未提交改动（V10-V20 迁移、Link AI、浏览统计、管理后台等）
git status
# 核对无敏感文件后按模块或统一提交并推送远程备份，上线前打 tag：
# git tag -a v1.0.0-rc1 -m "V1.0.0 launch candidate"
```

---

## 四、交接给 Codex / 备案通过后的剩余待办

1. **Git 基线与安全审计**：按上述分批命令完成提交、推送远程仓库；如需，补充跑一次 `mvn -Pdb-integration verify`（注意测试数据不要命中 `SettingsService` 默认敏感词）。
2. **可选增强（视微信审核类目与时间决定）**：
   - 如需更严格的图片风控，可基于本次 `OfficialWechatContentSecurityClient` 的 `stable_token` 机制扩展 `mediaCheckAsync` 异步回调；
   - 如需自助账号注销，可补充软注销接口并在 `app-shell` 退出登录旁挂载入口。
3. **备案通过后上线步骤**（严格按 `deploy/DEPLOY.md` 执行）：
   - 部署 MySQL 8.4 / Redis 7 / OSS / Nginx HTTPS & WSS / systemd；
   - 微信公众平台配置合法域名与《用户隐私保护指引》；
   - 将 `apps/miniprogram/miniprogram/backend/config.ts` 中 `BACKEND_ORIGIN` 改为正式 `https://` 域名，真机验收体验版全链路后提交审核。

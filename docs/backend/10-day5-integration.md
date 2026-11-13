# Day 5：管理 API、前端接入与整体验收

日期：2026-10-25。基于当前工作区及01～09设计实施，保留原有未提交成果，未执行 Git commit、push、reset、clean，也未部署生产环境。

## 1. 实际交付及验收边界

- 补齐30个 HTTP 方法：25个管理方法、5个用户资料/偏好/屏蔽方法。现有110个 HTTP 契约全部具备实现，包含108个业务方法与2个健康方法，另有1个 WebSocket 端点。
- 管理后台共48个管理方法；全部复用既有认证、内容、商品、举报和通知模型，统一管理员鉴权。没有复制第二套商品或用户状态。
- 小程序正式页面和管理后台默认调用真实 API。请求失败显示错误，不以模拟记录冒充成功。
- MySQL 8.4.13、Redis 7.4.11、HTTP 和本地 WS 已实测。微信身份换码仅在隔离的测试启动器中替换；真实 code2Session 和生产 WSS **未验收**。
- 当前数据库是开发数据库，包含明确的测试账号和验收内容。后台统计来自这些数据库记录，不代表真实学生规模或生产统计。

## 2. 管理 API 清单

完整字段、方法及权限仍以[04 API契约](04-api-contract.md)为准。

| 模块 | HTTP数 | 实际能力 |
| --- | ---: | --- |
| 管理认证 A01～04 | 4 | BCrypt登录、身份查询、Cookie刷新、退出与会话吊销 |
| Dashboard D01 | 1 | 数据库真实计数、发帖趋势、待办、动态；未采集DAU/停留时长明确不可用 |
| 帖子 AP01～05 | 5 | 筛选分页、详情、两级评论、整批隐藏/恢复、带版本删除 |
| 评论 AC01～04 | 4 | 关联业务筛选、上下文、批量隐藏/恢复/删除及级联计数 |
| 商品 AM01～04 | 4 | 详情图片、卖家、治理历史、状态/审核筛选、批量下架/恢复/删除 |
| 举报 AR01～03 | 3 | 队列、保全证据、一次性处置、原因与处理记录 |
| 用户 AU01～08 | 8 | 内容与举报关联、历史、独立通道限制、临时/永久封禁及安全解除 |
| 公告 AA01～07 | 7 | 草稿、编辑、发布、撤回、删除、首次发布站内通知 |
| 通知 AN01～06 | 6 | 草稿、受众预览、指纹复核、站内收件记录、重复发送保护 |
| 审计 AL01～03 | 3 | 安全摘要、筛选分页、详情、完整筛选导出CSV，上限10000条 |
| 设置 AS01～03 | 3 | 只读平台/账号信息、配置持久化、版本保存、确认恢复默认 |
| 合计 | **48** | 管理端接口均受服务端管理员权限保护 |

批量写入保持全有或全无；版本冲突不会留下部分状态或部分业务历史。售出商品不能恢复为在售，恢复展示不能解除用户封禁或清除无关限制。

新增U05修改昵称、头像、简介、专业、班级及入学年份；U10/U11读取/修改偏好；U12/U13幂等屏蔽/解除屏蔽。学号不上传，服务端拒绝studentId、school等未声明编辑字段。图片新增avatar用途，头像归属、解绑和短期授权均检查权限。

## 3. 跨模块审计与设置

在管理Service命令边界挂载统一审计，位于事务拦截器外层，区分success、business_rejected、execution_error。成功只在业务提交后记录；已有外层事务时等待最终提交。日志使用独立短事务追加，追加异常不替换原操作返回值、异常或已提交状态。

批量记录实际改变的所选对象数量；并发幂等创建及公告重复发布在持有业务锁时标记是否实变，避免事务外快照误判导致重复成功日志。无变化的设置不生成重复成功记录。

审计不采集任意请求、正文、处理原因、配置值、密码、Cookie或Token，只保存受控动作、结果码和白名单字段名。普通DTO不返回内部用户目标引用。运行数据库账号对审计表仅有SELECT/INSERT权限；没有编辑/删除审计接口。CSV具有BOM、引号转义及公式前缀保护。

设置保存到MySQL的xl_system_setting，刷新不会恢复演示默认值。effectMode固定configuration_only，审核开关/关键词/强度只保存配置，不执行AI、自动拦截或夜间队列。无Redis控制、服务器控制、清库或批量清空用户内容功能。

## 4. 两端真实接入

### 微信小程序

原生Page保留现有WXML/WXSS、Logo和五栏导航；新增统一请求、会话、上传、DTO和实时补取层，正式注册器以异步API替换原同步仓储行为。

| 页面/能力 | 对应真实接口 |
| --- | --- |
| 登录/会话 | U01～04、服务端刷新/吊销；wx.login只提交code，不接受客户端openid |
| 论坛/详情/发布编辑/我的帖子 | F01～16；匿名身份由服务端投影 |
| 评论、回复、互动与收藏 | F06～15、Q02；按真实字符串ID、版本及数量联动 |
| 交易/详情/发布编辑/商品管理 | P01～08、I01～04；整数分、成色、售出终态统一 |
| 搜索 | Q01；确认输入后查询；历史只在本机，不添加搜索发现 |
| 个人资料/个人主页/设置 | U04～13、I01～04、U06真实计数；资料自填标识保留 |
| 联系/聊天/聊天设置 | C01～11；HTTP历史、字符串水位、已读、清空、屏蔽及WS断线补取 |
| 举报/公告/通知 | R01、N01～05；接收者隔离、已读与角标来自服务端 |

本机保留搜索历史、商品草稿和用户自填学号；服务端持久化账号、内容、互动、商品、消息、举报、通知及偏好。旧Mock实现保留用于显式测试夹具和回归，正式网络请求失败不调用它们。

### PC管理后台

五个Pinia数据域、认证、Dashboard及所有管理页面已使用API；浏览器只保留访问Token于内存，刷新Token使用Secure/HttpOnly/SameSite=Strict Cookie，CSRF标记仅存当前标签页sessionStorage。账号可以记住，密码不保存。刷新请求单飞，过期请求和CSV下载最多刷新重试一次；再次401清除会话。

列表筛选、分页、抽屉详情、版本命令和成功反馈等待实际响应。旧同步Store移动到tests/fixtures，仅由明确的测试导入；生产没有演示登录入口或失败回Mock开关。

联调修复包括：后台隐藏图片按有效管理员会话授权；公开论坛/交易接口仍只返回公共投影；JSON与MySQL时间类型转换；搜索/筛选异步等待；设置校验反馈；无搜索结果与总表空状态区分；举报关联的用户封禁状态与中文标签。

## 5. 测试与真实运行证据

| 检查 | 本轮实际结果 |
| --- | --- |
| Java编译、打包及完整回归 | **315通过：135项单元/基础HTTP测试 + 180项真实数据库集成测试；0失败、0跳过** |
| 契约覆盖 | 自动对照04文档，109个MVC方法加Actuator健康方法，覆盖全部110个HTTP契约 |
| MySQL | 真实8.4.13，Flyway V1～V5验证成功；本轮没有新迁移，没有修改已执行迁移 |
| Redis | 真实7.4.11，Windows到WSL的16379连接、PING、Lua计数1/2、TTL及到期清除通过 |
| 小程序 | TypeScript、项目引用检查及**156项测试通过** |
| WXML/WXSS | 微信工具官方wcc/wcsc分别编译**27个WXML、29个WXSS**成功 |
| 小程序正式Page真实HTTP | 登录测试换码、发帖、评论、点赞收藏、我的内容、搜索、商品上传发布、私信、资料保存通过；这是Node Page适配测试，不等同于微信模拟器 |
| 管理后台 | TypeScript、**70项Vitest**及生产构建通过 |
| 浏览器显式HTTP夹具回归 | **15项通过**；另一个真实后端用例默认跳过，避免普通回归擅自写本地数据库 |
| 独立真实后端浏览器用例 | 显式启用后**1项完整流程通过**，无请求拦截；覆盖两种宽度、真实Cookie恢复、列表与权限及核心业务链 |
| 本地WebSocket | Day4RealtimeIT的5项真实TCP/WS场景回归通过：鉴权、会话隔离、提交后交付、断线后HTTP历史、会话失效 |

五条要求的完整业务链已在真实HTTP/MySQL环境验证：用户登录（测试换码）→发帖→评论→点赞收藏；发布商品→他人查看→关联会话及持久消息；举报→管理员处置→内容隐藏；发布公告→用户公告与站内通知；管理员限制发布→用户发帖被拒绝→显式解除。

真实管理员浏览器检查包含1366×812、1440×812；侧栏/顶栏位置保持不变、document不滚动，内容区域独立滚动，无横向页面溢出。截图来自真实Chrome页面，而非设计稿或HTML合成图。

证据位于仓库忽略目录`.local/backend-day5/`：

- `backend-delivery-command.log`及`backend-delivery.stdout.log`：最终完整编译/打包/回归。
- `admin-delivery-check.log`、`admin-browser-final.log`、`admin-browser-real-final.log`：后台检查及两种浏览器验证边界。
- `miniprogram-check.log`、`wechat-compile.log`、`miniprogram-live.log`及`miniprogram-live-http.json`。
- `real-http-evidence.json`：只记录请求方法、路径、状态及测试对象ID，不保存Token。
- `mysql-verification.txt`、`redis-info.log`、`redis-verification.log`、`jar-verification.json`。
- `screenshots/*-real-1366.png`、`screenshots/*-real-1440.png`：后台十个页面的实际截图。

## 6. 微信、Redis与生产边界

Redis原Windows服务是旧3.x且停止，Docker存在本地IPC初始化错误；没有执行Docker恢复出厂、系统重置或删除数据。采用既有Ubuntu WSL，在用户本地目录从[Redis官方7.4.11源码](https://download.redis.io/releases/redis-7.4.11.tar.gz)编译；只绑定127.0.0.1:16379，开发限流缓存不启用持久化。未安装Ubuntu仓库中的Redis8，不改变既定Redis7技术路线。

Redis停止后，新登录/刷新按既有设计返回SERVICE_UNAVAILABLE，不会绕过限流；已签发会话及核心内容查询不无谓依赖Redis。此开发进程不是生产Redis部署。

微信开发工具CLI已登录，并实际启动模拟器；本地调试的URL校验仅在被Git忽略的project.private.config.json关闭，正式project.config.json仍保持校验开启。实际微信网络栈请求本地帖子接口返回200；公共页面数据观察到loadFailed=false。自动路由/截图存在超时，**未取得可用于完整流程验收的新小程序截图，也未把Node测试、编译或旧截图当作完整微信模拟器通过**。

真实微信联调需在服务端设置XINKELINK_WECHAT_APP_ID与XINKELINK_WECHAT_APP_SECRET，且AppID与小程序一致；密钥通过环境注入。配置正式HTTPS请求/上传/下载域名及WSS域名，并在真机验证。当前未提供AppSecret，未调用真实code2Session，不宣称真实微信或真机通过。生产HTTPS/WSS环境尚不存在，生产WSS未验收。

测试换码适配器仅存在于src/test下的Day5IntegrationServer，必须显式从测试classpath启动，绑定本机地址；生产可执行Jar不包含该适配器，也没有接受openid的测试登录接口。

结束前已停止测试换码启动器，18081切换为打包后的XinkeLinkApplication常规启动，5173后台继续代理它。正式Jar再次验证管理员登录/刷新/审计读取成功，缺少微信配置时对测试code返回502 WECHAT_LOGIN_FAILED，没有签发用户Token。该复核记录见product-smoke.json；日常浏览不再运行测试换码适配器。

## 7. 启动与复核

复用Day1～Day4的MySQL环境变量、迁移账号和JWT密钥配置，正式后端使用现有`scripts/start-backend-dev.ps1`，以空闲18081端口及db/auth/redis配置启动。后台Vite默认代理/api到18081；管理员Secure Cookie要求从HTTPS打开后台。

本轮本地HTTPS证书/私钥在`.local/backend-day5/private/`，仅供开发；配置XINKELINK_DEV_CERT、XINKELINK_DEV_KEY后在apps/admin运行npm run dev。临时开发管理员身份仅保存在同目录browser-account.json，不写入源码、文档或截图，不是生产账号。私有目录已限制Windows访问权限。

自动复核入口：后端`scripts/verify-backend.ps1 -Database`；两端分别npm run check；小程序`node scripts/check-wechat.cjs <微信工具目录>`；后台npm run test:e2e。真实写库浏览器用例须额外设置XINKELINK_E2E_CREDENTIAL_FILE，并显式运行real-backend.spec.ts；Node小程序Page联调须XINKELINK_LIVE_HTTP=1及隔离测试服务。

## 8. 尚未完成与风险

1. 真实微信换码、完整微信端登录后流程、真机、生产HTTPS/WSS仍待环境和凭据验证；这是交付限制，不能标记V1已完全验收。
2. 当前只有本地图片存储；云存储厂商、WebP/HEIC支持和过期文件回收仍未实现。授权URL短期有效，长时间停留后的预览更新还需微信环境专项复核。
3. 无真实微信订阅推送、外部送达或已打开回执；通知sent只表示站内接收记录已提交。系统设置不会自动启用审核引擎。
4. 活跃用户、停留时长等没有完整采集，不补假数据；Dashboard明确显示不可用指标。
5. 所有验收夹具使用开发账号/内容。不可变审计和处置历史保留，部分测试主体被软删除/禁用；开发数据会持续影响开发环境统计，不删除原有业务数据。
6. 当前WebSocket为单实例尽力交付，客户端以HTTP历史补取为准；扩展多实例前需另行设计分发。
7. 搜索页目前查询首批最多50条，大结果集的继续分页交互尚未接入；论坛/交易列表的游标分页已接通。这一限制不应被解释为搜索已经覆盖任意规模数据。
8. 管理后台生产构建有单个JS包超过500kB的提示；当前约1.20MB（gzip约375kB），未为处理此提示进行无关构建重构。

## 9. 本轮修改文件

以下清单按本轮开始时的文件SHA256基线生成；不将以前未提交成果算成本轮新增。所有基线文件仍存在。共152个文件；忽略目录中的证据、密钥和编译产物不计入。

- 修改：`apps/admin/e2e/admin-phase6.spec.ts`
- 新增：`apps/admin/e2e/api-fixture.ts`
- 修改：`apps/admin/e2e/bootstrap.spec.ts`
- 修改：`apps/admin/e2e/content-moderation.spec.ts`
- 修改：`apps/admin/e2e/operations-management.spec.ts`
- 修改：`apps/admin/e2e/product-report-management.spec.ts`
- 新增：`apps/admin/e2e/real-backend.spec.ts`
- 修改：`apps/admin/e2e/user-management.spec.ts`
- 修改：`apps/admin/playwright.config.ts`
- 修改：`apps/admin/src/App.vue`
- 修改：`apps/admin/src/components/admin/AdminLayout.vue`
- 修改：`apps/admin/src/components/admin/AdminSidebar.vue`
- 修改：`apps/admin/src/components/admin/AdminTopbar.vue`
- 修改：`apps/admin/src/components/content/ContentMetricGrid.vue`
- 修改：`apps/admin/src/components/dashboard/TrendChart.vue`
- 修改：`apps/admin/src/router/index.ts`
- 新增：`apps/admin/src/services/admin-adapters.ts`
- 新增：`apps/admin/src/services/admin-dashboard.ts`
- 新增：`apps/admin/src/services/admin-http.ts`
- 新增：`apps/admin/src/services/admin-models.ts`
- 修改：`apps/admin/src/stores/admin-audit.ts`
- 修改：`apps/admin/src/stores/admin-auth.ts`
- 修改：`apps/admin/src/stores/admin-operations.ts`
- 修改：`apps/admin/src/stores/admin-settings.ts`
- 修改：`apps/admin/src/stores/content-moderation.ts`
- 修改：`apps/admin/src/views/AnnouncementManagementView.vue`
- 修改：`apps/admin/src/views/CommentManagementView.vue`
- 修改：`apps/admin/src/views/DashboardView.vue`
- 修改：`apps/admin/src/views/DevelopingView.vue`
- 修改：`apps/admin/src/views/LoginView.vue`
- 修改：`apps/admin/src/views/NotificationManagementView.vue`
- 修改：`apps/admin/src/views/OperationLogView.vue`
- 修改：`apps/admin/src/views/PostManagementView.vue`
- 修改：`apps/admin/src/views/ProductManagementView.vue`
- 修改：`apps/admin/src/views/ReportReviewView.vue`
- 修改：`apps/admin/src/views/SystemSettingsView.vue`
- 修改：`apps/admin/src/views/UserManagementView.vue`
- 修改：`apps/admin/tests/admin-audit.test.ts`
- 修改：`apps/admin/tests/admin-settings.test.ts`
- 新增：`apps/admin/tests/api-integration.test.ts`
- 修改：`apps/admin/tests/auth.test.ts`
- 修改：`apps/admin/tests/content-moderation.test.ts`
- 新增：`apps/admin/tests/fixtures/admin-audit.ts`
- 新增：`apps/admin/tests/fixtures/admin-auth.ts`
- 新增：`apps/admin/tests/fixtures/admin-operations.ts`
- 新增：`apps/admin/tests/fixtures/admin-settings.ts`
- 新增：`apps/admin/tests/fixtures/content-moderation.ts`
- 修改：`apps/admin/tests/health.test.ts`
- 修改：`apps/admin/tests/operations-management.test.ts`
- 修改：`apps/admin/tests/product-report-management.test.ts`
- 新增：`apps/admin/tests/real-auth.test.ts`
- 新增：`apps/admin/tests/real-operations.test.ts`
- 修改：`apps/admin/tests/router-auth.test.ts`
- 修改：`apps/admin/tests/user-management.test.ts`
- 修改：`apps/admin/vite.config.ts`
- 修改：`apps/admin/vitest.config.ts`
- 新增：`apps/miniprogram/miniprogram/backend/adapters.ts`
- 新增：`apps/miniprogram/miniprogram/backend/client.ts`
- 新增：`apps/miniprogram/miniprogram/backend/config.ts`
- 新增：`apps/miniprogram/miniprogram/backend/pages.ts`
- 新增：`apps/miniprogram/miniprogram/backend/realtime.ts`
- 新增：`apps/miniprogram/miniprogram/backend/service.ts`
- 修改：`apps/miniprogram/miniprogram/components/forum-comment/index.ts`
- 修改：`apps/miniprogram/miniprogram/components/forum-comment/index.wxml`
- 修改：`apps/miniprogram/miniprogram/components/forum-post-card/index.ts`
- 修改：`apps/miniprogram/miniprogram/custom-tab-bar/index.ts`
- 修改：`apps/miniprogram/miniprogram/features/forum/types.ts`
- 修改：`apps/miniprogram/miniprogram/pages/chat-settings/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/chat-settings/index.wxml`
- 修改：`apps/miniprogram/miniprogram/pages/contact-chat/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/contact-chat/index.wxml`
- 修改：`apps/miniprogram/miniprogram/pages/contact/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/forum-detail/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/forum-publish/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/forum/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/login/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/login/index.wxml`
- 修改：`apps/miniprogram/miniprogram/pages/market-chat/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/market-chat/index.wxml`
- 修改：`apps/miniprogram/miniprogram/pages/market-detail/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/market-detail/index.wxml`
- 修改：`apps/miniprogram/miniprogram/pages/market-publish/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/market/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/my-collections/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/my-items/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/my-posts/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/my-posts/index.wxml`
- 修改：`apps/miniprogram/miniprogram/pages/profile-edit/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/profile-edit/index.wxml`
- 修改：`apps/miniprogram/miniprogram/pages/profile/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/profile/index.wxml`
- 修改：`apps/miniprogram/miniprogram/pages/report/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/report/index.wxml`
- 修改：`apps/miniprogram/miniprogram/pages/search/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/settings/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/settings/index.wxml`
- 修改：`apps/miniprogram/miniprogram/pages/user-home/index.ts`
- 修改：`apps/miniprogram/miniprogram/pages/user-home/index.wxml`
- 新增：`apps/miniprogram/tests/backend-http-flow.cjs`
- 新增：`apps/miniprogram/tests/backend-pages.test.cjs`
- 新增：`apps/miniprogram/tests/backend.test.cjs`
- 新增：`apps/miniprogram/tests/http-runtime.cjs`
- 修改：`apps/miniprogram/tests/native-harness.cjs`
- 修改：`apps/miniprogram/tests/page-harness.cjs`
- 新增：`apps/miniprogram/tests/run-live-http.cjs`
- 新增：`apps/server/src/main/java/cn/xinkelink/admin/AdminCommandService.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/admin/AdminController.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/admin/AdminData.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/admin/AdminModels.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/admin/AdminProjection.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/admin/AdminQueryService.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/audit/AuditAction.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/audit/AuditChanges.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/audit/AuditConfiguration.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/audit/AuditController.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/audit/AuditPolicy.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/audit/AuditQueryService.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/audit/AuditRecorder.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/auth/AuthService.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/common/api/ErrorCode.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/config/AuthConfiguration.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/content/ContentSupport.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/forum/ForumService.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/market/MarketService.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/media/MediaService.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/media/persistence/MediaMapper.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/moderation/ReportModerationService.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/moderation/UserModerationService.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/operations/AnnouncementService.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/operations/NotificationTaskService.java`
- 修改：`apps/server/src/main/java/cn/xinkelink/operations/OperationsRepository.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/profile/ProfileController.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/profile/ProfileService.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/settings/SettingsController.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/settings/SettingsModels.java`
- 新增：`apps/server/src/main/java/cn/xinkelink/settings/SettingsService.java`
- 新增：`apps/server/src/test/java/cn/xinkelink/admin/AdminAuditIdempotencyIT.java`
- 新增：`apps/server/src/test/java/cn/xinkelink/admin/AdminHttpIT.java`
- 新增：`apps/server/src/test/java/cn/xinkelink/admin/AdminLiveHttpIT.java`
- 新增：`apps/server/src/test/java/cn/xinkelink/audit/AuditPolicyTest.java`
- 新增：`apps/server/src/test/java/cn/xinkelink/audit/AuditRecorderTest.java`
- 修改：`apps/server/src/test/java/cn/xinkelink/content/Day3HttpIT.java`
- 新增：`apps/server/src/test/java/cn/xinkelink/integration/Day5IntegrationServer.java`
- 新增：`apps/server/src/test/java/cn/xinkelink/integration/Day5SharedHttpIT.java`
- 修改：`apps/server/src/test/java/cn/xinkelink/market/MarketServiceIT.java`
- 修改：`apps/server/src/test/java/cn/xinkelink/media/MediaHttpIT.java`
- 修改：`apps/server/src/test/java/cn/xinkelink/operations/OperationsTransactionsIT.java`
- 修改：`apps/server/src/test/java/cn/xinkelink/realtime/Day4RealtimeIT.java`
- 修改：`docs/backend/04-api-contract.md`
- 新增：`docs/backend/10-day5-integration.md`
- 新增：`docs/superpowers/plans/2026-10-25-backend-day5.md`
- 修改：`README.md`


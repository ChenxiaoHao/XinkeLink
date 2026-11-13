# 点赞统一、登录确认与界面优化交付记录

> 2026-10-30补验：19页×两种尺寸的加载/溢出检查、用户两次实际资料确认、退出与真实刷新、点赞联动已补验。详见[实际验收补充记录](miniprogram-acceptance-20261030.md)。下文保留补验前的历史结果与边界，最新状态以上述记录为准。

日期：2026-10-30。工作目录：`D:\CODE\XinKeLink`。本轮单线执行，没有创建子 agent，没有执行 Git commit/push/reset/clean，没有接入 OSS 或生产部署。

## 1. 完成范围

### 点赞统一

- 正式 WXML 中删除论坛卡片、帖子详情和商品详情的收藏按钮、书签图标及收藏事件绑定。个人中心入口、列表标题及通知筛选统一为点赞。
- 帖子沿用原有点赞接口。商品增加 `PUT/DELETE /api/v1/products/{id}/like`，返回 `liked/likeCount/changed`；商品卡片、搜索结果、公开主页及详情复用相同商品 ID 和状态。
- 新增 `GET /api/v1/me/likes?type=post|product&cursor&size`。旧页面路径 `my-collections` 保持兼容，界面名称为“我的点赞”；两类列表分别读取真实点赞关系，不读取旧收藏冒充点赞。
- 操作等待服务端确认后，仅 `setData` 对应行/卡片/详情；不重新读取整个信息流，不清空游标或已加载分页。在我的点赞中取消点赞只移除对应条目。失败保留原状态并提示，重复请求由客户端操作锁和服务端唯一键、事务处理。
- 商品点赞支持交易卡片直接点击，使用 `catchtap` 避免误跳详情。保留帖子已有轻反馈、五栏切换反馈，新增商品心形图标的轻量按压反馈。
- 用户端详情、后台商品列表/抽屉和个人获赞统计共享服务端点赞数据。商品列表按当前页批量聚合点赞，没有新增逐商品点赞查询。
- 新增 `product_like` 通知类型，沿用提交后推送及收件人隔离；前端修正通知 actor 包装投影，昵称、头像和动作描述正确读取。旧收藏通知显示其历史含义，不伪装为点赞。相同目标/操作者取消后再次点赞仍遵循原有不重复打扰通知策略。

### 首次登录与资料确认

- 沿用当前页面的资料确认弹层和微信原生 `chooseAvatar`、`type="nickname"`，确认后调用 wx.login、服务端换码、头像上传、版本化资料 PATCH。
- 增加 `profileConfirmed` 会话标记。换码成功但资料未保存时，不视为完成登录；即使进程中断，重启也不会绕过资料确认。
- 已确认会话正常恢复、Token 刷新不重复弹层。主动退出清除会话，下次登录重新确认。失败保留当前流程并明确提示。
- 既有已登录会话缺少新标记时继续兼容，不强制现有用户重复确认。
- 不调用失效的静默头像昵称授权接口，不记录或写入任何密钥。

### 界面

依据 `design-taste-frontend` 的应用界面规范，复用既有雾蓝品牌方向与组件；没有引入新框架或重新生成 Logo。

- 小程序：聊天、商品/帖子详情、发布、搜索、我的闲置/帖子/点赞、个人中心、资料、举报、设置等子页提高过小辅助字号；共享辅助色加深。聊天消息、商品描述、评论编辑的正文与行距更易阅读；搜索文字 Tab 保持轻量，清空靠右、确认后搜索的既定行为不变。
- 论坛保持单列作者—正文—图片—互动的阅读结构，交易保持双列图片—标题—价格，联系保持会话列表与双侧聊天消息结构。没有新增营销发现区或重复操作。
- PC 后台：统一公共及页面作用域的小字底线为12px，表格主要内容14px、行距及操作区域调整；去掉统计卡片重复“服务器数据”标签及装饰性图标，保留真实数据来源提示；卡片扁平化，导航选中态更克制。
- 固定侧栏、顶栏和独立主内容滚动保持。商品列表改为“点赞 / 咨询”，抽屉同时保留浏览不可用的真实破折号与真实点赞数。
- 主色 #6489B8、Logo 和小程序五栏结构未改。旧卡片/导航的布局修复与“不整页刷新”保留。

## 2. 数据与安全

新增 Flyway `V6__product_likes.sql`，只扩展点赞目标与通知类型 CHECK；没有修改 V1～V5，没有删表、清库或搬运用户收藏。数据库仍是24张业务表，迁移 V1～V6 成功。

`xl_favorite` 原样保留；旧favorite接口作为历史兼容接口保留，正式小程序不再调用。旧审计、通知文案不批量改写。暂不需要用户作数据转换决定；若未来需要提供旧收藏导出、迁移或退出兼容接口，应单独确定规则，不自动转换。

后端测试使用随机、独立测试身份，不冒充真实微信身份。新增真实 HTTP 测试产生的商品在测试结束后仅对本测试所属商品做软删除，避免其成为正式推荐数据；不删除已有业务数据。

本机 Redis 起初未运行；启动既有 WSL Redis 7.4.11 的16379端口后返回 PONG，正常后端以 `db,auth,redis` 配置启动并通过健康检查。没有关闭限流或放宽认证。微信配置检查仅输出存在与否。

## 3. 实际验证结果

| 检查 | 命令/方式 | 实际结果 |
| --- | --- | --- |
| 后端完整编译、打包与回归 | `pwsh -NoProfile -File scripts/verify-backend.ps1 -Database` | 138单元测试 + 186集成测试 = 324通过，失败0、跳过0；真实MySQL8.4、Flyway6版本验证通过 |
| 新点赞HTTP补充回归 | `pwsh -NoProfile -File .local/backend-day5/run-admin-tests.ps1 -Tests Day5SharedHttpIT -Log likes-http-final` | 4通过、失败0；随机端口真实HTTP + MySQL；包含匿名401、管理员403、重复点赞、取消、历史收藏隔离、通知收件人隔离、后台及个人统计一致 |
| 小程序 | `npm run check --prefix apps/miniprogram` | TypeScript、211项测试、工程引用检查通过，失败0 |
| 官方离线编译 | `node apps/miniprogram/scripts/check-wechat.cjs "D:/develop/微信web开发者工具"` | wcc编译28份WXML、wcsc编译30份WXSS成功；这不是模拟器/真机视觉验收 |
| 管理后台 | `npm run check --prefix apps/admin` | TypeScript、72项Vitest、Vite生产构建通过；仍有包体超过500kB的构建提示 |
| 后台组件流程浏览器回归 | HTTP开发地址执行 `npm run test:e2e --prefix apps/admin` | 16通过、1显式跳过。此组使用API夹具，不能当作真实后端端到端证据；跳过项需要专用测试适配器凭据 |
| 正常后端真实浏览器 | `.local/likes-20261030/browser.cjs` | 无请求拦截；管理员登录/退出，10页面×1366/1440宽度共20项布局，真实HTTP加载、无错误提示、无请求失败、无页面异常；侧栏/顶栏固定与主内容独立滚动通过 |
| 微信模拟器原生运行 | `.local/likes-20261030/native.json` | 当前会话读取论坛5条、交易4条（当时包含临时测试商品）、已登录个人中心、我的点赞2条帖子；四页均无loadFailed，未捕获页面异常。后续设备尺寸调用超时，不能认定完整模拟器验收 |
| 模拟器实际点赞点击 | `.local/likes-20261030/native-like.json` | 自动化callMethod超时，未判通过；数据库复核目标2822仍无点赞，不存在测试留下的未还原点赞 |
| Git保护 | 工作前后状态、源码ZIP与逐文件比对 | 原源码文件无丢失；未执行提交、推送、重置或清理。已有未提交成果继续保留 |

验证中还遇到：首次Playwright因默认HTTPS但服务未启用HTTPS启动超时，改用明确HTTP测试地址后通过；首次后台真实截图出现开发服务断开提示，独立启动稳定的HTTPS服务后重新严格等待HTTP结果，20项复核无错误。没有通过伪造接口结果处理这些失败。

## 4. 截图与证据

- `.local/likes-20261030/admin-products-1440.png`：真实后台商品列表。
- `.local/likes-20261030/admin-settings-1366.png`：真实后台系统设置。
- `.local/likes-20261030/browser.json`：实际请求状态、布局、页面错误及退出记录，不含Token或密码。
- `.local/likes-20261030/mini-final.log`、`admin-final.log`、`backend-delivery.log`、`http-targeted.log`、`wechat-compile.log`：验证结果。
- `.local/likes-20261030/source-before.zip`、`git-before.txt`、`git-after.txt`：本轮保护记录，仅本机保存。

微信窗口捕获为白屏/未正确合成的工具画面，未作为页面交付截图；没有反复截屏。skill自带浏览器inspector访问本地自签名HTTPS时因 `ERR_CERT_AUTHORITY_INVALID` 失败，不宣称其评分通过；真实浏览器另用明确忽略本地证书错误的Playwright进行检查，未修改浏览器全局安全设置。

## 5. 未完成验收与已知边界

- **首次真实微信登录、用户手动选择头像昵称、主动退出后再次确认，仍需用户在微信工具或真机实际操作验收。** 本轮未代替用户选择微信资料，也未把本地身份适配器测试当成官方换码验收。
- 现有真实登录会话恢复与页面API读取已运行；375×812、430×932逐页视觉、键盘与安全区的完整原生复核未完成，不声称全部手机验收通过。
- 模拟器自动化SDK读取设备与调用页面方法超时；窗口截图无法可靠体现渲染。这个工具阻碍不影响已通过的官方语法编译与接口测试，但仍需补视觉确认。
- `git diff --check` 仍报告此前论坛卡片/论坛首页/交易首页WXSS的3处末尾空行；与工作前ZIP一致，本轮未为消除提示而改写已有成果。
- 后台Element Plus分页small等旧属性有弃用提醒；Vite包体提示保留为后续优化项，不为此进行无关依赖升级或构建重构。
- 本轮真实后台浏览器主要验证读取、布局和登录退出；修改/危险操作的组合流程由既有浏览器夹具测试及真实HTTP集成测试覆盖，没有声称重新手动执行所有后台管理动作。

## 6. OSS前准备

当前继续使用既有本地图片存储、上传与签名访问机制。后续需明确Bucket地域、读写权限、图片公开/授权访问策略、域名及小程序合法域名、凭据的本机/服务端配置方式，再按既有存储接口接入。旧图片保留与迁移另定方案；本轮没有新增云密钥、OSS依赖或远程桶操作。

后端运行端口18081，后台开发地址 `https://localhost:5173`；恢复所需敏感配置仍来自本机私有文件/环境，勿把其内容提交。历史数据不需要转换即可使用本轮新规则。

## 7. 本轮修改文件

以下按工作前源码ZIP进行比较，另列新增测试及设计文档；不把之前已有的Git未提交文件全部算作本轮成果。


本轮共69个源码、测试与文档文件：

- `apps/admin/src/components/content/ContentMetricGrid.vue`
- `apps/admin/src/services/admin-adapters.ts`
- `apps/admin/src/services/admin-models.ts`
- `apps/admin/src/styles/admin.css`
- `apps/admin/src/styles/content-management.css`
- `apps/admin/src/styles/tokens.css`
- `apps/admin/src/views/DashboardView.vue`
- `apps/admin/src/views/OperationLogView.vue`
- `apps/admin/src/views/ProductManagementView.vue`
- `apps/admin/src/views/SystemSettingsView.vue`
- `apps/miniprogram/miniprogram/backend/adapters.ts`
- `apps/miniprogram/miniprogram/backend/client.ts`
- `apps/miniprogram/miniprogram/backend/pages.ts`
- `apps/miniprogram/miniprogram/backend/service.ts`
- `apps/miniprogram/miniprogram/components/forum-post-card/index.wxml`
- `apps/miniprogram/miniprogram/components/market-product-card/index.ts`
- `apps/miniprogram/miniprogram/components/market-product-card/index.wxml`
- `apps/miniprogram/miniprogram/components/market-product-card/index.wxss`
- `apps/miniprogram/miniprogram/features/market/types.ts`
- `apps/miniprogram/miniprogram/pages/chat-settings/index.wxss`
- `apps/miniprogram/miniprogram/pages/contact-chat/index.wxss`
- `apps/miniprogram/miniprogram/pages/contact/index.wxml`
- `apps/miniprogram/miniprogram/pages/contact/index.wxss`
- `apps/miniprogram/miniprogram/pages/forum-detail/index.wxml`
- `apps/miniprogram/miniprogram/pages/forum-detail/index.wxss`
- `apps/miniprogram/miniprogram/pages/forum-publish/index.wxss`
- `apps/miniprogram/miniprogram/pages/forum/index.wxml`
- `apps/miniprogram/miniprogram/pages/login/index.wxml`
- `apps/miniprogram/miniprogram/pages/market-chat/index.wxss`
- `apps/miniprogram/miniprogram/pages/market-detail/index.wxml`
- `apps/miniprogram/miniprogram/pages/market-detail/index.wxss`
- `apps/miniprogram/miniprogram/pages/market-publish/index.wxss`
- `apps/miniprogram/miniprogram/pages/market/index.wxml`
- `apps/miniprogram/miniprogram/pages/my-collections/index.wxml`
- `apps/miniprogram/miniprogram/pages/my-items/index.wxss`
- `apps/miniprogram/miniprogram/pages/my-posts/index.wxml`
- `apps/miniprogram/miniprogram/pages/my-posts/index.wxss`
- `apps/miniprogram/miniprogram/pages/profile-edit/index.wxss`
- `apps/miniprogram/miniprogram/pages/profile/index.wxml`
- `apps/miniprogram/miniprogram/pages/profile/index.wxss`
- `apps/miniprogram/miniprogram/pages/report/index.wxss`
- `apps/miniprogram/miniprogram/pages/search/index.wxml`
- `apps/miniprogram/miniprogram/pages/search/index.wxss`
- `apps/miniprogram/miniprogram/pages/settings/index.wxml`
- `apps/miniprogram/miniprogram/pages/settings/index.wxss`
- `apps/miniprogram/miniprogram/pages/user-home/index.wxml`
- `apps/miniprogram/miniprogram/pages/user-home/index.wxss`
- `apps/miniprogram/miniprogram/styles/tokens.wxss`
- `apps/miniprogram/tests/backend-pages.test.cjs`
- `apps/miniprogram/tests/inline-login.test.cjs`
- `apps/miniprogram/tests/likes.test.cjs`
- `apps/miniprogram/tests/profile-auth-sheet.test.cjs`
- `apps/miniprogram/tests/v1-p1-fixes.test.cjs`
- `apps/server/src/main/java/cn/xinkelink/admin/AdminProjection.java`
- `apps/server/src/main/java/cn/xinkelink/content/ContentMapper.java`
- `apps/server/src/main/java/cn/xinkelink/content/DiscoveryController.java`
- `apps/server/src/main/java/cn/xinkelink/content/DiscoveryService.java`
- `apps/server/src/main/java/cn/xinkelink/market/MarketController.java`
- `apps/server/src/main/java/cn/xinkelink/market/MarketMapper.java`
- `apps/server/src/main/java/cn/xinkelink/market/MarketService.java`
- `apps/server/src/main/resources/db/migration/V6__product_likes.sql`
- `apps/server/src/test/java/cn/xinkelink/content/Day3HttpIT.java`
- `apps/server/src/test/java/cn/xinkelink/database/DatabaseMigrationIT.java`
- `apps/server/src/test/java/cn/xinkelink/integration/Day5SharedHttpIT.java`
- `apps/server/src/test/java/cn/xinkelink/market/MarketServiceIT.java`
- `apps/server/src/test/java/cn/xinkelink/market/MarketTransactionsIT.java`
- `docs/backend/03-database-design.md`
- `docs/backend/04-api-contract.md`
- `docs/likes-login-ui-verification-20261030.md`

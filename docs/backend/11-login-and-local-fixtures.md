# 原地微信登录与本地开发数据

> 后续状态：2026-10-26真实微信登录已联调成功，默认数据已改为少量照片预览。最新结果见[12号报告](12-real-wechat-curated-preview.md)。下文保留上一轮大批数据及当时未配置凭据的历史记录。

日期：2026-10-25～26。本轮仅简化小程序登录交互、补充可重复执行的开发数据，并验证已有接口；不扩展业务模块，不部署，不执行 Git 提交或清理。

## 1. 登录交互

“我的”保留原名片、统计和菜单布局。未登录昵称显示“登录”，点击名片或原有“微信登录”入口均在当前页面执行同一条登录链路；登录失败保留当前页面及未登录状态，以Toast反馈。

登录链路仍是 `wx.login → POST /api/v1/auth/wechat {code} → 服务端code2Session → 既有JWT会话`。多个同时触发的操作共用一次登录请求，退出或取消会话后不能被迟到的登录响应重新登录。受保护操作等待登录成功后继续，公开论坛及交易内容不要求登录。Token过期复用已有刷新逻辑。

登录成功只获得身份与平台资料，不代表微信头像、昵称授权。头像使用用户主动选择的 `open-type="chooseAvatar"`，昵称使用 `input type="nickname"`；上传和保存沿用现有资料API。不会静默获取真实微信资料，也不调用已回收的getUserProfile/userInfo授权方式。[微信登录说明](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/login)、[头像昵称填写能力](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/userProfile)。

旧登录页文件保留以免破坏原有成果，但取消路由注册，生产导航不再引用它；不再显示原介绍卡片、独立登录按钮及游客入口。

## 2. 微信失败的实际原因与配置

本轮在已登录的微信开发者工具中调用原生 `wx.login()`，得到 `login:ok` 及临时code；将该code发送给当前正常运行的后端后，实际返回 **502 / WECHAT_LOGIN_FAILED / data=null**，没有签发Token。

只读检查当前后端进程：`XINKELINK_WECHAT_APP_ID`、`XINKELINK_WECHAT_APP_SECRET` 均未注入。`OfficialWechatCodeClient.exchange` 在配置缺失时立即拒绝，尚未请求微信服务器，因此这次失败不能归因于已过期code、微信返回的invalid appsecret或网络上游超时。JWT配置存在，管理员登录可用，Redis连接可用；未用测试换码适配器伪造成功。

本地小程序公开AppID为 `wx82a5bf2ac74a2f33`。真实联调应：

1. 在对应微信小程序管理平台取得该AppID的AppSecret，仅通过后端进程环境或安全的密钥管理方式注入，不能写入源码或文档。
2. 启动后端前设置 `XINKELINK_WECHAT_APP_ID` 和 `XINKELINK_WECHAT_APP_SECRET`，重启该后端进程使其生效；确认不是另一个AppID或旧进程。
3. 在“我的”点击“登录”取得新code，验证服务端真实换码及账号创建、Token刷新、退出，再验证用户主动填写头像昵称。
4. 当前 `http://127.0.0.1:18081` 仅供本机开发工具联调；真机应使用手机可访问的合法HTTPS服务地址并配置小程序请求、上传、下载及WSS域名。关闭本地URL检查不等于完成真机域名配置。

安全证据在被Git忽略的 `.local/login-seed/wechat-login-evidence.json`，只保存状态码、错误码、code是否返回，不保存code、openid、AppSecret或Token。真实code2Session成功、真机和生产WSS仍未验收。

## 3. 数据约束

沿用Flyway V1～V5，不新增分类或改动历史迁移。论坛分类实际为校园生活、失物招领、学习交流、校园互助（`campus/lost_found/study/help`）；没有表白墙或二手精选分类。商品为数码、教材、生活、服饰、其他（`digital/books/living/fashion/other`），运动用品放入现有“其他”分类。

数据使用 `local-seed-v1` 独立命名空间，人物及经历均为虚构；用户合成身份字段只满足现有数据库非空约束，不是真实微信AppID/OpenID。不会生成可登录的code、Token或用户认证会话，也不增加产品测试登录入口。

素材为本地程序生成的PNG演示插图与头像，不是学生照片、实物商品照片或学校授权素材；不使用外部失效URL。文件通过既有媒体资源及权限接口访问，不直接暴露存储目录。

原有数据库已先备份到忽略且受限的 `.local/login-seed/private/before-seed.sql`；不会清空、覆盖或还原数据库。本轮生成脚本只新增自己的数据，重复运行不重置已经存在的业务状态。

## 4. 可重复执行的数据入口

在仓库根目录使用 PowerShell 7.4+：

```powershell
# 只输出计划，不连接数据库
./scripts/seed-local-data.ps1
# 生成完整批次并回滚数据库，用于试运行
./scripts/seed-local-data.ps1 -RollbackTest
# 显式提交本地开发数据
./scripts/seed-local-data.ps1 -Apply
# 只读检查数量、关联及素材完整性
./scripts/seed-local-data.ps1 -Verify
```

默认复用现有 `.local/backend-day1/private/runtime.json` 中的运行账号，数据库是 `127.0.0.1:13306/xinkelink_dev`，素材目录为 `.local/backend-day5/images`，应与后端 `XINKELINK_IMAGE_DIRECTORY` 保持一致。可用 `-JavaHome`、`-ImageDirectory`、`-CredentialFile`、`-DatabaseUrl` 指定现有环境；不会安装依赖。JDK必须为21，图片目录必须位于当前仓库的`.local`下。

PowerShell及Java入口均拒绝远程数据库、名称不以`_dev/_test`结尾的数据库；Java额外要求环境标记为`local/test`。生成器只存在于`src/test`，没有Spring组件、HTTP入口或产品运行开关，不打入生产Jar。默认计划不读取数据库凭据、不连接数据库；真正导入需要显式`-Apply`。

整个批次使用一个数据库事务，持有同批次的MySQL命名锁，失败回滚。只更新本事务新建用户的头像指针及新建会话的最后消息指针，不修改其他已有行。使用独立且不可登录的禁用管理员行作为批次标记，关联虚构公告和审核历史；没有可用密码、管理员会话或真实推送。

批次已提交后再次`-Apply`只核验，不补回用户后来移除的图片、点赞或收藏，也不重设状态；若数量或文件被人为破坏，则明确报错，交由开发者检查，不自动覆盖修复。PNG使用固定资源键及CREATE_NEW写入；回滚试运行会保留这些忽略目录中的素材文件，后续导入校验复用，绝不覆盖同名不同内容。

每次输出报告到`.local/seed-local-data/seed-<时间>.json`，最近报告为`latest-report.json`，包含实际数量、各表新增行数、图片目录及提交/回滚状态，不含凭据。

## 5. 本次实际导入

| 数据类型 | 本批实际数量 |
| --- | ---: |
| 虚构普通用户 | 100 |
| 帖子 | 500 |
| 评论和二级回复 | 2,000 |
| 商品 | 300 |
| 点赞与收藏 | 3,000（1,800点赞、1,200收藏） |
| 会话 | 150（50普通、100商品关联） |
| 持久消息 | 1,000 |
| 举报 | 60 |
| 站内通知 | 500 |
| 公告 | 15 |

另有1,685张本地PNG、3个通知任务（1个已发送、2个草稿）、238条虚构业务处置历史和58条明确标记`local_seed_snapshot`的系统样本日志。这些日志仅用于展示，不代表管理员真实执行过相应操作，更不是正式审计凭证。

覆盖四类论坛内容、纯文字/单图/双图/四图、匿名、无评论和多评论、隐藏/待复核/软删除；五类商品、五档成色、价格和可议价差异、1/2/4张图、在售/售出/下架及独立治理隐藏；2022～2026年级、不同专业、单独通道限制、三通道限制、临时及永久封禁；两级回复、商品消息卡片、已读水位、置顶/免打扰、待处理/处置/驳回举报及证据；发布/草稿/撤回公告和对应通知。

已发送通知任务有90名本批卖家收件人，指纹与接收记录一致；不向批次外的原有账号发送通知。举报结果反馈使用真实通知模型，不因关联内容隐藏而丢失反馈正文。回复通知按实际回复对象和帖子作者去重，避免自通知。

原有24张业务表共1,312行的逐行哈希在导入后均保持一致；再次导入后全部13,104行的哈希均保持一致，新增行数为空/0。证据：`.local/login-seed/preseed-preservation.json`、`before-repeat-preservation.json`、`seed-first.json`、`seed-repeat.json`及`seed-verify.json`。

合成用户不能拿来通过正式微信登录。公开帖子/商品和后台列表可直接用于界面测试；这些用户的私信、通知通过受权限保护的服务及数据库核验，不会把它们冒充当前真实微信用户的个人数据。

## 6. 最终验证与边界

| 检查 | 本轮实际结果 |
| --- | --- |
| 后端Java21编译、打包、全量回归 | **319通过：138单元/基础HTTP + 181真实MySQL集成；0失败、0跳过** |
| 新数据工具测试 | 计划与安全限制、完整批次回滚、二次执行、保留已有昵称及用户后来移除的图片；含在上述全量结果中 |
| 小程序TypeScript、项目检查和测试 | **172通过：原156项及新增16项登录场景；0失败、0跳过** |
| WXML/WXSS官方离线编译 | wcc编译27个WXML、wcsc编译29个WXSS成功；不等于模拟器/真机验收 |
| 管理后台TypeScript、Vitest和生产构建 | 70项Vitest通过、构建通过；后台代码本轮未改 |
| MySQL及迁移 | 真实MySQL8.4，保留既有V1～V5；无新迁移 |
| 首次导入/重复导入/只读检查 | 10类业务数量达到目标；第二次执行新增0行，已有行哈希不变；全量回归后再次只读核验通过 |
| 真实数据库查询 | 论坛分类/匿名/图片数/评论层级、商品状态及图片、会话成员/消息序号、已读水位、举报结果、通知收件人、公告状态均已查询 |
| 正常后端真实HTTP及Chrome | 管理登录、Dashboard、7类列表及第二页、公开论坛分类/关键词/游标、商品可售筛选/价格排序、种子PNG访问通过；无请求拦截或测试换码适配器 |
| 真实浏览器尺寸 | 1366×812、1440×812概览及帖子/商品/举报/用户/公告/通知列表；无页面JS异常 |
| 生产Jar隔离 | 不含`cn/xinkelink/seed`及`Day5IntegrationServer`测试换码类 |
| 旧登录页导航 | 已取消app注册，生产TS/WXML无`/pages/login/index`导航引用 |

测试计数以本轮Maven日志实际汇总为准。构建目录中仍保留10月24日的旧`AuthHttpIT`报告，不纳入本轮统计，也未为清理历史证据执行clean。

本次后台真实统计为102个有效用户、34条今日帖子、173件正常在售商品、22条待处理举报；其中包含原有开发数据和本批虚构样本，**不是真实学生或生产业务规模**。后台未采集的日活、停留时长仍标注未采集，没有为图表造实时指标。

安全证据目录`.local/login-seed/`：

- `backend-final.stdout.log`、`backend-final.stderr.log`、`test-counts.json`：本轮编译打包及319项结果。
- `miniprogram-final.log`、`wechat-compile-final.log`、`admin-check.log`：两端检查。
- `seed-first.json`、`seed-repeat.json`、`seed-verify.json`、`seed-after-regression.json`、`coverage.txt`：数量、状态、关联、已读及回归后核验。
- `http-evidence.json`：正常后端安全HTTP结果、独立数据库计数及页面检查，不保存Token。
- `screenshots/seed-dashboard-1366.png`、`seed-dashboard-1440.png`、`seed-posts.png`、`seed-products.png`、`seed-reports.png`、`seed-users.png`、`seed-announcements.png`、`seed-notifications.png`：真实Chrome截图；商品图已等待加载并核对为256px本地PNG。

未完成的真实环境验证：AppID/AppSecret配置后的真实code2Session成功及完整微信登录态、头像昵称真机交互、生产HTTPS/WSS。微信自动化切页/截图仍出现超时，未取得本轮可交付的小程序实际截图，不以Node Page测试代替微信模拟器验收。搜索仍沿用既有首批50条结果的前端边界，本轮没有扩展其分页交互。后台构建保留既有单包超过500kB的提示，未做无关重构。

## 7. 本轮文件清单

修改12个已有文件：

- `apps/miniprogram/miniprogram/app.json`
- `apps/miniprogram/miniprogram/backend/client.ts`
- `apps/miniprogram/miniprogram/backend/pages.ts`
- `apps/miniprogram/miniprogram/features/profile/repository.ts`
- `apps/miniprogram/miniprogram/pages/profile/index.ts`
- `apps/miniprogram/miniprogram/pages/profile/index.wxml`
- `apps/miniprogram/miniprogram/pages/settings/index.ts`
- `apps/miniprogram/miniprogram/pages/profile-edit/index.ts`
- `apps/miniprogram/miniprogram/pages/profile-edit/index.wxml`
- `apps/miniprogram/miniprogram/pages/my-posts/index.wxml`
- `apps/miniprogram/miniprogram/pages/user-home/index.wxml`
- `apps/miniprogram/tests/v1-profile-pages.test.cjs`

新增8个文件：

- `apps/miniprogram/tests/inline-login.test.cjs`
- `scripts/seed-local-data.ps1`
- `apps/server/src/test/java/cn/xinkelink/seed/LocalSeedData.java`
- `apps/server/src/test/java/cn/xinkelink/seed/LocalSeedDataTest.java`
- `apps/server/src/test/java/cn/xinkelink/seed/LocalSeedDataIT.java`
- `apps/server/src/test/java/cn/xinkelink/seed/LocalSeedRunner.java`
- `apps/server/src/test/java/cn/xinkelink/seed/LocalSeedRunnerTest.java`
- `docs/backend/11-login-and-local-fixtures.md`

没有修改管理后台、Java产品业务代码、API字段或数据库迁移；旧登录页面文件和所有基线文件保留。忽略目录中的素材、临时证据、备份与凭据不属于产品提交内容。

# 信科 Link 最终本地业务回归报告

本轮于 2026-11-05 开始，2026-11-06 完成收尾核对。范围：当前微信小程序业务控制器、Java 服务、MySQL/Redis、本地 WebSocket，以及 PC 管理后台真实浏览器操作。

## 结论

**本地业务回归通过：22 组主链路 + 5 组补充链路，共 27 组。** 后端、小程序及管理端的自动化测试共 656 项通过，最终有效运行无失败、无跳过；两端类型检查、后端打包、管理端构建、WXML/WXSS 官方离线编译通过。

这证明下列本地链路在本轮环境下可用。**不能据此认定真实微信登录、真机、生产 HTTPS/WSS、生产 OSS 或上线审核已经验收通过。**

本轮只新增测试脚本、测试证据及本文档，未修改产品业务代码、迁移或现有页面。未执行 Git commit、push、reset、clean。

## 环境与隔离

- Java 21.0.12.1、Spring Boot 4.1.1、MySQL 8.4.13、Node 24.15.0；使用仓库既有依赖，没有安装新依赖。
- Maven 全量测试库：`xinkelink_e2e_20261105_3114e825_test`。
- 最终业务回归库：`xinkelink_e2e_20261105_04ae9743_test`。复制原库结构、Flyway 历史及配置基线，**没有复制原有用户、私信、帖子和商品**。
- 最终业务服务：18086；真实管理端：HTTPS localhost:5175；独立测试 Redis：16385。既有开发 Redis 16379 与测试实例均实际返回 PONG。
- Flyway V1～V6 均为成功状态。本轮没有新增或更改迁移。
- 测试身份由现有 test-classpath 的 Day5IntegrationServer 适配器提供，只允许三个显式测试 code。该适配器不属于生产启动路径，也没有伪造真实微信 code/openid。
- Vue 管理端使用真实 Chromium、真实 API 和上述 MySQL/Redis，不拦截请求。浏览器忽略本地自签名证书错误，这不代表证书已获得公开信任。
- 小程序使用当前编译后的原生 Page 控制器及正式网络适配层，fixture:false，通过 TCP 请求真实后端；wx.login code 和资料确认由测试驱动。**不是原生模拟器界面点击、微信网络栈或真人授权。**
- 图片为本轮隔离的本地存储；用小 PNG 验证上传、头像/内容关联和授权 URL 实际读取，未调用 OSS。该图片仅是功能测试夹具，不代表真实素材及视觉质量验收。

## 实际业务用例

| 序号 | 链路 | 结果 |
| --- | --- | --- |
| 1 | 当前小程序资料确认→真实上传→用户资料保存 | 通过 |
| 2 | 公开浏览、未登录401及普通用户禁止访问后台403 | 通过 |
| 3 | 小程序发布帖子→详情→首页→我的帖子 | 通过 |
| 4 | 一级评论→二级回复→评论计数与评论点赞同步 | 通过 |
| 5 | 帖子点赞/取消→我的点赞同步，按钮不重新加载列表详情 | 通过 |
| 6 | 匿名展示保护、越权编辑拒绝、并发点赞唯一性 | 通过 |
| 7 | 小程序发布商品→搜索→详情→商品点赞与我的点赞 | 通过 |
| 8 | 我想要→唯一商品会话→持久化消息→WebSocket→未读已读 | 通过 |
| 9 | 用户举报持久化及举报结果待后台处置 | 通过 |
| 10 | 真实后台登录、路由守卫及服务端认证 | 通过 |
| 11 | 后台帖子搜索、详情、隐藏恢复→用户端展示同步 | 通过 |
| 12 | 后台评论上下文、隐藏恢复→回复关系及前台计数同步 | 通过 |
| 13 | 后台商品下架恢复→搜索列表及商品详情同步 | 通过 |
| 14 | 后台举报审核→帖子隐藏→通知隔离→重复审核拒绝 | 通过 |
| 15 | 后台禁言→禁止发帖/评论/私信→解除限制恢复 | 通过 |
| 16 | 后台临时封禁→受保护接口拒绝→解除后重新登录；永久封禁不可意外恢复 | 通过 |
| 17 | 后台公告创建发布→前台公告通知→编辑撤回 | 通过 |
| 18 | 后台系统通知受众计算→发送记录→用户接收及重复发送拒绝 | 通过 |
| 19 | 后台设置保存→刷新持久化→恢复测试前配置 | 通过 |
| 20 | 审计日志覆盖上述管理操作、查询详情及CSV导出 | 通过 |
| 21 | 后台全部菜单真实请求、固定栏滚动及无页面异常 | 通过 |
| 22 | 用户JWT刷新、退出吊销及后台退出路由保护 | 通过 |
| 23 | 后台真实关键词/分类/时间筛选、翻页、批量隐藏恢复及删除确认 | 通过 |
| 24 | 后台删除一级评论二次确认，二级回复及帖子计数同步 | 通过 |
| 25 | 商品编辑越权拒绝，卖家下架/上架/已售及后台禁止恢复已售 | 通过 |
| 26 | 通知收件人隔离、已读幂等及未读减少 | 通过 |
| 27 | WebSocket未认证及URL伪造身份拒绝 | 通过 |

### 重点证据

- 帖子和商品点赞后均查询“我的点赞”；取消后移除。测试明确断言点击期间没有额外 GET 列表/详情请求，保持局部更新。
- 匿名帖子向其他用户隐藏真实作者标识；越权编辑返回 403。五个并发点赞请求最终仅增加一条点赞关系。
- “我想要”打开关联商品的唯一会话；消息写入后收到本地 WebSocket 事件，另一方可以从 HTTP 历史读到该消息。断开实时连接后仍可发送、查历史；同 clientMessageId 不重复落库。
- 后台用真实页面执行隐藏/恢复、商品治理、举报、限制、临时及永久封禁、公告和通知；同时从另一用户接口核对效果。
- 删除帖子、删除评论、用户治理、公告发布撤回与通知发送均实际操作对应确认界面。取消帖子删除后内容仍存在；确认删除后不可见。一级评论删除后回复和计数同步。
- 后台搜索、分类、时间筛选和翻页都观察到真实请求与返回：22 条专用分页夹具，学习分类 21 条，第二页 10 条；批量隐藏/恢复后逐项读回状态。
- 公告编辑保存草稿后重新发布、撤回，用户端当前公告列表同步变化。系统通知依据真实测试用户计算接收范围，创建站内收件记录；没有发送微信订阅消息。
- 系统设置通过页面保存，整页重新加载后保持，再恢复测试前配置；SQL 可见 content_review 的持久化版本。effect_mode 仍为 configuration_only，**不执行自动 AI/敏感词拦截**。
- Dashboard 四项数字另与 MySQL 直接计数逐项对照；七天趋势返回真实帖子统计。DAU 仍返回 null，未伪造实时活跃数。收尾核对跨日，当日新增为 0 与库内当天记录一致。

## 命令与结果

下列命令均为本轮实际执行。隔离数据库、Redis、JDK 与存储环境由 .local/full-chain-20261105/environment.ps1 设置；不能省略隔离配置后直接对原开发库重跑治理测试。

| 验证 | 实际命令/入口 | 最终结果 |
| --- | --- | --- |
| Java 编译、测试、数据库集成、打包 | apps/server：mvn -B -Dxinkelink.build.directory=D:\CODE\XinKeLink\.local\full-chain-20261105\server-build -Pdb-integration verify | 150 单元 + 186 集成，共 336；0 失败/错误/跳过；BUILD SUCCESS |
| 小程序 | apps/miniprogram：npm run check | tsc --noEmit、241 测试、项目引用检查通过 |
| WXML/WXSS | apps/miniprogram：node scripts/check-wechat.cjs "D:/develop/微信web开发者工具" | 官方 wcc：28 个 WXML；wcsc：32 个 WXSS，通过；仅离线编译 |
| PC 管理端 | apps/admin：npm run check | 79 Vitest 测试、vue-tsc、Vite 生产构建通过 |
| 全新隔离库主链路 | .local/full-chain-20261105/run-clean.ps1 → regression.cjs（未使用 --resume） | 22/22，通过；最终证据无复用步骤 |
| 后台及权限补充 | node .local/full-chain-20261105/supplement.cjs | 5/5，通过 |
| 数据库保护与一致性 | .local/full-chain-20261105/verify-state.ps1 | 原库 11 张表计数一致；隔离库关联检查通过 |
| 统计与图片读取 | node .local/full-chain-20261105/check-dashboard-media.cjs | Dashboard 四项计数一致；图片实际 HTTP 200，68 字节 image/png |

最终两份业务证据保存了 139 条 HTTP/WS 状态记录，**不是接口数量或全部网络请求数**。401/403/404/409 是权限隔离、不可见资源、重复处置等明确预期的负向场景，不是被隐藏的失败。

首轮 Maven 有两项数据生成测试因隔离库名称未以 _dev/_test 结尾而被安全规则拒绝；已保留失败日志，并改用符合约束的隔离测试库完整重跑。没有放宽保护规则。业务脚本编写期间还校准了异步抽屉等待、接口字段和状态约定，初始日志均保留；未为让脚本通过修改业务实现。最终主链路完整重跑 22 项，不依赖前轮已完成标记。

## 数据保护与数据库证据

- 原开发库 xinkelink_dev 的 11 张表逐项比较记录数，均与本轮开始一致：[original-counts-verified.json](D:/CODE/XinKeLink/.local/full-chain-20261105/original-counts-verified.json)。这是计数核对，不冒充全库内容校验和。
- 原收藏数据未转换、未清理；原库 xl_favorite 的 1,224 行保留，最终测试库收藏为 0 行，所有当前流程使用点赞。
- 隔离库重复点赞组合、重复会话消息序号、非法二级回复关系、已处理举报缺少处理历史的数量均为 0。
- 存在成功与业务拒绝审计记录；CSV 实际导出，并检查未包含测试管理员密码或访问 Token。
- 隔离库、测试内容、日志均保留。本轮临时 18086、5175 和 16385 已停止；没有停止原有开发服务、没有清理原库。
- Git 分支为 codex/phase-1-foundations；写入本文档前仍为既有 293 条工作区状态。原有未提交成果保留。本轮凭据只在被 Git 忽略的 .local 私有目录，不进入本文档或仓库。

## 问题与剩余验收

本轮覆盖范围内未复现新的 P0/P1 业务故障。以下仍需区分处理：

1. **真实微信/真机未重验**：本轮不包含官方 code2Session、真人头像昵称选择、真机网络、键盘及图片预览。此前人工确认结果不能代替本轮验证。
2. **生产条件未验**：正式 HTTPS/WSS 域名和证书、合法域名配置、生产 OSS、上线审核未纳入。本地 HTTP/WS 通过不等于生产环境通过。
3. **设置的实际作用有限**：配置已持久化，但自动审核规则仍为配置展示；DAU 尚未接入真实活跃统计。这些限制已有界面/接口说明，不应对外宣称已实现。
4. **可改进交互（P2，后续已修复，见文末）**：本轮审计时商品管理的下架/恢复直接执行，没有二次确认；删除、封禁、举报处置有确认。审计阶段只测试，未改此交互。证据：ProductManagementView.vue 的 toggleStatus/setProductsStatus，及主链路直接点击后成功响应。
5. 管理端生产 JS 主包约 1.20 MB，构建通过；本轮未开展包拆分或性能改造。

## 证据路径

- [business-results.json](D:/CODE/XinKeLink/.local/full-chain-20261105/business-results.json)、[business-run-clean.log](D:/CODE/XinKeLink/.local/full-chain-20261105/business-run-clean.log)：22 组完整主链路及最终日志。
- [supplement-results.json](D:/CODE/XinKeLink/.local/full-chain-20261105/supplement-results.json)、[supplement.log](D:/CODE/XinKeLink/.local/full-chain-20261105/supplement.log)：5 组补充用例、真实管理端请求。
- [backend-test-summary.json](D:/CODE/XinKeLink/.local/full-chain-20261105/backend-test-summary.json)、[backend-tests.log](D:/CODE/XinKeLink/.local/full-chain-20261105/backend-tests.log)、[backend-tests-first-attempt.log](D:/CODE/XinKeLink/.local/full-chain-20261105/backend-tests-first-attempt.log)：最终及首轮后端记录。
- [miniprogram-check.log](D:/CODE/XinKeLink/.local/full-chain-20261105/miniprogram-check.log)、[wechat-compile.log](D:/CODE/XinKeLink/.local/full-chain-20261105/wechat-compile.log)、[admin-check.log](D:/CODE/XinKeLink/.local/full-chain-20261105/admin-check.log)：前端检查、官方离线编译与构建。
- [database-verification.json](D:/CODE/XinKeLink/.local/full-chain-20261105/database-verification.json)、[dashboard-media-results.json](D:/CODE/XinKeLink/.local/full-chain-20261105/dashboard-media-results.json)、[shutdown-verification.json](D:/CODE/XinKeLink/.local/full-chain-20261105/shutdown-verification.json)：数据库一致性、统计图片与环境收尾。
- 必要的真实浏览器截图（1440×900）：[admin-post-detail.png](D:/CODE/XinKeLink/.local/full-chain-20261105/admin-post-detail.png)、[admin-report-resolved.png](D:/CODE/XinKeLink/.local/full-chain-20261105/admin-report-resolved.png)、[admin-settings-persisted.png](D:/CODE/XinKeLink/.local/full-chain-20261105/admin-settings-persisted.png)、[admin-empty-results.png](D:/CODE/XinKeLink/.local/full-chain-20261105/admin-empty-results.png)。

本轮到此停止，不自动部署、不继续增加功能。

## 2026-11-06：商品状态确认 P2 修复补充

用户随后授权完成上述 P2。修复仅发生在管理端商品状态操作的公共入口，覆盖列表、详情和批量下架/恢复：

- 弹窗说明操作对象、批量数量和展示影响，明确区分“确认下架”和“确认恢复上架”。
- 取消或关闭弹窗不调用状态接口、不修改选择和商品状态、不显示错误。
- 确认后保留现有服务端更新、列表/统计/详情同步；等待期间拒绝重复发起。失败仍以服务端错误提示，不伪装成功。
- 已售商品不可恢复的前后端规则保持不变；没有修改后端、小程序、数据库迁移或页面布局。

验证结果：

| 检查 | 结果 |
| --- | --- |
| 先运行新增浏览器用例验证缺陷 | 修改前如预期失败：点击后没有确认弹窗；原始日志保留 |
| Playwright 组件交互回归（显式 HTTP fixture） | 7/7，通过：取消/关闭、确认、双击、批量两方向、接口失败、等待期间重复点击及已售限制 |
| 隔离 MySQL/Redis + 真实后端 + 真实 Chromium | 6/6，通过：列表、详情、批量下架/恢复、已售限制，以及真实 409 版本冲突提示；没有拦截或伪造响应 |
| 管理端 npm run check | 79 Vitest 测试、TypeScript 和生产构建通过；原有主包大小警告仍在 |
| 原库保护 | 原开发库 11 张业务表记录数保持不变；测试只操作隔离库新建商品 |

修改文件：

- [ProductManagementView.vue](D:/CODE/XinKeLink/apps/admin/src/views/ProductManagementView.vue)：统一确认入口及重复提交保护。
- [product-status-confirmation.spec.ts](D:/CODE/XinKeLink/apps/admin/e2e/product-status-confirmation.spec.ts)：新增 7 个浏览器回归用例。
- [product-report-management.spec.ts](D:/CODE/XinKeLink/apps/admin/e2e/product-report-management.spec.ts)：已有流程加入相应确认步骤。
- 本报告：保留历史审计结果，补充修复与验收结论。

证据保存在 [product-confirmation-20261106](D:/CODE/XinKeLink/.local/product-confirmation-20261106)：browser-red.log、browser-green.log、admin-check.log、real-check.log、real-results.json、original-data-check.json，以及仅一张真实批量确认弹窗截图 real-bulk-confirmation.png。真实接口测试使用既有测试身份适配器，不包含真实微信授权。

此 P2 已修复。本次未执行 Git 提交、推送、重置或清理，未删除原有业务数据。


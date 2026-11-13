# 信科 Link 后端准备：现有项目审查

审查日期：2026-10-23。状态：供审阅的后端设计依据，尚未实施。

本轮只新增 `docs/backend/` 内五份文档。源码、前端设计、依赖、数据库及现有未提交成果不作修改；没有运行迁移、调用微信业务接口或重新执行产品构建/测试。下文的“现状”来自源码和已读测试，不能理解为本轮重新验收通过。

## 1. 仓库结论与文档权威范围

| 位置 | 实际现状 | 后端接入含义 |
| --- | --- | --- |
| `apps/miniprogram` | 原生 TypeScript/WXML/WXSS，20 个注册页面、25 个 features 文件、24 份业务测试及测试 harness；数据来自 Mock、Storage、文件目录 | 没有真实用户身份、HTTP 业务服务或 WebSocket；前端可用不等于服务端闭环完成 |
| `apps/admin` | Vue 3/TypeScript/Vite/Element Plus/Pinia/Vue Router；登录和 10 个管理入口均接视图，另有备用 DevelopingView | 五个 Store 在当前 Pinia 内共享，刷新恢复样例；鉴权为演示开关 |
| `apps/server` | 已有 Maven/Spring Boot **4.1.1** 骨架，Java 编译目标 21 | 继续复用该目录，不能另建/覆盖服务端；目标 3.5.x 与现状不一致 |
| SQL / OpenAPI | 未发现数据库 `.sql`、Flyway migration 或 OpenAPI 文件 | 本轮为首次完整持久层及双端 API 契约设计，不代表已创建数据库 |
| Git | 有大量前序已修改、未跟踪成果 | 禁止清理或当作临时文件删除；本轮没有提交 |

Java 现有能力仅为启动类、`health/HealthController`、`storage/ImageStorage`、`ImageUpload`、`StoredImage` 和一个健康测试类（两项测试）。`pom.xml:9` 是 Boot 4.1.1；`pom.xml:18` 指定 Java 21；本机 `mvn -version` 检测为 Maven 3.9.4、运行 JDK 25.0.1。**编译目标为 21 不代表当前机器实际使用 JDK 21。**

只有 `GET /api/v1/health` 与基础 `/actuator/health` 已存在；应用健康返回 `{application,status}`，不表示数据库、存储或私信可用。当前没有 Security、MyBatis、数据库驱动、Redis、Validation、Flyway、上传实现或业务 Controller。依据：`apps/server/pom.xml`、`src/main/resources/application.yml`、`health/HealthController.java:8`、`src/test/java/cn/xinkelink/health/HealthHttpTest.java:19`。

文档沿用/替代规则：

- 沿用 `docs/architecture.md` 的单仓库、模块化单体、线下交易、匿名不泄露、无学校认证、HTTP 历史消息 + WebSocket、厂商无关图片存储边界。
- `docs/v1-backend-interface-checklist.md` 原本明确是建议；其 `/api/...` 路径及不完整字段，由本目录 [04-api-contract.md](04-api-contract.md) 的 `/api/v1/...` 契约统一。其整数分、稳定 ID、UTC、幂等、权限和消息水位原则继续沿用。
- `design-baseline.md`、`wechat-devtools-import.md`、`runbook.md` 和各阶段验收文档是历史记录；其中“只有页面容器”“后台只有连接页”等已不反映当前源码。
- `v1-frontend-verification.md:101` 仍说搜索/举报/聊天设置未开发，但当前 `app.json` 已注册并存在实现。不能用旧报告代替当前代码盘点。
- 后台第六阶段规格中的本地日志/设置边界仍正确；后端落地后持久化及服务端身份将取代这些模拟实现。没有批准的社区治理展开控件继续不设计。
- 本目录五份文档是待审阅的新后端设计，**不是修改既有前端业务的授权，也不是已实现清单**。不改写旧文件，避免破坏历史证据。

## 2. 小程序页面、组件与数据入口

路由依据：`apps/miniprogram/miniprogram/app.json:2`。底部四个原生 Tab 加中央发布选择构成五个视觉入口；中央发布不作为第五个业务页面。

| 页面名（均为 `pages/{name}/index`） | 当前主要功能 | 后端需求/边界 |
| --- | --- | --- |
| forum | 六类 feed、公告、搜索、点赞/收藏/分享、分页 | 可见帖子、个性化互动；公告目前固定字符串 |
| forum-detail | 正文、图集预览、作者、两层评论回复、点赞/收藏/删除自己的评论 | 匿名安全投影、真实计数、权限 |
| forum-publish | 正文、0–9 图、四个发布分类、匿名开关 | 服务端重新校验、上传、幂等提交；没有云草稿或帖子编辑 |
| market | 六类入口、双列商品、排序/过滤/分页、售出遮罩 | 商品查询、稳定排序；推荐不是商品分类 |
| market-detail | 轮播、价格/成色/卖家、收藏、我想要 | 商品状态、同一 ID、关联会话 |
| market-publish | 新发/`editId` 编辑、图片、价格/分类/成色/议价、本机新建草稿 | 归属、版本控制；编辑不能改变 ID、卖家、创建时间 |
| my-items | 在售/已售/已下架、编辑、下架/上架/售出 | 已售终态、状态计数；小程序没有删除商品入口 |
| contact | 私信/通知、未读、全部已读、按用户合并会话 | 实际接收者通知、会话水位、跨设备同步 |
| contact-chat | 文字消息、商品关联卡、发送、已读、定位消息 | 持久化后确认、去重、历史补取 |
| market-chat | 旧商品级聊天，仍可直接进入 | 真实接入时必须转同一会话服务，不能留下权限旁路 |
| chat-settings | 置顶/免打扰/屏蔽、历史搜索、本人清空、举报 | 每人独立偏好、清空水位；见第 5 节语义缺口 |
| profile | 本人资料和统计、帖子/收藏/闲置入口 | 真实本人 ID、聚合统计 |
| my-posts | 本人帖子（含匿名）、查看/删除 | owner 查询，不能靠公开主页反查匿名 |
| my-collections | 帖子/商品收藏、取消收藏 | 引用稳定 ID，不复制商品/帖子快照 |
| profile-edit | 头像、昵称、简介、专业、班级、学号、年份 | **学号当前仅本机保存**；其他资料均未经平台核实 |
| user-home | 公开资料、非匿名帖子、在售商品、私信 | 排除学号、匿名归属和管理字段 |
| settings | 通知偏好、关于、模拟退出 | 不等于微信订阅消息；退出不删除内容 |
| login | 模拟登录/访客 | 需替换为真正 `wx.login` + 服务端换身份 |
| search | 帖子/闲置切换、确认后搜索、清空/取消/返回、本机历史 | 没有搜索发现或大家在搜；不新增热门词接口 |
| report | 帖子/商品/用户举报、原因/说明/截图 | 当前 local-only，不提交管理员，无进度页 |

公共组件 `page-header`、`back-header`、`forum-post-card`、`forum-comment`、`market-product-card`、`publish-sheet` 与自定义 TabBar 只负责展示/触发事件。业务仓库在 `features/forum|market|contact|profile|moderation|search`；目前没有独立真实 HTTP service 层。未发现调用 `wx.request`、`wx.uploadFile`、`wx.login`、`wx.connectSocket` 的业务接入。

## 3. 小程序模型和必须保留的规则

| 领域 | 实际字段/限制 | 证据 |
| --- | --- | --- |
| 帖子 | category=`campus/lost-found/study/help`；正文 1–2000，0–9 图；真实作者在原始 MockPost，公开 ForumPostView 单独投影 | `features/forum/types.ts:1`、`publish.ts:4`、`feed.ts:40` |
| 匿名 | 公开无 authorId、真实头像/昵称；本人匿名评论及 replyToName 也使用帖子匿名号；公开主页排除匿名帖子 | `forum/comments.ts:59`、`profile/content.ts:21` |
| 评论 | 正文 1–1000；parentId 指一级，replyToId 可指二级；最多两层；删一级连同回复；计数含两层；根倒序、回复正序 | `forum/comments.ts`、`forum/repository.ts:53` |
| 商品 | 分类=`digital/books/living/fashion/other`；price 为整数分 1–9,999,999；condition=100/99/95/90/80；标题 1–60、描述 1–1000、1–9 图 | `market/types.ts:1`、`market/publish.ts:3` |
| 商品状态 | available→off-shelf/sold；off-shelf→available；sold 不可编辑/重上架；编辑下架商品不自动上架 | `market/repository.ts:61`、`my-items.ts` |
| 商品可见性 | 公开列表排除下架；默认推荐排除售出，分类/搜索可保留售出；本人收藏和历史详情保留售出/本人下架记录；我想要禁用 | `market/catalog.ts:10`、`profile/content.ts` |
| 资料 | nickname≤20、bio≤40、major≤60、className≤40、studentId≤32；year 空或四位年份；school 已从编辑表单移除 | `profile/repository.ts:1`、`pages/profile-edit` |
| 搜索 | q≤60、显式确认才检索；历史按(q,type)去重、最多8条、仅本机；当前搜索每域最多100条，无真正分页 | `features/search/history.ts:1`、`pages/search/index.ts:44` |
| 消息 | 文字 1–1000、productId 可选；当前 status 只有 sent（本地写入成功）；按对方用户合并 | `contact/storage.ts:2`、`contact/repository.ts:25` |
| 举报 | target=post/product/user；五个原因；说明 1–200、证据 0–3 图；status=local-only | `moderation/repository.ts:3`、`pages/report/index.ts:37` |

收藏/点赞当前是本机 toggle + Mock 基础数；真实服务必须改成幂等目标状态及数据库唯一约束。各种 `local-student`、`mock-*`、`local-post-*`、`post-001` 校验不是合法后端身份规则，后续接入需在仓储适配层一起移除，不能只替换 fetch。

学号边界见 `pages/profile-edit/index.wxml:11`、`pages/login/index.wxml:5`、`pages/settings/index.ts:18`。本轮设计默认 **不建立学号字段、不将 studentId 放进 PATCH /me**；是否改变承诺另行决定。专业、班级、年份同步后仍自填，不能推断在校身份。

## 4. PC 后台页面与真实能力边界

依据：`src/config/admin-navigation.ts:14`、`src/router/index.ts:19`；全部页面复用固定侧栏/顶栏及主区独立滚动。五个 Store 为 `admin-auth`、`content-moderation`、`admin-operations`、`admin-audit`、`admin-settings`。

| 页面 | 已有本地能力 | 服务端要实现的约束 |
| --- | --- | --- |
| login | 非空凭据即可演示登录、记住账号、回跳、退出 | 真实密码验证、账号状态、会话吊销、角色授权 |
| dashboard | 欢迎/统计/趋势/待办/动态/范围切换 | 当前数字与趋势硬编码，不是真实运行数据 |
| posts | 搜索/分类/状态/时间/分页、详情抽屉/图片/评论、隐藏/恢复/删除/批量状态 | 真实作者、统计、删除关联、并发版本 |
| comments | 搜索/层级/状态/forum-market筛选、上下文、单/批隐藏恢复删除 | 统一两级关系、商品关联键、有效可见性 |
| products | 搜索/状态/分类/分页、详情、单/批下架恢复删除 | 已售不可重上架；审核状态独立 |
| reports | 五类/优先级/状态/时间、证据/原文/历史、必填原因/二次确认 | 处理一次、事务同步、快照保护 |
| users | 资料、内容/举报/历史、三通道限制、7天封禁/永久封禁/解除 | 所有用户写入口执行；永久状态不能被其他命令降级 |
| announcements | 草稿/发布/撤回/重发/删除、编辑预览、分类/范围 | 明确编辑后的公开状态，保留发布时间历史 |
| notifications | 草稿、受众计算/计数、模拟发送一次/记录 | 真实站内收件记录；不调用微信订阅消息 |
| logs | 三类结果、过滤/分页/只读详情/CSV | 服务端独立持久化；日志失败不改变业务结果 |
| settings | 审核开关/强度/词条、本地保存/重置，其他分段只读 | 只存配置，不据此宣称自动审核或服务器控制 |

后台列表通常 `{items,total,page,pageSize}`，10/20 条，运营/日志另支持50。操作原因≤200，公告标题≤60/正文≤1200，通知标题≤50/正文≤500（见对应 Vue 输入）。Store 多数仅校验非空，长度限制必须在服务端重新执行。

## 5. 双端差异与后端统一决策

这些是源码差异或模拟缺口，**不是本轮已修复事项**；最终字段/状态以 [03](03-database-design.md)、[04](04-api-contract.md) 为准。

| 差异/风险 | 实际证据 | 设计处理 |
| --- | --- | --- |
| 用户键混乱 | 小程序 local/mock ID；后台 `content-moderation.ts:143` 以 `2022****312` 等掩码学号作为 FK | 服务端 BIGINT，API 字符串 ID；显示字段不当主键；不导入种子身份 |
| 匿名不可追溯 | 后台样例 authorId='匿名'，排除用户建档 | 数据库保留真实 author_id，公开 DTO 去身份，ADMIN 专用详情授权查看 |
| 金额单位不同 | 小程序 price=整数分；后台 price=浮点展示元 | API 统一 priceFen；两端未来适配转换，禁止 double 元入库 |
| 分类/成色不同 | 小程序编码/数字；后台中文自由文本，另有文创周边而无服饰 | 统一编码；文创样例不迁移；不可把文创静默映射为服饰 |
| 状态混杂 | 小程序 available/off-shelf/sold；后台 on_sale/off_shelf/sold，reviewState=normal/pending | saleStatus 与 reviewState 分轴；下架来源/治理保持不可被本人绕过 |
| 商品评论没关联键 | 后台 market 评论 postId=null，仅 linkedTitle；小程序无商品评论 UI | comment 预留明确 targetType/targetId；首版用户仅创建帖子评论，不能凭标题建关联 |
| 根评论隐藏计数 | 后台只看本条 status，隐藏根下的回复仍算可见 | 公共有效可见性同时看帖子、根、本条；管理端可查原状态；计数统一 |
| 举报下架无效仍处理 | `content-moderation.ts:570` 忽略商品变更结果 | 报告、目标和历史同事务；已售保持终态，治理隐藏单独标识；效果显式返回 |
| 举报提醒/私信隐藏仅视觉 | warn_user 只写历史，hide_message 没有真实消息实体 | 站内提醒需真实收件记录；消息隐藏需保留序号的占位；无效果不得声称送达/隐藏成功 |
| 限制与封禁混用 | 到期解封清空全部通道限制 | 独立保存人工通道限制与封禁层；到期仅移除临时封禁，永久不可解除 |
| 公告编辑清历史 | UI 保存走 saveAnnouncementDraft，清 publishedAt/withdrawnAt；重复发布未严禁 | 保存草稿会退出公开，但保留首次/最近发布时间；状态命令用版本控制 |
| 通知 all 歧义 | all 既为受众又为列表“不筛选” | 请求省略表示不过滤；audience=all 精确表示全员；发送快照才是实际站内接收数量 |
| 私信双路径 | 旧 market-chat 直接写商品存储，绕过 blocked/clear | 所有入口接同一服务；旧路由兼容不得独立写数据 |
| 屏蔽/免打扰不完整 | blocked 只禁止本人发；muted/sound 只保存偏好 | 文档明确现状；首版保持最小语义，双向拒收另待决定，不冒充已实现 |
| 商品与会话绑定过强 | 旧关联商品售出会令整段聊天 UI 禁发 | 会话按用户对；仅带 productId 的新咨询校验商品在售；普通文字不因历史商品售出永久禁用 |
| 通知来源不真实 | 4条 seed + 本人评论活动 | 按真实接收者写通知；不把本人动作当他人未读；不迁移 seed |
| 读失败回 Mock | market readProducts 吞异常回样例 | 真实模式返回错误并可重试，禁止网络失败伪装演示数据 |
| 媒体只在设备 | wxfile/临时路径、举报图未持久化、旧图不回收 | 统一 imageId、归属/引用/访问校验；不接受本机路径作为远端图片 |

## 6. 首版边界

| 模块 | 完整首版必须 | 可简化 | 后续/不在本次目标 |
| --- | --- | --- | --- |
| 用户/管理员 | 微信 code 换身份、本人资料、管理员真实登录/角色、写入权限 | 单一 ADMIN 角色、无权限配置 UI | 学号同步须另批；无学校认证、SSO或学生认证 |
| 论坛/互动/搜索 | 发布/详情/两级回复/本人删除、赞藏幂等、两域搜索、匿名安全 | MySQL 参数化包含匹配，推荐按最新稳定排序 | 搜索引擎/个性化推荐；不加话题、热门词或云历史 |
| 交易 | 发布/编辑/收藏、状态终态、管理治理、列表详情联动 | 无待审审批流程，首发 normal | 不加订单、支付、购物车、担保 |
| 私信 | MySQL持久消息、会话/历史/已读/未读、WSS实时事件、HTTP补取 | 单实例、只文字+商品关联；设置偏好可后接 | 图片私信、已送达回执、撤回、群聊均无当前前端依据 |
| 治理 | 举报人工审核、关联状态、用户三通道限制/封禁、不可逆保护 | 用户举报入口先覆盖现有三类；无真实样例冒充来件 | 评论/指定消息举报入口待前端设计；自动AI审核不做 |
| 公告/通知 | 公告生命周期、真实站内互动通知、管理通知接收快照 | 同步、小规模站内投递、预览不等于送达 | 微信订阅消息、外部推送不在本期 |
| 后台/审计/设置 | 对应真实 CRUD/状态命令、只读审计、基础统计 | 配置存取但不自动执行；DAU简单日表 | 停留时长无采集返回 unavailable；不造服务器状态 |
| 图片 | 统一接口、文件校验、引用、访问控制 | 开发期本地目录适配器 | 具体生产存储厂商后续决定 |

## 7. 测试证据边界与后续集成成本

已阅读小程序本地规则测试、管理端9份单测和6份E2E、Java健康测试；历史记录包括小程序125项、后台55项单测/15项E2E、Java2项健康测试。本轮未重跑，历史通过数量不能证明真实权限、事务、并发、跨设备或微信生产接入。

后续不仅要编写服务端，还要逐个仓储替换固定ID校验、Mock fallback、价格/分类映射、会话合并和登录开关；这些属于后续获准的联调改动，本轮不修改前端。Mock内容和虚构联系人默认不迁移到真实库。

审查覆盖由小程序、后台、历史文档/服务端三条只读检查汇总；关键实体定义和测试约束已交叉核对。五天目标及需要决定的问题见 [05-development-roadmap.md](05-development-roadmap.md)。

# Day 4：私信、举报审核、用户限制、公告与通知

实施日期：2026-10-25。依据01～08设计、既有24张业务表、04契约及本轮直接实施要求。复用Java 21、Spring Boot 4.1.1、MySQL 8.4、JWT和Spring Security，没有新增依赖、修改历史迁移或接入前端。

## 1. 交付范围和接口

本轮新增 **36个HTTP端点及1个WebSocket端点**。加上Day 1～3的44个HTTP端点，累计 **80个HTTP端点**；其中79个在`/api/v1`下，另一个为Actuator健康端点。完整规划仍为110个HTTP端点，不能把剩余规划或图片尚未开放的avatar用途计作完成。

| 模块 | 本轮HTTP数量 | 契约 |
| --- | ---: | --- |
| 私信会话与消息 | 10 | C01～C10 |
| 未读与接收通知 | 4 | C11、N01～N03 |
| 举报及用户限制 | 7 | R01、AR01～AR03、AU06～AU08 |
| 公告与系统通知任务 | 15 | AA01～AA07、AN01～AN06、N04～N05 |

以下路径均以`/api/v1`开头，数量按HTTP Method＋路径计算：

| Method | 路径 | 行为 |
| --- | --- | --- |
| GET / POST | `/conversations` | 本人会话列表；创建或获取双方唯一会话并关联可选商品 |
| GET | `/conversations/{conversationId}` | 成员会话详情及关联商品 |
| GET / POST | `/conversations/{conversationId}/messages` | 历史、补取、关键词、定位；发送持久文本消息 |
| PUT | `/conversations/{conversationId}/read` | 本人的只增已读水位 |
| POST | `/me/conversations/read` | 按捕获的已提交水位全部已读 |
| GET / PATCH | `/conversations/{conversationId}/settings` | 本人的置顶、免打扰与版本 |
| POST | `/conversations/{conversationId}/clear` | 本人清空水位，不删对方历史 |
| GET | `/me/unread` | 原始分类未读与按偏好计算的角标 |
| GET | `/notifications` | 仅本人通知，分类、未读筛选和游标分页 |
| PUT | `/notifications/{notificationId}/read` | 仅接收者标已读，重复操作幂等 |
| POST | `/me/notifications/read` | 按确切通知ID集合全部已读 |
| POST | `/reports` | 帖子、评论、商品、用户举报及0～3张截图 |
| GET | `/admin/reports` | 管理员举报队列、关键词/类型/优先级/状态/时间/分页及统计 |
| GET | `/admin/reports/{reportId}` | 案件快照、证据、处理结果和业务历史 |
| POST | `/admin/reports/{reportId}/resolution` | 一次性审核处置、版本和确认 |
| POST | `/admin/users/{userId}/restrictions` | 独立publish/comment/message通道限制与解除 |
| POST | `/admin/users/{userId}/ban` | 7天临时或永久封禁，吊销现有会话 |
| POST | `/admin/users/{userId}/release` | 解除人工限制及临时层，不能解除永久封禁 |
| GET | `/announcements`、`/announcements/{announcementId}` | 仅已发布公告，按范围查询和查看正文 |
| GET / POST | `/admin/announcements` | 公告管理列表；创建幂等草稿 |
| GET / PATCH / DELETE | `/admin/announcements/{announcementId}` | 详情、编辑保存草稿、按版本软删除 |
| POST | `/admin/announcements/{announcementId}/publish`、`…/withdraw` | 确认发布/重发、撤回 |
| GET / POST | `/admin/notifications` | 任务列表；创建幂等通知草稿 |
| GET / PATCH | `/admin/notifications/{notificationId}` | 任务详情、实际收件/已读汇总、草稿编辑 |
| POST | `/admin/notifications/audience-preview` | 计算真实受众数量与指纹，不代表发送 |
| POST | `/admin/notifications/{notificationId}/send` | 重新核对受众、确认发送并保存实际接收记录 |

`GET /api/v1/ws`执行WebSocket升级，不重复计入HTTP业务端点。首版业务发送仍走HTTP，没有第二套WS发送语义或可由客户端选择的订阅频道。

### 与原设计的最小差异

- 最新要求明确包含评论举报，因此在原R01增加`targetType=comment`；没有改小程序入口，没有新增普通用户的私信举报接口。既有message案件仍支持管理员按指定消息处置，不能浏览整段私信。
- 商品举报处置额外返回`effects.saleStatus`，明确已售商品仍为sold，不能把治理隐藏伪造成重新在售。
- 本轮管理员操作记录为同一业务事务中的`xl_moderation_action`；全局独立安全审计及只读查询/CSV仍留Day 5。业务处置历史不是安全审计日志的替代实现，也不导入前端内存日志。

## 2. 私信和实时推送

- 用户对按数值排序并使用既有唯一索引；会话和恰好两条成员记录同事务创建。并发双向打开不重复建会话，商品只作关联，不为每件商品另建会话。
- 带商品打开时校验商品属于对方且在售，关联立即持久化但不制造消息或未读。发送时带商品再次校验；普通文字不因历史关联商品售出而永久禁发。
- 消息正文1～1000字，服务端确定sender、时间、序号及`persistenceStatus=sent`。发送者＋clientMessageId唯一，初次规范请求摘要含会话、文字、商品；同内容重试返回原记录，变更内容409。
- 锁会话分配递增序号，消息、last指针和商品关联同事务。序号/水位用BigInteger承载并以规范十进制字符串输出，拒绝JSON数字水位、负值、非规范格式和uint64溢出。
- 历史支持before/after/around及关键词；仅成员且只查本人clear水位之后。隐藏消息保留ID、序号、时间和占位，不返回sender、clientMessageId、文字或商品。
- 已读与清空只增不减且不能超过已提交最高序号；全部已读先捕获水位，之后新消息仍未读。清空只影响本人，不物理删除对方记录。
- 未读来自消息与通知表，raw和badge分开；免打扰/声音不是微信送达状态。已存在的屏蔽关系按原“本人禁止新发”语义执行，屏蔽修改接口仍未开发。

WebSocket使用现有JWT与数据库会话鉴权，握手仅接受Authorization Bearer，拒绝查询串票据和管理员身份。连接仅接收本人有权接收的服务端事件；客户端文本订阅/发业务消息被拒绝，输入大小限制1024字节。每秒复核JWT、会话及封禁，注销、到期或封禁关闭现有连接。

事件为`message.created/conversation.updated/read.updated/unread.updated/notification.created`，包含独立eventId及UTC时间；消息携带字符串sequence。DTO在业务事务内准备，提交后推送；网络失败与回调异常不会回滚已经提交的消息，HTTP历史始终可用。WebSocket可能乱序/重复/丢失，客户端后续仍须按ID/序号去重和HTTP补取。

单连接发送使用Spring的`ConcurrentWebSocketSessionDecorator`串行化并限制发送缓存；不引入STOMP、消息队列或分布式架构。协议实现参考[Spring WebSocket官方说明](https://docs.spring.io/spring-framework/reference/web/websocket/server.html)。

## 3. 举报、证据与用户限制

- 举报原因固定五种，说明1～200字，服务端推导真实目标作者及优先级；不信任客户端提交的操作者、所有者、优先级或治理状态。
- 同请求幂等、同举报人同目标只有一个pending案件；重复已处理案件不能再处置。管理员动作按目标类型白名单校验，要求原因、expectedVersion和confirmed。
- 报告、内容状态、业务历史、必要站内反馈同事务提交；中途失败全部回滚。原目标已删除不能伪造状态操作成功，但仍可根据保全快照审阅并驳回。
- 隐藏帖子/根评论后公共读取与计数遵守既有父可见性。商品治理隐藏与出售态分离，sold保持终态；重复已隐藏目标允许结案，但实际changedCount为0。
- 截图上传扩展既有I01的report用途；绑定核对本人、用途、到期、状态、数量和重复。服务端另保存原帖/商品图片的report_snapshot引用，不因原作者删除内容而误删证据。
- report_evidence/report_snapshot仅有效ADMIN会话查看；短期grant绑定后台会话，输出图片时复核管理员状态、会话和案件引用。普通用户、截图上传者在绑定后、访客均不能凭imageId读取案件证据。
- publish/comment/message是独立人工通道；对应业务写接口真实执行限制。临时/永久封禁禁止认证业务并吊销现有会话，解除临时层不恢复旧票据。
- 临时到期在认证只读事务前规范化，并只移除临时层、保留人工通道，追加一次系统到期历史。管理员操作过期目标先核对客户端版本再规范同一行，避免到期更新反复回滚导致永远版本冲突。
- 永久封禁不能被临时封禁、举报限制或解除接口意外恢复。所有处置历史由服务器生成操作者与operationId，不接受普通用户修改。

## 4. 公告与站内通知

- 公告draft/published/withdrawn状态与历史时间分开；编辑保存退出公开但保留首次/最近发布时间。公开范围forum含all＋forum，market同理，all仅全范围公告。
- 公告首次发布与当前未删除用户的接收记录同事务；同一公告重复发布或撤回后重发不重复打扰。删除软删并使通知目标安全失效，无学校官方身份或标志。
- 系统通知任务支持all/active/recent/sellers；sellers包括任一未删商品卖家，active/recent按有效限制和封禁计算。预览指纹由范围及真实去重ID集合计算，发送时重新核对。
- 发送人数必须等于实际receipt行数，sent只表示站内持久化；零受众、超过5000、指纹变化或重复发送明确拒绝，不能伪称微信推送或用户已收到。
- 评论/回复、帖子/评论点赞、帖子/商品收藏产生真实接收通知，排除本人动作，回复接收者去重。自然事件唯一，重复动作不叠加；取消互动失活原通知，重新互动不反复轰炸同一接收者。
- 通知按当前匿名及父可见性投影，目标隐藏/删除后返回安全占位，不暴露匿名作者或原正文。系统任务的完整正文来自task，公告正文来自announcement。
- 标已读限定接收者并锁有效通知，取消收藏并发失活不会导致空指针。全部已读先物化确切ID集合，不使用max(id)范围误标晚提交通知。
- 互动准备阶段按相关用户ID排序锁定作者和接收者，再锁内容；覆盖通知外键的隐式锁，避免双向点赞/评论/商品收藏死锁。

## 5. 验证记录

最终完整回归在 **2026-10-25 15:54:02（Asia/Shanghai）** 完成，**BUILD SUCCESS**。JDK21编译、生产JAR打包、129项单元/基础HTTP＋155项集成＝**284项全部通过，0失败、0错误、0跳过**。保留Day 1～3的217项，新增67项，未以单模块通过替代完整回归。

执行命令：

```powershell
pwsh -NoProfile -File scripts/verify-backend.ps1 -Database
```

| 实际检查 | 结果及证据边界 |
| --- | --- |
| 基线完整复跑 | 217项通过，2026-10-25 12:51:49；没有重复开发已有认证能力 |
| 最终完整verify | 284项，34个测试类，0失败/错误/跳过；编译及Boot JAR重打包成功，耗时1分19秒 |
| 原有认证与核心业务 | Day 1～3测试均保留；路由范围断言更新到本轮79个`/api/v1`端点，并继续禁止Day 5全面管理入口 |
| 私信HTTP/事务/并发 | 8＋6项；双方隔离、重复会话/消息、12次并发发送、跨会话请求键、读水位捕获后新消息、回滚零推送、商品双端事件 |
| 举报与限制 | 14＋7项；评论举报、案件证据、同案只处理一次、双向举报/处置、永久保护、过期层、时间边界、状态/历史/提示整体回滚；包含root补充的已有message案件隐藏与序号占位 |
| 收件通知 | 6功能＋3双向并发＋1标读竞态；身份隔离、匿名、自然事件去重、偏好、游标、失效目标、安全标读 |
| 公告与通知任务 | 3单元＋6真实TCP HTTP＋7事务；首次发布、编辑/撤回、全部15端点、指纹变更、并发发送、实际5000上限及5001拒绝、写入后异常回滚 |
| 图片 | 原7项保留，新增案件后台会话授权1项，共8项；注销后旧grant不能取字节 |
| 真实TCP HTTP/WS | Day4RealtimeIT 5项，最终端口 **61156**；实际握手、消息/通知投递、非成员拒绝、第三人零事件、断线后HTTP补取、注销/到期/管理员封禁关闭连接 |
| 其他真实TCP回归 | Day3HttpIT 4项，端口61041；运营HTTP6项，端口61134。测试进程退出后随机端口关闭 |
| MySQL及迁移 | 独立复核MySQL **8.4.13**、24张业务表＋Flyway历史；既有V1～V5均成功且无新迁移执行 |
| 夹具保留边界 | 最终逐表实际COUNT：24张业务表均0行，测试事务回滚或只清理自己随机身份；444个已有文件中仅16个本轮范围内文件修改，无缺失、无前端/范围外修改，新增36个文件均属本轮范围 |
| 文档与配置检查 | 文档链接、Git差异空白检查通过；480个非忽略文件扫描，已知实际敏感配置值匹配为0。没有执行Git提交/推送/重置/清理 |
| Redis现场探测 | 127.0.0.1:16379和6379均连接失败，未安装/启动替代服务、未宣称Redis联调成功 |

实际网络测试的身份由测试源码调用既有受信AuthService或随机测试管理员准备；HTTP、JWT、Spring Security、事务、MySQL和WS使用真实实现。原Day 2认证测试中的微信/限流替身仍保持原边界，不能据此宣称真实微信登录或Redis脚本通过，没有产品级测试登录开关或默认管理员。

完整日志：`.local/backend-day4/final-verify.stdout.log`、`final-verify.stderr.log`、`final-verify-command.log`。按本次Maven实际执行清单归档34类XML至`.local/backend-day4/final-reports/`，`summary.json`独立复核284项；不把构建目录中的旧单项失败探针混入本次结果。独立SQL及Redis结果为`final-database-check.tsv`、`redis-probe.json`，模块红绿报告也保留在同一忽略目录。

本轮已保存RED→GREEN证据，包括：端点缺失404、并发消息回调耗尽连接、商品事件错误会话投影、序号数字强制转换、空会话排序、互报和相反案件处置死锁、时间排他上界、过期目标治理版本冲突、双向互动通知FK死锁、标读与取消收藏NPE。

首轮完整回归发现两个测试集成问题：旧Day 3夹具未清新增通知外键；多个Spring测试上下文各预建10条连接导致本地MySQL连接耗尽。已补精确夹具清理及仅测试配置minimumIdle=0/maximumPoolSize=6，未调大服务器连接上限或改变产品连接池配置。首轮失败日志完整保留，不能混同最终结果。

## 6. 新增与修改文件

相对于`D:\CODE\XinKeLink`，本轮新增生产代码22个，根目录`apps/server/src/main/java/cn/xinkelink/`：

- `messaging/`：`MessagingController.java`、`MessagingModels.java`、`MessagingService.java`、`MessagingStore.java`。
- `moderation/`：`BanExpiryService.java`、`ModerationController.java`、`ModerationModels.java`、`ModerationRepository.java`、`ReportModerationService.java`、`UserModerationService.java`。
- `notification/`：`NotificationController.java`、`NotificationMapper.java`、`NotificationService.java`。
- `operations/`：`AnnouncementController.java`、`AnnouncementService.java`、`NotificationTaskController.java`、`NotificationTaskService.java`、`OperationsModels.java`、`OperationsRepository.java`、`OperationsSupport.java`。
- `realtime/`：`RealtimeEvents.java`、`WebSocketConfiguration.java`。

修改已有生产代码9个：

- `auth/AuthJwtAuthenticator.java`、`config/AuthConfiguration.java`：认证前到期规范化，原登录/刷新逻辑与限流保持。
- `common/api/ErrorCode.java`、`content/ContentSupport.java`：新增业务错误、当前有效管理员校验。
- `config/SecurityConfiguration.java`：精确增加公开公告GET白名单，管理员/用户隔离保持。
- `forum/ForumService.java`、`market/MarketService.java`：事务内接收通知与相关用户排序锁，保留原数据权限和状态机。
- `media/MediaService.java`、`media/persistence/MediaMapper.java`：report用途及案件后台会话授权，post/product公开规则保持。

新增测试11个，根目录`apps/server/src/test/java/cn/xinkelink/`：

- `messaging/`：`MessagingHttpIT.java`、`MessagingConcurrencyIT.java`。
- `moderation/`：`ModerationHttpIT.java`、`ModerationTransactionsIT.java`。
- `notification/`：`NotificationServiceIT.java`、`NotificationConcurrencyIT.java`、`NotificationReadRaceIT.java`。
- `operations/`：`OperationsPolicyTest.java`、`OperationsHttpIT.java`、`OperationsTransactionsIT.java`。
- `realtime/Day4RealtimeIT.java`。

已有测试更新：`content/Day3HttpIT.java`（保留原行为断言并更新本阶段路由范围、清通知夹具）、`forum/ForumConcurrencyIT.java`（清通知夹具）、`media/MediaHttpIT.java`（新增案件授权与支持范围回归）；新增`apps/server/src/test/resources/application.properties`仅约束测试池。

文档：03/04同步最小契约差异，02/05记录实施状态，本文件及`docs/superpowers/plans/2026-10-25-backend-day4.md`。本地日志、报告、夹具范围及文件摘要仅在忽略目录`.local/backend-day4/`，不属于产品资源。

## 7. 环境边界、未完成项与Day 5依赖

- 本轮真实验证使用本地MySQL、Tomcat HTTP和`ws://127.0.0.1`，不是生产HTTPS/WSS、微信开发者工具或真机验收。两前端仍是原有Mock，没有修改页面或仓储。
- 真实微信code2Session及Redis7联调仍未完成。核心业务不调用Redis；登录/刷新在限流服务不可用时仍按Day 2返回503，不增加登录旁路。
- WebSocket为单实例、尽力实时事件；未做跨实例分发、5000在线连接或大规模投递性能验收。5000/5001边界通过真实数据库事务写入并回滚验证，不等于5000人在线负载通过。
- 治理后已经呈现在客户端的旧消息不新增实时撤回事件；后续HTTP补取/刷新返回hidden占位。
- 普通用户私信举报、图片私信、群聊、消息撤回及delivered回执不在当前契约，不实现。头像上传/资料修改、通知偏好及屏蔽修改端点仍待后续，当前会话设置和角标读取既有表值/默认值。
- 生产图片厂商、WebP/HEIC、过期文件物理清理仍待后续；本轮未新增清理任务或删除既有业务数据。
- Day 5剩余管理查询/帖子评论商品治理、完整用户详情与内容、Dashboard、独立安全审计与设置，以及前端真实适配均未开始。当前业务历史不能替代正式安全审计；后续审计必须保留失败隔离与脱敏规则。
- 既有18080旧服务未替换；真实测试随机端口随测试退出关闭，没有部署或另外常驻新业务服务。需按既有环境配置启动新版本时使用空闲端口，例如：

```powershell
pwsh -NoProfile -File scripts/start-backend-dev.ps1 -Build -Port 18081 -Profiles db,dev,auth
```

启动必须注入环境密钥与既有数据库配置，`.env.example`不自动加载。Redis未恢复前不要把健康UP解释成微信登录或刷新可用。

## 8. 对应Java学习重点

- 事务提交回调、幂等唯一键、行锁固定顺序与外键隐式锁；理解为什么业务已提交后不能通过推送异常回滚。
- 消息sequence与每个成员的read/clear水位，BigInteger/字符串避免JavaScript数字精度丢失；HTTP事实来源与尽力实时事件各自职责。
- Security身份、领域权限与真实会话吊销；独立人工限制和临时/永久封禁层不能混为一个状态。
- 业务处置历史、通知收件记录和安全审计是不同事实；参数化SQL、受众快照、事务回滚测试与实际TCP HTTP/WS测试的边界。

完成Day 4范围后停止，不自动进入Day 5、前端联调或部署。

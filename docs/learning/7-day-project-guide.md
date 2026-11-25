# 信科 Link：七天项目学习导航

适合即将学完黑马点评、已有 Java／Spring Boot／MySQL／Redis 基础的你。每天约三小时：基础回忆20分钟、源码70分钟、实验50分钟、复述40分钟。目标是建立整体理解、跟通关键源码、验证预测并解释取舍；七天不承诺精通全部中间件。

| 黑马点评已学内容 | 信科 Link 增加的实现 | 魔改版常见技术 | 本周深度 |
| --- | --- | --- | --- |
| Redis旁路缓存、批量查询 | 公共计数、Caffeine→Redis→MySQL | 多层缓存与缓存最佳实践 | 跟通读写与故障路径 |
| 更新数据库后删缓存 | 共享令牌、Lua校验、事务快照绕过 | 版本栅栏 | 预测旧回填并验证拒绝 |
| 应用主动删缓存 | binlog映射、确认失效与重放 | Canal CDC | 看懂补偿和位点边界 |
| Redis Stream消费与重试 | 事务Outbox、RocketMQ、Inbox | 可靠消息 | 解释重复、乱序与恢复 |
| Redis计数、排行榜 | Kafka精确PV／UV、七自然日榜单 | 行为统计与热榜 | 跟通接收、聚合和投影 |
| Lua扣库存、异步建单 | 本项目没有秒杀与订单链路 | 异步秒杀、订单可靠性 | 仅作原理对照 |

## 使用与证据边界

核对日期为2026-11-17。已对照[README](D:/CODE/XinKeLink/README.md)、[交接](D:/CODE/XinKeLink/docs/project-handover.md)、[业务流程](D:/CODE/XinKeLink/docs/business-flows.md)、[身份规则](D:/CODE/XinKeLink/IDENTITY-RULES.md)及最新源码。旧文档的25表、后端未启动、Kafka待实现已被后续记录覆盖；当前迁移到V14／41表。早期“MGET”描述对应的当前实现是批量READ Lua，读取令牌及计数。历史PID、开关和端口状态只代表记录时刻，不据此判断今天服务是否运行。

源码链接均为本机绝对路径，以方法名定位，避免沿用旧行号。实验先写预测再看断言／真实结果；只读推演同样要记录“预测、观察、解释”。本轮仅编写导航，未执行任何实验。以后运行定向单测须已有JDK21、Maven依赖及项目既有显式ByteBuddy agent配置；可用IDE选择指定测试方法，不要求安装组件或每天全量回归。

凡涉及写库、消息或故障，只允许事先核实的命名隔离环境。联合记录使用 `xinkelink_cache_joint_20261117_r2_test`、独立Redis16390和独立Topic／组／Canal destination；验收服务现已按记录停止，不能直接照抄旧端口操作。未恢复隔离条件时使用当天轻量替代，不改开发数据，不清库／清Redis，不停共享服务，不直接执行旧验收启动脚本。

## Day1：看懂项目与一条完整业务

1. **当天目标**：能把“浏览帖子→点赞／评论→页面刷新”从请求讲到数据库，并区分公开投影与私人状态。
2. **核心问题**：页面在哪里发请求？谁判定访客／本人／他人？哪些数据来自公共事实、哪些依赖当前会话？
3. **源码阅读顺序**：[pages.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/backend/pages.ts) 的 `forum-detail.reload/onLikeTap/onSubmitComment` 找请求与setData → [ForumController](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/ForumController.java) 找HTTP映射 → [ForumService](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/ForumService.java) 的 `detail/likePost/createComment/postViews` 看鉴权与事务 → [ForumMapper](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/ForumMapper.java) 看实际SQL，再沿响应返回页面。只认xl_user、xl_post、xl_like、xl_comment；评论root_id串起回复，点赞按目标类型和ID关联。
4. **小实验**：前提：无需服务；操作：遮住[详情测试](D:/CODE/XinKeLink/apps/miniprogram/tests/forum-detail.test.cjs)前两个用例的断言，预测匿名详情字段和首页／详情点赞同步，再读断言；预期匿名作者私有ID不出现、两页互动一致；解释fixture只验证本地数据和页面逻辑，live另经Java与MySQL，不能相互冒充。
5. **三个自测**：fixture等于微信登录吗？〔本地夹具≠真实会话〕；作者ID相同就能编辑吗？〔getViewerRole还需登录，异常按guest，服务端再鉴权〕；退出应删所有草稿吗？〔私人数据隔离读取，按身份恢复〕。

收尾画一张请求往返图：左侧写页面事件，中间写接口和业务方法，右侧写表，再用返回箭头标出响应字段进入页面的位置。把评论目标、根评论和回复对象分开标注，不要把评论点赞误认为帖子点赞。今天只解释一个正常成功场景和一个访客被拒场景；分页、图片签名、管理功能暂不逐行展开。

## Day2：Redis缓存与最佳实践

1. **当天目标**：能解释公共计数如何批量命中、回源和退化，以及为什么缓存命中仍有数据库工作。
2. **核心问题**：缓存什么才不会串号？一页多帖如何避免逐帖计数？Redis坏了和MySQL坏了应分别怎样处理？
3. **源码阅读顺序**：[ForumService.postViews](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/ForumService.java) 区分计数与用户态 → [ForumMapper.publicPostMetrics/postViewerStates](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/ForumMapper.java) 对比批量SQL → [PostMetricsCache.get](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/metrics/PostMetricsCache.java) 看批量miss回源 → [application-auth.yml](D:/CODE/XinKeLink/apps/server/src/main/resources/application-auth.yml) 核对默认20秒加0—10秒抖动。只共享likeCount/commentCount；liked、权限、匿名作者投影和图片授权实时生成。
4. **小实验**：前提：已有单测环境，无真实Redis；操作：在[PostMetricsCacheTest](D:/CODE/XinKeLink/apps/server/src/test/java/cn/xinkelink/forum/metrics/PostMetricsCacheTest.java)定向运行或逐断言推演 `firstMissLoadsOnceAndNextHitDoesNotLoad` 与 `batchLoadsOnlyMissingUniqueIdsAndPreservesHits`；预期两次同帖仅一次loader，混合请求只批量加载去重后的miss；解释减少的是公共计数SQL，权限／身份SQL仍在。Redis读失败回源且不盲填，数据库异常继续报错。
5. **三个自测**：TTL抖动解决什么？〔分散雪崩风险〕；零计数等于不存在吗？〔存在性先查，未实现通用空值／布隆防穿透〕；热点击穿有互斥重建吗？〔当前无读路径互斥锁／逻辑过期重建，仅学原理〕。

Memcached只作概念比较：也是易失键值缓存，本项目未采用；此处需要Redis Lua与Pub/Sub能力，不安排安装或虚构入口。

对照黑马点评时，先区分穿透的无效目标、击穿的热点失效、雪崩的集中失效，再标注本项目已有和缺少的措施。批量读取解决多帖查询成本，不能代替防击穿；TTL是陈旧兜底，也不保证故障期间固定时限内收敛。不要把计数缓存允许回源推广到认证和限流，它们保持失败时拒绝。

## Day3：Caffeine、多层缓存与版本栅栏

1. **当天目标**：能画出旧读晚回填的时序，说明共享栅栏拦住了什么、仍留下什么一致性窗口。
2. **核心问题**：L1为何不能无限续命？只广播删除为何不够？旧数据库快照为何会污染新缓存？
3. **源码阅读顺序**：[PostMetricsCache.get/fillRedis/safeSnapshot/invalidateAfterCommit](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/metrics/PostMetricsCache.java) 串起L1→L2→MySQL → [PostMetricsFence](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/metrics/PostMetricsFence.java) 看READ／FILL／INVALIDATE Lua原子校验 → [PostMetricsInvalidation](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/metrics/PostMetricsInvalidation.java) 看广播与重订阅 → [application-db.yml](D:/CODE/XinKeLink/apps/server/src/main/resources/application-db.yml) 核对RC。L1最多10000项、写后2秒过期，且不能超出来源截止时间；Pub/Sub不补发，漏广播仍有剩余L1窗口。
4. **小实验**：前提：单测环境；操作：在[PostMetricsL1Test](D:/CODE/XinKeLink/apps/server/src/test/java/cn/xinkelink/forum/metrics/PostMetricsL1Test.java)运行或推演 `alreadyDispatchedFillIsRejectedAfterSharedInvalidation`，先预测旧响应、L1、L2三处结果；预期旧请求可返回已读快照，但A令牌晚于B失效的回填被拒，不能进两层，后续读新值；解释本地锁不足以保护跨实例。真实Lua证据另见交接“公共计数分布式版本栅栏”。
5. **三个自测**：为何afterCommit失效？〔回滚不删〕；事务内都能缓存吗？〔写事务绕过，仅确认RC只读可用，RR／未知绕过〕；这是强一致吗？〔提交到推进、推进失败和漏广播仍有窗口〕。

复述时把数据库旧快照和缓存旧代次分开：即使先拿到新令牌，旧事务也可能读取过去的数据，所以仅校验令牌还不够。项目检查真实连接隔离级别，保持调用者事务并绕过不安全缓存。再比较容量淘汰、时间过期、广播失效三个原因：它们都能让本机重读，但只有共享令牌校验能拒绝另一实例已经发出的旧回填。

## Day4：Canal缓存一致性补偿

1. **当天目标**：能说明“binlog→Canal→事件映射→共享栅栏→失效”的补偿链与ACK边界。
2. **核心问题**：主动失效之外为何还需要CDC？重复或断线后怎么恢复？历史DDL为什么能让消费者停止前进？
3. **源码阅读顺序**：[PostMetricsEventMapper.map](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/metrics/cdc/PostMetricsEventMapper.java) 看xl_post／xl_like／xl_comment依赖 → [PostMetricsCdcConsumer.processBatch/consume](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/metrics/cdc/PostMetricsCdcConsumer.java) 看分批处理和重连 → [PostMetricsCache.invalidateConfirmed](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/metrics/PostMetricsCache.java) 确认共享失效 → [CdcCheckpoint](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/metrics/cdc/CdcCheckpoint.java) 区分本地审计与服务端确认游标。主动失效缩短正常窗口，CDC补偿绕过应用的改库及漏失效；它不替代权限判断。
4. **小实验**：前提：无需Canal服务；操作：在[Consumer测试](D:/CODE/XinKeLink/apps/server/src/test/java/cn/xinkelink/forum/metrics/cdc/PostMetricsCdcConsumerTest.java)比较 `confirmationAndDurableProcessedAuditPrecedeAck` 与 `partialChunkFailureCannotConfirmWholeBatch`，先预测第二块失败能否ACK；预期失败整批不确认，重放可重复推进令牌；解释必须先共享确认及PROCESSED审计，再发送ACK，ACK_SENT不证明服务端已刷盘。
5. **三个自测**：checkpoint能决定跳过吗？〔不能，重连从服务端确认游标恢复〕；相关DDL能忽略吗？〔当前映射拒绝并BLOCKED〕；联合r1怎么恢复？〔保留失败位点，另建r2，迁移后业务前取新起点；不等于旧订阅自动恢复〕。

已有退避重连和重复处理能力；位点不可用须恢复日志或经可信水位对账处置，不能跳latest。单主机单用户指定消费者的锁不提供跨主机HA。

结合联合记录保留的首次失败，分别写出“旧订阅被阻塞”和“新隔离订阅验收通过”两句话。后者没有证明旧历史DDL已被兼容处理。订阅迁移、日志保留和不可恢复位点的处置还需要操作者明确水位与对账依据；本周只识别这些边界，不自行重置订阅。

## Day5：RocketMQ可靠互动通知

1. **当天目标**：能解释业务事务→Outbox→发布→RocketMQ→Inbox→通知，以及每段成功分别保证什么。
2. **核心问题**：为什么发送成功不等于通知落库？重复、乱序如何处理？取消以后迟到的创建还能生效吗？
3. **源码阅读顺序**：[NotificationService.reaction/comment/route](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/notification/NotificationService.java) 找既有互动及默认同步／MQ分支 → [InteractionJournal.record/append/claim/finish](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/notification/mq/InteractionJournal.java) 看同事务意图、序号和租约 → [OutboxPublisher.publishBatch](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/notification/mq/OutboxPublisher.java) 看事务外发送与未知结果 → [InboxProcessor.process](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/notification/mq/InboxProcessor.java) 看去重、取消墓碑和原子落库；消费者在代理提交返回后才确认。
4. **小实验**：前提：仅已核实的命名隔离库和既有测试配置；操作：定向跟[NotificationMqIT](D:/CODE/XinKeLink/apps/server/src/test/java/cn/xinkelink/notification/NotificationMqIT.java)的 `mqCreatesOnlyAfterConsumerAndDuplicateDoesNotResetRead`；预期消费前无通知、重复消费只一条且不重置已读；解释SENT仅说明发布阶段。轻量替代：遮住该用例断言推演，再对照[MQ真实记录](D:/CODE/XinKeLink/.local/rocketmq-20261116/live-results.json)，无需启停Broker。
5. **三个自测**：如何防取消后迟到？〔自然键序号＋永久墓碑，再赞不重新通知〕；失败能无限丢给MQ吗？〔退避、PAUSED、失败表及DLQ，修复后按原ID人工重放〕；Stream方案少了什么？〔同样要解决数据库与消息双写、待处理恢复和幂等，换MQ不会自动解决〕。

可靠消息用于既有互动通知，跨实例WebSocket即时推送仍未实现，持久结果可经HTTP补取；不要写成订单系统经验。

把发布重试和消费重试画成两个环：前者耗尽保留暂停意图，后者可进入死信。人工重放复用可信原件，不改事件ID或正文，也不删除旧失败证据。记录中真实死信恢复与适配器注入故障要分别标注。

## Day6：Kafka浏览统计与热榜

1. **当天目标**：能把一次成功展示追到可靠接收、Kafka、事务聚合和Redis榜单，解释PV／UV与重放口径。
2. **核心问题**：一次刷新算新访问吗？提交数据库后未提交消费位点会怎样？七天UV能直接把每天UV相加吗？
3. **源码阅读顺序**：[views.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/statistics/views.ts) 看访问ID及身份绑定 → [ViewReceiptService.receive](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/statistics/ViewReceiptService.java) 看提交后202，首次数据库接收时间定日期 → [ViewProcessor.process](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/statistics/ViewProcessor.java) 看可信原件、eventId去重及PV／访客集合同事务 → [ViewProjection.capture/publish](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/statistics/ViewProjection.java) 看北京时间今天及前6日、跨日DISTINCT和绝对排名。中间可靠发布仍保留原ID重试，聚合提交后才提交offset。
4. **小实验**：前提：只读即可；操作：遮住[ViewReceiptIT](D:/CODE/XinKeLink/apps/server/src/test/java/cn/xinkelink/statistics/ViewReceiptIT.java)的 `receiptRetriesKeepServerTimeAndDifferentVisitsIncreasePvOnly` 断言，预测同ID接收／消费各两次，再用同访客新ID访问；预期receipt去重，最终PV2／UV1；解释业务去重覆盖跨重启，客户端重试沿用原ID。核对相邻跨午夜用例：消费延迟不改原日，窗口UV不逐日相加。
5. **三个自测**：为何不能拿点赞替代？〔浏览与互动不同，按UV降序、数字postId升序，PV展示〕；offset失败会重计吗？〔重放由事务幂等抵挡，不能声称全链路Exactly Once〕；为何两种MQ？〔通知与日志聚合职责分开，也增加部署、监控、保留期和排错成本〕。

UV是统计标识去重，换设备／清标识／登录转换可能重复计人；Redis默认每5秒重建，故障不把空榜或点赞榜伪装成统计成功。

统计触发以公开详情成功展示为准：同页刷新不新增访问，重新进入可新建事件；单纯GET详情和列表预加载不代表已展示。先确认这一业务口径，再讨论消息吞吐。

## Day7：串联、实验与复盘

1. **当天目标**：能独立画出完整链路，拿真实失败和成本证据解释方案，而非只列中间件名称。
2. **核心问题**：点赞、通知、浏览分别以哪里为事实源？哪个失败会阻塞哪条链？怎样证明恢复，怎样识别测量局限？
3. **源码阅读顺序**：先回看[ForumService.likePost/createComment](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/forum/ForumService.java)作为业务起点 → [ViewConsumer.dispatch](D:/CODE/XinKeLink/apps/server/src/main/java/cn/xinkelink/statistics/ViewConsumer.java)核对提交与位点窗口 → [联合验收](D:/CODE/XinKeLink/docs/backend/21-joint-acceptance-20261117.md)串联隔离双JVM观察 → [后续原生补核](D:/CODE/XinKeLink/docs/backend/22-native-menu-fix-20261117.md)辨认仍未完成项；由实现到联合证据再到限制。
4. **小实验**：前提：只读联合[结果](D:/CODE/XinKeLink/.local/joint-acceptance-20261117/live-results.json)；操作：先画“详情读取／浏览上报”与“互动事务／afterCommit／CDC／通知”分支，预测暂停消费时哪里积压，再核对pausedMqRetainsOutbox及恢复检查；预期意图和receipt保留、恢复后Inbox及统计不重复；解释该记录暂关隔离worker后恢复，没有停止共享Broker，不能改述为物理断网。自己标出提交后未offset窗口会重放哪一步。
5. **三个自测**：集成测试等于生产吗？〔单测、隔离集成、原生模拟器、物理设备、生产各有边界〕；免费福袋等于秒杀吗？〔仅补充行锁、唯一约束、防重复执行及恢复，无库存订单支付〕；六项技术怎么归类？〔前五已实现并有隔离证据，第六仅原理，本项目无异步建单／超时关单〕。

复盘时口述Lua＋Stream异步秒杀、幂等建单、超时关单需要的库存、业务唯一键和状态机，不向信科Link新增业务。AI页面仍调用本地replyTo，尚无真实模型交付；Python＋LangGraph＋ChromaDB留作后续专题。

## 用证据解释成本

最后20分钟从交接相应性能节选择一组较差结果复述条件，不选最好数字包装提升：栅栏专项同JVM、访客、20帖各10赞10评论、无图片、轮换模式，冷列表并发1平均MySQL为3.878ms、多层缓存为5.336ms；缓存未保证每次更快。热L1省计数与Redis调用，但生产隔离检查仍可能触发驱动SQL。

MQ专项同JVM反转顺序、每格120样本、批量4／100ms，并发4点赞P95同步29.855ms、MQ32.430ms，MQ落库P95约1.54秒；请求返回和通知可见不可混算。Kafka专项同JVM、单Broker、同帖三账号、两轮反转顺序，并发4开启时额外统计POST平均17.68ms／P95 29.93ms；详情展示不等待它，但请求、事务与锁成本仍存在。数据来自[交接性能记录](D:/CODE/XinKeLink/docs/project-handover.md)，均为本机小样本，不证明生产容量或长期稳定性。

选读版本按POM与验收依赖记录：Java21／Boot4.1.1、Canal1.1.8、RocketMQ5.5.1、Kafka4.2.1；Caffeine由BOM管理，不把网上新版本示例直接套入。无需购买课程，先读当前代码再补官方原理。每天结束用五分钟口述一条链路，并留一个尚不能解释的问题；七天达标以能预测和解释为准。

## 可复制的ChatGPT陪学提示词

> 按这份路线陪我学习Day N，一次只讲一个小节。先确认我是否理解对应黑马点评基础，再按“业务问题→原理→项目源码→预测实验结果→验证→复述”展开。先让我回答再纠正，不一次输出整天讲义。无法访问本地代码时明确让我粘贴相关小段源码，不假装读过仓库。实验复用已有入口，缺隔离环境就读真实记录；等我能解释关键链路再推进下一节。

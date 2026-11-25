# 2026-11-22 内测与福袋下线

小程序按公开浏览模式启动，管理后台已移除内测与福袋入口。服务端已撤除两项功能的接口、准入过滤与开奖任务。Flyway V19 停用旧中奖通知，排除收件列表与未读计数。既有迁移与历史数据可供核对。

旧版小程序仍依赖内测状态接口。若有旧版在用，应先确认后台处于开放阶段，并完成小程序和后台的版本切换，再停止旧版后端的开奖与补投任务，启动新后端执行 V19。

# 2026-11-17 原生操作补核与本人帖删除修复

按用户后续要求自行检测，已修复共用帖子卡片缺少删除入口，并撤除书签菜单/事件。沿用原删除权限、版本及二次确认；退出/换号拦截。原生发现fixture首页删后未刷新，补接现有onShow并复验。小程序完整检查378通过、0失败/跳过；live真实鼠标删除返回200并落库，fixture匿名帖真实删除及列表移除通过。福袋真实复制编码/微信、上下滚动及边界、fixture等待刷新通过；三页完整防穿透、小屏及生命周期仍有未完成项，未冒称全部通过。

本轮未重复后端/数据库/后台整套回归。固定JAR、隔离库及Topic沿用；新证据 `.local/manual-acceptance-20261117-201848/` 保留首次失败。原模式、存储和前端地址已恢复，双JVM、独占Canal/Redis已停止，18081未改变，未提交/推送。详见[操作补核与菜单修复](backend/22-native-menu-fix-20261117.md)。下方历史原文保留。

---

# 2026-11-17 福袋原生操作与多模块联合验收（最新接手记录）

本节覆盖下方历史状态。真实隔离双 JVM 联合后端链路通过，原生手势矩阵仍未完成，不能宣布完整端到端验收通过。详情及人工补验见[本轮联合验收记录](backend/21-joint-acceptance-20261117.md)。证据为 `.local/joint-acceptance-20261117/`；HEAD/分支未变，885 个接手文件的哈希核对和相对接手增量分别见 `preservation.json`、`incremental.patch`，未回滚、提交或推送。

实际原生鼠标点击和拖动证明 fixture 首页圆钮/正文参与/关闭及共享长卡片向下滚至底部；live 仅连接隔离18122，亦取得真实内部滚动位移。发现长内容绘出圆角边界，先补失败回归，再仅增加 `.bag-card{overflow:hidden}`，复测通过。三页坐标批次失败，刷新/复制、全部背景防穿透、上下边界/可滚动背景锁定、小屏安全区、抽屉及生命周期组合仍缺原生实证。没有将 handler 状态检查、离线编译或物理手机/正式微信授权未测记为手势通过。

固定 JAR SHA256 `E88064A480791FCE22FCA47830AB5BCDA3D626F2D0F5BD4DD9B88D9543596227`，18121/18122同库同产物，同时启用缓存、MQ、Kafka、福袋，独占Canal由18121单消费；MySQL13306隔离库 `xinkelink_cache_joint_20261117_r2_test`，独立Redis16390、Topic/组/destination。1个JUnit联合场景110项检查、22项必要补核通过；有真实Outbox/Inbox、Kafka PV3/UV2、热榜、CDC共享栅栏补偿141ms、故障恢复及开奖窗口普通业务证据。首次失败保留，不跳失败位点，不清库/Topic，不扩展跨实例WebSocket。

本轮完整检查：小程序373、后端普通331、真实数据库集成259、后台99及生产构建通过，失败/跳过均0；verify重复执行的331不重复计数。14迁移/41业务表静态契约、29 WXML/33 WXSS离线编译及git diff --check通过。原生live另有7项状态/API检查通过，不能代替手势矩阵；详情刷新上报、搜索热榜和换号队列的完整原生联合操作仍待补验。

18081保持原PID41624、原JAR及原开关，健康UP、公开帖子/当前福袋接口正常；开发库福袋0、浏览receipt0。本轮应用、故障worker、Canal11119、Redis16389/16390均已停止；已有MySQL、共享Redis/Kafka保留。本轮用既有启动器拉起的MQ namesrv28792/broker3244作为基础设施保留。小程序恢复local模式及原存储、会话，移除测试HTTP包装器，origin恢复18081；微信编辑器保留、自动化连接断开。私有凭据/隔离编码截图仅在受ACL保护的private目录。

---

# 2026-11-17 免费定时福袋第一版（当前交接）

本节覆盖下方历史运行状态；旧Kafka/MQ/身份接手原文按字节保留。HEAD仍为`7fc1af479e4e183d0c1d0f2e4bb9b0b6a08fa479`，分支`codex/phase-1-foundations`，未提交、未推送。接手857文件原文与Git状态在`.local/lucky-bag-20261117/before/`、`baseline-files.json`、`git-status-before.txt`；本轮精确文件归属、相对接手增量与保留核对见`task-files.json`、`incremental.patch`、`preservation.json`，没有把既有Kafka/身份/UI修改算作本轮成果。

三个状态流、截止/锁序、等概率选择、开奖恢复、编码保护/本人回显、人工发放、接口与复跑集中在[福袋交接与验收](backend/20-lucky-bag-acceptance.md)。免费、每账号一次、固定定时抽1人/10元；发布后冻结、截止后不可取消。结果/领奖/可靠通知意图同事务，通知独立补投不重抽；pending→issuing→paid，由开始发放的管理员人工核对后确认。没有支付、钱包、自动转账或重抽接口。

V1—V13原文未改；新增V14后为41张精确业务表。隔离库与开发库各自先备份再迁移/增量授权，开发库仅结构与空展示指针，无活动、参与或中奖样例。备份路径/哈希在保护private及state证据中；运行权限entry/result/operation仅INSERT，bag/display/claim仅INSERT、UPDATE，六表无DELETE，原审计、反馈列授权、统计最小权限与binlog保持。

完整回归：小程序372、后端普通331、真实数据库集成259、后台99条及生产构建通过，均无失败或跳过。真实双生产JVM福袋85项检查全true，实际提交前halt71/提交后halt72恢复；结果、编码、历史通知与已读不变。后台无网络拦截的真实Chrome操作流程通过；微信原生29 WXML/33 WXSS编译、fixture关键状态与两期历史路由通过。最新原生live、MQ/Canal/Kafka实情与限制统一见`.local/lucky-bag-20261117/final-verification.json`；首次失败也保留，不拿旧批次结果冒充本轮。

18081经原启动脚本运行验收JAR，SHA256为`3308ED849395B83EEE7E23CB49B738DABD70FFF8AF35F32B8252335575EF73E9`；两健康接口UP，公开帖子正常，当前福袋诚实返回OK/null。福袋扫描/通知补投true（默认每秒、每批16），原通知MQ生成/发布/消费、CDC及Kafka采集/发布/消费/投影全部false；热榜诚实空态。PID与归属快照见`runtime-18081.json`，本轮临时进程清理及保留基础服务见最终证据索引。

没有实际支付。隔离用户会话注入不等于正式微信网络换码，开发者工具不等于物理手机；不保证跨实例设备通知必达、自然人防多号、公开可验证密码学抽奖或生产HA。旧活动报名/人数抢占/秒杀及Lua/Redis Stream报名方案撤下，本版不实现这些业务。


# 2026-11-17 Kafka 公开详情浏览与真实热榜（当前交接）

本节覆盖下方“下一批Kafka尚未实现”等历史状态，旧记录原文保留。HEAD仍为`7fc1af479e4e183d0c1d0f2e4bb9b0b6a08fa479`，分支`codex/phase-1-foundations`；未提交、未推送。接手825份文件快照、Git状态和原差异在`.local/kafka-20261116/{baseline.json,baseline-files.json,before/,before.patch}`。本轮51份文件（32新增、19已有）的精确归属及相对接手增量见`task-files.json`、`incremental.patch`；其它接手文件SHA核对无意外变化，旧三份文档正文按字节保留。

## 已接入的业务和统计口径

小程序成功显示**公开帖子详情**后异步上报，服务端可靠接收入MySQL日志，发布到Kafka，消费者事务聚合，再由Redis投影供搜索页读取Top5。仅改榜单数据、必要文案及生命周期保护，没有新增点赞权重、搜索词/私信/私人主页采集、统计大屏、草稿收藏产品功能或界面设计。

- PV按唯一`eventId`计数。同一页面onShow、reload、图片续期、评论分页和上报重试共用一次访问；重新进入详情产生新事件。列表预加载及详情GET本身不计数。未找到、隐藏、删除、审核非normal及仅作者可见详情拒绝新统计；已确认后才隐藏的历史浏览仍保留事实，榜单实时过滤。
- 登录UV使用现有JWT/会话验证后的用户ID，经统计专用HMAC域派生内部键；访客持服务端随机签发的30天HMAC统计凭证。客户端传userId/事件时间/未知字段拒绝。该凭证不能登录，前端身份唯一标准仍为`getViewerRole()`。清理凭证、跨设备及登录前后转换可能重复计人；UV代表统计标识数，不等于真实自然人数。
- 有效事件时间为**数据库首次可靠接收的UTC毫秒**，客户端不提供时间。离线排队跨午夜后首次接收，按接收日归属；已接收的重试、Kafka积压与恢复保持原时间。应用/数据库需正常校时，消费对超过应用时钟60秒的未来时间持久阻塞，校正后重试，不改写原事件。
- 热榜为**北京时间今日及前6个自然日**，按窗口内`COUNT(DISTINCT visitor_key)`跨日精确去重，UV降序、数值postId升序处理同分，PV仅展示。不是滚动168小时，也不按帖子发布时间或点赞排序；两年前的帖子最近有浏览也可上榜。

live调用`GET /api/v1/posts/view-ranking`，返回`items[{post,pv,uv}]`、`window{startDate,endDate,zone,days}`、`updatedAt`和`available`，不返回任何统计身份。遍历完整排名、每50个ID复用`ForumService.favoriteItems`的公共可见性/匿名/图片授权投影，过滤后继续补齐5条；恢复帖子可立即重新出现。无投影返回诚实空态；Redis连接/解析失败报可重试503，不回退演示数据或旧点赞榜。正常投影默认5秒重算，快照60秒TTL；跨北京时间日期或过期快照立即视为不可用，下一周期重建。

fixture使用同WXML和相同自然日/精确集合/稳定同分规则，浏览样例由postId确定，不依赖列表顺序、帖子创建日或赞数；增量浏览只在App内存保存。既有fixture只有一个`local-student`身份及访客，不伪造A/B演示账号；live A/B及模式切换有独立回归。重试队列所有页面合计最多20条、10分钟过期，指数退避上限60秒；换号、退出及模式变化丢弃旧身份未确认事件，进程退出丢弃未确认工作。服务器已经确认的事件由日志保证，客户端丢失未确认工作不等于已确认数据丢失。

## 接收、消费及每个故障窗口

`POST /api/v1/statistics/anonymous-credential {}`签发统计凭证；`POST /api/v1/statistics/post-views {version:1,eventId,postId,anonymousCredential?}`最多2KiB，登录请求不带匿名凭证。读取有界请求体后检查协议、公开状态、验证身份和固定时间；现有鉴权/内测/封禁机制保留，新增仅统计用途的有限频率保护。远端地址只作限流，绝不作UV。事务提交后才返回202；统计失败不会影响详情GET。

| 故障位置 | 持久状态与恢复 |
| --- | --- |
| 接收提交前失败 | 不响应accepted；客户端保留同ID重试。提交成功但HTTP结果未知时，重试读回原ID/身份/帖子/时间，不再插入。并发ID冲突拒绝。 |
| 已接收、Kafka不可用或发布进程退出 | receipt保留READY/LEASED。短事务`SKIP LOCKED`有界领取，网络发送在事务外；租约到期回收。随机token和截止时间防旧工作回写，整批后部追加发送预算。默认16条/500ms，基础租约60秒；失败/未知指数退避最高60秒，不丢弃已接收工作。 |
| Kafka确认后、receipt回写前退出 | 同原ID/字节重发。生产者`acks=all`、幂等开启，跨生产者/重启仍由业务eventId去重，不能用Kafka producer幂等替代。SENT超过60秒仍无event时，发布器每30秒有界自动对账重排，覆盖Broker保留期内消息缺失。 |
| 消费事务失败 | 锁可信receipt、核对原字节摘要，唯一event、日PV、帖/日/访客集合在同一真实MySQL事务；回滚一起回滚。失败独立持久记topic/分区/offset、摘要、有限原因及尝试，不归档任意不可信正文。 |
| MySQL已提交、offset未提交退出 | 从原提交位点重放，唯一event及摘要抑制重复。消费者逐条在事务真实返回后提交该分区下一offset，失败seek回该记录，不能越过同分区失败记录。 |
| 非法协议、毒消息或位点失效 | 持久OPEN失败并阻塞，修复处理器后原位重试、成功RESOLVED；`auto.offset.reset=none`。新组仅在无已提交位点/无OPEN失败时初始化logStart；更早OPEN失败会挡住高offset。Broker已删除失败offset时保留OFFSET_UNAVAILABLE，不偷偷跳到最新，需要受保护的对账处置。 |
| MySQL成功、Redis失败或全部统计key丢失 | Kafka无需等待Redis；默认每5秒从持久结果全量重算。DB两阶段generation行锁＋Redis Lua CAS原子替换绝对JSON，旧任务无法覆盖新结果，即使generation Redis key也丢失。下一周期自动恢复，不用ZINCRBY、不改Kafka offset、不FLUSH。 |

Kafka key=postId、3分区，每实例一个专用consumer线程，最多3个实例并行拥有分区；每次poll最多16条。同帖同日更新由MySQL计数行锁串行，同eventId由receipt锁串行，唯一集合保护不同访客并发。除`wakeup`外所有Consumer调用在本线程；停止先停接收工作、唤醒并join，再关闭资源；发布与投影各自单线程，Broker失败不阻止投影退窗。没有声明Kafka事务自动覆盖MySQL/Redis的全链路Exactly Once。

Kafka本地保留**7天或每分区512MiB，以先到者为准**。receipt/event/精确访客/失败记录的未来归档最小保留保证为30天；**本版没有自动清理，实际持续保留**，所有仍有可信原件的旧事件都可重放，已验证45天积压只回到原日，不进入当前热榜。未完成接收、OPEN失败不能按短期保留期删除；未来归档须成套核对原件/摘要/去重及重放范围。源原件缺失或摘要不符持久拒绝。代价是接收日志和精确集合随事件增长；首版Redis全量排名查询/重建内存为O(窗口活跃帖子)，没有用近似UV冒充精确结果。

## 迁移、授权及独立环境

新增`V13__post_view_statistics.sql`，V1—V12未改。当前为**13迁移、35业务表**；六张新增表为`xl_view_receipt`、`xl_view_event`、`xl_view_daily`、`xl_view_visitor_day`、`xl_view_projection`、`xl_view_failure`。主键、窗口/调度/租约/失败索引、状态/租约/大小/JSON/正ID CHECK均由静态和真实契约校验。无额外FK阻碍去重保留或帖子软删。精确表清单仍在`scripts/database-table-contract.json`，没有移除表数校验。

迁移前ACL保护备份→隔离库迁移/增量授权→真实验收→开发库另取新备份后用本轮JAR的Flyway迁移和正式脚本授权。运行账号全库既有SELECT；event/visitor_day仅INSERT，另四表INSERT/UPDATE，六表均无DELETE；audit仅插入、feedback列权限和Flyway历史只读保留。开发库只应用结构，不灌样例浏览。备份/hash/迁移和授权日志见state/、private/及logs/；未连接3306、清库、替换JWT、全局REVOKE或使用skip-log-bin。

采用Boot4.1.1现有BOM管理的原生`kafka-clients 4.2.1`与同版Broker、Java21；无Spring Kafka Starter或项目整体升降级。依据[Kafka Java支持](https://kafka.apache.org/42/operations/java-version/)、[Linux/Windows说明](https://kafka.apache.org/42/operations/hardware-and-os/)及[4.2.1 KRaft文档](https://github.com/apache/kafka/blob/4.2.1/docs/operations/kraft.md)，并已真实Windows客户端/两个JVM验证。Docker engine不可用，复用WSL Ubuntu；本轮独立Linux JRE21和Kafka包均经官方摘要核验，应用仍用原JDK21。Broker使用Ubuntu EXT4独立data、loopback 39092/39093、heap384MiB/direct128MiB、3分区RF1/minISR1/每条本地flush；单Broker验收不代表生产HA。WSL relay转发还承载Redis，不得按其PID查杀；仅本轮Broker加preferIPv4Stack使Windows实际可达。

保留Canal protobuf4.35.1同FQN重生成、Fastjson2 2.0.63、RocketMQ5.5.1和Boot管理Netty4.2.17。依赖树`dependency-tree.txt`及`state/artifact-verification.json`核对本轮352个生产class和资源与打包产物一致，测试故障类/探针未进入交付JAR。

## 测试与故障验收

| 验证 | 实际结果与证据（均在.local/kafka-20261116） |
| --- | --- |
| 小程序完整npm run check | 355通过，0失败/跳过；mini-check.log。相对接手新增20项，包含同页一次、同ID重试、A/B/模式迟到保护、全局20条上限及fixture跨日精确排序。 |
| 后端完整mvn test | 329通过，0失败/错误/跳过；logs/mvn-test-final.log。 |
| 真实隔离库mvn -Pdb-integration verify | 普通329＋集成231通过，0失败/错误/跳过；logs/db-verify-final.log。 |
| 静态契约/差异 | 13迁移、35精确业务表、最小权限及binlog恢复契约通过；logs/schema-contract-final.log；git diff --check通过。 |
| 显式真实Kafka | 78个记录检查全true；双生产JAR服务、独立生产者重复、真实Broker停启、ACK后退出、提交后未offset退出、真实双消费者分配/rebalance、毒消息/非法协议/位点缺口及安全恢复；logs/kafka-live-final.log、live-results.json。 |
| 精确UV/Redis | 10项接收聚合IT及5项投影IT：并发、事务回滚、跨日、45天积压、无新事件退窗、错误连接、全命名空间丢失/旧任务/12任务并发、隐藏/删除/审核/恢复与匿名补齐。 |
| 当前JAR既有MQ/Canal | RocketMQ原生/完整通知专项及Canal原专项全部通过；existing-regression-results.json及独立run日志。保留原断言和全部故障阶段，只增加test-only路径/端口参数，副本MQ29876/30911及Canal11115；未停共享旧MQ/CDC。 |
| 微信原生工具 | 已安装工具真实编译27 WXML/31 WXSS，原生页面4/4；mini-wechat-compile.log、mini-native-results.json。发现并修复同名局部变量的原生编译差异；同访客新访问PV+1/UV不变，同页刷新不重计。最新补拍截图超时如实保留，已有原生搜索截图；未用H5代替。 |

所有RED/首次失败日志保留。独立源码审查发现offset自动跳过保留期失败记录及NEW指标found-rows误计，均补RED→GREEN修复。真实专项最初的端口退出等待、rebalance未等两成员都有分配是验收器边界问题，修复等待条件后重跑，没有删断言或把失败说成成功。数据库首次完整回归只暴露104→107旧路由契约，已新增三个精确路由断言。

真实Kafka最终78项检查全true，160次202（含一次重复重试）对应159个确认唯一事件、159个持久唯一事件和159 PV。Broker恢复15646.12ms；8条实际SENT积压恢复在worker ready后1354.54ms，含JVM启动7979.05ms；Redis重建629.88ms且前后排序一致；双消费者/退出后分区重分配共32条事件无重不漏。专项使用100ms发布poll、300ms投影、1秒发送超时、5秒租约；默认生产投影为5秒，不能直接照搬专项延迟。

真实Broker为本轮进程正常TERM/Start；Runtime.halt是真实测试服务JVM退出；UNKNOWN为真实ACK后的适配器注入；处理失败为受控处理器异常，Redis故障为专用错误连接，删除统计keys为真实Redis命令。隔离毒消息Topic的deleteRecords模拟保留期位点缺口，不称为物理断网、断电或磁盘损坏。临时服务及回归副本均已清理，毒消息失败证据及Topic保留用于审计。

## 性能、请求和退化

最终同一JVM/当前JAR、同一帖子/三个验证账号/无图片、认证与既有缓存保留，worker在采集关闭时仍清积压。先关闭/开启各20次预热（80个HTTP请求），两轮顺序off→on/on→off，每模式×并发1/4×每轮60个访问，共480对详情GET＋统计POST；另20访客准备、30笔单事件延迟和20次热榜预热。原始样本/计数在performance-results.json和其唯一run目录，最初只计PreparedStatement的样本另存，未拿来证明总SQL成本。

| 并发/每模式样本 | 详情关闭平均/P95 ms | 详情开启平均/P95 ms | 开启时额外统计POST平均/P95 ms |
| --- | ---: | ---: | ---: |
| 1 / 120 | 7.17/9.90 | 7.19/9.48 | 11.96/16.63 |
| 4 / 120 | 8.85/14.38 | 7.28/9.30 | 17.68/29.93 |

这不是页面渲染延迟：小程序不等待统计响应。关闭采集时每次也有首次统计POST，样本返回预期503；实际客户端还可能有10分钟内有界重试，不能声称关闭为零请求。测量组240个202、240个预期关闭503、0未预期失败/HTTP未知；含预热为260/260。真实专项另验证非法/隐藏请求拒绝及ACK后生产者UNKNOWN注入，不能把注入写成物理断网。短样本中详情均值随顺序/缓存/调度变化，不作为性能提升；上报并发耗时增加，额外事务和锁竞争没有消失。

| 每120对详情＋统计请求 | 关闭HTTP SQL/Redis调用 | 开启HTTP SQL/Redis调用 | 开启另有消费SQL |
| --- | ---: | ---: | ---: |
| 并发1 | 2040 / 242 | 2760 / 242 | 840 |
| 并发4 | 2040 / 240 | 2760 / 241 | 840 |

另外按批领取/回写及周期投影产生SQL（完整有限分类增量见原始记录）。探针统计Statement与PreparedStatement的execute调用，包含失败调用；不含驱动内部元数据和外部校验器查询，batch按JDBC执行调用计，不冒充逐行数量。Redis是Template调用数，Lua内命令未逐个计。240笔同JVM JDBC提交确认配对延迟平均/P95为470.75/1108.35ms（批量访问含积压）；30笔单独事件的接收确认→观察到聚合为89.51/132.25ms，→Top5可见为305.69/336.38ms，含25ms轮询观测成本。

热榜并发1、120次，平均/P95 4.37/5.95ms；应用HTTP SQL 960、Redis Template调用121（含120次排名GET及可见投影原缓存调用），背景投影另计。

热榜并发4、120次，平均/P95 6.56/9.78ms；应用HTTP SQL 960、Redis Template调用124（含120次排名GET及可见投影原缓存调用），背景投影另计。

性能组确认310＝持久唯一310＝PV 310，精确UV 21（20访客凭证＋1验证用户）。本机单Broker、共享本地MySQL/Redis、每条本地flush及探针均有成本；无生产容量或长期稳定性外推。

## 当前运行及开关

18081当前PID **38668**，使用本轮验收JAR经既有start-backend-dev.ps1启动（未重新构建）。开发库V13/35表；两个健康接口UP、未带Bearer读取帖子OK/3条；当前开发库可见样例不含匿名作者，匿名作者保护已在隔离真实专项验证；通知无Bearer为401。浏览采集/发布/消费/投影、通知MQ生成/发布/消费、CDC **全部false**。热榜接口OK，available=false、空榜；关闭时凭证接口503，未伪造统计成功。**实际启用统计的仅命名隔离库，开发库没有上线Kafka采集。**

验收JAR为`D:/CODE/XinKeLink/apps/server/target/kafka/xinkelink-server-0.1.0-SNAPSHOT.jar`，运行副本`D:/CODE/XinKeLink/.local/backend-day1/run/b945884ba3444ed5ac144854bf381bc8/server.jar`，SHA-256均为`98ea9a0d037ac108eb5a719ba1036ec45590dadc5e1b136a91763c598567c184`；本轮Kafka、性能和既有专项核对同一hash。runtime-18081.json和logs/start-18081.log记录显式启动环境与健康证据。新旧文件、服务归属和临时端口清理见final-verification.json；PID只是快照，启停脚本均先查归属。MySQL13306、Redis16379、原MQ/Canal及本轮Kafka基础服务保留，临时应用和独立回归MQ/Canal副本已停。

`XINKELINK_VIEW_ENABLED`只控制新接收；publisher/consumer/projection分别由`XINKELINK_VIEW_PUBLISHER_ENABLED`、`...CONSUMER_ENABLED`、`...PROJECTION_ENABLED`控制，默认继承总开关。关闭不删历史或积压，可关接收而保留三worker继续处理。投影关闭后已有快照最多保留60秒及当前北京日期，之后诚实空态；恢复投影自动重建。客户端关闭采集时仍有首次统计请求/有界重试的成本，不能把服务端开关解释成客户端零请求。

## 浏览统计学习与排错

先看本节“统计口径”和故障表，再按源码路径顺序阅读即可，不需要先安装或学习新组件：`features/statistics/views.ts`（一次访问与身份绑定）→`ViewReceiptController/Service`（确认接收）→`ViewJournal/Publisher/KafkaTransport`（可靠投递）→`ViewConsumer/Processor/Failures`（事务及offset）→`ViewProjection/RankingService`（精确窗口与公开投影）。验证最直接的证据是`live-results.json`的acceptedUnique/persistentUnique/ownedPostPv及`performance-results.json`的PV/UV核对。

从仓库根复跑，凭据只从原保护文件导入，数据库/消息/性能专项必须串行：

```powershell
& .local/kafka-20261116/kafka-server.ps1 -Action Status # Start/Stop/Restart同入口，只验证/操作本轮归属
. .local/kafka-20261116/test-environment.ps1 # 原JDK21/JWT/13306命名隔离库/Redis16379，所有worker默认false
# 进入apps/server，用原显式agent执行mvn test或mvn -Pdb-integration verify；后者生成验收JAR。
& .local/kafka-20261116/final-acceptance.ps1 # 当前固定JAR：普通、显式Kafka、性能、临时独立MQ/Canal全部串行
& .local/kafka-20261116/verify-artifact.ps1
& scripts/manage-view-statistics.ps1 -Action Status -Database xinkelink_cache_20261114_5646631c_test
& scripts/manage-view-statistics.ps1 -Action Replay -Database xinkelink_cache_20261114_5646631c_test -EventId '<原UUID>'
```

正式Maven参数为`-B -Dxinkelink.build.directory=target/kafka '-DargLine=-javaagent:D:/develop/apache-maven-3.9.4/mvn_repo/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar'`；无测试删除、跳过安全断言或动态agent替代。显式专项类为`ViewKafkaLiveVerification`、`ViewKafkaPerformanceVerification`。运维Replay只重排可信SENT原件，核对摘要，不覆盖活动租约、不改ID/时间/去重或offset，操作审计在`.local/view-statistics-operations/operations.jsonl`；OPEN未知协议/失效位点先看持久原因及原件，不能批量清失败记录假装恢复。

未完成边界：真实微信设备/live微信登录授权、生产多Broker HA/ACL/TLS、跨主机和长时间容量、物理断电断网；这些不计为通过。首版没有自动归档清理和大规模分页投影，留存与全量精确集合成本已明确，不把默认关闭写成开发库上线。

<!-- kafka-20261116-end -->

# 2026-11-16 RocketMQ 互动通知（当前交接，已实现及验收）

本节为当前状态；下方 Canal、缓存和业务修复记录保留原文，其“本轮”、旧 PID 和旧数量均为历史快照。HEAD 仍为 `7fc1af479e4e183d0c1d0f2e4bb9b0b6a08fa479`，分支 `codex/phase-1-foundations`。没有提交、推送或改动无关页面。既有 B01—B11、缓存、Canal 及匿名/权限修改全部保留。接手160份文件原文、HEAD/状态/差异在 `.local/rocketmq-20261116/before/`、`baseline-files.json`、`before.patch`；本轮精确文件归属、增量和验收索引见 `task-files.json`、`incremental.patch`、`final-verification.json`。

## 文件归属与接入范围

本轮新增 `notification/mq/` 十类（协议、配置、Journal、发布、原生传输、Inbox、失败处置、回调和生命周期），新增十三份测试/验收/探针源码、V12、数据库表名清单及 `manage-notification-outbox.ps1`。已有 `NotificationService.java` 接入路由与安全意图应用；已有 POM、application-auth、DatabaseMigrationIT、schema-contract、initialize-backend-dev 和三份入口/交接文档仅作本轮必要增量。原 `NotificationMapper`、`ForumService`、`RealtimeEvents`、三个同步通知 IT 及实时测试均未修改，原同步模式即时断言保留。其余接手快照 SHA 核对无意外变化；旧文档正文按字节保留。完整35个文件（26新增、9已有）以清单为准。

接入既有**帖子赞、收藏、评论赞、根评论和回复**；收藏/评论赞共用入口，也受同一开关控制，没有新增收藏或书签功能。公告、系统任务、治理通知和私信仍走原路径；公共计数缓存及 CDC 不参与通知正文、收件人或授权推导。

## 两条生成路径及切换

`XINKELINK_NOTIFICATION_MQ_ENABLED=false` 默认同步：在原业务事务内记录自然互动序号并立即落通知；开启后同一真实 MySQL/DataSource 事务只提交业务和 Outbox，消费者最终落通知。没有先同步再发送、afterCommit代替Outbox，或发送失败回退同步。Broker 停机不阻止业务提交；Outbox INSERT 约束失败会随点赞一起回滚。HTTP、鉴权、偏好仅影响角标、标读幂等、用户/游标隔离沿用原契约。

发布器/消费者开关分别为 `XINKELINK_NOTIFICATION_MQ_PUBLISHER_ENABLED` / `...CONSUMER_ENABLED`，默认继承 MQ 生成开关，允许关闭新事件生成时继续清积压。完全关闭不会删除积压；恢复同一配置后继续处理。切回同步时仍推进相同序号/取消栅栏，旧积压不能覆盖新的同步结果。升级时先将所有写实例升级到本轮代码，再统一启用 MQ；不支持混跑缺少栅栏的旧生产者。Topic/消费组/数据源不是自动切换机制，变更前须核对 READY/LEASED/PAUSED、**SENT但无Inbox**及失败记录，保留原组处理未完成消息，不能仅以 SENT 判断消费完成。

## 协议、顺序与取消防迟到

v1事件含稳定 UUID `eventId`、`version`、`kind`、`naturalKey`、`sequence`、目标/帖子/操作者关联 ID、业务事务中确定的去重收件人、取消标记和意图时间；不含正文、用户身份投影或图片授权 URL。Broker message ID 仅审计，不能作业务幂等键。自然键保留原格式：`post_like:目标:操作者`、`post_favorite:...`、`comment_like:...`、`comment:评论ID`。

`xl_notification_stream` 按自然键 INSERT/行锁分配递增序号，锁保持到业务事务提交/回滚，且持久保存永久取消墓碑；不使用墙钟或全局自增 ID 推断提交顺序。同步路径也写同一栅栏。消费者按原用户 ID 升序锁用户，再锁 stream，再改通知/Inbox；原标读通知行锁不变。取消先消费会推进 applied sequence；业务侧已提交取消墓碑也能挡住尚未消费的旧创建；再点赞始终保持“不重新通知”。历史通知仍由收件人+自然键唯一约束保护，不重置 read_at。

Inbox 通过独立 Spring 代理 `REQUIRES_NEW` 方法校验协议、原 Outbox 字节摘要和业务 ID，在**同一事务**应用通知/失活并插入完成 Inbox。回滚时二者一起回滚；方法真实提交后 listener 才返回消费成功。重复 Inbox 校验摘要后直接抑制；旧序号记 STALE 完成记录。不会把“处理中”当成功，也不按消费时当前点赞关系重建收件人。消费后投影仍实时查匿名、隐藏、软删、根评论可用性，生成安全占位。

## 发布、重试、失败与补偿

Outbox 状态为 READY/LEASED/SENT/PAUSED，状态+到期时间、状态+租约截止、状态+创建时间及自然键序号索引保留。短事务有界 `FOR UPDATE SKIP LOCKED` 领取/回收，默认16条/500ms；每次随机 lease token，默认基础租约60秒，并为后部串行消息追加前序发送预算。网络发送在事务和行锁外；回写同时要求状态、token及未过期截止匹配，旧持有者不能覆盖新租约。只认原生 `SEND_OK`，Broker 使用 SYNC_FLUSH；刷盘非成功与未知结果保留原字节/ID重试。发送成功后宕机、领取后宕机和未知结果均已真实复验。

生产默认超时3秒、最多12次、指数退避1至60秒；耗尽或人工暂停保留 PAUSED。隔离故障验收为4条/100ms、基础租约5秒、超时1秒、24次及0.5至5秒退避，原生消费最多重试2次；生产消费默认5次。初次过短的12次加速窗口在真实Broker路由恢复时耗尽，确实保留PAUSED，已通过补偿脚本恢复；原日志和 `state/paused-recovery-evidence.json` 保留，不以同步路径掩盖。

消费失败返回 RECONSUME_LATER；`xl_notification_failure` 用消息摘要保留有界原始消息和有限原因、尝试数/状态，失败事务不写成功 Inbox。到达上限的原生重试实际进入 `%DLQ%xl_notifications_20261116_6ef5452b_test_v1`；修复处理器后，按原事件ID重放Outbox，成功后失败记录变RESOLVED，死信原件仍保留审计。未知/不可信协议不能修改原字节后沿用旧ID伪装成功，应先修复消费者兼容性或按原档案处置。

补偿入口为受本机受保护数据库凭据控制的 `scripts/manage-notification-outbox.ps1`，只允许既有13306开发/命名隔离库；Status 查询状态、SENT无Inbox及失败数量，Replay 只重置 PAUSED/SENT 的调度，不改事件ID/内容，Pause 不覆盖活动租约。操作审计在 `.local/notification-operations/operations.jsonl`。没有新增管理HTTP入口。未完成记录不删除；本轮没有自动清理。已完成Outbox/Inbox至少保留Broker保留期及重放窗口（本测试为30天），归档需成套核对；stream墓碑和历史通知自然去重键不得随MQ短期保留期删除，超窗重放需先恢复可信原档案。

## 环境、依赖、迁移及授权

官方 RocketMQ **5.5.1** Broker+NameServer 与同版原生 Java remoting client，使用既有 JDK21.0.12.1、Boot4.1.1；无需Proxy或Starter。选择依据为[官方发行](https://rocketmq.apache.org/download/)、[该版本依赖/Java目标](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.1/pom.xml)及本轮真实JVM验证，不将最低Java目标当成全组合认证。Docker engine 不可用、WSL已存在，因此选Windows原生进程；安装包经官方SHA-512核验，原包/官方摘要、SHA-256在 downloads/、state/verified-archive.json。NameServer19876、Broker20911、fast20909、HA20912均只监听127.0.0.1；运行目录独立，NameServer堆128MiB、Broker384MiB。原HA地址不可配置，server-only SPI适配器仅限制绑定地址，官方JAR不改，源码/类及哈希保留；不是生产HA/ACL部署验收。

真实依赖冲突已修复：Canal传递 Fastjson2 2.0.31 缺少RocketMQ编码所需Charset重载，编码RED稳定复现 NoSuchMethodError；仅直接对齐2.0.63，原生连接、打包链路及本轮真实Canal回归均通过。保留 protobuf-java/protoc4.35.1 和原Canal同FQN重生成修复、Boot管理Netty4.2.17，未降级/升级整个项目。依赖树 before/final 与327个打包class对运行测试class逐项哈希一致证据已保存；测试故障开关/探针不在交付JAR。

新增 **V12__notification_delivery.sql** 四表，V1—V11原文未动。当前开发库与隔离库都为12迁移、29业务表；精确表名清单与数量/约束/权限校验保留，未简单移除“25”检查。先备份两库，再迁移隔离库、完成真实验收；开发库另取新备份后使用验收JAR内Flyway及原迁移账号应用V12，再用正式脚本增量逐表授权。备份SQL仅在ACL保护的private/，哈希/执行日志见state/backup-*、development-backup-applied.json、logs/development-flyway.log及development-grants.log。未清库、改Flyway历史、触碰3306、替换JWT或调整CDC账号。

正式 `initialize-backend-dev.ps1 -StartExisting` 已移除 skip-log-bin，改用原binlog目录及ROW/FULL、sync_binlog=1、server_id=16111601和30天保留，原目录/进程/监听归属检查保留；现有13306实测配置仍一致。旧全局REVOKE改为增量GRANT，保留应用/其它隔离库和CDC权限；audit仅插入、feedback列权限和Flyway历史只读规则不放宽。本轮验证了运行中的正式恢复入口；未额外停机重启共享MySQL。

## 回归与真实故障验收

| 项目 | 实际结果 / 本轮证据 |
| --- | --- |
| 小程序完整 npm run check | 335通过，0失败/跳过；logs/miniprogram-check.log，未替代微信工具编译 |
| 后端完整 mvn test | 308通过，0失败/错误/跳过；logs/mvn-test-final.log |
| 真实隔离库 mvn -Pdb-integration verify | 普通308＋集成214全部通过；logs/db-verify-final.log，含原MySQL/Redis/身份及实时回归 |
| 数据库静态契约 / diff | 12迁移、29表、精确授权/binlog契约通过；logs/schema-contract.log；git diff --check通过 |
| 原生MQ专项 | 显式3项＋性能复验1项通过，0跳过；logs/real-mq-final.log、performance-final.log；live-results.json共82个记录检查全部true |
| 新增数据库专项 | 16项，真实已提交事务；回滚、Outbox真实唯一约束失败、提交阶段失败回传、乱序/墓碑/切换/历史读态、并发claim/consume和lease fencing |
| 当前JAR真实Canal回归 | 显式原PostMetricsCdcLiveVerification通过；logs/canal-current-artifact.log、canal-current-artifact-results.json；原latest结果恢复、唯一raw run保留 |
| 独立审查 | 生命周期迟到启动/传输复活、整批租约预算、Broker/消费恢复计时分离均有RED/GREEN；复核无阻断项，未改其它接手代码 |

真实MQ使用18082/18083独立**打包服务JVM**：A真实HTTP互动，B唯一通知可读；验证收件人/访客鉴权、消费实例在线WS、断线HTTP、匿名回复、隐藏/删除前置于消费、偏好/游标及标读原回归。实际Runtime.halt在领取后未发、SEND_OK后未回写、消费事务提交后未ACK三个阶段退出JVM，重启安全补发/重放。真实正常Broker/NameServer Stop/Start、消费者停机形成SENT积压再恢复、20笔事件双publisher/consumer JVM并行、原生取消先到/旧创建后到/再点赞，以及真实重试→DLQ→修复→运维脚本重放均通过。超时未知结果是在真实SEND_OK之后的受控适配器注入；毒消息演练是处理器适配器强制失败，Broker重试及DLQ为真实。未称为物理断网、断电或真实数据损坏。并发测试主要用Latch/屏障；MQ用真实提交及有界等待，不修改旧同步测试为sleep。

## 性能、延迟及成本

最终同一JVM/构建/账号/等结构新互动/无图片、认证及缓存保留，CDC关闭；同步模式确实停止MQ workers，MQ模式启动原生workers。先同步/MQ各80次预热共160次；两轮顺序 sync→mq / mq→sync，每种接口×并发1/4×模式各120样本，总960请求、0失败。模式启动和队列等待不能解释成纯网络成本；同JVM JDBC提交确认配对记录落库延迟，业务序号不用该时间判序。原始HTTP/持久化样本、SQL探针、提交配对和指标快照均在performance-results.json及其run目录；最终汇总为performance-summary.json。

| 接口 / 并发 | 同步平均 / P95 ms | MQ平均 / P95 ms | MQ持久化延迟平均 / P95 ms |
| --- | ---: | ---: | ---: |
| 点赞 / 1 | 13.855 / 18.593 | 10.767 / 15.296 | 870.692 / 1342.052 |
| 点赞 / 4 | 22.812 / 29.855 | 22.799 / 32.430 | 842.333 / 1536.114 |
| 评论 / 1 | 17.014 / 21.666 | 13.793 / 20.787 | 587.550 / 1126.064 |
| 评论 / 4 | 33.504 / 45.596 | 34.027 / 44.710 | 750.952 / 1420.433 |


同步通知与业务同一提交，表中同步延迟为该提交边界0，不是端到端设备延迟。MQ将等待移到发布/消费；每120次点赞写请求同步3120条应用JDBC执行（26/次），MQ HTTP1680（14/次），但另有消费2160（18/次）及按批领取/回写/指标查询；评论同步4440（37/次）、MQ HTTP2880（24/次），另有消费2280（19/次）及发布SQL。MQ四组每组实际120次原生send，平均约2.2—2.7ms/次；另有5秒周期有界Broker状态RPC。消费者每条多出用户/stream锁，故数据库总调用及等待位置没有消失。SQL探针不含驱动内部元数据SQL、校验器轮询及Broker内部IO；FOR UPDATE耗时含网络/执行/等待，InnoDB全局锁等待增量仅作补充，不能归为单条SQL精确锁时间。

每组MQ120个已完成事件对应120次原生SEND_OK，调用平均2.182至2.666ms；同步组发送0次，MQ另有每5秒有界Broker探测RPC。该耗时包含客户端、网络和Broker刷盘确认，不是纯Broker CPU时间。

本次并发4的点赞P95、评论平均存在退化；MQ持久化P95最高约1.54秒，接口提速不能单独证明整体性能提升。测试批量4/100ms及同收件人锁竞争限制排队，不是生产容量压测。Broker重启至正确持久化（含启停/路由恢复）**28186.516ms**；消费者停机8条SENT积压恢复（含新JVM启动）**7322.485ms**，不能据此宣称稳态吞吐上限。

生产指标为有限标签：Outbox pending/retry/paused/oldest、发送OK/NOT_STORED/UNKNOWN及耗时、消费APPLIED/STALE/DUPLICATE/RETRY、失败处置、意图至持久化、消费者启动/重试恢复/最后成功年龄，以及独立Broker可达/探测/恢复。consumer.state=0停止、1启动、2原生客户端已启动、3最后消费成功、4消费重试；Broker探测成功不会清除业务消费失败。事件/用户/帖子ID不作生产指标标签；不输出正文、匿名身份投影、JWT或凭据，也不开放metrics管理HTTP接口。

## 当前运行与复跑

18081 PID **30064** 已用本轮验收JAR按既有start-backend-dev脚本启动，开发库V12；两个健康接口UP、匿名帖子OK/3条，未带Bearer访问通知401。**MQ生成、发布器、消费者、CDC全部关闭**；实际开启MQ的仅为隔离测试库，未伪称开发库已上线MQ。验收JAR `apps/server/target/rocketmq/xinkelink-server-0.1.0-SNAPSHOT.jar` 与运行副本 `D:\CODE\XinKeLink\.local\backend-day1\run\5b9701d7ec2b47a8bbc059cdbf82618d\server.jar` SHA-256同为 `8fb99f1492173847f9e92a22f788716df21db1cf2eab97fd51c8263cd02fd60c`。runtime-18081.json记录启动开关及当前健康证据。

RocketMQ NameServer PID24120/19876、Broker PID29620/20911（另20909/20912）保留运行；Topic=`xl_interactions_20261116_6ef5452b_test_v1`，消费组=`xl_notifications_20261116_6ef5452b_test_v1`，数据源=`xinkelink_cache_20261114_5646631c_test`。所有临时18082/18083/18084/18085及11113均释放，验收MQ/CDC消费者已停。MySQL13306、Redis16379保留；Canal11111 PID11964保留，其最新进程状态以既有状态脚本为准。各PID/内存/端口只是交付快照，后续操作先检查归属。

从仓库根可复跑（先确认临时端口空闲，串行执行；凭据只从原保护文件读入）：

```powershell
& .local/rocketmq-20261116/rocketmq-server.ps1 -Action Status
& .local/rocketmq-20261116/rocketmq-server.ps1 -Action Start # Stop/Restart同入口，正常退出走官方shutdown hooks
& .local/rocketmq-20261116/live-run.ps1 # 先普通package，再显式真实MQ/双JVM/故障/性能，不因默认关闭跳过
& .local/rocketmq-20261116/canal-regression.ps1 # 当前验收JAR的真实原Canal专项
& scripts/initialize-backend-dev.ps1 -StartExisting # 正式binlog保留恢复入口，拒绝未知13306
& scripts/manage-notification-outbox.ps1 -Action Status -Database xinkelink_cache_20261114_5646631c_test
& scripts/manage-notification-outbox.ps1 -Action Replay -Database xinkelink_cache_20261114_5646631c_test -EventId '<原业务UUID>'
```

完整回归命令由 `test-environment.ps1` 加载既有JDK21/命名隔离库/原JWT，进入apps/server分别执行 `mvn -B -Dxinkelink.build.directory=target/rocketmq '-DargLine=-javaagent:D:/develop/apache-maven-3.9.4/mvn_repo/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar' test` 和同参数 `-Pdb-integration verify`；小程序在apps/miniprogram执行 `npm run check`，静态契约为 `apps/server/src/test/resources/schema-contract.ps1`。不要并行运行数据库/Canal/MQ/性能专项；不要使用旧文档中的全局REVOKE授权或skip-log-bin恢复命令。

## 保证边界及下一批

保证的是可靠持久化、自然业务幂等及可补偿，**不等于设备收到WebSocket**。RealtimeEvents仍为实例内连接表，消费实例只推本机经过会话复验的连接；其它实例可靠HTTP读取相同MySQL，在线跨实例即时推送未实现。未做跨主机推送/生产HA/长时间容量/物理断电断网及真实微信设备验收；这些没有伪写为已通过。本轮范围内的实现及要求的本地验收已完成，失败重试原始日志保留。

下一批Kafka行为统计尚未实现：应独立定义统计事件、Topic/组、幂等、保留和隐私口径，可从已提交业务事实设计入口；不要把本通知“取消后不再通知”的自然键语义、通知投影或Canal公共计数当统计授权依据，也不要在本消费者里附加未经Outbox保证的Kafka双写。

<!-- rocketmq-20261116-end -->

# 2026-11-16 Canal 公共计数补偿（本轮最新交接）

本节接续下方 v3 栅栏交接。HEAD 仍为 `7fc1af479e4e183d0c1d0f2e4bb9b0b6a08fa479`，分支 `codex/phase-1-foundations`；保留所有既有 B01—B11、页面、多层缓存及栅栏修改，未提交、推送或新增迁移。接手状态、原文及差异在 `.local/canal-20261116/{head-before.txt,branch-before.txt,git-status-before.txt,before.patch,before/}`；本轮精确文件归属见同目录 `task-files.json`、`final-verification.json`。

## 本轮实现与文件归属

共20个源码、配置、协议、测试及交接文件：已有 `PostMetricsCache.java` 增加同步确认入口并复用原失效实现；已有 `apps/server/pom.xml`、`application-auth.yml` 增加原生客户端、协议生成和默认关闭的 CDC 配置；已有本交接只追加顶部。新增 `forum/metrics/cdc/` 下 Consumer、Checkpoint、Configuration、EventMapper 四类；`src/main/proto/canal/` 两份协议和 NOTICE；ConfirmedInvalidation、Consumer、EventMapper、ProtocolCompatibility 四个单测类，独立 WorkerProcess、LiveVerification、PerformanceVerification 三个验收类，以及两份原版协议 descriptor 测试资源。其余接手源码、业务、页面和权限修改未覆盖。

仍只缓存 likeCount/commentCount，保留三模式、v3 Lua、来源截止时间、L1容量/2秒TTL、实际RC事务检查、写事务绕过、afterCommit和回滚规则、实时身份/权限/匿名/图片授权及鉴权限流。没有增加公共管理接口。

| 实际表与事件 | 受影响帖子与处理依据 |
| --- | --- |
| `xl_like` INSERT/DELETE | 分别使用 after/before 的 target_type、target_id；仅 post 目标失效，comment 点赞明确忽略 |
| `xl_like` UPDATE | 比较前后目标，失效旧、新帖子并去重；未改变目标不额外失效 |
| `xl_comment` INSERT/DELETE | 使用 after/before 的 target_id；V9后 target_type 只允许 post，根评论与回复的 target_id 均为帖子ID |
| `xl_comment` UPDATE | 比较 target_type/target_id/root_id/status/deleted_at，失效前后帖子；根隐藏/删除导致回复退出计数，也由同一帖子失效覆盖；无需关联SQL |
| `xl_post` INSERT/DELETE/治理UPDATE | 本帖ID；UPDATE比较 id/status/review_state/deleted_at，覆盖隐藏、恢复、软删和审核状态 |
| 无关表/已知无关目标/事务标记 | 不失效；合法无关批次可ACK。内容-only UPDATE不影响计数则忽略 |
| 相关DDL、未知相关事件、缺失必要镜像或解析失败 | 整批失败，不跳过坏行、不ACK，状态BLOCKED/MAP；需修复兼容性后重放 |

映射依据为真实 Mapper 和 V1—V11，before/after按FULL行镜像校验。批次先整体解析、去重，再以最多512帖一组调用原批量Lua；不逐行查库。Canal获取批次不保证完整包含一个大事务，按服务端FIFO逐批确认，不无限缓冲至END，也不只ACK最后一批。

## 可靠失效、确认与生命周期

原业务 `invalidateAfterCommit` 继续尽力失效，不让已提交业务因Redis故障报失败。新增 `invalidateConfirmed` 非空调用拒绝处于任何活动数据库事务中，直接同步执行：清本机L1/推进本机代次→原INVALIDATE Lua原子推进共享令牌并删除计数→finally再次本机清理→独立尝试广播。共享Lua没有明确成功或结果未知时抛出脱敏异常，CDC不能确认。广播单独失败记指标；共享已确认时允许ACK，远端未收广播的L1保留最多原2秒边界。没有复制Lua、改版本协议或无保护SET。

Consumer使用实际1.1.8的 connect→subscribe→rollback→getWithoutAck→处理→ack，全部失效chunk成功后先强制写入原子替换的PROCESSED审计，再发ACK，再记ACK_SENT。客户端ACK是**只写协议，没有服务端成功响应**，ACK_SENT不代表磁盘持久确认。任何部分失败、超时、未知结果、解析或审计失败均不主动确认，回滚/重连后从服务端确认游标重放，不按本地记录跳过重复。重复失效只推进后继令牌。

Spring生命周期默认关闭；连接失败异步有界退避（默认250ms至5秒），不阻塞论坛启动/读写。poll默认500ms，测试Worker为100ms，timeout分别5秒/3秒。状态、失败阶段、重试、批次、相关事件、受影响帖数、处理、ACK尝试/发送、最后成功时间与延迟均为有限标签指标。日志只有安全位点、数量与阶段，不记录行/正文/身份/凭据。

本轮另外复现并修复停止超时后立即重启形成两个消费线程的问题：保留未终止执行器，旧I/O未退出前拒绝新start；确定Latch测试的RED/GREEN记录在 lifecycle-red.log、mvn-test-final.log。停止最长等6秒，超时旧owner继续持锁至线程finally结束。

明确拓扑为**同一主机、同一运行用户、一个指定活动消费者**，destination=`post_metrics_test`，原生SDK固定clientId=1001；其它业务JVM关闭CDC，通过已有Redis广播清L1。除检查点锁外，按loopback端口/destination/1001生成用户目录下 `.xinkelink/cdc-owners/<sha256>.lock`，防止换检查点路径而误启同身份。该锁不提供跨主机、跨用户选主或完整HA；不能复制该身份在其它用户/机器同时运行。

## 环境、兼容性与位点

复用JDK21.0.12.1、MySQL8.4.13/13306、Redis7.4.11/16379和原JWT。已确认13306归属，备份配置后原数据目录安全重启，启用ROW/FULL binlog、sync_binlog=1、server_id=16111601、保留2592000秒（30天）。独立SHA2 CDC账号仅复制所需全局权限及**隔离测试库三张目标表SELECT**；原业务账号/权限未改，未连接3306、重建数据库、降低认证或重跑授权初始化。新CDC密码因MySQL注册从库字段最多32字节而设为32位十六进制随机值；凭据只在受保护的 private/ 中。

官方Canal1.1.8明确支持MySQL8.4/caching_sha2_password，原包SHA256 `9322af29323eaca78e0085e245d8ef9697908feda01d9352532f9ca0c8b44cc7`。Windows原生JDK运行，TCP强认证仅127.0.0.1:11111，不开admin/metrics监听。选择依据：[官方发布](https://github.com/alibaba/canal/releases/tag/canal-1.1.8)。

两个实际兼容问题均保留失败证据并修复：

- 客户端旧Protobuf生成代码触发GHSA-h4h5-3hr4-j3g2告警。保留已解析的4.35.1运行时，匹配protoc重新生成同FQN应用类；Entry源按**发布JAR内proto2 descriptor**重建，避免仓库标签proto3改变默认值/字段存在性。完整descriptor/defaults、严格unsafe-gencode保护、原生协议测试及真实打包JAR CDC通过。来源/哈希在proto NOTICE，生成Java仅在target。未关闭校验或降级运行时；该修复范围是应用客户端，不声称整个Canal服务端依赖已升级。
- Canal内polardbx-parser5.4.19的 SQLCheck.cloneTo 把表达式复制回源对象，副本CHECK为空，导致TSDB快照NPE。以准确旧标签源生成仅该类的服务端overlay，修复目标表达式/parent及enforcement flags，原JAR和数据库CHECK均保留；RED克隆测试与GREEN完整表结构快照、真实BinlogDump通过，**TSDB最终保持开启**。服务端无须关闭历史或删除约束。证据 state/parser-compatibility.json、tsdb-sqlcheck-*、原故障日志；上游源码链接及所有哈希在operational-manifest.json。

抓取位置/内存ring buffer与客户端确认游标不同：FileMixedMetaManager将后者放在 `state/normal/meta/post_metrics_test/meta.dat`，100ms周期刷盘、正常停机flush；FailbackLogPositionManager重启从确认游标恢复，允许重放。表结构历史持久在 `state/normal/tsdb/h2.mv.db`。本地checkpoint.properties只是PROCESSED/ACK_SENT审计，不能取代服务端游标。实际重启日志确认从 `xinkelink-bin.000001:2240777` 恢复，后续ACK到 `2241156`，没有重新选择初始158或latest；见 server-recovery-evidence.json 与原日志。没有验证主机断电下每次ACK立即fsync。

初始位置固定 `xinkelink-bin.000001:158`，auto.reset.latest.pos.mode=false；不在每次启动重新读latest。独立11113故障destination请求不存在000000:4，真实返回MySQL1236并显示BLOCKED_POSITION_UNAVAILABLE，随后已停；没有PURGE共享binlog。这是**不可用位点复现，不是实际清除共享日志**。无法续读时先保持BLOCKED/报警，原业务依靠主动失效、TTL与MySQL；优先恢复所需binlog并从确认位点重放。确实不可恢复时由操作者建立可信水位，对该订阅环境的已知帖子ID分批调用确认失效、核对数据库并记录新的起点后恢复，不能自动跳最新、FLUSHDB或清业务数据。

## 本轮验收与成本

| 层次 | 本轮实际结果/证据（根目录 `.local/canal-20261116/`） |
| --- | --- |
| 新增普通单测 | 59项：同步确认12、Consumer18、映射24、协议5；缺失API、错误ACK顺序、协议语义、身份锁和停止竞态的RED原始日志保留 |
| 小程序完整 | npm run check：335通过、0失败/跳过；miniprogram-check.log |
| 后端完整普通 | mvn test：293通过、0失败/错误/跳过；mvn-test-final.log |
| 完整隔离库验收 | mvn -Pdb-integration verify：普通293＋集成198全通过、0跳过；full-db-verify.log；包括原真实Redis20项和MySQL快照/提交/回滚及缓存身份权限回归 |
| 静态契约/差异 | 11迁移、V9后25表通过；schema-contract.log；git diff --check通过，CRLF提示另存 |
| 实际CDC/双JVM | 显式LiveVerification 1项、143个记录断言全true；真实MySQL binlog＋Canal＋Redis，18082/18083独立业务JVM，另独立原生消费者；live-results.json、live-latest-log.txt所指原始日志与live-runs/ |
| 打包产物原生CDC | 同验收JAR在18084开启CDC，真实SQL推进栅栏和ACK_SENT，验证Spring配置及应用生成协议类实际可运行；同上packaged证据 |
| 配对开销专项 | 显式PerformanceVerification 1项通过，perf-cdc.log、perf-cdc-http.json、perf-summary-final.* |

全部后端沿用既有test-environment.ps1的13306命名隔离库、JDK21和显式ByteBuddy javaagent。专项按明确类名执行，未因默认关闭而跳过；不删除测试、跳过断言或开放敏感端点。初次联调在Redis故障阶段用全局ACK计数比较，被前一批合法ACK干扰而失败，已保留原日志/结果，改为检查故障**具体位点**没有ACK后真实重跑通过。

真实验收覆盖直接SQL点赞增减、根/回复新增和治理、隐藏恢复软删、事务回滚、无关表/评论点赞、访客/A/B个人状态与匿名身份、应用主动失效＋CDC重复、真实旧回填暂停后CDC推进并拒绝旧代次，均在原L1到期前验证目标链路。故障覆盖消费者停止积压、共享成功ACK前**强制退出消费者JVM**再重放、ACK已发送后注入结果未知、客户端网络边界故障、Canal服务端真实Stop/Start及不可用位点。Redis故障在该消费者的RedisTemplate.execute边界注入连接异常，走真实invalidateConfirmed；native网络/ACK未知也为受控适配器注入，未关共享Redis、未物理拔网，不能写成全机断网演练。相关广播故障/未知共享结果另有单测及原真实Redis脚本代理测试。

不开CDC，直接SQL提交后两实例仍读旧值且共享令牌不变；本轮仅测得当时陈旧，没有声称等待了完整TTL。开CDC后20次交替增减，从提交SQL起至两实例读到新值平均 **105.192ms、P95 125.632ms、0失败**，包括客户端/数据库/轮询/广播/HTTP；不等于纯网络传播耗时。消费者停机2笔提交合并成相关事件2、受影响帖1的一批，含新JVM启动的恢复 **1806.297ms**；Canal服务端重启含启停与重连 **13610.487ms**。

同一JVM/数据/构建/客户端/访客/root WARN，Caffeine＋Redis保持不变，对比空闲CDC关闭/开启；20帖、每帖10赞10评论；2080次预热、2000测量、0失败。热组两轮反转顺序合并，每格240样本，并发1/4；冷列表每格40。以下平均/P95毫秒，原始样本与每轮值完整保存：

| 请求/并发 | CDC关闭 | CDC开启 |
| --- | ---: | ---: |
| 热单帖/1 | 3.513 / 5.722 | 2.079 / 2.708 |
| 热单帖/4 | 3.949 / 6.508 | 3.102 / 3.737 |
| 热列表/1 | 4.353 / 7.832 | 3.383 / 4.168 |
| 热列表/4 | 4.361 / 7.529 | 3.946 / 4.808 |
| 冷列表/1 | 7.688 / 12.012 | 5.704 / 6.676 |

首轮关闭组明显慢于后续组，说明仍有JIT/调度等顺序漂移，**不能据此宣称CDC让HTTP提速或给出因果开销百分比**。可确认的调用成本：两种条件每240热请求公共计数SQL0、Redis脚本0，L1命中详情240/列表4800；个人SQL240，其它SQL详情1440/列表1680，两边相同，另各240次驱动隔离检查。每40冷列表两边同为40批公共SQL、40 READ＋40 FILL，800帖回源，计时外控制清理40 INVALIDATE＋广播。开启CDC在热测量窗口另有9次原生GET、无业务变更所以ACK0；冷组没有单独记native GET/ACK增量。HTTP探针不包含Canal服务端元数据SQL或驱动原生隔离SQL，不能把它们说成零。变更时另增加按批次的共享INVALIDATE＋广播；20次补偿和积压的原位点/事件数量证明其实际执行。双JVM另有1920个配对HTTP样本及全局SQL/Redis增量，见live-results.json；全局计数只作补充。

## 当前运行、复跑与后续边界

18081已按原start-backend-dev脚本启动本轮验收JAR，PID6684；api/v1/health与actuator/health均UP、匿名帖子接口OK/3帖。运行副本与验收JAR SHA256同为 `7f75c69ea8d4d5eccda92a9bf99e783c68fc9ce0083249870789ff7fb2f67b3d`，证据runtime-18081.json。18081连接原开发库，**CDC默认关闭**；本轮仅给隔离测试库授权和订阅，没有把测试订阅伪装成开发库已上线。

Canal服务端11111保留运行（最新PID24052，以state/canal-normal.json为准），订阅隔离库 `xinkelink_cache_20261114_5646631c_test`；所有临时业务/消费者JVM已退出，18082/18083/18084/11113释放。故目前没有活动Java CDC消费者，服务端保留抓取/表结构历史；需要继续消费时指定一个实例启用。MySQL13306仍启用binlog，Redis16379保留。当前状态和健康值是本次验收快照，不替代以后实时检查。

可复跑（先cd仓库，凭据均由保护文件加载，不写命令行）：

```powershell
& .local/canal-20261116/canal-server.ps1 -Action Status -Profile normal
& .local/canal-20261116/canal-server.ps1 -Action Start -Profile normal # Stop/Restart同入口，正常停机flush
& .local/canal-20261116/mysql-binlog.ps1 -Action Status # 以后恢复13306用该Start，旧初始化脚本StartExisting仍硬编码skip-log-bin
& .local/canal-20261116/live-run.ps1 # 独占测试库/1001身份，显式执行专项，结束清理自建JVM
& .local/canal-20261116/perf-run.ps1 # 需确认没有另一个活动消费者/测试负载
. .local/canal-20261116/consumer-environment.ps1 # 只加载隔离订阅环境；之后仅指定一个Spring服务实例启用CDC
```

默认业务服务保持CDC关闭。启用时配置 XINKELINK_POST_METRICS_CDC_ENABLED/HOST/PORT/DESTINATION/USERNAME/PASSWORD/SCHEMA/CHECKPOINT，BATCH_SIZE/POLL_MILLIS/TIMEOUT_MILLIS/RETRY_*可选；checkpoint需绝对路径。正常关闭服务走Spring生命周期，极端退出依赖确认游标重放。不要并行执行两个验收脚本或用初始化/授权脚本恢复已有环境。

交付时18081为PID6684，使用 `target/canal-final/xinkelink-server-0.1.0-SNAPSHOT.jar` 经既有start-backend-dev.ps1复制启动；运行副本 `.local/backend-day1/run/279abe341c43487b8b393aa26b4839c7/server.jar` 与真实双JVM验收产物SHA256均为 `7f75c69ea8d4d5eccda92a9bf99e783c68fc9ce0083249870789ff7fb2f67b3d`。两个健康接口UP，匿名帖子OK/3条。**18081连接开发库，CDC关闭；本轮仅对隔离库实际开启并验收CDC，未替开发库配置CDC授权或持续订阅。** Canal PID24052/11111保留运行、destination=post_metrics_test，验收消费者已退出；18082/18083/18084及故障11113均已释放。13306和16379继续保留。运行核对在runtime-final.json，服务日志仍为既有backend.stdout/stderr.log；Canal正常启停用上述canal-server.ps1，不按旧PID盲目停止。

从仓库根重新验收执行 `.local/canal-20261116/live-run.ps1` 和 `perf-run.ps1`（串行，脚本已含JDK21与显式agent）。正常手工运行隔离订阅实例可在确认18084空闲、没有其它1001消费者后执行下列命令；此实例用隔离测试库，Ctrl+C触发Spring正常停止，18081开发实例继续关闭CDC：

```powershell
. .local/canal-20261116/consumer-environment.ps1
& "$env:JAVA_HOME/bin/java.exe" -jar apps/server/target/canal-final/xinkelink-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=db,auth --server.address=127.0.0.1 --server.port=18084
```

一致性仍为异步补偿：数据库提交到CDC推进之间有窗口，服务离线/积压时窗口可延长；Pub/Sub丢失仍有L1剩余2秒；Redis失效失败不ACK但恢复前可能读旧L2；元数据刷盘不是每ACK fsync。POLLING仅说明客户端可拉取，不能证明服务端parser健康，需结合服务端状态脚本/错误日志、最后成功位点及延迟监控。仅验收单机Redis、单主机单用户指定consumer；未做跨主机选主、生产HA/断电恢复、真实物理网络故障、真实共享binlog清除、相关DDL迁移联调或超大单事务跨批故障专项。不能宣称强一致、生产容量验证或Canal全依赖安全升级。

下一批RocketMQ仅考虑现有点赞/评论等**互动通知**领域边界：业务事务内可靠事件/Outbox、事件ID与幂等、收件人与匿名/可见性校验、消费重试和死信；不要把本CDC公共计数事件直接当通知正文或授权依据。计数补偿继续复用invalidateConfirmed；原业务汇合点invalidateAfterCommit保留。后续需规划持久化/保留期告警、不可续读补偿流程和跨主机单活，再评估通知事件投递，均未在本轮实现。


# 2026-11-16 公共计数分布式版本栅栏（实现与验收）

本节为最新缓存交接，工作从11-15延续至11-16；下方11-15多层缓存记录完整保留。接手HEAD `7fc1af479e4e183d0c1d0f2e4bb9b0b6a08fa479`、分支 `codex/phase-1-foundations`，101条Git状态（包含折叠目录，不等于文件数）。快照、原文及差异在 `.local/metrics-fence-20261115/{head-before.txt,branch-before.txt,git-status-before.txt,before.patch,before/}`。保留既有B01—B11、Redis/Caffeine及页面修改，没有提交、推送、迁移、重授权限、替换JWT、连接3306或清理.local。

## 本轮文件与原有修改的归属

| 文件（相对仓库根） | 本轮增量 |
| --- | --- |
| `apps/server/src/main/java/cn/xinkelink/forum/metrics/PostMetricsCache.java` | 接手已有；接入共享栅栏、实际隔离检查、确认接受后才入L1、读取起点限定L1期限，失效前后本机代次保护 |
| 同目录 `PostMetricsFence.java` | 本轮新增；v3批量READ/FILL/INVALIDATE Lua及有限标签统计 |
| `apps/server/src/test/java/cn/xinkelink/forum/metrics/PostMetricsCacheTest.java`、`PostMetricsL1Test.java`、`PostMetricsL1SmokeTest.java` | 接手已有；保留28＋27＋1项，适配脚本协议、拒绝旧回填及未确认结果不入L1 |
| 同目录 `MockPostMetricsRedis.java` | 新增单测协议适配器，只模拟格式和故障，不作为Redis原子性证据 |
| 同目录 `PostMetricsFenceIT.java` | 新增20项真实Redis专项；Latch/Barrier和执行前后代理控制时序 |
| 同目录 `PostMetricsPropagationIT.java` | 接手已有；适配失效前后两次本机保护的计数，保留自动恢复与真实广播断言 |
| `apps/server/src/test/java/cn/xinkelink/forum/PostMetricsSnapshotIT.java` | 新增4项真实MySQL事务快照/提交/回滚专项 |
| `apps/server/src/test/java/cn/xinkelink/forum/PostMetricsCacheIT.java` | 接手已有14项；内部key断言升级v3，安全及权限断言保留 |
| `apps/server/src/test/java/cn/xinkelink/forum/PostMetricsPerformanceBenchmark.java` | 复用已有基准，改为3轮/本轮样本量并记录新增指标，不再执行管道诊断 |
| `docs/project-handover.md` | 只在顶部追加本节，保留原文 |

合计12个源码/测试/交接文件，精确清单 `task-files.txt`。本轮未修改 `ForumService`、`ForumMapper`、`PostMetricsInvalidation`、POM、全局隔离配置、鉴权限流、UI、消息或fixture业务。生产依赖没有新增/升级，仍只缓存likeCount/commentCount，Counts接口不变。

## 原竞态、共享协议和关键时序

两个RED已实际复现，不是静态推断：`redis-red.log`在真实Redis暂停旧SET，另一实例完成失效后恢复SET，新观察者错误读到旧计数（1项断言失败、0错误）；首次Redis未运行只算环境错误，另存 `redis-environment-failure.log`。`snapshot-red.log`在真实MySQL先建立RR旧快照0，另一事务提交点赞1并失效，旧事务随后把0回填，独立请求错误命中0（1项断言失败）。旧Redis复现测试原文 `redis-red-test.java` 和接手模块原文均保留。

v3数据key `xinkelink:cache:v3:post-metrics:{id}`，元数据key `xinkelink:cache:v3:post-metrics:generation:{id}`。数据格式仅 `token|expiresAtEpochMillis:likeCount:commentCount`，没有个人状态、作者、正文或图片。令牌为当前Redis `run_id` 加40位摘要，摘要组合请求UUID、Redis执行TIME、该key前代令牌及批内位置。它是高熵不透明世代，不是缺失时回到0的整型版本；同一失效命令重放也生成后继令牌，不把过去令牌直接SET回来。

1. 批量READ Lua原子取得各帖令牌及L2值；元数据缺失或所属Redis进程已变时，创建新世代并删除残留v3值。数据携带的令牌不匹配也被丢弃。不回读v1/v2值。
2. 所有miss只执行一次原批量公共计数SQL，数据绑定SQL前取得的令牌；来源截止时间仍在SQL开始前确定，慢SQL消耗原TTL。
3. 批量FILL Lua在同一次Redis执行内比较期望令牌、当前Redis进程及源截止时间，只有匹配且未到期才SET剩余TTL。元数据缺失时FILL绝不初始化。逐条返回接受、版本拒绝或过期拒绝，Java先验证整份响应，再允许明确接受项入L1；不会退回普通无保护SET。
4. 成功提交后的既有 `invalidateAfterCommit`先清本机L1/推进本机代次，再执行INVALIDATE Lua原子更换共享世代并删除v3/v2/v1对应计数；结束再清本机并推进一次，阻止失效调用期间开始的本地旧读转填。最后独立广播ID，广播失败不撤销已成功的共享栅栏。

于是，旧读持令牌A，失效成功推进至B后，晚到Redis的A回填返回拒绝，既不重新形成有效L2，也不能进入L1；其已经读取的旧响应仍允许返回。两个当前世代读者可正常回填；两个并发失效串行推进到不同后继世代。旧失效事件重复执行只会额外淘汰，不会回退版本。这里没有用进程锁或分布式锁串行化公共读取。

## 数据库旧快照与事务策略

实际池连接已核对为READ_COMMITTED，来自原有 `application-db.yml`，本轮未改；原MyBatis配置为STATEMENT级本地缓存。不能只依赖类级readonly或在聚合SQL前读取新Redis令牌，因为外层调用仍可能显式采用RR，并在之前的帖子/身份查询中建立旧快照。

生产构造器注入DataSource，缓存入口检查当前绑定的真实JDBC连接隔离级别。仅可确认的RC只读事务使用两层缓存；RR/Serializable/未知、无绑定连接或检查失败时，保持调用者事务直接执行原loader，既不读缓存也不发布缓存。写事务及总开关关闭继续直接loader，短路检查，不发布未提交数据。没有改事务边界、全局隔离级别或权限查询，也没有REQUIRES_NEW额外占用连接。当前旧RR响应可合法保持0，但之后独立RC请求必须取得1，已用真实数据库验证。

检查本身有代价：当前Connector/J 9.7.0的 `getTransactionIsolation()`在默认useLocalSessionState=false时原生查询会话变量，普通Statement代理不会统计到它；本轮通过独立 `post.metrics.isolation.checks/check/bypass`计数/计时报告，不能把它遗漏后声称零数据库访问。源码依据：[Connector/J实现](https://github.com/mysql/mysql-connector-j/blob/9.7.0/src/main/user-impl/java/com/mysql/cj/jdbc/ConnectionImpl.java#L1109)。没有为数字改变驱动全局设置。

## 生命周期、TTL、故障与混部边界

元数据TTL为 `max(60000ms,2×(Redis基础TTL＋最大抖动))`，默认60秒，每次实际L2 READ及失效刷新；FILL不复活元数据。空间为最近该时间窗内读写帖子数的O(N)，每帖一个额外元数据key；令牌本体81字节，另有key、过期和Redis对象开销，不为数据库所有帖子永久建版本key。热帖持续活跃可以续元数据TTL，停止访问后自动过期；元数据淘汰也不会让旧回填重新合法。使用随机摘要有常规碰撞概率假设，不宣称数学绝对无碰撞。

Redis基础TTL仍20秒＋0—10秒抖动；保留源截止时间，FILL按Redis服务器TIME计算剩余TTL。L1仍最大10000、expireAfterWrite 2秒，并额外受源截止时间及**本次READ起点＋2秒**限制，迟到的L2响应不会从到达时再获得完整2秒。L1热命中不访问Redis，但仍执行实际隔离检查。

READ/FILL脚本异常、超时、缺失/畸形回复均保守处理：无确认令牌的回源不填两层，FILL没有逐项明确接受则不装入L1，即使服务器可能已经执行。成功写入但回复丢失时，共享值仍必须符合原子校验；后续请求重新READ验证。计数SQL异常继续透传，不伪装成0。INVALIDATE异常或结果未知仍清本机、独立尝试广播，不把已提交业务改成失败；但共享推进未成功时仍可能有旧L2，不能宣称已关闭那种故障窗口。

令牌包含Redis进程epoch，READ/FILL脚本均检查当前run_id；重启后即使恢复带旧令牌的持久化数据，旧进程令牌也不能被当前进程接受。脚本内部读取INFO server，不对HTTP暴露；当前真实Redis已支持，无权限变更。若未来ACL禁止EVAL/EVALSHA、INFO等必要命令，缓存失败关闭到数据库，不自动扩大权限。整个Redis进程重启/持久化恢复本轮未实际执行；旧run元数据定向注入和真实脚本拒绝已测试，不能把模拟输入说成重启演练。

保留channel `xinkelink:cache:v2:post-metrics:invalidate`的ID广播及已有订阅重试/恢复清L1。失效与广播重复或乱序是安全的额外清理；Pub/Sub不补发离线消息。未收到广播时，旧L1仍可能在读取起点起最多剩余2秒内被使用，且受源截止时间约束；已观测的广播/订阅恢复会阻止在途转填。L2 READ后发生未观测失效不能由本机锁立即获知，这一短TTL边界明确保留。

混部时v3不读取旧格式，新失效也删除v1/v2；但未升级旧写实例不能推进v3共享栅栏，仅靠兼容ID广播不足以让旧写端获得本轮保证。必须升级所有写入实例后再按v3目标验收；未升级期间仍有原TTL边界，未清空Redis。当前仅验收既有单机Redis，未做Redis Cluster多slot适配。

数据库提交至共享栅栏推进之间、推进失败且没有可靠补偿时，仍存在窗口。本轮不宣称数据库与Redis强一致，也不承诺从提交时刻起严格30秒。客户端/服务器时钟需合理同步；同进程内人为恢复旧Redis元数据、异常时钟回退及生产持久化/故障切换演练不属于已完成验收。

## 本轮回归与证据分层

| 层次 | 实际结果与证据（均在本轮忽略目录） |
| --- | --- |
| 单元 | 原缓存28＋27＋1保留并通过；未确认读取/填充不入L1、源TTL、容量、开关、事务等，`unit-adapt-first.log`。模拟协议helper不证明Redis原子性 |
| 真实Redis | 新增20项全部通过；Lua前后Latch/Barrier控制旧回填、并发失效、元数据丢失/过期、命令重放、迟到L2、代理注入读/填/失效/广播故障及回复未知，`redis-green.log` |
| 真实MySQL | 新增4项全部通过；建立旧RR快照与独立提交并发、RR绕过新L1、写事务读己之写/回滚、提交失效，`snapshot-green.log` |
| 专项合并 | 98项，0失败/错误/跳过，`focused.log`；包含旧14项身份/匿名/隐藏删除/权限/批量无N+1集成回归 |
| 小程序完整 | `npm run check`335项、0失败0跳过，类型/工程检查通过，`miniprogram-check.log` |
| 后端完整普通 | `mvn test`234项，0失败/错误/跳过，`mvn-test.log` |
| 完整数据库验收 | `mvn -Pdb-integration verify`普通234＋数据库198，均0失败/错误/跳过，BUILD SUCCESS，`full-db-verify.log`、`final-verification.json` |
| 静态契约/空白 | 11个迁移、V9后25表，`schema-contract.log`；git diff --check通过，CRLF提示单独保留 |
| 两个独立服务JVM | 18082/18083使用同一验收JAR及隔离库，A真实PUT点赞，B在原L1到期前看到新值；访客/A/B liked、favorited、isMine与匿名映射正确，13项断言全true，`two-jvm-results.json` |

双JVM暖读至远端新值 **420.410ms**，包含HTTP往返、数据库提交及读取，不是纯Pub/Sub网络延迟。与真实Redis受控竞态测试分别说明，不把两个同进程缓存对象冒充两个服务进程。自建用户/帖子仅在隔离测试库软删除，临时JVM已停止、18082/18083已释放，脚本 `two-jvm-isolated.ps1` 可复跑。

全部后端测试使用既有JDK21.0.12.1及 `.local/cache-audit-20261114/test-environment.ps1`，测试JVM显式 `-DargLine=-javaagent:D:/develop/apache-maven-3.9.4/mvn_repo/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar`，构建 `target/fence-final`。没有改生产POM或跳过安全断言。资源未运行时仅恢复已有MySQL的StartExisting和原WSL Redis，`snapshot-environment.json`记录归属；早期WSL命令/Redis未运行失败没有算作验收通过。

## 栅栏成本实测

同一JVM/构建、Java HttpClient持久HTTP1.1、访客、root WARN、同一隔离fixture（20帖，每帖10赞10评论、无图片）、相同请求及闭环并发1/4；每路由/模式先400次全局预热，共2400，再每单元80次共2880。三轮轮换模式，每轮120测量请求，热组各360，冷组各40；总5280预热＋**4560测量，0失败**。只使用原命名cache测试库；额外核对性能自增ID区间高于开发数据ID区间，避免共用Redis下的ID碰撞，记录 `database-safety.json`。

下表平均/P95毫秒。P50、吞吐、全部18组和逐请求原始值分别见 `perf-summary-final.{md,json}`、`perf-http-final.json`。合并原始样本算分位数，不平均各轮P95。冷吞吐含控制失效操作，HTTP延迟不含；OS后台任务未完全控制，CPU前后快照不是全程负载。

| 请求/并发 | MySQL | Redis＋栅栏 | Caffeine＋Redis＋栅栏 |
| --- | ---: | ---: | ---: |
| 热单帖/1 | 2.920 / 4.540 | 2.440 / 3.132 | 2.049 / 2.838 |
| 热单帖/4 | 3.229 / 4.487 | 3.243 / 3.987 | 2.262 / 2.632 |
| 热列表/1 | 3.185 / 4.431 | 3.462 / 4.871 | 2.516 / 3.074 |
| 热列表/4 | 3.657 / 4.631 | 4.175 / 5.306 | 3.189 / 4.436 |
| 冷单帖/1 | 3.680 / 4.413 | 4.083 / 4.695 | 3.980 / 4.738 |
| 冷列表/1 | 3.878 / 4.401 | 5.471 / 6.771 | 5.336 / 6.325 |

热组每360请求：MySQL详情公共/个人/其它JDBC为360/360/2160，列表360/360/2520；缓存模式公共SQL0、个人/其它不变。Redis模式各360次READ脚本；双层模式各0次Redis调用、L1命中360或7200条。两个缓存模式各额外360次实际隔离检查，驱动原生查询不含在前述JDBC代理计数中，须另计，不能声称所有DB访问减少或零DB。正常只读负载旧回填拒绝0；受控旧回填拒绝由专项实际验证，而非声称性能负载制造了写竞态。

每40次冷列表，两缓存模式各40次READ、40次FILL、800条CAS接受、40批SQL回源(800ID)，无逐帖版本查询或逐帖SQL。控制清理各40次INVALIDATE脚本及PUBLISH，在HTTP计时外。指标中的脚本调用是Java逻辑EVALSHA调用，首次NOSCRIPT可能再EVAL，已预热；Lua内部INFO/TIME/GET/SET/PEXPIRE/DEL不是额外网络往返。READ每帖两次GET及一次元数据续期，FILL每帖比较后才SET，失效按帖推进并删除，不把这些内部命令误标成MGET。

新增可测成本：热请求隔离检查约0.063—0.092ms/请求，L1热命中也承担此成本；Redis热READ脚本约0.443—0.778ms/请求。冷列表FILL脚本约0.581/0.585ms、控制INVALIDATE约0.576/0.604ms（后者在HTTP外）。因此Redis热列表、两个缓存模式冷列表本轮仍比MySQL慢，热单帖并发4的Redis均值也略高于MySQL，不能只选有利样本。L1消除Redis访问，但收益仍受其它实时SQL/调度影响。

这只是本轮三模式同条件比较；旧11-15构建、样本量与运行时负载不同，不把新旧绝对值直接相减宣称栅栏提速。旧管道试验没有重做；批量Lua是正确性所需的原子协议，不将它等同此前未证明收益的管道优化。独立命令/检查Timer揭示成本，不声称已完成v2/v3成对因果性能实验或生产容量验收。脚本 `perf-run.ps1`、`perf-summary.py`可复跑，`perf-environment-final.json`记录源码SHA及环境快照。

新增指标仅固定operation/reason标签：`post.metrics.redis.script.calls/script`、`post.metrics.fence.accepted/rejected`、`post.metrics.isolation.checks/check/bypass`；沿用L1/L2命中、淘汰、回源和广播指标。无帖子/用户高基数标签，未新增公开管理接口，Actuator仍仅health。

## 运行产物与下一批Canal

验收JAR `apps/server/target/fence-final/xinkelink-server-0.1.0-SNAPSHOT.jar` SHA256 `8c21bb9e37dfabd82c6720cbbd01c696c4726b5d0ccd314ec18769e84b4a833e`。Cache class `0aa0c2f3c41c8a335e9a5ba6c57d1caf6d641a48f9ec150af2ffb101407d170b`，Fence class `6b7b418019ef39170a27b7b244879c07aed43d87b6f0c2fc2433c10a40da9928`，均与性能构建比对一致。

已有启动脚本使用同一JAR启动18081、db/auth，PID28164；API及Actuator health UP，匿名帖子3条，beta open/未登录。`server-start.log`、`final-health.json`、运行产物核对和 `final-verification.json`为证；`dev-counts-before/after.txt`显示开发库帖子/评论/点赞/收藏/用户/迁移记录行数未变。没有运行中的测试探针或新管理端点，JWT仍为原密钥。

后续Canal可复用 `PostMetricsCache.invalidateAfterCommit(Collection<Long>)`：没有业务事务时立即走同一共享推进＋删除＋本机清理＋广播，有事务则提交后执行；重复或乱序事件安全地额外推进，不让旧事件把版本恢复。仍待实现可靠消费/重试/补偿、提交至栅栏窗口收敛、真实Redis重启/持久化恢复及网络分区演练、跨主机时钟/ACL和生产负载验证。Canal、MQ、活动和AI均未在本轮实现。

---

# 2026-11-15 公共计数性能定位与 Caffeine 多层缓存（最终结果）

接手 HEAD `7fc1af4`；既有未提交变更快照和补丁保存在 `.local/l1-cache-20261115/git-status-before.txt`、`before.patch`，缓存模块等目标文件原文在 `before/`。保留原工作区，不提交推送，不新增迁移，不触碰业务数据或账号授权。

接手状态说明：99条Git状态，不等于99个文件（目录折叠）；既有B01—B11、Redis、V10/V11及页面改动均保留。本节取代下方11-14缓存说明，历史记录原样保留。

## 本轮文件归属

| 文件（相对仓库根） | 接手状态与本轮增量 |
| --- | --- |
| `apps/server/pom.xml` | 原未修改；只加Caffeine依赖，由Boot 4.1.1 BOM管理为3.2.4，无其它升级 |
| `apps/server/src/main/java/cn/xinkelink/forum/metrics/PostMetricsCache.java` | 原有未跟踪Redis模块；新增L1、v2截止时间、统计、广播及代次保护 |
| 同目录 `PostMetricsInvalidation.java` | 本轮新增；后台订阅启动、失败重试、停止和恢复清理 |
| `apps/server/src/main/resources/application-auth.yml` | 原已修改；只在原缓存配置下加三个L1参数 |
| `apps/server/src/test/java/cn/xinkelink/forum/metrics/PostMetricsCacheTest.java` | 原已有28项测试；保留测试，适配v2格式和剩余TTL断言 |
| `apps/server/src/test/java/cn/xinkelink/forum/PostMetricsCacheIT.java` | 原已有14项；保留并在L1开启下验证身份/权限/事务/治理；Redis过期专项关闭L1 |
| `apps/server/src/test/java/cn/xinkelink/forum/metrics/PostMetricsL1SmokeTest.java`、`PostMetricsL1Test.java` | 本轮新增1＋27项，包含确定性Clock/Ticker及在途竞态 |
| 同目录 `PostMetricsInvalidationTest.java`、`PostMetricsPropagationIT.java` | 本轮新增：Spring离线启动、初次失败自动恢复、独立实例真实Redis广播及中断/恢复 |
| `apps/server/src/test/java/cn/xinkelink/forum/PostMetricsPerformanceBenchmark.java` | 本轮新增；显式运行的真实HTTP性能基准，测试专用Service/MyBatis/JDBC探针 |
| `docs/project-handover.md` | 原已修改；只在顶部追加本节，保留全部旧交接 |

本轮没有改Mapper、ForumService、Counts业务模型、后台/举报写路径、界面、fixture业务、消息、权限、匿名投影或图片授权；继续复用上轮提交后失效汇合点。共12个源码/配置/测试/交接文件；本地脚本及证据位于忽略的 `.local/l1-cache-20261115/`，未新增重复跟踪报告。没有新迁移、提交或推送。

## 读取、配置与TTL

| 模式 | 配置（需重启） | 读取 |
| --- | --- | --- |
| MySQL | `XINKELINK_POST_METRICS_CACHE_ENABLED=false` | 两层均不读/填，真实批量查库 |
| Redis→MySQL | 总开关true，`XINKELINK_POST_METRICS_L1_ENABLED=false` | 一次MGET，所有miss一次批量SQL |
| Caffeine→Redis→MySQL | 总开关true、L1开关true（默认） | L1命中不访问L2或公共计数SQL；剩余ID批量MGET，L2miss一次批量SQL |

配置键 `xinkelink.cache.post-metrics.l1.{enabled,maximum-size,ttl-millis}`，环境变量 `XINKELINK_POST_METRICS_L1_{ENABLED,MAXIMUM_SIZE,TTL_MILLIS}`，默认true/10000/2000。使用Caffeine `expireAfterWrite`，访问不续期；按去重后的帖子ID处理，空输入不读两层/DB。Redis仍20秒＋0—10秒随机抖动，沿用原TTL配置。

v2 key为 `xinkelink:cache:v2:post-metrics:{postId}`；内部值仅 `expiresAtEpochMillis:likeCount:commentCount`。不读取缺少截止时间的旧v1值，失效同时删v1/v2，不清Redis；v1自然过期。混合版本滚动部署时旧实例不发布v2消息，仍需按TTL理解陈旧窗口。

回源开始前确定来源起点，回填使用剩余TTL；L1读取同时检查源截止时间。因此正常时钟下有效期为 `min(L1装入+2s,来源起点+20—30s)`，不是32秒，重新装入L1也不刷新源截止时间。慢SQL消耗TTL，源值过期则只返回当前响应、不填缓存。格式错误、溢出、过期和负数不缓存；缺行保留原Service报错，DB异常原样透传。跨主机时钟需合理同步，未承诺任意时钟偏差下严格上限。

## 事务、失效、多实例及故障

复用 `invalidateAfterCommit`，覆盖点赞变化、评论/回复增删、删帖、后台帖子/评论隐藏恢复删除、举报隐藏。成功提交后清本机L1、删L2并广播；回滚不失效或广播。写事务绕过两层读写，不发布未提交计数。总缓存关闭时仍执行失效。删Redis和广播分别捕获异常，删除失败仍尝试广播，不把已提交业务改成失败；DB异常不在Redis捕获范围。

channel `xinkelink:cache:v2:post-metrics:invalidate`只含逗号分隔帖子ID，无个人状态，接收端有界线性校验、溢出整条拒绝。每实例独立L1/订阅；Spring生命周期只启动后台任务，初次Redis故障不阻断应用，每约1秒检查/重试，停止时关闭任务及连接。订阅确认、取消和失败清本地L1，恢复确认再次清理。Pub/Sub没有离线补发；漏消息但L2删除成功时，本机已装入旧L1通常最多剩余2秒，删除也失败则可从旧L2再次装入，以源截止时间兜底。

有界全局代次抑制已观测失效/重订阅之后的旧SQL/Redis读取回填；代价是无关帖写入也可能保守取消一次填充。**已派发SET仍可能晚于DEL完成**，旧请求也可能返回旧快照，后来请求可再读到旧L2，但不能延长原截止时间。事务快照、网络及主机时钟都是边界，不宣称从提交时刻起严格30秒或强一致。分布式版本栅栏/Canal留后续。

Micrometer沿用现有注册表：`post.metrics.l1.hit/miss/evictions/size`、`l2.hit/miss`、`redis.read.calls/fill.calls/fill.keys/errors`、`database.loads/ids`、`invalidations`、`subscription.resets/failures`，另有Redis读/填和DBloader Timer。hit/miss按去重条目、MGET/loader按批、fill.keys按尝试SET计数；淘汰原因及错误operation是固定有限标签，无用户/帖子ID。未新增公开接口；Actuator仍只开放health。

## 回归与真实环境

- 小程序完整 `npm run check`：**335**，0失败0跳过，类型及工程检查通过，`miniprogram-check.log`。
- 后端完整 `mvn test`：**234**，0失败/错误/跳过，`mvn-test.log`。
- 隔离配置 `mvn -Pdb-integration verify`：普通**234**＋数据库IT **174**，均0失败/错误/跳过，BUILD SUCCESS；`full-db-verify.log`、`final-verification.json`。原缓存14项IT全部保留通过；专项合计60条已含在上述总数，不能重复累加。
- 数据库静态契约通过：11迁移，V9后25表；`schema-contract.log`。`git diff --check`通过，CRLF提示单独保留。
- JDK21.0.12.1：已有 `.local/backend-day1/tools/jdk21/jdk-21.0.12.1+1`；子shell点源 `.local/cache-audit-20261114/test-environment.ps1`，只连既有13306命名cache测试库及16379。命令带 `-Dxinkelink.build.directory=target/l1-cache-final` 和显式测试 `-DargLine=-javaagent:D:/develop/apache-maven-3.9.4/mvn_repo/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar`。未改生产agent或测试POM选项、删测试、跳过安全断言。
- RED证据 `l1-red.log`、`l1-tests-red.log`、`subscription-offline-red.log`；最后一项实际复现初次订阅失败阻断启动，再改为后台重试。修复后 `cache-final-focused.log`专项60项通过，独立复审原P1解决、无剩余阻塞。
- MySQL仅通过既有 `initialize-backend-dev.ps1 -StartExisting` 恢复，无初始化/授权；WSL Redis真实PING通过。验收JAR用于现有脚本启动18081，PID34112、db/auth，API/Actuator health UP、匿名首页3条、beta open/未登录。`final-health.json`、`server-start.log`、`dev-counts-before/after.txt`记录帖子/评论/点赞/收藏/用户/Flyway行数未变；JWT沿用既有密钥。未连接3306、未清理.local或业务数据。
- 自动传播IT使用同JVM两份L1与独立Lettuce连接、真实Redis传输，先两次初次连接失败再自动恢复，验证广播及中断/恢复；另外两个独立服务JVM已实测通过，见下方补记。

## 最终性能实测

缓存class SHA256 `38f48736075e3f1f5aae7472d32da9355992d97006d570fc7eeadfab3a33e9a4`，与验收JAR及18081一致。仅在既有隔离测试库准备20帖，每帖10赞/10评论、无图片；同一JVM/构建/访客身份、相同URL和数据、root WARN、闭环并发1/4，所有模式共享同一批fixture。结束只软删本批帖子，没有往开发/业务库灌样例。

每路由/模式1000次全局预热，共6000，再每单元80次共3840。四轮轮换三模式顺序，每轮240样本，每热组960；冷组每请求前主动失效、各40。**11760个测量请求，0失败**。P50/P95合并原始请求计算最近秩；热吞吐用总样本/总墙钟；冷吞吐另含控制DEL/PUBLISH，HTTP延迟不含控制操作。完整18组平均/P50/P95/吞吐/失败、所有SQL/Redis次数及命中数、分段、逐轮变化见 `.local/l1-cache-20261115/perf-summary-final.{md,json}`；逐请求原始值 `perf-http-final.json`，命令 `perf-http-final-run.log`，环境/CPU/源码SHA `perf-environment-final.json`。

下表是平均/P95毫秒；每热格960、冷格40样本。P50和吞吐详见完整证据，不将这些本机数字称为生产容量。

| 请求/并发 | MySQL | Redis | Caffeine＋Redis |
| --- | ---: | ---: | ---: |
| 热单帖/1 | 3.105 / 4.981 | 3.204 / 4.813 | 2.397 / 3.812 |
| 热单帖/4 | 3.515 / 5.974 | 3.393 / 4.813 | 2.947 / 4.199 |
| 热列表/1 | 4.181 / 6.126 | 3.857 / 5.281 | 3.755 / 5.588 |
| 热列表/4 | 4.569 / 6.731 | 4.175 / 5.716 | 3.762 / 4.885 |
| 冷单帖/1 | 4.671 / 6.267 | 5.337 / 7.526 | 4.795 / 5.785 |
| 冷列表/1 | 5.937 / 10.633 | 12.426 / 15.848 | 9.949 / 11.786 |

每960次热详情，MySQL公共/个人/其它SQL为960/960/5760，两缓存为0/960/5760；热列表960/960/6720→0/960/6720。Redis每热组960次MGET；L1热详情及并发4列表MGET0，单并发热列表因2条自然到期，**MGET2、L1命中19198/miss2/L2命中2**，公共SQL仍0。不能夸成全段零回访。每40次冷列表，两缓存各40MGET、40批SQL回源(800ID)、800SET，公共/个人/其它SQL40/40/280，无N+1。控制用失效命令与计数读填分开记录。

Service含事务；JDBC只计实际execute，loader包括SQL/映射/Counts投影，Redis为模块Timer，这些有重叠不能全部相加。HTTP-Service残差含网络、过滤器/控制器/序列化、客户端读body和通用计数校验，未进一步逐项归因。

已确认：热单并发详情公共JDBC0.311ms（loader0.376），Redis读取0.554ms，替换很便宜的SQL可以产生额外成本；本轮Redis平均3.204略高于DB3.105。列表公共JDBC0.493（loader0.603）与Redis0.571相近，仍有每次个人状态及7次其它SQL，其它JDBC1.15—1.33ms。L1减少缓存访问，但单并发列表P95 **5.588高于Redis5.281**，不能只选均值。冷L1比冷Redis低也不能归因于L1：全部miss、都执行SQL和800SET，保留运行顺序/时间漂移。四轮仍有漂移，不宣称统计显著性。

逐Key回填确有成本，先测后决定：原始RESP批20顺序SET均值2.568ms、管道0.188、MGET0.151（100次/项、30预热）；但实际同一Spring StringRedisTemplate/Lettuce，200次/项交替、30预热，批20顺序SET均值/P95 **3.955/5.353ms**、Session管道5.281/17.421、RedisCallback管道4.528/15.137；单条SET0.427，管道约3.1ms。两种管道未改善平均和长尾，最终**保留原顺序SET**，未把试验管道当已验证优化。原始/框架差异见 `perf-redis-baseline.json`、`perf-template-redis.json`、`perf-connection-pipeline-run.log`。最终冷列表回填Redis5.569ms、L1模式4.298ms，请求均值12.426/9.949，均慢于DB5.937，这是已确认的冷缓存代价。

**历史12次样本额外14—16ms的根因仍未确认**。热命中没有SET，不能归咎逐Key回填；本轮已分开测Redis、公共SQL、个人/其它SQL、Service与HTTP，但历史缺同等分段。旧脚本PowerShell Invoke-RestMethod/3条开发数据/Mapper DEBUG，本轮Java HttpClient持久HTTP1.1/20条测试数据/root WARN，**旧40ms与新3ms绝对值不能用来宣称提速**。只比较最终三模式的相同条件。未把Redis、WSL或业务代码认定为历史原因；当前收益取决于热点复用、公共SQL成本与冷启动比例，保留关闭L1/总缓存能力。

复跑 `.local/l1-cache-20261115/perf-run.ps1`，内部显式 `-Dtest=PostMetricsPerformanceBenchmark test`，普通全套不会自动跑此基准。`perf-summary.py`接受输入输出参数，合并原始样本。CPU前1%/后3%只是快照，不代表全程资源受控；OS后台任务未完全控制。`perf-http-initial.json`、`perf-http.json`是中间管道试验构建，不混入最终统计。

## 已知边界与下一步

尚未验证生产规模/持续高并发、真实图片/登录身份的性能分布、跨主机时钟偏移、整个Redis重启或网络分区的长时混沌；读/填/删/广播分别故障及订阅恢复已有自动覆盖。这些不写成生产验收。后续处理版本栅栏/Canal，再做适当负载与Lettuce管道长尾来源专项；不为数字改无关查询或安全校验。MQ/AI顺序沿用既定专项，本轮未开发。

双JVM服务补记：通过 `.local/l1-cache-20261115/two-jvm-isolated.ps1` 在独立loopback18082/18083启动同一验收JAR，连接既有命名隔离测试库13306/Redis16379，使用原JWT密钥和测试库真实会话。两实例读暖同一匿名帖计数0，A经真实PUT点赞提交，B读取计数1；从暖读开始至B新值 **220.383ms**，断言小于1500ms且早于原L1的2秒过期，验证真实跨进程广播失效。访客/A/B的liked/favorited/isMine分别正确、公共计数共享，三者匿名author.id均为空。14项断言全部true，JAR SHA256与验收产物一致。证据 `two-jvm-results.json`、`two-jvm-cleanup.json`；本批测试帖子和用户仅在隔离测试库标记删除，两个自启JVM已停止、18082/18083已释放，18081保留运行。首轮因测试昵称超过既有列长度失败、尚未插入fixture，修正测试数据后通过，保留 `two-jvm-first-fixture-failure.json`，未改生产规则。

---
# 2026-11-15 发布页标签与发布身份补充

按用户本轮两张截图，以第二张为目标调整发布页：底部常显当前分类胶囊和“以当前昵称发布／匿名发布”胶囊，移除误导性的“所有人都可以回复”及重复图标入口；保留原分类picker和匿名切换，编辑身份锁定。单图改为内容通栏、16圆角，修正全局button规则导致的图片删除键宽度及图片入口热区。仅改`pages/forum-publish/index.{wxml,wxss,ts}`及`backend/pages.ts`中该页的selfNickname初始化/访客清理，没有改发布请求、草稿或匿名身份规则；这些TS及backend文件接手前已有修改，按增量保留。

新增`tests/publish-chips.test.cjs`三条回归，覆盖fixture/live标签与真实昵称/匿名文案、分类变化、编辑及发送锁定、访客身份清理；先观察缺失展示状态的失败，再实现。`apps/miniprogram`实际`npm run check` **335/335、0失败0跳过**，类型及工程检查通过。独立代码复核无阻塞。

已用现有微信开发者工具实际查看430原生空态、带图、匿名及长昵称状态；证据`.local/publish-chips-20261115/`中的`publish-empty-430.png`、`publish-filled-430.png`、`publish-anonymous-430.png`、`publish-long-name-430.png`及`native.json`（0捕获异常）。通过临时模拟选图返回本地既有desk.jpg、正常选图/删除事件验证单图通栏，没有保存或发布测试帖子。375、真实软键盘及live真实授权仍未实测；双模式逻辑有自动回归。未新增图片/组件/依赖、未提交推送。

---

# 2026-11-15 帖子详情、评论与私信视觉实施

按已确认七张设计图实施，设计目录只读，没有重新设计。本轮接手HEAD为`7fc1af4`；接手前85条Git状态及目标文件备份在`.local/detail-chat-20261115/git-status-before.txt`和`before/`。未提交、推送、安装依赖、新增组件或图片。

## D1—D9、C1—C8结果

以下路径相对`apps/miniprogram/miniprogram/`，行号为定位入口；全部适用项源码已实现，视觉验证边界见下。

| 项 | 文件 : 行 → 原值 → 新结果 |
|---|---|
| D1 | `components/forum-post-card/index.wxss:42,54` → 详情15/20 → 17/26；首页15/20保持，平板同步 |
| D2 | 同组件`index.wxml:8,10`、`index.wxss:64` → 裁切宫格 → 仅详情单列widthFix，16圆角、1描边、8间距；load/error/预览及续期事件保留 |
| D3 | 同组件`index.wxss:44` → 时间15 → 14/20，位置保持 |
| D4 | 同组件`index.wxss:46` → 计数15 → 14；44热区、20图标保持，详情转发中性灰且无计数 |
| D5 | `pages/forum-detail/index.wxss:18` → 纯文字 → 26高描边排序胶囊，12/600，无箭头 |
| D6 | `components/forum-comment/index.wxss:8`、`index.wxml:15` → 时间15/作者12/动作13 → 13/11/12.5；正文15/21、展开间距8、0赞显示“赞”，长昵称截断 |
| D7 | `pages/forum-detail/index.wxss:20`、`index.wxml:14` → 无线 → x36，从根头像底+4到末条二级头像中线止，遮罩避开回复文本及查看全部链接 |
| D8 | `pages/forum-detail/index.ts:13,56`、`index.wxml:25,36`、`backend/pages.ts:208` → 仅toast → 双模式内联失败/重试，保留正文及目标；编辑/切目标/取消/重开/成功复位；取消授权及参数校验、400等业务拒绝不显示网络失败。live实际POST处捕获，保留safe提示、锁与clientRequestId。占位随草稿高度，无新增高度data |
| D9 | 帖卡`index.wxml:4`、`index.wxss:71` → 无标签 → 仅匿名详情昵称旁“匿名发布”，身份映射保持 |
| C1 | `styles/chat.wxss:3`、聊天`index.wxml:4` → 灰底/旧字号 → 白底细线、标题16/700、left52、胶囊前8避让 |
| C2 | `styles/chat.wxss:16` → 旧灰及间距 → 11号#8B98A5、上下14/16 |
| C3 | `styles/chat.wxss:17`、聊天`index.wxml:11` → 旧灰气泡 → 对方浅底/己方墨底反白，18/6圆角、15/23、内距10/14、71vw上限；昵称节点隐藏，少量消息贴底 |
| C4 | 聊天`index.wxml:19,23`、`backend/pages.ts:471,518` → 输入栏上方全局发送中 → 单个临时气泡，不改已持久化消息状态；仅最新己方已发送。未保存浅红气泡/红色重试，raw DTO既有clientMessageId对账，GET先于POST响应或响应丢失均不重叠；消息模型不变 |
| C5 | `styles/chat.wxss:31`、聊天`index.wxml:27` → 透明禁用 → 输入44高、发送44圆墨底、禁用#C9CED4、双色placeholder；实际env安全区及adjust-position=false保留 |
| C6 | 聊天`index.wxml:26`、`index.ts:64`、`backend/pages.ts:502` → 单文字 → 46高横幅，仅明确本人blocked显示解除文案和已有设置入口 |
| C7 | `styles/chat.wxss:40` → 顶部空会话 → 可用视口居中，头像48、标题16/700、副文13 |
| C8 | `styles/chat.wxss:44` → 旧字号 → 状态标题16/700、副文13、墨色胶囊，原文案保持 |

## 文件归属与业务边界

本轮修改12个源码文件：`pages/forum-detail/index.{ts,wxml,wxss}`、`pages/contact-chat/index.{ts,wxml,wxss}`、`components/forum-post-card/index.{wxml,wxss}`、`components/forum-comment/index.{wxml,wxss}`、`styles/chat.wxss`、`backend/pages.ts`。两页TS和backend/pages.ts接手前已修改，本轮仅增量处理相关UI；其余9个源码文件本轮开始修改。`components/forum-post-card/index.ts`接手前已修改，本轮未动，与备份字节相同。交接本文也在原有修改上追加。新增`tests/detail-design.test.cjs`、`tests/chat-design.test.cjs`，共17条回归。

没有改Java、数据库、缓存、业务模型、HTTP契约、排序或匿名映射，没有改首页、发布页、联系列表、设置页、app.json、tokens或custom-tab-bar；这些文件若显示dirty，属于接手前改动。帖卡修改限定detail，评论组件仅详情使用；聊天样式限定contact-chat并清理重复覆盖。live历史分页仍请求beforeSequence，滚动、已读水位、头像导航/兜底、访客守卫保持。

## 实际验证

- `cd apps/miniprogram; npm run check`：**332/332，0失败0跳过**，typecheck及check:project通过。日志`.local/detail-chat-20261115/miniprogram-check.log`。新增回归覆盖双模式失败/保留/重试/复位、参数校验及取消授权、发送锁；真实WXML表达式待发/失败不重复、原消息状态、相同ID重试及实时落库竞态、live历史分页及授权守卫、详情/首页图片mode及事件绑定。既有匿名、访客、排序、图片续期回归同时通过。
- 项目JDK21.0.12.1（`.local/backend-day1/tools/jdk21/jdk-21.0.12.1+1`），子shell设置JAVA_HOME/PATH，在apps/server实际执行`mvn -B -Dxinkelink.build.directory=target/detail-chat-20261115 -DargLine=-javaagent:D:/develop/apache-maven-3.9.4/mvn_repo/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar test`：**205，0失败0错误0跳过，BUILD SUCCESS**。同证据目录`mvn-test.log`；没有改POM、删除测试或跳过断言。本轮未跑数据库集成。
- 已有微信开发者工具Stable v2.01.2510290实际启动、编译并渲染共享WXML/WXSS，未搭H5。CLI为`D:/develop/微信web开发者工具/cli.bat auto --project D:/CODE/XinKeLink/apps/miniprogram --auto-port 9420 --trust-project`；经批准访问工具自身.cli及自动化socket，重开项目清除模式缓存后完成最终fixture检查。`native-final.json`记录430×932、0捕获运行异常（不代表覆盖所有潜在异常）。
- 实际查看的430原生截图均在`.local/detail-chat-20261115/`：`detail-430.png`（多图）、`detail-anonymous-430.png`、`detail-comments-final-430.png`、`detail-failed-final-430.png`、`detail-image-failed-430.png`、`chat-final-430.png`、`chat-sending-final-430.png`、`chat-failed-final-430.png`、`chat-blocked-430.png`。由微信截图接口生成，未修改。待发、失败、屏蔽展示用UI状态注入，没有向真实服务发送测试内容；实际失败/重试路径由Node回归验证。
- `detail-layout.json`原生量得展开编辑器及同高占位均177px（含1px边线和设备34安全区），输入56；不再按最大高度额外预留84px。安全区未硬编码，失败增加22px；最小高度公式为60/142/164加实际安全区及边线。
- 独立复核发现并修复实时发送重叠、短草稿占位过高及图片失败空白风险，复审无剩余代码正确性阻塞。`git diff --check`通过（既有LF/CRLF提示保留）。

## 偏差与未验证

- 按交接保留“收起”文字和原生loading，不新增关闭/警示/排序/旋转图标；图片失败保留既有相机资源。评论原实现默认一条二级回复，设计图两条示例没有用来改变排序/展开规则。
- 交接同时要求rpx和430字阶、16边距不放大，直接冲突。本轮优先同尺寸视觉：聊天使用375逻辑px固定值（1rpx细线保留），>=500把气泡上限设为460内容列的71%=326.6px；未改首页字体或全局tokens。
- D7参考片段left=-8在真实父容器会落x52；本轮按16+44父偏移修正x36，并从thread根头像定位，末条及链接遮罩止线。编辑器增加aria-hidden的不可见同字体高度占位，避免固定栏遮挡末条内容，不改业务数据。
- **375原生截图未验证**：模拟器为430，CDP返回“domain Emulation not supported”；没有把缩放截图或H5当375验收。375基准及430固定尺寸已核对源码，仍缺375设备实测。
- **真实键盘弹出未验证**：`detail-keyboard-height-430.png`及`chat-keyboard-height-430.png`仅通过既有事件注入300高度检查布局，并非OS软键盘截图；真机键盘及极长草稿还需人工核验。
- **live业务页视觉未验证**：切换live后原生路由停在beta-access准入页，本机HTTP健康请求迟迟未完成（`native-live.json`）；没有绕过准入/认证或改网络配置。共享模板在fixture已实际编译渲染，live逻辑测试通过不等于live页面/真实授权通过。已恢复原mode存储并重开fixture，最终返回首页。

---

# 2026-11-14 首批Redis公共计数缓存交接（当前状态）

本节更新上一批交接，旧“未恢复真实环境”与旧后续开发顺序已被取代。上一批B01—B11、V10/V11和前端/后台改动在本轮开始时仍未提交，已原样保留；它们不算本轮新增源码成果。初始状态快照：`.local/cache-audit-20261114/git-status-before.txt`。本轮没有提交或推送，没有改界面、fixture行为、完整HTTP响应缓存或引入其它业务缓存。

## 缓存设计及代码边界

- 新增`apps/server/src/main/java/cn/xinkelink/forum/metrics/PostMetricsCache.java`：小模块只接收帖子ID及批量数据库loader，返回两个long计数。Redis key为`xinkelink:cache:v1:post-metrics:{postId}`，例如末尾直接数字`10596`；值是严格校验的`likeCount:commentCount`字符串。缺失/格式错误/负数/溢出视为miss，绝不缓存正文、作者、匿名真实身份、图片URL、游标、liked/favorited/isMine或权限。
- `ForumMapper.java`将旧混合批查拆为`publicPostMetrics(ids)`和`postViewerStates(ids,viewer)`；`ForumModels.java`增加对应投影，`ForumService.java`接入。Redis一次MGET，所有miss一次IN批量SQL回源；个人状态仍每次按当前viewer批量查询。列表、详情、搜索、收藏/点赞列表及公开作者页通过既有forum投影复用。缓存不会替代原有可见性、删除、隐藏、审核或会话校验。
- 默认TTL 20秒+0—10秒独立随机抖动，可配置；只缓存读路径，活跃写事务内完全绕过Redis读/填，保证读己之写且回滚不污染缓存。数据库异常透传，缺少计数/状态行报错，不拿虚构0冒充成功。
- Redis读取、回填、失效异常均不使公共计数业务失败，回源真实MySQL；现有认证限流`RedisAuthRateLimiter`的fail-closed规则未修改。关闭缓存只停止读/填，失效仍执行，支持重新开启。

配置在`application-auth.yml`，需要重启生效：

| 配置键 | 环境变量 | 默认 |
|---|---|---|
| `xinkelink.cache.post-metrics.enabled` | `XINKELINK_POST_METRICS_CACHE_ENABLED` | true |
| `xinkelink.cache.post-metrics.ttl-seconds` | `XINKELINK_POST_METRICS_CACHE_TTL_SECONDS` | 20 |
| `xinkelink.cache.post-metrics.jitter-seconds` | `XINKELINK_POST_METRICS_CACHE_JITTER_SECONDS` | 10 |

失效均注册到数据库事务**成功提交后**，回滚不删除：

| 写路径 | 文件/处理 |
|---|---|
| 帖子点赞、取消点赞 | `ForumService.likePost`，实际关系发生变化时失效 |
| 新增根评论、回复 | `ForumService.createComment`，失效所属帖子 |
| 删除评论/回复/根及其回复 | `ForumService.deleteComment`，实际删除时失效所属帖子 |
| 用户删除帖子 | `ForumService.deletePost`，连带评论删除后失效 |
| 后台帖子隐藏/恢复/删除 | `AdminCommandService`，失效对应帖子 |
| 后台评论隐藏/恢复/批量删除 | `AdminCommandService`，批量收集受影响帖子ID失效 |
| 举报治理隐藏帖子/评论 | `ReportModerationService.resolve`，失效对应帖子 |

评论数沿用原`PUBLIC_COMMENT`条件：根被隐藏/删除时，其回复不计入公共数；单个回复删除只减少可见回复。收藏和评论点赞不改变这两个公共数，不做无关失效。

一致性为“提交后主动失效＋TTL兜底”：并发旧读可能在删除后回填旧值，删除失败时也可能暂时保留旧key；旧回填完成后由20—30秒TTL过期修正。不是强一致，也不是从提交时刻起严格30秒上限。Canal/版本栅栏留后续专项。

## 本次源码与自动测试

本轮生产代码仅涉及：新增`PostMetricsCache`，修改`ForumMapper/ForumModels/ForumService`、`AdminCommandService`、`ReportModerationService`、`application-auth.yml`。`ForumService/Models`中原有七分类变更属于上一批，未重做。`ForumCategoryTest`只适配新增构造参数，保留原断言。`ForumServiceIT`、`ForumConcurrencyIT`、`Day4RealtimeIT`修正本轮真实运行发现的测试预期，具体说明如下。新增`PostMetricsCacheTest`、`PostMetricsProjectionTest`、`PostMetricsCacheIT`。完整真实验收还修正原有IT的三个预期：分类查询size=100违反原上限50，改50保留分类断言；公共计数与viewer分批后SQL预算10→11，保留1条与20条总SQL相等及非零断言；WebSocket首个read的会话/未读合法事件先明确验证消费，再保留重复read后两端无事件断言。没有改实时业务实现、删除测试或跳过安全断言。

- 小程序`npm run check`：**315/315，0失败0跳过**，tsc及工程检查通过；小程序源码本轮未改，fixture继续本地演示，live没有失败回退Mock。
- 后端`mvn -Dxinkelink.build.directory=../../.local/cache-root-build test`：**205/205，0失败0错误0跳过**；使用独立构建目录避免旧产物及并行构建覆盖。28项缓存单测验证miss/hit/批量、损坏值、Redis读填删故障、DB异常透传、配置开关、TTL范围、提交/回滚及写事务绕过；2项投影单测验证公共数与身份分离及隐藏权限。
- 真实缓存专项IT **14/14，0失败0错误0跳过**，采用MySQL13306专用`xinkelink_cache_*_test`库、真实Redis16379，包含真实TTL、真实session、访客/A/B隔离、匿名、隐藏/删除/写限制、提交失效、回滚读己之写、隐藏根及回复、后台/举报治理、批量无N+1及开关等价。最终完整mvn -Pdb-integration verify另运行205单测＋173真实DB集成测试，全部0失败0错误0跳过（173中包含14项缓存专项，不重复计数），2026-11-14 19:30:02 BUILD SUCCESS。
- 数据库静态契约仍为**11迁移、25当前业务表**，通过；本轮不新增迁移、不改V1—V11。apps/admin前端源码本轮未改，未重复其完整检查；上一批89条只是历史证据。

测试先观察缺失缓存行为的失败后实现。过程中修正过新测试的mock Long默认值、Redis execute重载歧义、真实媒体权限要求的session夹具；独立测试库授权曾被现有开发授权脚本的global REVOKE ALL移除，补回相同最小权限后继续运行，保留失败日志，没有关闭认证或放宽权限。

## 真实环境及迁移

- JDK **21.0.12.1**（项目`.local/backend-day1/tools/jdk21/`），PowerShell 7.6.9可用；恢复已有隔离MySQL **8.4.13/13306**，未连接未知3306、未重建/清空原库。
- Redis **7.4.11/16379**为既有WSL实例，WSL及Windows TCP RESP PING都返回PONG，loopback/protected mode。没有改认证限流故障策略。
- JWT使用已有受保护`.local/backend-day5/private/jwt.key`，未生成、替换或输出。凭据/备份目录ACL仅当前用户与SYSTEM，Git忽略。
- 原开发库迁移前使用一致性备份：`.local/backend-day1/private/cache-audit-20261114/pre-migration.sql`，另存SHA256；原Flyway V1—V9，现真实应用既有V10/V11。确认11成功迁移、25业务表、七分类CHECK、open阶段version2及25表运行账号权限；开发库25表行数前后无增减，内测阶段记录按V11更新。
- 服务端通过现有`start-backend-dev.ps1 -Build -Profiles db,auth`重建启动18081，使用本轮新JAR；`/actuator/health`、`/api/v1/health`均UP，匿名`/api/v1/posts`返回3条可公开帖子，`/beta/access`为open且未登录未激活可浏览。actuator原配置不包含Redis，不能用其UP冒充Redis验证，Redis另有真实PING。
- 专用测试库仅复制表结构、系统设置和迁移历史，未复制开发库用户/帖子；IT在该库迁移10/11。测试使用独有UUID用户、帖子和真实session；按最小运行权限只软删除自测帖子、禁用自测管理员，保留测试审计/通知，不删除不可变审计记录。测试库保留，未清理`.local`。

启动/检查环境脚本及证据位于`.local/cache-audit-20261114/`，不要打印`runtime.json`、client.cnf、JWT或子进程环境。测试只在子shell点源`test-environment.ps1`后执行；脚本已被Git忽略。现有`initialize-backend-dev.ps1 -GrantRuntimePermissions`有全局REVOKE行为，共用账号测试库授权会受影响，不要在测试进行中重跑；本轮没有扩大开发库运行账号权限或修改该脚本。

## 实测SQL与耗时（观察值，不是提速承诺）

真实开发HTTP使用同一新JAR、相同路由、访客身份和相同公共数据，每种开关每条路由12次，预热后记录；方法级DEBUG仅用于计数SQL取证，不记录正文或身份。结果公共计数与个人标记相同。

| HTTP请求 | 开关 | 公共计数SQL/12次 | 平均耗时ms |
|---|---|---:|---:|
| `/api/v1/posts?size=20` | off | 12 | 48.01 |
| 同上 | on（热缓存） | 0 | 57.23 |
| `/api/v1/posts/10596` | off | 12 | 26.62 |
| 同上 | on（热缓存） | 0 | 42.34 |

真实MySQL+Redis的Service集成测试另对固定目标各30次，冷缓存开始；拦截器统计真正执行的公共聚合SQL，结果逐次相等：

| Service调用 | 开关 | 公共计数SQL/30次 | 中位数ms | P95 ms |
|---|---|---:|---:|---:|
| detail | off | 30 | 9.067 | 26.880 |
| detail | on | 1 | 11.557 | 23.870 |
| list | off | 30 | 10.886 | 25.022 |
| list | on | 1 | 13.989 | 26.322 |

固定目标启用缓存时30批中仅1批回源（由SQL计数观测，29/30批命中）；这不是生产全局命中率。公共计数SQL减少确定，HTTP整体耗时在此Windows/WSL小样本下反而更高，Service耗时接近，不能推导显著性能提升或生产P95。原始证据：`http-cache-off.json`、`http-cache-on.json`、mapper stdout快照，以及`.local/cache-it.log`。

## 最终验收命令与证据

在子进程PowerShell中点源`.local/cache-audit-20261114/test-environment.ps1`，仅设置隔离测试连接和JDK21，不打印环境变量；然后在apps/server运行：

```powershell
mvn -B '-Dxinkelink.build.directory=target/cache-full-it' -Pdb-integration '-DargLine=-javaagent:D:/develop/apache-maven-3.9.4/mvn_repo/net/bytebuddy/byte-buddy-agent/1.18.11/byte-buddy-agent-1.18.11.jar' verify
```

此显式agent只用于测试JVM。恢复后的受限执行上下文曾拒绝Mockito动态attach管道和本机socket访问；最终经自动审批允许本机数据库/Redis测试后完成整轮验收，没有改生产POM、关闭认证、删除或跳过测试。首轮原IT预期失败、授权恢复失败、受限attach失败及最终成功日志均保留。

最终证据目录`.local/cache-audit-20261114/`：`full-db-verify-final.log`、`final-test-counts.json`（205/173均零失败错误跳过）、`final-health.json`、`dev-migration-verification.txt`、开发库前后25表行数、JAR SHA256和HTTP对比JSON。缓存14项专项另见`.local/cache-it.log`。采样结束后已恢复默认日志，18081服务保持缓存开启；测试结束未再重启或重授权限。root在权限切换后也重新实际验证三个HTTP接口均200，actuator/API健康UP、公开帖子3条。
## 后续主线与边界

首批Redis缓存 → Caffeine多层缓存 → Canal一致性 → RocketMQ可靠消息 → Kafka行为统计与热榜 → 故障演练、性能对比和首版上线 → **Python＋LangGraph＋ChromaDB AI专项**。未来AI为校规知识库问答、自然语言查找帖子及二者联合查询，**不再采用Spring AI**。下方旧开发顺序仅供历史记录。

本批未做公告/首页整包缓存、Caffeine、Canal、消息队列或AI。微信真实授权链路未以开发测试账号代替验收；下一步按后续主线推进，并在代表性数据量/网络下继续对比缓存收益。

---

# 2026-11-14 本轮接手结果（B01—B11及开放准入）

本节是最新状态，下方为基线审查存档，旧缺陷描述及旧测试数量不能作为当前结论。接手HEAD为`7fc1af4`，工作区干净；没有覆盖用户修改，没有提交或推送。本轮未重设界面、未做大规模重构、未扩展交易及真实转发。

逐项根因、行为及状态见[BUGS.md当前修复表](../BUGS.md)，当前流程见[business-flows.md开头](./business-flows.md)。B01—B11均已完成源码修复与自动回归；B06数据库约束、open准入需在真实环境应用V10/V11后验收，不能理解为真实业务联调已通过。

## 本次实际验证

| 命令（各应用目录执行） | 结果 | 验证边界 |
|---|---|---|
| 小程序 `npm run check` | **315/315，0失败、0跳过**；tsc与check:project通过 | 同时覆盖fixture与live行为。Node harness的live请求是HTTP边界桩，不是真实后端；不编译WXML/WXSS。 |
| 后端 `mvn test` | **175/175，0失败、0错误、0跳过**，BUILD SUCCESS | 单元测试，不启动MySQL/Redis/微信；新增IT已编译，未运行。 |
| 后台 `npm run check` | **89/89，16测试文件**；vue-tsc与Vite生产构建通过 | 分类映射/服务契约；有现存大包提示，不影响构建。 |
| `pwsh -NoProfile -File apps/server/src/test/resources/schema-contract.ps1`（仓库根） | 通过：**11迁移，25当前业务表** | 纯静态检查，未连接数据库。 |
| `git diff --check` | 通过 | 未修改V1—V9历史迁移。 |

小批次先新增回归并观察失败，再修改实现；中间全量失败主要来自旧测试依赖“空会话默认登录”，已改为显式session=true，并保留访客安全断言。后续复核额外捕获并修复：搜索切换后旧互动响应污染新结果、live发布入口误用fixture守卫、确认弹窗期间退出后继续请求、迟到私人响应恢复已退出数据、fixture聊天退出刷新未清空页面。所有这些场景均有回归。

fixture验证：空会话/false/损坏/读取异常，深链、退出刷新、私人按钮与实际操作、聊天搜索/偏好/清空、个人匿名列表隔离、登录广播/reLaunch、历史草稿重登恢复、匿名编辑、新建/编辑多草稿并存、取消/失败重试、举报重复提交、搜索切换/保存失败、转发零写入。

live验证：现有requireSession授权续接、取消/失败/Promise异常、私人页守卫与迟到响应丢弃、确认后身份复验、发布编辑PATCH匿名不变、草稿按用户隔离、搜索分页及互动代次、转发不调用favorite、open匿名首页启动。上述是前端请求边界验证；真实微信登录、数据库迁移与真实HTTP/WSS尚未验收。

## 变更文件与归属

小程序路径下列相对`apps/miniprogram/miniprogram/`：

- 身份/入口：`features/profile/repository.ts`、`components/forum-post-card/index.ts`、`components/page-header/index.ts`、`components/app-shell/index.ts`、`pages/contact/controller.ts`、`pages/contact-chat/index.ts`、`pages/profile-edit/index.ts`、`pages/user-home/index.ts`。
- 私人页面：`pages/chat-settings/index.ts/.wxml`、`pages/profile/index.ts/.wxml`、`pages/my-posts/index.ts/.wxml`、`pages/my-collections/index.ts/.wxml`。WXML只补权限条件，未改布局/样式。
- 历史/草稿/编辑/搜索：新增`features/profile/private-storage.ts`、`features/forum/drafts.ts`，修改`features/search/history.ts`、`features/forum/repository.ts`、`pages/forum-publish/index.ts`、`pages/drafts/index.ts`、`pages/ai/index.ts`、`pages/search/index.ts`。
- 双模式桥接/保护：`backend/pages.ts`、`backend/service.ts`、`pages/report/index.ts`、`pages/feedback/index.ts`、`app.json`。
- 服务端：`ForumModels.java`、`ForumService.java`、`AdminQueryService.java`、`BetaService.java`，新增`V10__forum_categories.sql`、`V11__open_access.sql`。新增/更新`ForumCategoryTest`、`BetaInitializationTest`、`ForumServiceIT`、`AdminHttpIT`、`BetaAccessIT`、`DatabaseMigrationIT`及`schema-contract.ps1`。
- 后台：`src/services/admin-models.ts`、`admin-adapters.ts`、`src/views/PostManagementView.vue`及新增`tests/forum-categories.test.ts`，扩分类契约与筛选选项。
- 授权：`scripts/initialize-backend-dev.ps1`运行账号授予前的表数门槛27→25；保留迁移账号/运行账号隔离及审计表限制。
- 测试：新增`business-identity-fixes`、`privacy-page-guards`、`private-draft-search`、`async-privacy`、`open-startup`五个小程序测试文件；更新backend-pages及受默认会话、转发/首页规则影响的原测试，未删测试。文档同步`README.md`、`BUGS.md`、`IDENTITY-RULES.md`及本文件/业务流程文件。

## 数据库与环境操作

新增V10仅扩`xl_post.ck_post_category`到七分类，保留study，不恢复商品表/字段。新增V11将已有beta阶段改open并递增版本，保留内测码与用户绑定；初始化也使用open。数据库仍25业务表，共11迁移。静态契约同步授权脚本25表门槛；真实权限断言保留在DatabaseMigrationIT。

本轮仅探测环境并运行编译/测试，没有初始化或迁移实际数据库，没有授予实际账号权限，没有启动/关闭服务，没有清理`.local`/数据库/用户存储，没有连接3306，没有输出密钥。复核时13306、16379、18081均无监听；进程环境`XINKELINK_JWT_SECRET_BASE64`不存在。当前工具环境可找到pwsh（由Codex运行时提供），因此历史“本机缺pwsh”不能直接复用。端口配置仍统一18081。

## 下一位agent可直接执行

1. 配置隔离MySQL13306、Redis16379和必要JWT/微信凭据（只在受保护配置，不输出秘密）；沿用现有脚本和最小权限运行账号，不使用未知3306。
2. 正常执行Flyway V10/V11，检查25表、11迁移、分类CHECK、open阶段与运行账号权限；运行`mvn -Pdb-integration verify`，重点验收七分类创建/编辑/筛选、后台筛选和V11保留绑定。不要用`mvn test`的175条代替DB集成验收。
3. 使用真实`db,auth`配置启动18081，验证health、匿名首页/无需内测码、登录/退出、私人页、匿名编辑、举报反馈和偏好、分页竞态、封禁及限流；微信开发者工具编译并人工核验共享WXML与真机行为。
4. 真实业务联调完成后依既定顺序：Spring AI → 多层缓存 → Canal → RocketMQ可靠消息 → Kafka行为统计与热榜 → 故障恢复、性能对比与上线。真实转发仍是独立待办；其它历史审查建议未在本轮顺手实现。

---

# 信科 Link · 项目全貌说明（交接用）

> 生成时间：2026-11-14（刷新版）。事实来源：2026-11-13/14 对 `apps/miniprogram`、`apps/server`、`apps/admin`、Flyway 迁移、配置与脚本的**全量只读代码审查**（前端 41 个测试文件 / 后端 50 个测试类），不是凭记忆写的。
> 用途：交给任何 AI / 新人，读完即可接手开发与排查。
> 配套文档：**业务流程逐条走查见 [business-flows.md](./business-flows.md)**；演示模式缺陷清单见 `../BUGS.md`；身份规则审查见 `../IDENTITY-RULES.md`。

---

## 0. 一句话与仓库结构

**信科 Link** 是一个校园社区小程序：发帖 / 评论 / 点赞 / 收藏 / 私信 / 通知 / 搜索 / AI 写作助手，界面按 X（Twitter）风格黑白化复刻。三端组成：

```
D:/CODE/XinKeLink
├─ apps/miniprogram   微信小程序（用户端，原生小程序 + TypeScript）
├─ apps/admin         管理后台（Vue 3 + Element Plus）
├─ apps/server        服务端（Spring Boot 4.1.1 / Java 21 / MySQL 8.4 / Redis）
├─ docs/              设计稿、方案、验收记录、本交接文档
├─ scripts/           后端隔离环境初始化与启动脚本（PowerShell）
├─ BUGS.md            演示模式只读审查报告（6 条，1/2/3 已修复）
├─ IDENTITY-RULES.md  身份判定审查报告与规则表
└─ .workbuddy/memory/ 工作日志（memory/*.md，含历次踩坑记录）
```

**当前 git 状态**：11 个提交，最新 `bd4bf88 style: cap feed single-image height to 4:3`，工作区干净。**仓库无 remote**（`git remote -v` 为空），所有提交只在本地。

---

## 1. 微信小程序前端（apps/miniprogram）

### 1.1 技术栈与工程约定

- **原生微信小程序 + TypeScript**（无 uni-app/Taro），`project.config.json` 开启 typescript 编译插件，基础库 3.15.0，appid `wxda2ce56da7fced57`。
- 样式统一 **px@375 基准**（已废弃 rpx），主色 `#0F1419`（墨黑），旧蓝 `#1d9bf0` 仅存于个别功能色（爱心粉红 `#f91880` 保留）。`styles/tokens.wxss:1-4` 注释："数值口径是 375 逻辑宽（设计稿 px），WXSS 里统一 ×2 写成 rpx；≥500px 的窄平板按 px 还原"。
- 图标全部由 `scripts/generate-x-icons.cjs` + `generate-icons.cjs` 生成到 `miniprogram/assets/icons/`（lucide-static SVG → @resvg/resvg-js 渲染 72px PNG），**不要手改 PNG**，改 variants/aliases 表后重新生成。
- 命令：`npm run typecheck`（tsc --noEmit）、`npm test`（先 tsc 到 `.test-dist` 再跑 node:test，**260 条全绿，41 个测试文件**）、`npm run check:project`（工程一致性静态断言）、`npm run check`（三者串联）。
- `scripts/check-project.cjs` 只做静态 JSON 断言（compileType、typescript 插件、tabBar.custom、tabBar 5 项、每个 page 的 ts/json/wxml/wxss 四件套存在等），**不编译 WXML/WXSS**（脚本自述 `:35-36`）。

### 1.2 双轨运行模式（理解本项目的钥匙）

每个页面文件 `index.ts` 保留一份**完整的本地实现**（MOCK_POSTS + 本地 storage），由 `backend/pages.ts` 的 `registerPage(wx, Page, name)`（`:17-21`）决定走哪套：

- **演示模式**（`wx.__fixtureMode === true`）：`backend/demo-mode.ts:36-38` 在模块顶层自动开启（`platform === 'devtools'` 时默认开；`wx.setStorageSync('xinkelink.mode', 'live'|'local')` 可强制切换）。`registerPage` 直接 `nativePage(fixture)`，**不注入 `common`** → **不启动 Java 服务也能跑全功能**，数据存本地 storage。
- **后端模式**（真机 / mode=live）：`definition = { ...fixture, ...common, ...implementation }`（`backend/pages.ts:522`）合并 —— `common`（`:73-146`）提供 onShow/reload/资料确认弹层/图片续期/头像 error 兜底等公共行为，`definitions`（`:147-426`）覆盖业务方法，`data` 合并顺序 `fixture → pageData → implementation`。合并后若没有 `reload` 会自动补空实现（`:523-525`，防止 `common.onShow` 抛 TypeError）。onLoad/onShow 还会被包一层 beta 门禁 + 图片授权续期（`:527-556`）。

**两个必须记住的坑**：

1. **wxml 是两套模式共用的**。改 wxml 绑定的方法时，必须确认 fixture 与 implementation 两边都有（历史上多次出现"演示模式死链"，见 `BUGS.md`）。
2. **演示模式不注入 `common`**，所以每个演示页面必须**自备**：`onPullDownRefresh` / onShow 重载 / 头像 error 兜底 / 访客守卫 / `profile-auth-sheet` 的两个 handler（当前仍缺，见 §7.3）。

### 1.3 页面清单（app.json 注册 19 个，`app.json:2-22`）

| 页面 | 功能 | 备注 |
|---|---|---|
| `pages/beta-access/index` | 内测门禁/激活码 | app.json 首页（测试有断言，勿改顺序）；演示模式直接 switchTab 进论坛 |
| `pages/forum/index` | 首页 feed | 8 板块 tab（推荐/最新/失物认领/二手闲置/校园生活/吐槽/互帮互助/找搭子），PAGE_SIZE=2 本地分页 |
| `pages/contact/index` | 私信 + 通知双 tab | 访客显示引导空态 |
| `pages/profile/index` | **我的主页** | 动态/获赞统计 + 帖子/喜欢双 tab；访客态显示登录引导条；**非 tabBar 页** |
| `pages/forum-detail/index` | 帖子详情 | 两级评论、按热度/按时间排序、底部回复栏 |
| `pages/forum-publish/index` | 发帖/编辑 | 支持草稿、匿名 chip、editId 编辑 |
| `pages/contact-chat/index` | 聊天页 | 消息序号、已读、清空历史、发送锁 |
| `pages/my-posts/index` | 我的帖子 | 含匿名帖管理、编辑、删除 |
| `pages/my-collections/index` | 喜欢（点赞列表） | 页面名叫"喜欢"，走的是点赞接口 |
| `pages/profile-edit/index` | 编辑资料 | 头像持久化到 USER_DATA_PATH；有访客守卫 |
| `pages/user-home/index` | **公开主页** | 他人视角：简介 + 公开帖子数/获赞 + 分享·私信 + 帖子单 tab；本人+登录才有"编辑"按钮 |
| `pages/search/index` | 搜索 | 搜索历史 + 当周热榜（点赞前 5） |
| `pages/report/index` | 举报 | 支持 post/comment/user（演示模式本地存储） |
| `pages/chat-settings/index` | 聊天设置 | 置顶/免打扰/清空/屏蔽 |
| `pages/feedback/index` | 意见反馈 | 演示模式存本机，真机走后端 |
| `pages/notifications/index` | 通知 | 与 contact 共享 controller |
| `pages/ai/index` | Link AI | **本地写作助手**（`features/ai/draft.ts`），确定性整理不联网，免费不限次 |
| `pages/drafts/index` | 草稿箱 | 已注册、**无任何入口** |
| `pages/new-chat/index` | 发起私信 | 聊过的同学列表 |
| `pages/login/index` | — | **未注册 = 死代码**（`pages/login/index.ts:3` 仍调 `registerPage(...,'login')`，但 app.json 无此页；`tests/inline-login.test.cjs` 断言"old login route is unregistered"） |

**tabBar**（`app.json:30-57`）：`custom: true`，5 项 = 首页 / 搜索 / Link AI / 通知 / 私信，与 `custom-tab-bar/index.ts` + `utils/navigation.ts:2-8` 的 `TABS` 一致。

### 1.4 组件清单（6 个）

| 组件 | 职责 |
|---|---|
| `app-shell` | 右滑抽屉 = 设置中心：账号卡、通知偏好开关、反馈、关于、退出登录；内嵌 `profile-auth-sheet` 登录弹层；管理 tab 位移与滑动手势 |
| `page-header` | 各页左上角顶栏：头像按钮（打开抽屉）、标题、搜索态、右插槽（避让微信胶囊）；**订阅身份广播**实时刷新头像/访客态 |
| `forum-post-card` | 帖子卡片（列表/详情/主页三种形态）：头像点击分流、点赞/转发/收藏、"⋯"菜单（本人有编辑/书签） |
| `forum-comment` | 评论行：赞/回复/删除/展开 |
| `profile-auth-sheet` | 微信头像昵称授权弹层（chooseAvatar + nickname input） |
| `back-header` | 二级页返回头 |

`custom-tab-bar/` 为 tabBar 组件（含发布 FAB + 未读角标；**聊天 tab 的 FAB 已于 2026-11-13 移除**）。

### 1.5 核心机制（改代码前必读）

1. **身份事件广播**：`features/profile/identity-events.ts`（模块级订阅表）。`repository.ts` 的 `saveProfile` / `setLocalSession` 写入后自动 `emitIdentityChange()`，覆盖抽屉登录、访客一键登录、退出、资料编辑全部入口。`page-header` 与 `custom-tab-bar` 在 `attached` 订阅 + 立即刷新；真机侧另订阅 `ApiClient.onSessionChange`（`setMe`/`clear` 触发）。**没有它，页面内登录后顶栏不刷新**。
2. **头像持久化**：`features/profile/avatar.ts`。`persistProfileAvatar` 把 chooseAvatar 的临时路径复制到 `USER_DATA_PATH/xlk-profile-avatar-*`；`repairTransientAvatar` 在 `app.ts onLaunch` 自愈历史临时路径（失效则回退内置头像）。
3. **帖子头像跳转口径**（`forum-post-card.onAuthorTap` `:77`）：匿名/无 id → 打开帖子；**本人 + 已登录 → `/pages/profile/index`（我的主页）**；访客或别人 → `/pages/user-home/index?id=xxx`（公开主页）。公开主页的编辑按钮要求 `isSelf && loggedIn`（wxml:27）。
4. **图片授权链**（真机）：上传 `POST /images` → 展示走 `media()` 拼接 `/images/{id}/content?grant=`（grant 为 HMAC+AES-GCM 签名，**60 秒有效**）→ `PageImageRenewal` 在页面可见时自动续期，图片 `binderror` 触发重取。`backend/preview.ts:105` 明确**不改写用户正文**里的图片 URL。
5. **转发功能**：后端无 `/posts/{id}/repost` 接口，真机侧 `backend/pages.ts:32` 对 `reposted` 显式拦截并提示"转发功能即将上线"，**不要把它退回成收藏写请求**。
6. **乐观锁**：写操作带 `expectedVersion`（body）或 `If-Match: "N"`（header），409 冲突需刷新重试。
7. **幂等键**：发帖/评论/发消息/举报/反馈都带 `clientRequestId` / `clientMessageId`。

### 1.6 图片尺寸机制（2026-11-14 新增规则）

- 容器高 = 宽 × `singleImagePadding%`，由 CSS 变量 `--post-image-padding` + `::before padding-top` 实现（`features/media/layout.ts`）。
- **列表形态（home）**：`boundedImageRatio(w/h)` 把比例夹取到 **[4/3, 1.5]**，即高 ≤ 0.75×宽；超出部分 `aspectFill` **居中裁剪**。
- **详情形态（detail）**：用 `naturalImagePadding` 完全按原图比例，**不裁剪**，可点击预览。
- 多图宫格不受影响；圆角/边框/间距未改。
- 测试断言同步在 `tests/home-presentation.test.cjs`（期望值 125 → 75）。

### 1.7 身份模型（2026-11-13 起，核心改造）

**唯一判定源**：`features/profile/viewer.ts` 的

```ts
getViewerRole(runtime, authorId?) => 'guest' | 'self' | 'other'
```

- fixture 分支：`readLocalSession` 为假 → `guest`；否则 `authorId === LOCAL_AUTHOR_ID('local-student')` → `self`，否则 `other`。
- 后端分支：`api.loggedIn` 为假 → `guest`；否则 `authorId === api.me?.id` → `self`，否则 `other`。
- **任何读取失败一律 `guest`**（安全侧回退）。
- `authorId` 为 undefined：guest 分支 → guest；已登录 → other（`viewer.ts:34` 注释）。

**访客 = 干净旁观视角**（读隔离 + 写拦截）：

| 侧 | 实现 |
|---|---|
| 读隔离 | `features/forum/repository.ts:34-37`：访客 `readForum` 返回空 interactions + 空 `commentState.likes`；`custom-tab-bar/index.ts:52` 访客角标三项清零 |
| 写拦截 | `features/forum/repository.ts:47,71`：`togglePostInteraction` / `toggleForumCommentLike` 首行 `requireLocalSession` → 弹"一键登录"且**不落盘** |
| 入口拦截 | `pages/new-chat/index.ts` onShow 访客拦截并弹登录；`pages/contact-chat/index.ts` `refreshConversation` 首行访客拦截（**不读历史、不写已读标记**，用 `_guestPrompted` 只弹一次） |

**身份切换 reLaunch 规则**（用户明确要求的业务规则）：

| 场景 | 演示模式（`features/profile/repository.ts:68-79`） | 真机模式（`backend/client.ts:44-53`） |
|---|---|---|
| 访客 → 已登录 | **reLaunch `/pages/forum/index`** | **不跳**，原地刷新（登录流程有各自目的地，跳转会摧毁进行中的 beta 门禁流程页） |
| 已登录 → 访客（退出） | **reLaunch `/pages/forum/index`** | **reLaunch `/pages/forum/index`** |
| 资料保存 / 头像更新 | 不跳（`saveProfile` 只 `emitIdentityChange`） | 不跳（`setMe` 广播，`lastNotifiedLoggedIn` 不变） |
| 静默续期 | — | 不跳（`refresh()` → `save()`，登录态不变） |

实现方式：`setLocalSession` 比较**切换前后**登录态，**只有变化才 reLaunch**（已在首页也要 reLaunch）；真机侧 `client.changed()` 用 `lastNotifiedLoggedIn` 比较，**只在 true→false（退出）方向跳**。

**访客抽屉身份**：`components/app-shell/index.ts` fixture 退出分支补 `this.refreshIdentity()`，抽屉身份卡复位为默认头像 + "访客"。

### 1.8 前端调用的后端接口

`backend/client.ts` 的 `ApiClient` 负责：微信 code 登录、refresh 轮换、401 自动重试、multipart 上传、会话持久化（storage key `xinkelink.backend.session.v1`）。基址 `backend/config.ts:2` 的 `BACKEND_ORIGIN = 'http://127.0.0.1:18081'`。

小程序端调用（`/api/v1` 前缀省略）：`/auth/wechat|refresh|logout`、`/me`(GET/PATCH)、`/me/preferences`、`/me/summary`、`/me/posts`、`/me/favorites`、`/me/likes`、`/me/unread`、`/me/conversations/read`、`/me/notifications/read`、`/me/blocks/{userId}`、`/posts`(GET/POST)、`/posts/{id}`(GET/PATCH/DELETE)、`/posts/{id}/like|favorite|comments`、`/comments/{id}`(DELETE)、`/comments/{id}/like|replies`、`/search`、`/users/{id}`(+`/posts`)、`/conversations`(GET/POST)、`/conversations/{id}`(+`/messages|read|settings|clear`)、`/notifications`(+`/{id}/read`)、`/announcements`(+`/{id}`)、`/images`(POST)、`/images/{id}/access|content`、`/reports`、`/beta/access|redeem`、`/feedback`、WS `/ws`。

### 1.9 本地 storage key 清单（19 个）

| key | 内容 | 按用户隔离？ | 访客可读/可写 |
|---|---|---|---|
| `xinkelink.mode` | 演示模式开关 | 否 | 可见（公开读） |
| `xinkelink.session.v1` | 演示登录态（boolean） | 否（设备级单账号） | 可见；可写 |
| `xinkelink.profile.v1` | 昵称/头像/简介/专业/班级/学号/年份 | 否 | **可见**（兜底返回默认"陈林舟"）；可写 |
| `xinkelink.preferences.v1` | 四类提醒开关 | 否 | 可见；可写（**fixture 分支不判登录**） |
| `xinkelink.forum.posts.v2` | 本机发布的帖子 | 否 | **可见**（合并进所有 feed） |
| `xinkelink.forum.interactions.v1` | 帖子赞/藏/转 | **读隔离**（访客读为 `{}`） | 访客读不到；写被拦 |
| `xinkelink.forum.comments.v1` | 评论新增/删除/赞 | 部分（`likes` 访客读为空） | `added`/`deleted` 访客可见 |
| `xinkelink.contact.v1` | 会话消息/已读/清空水位 | 否 | **访客读隔离**（chat 页直接 return） |
| `xinkelink.contact.settings.v1` | 免打扰/置顶/屏蔽 | 否 | 正常入口不可达，**深链可达** ⚠️ |
| `xinkelink.notifications.v1` | 已读通知 id | 否 | 访客不可达通知页 |
| `xinkelink.search.history.v1` | 搜索历史（≤8 条） | 否 | **访客可见 + 可写** ⚠️ |
| `xinkelink.search.entry` | `'posts'` | 否 | **只写不读（死 key）** |
| `xinkelink.post.draft.v1` | 发帖草稿 | 否 | **访客可见** ⚠️ |
| `xinkelink.feedback.v1` | 本机反馈（≤50 条） | 否 | **访客可写** ⚠️ |
| `xinkelink.reports.v1` | 本机举报 | 否 | **访客可写** ⚠️ |
| `xinkelink.backend.session.v1` | JWT 会话对象 | ✅ 按 JWT | 真机模式 |
| `xinkelink.private.student-id.v1` | 学号（**仅本机**） | ✅ 设备级私有 | 真机模式，不进公开主页 |

⚠️ 标记项 = 尚未按访客隔离的遗留（见 §7.3）。

### 1.10 前端测试

`tests/*.test.cjs` 共 **41 个文件 / 260 条用例**（node:test）。两个自研 harness：

- `tests/page-harness.cjs:7-46` `loadPage(relativePath, {wx, fixture=true, betaPhase})` —— 从 `.test-dist/` 读编译产物，`vm.runInNewContext` 注入 `Page()`/`wx`/`getCurrentPages`；`betaPhase:'open'` 注入假的 `/api/v1/beta/access`。
- `tests/native-harness.cjs:6-27` `loadComponent(relativePath, {wx, pages, fixture})` —— 捕获 `Component()`。

用例数最多：`beta-access.test.cjs` 30、`inline-login` 16、`backend-pages` 16、`v1-profile-pages` 14。2026-11-13/14 新增 5 个：`viewer-role`(8)、`identity-relaunch`(6)、`guest-interactions`(4)、`demo-mode-fixes`(4)、`guest-guard`(2)。

---

## 2. 服务端（apps/server）

### 2.1 技术栈与模块

Maven 单体，Java 21，Spring Boot 4.1.1，MyBatis-Plus 3.5.17，Flyway（V1–V9），Spring Security + OAuth2 Resource Server（JWT），spring-data-redis（Lettuce），WebSocket，springdoc（dev 才开）。图片存储默认本地落盘（`LocalImageStorage`），可切阿里云 OSS（`xinkelink.storage.provider=oss`）。

模块包（`cn.xinkelink.*`）：`auth`（登录/JWT/限流）、`forum`（帖子评论点赞收藏）、`content`（发现/搜索/公共鉴权分页工具）、`messaging`、`notification`、`profile`、`media`、`beta`、`operations`（公告/通知任务）、`moderation`（举报/处置/封禁）、`feedback`、`admin`（后台聚合）、`audit`、`settings`、`realtime`、`storage`、`health`、`common`（ApiResponse/错误码/游标/全局异常）。

**除 HealthController 外所有 Controller 都标 `@Profile("auth")`** —— 不激活 auth profile 时业务接口全部不存在。

### 2.2 接口全集（16 个 Controller）

统一响应 `ApiResponse{ code, message, data, errors[], requestId, timestamp }`；错误码 39 个（`common/api/ErrorCode.java:5-52`），如 `UNAUTHENTICATED / TOKEN_EXPIRED / REFRESH_INVALID / VERSION_CONFLICT / PRECONDITION_REQUIRED / DUPLICATE_REQUEST_CONFLICT / BETA_ACCESS_REQUIRED / RATE_LIMITED / ACCOUNT_BANNED / ACTION_RESTRICTED`。

**认证（公开）**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/wechat` | 微信 code 换 token 并 upsert 用户；限流 10/60s/IP |
| POST | `/api/v1/auth/refresh` | 刷新令牌（轮换，旧票据重放 → REFRESH_INVALID）；30/60s/IP |
| POST | `/api/v1/auth/logout` | 吊销**当前这一个会话**（其它设备不受影响） |

**管理员认证（公开，Cookie + CSRF + Origin 白名单）**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/admin/auth/login` | BCrypt(12) 校验；refresh 走 `xinkelink_admin_refresh` HttpOnly Cookie（path=/api/v1/admin/auth, secure, sameSite=Strict）+ `X-CSRF-Token`；5/60s（IP+账号双键） |
| POST | `/api/v1/admin/auth/refresh` / `logout` | 轮换（同时轮换 CSRF）/吊销 |
| GET | `/api/v1/admin/me` | 当前管理员 |

**论坛**（`forum/ForumController.java`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/posts` | feed（category/sort/keyword/cursor/size），公开，签名游标 |
| POST | `/api/v1/posts` | 发帖 `{content≤2000, category, imageIds≤9, anonymous, clientRequestId}`；**幂等**（`uk_post_request` + `request_hash`） |
| GET/PATCH/DELETE | `/api/v1/posts/{id}` | 详情（公开）/ 编辑（body expectedVersion）/ 删除（header `If-Match: "<version>"`） |
| GET | `/api/v1/me/posts` | 我的帖子 |
| PUT/DELETE | `/api/v1/posts/{id}/like`、`/favorite` | 点赞/收藏切换 |
| GET/POST | `/api/v1/posts/{id}/comments` | 一级评论；POST **幂等**（`uk_comment_request`） |
| GET | `/api/v1/comments/{id}/replies` | 二级回复 |
| DELETE | `/api/v1/comments/{id}` | 删评论（If-Match，删一级连带其回复） |
| PUT/DELETE | `/api/v1/comments/{id}/like` | 评论点赞 |

**发现**（`content/DiscoveryController.java`）：`GET /search`（type 只接受 `posts`、filter 必须为空）、`GET /me/favorites`（type 只接受 `post`）、`GET /me/summary`、`GET /me/likes`、`GET /users/{id}`、`GET /users/{id}/posts`。

**私信**（`messaging/MessagingController.java`）：`GET/POST /conversations`、`GET /conversations/{id}`、`GET/POST /conversations/{id}/messages`（`beforeSequence/afterSequence/aroundMessageId/q` 三种模式互斥；POST **幂等** `uk_message_client`）、`PUT /conversations/{id}/read`、`POST /me/conversations/read`、`GET/PATCH /conversations/{id}/settings`（PATCH 带 expectedVersion）、`POST /conversations/{id}/clear`（`clearThroughSequence` + `confirmed`）。

**通知**：`GET /notifications`（type ∈ comment/reaction/announcement）、`PUT /notifications/{id}/read`、`POST /me/notifications/read`、`GET /me/unread`。

**资料**：`PATCH /me`、`GET/PATCH /me/preferences`、`PUT/DELETE /me/blocks/{userId}`。

**媒体**：`POST /images`（multipart，purpose=avatar/post/report，≤10MB 业务上限 / 11MB multipart 上限，临时资源 24h 过期）、`GET /images/{id}/access`（换 60s 签名 URL）、`GET /images/{id}/content?grant=`（签名校验返回字节，`private, no-store`）、`DELETE /images/{id}`。

**内测门禁**：`GET /beta/access`、`POST /beta/redeem`（5/60s）、管理端 `GET/PATCH /admin/beta`、`POST /admin/beta/initialize`（生成 `XK-XXXXXX-XXXXXX` 码并切 beta）。

**运营**：公告 `GET /announcements?scope=`(+`/{id}`) 公开；管理端 `/admin/announcements`（草稿→发布→撤回→删除，全 expectedVersion）；通知任务 `/admin/notifications`（草稿→受众预览→发送，发送需 `audienceFingerprint` 一致防受众漂移）。

**治理**：`POST /reports`（`{targetType∈post|comment|user, reasonCode, description≤200, evidenceImageIds≤3, clientRequestId}`，同目标待处理唯一）；管理端 `/admin/reports`(+`/{id}/resolution` 一次性处置)、`/admin/users/{id}/restrictions|ban|release`。

**后台聚合/设置/审计/反馈**：`/admin/dashboard|posts|comments|users`（列表/详情/批量隐藏/删除）、`/admin/settings/content-review`(+`/reset`)、`/admin/audit-events`(+`/{id}`/`/export` CSV)、`POST /feedback`（用户）+ `GET /admin/feedback`。

**健康**：`GET /api/v1/health`（**无 profile 常驻**）、`GET /actuator/health`。

**WebSocket**：`/api/v1/ws`（auth profile）。握手必须 `Authorization: Bearer`，URL 不允许 query；服务端**单向**推送 `{eventId, type, occurredAt, payload[, conversationId, sequence]}`；客户端发消息即被关闭；每秒校验会话有效性；推送失败仅记日志由 HTTP 补取。

### 2.3 鉴权机制

- JWT HS256，密钥 `XINKELINK_JWT_SECRET_BASE64`（Base64 解码后 **≥32 字节**否则启动失败）；issuer `xinkelink`，aud：用户 `xinkelink-mini` / 管理员 `xinkelink-admin`；claims 白名单严格校验（`iss/sub/sid/actorType/aud/iat/exp/jti`，多一个或少一个都判定失败）。
- **access token 900 秒**；refresh token 只存 SHA-256 摘要于 `xl_auth_session`（用户 30 天，管理员 8 小时），刷新走 `SELECT ... FOR UPDATE` + `version` 乐观锁轮换，**轮换不延长 expires_at**（30 天绝对过期，无滑动续期）。
- **每次请求**都回库查 `xl_auth_session` 复核：会话吊销/过期、用户封禁（`ACCOUNT_BANNED`）、软删 —— JWT 有效但会话吊销同样 401。
- 乐观锁两形态：body `expectedVersion`（PATCH/命令类，冲突 → `VERSION_CONFLICT`）与 header `If-Match: "N"`（DELETE 类，缺失 → `PRECONDITION_REQUIRED`，格式错 → `VALIDATION_FAILED`）。
- 通道限制：发布/评论/私信分别检查 `xl_user` 的 `restrict_*` 列 → `ACTION_RESTRICTED`。
- 管理接口**三层鉴权**：路由 `hasRole("ADMIN")` → 会话回查 → service 层 `support.requireAdmin(principal)`。
- **服务端无 CORS 配置**（全仓库零 `@CrossOrigin`）；`XINKELINK_ADMIN_ALLOWED_ORIGINS` 是 **CSRF Origin 白名单**，不是 CORS 白名单。管理后台依赖 Vite 开发代理。

### 2.4 Beta 门禁

`xl_beta_stage` 单例（缺省 open）。管理员 initialize 后切 `beta` 阶段：此时除白名单（auth、health、`GET /beta/access`、`POST /beta/redeem`、`GET/PATCH /me`、`POST /images` 仅限头像、图片 content、admin）外的**一切** `/api/v1/**` 都要求已激活用户，否则 403 `BETA_ACCESS_REQUIRED`。`BetaAccessFilter` 在授权过滤器前运行；阶段切换通过 WebSocket 即时复检在线会话。

---

## 3. 数据库（MySQL 8.4，Flyway V1–V9，25 张表）

连接：隔离开发环境 `127.0.0.1:13306/xinkelink_dev`（由 `initialize-backend-dev.ps1` 拉起的本地 mysqld）。会话时区 UTC，业务日切按上海时区。

| 迁移 | 内容 |
|---|---|
| V1 | 身份：`xl_user`、`xl_user_preference`、`xl_user_block`、`xl_admin`、`xl_auth_session` |
| V2 | 内容：`xl_post`、`xl_comment`、`xl_like`、`xl_favorite`（原含 `xl_product`） |
| V3 | 私信与治理：`xl_conversation`、`xl_conversation_member`、`xl_message`、`xl_report`、`xl_moderation_action`（原含 `xl_conversation_product`） |
| V4 | 运营与媒体：`xl_announcement`、`xl_notification_task`、`xl_user_notification`、`xl_audit_log`、`xl_system_setting`、`xl_image_asset`、`xl_media_ref`、`xl_user_activity_day` |
| V5 | 统一补齐 24 条物理外键（**全部 ON DELETE RESTRICT**）；多态目标/认证主体/最近消息保持逻辑关联 |
| V6 | 放开 product 相关 CHECK |
| V7 | 内测门禁：`xl_beta_stage`（单例）、`xl_beta_code` |
| V8 | `xl_feedback` |
| V9 | **下线二手市场**：建 5 张临时归档表 → 按序删表/解绑 → `DROP TABLE xl_conversation_product`、`xl_product` → `xl_message` 删商品三列 → 收窄全部 CHECK → 删临时表 |

**25 张表逐表速查**：

| # | 表 | 关键列与约束 |
|---|---|---|
| 1 | `xl_user` | UNIQUE `uk_user_wechat(wechat_app_id, wechat_open_id)`；`nickname/bio/major/class_name/enrollment_year`；`ban_state/ban_until`；`restrict_publish|comment|message`；`avatar_image_id`→FK；软删 + `version`；4 个索引 |
| 2 | `xl_user_preference` | PK `user_id`；`messages/interactions/announcements/sound` 四布尔；`version` |
| 3 | `xl_user_block` | PK `(owner_id, blocked_user_id)`；CHECK 不同人；解除时物理删行 |
| 4 | `xl_admin` | UNIQUE `uk_admin_login`；`password_hash` BCrypt(12)；`role='ADMIN'`；`status=active/disabled`；`version` |
| 5 | `xl_auth_session` | `subject_type/subject_id`（逻辑关联）；UNIQUE `uk_session_refresh(refresh_hash BINARY(32))`；`refresh_generation`；`csrf_hash`；`expires_at/revoked_at/last_used_at`；`version` |
| 6 | `xl_post` | FK `author_id`；UNIQUE `uk_post_request(author_id, client_request_id)` + `request_hash`；`category` CHECK ∈ (`campus,lost_found,study,help`)；`is_anonymous/anonymous_label`；`status/review_state`；软删 + `version`；3 个 feed 索引 |
| 7 | `xl_comment` | 两级：`root_id`/`reply_to_id` 自引用 FK；`target_type` V9 后 CHECK = 'post'；UNIQUE `uk_comment_request`；`content≤1000`；软删 + `version` |
| 8 | `xl_like` | PK `(user_id, target_type, target_id)`；target CHECK ∈ (post, comment)；取消时物理删行 |
| 9 | `xl_favorite` | PK 同构；target CHECK = 'post' |
| 10 | `xl_conversation` | UNIQUE `uk_conversation_pair(low_user_id, high_user_id)`（CHECK low<high）；`next_sequence`；`last_message_id`（逻辑指针，无 FK）；`version` |
| 11 | `xl_conversation_member` | PK `(conversation_id, user_id)`；`read_through_seq/cleared_through_seq`（CHECK read≥cleared）；`muted/pinned`；`version` |
| 12 | `xl_message` | UNIQUE `uk_message_sequence(conversation_id, sequence_no)` + `uk_message_client(sender_id, client_message_id)`；`kind` CHECK 恒 = 'text'；`text≤1000`；`moderation_state`；**隐藏后保留序号占位** |
| 13 | `xl_report` | `reporter_id`/`target_user_id`/`resolved_by` FK；`target_type` ∈ (post, comment, user, **message**——但用户侧禁 message)；`target_snapshot JSON`；UNIQUE `uk_report_request`；**`pending_guard` 生成列 + `uk_report_pending`**（同目标只允许一条待处理）；8 个 CHECK |
| 14 | `xl_moderation_action` | UNIQUE `uk_moderation_operation`；`before_state/after_state JSON`；只追加 |
| 15 | `xl_announcement` | UNIQUE `uk_announcement_request`；`category` ∈ (community, activity, safety)；`scope` ∈ (all, forum)；`status` draft→published→withdrawn；`version` |
| 16 | `xl_notification_task` | UNIQUE `uk_task_request` + `uk_task_send(send_operation_id)`；`audience` ∈ (all, active, recent)；`audience_fingerprint CHAR(64)`；`version` |
| 17 | `xl_user_notification` | UNIQUE `uk_notice_event(recipient_id, event_key)`；`type` ∈ (comment, reaction, announcement)；`event_kind` 8 种；`read_at/active`；**运行账号仅 SELECT+INSERT** |
| 18 | `xl_audit_log` | UNIQUE `uk_audit_operation`；`actor_type=admin/system`；`module` 6 种；`outcome` 3 种；`changed_fields JSON ARRAY`；**只追加，运行账号不可 UPDATE/DELETE** |
| 19 | `xl_system_setting` | PK `setting_key` CHECK = 'content_review'；`value_json JSON OBJECT`；`effect_mode` 恒 'configuration_only'；`version` |
| 20 | `xl_image_asset` | UNIQUE `uk_asset_key(storage_key)`；`purpose` ∈ (avatar, post, report)；`sha256 BINARY(32)`；`state` temporary→ready→deleting→deleted；临时 24h；`version` |
| 21 | `xl_media_ref` | PK `(owner_type, owner_id, position)`；UNIQUE `uk_media_owner_image`；`owner_type` ∈ (user_avatar, post, report_evidence, report_snapshot)；CHECK `user_avatar` 必须 position=0、`report_evidence` position<3 |
| 22 | `xl_user_activity_day` | PK `(user_id, activity_date)`；**当前无任何生产写入**（管理端显式声明 dau 不可用） |
| 23 | `xl_beta_stage` | 单例 `id=1` CHECK；`phase` ∈ (open, beta)；`initialized`；`version BIGINT`（**与其他表 INT 不一致**） |
| 24 | `xl_beta_code` | UNIQUE `code` + UNIQUE `user_id`；`used_at`；**无 version/updated_at/deleted_at** |
| 25 | `xl_feedback` | `content≤1000`；UNIQUE `uk_feedback_request(user_id, client_request_id)`；**无 version/updated_at/deleted_at，无 CHECK** |

**已删除**：`xl_product`（V2）、`xl_conversation_product`（V3）。

通用约定：软删 `deleted_at`；可变表都有 `version INT UNSIGNED`；幂等键唯一约束；枚举用 CHECK 兜底；所有外键 `ON DELETE RESTRICT`（禁止物理级联）。

**改 SQL 后的快速自检**：`apps/server/src/test/resources/schema-contract.ps1`（静态文本契约，不连库）+ `test/java/cn/xinkelink/database/DatabaseMigrationIT.java`（真实 MySQL 8.4 验收，断言恰好 25 张表、9 个版本、READ-COMMITTED、UTC、迁移/运行账号不同、外键 RESTRICT、运行账号无 DDL/审计写权限）。

---

## 4. 缓存（Redis）

**唯一的业务用途是登录限流**（`auth/ratelimit/RedisAuthRateLimiter.java`，StringRedisTemplate）。全仓库只有 3 处生产引用：

- key：`xinkelink:auth:rate:{operation}:{SHA-256(key) 的 hex}`，operation ∈ `wechat|admin|refresh|beta`（其它值直接抛 `VALIDATION_FAILED`）。SHA-256 摘要化避免 IP/账号明文进 Redis。
- Lua 脚本原子 INCR + `EXPIRE 60` 固定窗口；后续请求不延长窗口。
- 限额：微信登录 10/60s(IP)、刷新 30/60s(IP)、管理员登录 5/60s(IP+账号双键)、beta 兑换 5/60s(IP+账号)；超限 429 `RATE_LIMITED`。
- **fail-closed**：Redis 抛任何 `RuntimeException` → 503 `SERVICE_UNAVAILABLE`（**不放行**，异常被吞不保留 cause，因为可能含连接凭据）。
- **没有** `@Cacheable`/`CacheManager`、会话不存 Redis（存 MySQL）、无分布式锁（并发靠 MySQL 行锁 + 唯一键）、无缓存/计数/排行榜（帖子计数靠 `COUNT(*)` 子查询）。
- 配置：默认 `127.0.0.1:16379`，无密码，connect-timeout 2s。

---

## 5. 管理后台（apps/admin）

Vue 3.5 + TS 5.9 + Vite 8 + Pinia + Element Plus + vue-router；测试 Vitest + Playwright；要求 node ≥ 24。

- 路由：`/login` + `/admin/dashboard|posts|comments|reports|users|announcements|notifications|logs|settings|beta|feedback`（未列出的导航项落到 DevelopingView）。
- 认证：accessToken 存内存 + csrfToken 存 sessionStorage；refresh token 由服务端 HttpOnly Cookie 承载；401 自动刷新并重放；所有请求带 `X-CSRF-Token` 与 `credentials: 'same-origin'`。
- 后端代理：`apps/admin/vite.config.ts:16` 把 `/api` 代理到 `http://127.0.0.1:18081`。
- 调用的接口：`/admin/` 下 dashboard、posts/comments（列表/详情/批量 status/delete）、reports(+resolution)、users(+restrictions/ban/release)、announcements、notifications（含 audience-preview/send）、settings/content-review、audit-events(+export)、beta、feedback；图片预览走 `/api/v1/images/{id}/access`。
- **开发时需 HTTPS**：`XINKELINK_DEV_CERT` / `XINKELINK_DEV_KEY` 指定本地证书与私钥，访问 `https://localhost:5173`（管理员刷新 Cookie 要求 secure）。

---

## 6. 配置与启动

### 环境变量（`XINKELINK_*`）

| 变量 | 默认 | 必填 | 说明 |
|---|---|---|---|
| `XINKELINK_DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | 无 | **是**（db profile） | JDBC 与运行账号 |
| `XINKELINK_MIGRATION_USERNAME` / `MIGRATION_PASSWORD` | 无 | **是** | 迁移账号（**须与运行账号不同**） |
| `XINKELINK_JWT_SECRET_BASE64` | 无 | **是**（auth profile） | HS256 密钥，Base64 解码 ≥32 字节；**无默认值，缺失直接启动失败** |
| `XINKELINK_WECHAT_APP_ID` / `SECRET` | 空 | 否 | 未配 → `WECHAT_NOT_CONFIGURED` |
| `XINKELINK_ADMIN_ALLOWED_ORIGINS` | 空 | 否 | 空 → 后台登录必失败；必须是 https 或 127.0.0.1/localhost/[::1] |
| `XINKELINK_REDIS_HOST` / `PORT` / `PASSWORD` | 127.0.0.1 / 16379 / 空 | 否 | |
| `XINKELINK_IMAGE_STORAGE` | `local` | 否 | `local`/`oss` |
| `XINKELINK_IMAGE_DIRECTORY` | `.local/backend-images` | 否 | local 模式落盘目录 |
| `XINKELINK_OSS_BUCKET` / `REGION` / `ENDPOINT` / `ACCESS_KEY_ID` / `ACCESS_KEY_SECRET` | — | oss 模式必填 | endpoint 强制 https 且 host 必须匹配 `oss-<region>.aliyuncs.com` |
| `XINKELINK_DEV_CERT` / `XINKELINK_DEV_KEY` | 无 | 否 | 仅 admin 前端本地 HTTPS |

`.env.example`（`apps/server/.env.example`）**不会自动加载**，仅作变量名清单。

### Profile 组合

| profile | 作用 |
|---|---|
| （无） | 仅注册 `HealthController`；`/api/**` 被 `denyAll` |
| `db` | 启用 DataSource + Flyway（`application-db.yml` 通过整体重定义 `spring.autoconfigure.exclude` 列表来重新启用 DataSource） |
| `auth` | **必需**：注册全部业务 Controller/Service/Mapper、JWT、BCrypt、Redis 限流、微信换码、存储、实时推送 |
| `dev` | 打开 OpenAPI / Swagger UI |
| `redis` | 仅打开 actuator 的 redis 健康检查（auth profile 已含 Redis 连接配置） |

**默认 profile 组合**：`scripts/start-backend-dev.ps1:4` → `'db,dev,auth'`（**不含 `redis`**）。
测试用组合：`db,auth`（多数 IT）、`db,auth,redis`（`Day5IntegrationServer.java:17`）。

### 启动

1. `scripts/initialize-backend-dev.ps1` —— 拉起隔离 MySQL 13306、生成 `.local/backend-day1/private/runtime.json`。
2. `scripts/start-backend-dev.ps1` —— 默认 `-Port 18081 -Profiles db,dev,auth`，第 104 行显式传 `--server.port=$Port`（yml 仅影响手动启动）。
3. 健康检查：`/actuator/health` = UP 且 `/api/v1/health` 返回 `application=xinkelink-server`。

小程序端演示模式不需要后端；后端模式才需要以上全部。

---

## 7. 已知问题与待办（接手前先看）

> 完整清单见 `docs/business-flows.md` §4（前端）与本节（后端）。以下按优先级。

### 7.1 🔴 后端高危

1. **无 `spring.profiles.active` 默认值** —— `application.yml` 全文 36 行没有这个键。直接 `java -jar` 启动 → 不激活 auth → 业务 Controller 全不注册 → `/api/**` 全 403，只剩 health。**必须用 `scripts/start-backend-dev.ps1` 或显式传 `--spring.profiles.active=db,auth`**。
2. **profile `auth` 隐式强依赖 `db`** —— `application.yml:4-7` 把 `DataSourceAutoConfiguration` 放进 `spring.autoconfigure.exclude`，`application-db.yml:1-4` 靠**整体替换同一个 List** 重新启用它。只激活 `auth` → "Failed to configure a DataSource"，报错信息与真实原因无关。
3. **Redis 健康检查默认关闭，但 Redis 是登录链路强依赖** —— `management.health.redis.enabled: false`，且默认 profile 不含 `redis`；Redis 挂时登录全 503 但 `/actuator/health` 仍报 UP，启动脚本也察觉不到。**建议**：把该开关移到 `application-auth.yml`。
4. **每次登录 `version = version + 1`，与资料乐观锁令牌冲突** —— `AuthMapper.touchUser` 每次登录递增 `xl_user.version`，而 `ProfileService` 用 `version` 做资料更新令牌 → 用户在编辑页停留期间重新登录，保存资料必然 409。

### 7.2 🟠 后端中危

5. `xl_user_notification` **缺 `event_key` 前导索引** → 取消点赞/收藏时全表扫描。建议 `KEY idx_notice_event_key (event_key, active)`。
6. `xl_report` / `xl_moderation_action` 的 CHECK 允许 `target_type='message'`，但用户侧创建接口硬拒 message（`ReportModerationService.java:232`）→ "预留未开放"还是遗漏，需明确。
7. `xl_auth_session.refresh_generation` / `last_used_at` 是**死列**（只写不读）。
8. `xl_message.request_hash` 存了但从不比对（实际用 `conversationId + text` 比对）。
9. `xl_user_activity_day` **无任何生产写入**（25 张表里 1 张是纯占位）。
10. `BetaAccessFilter` 对每个非白名单 `/api/v1/` 请求都多打一次 DB（`SELECT phase FROM xl_beta_stage WHERE id=1`）。
11. `RealtimeEvents.checkSessions` **每秒对每个连接做两次 DB 查询** → N 个在线客户端 = 每秒 2N 次查询。
12. 服务端**完全无 CORS 配置**；管理后台一旦脱离 Vite 代理独立部署会全部失败。
13. `xl_beta_stage.version` 是 `BIGINT UNSIGNED`，其他表统一 `INT UNSIGNED`。
14. `xl_beta_code` / `xl_feedback` 缺 `version` / `updated_at` / `deleted_at`，`xl_feedback` 无任何 CHECK。

### 7.3 🟠 前端高危（演示模式访客越权）

> 详见 `docs/business-flows.md` §4。

15. **访客可深链读上一账号私信** —— `pages/chat-settings/index.ts:9-13` `onLoad` → `reload()` → `readConversation` **不判登录**；`:38-42` `onHistoryInput` 会把上一账号的消息正文渲染出来。真机侧已有守卫（`backend/pages.ts:568` `protectedActions`）。
16. **访客可读上一账号资料与「我的帖子」** —— 抽屉 `onNavigate` 对 `profile` 路由不判登录（`components/app-shell/index.ts:131`）；`pages/profile/index.ts:64,65` 的 `onMyPostsTap` / `onCollectionsTap` 无 `requireLocalSession`；`pages/my-posts/index.ts:8` onShow 同样无守卫。
17. **"已登录"判定有 5 套实现，回退值互相矛盾** —— `forum-post-card/index.ts:9-14`、`contact/controller.ts:9-14`、`page-header/index.ts:71` catch→**true**；`app-shell/index.ts:27` catch→维持旧值；只有 `viewer.ts:22` catch→guest。存储损坏时同一屏会出现三种身份结论。**修复方向**：全部改用 `getViewerRole(wx) === 'guest'`。

### 7.4 🟡 前端中低危

18. 「编辑帖子」入口不判登录（`forum-post-card/index.ts:110`）→ 访客可回填帖子内容到 UI。
19. 访客可见并可写**搜索历史**（`pages/search/index.ts:32,62`）。
20. 演示模式的**举报 / 反馈**无登录守卫，真机模式有 → 两模式权限不一致。
21. 「转发」两模式能力不一致：演示真 toggle，真机 toast「即将上线」。
22. `readLocalSession` 在 key 缺失时**返回 true**（默认已登录）→ 新设备上 `getViewerRole` 返回 `self`、所有写操作放行。与「访客=未登录」规则冲突，需明确写死语义。
23. `pages/contact/controller.ts:22` 真机访客登录**未 await** → 失败被吞且 UI 已切「已登录」。
24. `pages/search/index.ts:69` 互动写入无 try/catch 且未校验 id 是否在当前结果内 → 未捕获异常。
25. `profile-auth-sheet` 的 `bind:confirm/bind:cancel` 在**演示模式全 17 页未实现**（只在 `backend/pages.ts`），目前不可达但未来 setData `authVisible` 即 "handler not found"。→ `BUGS.md` 第 4 条。
26. 访客可见发帖草稿与草稿页（`pages/drafts/index` 已注册但无入口，仅深链可达）。
27. 编辑帖子成功后**不清理草稿**（`backend/pages.ts:257` 仅新建态清）。
28. `backend/pages.ts:231` 评论删除 **未做 URL 编码**（同文件其它地方都做了）。
29. `pages/login/` 是已注销的孤儿代码（app.json 未注册，但 `registerPage(...,'login')` 与 `definitions.login` 都还在）。
30. `xinkelink.search.entry` 只写不读（死 key）。
31. 真机模式**每页 onShow 都发一次 `/beta/access`**（pending 缓存仅同 token 内生效）。

### 7.5 🟡 其它值得注意

- **帖子分类前后端不一致**：前端 8 板块（含 `secondhand/complain/buddy`），V9 后端 CHECK 收窄为 `campus/lost_found/study/help` → **真机模式发新分类帖子会被 400**。要么后端扩 CHECK，要么前端收缩板块。**这是当前最容易踩到的功能级问题。**
- **真机侧小缺口**：编辑帖子不带 `anonymous`（后端 `UpdatePost` 无该字段，硬加会 400）；`ForumService.author()` 不返回 `major`（前端 `authorMajor` 恒空但 UI 未消费）。
- **WXSS @import 位置违规**：`pages/report|chat-settings|profile-edit/index.wxss` 的 `@import "../../styles/page-polish.wxss"` 写在规则之后被忽略；移到首行会改变层叠顺序，需重新目验这三个旧排版页。
- **前端死代码**：`pages/login/`、`pages/drafts/`、`service.collections()` 无调用点。
- **端口四方已统一到 18081**（2026-11-13，`3d7f937`）：`backend/config.ts:2`、`application.yml:17`、`apps/admin/vite.config.ts:16`、`README.md` 全部一致；`scripts/start-backend-dev.ps1` 默认 `-Port 18081`。**注意**：`backend/config.ts:1` 的注释仍写"手机热点局域网地址"，但值是回环地址 `127.0.0.1` —— **真机联调必须手改此文件**（容易被误提交）。
- **后端启动至今未在本机验证成功**：缺 pwsh 7.4（脚本 `#Requires -Version 7.4`，系统仅 PS 5.1）、隔离 MySQL 13306 未运行、Redis 16379 未运行、`XINKELINK_JWT_SECRET_BASE64` 未设置。**Codex 接手后第一件事：在补齐上述环境后跑通 `/api/v1/health` 与 `/api/v1/posts`。**
- **验证/自测习惯**：`apps/miniprogram` 改动后跑 `npm run check`（260 条）；微信开发者工具对新建 .ts 文件的编译缓存不敏感，验证运行时行为前先手动点「编译」；`cli.bat open --project` 会重启模拟器导致自动化读不到页面数据。
- 历次踩坑与决策记录在 `.workbuddy/memory/2026-11-12.md`、`2026-11-13.md`，排查前值得先读。

---

## 8. 交接后第一周的建议顺序

1. 补齐本机后端环境（pwsh 7.4 / MySQL 13306 / Redis 16379 / JWT 密钥），跑通 `mvn test`（~130 单测，不需要 Docker）与 `/api/v1/health`。
2. 修 §7.3 的 3 条前端高危（chat-settings 守卫、profile/my-posts 守卫、统一 `getViewerRole`），补测试。
3. 决策分类口径（§7.5 第一条），这是发布前的阻塞项。
4. 决策 `readLocalSession` 默认值语义（§7.4 第 22 条），并同步 `IDENTITY-RULES.md` 的规则表。
5. 若要跑集成测试：`mvn -Pdb-integration verify`（~160 用例，需 Docker + MySQL 8.4）。

## 2026-11-18 Link AI 第一版：实现、真实模型与最终验收

详细说明：[23-link-ai-v1.md](backend/23-link-ai-v1.md)。本轮仅学校规章问答、公开查帖，保留现有Java21/Boot4.1.1、中间件行为与五个小程序导航。代码未提交、未推送，未部署生产。初始大量未提交修改保留，基线patch/status及最终对比在 `.local/link-ai-20261117/` 和 `.local/link-ai-20261118-real/workspace-safety.json`，未发现原文件丢失、无关旧diff变动或API Key字面量泄漏。

新增后端 `cn.xinkelink.ai`：AiGateway、AiRoute、AiController、AiSessionService、AiPostSearch及knowledge包。接入稳定Spring AI2.0.2、外部Chat/Embedding、显式Cohere/DashScope精排协议，Java中文BM25、Chroma REST v2、RRF、证据检查及受控引用。资料提取、草稿校对、版本发布/停用、双索引准备后切换、重启恢复及审计已实现；会话归属、完整历史、稳定requestId、同时发送限制、退出/删除迟到写入保护与帖子当前公开投影均有回归。前端复用原AI页风格及后台布局，fixture/live共用契约且不做演示回退。

新增V15—V18，原V1—V14不改；当前18条迁移、47张业务表。知识版本和片段运行权限仅INSERT，资料/索引/会话不授DELETE，删除请求消息历史保留必要权限。真实业务在13306的 `xinkelink_cache_ai20261117_test`；最终数据库回归另用 `xinkelink_cache_ai20261118_regression_test`，避免真实1024维资料与模拟3维向量混用。没有向开发库发布真实学校制度，没有清库或清Redis。

桌面配置文件可读取，三个真实API均打通：glm-5.3、qwen3.7-text-embedding（1024维）、gte-rerank-v2。密钥仅本地进程环境，不打印、不写源码、不改变桌面权限。gte需DashScope原生精排接口，兼容/rerank(s)实测404；glm-5.3关闭思考实测400，本轮采用4096输出Token/60秒，截断及空正文明确报可重试错误。既有Netty4.2.17、Protobuf4.35.1、Kafka4.2.1、RocketMQ5.5.1、Canal1.1.8依赖保留，依赖树有证据。

真实HTTPS后台经18131上传合成TXT、人工校对、保存、独立发布v1、更新草稿保留v1、独立发布v2成功；资料13保留v2用于问答。官网首页真实指定导入成功并提示附件/图片缺失，没有发布。真实HTTP第二轮13/14首次通过，余项503后按原requestId重试成功且消息数不变；完整失败尝试均保留。最终构建另行真实问答、稳定回放、历史及删除后404烟测4/4通过。微信开发者工具live原生运行状态11/11通过，无响应桩；其性质是handler/状态/真实HTTP，未冒称物理手势、手机或正式微信授权。

| 最终检查 | 实际结果 |
| --- | --- |
| 小程序完整npm run check | 395通过，0失败/跳过 |
| 后端完整mvn test | 378通过，0失败/错误/跳过 |
| mvn -Pdb-integration verify | 集成278通过，0失败/错误/跳过；同次普通378重跑不重复统计 |
| 后台完整npm run check | 107通过，类型检查及生产构建通过；既有大chunk提示保留 |
| 数据库静态契约 | 18迁移/47业务表，真实回归库亦验证 |
| 官方原生编译 | 29 WXML/33 WXSS通过 |
| 最终构建真实烟测 | 4项通过，真实Chat/Embedding/Reranker/Chroma/MySQL |

两份标注题集共24条。7条真实精排合成样例给出试验阈值约0.139779，仅用于隔离试验；学校真实资料阈值仍未校准，没有宣称默认值最优或生产准确率。本地Java-Xmx512m，真实查询后工作集约561MB/私有内存619MB；不代表全套服务4GB容量验收。PDF解码正文和证据提示词的有界内存/长度问题经独立复核、红绿测试修复，最终完整回归包含修复。

最终临时18131应用、Chroma18300、Redis16389、后台5178及DevTools自动化9420已按归属收尾；Chroma持久目录、隔离库和必要证据保留，共享13306/16379保留。18081本轮初始未监听，没有替换原服务或改其运行开关。小程序config.ts按字节恢复origin18081，原local模式、账号存储恢复，测试包装器移除。最终端口与进程链记录见 `final-cleanup.json`；真实资料仍须管理员人工校对发布，4GB整体部署、物理手机及此前三页完整防穿透缺口未计通过。

重跑使用新证据目录和新的命名隔离库，不覆盖旧日志或混用向量维度；先核实13306进程记录。API密钥继续使用原桌面受保护文件/环境变量，禁止复制到Git。Chroma使用已保留持久目录启动，运行索引只接受当前有效版本；未就绪或外部故障返回错误，不回退演示。

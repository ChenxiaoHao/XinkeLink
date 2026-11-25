# 福袋原生操作与多模块联合验收（2026-11-17）

本轮真实联合后端链路通过；原生手势矩阵仍未完成，不能据此宣布完整端到端验收通过。未开发 AI、增加业务、部署生产、压测、提交或推送。

证据根目录：`.local/joint-acceptance-20261117/`。下文文件名均相对此目录。接手 HEAD 为 `7fc1af479e4e183d0c1d0f2e4bb9b0b6a08fa479`，分支 `codex/phase-1-foundations`；接手 885 个文件的哈希和原有差异保存在 `baseline-hashes.json`、`before.patch`、`git-status-before.txt`。历史专项结果不计入本轮。

## 原生操作、修复与剩余缺口

使用已安装微信开发者工具、原生模拟器和 Windows user32 鼠标输入。真实输入脚本为 `input.ps1`；状态准备可用自动化，但点击、拖动证据没有调用 handler、triggerEvent 或程序化滚动。只读 selector query 记录卡片矩形及 scrollTop。模拟设备为 430×932，安全区底部 898；没有完成小屏组合，也没有物理手机或正式微信授权验证。

| 项目 | 本轮观察与结论 |
| --- | --- |
| 圆钮、正文、参与、关闭、遮罩 | fixture 首页真实圆钮打开，正文/参与后保持打开，关闭按钮关闭；底部导航位置被遮罩覆盖时关闭且仍在首页。见 `native-round-click.json`、`native-body-click.json`、`native-close-correct.json`、`native-tab-under-mask.json` 及对应截图。没有覆盖全部正文坐标和按钮组合。 |
| 内部滚动 | fixture 合法长内容准备为标题 19、规则 380、说明 450 字；真实拖动 scrollTop 0→602.67，内容高 1349、视口高 745.59，抵达底部。live 连接隔离 18122，标题 51、说明 420 字，0→348.67，内容高 1095、同一视口。见 `long-top-measure.json`、`long-bottom-measure.json`、`live-long-top.json`、`live-long-bottom.json`。长卡片在历史页准备为共享组件弹层，不能替代自定义 tab 三页全覆盖。 |
| 边界、遮罩拖动 | 真实再次拖动后卡片停在底部；遮罩拖动截图未见变化。但未取得可滚动背景的可靠位移对照，不能宣布背景锁定、反向滚回和边界不穿透全部通过。 |
| 三页入口 | `native-entrances.json` 最终批次失败：坐标输入后首页/搜索未可靠打开、通知页落入帖子详情。已有首次圆钮成功证据，但这批定位/渲染/前台焦点失败不能当成组件缺陷，也不能把“本来未打开，遮罩后仍关闭”的真值计为成功。未无限重复输入。 |
| 状态和身份 | `mini-native-live-results.json` 7/7 状态/API 检查通过：隔离 HTTP、本人编码、其他账号/访客清理、历史中奖通知。脚本中的通知跳转调用 handler，仅算状态检查，不算手势。迟到响应/队列/计时器另有完整单测；抽屉、切页、切后台的原生组合仍缺实证。 |
| 明确未完成 | 刷新和复制按钮的真实点击、全部背后帖子/发帖/导航的点击防穿透、小屏及安全区可达性、反向滚动、可滚动背景锁定、三页及生命周期完整矩阵。 |

实际组件问题：长内容滚动后在圆角视口外绘制。先加入回归断言得到失败（`mini-clip-red.log/.exit`），再只给 `.bag-card` 增加 `overflow:hidden`，断言转绿并重新真实拖动检查（`mini-clip-green.log`、`popup-long-top.png`、`clip-fixed-bottom.png`、`measured-drag*.png`）。没有批量替换 touch 拦截、scroll-view 或 tab 层级。

本轮代码仅修改：

- `apps/miniprogram/miniprogram/components/lucky-bag-card/index.wxss`：裁切视口外内容。
- `apps/miniprogram/tests/lucky-bag.test.cjs`：增加对应样式回归断言；它不代替原生输入验证。

## 同一隔离环境及固定产物

最终 JAR：`apps/server/target/joint-20261117/xinkelink-server-0.1.0-SNAPSHOT.jar`。

SHA256：`E88064A480791FCE22FCA47830AB5BCDA3D626F2D0F5BD4DD9B88D9543596227`。

最终主验收和全部补核均使用此产物，没有再次打包。`live-results.json`、`fixed-jar-sha256.txt`、`supplement-processes.json` 保存多次启动的独立 JVM、命令、产物哈希；最后补核 JVM 为 36808/22000，均已停止。测试故障工作类只在独立 test-classes，不进入生产 JAR。

| 项目 | 实际隔离配置 |
| --- | --- |
| 应用 | 127.0.0.1:18121、18122，Java21，同一 JAR、同一业务库；崩溃边界临时 worker 使用 18123。 |
| MySQL | 已有 13306；`xinkelink_cache_joint_20261117_r2_test`，14 个迁移、41 张业务表；独立测试用户及管理员会话。未连接 3306、未清空开发库、未修改迁移。 |
| 缓存/榜单 | 本轮独立 Redis 16390，两个 JVM 共用；没有因换库而误用共享 16379 的 postId 缓存。 |
| Canal | 独立原生 server 11119、destination `joint_metrics_r2`、订阅 client_id 1001、独立复制/客户端身份、仅目标 schema。18121 唯一 CDC 消费者，18122 显式关闭 CDC，遵守单消费者归属。 |
| RocketMQ | 复用既有安装并启动 namesrv 19876/broker 20911；topic `xl_interactions_joint_20261117_r2_test_v1`，group `xl_notifications_joint_20261117_r2_test_v1`。 |
| Kafka | 保留共享 broker 39092；独立 topic/group `xl_views_joint_20261117_r2_test_v1`、namespace `joint_20261117_r2_test`。 |

`environment.ps1` 将以下十一项均设为 true，18122 的 CDC 由启动参数覆盖为 false：`POST_METRICS_CACHE`、`POST_METRICS_L1`、`POST_METRICS_CDC`、`NOTIFICATION_MQ`、`NOTIFICATION_MQ_PUBLISHER`、`NOTIFICATION_MQ_CONSUMER`、`VIEW`、`VIEW_PUBLISHER`、`VIEW_CONSUMER`、`VIEW_PROJECTION`、`LUCKY_BAG`（完整变量名为 `XINKELINK_<名称>_ENABLED`）。不是只检查环境变量：缓存命令、栅栏、CDC 位点、broker message id、Inbox、Kafka 持久事件、榜单和正式开奖均有下表行为证据。恢复窗口暂时关闭发布/消费/投影后，再恢复全开。

首次 r1 destination 从迁移前旧 binlog 起点读取，遇到 DDL 被安全映射器阻塞；保留 `r1-final-failure.json`、位点和状态，没有跳过、重置或覆盖。随后建立新 r2 库/destination，在迁移完成、任何业务写入之前记录新起点（`r2-initial-position.json`），完整重新运行业务。最终使用独立新 Redis、Topic/组；早期部分失败结果不并入最终通过数。

## 联合链路的期望与观察

主验收 `joint-r2-final.log`：1 个 JUnit 联合场景通过，`live-results.json` 内 110 项检查全部 true。补核脚本另外记录 22 项断言通过（9+7+2+4）；这些不是额外 22 条 JUnit，也不和普通测试重复相加。

| 链路/期望 | 实际观察与证据 |
| --- | --- |
| 普通帖、匿名帖、图文、互动与通知 | A 发普通/匿名帖，访客公共投影无匿名作者 ID；B 赞/取消/再赞、评论/回复/删除。真实 Outbox→broker→Inbox，HTTP 跨 JVM 补取；取消后的再赞不重新激活通知，已读未被重置。`live-results.json`；补核 post 10 上传真实测试图片，`supplement-results.json`。 |
| 多层缓存及 CDC | 两 JVM 最终公共计数与 DB 一致。绕过应用对本轮帖子受控 INSERT 点赞，CDC 推进共享 generation，两个 JVM 均读到 2，实测补偿 141ms，非强一致。`live-results.json`、`cdc-r2-checkpoint.properties`。 |
| Caffeine 实际命中 | 对同一 post 10，每 JVM 冷读产生公共计数 Redis 命令，随后各 4 次请求返回 1 且没有公共计数 Redis 命令；只监控独立 16390，未公开原始命令内容。`l1-runtime-results.json`。 |
| Kafka、PV/UV、热榜 | 同 eventId 重试不新增；主 post 4 为 3 个唯一事件、PV3/UV2。SQL 持久事件和 Redis 榜单对应；补核 post 10 从已记录 PV7 再接收两事件后 PV9/UV2。同 UV 按数字 postId 升序，北京时间 11月11—17日七个自然日。`ranking-governance-results.json`、`final-database-observations.tsv`。 |
| 治理安全投影 | post 10 隐藏后两个 JVM 详情 404、恢复后 200；热榜隐藏剔除/恢复出现；帖子通知 target unavailable、quote/image 为空、标题“内容已不可用”。按 target.kind=post 筛选，避免与同 ID 福袋通知混淆。 |
| MQ/Kafka 有界恢复 | 本轮应用重启并暂关 MQ 发布/消费、Kafka 消费/投影，业务仍写出 READY outbox 和可靠浏览 receipt；重新全开后 Inbox 只一条、浏览事件一条、PV保持1。没有停止共享 broker。`live-results.json`。 |
| 福袋并发与安全 | 0/1/多人；8 路跨 JVM 同账号重试只一条参与；双扫描只一正式结果/领奖；公共、非中奖者、管理员普通列表不泄露编码；核验不核销；两管理员开始发放仅一人成功，原管理员确认，重试不重复，只有测试人工记账、无转账。历史通知与原编码可恢复。 |
| JVM 崩溃恢复 | 实际 halt71 在提交前回滚，重启只开奖一次；halt72 在提交后通知前，正式结果/领奖与通知意图保留，恢复补投不重抽、已读不重置。`live-results.json` 和独立 worker 日志。 |
| 各任务同窗口工作 | activity 15 两人参与，08:40:38.821—08:40:41.081 UTC 间普通发帖、赞、浏览、交替详情及热榜正常，中奖通知由0变1；DB正式结果/领奖各1，普通 MQ SENT、Kafka event 各1。`scheduler-overlap-results.json`。 |
| Redis 不可用 | 只暂停独立 Redis：详情 200、计数1回源；榜单明确 503 SERVICE_UNAVAILABLE，恢复后真实榜单可用。没有演示兜底。`supplement-results.json`。 |

应用事务失效/CDC 重复与旧版本回填的细粒度竞争、截止锁竞争、迟到身份响应/上报队列依旧由本轮完整单测和数据库集成测试覆盖；未把它们冒称新增原生联合实证。主验收有 Kafka 重试防重；详情成功展示、同页刷新不重复上报、搜索页榜单显示与 A→访客→B 队列切换的完整原生联合操作仍未补齐。跨实例 WebSocket 限制没有修改，通知采用 HTTP 验证持久结果。

首次失败全部留存，包括 PowerShell/Maven 参数转义、验收 HTTP 状态/响应结构/版本头/worker 路径、Canal 配置路径/密码协议，以及后续验收器错用通知 target 类型、过期会话未刷新。这些先按配置或验收器错误定位修正，没有删断言、调整业务预期或关闭安全机制。11 次带 exit 文件的主验收尝试中，10 次非零、最终1次通过；不能将失败尝试算作通过。原生三页输入批次仍失败。

开关由启动环境、CLI覆盖和上表实际缓存/CDC/MQ/Kafka/福袋行为共同核实；没有为了读取配置而放开 Actuator 安全规则。

| 环境变量 | 18121 | 18122 |
| --- | --- | --- |
| XINKELINK_POST_METRICS_CACHE_ENABLED | true | true |
| XINKELINK_POST_METRICS_L1_ENABLED | true | true |
| XINKELINK_POST_METRICS_CDC_ENABLED | true | false（CLI覆盖；同一 destination 只有18121消费） |
| XINKELINK_NOTIFICATION_MQ_ENABLED | true | true |
| XINKELINK_NOTIFICATION_MQ_PUBLISHER_ENABLED | true | true |
| XINKELINK_NOTIFICATION_MQ_CONSUMER_ENABLED | true | true |
| XINKELINK_VIEW_ENABLED | true | true |
| XINKELINK_VIEW_PUBLISHER_ENABLED | true | true |
| XINKELINK_VIEW_CONSUMER_ENABLED | true | true |
| XINKELINK_VIEW_PROJECTION_ENABLED | true | true |
| XINKELINK_LUCKY_BAG_ENABLED | true | true |

MQ 为本机19876/20911，Topic `xl_interactions_joint_20261117_r2_test_v1`，消费组 `xl_notifications_joint_20261117_r2_test_v1`；Kafka39092，Topic/消费组 `xl_views_joint_20261117_r2_test_v1`，namespace `joint_20261117_r2_test`。独占 Canal11119，destination `joint_metrics_r2`，独立复制/订阅凭据，仅过滤本库 `xl_post|xl_like|xl_comment`，持久 checkpoint 独立。两 JVM 的 MQ/Kafka实例身份由既有随机/进程机制生成，不抢旧 destination。

初次 r1 从旧 binlog 起点读到历史 DDL，安全映射器进入 BLOCKED/MAP；保留 `r1-final-failure.json`、`r1-cdc-status.txt`、`r1-cdc-checkpoint.txt`，没有跳过失败位点。另建 r2 空库并迁移后、任何业务写入前记录 `xinkelink-bin.000002:6919129`，再从新 destination 完整验收，见 `r2-initial-position.json`。旧 r1 库/位点未删除或重置。r2 的早期验收器失败样例也保留在同库，最终断言按本次 runId/自有 ID 定位，没有清表。

## 完整检查与收尾

| 检查 | 本轮最终通过/失败/跳过 |
| --- | --- |
| 小程序完整 npm run check | 373 / 0 / 0（修复前372，新增回归1） |
| 后端完整 mvn test | 331 / 0 / 0 |
| mvn -Pdb-integration verify | 集成259 / 0 / 0；同次普通331重跑不重复统计 |
| 后台完整 npm run check | 99 / 0 / 0，17测试文件，生产构建通过；原有大 chunk 提示保留 |
| 数据库静态契约 | 通过，14迁移/41表；真实隔离库亦核对 |
| 微信语法编译 | 最终29 WXML、33 WXSS通过；与原生操作分别记录 |
| git diff --check | 通过；最终日志/exit见证据目录 |

`mini-*.log`、`server-test.log`、`server-db.log`、`admin-check.log`、`schema-contract.log`、`native-final-compile.log` 保存实际输出。Java21、显式 bytebuddy agent、独立构建目录；数据库验收串行，无修改测试预期或跳过失败断言。

已按 PID+启动时间+命令/JAR归属停止本轮 18121/18122、故障 worker、独占 Canal 11119、Redis 16389/16390；没有只凭旧 PID 杀进程。共享 MySQL13306、Redis16379、Kafka39092保留。本轮启动的既有 MQ namesrv28792/broker3244作为可复用基础设施保留，没有清空 Topic。微信编辑器保留，自动化连接已断开；本地 local 模式、fixture登录/福袋存储及原后端会话已恢复，测试 HTTP 包装器移除、origin恢复18081，见 `native-restore-results.json`。

18081仍是原 PID41624、原 JAR `3308ED849395B83EEE7E23CB49B738DABD70FFF8AF35F32B8252335575EF73E9`；未更新服务或开关。健康 UP、公开帖子及 current 接口成功，开发库福袋0、浏览 receipt0。`service-verification-before-cleanup.json` 的健康字段误读 code 为 null（health 实际用 status），并非健康失败；最终健康另存。只保留隔离库、Topic、受保护测试凭据及必要证据，未删旧库或重置位点。

## 复跑与人工补验

复跑先建立新的 `.local/joint-acceptance-20261117-<run>/`，不要直接覆盖本证据目录。复用这里的 `environment.ps1`、`JointBusinessLiveVerification.java`、`JointBagCrashProcess.java`、`run-joint.ps1` 和原专项启动方式；准备独立命名库、Redis、Topic/组、Canal destination/凭据及 checkpoint。先在13306迁移/按既有最小权限授权，再记录迁移后且业务前的新 binlog 起点。更新脚本证据路径和 worker 允许路径，编译后核对固定 JAR 哈希；主验收要求空展示指针，不清表，不抢现有 destination。`start-supplement.ps1`、`supplement.py`、`ranking-governance.py`、`l1-runtime.py`、`scheduler-overlap.py` 是本轮必要补核，不是通用测试框架；私有会话到期必须经隔离后端 refresh，不能换成开发库会话。所有数据库/中间件验收串行，结束执行同样归属检查收尾。

人工补验（当前隔离服务已停止，须先按上段在独立目录恢复固定 JAR 双实例）：

1. 开微信模拟器，准备合法范围长标题/说明；分别 fixture 与指向隔离后端的 live。不要让 live 指向18081；私有中奖状态只用隔离账号。
2. 首页、搜索、通知逐页点圆钮；依次点正文、参与、刷新、复制，确认卡片不关、后台帖子/发帖/导航不触发；最后分别点关闭和遮罩确认关闭。
3. 背景先滚到可辨认帖子，记下位置；在遮罩拖动，再在长卡片上下拖至两端，确认底部操作可达且背景位置不变。切小屏、带安全区机型重做关闭/底部操作。
4. 开抽屉、切页、切后台再回来；A退出→访客→B，检查编码/通知清理、计时器停止、迟到响应及浏览队列不串号；详情刷新/重新进入并对照 Kafka receipt 与榜单。
5. 保存录像/截图、路由和滚动位移、隔离 HTTP/DB观察；失败保留首次证据。恢复原模式/存储并停止本轮独占进程。这些补验尚未执行，不能写成通过。

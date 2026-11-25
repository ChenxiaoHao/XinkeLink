# 福袋第一版交接与验收

本轮只实现免费定时福袋：每期固定 10 元、抽取 1 个成功参与的账号，管理员线下人工转账。没有支付、钱包、订单、自动红包、任务门槛、重抽、奖品转让或自然人防多号。既有普通帖子、身份、搜索、缓存、互动 MQ、CDC 和 Kafka 统计继续保留。

## 状态与时间边界

| 对象 | 状态流 | 约束 |
| --- | --- | --- |
| 活动 | draft → published → drawn；draft/published 可在截止前 → cancelled | 发布后所有编辑字段冻结；截止后不能取消；无参与者也正常 drawn。展示指针独立，可结束展示或切换已发布/已开奖期次。 |
| 开奖 | 未到期 → 到期待处理 → 唯一正式结果 | 数据库时间、活动行锁及结果主键共同保证；提交前失败可重试，提交后永不重抽。 |
| 领奖 | pending → issuing → paid | 先核验，不自动核销；原开始发放管理员人工核对、线下转账后确认。issuing 不自动回退，paid 不可重置。 |

参与先复用既有会话及账号校验，再取得活动行锁；**取得锁之后**读取 `UTC_TIMESTAMP(3)`，必须严格早于 `draw_at`。校验通过且最终提交才有资格，事务回滚同时回滚参与记录和人数。开奖取得同一活动锁，等待先行事务完成；任务延迟不延长截止。已成功参与后的重试在截止/开奖后仍返回同一参与事实。

资格为截止前通过账号校验且最终提交的全部账号记录；每活动/账号唯一。后续是否在线、活跃度和管理员关系不影响资格，也不事后重新加权。前端只按服务端时间偏移显示北京时间与倒计时，无权确定截止资格、中奖用户或编码。

## 唯一性、随机与恢复

`SecureRandom.nextLong(participantCount)` 在本期按参与记录 ID 排序的范围内选取偏移，算法版本为 `secure-random-bounded-entry-id-v1`。0 人无中奖者，1 人必中奖，多人仅 1 人。记录计划/实际开奖时间、最终人数、中奖用户及算法版本；不使用抽样频率宣称公平证明。

每次扫描最多 16 个到期活动，使用 `idx_lucky_bag_due(status,draw_at,id)`；选取候选只访问该活动的参与索引，不扫描全部历史。活动锁内写入结果、领奖及 `notification_sent=0` 的可靠通知意图，然后提交；无网络调用。两实例可同时扫描，正确性来自 MySQL 行锁、唯一参与、结果主键及唯一领奖约束。

开奖提交后尽快在独立 `REQUIRES_NEW` 事务投递通知；失败仍保留正式结果与原编码，扫描器每批最多补 16 条意图。投递按“用户 → 领奖记录 → 通知”锁序，通知和 sent 标志一起提交；自然事件键和领奖锁防重，不恢复已失活通知、不重置已读、不重复增加未读。通知事件为 `lucky_bag_win`、目标为 `lucky_bag`，分类仍是平台公告，完全不借用互动 MQ。MQ 关闭也能通过 HTTP 补取。

## 编码、权限与人工发放

每个编码由密码学随机源生成 16 字节，回显为 32 位大写 HEX，数据库区分大小写并唯一。复合外键绑定该活动的正式中奖用户。为了本人再次进入仍能复制，编码保存在受控数据库明文字段；保护数据库访问、binlog/备份访问及本地 private 目录同样必要，本版没有新增密钥或另行加密系统。

公开活动接口和后台列表/详情不返回编码。仅登录中奖本人可通过 `/me/lucky-bags/{id}` 读取编码、管理员微信和说明；其他账号返回 `claim:null`，访客 401。私有/福袋接口使用 `Cache-Control: no-store`。编码只在领奖 UI 短期内存中展示，不进入通知正文、URL、统计或公共前端缓存；后台 POST 核验不回显编码，通用审计仅记录受控动作、字段名与标识。

开始发放与确认分别保存管理员、时间、版本和操作 ID。`FOR UPDATE` 与版本检查使并发开始只有一个成功；相同管理员/操作 ID 的响应丢失重试恢复现有事实。确认要求 issuing 且操作人为 started_by；需要换人处理时，本版不提供隐式接管或自动重试转账。程序的 paid 表示管理员作出的人工发放确认，不是转账接口回执。

## 两端与通知历史

小程序入口在原发帖圆钮上方，沿用首页/搜索/通知页条件及抽屉隐藏规则，无新底部导航。共享卡片组件同时服务入口弹层和历史详情页，覆盖加载/提交错误、等待开奖、未中、中奖及发放状态。关闭、离页、切后台清理计时器与私密状态；身份代次阻止旧账号响应回填。临近开奖最多 24 次轮询，不做全局持续轮询。

fixture 有明确 DEMO 标记、参与记录集合、可控时钟和两个演示期次，编码包含 DEMO 且不能被 live HEX 核验。live 失败不回退演示数据。中奖通知只指向活动 ID，新一期上线或结束旧展示均不删除历史记录，点击旧通知仍重新鉴权读取本人信息。

后台 `/admin/lucky-bags` 沿用真实管理员认证、错误提示及通用审计，支持草稿、发布、取消原因、单例展示、结果、编码核验、发放和操作记录。所有金额均为整数 1000 分，页面不宣称自动支付。后台最近列表有界为 100 期，按 ID 的历史接口仍可访问；没有公开参与用户列表。

## 数据库与运行

新增 `V14__lucky_bags.sql`，V1—V13 原文未改。当前精确清单为 41 张业务表；新增 `xl_lucky_bag`、`xl_lucky_display`、`xl_lucky_entry`、`xl_lucky_result`、`xl_lucky_claim`、`xl_lucky_operation`。display 仅有 id=1 的空指针结构种子，迁移不发布奖品。

在既有全库 SELECT 之外，entry/result/operation 仅 INSERT，bag/display/claim 仅 INSERT、UPDATE；六表无 DELETE。保留审计只追加、反馈列授权和原统计最小授权。业务历史使用 RESTRICT 外键；未增加用户外键锁来改变既有账号锁顺序，账号和中奖归属由真实会话校验及复合中奖外键保证。

运行沿用 JDK21、MySQL13306、Redis16379、原 JWT、启动脚本和已修复依赖。`XINKELINK_LUCKY_BAG_ENABLED` 默认 true，控制开奖扫描和通知补投；`XINKELINK_LUCKY_BAG_SCAN_MILLIS` 默认 1000。关闭不会删历史或改截止，恢复后补开所有到期未完成活动，并补投未完成意图。

## 证据与复跑

原始证据统一索引为 `.local/lucky-bag-20261117/final-verification.json`。完整文件归属、接手 SHA、增量与保留核对在同目录 `baseline*.json`、`task-files.json`、`incremental.patch`。所有复跑使用既有保护凭据文件，禁止把参数或 private 内容打印到日志。

```powershell
. .local/lucky-bag-20261117/test-environment.ps1
# 在 apps/server：完整普通和真实隔离数据库回归；显式 byte-buddy agent 路径见该脚本。
mvn "-Dxinkelink.build.directory=$env:LUCKY_BUILD" "-DargLine=-javaagent:$env:LUCKY_AGENT" test
mvn "-Dxinkelink.build.directory=$env:LUCKY_BUILD" "-DargLine=-javaagent:$env:LUCKY_AGENT" -Pdb-integration verify
# 上述命令之后若重打包产生新hash，真实专项必须针对新的固定hash重新运行。
# 回到仓库根：双JVM专项会核对hash、隔离库名/端口和所有临时进程归属。
& .local/lucky-bag-20261117/live-run.ps1 -ExpectedSha256 (Get-FileHash apps/server/target/lucky-bag/xinkelink-server-0.1.0-SNAPSHOT.jar).Hash
& apps/server/src/test/resources/schema-contract.ps1
& .local/lucky-bag-20261117/verify-artifact.ps1
git diff --check
```

小程序和后台分别在各自目录执行完整 `npm run check`。微信原生与后台真实浏览器复跑脚本、固定产物及本轮服务状态见证据索引。备份/迁移脚本仅接受已命名隔离库和 xinkelink_dev，先备份再迁移授权；开发库只应用结构，不创建真实活动或中奖样例。

验收边界：本地命名隔离数据库与真实打包 JVM；真实用户会话为测试夹具建立的数据库会话，未冒充官方微信网络换码。原生开发者工具验证不等于物理手机验证。不保证跨实例 WebSocket 设备必达，不声明自然人多号识别、公开可验证密码学抽奖或生产 HA。

原生工具已展示关键卡片状态、实际弹层层级与两期历史，并以真实隔离会话检查私密读取和换号隔离；自定义 tab 内部的指针事件穿透尚未完成原生手势自动验证，不将 handler 调用或 WXML `catchtap` 检查冒充真实点击通过。详细手势/长内容滚动实情见 `mini-native-popup-results.json`；首次超时、初始化会话及工具选择器失败证据均保留。

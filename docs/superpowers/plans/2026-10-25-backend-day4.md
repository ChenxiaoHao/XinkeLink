# Day 4 私信、治理与公告通知实施计划

依据docs/backend/01～08及用户直接连续实施指令。复用当前工作区全部未提交成果；不提交、不重置、不清理，不改前端。既有设计已确定，本轮不重复技术选型或追加逐步审批。

## 全局约束

- Java21/Boot4.1.1/MySQL8.4，复用24表和已有鉴权；Redis不可用不阻断核心业务，登录限流故障策略不降级。
- 消息先事务提交再推送；用户对唯一，消息请求幂等，DecimalSequence字符串，HTTP可独立补取。
- 举报/目标/业务历史/站内提示同事务，已处理不重复，永久封禁不可被其他措施覆盖，临时到期保留人工通道。
- 最新要求包括评论举报，在既有R01补comment；不开放普通用户私信案件接口，不增前端入口。
- 图片只补本轮report用途和案件证据授权，保留post/product原有规则。
- 公告与站内任务只写实际收件记录，不调用外部微信推送；最多5000受众，严守首次发布去重和受众指纹。
- 治理管理命令在同一事务追加业务处置历史，作为本轮可追溯操作记录；独立安全审计机制及其查询/设置/其他管理API按既定设计保留Day5，不混淆两种记录。

## 任务与边界

1. 审查、保存现有文件摘要、复跑217基线；确认公共接口和无破坏迁移方案。
2. 私信HTTP：messaging/**，C01～C10，独立真实MySQL隔离/幂等/水位/并发测试。
3. 治理：moderation/**，R01/AR01～03/AU06～08，目标联动、截图快照、限制及业务历史测试。
4. 公告/通知任务：operations/**，AA01～07/AN01～06/N04～05，状态、指纹、去重与事务测试。
5. 公共通知：notification/**，N01～03/C11，Day3互动触发，匿名与不可见内容安全投影、接收者隔离。
6. 实时：realtime/**，Authorization鉴权、仅当前用户事件、实时会话失效关闭、提交后推送及失败隔离；report图片访问。
7. 合并验证：JDK21完整verify、真实MySQL/HTTP/WebSocket、并发、Day1～3回归、范围外摘要及09总结。

并行任务严格按目录分工；共享配置、错误码、ContentSupport、媒体、原论坛/商品联动由协调者单独编辑。每模块先RED再实现GREEN，独立构建目录避免报告覆盖；最后统一完整回归，不以代理报告代替实际结果。

## 共享接口

- RealtimeEvents.afterCommit(Runnable)，publish(long userId,String type,Map<String,Object> payload)。事件只在提交成功后发送，失败不改变业务结果。
- NotificationService.unread(AuthPrincipal)；moderation(long,String,String,String,String,Long)；announcement(long)；systemTask(long,List<Long>)。事务写入，内部安排提交后事件。
- ContentSupport.requireAdmin(AuthPrincipal)：复核有效管理员主体，返回服务器侧ID。

## 交付

docs/backend/09-day4-messaging-moderation.md记录实际接口/文件、测试数量、真实HTTP及WS证据、Redis/微信/前端联调边界、缺口和Day5依赖。完成本范围后停止，不部署。

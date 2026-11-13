# Day3论坛与交易实施计划

依据docs/backend/01～07及本轮用户直接实施要求。使用当前codex分支，保留未提交成果，不创建替代工程，不执行Git提交/重置/清理。

## 约束与决定

- Java21/Boot4.1.1/MySQL8.4，复用JWT、统一响应、已有24表，不改前端或已应用迁移。
- 核心业务不依赖Redis；既有登录限流失败503策略不变，测试凭据只在测试中准备。
- 最新要求包含帖子编辑，因此在旧F01～F15旁补F16 PATCH /posts/{postId}：本人、expectedVersion、content/category/imageIds可改，匿名属性不变；不增加前端编辑器。
- 图片是发布前置依赖：I01～I04先支持post/product及JDK支持的PNG/JPEG/GIF，安全重编码PNG，本地非静态存储；其他用途/格式明确拒绝，不伪称已支持。
- 规范字段、匿名投影、两级评论、商品终态、幂等及版本要求均按04；不接Day4消息/通知，不实现管理API。

## 任务与接口

1. 审查与基线：阅读设计、迁移和前端模型，保存范围外335文件摘要；复跑147项既有测试。
2. 公共基础：content/ContentSupport、ContentCursor、ContentMapper；测试权限、版本、If-Match、签名游标绑定筛选/身份及失效。补错误码和仅GET公开白名单。
3. 媒体依赖：media/MediaService、Mapper、Controller及LocalImageStorage；上传校验、归属/引用、60秒授权及再次可见性校验。文件/SQL失败应回滚或补偿。
4. 论坛：forum/**，F01～F15，批量作者/图片/互动计数；事务内创建幂等、评论根关系、软删及赞藏唯一。完成后补F16及相关测试。
5. 交易：market/**，P01～P08，批量卖家/图片、分类与价格keyset、本人列表counts、终态与治理保护。
6. 联合查询：content/DiscoveryService/Controller，Q01/Q02及U06～U09，复用两模块查询/投影，收藏失效占位不能泄露匿名归属。
7. 验证与总结：真实MySQL/HTTP、并发、图片、分页、匿名/越权及完整回归；独立审查后修复明确缺陷；输出08总结并停止。

并行边界：论坛代理只拥有forum及对应测试，交易代理只拥有market及测试，媒体代理只拥有media/LocalImageStorage及测试，协调者负责content/安全配置/文档/跨模块验收。公共Java方法签名已逐个发给代理，不以共享目录同时编辑同一文件。

## 验证方案

测试先失败再实现；各代理Maven构建目录独立，最终统一运行scripts/verify-backend.ps1 -Database。重点覆盖未登录写入401、跨用户403/404、匿名在详情/评论/收藏/图片不泄密、重复请求同结果/异payload409、并发关系唯一、删除版本428/409、商品状态竞争仅一次成功、游标改过滤/身份400和同时间边界、LIKE通配符作为普通字符、图片越权/失效/父隐藏访问拒绝。实际HTTP服务与真实MySQL验证不等同于微信真机联调。所有凭据从环境读取，测试夹具回滚或精确按随机身份清理。

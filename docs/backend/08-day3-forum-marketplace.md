# Day 3：论坛与二手交易核心业务开发总结

实施时间：2026-10-24至2026-10-25；最终完整回归：2026-10-25 10:29（Asia/Shanghai）。依据[01～05设计](04-api-contract.md)、[Day 1](06-day1-foundation.md)、[Day 2](07-day2-authentication.md)及本轮明确授权。

本轮完成论坛、评论、互动、二手商品、图片依赖及相关查询。Java 21、Spring Boot 4.1.1、MySQL 8.4保持不变；复用既有JWT、会话、统一响应和数据库。没有修改小程序、管理后台、认证业务、已应用迁移或Maven依赖；没有执行Git commit、push、reset、clean，没有进入Day 4。

## 1. 实际完成范围

新增 **34个HTTP端点**，与Day 1两个健康端点、Day 2八个认证端点合计44个。统计按HTTP Method＋路径计算，不把同一路径不同Method合并，也不把完整规划当成实现。

| 模块 | 本轮数量 | 契约及实际接口 |
| --- | ---: | --- |
| 帖子、评论、互动 | 16 | F01～F16，见下表 |
| 二手商品 | 8 | P01～P08，见下表 |
| 图片 | 4 | I01～I04，仅完成post/product用途 |
| 联合查询、本人及公开资料查询 | 6 | Q01、Q02、U06～U09 |

| HTTP Method | 路径（均以`/api/v1`开头） | 行为 |
| --- | --- | --- |
| GET / POST | `/posts` | 分类、关键词、推荐/最新、游标列表；发布帖子 |
| GET / PATCH / DELETE | `/posts/{postId}` | 详情、本人编辑、本人软删除 |
| GET | `/me/posts` | 本人的帖子，包含本人匿名及治理状态 |
| PUT / DELETE | `/posts/{postId}/like` | 点赞、取消点赞 |
| PUT / DELETE | `/posts/{postId}/favorite` | 收藏、取消收藏 |
| GET / POST | `/posts/{postId}/comments` | 一级评论列表、发布评论或回复 |
| GET | `/comments/{commentId}/replies` | 一级评论下的回复列表 |
| DELETE | `/comments/{commentId}` | 本人删除评论；删根评论同时软删回复 |
| PUT / DELETE | `/comments/{commentId}/like` | 评论点赞、取消点赞 |
| GET / POST | `/products` | 分类、关键词、排序、筛选、游标列表；发布商品 |
| GET / PATCH | `/products/{productId}` | 商品详情、本人编辑 |
| POST | `/products/{productId}/status` | 本人下架、售出及符合条件的重新上架 |
| GET | `/me/products` | 本人商品列表及出售状态数量 |
| PUT / DELETE | `/products/{productId}/favorite` | 收藏、取消收藏 |
| POST | `/images` | multipart上传，返回201及imageId |
| GET | `/images/{imageId}/access` | 获取短期图片访问授权 |
| GET | `/images/{imageId}/content?grant=…` | 鉴权后输出图片字节，不使用公开静态目录 |
| DELETE | `/images/{imageId}` | 本人删除尚未被引用的图片 |
| GET | `/search` | 帖子或商品搜索，复用各模块可见性与分页 |
| GET | `/me/favorites` | 本人帖子或商品收藏，保留失效目标占位 |
| GET | `/me/summary` | 本人帖子、闲置和收藏数量 |
| GET | `/users/{userId}` | 公开资料及可公开的数量 |
| GET | `/users/{userId}/posts` | 他人的公开非匿名帖子 |
| GET | `/users/{userId}/products` | 他人的公开商品 |

旧契约曾将帖子编辑列为非首版，本轮最新要求明确包含编辑，因此最小增补 **F16 PATCH**，没有新增前端编辑器。完整规划同步为110个HTTP端点（100个M、10个S），不是110个已实现端点。

### 1.1 论坛与评论

- 帖子正文最多2000字、图片最多9张；分类沿用`campus/lost_found/study/help`。匿名标记创建后不可修改；数据库始终保存真实作者。
- 公开匿名投影不输出真实作者ID、昵称、头像；匿名作者在自己的评论、回复及replyTo中继续匿名。公开个人帖子列表和数量排除匿名帖子，本人列表不丢失自己的匿名内容。
- F16只允许`content/category/imageIds`及必填`expectedVersion`，至少修改一项。不能覆盖作者、匿名属性、治理状态、创建时间或创建幂等摘要。
- 评论最多1000字；回复到二级评论仍归同一一级根评论。根评论倒序、回复正序，校验根与目标当前可见性；删除父帖或根评论后不留下公开孤立回复。
- 帖子与评论删除要求`If-Match: "版本号"`；缺失返回428，陈旧版本409。发布、评论、编辑检查本人身份和有效通道限制，用户不能修改他人内容。
- 点赞、收藏使用唯一关系并幂等返回`changed`；重复操作不重复计数。取消本人关系不因目标下线而永久失败。

### 1.2 二手商品

- 标题最多60字、描述最多1000字、1～9张图片；`priceFen`为1～9999999的整数分，成色为100/99/95/90/80。整数不能通过JSON小数自动截断。
- `available → off_shelf / sold`；`off_shelf → available`要求内容治理状态允许；`sold`为终态，禁止编辑或恢复在售。版本条件更新防止并发覆盖。
- 交易推荐默认不展示已售商品，下架商品不进入公开列表；指定分类或关键词查询可展示已售记录。历史详情及收藏保留出售状态；治理隐藏/删除对公众返回安全占位，不泄露处理原因或卖家私有资料。
- 卖家信息、价格、图片、收藏和状态来自同一MySQL记录。没有订单、在线支付、物流、担保交易或“我想要”私信实现。

### 1.3 数据一致性与查询

- Service事务与参数化Mapper复用既有24张业务表，没有新增表或迁移。锁顺序统一为当前用户、帖子/商品、排序后的图片；写入时复核账号/通道限制。
- 内容创建使用`clientRequestId`＋规范化请求摘要；同ID同内容返回同一资源，不同内容返回409。创建后的编辑不破坏原始创建请求的幂等性。
- 点赞、收藏依靠数据库唯一约束；图片绑定与父内容同事务，绑定失败回滚正文和引用。图片替换只解除父引用，不误删举报保全引用。
- 列表按页批量读取作者、图片与指标。实际查询计数测试从1条增加到20条（不同作者和图片）时查询数相同且有界，不采用逐卡查询。
- 游标绑定筛选、排序与viewer，有签名、1小时期限和快照上界；默认20条，最多50条。快照时间使用数据库UTC时钟，避免JVM与数据库微小偏差遗漏刚创建的数据。
- 搜索为转义通配符的参数化LIKE，不匹配匿名真实作者。统一搜索空白关键词400，无结果返回空页；未新增搜索历史、热门或建议接口。

### 1.4 图片实现及明确边界

- 保留`ImageStorage.store/delete`厂商接口，新增本地实现及媒体访问层。目录通过`XINKELINK_IMAGE_DIRECTORY`配置，默认仓库`.local/backend-images`，不作为静态资源公开。
- 本轮仅开放`purpose=post/product`。实际输入流和重编码结果均限制10MiB，检查签名、可解码性、单边8192及总像素4000万；PNG/JPEG/GIF重编码为PNG，去除EXIF等元数据。GIF仅保留静态帧。
- WebP、HEIC返回415；avatar/report用途明确拒绝，不声称完整媒体功能已交付，也未安装额外编解码依赖。
- 上传临时资源24小时有效；绑定核对归属、用途、数量、重复、到期和既有父引用。已绑定资源不能任意跨内容复用，避免匿名与所有权混淆。
- 访问授权60秒，使用加密载荷及签名，不在URL明文携带作者、会话或存储路径。私有访问绑定当前会话，公开访问不暴露所有权；输出字节时再次核对引用、可见性和会话。
- 响应包含`Cache-Control: private, no-store`和`nosniff`。合法管理员访问公开资源按访客投影处理，不能借同数值ID获得学生所有权。
- 未实现过期/孤立文件定时物理清理；提供本人未引用图片删除接口。生产存储厂商、清理任务和其他用途仍待后续授权。

## 2. 实际验证结果

在仓库根目录执行既有脚本，没有安装新依赖或更换系统JDK：

```powershell
pwsh -NoProfile -File scripts/verify-backend.ps1 -Database
```

| 检查 | 本轮实际结果 |
| --- | --- |
| JDK21编译、打包及完整Maven verify | **BUILD SUCCESS**；2026-10-25 10:29:02完成 |
| 单元及基础HTTP测试 | **126项**，0失败、0错误、0跳过 |
| 真实数据库集成测试 | **91项**，0失败、0错误、0跳过 |
| 合计 | **217项**；保留原Day 1/Day 2共147项，新增Day 3共70项 |
| MySQL与Flyway | 本地MySQL **8.4.13，13306端口**；校验既有5个迁移，版本5，无需执行迁移 |
| 论坛Service | 13项；发布、编辑、匿名、权限、两级评论、软删除、幂等、互动和分页 |
| 商品Service、HTTP及事务 | 18＋7＋3项；整数校验、排序、筛选、版本、售出终态、治理可见性、图片回滚及竞争 |
| 论坛并发与查询计数 | 4项；4线程同请求仅1帖子/引用，同关系只有1次changed，根评论删除/回复竞争无孤立回复，1与20条列表查询数一致 |
| 图片 | 4项本地存储＋1项授权＋7项HTTP；格式、大小、元数据、归属、引用、短期授权及实时权限 |
| 公共支持及联合查询 | 6＋3项；权限/版本、签名游标、搜索、收藏与公开资料 |
| Day 3真实TCP HTTP | 4项，最终Tomcat随机端口 **58937**；Java HTTP客户端访问真实服务器、MySQL、JWT、安全过滤器和事务 |
| 原认证真实TCP回归 | 2项，最终随机端口58923；测试替身边界仍与Day 2相同，不等同真实微信/Redis联调 |
| 接口注册复核 | `/api/v1`下43个Method＋路径，另有Actuator健康端点，合计44；没有打开后续模块接口 |
| Redis连接探测 | 127.0.0.1:16379及6379均连接失败；Day 3真实业务HTTP未替换Redis限流器，持有有效数据库会话即可访问核心业务 |
| 数据及保留检查 | 测试夹具回滚或按随机身份精确清理；抽查用户、管理员、会话、帖子、评论、商品、赞藏、图片、引用均0行。原范围外335文件摘要一致，未改前端成果 |

真实HTTP覆盖：匿名帖子发布/详情/编辑/删除，评论与回复、点赞收藏、商品发布/编辑/下架/恢复/售出、图片上传及访问、未登录写入、缺少参数与文件、非法请求及接口范围。测试中的身份由既有受信AuthService准备随机夹具；没有新增产品登录旁路，没有把客户端openid作为可信身份。

日志保留在忽略目录：`.local/backend-day3/final-verify.stdout.log`、`final-verify.stderr.log`、`final-verify-command.log`、`redis-probe.json`；JUnit XML在`apps/server/target/backend-dev/surefire-reports/`及`failsafe-reports/`。测试服务器随进程退出关闭，**既有18080端口的Day 1服务没有替换，不能把它当成新业务版本**。

验证目录保留了一份2026-10-24 23:20的单项探针旧报告（Surefire下`AuthHttpIT`，1项失败），没有删除历史证据。最终结果依据本次Maven实际执行列表及对应插件报告统计，不把旧探针混入；已将本次23个测试类的XML单独复制至`.local/backend-day3/final-reports/`，其中`summary.json`复核为217项、0失败/错误/跳过。当前完整回归的`AuthHttpIT`在Failsafe执行19项且全部通过。

### 已定位并修复的回归问题

1. JSON小数价格曾被截为整数：关闭整数浮点强制转换，真实HTTP验证400。
2. 数据库比JVM快数毫秒时，新商品可能落在游标快照之外：使用同一数据库时钟，测试验证立即列表可见。
3. 合法管理员读取公开商品图片曾因学生角色限制失败：按公开访客投影，保留私有资源拒绝规则，完整回归通过。
4. 缺少查询/上传参数曾返回500、图片创建曾返回200：统一400和201，真实TCP回归通过。
5. 图片重编码超限可能被ImageIO包装异常掩盖：保留明确超限标记，返回413并清理失败文件。

## 3. Day 3新增与修改文件

以下相对于`D:\CODE\XinKeLink`，只列本轮成果，不把现有Day 1/Day 2未提交文件重复归为本轮修改。

新增生产代码20个文件，根目录`apps/server/src/main/java/cn/xinkelink/`：

- `content/`：`ContentSupport.java`、`ContentCursor.java`、`ContentMapper.java`、`DiscoveryService.java`、`DiscoveryController.java`。
- `forum/`：`ForumModels.java`、`ForumMapper.java`、`ForumService.java`、`ForumController.java`。
- `market/`：`MarketModels.java`、`ProductRow.java`、`MarketMapper.java`、`MarketService.java`、`MarketController.java`。
- `media/`：`MediaContentReader.java`、`MediaGrant.java`、`MediaService.java`、`MediaController.java`、`persistence/MediaMapper.java`。
- `storage/LocalImageStorage.java`。

新增测试12个文件，根目录`apps/server/src/test/java/cn/xinkelink/`：

- `content/`：`ContentSupportTest.java`、`ContentCursorTest.java`、`DiscoveryServiceIT.java`、`Day3HttpIT.java`。
- `forum/`：`ForumServiceIT.java`、`ForumConcurrencyIT.java`。
- `market/`：`MarketServiceIT.java`、`MarketHttpIT.java`、`MarketTransactionsIT.java`。
- `media/`：`LocalImageStorageTest.java`、`MediaGrantTest.java`、`MediaHttpIT.java`。

修改既有文件6个：

- `apps/server/src/main/java/cn/xinkelink/common/api/ErrorCode.java`：本轮业务错误码。
- `apps/server/src/main/java/cn/xinkelink/common/web/ApiExceptionHandler.java`：multipart超限及缺参错误响应。
- `apps/server/src/main/java/cn/xinkelink/config/SecurityConfiguration.java`：精确GET公开白名单；写入和后台权限继续隔离。
- `apps/server/src/main/resources/application-auth.yml`：上传容器限制和媒体SQL日志保护。
- `apps/server/src/main/resources/application.yml`：禁止小数强制转换为整数。
- `apps/server/.env.example`：仅增加本地图片目录变量，不含真实密钥。

文档新增/更新：`docs/backend/02-backend-architecture.md`、`04-api-contract.md`、`05-development-roadmap.md`、本文件，以及`docs/superpowers/plans/2026-10-24-backend-day3.md`。本地进度、证据及保留摘要只放在忽略目录`.local/backend-day3/`。

## 4. 运行方法与环境边界

沿用Day 1隔离MySQL与Day 2环境变量。`.env.example`不会自动加载，JWT随机密钥、数据库账号等只能由环境或受控启动配置注入。按既有方式准备环境后，在空闲端口启动：

```powershell
pwsh -NoProfile -File scripts/start-backend-dev.ps1 -Build -Port 18081 -Profiles db,dev,auth
```

本轮没有为交付另行常驻启动18081。现有数据库已初始化，不要重新初始化或清理；已有端口的旧服务保持原样。

Day 3业务只依赖MySQL及有效JWT会话，不强制依赖Redis；**Day 2登录/刷新仍在Redis不可用时返回503**，没有降低既有限流安全策略。无有效会话的真实登录联调仍需Redis7和真实微信凭据，不得用测试准备身份的方式替代产品登录。

## 5. 未完成项及Day 4依赖

- 真实微信换码、Redis7实际Lua/TTL、HTTPS/WSS、真机与前端适配均未验收；两前端仍保持本地Mock，没有声称已跨端联调。
- 私信/会话/“我想要”、举报审核、限制变更接口、公告及通知尚未实现，保留Day 4范围。本轮写接口已经读取有效限制，后续通过统一规则写入限制数据。
- Day 4举报处置需复用帖子/评论/商品的可见性及事务规则；商品治理状态与出售状态分离，不能让售出商品重回在售。
- 评论、回复、赞藏的站内通知尚未生成；不能把已实现互动计数当成通知送达。Day 4应在业务事务与接收记录之间明确一致性。
- 私信商品卡片须沿用本轮商品ID与安全占位；已售/下架状态不能被聊天快照覆盖。WSS仅在消息持久化成功后推送。
- 管理API、统一服务端审计、系统设置及全面双端适配保留Day 5，不把当前前端内存审计当成正式记录。
- 图片生产服务、avatar/report用途、WebP/HEIC和过期文件清理待后续明确实现；推荐目前为最新排序简化实现，没有推荐算法。

## 6. 对应Java学习重点

- Controller只处理参数、身份和响应；Service负责权限、事务与状态机，Mapper执行参数化SQL，避免在Controller堆业务。
- `@Transactional`与真实提交、READ_COMMITTED、`FOR UPDATE`、版本条件和唯一索引各解决不同并发问题；重复请求需要业务幂等键，不能只靠接口返回成功。
- DTO校验保护输入，响应投影保护匿名与隐私；数据库保存真实作者不意味着公开接口可以返回。
- 分页批量查询与keyset游标、图片流限制及异常补偿、单元/数据库/真实HTTP测试的职责区别均有对应代码和验收证据。

Day 3范围已完成并通过上述本地验收，到此停止；真实环境缺口不以测试替身或健康接口代替。

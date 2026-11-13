# 信科 Link REST 与实时事件契约

实施状态（2026-11-11）：当前为纯校园社交社区。本文列出105个HTTP端点（104个 `/api/v1/` 方法及1个Actuator健康端点）和1个WebSocket端点。接口存在不等于完整产品验收，既有验证边界见[最终审计报告](../11-final-audit-report.md)。删除范围、数据迁移与验证见[二手模块移除数据库报告](19-marketplace-removal-database.md)。历史阶段报告保持原样。

制定日期：2026-10-23；实施状态复核：2026-11-11。本文件定义完整契约，实际验收以上方实施状态及最终审计为准。统一取代旧`docs/v1-backend-interface-checklist.md`中未版本化的建议路径，保留其业务原则。

本文件定义当前有效契约。M=当前前端接入必需；L=旧数据兼容；S=可分批接入。未验证的能力不能标为已验收。当前数据库以Flyway迁移及[移除报告](19-marketplace-removal-database.md)为准，[03](03-database-design.md)保留早期设计背景。

当前互动规则：论坛只保留点赞，`我的点赞`仅查询帖子；旧帖子favorite方法仅兼容访问，不作为新界面行为。二手功能及关联数据已移除，没有兼容API或页面入口。

## 1. 通用规范（适用于每一行）

### 1.1 路径、响应与时间

- 小程序前缀`/api/v1`，管理员前缀`/api/v1/admin`；下表完整列出路径。纯JSON字段camelCase，枚举小写snake_case；ID为字符串形式的不透明数据库ID，不能使用学号/掩码学号/客户端local ID。
- JSON成功：`{code:"OK",message:"成功",data:T,requestId:"…",timestamp:"2026-10-24T01:00:00.000Z"}`。创建资源201，查询/命令/删除200；删除返回结果而非204。H01/H02保持既有健康格式；CSV、图片字节、WS协议是明确例外。
- JSON失败：`{code:"VERSION_CONFLICT",message:"记录已更新，请刷新后重试",data:null,errors?:[{field:"expectedVersion",code:"conflict",message:"…"}],requestId,timestamp}`。不回显密码、token、输入正文、数据库SQL或堆栈。
- 所有时间ISO8601 UTC毫秒；无值null，不混用空串/epoch/“昨天”。日期控件由客户端按Asia/Shanghai转成UTC起止，`from`含起点、`to`不含终点；`from>=to`拒绝。按“今日”统计固定Asia/Shanghai。
- 版本号为非负整数。帖子分类统一为campus/lost_found/study/help，小程序原有lost-found在适配边界转换。所有消息序号/水位使用`DecimalSequence`（规范十进制字符串，正则`^(0|[1-9][0-9]{0,19})$`且≤18446744073709551615；水位可为"0"，消息序号≥"1"），不作为JS浮点数处理。客户端按长度再按字典序比较或使用安全BigInt封装。新增时间、作者/操作者和状态由服务端生成，拒绝客户端越权赋值。

### 1.2 身份与权限代号

| 代号 | 每行所需权限 |
| --- | --- |
| U | 有效微信用户会话；针对动作额外校验账号和publish/comment/message通道限制 |
| O | U且本人所有；服务端按真实owner判断，匿名显示不影响所有权 |
| C | U且为指定会话成员，不能凭conversationId越权 |
| A | 有效管理员会话、actorType=admin且ROLE_ADMIN；普通用户token不能使用 |
| T | 有效refresh会话；admin另需HttpOnly cookie、受信Origin和CSRF token |

封禁不表示学校认证。所有admin写操作由服务端取当前管理员名称；不使用前端传来的“林教员”。系统日志/设置无普通用户访问权限。

### 1.3 分页与排序

- `CursorPage<T>={items:T[],nextCursor:string|null,hasMore:boolean}`；Q=`cursor?,size?`，默认20、最大50。cursor绑定筛选、排序和viewer，错误/改过滤条件继续用旧cursor返回400 CURSOR_INVALID。keyset最终带id，支持快照上界；并发内容变化后允许重新刷新，不承诺旧游标反映新结果。
- `Page<T>={items:T[],total:number,page:number,pageSize:number}`；admin Q=`page=1,pageSize=10`，允许10/20/50（最大50），超范围400；筛选变化回page1。越界页返回空items和真实total，不自动伪造条目。
- `AdminList<T>={...Page<T>,stats:object}`；stats是该模块**未软删全局记录**统计，result.total是当前筛选命中数，不能混淆。
- 默认：帖子/评论`createdAt desc,id desc`；用户`joinedAt desc,id desc`；公告/管理通知`updatedAt desc,id desc`；审计`createdAt desc,id desc`；举报`pending优先、high→medium→low、createdAt desc,id desc`。
- 一级评论`createdAt desc,id desc`；二级`createdAt asc,id asc`；消息`sequence asc`（历史倒向取页后响应仍正序）；会话`pinned desc,lastMessageAt desc,id desc`。每行特殊sort覆盖默认。
- 搜索q/keyword trim后≤60；空白q的统一搜索返回400，普通列表keyword空白等于无搜索。首版参数化LIKE包含匹配（转义`%/_`），不用未批准的搜索引擎。用户搜索不匹配匿名真实作者。

### 1.4 通用错误E0与业务错误

每个端点除表内列出的附加错误外，均适用E0：400 VALIDATION_FAILED/CURSOR_INVALID、401 UNAUTHENTICATED/TOKEN_EXPIRED、403 FORBIDDEN/ACCOUNT_BANNED/ACTION_RESTRICTED（按权限要求）、404 NOT_FOUND、429 RATE_LIMITED、503 SERVICE_UNAVAILABLE、500 INTERNAL_ERROR。P端点可忽略未提供身份；**提供了无效Bearer不能当作已登录身份返回私人状态**。

| HTTP | 业务码 | 含义 |
| --- | --- | --- |
| 400 | UNSUPPORTED_TARGET / INVALID_RANGE | 未开放目标类型/时间排序组合不合法 |
| 401 | INVALID_CREDENTIALS / REFRESH_INVALID | 登录失败统一提示/refresh已撤销或复用 |
| 403 | IMAGE_FORBIDDEN / MESSAGE_BLOCKED | 资源或当前屏蔽规则拒绝，不泄露他人隐私 |
| 404 | TARGET_UNAVAILABLE | 私有/治理隐藏目标对无权限者不可读；收藏/消息引用可按下文返回占位 |
| 409 | VERSION_CONFLICT / STATE_CONFLICT / STATE_UNCHANGED | 并发版本冲突、非法状态、管理命令没有实际改变 |
| 409 | REPLY_TARGET_INVALID / ALREADY_PROCESSED | 回复目标失效、举报已处理 |
| 409 | DUPLICATE_REQUEST_CONFLICT / REPORT_PENDING / AUDIENCE_CHANGED | 同请求ID不同内容、已有待处理报告、受众发生变化 |
| 409 | IMAGE_IN_USE | 试图直接删仍有有效引用的图片 |
| 413/415 | FILE_TOO_LARGE / UNSUPPORTED_MEDIA_TYPE | 图片字节/格式不合法 |
| 422 | NO_RECIPIENTS / AUDIENCE_TOO_LARGE / EXPORT_TOO_LARGE | 无接收人、同步站内接收人>5000、同步导出>10000 |
| 502/503 | WECHAT_LOGIN_FAILED / WECHAT_NOT_CONFIGURED / STORAGE_UNAVAILABLE | 换码失败、服务端微信配置缺失或存储暂不可用；不回显微信密钥/原错误响应 |

匿名公共资源不存在与无权限可统一404避免枚举；公开目标被移除不影响取消本人收藏/点赞。403禁止写与404隐藏目标按资源场景执行，不以错误码泄露真实匿名身份。

### 1.5 幂等、版本、批量与确认

- 创建帖子/评论/举报/管理员公告草稿/通知草稿带`clientRequestId`（1–64 ASCII不透明值）；消息带`clientMessageId`。相同主体+ID相同初次payload重试返回同一资源及其当前状态；不同payload409。每表以owner+请求ID唯一，保存初次规范化请求摘要；后续编辑不改摘要。客户端时间/随机业务ID不能当最终记录ID。
- 管理命令及帖子编辑/状态/配置更新带`expectedVersion`。仅实体删除F04/F13/AP05/AA07用`If-Match:"{version}"`；缺少为428 PRECONDITION_REQUIRED。关系取消U13/F07/F09/F15及仅未绑定临时图片删除I04不要求版本，按归属/引用锁验证且幂等；logout同样不要求。批量删除由items的expectedVersion控制。成功返回必要的新version，读到旧页的操作不能覆盖他人变化。
- `BatchCommand={items:[{id,expectedVersion}],targetStatus?,reason?}`，1–100个去重ID。先检查权限/版本/状态；有缺失、冲突或状态不合法项则整个请求失败、零更改。合法但已是目标态的项可skip；全部未改变返回409 STATE_UNCHANGED。
- `BatchResult={requestedCount,changedCount,skippedCount,items:[{id,outcome:"changed"|"unchanged",code,newVersion}]}`；事务整体成功才200。单行状态操作复用长度1的命令；不额外制造一套状态接口。批量一个审计事件，actual count不能等于默认所选数量。
- `MutationResult={id,changed:boolean,version,...必要最终状态}`。点赞/收藏的PUT/DELETE天然幂等，重复是200 changed=false，**不是**管理状态规则拒绝。相同设置保存/重置无变化不增版本、不重复记日志。
- 删除、封禁、举报处理、发布/撤回、模拟环境改成真实站内发送及恢复默认都需前端二次确认；服务端不能把`confirmed:true`视为权限凭据。契约仅在高风险命令显式要求该布尔用于防误触，仍执行全部鉴权。
- 每个HTTP请求有requestId，管理命令统一产生一个operationId；不把用户任意提供的同名ID当跨业务万能去重票据。举报/通知发送/创建等的业务幂等由状态锁/实体唯一约束保证，审计自身operationId唯一只负责防重复记录。

## 2. 统一DTO字典

下列对象均是JSON data内容；表中`→ X`意味着`data:X`，不重复写外层响应。`?`是可选请求字段，响应缺值统一null。公共模型不返回数据库Entity全部字段。

| DTO | 字段/规则 |
| --- | --- |
| UserTokens | accessToken:string、expiresIn:900、refreshToken:string、refreshExpiresIn:number、me:Me；refresh轮换后替换旧票据 |
| AdminTokens | accessToken、expiresIn、csrfToken、admin:AdminMe；refresh仅HttpOnly cookie，不在JSON中回传 |
| Me | id、nickname、avatar:ImageAccess\|null、bio、major、className、enrollmentYear:string\|null、selfReported:true、status、restrictions:{publish,comment,message}、version；**无studentId/openid/session_key** |
| PublicUser | id、nickname、avatar、bio、major、className、enrollmentYear、selfReported:true、stats:{postCount,likeCount}；统计只含公开非匿名内容 |
| UserSummary | id、nickname、avatar:ImageAccess或null、major、className、selfReported:true；仅已公开普通身份，用于会话对端，不用于匿名帖子作者 |
| MeSummary | postCount含本人匿名、likedPostCount、receivedLikeCount（帖子获赞数）；favoritePostCount仅历史兼容；均从统一有效记录派生 |
| Preferences | messages/interactions/announcements:boolean、sound:boolean、version；不包含系统订阅消息凭证 |
| ImageMeta | id、purpose、mediaType、byteSize、width、height、state、expiresAt:null\|string；无原文件名/存储key |
| ImageAccess | imageId、url、expiresAt、width:number、height:number（均为安全展示图的正整数像素）；URL为短期代理grant，失效重新获取，不能持久化URL代替ID |
| PublicAuthor | id:string\|null、nickname、avatar、isAnonymous:boolean；匿名时id=null、昵称为帖子匿名号、头像默认，其他身份字段不存在 |
| PostCard / PostDetail | id、category、content、images:ImageAccess[]、author:PublicAuthor、createdAt、likeCount、commentCount、liked、favorited、isMine、version；Detail再含permissions:{canDelete,canComment}，公开无真实匿名作者 |
| OwnerPostDetail | PostDetail + availability:available/hidden/pending/deleted、status:visible/hidden、reviewState:normal/pending、moderationReason:null或string（只给本人可告知的摘要，无历史原因时null）；author仍匿名投影，不需下发他人的真实身份 |
| Comment | id、targetType、targetId、rootId:null\|string、replyToId:null\|string、author:PublicAuthor、replyTo:{commentId,name}\|null、content、createdAt、likeCount、liked、isPostAuthor、isMine、version；回复匿名作者保持匿名 |
| CommentThread | Comment + replyCount + replies:Comment[]（首批默认1）；回复分页独立读取；根被隐藏时公开不返回子回复 |
| Favorite | type:post、targetId、favoritedAt、availability:available/unavailable、item:PostCard或null；无效帖子item为空且可取消收藏 |
| Conversation | id、peer:UserSummary、lastMessage:Message或null、updatedAt、unreadCount、pinned、muted、version |
| Message | id、conversationId、sequence:DecimalSequence、clientMessageId、senderId、isMine、kind:text、text、createdAt、persistenceStatus:"sent"、visibility:normal/hidden；hidden时senderId/clientMessageId/text均null，仅保留id/序号/时间/占位状态，isMine=false |
| Unread | raw:{messages,comments,reactions,announcements,total}、badgeTotal按全局偏好计算、snapshotAt；不把本机sound/muted当系统送达状态 |
| NoticeActor | kind:user/platform、user:PublicAuthor或null、displayName:string；平台时user=null且displayName=“信科 Link 平台通知”，用户时遵守匿名投影 |
| Notice | id、type:comment/reaction/announcement、eventKind、actor:NoticeActor、title:string、summary:string、content:string或null、quote:null或string、image:ImageAccess或null、target:{kind,id,availability}或null、readAt:null或string、createdAt；system的title/content取已发task完整标题/正文，不截为summary；无target的系统通知在现有公告弹窗展示全文并标已读，不增加新页面；互动类content=null；本人动作不伪造成收到的未读 |
| Announcement | id、title、body、category:community/activity/safety、scope:all/forum、status、publishedAt:lastPublishedAt；公开仅published且范围匹配，无“学校官方”标志 |
| ReportReceipt | id、status:pending、createdAt；不返回目标真实作者/举报队列/其他人记录；现有页面仅需提交反馈 |
| AdminMe | id、loginName（允许安全展示的账号）、displayName、role:ADMIN；无passwordHash |
| AdminPost | PostDetail基础 + title派生、status、reviewState、author:{id,name,avatar,selfReported:true}真实归属、visibleCommentCount、viewCount:null、version；ADMIN专用 |
| AdminComment | Comment基础 + status、linkedTarget:{type,id,title,availability}、author真实归属、level:root/reply、version |
| AdminCommentThread | AdminComment + replyCount + replies:AdminComment[]；可查看hidden，deleted仅保留id/关系/状态占位，不回传已删正文 |
| AdminReport | id、type、priority、status、reasonCode、description、reporter安全资料、target及targetUser、targetSnapshot、evidence:ImageAccess[]、resolution、resolutionReason、processedAt、history、version；消息证据仅当前案件授权范围 |
| AdminReportSummary | id、type、priority、status、reasonCode、description、reporter:UserSummary、target:{type,id,title,availability}、targetUser:UserSummary、createdAt、processedAt、version；案件原始私信/证据仅详情返回 |
| AdminUser | id、name、avatar、major、className、enrollmentYear、selfReported:true、joinedAt、lastActiveAt、status、restrictions、manualRestrictions、banUntil、contentCounts:{posts,comments}、reportCount（作为被举报人次数）、version；不含微信身份或学号 |
| ModerationHistory | id、source、action、reason、operator:{id,displayName}\|system、createdAt、beforeState、afterState；仅A |
| AdminAnnouncement | 公告字段 + createdAt/updatedAt、firstPublishedAt/lastPublishedAt/withdrawnAt、creator/updater、version |
| AdminNotification | id、title、body、audience、status:draft/sent、createdAt/updatedAt/sentAt、recipientCount:null\|真实去重人数、deliveryMode:"in_app"、externalPush:false、version；无simulated*字段冒充真实送达 |
| AuditEvent | id、operationId、createdAt、actor安全名、module、action、targetType、targetRef安全投影、targetCount、outcome、summaryCode/安全摘要、resultCode、changedFields；无理由/正文/配置值/凭据/收件人列表 |
| Settings | platform只读、administrator只读、system只读、community:{controlsAvailable:false,note}、contentReview:{sensitiveWordIntercept,nightQueueMode,imageSensitivity,sensitiveWords}、effectMode:configuration_only、version |

公共限制：昵称≤20、简介≤40、专业≤60、班级≤40、年份空或匹配`^(19|20)\d{2}$`；帖子正文1–2000/0–9图；评论1–1000；消息1–1000；举报说明/处置原因1–200；公告标题1–60/正文1–1200；通知标题1–50/正文1–500。字符串长度与现有TypeScript表单规则对齐，涉及emoji时实施统一UTF-16长度计数（Java String.length与当前JS一致），不能端间一个数码点、另一个数代码单元。

`AdminList<T>.stats`按端点定义如下，均为非负整数，除明确说明外按未软删全局数据统计，不随列表keyword改变；“今日”按Asia/Shanghai：

| 端点 | stats字段及口径 |
| --- | --- |
| AP01 | total、visible（有效公开）、hidden（status=hidden）、today、pending（reviewState=pending）；hidden与pending可重叠，不相加冒充总数 |
| AC01 | total、visible（父目标/根/本条均公开）、hidden（本条status=hidden）、today、interactions（当前有效评论点赞关系数） |
| AR01 | total、pending、processed、highPriority（pending且high） |
| AU01 | total、todayNew、restricted（有效restricted或临时banned）、permanentlyBanned |
| AA01 | total、published、draft、withdrawn |
| AN01 | total、sent、draft、inAppRecipientCount（已发送任务实际接收行累计；不是独立人数或微信送达） |
| AL01 | total、success、businessRejected、executionErrors、today；来自数据库安全事件，不再是浏览器当前会话计数 |

不再下发Mock的simulatedSends、虚构浏览数或实时安全结论。`D01`响应的metrics明确为`{totalUsers,todayPosts,pendingReports}`；trends为`[{date:"YYYY-MM-DD",postCount,dau:number或null}]`，todos为`[{kind:report/post,id,title,priority,createdAt}]`，recentActivity为`[{id,kind,summary,createdAt,target:{kind,id}或null}]`（均≤10条并安全投影），unavailableMetrics为`[{key,reason}]`；另含rangeStart/rangeEnd的UTC边界。没有采集的环比/停留时长不生成数值。

## 3. 健康（2M）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/排序 |
| --- | --- | --- | --- | --- | --- | --- |
| H01 | GET | /api/v1/health | 无 → 既有{application:"xinkelink-server",status:"UP"} | P；运维/已有health服务 | M | 不使用业务envelope；仅进程健康 |
| H02 | GET | /actuator/health | 无 → 既有{status} | P，生产限定网络；运维 | M | 不公开env/配置/组件；无分页 |

## 4. 小程序身份与资料（8M、4S）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/分页排序 |
| --- | --- | --- | --- | --- | --- | --- |
| U01 | POST | /api/v1/auth/wechat | B:{code:string} → UserTokens；服务端换身份 | P；profile及受保护操作原地登录 | M | WECHAT_LOGIN_FAILED / WECHAT_NOT_CONFIGURED；流程见02，不信任客户端openid |
| U02 | POST | /api/v1/auth/refresh | B:{refreshToken} → 轮换UserTokens | T；全局会话 | M | REFRESH_INVALID；过期/重放不能恢复会话 |
| U03 | POST | /api/v1/auth/logout | 无 → {revoked:true}当前sid | U；settings | M | 吊销不删内容；票据已失效可401，客户端仍清本机票据并退出，不承诺失效票据重复200 |
| U04 | GET | /api/v1/me | 无 → Me | U；profile/profile-edit | M | 无学号；无分页 |
| U05 | PATCH | /api/v1/me | B:{nickname?,avatarImageId?,bio?,major?,className?,enrollmentYear?,expectedVersion} → Me | O；profile-edit | M | VERSION_CONFLICT/IMAGE_FORBIDDEN；未知studentId/school字段拒绝 |
| U06 | GET | /api/v1/me/summary | 无 → MeSummary | U；profile | M | 真实有效计数 |
| U07 | GET | /api/v1/users/{userId} | 路径ID → PublicUser | P；user-home/作者 | M | TARGET_UNAVAILABLE；不能从匿名帖反查 |
| U08 | GET | /api/v1/users/{userId}/posts | Q:cursor,size → CursorPage<PostCard> | P；user-home帖子 | M | 仅公开非匿名；createdAt倒序 |
| U10 | GET | /api/v1/me/preferences | 无 → Preferences | U；settings/角标 | S | 缺行返回默认值 |
| U11 | PATCH | /api/v1/me/preferences | B:{messages?,interactions?,announcements?,sound?,expectedVersion} → Preferences | O；settings | S | VERSION_CONFLICT；不调用微信订阅消息 |
| U12 | PUT | /api/v1/me/blocks/{userId} | 无 → {blocked:true} | O；chat-settings | S | 禁止自己；用户目标无效404；默认仅本人禁发 |
| U13 | DELETE | /api/v1/me/blocks/{userId} | 无 → {blocked:false} | O；chat-settings | S | 幂等；不删除历史 |

## 5. 图片（4M）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/规则 |
| --- | --- | --- | --- | --- | --- | --- |
| I01 | POST | /api/v1/images | multipart:file,purpose=avatar/post/report → ImageMeta（temporary） | U；发帖/资料/举报 | M | FILE_TOO_LARGE/UNSUPPORTED_MEDIA_TYPE/STORAGE_UNAVAILABLE；单张≤10MiB |
| I02 | GET | /api/v1/images/{imageId}/access | 路径ID → ImageAccess，grant建议60秒 | P或对应U/A；所有图片展示/预览 | M | IMAGE_FORBIDDEN/TARGET_UNAVAILABLE；按当前父引用授权，临时图仅owner |
| I03 | GET | /api/v1/images/{imageId}/content | Q:{grant} → 安全图片字节，非JSON | 已授权短期grant；image/预览组件 | M | grant过期401；每次复核父可见性/主体会话，private no-store |
| I04 | DELETE | /api/v1/images/{imageId} | 路径ID → {deleted:true} | O；发布失败/移除未绑定图 | M | IMAGE_IN_USE；已绑定资源随业务引用管理，不能直接删 |

服务端校验实际字节、文件签名/解码、尺寸及归属。当前接受JPEG/PNG/GIF，解码后重编码为静态PNG并移除元信息；不接受WebP、HEIC或SVG。单张≤10MiB、单边≤8192、总像素≤4000万，不把客户端MIME当真。服务端绑定时检查帖子0–9、头像1、举报证据0–3。原文件路径/存储key不在API中接受或返回。

## 6. 帖子、评论、互动（14M、2L）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/分页排序 |
| --- | --- | --- | --- | --- | --- | --- |
| F01 | GET | /api/v1/posts | Q:{category?,sort=recommended/latest,keyword?,cursor?,size?} → CursorPage<PostCard> | P；forum | M | category仅四编码；推荐首版退化为最新；CURSOR_INVALID |
| F02 | POST | /api/v1/posts | B:{content,category,imageIds:[],anonymous:boolean,clientRequestId} → PostDetail | U+publish；forum-publish | M | IMAGE_FORBIDDEN/DUPLICATE_REQUEST_CONFLICT；无客户端author/status |
| F03 | GET | /api/v1/posts/{postId} | ID → PostDetail；owner为OwnerPostDetail | P，owner可读本人治理信息；forum-detail/分享 | M | TARGET_UNAVAILABLE；匿名投影 |
| F04 | DELETE | /api/v1/posts/{postId} | If-Match → {deleted:true,removedCommentCount} | O；my-posts | M | VERSION_CONFLICT；软删帖子及评论，公共关联失效 |
| F05 | GET | /api/v1/me/posts | Q:cursor,size → CursorPage<OwnerPostDetail>含本人匿名和可见状态 | O；my-posts | M | createdAt倒序；不复用公开用户帖子过滤；已删除不再列出 |
| F06 | PUT | /api/v1/posts/{postId}/like | 无 → {liked:true,likeCount,changed} | U；卡片/详情/搜索/收藏 | M | TARGET_UNAVAILABLE；唯一约束幂等 |
| F07 | DELETE | /api/v1/posts/{postId}/like | 无 → {liked:false,likeCount,changed} | U；同F06 | M | 失效目标仍可取消本人关系 |
| F08 | PUT | /api/v1/posts/{postId}/favorite | 无 → {favorited:true,changed} | U；卡片/详情 | L | TARGET_UNAVAILABLE；幂等 |
| F09 | DELETE | /api/v1/posts/{postId}/favorite | 无 → {favorited:false,changed} | U；卡片/详情/my-collections | L | 允许清理失效关系 |
| F10 | GET | /api/v1/posts/{postId}/comments | Q:cursor,size → CursorPage<CommentThread> | P；forum-detail | M | 一级倒序；每根首批回复1条；统一有效可见性 |
| F11 | POST | /api/v1/posts/{postId}/comments | B:{content,replyToCommentId?,clientRequestId} → {comment:Comment,commentCount} | U+comment；forum-detail | M | REPLY_TARGET_INVALID/IMAGE不适用；最多两层 |
| F12 | GET | /api/v1/comments/{commentId}/replies | ID必须为一级；Q:cursor,size → CursorPage<Comment> | P；详情展开回复 | M | 根有效且同目标；回复正序 |
| F13 | DELETE | /api/v1/comments/{commentId} | If-Match → {deleted:true,removedCount,postCommentCount} | O；forum-detail | M | VERSION_CONFLICT；根连同回复，二级只删自身 |
| F14 | PUT | /api/v1/comments/{commentId}/like | 无 → {liked:true,likeCount,changed} | U；评论 | M | TARGET_UNAVAILABLE；幂等 |
| F15 | DELETE | /api/v1/comments/{commentId}/like | 无 → {liked:false,likeCount,changed} | U；评论 | M | 允许取消失效关系 |
| F16 | PATCH | /api/v1/posts/{postId} | B:{content?,category?,imageIds?,expectedVersion} → OwnerPostDetail；至少一个可编辑字段 | O+publish；本轮新增后端编辑能力，未改前端 | M | VERSION_CONFLICT；已删STATE_CONFLICT；不接受anonymous/author/status/createdAt/clientRequestId，保留原匿名属性/治理标记/初次请求摘要 |

F16覆盖原设计中“无帖子编辑”的历史决定，其他既有路径不变；仍无作者隐藏接口。评论body不接受author、rootId任意指定；Service从replyTo推导root并检查归属。帖子匿名作者参与评论/回复时PublicAuthor按该帖匿名号投影。

## 7. 已移除的业务

二手功能及关联数据已删除，原P系列和AM系列接口不再注册。

## 8. 搜索与互动汇总（2M、1L）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/分页排序 |
| --- | --- | --- | --- | --- | --- | --- |
| Q01 | GET | /api/v1/search | Q:{q,type:posts,category?,sort?,cursor?,size?} → {type,q,result:CursorPage<PostCard>} | P；统一search | M | 仅支持posts；sort为latest/recommended；共享F01可见性规则 |
| Q02 | GET | /api/v1/me/favorites | Q:{type:post,cursor?,size?} → CursorPage<Favorite> | O；my-collections | L | 按收藏时间倒序；目标状态与详情同一ID/最新数据 |
| Q03 | GET | /api/v1/me/likes | Q:{type:post,cursor?,size?} → CursorPage<{type,targetId,likedAt,availability,item}> | U；my-collections（界面名：我的点赞） | M | 按点赞时间与ID倒序，cursor绑定用户与type；隐藏目标仅安全占位；无效type400，未登录401 |

历史记录保留本机，清空/取消/返回为客户端行为，没有历史、搜索发现、热门词或“大家在搜”接口。输入文字不自动调用搜索，确认后再请求；无结果返回空页而非错误，加载态由请求生命周期驱动。

## 9. 会话与消息（8M、3S）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/分页排序 |
| --- | --- | --- | --- | --- | --- | --- |
| C01 | GET | /api/v1/conversations | Q:cursor,size → CursorPage<Conversation> | U；contact | M | 置顶/最近活动；只本人成员 |
| C02 | POST | /api/v1/conversations | B:{peerUserId} → Conversation | U；user-home/私信 | M | 禁止self；用户对唯一；只打开/建会话不发送消息，不因屏蔽拒绝已有历史 |
| C03 | GET | /api/v1/conversations/{conversationId} | ID → Conversation | C；contact-chat/chat-settings | M | 404对非成员；不通过查询参数选择其他成员 |
| C04 | GET | /api/v1/conversations/{conversationId}/messages | Q:{beforeSequence?,afterSequence?,aroundMessageId?,size=20,q?} → {items:Message[],hasMore,nextBeforeSequence,nextAfterSequence} | C；聊天/历史搜索/定位 | M | before/after/around互斥；around不得与q同用，定位目标前后各取size/2再正序，目标不属会话或不在本人clear水位之后404；q≤60；最多100条；隐藏项仅占位 |
| C05 | POST | /api/v1/conversations/{conversationId}/messages | B:{text,clientMessageId} → Message | C+message；contact-chat | M | MESSAGE_BLOCKED/STATE_CONFLICT/DUPLICATE_REQUEST_CONFLICT；落库后才返回sent |
| C06 | PUT | /api/v1/conversations/{conversationId}/read | B:{readUpToSequence} → {readThroughSequence,unreadCount} | C；进入聊天/阅读 | M | 水位不能超过最高已提交序号；只增；历史读取与已读写失败分开处理 |
| C07 | POST | /api/v1/me/conversations/read | B:{} → {watermarks:[{conversationId,readThroughSequence}],unread:Unread} | U；联系全部已读 | M | 取请求开始的服务端已提交水位，不抹掉之后新消息 |
| C08 | GET | /api/v1/conversations/{conversationId}/settings | 无 → {pinned,muted,blocked,version} | C；chat-settings | S | blocked来自本人user_block，不是会话全局状态 |
| C09 | PATCH | /api/v1/conversations/{conversationId}/settings | B:{pinned?,muted?,expectedVersion} → 同C08 | C；chat-settings | S | VERSION_CONFLICT；屏蔽走U12/U13，避免双份状态 |
| C10 | POST | /api/v1/conversations/{conversationId}/clear | B:{clearThroughSequence,confirmed:true} → {clearedThroughSequence,unreadCount} | C；本人清空记录 | S | existingClear≤请求≤最高已提交序号，否则INVALID_RANGE；read=max(read,clear)，只影响本人，不物理删对方数据 |
| C11 | GET | /api/v1/me/unread | 无 → Unread | U；TabBar/contact/页面onShow | M | raw与badge分开；无微信外部送达声明 |

不接收fromSelf/senderId/createdAt/sent等客户端伪造字段。普通私信仅携带文字和幂等消息编号，按当前账号、会话成员及屏蔽规则验证发送权限。

## 10. 收件通知、公告与举报（6M）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/分页排序 |
| --- | --- | --- | --- | --- | --- | --- |
| N01 | GET | /api/v1/notifications | Q:{type?:comment/reaction/announcement,unreadOnly?,cursor?,size?} → CursorPage<Notice> | U；contact通知 | M | 省略type是全部；倒序；目标不可用时安全占位 |
| N02 | PUT | /api/v1/notifications/{notificationId}/read | 无 → {readAt,changed,unread:Unread} | O接收者；通知点击 | M | 幂等；他人通知404 |
| N03 | POST | /api/v1/me/notifications/read | B:{throughNotificationId?} → {readCount,unread:Unread} | U；通知全部已读 | M | 先物化请求开始已提交且未读的本人ID集合，再仅更新此集合；可指定上界；不用单独max(id)误标晚提交来件 |
| N04 | GET | /api/v1/announcements | Q:{scope:all/forum,cursor?,size?} → CursorPage<Announcement> | P；论坛公告/联系公告 | M | scope=forum含all+forum；all仅全范围公告；按lastPublishedAt倒序 |
| N05 | GET | /api/v1/announcements/{announcementId} | ID → Announcement | P；公告弹窗 | M | 仅当前published可读；撤回/删除404 |
| R01 | POST | /api/v1/reports | B:{targetType:post/comment/user,targetId,reasonCode,description,evidenceImageIds:[],clientRequestId} → ReportReceipt | U；report | M | REPORT_PENDING/IMAGE_FORBIDDEN/TARGET_UNAVAILABLE；targetOwner/priority不信任客户端；comment为Day4最新授权扩展 |

reasonCode固定commercial_ad/fraud_or_offsite_payment/harassment/impersonation/other；说明1–200、证据0–3。现有小程序没有评论/具体私信举报和进度页；本轮最新授权仅让R01服务端接受comment，不修改或新增前端页面。具体私信举报仍不开放普通用户提交，后台已有message案件可按授权处理，不能用假来件掩盖缺口。

## 11. 管理员认证与数据概览（5M）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/规则 |
| --- | --- | --- | --- | --- | --- | --- |
| A01 | POST | /api/v1/admin/auth/login | B:{account,password} → AdminTokens并设置refresh cookie | P；LoginView | M | INVALID_CREDENTIALS；限流；不返回明文密码/hash |
| A02 | POST | /api/v1/admin/auth/refresh | cookie+CSRF header → AdminTokens并轮换cookie | T；全局会话 | M | REFRESH_INVALID；检查Origin/CSRF，不能接受用户refresh |
| A03 | POST | /api/v1/admin/auth/logout | cookie+CSRF及access（有则验证） → {revoked:true}，清cookie | T；管理员菜单 | M | 吊销不删除业务；失效票据可401，客户端仍退出并清access，响应同步过期cookie |
| A04 | GET | /api/v1/admin/me | 无 → AdminMe | A；顶部身份/设置 | M | 替换硬编码展示名，不公开凭据 |
| D01 | GET | /api/v1/admin/dashboard | Q:{range:7d/30d/semester} → {asOf,range,metrics,trends,todos,recentActivity,unavailableMetrics} | A；DashboardView | M | 真实计数；学期首版定义当前半年1/1或7/1至今并标rangeStart；无数据空数组，不生成假趋势 |

Dashboard基础metrics=未删用户总数/今日新帖/待处理举报；trends每日帖子发布量和已启用采集的DAU；todo来自pending报告/待复核记录；activity来自业务创建与审计。DAU未采集、停留时长/无证据环比返回null+原因。统计窗口≤当前半年，不以“实时服务器正常”标签冒充观测。

## 12. 管理帖子与评论（9M）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/分页排序 |
| --- | --- | --- | --- | --- | --- | --- |
| AP01 | GET | /api/v1/admin/posts | Q:{keyword?,category?,status?,from?,to?,page?,pageSize?} → AdminList<AdminPost> | A；帖子管理 | M | 状态visible/hidden；默认创建倒序；无伪造viewCount |
| AP02 | GET | /api/v1/admin/posts/{postId} | ID → {post:AdminPost,author,commentSummary} | A；右侧帖子抽屉 | M | 真实匿名owner仅此权限；软删明确deleted |
| AP03 | GET | /api/v1/admin/posts/{postId}/comments | Q:page,pageSize → Page<AdminCommentThread> | A；抽屉评论 | M | 管理可见hidden，但deleted按历史占位；根倒序 |
| AP04 | POST | /api/v1/admin/posts/status | B:BatchCommand，targetStatus=visible/hidden → BatchResult | A；单/批隐藏恢复 | M | VERSION_CONFLICT/STATE_UNCHANGED；不等同pending审批 |
| AP05 | DELETE | /api/v1/admin/posts/{postId} | If-Match；已确认 → {deleted:true,removedCommentCount} | A；删除确认 | M | VERSION_CONFLICT；保留举报证据/业务历史 |
| AC01 | GET | /api/v1/admin/comments | Q:{keyword?,level?:root/reply,status?,business?:forum,page?,pageSize?} → AdminList<AdminComment> | A；评论管理 | M | forum→post；默认创建倒序；不按linkedTitle关联 |
| AC02 | GET | /api/v1/admin/comments/{commentId}/context | Q:{replyCursor?,size?} → {target,root:AdminComment,selected:AdminComment,replies:CursorPage<AdminComment>,visibility} | A；评论上下文抽屉 | M | 正确两层/回复对象；目标已删显示快照/占位 |
| AC03 | POST | /api/v1/admin/comments/status | B:BatchCommand，targetStatus=visible/hidden → BatchResult | A；单/批隐藏恢复 | M | VERSION_CONFLICT/STATE_UNCHANGED；有效可见性还看根/父内容 |
| AC04 | POST | /api/v1/admin/comments/delete | B:{items:[{id,expectedVersion}],confirmed:true} → BatchResult及removedReplyCount | A；单/批删除确认 | M | 根及二级一起软删，去重重叠选项，真实removedCount |

## 13. 管理举报（3M）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/分页排序 |
| --- | --- | --- | --- | --- | --- | --- |
| AR01 | GET | /api/v1/admin/reports | Q:{keyword?,type?,priority?,status?,from?,to?,page?,pageSize?} → AdminList<AdminReportSummary> | A；举报队列 | M | 四类post/comment/user/message；pending/processed；队列排序见1.3 |
| AR02 | GET | /api/v1/admin/reports/{reportId} | ID → AdminReport | A；举报处理抽屉 | M | 案件范围的原始快照/证据；不开放整段私信 |
| AR03 | POST | /api/v1/admin/reports/{reportId}/resolution | B:{action,reason,expectedVersion,confirmed:true} → {report:AdminReport,effects:{changedCount,contentVisibility?,userStatus?,noticeCreated?}} | A；处置确认 | M | ALREADY_PROCESSED/VERSION_CONFLICT/STATE_CONFLICT；目标+报告+历史同事务；Day5统一安全审计仅一个主事件 |

AR03动作：post→reject/hide_post；comment→reject/hide_comment；user→reject/warn_user/restrict_user；message→reject/hide_message。本期无评论/消息用户举报入口，不伪造这两类来件。restrict_user只限制三通道、不改变永久封禁；warn_user创建站内提醒而非微信发送；hidden消息保留序号占位。相同已隐藏目标可结案，effects.changedCount=0；已经处理的报告不能再次处理。

## 14. 管理用户（8M）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/分页排序 |
| --- | --- | --- | --- | --- | --- | --- |
| AU01 | GET | /api/v1/admin/users | Q:{keyword?,status?,restriction?:publish/comment/message,from?,to?,page?,pageSize?} → AdminList<AdminUser> | A；用户管理 | M | keyword匹配ID/昵称/专业/班级，不搜索未收集的学号；按joinedAt倒序 |
| AU02 | GET | /api/v1/admin/users/{userId} | ID → AdminUser及counts | A；用户抽屉 | M | lastActiveAt可空；真实有效封禁/人工限制分层 |
| AU03 | GET | /api/v1/admin/users/{userId}/content | Q:{type:post/comment,page?,pageSize?} → Page<对应Admin模型> | A；发布内容Tab两组 | M | 匿名归属仅A；默认创建倒序 |
| AU04 | GET | /api/v1/admin/users/{userId}/reports | Q:page,pageSize → Page<AdminReportSummary加roles数组> | A；举报记录Tab | M | reporterId=id或targetUserId=id；roles为reporter/target_user的一项或两项，同案只一条；不用昵称拼接 |
| AU05 | GET | /api/v1/admin/users/{userId}/moderation-history | Q:page,pageSize → Page<ModerationHistory> | A；处理历史Tab | M | 时间倒序，report/system/user_management均保留 |
| AU06 | POST | /api/v1/admin/users/{userId}/restrictions | B:{channel:publish/comment/message,enabled:boolean,reason,expectedVersion,confirmed:true} → AdminUser | A；通道限制/解除 | M | 已临时/永久封禁时独立解除不允许；STATE_UNCHANGED/VERSION_CONFLICT |
| AU07 | POST | /api/v1/admin/users/{userId}/ban | B:{mode:temporary/permanent,durationDays?:7,reason,expectedVersion,confirmed:true} → AdminUser | A；7天/永久封禁 | M | 永久不可降级；临时可升永久；封禁吊销会话 |
| AU08 | POST | /api/v1/admin/users/{userId}/release | B:{reason,expectedVersion,confirmed:true} → AdminUser | A；解除限制/提前解封 | M | 永久封禁STATE_CONFLICT；明确解除所有人工通道和临时层；不恢复被隐藏内容 |

期限到期只解除临时层、保留人工限制；这是对Mock清空所有限制的安全修正，不新增权限管理UI。所有用户发帖/评论/消息服务端必须执行有效限制，而非仅后台标签变化。

## 15. 管理公告（7M）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/分页排序 |
| --- | --- | --- | --- | --- | --- | --- |
| AA01 | GET | /api/v1/admin/announcements | Q:{keyword?,category?,status?,scope?,page?,pageSize?} → AdminList<AdminAnnouncement> | A；公告管理 | M | category三编码community/activity/safety；status三态；updatedAt倒序 |
| AA02 | GET | /api/v1/admin/announcements/{announcementId} | ID → AdminAnnouncement | A；预览/编辑抽屉 | M | 预览本地展示，不等于发布 |
| AA03 | POST | /api/v1/admin/announcements | B:{title,body,category,scope,clientRequestId} → AdminAnnouncement(draft) | A；新建草稿 | M | 60/1200长度；DUPLICATE_REQUEST_CONFLICT；无学校官方身份 |
| AA04 | PATCH | /api/v1/admin/announcements/{announcementId} | B:{title,body,category,scope,expectedVersion} → AdminAnnouncement(draft) | A；编辑保存草稿 | M | VERSION_CONFLICT；已发布编辑保存会退出公开，保留首次/最近发布历史 |
| AA05 | POST | /api/v1/admin/announcements/{announcementId}/publish | B:{expectedVersion,confirmed:true} → AdminAnnouncement(published) | A；发布/重发 | M | 重复已发布幂等无新增通知；合法发布写日志 |
| AA06 | POST | /api/v1/admin/announcements/{announcementId}/withdraw | B:{expectedVersion,confirmed:true} → AdminAnnouncement(withdrawn) | A；撤回 | M | 仅published；撤回后公共接口不可读 |
| AA07 | DELETE | /api/v1/admin/announcements/{announcementId} | If-Match；已确认 → {deleted:true} | A；删除 | M | VERSION_CONFLICT；收件记录目标失效，不伪造撤回微信推送 |

首版拟在首次发布为当前未删除用户生成站内公告通知，event_key固定`announcement:{id}:first_publish`；scope限定公告展示位置，不作为尚无模型的订阅人群条件。重发只更新公开内容，不重复生成未读（待05审阅）；重复点击不能反复通知。超过同步上限5000先返回AUDIENCE_TOO_LARGE，保留原状态，不产生半数接收记录。

## 16. 管理站内系统通知（6M）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/分页排序 |
| --- | --- | --- | --- | --- | --- | --- |
| AN01 | GET | /api/v1/admin/notifications | Q:{keyword?,audience?,status?,page?,pageSize?} → AdminList<AdminNotification> | A；通知管理 | M | 省略audience=不过滤；传all=精确全员；updatedAt倒序 |
| AN02 | GET | /api/v1/admin/notifications/{notificationId} | ID → AdminNotification及站内接收汇总 | A；发送记录/编辑 | M | sentAt/recipientCount为事实，不填推测已读/微信送达 |
| AN03 | POST | /api/v1/admin/notifications | B:{title,body,audience,clientRequestId} → AdminNotification(draft) | A；创建/存草稿 | M | 50/500长度；DUPLICATE_REQUEST_CONFLICT；无任意SQL受众条件 |
| AN04 | PATCH | /api/v1/admin/notifications/{notificationId} | B:{title,body,audience,expectedVersion} → AdminNotification(draft) | A；编辑草稿 | M | sent不可编辑STATE_CONFLICT |
| AN05 | POST | /api/v1/admin/notifications/audience-preview | B:{audience:all/active/recent} → {recipientCount,audienceFingerprint,computedAt} | A；创建抽屉受众核对 | M | 精确口径见03；不返回完整收件人列表，不是实际送达 |
| AN06 | POST | /api/v1/admin/notifications/{notificationId}/send | B:{expectedVersion,audienceFingerprint,confirmed:true} → AdminNotification(sent)+{recipientCount,externalPush:false} | A；发送风险确认 | M | NO_RECIPIENTS/AUDIENCE_CHANGED/AUDIENCE_TOO_LARGE/STATE_CONFLICT；已发送不重发 |

all含未删除的限制/封禁账号；active/recent按最新有效状态。状态sent表示站内接收记录事务已提交，**不表示微信推送/用户打开/对端送达**。`recipientCount`从实际receipt行核对，已发只读；外部微信接口不调用。

## 17. 审计与系统设置（3M、3S）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/分页排序 |
| --- | --- | --- | --- | --- | --- | --- |
| AL01 | GET | /api/v1/admin/audit-events | Q:{keyword?,actorKeyword?,adminId?,module?,action?,outcome?,from?,to?,page?,pageSize?} → AdminList<AuditEvent> | A；OperationLogView | M | actorKeyword匹配允许展示的管理员账号/安全名，adminId精确筛选；只读；三结果过滤；createdAt倒序 |
| AL02 | GET | /api/v1/admin/audit-events/{eventId} | ID → AuditEvent安全投影 | A；日志详情抽屉 | M | 不返回内部用户引用/正文/理由/配置值/token |
| AL03 | GET | /api/v1/admin/audit-events/export | Q:同AL01筛选但无分页 → UTF-8 BOM CSV文件 | A；导出当前筛选结果 | S | 全筛选不是当前页；≤10000；EXPORT_TOO_LARGE；CSV引号/换行/公式前缀防护 |
| AS01 | GET | /api/v1/admin/settings | 无 → Settings | A；SystemSettingsView | M | 平台/社区/账号/参数只读；不返回服务器密钥或虚构状态 |
| AS02 | PATCH | /api/v1/admin/settings/content-review | B:{sensitiveWordIntercept,nightQueueMode,imageSensitivity,sensitiveWords,expectedVersion} → Settings及changedFields | A；保存配置 | S | 词≤30、每词2–20去重；值不入审计；VERSION_CONFLICT/VALIDATION_FAILED |
| AS03 | POST | /api/v1/admin/settings/content-review/reset | B:{expectedVersion,confirmed:true} → Settings及changedFields | A；恢复默认弹窗 | S | 默认来自服务端，不从客户端接受任意默认值；无变化不写重复日志 |

AS配置仅持久化/展示，effectMode=configuration_only，不执行自动AI/内容拦截/夜间排队；没有Redis控制、服务器操作、清库/清空内容接口，也没有缺少Stitch依据的社区展开控件。AL三端点不接受客户端插入、更新或删除审计事件。

## 18. WebSocket契约（1个，不计入HTTP端点）

- `wss://{approved-host}/api/v1/ws`，U，握手Authorization Bearer；token/session失效拒绝或关闭，禁用查询串JWT。客户端不能自行订阅任意用户/会话频道。
- 首版只推送服务端持久事件，发送、历史和读水位通过C05/C04/C06。无需额外WS发送API或STOMP。外部心跳/网络断线不生成业务消息。
- 事件包：`{eventId,type,occurredAt,conversationId?:string,sequence?:DecimalSequence,payload:object}`。type为`message.created`（Message）、`conversation.updated`（会话摘要）、`read.updated`（成员水位）、`unread.updated`（Unread）、`notification.created`（Notice）。仅推实际有权接收的主体。
- 先MySQL commit后推送；event可能重复/乱序/丢失，客户端按messageId/sequence去重，通过HTTP afterSequence补取；WSS成功连接不等于聊天完整性验收。默认单实例，升级多实例前另行设计跨实例分发。
- sequence、beforeSequence、afterSequence、nextBeforeSequence、nextAfterSequence、readUpToSequence、readThroughSequence、clearThroughSequence和clearedThroughSequence统一为DecimalSequence；无下一页水位为null。客户端用十进制安全比较工具，不混用浮点数。

## 19. API统计、前端适配与契约验收

| 分组 | HTTP数量 | M | L | S |
| --- | ---: | ---: | ---: | ---: |
| H | 2 | 2 | 0 | 0 |
| U | 12 | 8 | 0 | 4 |
| I | 4 | 4 | 0 | 0 |
| F | 16 | 14 | 2 | 0 |
| Q | 3 | 2 | 1 | 0 |
| C | 11 | 8 | 0 | 3 |
| N | 5 | 5 | 0 | 0 |
| R | 1 | 1 | 0 | 0 |
| A | 4 | 4 | 0 | 0 |
| D | 1 | 1 | 0 | 0 |
| AP | 5 | 5 | 0 | 0 |
| AC | 4 | 4 | 0 | 0 |
| AR | 3 | 3 | 0 | 0 |
| AU | 8 | 8 | 0 | 0 |
| AA | 7 | 7 | 0 | 0 |
| AN | 6 | 6 | 0 | 0 |
| AL | 3 | 2 | 0 | 1 |
| AS | 3 | 1 | 0 | 2 |
| B | 2 | 2 | 0 | 0 |
| AB | 3 | 3 | 0 | 0 |
| FB | 1 | 1 | 0 | 0 |
| AF | 1 | 1 | 0 | 0 |
| **合计** | **105** | **92** | **3** | **10** |

两端使用真实ID与匿名DTO、ISO时间、游标、鉴权及错误响应；后台不能用掩码学号作关联。自填学号与搜索历史仅保留本机，不自动上传。契约检查逐项核对本文端点。

验收必须包括：跨用户/角色越权、匿名正文和评论的身份泄露、双端同ID/同计数、重复赞藏/消息/发送、并发举报只处理一次、永久封禁保护、临时解封不误清人工限制、图片引用/证据权限、消息水位与断线补取、审计失败隔离、列表/统计/CSV同过滤口径。没有真实微信/数据库/双账号证据时只能写“未验证”，不能把Mock测试替代服务端验收。


## 20. 内测准入与内测管理（2026-11-08 新增5项）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/分页排序 |
| --- | --- | --- | --- | --- | --- | --- |
| B01 | GET | /api/v1/beta/access | 无 → {phase:beta/open,authenticated,activated} | 匿名/用户；准入页 | M | 不返回内测码；读取失败不能视为开放 |
| B02 | POST | /api/v1/beta/redeem | B:{code} → 准入状态 | U；输入内测码 | M | BETA_CODE_UNAVAILABLE；账号/IP限流；一码一账号、一账号一码，重试不多消耗 |
| AB01 | GET | /api/v1/admin/beta | 无 → BetaOverview | A；内测管理/顶栏阶段 | M | 完整码仅管理员可见；无openid/token |
| AB02 | POST | /api/v1/admin/beta/initialize | B:{} → BetaOverview | A；生成30个内测码并开启内测 | M | 原子固定30个；重复调用不追加/重置/切换阶段 |
| AB03 | PATCH | /api/v1/admin/beta | B:{phase:beta/open,expectedVersion} → BetaOverview | A；确认阶段切换 | M | VERSION_CONFLICT；未初始化不能开启内测 |

BetaOverview={phase,version,total,used,remaining,codes:[{id,code,status:unused/used,user:{id,nickname}/null,usedAt,createdAt}]}。ID用十进制字符串，时间为UTC ISO。未使用码忽略大小写、空白及分隔符；已激活账号误填其他码不会额外消耗。

内测状态由数据库保存；未登录或未兑换用户的业务HTTP、图片和WebSocket均受服务端限制，返回BETA_ACCESS_REQUIRED（403），不依赖前端遮罩。登录/刷新/退出、本人资料确认及头像上传、内测准入接口和管理员操作为必要例外。原生图片字节请求通过授权grant中的身份验证内测资格，不要求image组件发送Bearer。

正式开放后关闭内测门槛，账号、社交内容及绑定历史保留；重开内测立即恢复限制。首批初始化及验证结果见该轮验收报告。当前HTTP数量见第19节。


## 21. 意见反馈（2M）

| ID | HTTP | URL | 请求 → 响应 | 权限/页面 | 级别 | 附加错误/规则 |
| --- | --- | --- | --- | --- | --- | --- |
| FB01 | POST | /api/v1/feedback | B:{content,clientRequestId} → {id} | U；意见反馈 | M | 正文1–1000字符；编号1–64位字母、数字或连字符；DUPLICATE_REQUEST_CONFLICT；同请求编号不覆盖正文 |
| AF01 | GET | /api/v1/admin/feedback | Q:{page=1,pageSize=10} → Page<{id,userId,nickname,content,createdAt}> | A；意见反馈管理 | M | page≤100000，pageSize≤50；createdAt/id倒序 |

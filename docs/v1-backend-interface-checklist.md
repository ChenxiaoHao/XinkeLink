# V1 前端后续 Java 接口清单

本文件为后续接入建议，不表示服务端已有这些端点。本轮没有修改 Java、PC 后台、数据库，也没有发出这些请求。当前数据由小程序本地 Storage/文件系统管理，替换时通过 features 下的数据仓库接入，保持页面和业务 ID 稳定。

## 公共契约

- 所有 ID 使用服务端不透明字符串；价格为整数分，时间为稳定的 UTC 时间戳/ISO 时间，不使用每次启动重新生成的业务时间。所有列表支持明确的分页游标和 hasMore。
- 公共响应只返回可展示字段及当前用户的 liked/collected 等状态；匿名帖不返回真实 authorId、头像、昵称、学校专业。自己的匿名帖子可在受保护的「我的帖子」接口管理，不能反查到公开个人主页。
- 学校、专业、入学年份均是自填资料，返回 selfReported=true 或等价标记；前端继续显示「用户自填，未经平台核实」。没有学生认证或学校官方认证。
- 写操作须服务端鉴权和归属校验；本地模拟登录开关不是权限保护。错误至少区分未登录、无权限、目标不存在、状态冲突、参数错误、限流和临时不可用，并保留前端输入以便重试。
- 上传和业务提交分开；业务提交失败后按存储策略清理未引用文件，避免删掉其他已使用资源。既有 ImageStorage 接口继续保持厂商无关。

## HTTP 接口建议

| 模块 | 方法与建议路径 | 主要输入/输出 |
| --- | --- | --- |
| 登录 | POST /api/auth/wechat | 微信临时登录凭据；返回用户、会话凭据及有效期。后端核实凭据，前端不保存任何应用密钥 |
| 会话 | POST /api/auth/refresh；POST /api/auth/logout | 更新/撤销当前会话；退出不等于删除用户内容 |
| 我的资料 | GET /api/me；PATCH /api/me | nickname、avatarId、bio、school、major、year、自填标记 |
| 个人主页 | GET /api/users/{id} | 公开资料及可见统计；不提供匿名作者关联 |
| 用户内容 | GET /api/users/{id}/posts；GET /api/users/{id}/products | 公开帖子/在售商品分页，排除匿名帖和私有下架内容 |
| 图片 | POST /api/images；DELETE /api/images/{id} | 文件/上传凭据、imageId、可访问 URL、尺寸；删除校验归属及引用关系 |
| 论坛列表 | GET /api/posts | category、sort、keyword、cursor；公开帖子和当前用户交互状态 |
| 帖子 | POST /api/posts；GET /api/posts/{id}；DELETE /api/posts/{id} | 正文、图片 ID、分类、匿名；删除仅作者可操作，列表/详情/收藏/通知同步失效 |
| 我的帖子 | GET /api/me/posts | 包含本人匿名帖子、真实可见性、当前统计、管理权限 |
| 帖子点赞/收藏 | PUT/DELETE /api/posts/{id}/like；PUT/DELETE /api/posts/{id}/collection | 幂等目标状态；返回最终计数和本人标记，避免重试切换两次 |
| 评论 | GET/POST /api/posts/{id}/comments | 一级评论分页、二级回复数与首批回复；发布 body、replyToCommentId、clientRequestId |
| 回复 | GET /api/comments/{id}/replies | 二级分页；回复二级仍落到一级线程，返回公开回复对象 |
| 评论管理 | DELETE /api/comments/{id}；PUT/DELETE /api/comments/{id}/like | 仅本人删除；明确一级删除级联规则，返回最新帖子评论数 |
| 商品列表 | GET /api/products | category、sort、keyword、filters、cursor；推荐排除已售，公开列表排除已下架 |
| 商品 | POST /api/products；GET/PATCH /api/products/{id} | 图片、标题、描述、priceFen、category、condition、negotiable；修改保持 ID 与创建时间 |
| 商品状态 | PATCH /api/products/{id}/status | available→off-shelf/sold；off-shelf→available；sold 不可恢复。携带版本或条件更新防止旧页覆盖 |
| 我的闲置 | GET /api/me/products | 按状态分页和各状态计数，保留已售历史 |
| 商品收藏 | PUT/DELETE /api/products/{id}/collection | 幂等状态与同一商品 ID |
| 我的收藏 | GET /api/me/collections | type=post/product、cursor；目标删除/不可见时返回明确状态或过滤，不能出现无效跳转 |
| 会话列表 | GET /api/conversations | 对端公开资料、最后消息、最后活动时间、未读数、关联商品摘要 |
| 创建/查找会话 | POST /api/conversations | peerUserId、productId?；同一对端复用会话，商品仅作关联，不为每件商品另建用户关系 |
| 历史消息 | GET /api/conversations/{id}/messages | before/after cursor；messageId、clientMessageId、sender、正文、关联商品、serverTime、服务端顺序号 |
| 发送消息 | POST /api/conversations/{id}/messages | text、productId?、clientMessageId；持久化成功后返回确认，重试幂等 |
| 已读 | PUT /api/conversations/{id}/read | readUpToSequence；返回新未读数；不能仅按客户端时间推断已读 |
| 全部会话已读 | POST /api/me/conversations/read | 以服务端可见消息水位更新，不抹掉同时到达的新消息 |
| 通知 | GET /api/notifications | type、cursor；通知 ID、公开参与者、事件类型、目标 ID、摘要、已读和时间 |
| 通知已读 | PUT /api/notifications/{id}/read；POST /api/me/notifications/read | 单条/全部已读的明确水位，返回最新未读数 |
| 未读摘要 | GET /api/me/unread | messages、comments/replies、reactions、announcements 及合计 |
| 通知偏好 | GET/PATCH /api/me/preferences | messages、interactions、announcements、sound；系统推送能力与本机页面提醒分别说明 |
| 平台公告 | GET /api/announcements；GET /api/announcements/{id} | 社区服务公告，不冒充学校教务公告 |

## 私信后续 WebSocket 边界

保留 HTTP 历史/发送兜底和稳定客户端消息 ID。连接建立后鉴权；服务端在 MySQL 持久化后确认消息，再发送 message.created、conversation.updated、read.updated、unread.updated 等事件。客户端按 messageId/clientMessageId 去重，并按服务端顺序展示；重连通过 HTTP 补齐断线期间消息。

当前前端只显示「已发送」的本地写入状态，失败保留输入并可重试。它不表示服务器已收到、对方已收到或已读。后端接入时应明确 sending/sent/delivered/read/failed 各状态，而不是沿用本地保存成功作为送达凭证。

## 本地数据迁移与验证

1. 现有旧聊天位于 xinkelink.market.messages.v1.{productId}；新会话按卖家聚合，消息携带商品 ID。接入前确定用户同意的迁移/演示数据丢弃策略，不能直接把演示对端和本地身份上传成真实记录。
2. 本地通知含四条预置演示事件，以及本人评论/回复产生的已读操作记录。真实接口应按接收者产生通知；本人操作成功反馈与收到他人事件分别处理，不能伪造对端活动。
3. 帖子/商品/评论的管理、匿名隐藏、收藏、消息读写权限都需在 Java 服务端重新验证，前端 guard 不能代替鉴权。
4. 验收增加双账号、两台设备、断网重连、重复发送、并发删除/售出、越权和匿名泄露、上传失败/资源回收、跨时区时间显示及真实微信分享/相册/键盘。

没有购物车、在线支付、微信支付、平台担保交易或话题接口。

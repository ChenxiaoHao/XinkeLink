# 信科 Link 小程序前端 V1 本轮验收

2026-10-23。本轮按既有原生小程序工程完成联系、我的及全局联动；没有修改 Java 或 PC 管理后台业务代码，没有连接真实服务，没有执行 Git commit/push/reset/clean。开始时保存了 231 个项目文件的 SHA-256 快照，原有 88 项小程序检查通过；本轮在此基础上增加 37 项行为测试，全部已有未提交成果保留。

## 功能完成状态

| 模块 | 状态 | 本轮结果 |
| --- | --- | --- |
| 论坛首页、详情、评论、发布 | 保留并联动完成 | 发布同步首页/我的帖子；点赞收藏计数同步；本人删除后首页、收藏、公开主页、通知不再展示；旧详情显示目标不存在 |
| 交易、发布、编辑、我的闲置 | 保留并联动完成 | 复用原页面与状态机制；资料变化投影到本人卖家信息；商品 ID 保持一致；我想要进入新会话 |
| 私信列表 | 完成 | 头像、昵称、摘要、时间、未读、读失败重试；同卖家聚合一条会话 |
| 聊天详情 | 完成 | 本地文字消息、时间分隔、已发送状态、商品卡、失败草稿与重试、空会话、售出/下架禁止发送；无伪造自动回复或送达回执 |
| 通知 | 完成 | 评论/回复、赞/收藏和公告分类，单条/全部已读、未读徽标；本人评论活动默认已读，删除后同步过滤 |
| 我的菜单/个人主页 | 完成 | 头像、昵称、简介、学校专业年份、真实本地统计；自己的公开预览隐藏匿名帖；正常作者/卖家主页可进入私信 |
| 我的帖子 | 完成 | 列表、详情、本人删除确认与失败反馈、空状态；本人匿名帖只在自己的管理列表出现 |
| 我的收藏 | 完成 | 帖子/闲置两类切换、同 ID 详情、取消收藏、空状态；保留商品状态历史 |
| 编辑资料 | 完成 | 原生图片选择、头像文件持久化、昵称/简介/学校/专业/年份校验、保存反馈；学校专业标注「用户自填，未经平台核实」 |
| 设置 | 完成 | 私信/互动/公告/声音偏好、关于、本地资料说明、确认退出、模拟登录/访客；偏好不宣称系统推送 |
| 公共入口 | 完成 | 原五栏与中央发布两个入口保持可用；联系徽标不改变布局；查看主页、返回和错误重试入口接通 |

## 本地数据与持久化边界

所有内容均为当前设备上的演示数据，没有服务器账号、远端消息或跨设备同步。资料、偏好和模拟会话分别使用 `xinkelink.profile.v1`、`xinkelink.preferences.v1`、`xinkelink.session.v1`。默认保留既有已登录的演示行为；访客可以浏览，写入操作提示模拟登录。这不是安全鉴权，后续须由 Java 服务端执行权限校验。

新联系状态位于 `xinkelink.contact.v1`，按对端卖家 ID 聚合，单条消息附带商品 ID。旧 `xinkelink.market.messages.v1.{productId}` 历史保留并合并读取，消息按 ID 去重；新消息只写一个联系状态，避免跨 key 部分成功。打开会话写已读失败时，仍显示已能读取的历史，并提供重试，不将保存失败冒充读取失败。

通知包含四条明确的预置演示事件，以及本地评论/回复派生的「你发表/回复」已读记录。没有伪造他人回复，不把自己的操作制造为自己的未读消息。偏好控制徽标汇总，不删除历史；声音选项当前仅保存偏好，没有系统音频推送服务。

帖子、商品与收藏继续使用原存储 key。资料变更在页面重显时投影到自己已发布的非匿名内容；公开主页排除匿名帖。删除本人帖子只写帖子数组，其他页面按仍存在的 ID 过滤，避免跨 key 部分删除；旧交互记录/图片不主动批量删除，防止误删仍有引用的资源。头像保存失败仅清理本次成功复制的副本，旧头像保留。

图片位于小程序用户文件目录；清缓存、重装、删除本地文件或换设备会丢失演示数据。不会自动迁移到未来真实账号。数据损坏时阻止覆盖并显示重试；不自动用空列表覆盖旧数据。

## 实际检查结果

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| `npm run check` | 通过，退出码 0 | `.local/v1-completion/final-check.log`；包含 TS、125/125 测试、项目 JSON/路由/组件引用检查 |
| 微信官方 WXML 编译 | 通过 | `wcc.exe` 编译 24 个 WXML |
| 微信官方 WXSS 编译 | 通过 | `wcsc.exe` 编译 26 个 WXSS；`.local/v1-completion/final-compile.log` |
| 独立只读复核 | 完成并修复发现项 | 复核匿名、访客、头像回滚、错误恢复、路由与会话兼容；修复访客评论点赞及已读保存阻断浏览，新增回归先失败后通过 |
| IDE 375×812 | 主要流程与页面通过 | iPhone 12/13 mini，基础库 3.17.5，pixelRatio=3，底部安全区 34px |
| IDE 430×932 | 主要流程与页面通过 | iPhone 15 Pro Max，同上，底部安全区 34px |
| Git 差异格式 | 通过，退出码 0 | `git diff --check`；只有原工作区 CRLF 提示，无空白错误 |
| 真机/真实服务 | 未执行 | 不把离线编译或模拟器结果等同于真机、双账号或生产验收 |

复现命令（在 `D:\CODE\XinKeLink\apps\miniprogram`）：

```powershell
npm run check
node scripts/check-wechat.cjs 'D:/develop/微信web开发者工具'
```

微信开发者工具导入 `D:\CODE\XinKeLink\apps\miniprogram`，普通编译。`project.config.json` 的 `miniprogramRoot` 指向 `miniprogram/`，本轮新增的七个非 Tab 页面均已注册。不需要启动后台或 Java 服务。

## 模拟器流程记录

在已打开的微信开发者工具 Stable 2.01.2510290 中实际运行原生小程序。为减少坐标操作，部分表单输入和页面按钮通过 IDE 控制台调用同一个页面事件处理器；原生图片选择器、全屏预览、删除/退出确认框及设备切换在工具中执行。截图来自真实工具窗口，仅裁切手机区域，不是 HTML 重绘或离线测试渲染。

| 流程 | 实际结果/证据文件（均在 `.local/v1-completion`） |
| --- | --- |
| 发布→详情点赞/收藏/评论→首页→我的帖子 | 新 ID 一致，首页 likeCount=1/commentCount=1；`published-big.json`、`feed-published-big.json`、`my-posts-big.json` |
| 编辑资料与原生选头像→保存→重启/换机型重显 | 文件复制成功，名字/头像/长专业名称持久化；既有本人帖子作者随之更新；`profile-saved-big.json`、`profile-persisted-small.json`、`my-posts-375.png` |
| 商品收藏→我的收藏 | `product-001` 在收藏页与详情相同；`collections-big.json`、`collections-products-375.png` |
| 商品我想要→发送文字→返回联系 | 同一 mock-ajie 会话，保留商品卡、消息状态；最新摘要更新，读过的会话未读为 0；`chat-big.json`、`contact-sync-big.json` |
| 换设备→历史恢复 | 375 屏仍显示同一条已发送内容；`chat-persisted-small.json` |
| 评论→通知→全部已读 | 本人评论记录存在且默认已读；四条演示通知可全部已读；`notices-sync-big.json`、`notice-read-big.json` |
| 退出→访客点赞→模拟登录 | 原生确认后 session=false，已有帖子和资料保留；访客出现登录提示；登录返回我的且计数/资料保留；`logout-big.json`、`guest-guard-430.png`、`login-return-big.json` |
| 删除自己的帖子 | 原生确认后我的帖子=0，首页不再有该 ID，收藏帖子=0，本地派生通知移除；`my-posts-deleted-small.json`、`feed-after-delete-small.json`、`collections-after-delete-small.json`、`notices-after-delete-small.json` |
| 旧商品流程回归 | 原生选图发布，卖家使用新资料，我的在售计数=1；下架→重新上架→售出依次同步；`goods-published-small.json`、`profile-goods-small.json`、`off-shelf-small.json`、`relisted-small.json`、`sold-small.json`、`sold-detail-small.json` |
| 错误与空态 | 注入无效联系数据时显示重试，通知仍独立可读，恢复后正常；自然空的 mock-zhou 会话、删帖后的空帖子/收藏可见；`contact-error-375.png`、`chat-empty-375.png`、`collections-empty-375.png` |
| 图片/导航/尺寸 | 原生 1/4 全屏图片预览、两个发布入口、五栏切换、页面返回、长页滚动、长消息换行已检查；`image-preview-375.png`、`publish-sheet-375.png`、`publish-product-entry-small.json` |

验收前先保存业务 Storage 与用户文件名单。验收后只恢复已记录的 key、移除本轮新增的演示 key 和两个本次复制的图片文件；对比确认 Storage 值与文件名单和开始时完全一致（`storage-before.json` / `storage-restored.json`）。没有整库清缓存，没有删除原有文件。截图中的新帖子、改名、聊天和商品属于验收过程数据，已恢复，不会遗留到用户的本地数据。

## 实际截图

全部截图位于 `D:\CODE\XinKeLink\.local\v1-completion`，被 Git 忽略。对应 `*.ide.png` 保留完整工具窗口供追溯；手机裁切 PNG 为 480×1038 / 550×1190，是窗口图像而非原生三倍像素导出。

| 页面 | 375×812 | 430×932 |
| --- | --- | --- |
| 联系列表 | [contact-375.png](../.local/v1-completion/contact-375.png) | [contact-430.png](../.local/v1-completion/contact-430.png) |
| 通知 | [notifications-375.png](../.local/v1-completion/notifications-375.png) | [notifications-430.png](../.local/v1-completion/notifications-430.png) |
| 聊天 | [chat-375.png](../.local/v1-completion/chat-375.png) | [chat-430.png](../.local/v1-completion/chat-430.png) |
| 我的 | [profile-375.png](../.local/v1-completion/profile-375.png) | [profile-430.png](../.local/v1-completion/profile-430.png) |
| 我的帖子 | [my-posts-375.png](../.local/v1-completion/my-posts-375.png) | [my-posts-430.png](../.local/v1-completion/my-posts-430.png) |
| 收藏帖子 | [collections-posts-375.png](../.local/v1-completion/collections-posts-375.png) | [collections-posts-430.png](../.local/v1-completion/collections-posts-430.png) |
| 收藏闲置 | [collections-products-375.png](../.local/v1-completion/collections-products-375.png) | [collections-products-430.png](../.local/v1-completion/collections-products-430.png) |
| 编辑资料 | [profile-edit-375.png](../.local/v1-completion/profile-edit-375.png) | [profile-edit-430.png](../.local/v1-completion/profile-edit-430.png) |
| 设置 | [settings-375.png](../.local/v1-completion/settings-375.png) | [settings-430.png](../.local/v1-completion/settings-430.png) |
| 个人主页 | [user-home-375.png](../.local/v1-completion/user-home-375.png) | [user-home-430.png](../.local/v1-completion/user-home-430.png) |
| 模拟登录 | [login-375.png](../.local/v1-completion/login-375.png) | [login-430.png](../.local/v1-completion/login-430.png) |
| 发布选择弹层 | [publish-sheet-375.png](../.local/v1-completion/publish-sheet-375.png) | [publish-sheet-430.png](../.local/v1-completion/publish-sheet-430.png) |

源图、对照图、布局边界与修复记录见根目录 [design-qa.md](../design-qa.md)。保留日系极简、雾蓝主色、原有品牌及五栏设计；未做新的视觉方向。

## 已知边界与未实现项

- 本轮列出的联系、我的和联动功能完成；没有待处理的已知阻断问题。模拟器不是实际手机；真机相册权限、软键盘、分享送达、系统通知、性能与不同微信版本仍需验证。
- 无真实微信登录、账号隔离、远端用户、Java API、MySQL、WebSocket 或推送。发送状态只表示本地写入成功；演示收到的私信/通知固定预置，不会真实接收消息。会话列表有三条演示种子；空列表分支由组件/页面测试覆盖，自然空的直接会话另有实际截图。
- 学校专业是用户自填，不含认证。匿名公开页面没有作者真实 ID 或主页关联；这是前端投影，服务端接入时仍必须校验。
- 后续新增参考中的独立搜索结果、举报表单、聊天设置、黑名单、关注、设备管理和账号绑定未在本轮开发。现有搜索仍为已有关键词弹窗；帖子卡片省略号是原有装饰，整卡进入详情，不代表已实现更多/举报菜单。商品页提及举报的说明也不表示举报工作流已经完成。
- 收藏不添加云端同步。删除帖子不自动回收旧图片/历史交互 key；头像更新保留旧头像文件，避免破坏已有引用，未来需引用计数与资源回收策略。
- 不包含购物车、在线支付、微信支付、平台担保交易、话题系统、PC 管理后台业务或真实后端。

后续 Java 接入所需 HTTP、鉴权、上传、私信历史/发送/已读、通知偏好及 WebSocket 契约已经整理为 [接口清单](v1-backend-interface-checklist.md)。本轮到此停止，不自动开始后端。

## 本轮修改文件

以下相对于本轮开始时的文件快照；不把之前交易/论坛的未提交成果算成本轮新写。原快照中文件没有删除，品牌资源、公共设计变量、后台与 Java 文件未改。

本轮共 79 个新增/修改文件，未删除任何原快照文件。

| 类型 | 文件 |
| --- | --- |
| 修改 | [apps/miniprogram/miniprogram/app.json](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/app.json) |
| 新增 | [apps/miniprogram/miniprogram/assets/icons/check-check-primary.png](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/assets/icons/check-check-primary.png) |
| 修改 | [apps/miniprogram/miniprogram/components/forum-post-card/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/components/forum-post-card/index.ts) |
| 修改 | [apps/miniprogram/miniprogram/components/forum-post-card/index.wxml](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/components/forum-post-card/index.wxml) |
| 修改 | [apps/miniprogram/miniprogram/components/publish-sheet/index.wxss](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/components/publish-sheet/index.wxss) |
| 修改 | [apps/miniprogram/miniprogram/custom-tab-bar/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/custom-tab-bar/index.ts) |
| 修改 | [apps/miniprogram/miniprogram/custom-tab-bar/index.wxml](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/custom-tab-bar/index.wxml) |
| 修改 | [apps/miniprogram/miniprogram/custom-tab-bar/index.wxss](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/custom-tab-bar/index.wxss) |
| 新增 | [apps/miniprogram/miniprogram/features/contact/badges.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/contact/badges.ts) |
| 新增 | [apps/miniprogram/miniprogram/features/contact/notifications.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/contact/notifications.ts) |
| 新增 | [apps/miniprogram/miniprogram/features/contact/repository.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/contact/repository.ts) |
| 新增 | [apps/miniprogram/miniprogram/features/contact/storage.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/contact/storage.ts) |
| 修改 | [apps/miniprogram/miniprogram/features/forum/feed.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/forum/feed.ts) |
| 修改 | [apps/miniprogram/miniprogram/features/forum/repository.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/forum/repository.ts) |
| 修改 | [apps/miniprogram/miniprogram/features/forum/types.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/forum/types.ts) |
| 修改 | [apps/miniprogram/miniprogram/features/market/conversation.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/market/conversation.ts) |
| 修改 | [apps/miniprogram/miniprogram/features/market/repository.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/market/repository.ts) |
| 新增 | [apps/miniprogram/miniprogram/features/profile/avatar.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/profile/avatar.ts) |
| 新增 | [apps/miniprogram/miniprogram/features/profile/content.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/profile/content.ts) |
| 新增 | [apps/miniprogram/miniprogram/features/profile/repository.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/features/profile/repository.ts) |
| 新增 | [apps/miniprogram/miniprogram/pages/contact-chat/index.json](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/contact-chat/index.json) |
| 新增 | [apps/miniprogram/miniprogram/pages/contact-chat/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/contact-chat/index.ts) |
| 新增 | [apps/miniprogram/miniprogram/pages/contact-chat/index.wxml](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/contact-chat/index.wxml) |
| 新增 | [apps/miniprogram/miniprogram/pages/contact-chat/index.wxss](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/contact-chat/index.wxss) |
| 修改 | [apps/miniprogram/miniprogram/pages/contact/index.json](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/contact/index.json) |
| 修改 | [apps/miniprogram/miniprogram/pages/contact/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/contact/index.ts) |
| 修改 | [apps/miniprogram/miniprogram/pages/contact/index.wxml](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/contact/index.wxml) |
| 修改 | [apps/miniprogram/miniprogram/pages/contact/index.wxss](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/contact/index.wxss) |
| 修改 | [apps/miniprogram/miniprogram/pages/forum-detail/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/forum-detail/index.ts) |
| 修改 | [apps/miniprogram/miniprogram/pages/forum-publish/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/forum-publish/index.ts) |
| 修改 | [apps/miniprogram/miniprogram/pages/forum/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/forum/index.ts) |
| 新增 | [apps/miniprogram/miniprogram/pages/login/index.json](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/login/index.json) |
| 新增 | [apps/miniprogram/miniprogram/pages/login/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/login/index.ts) |
| 新增 | [apps/miniprogram/miniprogram/pages/login/index.wxml](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/login/index.wxml) |
| 新增 | [apps/miniprogram/miniprogram/pages/login/index.wxss](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/login/index.wxss) |
| 修改 | [apps/miniprogram/miniprogram/pages/market-chat/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/market-chat/index.ts) |
| 修改 | [apps/miniprogram/miniprogram/pages/market-detail/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/market-detail/index.ts) |
| 修改 | [apps/miniprogram/miniprogram/pages/market-publish/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/market-publish/index.ts) |
| 新增 | [apps/miniprogram/miniprogram/pages/my-collections/index.json](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/my-collections/index.json) |
| 新增 | [apps/miniprogram/miniprogram/pages/my-collections/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/my-collections/index.ts) |
| 新增 | [apps/miniprogram/miniprogram/pages/my-collections/index.wxml](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/my-collections/index.wxml) |
| 新增 | [apps/miniprogram/miniprogram/pages/my-collections/index.wxss](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/my-collections/index.wxss) |
| 修改 | [apps/miniprogram/miniprogram/pages/my-items/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/my-items/index.ts) |
| 新增 | [apps/miniprogram/miniprogram/pages/my-posts/index.json](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/my-posts/index.json) |
| 新增 | [apps/miniprogram/miniprogram/pages/my-posts/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/my-posts/index.ts) |
| 新增 | [apps/miniprogram/miniprogram/pages/my-posts/index.wxml](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/my-posts/index.wxml) |
| 新增 | [apps/miniprogram/miniprogram/pages/my-posts/index.wxss](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/my-posts/index.wxss) |
| 新增 | [apps/miniprogram/miniprogram/pages/profile-edit/index.json](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/profile-edit/index.json) |
| 新增 | [apps/miniprogram/miniprogram/pages/profile-edit/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/profile-edit/index.ts) |
| 新增 | [apps/miniprogram/miniprogram/pages/profile-edit/index.wxml](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/profile-edit/index.wxml) |
| 新增 | [apps/miniprogram/miniprogram/pages/profile-edit/index.wxss](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/profile-edit/index.wxss) |
| 修改 | [apps/miniprogram/miniprogram/pages/profile/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/profile/index.ts) |
| 修改 | [apps/miniprogram/miniprogram/pages/profile/index.wxml](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/profile/index.wxml) |
| 修改 | [apps/miniprogram/miniprogram/pages/profile/index.wxss](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/profile/index.wxss) |
| 新增 | [apps/miniprogram/miniprogram/pages/settings/index.json](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/settings/index.json) |
| 新增 | [apps/miniprogram/miniprogram/pages/settings/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/settings/index.ts) |
| 新增 | [apps/miniprogram/miniprogram/pages/settings/index.wxml](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/settings/index.wxml) |
| 新增 | [apps/miniprogram/miniprogram/pages/settings/index.wxss](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/settings/index.wxss) |
| 新增 | [apps/miniprogram/miniprogram/pages/user-home/index.json](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/user-home/index.json) |
| 新增 | [apps/miniprogram/miniprogram/pages/user-home/index.ts](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/user-home/index.ts) |
| 新增 | [apps/miniprogram/miniprogram/pages/user-home/index.wxml](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/user-home/index.wxml) |
| 新增 | [apps/miniprogram/miniprogram/pages/user-home/index.wxss](D:/CODE/XinKeLink/apps/miniprogram/miniprogram/pages/user-home/index.wxss) |
| 修改 | [apps/miniprogram/scripts/generate-icons.cjs](D:/CODE/XinKeLink/apps/miniprogram/scripts/generate-icons.cjs) |
| 修改 | [apps/miniprogram/tests/market-detail.test.cjs](D:/CODE/XinKeLink/apps/miniprogram/tests/market-detail.test.cjs) |
| 新增 | [apps/miniprogram/tests/v1-contact-pages.test.cjs](D:/CODE/XinKeLink/apps/miniprogram/tests/v1-contact-pages.test.cjs) |
| 新增 | [apps/miniprogram/tests/v1-contact.test.cjs](D:/CODE/XinKeLink/apps/miniprogram/tests/v1-contact.test.cjs) |
| 新增 | [apps/miniprogram/tests/v1-global.test.cjs](D:/CODE/XinKeLink/apps/miniprogram/tests/v1-global.test.cjs) |
| 新增 | [apps/miniprogram/tests/v1-profile-content.test.cjs](D:/CODE/XinKeLink/apps/miniprogram/tests/v1-profile-content.test.cjs) |
| 新增 | [apps/miniprogram/tests/v1-profile-pages.test.cjs](D:/CODE/XinKeLink/apps/miniprogram/tests/v1-profile-pages.test.cjs) |
| 新增 | [apps/miniprogram/tests/v1-profile.test.cjs](D:/CODE/XinKeLink/apps/miniprogram/tests/v1-profile.test.cjs) |
| 修改 | [design-qa.md](D:/CODE/XinKeLink/design-qa.md) |
| 新增 | [docs/references/v1-chat-settings-other.png](D:/CODE/XinKeLink/docs/references/v1-chat-settings-other.png) |
| 新增 | [docs/references/v1-notifications.png](D:/CODE/XinKeLink/docs/references/v1-notifications.png) |
| 新增 | [docs/references/v1-profile-collections-posts.png](D:/CODE/XinKeLink/docs/references/v1-profile-collections-posts.png) |
| 新增 | [docs/references/v1-profile-settings-login.png](D:/CODE/XinKeLink/docs/references/v1-profile-settings-login.png) |
| 新增 | [docs/superpowers/plans/2026-10-23-v1-completion.md](D:/CODE/XinKeLink/docs/superpowers/plans/2026-10-23-v1-completion.md) |
| 新增 | [docs/v1-backend-interface-checklist.md](D:/CODE/XinKeLink/docs/v1-backend-interface-checklist.md) |
| 新增 | [docs/v1-frontend-verification.md](D:/CODE/XinKeLink/docs/v1-frontend-verification.md) |
| 修改 | [README.md](D:/CODE/XinKeLink/README.md) |


# 论坛核心前端验收

日期：2026-10-23。分支 codex/phase-1-foundations，HEAD 04dd5f0。相对本轮开始的 .local/forum-core/baseline.json 核对文件；上一轮所有未提交成果保留。本轮没有执行 commit、push、reset 或 clean。

## 已完成

- 帖子详情：复用帖子卡片，显示完整正文、作者公开资料、发布时间、全部图片，支持点赞、收藏、分享及微信原生全屏预览。首页卡片和评论入口进入相同帖子 ID 的详情；保留旧首页 postId 分享链接的兼容处理。
- 评论：一级评论、二级回复、回复二级仍归入同一一级线程、评论点赞、自己的评论删除确认、回复展开和长评论展开/收起。删除一级评论连带移除其回复，重新计算总数；不能删除他人评论。
- 发布：正文最多 2000 字、四个业务分类、匿名开关、最多 9 张图片（单张 10MB）、图片选择/删除/预览、必填校验、防重复提交、保存成功反馈并进入新帖详情。支持纯文字帖子；取消非空编辑需确认。
- 联动：新帖进入首页推荐和对应分类；首页、详情的点赞收藏与评论数量均来自同一仓库。操作前重新读取当前存储，避免旧页面覆盖其他页面的新状态。
- 中央发布：保留原发布闲置分支，新增发布帖子跳转。导航和发布选择层的 WXML/WXSS 未改变。

帖子与评论均在传入页面和分享数据前投影为公开字段。匿名作者自己的评论及回复目标保持匿名昵称与默认头像，不展示原作者 ID 或真实昵称；「作者」是该帖作者标记，不是学生认证。

新增本地帖子/评论分别保存在 xinkelink.forum.posts.v1、xinkelink.forum.comments.v1，点赞收藏沿用 xinkelink.forum.interactions.v1；论坛上传图片使用独立的 xlk-forum-upload- 文件前缀。读取异常时阻止写入；发布失败保留正文并回滚仅本次复制的文件；评论失败保留输入。

## 设计与运行入口

详情依据 docs/references/stitch-overview-02.png 第二张画板（x362 y63，236×575）。发布依据 market-publish-overview.png 第三张画板的顶部操作区（x844 y101，334×94）；正文区域未提供完整高清画板，已在开发前说明，并按本轮授权复用现有白色表单卡片、图片选择、分类与开关规范。没有编造话题、学生认证、关注业务或其他模块。

沿用原生小程序、既有 #6489B8 变量、字体、圆角与图标体系；复用所有论坛图片和头像，只增加两张 Lucide 评论点赞图标。公共 back-header 仅增加可选的返回拦截和兜底路由，默认行为保持原交易页面不变，未修改头部布局。

导入微信开发者工具：apps/miniprogram，普通编译。

1. 论坛 → 点击帖子或评论入口 → 详情 → 底部输入入口/评论回复。
2. 中央加号 → 发布帖子 → 填写正文、选图、分类、匿名 → 发布 → 新帖详情 → 返回首页查看。
3. 中央加号 → 发布闲置仍进入原有交易发布流程。

## 自动化检查

开始基线 70/70；详情后 75/75，评论后 82/82，发布与联动后 88/88。新增测试先运行失败再实现，过程中发现的 TypeScript 注解问题已修复。

最终实际执行：

```powershell
cd apps/miniprogram
npm run check
node scripts/check-wechat.cjs 'D:/develop/微信web开发者工具'
```

| 检查 | 结果 |
| --- | --- |
| TypeScript tsc --noEmit | 通过 |
| 全部小程序组件、页面与数据测试 | 88/88，通过；新增 18 项 |
| 页面/组件注册与工程结构检查 | 通过 |
| 官方 wcc / wcsc 编译 | 17 WXML、19 WXSS 通过 |
| git diff --check | 退出码 0 |

日志：.local/forum-core/final-check.log、final-compile.log；阶段失败和通过日志同目录保留。独立只读复核另跑相关 50 项测试及 7 项补充检查，涵盖交错页面写入、延迟删除确认期间的新回复、同级回复保留、匿名投影、四种写入失败、复制第二张图片失败回收及选图期间禁止发布，均通过，未发现待修的实质错误。

本轮没有开发或启动真实后端、数据库和 PC 后台业务，也未重跑后台与 Java 的检查。

## 实际模拟器

微信开发者工具 Stable v2.01.2510290，基础库 3.17.5。使用真实原生小程序运行；通过 IDE 控制台触发页面方法、原生文件选择器选图及鼠标点击确认/取消和发布按钮。截图为项目窗口捕获后的模拟器区域，未以网页或离线测试冒充模拟器。控制台填表不等同于手工键盘或真机输入验收。

| 设备 | 实际尺寸 | 检查 |
| --- | --- | --- |
| iPhone 12/13 mini | 375×812；状态栏 47px、底部安全区 34px | 详情四图、原生 2/4 图片预览、评论与长文展开、展开回复、回复编辑、评论点赞、删除取消/确认、发布必填校验、原生选图、匿名与分类、取消编辑保留、发布成功及首页同步 |
| iPhone 15 Pro Max | 430×932；状态栏 54px、底部安全区 34px | 详情双图/四图与匿名单图、评论回复区、完整发布表单、原生选图及图片删除、已有发布闲置入口、底栏与安全区 |

关键证据（均在 .local/forum-core/）：

- replied-runtime.json：回复后原帖评论 12→13；回复二级归入原一级线程。
- delete-cancel.json、delete-confirm.json：取消保留 14 条；确认后 13 条且自己的记录不再可见。
- detail-home-sync.json：首页重显同步点赞、收藏与 13 条评论。
- selected-image.json：原生选择器返回 http://tmp 图片路径；native-remove-image.json：点击删除后图片列表为空。
- empty-validation.json：空正文未创建帖子；cancel-publish-retained.json：取消放弃编辑后正文、图片和匿名设置保留。
- published-runtime.json、published-home.json：真实发布按钮创建同一 ID 的帖子；详情与首页均显示匿名、点赞 1、评论 1、收藏选中，自己的评论也保持匿名。
- product-entry.json：原有中央发布闲置仍进入 pages/market-publish/index。
- device-big.json、layout-publish-big.json、layout-detail-big.json：430px 视口下发布匿名卡结束 y≈730.33（安全区前 y898），详情固定底栏 y842–932，宽度完整为 430px。小屏完整布局见对应实际截图。

临时帖子、三条验收评论/回复、对应点赞收藏和一张论坛上传文件已定向清理；原 post-001 的既有 liked=true、collected=false 恢复，交易收藏未动。cleanup-runtime.json 确认 Storage 恢复原两个 key、新帖子及评论 key 不存在、论坛上传文件为零。既有论坛图片预览缓存保留。没有清空 Storage。

### 实际截图

以下位于 Git 忽略的 .local/forum-core/，不进入应用资源或提交。

| 页面/状态 | 375×812 | 430×932 |
| --- | --- | --- |
| 详情 | detail-mini.png（四图） | detail-two-images-big.png（双图）、detail-big.png（四图） |
| 评论与展开回复 | comments-mini.png、replies-expanded-mini.png | comments-big.png |
| 回复输入 | reply-mini.png | — |
| 发布表单 | publish-mini.png | publish-big.png |
| 匿名新帖详情 | published-mini.png（包含当时成功提示） | published-big.png（提示消失后的完整页面） |
| 原生图片预览 | preview-mini.png | — |
| 原生删除确认 | delete-confirm-mini.png | — |
| 首页联动 | home-linked-mini.png | — |

## 已知限制与未完成事项

本轮要求的前端功能已实现，自动检查和上述模拟器范围通过，未发现待处理的阻塞问题。仍有以下验收边界：

- 现有设计为总览图；发布正文缺少完整高清画板，当前按既有规范实现，未声称完成高清稿逐像素验收。对照图 comparison-detail.png、comparison-publish-header.png 已打开检查，详见根 design-qa.md。
- 未进行真实手机扫码、真实软键盘遮挡、相册权限真机差异及向微信好友实际发送分享的验收。分享路径和公开内容已检查，未代用户发送消息。
- 帖子、评论、收藏、图片仅存在当前工具/微信设备的本地模拟存储。没有真实用户登录、后端、跨设备同步或服务端匿名权限保护。
- 本轮不增加帖子编辑/删除、举报、关注、通知或其他未授权模块。

## 本轮文件清单

相对开始快照新增或修改 36 个项目文件；没有删除旧业务文件。下列目录中的 index.{ts,json,wxml,wxss} 表示实际四个文件。

- apps/miniprogram/miniprogram/app.json
- apps/miniprogram/miniprogram/assets/icons/thumbs-up-muted.png
- apps/miniprogram/miniprogram/assets/icons/thumbs-up-primary-filled.png
- apps/miniprogram/miniprogram/components/back-header/index.ts
- apps/miniprogram/miniprogram/components/forum-comment/index.{ts,json,wxml,wxss}
- apps/miniprogram/miniprogram/components/forum-post-card/index.{ts,wxml,wxss}
- apps/miniprogram/miniprogram/custom-tab-bar/index.ts
- apps/miniprogram/miniprogram/features/forum/feed.ts
- apps/miniprogram/miniprogram/features/forum/comments.ts
- apps/miniprogram/miniprogram/features/forum/media.ts
- apps/miniprogram/miniprogram/features/forum/publish.ts
- apps/miniprogram/miniprogram/features/forum/repository.ts
- apps/miniprogram/miniprogram/pages/forum/index.{ts,wxml}
- apps/miniprogram/miniprogram/pages/forum-detail/index.{ts,json,wxml,wxss}
- apps/miniprogram/miniprogram/pages/forum-publish/index.{ts,json,wxml,wxss}
- apps/miniprogram/scripts/generate-icons.cjs
- apps/miniprogram/tests/forum-comments.test.cjs
- apps/miniprogram/tests/forum-detail.test.cjs
- apps/miniprogram/tests/forum-publish.test.cjs
- apps/miniprogram/tests/forum-page.test.cjs
- apps/miniprogram/tests/navigation.test.cjs
- docs/superpowers/plans/2026-10-23-forum-core.md
- docs/forum-core-verification.md
- design-qa.md（追加记录，原验收保留）

77 个受保护的交易、我的闲置、品牌/模拟素材、导航视觉、变量、后台和服务端文件 SHA256 检查无变化。完整逐文件摘要清单 .local/forum-core/current-files.txt。

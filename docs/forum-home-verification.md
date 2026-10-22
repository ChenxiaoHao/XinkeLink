# 论坛首页开发与验收记录

日期：2026-10-22，Asia/Shanghai。工作区：D:\CODE\XinKeLink；分支 codex/phase-1-foundations；仓库仍无提交，本轮未提交或推送。

## 交付范围和设计依据

依据 docs/references/stitch-overview-01.png 右侧论坛画板、现有主题变量与本轮用户要求，实现原生微信小程序论坛首页。现有材料是缩小拼图，没有把未提供的高清画板或标注作为已知依据；当前不是高清稿的逐像素验收结论。

- 保留公共品牌头部；首页容器使其在页面滚动时保持顶部背景，避免图片进入状态栏。
- 顶部搜索入口、平台公告条，推荐/最新/校园生活/失物招领/学习交流/校园互助六分类。
- 白色圆角单列卡片：头像、昵称、相对发布时间、正文、图组、点赞、评论数、收藏、原生分享按钮。
- 单图横向、双图两列、四图两列、多图三列；点击图片调用原生全屏预览。
- 每批两条模拟记录；推荐排序、时间排序、分类筛选、下拉刷新、触底加载、本地关键词查询。
- 点赞与收藏同步保存到 xinkelink.forum.interactions.v1；保存失败保留原状态并反馈。重新进入和刷新可恢复状态。
- 匿名卡片使用通用头像和匿名编号，公开展示数据不含原始作者对象、姓名或 ID；没有认证标识或话题功能。
- 搜索与公告使用微信原生弹窗，未新增业务页面；评论仅展示数量。分享路径回到首页并定位对应卡片，未实现帖子详情。

12 条模拟记录在 features/forum/mock-posts.ts 中维护。照片与头像为本地模拟素材，不代表本校实拍或真实用户；来源和许可见 assets/mock/forum/README.md。页面没有真实后端请求。

## 实际文件变更

以下路径相对于 apps/miniprogram/。

| 类型 | 文件 |
| --- | --- |
| 修改现有文件 | miniprogram/pages/forum/index.ts、index.json、index.wxml、index.wxss |
| 修改图标脚本 | scripts/generate-icons.cjs，仅增加首页图标，原有图标文件哈希一致 |
| 新增数据与状态 | miniprogram/features/forum/types.ts、mock-posts.ts、feed.ts |
| 新增帖子组件 | miniprogram/components/forum-post-card/index.ts、index.json、index.wxml、index.wxss |
| 新增测试 | tests/forum-feed.test.cjs、forum-page.test.cjs、forum-card.test.cjs、page-harness.cjs |
| 新增本地图片 | miniprogram/assets/mock/forum/campus.jpg、desk.jpg、campus-green.jpg、library.jpg、tablet.jpg、book.jpg、avatar-lin.jpg、avatar-zhou.jpg、README.md |
| 新增图标 | miniprogram/assets/icons/search-primary.png、megaphone-primary.png、chevron-right-muted.png、heart-muted.png、heart-primary-filled.png、bookmark-muted.png、bookmark-primary-filled.png、message-circle-muted.png、share-2-muted.png、ellipsis-muted.png |

根目录新增文档：docs/superpowers/plans/2026-10-22-forum-home.md、本文件。没有新增 npm 依赖。

以本轮开始前 100 个文件的 SHA256 为基准，只改动了上述 5 个现有文件，没有删除文件。以下源码/配置与基准一致：custom-tab-bar 全部文件、publish-sheet 全部文件、page-header 全部文件、styles/tokens.wxss、app 配置、project.config.json（保留用户 AppID）、其他小程序页面、PC 后台、Java 服务端、原 README。完整对照结果在 .local/forum-home/file-changes.json。

## 已执行的自动化验证

统一入口实际执行：

~~~powershell
Set-Location D:\CODE\XinKeLink
.\scripts\verify.ps1 -WeChatDevToolsPath 'D:\develop\微信web开发者工具'
~~~

| 检查 | 实际结果 |
| --- | --- |
| 小程序 TypeScript | tsc --noEmit 通过 |
| 小程序测试 | 38 项通过，0 失败；含原有 14 项以及论坛 24 项 |
| 小程序工程检查 | JSON、页面文件、组件引用与图标入口通过 |
| 官方 wcc.exe / wcsc.exe | 8 个 WXML、10 个 WXSS 离线语法编译通过 |
| PC 后台既有检查 | 4 项测试、Vue 类型检查、Vite 构建通过 |
| Maven verify | 2 项既有 HTTP 测试通过，BUILD SUCCESS |
| 独立只读代码审查 | 初审和增量复审未发现实质性问题；审查者独立执行论坛 24 项测试通过 |

测试先运行并观察到缺失实现失败，再完成实现。预览问题另有两项回归测试，先失败、修复后通过。Node 测试使用原生 Page/Component 注册适配，不能证明微信渲染。官方离线编译也不是模拟器验收。

完整统一检查日志：.local/forum-home/verify-all.log。Maven 有本机 JDK 原生访问和 Mockito 动态 agent 警告，未导致失败。本轮没有重新执行 PC 浏览器端到端测试或启动生产服务。

## 微信开发者工具的实际验证

实际工具：Windows 微信开发者工具 Stable v2.01.2510290；本地基础库 3.17.5；iPhone 15 Pro Max 模型，430 × 932。没有开启原本关闭的 CLI 服务端口，通过现有 IDE 模拟器和调试控制台操作。

已实际观察：

- 编译后首页显示品牌、六分类、公告条、四图帖子与匿名单图帖，底部五栏和中央按钮均可见。
- 直接点击最新、学习交流、校园互助、失物招领，选中态与帖子内容切换；学习交流页面显示双图及三图布局。推荐首屏显示四图。
- 直接点击首帖点赞，28 变 29、心形变为主色；点击收藏，实心书签变为空心。重新编译后状态保留。
- 通过调试控制台调用原生 wx.pageScrollTo 到底部，触发真实 onReachBottom，页面加载后续记录；另实际发送鼠标滚轮操作，后续卡片可见，顶部品牌和底部导航保持固定。
- 原生 wx.startPullDownRefresh 触发页面刷新：列表回到两条，点赞 29 与收藏状态保留，刷新结束。不是用 Node 测试代替这个结果；本轮未将模拟器的手动下拉手势标为通过。
- 实际点击图片进入原生全屏预览，并显示四图组中的照片。

验收中发现并修复：

1. 页面滚动时品牌头部移出屏幕，图片进入状态栏。仅增加论坛页面的 sticky 容器；分享定位扣除实测头部高度。
2. 代码包图片直接传入 wx.previewImage 时一直加载，即使 API 返回 success 也没有显示图片。实际控制台试验确认，复制到 wx.env.USER_DATA_PATH 后可以显示；最终组件对论坛模拟图片做此转换。使用固定文件名，最多对应六种正文图片，失败时提示，其他路径透传。
3. 新增页面/组件样式改用类选择器；新按钮用两级类选择器覆盖 style:v2 的默认宽度。原公共组件已有的选择器警告仍属于既有代码，未修改相关文件。

本地实际截图（不是生成的效果图）：

| 内容 | 文件 |
| --- | --- |
| 首页四图、五栏导航 | .local/forum-home/home-final.png |
| 点赞 29、收藏切换 | .local/forum-home/liked-collected.png |
| 匿名单图帖 | .local/forum-home/lost-anonymous.png |
| 学习交流双图及三图 | .local/forum-home/study-double.png |
| 校园互助文本与单图 | .local/forum-home/help-text.png |
| 最新分类 | .local/forum-home/latest.png |
| 真实运行时触底记录 | .local/forum-home/load-more-runtime.png |
| 真实运行时刷新后数据 | .local/forum-home/refresh-after-wait.png |
| 实际滚轮后的页面 | .local/forum-home/scroll-gesture.png |
| 修复后的全屏图片 | .local/forum-home/preview-fixed.png、preview-fixed-ide.png |

截图为本地忽略产物，不进入小程序代码包。真实微信客户端的分享接收、真机触摸、更多机型、重复预览以及高清稿精校未在本轮完成；不把这些项目描述为验收通过。六图布局的映射、完整分类过滤、缓存恢复、失败路径、分享返回值和匿名信息由自动化测试覆盖。

## 运行与复核

微信开发者工具继续打开 D:\CODE\XinKeLink\apps\miniprogram，点击「编译」，进入 pages/forum/index。无需启动 Java 或 MySQL。推荐列表首批两条，上滑至底部加载后续记录；分类切换重置分页；下拉刷新保留本地交互。

搜索入口弹出原生输入框，可搜索 iPad；输入留空恢复全部推荐数据。公告条点击后显示完整公告。评论未跳转详情，分享落到论坛首页。本轮停在首页范围。

如需重置点赞与收藏，只删除开发者工具 Storage 中的 xinkelink.forum.interactions.v1，不需要改工程配置或 AppID。

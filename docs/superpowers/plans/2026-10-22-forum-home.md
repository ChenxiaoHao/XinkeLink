# 论坛首页实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 按已有 Stitch 论坛首页拼图实现使用本地模拟数据的原生小程序首页。

**Architecture:** 数据类型、模拟记录和信息流查询/本地交互状态分离；帖子卡片为原生组件；页面处理分类、分页、下拉刷新及本地存储。

**Tech Stack:** 原生微信小程序、TypeScript、WXML/WXSS，沿用现有主题变量和本地 PNG 图标。

**Spec:** 用户本轮十项要求；docs/references/stitch-overview-01.png 右侧论坛页；docs/design-baseline.md；docs/tabbar-p1-fix.md。

## Global Constraints

- 五栏导航、公共变量及 P1 修复源码保持不变。
- 只开发首页；不新增详情、编辑器、管理后台或真实接口。
- 无话题标签、学生认证标识；匿名帖公开视图不含真实作者信息。
- 已有材料为缩小拼图，不声称达到未提供的高清标注精度。
- 本地模拟图像不代表学校实拍；来源记录在素材目录。

## Review Focus

- 分类及搜索后重新分页，不混入其他分类的记录。
- 点赞重复切换不累加漂移，刷新/分类切换后保持本地交互状态。
- 匿名信息在卡片数据和分享信息中均不暴露。
- 微信 v2 原生按钮样式不覆盖卡片及顶部控件宽度。
- 页面自然滚动、触底分页、底部留白不遮挡已修复导航。

## Task 1 数据与状态

**Files:** features/forum/{types,mock-posts,feed}.ts，tests/forum-feed.test.cjs。
**Interfaces:** 六分类常量；queryFeed；toPostView；normalizeInteractions；toggleInteraction；ForumPostView。

- [x] 先写分页、分类、匿名、图片布局、本地交互状态测试并观察失败。
- [x] 实现类型、本地样本和纯函数，验证通过。

## Task 2 帖子卡片与首页

**Files:** components/forum-post-card/index.*，pages/forum/index.*，tests/forum-page.test.cjs / forum-card.test.cjs。
**Interfaces:** 卡片事件 like / collect；图片预览；页面原生 onPullDownRefresh / onReachBottom / onShareAppMessage。

- [x] 测试首页事件、刷新、分页和状态持久化边界。
- [x] 按截图还原品牌、搜索、公告、六分类、单列卡片与图组。
- [x] 评论仅展示数量；分享回到首页；搜索使用本地关键词，不创建新页面。

## Task 3 资源与验收

**Files:** 本地图标、模拟图片及来源文档，docs/forum-home-verification.md。

- [x] 执行类型检查、所有小程序测试、工程检查和官方离线编译。
- [x] 在真实微信开发者工具中编译、操作并截图；记录未能执行的项目。
- [x] 只读审查，核对导航、其他业务工程与账号配置未被代码覆盖。

验收证据、修复记录及未执行项见 docs/forum-home-verification.md。

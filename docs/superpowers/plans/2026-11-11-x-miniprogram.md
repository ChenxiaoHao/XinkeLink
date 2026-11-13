# X 手机端小程序改造 Implementation Plan

> **For agentic workers:** Use executing-plans inline; user requested implementation in this chat.

**Goal:** 实现已确认的 X 手机端导航、信息流、侧栏及各主要页面。
**Architecture:** 保持原生 TypeScript/WXML/WXSS，复用 registerPage 真实 API 适配；新增页面共享既有消息和登录准入逻辑。以共享组件控制视觉与导航。
**Tech Stack:** 原生微信小程序、TypeScript、Node test runner、Lucide PNG 图标。
**Spec:** ../specs/2026-11-11-x-miniprogram-design.md

## Constraints
保留现有未提交成果、真实接口与匿名保护；不伪造 AI、关注或转发成功；不提交或部署。

## Review focus
普通页与 tab 页跳转差异；内测准入重定向；互动后滚动和分页保持；通知未读和私信未读隔离；侧栏遮罩与滑动冲突。

## Tasks
- [x] 1. 导航和侧栏：更新 app.json、utils/navigation.ts、custom-tab-bar、page-header；新增五导航和侧栏行为测试，先失败后实现。
- [x] 2. 首页与卡片：forum 双时间线，forum-post-card 布局和操作，复用收藏，验证匿名及图片预览。
- [x] 3. 主页面：独立 notifications、ai；search 升为 tab；更新 contact 与原页面跳转。核对准入和生命周期。
- [x] 4. 编辑器与次级页面：统一视觉、保留商品发布和个人资料业务。
- [x] 5. 执行 npm run check 与微信离线编译，工具截图；记录实际结果和未接通的服务。

292 项测试、类型与工程检查通过；34 个 WXML、38 个 WXSS 离线编译通过。隔离 fixture 模拟器完成主页面检查。关注、转发引用、AI 服务、统一发布器及真实设备验收仍待完成；整体逐像素复刻目标未完成。实际记录见 docs/ui/2026-11-11-x-miniprogram.md。

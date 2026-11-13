# 校园二手交易前端验收

日期：2026-10-22。基线提交：04dd5f0。仅小程序前端，代码未提交。

## 实现范围

- 交易首页：校园二手标题、搜索、六分类、综合/最新/双向价格排序、原生筛选、双列瀑布流、卖家和成色、售出灰色遮罩、刷新和每页 4 条分页、商品跳转。模拟商品共 12 条。
- 商品详情：两张同一键盘的轮播与全屏预览、价格/成色/描述、卖家资料原生弹窗、校内面交提醒、收藏、本地商品关联聊天、已售/自己发布的商品禁咨询、无效 ID 状态。
- 发布闲置：最多 9 张图片，选择/删除/预览/主图；标题、描述、价格、分类、成色、议价；必填和价格校验；本地草稿；图片文件持久保存；发布防重复及成功后进入详情。价格按整数分保存，合法范围 0.01–99999.99 元。
- 商品关联会话：按提供的聊天参考显示商品卡片、模拟历史和本地文字发送；不自动伪造卖家回复。商品卡优先返回已打开的详情，避免重复堆叠页面。
- 用户明确允许只接通中央「发布闲置」跳转：custom-tab-bar/index.ts 增加 product 分支；导航 WXML/WXSS、五栏布局和发布选择面板未改。

## 设计依据和边界

来源：docs/references/market-overview-01.png、market-overview-02.png、market-publish-overview.png，均为用户本轮提供的设计总览。沿用原有 #6489B8 变量、浅背景、白卡、圆角、阴影、头部胶囊避让。

表单在原描述卡内增加用户明确要求的商品标题输入。推荐是首页排序入口，不作为可发布商品分类，发布提供其余五个物品类别。没有增加话题标签、认证身份、购物车、支付、担保交易；图中的可选视频、发布帖子、我的闲置管理和通知页不属于本轮实现。卖家「查看主页」当前打开资料弹窗，不创建个人主页业务模块。

商品照片为本轮独立生成的模拟图片，无截图 UI；来源及尺寸见 assets/mock/market/README.md。头像复用既有模拟资源。原论坛、Logo、公共头部、变量和导航样式经 Git 对照保持不变。

## 逐页自动检查

| 完成位置 | TypeScript / 项目结构 | 小程序测试 | 官方离线编译 |
| --- | --- | --- | --- |
| 首页 | 通过 | 44/44 | 9 WXML、11 WXSS |
| 详情与商品关联聊天 | 通过 | 50/50 | 12 WXML、14 WXSS |
| 发布页 | 通过 | 56/56 | 13 WXML、15 WXSS |
| 复核修复后 | 通过 | 60/60 | 13 WXML、15 WXSS |

最终执行根 scripts/verify.ps1：小程序 60 项、管理后台 4 项、Java 2 项测试均通过；Vue 类型检查/Vite 构建、Maven verify/打包均成功。实际日志 .local/market-frontend/all-check.log，退出码 0。本轮没有重跑后台浏览器 E2E，未修改后台或服务端。

复核修复并有失败→通过证据：旧聊天页覆盖新历史；商品/收藏/消息读取失败后仍覆盖写入；返回旧详情收藏状态未同步。最终复核 21 项交易测试通过。真实模拟器发现并修正双列数组 wx:key 重复警告，以及空输入时发送按钮的禁用底色。

## 微信开发者工具实际验收

工具 Stable v2.01.2510290，基础库 3.17.5。实际运行原生小程序，非网页替代。CLI 自动连接初始化超时；验收使用已打开工具的控制台触发页面方法、原生界面选图/点击和微信自己的图片预览，截图通过窗口捕获并裁切模拟器区域。离线编译不替代以下实际验收。

| 机型 | 逻辑尺寸 | 已验证 |
| --- | --- | --- |
| iPhone 15 Pro Max | 430×932，底部安全区 34px | 三页实际显示、首页类别/分页/刷新/搜索结果/数值排序、详情与收藏、关联聊天发送、本机文件选择器选图、草稿、原生预览、发布按钮成功持久化并跳转 |
| iPhone 12/13 mini | 375×812，底部安全区 34px | 三页显示、轮播切换与打包图片全屏预览、收藏、售出禁咨询、中央发布入口、草稿恢复、卖家区域可滚动抵达、五栏和底部操作区 |

小屏运行时测量：五个导航槽宽均为 75.0625px；详情底栏覆盖 y=722–812，发布底栏 y=721–812；当前表单成色行底部 y≈719.67，可见且在底栏之上。滚至详情底部时卖家卡 y≈562.67–662，位于底栏上方。浮点 rpx 换算存在约 0.33px 舍入，不构成入口裁切。

发布验收创建的单条商品、测试草稿、测试消息和唯一持久图片已定向清理，未清空 Storage 或论坛交互。cleanup-runtime.json 已确认所有测试状态清理成功；工具最终停在交易首页。

## 实际截图

以下均为本地忽略文件，不放入代码包或提交。

| 页面 / 状态 | iPhone 15 Pro Max | iPhone 12/13 mini |
| --- | --- | --- |
| 首页 | .local/market-frontend/home-iphone15promax.png | .local/market-frontend/home-iphone-mini.png |
| 商品详情 | .local/market-frontend/detail-iphone15promax.png | .local/market-frontend/detail-iphone-mini.png |
| 发布表单 | .local/market-frontend/publish-iphone15promax.png | .local/market-frontend/publish-iphone-mini.png |
| 原生图片预览 | .local/market-frontend/publish-preview-iphone15promax.png | .local/market-frontend/detail-preview-iphone-mini.png |
| 发布成功后的详情 | .local/market-frontend/published-iphone15promax.png | — |
| 卖家卡滚动可达 | — | .local/market-frontend/detail-seller-iphone-mini.png |
| 售出不可咨询 | — | .local/market-frontend/sold-iphone-mini.png |
| 商品关联聊天 | — | .local/market-frontend/chat-iphone-mini.png |

## 模拟范围和实际限制

商品查询、排序、筛选和历史均为本地模拟。收藏、草稿、发布商品、图片和发送文字仅存在当前工具/微信设备的本地文件与 Storage，无 HTTP、WebSocket、MySQL、真实登录、上传服务或对端收信。

本轮未进行真实手机扫码、跨设备同步、生产上传、真实聊天收发或移动端软键盘遮挡的真机验收。设计来源为总览图，已按其结构和风格对照；未声称拥有高清单页标注或完成逐像素验收。完整视觉对照见项目根 design-qa.md。

运行：微信工具导入 apps/miniprogram，普通编译，点击「交易」；中央加号→发布闲置；商品卡→详情→我想要。无需启动后端。

## 修改文件清单

下方由 Git 当前差异及新增文件生成，含源设计参考；不含 .local、node_modules 或构建产物。
- apps/miniprogram/miniprogram/app.json
- apps/miniprogram/miniprogram/assets/icons/arrow-down-primary.png
- apps/miniprogram/miniprogram/assets/icons/arrow-up-down-primary.png
- apps/miniprogram/miniprogram/assets/icons/arrow-up-primary.png
- apps/miniprogram/miniprogram/assets/icons/arrow-up-white.png
- apps/miniprogram/miniprogram/assets/icons/camera-primary.png
- apps/miniprogram/miniprogram/assets/icons/chevron-down-primary.png
- apps/miniprogram/miniprogram/assets/icons/chevron-left-muted.png
- apps/miniprogram/miniprogram/assets/icons/file-muted.png
- apps/miniprogram/miniprogram/assets/icons/map-pin-primary.png
- apps/miniprogram/miniprogram/assets/icons/send-white.png
- apps/miniprogram/miniprogram/assets/icons/shield-check-primary.png
- apps/miniprogram/miniprogram/assets/icons/sliders-horizontal-primary.png
- apps/miniprogram/miniprogram/assets/mock/market/bag.jpg
- apps/miniprogram/miniprogram/assets/mock/market/charger.jpg
- apps/miniprogram/miniprogram/assets/mock/market/cooker.jpg
- apps/miniprogram/miniprogram/assets/mock/market/keyboard-detail.jpg
- apps/miniprogram/miniprogram/assets/mock/market/keyboard.jpg
- apps/miniprogram/miniprogram/assets/mock/market/README.md
- apps/miniprogram/miniprogram/assets/mock/market/storage.jpg
- apps/miniprogram/miniprogram/assets/mock/market/textbooks.jpg
- apps/miniprogram/miniprogram/components/back-header/index.json
- apps/miniprogram/miniprogram/components/back-header/index.ts
- apps/miniprogram/miniprogram/components/back-header/index.wxml
- apps/miniprogram/miniprogram/components/back-header/index.wxss
- apps/miniprogram/miniprogram/components/market-product-card/index.json
- apps/miniprogram/miniprogram/components/market-product-card/index.ts
- apps/miniprogram/miniprogram/components/market-product-card/index.wxml
- apps/miniprogram/miniprogram/components/market-product-card/index.wxss
- apps/miniprogram/miniprogram/custom-tab-bar/index.ts
- apps/miniprogram/miniprogram/features/market/catalog.ts
- apps/miniprogram/miniprogram/features/market/conversation.ts
- apps/miniprogram/miniprogram/features/market/media.ts
- apps/miniprogram/miniprogram/features/market/mock-products.ts
- apps/miniprogram/miniprogram/features/market/publish.ts
- apps/miniprogram/miniprogram/features/market/repository.ts
- apps/miniprogram/miniprogram/features/market/types.ts
- apps/miniprogram/miniprogram/pages/market-chat/index.json
- apps/miniprogram/miniprogram/pages/market-chat/index.ts
- apps/miniprogram/miniprogram/pages/market-chat/index.wxml
- apps/miniprogram/miniprogram/pages/market-chat/index.wxss
- apps/miniprogram/miniprogram/pages/market-detail/index.json
- apps/miniprogram/miniprogram/pages/market-detail/index.ts
- apps/miniprogram/miniprogram/pages/market-detail/index.wxml
- apps/miniprogram/miniprogram/pages/market-detail/index.wxss
- apps/miniprogram/miniprogram/pages/market-publish/index.json
- apps/miniprogram/miniprogram/pages/market-publish/index.ts
- apps/miniprogram/miniprogram/pages/market-publish/index.wxml
- apps/miniprogram/miniprogram/pages/market-publish/index.wxss
- apps/miniprogram/miniprogram/pages/market/index.json
- apps/miniprogram/miniprogram/pages/market/index.ts
- apps/miniprogram/miniprogram/pages/market/index.wxml
- apps/miniprogram/miniprogram/pages/market/index.wxss
- apps/miniprogram/scripts/generate-icons.cjs
- apps/miniprogram/tests/market-chat.test.cjs
- apps/miniprogram/tests/market-detail.test.cjs
- apps/miniprogram/tests/market-page.test.cjs
- apps/miniprogram/tests/market-publish.test.cjs
- apps/miniprogram/tests/market-query.test.cjs
- apps/miniprogram/tests/market-storage.test.cjs
- apps/miniprogram/tests/navigation.test.cjs
- design-qa.md
- docs/market-frontend-verification.md
- docs/references/market-overview-01.png
- docs/references/market-overview-02.png
- docs/references/market-publish-overview.png
- docs/superpowers/plans/2026-10-22-market-frontend.md

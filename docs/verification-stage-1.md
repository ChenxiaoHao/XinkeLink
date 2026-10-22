# 第一阶段验收记录

日期：2026-10-22，Asia/Shanghai。验证对象为当前工作区 codex/phase-1-foundations 分支，仓库尚无提交。

## 实际交付

~~~text
D:\CODE\XinKeLink
├─ README.md                         原样保留
├─ .editorconfig
├─ .gitignore
├─ apps
│  ├─ miniprogram
│  │  ├─ project.config.json
│  │  ├─ package.json / package-lock.json
│  │  ├─ tsconfig.json / tsconfig.test.json
│  │  ├─ miniprogram
│  │  │  ├─ app.ts / app.json / app.wxss
│  │  │  ├─ sitemap.json
│  │  │  ├─ styles/tokens.wxss
│  │  │  ├─ custom-tab-bar/index.*
│  │  │  ├─ components/page-header/index.*
│  │  │  ├─ components/publish-sheet/index.*
│  │  │  ├─ pages/forum/index.*
│  │  │  ├─ pages/market/index.*
│  │  │  ├─ pages/contact/index.*
│  │  │  ├─ pages/profile/index.*
│  │  │  ├─ utils/navigation.ts
│  │  │  └─ assets/icons/              17 个 PNG 与 Lucide 许可
│  │  ├─ scripts/                     图标生成、工程检查、微信离线检查
│  │  └─ tests/                       导航、面板与头部行为测试
│  ├─ admin
│  │  ├─ package.json / package-lock.json
│  │  ├─ index.html / tsconfig.json
│  │  ├─ vite.config.ts / vitest.config.ts / playwright.config.ts
│  │  ├─ src/App.vue / src/main.ts
│  │  ├─ src/styles/                  同语义主题变量与基础样式
│  │  ├─ src/services/health.ts
│  │  ├─ tests/health.test.ts
│  │  └─ e2e/bootstrap.spec.ts
│  └─ server
│     ├─ pom.xml
│     └─ src
│        ├─ main/java/cn/xinkelink/
│        │  ├─ XinkeLinkApplication.java
│        │  ├─ health/HealthController.java
│        │  └─ storage/                ImageStorage / ImageUpload / StoredImage
│        ├─ main/resources/application.yml
│        └─ test/java/cn/xinkelink/health/HealthHttpTest.java
├─ docs
│  ├─ architecture.md
│  ├─ design-baseline.md
│  ├─ runbook.md
│  ├─ verification-stage-1.md
│  ├─ references/                     两张原始 Stitch 拼图的副本
│  └─ superpowers/plans/2026-10-22-phase-1.md
└─ scripts
   ├─ verify.ps1
   └─ run-server.ps1
~~~

node_modules、dist、target、测试输出和 .local 临时验证材料已加入忽略规则。没有引入 workspace、共享类型包或额外 monorepo 构建工具。

## 已执行与结果

| 检查 | 实际结果 | 能证明的范围 |
| --- | --- | --- |
| README SHA256 前后对照 | 一致 | 原有文件未覆盖 |
| 小程序 npm run check | 通过 | TypeScript、14 项组件行为测试、JSON/页面/组件引用检查 |
| 微信工具自带 wcc.exe | 7 个 WXML 语法检查通过 | 不代表原生渲染或视觉通过 |
| 微信工具自带 wcsc.exe -lc | 9 个 WXSS 语法检查通过 | 不代表样式兼容与安全区通过 |
| 后台 npm run check | 通过 | 4 项接口边界测试、Vue 类型检查、Vite 生产构建 |
| Maven verify | BUILD SUCCESS | 2 项 Spring HTTP 集成测试通过，jar 打包成功 |
| 运行 jar 后请求 /api/v1/health | HTTP 200，application=xinkelink-server，status=UP | 真实 Java 服务启动 |
| /actuator/health | HTTP 200，status=UP | 运行健康；不是数据库或业务健康 |
| 通过 Vite 请求 /api/v1/health | HTTP 200 | 后台开发代理到 Java 服务正常 |
| 后台 Chrome 端到端测试 | 2 项通过 | 页面渲染、实际连接、HTTP 失败提示、重试恢复；成功场景无 pageerror |
| 后台实际截图打开检查 | 已执行 | 工程启动页可见，无截断；尚无 PC 设计稿可对照 |
| 根目录 verify.ps1 | 通过 | 上述静态检查、测试、离线语法检查和构建的统一入口可用 |

最新统一检查日志：.local/stage-1-verify.log。浏览器截图：.local/admin-connected.png 和 .local/admin-unavailable.png；已打开检查成功截图。它们为本地忽略产物。

## 过程中遇到并处理的情况

- 自定义 Java 健康端点实现前，测试得到预期的 404；实现后测试通过。
- 小程序组件与后台健康读取模块实现前，测试因缺失实现失败；实现后通过。原生组件测试使用 Node 边界适配，并不是微信模拟器测试。
- 首轮后台采用 Element Plus 全量注册产生大 chunk 警告。改为显式使用三个组件后，JS 构建产物约 113KB，复核构建无此警告。
- Java 首次直接启动因本机 JDK 25 的临时套接字目录报错。独立 Selector.open() 程序可复现；指定项目目录后通过。run-server.ps1 固化当前 JVM 的局部参数后，服务已实际启动并完成 HTTP 与浏览器验证。
- Maven/JDK 输出包含 Jansi 原生访问、Mockito 动态 agent 等环境警告；未导致测试或构建失败。
- 微信 CLI 检查提示“工具的服务端口已关闭”。没有修改本机 IDE 安全设置；因此未把 CLI 自动化或模拟器渲染写为通过。

## 未执行、未通过验收的项目

- 微信开发者工具完整导入、模拟器运行与点击。
- 微信真机预览、不同屏幕、安全区与实际触摸/手势验证。
- 小程序与单页高清 Stitch 稿的逐项视觉比对。当前仅据拼图建立公共结构，非像素级还原结论。
- MySQL 连接和持久化、真实聊天、登录、图片上传或任何生产服务。

第一阶段代码与自动化检查已交付；小程序的原生运行和视觉仍待验收。后续需单页高清设计与页面盘点报告，再推进第二阶段页面实现。

最终只读审查：未发现需修复的 P1/P2 问题；审查者独立确认应用健康接口返回 UP，原生模拟器与真机仍为待验收项。

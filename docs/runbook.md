# 第一阶段运行方法（历史记录）

本文保留工程初始化时的环境与操作，不是当前启动指南。当前 Java 21、真实数据库、Redis、微信登录和 HTTPS 后台的配置及恢复步骤见[项目冻结与恢复指南](12-project-freeze-and-resume.md)，验收边界见[最终审计报告](11-final-audit-report.md)。请勿按下文的早期无数据库、touristappid 或健康演示服务替代当前业务环境。

当前范围补充（2026-11-11）：项目已收敛为纯校园社交社区，保留论坛、评论、点赞、私信、公告、举报、意见反馈、内测与管理功能。二手功能及关联数据已移除；当前API见[正式契约](backend/04-api-contract.md)，迁移与验证见[数据库移除报告](backend/19-marketplace-removal-database.md)。下文初始化阶段的页面和业务说明仅为历史记录。

## 环境

- Node.js 24.12+；本次实际使用 24.15.0 / npm 11.12.1。
- JDK 21+、Maven 3.6.3+；Java 编译目标为 21。本次实际使用 Oracle JDK 25.0.1 / Maven 3.9.4。
- Spring Boot 4.1.1，Vue 3.5.44，Vite 8.3.5，Element Plus 2.14.7，TypeScript 5.9.3；版本由 pom 和两个 package-lock.json 固定。
- 微信开发者工具稳定版。小程序运行时不依赖 npm 包，不需要执行“构建 npm”。
- 本阶段无需安装或启动 MySQL，不配置微信密钥、聊天服务或图片存储凭据。

## 微信小程序

导入目录：D:\CODE\XinKeLink\apps\miniprogram。该目录包含 project.config.json，miniprogramRoot 指向 miniprogram/。

项目默认使用 touristappid 进行本地工程开发。实际账号权限、预览和上传需后续配置用户自己的小程序 AppID；不要把私有配置或密钥提交到仓库。

在微信开发者工具中导入后，使用 TypeScript 编译插件，默认进入论坛页面。检查四个 tab 的切换、选中状态，点击中央加号打开面板，测试遮罩关闭、关闭按钮、帖子和闲置两种选择。

~~~powershell
Set-Location D:\CODE\XinKeLink\apps\miniprogram
npm.cmd ci
npm.cmd run check
~~~

PNG 图标已生成，可直接导入运行。修改图标来源后可执行 npm.cmd run icons 重新生成。

本机可额外进行微信官方工具所附编译器的离线语法检查：

~~~powershell
node scripts/check-wechat.cjs 'D:\develop\微信web开发者工具'
~~~

这只编译 WXML/WXSS，不启动模拟器，也不证明视觉或真机适配通过。

## Java 服务端

~~~powershell
Set-Location D:\CODE\XinKeLink\apps\server
mvn -B verify

Set-Location D:\CODE\XinKeLink
.\scripts\run-server.ps1
~~~

run-server.ps1 在 jar 缺失时先运行 Maven package；需要主动重建时加 -Build。默认监听 127.0.0.1:8080，Ctrl+C 停止。支持 -Port 参数；变更端口时需同步修改 Vite 的代理地址。

本机首次直接运行 jar 时，JDK 在用户的短格式临时目录下建立 Selector 套接字失败。最小 Java 程序可以复现，指定项目内目录后恢复。运行脚本仅对当前 JVM 设置 jdk.net.unixdomain.tmpdir，不修改系统环境变量或网络设置。

正常 JDK 环境亦可在 apps/server 执行 mvn spring-boot:run。遇到上述 Windows Selector 错误时使用运行脚本。

- http://127.0.0.1:8080/api/v1/health：应用健康信息。
- http://127.0.0.1:8080/actuator/health：基础运行健康。
- 第一阶段没有数据库连接、聊天、图片上传或其他业务接口。

## PC 管理后台

另开终端，保持 Java 服务运行：

~~~powershell
Set-Location D:\CODE\XinKeLink\apps\admin
npm.cmd ci
npm.cmd run dev
~~~

打开 http://127.0.0.1:5173/，点击“检查服务连接”。/api 请求由 Vite 代理到 127.0.0.1:8080。页面为工程启动与连接检查页，尚未实现管理业务页面，也不作为 PC 后台设计定稿。

~~~powershell
npm.cmd run check
npm.cmd run test:e2e
~~~

check 执行接口边界测试、Vue 类型检查和生产构建。test:e2e 使用本机 Chrome，需 Java 服务正在运行；会复用已启动的 Vite，或自动启动并在结束后关闭测试用 Vite。检查实际连接，以及服务失败后的重试恢复。截图写入仓库 .local/，不提交。

npm.cmd run preview 仅用于检查生产静态资源，不提供开发代理；本阶段端到端连接请使用 npm.cmd run dev。

## 统一检查

先分别执行两个前端工程的 npm.cmd ci：

~~~powershell
Set-Location D:\CODE\XinKeLink
.\scripts\verify.ps1 -WeChatDevToolsPath 'D:\develop\微信web开发者工具'
~~~

不传 WeChatDevToolsPath 时会明确跳过离线编译。统一脚本不自动启动服务或浏览器；实际 HTTP 与浏览器验证单独运行。

## 当前文件保护与后续材料

原始 README.md 未修改，SHA256 为 FBCFFA36783CF06B73314A4092A789D993B20D9F1CB41E2EBEE4B09D6A25B6BF。当前分支 codex/phase-1-foundations，未创建提交或远程仓库。

下一阶段先对照页面盘点报告及论坛首页、我的、发布选择面板的单页高清图，再逐批实现完整页面。身份标识和关注功能仍需依据业务范围确认。

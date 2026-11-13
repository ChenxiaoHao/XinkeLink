# XinkeLink

中文名称：信科 Link

2026-10-26 冻结前审计已完成本轮可执行检查，结论为 **C：检查不完整，证据不足**，不代表正式 V1.0.0 全量验收通过。当前状态、验证边界和已修复问题见[最终审计报告](docs/11-final-audit-report.md)，暂停与恢复操作见[项目冻结与恢复指南](docs/12-project-freeze-and-resume.md)。旧阶段报告作为历史记录保留。

面向河南信息科技学院学生的纯校园社交社区微信小程序，配套独立 PC 管理后台。

技术栈已确定：原生微信小程序 + TypeScript + WXML/WXSS；PC 后台为 Vue 3 + TypeScript + Vite + Element Plus；服务端为 Java + Spring Boot + Maven，数据库 MySQL。保留单仓库，分别位于 `apps/miniprogram`、`apps/admin`、`apps/server`。

当前两端正式页面默认使用Java API：Java 21、Spring Boot 4.1.1、MySQL 8.4、Redis 7。论坛、评论、点赞、私信、公告、举报、意见反馈、内测及管理操作和配置持久化到服务端；搜索历史和自填学号仅保存在小程序本机。自填资料不代表学校或平台认证。

先依照[后端环境报告](docs/backend/06-day1-foundation.md)与[认证报告](docs/backend/07-day2-authentication.md)配置环境变量并启动Java服务。当前前端开发地址为`http://127.0.0.1:18081`。密钥不写入仓库，`.env.example`不会自动加载。

在`apps/miniprogram`执行`npm run check`，微信开发者工具导入此目录。访问本地HTTP服务时，仅在开发工具本地配置中关闭域名校验；真实微信登录仍需服务端AppID/AppSecret及正式域名，不会接受客户端传来的openid或演示登录标志。

在`apps/admin`执行`npm run check`及`npm run dev`。Vite将`/api`代理到18081；管理员刷新Cookie要求HTTPS，开发时通过`XINKELINK_DEV_CERT`、`XINKELINK_DEV_KEY`指定本地证书和私钥，访问`https://localhost:5173`。使用服务端配置的管理员账号，页面不提供演示账号登录。

当前交付、测试证据、实际接入范围及剩余微信/WSS验证事项见[Day 5联调报告](docs/backend/10-day5-integration.md)，接口以[正式API契约](docs/backend/04-api-contract.md)为准。[早期V1前端报告](docs/v1-frontend-verification.md)与[旧接口建议清单](docs/v1-backend-interface-checklist.md)作为历史记录保留。测试夹具与隔离的微信换码测试适配器不属于正式运行模式。

2026-11-11 已移除二手功能及关联数据。当前业务范围为校园社交与社区治理；删除范围、迁移及验证见[二手模块移除数据库报告](docs/backend/19-marketplace-removal-database.md)。

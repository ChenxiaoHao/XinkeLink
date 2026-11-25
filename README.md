> **2026-11-22 内测与福袋下线**：小程序和管理后台已移除入口，服务端已移除内测准入、福袋接口与开奖任务。V19 停用历史中奖通知，保留内测码、活动、领奖和操作审计记录。历史实现见[福袋交接](docs/backend/20-lucky-bag-acceptance.md)。


> **2026-11-17 Kafka浏览统计**：公开详情→可靠接收日志→Kafka→幂等精确PV/UV→Redis→搜索页真实近7日Top5已实现并在命名隔离库验收。小程序355、后端普通329、数据库集成231；V13、35业务表。18081为同一验收JAR，统计四开关、通知MQ及CDC均关闭，热榜诚实空态；不是开发库上线。统计口径、故障恢复、性能成本、文件归属和无需安装即可阅读的学习指南见[当前交接](docs/project-handover.md)顶部；下方旧状态为历史。

> **2026-11-16 RocketMQ互动通知**：既有帖子赞、收藏、评论赞、评论/回复支持事务Outbox→可靠发布→Inbox幂等落库，默认同步兼容；V12当前29业务表。小程序335、后端普通308、真实数据库214、真实MQ/双JVM/故障/性能和当前JAR的Canal专项均通过。18081已运行验收JAR，MQ/发布器/消费者/CDC均关闭，MQ仅隔离库验收。文件归属、成本、运行状态及复跑见[当前交接](docs/project-handover.md)顶部；下方25表及早期环境说明为历史记录。

> **2026-11-14首批Redis缓存更新**：仅缓存帖子公共likeCount/commentCount，个人状态及权限仍实时查验。本轮小程序315、后端单测205、真实数据库集成173条通过（其中缓存专项14条）。隔离MySQL13306、Redis16379及18081服务已恢复；已备份后真实应用既有V10/V11。缓存配置、真实测试与SQL/耗时对比见[最新交接记录](docs/project-handover.md)开头。上一批B01—B11未提交修改完整保留，本轮未提交推送。后续AI方案为Python＋LangGraph＋ChromaDB，旧Spring AI顺序作废。

> **2026-11-14业务修复更新**：基线`7fc1af4`的B01—B11已修复并补回归；正常启动直接进入首页，取消内测码准入，沿用服务端open阶段（已有数据库需应用新增V10/V11）。本次小程序315、服务端175、后台89条通过；真实DB/微信联调未完成。最新证据及下一步见[接手记录](docs/project-handover.md)开头、[缺陷状态](BUGS.md)和[当前流程](docs/business-flows.md)。下方旧审查数量/环境说明仅供历史参考。

# XinkeLink

中文名称：信科 Link

2026-10-26 冻结前审计已完成本轮可执行检查，结论为 **C：检查不完整，证据不足**，不代表正式 V1.0.0 全量验收通过。当前状态、验证边界和已修复问题见[最终审计报告](docs/11-final-audit-report.md)，暂停与恢复操作见[项目冻结与恢复指南](docs/12-project-freeze-and-resume.md)。旧阶段报告作为历史记录保留。

面向河南信息科技学院学生的纯校园社交社区微信小程序，配套独立 PC 管理后台。

技术栈已确定：原生微信小程序 + TypeScript + WXML/WXSS；PC 后台为 Vue 3 + TypeScript + Vite + Element Plus；服务端为 Java + Spring Boot + Maven，数据库 MySQL。保留单仓库，分别位于 `apps/miniprogram`、`apps/admin`、`apps/server`。

当前两端正式页面默认使用Java API：Java 21、Spring Boot 4.1.1、MySQL 8.4、Redis 7。论坛、评论、点赞、私信、公告、举报、意见反馈及管理操作和配置持久化到服务端；搜索历史和自填学号仅保存在小程序本机。自填资料不代表学校或平台认证。

先依照[后端环境报告](docs/backend/06-day1-foundation.md)与[认证报告](docs/backend/07-day2-authentication.md)配置环境变量并启动Java服务。当前前端开发地址为`http://127.0.0.1:18081`。密钥不写入仓库，`.env.example`不会自动加载。

在`apps/miniprogram`执行`npm run check`，微信开发者工具导入此目录。访问本地HTTP服务时，仅在开发工具本地配置中关闭域名校验；真实微信登录仍需服务端AppID/AppSecret及正式域名，不会接受客户端传来的openid或演示登录标志。

在`apps/admin`执行`npm run check`及`npm run dev`。Vite将`/api`代理到18081；管理员刷新Cookie要求HTTPS，开发时通过`XINKELINK_DEV_CERT`、`XINKELINK_DEV_KEY`指定本地证书和私钥，访问`https://localhost:5173`。使用服务端配置的管理员账号，页面不提供演示账号登录。

当前交付、测试证据、实际接入范围及剩余微信/WSS验证事项见[Day 5联调报告](docs/backend/10-day5-integration.md)，接口以[正式API契约](docs/backend/04-api-contract.md)为准。[早期V1前端报告](docs/v1-frontend-verification.md)与[旧接口建议清单](docs/v1-backend-interface-checklist.md)作为历史记录保留。测试夹具与隔离的微信换码测试适配器不属于正式运行模式。

2026-11-11 已移除二手功能及关联数据。当前业务范围为校园社交与社区治理；删除范围、迁移及验证见[二手模块移除数据库报告](docs/backend/19-marketplace-removal-database.md)。

## 交接入口

接手本项目请先读这四份（均为 2026-11-13/14 基于源码全量审查生成，含文件+行号证据）：

1. [项目全貌说明](docs/project-handover.md) —— 架构、技术栈、后端接口全集、数据库 25 张表、Redis 用途、配置与启动、测试现状、**已知问题与待办（按严重度排序）**。
2. [业务流程逐条走查](docs/business-flows.md) —— 21 条业务流程的双模式逐环节走查、访客隔离现状、身份判定点清单、**24 条缺陷清单**、存储 key 清单、交接检查清单。
3. [演示模式只读审查报告](BUGS.md) —— wxml 绑定与 fixture 实现的死链扫描结果（6 条，1/2/3 已修复）。
4. [身份判定审查报告](IDENTITY-RULES.md) —— 访客/本人/他人三态规则表、判定点清单、storage 隔离结论。

**当前状态**：11 个本地提交（最新 `bd4bf88`），工作区干净，仓库**无 remote**。端口四方已统一到 `18081`（`backend/config.ts` / `application.yml` / `vite.config.ts` / `start-backend-dev.ps1`）。前端 `npm run check` 260 条全绿；后端单测 ~130 条（`mvn test`，不需 Docker），集成测试 ~160 条（`mvn -Pdb-integration verify`，需 Docker）。

**⚠️ 后端启动至今未在本机验证成功**：缺 pwsh 7.4、隔离 MySQL 13306 未运行、Redis 16379 未运行、`XINKELINK_JWT_SECRET_BASE64` 未设置。接手后第一件事是补齐这些环境并跑通 `/api/v1/health`。

## 2026-11-18 Link AI 第一版

学校规章知识库问答、自然语言公开查帖及账号会话历史已实现；后台支持资料提取、校对与版本发布。真实模型与隔离联调、最终完整回归及配置见 [AI 第一版说明](docs/backend/23-link-ai-v1.md) 和 [最新交接](docs/project-handover.md)。代码留工作区，未部署生产；学校真实资料仍须管理员确认发布。

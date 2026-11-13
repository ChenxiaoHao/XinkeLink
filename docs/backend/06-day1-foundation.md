# 10月24日：后端基础实施记录

依据：01–05设计文档及用户“完成当日目标，代码简洁，注释使用中文”的执行指令。

## 范围与决策

- 保留当前`codex/phase-1-foundations`工作区和前序未提交文件，不创建会丢失未提交基础的独立检出，不执行Git提交或清理。
- 采用既有Boot 4.1.1路线，Java 21，兼容的MP Boot4与springdoc；精确解析结果在验收后记录。无需为沿用既有版本修改前端。
- 学号不上传；不增加业务接口；今天只做公共基础、安全默认、数据库迁移与可复现验证。
- 测试先行：先记录现有健康测试，再以失败测试约束统一响应、参数校验、安全错误和数据库结构。
- 构建隔离：现有8080进程锁定旧target根目录的JAR；新开发构建使用`-Dxinkelink.build.directory=target/backend-dev`，不停止旧进程。启动从独立运行副本执行，避免开发进程再次锁住构建产物。
- 只在新建的本项目隔离MySQL开发库中迁移；不覆盖现有数据库，不使用真实学生数据。安装/下载仅为完成今日目标所需的项目依赖或便携开发工具，不改系统默认JDK和全局服务。

## 任务与验证

| 任务 | 主要文件 | 验证 |
| --- | --- | --- |
| 1. 环境与依赖 | `apps/server/pom.xml`，项目启动/验证脚本 | JDK21、Maven依赖树、原健康测试 |
| 2. 公共HTTP基础 | `common/api`、`common/web`、`config`及对应测试 | 成功封装、UTC、字符串ID、分页校验、400/401/403/500不泄密 |
| 3. 数据库结构 | `src/main/resources/db/migration/V1__identity.sql`至`V5__relations.sql` | 24张表、索引/FK/约束、实际MySQL8.4 migrate/validate/重复迁移 |
| 4. profile与文档 | `application*.yml`、`.env.example`、OpenAPI配置 | 默认健康可启动；数据库profile无凭据时明确失败；OpenAPI仅开发启用 |
| 5. 集成与复核 | 数据库集成测试、运行指南、本文件 | 真实HTTP、重启无重复、权限/唯一约束、无前端改动 |

共享接口检查：迁移只提供表结构，HTTP基础不依赖业务Mapper；数据库profile负责DataSource/Flyway/事务设置。测试专用Controller仅位于test源码，不向产品增加探测业务接口。基础profile无需数据库的健康行为继续保留，数据库profile单独验证持久层。

## 已完成与实际版本

2026-10-24已完成今日基础目标；没有进入10月25日认证业务。只实现HTTP公共边界及数据库基础，不能将这些结果解释为完整后端上线验收。

| 组件 | 实测版本 |
| --- | --- |
| JDK / Maven | Microsoft OpenJDK21.0.12.1 / Maven3.9.4 |
| Spring Boot / Framework / Security | 4.1.1 / 7.0.9 / 7.1.1 |
| MyBatis-Plus / springdoc | Boot4 starter3.5.17 / 3.1.1 |
| Jackson / Validation | Jackson3.1.5 / Hibernate Validator9.1.3.Final |
| MySQL / Connector/J / Flyway | 新隔离实例8.4.13 / 9.7.0 / core及mysql模块12.4.0 |
| JUnit / Maven测试插件 | Jupiter6.0.3 / Surefire及Failsafe3.5.6 |

精确依赖树见`.local/backend-day1/dependency-tree.txt`。Boot版本未跨代变更；额外依赖采用Boot4对应坐标。JDK下载已核对厂商SHA256，MySQL下载包核对官网MD5；工具仅位于项目`.local`，未改变系统JDK/MySQL服务。

实现包括统一成功/错误响应、服务端请求ID、UTC毫秒时间、分页DTO与校验、默认拒绝的Security边界、开发OpenAPI开关、数据库/Redis可选profile。测试Controller仅在test源码中，不在产品包开放演示业务。业务DTO的ID采用字符串，统计/金额仍为数值，不做破坏所有Long类型的全局字符串转换。

数据库共24张业务表、1张Flyway历史表，5个迁移。外键在V5后补；明细CHECK、唯一约束、状态枚举与设计对齐。迁移账号和运行账号分离，运行账号无DDL权限；审计表仅SELECT/INSERT，Flyway历史表仅SELECT。不导入任何前端Mock用户、私信或举报。

## 验收证据

| 实际执行 | 结果与边界 |
| --- | --- |
| 变更前`mvn test` | 原2项健康测试通过，当时环境为原JDK25 |
| 统一响应测试首次执行 | 公共类型和依赖尚不存在，编译失败，随后按测试补齐实现 |
| 默认拒绝回归RED→GREEN | 测试外部路径原200、预期404；修正全路径Security匹配后通过，401/403也保持正确 |
| JDK21 Maven完整verify | 19项HTTP/基础测试＋9项真实数据库集成测试，0失败、0错误、0跳过；生产JAR打包通过 |
| `schema-contract.ps1` | 无迁移时RED，新增5迁移/24表后GREEN；静态检查不能代替下述实际数据库结果 |
| MySQL8.4首次启动 | Flyway成功应用V1–V5，24表及39外键创建成功，无旧Flyway版本认证警告 |
| 数据库集成测试 | UTC、READ_COMMITTED、两个数据库账号、重复身份/赞藏、价格范围、待处理举报唯一约束、FK、审计只追加权限通过 |
| 测试完成后的独立SQL复核 | user/admin/post/product/report表均0行，夹具回滚，没有残留真实或演示业务数据 |
| 真实Java和MySQL进程关闭/重启 | 仅关闭本轮创建实例；`-StartExisting`保留数据启动成功；新Java启动validate5迁移，报告No migration necessary |
| 实际HTTP | health及Actuator health为200/UP；未登录me及admin/users为401 JSON；actuator/env为404；开发OpenAPI200且仅列真实health业务路径 |
| 缺少数据库环境变量启动 | 使用db profile时进程退出1并明确缺少配置，不悄悄回退Mock或H2 |
| 脚本/源码检查 | PowerShell语法检查、源码diff空白检查通过；新临时凭据只在被忽略且限制ACL的private目录 |

最终保留检查：本轮范围外335个已有文件的SHA256清单与开始时完全一致；新生成的3个开发密码未出现在任何非忽略文本文件中，private目录ACL已禁用继承。没有改动小程序、PC后台或原有启动脚本，没有执行Git提交/推送/重置/清理。

完整日志：`.local/backend-day1/verify.stdout.log`、`verify.stderr.log`、`integration-verify.log`、`first-start.log`及`backend.stdout.log`。单线程重复INSERT证明唯一约束，不能冒充并发压力测试；真实重启由独立进程操作和日志证明，不能仅凭再次调用migrate断言。

发现并处理了现有JAR锁、Windows mysqld监控子进程、PowerShell日期JSON往返和Security匹配范围问题；独立构建/运行副本、`--no-monitor`、PID＋进程时间核验避免影响旧服务。已应用迁移文件没有反复改写或执行repair/clean。

## 运行方法

在项目根目录使用PowerShell7.4及以上。当前实例已启动，不能再次初始化同一数据目录。

```powershell
# 重启电脑或独立MySQL停止后，无损恢复已有开发库。
pwsh -NoProfile -File scripts/initialize-backend-dev.ps1 -StartExisting

# 18080未运行时启动；-Build会使用JDK21构建隔离产物。
pwsh -NoProfile -File scripts/start-backend-dev.ps1 -Build

# 完整单元/HTTP/真实数据库回归，凭据仅注入子进程环境。
pwsh -NoProfile -File scripts/verify-backend.ps1 -Database
```

新电脑首次配置顺序为：准备JDK21/MySQL8.4便携目录→`initialize-backend-dev.ps1`（不加开关）→`start-backend-dev.ps1 -Build`（首次迁移）→`initialize-backend-dev.ps1 -GrantRuntimePermissions`→完整verify。不能对非空datadir重初始化。可用`-JavaHome`指定另一处JDK21；工具下载地址见下方官方资料，不要求安装为系统服务。

- 开发地址：`http://127.0.0.1:18080/api/v1/health`；数据库：`127.0.0.1:13306/xinkelink_dev`。
- 开发接口文档：`http://127.0.0.1:18080/swagger-ui/index.html`，默认无dev profile时关闭。
- 凭据：`.local/backend-day1/private/runtime.json`，不应粘贴到聊天、提交仓库或复制到前端。`.env.example`只含变量名与示例地址，不自动加载。
- 当前角色边界通过测试身份验证；真正微信/管理员登录、JWT签发/校验将在后续任务实现，今天没有演示登录旁路。

## 修改文件与学习重点

- `apps/server/pom.xml`：兼容依赖、隔离构建属性、数据库集成测试profile。
- `apps/server/src/main/java/cn/xinkelink/common/api/`：响应、错误码/异常、两类分页DTO。
- `apps/server/src/main/java/cn/xinkelink/common/web/`：请求ID和安全异常映射。
- `apps/server/src/main/java/cn/xinkelink/config/`：Security、Jackson UTC和OpenAPI。
- `apps/server/src/main/resources/application*.yml`及`.env.example`：默认、dev、db、redis配置边界。
- `apps/server/src/main/resources/db/migration/V1__identity.sql`至`V5__relations.sql`：24表/索引/约束；没有修改原有健康Controller和ImageStorage抽象。
- `apps/server/src/test/java/cn/xinkelink/common/`、`database/`及`src/test/resources/schema-contract.ps1`：新增HTTP/数据库/静态结构验证，保留原健康测试。
- `scripts/initialize-backend-dev.ps1`、`start-backend-dev.ps1`、`verify-backend.ps1`：独立开发实例与可复现检查；原启动脚本不变。
- 本文件及02/03/05的实施状态补充；小程序、PC后台、现有README未修改。

今日复习重点：record表示DTO，`@Valid`只守输入边界；Service后续负责业务和事务，Mapper只做参数化SQL；Security过滤链异常与Controller异常分别处理；BOM控制兼容依赖，Flyway控制不可变结构版本；唯一键/FK/CHECK与Service状态机职责不同；运行账号不应拥有迁移和审计改删权限。

## 明确未完成/未验证

- Redis已加入兼容客户端和可选profile，但没有可用Redis7.2实例。本机仅有停止的3.2旧服务、Docker daemon未运行；没有启动旧服务或安装替代分支。真实Redis连接、限流将在认证阶段准备和验证，不宣称Redis实测通过。
- 不含真实登录、业务Controller/Service/Mapper、图片上传适配实现、WebSocket消息、跨端联调；这些属于之后日期。今天不为目录齐全制造空业务类。
- 图片本地适配设计保持厂商无关ImageStorage：先上传临时imageId，事务绑定归属/用途/引用，访问时复核权限；今天不提前实现或绑定生产厂商。
- 管理员Cookie认证尚未开始，因此保留CSRF保护；不得把未来refresh/logout全局关闭CSRF。
- 没有生产部署、真实微信、真实学生或真机验收。默认健康只表示进程；db profile的Actuator检查当前数据库，Redis未启用不计入健康结论。

官方冻结依据：[Boot4.1.1 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom)、[MP安装](https://baomidou.com/en/getting-started/install/)、[springdoc](https://springdoc.org/)、[微软JDK](https://learn.microsoft.com/en-us/java/openjdk/download)、[MySQL8.4 Windows下载](https://dev.mysql.com/downloads/mysql/8.4.html?os=3)。

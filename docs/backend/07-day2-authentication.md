# Day 2：登录认证与权限系统

日期：2026-10-24。依据01～06设计与本轮明确范围执行，未开发Day 3业务，不修改两端前端。

## 实施范围与约定

- 复用Day 1安全响应、UTC时间、MySQL8.4开发库及既有用户/管理员/会话表，不改写已应用迁移。
- 本轮实现U01～U04和A01～A04：微信换码、用户/管理员Token、刷新/退出、当前身份；不扩大到图片上传、资料编辑或论坛交易。
- JWT只含身份/会话和必要签名声明，短期15分钟；MySQL会话在每次鉴权时复核，不能用签名正确掩盖会话吊销或账号封禁。
- 刷新票据随机生成，仅存SHA256摘要；用户30天、管理员8小时绝对期限，轮换不能无限延长会话。管理员使用Secure/HttpOnly/SameSite=Strict Cookie，刷新/退出另校验受信Origin及CSRF摘要。
- 微信适配器固定官方code2Session目标；测试替身只位于测试源码，不给产品增加Mock登录开关。只接收code，不信任客户端openid。
- 登录限流使用Redis接口，环境不可用时明确返回503，不静默放行。正向测试使用显式测试替身，不能据此声称Redis或微信真实联调通过。
- 管理员仅固定ADMIN，普通用户仅USER；保持默认拒绝，公开路径采用白名单。实际管理员账号通过受控运维步骤和BCrypt哈希准备，不内置默认真实密码。

## 顺序与验证

| 步骤 | 内容 | 检查 |
| --- | --- | --- |
| 1 | 重读设计、核对代码/环境与保留基线 | 现有功能不重复开发，Redis可用性实测 |
| 2 | 微信可替换适配器与认证持久层 | 官方协议模拟HTTP、真实MySQL映射/约束 |
| 3 | JWT、会话、刷新/吊销、角色和管理员认证 | 先失败再实现，验证过期/伪造/越权/重放 |
| 4 | Controller、安全过滤器、Cookie/CSRF/Origin | HTTP契约、未知字段、权限及不泄密 |
| 5 | 全量回归、实际接口与总结 | 独立JDK21构建、Day1保留、真实环境边界 |

## 实现结果

本轮代码和本地测试验收已完成；真实微信、Redis7和HTTPS浏览器联调仍未通过或未执行，不能据此宣称生产认证已就绪。采用Java21、Boot4.1.1、BOM管理的Security7.1.1/Nimbus和MySQL8.4.13，无新增表或迁移。

| 契约 | 已实现 |
| --- | --- |
| U01 POST `/api/v1/auth/wechat` | 仅接收code，服务端请求官方code2Session；唯一微信身份创建/复用用户，不覆盖资料、封禁或人工限制 |
| U02 POST `/api/v1/auth/refresh` | 摘要查找、事务行锁、一次性轮换、不延长会话；过期/重放返回REFRESH_INVALID |
| U03 POST `/api/v1/auth/logout` | 吊销当前sid，原access及refresh立即不可用，不删除业务内容 |
| U04 GET `/api/v1/me` | 本人资料及有效限制，无openid/session_key/学号；未接媒体时avatar为null |
| A01 POST `/api/v1/admin/auth/login` | BCrypt成本12，未知/错误/停用账号统一失败；IP及归一化账号限流；返回access/CSRF，refresh只写Cookie |
| A02 POST `/api/v1/admin/auth/refresh` | Origin、Cookie、绑定CSRF校验并轮换；失败不写Cookie，避免并发失败覆盖成功响应 |
| A03 POST `/api/v1/admin/auth/logout` | Cookie/CSRF校验后吊销；受信后台携带无效access时仍401并过期Cookie，不放行无效access |
| A04 GET `/api/v1/admin/me` | 仅当前有效ADMIN；用户JWT不能访问后台，管理员JWT也不能充当学生访问U04 |

JWT为HS256，环境密钥至少32字节随机值并Base64编码，无演示默认值。验证签名、issuer、audience、actorType、主体/session ID、时间及必要声明；过期明确TOKEN_EXPIRED。每次鉴权还读取MySQL会话和当前账号，不信任JWT中的陈旧权限。未启用auth profile时保持Day1默认拒绝。

微信网络换码在数据库事务外，固定目标、不重试一次性code、不跟随重定向，连接/读取超时3/5秒。不保存session_key，不回传上游异常正文。敏感DTO/配置的toString隐藏凭据，认证Mapper不启用SQL参数调试日志。协议核对见[参考记录](references/2026-10-24-wechat-code2session.md)。

Redis Lua原子计数，仅首次设置60秒TTL；微信/管理员/刷新上限10/5/30次，键只包含操作名和来源摘要。故障或无效结果503、超限429；没有产品级Mock或故障放行开关。

## 实际验收

项目根目录执行：

```powershell
pwsh -NoProfile -File scripts/verify-backend.ps1 -Database
```

| 检查 | 实际结果 |
| --- | --- |
| 编译、打包、完整verify | JDK21，BUILD SUCCESS；115项单元/基础HTTP＋32项集成＝147项，0失败、0错误、0跳过 |
| 微信适配器 | 36项；MockRestServiceServer核对固定目标、编码、错误及隐私，没有真实微信登录调用 |
| JWT | 37项；伪造、错误身份/受众/声明、密钥、过期及精确秒边界 |
| Redis限流单元测试 | 21项；脚本调用、阈值、摘要键、故障封闭，StringRedisTemplate被替换，不证明真实Lua/TTL执行通过 |
| 签发边界 | 2项；不足一秒及处理跨过签发期限返回REFRESH_INVALID，不提交失败轮换 |
| 认证HTTP＋真实MySQL | 19项；8接口、未知openid/缺code、401/403/502、刷新重放、Cookie/Origin/CSRF、吊销、封禁/软删、管理员停用、DB会话到期、旧CSRF不消耗新票据 |
| 并发真实事务 | 2项；4线程首次登录仅1用户/4会话；2线程同refresh仅1成功/1拒绝，代次仅增1、绝对期限不变 |
| 真实TCP HTTP流程 | 2项；最终Tomcat随机端口62800，Java HTTP客户端执行登录→本人资料→后台越权403→刷新→旧票据401→退出→旧access401；另验证限流边界503前不创建用户 |
| Day1回归 | 19项基础HTTP/健康/OpenAPI及9项真实数据库检查包含在147项内；Flyway验证5个迁移，无迁移执行 |
| 独立SQL复核 | xl_user、xl_admin、xl_auth_session均0行；夹具回滚或仅按随机身份清理，没有导入前端Mock数据 |
| Redis环境实测 | 127.0.0.1:16379及6379 TCP连接均失败；旧Windows服务停止、旧程序3.0.504不满足Redis7，Docker daemon不可用，WSL无已装Redis7；未安装/启动替代服务 |
| 保留检查 | 范围外335文件清单SHA256与开始一致；PowerShell语法及Git diff空白检查通过 |

真实端口测试的微信及限流使用**测试源码中的显式替身**；HTTP服务器、JWT、Security、事务及MySQL使用真实实现。503测试也是故障边界替身，不能当作真实Redis故障集成测试。随机端口62800随测试进程退出关闭；既有18080 Day1服务未替换，不能将旧进程当作新认证版本。

先失败再实现的证据包括模块测试、认证缺实现编译失败、刷新不足一秒、签发跨界及刷新Cookie覆盖回归。发现此机用户Temp的JDK21 UDS连接异常后，用独立探针定位；验证脚本只对子进程设置`.local/jvm-sockets`，不修改系统JDK或全局环境。

完整记录：`.local/backend-day2/final-verify.stdout.log`、`final-verify.stderr.log`、`final-verify-command.log`、`redis-probe.json`；JUnit报告在`apps/server/target/backend-dev/surefire-reports/`和`failsafe-reports/`。各模块RED过程和临时探针保留在忽略目录，不是产品资源。

## 运行与配置

沿用Day1隔离MySQL13306和受控private目录中的账号，不连接原3306实例、不重新初始化数据库。在启动环境设置`.env.example`列出的JWT、微信、Redis及管理员Origin；示例文件不自动加载，不可填真实值后提交。JWT密钥由密码管理工具/部署环境生成至少32字节随机值并Base64编码，禁止使用可猜测文字。

另行准备Redis7、真实微信凭据及受控管理员账号后，可在空闲18081启动；不会取代原18080：

```powershell
pwsh -NoProfile -File scripts/start-backend-dev.ps1 -Build -Port 18081 -Profiles db,dev,auth
```

`auth`必需JWT密钥并依赖Redis限流；额外启用`redis` profile才把Redis计入Actuator。health=UP只代表当前已纳入的进程/数据库检查，不表示微信、Redis或管理员登录可用。不要为本机缺Redis增加旁路。

无管理员注册接口或默认密码，本轮没有创建真实管理员。受控运维先归一化账号（strip＋小写），用同一`BCryptPasswordEncoder(12)`离线生成摘要，通过参数化SQL写入现有`xl_admin`的login_name/password_hash/display_name/password_changed_at。真实密码只能通过安全输入交给哈希工具，不放SQL、命令行、代码或日志；随机回滚测试管理员不能用于实际登录。

管理员Origin显式列入环境配置；Cookie固定Secure/HttpOnly/SameSite=Strict，刷新/退出携带`X-CSRF-Token`。尚无HTTPS浏览器验收，不能为本地调试降低Cookie安全属性。登录/刷新请求不附带失效Bearer；其他受保护请求使用Authorization头，禁止URL传Token。后续客户端刷新采用单次共享请求，成功替换access/CSRF，避免并发重试旧票据。

## 新增与修改文件

以下均相对于仓库根目录，区分Day2与已有Day1及前端成果。

新增：

- `apps/server/src/main/java/cn/xinkelink/auth/`：`AuthController.java`、`AuthService.java`、`AuthModels.java`、`AuthPrincipal.java`、`AuthSecrets.java`、`AuthJwtAuthenticator.java`、`AdminBrowserSecurity.java`、`AdminSecurityProperties.java`。
- 同包`persistence/`：`AuthMapper.java`、`UserAccount.java`、`AdminAccount.java`、`AuthSession.java`；`token/`：`JwtTokenService.java`、`TokenProperties.java`。
- 同包`wechat/`：`WechatCodeClient.java`、`OfficialWechatCodeClient.java`、`WechatIdentity.java`、`WechatProperties.java`；`ratelimit/`：`AuthRateLimiter.java`、`RedisAuthRateLimiter.java`。
- `apps/server/src/main/java/cn/xinkelink/config/AuthConfiguration.java`；`apps/server/src/main/resources/application-auth.yml`。
- `apps/server/src/test/java/cn/xinkelink/auth/`：`AuthServiceTest.java`、`AuthHttpIT.java`、`AuthConcurrencyIT.java`、`AuthLiveHttpIT.java`，以及子包`JwtTokenServiceTest.java`、`OfficialWechatCodeClientTest.java`、`RedisAuthRateLimiterTest.java`。
- 本总结及`docs/backend/references/2026-10-24-wechat-code2session.md`。

修改：

- `apps/server/pom.xml`：Boot4资源服务器starter；`src/main/java/cn/xinkelink/common/api/ErrorCode.java`：认证和依赖不可用错误码。
- `src/main/java/cn/xinkelink/config/SecurityConfiguration.java`：JWT过滤链、角色及安全失败响应，保留非Bearer的CSRF保护。
- `src/main/resources/application-db.yml`：MyBatis语句级本地缓存，避免同事务状态读取陈旧；`apps/server/.env.example`：环境变量名及说明。
- `scripts/verify-backend.ps1`：测试子进程UDS目录；`docs/backend/02`、`04`、`05`：追加实际状态，不把原完整设计写成全部完成。

没有修改小程序、PC后台、Day1迁移，没有安装新环境服务，没有执行Git提交、推送、重置或清理。

## 未完成及后续边界

- **真实微信未验证**：缺真实AppID/AppSecret及新wx.login code；网络/TLS、平台策略、真机和跨端联调待真实环境，不能提交openid替代身份。
- **真实Redis未通过**：无Redis7监听；PING、Lua计数/TTL/过期和实际并发限流待环境可用后验收。在此之前正式登录/刷新应503，不能宣称可直接真实联调。
- **管理员/HTTPS未准备**：需受控账号摘要及后台Origin；浏览器Cookie、代理和前端登录尚未验收。当前直接使用remoteAddr，反向代理后可能共用限流桶；部署前需配可信代理并验证来源隔离，不能信任任意X-Forwarded-For。
- **后续业务权限**：已实现当前账号封禁/软删/停用鉴权拒绝及人工限制投影；发布、评论、私信服务尚未开发，必须在其写入口另校验通道限制，不能把三个布尔值等同于全部权限已落地。
- 临时封禁到期在认证时忽略过期层并保留人工限制；治理阶段仍需按03原子规范化持久字段及实现封禁命令主动吊销。当前每次查库可即时拒绝有效封禁，不等待JWT过期。
- 图片上传、资料修改、论坛、交易、私信、审计及后台业务API不在本轮范围，不自动进入Day3。

## Java复习重点

Controller处理输入/HTTP/Cookie，Service管理规则和事务，Mapper执行参数化SQL；构造器注入使微信、时钟和限流可替换测试。理解JWT签名与有效会话的区别、BCrypt与SHA256各自用途、FOR UPDATE及唯一键的并发职责，以及过滤链异常不能只靠ControllerAdvice处理。关键逻辑保留中文注释。

# 信科 Link 项目冻结与恢复指南

> 2026-10-31存储更新：开发后端已使用OSS，新图在云端、旧图保留本地。恢复时需同时保留桶对象及凭据配置，不能仅恢复旧本地图片目录。当前配置与验证详见[OSS接入记录](backend/16-oss-storage-integration.md)，下文原冻结现场继续保留。

日期：2026-10-26。冻结对象为**当前未提交工作区的V1.0.0候选开发成果**，不是正式release/tag。最终审计结论为**C：检查不完整，证据不足**，详细原因见[最终审计](11-final-audit-report.md)。成果可以先保全暂停，后续先补验收再决定是否正式通过。暂停期间不继续加功能，也不自动转做其他项目。

## 1. 当前工程与运行依赖

仓库：`D:\CODE\XinKeLink`。

```text
apps/miniprogram/       原生微信小程序、公共组件、真实请求/会话层及测试
apps/admin/             Vue3/Element Plus/Pinia 管理后台、Vitest/Playwright
apps/server/            Java21/Spring Boot4.1.1/Maven/MyBatis-Plus服务
  src/main/resources/db/migration/   已应用V1～V5，不改写历史迁移
docs/backend/01～15     设计、每日实施及后续修复历史
docs/11-final-audit-report.md         当前审计结论
docs/12-project-freeze-and-resume.md  当前恢复入口
scripts/               数据库恢复启动、Java启动/验证、明确本地测试数据工具
.local/                被Git忽略的工具、数据库、图片、私有配置、证据和备份
```

当前环境：Node24.15.0/npm11.12.1；项目自带Microsoft JDK21.0.12.1、Maven3.9.4；MySQL8.4.13，Redis7.4.11运行于Ubuntu WSL。系统默认JDK可能是25，不能据此用它替代项目Java21验证。前端依赖版本以package-lock.json为准。

| 用途 | 当前地址 |
|---|---|
| Java正常开发服务 | `http://127.0.0.1:18081`，profiles=db,auth,redis |
| 健康 | `/api/v1/health`、`/actuator/health` |
| 管理后台 | `https://localhost:5173`，本地开发证书；/api代理18081 |
| MySQL | `127.0.0.1:13306/xinkelink_dev`，24业务表、39外键，Flyway V1～V5 |
| Redis | `127.0.0.1:16379`，WSL本机开发实例，仅限流用途，无生产持久化承诺 |
| 微信CLI/自动化 | 本机14237/9420是工具端口，不是业务服务接口；重开工具后可能变化 |

旧`docs/runbook.md`和06～10中的早期端口、Redis不可用、微信未联调等属于历史阶段，当前操作以本指南及12～15后端报告为准。不要启动旧8080健康演示或旧测试换码服务替代正常后端。

## 2. Git与未提交成果

- 分支：`codex/phase-1-foundations`。
- HEAD：`04dd5f0b2c8b31251e5c0d512f44b1e8d68f34cf`，本轮未改变。
- 小程序、完整管理后台、后端模块、迁移、测试、Logo/素材及大量文档仍在未提交修改/未跟踪文件中。**仅保留Git HEAD不能恢复完整项目。**
- 本轮没有commit、push、reset、clean、强制覆盖或release tag；准确终态逐文件清单见`.local/final-audit/git-final.txt`、`source-final.json`、`source-changes.json`。
- 暂停前保留完整目录与下列备份，勿仅复制apps下的源码而遗漏图片、私有环境与数据库。计划离机备份时对私有材料加密；本轮未完成异地备份。

只读检查：

```powershell
Set-Location D:\CODE\XinKeLink
git branch --show-current
git rev-parse HEAD
git status --short
```

不要因为未跟踪文件很多而执行clean，也不要用checkout/reset恢复旧提交。恢复开发时是否整理提交应另行由用户决定。

## 3. 已有备份及恢复验证

备份目录：`.local/final-audit/private/`，Windows ACL仅当前用户和SYSTEM可访问，且被Git忽略。

| 文件 | 内容 |
|---|---|
| `source-before.zip`、`tracked-before.patch` | 本轮开始前完整可追踪/未跟踪源码与tracked差异 |
| `source-final.zip` | 本轮结束源码、测试、素材、文档快照，不含依赖/构建产物/运行私密配置 |
| `history.bundle` | Git完整历史与refs，已通过bundle verify；不包含未提交工作树 |
| `database-before.sql`、`database-final.sql` | 一致性逻辑备份，包含全部开发数据及迁移历史，不是空库脚本 |
| `images-before.zip`、`images-final.zip` | 全部本地图片文件，包括历史及仍被引用文件 |
| `runtime-config/` | 恢复本机服务所需的现有私有文件副本；不在文档显示值 |

SHA256与文件大小见`.local/final-audit/backup-manifest.json`、`freeze-manifest.json`；开始566个文件的校验在`source-before.json`，结束清单在`source-final.json`。暂停后先校验再使用，不能仅凭同名文件假定完整。

已经把`database-before.sql`恢复到独立新库，核对24张业务表、V1～V5均成功及备份数据存在，**没有向xinkelink_dev导入**。恢复检查库和失败的隔离检查现场保留，不用于正常运行。不要把它们误选为开发库；日后清理须再次核对库名和归属。

### 再做备份

先停止新增业务写入，使用当前项目MySQL8.4的mysqldump与受限客户端配置。选择一个不存在的新文件，不覆盖冻结备份：

```powershell
$mysqlHome = 'D:\CODE\XinKeLink\.local\backend-day1\tools\mysql84\mysql-8.4.13-winx64'
$clientConfig = 'D:\CODE\XinKeLink\.local\backend-day1\private\client.cnf'
& "$mysqlHome\bin\mysqldump.exe" "--defaults-extra-file=$clientConfig" `
  --single-transaction --no-tablespaces --routines --events --triggers `
  '--result-file=D:/CODE/XinKeLink/.local/your-private-backup/new-backup.sql' xinkelink_dev
# 确认退出码0，再计算文件哈希；同一时段备份图片目录。
```

示例输出目录须预先创建并限制访问权限。不要把密码放在参数里；不要把SQL、私钥或runtime.json提交Git、贴到聊天或公开上传。

### 数据恢复

1. 优先在**另一个明确命名、全新的空测试库**恢复验证，不在现有xinkelink_dev试导入。
2. 使用相同MySQL8.4、utf8mb4和项目对应版本；先核验备份SHA256。
3. 用mysql客户端直接读取SQL文件，或用原始字节流输入；**不要`Get-Content dump.sql | mysql`**，字符流转码可能破坏中文及二进制数据。本轮恢复检查曾因此失败，改为原始字节流后通过。
4. 例：使用受限配置连接已新建的空库，然后执行`SOURCE D:/.../database-final.sql`；必须先核验`SELECT DATABASE()`确为目标新库。SQL中含建表/替换表语句，绝不盲目对非空库执行。
5. 校验Flyway历史、表/外键、主要数据数量和图片引用，再把图片解压到单独新目录，检查文件名与资源表storage_key对应。
6. 只有确认目标库/图片都正确且用户决定切换后，才修改运行配置；保留旧库和旧图片，不强行覆盖。生产库恢复不属于本指南的已执行范围。

源码恢复也先解压到新目录核对。Git历史可从bundle克隆到另一个新目录，源码ZIP另外解压比较，逐项恢复未提交成果；不要直接对现工作树使用强制覆盖。仅还原Git历史会缺少绝大多数当前业务代码。

上面的`-StartExisting`流程用于本机仍保留datadir的实例。若是换机或datadir丢失，SQL备份不包含MySQL系统账号/授权：须先在新的隔离实例建立迁移/运行账号及最小权限，再恢复业务库和图片，重新核验路径、实例UUID及私有运行记录。不要把旧`runtime.json`当作新实例已初始化的凭证，不要在已有数据上运行首次初始化。本轮验证的是独立库的数据恢复，不是完整换机灾备演练。

## 4. 私有配置与环境变量

本机已有文件：

- `.local/backend-day1/private/runtime.json`：开发数据库运行/迁移账号及实例标识。
- 同目录`client.cnf`：仅本机备份/管理工具使用。
- `.local/backend-day5/private/jwt.key`：当前开发JWT密钥。
- 同目录`browser-account.json`：本地管理员凭据。
- 同目录`dev-cert.pem`、`dev-key.pem`：本地后台HTTPS证书/私钥。
- 微信AppID/AppSecret保存在Windows用户环境变量；本轮只检查存在性和匹配，不将值复制到文档。新机器需从安全凭据来源重新配置。

必要变量名：`XINKELINK_DB_URL`、`XINKELINK_DB_USERNAME`、`XINKELINK_DB_PASSWORD`、`XINKELINK_MIGRATION_USERNAME`、`XINKELINK_MIGRATION_PASSWORD`、`XINKELINK_JWT_SECRET_BASE64`、`XINKELINK_WECHAT_APP_ID`、`XINKELINK_WECHAT_APP_SECRET`、`XINKELINK_REDIS_HOST/PORT/PASSWORD`、`XINKELINK_IMAGE_DIRECTORY`、`XINKELINK_ADMIN_ALLOWED_ORIGINS`；后台开发另需`XINKELINK_DEV_CERT/KEY`。

`.env.example`只有说明，不会自动加载。微信开发工具AppID必须与服务端一致；wx.login的code不等于昵称头像授权。旧code、旧截图、测试身份或客户端openid不能替代真实微信身份校验。

JWT密钥丢失或主动轮换会使旧Token失效，用户需重新登录，但不能据此清库。私有配置备份不是生产凭据配置方案；生产应另行准备，不直接复用开发密钥/证书。

## 5. 启动、停止、再次恢复

### 启动顺序

1. 只读检查Git与备份，不删除任何工作区文件；确认没有误启动另一个项目同端口服务。
2. 使用PowerShell7.4+，在项目根目录恢复**已有**MySQL：

```powershell
Set-Location D:\CODE\XinKeLink
pwsh -NoProfile -File scripts/initialize-backend-dev.ps1 -StartExisting
```

已有数据库绝不再次执行首次初始化，不对非空datadir操作。脚本会核验PID、进程时间、端口及数据目录，不应绕过。

3. 检查WSL Redis。当前二进制位于`/home/chenxiaohao/.local/share/xinkelink-day5/redis-7.4.11/src/`。先PING，未运行才启动同版本本机实例：

```powershell
wsl -d Ubuntu -- /home/chenxiaohao/.local/share/xinkelink-day5/redis-7.4.11/src/redis-cli -p 16379 PING
# 未运行时，在WSL中以 --bind 127.0.0.1 --port 16379 --protected-mode yes
# 启动同目录redis-server；不要启动系统旧6379服务或安装另一主版本代替。
```

再从Windows检查16379可达。Redis不可用时登录/刷新应明确失败，不得为启动演示而关闭限流或伪造成功。

4. 设置当前进程所需的非公开配置后启动正常后端：

```powershell
$env:XINKELINK_JWT_SECRET_BASE64 = (Get-Content .local/backend-day5/private/jwt.key -Raw).Trim()
$env:XINKELINK_IMAGE_DIRECTORY = 'D:/CODE/XinKeLink/.local/backend-day5/images'
$env:XINKELINK_ADMIN_ALLOWED_ORIGINS = 'https://localhost:5173'
$env:XINKELINK_REDIS_PORT = '16379'
pwsh -NoProfile -File scripts/start-backend-dev.ps1 -Port 18081 -Profiles 'db,auth,redis' -Build
```

脚本读取既有数据库私有记录及微信Process/User/Machine环境变量，仅把配置传入子进程。请勿输出变量值。以UP和实际端口归属为准，不把旧健康演示当业务服务。

5. 后台在另一个终端启动，Secure Cookie要求HTTPS：

```powershell
$env:XINKELINK_DEV_CERT = 'D:/CODE/XinKeLink/.local/backend-day5/private/dev-cert.pem'
$env:XINKELINK_DEV_KEY = 'D:/CODE/XinKeLink/.local/backend-day5/private/dev-key.pem'
Set-Location D:\CODE\XinKeLink\apps\admin
npm run dev
```

6. 微信开发者工具导入`apps/miniprogram`；TypeScript编译，无需构建npm。当前API为127.0.0.1:18081，仅模拟器本机可用；真机必须使用可达的合规HTTPS/WSS域名。开发工具的关闭域名校验不能写成生产通过。

### 停止顺序

本轮结束不自动停止你的运行服务。需要让出资源给RentPilot时：关闭小程序预览→在Vite终端Ctrl+C→正常停止本项目Java→最后正常关闭MySQL/Redis。不要按端口无差别杀进程。

- Java PID记录在`.local/backend-day1/backend.pid`；先核对进程命令确为本仓库`.local/backend-day1/run/.../server.jar`且拥有18081，再停止该PID。
- MySQL使用项目mysqladmin与已核验的私有client.cnf执行正常shutdown，先检查实例是13306及本项目datadir；不删datadir，不强杀系统MySQL。
- Redis确认16379是本项目WSL开发实例后用redis-cli正常SHUTDOWN；该开发实例非生产持久化，限流计数清除不等于业务数据丢失。
- 不停止未知Java、数据库、其他WSL服务或其他项目的Vite。

## 6. 恢复开发后的首批任务

先重跑当前检查，再处理缺口，不能直接沿用本次通过记录：

```powershell
# 根目录：Java21、真实MySQL，统一脚本不执行清库。
pwsh -NoProfile -File scripts/verify-backend.ps1 -Database
# apps/miniprogram
npm run check
node scripts/check-wechat.cjs 'D:/develop/微信web开发者工具'
# apps/admin
npm run check
npm run test:e2e
```

真实浏览器写库用例须另行明确准备测试身份和私有管理员凭据。旧`real-backend.spec.ts`默认跳过且要求旧测试换码服务，不能直接指向正常后端把失败当产品问题。本轮证据脚本在`.local/final-audit/`，其中的Token已过期/吊销，不能重放；需要重新准备明确身份，并严格与真实微信验收区分。

优先级：

1. 补375×812和430×932完整原生视觉与键盘/弹层/全屏图片、原生头像昵称选择、双真实微信会话验收，解决审计结论C的证据缺口。
2. 复核本轮草稿发布/发送与图片预览续期，检查错误反馈、Token到期、断网及无数据状态；每项变更保留回归。
3. 搜索超过50条的分页、长时图片统一续期、后台明细查询成本、临时/孤儿图片回收与配额按需求另立范围。
4. 明确生产图片服务、HTTPS/WSS、微信域名与真机验证；微信订阅推送不属于当前已实现能力。
5. 用户确认后再整理Git提交、版本号和发布tag；不要把当前未提交候选当正式V1.0.0发布。

本轮大量压力样本已在此前软归档，预览维持4位虚构学生、4条动态、4件商品及真实账号自己的内容。本次新增验收主体也已按精确批次软归档；审计、举报、通知与消息历史保留，统计仍是开发库统计。不要清库来让截图更好看，默认不要重新执行stress数据脚本。

## 7. 后续架构待办，仅记录不实施

| 方向 | 恢复后先满足的条件 |
|---|---|
| Redis生产化 | 确认真实需求、认证/TLS、持久化/备份与可用性；当前只有本机限流实例，不将其当可靠消息库 |
| 高并发 | 先压测和慢SQL/查询数证据，治理后台N+1、连接预算、WS复核成本和上传资源限制；再决定缓存/索引/限流调整 |
| RabbitMQ | 只有出现可靠异步通知等明确需求才评估，先定义事务一致性、幂等、失败重试和运维成本；当前不安装、不接入 |
| Spring Cloud微服务 | 仅在模块边界、团队与部署规模证明必要时评估；当前模块化单体继续保留，不预先引入Nacos/Gateway/Kafka/Kubernetes |

冻结期间以上均不推进。恢复入口始终是本指南、最终审计、现有API契约和实际代码，不是旧阶段“已完成”的一句结论。

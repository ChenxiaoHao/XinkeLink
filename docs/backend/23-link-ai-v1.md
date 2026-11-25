# Link AI 第一版（2026-11-17—18）

范围：现有Java后端独立 `cn.xinkelink.ai` 包，学校规章制度问答及公开帖子查询。保持Boot4.1.1、Java21及既有中间件开关；不含通用聊天、写作、发帖、交易或Agent平台。代码留工作区，未提交、未推送。

## 面试演示资料

[请假流程](link-ai-demo/leave.md)、[进步奖学金](link-ai-demo/scholarship.md)和[图书馆开放与借阅](link-ai-demo/library.md)均为虚构的文字样例。需要在其他本地环境恢复样例时，可在「Link AI 知识库」上传 Markdown，沿用带 `【测试样例】` 前缀的标题，填来源 `urn:xinkelink:demo-knowledge`、版本 `demo-v1`、适用范围 `all`，在有效性中注明「虚构样例，仅供测试」，校对保存后发布。小程序入口会显示测试提醒，引用这些样例的在线回答会附上 `【测试回答】`，来源卡片只显示样例信息。

## 文件和运行

- 后端 `apps/server/src/main/java/cn/xinkelink/ai/`：受控模型网关、路由、公开帖子查询、会话以及 `knowledge/` 资料导入和混合检索。
- 新增Flyway V15资料归档/版本/片段/索引指针、V16会话/请求消息、V17有效日期、V18审计模块；原V1—V14不改。18迁移、47业务表。`scripts/database-table-contract.json` 与授权脚本保持同步。
- 小程序 `features/ai/` 与既有 `pages/ai`，保留共用WXML和五导航；后台 `KnowledgeManagementView.vue` 在既有认证布局下维护知识资料。
- Spring AI2.0.2来自Maven Central，程序式OpenAI模型，不引入自动配置starter。官方兼容说明：https://docs.spring.io/spring-ai/reference/getting-started.html ，Chroma参考：https://docs.spring.io/spring-ai/reference/api/vectordbs/chroma.html 。无Boot/Netty/Protobuf降级或无关升级。

聊天与Embedding支持OpenAI兼容协议，URL为供应商的兼容基础地址（含其要求的 `/v1` 路径），各自模型和密钥独立。精排通过 `XINKELINK_AI_RERANK_PROTOCOL` 显式选择 `cohere`（默认，请求model/query/documents/top_n，响应results[index,relevance_score]）或 `dashscope`（原生请求model/input/parameters，响应output.results[index,relevance_score]），URL均填写对应协议的完整精排接口。不能把只支持DashScope原生协议的精排模型直接填入Cohere接口；错误、限流、非法结构不回退固定答案。

设置 `XINKELINK_AI_CHAT_URL/KEY/MODEL`、`XINKELINK_AI_EMBEDDING_URL/KEY/MODEL`、`XINKELINK_AI_RERANK_URL/KEY/MODEL` 及精排协议。密钥仅受保护配置/环境变量，禁止写入仓库和日志。2026-11-18已在受保护配置下完成真实聊天、Embedding与DashScope原生精排协议探测；后台真实发布也已调用外部Embedding。该结果与此前HTTP桩/Mockito测试分开记录，完整问答与题集评测结果以后续真实验收记录为准。

Chroma配置 `XINKELINK_AI_CHROMA_URL`（或属性 `xinkelink.ai.chroma.url`）、tenant/database（默认default_tenant/default_database）。使用REST v2和显式API生成的向量，Chroma不运行本地Embedding。本轮在 `.local/link-ai-20261117/chroma-venv` 独立安装Chroma1.0.0并用 `chroma run --path .../chroma-data --host 127.0.0.1 --port 18300` 运行；生产须独立持久化卷、服务访问控制，不向小程序开放端口。Python仅用于Chroma发行版和测试工具，无Python Agent服务。

本地验证 Link AI 时可运行 `./scripts/start-backend-dev.ps1 -Profiles 'db,auth,redis' -RequireAi`。启动前会检查模型配置项和 Chroma 心跳。模型配置可由环境变量提供，也可写入受保护且被 Git 忽略的 `.local/backend-day1/private/link-ai-environment.json`，以环境变量名为键，值为字符串。管理员从知识库页面确认索引已就绪，并在发布至少一条核对过的资料后再验证带引用的问答。

输入/上下文/输出字符数与输出Token、API超时、每用户分钟频率、全局并发由 `XINKELINK_AI_INPUT_CHARS/CONTEXT_CHARS/OUTPUT_CHARS/OUTPUT_TOKENS/TIMEOUT_SECONDS/REQUESTS_PER_MINUTE/GLOBAL_CONCURRENCY` 配置。默认1000/6000/8000/1200/25秒/6次/2路只是保守起点，非容量承诺。首版单应用实例，内存锁/限流不适用于多实例。

## 业务流程

登录、账号状态和会话归属校验后，读取该会话有界主题/时间/分类/上次展示ID顺序上下文；一次模型结构化调用做补全和两类意图识别。程序校验字段、枚举及边界；混合/模糊/越界请求只选择能力或澄清。模型不能提供用户ID或SQL。

`search_posts`复用ForumService现有公开查询和投影，时间/分类受控，匿名作者及图片授权保留；回复只展示真实卡片，价格以原帖为准。历史引用每次按当前公开状态重新投影，隐藏/删除条目安全消失；不持久化旧敏感投影。

后台上传文字PDF/DOCX/TXT/MD或指定学校官网文章，预览校对提取正文、补齐来源/版本/适用范围/有效说明及可选有效起止日期，先保存草稿、单独发布。扫描件、图片/附件缺失明确提示；无OCR。官网白名单仅https://hbcet.hpu.edu.cn，逐次重定向与DNS检查、大小及超时限制，不跟踪链接。

发布准备带稳定片段ID的中文BM25与Chroma新一代索引，再用短事务归档原文件/校对正文/版本/向量并切换有效指针；失败保留旧有效版本。更新草稿期间旧已发布版继续有效，停用后两路都不可作为证据。重启从MySQL重建BM25并检查Chroma指针；失败返回未就绪。

知识库先过滤已发布、未停用、适用范围与有效日期，再并行BM25/Dense，去重RRF，外部精排，结构化证据检查，通过后才生成答案。最终引用只允许本次真实证据的程序编号和元数据；拒绝虚构编号及模型生成URL。无候选、低相关、证据不全、版本冲突、过期与外部故障分别处理。首版 `all` 表示全校适用资料，账号细分组织范围未开放，非all资料不会对所有用户开放。

会话API为 `/api/v1/ai/sessions`（GET/POST），`/{id}`（GET/DELETE），`/{id}/messages`（POST `{requestId,text}`）。MySQL保存完整问题、回答、来源、帖子ID和PENDING/DONE/ERROR状态；重试同ID同内容复用记录，不同内容拒绝。每会话简单互斥、全局有界，删除与迟到写入用数据库归属/行锁保护。外部API调用不承诺恰好一次。

fixture明确标记演示；live只读真实后端，失败/未配置/空结果不回退演示。小程序请求绑定当前身份和页面代次，退出/换号/删除后丢弃迟到响应。

## 验收记录

独立证据目录 `.local/link-ai-20261117/`、`.local/link-ai-20261118-real/`。初始工作区状态及binary diff留存，原有未提交文件未回滚。开发端口18081本轮开始没有监听，没有替换原服务。恢复仅登记的13306实例；所有新AI数据在命名隔离库，未向开发库发布资料。测试Redis16389、Chroma18300及临时应用均按归属记录收尾，共享MySQL保留。

真实Chroma/真实MySQL与HTTP桩/Mockito外部模型严格区分。首次编译/迁移准备/代理/原生自动化失败日志均保留，不能计为通过。最终测试和内存结果见本节后续追加记录。

题集为 `link-ai-knowledge-evaluation.json` 和 `link-ai-post-evaluation.json` 共24条；合成资料明确只用于隔离环境，不代表学校真实规定。默认两路各30、融合20、最多5证据可配置。7条隔离合成样例已调用真实gte-rerank-v2：正例最大得分的最小值0.195981，负例最大值0.083576，试验中点0.139779仅用于本轮隔离运行。**学校真实资料阈值尚未校准**；小样例不能证明生产准确率或最优参数，生产默认值没有改为试验分数。

本地进程观察不等于4GB部署验收；未做全套服务4GB容量、生产部署、物理手机或正式微信授权验收。

### 2026-11-18 后台真实导入、校对和版本发布

使用真实Chrome浏览器，既有HTTPS后台5178代理独立后端18131；全程未设置路由桩。真实管理员登录后上传隔离合成TXT，在正文编辑框将待校对文字人工改为题集原文，核对来源、版本、全校范围和有效日期2026-01-01至2027-12-31，再勾选校对并保存。POST草稿返回未发布状态，之后单独确认发布v1，真实MySQL、Chroma及外部Embedding链路返回成功。

同一资料13在页面更新为v2、增加第二条后重新校对并保存；页面明确显示“原发布版本仍生效”，响应仍指向v1有效版本。单独确认发布v2后，服务切换到新的UUID版本指针。此次验收保留v2有效，供后续真实问答评测使用；未在该浏览器流程中停用资料，也未向开发库发布真实学校规章。

证据位于 `.local/link-ai-20261118-real/admin-real-report.json` 及 `admin-real-v1-draft.png`、`admin-real-v1-published.png`、`admin-real-v2-draft-v1-effective.png`、`admin-real-v2-published.png`。首次流程的登录、提取和人工校对截图保留在 `admin-attempt1/`；随后因隔离库存在同名历史样例导致验收脚本定位不唯一，已按原草稿13继续，无重复创建。截图人工检查确认发布日期、校对正文、有效日期及旧版本提示，未包含密钥、密码或令牌。临时后台5178已停止。

### 2026-11-25 本地模型配置

管理后台的「Link AI 服务」可启用或暂停新问答，分别编辑对话、向量、重排的模型名称、Base URL 和 API Key。历史会话在暂停时仍可查看。页面按北京时间统计当天由模型供应商实际报告的 Token，最近 20 条记录只显示时间、模型、结果和耗时等运行信息。

API Key 只在更换时提交，使用服务端加密密钥保存在数据库。可设置 `XINKELINK_AI_SETTINGS_KEY_BASE64`，值为至少 32 字节随机密钥的 Base64；未配置时使用独立派生自当前 JWT 密钥的密钥。更换部署密钥后，如旧的模型凭据无法读取，可在后台重新输入。其他 HTTPS 模型服务的主机需事先加入 `XINKELINK_AI_ALLOWED_HOSTS`。重排使用完整接口地址。已发布资料的向量重建成功后才会切换模型。

本地 Link AI 对话使用百炼 `qwen3.7-flash-2026-07-15`，通过 `XINKELINK_AI_CHAT_MODEL` 配置。部署时如使用该模型，同时设置 `XINKELINK_AI_CHAT_ENABLE_THINKING=false`，让意图识别和知识问答使用非思考模式。未配置此选项时，服务沿用模型提供方的默认行为。检索向量继续使用 `qwen3.7-text-embedding`，重排使用 `gte-rerank-v2`。[百炼深度思考配置](https://help.aliyun.com/zh/model-studio/deep-thinking)

### 2026-11-18 模型适配、资源边界与真实结果

本次配置为Chat `glm-5.3`、Embedding `qwen3.7-text-embedding`（实测1024维）、Reranker `gte-rerank-v2`。用户指定桌面文件可读，API Key仅由忽略目录下的本地环境加载器读取并传给子进程，没有写入源码或日志，没有修改桌面文件权限或系统/用户级环境变量。网关真实调用证据：`gateway-real.log`，三项success=true。

百炼gte-rerank-v2使用原生 `/api/v1/services/rerank/text-rerank/text-rerank` 与 `dashscope` 协议，兼容API `/rerank`、`/reranks` 实测404。glm-5.3仅支持思考模式，尝试关闭实测400；原1200输出Token预算可导致过早截断，不能把空正文或LENGTH完成原因当正常答案。本轮本地配置使用4096输出Token、单调用60秒，仍保留可配置限额；截断、空正文、无效结构均报可重试错误。[官方GLM说明](https://www.alibabacloud.com/help/en/model-studio/glm)、[精排协议](https://www.alibabacloud.com/help/en/model-studio/text-rerank-api)。

真实业务HTTP第二轮13/14项首次通过，剩余疾病代理追问503后使用同一requestId重试成功，消息数保持4条，没有重复追加（`real-business-r2.log`、`real-business-results.json`、`real-stable-retry.json`）。包含公开查帖、最近一周、第二条、换主题、空结果、跨会话澄清、混合选择、超范围、历史恢复、知识问答、部分回答、无依据拒答。第一次6/14的失败也保留在r1记录中，不能记为通过。直接真实知识链路另验证3例成功，例外条件及部分回答引用有效；均为隔离合成制度，不代表学校真实规定。

真实微信开发者工具live验收11/11通过，原生页面使用真实18131请求和真实模型：6张公开卡片、按当前顺序取第二条、既有详情跳转、历史恢复及v2来源问答。此处是页面handler/运行状态与真实HTTP验证，没有冒称物理手势或手机验证。SDK返回路由的首次脚本失败另存，最终显式重访AI页成功；原配置字节、账号存储、local模式、origin18081恢复，临时项目关闭。见 `native-live-README.md`。官方编译独立通过29 WXML/33 WXSS。

证据提示词按配置预算检查完整JSON及系统提示词，超限返回证据不全，不截断条款/例外；PDF通过流式有界Writer在200000字符（含页码标记）处停止，避免先积累解码正文再拒绝。上传5MB、页数400、正文200000字符及同步处理期限都是有界首版限制。知识版本/片段归档仅可INSERT；资料/索引指针/会话不授予DELETE，删除消息历史的权限限定在请求消息表。原业务表授权及中间件开关保留。

本地临时Java应用使用-Xmx512m，空载工作集约500MB，连续真实查询后约561MB、私有内存约619MB；Chroma、MySQL另行运行。这里只是Windows本机进程采样，不是Linux驻留内存总和、峰值压测或全套4GB容量验收。4GB服务器的应用/Chroma/数据库/Redis及既有中间件共存、真实学校资料规模、物理手机和正式微信授权仍须独立验收。

最终回归使用单独的 `xinkelink_cache_ai20261118_regression_test`（仅已登记13306），避免将真实1024维验收资料与Mockito的3维测试向量混用；保留原 `xinkelink_cache_ai20261117_test` 的真实样例、版本、会话和Chroma持久目录。没有清库、清Redis或改18081开关。最终检查计数与收尾记录在下方补充。


### 最终完整检查与收尾

最终日志均位于 `.local/link-ai-20261118-real/`：

| 命令/项目 | 结果 | 证据 |
| --- | --- | --- |
| 小程序npm run check | 395通过，0失败/跳过 | miniprogram-final.log/.exit |
| 后端mvn test | 378通过，0失败/错误/跳过 | server-final-unit.log/.exit |
| mvn -Pdb-integration verify | 集成278通过，0失败/错误/跳过；同次普通378不重复统计 | server-final-db.log/.exit |
| 后台npm run check | 107通过，类型检查、生产构建成功 | admin-final.log/.exit |
| 数据库静态契约 | 18迁移、47业务表 | schema-final.log |
| git diff --check | 通过 | diff-final.log/.exit |
| 官方原生编译 | 29 WXML、33 WXSS通过 | 前一证据目录native-compile-final.log |
| 最终构建真实模型烟测 | 问答/同ID回放/完整历史/删除后404均通过，首次200 | final-smoke.json/.log/.exit |

最后的真实烟测使用最终构建classes及真实MySQL、Chroma和三个外部模型，最终JAR哈希另存 `final-artifact.json`。此前首次失败、HTTP桩、Mockito及真实模型证据分别留存，没有删断言、跳过测试或用模拟分数宣称真实学校准确率。两处独立复核资源上限问题均经失败回归和最终完整检查验证。

已按PID、启动时间、命令与子进程归属停止本轮18131、Chroma18300进程链、Redis16389、后台5178和原生自动化9420；Chroma持久目录和全部命名隔离库保留。共享MySQL13306及Redis16379保留。18081初始未监听，最终亦未监听，没有替换原服务。小程序字节配置、账号存储、local模式及origin18081恢复。见 `final-cleanup.json`、`native-live-README.md` 和 `workspace-safety.json`。

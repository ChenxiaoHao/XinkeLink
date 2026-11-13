# 真实微信登录联调与少量照片预览数据

日期：2026-10-26。本报告更新11号报告中的微信配置及大批测试数据状态；不修改已有页面设计，不执行Git提交或部署。

## 真实微信登录结果

**开发者工具中的真实认证及原地登录功能通过。没有使用测试换码适配器。**

此前的问题有两项：Windows用户环境中已有凭据，但已运行的后端进程没有读取新变量；工程AppID与用户新配置的AppID不一致。本轮让开发启动器从进程、用户、系统环境依次读取缺失值，只将值传给后端子进程；同步工程公开AppID后重启后端及工具自动化项目。未把AppSecret写入任何配置文件或命令行。

确认结果仅输出存在性：AppID存在、AppSecret存在、工具AppID与后端配置一致。没有输出密钥、openid、session_key、登录code或Token。

| 真实步骤 | 实际结果 |
| --- | --- |
| 原生wx.login | login:ok，取得新临时code |
| POST /api/v1/auth/wechat | 200 / OK；正式服务端OfficialWechatCodeClient调用微信官方code2Session |
| JWT | 返回访问Token及刷新票据；访问Token具有JWT结构 |
| GET /api/v1/me | 200 / OK，身份与登录响应一致 |
| POST /api/v1/auth/refresh | 200 / OK，访问Token和刷新票据均轮换 |
| 使用新Token访问/me | 200 / OK |
| POST /api/v1/auth/logout | 200 / OK |
| “我的”原地登录 | 原生Page为pages/profile/index，loggedIn=true、loggingIn=false、loadFailed=false，昵称存在 |
| 小程序保存的会话 | 原生wx存储中存在JWT，实际请求/me返回200，资料与保存会话一致 |
| 后端重启及自然到期后的页面读取 | 页面reload沿用真实刷新机制，更新会话后/me再次200，保持已登录 |

首次登录仍显示服务端默认“新同学”，不会静默取得微信头像昵称。用户在资料编辑页主动选择头像、填写昵称后保存，这是既有头像昵称填写流程，不是登录失败。

新增`503 WECHAT_NOT_CONFIGURED`区分服务端未配置与真正换码失败；缺少配置时明确提示“微信登录尚未配置，请联系管理员”。真实上游换码失败仍使用`502 WECHAT_LOGIN_FAILED`，不返回第三方原文和密钥。URL和Token契约不变。

安全证据：`.local/curated-preview/wechat-real-login.json`、`wechat-ui-confirmed.json`、`wechat-confirm-refreshed.log`。前一次自动化切页/取数超时后，已通过原生Page状态和真实/me请求另行确认成功，不能把早先超时误报为换码失败。部分自动化对象对undefined的序列化不兼容，取数改为明确的字符串/布尔值；产品逻辑没有为此添加假状态。

## 数据缩减及照片

默认入口现在是**小规模preview**，不再默认生成500帖子、300商品。

| 本批内容 | 实际数量 |
| --- | ---: |
| 独立模拟用户 | 4 |
| 自然中文动态 | 4 |
| 商品 | 4（3在售、1已售） |
| 评论及二级回复 | 8 |
| 点赞及收藏 | 16 |
| 平台说明公告 | 1 |
| AI照片源文件 | 6 |
| 按归属及用途绑定的照片资源 | 8 |

动态包括晚霞、图书馆复习、失物求助、匿名羽毛球互助；商品为键盘、数学教材、台灯和球拍。正文不再出现“样本编号”或重复的虚构说明。平台公告及模拟用户简介集中说明这是本地预览；没有冒充真实在售商品、真实学生或学校通知。

照片使用**内置image_gen.imagegen**生成，每个主题单独生成，再复制进入仓库。不是几何占位图，不是整张品牌板、UI截图或外链图片；也不代表河南信息科技学院的真实实景。服务端按既有图片资源和短期访问授权提供图片，不暴露存储目录。文件及全部原始提示词见[照片说明](../assets/local-preview/README.md)和[prompts.json](../assets/local-preview/prompts.json)。

旧`local-seed-v1`批次仅软归档：490条未删除帖子、290件未删除商品、1,930条未删除评论、15条公告及100名模拟用户。已删除记录不再次修改，已售商品保留出售状态。旧会话、消息、举报、通知和审计历史没有物理清空或伪造结案；案件队列中仍可有旧样本。这些历史不等同于当前公开信息流。

归档严格按命名空间及创建请求前缀筛选；旧批次以外的真实账号、原有开发记录和本轮真实微信账号均保留。独立事务测试验证无关账号不变，图片、评论根关系、外键和商品出售状态继续使用原模型。

因此当前公开论坛共7条、交易推荐共7件，其中包含保留下来的原有开发记录；新预览只有4条动态、4件商品。后台实际帖子总数9、商品总数9、有效用户7，均来自数据库，并非生产统计。

## 执行方式

```powershell
# 默认计划为4条动态、4件商品，不写库
./scripts/seed-local-data.ps1
# 提交小规模预览；仅首次归档旧批次并创建新记录
./scripts/seed-local-data.ps1 -Apply
# 只读验证
./scripts/seed-local-data.ps1 -Verify
```

旧大数据工具保留为显式`-Dataset stress`选项，供独立开发/测试库使用，不是默认预览。重复执行preview只核验，不恢复旧大批数据，不覆盖后来编辑的内容。旧数据和图片未删除；本轮执行前另有受限备份`.local/curated-preview/private/before-curated.sql`。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| Java21编译、打包、完整回归 | **320通过：138单元/基础HTTP + 182真实MySQL集成；0失败、0跳过** |
| 新数据数据库事务测试 | 4/4数据、真实图片宽度、旧批次定向软归档、无关记录保留、重复执行不覆盖；已包含在320中 |
| 小程序TypeScript、项目检查及测试 | **173项通过**，包含缺少服务端配置的明确提示回归 |
| 官方WXML/WXSS离线编译 | 27个WXML、29个WXSS成功 |
| 重复执行数据脚本 | 新增0；不重新显示旧批次 |
| 图片真实HTTP | 4个帖子详情、4个商品详情及8个照片引用全部200；实际PNG宽度1448且字节数超过1MB，无失效URL |
| 正常管理API | 管理员真实登录、Dashboard、帖子/商品/用户列表均200，列表数量与缩减后的数据一致 |
| 小程序实际Page | 论坛、交易loadFailed=false；“我的”真实登录会话及/me已确认 |
| 密钥检查 | 仓库文本及本轮输出文件未发现实际AppSecret；运行中后端stdout/stderr另用共享读取复核，未发现密钥 |
| 迁移 | 维持V1～V5，无新迁移 |

证据位于`.local/curated-preview/`：`backend-full.stdout.log`、`miniprogram-check.log`、`wechat-compile.log`、`applied.json`、`repeat.json`、`verified.json`、`content-evidence.json`、`admin-evidence.json`及上述真实微信文件。证据不保存Token或code。

边界：本轮已完成开发者工具的真实认证与页面功能确认，**不等于真机及生产HTTPS/WSS验收**。工具窗口截图存在空白/截取超时，空白截图没有作为视觉验收证据交付；没有用设计图或合成页面代替。完整真机登录、头像昵称选择以及生产域名仍需实际设备和环境验证。

## 修改范围

- 微信配置：`scripts/start-backend-dev.ps1`、`apps/miniprogram/project.config.json`（仅公开AppID）。
- 登录错误：`ErrorCode.java`、`OfficialWechatCodeClient.java`、对应适配器测试及小程序`inline-login.test.cjs`、04接口契约。
- 小规模数据：新增测试工具`LocalPreviewData.java`、`LocalPreviewDataIT.java`；更新`LocalSeedRunner.java`、`LocalSeedRunnerTest.java`及`scripts/seed-local-data.ps1`默认数据集。
- 素材：`docs/assets/local-preview/`下6张PNG、提示词和说明。
- 本文及11号报告的后续状态链接。

没有新增微信测试登录入口，没有修改管理后台业务或小程序页面布局，没有执行Git commit/push/reset/clean。

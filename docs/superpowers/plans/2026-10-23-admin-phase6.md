# 信科 Link PC 管理后台第六阶段实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task by task. Do not begin until the user approves this plan. Do not commit.

**Goal:** 在现有 PC 管理后台加入只读操作日志和本地演示系统设置，并保持前五阶段行为不变。

**Architecture:** 新增独立的审计事件 Pinia store 与设置 Pinia store。现有业务 store 在 mutation 边界追加事件，日志写入采用异常隔离；日志页只读消费事件，设置页只修改本地配置。页面继续使用现有后台路由、固定壳层和设计变量。

**Tech Stack:** Vue 3、TypeScript、Pinia、Vue Router、Element Plus、Vitest、Playwright。

**Spec:** `docs/superpowers/specs/2026-10-23-admin-phase6-design.md`

## Global Constraints

- 本阶段仅完成“操作日志”和“系统设置”。
- 继续使用现有 Vue 3、TypeScript、Element Plus、Pinia 与本地模拟数据；不接 Java 后端，不改小程序。
- 刷新页面后模拟状态恢复初始值。
- 品牌名称固定为“信科 Link”，学校信息只作为平台服务范围展示，不宣称学校官方身份。
- 管理员信息仅展示安全的账号标识和角色，不存储或回显凭据。
- 日志事件只接受显式白名单字段，不接收整份表单或任意业务对象。
- CSV 使用 UTF-8 BOM、标准字段转义，并对以 `=`, `+`, `-`, `@` 开头的单元格加安全前缀。
- 日志 store 不提供编辑或删除动作。
- 不实现真实服务器、Redis、数据库清理、AI 审核或微信消息能力。
- 管理操作日志区分成功、业务拒绝、执行异常；日志写入异常不能改变业务状态、现有返回值或原有异常传播行为。
- 当前内存日志只用于前端演示，不能作为正式审计凭证；未来正式审计日志由 Java 服务端持久化。
- 保持前五阶段所有功能、数据联动、页面视觉和操作结果不变；不调整 Dashboard、登录页或其他业务页面布局。
- 不执行 Git commit、push、reset 或 clean。

## Review Focus

- 审计 writer 抛错发生在业务变更之后时，业务状态及原返回值保持不变；由 Task 2/3 的注入测试验证。
- 同一业务动作从 UI 调用时只写一条；举报联动多个对象时仍只写主审核事件；由 Task 2/3 的事件数量断言验证。
- 密码、Token、Cookie、Authorization、手机号/学号等敏感字段不会进入日志详情或 CSV；由 Task 1 的脱敏和导出测试验证。
- 业务规则拒绝与执行异常使用不同结果类别，异常仍按既有行为抛出；由 Task 1/2/3 验证。
- 设置无变化保存、非法设置、取消恢复及确认恢复均有明确且不重复的结果；由 Task 4/6 的单测和 E2E 验证。

## File Map

**新增文件**

- `apps/admin/src/stores/admin-audit.ts`：审计事件类型、只读 Pinia 状态、查询过滤和分页。
- `apps/admin/src/stores/admin-settings.ts`：本地默认设置、校验、保存、恢复默认值及设置日志。
- `apps/admin/src/utils/admin-audit.ts`：安全写日志包装、事件安全投影和 CSV 编码。
- `apps/admin/src/views/OperationLogView.vue`：日志统计、查询筛选、分页、详情抽屉及 CSV 导出。
- `apps/admin/src/views/SystemSettingsView.vue`：设置分组、校验反馈、保存及恢复确认。
- `apps/admin/tests/admin-audit.test.ts`：日志分类、脱敏、查询、分页、CSV 与异常隔离单测。
- `apps/admin/tests/admin-settings.test.ts`：设置默认值、校验、保存/重置和设置审计单测。
- `apps/admin/e2e/admin-phase6.spec.ts`：日志/设置浏览器流程及响应式验收。

**修改文件**

- `apps/admin/src/stores/admin-auth.ts`：只在内存中增加当前演示管理员标识，成功登录时赋值、退出时清除。
- `apps/admin/src/stores/content-moderation.ts`：为帖子、评论、商品、举报和用户 mutation 接入一次性审计事件。
- `apps/admin/src/stores/admin-operations.ts`：为公告和通知 mutation 接入一次性审计事件。
- `apps/admin/src/router/index.ts`：将 `logs` 和 `settings` 从开发中占位组件映射到正式页面，并取消这两个路由的开发中状态。
- `apps/admin/tests/auth.test.ts`、`apps/admin/tests/content-moderation.test.ts`、`apps/admin/tests/operations-management.test.ts`、`apps/admin/tests/router-auth.test.ts`：补充操作者、日志联动及路由回归断言。

不会修改 `DashboardView.vue`、前五阶段业务视图、公共布局、微信小程序或 Java 服务端。

## Task 1: 定义安全审计事件与只读查询接口

**Files:**
- Create: `apps/admin/src/stores/admin-audit.ts`
- Create: `apps/admin/src/utils/admin-audit.ts`
- Test: `apps/admin/tests/admin-audit.test.ts`

**Interfaces:**
- `AuditOutcome = 'success' | 'business_rejected' | 'execution_error'`。
- `AuditModule = 'posts' | 'comments' | 'products' | 'reports' | 'users' | 'announcements' | 'notifications' | 'settings'`。
- `AuditFilters = { keyword: string; actor: string; module: AuditModule | 'all'; action: string; outcome: AuditOutcome | 'all'; from?: string; to?: string }`。
- `AuditEventInput` 只接受 `module`、`action`、`targetType`、安全目标引用/数量、`outcome`、安全摘要码/短摘要、`changedFields` 和非敏感结果码；不接受任意对象、原始正文、理由、表单值或错误堆栈。用户对象不记录原始用户 ID，只记录掩码引用或目标数量。
- `AuditEvent` 添加由 store 生成的 ID、时间和操作者标签。
- 操作者标签从 `currentAccount` 生成；如账号呈现手机号/邮箱样式则在日志展示层掩码，不改变登录账号输入。
- `useAdminAuditStore().record(input: AuditEventInput): void` 只追加；`stats` 返回 `{ total, success, businessRejected, executionErrors }`；`query(filters: AuditFilters, page: number, pageSize: number): { items: AuditEvent[]; total: number; page: number; pageSize: number }` 返回筛选结果和安全分页结果。
- `recordAdminAuditSafely(writer: (input: AuditEventInput) => void, input: AuditEventInput): void` 捕获 writer 的异常，不向业务调用者抛出。
- `toSafeAuditRow(event: AuditEvent): SafeAuditRow` 与 `toAuditCsv(events: AuditEvent[]): string` 共用同一白名单安全投影；CSV 输出 UTF-8 BOM 字符串内容并按 RFC 字段引用、转义公式前缀。

- [x] **Step 1: 编写失败单测**，覆盖三种结果值、拒绝额外字段、凭据/JWT/电话/学号脱敏、操作者空值回退与手机号/邮箱掩码、统计计数、筛选和越界分页、CSV 逗号/引号/换行/公式前缀，以及 writer 抛错不向外传播。
- [x] **Step 2: 运行聚焦测试确认失败**：`npm --prefix apps/admin run test -- tests/admin-audit.test.ts`；预期失败，因为 store 与工具尚不存在。
- [x] **Step 3: 实现事件类型、Pinia 只读账本、筛选分页和安全投影**；状态初始为空，刷新后清空；详情和 CSV 均不得读取原始业务对象。
- [x] **Step 4: 实现 `recordAdminAuditSafely`**，将写入异常吞在审计边界内；该保护不包住或改变业务 mutation。
- [x] **Step 5: 重跑聚焦测试**，全部通过后再进入接入阶段。

## Task 2: 将内容治理和用户操作接入审计

**Files:**
- Modify: `apps/admin/src/stores/admin-auth.ts`
- Modify: `apps/admin/src/stores/content-moderation.ts`
- Test: `apps/admin/tests/auth.test.ts`
- Test: `apps/admin/tests/content-moderation.test.ts`

**Interfaces:**
- 消费 Task 1 的 `AuditEventInput` 和 `recordAdminAuditSafely`。
- `admin-auth` 对外增加只读 `currentAccount: string`；登录成功时设置，退出时清空，不写入 localStorage/sessionStorage。
- 保留现有业务方法签名和返回值，包括 `setPostStatus`、`deletePosts`、`setCommentStatus`、`deleteComments`、`setUserRestriction`、`banUser`、`liftUserRestrictions`、`applyReportUserAction`、`setProductStatus`、`deleteProducts`、`processReport`。

- [x] **Step 1: 为账号内存生命周期和内容操作审计编写失败测试**：成功 mutation 恰好一条；无效 ID/已处理/已封禁等业务拒绝记 `business_rejected`；冻结目标对象触发 mutation 异常时记 `execution_error` 并保持原异常传播；举报同步隐藏帖子/评论/商品或处理用户时只有一条 report 事件；不得记录正文、用户理由、学号或原始用户 ID；替换审计 writer 为抛错函数后业务状态及方法返回值仍与原行为一致。
- [x] **Step 2: 运行聚焦测试确认新增断言失败**：`npm --prefix apps/admin run test -- tests/auth.test.ts tests/content-moderation.test.ts`。
- [x] **Step 3: 增加 `currentAccount`**，不改变记住账号的持久化规则，不保存密码；signOut 清空内存账号。
- [x] **Step 4: 在 `content-moderation` 的 mutation 边界写日志**：状态真实改变记 `success`；当前校验显式拒绝或目标不存在记 `business_rejected`；mutation 抛异常时安全记录 `execution_error` 后原样重抛。对 `void` 方法通过内部 helper 的 changed ID 数量判断结果，不改变外部签名。
- [x] **Step 5: 为帖子/评论/商品状态更新增加仅供 store 内部调用的无日志 helper**；`processReport` 使用这些 helper 并只在完整处理结束后写一条主事件，防止嵌套重复日志；保留当前业务联动顺序、状态规则及用户历史。
- [x] **Step 6: 运行两份聚焦测试和现有内容/认证回归测试**，确保既有断言不变且新增断言通过。

## Task 3: 将公告和通知操作接入审计

**Files:**
- Modify: `apps/admin/src/stores/admin-operations.ts`
- Test: `apps/admin/tests/operations-management.test.ts`

**Interfaces:**
- 使用 Task 1 审计接口；操作者由 Task 2 的 `currentAccount` 读取。
- 保留 `saveAnnouncementDraft`、`updateAnnouncement`、`publishAnnouncement`、`withdrawAnnouncement`、`deleteAnnouncement`、`saveNotificationDraft`、`sendNotification` 的现有返回值与状态逻辑。

- [x] **Step 1: 增加失败测试**：草稿新建/编辑、发布、撤回、删除和模拟发送分别只产生一条对应事件；无效输入、已发送通知重复发送、无接收人等拒绝记录 `business_rejected`；注入 mutation 异常时记录 `execution_error` 并继续抛出原异常；事件和 CSV 不含公告/通知正文、收件人列表或原始用户 ID；审计 writer 抛错不改变方法结果或数据状态。
- [x] **Step 2: 运行聚焦测试确认新增断言失败**：`npm --prefix apps/admin run test -- tests/operations-management.test.ts`。
- [x] **Step 3: 在各业务 mutation 完成后追加单条审计事件**；草稿修改只记录动作和目标 ID，通知只记录模拟接收人数与范围类型，不记录接收者 ID；执行异常安全记录后原样抛出。
- [x] **Step 4: 重跑运营管理测试**，并核对旧公告、通知统计和发送状态断言仍然通过。

## Task 4: 建立本地系统设置 store

**Files:**
- Create: `apps/admin/src/stores/admin-settings.ts`
- Test: `apps/admin/tests/admin-settings.test.ts`

**Interfaces:**
- `AdminSettings` 仅覆盖 Stitch 已确认的分组：平台基本信息、内容审核规则、社区管理规则、管理员展示信息、系统基础参数。
- 可编辑规则只对应 Stitch 中明确出现的审核/社区配置控件；平台品牌、服务范围、管理员身份和演示环境参数只读。内容审核规则包括开关、审核敏感度选项和关键词配置；这些值只在设置表单与设置 store 生效，不执行实际内容拦截。
- 社区规则配置只实现已确认画板中可见的字段和文案，不增加推送、封禁或自动执行规则；当前提供的系统设置截图没有展开“社区治理公约”分段，若该分段有独立可编辑控件，开始实现前需要该展开态截图。
- 展示型信息（品牌“信科 Link”、学校服务范围、演示环境说明、当前管理员角色）为只读；不显示虚构服务器状态或安全审计结果。
- 只对设计稿中可编辑的规则配置提供本地字段；规则变化仅更新当前 Pinia 设置，不改变帖子审核、举报、通知、用户限制或其他业务 store。
- `SettingsSaveResult = { ok: boolean; changedFields: string[]; errors: Array<{ field: string; code: 'required' | 'invalid' }> }`。
- `saveSettings(next: AdminSettings): SettingsSaveResult` 校验后保存并返回结果；无变化成功返回空 changedFields 且不重复记日志。
- `resetToDefaults(): { changedFields: string[] }` 恢复本地默认值；调用方负责二次确认。

- [x] **Step 1: 写失败单测**，覆盖默认值/只读展示、合法保存、非法值拒绝、无变化保存不产生重复日志、字段名审计不包含字段值、恢复默认值、刷新新 Pinia 实例回到默认值、writer 抛错不影响保存结果。
- [x] **Step 2: 运行聚焦测试确认失败**：`npm --prefix apps/admin run test -- tests/admin-settings.test.ts`。
- [x] **Step 3: 实现本地默认设置和字段校验**；允许值限制在设计稿已确认选项，校验失败反馈字段路径和安全错误码，不把用户输入值写入日志。
- [x] **Step 4: 实现保存与恢复默认值**；只在保存发生实际字段变化或默认值实际变化时，分别写一条 settings 事件，事件只包含变更字段名；审计异常不能影响设置更新。
- [x] **Step 5: 重跑聚焦测试**，确认新实例没有跨刷新持久化依赖。

## Task 5: 实现操作日志页面及路由

**Files:**
- Create: `apps/admin/src/views/OperationLogView.vue`
- Modify: `apps/admin/src/router/index.ts`
- Test: `apps/admin/tests/router-auth.test.ts`
- Test: `apps/admin/e2e/admin-phase6.spec.ts`

**Interfaces:**
- 消费 `useAdminAuditStore().query(filters, page, pageSize)`、`toSafeAuditRow`、`toAuditCsv`。
- 筛选项包含关键词、管理员、业务模块、操作结果、操作类型、日期范围；列表数据、详情和 CSV 全部来自同一筛选结果。

- [x] **Step 1: 编写路由与浏览器验收场景**：受保护的 `/admin/logs` 显示正式页面，未登录仍重定向登录；筛选后结果、分页总数和导出范围一致；点击行只打开只读详情；空列表和无匹配状态可恢复。
- [x] **Step 2: 运行对应测试确认页面/路由尚未满足断言**。
- [x] **Step 3: 创建日志视图**，按 Stitch 组织统计卡片、筛选工具栏、表格、分页和右侧详情抽屉；使用现有 token 和固定 admin shell，页面本身只在主内容区域滚动。
- [x] **Step 4: 接入真实内存事件**，统计、筛选及导出均从 audit store 计算；提供醒目的“前端演示日志，刷新后清空，不作为正式审计凭证”提示。
- [x] **Step 5: 将 `logs` 路由映射到该视图**，只从 `development` 判定集合中移除 logs，不改变其他路由。
- [x] **Step 6: 运行路由单测和单页 E2E**，检查无数据显示、搜索、过滤、详情与下载文件内容。

## Task 6: 实现系统设置页面及路由

**Files:**
- Create: `apps/admin/src/views/SystemSettingsView.vue`
- Modify: `apps/admin/src/router/index.ts`
- Test: `apps/admin/tests/router-auth.test.ts`
- Test: `apps/admin/e2e/admin-phase6.spec.ts`

**Interfaces:**
- 消费 Task 4 的 `AdminSettings`、`saveSettings`、`resetToDefaults`。
- 所有保存结果经 Element Plus 消息反馈；恢复默认值经二次确认弹窗后调用 store。

- [x] **Step 1: 写浏览器场景**：分组字段正确显示；编辑、校验错误、保存成功、取消恢复、确认恢复、刷新重置均可观察；保存/恢复后日志列表出现相应事件。
- [x] **Step 2: 运行对应测试确认缺少系统设置页面及路由映射**。
- [x] **Step 3: 创建设置视图**，按 Stitch 分组展示可编辑配置与只读信息；标注“本地模拟配置，刷新后恢复，当前不驱动真实审核或服务端行为”。
- [x] **Step 4: 实现编辑草稿、字段校验、保存与二次确认恢复默认**；取消弹窗不调用 store；不添加服务器、缓存清理、内容清空或其他危险操作。
- [x] **Step 5: 将 `settings` 路由映射到新视图**，保持其他路由和公共布局不变；运行单测与 E2E。

## Task 7: 全量回归、布局验收与截图

**Files:**
- Modify: `apps/admin/e2e/admin-phase6.spec.ts`
- Create screenshots under `.local/admin-phase6/` (workspace evidence only; do not commit)

- [x] **Step 1: 在 Playwright 中串联跨模块流程**：登录后在帖子、评论、商品、举报、用户、公告、通知至少各完成一项本地管理动作，并确认日志各有一条对应记录；举报处理没有写入重复的内容模块事件。
- [x] **Step 2: 验证操作结果分类与异常隔离**：浏览器覆盖成功操作及设置校验产生的业务拒绝；注入的执行异常由 Task 2/3 单测覆盖并确认异常事件出现、原异常继续传播；日志 writer 异常由 Task 1/2/3/4 单测确认不改变业务状态或返回值。
- [x] **Step 3: 验证设置流程**：合法保存、非法保存、无变化保存、取消/确认恢复默认值、刷新回到默认值；确认只记录字段名而不暴露配置值。
- [x] **Step 4: 运行全部 Vitest**：`npm --prefix apps/admin run test`；9 个测试文件 / 55 个测试全部通过。
- [x] **Step 5: 运行类型检查和生产构建**：`npm --prefix apps/admin run typecheck` 与 `npm --prefix apps/admin run build`；通过。构建有既有单 bundle 超过 500 kB 的体积提示，不影响产物生成。
- [x] **Step 6: 运行全部 Playwright**：`npm --prefix apps/admin run test:e2e`；15/15 Chromium 测试通过，覆盖前五阶段及第六阶段流程。
- [x] **Step 7: 在 1366px 和 1440px 检查固定侧栏/顶栏、主区独立滚动、表格与抽屉；截图操作日志列表、详情、系统设置和恢复确认弹窗至 `.local/admin-phase6/`，并目视复核截图。**

## 执行边界

- 本计划只描述后续实现；当前阶段不得修改产品代码。
- 不执行 Git commit、push、reset 或 clean；保留工作区内全部既有未提交成果。
- 前端日志和设置刷新后重置，不能用于正式审计；真实持久化审计记录由未来 Java 服务端负责。
- 如果测试揭示前五阶段既有失败，先区分基线失败与本阶段回归；不得通过改写既有业务规则来消除差异。
- 当前 Stitch 系统设置参考未展示“社区治理公约”展开后的表单细节；实施时不得据此臆造可编辑控件。







# 管理后台黑白主题与审查问题修复

日期：2026-11-05。依据：`2026-11-05-admin-ui-audit.md`，以及用户“颜色改为黑白风格，按照建议修改”的确认。单线执行，无子 agent、无 Git 提交。

## 完成内容

- 登录和十个后台主页面改为黑白灰。Logo 图形保持，用 CSS 显示为黑色；照片不去色。删除、封禁、异常等危险状态保留红色，不依赖颜色单独表达状态。
- 保留固定侧栏、固定顶栏、主内容独立滚动。统一菜单、按钮、统计条、字体、边框、表格和详情抽屉；普通表格正文提高到 14px，辅助文字提高至 12–13px，常用管理操作点击区为 34px。
- 四个独立统计卡合并为同组统计条。Dashboard 待办限三条并显示不同案件编号；图表不再被待办撑高，近期动态回到首屏。
- 趋势改称“每日发帖量”，补纵轴、真实本期合计和逐点数值说明；不虚构活跃用户、增长率或零值。
- 帖子列表移除 `点赞数＋120` 伪浏览量，统一心形点赞和评论数量。动态没有独立标题时，不再重复显示正文摘要；评论上下文也移除重复的作者／正文块和“演示评论”旧文案。
- 用户列表直接使用已有 API 的 `contentCounts` 和 `reportCount`，不再把未加载档案当成 0，也不增加每行内容查询。真实 0 保留，缺失计数为“—”。
- 用户档案补未填写文案，普通限制与封禁操作分组，保留原原因填写、二次确认和封禁状态规则。
- 举报原因映射为中文；历史身份缺失时明确说明，不猜 ID；内容可用状态归到内容区域，不再放在被举报人卡中；无图片证据只用一行说明。
- 商品售价右对齐并统一两位小数，成色 90／80 分显示为“9成新／8成新”。已售商品限制和原管理流程不变。
- 公告减少重复时间，新建表单标记“未保存”；通知统一“真实站内发送、不可撤回、不调用微信订阅消息”，移除操作区重复“已发送”按钮。
- 日志长目标编号截短、完整值保留悬停提示；重复动作代码不再占一行；保留日志只读及安全脱敏。
- 系统设置按字段值比较，不再受 JSON 键顺序影响而误报“未保存”；审核强度保留单选控件，配置版本与系统架构改用准确标签；白色保存区保持清楚可读。
- `scripts/start-backend-dev.ps1` 补读取 Process／User／Machine 的后台可信来源配置；本地 auth 开发模式缺省使用 `https://localhost:5173`。不关闭或放宽服务端来源校验。

## P1 状态

| 审查项 | 本轮结果 |
| --- | --- |
| 伪造浏览量 | 已修复；真实浏览器由 121 变为服务端点赞数 1，图标改为心形 |
| 用户列表假 0 | 已修复；列表进入即显示后端 1帖／1评论，无需打开档案 |
| 通知风险说明矛盾 | 已修复；抽屉和原发送确认说明一致 |
| 默认后台来源登录403 | 启动脚本的默认值、自定义值传递和语法检查已通过；**未重启原18081进程，旧进程配置不会因源码修改自动更新** |

## 实际验证

| 项目 | 结果 |
| --- | --- |
| 新增计数回归测试 | 修复前3失败，修复后3通过 |
| `npm run check`（apps/admin） | 14个测试文件、79项测试全部通过；TypeScript与生产构建通过 |
| 真实浏览器修复前复现 | 5项检查失败：伪浏览量、用户假0、假未保存、通知文案、旧雾蓝色主色 |
| 真实浏览器第一轮 | 15项通过：5项问题回归＋10页布局／固定滚动检查 |
| 补充浏览器检查 | 13项通过：问题回归、修改后的页面和6个抽屉边界；无未捕获异常 |
| 开发启动脚本 | 3项通过：语法、默认可信来源、自定义来源优先。截获进程启动参数检查，不重启原服务 |
| 截图 | 1440×900真实Chrome，11个主页面＋8个抽屉，共19张；全部已查看 |

浏览器使用临时 HTTPS 预览 `https://localhost:5174` 与既有开发数据库的 18084 检查进程，没有拦截请求。原18081服务保持运行。没有修改、删除、发送、封禁现有业务数据；没有执行迁移或接入新的服务。

构建有主 JS 包约 1.20MB 的体积提示，构建未失败。本轮未为消除提示做额外打包重构。

## 边界与剩余事项

- 本轮不等于完整上线验收：没有在真实数据库上逐项执行删除、封禁、通知发送或保存配置，以免修改现有数据；这些写入链路仍应在隔离测试数据上做最终回归。
- 原18081进程需在合适时机按更新后的启动说明重启，再用 `https://localhost:5173` 复验登录、刷新恢复和退出。当前5174预览可查看本轮样式。
- CSV导出范围／上限、生产认证域名、生产环境及微信真机验收仍按最终上线清单处理，不把本次界面调整标记成这些项目已完成。
- 删除入口暂保留可见按钮和现有二次确认，没有为“更多”菜单另改操作路径；长日志ID支持悬停完整显示，暂未新增复制操作。
- 公告、通知、举报和审计中的旧测试数据如实保留；没有清库或自动转换历史数据。

## 修改文件

主题与布局：

- `apps/admin/src/styles/tokens.css`
- `apps/admin/src/styles/base.css`
- `apps/admin/src/styles/admin.css`
- `apps/admin/src/styles/content-management.css`
- `apps/admin/src/components/admin/AdminSidebar.vue`
- `apps/admin/src/components/admin/AdminTopbar.vue`
- `apps/admin/src/components/content/ContentMetricGrid.vue`
- `apps/admin/src/components/dashboard/TrendChart.vue`

页面：

- `apps/admin/src/views/LoginView.vue`
- `apps/admin/src/views/DashboardView.vue`
- `apps/admin/src/views/PostManagementView.vue`
- `apps/admin/src/views/CommentManagementView.vue`
- `apps/admin/src/views/ProductManagementView.vue`
- `apps/admin/src/views/ReportReviewView.vue`
- `apps/admin/src/views/UserManagementView.vue`
- `apps/admin/src/views/AnnouncementManagementView.vue`
- `apps/admin/src/views/NotificationManagementView.vue`
- `apps/admin/src/views/OperationLogView.vue`
- `apps/admin/src/views/SystemSettingsView.vue`

展示数据、测试和启动：

- `apps/admin/src/services/admin-adapters.ts`
- `apps/admin/src/services/admin-models.ts`
- `apps/admin/src/utils/admin-display.ts`（新增）
- `apps/admin/tests/admin-display-regression.test.ts`（新增）
- `scripts/start-backend-dev.ps1`

本轮产品与启动文件变更24个。小程序、Java业务、数据库结构、依赖版本均未修改。原有未提交成果已保留。

## 查看结果

- 截图画廊：`D:/CODE/XinKeLink/.local/admin-monochrome-20261105/index.html`
- 测试与构建：同目录 `check-final.log`
- 浏览器证据：同目录 `browser-before.json`、`browser-after.json`、`browser-finish.json`
- 启动配置检查：同目录 `startup-check.json`
- 原文件保护与本轮精确变更：同目录 `before/`、`before-hashes.json`、`changed-files.json`

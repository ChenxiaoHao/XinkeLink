# 我的闲置与商品管理验收

日期：2026-10-22。基线分支 codex/phase-1-foundations，HEAD 04dd5f0。本轮开始前有上一轮交易模块的未提交成果，全部保留；本轮也未创建 Git 提交。

## 实现与入口

微信开发者工具导入 apps/miniprogram，普通编译 →「我的」→「我的闲置」。也可打开 pages/my-items/index。无需运行后端。

- 在售、已售出、已下架三个分类、数量、横向商品卡、对应空状态、读取失败重试。
- 在售可编辑、下架、标记已售；已下架可编辑和重新上架；状态操作均先弹出原生确认框。已售只保留历史，没有编辑或重新上架入口，数据层同样阻止恢复。
- 编辑复用发布闲置表单，通过 editId 读取原商品，保存标题、描述、价格、分类、成色、议价和图片。商品 ID、卖家、发布时间、状态保持不变，不覆盖新发布草稿。
- 页面显示时重读本地商品。交易推荐排除已售，全部公开查询排除下架；明确搜索或其他分类保留已售历史与原有灰色状态。详情、关联私信和收藏仍使用同一个商品 ID；下架和售出均不能发起咨询或发送消息。
- 存储读取异常阻止管理写入；编辑失败保留输入，仅清理本次复制的图片。发布与编辑使用不同的存储命令，不能通过再次保存同 ID 商品将已售恢复。

## 设计依据

使用用户提供的 docs/references/market-publish-overview.png 第二张画板（x457 y101，335×553）。当前材料是总览图，未提供独立高清单页或字号、间距标注，已在开始实现前说明。

沿用既有头部、#6489B8、浅色背景、白色圆角卡片和阴影；保留图中数量行、发布按钮、三分类、横向卡片、底部绿色置换建议的顺序。按本轮操作范围显示编辑、下架、售出和重新上架；没有虚构浏览/咨询量、降价专用功能或额外菜单。我的页只增加已有参考中的「我的闲置」入口，未展开个人主页业务。

视觉对照 .local/my-items/comparison-my-items.png 已打开检查。原图不含完整 iOS 状态栏且内容、素材、状态数量不同，本轮不声称高清稿逐像素一致。编辑页保持已完成发布页的表单结构。论坛、Logo、共享变量和五栏导航文件相对本轮开始的 SHA256 快照无变化。

## 自动检查

本轮开始先运行原有检查，60/60 测试通过。管理与编辑先新增失败测试，再实现并修复至通过。最终执行：

```powershell
./scripts/verify.ps1 -WeChatDevToolsPath 'D:/develop/微信web开发者工具'
```

退出码 0，日志 .local/my-items/all-check.log：

| 检查 | 实际结果 |
| --- | --- |
| 小程序 TypeScript、项目结构 | 通过 |
| 小程序 Node 组件/页面/数据测试 | 70/70，通过；本轮新增 10 项 |
| 微信官方离线编译 | 14 WXML、16 WXSS 通过 |
| 既有后台类型检查、测试与 Vite 构建 | 4/4，通过 |
| 既有 Java Maven verify | 2/2，BUILD SUCCESS |

新增覆盖：状态流转和归属、售出不可恢复（含绕过更新路径）、下架查询排除、取消确认不写入、计数和分类、读取/写入失败、编辑白名单、同 ID 及收藏一致、编辑草稿隔离、编辑途中售出后的拒绝与图片回滚、旧详情重新显示时同步。相关文件由只读复核另跑 23 项检查，并补查九种状态组合、读取异常零写入和下架的各分类/搜索排除，未发现待处理的实质错误。

git diff --check 退出码 0；Git 的 LF→CRLF 提示是现有 Windows 行尾配置提示。后台和服务端仅运行原有检查，本轮未改源码。

## 实际微信模拟器验收

使用微信开发者工具 Stable v2.01.2510290，基础库 3.17.5。通过实际 IDE 控制台填入验收数据、触发页面方法，原生确认框采用鼠标点击取消/确认；截图来自项目窗口捕获后裁切模拟器区域。未用网页或离线测试代替模拟器运行，也未将控制台填表描述为手工键盘输入验收。

| 流程 | 实际结果 / 证据文件（.local/my-items/） |
| --- | --- |
| 发布 → 我的闲置 | 调用原发布页提交，两件商品持久化成功；返回我的闲置显示 2 件。publish-to-my-items.json、available-runtime.json |
| 编辑 → 列表与详情 | 标题、描述、价格 280→260、分类、成色、议价和图片数量修改后同步；ID 和创建时间不变，收藏仍指向原 ID。edit-list-sync.json、edit-detail-sync.json |
| 下架取消 | 实际点击取消，状态仍 available，忙碌状态释放。cancel-confirm.json |
| 下架确认 | 实际点击确认，移入已下架；推荐和关键词搜索均找不到，详情显示下架。off-shelf-runtime.json、off-shelf-market.json、off-shelf-detail.json |
| 重新上架 | 实际确认后回到在售，推荐恢复展示。relisted.json、relisted-market.json |
| 标记已售出 | 实际确认后保留历史，退出默认推荐；详情保留相同 ID/收藏且不可咨询。sold-runtime.json、sold-market.json、sold-detail.json |
| 已售不可恢复 | 页面调用重新上架不改变状态；以 editId 直接打开表单仍被拦截。sold-editor-block.json |
| 我的页入口和空状态 | 我的页在售计数为 1，入口正常；无商品在售、无已下架记录时均显示对应空状态。profile-entry.json、initial-runtime.json、empty-off-shelf.json |

两种实际机型：iPhone 12/13 mini 375×812（顶部 47、底部安全区 34px），iPhone 15 Pro Max 430×932（顶部 54、底部安全区 34px）。My Listings 操作按钮的运行时边界分别落在 x200.33–350.33 和 x238–402，均在视口内；卡片、三分类及底部提示无横向裁切，编辑保存栏避开底部安全区。测量日志 layout-mini.json、layout-big.json。

验收使用两条临时商品；完成后仅移除这两条记录、对应收藏 ID 和三张复制图片，未清空 Storage，也未改论坛交互。cleanup-runtime.json 确认商品/草稿为空、收藏恢复空数组、剩余验收上传文件为零，原有两个 Storage key 保留。工具最终停在「我的闲置」空列表。

### 实际截图

截图全部在 Git 忽略的 .local/my-items/ 下，不作为应用资源或构建产物提交。

| 状态 | 截图 |
| --- | --- |
| 在售，两张横向卡，小屏 | available-mini.png |
| 编辑复用表单，小屏 | edit-mini.png |
| 下架原生确认框，小屏 | down-confirm-mini.png |
| 已下架与重新上架入口，小屏 | off-shelf-mini.png |
| 已售历史，小屏 | sold-mini.png |
| 在售，大屏 | available-big.png |
| 已售历史，大屏 | sold-big.png |
| 已售详情与禁用操作栏，大屏 | sold-detail-big.png |
| 在售空列表，小屏 | empty-mini.png |

## 本地模拟边界

所有商品、编辑、状态和收藏仅保存在当前工具/微信设备的本地 Storage 与文件系统。没有真实登录、后端、跨设备同步或真实收信。已有模拟卖家商品不归本地学生管理，发布的新商品才进入「我的闲置」。本轮没有真实手机扫码、软键盘输入或生产服务验收；这些不列为已通过项目。

## 本轮修改文件

以下相对本轮开始的 .local/my-items/baseline.json 计算，不把上一轮未提交的交易代码误算为本轮新增。

- apps/miniprogram/miniprogram/app.json
- apps/miniprogram/miniprogram/assets/icons/plus-primary.png
- apps/miniprogram/miniprogram/features/market/catalog.ts
- apps/miniprogram/miniprogram/features/market/my-items.ts
- apps/miniprogram/miniprogram/features/market/repository.ts
- apps/miniprogram/miniprogram/features/market/types.ts
- apps/miniprogram/miniprogram/pages/market-chat/index.ts
- apps/miniprogram/miniprogram/pages/market-chat/index.wxml
- apps/miniprogram/miniprogram/pages/market-detail/index.ts
- apps/miniprogram/miniprogram/pages/market-detail/index.wxml
- apps/miniprogram/miniprogram/pages/market-publish/index.ts
- apps/miniprogram/miniprogram/pages/market-publish/index.wxml
- apps/miniprogram/miniprogram/pages/market-publish/index.wxss
- apps/miniprogram/miniprogram/pages/my-items/index.json
- apps/miniprogram/miniprogram/pages/my-items/index.ts
- apps/miniprogram/miniprogram/pages/my-items/index.wxml
- apps/miniprogram/miniprogram/pages/my-items/index.wxss
- apps/miniprogram/miniprogram/pages/profile/index.ts
- apps/miniprogram/miniprogram/pages/profile/index.wxml
- apps/miniprogram/miniprogram/pages/profile/index.wxss
- apps/miniprogram/scripts/generate-icons.cjs
- apps/miniprogram/tests/market-query.test.cjs
- apps/miniprogram/tests/my-items-edit.test.cjs
- apps/miniprogram/tests/my-items.test.cjs
- docs/superpowers/plans/2026-10-22-my-items.md
- docs/my-items-verification.md
- design-qa.md（追加本轮记录，原验收内容保留）

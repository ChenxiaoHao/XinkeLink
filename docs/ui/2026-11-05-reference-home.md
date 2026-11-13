# 论坛与交易首页：指定参考图落地

日期：2026-11-05。范围为两个首页、对应卡片和共用五栏导航，保留既有真实 API 与业务数据。本轮没有修改 Java、管理后台或数据库，没有执行 Git 提交、推送或清理。

## 视觉依据与明确差异

- 交易参考：`codex-clipboard-4b2b377e-4c8d-4727-92ec-cb7a1b350891.png`。
- 论坛参考：`codex-clipboard-13f613a3-4745-49ab-bb89-d2f251f818bc.png`。
- 用户确认：只保留点赞，去掉参考图中的书签；保留原有商品分类，使用图标分类样式。
- 使用真实内容、照片、价格和时间。当前接口返回 5 条帖子、3 件商品；首帖只有 1 张照片。没有为匹配参考数量而复制图片或添加假商品，没有编造原价划线、租金或推荐标记。
- 不绘制假的系统时间、灵动岛和微信胶囊。截图来自工具的应用内容图层。

## 完成内容

1. 两页顶部采用原 Logo 的黑色显示样式、黑色品牌文字与搜索图标，保留原生胶囊避让。原 Logo 文件未替换。
2. 论坛保留作者、正文、缩略图、互动的顺序，改为白色圆角独立卡片、黑色分类下划线及统一线性图标。单图不占满整卡；双图、三图、四图继续有各自排列和原图预览。可用的作者专业附带“自填”标识，匿名帖不展示真实专业。
3. 交易使用搜索栏、圆形筛选入口、六个图标分类和等高双列商品卡。图上心形点赞、左下状态标识、红色价格和校内面交信息与参考方向对齐。原排序及筛选通过筛选入口保留，不增加商品分类。
4. 五栏导航改为浮动白色圆角底板、黑色图标、轻灰选中背景与黑色中央发布按钮。保留原切换动效、未读数和发布流程。
5. 点赞仍只更新对应内容，不改变已经确认的不整页刷新机制。其他页面使用的普通商品卡样式保留；默认瀑布流分列逻辑继续存在，只有交易首页选择等高网格分列。

## 验证结果

| 检查 | 实际结果 |
| --- | --- |
| `npm run check`（`apps/miniprogram`） | 类型检查、236 项测试和工程引用检查通过；0 失败 |
| 官方离线编译 `node apps/miniprogram/scripts/check-wechat.cjs "D:\develop\微信web开发者工具"` | 28 个 WXML、32 个 WXSS 通过 |
| 微信开发者工具实际渲染 | 两页正常显示；真实数据数量为论坛 5、商品 3 |
| 页面事件与真实 API | 论坛最新分类、商品价格排序检查通过 |
| 底栏测量 | 五个入口等宽，中央发布按钮宽高相等 |

回归测试覆盖原排序选项、原状态筛选入口、网格分页顺序及匿名专业隐藏。改变分列预期是本轮明确的等高双列布局要求，未删除业务或安全断言。

初次增加独立 WXSS 导入时，实际开发工具出现样式依赖缓存错误，虽离线编译通过仍无法渲染。已将样式放回两页现有 WXSS，重新实际编译并确认两页恢复，未保留该失效导入。

工具实际型号为 **iPhone 15 Pro Max，430×932**，没有切换多个尺寸；未声称已完成指定 iPhone 15 Pro 或真机验收。本轮没有重新验收真实账号点赞写入、系统分享送达或全站业务流程。真实内容及图标细节与参考有差异，因此不宣称逐像素完全一致。

## 实际截图与证据

- [论坛最终截图](../../.local/reference-match-20261105/forum.png)
- [交易最终截图](../../.local/reference-match-20261105/market.png)
- [测试输出](../../.local/reference-match-20261105/check.log)
- [官方编译输出](../../.local/reference-match-20261105/compile.log)
- [模拟器事件和测量结果](../../.local/reference-match-20261105/native.json)
- [逐文件修改清单](../../.local/reference-match-20261105/changed-files.txt)

## 本轮修改文件

以下为相对本轮开始前备份的差异，不能将 Git 中原有未提交成果算入本轮修改。

```text
apps/miniprogram/miniprogram/
  backend/adapters.ts
  backend/pages.ts
  components/forum-post-card/index.wxml
  components/forum-post-card/index.wxss
  components/market-product-card/index.wxml
  components/market-product-card/index.wxss
  components/page-header/index.ts
  components/page-header/index.wxml
  components/page-header/index.wxss
  custom-tab-bar/index.wxml
  custom-tab-bar/index.wxss
  features/forum/types.ts
  features/market/catalog.ts
  pages/forum/index.wxml
  pages/forum/index.wxss
  pages/market/index.ts
  pages/market/index.wxml
  pages/market/index.wxss
  assets/icons/ref-*.png（33 个新增资源）
apps/miniprogram/scripts/generate-reference-icons.cjs
apps/miniprogram/tests/reference-home.test.cjs
apps/miniprogram/tests/home-presentation.test.cjs
docs/ui/2026-11-05-reference-home.md
design-qa.md（追加本轮记录）
```

图标由现有 Lucide 库统一生成，现有图标未覆盖。`backend/` 两个文件位于小程序，是显示适配层，不是 Java 服务端。

## 同日追加：底栏下移与公告恢复

按用户后续反馈，底栏下移约 12px，保持五栏布局与底部安全距离；论坛分类下方恢复平台公告条，继续读取原有真实公告 API，并保留点击查看逻辑。仅修改底栏 WXSS、论坛 WXML 和 WXSS。

重新执行检查：236 项测试、类型检查及 28 WXML / 32 WXSS 官方编译通过。实际工具检查显示公告成功加载，底栏距窗口底部为 28px，符合本轮位置设置；未新增真机或其他尺寸验收。[实际截图](../../.local/nav-announcement-20261105/forum.png)及[测量记录](../../.local/nav-announcement-20261105/native.json)存于本地证据目录。

## 同日再次调整：半高底栏与白底公告

按用户最新要求，底栏高度由 124rpx 改为 62rpx，并在上一位置上再下移约 5px。缩小图标与标签间距，中央发布按钮保留突出效果；普通入口的点击区域向上扩展，避免随底板一起缩小。公告取消蓝色底、喇叭和“平台公告”长标签，改为白底细边框、“公告”小标签、单行标题及右箭头，保留原公告数据和查看行为。

本次重新运行的 236 项测试、类型检查、28 WXML / 32 WXSS 官方编译通过。现有 iPhone 15 Pro Max 模拟器实际测量：底板含边框高 37px、距窗口底部 23px；四个普通入口点击区均高 50px、发布按钮 44×44px；公告真实加载正常。[最终截图](../../.local/compact-nav-20261105/forum.png)和[测量记录](../../.local/compact-nav-20261105/native.json)以此版为准。没有补做真机或其他型号验收。

## 当前版本：恢复完整导航高度，保留下移位置

用户随后要求“导航栏恢复，下移”，因此半高方案已撤回：恢复 124rpx 底板、原图标和文字大小、原中央发布按钮；保留较低的位置，白底公告不变。当前实际测量为底板含边框高 73px、距底部 23px。[最新测量记录](../../.local/restored-nav-20261105/native.json)替代上一节的半高导航证据。236 项测试、类型检查及 28 WXML / 32 WXSS 官方编译重新通过。本次没有重复截图。

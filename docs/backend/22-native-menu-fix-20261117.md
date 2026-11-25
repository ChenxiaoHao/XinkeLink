# 原生操作补核与帖子菜单修复（2026-11-17）

本轮延续联合验收隔离环境，随后按用户要求自行检测、修复本人帖无法删除并移除书签入口。未重新执行完整后端/数据库/后台回归，未提交或推送。

证据：`.local/manual-acceptance-20261117-201848/`，未覆盖上一轮目录。

## 修复

- 共用 `forum-post-card` 原菜单只提供编辑、书签、举报，漏掉删除；原有服务端删除接口与“我的帖子”删除能力正常。本次本人帖菜单改为编辑/删除，他人和访客菜单仅举报，移除书签操作方法。历史数据和后端兼容接口未清理。
- 删除使用原确认框语义、现有 fixture 仓库或 live DELETE，以及带引号的 If-Match 版本。保留 `getViewerRole()`，确认前退出/换号/切模式不发出旧身份删除，失败不显示删除成功。
- 原生复验发现 fixture 首页没有 `reload`，删后父列表仍保留条目；补回其现有 `onShow` 刷新路径。保留失败证据，补回归后再真实操作通过。
- 修改文件：`apps/miniprogram/miniprogram/components/forum-post-card/index.ts`、`apps/miniprogram/tests/forum-card.test.cjs`、`apps/miniprogram/tests/x-shell.test.cjs`。原书签断言按用户明确撤除功能的要求改为验证不再提供入口/事件，并非跳过失败。

## 实测结果

| 项目 | 观察 |
| --- | --- |
| 小程序 npm run check | 378通过，0失败，0跳过；`mini-final.log/.exit`。新增5条针对删除、匿名fixture、HTTP失败/版本、退出和换号的检查。 |
| live 原生删除 | 系统鼠标点击本人帖更多→删除→确认，真实 DELETE `/posts/15` 返回200，DB deleted_at有值、version=1；他人菜单仅举报。`native-delete-completed.json`、`native-deletion-db.tsv`及受保护截图。 |
| fixture 原生删除 | 准备本地匿名帖后真实点击删除；仓库和已渲染列表均不再含该帖。`native-fixture-deletion-final.json`。首次列表未刷新的失败亦保留。 |
| 福袋正文/参与/遮罩拖动 | live共享弹层正文及参与后保持打开；背景可滚动高4214、遮罩拖动前后背景scrollTop=0。`live-before.json`、`live-after-body.json`、`live-after-join.json`、`live-after-mask-drag.json`。17期最终A/B各一条参与。 |
| live 中奖复制与滚动 | 独立16期真实任务开奖，A本人卡片；系统鼠标点击两复制按钮，剪贴板与本人编码/测试微信一致，卡片保持打开；向下拖动到边界稳定，再向上回到顶部。`native-winner-gestures.json`，5项通过。此处是历史页的原生共享卡片，不代替三页弹层全覆盖。 |
| fixture 刷新按钮 | 使用已有fixture控制准备截止等待状态，共享卡片在历史页准备为弹层；真实点击刷新，仍waiting、visible=true、无错误。`native-refresh.json`。 |
| 原生三页批次 | 批量定位/状态读取仍失败，见 `native-three-entries-error.json`、`native-three-entries.json`；未算通过，未无限重试。小屏、安全区、抽屉/切后台及三页完整防穿透仍未完成。本轮关闭按钮坐标检查亦未取得成功证据。 |

测试程序可准备数据，但上表实际点击、复制与拖动采用Windows原生输入，没有handler/triggerEvent/程序滚动替代。组件状态读取不算输入证据。没有物理手机或正式微信授权结果。

## 环境和收尾

沿用固定JAR `E88064A480791FCE22FCA47830AB5BCDA3D626F2D0F5BD4DD9B88D9543596227`、13306上的 `xinkelink_cache_joint_20261117_r2_test`、原Topic/组、独立Redis16390、Canal11119及原单消费者策略。复制必要的checkpoint/Canal持久状态到本轮目录恢复，未跳位点、清库或覆盖旧证据。双JVM14996/16688、Canal8284归属记录及启动方式保存在本轮目录。

新准备隔离16期中奖长说明、17期可参与长标题，以及背景帖12—15。16期仅为隔离测试A唯一参与并由真实任务开奖，无实际转账；17期A/B各一次，测试数据保留。旧A/B会话已到期，按既有验收方法只为原隔离账号4/5建立新测试会话。准备器曾错误保存账号对应会话、旧刷新票据过期，均按准备器问题处理，未修改业务身份体系。

小程序后端地址临时改为18122，避免启动误连18081；结束按字节恢复原 `backend/config.ts`。确认Ctrl+B可能被内置编辑器截获后，改用工具栏真实编译按钮切换模式；首次fixture模式准备失败不计为业务失败。

结束恢复原local模式、六项原存储及fixture发帖存储，移除请求记录包装器，重新实际编译，origin恢复18081。`restored-ui.json`记录keysMatch=true、wrapperRemoved=true。已按启动时间/命令归属停止双JVM、Canal及本轮Redis；共享基础设施保留。18081未更新、未改开关，原进程/JAR保持健康。最终端口、健康、哈希及差异检查见 `delivery-check.json`，此前的临时配置和定位ID不留在代码中。

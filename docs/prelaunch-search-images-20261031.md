# 上线前收尾：搜索分页与图片授权续期

日期：2026-10-31。本轮完成“补齐已知问题”，OSS及页面优化尚未开始。保留全部已有成果，未修改Java业务或数据库，未执行Git提交、推送、重置或清理。

## 修复内容

- 搜索后端原本已有游标，正式小程序只取首批50条。现按每页20条追加，帖子和商品分别保存游标；支持上拉、继续加载、失败重试、请求锁和ID去重。
- 输入仍等待确认才搜索。清空、输入变化和新搜索会使旧请求失效；切换Tab不重新请求第一页。已有点赞与结果保留，有下一页时数量显示`+`。
- 小程序公共页面在显示时及每15秒检查图片到期时间，为15秒内到期的图片续取权限；隐藏或卸载时停止。仅更新图片字段，不重新加载业务数据，不改写正文、标题或搜索词。
- 相同图片并发续期共用请求；失败退避60秒。权限拒绝清除失效地址，不回退旧签名。提交更新前核对页面、登录身份及原字段，避免覆盖正在替换或删除的照片。
- 编辑保存按图片ID识别已有图片，避免授权URL变化后误重新上传。
- 后台帖子、评论、商品、举报证据和用户头像复用续期组件，沿用Element Plus样式及预览。使用现有管理员认证与图片接口，不重载表格或抽屉，不扩大读取权限。

## 本轮验证

| 检查 | 结果 |
| --- | --- |
| `npm run check --prefix apps/miniprogram` | 类型检查、223项测试、工程检查通过，0失败/跳过 |
| `node apps/miniprogram/scripts/check-wechat.cjs "D:/develop/微信web开发者工具"` | 28份WXML、30份WXSS编译通过 |
| `npm run check --prefix apps/admin` | 76项Vitest、类型检查、生产构建通过 |
| 超过50条结果 | 帖子和商品分别用60条回归数据验证追加；包含失败重试、独立游标和旧请求失效 |
| 真实HTTP及MySQL | 正常本地后端，两个分类均以size=1验证真实下一页，无新增测试商品 |
| 微信开发者工具 | 8项实际运行检查通过：确认后搜索、清空、切换及图片续期等 |
| 小程序自然过期 | 保持商品详情66秒：原图片URL返回401，当前URL返回200；reload为0，标题和点赞不变 |
| 后台真实Chromium | 16项检查通过；商品抽屉停留66秒后旧URL401、新URL200，全屏预览正常 |
| 后台布局 | 帖子、评论、商品、举报、用户五页在1366/1440均无横向溢出；已渲染图片无加载失败，脚本异常0 |

新增回归先观察失败再修复。最后新增的文本保护和并发替换回归也实际复现失败后修复。Java未改，本轮未重跑此前324项后端测试，不将历史结果计为本轮通过。

本机MySQL、Redis及正常后端已恢复，健康检查UP、Redis PONG。仅检查微信配置是否存在，不打印密钥。后台浏览器仅为本地开发证书忽略证书错误，生产配置未改变。

## 文件与证据

小程序修改：`backend/pages.ts`、`backend/adapters.ts`、`backend/preview.ts`、`backend/service.ts`、`pages/search/index.wxml`、`pages/search/index.wxss`；新增`backend/image-urls.ts`、`tests/search-pagination.test.cjs`、`tests/image-renewal.test.cjs`。路径均位于`apps/miniprogram/`，业务源码在其`miniprogram/`下。

后台修改：`src/services/admin-http.ts`、`src/services/admin-adapters.ts`，以及`PostManagementView.vue`、`CommentManagementView.vue`、`ProductManagementView.vue`、`ReportReviewView.vue`、`UserManagementView.vue`；新增`src/services/admin-images.ts`、`src/components/content/RenewingImage.vue`、`tests/image-access.test.ts`。路径均位于`apps/admin/`。

证据目录：`.local/prelaunch-fixes-20261031/`。

- `native-check.json`：8项通过；起初商品关键词不足多条的覆盖缺口原样保留，随后用`product-search-http.json`补验真实商品游标。
- `browser-check.json`：16项通过，浏览器脚本异常0。
- `miniprogram-check.log`、`admin-check.log`：检查完整输出。
- `products-renewed-1440.png`：唯一新增截图，图片续期后的真实后台商品详情。
- `before-09aa0c0ca2a2464ba134b1ea1c3ad16b/`：修改前源码与Git状态。

当前真实数据较少，60条分页属于自动化测试，不能称为60条生产数据验收。本轮未做真机、软键盘、生产HTTPS/WSS、OSS验收；后台原有大包提醒保留。旧收藏和现有业务数据没有删除或转换。

下一步确认OSS的Bucket地域、名称、访问域名和权限方案。凭据仅在本机或服务端配置，保留旧本地图片，再单独实施OSS接入；页面与交互优化在其后执行。

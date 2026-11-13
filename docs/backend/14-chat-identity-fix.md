# 聊天头像与昵称修复（2026-10-26）

## 原因与修改

聊天模板仅显示顶栏的对方昵称，没有在消息旁显示双方昵称。本人头像取自登录时的缓存，签名图片地址过期后加载失败，没有默认图兜底。

本次真实检查：旧页面本人及对方头像地址均返回401；同一真实登录账号重新读取 `/me` 后，头像地址返回200。昵称与头像在数据库中存在，没有丢失。

- 两个聊天入口的消息气泡上方显示发送者昵称，左侧显示对方头像，右侧显示本人头像；失败待重试消息也保留本人身份。
- 聊天刷新时通过既有 `/me` 接口获取当前昵称、头像，避免一直使用旧图片签名。资料读取不触发Token会话变更通知，防止WebSocket重复重连。
- 个人资料暂时读取失败仍能查看聊天历史，使用缓存昵称与默认头像。头像加载失败使用统一默认图，不重复请求或循环重试。
- 长昵称单行省略，保留气泡、输入栏、商品卡片、发送及已读逻辑。没有修改后端、管理后台、业务数据或用户资料。

## 验证

- 新增6项回归，先观察失败再实施；小程序全部186项测试通过，TypeScript与工程引用检查通过。
- 微信官方离线编译：28个WXML、30个WXSS通过。
- 重新加载微信开发者工具后，真实聊天页：2条消息（包含本人1条），双方昵称绑定存在；两个头像的真实HTTP请求均200。
- 实际渲染节点：2个昵称节点高度均18px，2个头像节点均35×35px；聊天加载正常，消息记录保留。
- 截图接口未返回图片，没有生成或使用替代图冒充模拟器截图。检查证据在忽略目录 `.local/chat-identity/`，不包含Token、登录code或图片签名地址。

## 修改文件

- `apps/miniprogram/miniprogram/backend/pages.ts`
- `apps/miniprogram/miniprogram/pages/contact-chat/index.ts`、`index.wxml`、`index.wxss`
- `apps/miniprogram/miniprogram/pages/market-chat/index.ts`、`index.wxml`、`index.wxss`
- `apps/miniprogram/tests/chat-identity.test.cjs`

未执行Git提交、push、reset或clean。

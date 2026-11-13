# 个人主页顶部头像修复（2026-10-26）

问题：个人主页顶部显示默认图，但同页帖子作者头像正常。

原因：`DiscoveryService.profile()` 将公开用户资料的 `avatar` 硬编码为 `null`。不是用户资料丢失，也不是本次截图中的登录失效。

修改：注入已有 `MediaService`，与帖子、会话共用 `avatars()` 投影，返回同一个头像资源的有效访问地址。没有改页面样式、修改用户资料或增加接口，也没有改变匿名身份保护。

新增真实MySQL事务回归：未设置头像时为null；绑定头像后公开资料与帖子作者头像ID一致且返回授权地址；资源软删除后不再返回；公开资料不泄露微信openid。测试数据均回滚。

验证：

- 后端编译、打包及全部321项测试通过（138基础/单元 + 183 MySQL集成）。
- 已重启本地正式开发服务，保留原数据库及JWT密钥配置。
- 真实公开资料接口返回头像，与同用户帖子作者的头像ID一致，图片请求200。
- 微信开发者工具原生个人主页加载正常，顶部头像使用实际资源，渲染节点85×85px，页面使用的图片请求200。
- 小程序及管理后台代码未修改，没有清理业务数据或执行Git提交。

修改文件：`apps/server/src/main/java/cn/xinkelink/content/DiscoveryService.java`、`apps/server/src/test/java/cn/xinkelink/content/DiscoveryServiceIT.java`。证据保存在忽略目录 `.local/profile-avatar/`，不记录图片签名、Token或微信密钥。

# Day 5 管理API、双端接入与整体验收实施计划

依据docs/backend/01～09及用户直接连续实施授权。复用当前工作区，480个已有文件摘要已保存.local/backend-day5/before-files.json，不创建丢失未提交内容的检出，不提交/推送/重置/清理Git，不部署生产。

## 目标与约束

- 沿用Java21/Boot4.1.1/MySQL8.4、原生小程序和Vue3后台；不重设计UI，不引入框架。
- 补齐既有110个HTTP契约剩余30方法，图片avatar用途接入；不重写已完成80方法/消息协议。
- 正式页面默认真实API。测试可显式选择受控fixture，不得请求失败回Mock、任意凭据登录或伪造成功。
- 微信AppID/AppSecret及生产HTTPS/WSS缺口只记录，不把测试适配器当真实微信。密钥不入仓库。
- Redis仅检查和安全修复本机开发环境；失败不放宽Day2登录限流，不改用其他产品冒充Redis7。
- 审计成功/业务拒绝/执行异常三结果，事务提交后独立追加，写失败不得改变原业务状态/返回/异常，白名单字段不含正文、理由、密码、Token或配置值。

## 任务与目录边界

1. 审查01～09、代码/前端差异和Git保留基线，复核284测试。
2. admin/**：D01/AP01～05/AC01～04/AM01～04/AU01～05共19方法，复用既有权限/领域事实，真实分页/统计/事务批量。
3. audit/**与settings/**：AL01～03/AS01～03；统一服务命令审计、只读安全投影/CSV、持久配置且configuration_only。
4. profile/**及media：U05/U10～13共5方法，avatar用途与授权；学号只本机，身份自填未核实，旧图片/证据引用安全。
5. apps/admin/**：统一HTTP/认证/单飞刷新、Pinia真实数据与全页面命令、异常状态；access仅内存，refresh Secure HttpOnly同源HTTPS代理。
6. apps/miniprogram/**：request/session/upload/WS、DTO适配和页面async接入；真实ID/金额/分类/水位、明确错误、本机历史/学号边界。
7. 环境与验收：Redis/HTTPS本机可用性、测试源码可替换微信适配器、随机隔离测试账号、真实HTTP/WS及浏览器流程，双端类型/构建/测试、完整后端回归和10总结。

并行实现严格按目录分工；协调者单独编辑共享配置、审计标注的既有服务、媒体与总体验收。各任务先RED后GREEN，隔离构建目录，最后统一回归。公共审计注解AuditAction(module,action,targetType,fields)标记@Transactional管理命令；审计包装必须观察事务代理的最终结果。

## 验证与交付

- Java真实MySQL/HTTP测试包括剩余管理API、普通用户拒绝、版本/批量回滚、审计敏感过滤/失败隔离、资料和头像；保留原284项。
- 小程序check、管理端typecheck/test/build/Playwright；明确网络mock、测试微信适配器、本地真实HTTP、真实微信/真机和生产WSS的区别。
- 覆盖发帖评论赞藏、商品咨询、举报处置、公告收件、禁言阻止写入闭环，不以单元通过代替端到端。
- docs/backend/10-day5-integration.md列真实接通矩阵、实际命令结果、修改文件、仍为Mock的功能、缺口和风险。只清理本轮核实的随机测试夹具，不删除已有业务数据。

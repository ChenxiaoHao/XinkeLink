# 微信换码协议核对记录

核对日期：2026-10-24。来源为微信官方[小程序登录凭证校验](https://developers.weixin.qq.com/miniprogram/dev/server/API/user-login/api_code2session.html)。旧的 `OpenApiDoc/user-login/code2Session.html` 当前展示接口目录，目录链接指向上述新版页面。

浏览工具无法读取该站点；使用本机只读 HTTP 请求读取官方文档页面，返回 HTTP 200，正文包括以下协议。没有请求 `api.weixin.qq.com`，没有使用真实 code 或真实密钥。

- 服务端以 GET 请求固定 `https://api.weixin.qq.com/sns/jscode2session`，没有请求体。
- 查询参数为服务端配置的 `appid`、`secret`，小程序 `wx.login` 取得的 `js_code`，以及固定的 `grant_type=authorization_code`。
- 成功身份字段是字符串 `openid`。`session_key` 是会话密钥，本项目首版不保存、不返回、不记录；`unionid` 不用于首版跨应用合并身份。
- 官方将 `errcode` 定义为 number，失败时返回；成功示例也包含 `errcode:0`。适配器接受无 `errcode` 或整数零，并拒绝非零错误及异常字段类型。
- 官方本接口错误表列出 `-1`（系统繁忙）、`40029`（code 无效）、`40226`（高风险用户登录拦截）、`45011`（调用频率限制）。适配器将已知和未知非零错误统一映射为本平台 `WECHAT_LOGIN_FAILED`，不向客户端透传 `errmsg`。

本地验证仅使用 `MockRestServiceServer` 截获 `RestClient` 请求，覆盖固定目标、参数编码、身份输出、HTTP 非成功状态、超时、微信业务错误、空身份及非法 JSON；它证明本项目的请求与错误处理行为，不证明微信平台已经接入成功。

真实联调仍未验证：真实开发账号 AppID/AppSecret 的权限与有效性、真实 `wx.login` code 换码、微信平台风险及频率策略、部署网络/TLS/超时行为、真机登录和跨端用户会话。实际联调必须使用部署环境注入的凭据与新取得的 code，不能把模拟响应当作真实验收证据。

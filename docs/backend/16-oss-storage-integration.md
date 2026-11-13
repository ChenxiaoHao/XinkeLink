# OSS图片存储接入与验证

日期：2026-10-31。开发环境接入完成；未部署生产。单线执行，无子agent，未提交、推送、重置或清理Git，未批量迁移或删除旧业务图片。

## 当前状态

- 开发后端已使用OSS，健康检查UP。用户环境变量`XINKELINK_IMAGE_STORAGE=oss`已保存。
- Bucket：`xinkelink-images-dev-7316`；地域：`cn-beijing`；Endpoint：`https://oss-cn-beijing.aliyuncs.com`。
- 新图写入私有桶，存储键为`oss/<随机UUID>.png`；旧UUID键继续读取原本地目录。云端失败不会尝试本地同名文件。
- 没有数据库迁移或新增表，仍为Flyway V1～V6。前端、API字段、短期授权和图片访问权限不变；客户端继续通过后端读取图片，不持有OSS密钥。
- 共用原图片安全检查：实际输入/输出最多10MB，PNG/JPEG/GIF签名、尺寸及像素限制，重新编码PNG以剔除EXIF、定位与附加内容。旧测试保留。
- SDK为`aliyun-sdk-oss:3.18.5`，V4签名，Java21补充`jaxb-api:2.3.1`。客户端有连接/读取超时与生命周期关闭；随机对象键禁止覆盖。
- 供应商完整日志关闭，只记录经过格式限制的错误码，不传播完整供应商异常或请求头。

参考：[SDK版本](https://github.com/aliyun/aliyun-oss-java-sdk/releases)、[Java SDK](https://help.aliyun.com/en/oss/developer-reference/oss-java-sdk/)、[RAM策略](https://help.aliyun.com/zh/oss/user-guide/ram-policy/)。

## 配置与权限

用户环境变量：`XINKELINK_IMAGE_STORAGE`、`XINKELINK_OSS_BUCKET`、`XINKELINK_OSS_REGION`、`XINKELINK_OSS_ENDPOINT`、`XINKELINK_OSS_ACCESS_KEY_ID`、`XINKELINK_OSS_ACCESS_KEY_SECRET`。实际密钥不写入项目、日志或文档。

`configure-oss-dev.ps1`支持Windows PowerShell5.1，隐藏输入，`-CheckOnly`只检查存在性。RAM用户`xinkelink-oss-dev`的策略只对本开发桶对象授权`PutObject/GetObject/DeleteObject`；不含其他桶访问、删桶或账号管理权限。

第一次云端上传被`AccessDenied/oss:PutObject`拒绝，用户补齐专用策略授权后通过。普通构建及回归强制本地存储，并清除构建子进程的云凭据；显式云验证单独运行。

## 本轮验证

| 检查 | 实际结果 |
| --- | --- |
| `scripts/verify-backend.ps1 -Database` | Java21编译打包；150项单元测试+186项真实MySQL集成测试=336项，0失败/错误/跳过 |
| 新增存储测试 | 12项，覆盖校验重编码、旧图、路径限制、流关闭、超限、脱敏、适配器选择及错误配置；先观察缺少实现时8项失败，再实现通过 |
| `scripts/verify-oss-dev.ps1` | 真实上传、回读、像素比对成功；匿名403；测试对象删除并验证不存在 |
| 真实HTTP/MySQL及既有微信会话 | 10项通过：上传201、数据库OSS键、授权及图片200、匿名拒绝、旧图读取、详情和删除后旧授权失效 |
| 微信工具真实异步API | `wx.uploadFile`201，授权200，`wx.request`图片读取200；验证图片删除成功 |
| 小程序`npm run check` | 类型检查、223项测试、工程检查通过 |
| 官方编译 | 28份WXML、30份WXSS成功 |
| 后台`npm run check` | 76项Vitest、类型检查、生产构建成功；原有大包提醒保留 |
| 日志扫描 | 当前运行stdout/stderr及最终SDK日志共3份，实际密钥字面值命中0 |

数量依据本次Maven执行输出；2026-10-24旧失败XML保留，不计入本次。独立Java检查的本机回环初始化错误已用项目既有`jdk.net.unixdomain.tmpdir`解决，没有放宽TLS。

自动化最初在evaluate中注册异步回调未能正确收取结果；改用真实异步callWxMethod后通过。失败记录保留。其留下的一张1×1验证临时图按所有者、时间和已知验证图哈希确认后通过正常接口清理；未清理其他业务图。剩余云端temporary元数据为0，测试元数据保留deleted历史。

证据目录`.local/oss-integration-20261031/`：`red-tests.log`、`storage-tests.log`、`final-backend-check.log`、`final-package.log`、`live-oss-final.log`、`http-check.json`、`native-upload-method.json`、`cleanup.json`、`log-safety.json`及两端检查日志。之前失败日志不改成成功；首份临时日志的供应商大段诊断已移除，实际密钥字面值检查0命中。

## 启动与恢复

配置脚本兼容5.1；后端启动/完整检查需要PowerShell7.4+。当前用户终端找不到pwsh，本机可使用Codex已有主机（应用更新后路径可能变化）：

```powershell
$taskPwsh = 'C:\Users\chenxiaohao\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe'
```

按原恢复指南启动MySQL和Redis，并设置原JWT、微信、本地图片目录和后台Origin；只在确认本项目后端已正常停止后启动：

```powershell
Set-Location D:\CODE\XinKeLink
$env:XINKELINK_IMAGE_STORAGE = 'oss'
$env:XINKELINK_JWT_SECRET_BASE64 = (Get-Content .local/backend-day5/private/jwt.key -Raw).Trim()
$env:XINKELINK_IMAGE_DIRECTORY = 'D:/CODE/XinKeLink/.local/backend-day5/images'
$env:XINKELINK_ADMIN_ALLOWED_ORIGINS = 'https://localhost:5173'
$env:XINKELINK_REDIS_PORT = '16379'
& $taskPwsh -NoProfile -File scripts/start-backend-dev.ps1 -Port 18081 -Profiles 'db,auth,redis' -Build
```

当前服务已运行，不重复启动。密钥轮换后需重启后端。完整回归运行`verify-backend.ps1 -Database`；显式云检查运行`verify-oss-dev.ps1`，只创建和清理本次随机对象。

旧本地目录不能删除。以后备份必须包含MySQL、旧本地图片和OSS对象；SQL与本地目录不足以恢复云图。本轮没有配置云定时备份或生产灾备。业务产生云图后不能简单改回local作为完整回滚：本地适配器不读取云图，应保留云读取能力并单独制定回退方案。

## 修改文件

- 新增`apps/server/src/main/java/cn/xinkelink/storage/`下的`ImageSanitizer.java`、`OssImageStorage.java`、`OssStorageConfiguration.java`。
- 更新同目录`ImageStorage.java`、`LocalImageStorage.java`，以及`apps/server/pom.xml`、`apps/server/.env.example`、`apps/server/src/main/resources/application-auth.yml`。
- 新增`apps/server/src/test/java/cn/xinkelink/storage/`下的`OssImageStorageTest.java`、`OssStorageConfigurationTest.java`、显式运行的`OssStorageLiveCheck.java`。
- 新增配置/验证脚本`configure-oss-dev.ps1`、`verify-oss-dev.ps1`；更新`start-backend-dev.ps1`、`verify-backend.ps1`。
- 本报告及架构、冻结指南的后续说明。

没有修改两端前端源码，没有发布测试帖子/商品或修改用户资料。验证对象已清理，旧图未迁移，Bucket暂时没有新业务图片属于正常。页面交互优化在后续进行；真机相册/相机完整发布、生产HTTPS/WSS、备份与正式部署仍需单独验收。

# 底部五栏导航 P1 修复与回归记录

日期：2026-10-22。范围仅为底部导航布局、圆形发布按钮和该组件树的主题变量作用域，不开发论坛内容或其他业务。

## 原因

1. app.json 启用了 style:v2。本机运行的 3.17.5 基础库中，wx-button:not([size=mini]) 为普通按钮设置 width:184px。其选择器优先级高于原 .tab-item / .publish-button，叠加原 flex-shrink:0，使后续入口移出屏幕，发布按钮也不能保持圆形。
2. 公共主题变量原本只声明在 page 上；独立自定义 TabBar 不在该 page 继承链内，导致按钮背景、导航背景等 var() 颜色无法正常解析。

修复前已通过真实微信开发者工具截图复现“仅前两个入口正常、加号在右侧、联系和我的不可见”。同时读取了本机基础库中的实际按钮样式，不只依据离线逻辑推断。

## 修改文件

| 文件 | 修改 |
| --- | --- |
| apps/miniprogram/miniprogram/custom-tab-bar/index.wxml | 五个视觉位置使用独立 view 槽位；增加 xlk-theme 根容器，同时覆盖导航和原有发布面板 |
| apps/miniprogram/miniprogram/custom-tab-bar/index.wxss | 等宽 flex 槽位、min-width:0；提高按钮样式优先级；显式限定发布按钮宽高与缩放；保留底部安全区并避免裁切；导入公共变量 |
| apps/miniprogram/miniprogram/styles/tokens.wxss | 为同一套变量增加 .xlk-theme 作用域，所有变量数值保持原样 |

组件逻辑、四个页面、公共头部、发布面板源码和业务接口未修改。发布按钮使用现有主色变量 #6489B8。

## 真实模拟器验证

环境：微信开发者工具 Stable v2.01.2510290，实际本地基础库 3.17.5。

CLI 服务端口仍关闭。本次通过已运行的 XinkeLink 工具窗口操作并进行实际屏幕截图，没有使用 HTML 仿制图或离线编译图替代模拟器。

| 实际机型 | 逻辑尺寸 | 验证结果 |
| --- | --- | --- |
| iPhone 15 Pro Max | 430×932 | 五个位置可见、横向均衡，发布按钮雾蓝且为圆形，底部安全区正常 |
| iPhone 12/13 mini | 375×812 | 五栏与圆形按钮完整显示，无右侧裁切，底部安全区正常 |

初始工具栏机型名称被截断为“iPhone 15 Pro …”；展开菜单后确认实际为 Pro Max。该机型不记为 iPhone 15 Pro 393×852 的单独测试，真机也未测试。

在 Pro Max 模拟器实际点击验证了论坛、交易、联系和我的切换，以及中央加号打开“发布帖子 / 发布闲置”面板。已检查面板关闭后的导航画面。发布面板其他视觉细节不作为本次导航修复的视觉定稿。

截图均保存在本地忽略目录 .local/tabbar-fix/：

- before-ide.png：原始缺陷，包含开发者工具界面。
- iphone15promax-tabbar.png：修复后的五栏导航。
- iphone15promax-publish.png：点击中央按钮后出现的发布面板。
- iphone15pro-contact.png / iphone15pro-profile.png / iphone15pro-market.png：实际页面切换记录；这些旧文件名中的 pro 为截断名称的历史缩写，实际机型均为 Pro Max。
- iphone-mini-tabbar.png / iphone-mini-ide.png：375×812 机型的实际画面。

## 已执行检查

~~~powershell
Set-Location D:\CODE\XinKeLink\apps\miniprogram
npm.cmd run check
node scripts/check-wechat.cjs 'D:\develop\微信web开发者工具'
~~~

实际结果：TypeScript 通过；14 项组件行为测试全部通过；工程文件检查通过；7 个 WXML 和 9 个 WXSS 官方离线语法检查通过。最后一轮命令输出保存于 .local/tabbar-fix/check.log。

原有 Node 测试验证组件行为，不渲染 WXSS，无法发现基础库样式优先级与页面外变量继承的问题。本次使用真实模拟器补充这些回归检查，没有新增只匹配源码文字的测试。

最终只读审查未发现三个源码文件中的新增正确性或回归问题。

## 后续导航回归步骤

1. 普通编译后，在 375px 和 430px 宽机型查看完整五栏。
2. 确认发布圆形在正中央，右侧两个入口完整，无横向溢出。
3. 逐一切换四个 tab，检查正确的选中状态。
4. 从不同 tab 点击中央发布按钮，检查面板仍可出现。
5. 关闭面板后，确认当前 tab 与底部安全区不受影响。

本轮到此结束，不进入下一阶段。

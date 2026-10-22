# 信科 Link 品牌资源接入

日期：2026-10-22。依据用户提供的品牌展示板，重制其中的独立「学士帽 + Link 链环 + 右上星光」图形。运行资源不含品牌展示板、文字或宣传页。本轮只接入论坛首页。

## 资源

| 文件 | 用途 | 实际规格 |
| --- | --- | --- |
| apps/miniprogram/miniprogram/assets/brand/xinkelink-mark.png | 论坛头部透明图形 | 256 × 256，RGBA，31,670 字节；背景和链环空隙透明 |
| apps/miniprogram/miniprogram/assets/brand/xinkelink-square.png | 小程序方形图标资源 | 512 × 512，226,702 字节；雾蓝底白色图形，全部像素不透明 |
| docs/brand/xinkelink-mark-master.png | 透明图形 PNG 母版 | 高分辨率原始输出，未放入运行代码包 |
| docs/brand/xinkelink-square-master.png | 方形 PNG 母版 | 高分辨率原始输出，未放入运行代码包 |
| docs/references/xinkelink-brand-board.png | 用户品牌展示板存档 | 原图保留，仅用于视觉参考 |

使用内置 image_gen 工具重制独立图形，没有使用 CLI/API fallback。资源为 PNG，没有把它们描述成原始矢量文件。透明版采用更适合小尺寸的雾蓝色实心图形，保留展示板的帽子、链环和单颗星光。现有主题变量 #6489B8 未修改。

## 接入与修改清单

修改了四个现有文件：

- apps/miniprogram/miniprogram/components/page-header/index.ts：新增默认值为空的可选 logoSrc 属性。
- apps/miniprogram/miniprogram/components/page-header/index.wxml：传入 logoSrc 时显示品牌图片；没有传入时保留原图标分支。
- apps/miniprogram/miniprogram/components/page-header/index.wxss：新增仅对品牌分支生效的透明底和图片尺寸样式。
- apps/miniprogram/miniprogram/pages/forum/index.wxml：仅为原 page-header 传入 /assets/brand/xinkelink-mark.png。

新增七个文件：两张运行 PNG、两张母版、用户展示板副本、docs/brand/export-assets.ps1、本说明。

标题「信科 Link」、Forum 副标题、头部行高、胶囊避让、文字字号、内边距和图标外层 56rpx 占位均保留。品牌图片用 aspectFit 等比显示；没有改变其他页面的默认图标。交易页的实际模拟器画面仍显示原来的学士帽圆形图标。

基于本轮开始前 132 个文件的 SHA256 对照，只改动上述四个现有文件，未删除文件。论坛 TypeScript、模拟数据、帖子组件、分页/点赞/收藏/分享逻辑、五栏导航、发布弹层、其他页面源码、主题变量、AppID 配置、PC 后台、Java 服务端与原 README 均与基准一致。完整对照结果：.local/brand-logo/file-changes.json。

## 导出方法

在仓库根目录执行：

~~~powershell
Set-Location D:\CODE\XinKeLink
.\docs\brand\export-assets.ps1
~~~

导出脚本只做等比缩放、透明图形可见边界居中和安全留白。透明版按约 8% 留白导出，忽略几乎不可见的 alpha 噪点测量边界；未重画图形。方形版合成到不透明雾蓝底，避免重采样导致半透明边角。没有新增 npm 依赖或更改项目构建方式。

## 实际验证

- 最终透明 PNG 四角 alpha 为 0；alpha > 32 的图形边界为 (24,20) 至 (231,234)，没有贴边或裁切。
- 最终方形 PNG 的 262,144 个像素全部 alpha = 255。
- 已将最终导出资源按 24、28、32、48px 显示于白色和 #E9F1F8 浅色底上检查，帽子、链环和星光可辨认；方形白色图形也检查了相应小尺寸。预览表是离线资源检查，不是模拟器截图。
- 微信开发者工具 Stable v2.01.2510290、基础库 3.17.5 实际运行并截图。观察了 iPhone 15 Pro Max（430 × 932）与 iPhone 12/13 mini（375 × 812）的新品牌图形；mini 中原图标占位 56rpx 对应 28px。
- iPhone 15 Pro Max 最终资源重新编译后的截图可见新图形、原文字、信息流和全部五栏导航。实际交易页仍显示旧图标，公共组件的默认分支正常。
- 运行小程序 npm run check：TypeScript、38 项原有测试、工程文件和引用检查全部通过。
- 官方 wcc.exe / wcsc.exe 离线编译：8 个 WXML、10 个 WXSS 通过。
- 运行根目录 scripts/verify.ps1：后台 4 项原有测试、Vue 类型检查、Vite 构建、Java 2 项 HTTP 测试及 Maven verify 通过。日志：.local/brand-logo/verify-all.log。
- 微信工具仍提示原公共组件中的旧选择器警告；未因本轮任务修改无关规则。没有把离线检查作为模拟器验收，也没有执行真机预览或微信公众平台图标上传。

本地证据：

| 内容 | 路径 |
| --- | --- |
| 最终论坛模拟器截图 | .local/brand-logo/forum-iphone15promax.png |
| mini 模拟器完整画面 | .local/brand-logo/mini-ide.png |
| mini 画面中的手机区域 | .local/brand-logo/forum-iphone-mini.png |
| 交易页保留旧图标 | .local/brand-logo/market-unchanged.png |
| 最终资源小尺寸检查表 | .local/brand-logo/small-size-check.png |
| PNG 尺寸、alpha、边界检测 | .local/brand-logo/asset-metadata.json |

这些截图来自实际微信开发者工具或明确标注的离线资源检查，没有用生成图冒充运行截图。mini 手机区域由该次完整 IDE 截图裁出。

## 最终图形生成提示词

以下是本轮采用的提示词，供后续追溯；母版已保存在工作区，重新导出不需要再次调用图像工具。

透明图形（transparent_background=true；参考独立方形图形母版）：

~~~text
Use case: precise-object-edit / background-extraction. Edit target: the white XinkeLink symbol on the supplied blue square icon. Create its production transparent BLUE version. Preserve EXACTLY the existing white silhouette and its geometry: graduation cap, right vertical stem, two interlocking diagonal chain links below, and one four-point sparkle at upper right. Convert the white symbol into a clean FLAT SOLID mist blue #6489B8, fully opaque inside the shapes, on a genuinely transparent alpha background. All of the blue square background and all the spaces/holes inside the links must become fully transparent. Do not leave any of the square or any white backing. Clean professional vector-like edges with only narrow antialiasing, no semi-transparent fog, no colored cyan/green speckles, no halo, no glow, no texture, no glossy highlights, no gradient, no shadows. Keep every original contour and the white-symbol geometry unchanged. Output one square transparent PNG with the mark optically centered and even 12% transparent safe padding around its visible bounds. No lettering, no wordmark, no slogans, no panel, no mockup, no extra elements. It will be used at 28px, so clean substantial edges and clear chain-link negative space are critical.
~~~

方形图标（transparent_background=false；参考由用户展示板提取的独立图形）：

~~~text
Use case: precise-object-edit. Asset type: square WeChat mini-program app icon derived from the approved XinkeLink mark. Edit target: the attached isolated transparent blue mark. Preserve the EXACT geometry, proportions and arrangement of the mortarboard, right stem/tassel, two interlocking diagonal chain links, and the single four-point sparkle at upper right. Convert all visible parts of that mark to clean solid white. Place it centered on a completely opaque, flat mist-blue #6489B8 square background, with generous even safe padding of about 18% around the symbol. Output ONLY this single square icon asset. No wordmark, no text, no slogan, no extra sparkles, no strokes around the mark, no shadows, no 3D or gloss, no corner ornaments, no presentation board or mockup. The square color fills all four corners, without pre-rounded corners. Keep the internal chain holes and gaps the same blue as the background, preserving clear separation. Clean sharp antialiased edges suitable for small app icons.
~~~


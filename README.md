# GeoGebra Classic 5 for Android

基于本工作路径下 `geogebra` 项目（选择 **classic / Classic 5** 版本）构建的安卓应用。

应用完整复刻 GeoGebra Classic 5 功能：内置 **完整的 GeoGebra Classic 5 (web3d GWT) 离线应用包**，
运行在 Android `WebView` 中，不依赖网络即可使用代数区、绘图区、工具栏、菜单栏、CAS 等完整功能。

在完整 GeoGebra 功能之上，额外提供：

- **导出 SVG** —— 调用 GeoGebra `getSVGBase64` 导出当前绘图为 SVG 并保存。
- **导出 PNG** —— 调用 `getScreenshotBase64` 导出当前绘图为 PNG。
- **打开 / 保存 .ggb** —— 支持从系统文件选择器打开 `.ggb` 文件、保存当前作图。
- **当前绘图 → TikZ 代码块** —— 复用 `D:\DSH\WD\ggbForAndroid\obsidian-svg2tikz` 的
  `SvgToTikzConverter` 转换引擎，把当前所绘图像导出为可直接粘贴到 Obsidian / TikZJax 的
  `~~~tikz` 代码块（也支持 standalone / 仅 tikzpicture / 仅路径代码模式）。

---

## 目录结构

```
GeoGebraClassic5/
├─ app/
│  ├─ build.gradle.kts
│  ├─ proguard-rules.pro
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ assets/
│     │  ├─ ggb.html              # GeoGebra Classic 5 宿主页 + JS 桥
│     │  ├─ deployggb.js          # GeoGebra 官方 applet 加载器
│     │  ├─ svg2tikz.js           # obsidian-svg2tikz 的 SvgToTikzConverter
│     │  ├─ web3d/                # GeoGebra Classic 5 离线 web3d GWT 应用
│     │  └─ css/                  # 配套样式
│     ├─ java/com/ggb/classic5/
│     │  └─ MainActivity.java     # WebView 宿主、资源拦截、SVG/PNG/GGB/TikZ 导出
│     └─ res/
│        ├─ layout/activity_main.xml
│        └─ values/strings.xml
├─ build.gradle.kts
├─ settings.gradle.kts
├─ gradle.properties
└─ gradle/wrapper/...
```

## 构建

要求：JDK 17+、Android SDK（`compileSdk 34`、`build-tools 34.0.0`）。

```bat
set JAVA_HOME=<JDK 17 路径>
set ANDROID_HOME=<Android SDK 路径>
gradlew.bat assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

安装：

```bat
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

顶栏按钮：

| 按钮 | 功能 |
|------|------|
| 返回 | 从打开的搜索/材料页返回搜索（若搜索已打开则关闭搜索） |
| 菜单 | 打开 GeoGebra 菜单（三个横线） |
| 搜索 | 打开 GeoGebra 搜索 |
| 新建 | 新建作图（`ggbApplet.reset()`） |
| 打开 | 从系统文件选择器打开 `.ggb` 文件 |
| 保存 | 将当前作图保存为 `.ggb` |
| SVG  | 将当前绘图导出为 SVG 文件 |
| PNG  | 将当前绘图导出为 PNG 文件 |
| TikZ | 将当前绘图转换为 TikZ 代码块（弹出预览，可复制/保存） |
| 设置 | 界面语言、TikZ 输出模式/单位/缩放/小数位/箭头等 |

撤销/重做保持 GeoGebra 原始箭头图标，固定在右下角缩放按钮组（home 按钮）上方。

TikZ 默认输出为 TikZJax / Obsidian 代码块格式：

````markdown
~~~tikz
\begin{document}
...
\end{document}

~~~
````

## 实现说明

- `MainActivity` 用 `shouldInterceptRequest` 拦截 `https://appassets.androidplatform.net/assets/*`，
  直接从 APK assets 提供 GeoGebra 应用文件。页面因此拥有真实的 https origin，GWT 的脚本加载、
  语言文件 XHR、deferredjs 分片加载均正常工作，且全程离线。
- 页面 `ggb.html` 按 Classic 5 视图配置（代数区 + 绘图区）部署 `GGBApplet`，与上游 GeoGebra
  `source/web/web/src/main/resources/org/geogebra/web/resources/war/full.html` 一致。
- `svg2tikz.js` 提取自 `obsidian-svg2tikz` 的 `SvgToTikzConverter` 类（同样的 path/rect/circle/
  ellipse/line/poly/text、样式、变换、颜色、箭头处理逻辑），在 WebView 内直接解析 GeoGebra 返回的
  SVG DOM 并生成 TikZ。

## 许可

GeoGebra 应用包版权归 GeoGebra 所有，按其许可证使用（见 `assets` 内随附许可文件与
`https://www.geogebra.org/license`）。`obsidian-svg2tikz` 的转换代码为 GNU GPL。

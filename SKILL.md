# GeoGebra Classic 5 Android — 脚本/操作说明书（LLM Skill）

本文件供后续接入大语言模型（LLM）时使用。LLM 可依据本文件生成 GGB 脚本、
JavaScript 脚本或调用桥接函数，完成作图、计算、导出与文件控制。

---

## 1. 应用架构

```
Android 原生层 (MainActivity.java)
  ├─ 顶栏按钮: 返回 / 菜单 / 搜索 / 新建 / 打开 / 保存 / 另存为 / SVG / PNG / TikZ / LaTeX / 脚本 / 设置
  ├─ WebView 加载 assets/ggb.html
  │    ├─ assets/deployggb.js + assets/web3d/   (离线 GeoGebra Classic 5)
  │    ├─ assets/svg2tikz.js                    (SVG -> TikZ 转换器)
  │    └─ ggb.html 注入的 JS 桥 (window.__ggb*)
  └─ JS 桥: window.ggbApi (GeoGebra 官方导出 API)
```

GeoGebra 官方导出 API 对象保存在 `window.ggbApi`（页面加载完成后也可在 JS 中直接使用）。
Android 侧通过 `webView.evaluateJavascript(...)` 调用 `window.__ggb*` 函数。

---

## 2. 可调用 JS 桥函数（Android 注入层）

这些函数定义在 `ggb.html`，Android 通过 `evaluateJavascript` 调用。

| 函数 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `window.__ggbIsReady()` | 无 | boolean | GeoGebra 是否就绪 |
| `window.__ggbGetSvg(cbId)` | callback id | 通过 AndroidBridge.onResult 回传 | 导出当前 SVG 文本 |
| `window.__ggbGetPng(cbId)` | callback id | 同上 | 导出当前 PNG base64 |
| `window.__ggbGetGgb(cbId)` | callback id | 同上 | 导出当前 .ggb base64 |
| `window.__ggbSetGgbBegin(cbId)` / `__ggbSetGgbChunk(cbId, chunk)` / `__ggbSetGgbEnd(cbId)` | 分块 base64 | 回调 | 载入 .ggb 文件 |
| `window.__ggbReset()` | 无 | boolean | 新建作图 |
| `window.__ggbEvalCommand(cmd)` | GeoGebra 命令字符串 | boolean | 执行单条 GeoGebra 命令 |
| `window.__ggbEvalLaTeX(latex)` | LaTeX 字符串 | boolean | 将常见 LaTeX 转为 GeoGebra 命令并执行 |
| `window.__ggbRunGgbScript(script)` | 多行 GeoGebra 命令（每行一条） | boolean | 逐行执行 GGB 脚本；至少执行一条返回 true |
| `window.__ggbGetTikz(cbId, settingsJson)` | callback id + TikZ 设置 JSON | 回调 | 当前绘图转 TikZ 代码块 |
| `window.__ggbBack()` | 无 | boolean | 应用内返回：搜索页关闭 / 材料页返回搜索 / 主页面无操作 |
| `window.__ggbClickRightButton(role)` | `"menu"` 或 `"search"` | boolean | 触发 GeoGebra 菜单/搜索 |
| `window.__ggbUndo()` / `window.__ggbRedo()` | 无 | boolean | 撤销 / 重做 |

Android 侧桥接回调：`AndroidBridge.onResult(cbId, ok, payload, error)`。

---

## 3. GeoGebra 官方导出 API（window.ggbApi）

以下为 Classic 5 (web3d) 构建中可用的主要方法。调用示例用 JS 表示。

### 3.1 作图与命令
```js
ggbApi.evalCommand("A=(1,2)");        // 创建点 A
ggbApi.evalCommand("B=(3,4)");        // 创建点 B
ggbApi.evalCommand("f(x)=x^2");       // 函数
ggbApi.evalCommand("c=Circle(A,2)");  // 圆
ggbApi.evalCommand("s=Segment(A,B)"); // 线段
ggbApi.evalCommand("SetVisible(A,false)"); // 隐藏对象
ggbApi.evalCommand("Delete(A)");      // 删除对象
ggbApi.setValue("a", 5);              // 设置数值
ggbApi.getValue("a");                 // 读取数值
ggbApi.getXML();                      // 获取当前作图 XML
ggbApi.setXML(xml);                   // 载入 XML
ggbApi.setBase64(b64, callback);      // 载入 .ggb
ggbApi.getBase64(callback);           // 导出 .ggb
```

### 3.2 视图与模式
```js
ggbApi.setMode(0);                     // 0=移动, 1=点, 2=线, 10=圆, 15=线段, 62=画笔...
ggbApi.getMode();
ggbApi.setPerspective("G");            // 绘图区; "AG" 代数+绘图
ggbApi.showToolBar(true/false);
ggbApi.reset();
ggbApi.undo();
ggbApi.redo();
ggbApi.setUndoPoint();
```

### 3.3 导出
```js
ggbApi.exportSVG(function(svgText){ ... });        // SVG 源码
ggbApi.getScreenshotBase64(function(b64){ ... });  // PNG base64
ggbApi.getBase64(function(b64){ ... });            // .ggb base64
```

### 3.4 常用工具模式号（EuclidianConstants）
| 模式 | 编号 | 模式 | 编号 |
|------|------|------|------|
| 移动 | 0 | 圆(两点) | 10 |
| 点 | 1 | 线段 | 15 |
| 直线 | 2 | 平移视图 | 40 |
| 垂线 | 3? | 放大 | 41 |
| 平行线 | 4? | 缩小 | 42 |
| 多边形 | 22? | 画笔 | 62 |
| 自由绘图 | 73 | | |

> 具体编号以 GeoGebra `EuclidianConstants` 为准，常用命令建议直接使用 `evalCommand`。

---

## 4. GGB 脚本规范（每行一条 GeoGebra 命令）

- 每行一条命令；空行与 `#` 开头的行为注释（脚本执行器会跳过空行）。
- 命令语法与 GeoGebra 代数输入完全一致。
- 对象名建议使用大写字母：`A`, `B`, `f`, `c`。
- 创建对象示例：
  ```
  A=(1,2)
  B=(3,4)
  s=Segment(A,B)
  c=Circle(A,2)
  f(x)=x^2
  SetColor(s,"red")
  ```

LLM 生成脚本时，应保证每行可独立被 `ggbApi.evalCommand` 执行。

---

## 5. JavaScript 脚本规范（在 WebView 内执行）

通过“脚本”按钮或 Android `evaluateJavascript` 执行。脚本运行在 `ggb.html` 页面上下文，
可直接使用 `window.ggbApi` 与 `window.__ggb*`。

约定：
- 使用 `window.ggbApi`，不要假设 `ggbApplet` 变量。
- 执行多个命令用 `ggbApi.evalCommand`。
- 导出操作使用回调，例如：
  ```js
  window.ggbApi.exportSVG(function(svg){ console.log(svg.length); });
  ```
- 读取状态：
  ```js
  var xml = window.ggbApi.getXML();
  var mode = window.ggbApi.getMode();
  ```
- 返回 JSON 可序列化的值给 Android：`return JSON.stringify(...)`。

---

## 6. LaTeX → GeoGebra 翻译规则

`window.__ggbEvalLaTeX` 支持以下常见 LaTeX 语法（不保证完整 LaTeX）：

| LaTeX | GeoGebra |
|-------|----------|
| `\frac{a}{b}` | `((a)/(b))` |
| `\sqrt{x}` | `sqrt(x)` |
| `\cdot`, `\times` | `*` |
| `\div` | `/` |
| `\le`, `\le` | `<=` |
| `\ge` | `>=` |
| `\ne` | `!=` |
| `\infty` | `infinity` |
| `\pi` | `pi` |
| `\theta` | `theta` |
| `\sin(x)` | `sin(x)` |
| `\{`, `\}` | `(`, `)` |

示例：`\frac{1}{2} + \sqrt{x}` → `((1)/(2)) + sqrt(x)`。

---

## 7. 文件保存/打开语义

- **打开 .ggb**：系统文件选择器，载入后记住该 URI；之后“保存”写回该文件。
- **保存**：若当前有已打开/已保存的 .ggb URI，直接写回；否则等价于“另存为”。
- **另存为**：始终弹出系统保存对话框，指定新文件名与位置；保存后将该文件记为当前文件。
- **新建**：清空当前文件 URI，之后“保存”会走“另存为”。

---

## 8. LLM 操作建议

- 需要作图：优先调用 `window.__ggbEvalCommand` 或 `window.__ggbRunGgbScript`。
- 需要导入/导出：使用 `__ggbSetGgbBegin/Chunk/End`（导入）与 `__ggbGetGgb`（导出）。
- 需要公式：使用 `window.__ggbEvalLaTeX`。
- 需要 TikZ：调用 `window.__ggbGetTikz`，返回 `~~~tikz` 代码块。
- 需要截图/保存：调用 `__ggbGetPng` / `__ggbGetSvg`。

---

## 9. 已知限制

- 文件保存依赖 Android Storage Access Framework（ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT）。
- JavaScript 脚本运行在 WebView 页面上下文，不能访问 Android 文件系统。
- `__ggbEvalLaTeX` 只覆盖常见 LaTeX 子集，复杂 LaTeX 请先转换为 GeoGebra 命令。
- 在绘图工具模式下双指缩放会临时切换到移动模式（避免误放点），缩放结束后恢复原工具。

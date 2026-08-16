# Third-Party Licenses

本项目包含以下第三方组件。请在使用、修改或再分发前阅读并遵守对应许可证。

---

## 1. GeoGebra Classic 5 (web3d / deployggb.js / css / language files)

- **来源**：GeoGebra Classic 5 官方 Web 版（`classic` / `web3d` GWT 构建）
- **版权**：Copyright © GeoGebra GmbH
- **位置**：`app/src/main/assets/web3d/`、`app/src/main/assets/deployggb.js`、`app/src/main/assets/css/`
- **许可证**：EUPL-1.2
- **附加说明**：GeoGebra 官方声明整体软件包**仅限非商业用途免费使用**。
- **官方许可页**：<https://www.geogebra.org/license>
- **上游源码**：<https://github.com/geogebra/geogebra>

> 本项目仅为 GeoGebra Classic 5 的 Android WebView 离线移植，不声称对
> GeoGebra 应用包本身拥有任何版权。再分发时请保留 GeoGebra 官方许可声明。

---

## 2. obsidian-svg2tikz (SvgToTikzConverter)

- **来源**：`obsidian-svg2tikz`
- **位置**：`app/src/main/assets/svg2tikz.js`（提取自该项目的 SVG → TikZ 转换器）
- **许可证**：GNU General Public License v3.0（GPL-3.0）
- **版权**：归 obsidian-svg2tikz 作者所有
- **完整文本**：见本仓库根目录 `LICENSE`

> 因本项目整体包含 GPL-3.0 的 svg2tikz 转换代码，本项目自身代码亦按
> GPL-3.0 发布；对应源代码在本仓库中公开。

---

## 3. canvas-to-svg.umd.min.js

- **来源**：GeoGebra 官方源码树中随附的浏览器 polyfill
- **位置**：`app/src/main/assets/web3d/js/canvas-to-svg.umd.min.js`
- **许可**：随 GeoGebra 软件包一并提供，遵守 GeoGebra 官方许可要求。

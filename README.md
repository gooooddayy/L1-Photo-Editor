# L1 图片编辑器

> 🖼️ 纯本地运行的浏览器图片编辑器 —— AI 抠图 · AI 消除 · 比例裁剪 · 多格式导出，**双击即用、断网可用、图片永不上传**。

**English**: A fully local, single-file browser image editor with AI background removal (SILUETA) and AI object removal (MI-GAN). Works offline from `file://`, zero backend, zero upload — your photos never leave your device.

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Offline](https://img.shields.io/badge/Offline-100%25-brightgreen)](#-离线使用)
[![No Upload](https://img.shields.io/badge/Privacy-No_Upload-blue)](#-隐私)
[![Engine](https://img.shields.io/badge/Engine-onnxruntime--web_1.16.3-orange)](https://onnxruntime.ai/docs/tutorials/web/)

**在线使用**：打开 <https://gooooddayy.github.io/L1-Photo-Editor/>（首次使用 AI 功能会自动下载模型并缓存，之后离线可用）

---

## ✨ 功能

| 模块 | 说明 |
|---|---|
| ✂️ 比例裁剪 | 原图 / 自由 / 1:1 / 16:9 / 9:16 / 4:3 / 3:4 / 16:10 / 10:16 / 3:2 / 2:3，等比盒钳位算法（13 万测例 0 失败） |
| 🔄 旋转镜像 | 逆时针 90° 循环旋转、水平镜像 |
| 🧩 马赛克 | 笔刷涂抹，快速拖动自动连线 |
| 🖌️ AI 抠图 | SILUETA 模型（42MB），一键去除背景，导出 PNG 保留透明 |
| 🧹 AI 消除 | MI-GAN 模型（28MB），涂抹要移除的物体/水印/文字，自动填补背景 |
| 🔤 文字 | 自定义颜色（默认红）/ 大小 / 加粗，可拖动后合并到图 |
| ↩️ 撤销 | 快照历史，支持连续撤销 |
| 📤 导出 | JPG / JPEG / PNG / WEBP / BMP / ICO（+AVIF 自动探测）；37 个预设尺寸 + 自定义宽高 + 锁定比例 + 限制体积（≤XX MB 自动降质） |
| 📱 响应式 | 桌面 / 手机 / 平板 / 异形屏（刘海屏 safe-area）自适应，触屏优化 |
| 🔒 隐私 | 纯前端本地处理，无任何上传、无统计、无第三方请求（离线时零网络活动） |

## 🚀 快速开始

### 方式一：在线使用
直接访问 **https://gooooddayy.github.io/L1-Photo-Editor/**，点「AI 抠图 / AI 移除」时自动下载模型（浏览器缓存，仅一次）。

### 方式二：完全离线
1. 下载仓库 ZIP（Code → Download ZIP）并解压
2. 双击 `index.html` —— 全部功能立即可用，包括 AI 功能（模型 base64 内置在 models 目录）

> AI 功能需要 `ort/` 与 `models/` 文件夹和 `index.html` 在同一目录。只复制单个 HTML 文件时 AI 功能会提示缺失。

## 🧠 模型说明

| 模型 | 用途 | 体积 | 来源 |
|---|---|---|---|
| SILUETA (`u2netp.onnx`) | 抠图 | 42MB | [rembg 官方蒸馏版](https://github.com/danielgatis/rembg)（u2net 精度的轻量实现） |
| MI-GAN (`migan_pipeline_v2.onnx`) | 消除 | 28MB | [andraniksargsyan/migan](https://huggingface.co/andraniksargsyan/migan)（inpaint-web 同款管线） |

- 模型加载顺序：**本地 models 目录 → 浏览器 IndexedDB 缓存 → hf-mirror.com 在线下载**；本仓库部署后优先从站点自身（同源）加载
- 推理引擎：onnxruntime-web 1.16.3（WASM SIMD），运行时经 Blob URL 注入实现 `file://` 离线
- 更换 / 更新模型后需清除浏览器站点数据（IndexedDB 缓存按版本号失效）

## 📐 技术架构

- **单文件 HTML**（约 64KB），零依赖、零构建
- `ort/ort-wasm.b64.js`：两个 WASM 变体 base64 化，运行时解码为 Blob URL 注入 `ort.env.wasm.wasmPaths`（绕开 Chromium data:URI 2MB 上限，实现 file:// 离线推理）
- `models/*.b64.js`：模型 base64 懒加载脚本，双击场景下无需任何网络
- 详见 [docs/技术方案与踩坑记录.md](docs/图片编辑器-技术方案与踩坑记录.md)（19 条实战踩坑）

## ❓ FAQ

**Q: 会被压缩画质吗？**
导出默认原始分辨率、原格式；质量滑杆可调，默认与原图一致。

**Q: AI 功能失败提示"文件夹缺失"？**
`index.html`、`ort/`、`models/` 必须同目录；在线使用则需允许站点下载模型。

**Q: 支持手机吗？**
支持。手机浏览器访问在线版即可，布局自适应，触屏涂抹/裁剪已优化。

**Q: 图片会上传吗？**
不会。全部处理在浏览器内完成，AI 推理也在本地 WASM 中运行。

## 📄 License

[MIT](LICENSE) · 模型版权遵循各自来源仓库许可

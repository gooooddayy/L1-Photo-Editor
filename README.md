# L1 图片编辑器

> 🖼️ **免费在线图片编辑器** —— AI 一键抠图 · AI 消除水印与多余物体 · 比例裁剪 · 多格式导出。**纯本地处理，图片永不上传，无水印，无需注册**。

**English**: A free, privacy-first online image editor with AI background removal, AI object/watermark removal (inpainting), ratio cropping and multi-format export. All processing runs 100% locally in your browser — no upload, no watermark, no sign-up, works offline.

[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Privacy](https://img.shields.io/badge/Privacy-本地处理_永不上传-blue)](#-隐私与合规)
[![Offline](https://img.shields.io/badge/Offline-100%25-brightgreen)](#-离线使用)
[![Engine](https://img.shields.io/badge/Engine-onnxruntime_1.16.3-orange)](https://onnxruntime.ai/)

| 平台 | 入口 |
|---|---|
| 🌐 **网页版**（Windows / macOS / Linux / Android / iOS 浏览器） | [在线使用 →](https://gooooddayy.github.io/L1-Photo-Editor/) |
| 📱 **安卓版 APK**（v1.0.0，Android 7.0+，arm64） | [GitHub Releases 下载 →](https://github.com/gooooddayy/L1-Photo-Editor/releases) |

---

## ✨ 产品功能

| 模块 | 说明 |
|---|---|
| 🖌️ **AI 一键抠图** | 自动识别画面主体（人像 / 宠物 / 物品 / 静物），一键去除背景，导出 PNG 保留透明通道 |
| 🧹 **AI 消除 / 去水印** | 涂抹想要移除的水印、文字、路人、杂物，AI 自动填补背景（图像修复） |
| ✂️ **比例裁剪** | 原图 / 自由 / 1:1 / 16:9 / 9:16 / 4:3 / 3:4 / 16:10 / 10:16 / 3:2 / 2:3，等比钳位算法（13 万组合测例 0 失败） |
| 🔄 旋转镜像 | 逆时针 90° 循环旋转、水平镜像 |
| 🧩 马赛克 | 笔刷涂抹，快速拖动自动连线 |
| 🔤 文字工具 | 自定义颜色 / 大小 / 加粗，可拖动摆放后合并到图片 |
| ↩️ 撤销 | 快照式历史，支持连续撤销 |
| 📤 **导出** | JPG / PNG / WEBP / BMP / ICO（+AVIF 自动探测）；37 个预设尺寸 + 自定义宽高 + 锁定比例 + 限制体积（≤XX MB 自动降质），导出前实时预估文件大小 |
| 📱 响应式 | 桌面 / 手机 / 平板 / 异形屏（刘海屏 safe-area）自适应，触屏优化；低内存设备自动切换轻量模型 |

## 🆚 与同类工具的差别

| | L1 图片编辑器 | 常见在线抠图工具 |
|---|---|---|
| 图片是否上传服务器 | ❌ **永不上传，全本地推理** | ✅ 通常需上传到云端处理 |
| 水印 | 无水印 | 免费档常带水印或限高清 |
| 注册登录 | 不需要 | 多数需要 |
| 断网可用 | ✅ 首次加载后完全离线 | ❌ |
| 隐私 | 图片不离开设备 | 图片经第三方服务器 |

## 🚀 快速开始

### 网页版
- **在线使用**：访问 <https://gooooddayy.github.io/L1-Photo-Editor/>，点「AI 抠图 / AI 消除」时自动下载模型（浏览器缓存，仅一次），之后离线可用
- **完全离线**：下载仓库 ZIP（Code → Download ZIP）解压，双击 `index.html` —— 全部功能立即可用

> AI 功能需要 `ort/` 与 `models/` 文件夹和 `index.html` 在同一目录。只复制单个 HTML 文件时 AI 功能会提示缺失。

### 安卓版
1. 前往 [Releases](https://github.com/gooooddayy/L1-Photo-Editor/releases) 下载 `L1图片编辑器_v1.0.0_release.apk`
2. 安装（需允许安装未知来源应用），Android 7.0 及以上、arm64 设备
3. 模型内置在安装包中，**安装后完全离线可用，无需任何下载**

## 🧠 模型说明

| 模型 | 用途 | 体积 | 来源 |
|---|---|---|---|
| SILUETA (`u2netp.onnx`) | 抠图 | 42MB | [rembg 官方蒸馏版](https://github.com/danielgatis/rembg)（u2net 精度的轻量实现） |
| MI-GAN (`migan_pipeline_v2.onnx`) | 消除 | 28MB | [andraniksargsyan/migan](https://huggingface.co/andraniksargsyan/migan)（inpaint-web 同款管线） |

- 模型加载顺序：**本地 models 目录 → 浏览器 IndexedDB 缓存 → 在线下载**；部署后优先从站点自身（同源）加载
- 推理引擎：onnxruntime（网页端 WASM SIMD / 安卓端 onnxruntime-android，均为 1.16.3）
- 网页端单文件体积约 64KB；`ort/ort-wasm.b64.js` 与 `models/*.b64.js` 经 base64 编码实现 `file://` 双击离线推理

## 📐 技术架构

- **网页端**：单文件 HTML，零依赖、零构建、零后端
- **安卓端**：纯 Java（零 androidx / 零 Kotlin），onnxruntime-android 原生推理；源码在 [`android/`](android/)，模型资产不含在仓库内（见 Releases 的 APK）
- 详见 [docs/技术方案与踩坑记录.md](docs/图片编辑器-技术方案与踩坑记录.md)（35 条实战踩坑）与 [tools/e2e_web.js](tools/e2e_web.js)（断网端到端测试脚本）

## 🔒 隐私与合规

- **数据不收集**：不收集、不存储、不传输任何用户图片或使用数据；无统计埋点、无广告、无第三方请求
- **纯本地处理**：AI 推理在本地 WASM / 本地 CPU 完成，图片不离开设备
- **权限最小化**（安卓端）：仅使用旧版存储权限（Android 9 及以下读图需要），无网络权限、无后台组件
- **免责声明**：本项目为个人学习与技术存档用途，按 MIT 许可开源，不提供任何担保；使用者需自行遵守当地法律法规，不得用于处理违法内容或侵犯他人权益（包括未经授权移除他人作品版权水印）

## ❓ FAQ

**Q: 会被压缩画质吗？**
导出默认原始分辨率、原格式；质量滑杆可调，默认与原图一致。

**Q: AI 功能失败提示"文件夹缺失"？**
`index.html`、`ort/`、`models/` 必须同目录；在线使用则需允许站点下载模型。

**Q: 支持手机吗？**
支持。手机浏览器访问在线版即可；也可安装安卓 APK 获得原生体验（更快、完全离线）。

**Q: 图片会上传吗？**
不会。全部处理在浏览器 / 设备内完成，AI 推理也在本地运行。

## 📄 License

[MIT](LICENSE) · 模型版权遵循各自来源仓库许可

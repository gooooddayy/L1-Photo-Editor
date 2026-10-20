# 安卓版（L1 图片编辑器 APK）

纯 Java 实现（零 androidx、零 Kotlin），onnxruntime-android 原生推理。

## 环境

- JDK 11、Android Gradle Plugin 7.2.2 / Gradle 7.3.3、Android SDK（compileSdk 33, build-tools 33.0.2）
- `minSdk 24`（Android 7.0）`targetSdk 33`，仅 `arm64-v8a`

## 模型资产（仓库不含，需自行放入）

构建前将两个 ONNX 模型放到 `app/src/main/assets/`：

| 文件 | 用途 | 来源 |
|---|---|---|
| `silueta.onnx` | AI 抠图 | [rembg](https://github.com/danielgatis/rembg) 蒸馏版 u2net（SILUETA，约 44MB） |
| `migan.onnx` | AI 消除 | [andraniksargsyan/migan](https://huggingface.co/andraniksargsyan/migan) pipeline v2（约 28MB） |

## 构建

```bash
# 调试包
gradle assembleDebug

# 正式包（构建 → 对齐 → 签名）
gradle assembleRelease
zipalign -p 4 app/build/outputs/apk/release/app-release-unsigned.apk out.apk
apksigner sign --min-sdk-version 24 --ks <你的keystore> --ks-key-alias <alias> out.apk
```

## 源码结构

```
app/src/main/java/com/l1/editor/
  MainActivity.java   入口 + 自绘 UI + 导出面板（复刻网页端导出逻辑）
  EditorView.java     画布视图（手势、涂抹、裁剪框）
  AiManager.java      ONNX 推理（抠图/消除）
  Exporter.java       编码导出（质量/体积限制策略与网页端一致）
  ImageOps.java       裁剪/旋转/镜像/马赛克
  UndoManager.java    快照撤销栈（内存预算管理）
  Util.java           工具函数
make_icons_xml.py     SVG → Android vector drawable 转换器（含 PathParser 参数校验）
```

预编译 APK 见 [GitHub Releases](https://github.com/gooooddayy/L1-Photo-Editor/releases)。

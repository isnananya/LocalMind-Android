# 开源许可与致谢

LocalMind 自身代码以 **MIT** 发布（见仓库根目录 [LICENSE](../LICENSE)）。
下面列出随 APK、随运行时下载的原生库，以及设计与数据来源。

## 随 APK 分发

| 组件 | 许可 | 用途 |
| --- | --- | --- |
| llama.cpp (ggml-org) | MIT | 本地文本推理内核 |
| ggml (ggml-org) | MIT | 张量计算 |
| KleidiAI (Arm) | Apache-2.0 / BSD-3-Clause | arm64 的 i8mm / dotprod / SME 微内核，经 llama.cpp 编入。Copyright 2024-2026 Arm Limited |
| libc++ (Android NDK) | Apache-2.0 | C++ 运行库（libc++_shared.so） |
| Jetpack Compose / AndroidX | Apache-2.0 | 界面框架 |
| Material Icons | Apache-2.0 | 内置图标（只打包用到的子集） |
| Kotlin / kotlinx（coroutines、serialization） | Apache-2.0 | 语言与协程/序列化 |
| OkHttp / Okio | Apache-2.0 | 下载与本地 API 服务 |

## 运行时按需下载

| 组件 | 许可 | 用途 |
| --- | --- | --- |
| sherpa-onnx (k2-fsa) | Apache-2.0 | 离线神经语音合成 |
| ONNX Runtime (Microsoft) | MIT | 语音引擎推理运行时 |
| stable-diffusion.cpp (leejet) | MIT | 可选生图后端插件 |

## 许可要求署名的数据

| 来源 | 许可 | 说明 |
| --- | --- | --- |
| Open-Meteo | CC BY 4.0 | 人设聊天的天气数据。仅在用户打开「看天气」并聊到天气时请求一次 |

## 设计参考

* **Liquid Glass 底栏**：视觉与实现思路参考 Apple iOS 26 的 Liquid Glass，以及
  [liuran001/WeChat-LiquidGlass](https://github.com/liuran001/WeChat-LiquidGlass)
  的公开做法；本项目是独立的 Kotlin/Compose 实现（抓窗口缩略图 + box blur + 高光边），
  未复制其代码。
* **AirLLM**（lyogavin/airllm，Apache-2.0）：「先算预算、再按层取权重」的内存规划思路。

## 模型权重

本项目**不分发任何模型权重**。模型由用户自行下载，每个模型的许可在模型页条目上
逐个标注；使用前请阅读原始仓库的 LICENSE。

## 插件与素材

* 表情包插件里的图片素材，其授权由**插件作者**负责；
* 第三方插件的行为与内容由插件作者负责，安装前请阅读其 README。


## AndroidLiquidGlassView（MIT）

- 项目：https://github.com/QmDeve/AndroidLiquidGlassView
- 文档：https://liquidglass.qmdeve.com/zh/
- 版权：Copyright (c) 2025-2026 Donny Yale
- 用途：底栏/导航栏的液态玻璃（RenderEffect + RuntimeShader 折射、色散、模糊）。
- 依赖版本：`com.qmdeve.liquidglass:core:1.0.2`（1.1.0 要求 compileSdk 37，工程当前 35）。
- 只在 Android 13+ 硬件渲染时启用；模拟器软渲染自动回退到内置实现。


## LiquidTab（GPL-3.0，可选插件）

- 项目：https://github.com/big-dictator/LiquidTab
- 版权：LiquidTab 作者（big-dictator）
- 用途：紧凑悬浮液态玻璃底栏的布局与交互参考。
- 分发方式：**独立插件** `glass-bar-module.zip`，不是主程序的一部分。
- 许可：插件模块按 GPL-3.0 声明；主程序仍是 MIT。
- 插件清单里的 license / sourceUrl 会显示在「关于 → 插件开源声明」。

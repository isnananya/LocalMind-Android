# LocalMind 文档索引

## 先读这几篇

| 文档 | 读它做什么 |
| --- | --- |
| [../README.md](../README.md) | 这是什么、怎么编译、第一次怎么用 |
| [PROJECT.md](PROJECT.md) | 架构总览：模块、数据流、为什么这么拆 |
| [../BUILD.md](../BUILD.md) | 构建环境、NDK、常见编译错误 |
| [MODELS.md](MODELS.md) | 模型格式、量化、内存规划 |

## 插件开发

| 文档 | 内容 |
| --- | --- |
| [PLUGIN.md](PLUGIN.md) | 插件清单、能力总表、各类内容文件、打包与排错 |
| [UI-LAYOUT.md](UI-LAYOUT.md) | `ui_layout` 字段级规范：底栏、首页块、screens/overrides |
| [UI-PLUGIN.md](UI-PLUGIN.md) | `ui_theme` 外观令牌完整参考 |

想直接看可运行的文件：`plugins-src/` 下每个目录都是一个完整插件源。

## 功能深入

| 文档 | 内容 |
| --- | --- |
| [IMAGE_ENGINE.md](IMAGE_ENGINE.md) | 生图后端：程序化渲染与 stable-diffusion 接入 |
| [AIRLLM.md](AIRLLM.md) | 磁盘流式加载（大模型跑在小内存设备上） |
| [DOWNLOAD.md](DOWNLOAD.md) | 模型与二进制的下载源、镜像策略 |
| [OPEN-SOURCE.md](OPEN-SOURCE.md) | 开源许可清单与第三方致谢 |

## 归档

[archive/](archive/) 是历史进度记录（HANDOFF / STATUS / BLOCKERS / SPEED / PLUGINS / MODEL-CANDIDATES）。
它们描述的是当时的状态，结论可能已经被代码推翻；**不要把它当作当前行为**。

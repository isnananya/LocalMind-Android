# 第三方代码许可文本

本文件收拢**直接改编进源码**的第三方代码所要求的版权与许可声明。
完整的第三方组件清单见 [docs/OPEN-SOURCE.md](../docs/OPEN-SOURCE.md)。

---

## AndroidMonitor（MIT）

- 项目：https://github.com/TerminalDev-1/AndroidMonitor-Preview
- 用途：状态页的设备采样（CPU / 内存 / 电池 / 网络）与折线图组件。
  改编文件：
  - `app/src/main/java/com/localmind/service/SystemStatus.kt`
  - `app/src/main/java/com/localmind/ui/components/Sparkline.kt`
- 改动：去掉了 Shizuku/root 依赖，读不到的内核计数如实返回 null；
  图表去掉 MaterialTheme 依赖，改为调用方传入令牌色。

```
MIT License

Copyright (c) 2026 Android Monitor contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## AndroidLiquidGlassView（MIT）

- 项目：https://github.com/QmDeve/AndroidLiquidGlassView
- 文档：https://liquidglass.qmdeve.com/zh/
- 用途：液态玻璃导航栏 / 底栏的 RenderEffect 折射与模糊。
- 依赖坐标：`com.qmdeve.liquidglass:core:1.0.2`（1.1.0 要求 compileSdk 37，本工程 35）。

```
MIT License

Copyright (c) 2025-2026 Donny Yale

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## LiquidTab（GPL-3.0，只作为独立插件）

- 项目：https://github.com/big-dictator/LiquidTab
- 用途：紧凑悬浮液态玻璃底栏的**布局与交互参考**；主程序是 MIT 的独立实现。
- 分发：`glass-bar-module.zip` 是独立插件，按 GPL-3.0 声明；主程序不复制其代码。
- 完整 GPL-3.0 文本：https://www.gnu.org/licenses/gpl-3.0.txt

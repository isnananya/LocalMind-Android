# 交接说明（下一轮从这里读）

> 这一节是给下一个对话/开发者的交接文档，写在 README 最前面；下面才是项目本身的 README。
> 最近一轮做完了「内置音色 + 多选」「低端模式」，并把 Breeze 的界面入口撤掉。
> 第 1 节是**已经验证过、不要回退**的东西；第 2 节是剩下的活。

## 0. 环境、构建、验证

- 仓库：`D:\aichat\LocalMind`（Compose App）；上游 LiquidTab 源码：`D:\aichat\lt\LiquidTab-main`（GPL-3.0，只作参考，不要整文件粘）。
- 构建：
  ```powershell
  $env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
  $env:ANDROID_HOME='C:\Users\16505\AppData\Local\Android\Sdk'
  cd D:\aichat\LocalMind
  .\gradlew.bat :app:assembleDebug
  adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
  ```
- 模拟器：`emulator-5554`，1080×2400，density 420，**SwiftShader 软件 GLES**，2.5 GB 内存 / 4 核。
  - 因此 `RenderNode + RuntimeShader` 的 RenderEffect 链在这个模拟器上会整块渲染成透明；
    `LiquidBarGlass` 里必须保留 `Paint + BitmapShader` 的降级路径，真机才走 AGSL。
  - 模拟器默认网络是 3G 档；`adb emu network speed full` 能放开限速，但从这台机器到
    GitHub 直连本来就慢（实测 30–40 KB/s），下 36 MB 的语音引擎要十几分钟。
- 截图/素材目录：`D:\aichat\_shots`（本轮关键几张：`s12` 内置音色、`s15` 多选、`s16` 内存模式）。
- 偏好设置（theme_overrides）在 `shared_prefs/localmind.xml`，JSON 字符串。改法：
  ```powershell
  adb -s emulator-5554 exec-out run-as com.localmind cat shared_prefs/localmind.xml > prefs.xml
  adb -s emulator-5554 push prefs.xml /data/local/tmp/prefs.xml
  adb -s emulator-5554 shell "run-as com.localmind cp /data/local/tmp/prefs.xml shared_prefs/localmind.xml"
  ```
- 生图原生库默认**不打包**：要带 SD 用 `.\gradlew.bat :app:assembleSdPlugin` 或 `-PwithSdLib=true`。

## 1. 已完成、不要回退

### 1.1 液态底栏（已内置，不是插件）
- 文件：`ui/components/LiquidGlassTabBar.kt`、`ui/components/LiquidBarGlass.kt`、`ui/components/GlassBackdrop.kt`。
- 内置化：`data/BuiltinPlugins.kt` 里 `com.localmind.bar.liquidtab` 已删除；
  `ui/screen/PluginsScreen.kt` 列表过滤掉旧记录；`ThemeScreen` 只留一个「液态底栏」总开关。
- 关键常量（**照抄 LiquidTab，别再"优化"**）：
  - 面板填充 `fillAlpha = 0.40`；
  - 胶囊：`pillInset = 4dp`、`pillTop = 4dp`、radius = h/2（**不要 clamp 到 18dp**）；
  - 胶囊静止：模糊图 + 10% 白/黑 + 16% 轻透镜；按压：缩放 1.36 + 清晰图 + 色散透镜；
  - 长按拖动：整条放大 8%、阴影 12→22dp、透镜 ×1.5、**采样要加 dragY**；
  - 默认模糊 10dp，抓图 `SAMPLE = 3`。
- 手势（1.0.6 二次修正，**别再退回 Compose 的现成检测器**）：标签按钮是 clickable，
  它会消费 DOWN，于是 `awaitLongPressOrCancellation` / `awaitHorizontalTouchSlopOrCancellation`
  一看 isConsumed 就取消 —— 表现就是"长按只放大、拖不动"。现在是自己写的指针循环：
  在 **PointerEventPass.Initial** 读事件（子级还没消费），自己判方向/长按；长按用
  `scope.launch { delay(longPressTimeout) }` 置 `grabbed`，**不要**在指针作用域里
  `withTimeout`（AwaitPointerEventScope 是 @RestrictsSuspension）。
  - 起手在选中胶囊上 + 横向过 slop → 拖胶囊（不用先长按）；
  - 长按之后 → 横向拖胶囊 / 纵向拖整条底栏；
  - 其它情况一律不接管，点按照常给按钮。
- 胶囊折射的是**底栏上的内容**（图标/文字），不是底下的页面：Row 用 GraphicsLayer 录一份
  内容位图（`rememberGraphicsLayer` + `drawWithContent`），胶囊画在 Row **之上**：
  先铺底栏自己的磨砂底（盖住原始图标）→ 内容位图过透镜 → 10% 叠色/高光。
  旧实现直接采样窗口截图，胶囊就成了底栏上的一个"洞"，透出页面内容。

### 1.2 外观设置（顺序固定）
`ThemeScreen.kt`：宽度（悬浮胶囊/通栏）、选中态（圆形/下划线/圆点/无）、液态底栏、底部渐变模糊、
描边、动态配色、底栏模糊半径、高光强度、底栏高度、单栏宽度、距底距离、仅图标、文字大小、图标大小、隐藏底栏。
令牌在 `data/UiTheme.kt`；动态配色在 `ui/theme/DynamicAccent.kt`，在 `ui/AppTheme.kt` 合并。

### 1.3 内置音色 + 多选（本轮 A，做完了）
- **`data/BuiltinVoices.kt`**：4 个内置音色，只放 **VITS / Piper**（原因见 §3.2）：
  `vits-piper-zh_CN-chaowen-medium-int8`（13 MB）/ `...huayan-medium`（64 MB）/
  `vits-zh-hf-fanchen-unity`（114 MB）/ `sherpa-onnx-vits-zh-ll`（113 MB）。
- **合并规则**（`data/VoiceCatalog.kt` 的 `setFromPlugins`）：内置按声明顺序在前；
  插件里**同 id 的条目覆盖**内置那一份（插件可以改地址/文案/镜像）；插件独有的追加在后面。
  所以「没装插件也有音色」和「换地址不用发版」同时成立。
- **语音页多选**（`ui/screen/VoiceScreen.kt`）：右上「多选 / 完成」；行首圆形勾选框（已装的也能勾）；
  底部固定「下载所选 (n) / 删除所选 (n)」，n 是**可执行的数量**（已装的不计入下载）；
  退出多选恢复行内「使用/试听/删除」。「使用」仍然单选（sherpa 引擎单实例）。
- 批量下载并发 **2**：`AppViewModel.downloadVoices` 用 `Semaphore(2)` + `VoiceModelDownloader.await`
  （`install()` 只起协程就返回，不限流的话会同时开 N 个下载）。批量删除走 `Dispatchers.IO`。
- **Breeze 入口撤掉**：TTS 页右上圆形按钮和「外部引擎」分组已删除；
  `service/BreezeTts.kt`、`ui/screen/BreezeScreen.kt` 保留（有服务器的用户仍可走）。
  「我的」页（`ui/design/MePage.kt`）本来就没有 Breeze 入口。
- 插件里的 `url / mirrors` **现在真的生效**：下载器改走 `VoiceCatalog.urlsFor(voice)`，
  之前按 id 现拼 URL，插件的镜像字段是死的。
- **修了一个会让进程直接消失的崩溃**：Piper 类音色（`vits-piper-*`）的包里带
  `espeak-ng-data/`，旧代码只找 lexicon/tokens、`data_dir` 传空 → sherpa 的 VITS 前端
  落到最后一条分支，打一行 "Not a model using characters as modeling unit" 然后
  `SHERPA_ONNX_EXIT(-1)`（真机上是 exit 255：没有 Java 栈、没有 tombstone）。
  现在 `resolve()` 会找 `espeak-ng-data` 并传 `dataDir`；另加一道前端闸门：
  非 characters 前端、又不是 piper+dataDir、又没有 lexicon 的音色直接标成"用不了"，
  **绝不进原生**。旧版本因此被拉黑的音色，升级后清一次黑名单（看门狗仍然有效）。

### 1.4 低端模式（本轮 B，做完了）
- `data/MemoryPlanner.kt` 的 `lowEndPolicy(context)`（按 `totalMem`）：

  | 总内存 | ctx 上限 | batch 上限 | KV |
  |---|---|---|---|
  | < 4 GB | 1024 | 128 | q8_0 |
  | < 6 GB | 2048 | 256 | q8_0 |
  | < 8 GB | 4096 | 512 | q8_0 |
  | ≥ 8 GB | 用户设置 | 用户设置 | 用户设置 |

- `plan()` 新增 `policy` 参数（默认自己算）、`recommendedBatch`、`lowEndNote`；
  `n_ctx = min(内存预算, 档位上限)`，夹了会写进 `warnings`。
- `LlamaEngine.load`：用 policy 的 KV 精度估 KV（否则按 f16 估保守一倍），
  `kvCode / batch / ubatch` 都按档位落到原生；`nCtx` 用 `plan.recommendedContext`。
- 线程：`bigCoreCount()` 读 `/sys/.../cpuinfo_max_freq` 数**最高一档**的核数，
  `recommendedThreads()` 夹在 2–6；读不到退回 `autoThreads()`。
  设置页「自动（N）」必须调 `recommendedThreads()`。
- 展示：设置页「内存模式」**常显**（不依赖加载模型）、状态页「内存模式」、模型详情、设置页内存规划。
- 模拟器实测（2.5 GB）：显示「低端模式 · 2.4 GB 内存 · ctx 1024 · KV q8」，自动线程 4。

### 1.5 其它已完成
- 捐赠榜按金额降序（`AboutScreen.kt` 的 `Donors`）。
- 状态页「正在使用」实时卡：引擎每 200 ms 推一次进度（不要改成每 token，重组会炸）。
- 本地 TTS 优先：`AppViewModel.speak()` = 本地神经音色 → Breeze → 系统 TTS。
- 语音引擎下载有百分比了：`SherpaLibraryInstaller.onProgress` → `VoiceDownloader.installLibrary`。
- 引擎安装抗中断：下完写 `.ok` 标记，只有标记在才复用 tarball；解压失败会删包重下。
  原来只按 `> 40 MB` 判断，被杀过一次的半包会被当成"已完成"，之后每次安装都失败。

## 2. 待办（下一轮）

### 2.1 生图 / 识图收尾（必须真机）
- 生图：装 `assembleSdPlugin` 产物 → 下一个小 SD 模型（512×512、低步数先跑通）→
  看 `diffusionBackendReady` / `ImageEngine` 日志。
- 识图：`data/ModelCatalog.kt` 里挑带 `mmprojUrl` 的多模态模型，主模型 + mmproj 都下完，
  `loadModel` 会自动 `nativeMmprojLoad`；验收 = 状态页「现在能看图」+ 真机发图问答。

### 2.2 语音这块还能做
- 引擎包 36 MB，直连 GitHub 实测 30–40 KB/s（这台机器上直连甚至会 RST），
  可以在 `SherpaLibraryInstaller.URLS` 里加国内镜像（gh-proxy 也慢，约 8–10 KB/s）。
- Kokoro 想要「最自然」得先接非 VITS 路径；现在 `Layout.unsupportedReason` 会拦住它，
  **不要只把模型塞进内置清单**。

### 2.3 清理
- `ui/screen/ProfileScreen.kt` 是**死代码**（根页面用 `MePage`），里面还留着 Breeze 入口和旧样式。
  要么删掉，要么接回根页面 —— 不要两边都改。
- `docs/archive/` 下的文档是历史记录，不要当现状改。

## 3. 硬约束（别浪费时间）

1. **Breeze TTS 2 = 3.47B、CUDA 专用、~7 GB fp16，手机跑不了**。本地 TTS 只有 sherpa-onnx 这条路。
2. **主程序只接了 VITS 引擎**：Kokoro / Matcha 的 tarball 装上也发不出声
   （`NeuralVoiceModels.Layout.unsupportedReason` 会拦，放行会让原生库直接结束进程）。
   内置清单因此只放 VITS/Piper。
3. **GPL-3.0**：从 LiquidTab 移植的 AGSL/几何常量属于派生作品，发布按 GPL；其余代码 MIT。
4. 模拟器只能验证 UI/交互/降级渲染；性能、真机 AGSL、SD、识图必须真机。
5. 底栏截图循环 180 ms/次、1/3 缩略、后台模糊双缓冲；`capturing` 期间不能画底栏，否则自拍自。

## 4. 本轮验证记录

- `:app:assembleDebug` 通过（Kotlin 只有 3 条历史 warning，无新增）。
- 模拟器：关掉「LocalMind 官方模型库」插件 → TTS 页只剩 4 个内置音色（`_shots/s12.png`）；
  打开时是 17 个且插件文案生效（同 id 覆盖）。
- 多选：勾 2 个 → 行变蓝、底部「下载所选 (2)」可用（`_shots/s15.png`）。
- 低端模式：设置页显示档位文案（`_shots/s16.png`）。
- 语音引擎安装：40 MB+ 的包在模拟器上只下到一半（直连 GitHub 30–40 KB/s），
  完整「下载 → 试听 → 使用」没跑完；新增的 `.ok` 标记保证下次点击从干净状态重下。
  这一段建议真机 / 快网复核。
- 真机（Xiaomi 24031PN0DC / Android 16）实测崩溃与修复：08:48 加载
  `vits-piper-zh_CN-huayan-medium` 时进程 exit 255；修复后同一台机器
  「音色就绪：vits-piper-zh_CN-huayan-medium 1 个说话人 @ 22050 Hz」，
  华研与超文 int8 都能加载、应用不再退出。
- 底栏手势在模拟器上验证过：快速横拖胶囊切页、长按胶囊再拖也切页；
  折射内容在模拟器上走的是无 AGSL 的降级路径，真机 AGSL 效果需要目视确认。
- 最终产物：`dist/LocalMind-1.0.6-arm64+armv7.apk`（release，44.4 MB，debug 签名）；
  模拟器装的是 x86_64 的 debug 包（release 只带 arm64/armv7）。

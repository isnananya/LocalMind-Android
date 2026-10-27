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

---

# LocalMind

> 当前版本 **1.0.5**。这一轮把插件开放到「可执行代码」：`code_plugin`（classes.dex，
> 含 View 整页）、`web_screen`（HTML/CSS/JS 整页）、`theme.css`（用 CSS 变量写主题与底栏样式），
> 底栏换成 AGSL 折射 + CPU 降级的液态玻璃。版本号按批次保持 1.0.5。

手机本地大模型工作台：把开源大模型**下载到手机、在本机跑起来、直接聊天**，
还要能**文字生图**、**语音对话**，并把整个模型能力用 **OpenAI 兼容 API**
开放给本机其它 App 或局域网设备。

界面按 iOS 26 的思路重做：大标题、分组卡片、发丝分隔线、弹性动效，
底栏是带**真实背景模糊**的液态玻璃胶囊。只有三个标签：对话 / 模型 / 我的。

> Kotlin + Jetpack Compose · JNI + **llama.cpp**（编译进 APK，不是远程调用）· 零 Python 依赖

---

## 一、能力总览

| 能力 | 实现方式 | 需要网络吗 |
| --- | --- | --- |
| 下载模型 | 内置 6 个推荐 GGUF（229 MB–1.2 GB，覆盖各内存档位），断点续传、多镜像回退；更大的清单走模型库插件 | 下载时需要 |
| 本地推理 | **llama.cpp** 经 JNI 编译进 APK，CPU 多线程 | 完全离线 |
| 聊天 | 流式输出、代码块/Markdown 渲染、多会话、tok/s 实时统计 | 完全离线 |
| 生图 | 内置原生程序化渲染引擎（毫秒级、可复现），并预留真实扩散模型接入位 | 完全离线 |
| 语音对话 | 长按说话 → 语音识别 → 本地大模型 → TTS 朗读；**8 种情感音色** | 识别取决于机型 |
| **神经音色** | **sherpa-onnx VITS 离线语音合成**：内置 4 个（Piper / VITS，13–114 MB），官方插件再加 17 个；支持多选批量下载/删除。模型下载后完全离线、跟机型无关 | 下载时需要 |
| **Breeze 语音** | **外部语音引擎插件**（`tts_engine`）：端点、密钥、模型名由插件声明，手机只当客户端。Breeze TTS 2 是 3.47B / 需要 CUDA 的模型，**手机上跑不动**，必须在 PC 或 GPU 服务器上自建服务 | 合成时需要（局域网/云端） |
| **分步流水线** | `pipeline` 插件：把长文拆成片，逐片跑（本机模型 / 外部语音引擎），最后聚合。手机装不下的模型靠这个绕开内存与上下文限制 | 看具体步骤 |
| **界面布局插件** | `ui_layout`：底栏入口、首页块、应用标题都能声明；`screens` 自己写整页、`overrides` **整页替换**内置页面；动作目标支持 `prompt:` / `send:` / `copy:` | — |
| **聊天动作插件** | `chat_action`：`actions.json` 声明一键提示词，显示在输入框上方，支持 `{{input}}` 引用当前输入 | — |
| **代码插件** | `code_plugin`：classes.dex 经 DexClassLoader 加载，可注册动作、可用 Java View 替换整页；安装前确认，异常自动停用 | — |
| **网页插件** | `web_screen`：HTML/CSS/JS 整页由 WebView 渲染，JS 桥只有 send / prompt / toast / copy | — |
| **CSS 主题** | `theme.css`：用 CSS 变量写颜色、圆角、底栏形状/指示器，支持 `dark-` 深色令牌 | — |
| 开放接口 | 应用内 HTTP 服务器，OpenAI 兼容，可开局域网 | 局域网内 |
| 内存优化 | 内存规划器 + mmap 磁盘流式加载（AirLLM 思路的移动端实现） | — |
| **低端模式** | 按总内存自动封顶上下文 / batch / KV（< 4 GB → ctx 1024 · KV q8），4 GB 以下的机器先保证能跑起来而不是 OOM 闪退；设置页与状态页如实显示档位 | — |
| **对话走 API** | 每个对话可以单独选「本机模型」或任意 **OpenAI 格式接口**（OpenAI / DeepSeek / Kimi / 硅基流动 / 局域网 Ollama…），也可在对话设置里现场填地址与 Key；记忆/情绪照常工作 | 走 API 时需要 |
| **提示词前缀复用** | 稳定段进 system prompt、易变段挂在最新消息上，KV cache 能真正命中；日志与界面直接显示命中率 | 完全离线 |

---

## 悬浮底栏（1.0.5）

- 底栏改为**覆盖在内容之上**的悬浮胶囊，不再占据布局、也没有整条底色遮挡；内容可以从它下面滚过。
- 页面底色改成大色团渐变（蓝/紫/青），卡片改成半透明玻璃（白 74% + 1px 高光边），导航栏去掉整条实色。
- 底栏不再采样窗口缩略图：采样会把上一帧的自己画进来，产生重影；当前用高不透明度的磨砂染色保证可读性。

## 液态玻璃组件库（已评估，默认关闭）

已把 [AndroidLiquidGlassView](https://liquidglass.qmdeve.com/zh/)（MIT）源码引入工程评估：

- 它只提供一个玻璃 View，不是完整 UI 组件库；且 1.1.0 要求 compileSdk 37（当前 35），1.0.2 可用。
- **不能直接放进 Compose 树**：它把采样源 draw 进 RenderNode，而 Compose 单 View 树里玻璃自己也在采样源中，
  Android 会抛 `Recording currently in progress - missing #endRecording()`，启动即崩（GPU 模拟器已复现）。
- 正确接法是把玻璃层放到 ComposeView 之外（Activity 根布局 + 原生底栏），这项改造还没做。
- `uiKit` 令牌保留（auto / library / compose），等原生 host 接入后再启用。

## 二、编译

环境要求：**JDK 17**、Android SDK（platform 35、build-tools 34/35）、
**NDK 27.0.12077973**、CMake 3.22.1。

```bash
# 1. 取回 llama.cpp 源码（体积大，未提交进 git）
git clone --depth 1 --branch v0.6.0 https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp

# 2. 编译（首次会编译整个 llama.cpp，8 核机器约 4-5 分钟）
./gradlew assembleDebug        # 产出 arm64-v8a + armeabi-v7a
./gradlew assembleRelease      # 已用 debug 签名，可直接安装

# 需要模拟器 ABI 时：
./gradlew assembleDebug -PabiList=arm64-v8a,x86_64

# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

产物：`app/build/outputs/apk/debug/app-debug.apk`
（单 x86_64 **58.8 MB**，默认双 ABI **79.7 MB** —— 含 sherpa-onnx 原生库）

> **网络**：Gradle 发行包已指向腾讯云镜像（`services.gradle.org` 直连超时）；
> `gh-proxy.com` 是唯一实测可用的 GitHub 代理。详见 [BUILD.md](BUILD.md) §0。

> 本机没有 NDK 或不想编译？`.github/workflows/android.yml` 会在 GitHub 机器上
> 自动编译并把 APK 上传为 artifact，下载即可安装。

环境踩坑与验证清单见 [BUILD.md](BUILD.md)。

---

## 三、第一次使用

1. **模型页** → 「可下载」里挑一个：
   - 先试水：`ERNIE 4.5 0.3B`（229 MB）或 `Kimi-K3 0.40B`（260 MB）
   - 日常：**`Qwen3.5 0.8B`（508 MB）**，速度与质量最均衡
   - 答得更好：`Qwen3.5 2B`（1.2 GB）；想要推理过程：`DeepSeek-R1 蒸馏 1.5B`（1.0 GB）
   - 想装更多：导入 `官方模型库插件.zip`（83 个实测条目）
2. 下载完点 **加载**（模型卡片右侧的播放键，或点卡片进详情再加载）。
3. **对话页** 点右上角 + 直接开聊。
4. 要语音：**我的 → TTS 测试** → 打开「启用语音」→ 选音色 / 情感 → 点「朗读」试听；
   每个对话的朗读开关与音色提示词在**对话 → 右上角设置 → 朗读**里。
   - 想要更自然的声音：在同一页的「神经音色」里下载一个（小雅 13 MB 最轻量），
     下完点「使用」。**下载后完全离线**，且跟机型无关 —— 换手机听起来还是同一个人。
   - 不下载也行，默认走系统 TTS。
5. 要给别的程序用：**我的 → 本机 API 服务** → 打开"允许局域网访问" → 启动服务，然后：

```bash
curl http://<手机IP>:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"localmind","messages":[{"role":"user","content":"你好"}]}'
```

任何支持"自定义 OpenAI Base URL"的客户端（Cherry Studio、ChatBox、Open WebUI、
沉浸式翻译、IDE 插件…）填 `http://<手机IP>:8080/v1` 即可使用手机上的模型。

> 调试时不必从别的设备连：`adb forward tcp:18080 tcp:8080` 之后，
> 在开发机上直接 `http://127.0.0.1:18080/v1/...` 就能打进来。

---

## 四、项目结构

```
ai-model-hub/
├── app/
│   ├── build.gradle.kts              # AGP 8.5.2 / Kotlin 2.0.20 / Compose BOM 2024.09.02
│   └── src/main/
│       ├── cpp/
│       │   ├── CMakeLists.txt        # llama.cpp 作为子项目静态链接
│       │   ├── llama_jni.cpp         # 推理 JNI：加载/对话模板/采样链/流式/取消
│       │   └── image_jni.cpp         # 生图 JNI：程序化渲染 + 扩散后端接入位
│       ├── java/com/localmind/
│       │   ├── MainActivity.kt
│       │   ├── LocalMindApp.kt       # 依赖容器
│       │   ├── native/llama/LlamaBridge.kt
│       │   ├── native/image/ImageBridge.kt
│       │   ├── data/
│       │   │   ├── Models.kt         # 数据模型、情感与采样参数定义
│       │   │   ├── ModelCatalog.kt   # 13 个已验证 URL 的 GGUF 清单
│       │   │   ├── MemoryPlanner.kt  # GGUF 头解析 + 内存/上下文规划
│       │   │   ├── VoiceCatalog.kt   # 8 个神经音色（id 即官方 tarball 名）
│       │   │   └── Storage.kt        # 设置与文件持久化
│       │   ├── service/
│       │   │   ├── LlamaEngine.kt    # 唯一推理入口（互斥锁串行化）
│       │   │   ├── ModelDownloader.kt# 断点续传 + 镜像回退
│       │   │   ├── ApiServer.kt      # OpenAI 兼容 HTTP 服务器
│       │   │   ├── ImageEngine.kt    # 生图引擎 + TtsEngine（系统 TTS 外壳）
│       │   │   ├── NeuralTts.kt      # 神经音色：sherpa-onnx VITS
│       │   │   ├── VoiceModelDownloader.kt # 音色下载 + tar 解压
│       │   │   ├── SttEngine.kt      # 语音识别
│       │   │   ├── ApiForegroundService.kt
│       │   │   └── SelfTest.kt       # 应用内自检
│       │   └── ui/                   # iOS 风格 Compose UI（5 页 + 设置）
│       ├── java/com/k2fsa/sherpa/onnx/  # vendored sherpa-onnx TTS API（神经音色）
│       └── res/                      # iOS 风格主题、图标
├── third_party/
│   ├── llama.cpp/                    # 需自行 clone（见上）
│   └── airllm-reference/             # AirLLM 的 LICENSE/README（出处与授权）
├── docs/
│   ├── AIRLLM.md                     # AirLLM 集成说明（含为何不能直接跑）
│   ├── IMAGE_ENGINE.md               # 生图引擎与接入真实扩散模型
│   ├── BLOCKERS.md                   # 生图/识图障碍 + TTS 走通记录（含 JNI 反射坑）
│   └── STATUS.md                     # 进度与实测记录
├── BUILD.md
└── .github/workflows/android.yml     # 一键出 APK
```

---

## 五、API 一览（OpenAI 兼容子集）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/`、`/health` | 服务信息 / 存活与模型状态 |
| GET | `/v1/models` | 列出本机模型 |
| POST | `/v1/chat/completions` | 对话补全，`stream:true` 走 SSE |
| POST | `/v1/completions` | 旧版文本补全 |
| POST | `/v1/embeddings` | 384 维向量（本地哈希嵌入，可做 RAG 检索） |
| POST | `/v1/images/generations` | 文字生图，返回 PNG 链接 |
| GET | `/v1/images/{file}` | 取回图片 |
| POST | `/v1/audio/speech` | 文字转语音（WAV，带情感参数；接了神经音色就是人声） |
| GET | `/v1/system` | 内存 / 线程 / 模型诊断 |
| POST | `/v1/admin/load` | 切换常驻模型（可关闭） |

支持 `Authorization: Bearer <key>` 鉴权，Key 在「我的 → 本机 API 服务」生成。

### 实测样例（模拟器，Qwen2.5 1.5B Q4_K_M）

```bash
curl http://127.0.0.1:18080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"localmind","messages":[{"role":"user","content":"你好，请用一句话说明你现在运行在哪里。"}]}'
# → {"choices":[{"message":{"content":"我在阿里云的服务器上。"}}], ...}
#   54 tokens / 28.1 s（模拟器 2.2 tok/s；真机 arm64 快得多）
```

```bash
curl -X POST http://127.0.0.1:18080/v1/audio/speech \
  -H "Content-Type: application/json" \
  -d '{"input":"你好，我是小雅。","voice":"alloy","response_format":"wav"}' -o speech.wav
# → 22050 Hz / 单声道 / 16-bit；小雅 4.49 s 文本 → 198 KB WAV
```

---

## 六、性能参考（CPU 推理，Q4_K_M）

| 机型档位 | 模型 | 大致速度 |
| --- | --- | --- |
| 骁龙 8 Gen 2/3、天玑 9200+ | Qwen2.5 1.5B Q4_K_M | 12-22 tok/s |
| 骁龙 7 系 / 天玑 8000 | Qwen2.5 1.5B Q4_K_M | 6-12 tok/s |
| 同上 | Qwen3 4B Q4_K_M | 3-6 tok/s |
| 老机型（骁龙 6 系） | Qwen2.5 0.5B | 15-25 tok/s |

实际速度受内存带宽与散热影响。每次回答后界面会显示真实 tok/s。

---

## 七、授权与出处

- `llama.cpp` — MIT，作为外部源码依赖（需自行 clone）。
- 模型权重各自遵循原始许可（清单中逐个标注 Apache-2.0 / Llama-3.2-Community / Gemma / MIT…）。
- **AirLLM 思路参考**：[lyogavin/airllm](https://github.com/lyogavin/airllm)（Apache-2.0），
  其"先算预算、再按层取权重"的思想被移植为移动端的内存规划 + 磁盘流式加载，
  详见 [docs/AIRLLM.md](docs/AIRLLM.md)。
- 本项目代码：MIT。

---

## 支持作者

如果这个项目对你有用，可以扫码请作者喝杯咖啡。金额随意，不影响任何功能。

<img src="docs/assets/author_reward.png" width="240" alt="作者的赞赏码" />

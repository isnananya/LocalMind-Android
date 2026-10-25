# LocalMind 项目总览

> 手机本地大模型工作台 · Kotlin + Jetpack Compose + llama.cpp (JNI)
> 本文档是**项目全貌**：架构、功能现状、差距、路线图。
> 相关：[../README.md](../README.md)（快速上手）· [../BUILD.md](../BUILD.md)（编译）· [STATUS.md](STATUS.md)（进度）· [DOWNLOAD.md](DOWNLOAD.md)（拉源码网络）

---

## 一、这是什么

把开源大模型**下载到手机、在本机跑起来、直接聊天**，并支持生图、语音、
以及用 **OpenAI 兼容 API** 把模型能力开放给局域网。

核心约束：**零 Python 依赖**，llama.cpp 静态编译进 APK，推理完全离线。

---

## 二、代码规模

| 部分 | 文件数 | 行数 |
| --- | --- | --- |
| Kotlin（`app/src/main/java`） | 40 | 13,828 |
| C++ / CMake（`app/src/main/cpp`） | 3 | 960 |
| Markdown 文档 | 7 | — |

产物：`app-debug.apk` **27.8 MB** / `app-release.apk` **21.8 MB**（含三 ABI 的原生库）

---

## 三、目录结构

```
ai-model-hub/
├── app/
│   ├── build.gradle.kts          AGP 8.5.2 / Kotlin 2.0.20 / Compose BOM 2024.09.02
│   └── src/main/
│       ├── cpp/
│       │   ├── CMakeLists.txt     llama.cpp 作为子项目静态链接
│       │   ├── llama_jni.cpp      推理 JNI：加载/对话模板/采样链/流式/取消
│       │   └── image_jni.cpp      生图 JNI：程序化渲染 + 扩散后端接入位
│       ├── java/com/localmind/
│       │   ├── MainActivity.kt
│       │   ├── LocalMindApp.kt   依赖容器
│       │   ├── data/
│       │   │   ├── Models.kt         数据模型：Persona / ChatMode / ModelRouting / ProxySettings
│       │   │   ├── ModelCatalog.kt   32 个模型条目（含 13 个已验证 GGUF 直链）
│       │   │   ├── MemoryPlanner.kt  GGUF 头解析 + 内存/上下文规划
│       │   │   ├── Companion.kt      拟人状态：记忆 / 情绪 / 性格
│       │   │   ├── VoiceCatalog.kt   神经音色清单（sherpa-onnx / VITS）
│       │   │   └── Storage.kt        设置与文件持久化
│       │   ├── native/
│       │   │   ├── llama/LlamaBridge.kt
│       │   │   └── image/ImageBridge.kt
│       │   ├── service/
│       │   │   ├── LlamaEngine.kt        单模型推理入口（互斥锁串行化）
│       │   │   ├── DecisionEngine.kt     function-calling 决策层（可用独立引擎）
│       │   │   ├── CompanionMind.kt      拟人：记忆提取 / 情绪 / 性格漂移
│       │   │   ├── ModelDownloader.kt    断点续传 + 镜像回退 + 代理
│       │   │   ├── ApiServer.kt          OpenAI 兼容 HTTP 服务器
│       │   │   ├── ImageEngine.kt        生图引擎 + TtsEngine（系统 TTS 外壳）
│       │   │   ├── NeuralTts.kt          ✅ 神经音色：sherpa-onnx VITS 推理 + 播放
│       │   │   ├── VoiceModelDownloader.kt ✅ 音色模型下载 + tar 解压 + 布局校验
│       │   │   ├── SttEngine.kt          语音识别
│       │   │   ├── RemoteChatEngine.kt   云端模型接入
│       │   │   ├── ApiForegroundService.kt
│       │   │   └── SelfTest.kt           应用内自检
│       │   └── ui/
│       │       ├── LocalMindRoot.kt      顶层导航 + 引导拦截 + 语音页覆盖
│       │       ├── AppViewModel.kt       全部 UI 状态 + 决策模型装载 + 音色装载
│       │       ├── components/           iOS 风格组件（手写）
│       │       │   ├── LiquidGlassTabBar.kt      iOS 26 液态玻璃药丸底栏
│       │       │   ├── GlassBackdrop.kt          实时模糊背景
│       │       │   ├── AvatarPicker.kt           AvatarStore + 相册选图
│       │       │   ├── ModelRoutingSection.kt    五槽位模型分工菜单（共享）
│       │       │   ├── ModelPickerSheet.kt       PickerOption / RouteRow
│       │       │   ├── IosComponents.kt / IosScaffold.kt / Insets.kt
│       │       ├── screen/
│       │       │   ├── ChatScreen.kt         对话列表 + 会话详情 + 设置入口
│       │       │   ├── ModelsScreen.kt       模型下载 / 管理（含生图·看图分类）
│       │       │   ├── ProfileScreen.kt      我的
│       │       │   ├── OnboardingScreen.kt   首启引导
│       │       │   ├── SettingsScreen.kt     设置栏（生成参数 / 模型分工 / 下载源 / 诊断）
│       │       │   ├── PersonaEditorSheet.kt 人设编辑 + PersonaAvatar + AvatarEditor
│       │       │   ├── VoiceScreen.kt        ✅ 已加入口（含神经音色区）
│       │       │   ├── ApiScreen.kt          ✅ 已加入口
│       │       │   └── ImageScreen.kt        生图工作台（模型页子页）
│       │       └── theme/IosTheme.kt
│       └── res/
├── third_party/
│   ├── llama.cpp/              v0.6.0（编译用，需自行取回）
│   ├── llama.cpp.b4567/        旧版备份（回滚用）
│   ├── stable-diffusion.cpp/   ❌ 未就位（生图用）
│   └── airllm-reference/       仅 LICENSE + README（出处凭据）
├── docs/
│   ├── PROJECT.md              本文件
│   ├── STATUS.md               进度与待办
│   ├── DOWNLOAD.md             拉源码网络实测
│   ├── AIRLLM.md               AirLLM 集成说明
│   ├── IMAGE_ENGINE.md         生图引擎与扩散模型接入
│   └── MODELS.md               模型清单与选择建议
├── BUILD.md
└── README.md
```

---

## 四、架构要点

### 4.1 导航：三页 + 两处浮层

```
LocalMindRoot
├── if (!profile.onboarded) → OnboardingScreen（全屏向导，含返回键步进）
└── else
    ├── Tab.CHAT   → ChatScreen      （列表态 / 会话态由 inChat 切换）
    ├── Tab.MODELS → ModelsScreen
    ├── Tab.ME     → ProfileScreen
    ├── LiquidGlassTabBar（iOS 26 液态玻璃药丸，3 个入口）
    └── AnimatedVisibility(showSettings) → SettingsScreen（底部升起）
```

- `Tab` 枚举定义在 `ui/AppViewModel.kt:43`，取值 `CHAT / MODELS / ME`。
- 底栏组件 `ui/components/LiquidGlassTabBar.kt`，真实模糊背景 + 弹簧选中指示器。
- 设置栏由 `ChatScreen` 左上角 `Tune` 图标（`ChatScreen.kt:132`）和
  `ProfileScreen` 的「更多」区三行共同打开。

### 4.2 双模式

`ChatMode`（`data/Models.kt:144`）：

| 模式 | 含义 | 行为 |
| --- | --- | --- |
| `AI("AI 聊天")` | 当普通助手用 | 只需选模型，不填个人信息 |
| `COMPANION("当人")` | 当人对待 | 默认流式；启用记忆 / 情绪 / 性格；年龄性别生效 |

判断用 `ChatMode.isCompanion`。人设上的 `humanMind`(bool) + `humanMindIntensity`(0..1)
进一步控制拟人强度。

### 4.3 多模型路由（五个槽位）

`ModelRouting`（`data/Models.kt:63`）：

| 槽位 | 字段 | 状态 |
| --- | --- | --- |
| 对话模型 | `chatModelId` | ✅ 已消费 |
| 识图模型 | `visionModelId` | ❌ **只有 UI，无消费端**（见下） |
| 生图模型 | `imageModelId` | ⚠️ 槽位存在，扩散后端未编译 |
| 决策模型 | `decisionModelId` | ✅ **独立引擎**，见 §4.7 |
| 语音 / TTS | `voiceId` | ✅ 系统 TTS + **神经音色**（见 §4.8） |

辅助开关：`autoDescribeImages`、`visionAsChat`。

> ⚠️ **识图槽位是虚的**：`visionModelId` 只在 `ModelRoutingSection` 与对话详情里
> 读写，**没有任何服务消费它**。`llama_jni.cpp` 里没有 mmproj / clip / mtmd 相关代码，
> `LlamaBridge` 也没有对应的 `external fun`。要做识图得从 C++ 加 `libmtmd` 支持
> 一路做到 Kotlin 侧的图片预处理 —— 那是**一整条链路**，不是接线问题。

会话级可覆盖全局（`Conversation.chatModelId` 等，空串 = 跟随全局），
所以「这个会话用 Qwen，那个用 DeepSeek」是支持的。

**配置入口**：`ui/components/ModelRoutingSection.kt` —— 五个槽位共用一个菜单，
被 `SettingsScreen`（活的）使用。决策槽位必须走 `setDecisionModel()`，
因为它要在专用引擎上做加载/卸载，不能只写路由字段。

### 4.4 人设与头像

`Persona`（`data/Models.kt:93`）关键字段：
`name` / `tagline` / `systemPrompt` / `avatarEmoji` / `avatarImage` / `avatarColor` /
`voiceId` / `temperature` / `topP` / `maxTokens` / `humanMind` / `humanMindIntensity` /
`age` / `gender` / `pronouns` / `mode`。

`UserProfile`：`nickname` / `avatarEmoji` / `avatarColor` / `avatarImage` / `gender` / `onboarded`。

头像三件套（`ui/screen/PersonaEditorSheet.kt`）：
- `PersonaAvatar`（:356）—— 有照片显示照片，否则 emoji（emoji 永不清空，作为兜底）
- `AvatarEditor`（:406）—— 大头像 + emoji 网格 + 配色 + 拍照徽标
- `AvatarStore`（`ui/components/AvatarPicker.kt:28`）—— 相册选图 → 中心裁方 → 512px → `filesDir/avatars/`

**所有头像渲染点都必须传 `imagePath`**，漏传就会显示 emoji 而不是用户选的照片。

### 4.5 代理下载

`ProxySettings`（`data/Models.kt:360`）：`enabled` / `template` / `preferProxy`。

模板支持两个占位符：`{url}`（整条 URL）和 `{host}`（仅主机名），
所以前缀式代理（`https://proxy/{url}`）和独立 host 参数式都能表达。
`isUsable()` 要求模板含 `{url}` 且长度 > 8。

`ModelDownloader`（:107-121）把镜像列表**和它经代理的版本**合并：
`preferProxy` 决定代理排在直连**前面还是后面**，直连始终保留为回退。

> ⚠️ 内置预设里的 5 个 gh-proxy 镜像**全部已 403 失效**（实测见 [DOWNLOAD.md](DOWNLOAD.md)），
> 但模型走的是 `hf-mirror.com` 路径，不依赖这些预设。

### 4.6 生图后端接入位

`app/src/main/cpp/image_jni.cpp` 目前是**程序化渲染器**（确定性、可复现、<100ms、无需模型文件）。
真实扩散后端预留了两个 JNI 函数：`nativeSdLoad` / `nativeSdGenerate`，
`ImageBridge.hasDiffusionBackend` 报告是否已编译进来（当前默认构建：**否**）。
详见 [IMAGE_ENGINE.md](IMAGE_ENGINE.md)。

### 4.7 双引擎：决策模型独立常驻

`LlamaEngine` 是单模型设计，所以**一个实例装不下聊天模型 + 决策模型**。

| 实例 | 归属 | 用途 |
| --- | --- | --- |
| `LocalMindApp.engine` | 聊天 | 用户对话、HTTP API、语音 |
| `LocalMindApp.decisionEngine` | 决策 | 只跑 `CALL ...` 一行输出的路由判断 |

原生侧 `nativeInit()` 返回独立的 `new engine()`（`llama_jni.cpp:494`），
每个实例有自己的 model + context；`llama_backend_init()` 只在 `JNI_OnLoad` 跑一次，
本就支持多实例，因此两个模型可以同时常驻。

`DecisionEngine.routeEngine()` 的选择顺序：

1. 专用引擎已就绪 → 用它（真正的「决策模型接管」）
2. 专用引擎存在但加载失败 → 返回 null，**走规则兜底**（不静默改用聊天模型）
3. 未配置专用引擎 → 退回共享聊天引擎（等价于旧行为）

选型参数：`DECISION_CONTEXT = 1024`、`DECISION_THREADS = 2`
（`AppViewModel.kt`）—— 决策提示词很短、输出只有一行，所以上下文开小，
这是让两个模型同时常驻在手机上可承受的关键。

UI 上由 `components/ModelRoutingSection.kt` 的「决策模型」行驱动，
调用 `AppViewModel.setDecisionModel()`，并在下方显示
「内置规则 / 加载中 / 已就绪 / 已接管」的真实状态。

### 4.8 双 TTS：系统引擎常驻，神经音色按需加载

语音有**两层**，不是替换关系：

| 层 | 实现 | 何时生效 |
| --- | --- | --- |
| 系统 TTS | `TtsEngine`（`ImageEngine.kt`），`android.speech.tts` | **永远在位**，是兜底 |
| 神经音色 | `NeuralTtsEngine`（`NeuralTts.kt`），sherpa-onnx VITS | 用户下载了音色模型、且原生库打进了 APK |

`TtsEngine.attachNeural(engine)` 把神经层挂上来之后，`speak()` /
`synthesizeToFile()` 都是**先试神经、失败静默退回系统**：

```kotlin
neural?.let { n -> if (n.speak(clean, rate, pitch, volume)) return true }
// …系统 TTS 路径
```

这样设计的原因：语音是给人设加分的，不该因为一个可选组件（36 MB/ABI 的
`libonnxruntime.so`）缺失就让聊天说不出话。三种失败都要安静退化：
**库没打进 APK、模型没下载、模型文件坏了**。

为什么神经音色独立成一套而不是塞进 `TtsEngine`：`TtsEngine` 是 200 行的系统 API
外壳，而神经那套要管模型目录、下载、解压、布局解析、`AudioTrack` 播放、
WAV 导出 —— 混在一起两边都读不懂。

`VoiceSettings` 里两个字段**故意分开**：

| 字段 | 含义 | 换机型后 |
| --- | --- | --- |
| `voiceName` | 系统 TTS 引擎里的音色名 | ❌ 失效（各 ROM 命名不同） |
| `neuralVoiceId` | `VoiceCatalog` 里下载的音色 id | ✅ 不变（是模型文件，跟机型无关） |

二者同时存在，是因为神经音色加载失败时必须能退回一个**用户已经选过**的系统音色。

`NeuralVoiceModels.resolve()` **不写死文件路径**，而是扫目录找最大的 `.onnx`，
再在同级找 `tokens.txt` / `lexicon.txt` / `*.fst`。理由：sherpa-onnx 各音色包的
命名并不统一（piper 系是 `zh_CN-xiao_ya-medium.onnx`，icefall 系是 `model.onnx`），
写死等于每加一个音色都要先解包确认一遍。

详见 [BLOCKERS.md](BLOCKERS.md) 第二节（含 JNI 字段反射那个坑）。

---

## 五、构建

```bash
# 环境：JDK 17 + Android SDK 35 + NDK 27.0.12077973 + CMake 3.22.1
# 本机 JDK 在 C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot（不在 PATH，需设 JAVA_HOME）

export JAVA_HOME="C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot"
./gradlew :app:assembleDebug      # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # debug 签名，可直接侧载
./gradlew :app:assembleDebug -PabiList=x86_64   # 模拟器（最快）
```

产物：`x86_64` 单 ABI **58.8 MB**；默认 arm64-v8a + armeabi-v7a **79.7 MB**。
（含 sherpa-onnx 的 36 MB/ABI；不跑 `:app:fetchSherpaOnnx` 会小 22 MB/ABI，
神经语音自动不可用。）

> 📡 **网络**：`git clone` GitHub 直连可用；**`gh-proxy.com` 是唯一稳定可用的
> 代理**（实测 ~750 KB/s），`ghproxy.net` / `ghfast.top` / `gh.llkk.cc` 全部连接超时。
> `codeload` **忽略 `Range`**，别用 `curl -r` 试探。
> `services.gradle.org` 直连超时 → wrapper 已指向腾讯云镜像。
> 详见 [DOWNLOAD.md](DOWNLOAD.md) 与 [BUILD.md](../BUILD.md) §0。

---

## 六、API 一览（OpenAI 兼容子集）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/`、`/health` | 服务信息 / 存活与模型状态 |
| GET | `/v1/models` | 列出本机模型 |
| POST | `/v1/chat/completions` | 对话补全，`stream:true` 走 SSE |
| POST | `/v1/completions` | 旧版文本补全 |
| POST | `/v1/embeddings` | 384 维向量（本地哈希嵌入） |
| POST | `/v1/images/generations` | 文字生图，返回 PNG 链接 |
| GET | `/v1/images/{file}` | 取回图片 |
| POST | `/v1/audio/speech` | 文字转语音（WAV，带情感参数） |
| GET | `/v1/system` | 内存 / 线程 / 模型诊断 |
| POST | `/v1/admin/load` | 切换常驻模型 |

---

## 七、路线图

按依赖关系排序，详见 [STATUS.md](STATUS.md) 的差距清单：

| # | 项 | 状态 |
| --- | --- | --- |
| — | **构建与运行链路打通** | ✅ 修掉 3 处构建硬错误，模拟器实测跑通 |
| — | **TTS 神经音色** | ✅ sherpa-onnx VITS 接入并实测合成 |
| — | **死代码页面加入口** | ✅ `VoiceScreen` / `ApiScreen` 已可从「我的」与聊天页进入 |
| — | **替换已失效的 gh-proxy 预设** | ✅ 实测后从 5 个换成 3 个可用的 |
| — | **`Content-Range` 校验** | ✅ 已在位（`ModelDownloader`），并复制到 `VoiceModelDownloader` |
| 1 | **纯本地生图** | ❌ 需取回 sd.cpp 源码 + 解 ggml 目标冲突（[BLOCKERS.md](BLOCKERS.md) §1） |
| 2 | **识图（多模态）** | ❌ 需从 `llama_jni.cpp` 加 `libmtmd` 支持，跨 C++/Kotlin 一整条链路 |
| 3 | **MCP 客户端** | ❌ 只有能力声明 |

# LocalMind 进度交接 · 2026-10-15

> **给下一个对话**：这份文档是完整的交接。读完它你应该能直接接着干，不需要重新摸索。
> 项目根目录 `D:\aichat\LocalMind`。JDK 17 在 `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`。
> 构建命令见 §2。**先读 §6「踩过的坑」** —— 里面有两个会浪费你半小时的陷阱。

---

## 1. 这个项目现在是什么

LocalMind 是一个 Android 本地 AI 应用（Kotlin + Compose）。这一轮改造把它从
"什么都内置" 变成了 **纯运行时 + 插件生态**：

| | 改造前 | 现在 |
| --- | --- | --- |
| 模型库 | 34 个模型写死在 `ModelCatalog.kt`（839 行） | **空表**（166 行），模型由插件提供 |
| 语音音色 | 8 个写死在 `VoiceCatalog` enum | **运行时注册表**，由插件填充 |
| 表情包 | 无 | 插件（支持图片梗图 + emoji 兜底） |
| 界面主题 | 无 | 插件（声明式令牌，能改主色/圆角/字号/标签栏） |
| 生图后端 | 链进主库（41.66 MB） | **独立 .so，按需下载**（14.4 MB 的插件包） |
| 插件类型 | 写死的 `enum class PluginAbility` | **注册表**，加一种能力 = 表里加一行 |
| APK | 73.7 MB（最初）→ 12.16 MB | **15.70 MB**（r8debug，x86_64） |

设计原则（用户明确要求，别再往回改）：
- **主程序不带任何模型/音色/素材**，全部通过插件或运行时下载获取
- **插件是纯数据**（JSON + 资源文件），**绝不执行代码** —— 能塞代码就等于装后门
- 插件页是**骨架式**：只有「导入 zip + 已装列表 + 开关」，不做能力说明表
- 装插件**只有一个动作**：选一个 zip，类型由程序识别，不让用户先判断

---

## 2. 怎么构建与运行

```powershell
$env:JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
cd D:\aichat\LocalMind

# 编译检查（最快，4 秒）
.\gradlew.bat :app:compileDebugKotlin

# 装到模拟器（x86_64）。r8debug = 混淆 + 可调试，能用 run-as
.\gradlew.bat :app:assembleR8debug "-PabiList=x86_64"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r -t app\build\outputs\apk\r8debug\app-r8debug.apk

# 生图后端插件 zip（会重编 sd.cpp，很久）
.\gradlew.bat :app:assembleSdPlugin
```

**当前 APK 体积**：r8debug 15.70 MB / release 12.16 MB（release 未重编，可能已变）。

**模拟器**：AVD `lm35`，android-35 google_apis x86_64。
启动参数必须用 `-gpu auto -memory 4096 -cores 4`，**不能加 `-no-window`**（用户要看窗口）。

---

## 3. 插件系统（本轮的核心）

### 3.1 能力注册表

`app/src/main/java/com/localmind/data/Plugin.kt` → `PluginAbilities.ALL`

| id | 中文名 | 内容文件 | 已实现 |
| --- | --- | --- | --- |
| `emoji_pack` | 表情包 | `emoji.json` + `assets/` | ✅ |
| `model_library` | 模型库 | `models.json` | ✅ |
| `model_source` | 模型下载源 | — | ✅ |
| `tts_voice` | 语音音色 | `voices.json` | ✅ |
| `cloud_credentials` | 云端服务 | `providers.json` | ✅（部分） |
| `native_backend` | 推理后端 | `backends.json` + `lib/` | ✅ |
| `ui_theme` | 界面主题 | `ui.json` | ✅ |
| `remote_provider` / `image_backend` / `web_tool` / `mcp_server` / `persona_pack` / `api_endpoint` | — | — | ❌ 占位 |

### 3.2 安装流程

`app/src/main/java/com/localmind/ui/PluginInstall.kt` —— **唯一的安装逻辑**，
界面和诊断端点共用（留两份必然分叉）。

1. 先读一遍 zip（`assetDir = null`）拿清单 → 好确定资源目录名
2. 再带 `assetDir` / `libDir` 正式展开
3. 有 `plugin.json` 就按声明的 `abilities` 走；没有就从"包里有哪些已知文件"反推
4. 落库 + 写 `Storage`

`app/src/main/java/com/localmind/data/PluginArchive.kt` —— zip 读取。
安全边界：只认白名单文件名、拒绝 zip-slip、单文件/总量上限、不执行代码。

### 3.3 已交付的插件 zip

在 `D:\aichat\LocalMind\dist\plugins\`：

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `本地生图后端插件.zip` | 14.4 MB | 36 MB 的 `liblocalmind-sd.so`，**已实测能加载** |
| `中文表情包插件.zip` | 3.7 KB | 31 个 emoji 表情、16 标签、40 关键词 |
| `图片表情包插件.zip` | 7 KB | 6 张真 PNG 梗图（`assets/`） |
| `官方模型库插件.zip` | 12 KB | **52 个模型 + 17 个音色**（清单，不含权重） |
| `语音音色插件.zip` | 3 KB | 8 个 sherpa-onnx 离线音色 |
| `暖橙界面主题插件.zip` | 2.9 KB | 主色/圆角示例 |

源码在 `plugins-src/`（每个子目录一个插件，改完重新压 zip）。

**⚠️ zip 必须是平的** —— `plugin.json` 在根，不能套一层文件夹。
代码里虽然按 basename 取用、套层也能装，但这是**文档契约**，别依赖那个宽容度。

> 📄 **完整的插件清单（含 52 个模型逐个列表）在 [`docs/PLUGINS.md`](PLUGINS.md)**
> —— 那一份是插件相关的唯一入口文档，别在别处再抄一遍数量和体积。

---

## 3.5 第二轮：删腾讯云 TTS + 界面主题彻底重做

### 3.5.1 腾讯云 TTS：**整条移除**

见 §5.3。一句话：这个应用承诺完全离线，而云端 TTS 需要用户自己的密钥
才能验证，等于一块永远测不到的代码。**不要再加回来。**

### 3.5.2 界面主题：为什么"改了没反应"

用户的原话是"ui 模块更改有问题并且没办法彻底改风格 ui 界面之类的"。
查下来是**两个独立的毛病叠在一起**：

| 毛病 | 后果 | 修法 |
| --- | --- | --- |
| 颜色令牌只有 5 个有消费端，`IosPalette` 里另外 13 个槽位写死 | "改风格"只能改一点点，文字色/分隔线/导航栏/气泡全都动不了 | 扩到 37 个令牌，**全部接线** |
| 令牌在 `remember(dark, pluginRevision, overrideRevision)` 里算，而那两个是**普通 Int** | 换主题后界面**永远不重画**，只有整页重建才偶然生效 | 令牌改成 `AppViewModel.uiThemeState`（StateFlow）单一路径 |

细节见 §6.3 / §6.4 —— 那两个坑值得单独读一遍。

### 3.5.3 主题现在怎么用（三种方式，优先级从低到高）

1. **内置预设**（应用里「我的 → 主题 → 快捷风格」）：暖橙 / 极光 / 纸张 / 深海。
   点一下就换整套配色，不用装任何东西。这是"想改风格"最快的路径。
2. **界面插件**（`ui_theme` zip）：作者可以做完整的主题包。
3. **用户微调**（同一页的「微调」）：主色 / 圆角 / 字号 / 标签栏，改一项只影响一项。

预设和微调写进的都是**同一份** `Storage.themeOverrides`（用户手动覆盖），
所以它们能盖住插件、也能和插件叠加。规则只有一条：
**内置 iOS 原色 → 插件令牌 → 用户覆盖，后面的盖前面的。**

### 3.5.4 顺手加的一个诊断端点

见 §7 第 5 条：`POST /v1/debug/theme`。它能读/套预设/改单项/清空，
并返回**当前真正生效**的令牌表 —— 验证主题不用再点五六下 UI 加人眼比对。

---
## 3.6 第三轮：模型库 v2 + 多模态两用 + 语音分类 + 决策模型补齐

### 3.6.1 模型库插件 v2.1：52 个模型 + 17 个音色

从 `docs/MODEL-CANDIDATES.md`（上一轮的调研档案，57 条地址逐条 HEAD 验证过）
生成，不是手抄的。清单和数量见 [`docs/PLUGINS.md`](PLUGINS.md) §二。

### 3.6.2 ⚠️ 多模态模型必须同时出现在「对话」和「看图」

用户指出的一个**白花流量**的 bug：Qwen3.5 / Gemma 4 / InternVL 这些是
**一个 GGUF 两用**（加 mmproj 能看图，不加也能纯文字对话）。而原文
`modelOptions()` 的对话槽位写的是 `!it.vision` —— 把它从对话模型里排掉了。
结果：用户下了 3 GB 的 Qwen3.5，在「对话模型」里找不到它，只能再下一个纯文字模型。

**判据现在是**：对话槽位只排除 `decisionModel` / `isImageModel` / `embedding` / `voice`，
**不排除** `vision`；识图槽位只列 `vision`。两处判据都在 `modelOptions()` 里，
别在界面里另写一份。

### 3.6.3 分类筛选从横滑改成自动换行

12 个分类原来在一个 `LazyRow` 里 —— 后面几个（含「语音」）要**横滑**才看得到，
而横滑这一行没有任何视觉提示。用户的原话是"语音 tts 模型为 0"，其实是他没
发现那个分类可以滑动。改成 `FlowRow` 换行：多占一行高度，换来全部分类一眼可见。

> **教训**：横向滚动的筛选/标签行看起来省地方，但**隐藏了内容**。
> 只要分类数 > 5 就优先考虑换行。

### 3.6.4 模型页的「语音」分类来自插件，下载走音色下载器

用户要"模型库里能看到语音模型"。做法：

* 插件的 `voices.json` 提供音色（和 `models.json` 同一个 zip，两个能力）；
* `VoiceEntry.toLocalModel()` 把它映射成一条**只用于展示**的 `LocalModel`（`voice = true`）；
* 模型页的下载按钮统一走 `AppViewModel.downloadAny()`，它按 `voice` 标记分流 ——
  音色必须解压到 `files/voices/`，走 GGUF 下载器只会得到一个加载不了的 `.tar.bz2`。

### 3.6.5 决策模型补上「下载页面」

上一版把 `gutsy`（Jev 形态、校准概率）当成"基线已有"跳过了，但它其实
**两条路径都没有** —— 模型页和决策模型 picker 里都看不到。现在收进插件：

| 新增 | 体积 | 为什么是它 |
| --- | --- | --- |
| Gutsy 0.8B（校准概率） | 505 MB | 决策类里**唯一**输出概率分布（形态 c）的，最对口 |
| FunctionGemma 270M | 241 MB | Google 端侧 function calling，亚秒级 |

加上原有的 Qwen3Guard / Granite Guardian / Hammer / xLAM / Arch-Router 与
「也能决策」的 Granite 4.0 Micro、ERNIE 0.3B，决策分类现在 **9 条**。

### 3.6.6 Ollama / GitHub 链接（只用于"去看看"）

18 个模型条目带 `sourceUrl`，指向 `ollama.com/library` 上对应的系列页。

> ⚠️ **Ollama 的地址不能当下载源**：它的 blob 是内容寻址格式（要 manifest 才能拼），
> 不是能直接喂 llama.cpp 的单文件 GGUF。所以这个字段只用于"去看看还有哪些尺寸"，
> 下载仍走 `url`（hf-mirror 的单文件 GGUF）。

### 3.6.7 线程数改成按大核数取

原来自动线程数是 `(cores - 1).coerceIn(2, 6)` —— 在 8 核机器上给 6 个线程，
而手机是大小核，矩阵乘是**内存带宽瓶颈**，摊到小核上反而更慢。
现在：`cores <= 4 → cores, 否则 4`（实测经验值），上限 8，用户仍可在设置里写死。

**多模型并发本来就有**（这条不用改）：决策模型跑在**独立的第二个引擎实例**上，
和主对话引擎互不阻塞；主引擎忙时决策走内置规则兜底（`decisionUsingDedicatedModel()`）。

---
## 4. 第一轮改动的文件（按重要性）

### 新增

| 文件 | 作用 |
| --- | --- |
| `data/UiTheme.kt` | 界面插件格式 + 令牌白名单（`UiTokens.ALL`）+ 夹取逻辑 |
| `data/VoicePack.kt` | 语音插件格式；`VoiceDeployment`（本地/云端） |
| `data/PluginArchive.kt` | zip 读取（本轮重写过，加了 `lib/` 支持） |
| `ui/PluginInstall.kt` | 统一安装逻辑 |
| `ui/screen/ThemeScreen.kt` | 主题设置页（从「我的」进，仅装了界面插件时出现） |
| `service/DiffusionModels.kt` | **读 GGUF 文件头**判断是不是扩散模型 |
| `service/NativeLibraryInstaller.kt` | 原生库安装/校验/加载 |
| `service/TencentTts.kt` | 腾讯云 TTS（自己实现 TC3 签名，不引 SDK） |
| `cpp/sd-alone/sd_jni.cpp` | 生图后端的独立 JNI 层（4 个入口） |
| `tools/gguf_kind.py` | 独立的 GGUF 头校验器（交叉验证 Kotlin 侧判据） |
| `tools/make_fake_gguf.py` | 造合成 GGUF 样本，测试判据的两条分支 |

### 重写 / 大改

| 文件 | 改动 |
| --- | --- |
| `data/Plugin.kt` | `enum PluginAbility` → 注册表 `PluginAbilities` |
| `data/VoiceCatalog.kt` | 写死 enum → 插件填充的运行时注册表 |
| `data/ModelCatalog.kt` | 839 → 166 行，`models = emptyList()` |
| `data/EmojiPack.kt` | `EmojiPack` → `StickerPack`，**图片优先** |
| `service/EmojiResolver.kt` | `EmojiResolver` → `StickerResolver` |
| `ui/screen/PluginsScreen.kt` | 618 → 290 行骨架式 |
| `ui/theme/IosTheme.kt` | `IosTypography` object → `IosTypographySet` data class + CompositionLocal |
| `ui/LocalMindRoot.kt` | 沉浸式对话 + 插件页提到根层 |
| `cpp/image_jni.cpp` | **移除全部 SD 代码**（只有程序化渲染器） |
| `cpp/CMakeLists.txt` | 不再链生图静态库；删掉 `GGML_MAX_NAME` 全局定义 |
| `app/build.gradle.kts` | 加 `assembleSdPlugin` 任务 |

### 第二轮改动的文件（2026-10-15 第二段）

| 文件 | 改动 |
| --- | --- |
| `data/UiTheme.kt` | 令牌 16 → **37 个**（24 色）；删掉 3 个 `*Dark` 死键；加 `rgba()` 写法；加 `UiPresets`（4 个内置预设） |
| `ui/theme/IosTheme.kt` | 令牌 → `IosPalette` 全量映射；新增 `ThemeState` + `LocalUiTheme`；**删掉** `rememberPluginUiTokens`（就是它导致主题不生效） |
| `ui/theme/IosTheme.kt` | `IosPalette` 加 `onAccent` / `selection` / 5 个聊天区字段；`IosMetrics` 加 `bubbleCorner` / `barBorderWidth` / `tabIconSize` |
| `ui/AppViewModel.kt` | `_uiThemeState`（唯一真相来源）+ `uiThemeState` StateFlow；`applyUiPreset`；收集两个 revision Flow；删除全部腾讯云 TTS 代码 |
| `ui/screen/ThemeScreen.kt` | 加「快捷风格」（4 预设 + 恢复默认）和「微调」（主色/圆角/字号/标签栏 chip） |
| `ui/components/IosComponents.kt` | 写死的 `Color.White` → `ios.onAccent`；加 `onColorFor()` 亮度挑色；滑块/开关/徽标跟主题 |
| `ui/components/IosScaffold.kt` | 导航栏读 `metrics.navBarHeight` / `barBorderWidth`；标题位置读 `navTitleAlign`；**删掉**没人用的旧 `GlassTabBar`（119 行） |
| `ui/components/LiquidGlassTabBar.kt` | 高度 / 图标大小 / 选中色 (`selection`) / 高光全部走令牌 |
| `ui/screen/ChatScreen.kt` | 用户气泡 / AI 气泡 / 输入框 / 代码块 / 各按钮全部走令牌；标题读主题的 `appName` |
| `ui/LocalMindRoot.kt` | 主题从 `LocalUiTheme` 读（不再收集第二个 StateFlow）；Toast 底色走 `overlay` |
| `LocalMindApp.kt` | `pluginRevision` / `overrideRevision` 从 `@Volatile Int` → `MutableStateFlow<Int>`（见 §6.3） |
| `service/ApiServer.kt` | 加 `POST /v1/debug/theme`（读/套预设/改单项/清空） |
| `data/VoicePack.kt` | 删 `VoiceDeployment` / `cloudVoiceId` / `provider`；跳过老插件里的云端音色并如实报告 |
| `data/Models.kt` | 删 `CloudTtsSettings` 与 `VoiceSettings.cloudTts` |
| `data/Plugin.kt` | 删 `cloud_credentials` 能力（它唯一的消费者是腾讯云 TTS） |
| `service/TencentTts.kt` | **删除整个文件** |

### 331 处批量替换

`IosTypography.<样式>` → `IosTheme.typography.<样式>`（19 个文件）。
原因见 §6 的第一个坑。

---

## 5. 还没做的（按优先级）

### 5.1 识图（多模态）—— **今天不可能生效**

- `AppViewModel.kt` 里 `canSeeImages` **写死 false**
- `LocalModel` 只有**一个** `fileName`/`url`，**没有 mmproj 字段** → 下载器拿不到投影层
- 需要：`llama_jni.cpp` 接 `libmtmd` + `LlamaBridge` 加 external funs +
  `LocalModel` 加 `mmprojFileName` / `mmprojUrl`
- `docs/MODEL-CANDIDATES.md` 已经备好 11 个识图模型 / 22 个文件（主 GGUF + mmproj
  的文件名和字节数都有），可以直接用

### 5.2 语音只支持 VITS

- `NeuralTts.kt` 里只构造 `vits = OfflineTtsVitsModelConfig(...)`
- Matcha / Kokoro / ZipVoice / Kitten / Pocket / Supertonic 的类在 `.so` 里，
  但需要加分支；Matcha/Kokoro 还会破坏"扫目录找最大 .onnx"那套布局识别
- `voices.json` 的 `url` / `mirrors` **仍然被忽略**，所以每个 `id` 必须等于官方
  tarball 名（`VoiceModelDownloader` 从 id 拼地址）

### 5.3 ~~腾讯云 TTS~~ —— **已按用户要求整条移除**（2026-10-15）

删掉的东西（一个都不剩，别去找）：

| 删了什么 | 位置 |
| --- | --- |
| TC3-HMAC-SHA256 签名 + TextToVoice 调用 | `service/TencentTts.kt`（**整个文件删了**） |
| `CloudTtsSettings`（密钥 / region / voiceType / modelType） | `data/Models.kt` |
| `VoiceDeployment` 枚举、`cloudVoiceId` / `provider` 字段 | `data/VoicePack.kt` |
| 云端播放器、`speakCloud` / `testCloudTts` / 流式朗读的云端分支 | `ui/AppViewModel.kt` |
| 「云端语音」区块、密钥输入框、音色横滑选择 | `ui/screen/VoiceScreen.kt` |
| `cloud_credentials` 能力（它唯一的消费者就是腾讯云 TTS） | `data/Plugin.kt` |
| 云端的「本地/云端」橙色徽标 | `ui/screen/VoiceScreen.kt` |

**理由**（用户的选择，不要再加回来）：这个应用的核心承诺是**完全离线**；
而云端 TTS 需要用户自己的密钥才能验证，等于一块永远测不到的代码。
现在只有本地音色：模型跑在手机上，断网能用，文字不出设备。

> ⚠️ 老插件如果还写着 `"provider": "tencent"` / `"deployment": "cloud"`，
> 那些音色会在安装时**被跳过**，并在安装报告里说明跳过了几个。
> 这是刻意的 —— 静默降级成一个"能点但发音不对"的本地音色是最糟的结果。
> 实现见 `VoicePacks.parse` 的 `droppedCloud`。

### 5.4 生图从未真正出过图

- 后端能加载（已实测），但**本机没有任何 SD checkpoint**
- 需要下 1.5–6 GB 的模型才能验证 `nativeSdGenerate` 的成功路径
- 建议从 SD-Turbo / LCM 系列开始（4 步出图）；标准 SD1.5 要 20 步

### 5.5 ~~界面插件的令牌还有没接线的~~ —— **已全部接线**（2026-10-15）

这一节原来列着 6 个"没接线"的令牌。现在**一个都没有了**：令牌从 16 个扩到
**37 个，全部有消费端**，其中 24 个是颜色（文字三级、分隔线、导航栏、卡片描边、
聊天气泡、输入框、代码块、浮层遮罩……）。三个从没生效过的 `*Dark` 键已删除。

完整清单看 `docs/UI-PLUGIN.md` §4.1（那张表是从源码生成的，不会过时）。

**为什么原来会"改了没反应"**（这条比令牌表重要，见 §6.6）。

### 5.6 其它

- `MODEL_SOURCE` 插件的消费端要复查一遍
- `docs/PLUGIN.md` §8.3 / §15 有三处过时结论（子代理指出），需要和 `UI-PLUGIN.md` 对齐

---

## 6. 踩过的坑（**先读这段，能省你半小时**）

### 6.1 ⚠️ Kotlin 块注释**可嵌套** —— `/*` 会吃掉整个文件

这条坑我踩了一次，症状极其误导：报一堆 `Unclosed comment` 和"参数解析错误"，
而报错行号指向完全无关的地方（`data class` 的参数上）。

原因：文件注释里写了 `assets/*` 这种路径通配。那三个字符在 Kotlin 里
**开启一个新的注释层**，于是后面整个文件都被注释掉了。

**规矩**：源码里**永远不要**写出「斜杠 + 星号」连在一起的字面量。
仓库里的规避写法：

```kotlin
// Plugin.kt 里拼通配符
private const val ASSET_WILDCARD = "assets/"
contentFiles = setOf("emoji.json", ASSET_WILDCARD + "*")

// Plugin.kt 里判断通配符
if (pattern.endsWith("*")) { zipPath.startsWith(pattern.dropLast(1)) }
```

（注：**字符串字面量里**其实是安全的，不安全的是**块注释里**。
但既然注释里写了会中招，统一避开最省事。）

### 6.2 ⚠️ `StateFlow` 字段必须声明在 `init` **之前**

Kotlin 按**书写顺序**初始化属性。`AppViewModel.init` 会调 `refreshVoiceCatalog()`
/ `refreshUiTheme()` / `restoreNeuralVoice()`，所以任何被它们碰到的状态字段
**必须声明在 init 之前**，否则：

```
NullPointerException: Attempt to invoke interface method
'void kotlinx.coroutines.flow.MutableStateFlow.setValue(java.lang.Object)'
on a null object reference
```

**这个坑踩了两次**，两次都是把新字段追加到文件末尾造成的。

现在有护栏：`AppViewModel.assertFieldsInitialized()` 在 init 第一行执行，
会把关键字段逐个读一遍，报出**是哪个字段**而不是一个指向 `setValue` 的栈。

**规矩**：新状态加到文件顶部「插件驱动的状态」那一段（约 L374-410），
并在 `assertFieldsInitialized` 的列表里补一项。

### 6.3 ⚠️ `remember(...)` 的键必须是 Compose 状态 —— 否则界面永远不更新

**这条是"界面主题改了没反应"的真正原因**，排查了半天才定位。

原来的写法（`IosTheme.kt` 里那个已经删掉的 `rememberPluginUiTokens`）：

```kotlin
// LocalMindApp 上：
@Volatile var pluginRevision: Int = 0      // ← 普通变量，不是 State

// 界面里：
remember(dark, pluginRevision, overrideRevision) { 算令牌() }
```

**在读参数的位置读一个普通变量，不会建立任何订阅。** Compose 看到的是三个
普通值，`remember` 只在第一次组合时算一次，之后哪怕 `pluginRevision` 变成 99
也没人通知它重算。

症状非常有误导性：主题文件是对的、`UiThemes.resolve` 是对的、安装报告也是对的，
但界面不变 —— 而**切换一次深浅色**（整页重建）就"突然生效"了。
看到"重启/切主题模式之后才对"这类症状，第一个怀疑对象就是它。

现在的规矩：

* 想让界面跟着变，值必须是 `State` / `StateFlow` / `mutableStateOf`；
* 版本号计数器一律用 `MutableStateFlow<Int>`（见 `LocalMindApp.pluginRevisionFlow`）；
* 主题这种"多处要读"的东西只留**一条**解析路径：`AppViewModel.uiThemeState`，
  界面 `collectAsState()` 它。**不要再写第二个解析器** —— 两份结果一定会分叉。

### 6.4 ⚠️ 改了存储不等于界面知道（同一个病的另一面）

`overrideRevision` 原来**从来没有被 bump 过**（`bumpOverrideRevision()` 定义了
但零调用点）。所以即使订阅是对的地方，也永远不会被触发。

现在 `AppViewModel` 在 `init` 里直接收集 `pluginRevisionFlow` / `overrideRevisionFlow`，
**任何来源**改了插件清单或用户覆盖（界面、诊断端点、以后的后台服务）都会重算主题。
加新的"改主题"入口时：写存储 → bump revision → 完事，不要自己去调 `refreshUiTheme()`。

### 6.5 两个 ggml 的 ABI 不同（已解决，但要知道历史）

sd.cpp 和 llama.cpp 各带一份 ggml。sd.cpp 编它的 ggml 时带
`-DGGML_MAX_NAME=160`，llama 用默认 64。而 `char name[GGML_MAX_NAME]` 是
`struct ggml_tensor` 的**真实字段** → 两边 `sizeof` 差 96 字节，
`extra` / `view_src` 落在不同偏移上。

**这不报链接错误，只是静默的内存错乱。**

解决方案是拆成两个 `.so`（bionic 解析符号时先看库自己）。
`cpp/CMakeLists.txt` 里现在**故意不设** `GGML_MAX_NAME` —— 那段注释说明了
"删掉一个看起来没用的宏"很容易被当成清理，但它是**前提变了**，不是没用。

### 6.6 模拟器 UI 自动化很脆

- 文件选择器（SAF）**驱动不了** —— 所以加了 `POST /v1/debug/plugin` 和
  `POST /v1/debug/backend` 两个诊断端点，把插件/后端安装脚本化
- `adb exec-out` 经 PowerShell 重定向会被转成 UTF-16，**要用 `adb pull`**
- `input text` 发中文要用百分号编码，且经常丢字
- `uiautomator dump` 显示中文是乱码（只是显示问题，文件内容是对的）
- **最简单可靠的方式是让用户自己点** —— 用户明确说过"有些东西不要那么麻烦
  直接给我说让我做就行"

### 6.7 中文源码文件的编码

用 shell 命令写 Kotlin 文件时，中文容易被写坏。**用工具（write/edit）而不是
PowerShell 内联 heredoc**。PowerShell 控制台**显示**中文乱码是正常的，
文件内容是对的 —— 判断编码要用 `[System.IO.File]::ReadAllText` 或 grep 工具。

---

## 7. 诊断工具（不用点 UI 就能测）

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
# 1) 先让 API 服务跑起来（manifest 里已有 /v1/debug/* 端点）
& $adb forward tcp:18080 tcp:8080

# 2) 健康检查
Invoke-WebRequest http://127.0.0.1:18080/health

# 3) 装插件 zip（绕过文件选择器）
Invoke-WebRequest -Method POST http://127.0.0.1:18080/v1/debug/plugin `
  -ContentType 'application/json' -Body '{"path":"/data/local/tmp/x.zip"}'

# 4) 装并加载原生后端（会真的 System.load，返回 load_ok / probe / info）
Invoke-WebRequest -Method POST http://127.0.0.1:18080/v1/debug/backend `
  -ContentType 'application/json' -Body '{"path":"/data/local/tmp/liblocalmind-sd.so"}'

# 5) 界面主题：读 / 套预设 / 改单项 / 清空（返回**当前真正生效**的令牌）
Invoke-WebRequest -Method POST http://127.0.0.1:18080/v1/debug/theme '
  -ContentType 'application/json' -Body '{}'                       # 只读
Invoke-WebRequest -Method POST http://127.0.0.1:18080/v1/debug/theme '
  -ContentType 'application/json' -Body '{"preset":"earth"}'        # earth/aurora/paper/night
Invoke-WebRequest -Method POST http://127.0.0.1:18080/v1/debug/theme '
  -ContentType 'application/json' -Body '{"set":{"accent":"#C2255C"}}'
Invoke-WebRequest -Method POST http://127.0.0.1:18080/v1/debug/theme '
  -ContentType 'application/json' -Body '{"clear":true}'

# 6) GGUF 头判据的独立校验（Python）
python tools\gguf_kind.py <file.gguf>
python tools\make_fake_gguf.py diffusion out.gguf   # 造样本
```

API 设置如果没落盘，可以注入 prefs（见 `shared_prefs/localmind.xml` 的 `api` key）：

```json
{"enabled":true,"port":8080,"bindLan":false,"apiKey":"","allowModelSwitch":true,
 "allowImage":true,"maxConcurrent":2,"requestLogSize":200,"corsOrigin":"*"}
```

---

## 8. 用户偏好（重要，照着做）

- **要快，少啰嗦**。不要长篇解释，不要复述他刚说过的话
- **不要过度自动化**：需要他点的地方直接说"请你点这里"，别硬写自动化脚本
- **iOS 26 观感**：`Motion.smooth/snappy/bouncy/gentle`、`pressScale`、`appearIn`
  已经在 `ui/motion/Motion.kt`，新界面要用上
- **不要多一层 UI**：整页的东西必须在**根层**渲染（`LocalMindRoot`），
  画在子组件里会出现"两条导航栏叠着"
- **体积敏感**：能运行时下载的就别塞包里
- **本地/云端必须标注** —— ⚠️ 这条现在只剩一半适用：云端 TTS 已经按用户要求
  整条删掉（§5.3），所以**今天没有"云端"这个概念**了。将来如果加回任何
  会把用户数据送出设备的功能，仍然必须按这条标注
- **诚实**：功能没做完就说没做完，别让用户以为是模型笨
  （例：发图那条现在直接写"当前版本还不能识图"）
- **主题不能只是"能改"，要"改得动"**：用户提"改风格"时，他要的是**立刻看到
  变化**。所以内置预设 + 微调面板 > 让他写 zip；而且任何新令牌都必须同时有
  消费端，否则就是在造下一个"改了没反应"（§6.3 / §6.4）

---

## 9. 相关的文档

| 文件 | 内容 |
| --- | --- |
| `docs/PLUGINS.md` | **插件总览**：6 个已交付插件 + 52 个模型清单 + 能力注册表 + 安全边界 |
| `docs/PLUGIN.md` | 插件开发完整文档（1177 行）⚠️ §8.3/§15 有过时结论 |
| `docs/UI-PLUGIN.md` | 界面插件规范。§4.1 的令牌表**是从源码生成的**，以它为准；§14 的"提案"部分已部分落地 |
| `docs/MODEL-CANDIDATES.md` | 模型候选清单（473 行，103 个新模型，URL 已逐个实测） |
| `docs/STATUS.md` | 构建状态与体积 |
| `docs/BLOCKERS.md` | 阻塞项 |
| `docs/PROJECT.md` / `BUILD.md` / `DOWNLOAD.md` | 项目/构建/下载说明 |
| `docs/IMAGE_ENGINE.md` | 生图引擎说明 ⚠️ 部分已过时（后端已拆成插件） |

---

## 10. 关键数据（备查）

**体积演进**：73.7 MB（最初）→ 12.16 MB（R8 + 精简图标 + 剥离 sherpa）
→ 51.95 MB（误把 SD 链进主库）→ **15.70 MB**（SD 拆成插件后）

**实测通过的**：
- 生图后端：`load_ok: true`、`probe: true`、`info: SSE3 | SSSE3 | REPACK`
- 语音神经音色：xia_ya 4.49 s WAV / 198 KB / RMS 2097 / 98% 非零；
  bronya 2.26 s，合成 1.9 s（快于实时）；含直连失败 → `gh-proxy.com` 回退
- 原生库运行时下载：`原生库就位：x86_64，34 MB`
- 代理测速：`gh-proxy.com` ~750 KB/s、`hk.gh-proxy.com` ~450 KB/s、
  `down.npee.cn` ~150 KB/s；`hf-mirror.com` ~2.8 MB/s；
  `ghproxy.net` / `ghfast.top` / `gh.llkk.cc` 等 12 个全部超时（已从预设移除）

**GGUF 判据的 5 个测试样本**（全部通过）：

| 样本 | 期望 | 实测 |
| --- | --- | --- |
| 合成的扩散模型头 | DIFFUSION | DIFFUSION ✅ |
| 合成的文本模型头 | TEXT | TEXT ✅ |
| 带嵌套数组的 | TEXT | TEXT ✅ |
| 非 GGUF 文件 | UNKNOWN | UNKNOWN ✅ |
| 空 GGUF | UNKNOWN | UNKNOWN ✅ |
| 真实 Qwen3-0.6B | TEXT | TEXT ✅ |

**已确认无效的槽位**：`ImageSettings.externalEndpoint` / `externalApiKey`
从没被消费；`visionModelId` 没有消费者。

### 第二轮的数据

**令牌**：16 → **37 个**（24 色 / 11 数字 / 1 开关 / 2 枚举）。删掉 3 个死键。
**主题解析路径**：3 条 → **1 条**（`AppViewModel.uiThemeState`）。

**实测过的**（通过 `POST /v1/debug/theme`）：

| 项 | 结果 |
| --- | --- |
| `{}` 只读 | `token_count: 0`、`overrides: 0`（没装主题时的基线，正确） |
| `{"preset":"earth"}` | `overrides: 21`、`token_count: 21` |
| 发现并修掉的 bug | 预设原来把 `tokens + darkTokens` 合并存进覆盖表，**深色值会盖掉浅色值** → 浅色模式下整页发黑。现在深色项存成 `dark:键名`，只在深色模式取用（`UiPresets.Preset.overrideEntries`） |

---

## 11. 第四轮（2026-10-16）：对话设置拆成两页 + 记忆系统补强

用户的原话：“对话单独设置的界面应该不同啊，ai 对话是一个设置界面，人设聊天是一个设置界面，
并且 ai 对话是无人设的，直接用模型调用保证最快回应……我的里面那些 ai 设置就别显示了……
添加对话是先显示人设对话和 ai 对话再创建，创建好就无法更改……人设对话那种人机恋思路，
记忆系统啥的都好好写。”

### 11.1 核心原则（这一轮定下来的，别再往回改）

| 原则 | 含义 |
| --- | --- |
| **对话只有两种，建的时候选，之后不能改** | AI 对话（无人设/无记忆/最快）与人设聊天（人机恋那套）。改类型要回答“已有的记忆怎么办”，所以不给开关 |
| **语言模型的每一样东西都是按对话的** | 用哪个模型、系统提示词、采样参数、能力、多重思考、短话连发 —— 全部挂在 Conversation 上 |
| **「我的」只放设备级的东西** | 性能/内存、外观、下载源、诊断、能力开关（联网/Agent/Shell）。生成参数、系统提示词、模型分工**已整块撤掉** |
| **拟人项只出现在人设聊天里** | 表情包、主动找你、情绪、记忆、经验、疑问 —— AI 对话那页一个都不显示 |

### 11.2 改了什么

**新增文件**

| 文件 | 作用 |
| --- | --- |
| ui/screen/ConversationSettings.kt | ConversationDetailScreen 变成**分流口**；共享部件：SettingsNavBar / SettingsPage / ConversationModelRow / DangerRow / ThinkLine |
| ui/screen/AiChatSettingsScreen.kt | AI 对话设置页：类型徽标、对话模型、工具性能力、提示词、采样、清理 |
| ui/screen/PersonaChatSettingsScreen.kt | 人设聊天设置页：它是谁、相处方式、状态与记忆、它在想什么、能力、四个模型、额外要求、清理 |

**数据结构**（data/Models.kt 的 Conversation）

| 新字段 | 语义 |
| --- | --- |
| systemPrompt | 这个对话自己的提示词（AI 对话=全部指令；人设聊天=追加要求） |
| decisionModelId | 这个对话的决策模型（跑在独立引擎上） |
| deepThinking | 多重思考，null=关（原来是全局 GenerationSettings.deepThinking） |
| burstReplies | 短话连发，null=跟类型走（人设聊天开） |

**修掉的三个真 bug**（都不是新功能，是“选了没反应”那类）

| 症状 | 根因 | 修法 |
| --- | --- | --- |
| 对话里选「本对话用的模型」完全没反应 | Conversation.effectiveChatModel() 定义了但**零调用点**，send() 永远用碰巧加载在内存里的那个 | send() 解析目标模型；是本地且未加载就 engine.load()，是 API 就走 remote。选模型时 selectConversationChatModel 会**预热加载** |
| 改个名字，对话悄悄从「当人」变成 AI 对话，关掉的表情包又开了 | 人设编辑器保存时走 adoptPersona，它连 mode/abilities 一起重置 | 新增 Conversation.adoptIdentity()，编辑已有对话只拷身份字段 |
| 人设聊天里写「额外的要求」不生效 | buildSystemPrompt(base, …) 里 persona.systemPrompt.ifBlank { base } —— 人设正文非空时 base 被整个丢掉 | 额外要求改成**显式追加**一节，不走那个 base |

**AI 对话的“最快”具体快在哪**

1. 不召回记忆（原来的 recallByMeaning 可能触发一次模型推理）；
2. onTurnComplete 钩子直接返回 —— 不跑 afterTurn（情绪漂移 + 记忆抽取，后者要调模型）；
3. 系统提示词只有一句内置默认（AI_CHAT_DEFAULT_PROMPT）或用户自己写的那句；
4. 不拼人设、不拼情绪、不拼表情包指令、不拼短话连发指令；
5. 采样基线是 GenerationSettings() 出厂值，不读人设覆盖。

> ⚠️ 第 2 条最容易漏：engine.onTurnComplete 是**引擎级**钩子，HTTP API 也走它。
> 每轮回答完再偷偷跑一次模型调用，用户看不见，只会觉得“慢”。

### 11.3 人设聊天（人机恋）这轮补的东西

| 补什么 | 在哪 | 为什么 |
| --- | --- | --- |
| **时间感** | CompanionPrompt.build：现在是清晨/上午/…/深夜（带小时），认识多少天 | 人活在具体时刻里。缺了这层，“在干嘛”只能答“我在这儿陪你” |
| **记忆的相对时间锚** | 同上：召回的记忆带（前几天）（agoText()） | 人回忆时天然带时间（“你上次说…”）。只给事实不给时间，回忆就变成数据库查询 |
| **主动来找你说话** | AppViewModel.maybeProactiveOpening，openConversation 时触发 | 这个开关以前**没有任何消费端**（AiAbilities.proactive 是死字段）。现在：上一句是你说的，或隔了 8 小时以上，它会先开口。空对话不主动（还不认识就先搭话是推销不是恋爱）；引擎忙时不主动（抢锁只会让你发的话卡住）。生成走 notifyTurnComplete = false —— 主动开口不是一轮对话，绝不能把上一条用户消息再学一遍 |

### 11.4 踩坑

* **UI 文案里不要写 Markdown 的星号强调** —— 那些 Text 不渲染 Markdown，用户看到的是字面上的星号。要强调就用「」。
* **「全局」这个词在新架构里要清干净**：OverrideSlider 原来标「（全局）」，而现在根本没有全局值可跟随，标签已改成「（默认）」。
* **adoptPersona 只适合“建对话”**，任何“编辑已有对话”的地方都要用 adoptIdentity。

### 11.5 实测（模拟器 lm35 / x86_64）

* 新建对话弹窗：AI 对话（无人设、不留记忆，直接调模型 —— 最快）/ 人设聊天（有名字、有记忆、有情绪），带一句“类型建好之后不能改”。
* 两种对话的设置页**标题与栏目完全不同**：AI 对话设置 没有人设/记忆/表情包/主动；人设聊天设置 有“这个 AI 是谁”“相处方式（拟人强度 60% / 短话连发 / 多重思考）”“它现在的状态（亲密度 15 / 信任 30 / 熟悉度 5 …）”。
* 对话列表每一行带类型徽标（AI 蓝 / 人设 粉）。

---

## 12. 第五轮（2026-10-16）：人设聊天的「设备感知」——电量 / 位置 / 天气

用户的原话：“人机恋的话 让 ai 能读电量和地址定位吧 ai 可选读取并不是每回合要读，
还能通过设备查询用户的所在地天气，尽量直接用设备接口读取，api 也可以，要适配多机型和品牌，
这些功能也是可选的打开。”

### 12.1 设计定下来的三条

| 原则 | 含义 |
| --- | --- |
| **按需读，不是每回合读** | `DeviceSense.needs(text, enabled)` 用规则判断这句话有没有聊到电量/位置/天气，聊到才读。规则零成本、不会失败 |
| **经纬度不给模型** | 给模型的是「浙江省杭州市西湖区」这种人话。经纬度只用于查天气那一次网络请求 |
| **读不到就不注入** | 权限被拒 / 定位关着 / 没网络 / 没 Geocoder 后端 → 返回 null，**什么都不加**，绝不塞“读取失败”进提示词 |

### 12.2 新增

| 文件 | 作用 |
| --- | --- |
| `service/DeviceSense.kt` | 电量 / 位置 / 天气 的唯一入口 + 规则触发 + 缓存 + 提示词拼装 |

**数据结构**：`AiAbilities` 加了 `battery` / `location` / `weather`（对话级，默认全关）。
`allOn()`（“全部打开”）**故意不带它们** —— 那是表达方式的开关，不是把位置交出去的一键同意。

**UI**：人设聊天设置页新增「它能感知到什么」：三个开关 + **「现在读一次看看」**。
最后那个按钮和聊天路径共用同一套读取函数，所以它显示的就是模型会看到的东西 ——
定位/天气“读不到”的原因太多（权限、开关、信号、网络），没有这个按钮用户只能靠猜。

### 12.3 多机型 / 多品牌踩过的点（都在 DeviceSense 里注释了）

| 坑 | 处理 |
| --- | --- |
| **FUSED provider 在没有 Play 服务的机器上永远不回调** | provider **并行竞速**（`firstFix`），谁先给出结果用谁。顺序等待的话坏掉的那个会把预算吃光，表现就是“某个牌子的手机读不到位置” |
| 国产 ROM 经常**没有 Geocoder 后端**（实测模拟器就是 `TimeoutException`） | 回退链：人话地名 → **天气服务返回的时区**（`Asia/Shanghai` → 「上海一带」）→ 坐标 |
| 电量字段可能缺失（温度、充电器类型） | 全部按“没有就算了”，两个来源都拿不到百分比就返回 null，不编数字 |
| `BatteryManager.isPowerSaveMode` 不存在 | 省电模式在 **PowerManager** 上（这两个类很容易记混） |
| 定位总开关被关掉 | 安静返回 null，**不弹系统设置页** |
| 历史位置 vs 新定位 | 聊天路径先用 15 分钟内的 `getLastKnownLocation`（秒回）；「现在读一次」传 `force=true`，连历史都不信，一定要一个新的 |

**Android 没有系统级天气接口** —— 各厂商的天气 App 都是自己接的服务，没有公开 API。
所以“通过设备查询天气”能做的部分是**用设备定位**，天气本身走网络（Open-Meteo，**不需要密钥**，
理由和 WebSearch 一样：任何要注册领 key 的服务都会变成“装好了功能是灰的”）。

**缓存**：电量 45 秒 / 位置 10 分钟 / 天气 25 分钟。连着问三次天气不会打三次网络。

### 12.4 实测（模拟器 lm35，adb 造的场景）

```powershell
adb shell pm grant com.localmind android.permission.ACCESS_FINE_LOCATION
adb shell settings put secure location_mode 3
adb emu geo fix 120.1551 30.2741 50     # 杭州
adb emu power capacity 23                # 电量 23%
adb emu power status discharging
```

「现在读一次看看」的结果：

```
电量：23%（未充电）
位置：上海一带                              ← Geocoder 无后端 → 时区兜底（杭州也在 Asia/Shanghai）
天气：毛毛雨 23.3℃（体感 25.3℃） 湿度72%    ← 杭州当时的真实天气
```

发一句 `is it raining outside` 之后，logcat 里能看到**注入确实发生**：

```
I DeviceSense: 注入： 【你现在能感知到的】… - 我大概在：上海一带 - 那边现在的天气：毛毛雨，23.3℃（体感 25.3℃），湿度 72%
```

模型的回复里用上了这些数字（23.3℃ / 72% 与注入完全一致）。
注意：“注入成功”和“模型说得好”是两件事 —— 450M 的小模型会把“毛毛雨”说成“阴沉、晴朗”，
那是模型能力问题，不是链路问题。**注入日志就是用来分开这两件事的**。

### 12.5 没做的

* **模型主动要求读**（模型写个标记说“我想知道你那边的天气”再补一轮）：现在是**规则触发**。
  做标记要额外一次生成，等于把那一轮的回答时间翻倍，而规则能覆盖的都是很明确的词。
  真要做的话，方向是：解析到标记 → 读 → 只补发一条短消息，不要重跑整轮。
* 天气端点还不能自定义（WebSearch 有 `webSearchEndpoint`）。如果默认端点在国内某地不通，
  下一步应该加一个 `weatherEndpoint` 到 `CapabilitySettings`，和下载源/检索端点一样的做法。

---

## 13. 第六轮（2026-10-16）：测试模式补强 + 开源声明

### 13.1 测试模式（关于页版本号连点三下进入）

| 新增/改了 | 内容 |
| --- | --- |
| **设备感知**（新块） | 「读一次」绕开对话开关把电量/位置/天气都读一遍 + 「清缓存」。对话里那三个开关回答的是「要不要用」，这一页回答的是「这台机器读不读得到」——两个问题混在一起时，读不到到底是没开还是坏了谁都说不清 |
| **存储占用**（新块） | 模型 / 图片 / 插件 / 原生库 / 诊断日志 / 合计。「手机没空间了」是最常见的反馈之一，一次列清楚省一轮来回 |
| **自检** +2 项 | 「电量读取」「定位与天气」。两项**永远返回 ok**：没权限、定位关着都是正常状态（默认就是关的），标红只会让人以为应用坏了 |
| **运行状态** +2 组 | 「设备感知」一行状态；「对话数」+ 每个对话的类型与它实际用的模型（`· AI → hello / lfm2vl-450q4km`）。「模型配合没生效」是最难从界面上看出来的一类问题 |
| **操作流水** +1 类 | 每轮生成记一行 `注入：人设 · 提示词 1234 字 · 设备感知 · 记忆 3 条/经验 1`。排查「它怎么不知道 X」时第一个要回答的就是「提示词里到底有没有那段」 |
| 导出报告 | 自动带上上面全部（走 `diagnosticsExtra()`，界面和报告**同一个来源**） |

**一个刻意的取舍**：`Geocoder.isPresent()` 会撒谎（本机 isPresent=true，实际解析 TimeoutException）。
所以诊断里显示的是 `DeviceSense.lastGeocodeOk` —— **上一次实际解析成没成**，三种状态：
上次成功 / 上次失败（地名退回时区推断）/ 还没试过。乐观的「支持」没有任何诊断价值。

### 13.2 开源声明（关于页）

* **加了一条必须加的署名**：`Open-Meteo · CC BY 4.0`。它的免费接口按 CC BY 4.0 提供数据，
  **署名是使用条件** —— 删掉这一行是违约，不是「少写一行」。天气功能引入的就是它。
* **新增「会联网的功能」整块**：一行一件事，写清**什么时候、把什么、发给谁** ——
  下载模型 / 联网检索（查询词 → Bing/DDG）/ 所在地天气（经纬度 → Open-Meteo）/ 本机 API 服务。
  应用一直说「完全离线」，而离线的前提是「你没打开那几个开关」。与其让人猜，不如列出来。
* **免责说明改写**：原来写「不收集、不上传任何对话内容」，现在补上例外与边界 ——
  对话/记忆/人设都在本机；可能离开设备的只有那几项；**本项目没有服务器**，
  所以天气和检索都是直连第三方，没有任何地方能收到这些数据；
  定位只用于本机显示地名与查天气，不写进对话历史，模型看到的只有地名。

### 13.3 踩了第三次的坑：UI 文案里不要写 Markdown 的星号强调

`Text()` 不渲染 Markdown，用户看到的是字面上的两个星号。这一轮又在关于页和生图页各踩了一次。
要强调就用「」。已加进 §11.4 的规矩 —— **改完文案 grep 一遍**：

```powershell
Select-String -Path 'app\src\main\java\com\localmind\ui\**\*.kt' -Pattern '\*\*'
```

---

## 14. 第七轮（2026-10-16）：手机安装包

### 14.1 产物

| 文件 | 体积 | ABI | 给谁 |
| --- | --- | --- | --- |
| `dist/LocalMind-1.0.1-手机版-arm64+armv7.apk` | 12.32 MB | arm64-v8a + armeabi-v7a | **手机**（2016 年后的机器都覆盖；含老 32 位机） |
| `dist/LocalMind-1.0.1-通用版-三ABI.apk` | 18.96 MB | 再加 x86_64 | 模拟器 / x86 平板 |
| `dist/安装说明.txt` | — | — | 一起发出去的那张说明书 |

命令（默认 ABI 就是 arm64+armv7，所以手机包不用带参数）：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
.\gradlew.bat :app:assembleRelease                       # 手机包
.\gradlew.bat :app:assembleRelease -PabiList=arm64-v8a,armeabi-v7a,x86_64   # 通用包
```

产物体积构成（手机版）：`liblocalmind.so` 4.85 MB(arm64) + 3.6 MB(v7a)、`libc++_shared.so` 2.06 MB、
`classes.dex` 3.3 MB，其余是资源。**生图后端不在里面** —— 它是 14 MB 的独立插件，按需下载。

### 14.2 这一轮为了让「装完就能用」成立而改的两件事

**1. 自带模型清单改成首次运行自动装上**（`data/BuiltinPacks.kt`）

原来 `ModelCatalog.models` 是空表，模型清单要靠用户去插件页导一个 zip。
自己用没问题，**发给别人就是灾难**：装完打开，模型页空空如也，他只会觉得这应用是坏的。

清单只有 32 KB，本来就打在 APK 里（`assets/model-library/model-library.json`），
所以第一次启动直接装上。边界写死了三条：

* **只在没有任何模型包时动手** —— 用户自己导过插件就说明他知道这条路，再塞一份只会两份并存；
* **只写清单，不碰文件** —— 权重仍然是运行时下载，没破坏「主程序不带素材」；
* **失败就什么都不做** —— 读不到 asset / JSON 坏了都静默跳过，导入 .gguf 和接云端 API 两条路照旧。

实测：`pm clear` 之后全新启动，「模型」页直接显示 32 个条目（本机内存筛出 19 个可装）。

**2. 麦克风权限从「启动时」挪到「按住说话时」**（`MainActivity` + `VoiceScreen`）

原来 `requestBasePermissions()` 在启动时就申请 RECORD_AUDIO，于是**全新安装的第一次打开**，
引导页上先弹一个「允许 LocalMind 录音吗？」—— 用户还没搞清楚这应用是干什么的，
就被问要不要交出麦克风；拒绝是正常反应，而**一旦拒绝系统就不再弹了**，语音等于永久失效。

现在启动只申请通知权限（无隐私含义，且 API 服务要用）；麦克风在语音页按住说话那一刻才申请，
没权限时那行提示会直接说明白：「按住说话 —— 第一次会向你要麦克风权限」。

### 14.3 签名（必须知道的一条）

release 用 **Android 调试密钥**（`CN=Android Debug`），这是项目早先刻意做的选择
（`assembleRelease` 出来就能直接装）。后果要讲清楚：

* 自用 / 小范围内测：没问题，v2 签名验证通过；
* 上应用商店：不行；
* 调试密钥是**公开**的，任何人都能签出同包名同签名的包 —— 所以这个包不能当正式分发渠道的产物。

要正式签名就改 `app/build.gradle.kts` 的 `signingConfigs`，其余不用动。

---

## 15. 第八轮（2026-10-16）：引导页瘦身 + 四类任务的路由 + 生图可诊断

用户的原话：开始引导界面的时候就不用设置 ai 对话了，让用户自己在界面设置；
生图不能用；不同任务（生图/决策/对话/识图）多模型共同配合使用；生图识图测试完善了吗。
要求：先模拟器测试，没问题了再实机。

### 15.1 引导页只剩「你是谁」

原来三步：你是谁 → 你想怎么用它（当 AI 用 / 当一个人）→ 它是谁（名字/头像/年龄/人设）。
那三步属于「全局只有一个 AI 身份」的时代。现在：对话类型是**新建对话时选的**、
人设/记忆/模型/能力全是**按对话设置的** —— 在一个还没有任何对话的时刻问这两件事
没有着落点，用户白填一次还得回去改。

删掉之后：`OnboardingScreen` 只剩昵称/头像/性别，`finish()` **不再写人设**
（内置默认人设继续当模板），按钮从「继续/开始对话」变成一个「开始」。

### 15.2 四类任务各用各的模型 —— 两个「选了没反应」

`effectiveImageModel()` / `effectiveVisionModel()` 原来和当初的 `effectiveChatModel()`
一模一样：**定义了，零调用点**。也就是：

* 对话里选「生图模型」→ 生图时被忽略，引擎自己去 `models/` 扫一个「看起来像扩散模型」的文件；
* 对话里选「识图模型」→ 根本没人读。

现在：

| 任务 | 谁决定用哪个模型 | 落点 |
| --- | --- | --- |
| 对话 | `conv.effectiveChatModel(routing)` | `send()` 解析 → 本地就预热加载 / API 就走 remote |
| 生图 | `conv.effectiveImageModel(routing)` | `imageModelPathFor(conv)` → `ImageEngine.generateWithSettings(modelPath=…)` |
| 识图 | `conv.effectiveVisionModel(routing)` | `visionModelFor(conv)`（当前只用于如实告知，见 15.4） |
| 决策 | `conv.decisionModelId`（人设聊天） | 独立第二个引擎，随对话切换重新绑定 |

测试模式新增「**四类任务用哪个模型**」：把上面四个解析结果直接摊开（含「未指定/未下载」），
并显示当前对话是哪一条。**多模型配合最难验证的就是「界面上看不出谁在用谁」**。

### 15.3 生图：不再静默兜底

`ImageEngine.generateWithSettings` 现在返回 `Generated(file, via, note)`：

* `via = DIFFUSION` → 真模型画的；
* `via = PROCEDURAL` → 内置渲染器画的，`note` 说明**为什么**：
  「没有安装生图后端插件 / 还没有下载扩散模型 / 选定的模型文件不在了 / 模型加载或出图失败」。

生图页顶部会显示上一张图的出处（绿=模型出图，橙=内置渲染器 + 原因），
诊断流水里也会记一行 `生图兜底：…`。

> 这一条是「生图不能用」这个反馈的根：代码安静地退回程序化渲染，用户看到一张渐变图，
> 结论自然是「功能坏了」。**静默兜底在这里等于把「缺东西」伪装成「能用」。**

### 15.4 识图：把「换模型也没用」说出来

`canSeeImages` 收敛成 `AppViewModel.CAN_SEE_IMAGES`（现在 false）—— 自检、
两处对话设置页的开关、聊天页的提示都读这一个常量，接上 libmtmd 时改一处即可。

关键是文案分三种（`visionSubtitle()`）：没装多模态模型 / **装了但代码还没接视觉投影** / 已就绪。
原来只有第一种判断，用户下完 Qwen3-VL 会看到「你发的图片它可以看」，打开、发图、
得到一句没看见图的回复 —— 那是我们在骗他。现在开关直接置灰并写明「换模型也没用，等版本更新」。

### 15.5 自检新增两项 + 试画一张

| 检查 | 判定 |
| --- | --- |
| **生图链路** | 后端（已加载/已安装未加载/未安装）**和** checkpoint（找到/没找到）都齐才 ok —— 缺任一项都是红的，因为用户点「生图」的期望就是拿到模型画的图 |
| **识图（多模态）** | 如实报告当前版本没有视觉投影，任何模型都看不见图 |

测试模式另有「试画一张 64×64」：用**当前生效的路由**真的画一张，回答「谁画的 / 多久 / 为什么不是模型画的」。
64×64 是刻意的 —— 真模型也只要几秒，把「能不能跑通」和「画得好不好」分开。

### 15.6 模拟器实测（lm35 / x86_64）

```powershell
# 1) 生图后端是插件，用调试端点装（SAF 文件选择器驱动不了，见 §6.6）
adb push dist/native/x86_64/liblocalmind-sd.so /data/local/tmp/
# 应用内先启动「本机 API 服务」，然后：
POST /v1/debug/backend {"path":"/data/local/tmp/liblocalmind-sd.so"}
# → {"load_ok":true,"probe":true,"info":"SSE3=1|SSSE3=1|REPACK=1"}

# 2) checkpoint 只能真下：模型页 →「生图」分类 → SD 2.1 768 (Q4, 1.58 GB)
```

结论写在下面（实测结果见本轮回复）。

### 15.7 真机诊断报告暴露的三个真 bug（2026-10-16 下午，已修）

用户发来 `localmind-诊断-20261016-121824.txt`（小米 24031PN0DC / Android 16，release 包）。
报告里三条线索各自指向一个真 bug：

**① 生图永远不出真图 —— 判据把扩散模型当成了文本模型**

报告里那句 `加载失败：llama.cpp 加载失败（sdxs-512-tinySDdistilled_Q8_0.gguf）` 是表象；
真正的问题在 `DiffusionModels.readKind`：

```kotlin
if (key == "general.architecture") sawTextArch = true   // ← 只记了「见过这个键」
```

扩散 checkpoint 里 `general.architecture` 的值就是 **`"stable-diffusion"`** ——
而原代码**只记键名、不读值**，于是每一份 sd.cpp 导出的 checkpoint 都被判成 TEXT，
`resolve()` 返回 null，生图**安静地**退回程序化渲染。模拟器上同样复现（我用 SD 2.1 验证）。

修法两条一起上（`readKind` 重写）：

1. **读 `general.architecture` 的值**，`stable-diffusion` / `sd` / 含 `diffusion` → 扩散；
2. **扫张量名**：GGUF 的权重名在 KV 段**之后**的张量信息里，sd.cpp 是 `vae.*` /
   `model.diffusion_model.*` / `first_stage_model.*` / `double_blocks.*`，
   文本模型是 `blk.*` / `token_embd` —— 这是文件自己声明的结构，比任何键名猜测都可靠。

顺带加了 `DiffusionModels.lastProbe`（架构名 + 张量数 + 首个张量名），
下次再认不出来，报告里能直接看出是文件不对还是判据太窄。

**② 假崩溃记录 —— `endSession()` 零调用点**

报告的 3 条「崩溃记录」**全是假的**，类型都写着
「上次进程没有正常退出（原生崩溃 / 被系统杀掉 / 被手动划掉）」——三种可能不分，
而 `Diagnostics.endSession()` 定义了却**从来没人调**，于是**每一次**退出（划掉、
系统回收、真崩溃）都会在下次启动时记一条。真机跑一天能攒出好几条，
用户打开测试模式看到「崩溃记录 3 条」，结论自然是「这应用老闪退」。

修法：标记文件变成两行（开始时间 + **当前在哪**），`MainActivity.onResume/onPause`
维护前台标记，`onDestroy(isFinishing)` 真正调 `endSession()`。分类规则：

* **前台**消失 → 记崩溃（`前台异常退出（很可能是真的崩溃…）`）；
* **后台**被回收 → 只记一行操作流水「上次进程在后台被回收（正常，不是崩溃）」。

实测：修完之后再启动，流水里出现的就是后一种，崩溃列表不再增长。

**③ 生图模型能被当聊天模型加载**

报告里连续 5 次 `llama.cpp 加载失败（sdxs-512-…）`：用户在模型页点了扩散模型的「加载」。
扩散 GGUF 和文本模型结构完全不同，llama.cpp 必然失败，**而且在原生层有直接 abort 的风险**
（那 5 次失败之后就跟着一条异常退出记录）。

修法：`AppViewModel.loadModel` 开头直接挡掉 —— `isImageModel` / `embedding` 的条目
提示「这是生图模型，不能当聊天模型加载 —— 到「生图」页用它」并返回，不进 native。

### 15.8 模拟器实测（结论）

```
测试模式 → 四类任务 → 试画一张 64×64
结果：生图模型出图 · 69618ms · img_a-green-forest-self-test_…_64x64.png
logcat: ImageEngine: 用 v2-1_768-nonema-pruned-Q4_0.gguf（1620 MB）生图
```

69.6 秒是 x86_64 模拟器 + SD2.1 20 步 + 64×64 的数字，真机 arm64 会快一个量级；
这里要证明的只是**链路通了**：后端 → checkpoint 识别 → 真模型出图。

识图当时仍然**不能**工作（JNI 没接 libmtmd）；第九轮把它接上了（见 §16），

---

## 16. 第九轮：识图（多模态 / mmproj）接线

用户的诉求是「识图完善」。这一轮把**整条链路接了起来**，但**最后一步没打通**，
所以功能开关仍然是关的（§16.4 说清楚卡在哪）。已接好的部分都是真的、可验证的。

### 16.1 原生侧：把 llama.cpp 的 mtmd 编进包里

llama.cpp 自带多模态（tools/mtmd，前身是 llava）。它提供了一个**只编这个库**的开关，
所以不用改它的源码，也不用顺带编一整个 tools/ 目录：

```cmake
# app/src/main/cpp/CMakeLists.txt
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)   # 见 llama.cpp 根 CMakeLists 里那段说明
foreach(_tgt llama ggml ggml-base mtmd)         # mtmd 也要求存在
target_include_directories(localmind PRIVATE "${LLAMA_DIR}/tools/mtmd")
target_link_libraries(localmind llama mtmd ggml ggml-base ...)
```

代价（**实测**，不是估算）：未 strip 的 .so 从 ~45 MB 涨到 60.65 MB（mtmd + clip 里
几十个模型实现），但 strip 后**每个 ABI 只多约 1 MB** —— 发布包从 12.32 MB 变成 14.37 MB
（arm64+armv7 两个 ABI 合计 +2.05 MB），通用包 18.96 -> 22.34 MB。
链接器把没被引用的那部分丢掉了，所以体积代价比看上去小得多。要省体积就把 `LLAMA_BUILD_MTMD` 关掉，
再把 `llama_jni.cpp` 里的 mtmd 那段用宏包起来。

JNI 新增三个入口（`llama_jni.cpp`）：

| 函数 | 作用 |
| --- | --- |
| `nativeMmprojLoad(handle, path)` | 装上配套的视觉投影 |
| `nativeVisionReady(handle)` | 现在能不能看图 |
| `nativeVisionGenerate(handle, roles, contents, imagePath, sampler, max)` | 带图生成 |

带图生成不走文本那条路：提示词里的 `<__media__>` 由 mtmd 换成图像嵌入，
必须按 chunk 求值（`mtmd_helper_eval_chunks`），位置数也不等于 token 数，
而且**不能做前缀复用**（KV 里混着图像嵌入，账本对不上）—— 每次带图都清空重来。

顺手做了一次重构：把「token -> UTF-8 -> Kotlin 回调」抽成 `token_sink`，
文本和图像两条路径共用一份 —— 原来那段（UTF-8 补齐 + R8 反射坑）复制第二份一定分叉。

### 16.2 数据与下载：mmproj 是**第二个文件**

`LocalModel` 新增 `mmprojFileName / mmprojUrl / mmprojMirrors / mmprojSizeBytes`，
内置清单里 LFM2-VL 450M、SmolVLM 256M、SmolVLM2 2.2B 三条已填好（文件名与字节数
逐个对过 HF 的 API，来源见 docs/MODEL-CANDIDATES.md §3）。

`ModelDownloader` 在主文件下完之后**顺带**把 mmproj 拉下来（同一套断点/代理/校验），
失败**不算主模型失败** —— 主模型已经可用，只是识图保持关闭。

实测（模拟器）：点一次「下载 218.69 MB」，两个文件都到位，字节数和 HF 完全一致：

```
-rw- LFM2-VL-450M.Q4_K_M.gguf          229310304
-rw- LFM2-VL-450M.mmproj-Q8_0.gguf     105069664
```

### 16.3 引擎与界面

* `LlamaEngine.load(model, settings, mmprojPath)`：装不上 mmproj **不影响**主模型加载，
  只是 visionReady 保持 false —— 用户不该因为一个可选文件而下不了台；
* `LlamaEngine.chatWithImage(...)`：带图那条路的入口，图**由原生侧自己解码**
  （走 mtmd 官方加载器，尺寸/切片/EXIF 都归它管）；
* `AppViewModel.send()`：开了识图 + 挂了图 + 模型真的能看 才走多模态，否则退回纯文字，
  **并在诊断流水里记一行**（带图但模型看不见）—— 静默降级是最难查的那类 bug；
* `POST /v1/debug/vision`：脚本化的识图入口。图片选择器是系统 SAF 界面，adb 驱动不了
  （和插件/主题同一个处境），没有这个端点就只能手点验证。它走**和聊天完全同一条**引擎路径；
* `POST /v1/admin/load` 之前**漏了 mmproj**：用 API 切过去的模型看不见图，界面切过去就行。
  两处现在共用 `LocalModel.mmprojPathOn(modelsDir)`。

### 16.4 卡在哪：mtmd_tokenize 在图像预处理里 std::bad_alloc

**现象**：模型加载成功（日志 vision=true），一带图就失败：

```
{"error":{"message":"mtmd_tokenize failed (2)","code":"vision_failed"}}
logcat: LocalMindMtmd: mtmd_tokenize: error: std::bad_alloc
```

排查过程（每一步排除一个可能）：

1. **不是模型** —— SmolVLM 256M（idefics3 投影）和 LFM2-VL 450M（lfm2 投影）都复现；
2. **不是图片** —— 256x256、512x512、1080x2400 截图都失败；
3. **不是自己拼 bitmap** —— 换成 mtmd 官方加载器 `mtmd_helper_bitmap_init_from_file` 后一样；
4. **不是 mmproj 缺参数** —— 把 mmproj 文件头拉下来解析，参数齐全：
   `clip.vision.image_size = 256`、`patch_size = 16`、`projector.scale_factor = 2`、
   `projector_type = lfm2`；
5. 在 llava-uhd 的网格计算后加了日志和防御，**日志没打出来** —— 异常发生在
   `get_best_grid()` / `get_refine_size()` 里（mtmd-image.cpp 第 576-577 行附近），
   即切片**还没开始**就已经申请了一块巨大的内存。

最可能的原因：`get_best_grid()` 返回非法/过大的网格（0 或极大），下游按它算 refine
尺寸时溢出。这在 **llama.cpp 上游的代码**里，不是我们的接线。

**已加的本地补丁**（third_party/llama.cpp/tools/mtmd/mtmd-image.cpp，grid_x/grid_y 之后）：
一条 LOG_ERR 打印网格与尺寸，外加 grid_x/grid_y <= 0 时直接返回（步长为 0 的双重循环
会一直 push_back 直到 OOM）。补丁无害且有用，但它**不是**这次 bad_alloc 的修复
（异常在它之前）。

**下一步（按性价比）**：

1. 在 `get_best_grid` / `get_refine_size` 里再插两行日志，看 multiple / log_ratio /
   返回的网格到底是什么（一次构建即可定位）；
2. 换投影类型不同的模型验证是不是 llava-uhd 专属：Qwen2.5-VL 3B（qwen2vl，动态尺寸）
   或 InternVL3.5 2B（internvl），清单里都有；
3. 确认是上游 bug 后，要么提 issue，要么在 vendored 代码里做一处**有注释、可摘除**的修复。

### 16.5 开关为什么还是灰的

`AppViewModel.VISION_USABLE = false`（和 `CAN_SEE_IMAGES = true` 分开）：

* `CAN_SEE_IMAGES`：这个 build **编进了** mtmd（真的，符号在 .so 里，329 个）；
* `VISION_USABLE`：整条链路**验证通过**（现在还没有）。

`canSeeImages = VISION_USABLE && visionReady`，所以：对话设置里的「看懂你发的图」保持灰的、
发图仍按纯文字回答（并在诊断流水留一行）、自检的「识图（多模态）」如实报告卡在哪一步。

> 把开关留灰而不是先打开，是因为「打开 -> 发图 -> 报错」比「灰着 + 说明原因」糟得多。
> 打通之后改一个常量即可，其余代码都已就位。

当时这一轮的改动是让它**说实话**：
开关置灰 + 「换模型也没用，等版本更新」。要真正识图，下一步是在 `llama_jni.cpp` 里接 mtmd。





### 11.6 模拟器实测（2026-10-16，lm35 / x86_64 / LFM2-VL 450M Q4_K_M）

**验证通过的**

| 项 | 结果 |
| --- | --- |
| 新建对话 | 弹窗先让人选 AI 对话 / 人设聊天，并写明“类型建好之后不能改” |
| 两种设置页 | `AI 对话设置` 只有模型/工具性能力/提示词/采样；`人设聊天设置` 有它是谁/相处方式/状态与记忆/四个模型 |
| 列表徽标 | 每行带 `AI`（蓝）/`人设`（粉）徽标 |
| **按对话选模型真的生效** | 在 AI 对话里选 LFM2-VL 450M → 立刻加载（toast「已加载 …ctx 4096」），行内变成“当前已加载：…”；发消息直接用它回答 |
| AI 对话速度 | `hello` → `Hello! How can I help you today?`，**20.2 tok/s**（450M，模拟器） |
| 人设聊天记忆 | 一轮之后「它现在的状态」显示 **共 1 轮**、心情从“平静”变“还不错”、话多程度 50→49（情绪漂移 + 轮次记账生效） |
| 拾取器默认项 | 修掉重复勾选（`跟随全局默认` + `不指定` 同时打勾） |

**实测抓到并修掉的一个真 bug（只靠读代码看不出来）**

症状：在人设聊天里发消息，**回复是一个空气泡**（有气泡、有 copy/朗读图标，但没有字）；
AI 对话同样的模型却正常。`conversations.json` 里那条 assistant 消息的 content 是空串。

根因：`RichReply.instruction(canEmoji = conv.abilities.emoji)` 只看了**对话自己的能力开关**。
而人设聊天默认就开着 emoji，于是提示词里写着“你可以发 `[[emoji: 😄]]`”——
但设备上**根本没装表情包插件**，`StickerResolver.promptHint()` 在这种情况下返回空串，
`stickerOf()` 也恒为 null。结果模型老老实实回了一整条只有标记的回答，标记被解析器剔除后什么都不剩。

修法三条（都在 `AppViewModel.send`）：

1. `canEmoji = conv.abilities.emoji && emoji.availableTags().isNotEmpty()` —— **不广告不存在的能力**；
2. 收尾时如果正文为空、表情又没能兑现，**把模型的原话还回去**（宁可显示一行标记，也不要空气泡）；
3. 短话连发那条尾巴同样处理。

修完复测：同样的人设聊天，回复正常显示文字（`2.8 tok/s`）。

**顺带量出来的一个事实**：同一条消息，AI 对话 20.2 tok/s、人设聊天 2.8 tok/s。
这个数字是 `tokens / (整轮墙钟时间)`，所以差异主要来自**prefill**：人设聊天要发
人设 + 长期状态 + 时间感 + 记忆 + 表达方式那几千字。这就是“AI 对话要最快、
人设聊天可以慢一点但更像人”的量化依据 —— 别为了追平 tok/s 去砍人设那一层。

**另一个发现（尚未处理，不是本轮引入的）**：`files/models/` 里有一个 396 MB 的
`Qwen3-0.6B-Q4_K_M.gguf`，但当前模型库插件里已经没有这个条目了（v2 清单把它换成了别的），
于是它**在应用里完全看不见**（`ModelStore.resolveAll()` 只认清单里的 fileName）。
用户视角就是“我下过的东西不见了，磁盘还被占着”。要修的话方向是：扫描 `files/models/` 目录，
把没有清单条目的 GGUF 当作 `imported` 条目列出来。

---

## 17. 第十轮（2026-10-16）：内测视角的一轮 —— 8 个真问题（全部已修 + 模拟器复测）

这一轮不按功能走，而是**按一个刚拿到包的内测用户会怎么用**走：
全新安装 → 引导 → 装模型 → 聊天 → 语音 → API → 关于页 → 测试模式。
下面每一条都是**在模拟器上真实复现过的**，并且写清了「为什么以前没被发现」。

### 17.1 MemoryPlanner.readGguf 把完好的 GGUF 判成「非 GGUF」（最严重）

**现象**：模型页里已经下载好、且能正常回答问题的 Qwen2.5 0.5B，详情页写着
「GGUF：非 GGUF / 无法解析头部」，卡片副标题也是同一句。

**根因**：readGguf 只读文件开头 **512 KB** 用来走 KV 段，而每个真实 GGUF 的 KV 段里
都有一个几百 KB 的 tokenizer 词表数组 —— 窗口一定走不完它。走到窗口边缘时：

* skipValue 的字符串分支里 buf.long **没有先检查剩余字节**；
* 主循环里 val type = buf.int 也只在循环开头查过一次。

两处都会抛 BufferUnderflowException，被最外层的 runCatching 吞掉 → readGguf 返回 null
→ 界面说「非 GGUF」，**内存规划器同时静默退回默认几何**（层数 0、KV 按 64 KB/token 兜底、
上下文恒为 4096）。

**实测证据**（把设备上那个文件的头 700 KB 拉回本机，用同一套字节语义复算）：

```
WIN=524288  magic=46554747 v=3 tensors=291 kv=26
  !!! BufferUnderflowException at pos=524287 need=8 remaining=1
WIN=716880（把窗口放大就正常）
  break: skipValue false at i=17 key=tokenizer.ggml.tokens
```

同时 llama.cpp 能正常加载同一个文件（loaded qwen2.5-0.5b-instruct-q4_k_m.gguf）——
**文件和加载都没问题，是我们的解析器在骗人**。

**修法**：把「读到哪算哪」这句话真正实现出来。

1. 循环里补 remaining() < 4 检查，skipValue 的字符串分支补 remaining() < 8；
2. **一次 KV 的解析整体包一层**，任何未预料的越界都只结束扫描、不外泄异常
   （外层那次 runCatching 一旦接住就等于判「非 GGUF」，语义完全相反）。

**复测**：同名模型的卡片与详情页变成 qwen2 · 24 层 · d=896 · ctx 32768 · Q4_K_M；
加载日志 model ready: n_ctx=18944（不再是 4096）—— **顺带把内存规划器修活了**。

> 教训：runCatching 把「解析失败」和「文件不是 GGUF」变成了同一件事，
> 而这两件事在用户眼里差了十万八千里。

### 17.2 一个模型条目凭空消失（决策模型选不到）

**现象**：内置清单 34 条，界面只列 33 条，/v1/models 也是 33。

**根因**：ModelStore.resolveAll() 里有 distinctBy 文件名 —— **按文件名去重**。
而本项目恰恰刻意让「一个 GGUF 两种用途」：「Qwen2.5 0.5B Instruct」与
「Qwen2.5 0.5B（通用决策备选）」是同一个权重文件。于是排在后面的决策条目
**被静默丢掉**，用户在决策模型选择器里永远看不到它。

同一个机制还会打断官方模型库插件（52 条里 5 组同名：Qwen3.5 / Gemma 4 的
「对话」+「看图」两条）—— 装上去一次少 5 条。

**修法**：去重键改成「文件名 + 用途」（roleKey：vision / decision / embedding /
voice / asr 的标记位）。同文件同用途才算重复（用户自己导入的文件仍然让位给清单条目），
用途不同就两条都留。

**复测**：/v1/models 33 → **34**，决策条目出现，界面「可下载（19/34）」。
另外那条 sizeBytes 也写错了（397,000,000，真值 491,400,032），一并改掉；
并且 UI 改成**已下载时以磁盘真值为准**（displaySizeBytes），
所以同一个文件不再显示成「468.64 MB」和「378.61 MB」两个体积。

### 17.3 通知权限**每次启动**都弹

**现象**：第一次拒绝之后，第二次、第三次启动照弹同一个「允许 LocalMind 发送通知吗」。

**根因**：MainActivity.requestBasePermissions() 在 onCreate 里无条件申请。
Android 13+ 的 POST_NOTIFICATIONS 与别的权限不同 —— 用户拒绝之后**系统还会再弹**，
于是「每次启动都申请」的真实表现就是每次打开都弹同一个窗。

**修法**：记一个 notif_asked 标记，一辈子只问一次；已经有权限也不问。
（这个权限只有开「本机 API 服务」时才有实际作用，问到一次就够。）

**复测**：pm revoke → 启动弹一次 → 拒绝 → 再启动**不再弹**（mCurrentFocus=MainActivity）。

### 17.4 系统发音人列表：442 个、按引擎顺序排、中文用户看到阿拉伯语

**现象**：语音页「系统发音人（442）」，下面 12 行是
Arabic · language / Arabic · network / Bulgarian (Bulgaria) · local …
—— 一个中文用户能用的都没有；而且标题的数字和看到的条数对不上。

**根因**：availableVoices() 只按 locale 排序，**不按当前语言筛**；列表只取前 12 个；
标签取引擎内部名的最后一段（cmn-cn-x-ssa-lstm-embedded → ssa-lstm-embed）。

**修法**：新增 TtsEngine.voicesFor(language) 先按语言筛（筛不到再退回全部），
标题写清「显示前 12 个」，标签改成「怎么合成 + 可区分的短后缀」：
cmn-cn-x-ssa-local → 「本地合成 · ssa」。

> 第一版只写「本地合成」，复测时发现 16 个中文发音人里有 10 个显示成同一个词 ——
> **标签既要说明是什么，也要能彼此区分**，少一个都不行。

**复测**：442 → **16**，每行都不一样，且全在普通话下。

### 17.5 没有消费端的开关可以拨

**现象**：「让 AI 成为 Agent」「允许使用设备 Shell」是能拨的开关，副标题写着
「当前版本还没接线：开关不会生效」；拨完会真的写进设置。

**修法**：ToggleRow 加 enabled，这两项置灰不可拨，文案改成「这个版本还没做，暂时不能开启」。
**能拨的开关是在承诺一个行为** —— 用户拨完没变化，得到的结论不是「还没做」，而是「这应用不靠谱」。

**复查**：CapabilitySettings 里还有 7 个字段（webFetch / adb* / agentMaxSteps / fileAccess /
toolCalling 等）同样没有消费端，今天没有界面入口，留着不动 —— 但**加新开关前先确认它有消费端**。

### 17.6 取消/删除下载后的段目录永远留在手机上

**现象**：上一个安装的 files/models/ 里躺着 sd_turbo-f16-q8_0.gguf.2023745376.part.d
（1.6 GB 的模型下到一半留下的）。它没有任何界面入口：存储占用看不见、模型列表里也没有，
只有手机空间在变少。

**根因**：多连接下载的中间产物是**段目录**（名字.总大小.part.d），
而 deleteModel 只删了单连接那条路径的 .part。

**修法**：Storage.leftoverParts(fileName) 统一认这两种临时产物，deleteModel 一起清掉；
多模态模型的 mmproj（第二个文件）也一起处理（没人再引用它时才删）。
测试模式的「存储占用」新增**「未完成的下载」**一行 —— 以前这类数据在界面上完全不可见。

**复测**：下到 60%（239 MB）→ 暂停 → 删除 → segment_dirs: []，
存储行从 49.70 MB 回到 0。

### 17.7 设置页的「自动线程数」和实际用的不是一回事

SettingsScreen 写的是 cores - 1，而 MemoryPlanner.plan 用的是 if (cores <= 4) cores else 4：
4 核机器上界面说「自动（3）」实际用 4，8 核机器上说「自动（7）」实际用 4。
用户拿这个数字去理解速度，只会得出错误结论。

**修法**：把公式收敛成 MemoryPlanner.autoThreads()，界面只调它 —— 一份公式，两处用。

**复测**：设置页显示「线程数：自动（4）」，与 model ready: threads=4 一致。

### 17.8 还没下载的模型也写着「非 GGUF / 无法解析头部」

模型详情页对**没有本地文件**的条目也调 preview()，而 preview(null) 返回的就是
「非 GGUF / 无法解析头部」。用户点开一个还没下载的模型，看到的是「文件坏了」。

**修法**：没下载就不显示这一行（ggufPreview 返回空串，卡片侧改成 isNullOrBlank() 判断）。

### 17.9 顺带清掉上一轮记下的「看不见的模型文件」

上一轮 §11.6 末尾记着：files/models/ 里那个 396 MB 的 GGUF 因为清单里没有对应条目，
在应用里**完全看不见**。这一轮做了：ModelStore.orphanFiles() 扫描 models 目录，
把**没有任何条目引用**的 .gguf（跳过 mmproj 与临时产物）作为「本机文件」列出来 ——
能加载、能删除、能看出是什么。

**复测**：手动放一个 stray-test.gguf → 模型列表 34 → **35**，卡片带「本机文件」标签与说明
→ 点删除 → 回到 34，磁盘文件同步消失。

### 17.10 这一轮的验证方式（可复现）

```powershell
# 构建（模拟器）
.\gradlew.bat :app:assembleRelease "-PabiList=x86_64"
adb install -r -t app\build\outputs\apk\release\app-release.apk

# GGUF 判据：把设备上那份头拉回本机，按同一套字节语义复算
adb exec-out run-as com.localmind dd if=files/models/x.gguf bs=1024 count=700 > head.bin

# 下载 / 段目录：不需要点界面
curl -X POST http://127.0.0.1:18080/v1/debug/download -d '{"id":"qwen3-0.6b-q4km"}'
curl -X POST http://127.0.0.1:18080/v1/debug/download -d '{"id":"qwen3-0.6b-q4km","cancel":true}'
```

**最终状态**：自检 12/13（唯一红的是「生图链路」—— 这台机器没装生图后端插件、也没有扩散模型，
属于如实报告）；model ready: n_ctx=18944 threads=4；34 个模型条目；
聊天与 /v1/chat/completions 均正常；logcat 无 FATAL。

**产物**：assembleRelease（arm64-v8a + armeabi-v7a）**14.39 MB**；
x86_64 单 ABI 10.19 MB（模拟器用）。

### 17.11 这一轮改动的文件

| 文件 | 改动 |
| --- | --- |
| data/MemoryPlanner.kt | GGUF 走访补剩余字节检查 + 单步 runCatching 兜底；新增 autoThreads() |
| data/ModelCatalog.kt | 去重键改成「文件名 + 用途」；新增 displaySizeBytes；新增孤儿 GGUF 扫描 orphanFiles() |
| assets/model-library/model-library.json | 决策条目的 sizeBytes 改成真值 491,400,032 |
| data/Storage.kt | notificationAsked / setNotificationAsked；leftoverParts / allLeftoverParts |
| MainActivity.kt | 通知权限一辈子只问一次 |
| service/ImageEngine.kt | voicesFor(language) 按语言筛；voiceKind() 人类可读且可区分的标签 |
| ui/screen/VoiceScreen.kt | 用筛选后的列表；标题说明只显示前 12 个 |
| ui/screen/ProfileScreen.kt | ToggleRow 加 enabled；Agent / Shell 置灰 |
| ui/AppViewModel.kt | deleteModel 清段目录与孤儿 mmproj；ggufPreview 未下载时不显示；删掉一处恒真条件 |
| ui/screen/DebugScreen.kt | 存储占用新增「未完成的下载」 |
| ui/screen/SettingsScreen.kt | 自动线程数改问 MemoryPlanner.autoThreads() |
| ui/screen/ModelsScreen.kt | 体积以磁盘真值为准；GGUF 行空串不渲染 |

> **这一轮的通用教训**（比这 8 条本身重要）：
> 1. **静默兜底等于把「缺东西」伪装成「能用」** —— 17.1 的 runCatching、17.4 的只显示前 12 个、
>    17.6 的不可见临时文件，用户看到的都是「功能坏了」，而不是「缺什么」。
> 2. **同一句话出现在两个处境里，就会被当成 bug 报上来**（17.8 的「非 GGUF」）。
> 3. **「能拨」是一种承诺**（17.5）；**「显示出来的数字」也是一种承诺**（17.7）。


---

## 18. 第十一轮（2026-10-16）：提速 + 多接口（对话/识图/生图/语音/决策 + 转发）

用户这一轮的两句话：

> 1. 优化大模型生图和对话加载生成速度过慢的问题，可以去 GitHub 上找解决办法，记得改开源声明
> 2. 支持对话/生图/识图/语音/jev 调用 api 对话和转发，多接口兼容，openai 等等

### 18.1 先做了一次外部调研（结论直接改变了做法）

llama.cpp 在 Android arm64 上到底该开什么，查到的关键几条：

| 项 | 结论 |
| --- | --- |
| `GGML_CPU_KLEIDIAI` | 官方为 Android arm64-v8a 写的 ARM 微内核路径，**运行期按 HWCAP 探测**，不抬编译基线（全局 `-march=armv8.2-a+dotprod` 会让老 arm64 直接 SIGILL，所以不能用那条路）。但它**只覆盖 Q4_0 / Q8_0 / F16 系**，清单里占多数的 Q4_K_M 拿不到收益。 |
| flash attention | CPU 上同样生效，省内存；但**在 Arm 上有负收益的实测报告**（llama.cpp issue #27086：Neoverse-V2 上关掉 FA，prefill 430→854 t/s，8K 上下文差 5.2 倍），x86 上符号相反。结论：**默认给 AUTO，并且必须让用户能关**。 |
| 量化 KV cache | 省一半内存，但**强制要求 flash attention**，而且会绕过 CPU 上的 split-KV / tiled 快路径。所以默认仍是 f16，q8_0 作为「想开大上下文」的可选项。 |
| `n_batch` / `n_ubatch` | 纯运行期、零风险。原来 256/256，现在默认 512/256。 |
| `GGML_OPENMP` / `GGML_LLAMAFILE` | Android 上都保持 OFF（官方 NDK 构建就是这么写的）。 |
| stable-diffusion.cpp | 有 `flash_attn` / `diffusion_flash_attn`，还有新的模式字符串 `flash_attn_mode`（支持模块级 `auto,te=off,vae=on`）。上游 CLI 默认 auto，而 auto 在 CPU 上会选开 —— 我们原来**硬编码关**。 |

### 18.2 对话提速：四个运行期开关 + 一个编译期开关

改的都是「同样的权重、更少的时间」：

| 开关 | 在哪 | 默认 | 说明 |
| --- | --- | --- | --- |
| Flash Attention | 设置 → 推理加速 | 开（AUTO） | 长 system prompt（人设聊天）收益最大；不支持时自动降级 |
| KV cache 精度 | 同上 | f16 | 可选 q8_0 / q4_0；省内存 → 内存规划器能给出更大上下文 |
| 批大小 / 物理批 | 同上 | 512 / 256 | prefill 吞吐；原来 256/256 |
| 线程数 | 已有 | 大核数 | 与 llama 侧共用 `autoThreads()` |
| KleidiAI | CMake（**只对 arm64-v8a**） | 开 | 见 §18.1；x86_64 / armeabi-v7a 必须关，否则 ggml 直接 FATAL_ERROR |

原生侧加了一条**降级阶梯**：用户要的配置 → 关 flash attention → 关 KV 量化 → 上下文降到 2048。
每一步都记进 `accel_desc`，设置页与诊断页显示的是**真正生效**的那一组，不是用户选的那组。
理由很简单：一个可选的性能选项绝不该换来「这个模型用不了」。

JNI 用**新增函数**（`nativeLoadModelEx` / `nativeAccelInfo`）而不是改老函数的参数表 ——
JNI 的短名解析不含参数，改签名会让「新代码调到旧 .so」按错误的参数表读寄存器，那是崩溃不是报错。

### 18.3 生图提速：最大的那根杠杆原来是**死的**

`ImageSettings.steps` 一直存在、界面上也存着值，但：

* 它**从来没有被传给原生侧**（`nativeSdGenerate` 的参数表里根本没有 steps）；
* 界面上**也没有任何地方能改它**（ImageScreen 只有分辨率与风格）。

也就是说 SD 2.1 一直按 checkpoint 默认的 20 步跑，用户既改不了、也不知道为什么慢。
这一轮把它接上了，并补上界面：

| 改动 | 内容 |
| --- | --- |
| JNI | 新增 `nativeSdGenerateEx`（步数 / 负面提示词 / 线程数 / flash 模式）+ `nativeSdLoadEx` + `nativeSdAccelInfo`；老入口保留，旧插件包照常能用 |
| 线程数 | 原来 `hardware_concurrency()`（**全部核心，含小核**）→ 改成按大核数（和 llama 同一个经验公式） |
| Flash Attention | 原来硬编码 `false` → 改成 `auto`（上游 CPU 默认就是开），可在生图页关掉 |
| 界面 | 新增「采样步数」一行（自动 / 4 / 8 / 15 / 25）与「后端 Flash Attention」开关 |
| 默认值 | `steps` 默认从 4 改成 **0 = 自动**（4 对 SD 1.5/2.1 是错的，对 Turbo 才对） |

### 18.4 顺带修掉的两个构建问题（都直接影响用户下载量）

| 问题 | 后果 | 修法 |
| --- | --- | --- |
| 插件打包时 `ndkDir` 多往上数了一级 | 找不到 `llvm-strip`，只打一行警告 → **插件 zip 里塞的是未 strip 的 147 MB .so**（正常约 37 MB / zip 14 MB） | 路径改成四级 + 找不到时把「找过哪里」打出来 |
| `:app:assembleSdPlugin` 默认 ABI 列表里带 armeabi-v7a | 而 sd.cpp 在 v7a 上根本 configure 不过 → **这个任务一直是坏的**（dist 里也只有 arm64/x86_64 两个包） | 插件用自己的 ABI 默认值 `arm64-v8a + x86_64` |

### 18.5 多接口：对话 / 识图 / 生图 / 语音 / 决策 + 转发

#### 原来是什么样

* 数据层有 `RemoteProvider`，模型选择器会列出来，**但没有任何界面能新建一个接口** ——
  也就是说「接云端 API」在界面上根本走不通，而聊天页的提示还写着「到「能力」页接入一个 API 服务」（那一页没有这个东西）。
* `RemoteChatEngine` 只会说 OpenAI 的 `/v1/chat/completions`，只服务「对话」；识图/生图/语音/决策都没有路径。

#### 现在是什么样

| 新增 | 内容 |
| --- | --- |
| `service/RemoteApi.kt` | 统一入口：**三种协议**（OpenAI 兼容 / Anthropic Messages / Google Gemini generateContent）× 四类任务（对话、识图、生图、语音）+ 原始转发 |
| `ProviderKind` + 预设 | 10 个一键预设（OpenAI、DeepSeek、Kimi、硅基流动、阿里百炼、智谱、OpenRouter、Ollama/LAN、Anthropic、Gemini）；**预设只填地址与示例模型，密钥必须用户自己填** |
| 每任务模型 | `visionModel` / `imageModel` / `speechModel` / `decisionModel`，留空回落到 `model` |
| 识图 | 本机没有多模态模型时，图片会**发给云端多模态模型**（OpenAI 的 image_url / Anthropic 的 image 块 / Gemini 的 inline_data） |
| 界面 | 「我的 → 云端接口」整页：默认后端选择、增删改、**测试连接** |
| 转发 | 本机 `/v1/chat/completions`、`/v1/images/generations` 在 model 指向某个接口时转发（含 SSE 流）；`/v1/models` 也会列出云端接口，客户端能发现它们 |
| 转发的两种做法 | OpenAI 兼容上游：**原样透传**（tools / response_format 全部保住）；Anthropic / Gemini：**翻译转发** —— 请求用适配器发出去，响应再拼回 OpenAI 的形状，客户端无感 |

转发判定只有一条规则（`providerFor`），不另写第二份：

1. `model` 等于某个已启用接口的 id 或名字 → 转给它；
2. `model` 是 localmind / auto / 空 → 看「默认对话后端」；
3. 其余 → 本机模型。

翻译转发的实测（同一次运行）：

```
OpenAI 转发       : {"choices":[{"message":{"content":"MOCK-OPENAI:mock-chat"}}]}
Anthropic 翻译转发 : {"object":"chat.completion","choices":[{"message":{"content":"MOCK-ANTHROPIC:mock-claude"}}]}
Gemini 翻译转发    : {"object":"chat.completion","choices":[{"message":{"content":"MOCK-GEMINI:mock-gemini"}}]}
Anthropic 流式     : data: {"object":"chat.completion.chunk","choices":[{"delta":{"role":"assistant"}}]}
```

> ⚠️ 顺序上有一条容易写错的：**转发判定必须在「本机有没有加载模型」之前**。
> 手机上一个本地模型都没下、只配了云端接口时，客户端照样应该能用。

### 18.6 验证方式：对着 mock 上游跑，不碰真密钥

新增 `tools/mock-upstream.js`（三种协议的假上游，把收到的请求打日志）与三个诊断端点，
于是「接进来但没人验证过」这件事不会发生：

```
POST /v1/debug/provider   {}                      （列出）
                          {"preset":"openai"}       （按预设新建）
                          {"id":"...","test":true} （测试连接）
                          {"backend":"..."}         （切默认后端）
POST /v1/debug/remote     {"name":"...","prompt":"hi","stream":true}  （直接跑适配器）
POST /v1/debug/accel      {"flashAttn":false,"kvQuant":"q8_0","reload":true}  （加速 A/B）
```

**实测结果**（模拟器，mock 在宿主机 10.0.2.2:9099）：

```
OpenAI           不流式=MOCK-OPENAI:mock-chat       流式=MOCK-OPENAI:mock-chat
Anthropic Claude 不流式=MOCK-ANTHROPIC:mock-claude  流式=MOCK-ANTHROPIC:mock-claude
Google Gemini    不流式=MOCK-GEMINI:mock-gemini     流式=MOCK-GEMINI:mock-gemini

# 转发：本机 /v1/chat/completions 指定云端模型名
非流式 200 {"choices":[{"message":{"content":"MOCK-OPENAI:mock-chat"}}]...}
流式     data: {"choices":[{"delta":{"content":"MOCK-O"}}]}
        data: {"choices":[{"delta":{"content":"PENAI:mock-chat"}}]}
        data: [DONE]

# 生图转发：拿走 mock 的 b64 PNG，落盘后返回本机 URL
200 {"data":[{"url":"http://127.0.0.1:8080/v1/images/img_a-cat_0_s0_0x0_remote.png"}]}
取回图片: 200 70 bytes, PNG 头 = PNG
```

加速项的 A/B（同一个 210 token 的 prompt，x86_64 模拟器）：

```
A 旧行为   flash off · kv f16  · 256/256   prefill 7615ms
B 新默认   flash on  · kv f16  · 512/256   prefill 7420ms
C ub512    flash on  · kv f16  · 512/512   prefill 7444ms
D q8_0     flash on  · kv q8_0 · 512/256   prefill 7435ms
```

补充（复测时发现的一个坑，值得记下来）：**模拟器会挑 ARM 库跑翻译层**。
当我只装了 arm64+v7a 那个包时，`primaryCpuAbi` 变成 arm64-v8a，llama 跑在 ARM 翻译层上，
日志里出现

```
kleidiai: no compatible q8 kernels found for CPU features mask 0
kleidiai: no compatible f32 kernels found for CPU features mask 0
done_getting_tensors: tensor 'token_embd.weight' (q5_0) cannot be used with preferred
  buffer type CPU_KLEIDIAI, using CPU instead
```

两条结论：

1. 这是**降级路径正常工作**的表现（找不到内核就退回通用 CPU 实现，不崩不报错）；
   真机上如果看到 `mask 0`，说明 KleidiAI 没吃到这个 CPU 的特性位，
   而不是「编译没生效」—— 验收要看的是有没有 `CPU_KLEIDIAI model buffer size`。
2. 模拟器上的绝对速度数字**不能代表真机**：跑在翻译层时 prefill 慢到 23 tok/s，
   而 x86_64 原生跑同一个 prompt 是 7.7 s / 210 token（约 27 tok/s 也差不多，但这台机器
   本来就慢）。所以「模拟器 A/B 看不出差别」这件事，只能说明**模拟器测不出 ARM 的加速**。

差异在噪声范围内（±2%）。**这符合预期**：x86_64 模拟器上既没有 dotprod/i8mm，
也没有 KleidiAI 的用武之地，flash attention 的收益本来就要长上下文才显出来。
所以这一轮的速度结论只能这样表述：

* **确定修好的是「改不了」**：生图步数、KV 精度、批大小、flash attention 以前要么没有入口、
  要么传不到原生侧；
* **确定接上的是 arm64 的 KleidiAI**（符号已在 .so 里：`ggml_kleidiai_select_kernels_q4_0/q8_0/f32`），
  但**没有真机验证过提速幅度** —— 本机没有 arm64 设备；
** 生图的步数收益是**结构性的**：步数与耗时几乎线性，20 步 → 8 步就是 2.5 倍。

> 这一条必须如实写在文档里：**模拟器测不出 ARM 的加速**。谁拿到真机，先跑
> `POST /v1/debug/accel` 把 A/B 各测一遍，再决定默认值要不要改。

### 18.7 开源声明（用户明确要求）

| 新增条目 | 为什么 |
| --- | --- |
| **KleidiAI（Arm）** Apache-2.0 / BSD-3-Clause | arm64 构建里**真的编进去了**（`GGML_CPU_KLEIDIAI`）。它的许可证与 MIT 不同，署名要求是 Arm Limited，所以必须单独点名，不能并进 ggml 那条 |
| **libc++（Android NDK）** Apache-2.0 | `ANDROID_STL=c++_shared` 会进 APK，属于实际分发物 |
| llama.cpp / ggml / stable-diffusion.cpp | 已有条目，但把**出处与许可证**写全（llama.cpp 与 ggml 是 MIT、The ggml authors；sd.cpp 是 MIT、leejet） |

> Apache-2.0 第 4 条要求随分发提供许可证副本。KleidiAI 是构建期 FetchContent 下载的，
> 所以**签正式包时要把 kleidiai 源码包与它的 LICENSES/ 一起归档** —— 只在界面上写一行不够。
> 这一条写在这里，免得以后有人以为「声明里加了名字就完事」。

### 18.8 这一轮改动的文件

| 文件 | 改动 |
| --- | --- |
| `cpp/llama_jni.cpp` | 加速字段 + 降级阶梯 + `nativeLoadModelEx` / `nativeAccelInfo` |
| `native/llama/LlamaBridge.kt` | 新 JNI 声明 + `loadModel(...)`（拿不到符号自动退回老入口） |
| `data/Models.kt` | `GenerationSettings` 加 flashAttn / kvQuant / batchSize / ubatchSize；`ImageSettings.steps` 默认改 0（自动）+ `flashAttn`；`ProviderKind` + `RemoteProvider` 扩字段 + 10 个预设 |
| `data/MemoryPlanner.kt` | `plan(..., kvBytesPerElement)`：量化 KV 时上下文估算按实际元素大小 |
| `service/LlamaEngine.kt` | 传加速参数、记录生效值 |
| `service/ImageEngine.kt` | 生图带上 steps / threads / flash |
| `native/image/ImageBridge.kt` | `nativeSdLoadEx` / `nativeSdGenerateEx` / `nativeSdAccelInfo` + 旧插件回退 |
| `cpp/sd-alone/sd_jni.cpp` | 步数、负面提示词、线程数、flash 模式；配置进 key（改了会重建上下文） |
| `service/RemoteApi.kt` | **新增**：三协议 × 四任务 + 原始转发 |
| `LocalMindApp.kt` | 容器里加 `remoteApi` |
| `ui/AppViewModel.kt` | 接口增删改查 / 测试 / 默认后端；`send()` 改走 RemoteApi（含带图） |
| `ui/screen/ProviderScreen.kt` | **新增**：云端接口整页 |
| `ui/screen/ProfileScreen.kt` | 「我的 → 云端接口」入口 |
| `ui/screen/SettingsScreen.kt` | 「推理加速」一栏 |
| `ui/screen/ImageScreen.kt` | 采样步数 + 后端 flash attention |
| `ui/screen/AboutScreen.kt` | 开源声明加 KleidiAI / libc++ |
| `service/ApiServer.kt` | 转发（chat / images）+ `/v1/models` 列云端 + `debug/provider`、`debug/remote`、`debug/accel` |
| `app/build.gradle.kts` | 插件 zip 的 strip 路径、插件自己的 ABI 列表 |
| `tools/mock-upstream.js` | **新增**：三协议假上游（验证用） |

### 18.9 这一轮**没有**做完 / 需要真机验证的

| 项 | 状态 |
| --- | --- |
| arm64 上 KleidiAI 的实际提速 | **未验证**。生成阶段确认符号编进 .so（`ggml_kleidiai_select_kernels_q4_0/q8_0/f32`），CMake 缓存确认只有 arm64 打开；但本机没有 arm64 设备 |
| 生图插件的重新打包 | ✅ 已完成。`dist/plugins/本地生图后端插件-通用版.zip`（arm64-v8a + x86_64，30.7 MB）带 `nativeSdGenerateEx2` / `nativeSdUniversal`；旧插件仍能出图，按 Ex → 基础入口回退 |
| 生图步数的实测收益 | 结构上确定（步数与耗时线性），但需要重新打包的插件 + 一个真实 checkpoint 才能跑出数字 |
| 语音走云端（`/audio/speech` 适配器） | 代码就位（`RemoteApi.speech`），**接口未接**：TtsEngine 还是只走本机与神经音色 |
| 决策模型走云端 | `RemoteProvider.decisionModel` 字段就位，DecisionEngine 仍只跑本机专用引擎 |

### 18.10 一个真的会让人问「怎么还会显示」的不一致（已修）

内测的人问：**「这个模型插件没装，怎么模型列表还会显示？」**

答案是设计如此 —— 但要先把三份清单的区别摆出来，因为它们条数不同、来源不同：

| 清单 | 条数 | 什么时候用 |
| --- | --- | --- |
| APK 内置（`assets/model-library/model-library.json`） | **34** | **首次运行自动装上**（[BuiltinPacks]），让「装完就能下模型」成立 |
| 官方模型库插件（`dist/plugins/官方模型库插件.zip`） | **83** | 用户自己导入；packId 与内置相同，导入即**替换**内置那份，不会重复 |
| 用户导入的 .gguf | — | 模型页的「本机文件」 |

内置那份是**一次有意的例外**（见 [BuiltinPacks] 的类注释）：清单只有 32 KB、本来就打在 APK 里，
而一个刚装好的应用如果模型页是空的，用户看到的是「这应用坏了」。
边界写死了三条：只在**没有任何模型包**时动手、只写清单不碰权重、失败就什么都不做。

**顺手抓到的真问题**：`plugins-src/model-library/` 里的源是 **2.1.0 / 52 个模型**，
而 dist 里发出去的插件 zip 是 **3.0.0 / 83 个模型** —— 也就是说**发出去的那份比仓库里的源新**。
谁要是从 `plugins-src` 重新打一次包，就会把 83 条**静默降级回 52 条**（少了识图/生图那几类的一部分）。

已把 zip 里的 3.0.0 内容写回 `plugins-src/model-library/`，旧源留在同目录的 `_old-v2.1.0/` 下备查。
同步后：plugin version = 3.0.0、models = 83、voices = 17。

> 教训：**「打包产物」与「打包源」必须有一处能互相校验**。这个仓库没有提交历史，
> 源比产物旧时没有任何提示 —— 只能靠人肉比对条数。

### 18.11 ⚠️ 上一节的决定**已被推翻**：APK 不再自带模型库

§18.10 我写的结论是「内置清单是有意的兜底，保留」。用户否掉了，理由是**法律层面的**：

> 原 apk 应该没有模型库 要不然会被告

这是对的，而且比我原来的论证更重要：那份 JSON 是**第三方模型的索引**（名字、仓库、文件、
许可、体积）。把它打进主程序包，等于主程序在**分发/推荐**这些模型 —— 许可与责任跟着 APK 走。
「新装的应用模型页空白不好看」是个产品问题，不该用法律风险去换。

**改了什么**：

| 动的东西 | 结果 |
| --- | --- |
| `app/src/main/assets/model-library/model-library.json`（34 条，35 KB） | **删除** |
| `data/BuiltinPacks.kt` | **删除**（连同 `LocalMindApp` 里首次运行的自动安装） |
| 模型页空态 | 新增：拼图图标 + 「还没有模型库」+ 一句话说明清单来自插件 + 「去导入模型库插件」按钮 |

插件那条路不变：导入 `官方模型库插件.zip`（3.0.0 / 83 个模型 + 17 个音色）之后模型页照常工作。

**实测（模拟器，全新安装）**：

```
pm clear → 启动 → 模型页：模型 0 B，「还没有模型库」+ 按钮（截图见回复）
POST /v1/debug/plugin {"path":"/data/local/tmp/official-models.zip"}
  → 已安装 LocalMind 官方模型库（83 个模型）、语音音色（17 个）
GET /v1/models → 模型条数 = 83
```

> 顺带把「模型页只有可下载（19/34）」这个没人能解释的数字也补了说明：现在那一行下面会写**清单来源**。

### 18.12 版本号规矩：按**发布批次**走，不是按我改了几轮

我这一轮自作主张把版本从 1.0.2 加到了 1.0.3，用户纠正：**这一批对外就叫 1.0.2**。

已改回：`versionCode = 3`、`versionName = "1.0.2"`；dist 里的产物也重打并删掉了 1.0.3 那两个文件。
规矩写进 `build.gradle.kts` 的注释里了：**版本号只在发布批次变化时动，不要因为内部又修了几次就往上加。**

（注意：`adb install -r` 不给 `-d` 时装不了更低 versionCode —— 真机从 1.0.3 回到 1.0.2 用的是 `install -r -d`。）


---

## 19. 第十二轮（2026-10-17 晚）：1.0.4 —— 语音换血 + 界面插件化 + 提速

用户这一轮的 4 句话：

> 1. 把大模型运行速度和多模型同时运行的方式优化，无论是什么办法；
> 2. 把原有的 tts 方式修改，改成刚才告诉你的 breezet ts2 这个，单独一个页面，并且也是由插件控制开关；
> 3. 插件的能力完善丰富，让插件可以干更多事情；
> 4. 把界面 ui 做成插件……我的里面那个更多里面的主题设置插件主题就是说这个 ui 整体的更改，
>    而不是这种色调等的小更改；
> 5. 修复已知影响使用的问题，现在是 1.0.3 内测版，该更新 1.0.4 了。

### 19.1 结论先说：Breeze TTS 2 **不能**在手机上本地跑

这一点必须写死在文档里，否则下一个人会重新试一遍：

| 事实 | 数据 |
| --- | --- |
| 参数量 | 3,466,363,713（HF safetensors 元数据） |
| 权重 | BF16 约 6.96 GB（两个分片 4.96 GB + 2.00 GB）+ codec 0.68 GB |
| 结构 | T5Gemma2 文本编码器 + Qwen3 风格主干（28 层 / 2048 隐层）+ 12 层深度解码器 + Mimi 系 codec（24 kHz / 12.5 Hz / 16 码本） |
| 官方推理路径 | **只有 PyTorch + CUDA**（`infer.py` / `breeze_infer`），eager 也要 7.7 GiB 显存 |
| 结论 | Android 上没有 CUDA、没有对应运行时、也没有 llama.cpp 支持（它不是 GGUF 可加载的 Llama 结构） |

所以本轮的做法是：**手机当客户端**，模型跑在 PC / GPU 服务器上，
用 `tts_engine` 插件声明端点。这是唯一诚实的接法 —— 假装"本地部署"只会让用户
下载 7 GB 之后得到一个跑不起来的模型。

### 19.2 新增能力：`tts_engine`（语音引擎）

| 位置 | 内容 |
| --- | --- |
| `data/TtsPack.kt` | `tts.json` 的 schema + 校验（kind=breeze/openai、format=pcm_s16le/wav、baseUrl 前缀、采样率区间、重复 id） |
| `service/BreezeTts.kt` | HTTP 客户端：multipart（text/instruction/voice/cfg_scale/seed/ref_audio/ref_text）/ JSON（OpenAI 兼容）、裸 PCM 自动套 44 字节 WAV 头、MediaPlayer 播放 |
| `ui/screen/BreezeScreen.kt` | 独立页：状态 → 引擎列表 → 连接配置（地址/密钥/模型/音色/演技/CFG）→ 试听 → 关于 |
| `AppViewModel` | `BreezeState` + `refreshBreeze()` / `setBreezeEnabled()` / `saveBreezeSpec()` / `testBreeze()` / `speakViaBreeze()` |
| `TtsEngine` 路由 | `speak()` 先试外部引擎 → 失败**静默退回**神经/系统音色（用户要的是"能听见"） |
| 入口 | 「我的 → 更多 → Breeze 语音」（子页）+ 布局插件可以把 `breeze` 放到底栏 |

实测（模拟器 x86_64，端点填 127.0.0.1:7860 故意不通）：
合成按钮 → 状态栏显示「上次的错误：Failed to connect to /127.0.0.1:7860」，
**不崩、不卡界面**，这是刻意验证的失败路径。

### 19.3 新增能力：`ui_layout`（界面布局）

和 `ui_theme`（37 个外观令牌）分工：**布局管结构，主题管颜色**。

`layout.json` 能声明：

| 字段 | 作用 |
| --- | --- |
| `tabs[].id / screen` | 底栏有哪几个入口、每个进哪一页（chat / models / me / home / voice / breeze / plugins / image） |
| `startTab` | 启动落在哪一页 |
| `home[]` | 首页的块：`hero` / `actions` / `text` / `recent` / `status` |
| `appName` / `subtitle` | 应用标题与首页副标题 |
| `tokens` / `darkTokens` | 顺带覆盖外观令牌（优先级低于用户手动覆盖） |

渲染侧：`ui/screen/LayoutHomeScreen.kt`（块式首页 + 内置默认首页）、
`LocalMindRoot` 按 `Tab.fromId()` 动态生成底栏、`Tab` 枚举扩到 8 个页面。

**优先级**：内置 iOS 原色 → 主题插件 → 布局插件 → 用户手动覆盖。
理由：布局通常整体成套（同一套设计语言），而用户的手动微调永远最大。

### 19.4 提速与"多模型同时跑"

真机约束是**内存容量**，不是算力也不是并发。这轮落地的是：

| 改动 | 说明 |
| --- | --- |
| 启动预热 | `preloadChatModel()`：启动 0.9 秒后把上次的对话模型加载进来（可关，默认开） |
| 常驻面板 | 设置页新增「常驻与预热」：列出**真正**在内存里的东西（对话 / 决策 / 语音 / 生图后端）+ 立即预热 / 卸载 |
| 池化不做 | 试过"保留上一个模型的热态"，但 `engineState` 是在构造时捕获的 `StateFlow`，换引擎实例会让界面永远看到旧状态。**要做得先重写状态层，不是接线**。写在这里防止下一个人再试一次 |

"同时跑多个"的正确做法（文档已写进设置页脚注）：把常驻压到最小、其余按需换入 ——
决策模型只有 1K 上下文、语音与生图都是用到才进内存。

### 19.5 ⚠️ 又踩了同一个坑：StateFlow 必须声明在 init 之前

第一次装 1.0.4 直接闪退：

```
NullPointerException: MutableStateFlow.getValue() on a null object reference
  at AppViewModel.refreshBreeze(AppViewModel.kt:3168)
  at AppViewModel.<init>(AppViewModel.kt:927)
```

原因和 §6.2 一模一样：`_breeze` 一开始声明在文件后半段，而 `init` 里调了
`refreshBreeze()`。修复=把声明挪到「插件驱动的状态」那一段（`_uiTokens` 旁边）。

**教训**：任何会被 `init` 碰到的 StateFlow，一律放那一段。
这次的注释也写进了代码里（搜索 "已经踩过一次"）。

### 19.6 同时修掉的问题

| 问题 | 修法 |
| --- | --- |
| Breeze 页空态文字被裁掉半句 | 固定 220dp 的 Box 改成 380dp，并缩短文案（实测截图确认） |
| 首页状态行「对话引擎」和值重叠 | 名字固定 88dp，值 `weight(1f)` + 右对齐 + 两行 |
| 布局插件把语音/生图/插件页放到底栏后，返回键直接退出应用 | 返回键先回第一个标签（那几页没有自己的返回键） |
| 「我的」里 Breeze 入口显示"未安装"却在装了之后不刷新 | 副标题读 `breeze` StateFlow，插件装完自动变 |

### 19.7 本轮交付物

| 文件 | 说明 |
| --- | --- |
| `dist/LocalMind-1.0.4-内测-arm64+armv7.apk` | 真机包（versionCode 5） |
| `dist/plugins/breeze-tts2-plugin.zip` | `tts_engine` 插件（1.1 KB，只有连接方式） |
| `dist/plugins/ui-layout-card-plugin.zip` | `ui_layout` 插件（卡片首页 + 4 入口 + 青色主色） |
| `plugins-src/breeze-tts2/`、`plugins-src/ui-layout-card/` | 上面两个插件的源 |

### 19.8 还没做 / 需要真机验证

| 项 | 状态 |
| --- | --- |
| arm64 真机的 Breeze 合成 | ❌ 需要一个真的跑着 Breeze TTS 2 的服务器（本机没有 GPU 服务） |
| 语音克隆（ref_audio + ref_text） | ⚠️ 客户端已支持这两个字段，界面还没做"上传参考音频"的入口 |
| 底栏多于 4 个入口的排版 | ⚠️ 模拟器只验到 4 个（毛玻璃药丸在第 5 个时会被压扁，未调） |
| 布局插件的 `label` / `icon` | ⚠️ `label` 解析了但底栏仍用页面自带的名字（图标同理）。要做得改 `LiquidGlassTabBar` 的 spec |
| 预热后的真实提速数字 | ⚠️ 需要真机（模拟器 2.2 tok/s 没有参考价值） |


---

## 20. 第十三轮（2026-10-17 深夜）：`pipeline` —— 跑不动的模型，拆开跑

用户的原话：

> 搞一个插件，安装后本地部署 breeze，跑不动就想办法拆解步骤单个跑聚合在一起，还有大模型也是。

### 20.1 先说清楚两件事

1. **Breeze 本地部署不成立**：3.47B / 7 GB / 只有 CUDA 推理（见 §19.1）。这一条不会因为
   "拆开跑"而改变 —— 拆开的是**任务**，不是模型图层。把 3.47B 的权重按层搬到手机内存里
   逐层算，需要的是一个能在 ARM 上跑这个架构的运行时（T5Gemma2 + Qwen3 主干 + 深度解码器
   + Mimi codec），现在没有。任何"分层搬运"的方案都先要解决这个问题。
2. **能拆的是任务**。把"读 5000 字并总结"拆成 N 块、把"念 3000 字"拆成 N 句，
   每块/每句单独执行、最后聚合 —— 这一条在手机上立刻成立，而且对**任何**后端
   （本机 llama.cpp、Breeze 服务、任意 OpenAI 兼容接口）都适用。

### 20.2 新增能力：`pipeline`（分步流水线）

| 位置 | 内容 |
| --- | --- |
| `data/PipelinePack.kt` | `pipeline.json` schema + 校验（动作白名单、stage id 唯一、llm 必须含 `{{chunk}}`、参数区间） |
| `service/PipelineRunner.kt` | 执行器：`probe` / `split_text` / `llm` / `join_text` / `tts` / `concat_audio` |
| `ui/screen/PipelineScreen.kt` | 选流程 → 粘输入 → 看进度 → 看结果（文本 / 播放音频） |
| `AppViewModel` | `PipelineUi` 状态 + `refreshPipelines()` / `runPipeline()` / `cancelPipeline()` |
| 入口 | 「我的 → 更多 → 分步流水线」；布局插件也可以把 `pipeline` 放到底栏 |

三条设计规则（写在 `PipelineRunner` 文件头）：

* **每一步独立可失败**，错误里带"第几步、第几片、前几片已完成"，方便只重跑失败那段；
* **内存占用与输入长度无关**：每片处理完只留产物，不留中间态；
* **随时可中断**：长文几十片，用户改主意要能立刻停。

### 20.3 插件里带的两条流程

**A. Breeze 分步合成**（`probe → split_text(120) → tts → concat_audio`）

长文按句号/问号/换行切成 ≤120 字的分片，逐片调用当前语音引擎，最后拼成一个 wav
（段间 250 ms 静音）。`probe` 先把本机内存、Breeze 的体量、"为什么本机跑不动"打在页面上，
让用户一开始就知道这条路是"借别人的算力"。

**B. 长文分段理解**（`split_text(800) → llm → join_text`）

每块单独进上下文、单独出结论，最后拼起来。**这就是"大模型也拆开跑"的落地**：
上下文装不下一整篇的小模型，靠这个读长文。实测（模拟器 / qwen2.5-0.5b）：
198 字的输入 → 一块 → 一次生成 → 结果区正常输出。

### 20.4 顺手修掉的第 5 个 bug：诊断端点装插件不刷新界面

`POST /v1/debug/plugin` 只写存储、不 `bumpPluginRevision()`，于是脚本装的插件
"装成功但界面没反应"（主题、语音引擎、流水线列表都不更新，必须杀进程重进）。
现在装完统一 bump。这一条是实测出来的：装完流水线插件，我的页仍显示"0 条"，
重启后才变 2 条。

### 20.5 交付物

| 文件 | 说明 |
| --- | --- |
| `dist/plugins/pipeline-staged-plugin.zip` | 1.5 KB：两条流程的声明，不含任何二进制 |
| `plugins-src/pipeline-staged/` | 源（`plugin.json` + `pipeline.json`） |
| `dist/LocalMind-1.0.4-内测-arm64+armv7.apk` | 重新打包（含流水线） |

### 20.6 还没做

| 项 | 说明 |
| --- | --- |
| 真正的分层推理（AirLLM 式逐层搬运） | 需要能跑该架构的 ARM 运行时；llama.cpp 只吃 GGUF 的 Llama 结构 |
| 失败片重试 | 目前要整条重跑（错误信息里已给出"前 N 片完成"） |
| 并行分片 | 故意不做：引擎单实例 + 服务端通常单并发，并行只会全变慢 |
| 音频响度归一 | 拼接只做静音间隔，没做 LUFS 归一（不同片音量可能不齐） |

# 进度与待办

> 更新于 2026-10。**已在 Android 15 x86_64 模拟器上实测跑通**：
> 编译 → 安装 → 加载模型 → 中文对话 → OpenAI 兼容 API → 神经语音合成 → 运行时按需下载原生库。
> 项目全貌见 [PROJECT.md](PROJECT.md)，生图障碍见 [BLOCKERS.md](BLOCKERS.md)。


## ✅ 第十三轮（2026-10-17）：1.0.3（内测）—— 生图提速 + 自定义 API 入口 + 应用图标

| # | 改了什么 | 之前 | 之后 |
| --- | --- | --- | --- |
| 1 | 默认出图分辨率 | 768² | **512²**（老用户停在 768² 的那一份一次性迁移过去） |
| 2 | 「自动」步数 | 交给 sd.cpp 默认 = **20 步** | 按模型名推断：Turbo/LCM/SDXS **4 步**，其余 **12 步** |
| 3 | Turbo 系的 CFG | 3.5（每步跑 cond+uncond 两遍） | **1.0**（sd.cpp 直接跳过 uncond） |
| 4 | 大图 | 768²/1024² 直接算 | **快速放大**：先 512 出图再放大（默认开） |
| 5 | 模型加载 | 算在**第一张图**的时间里 | 进「生图」页就在后台**预热** |
| 6 | 等待体验 | 只有一个转圈 | 「已用 X 秒 / 上次 Y 秒」+ 出图后「512²→768² · 12 步 · 8.4 秒」 |
| 7 | 模型选择器 | 未下载的模型也列出来（选了只会触发下载、选择被丢掉） | **没下载的不显示**；最下面新增「＋ 手动添加自定义 API 地址」（现场填 Base URL / Key / 模型名，接入即绑定） |
| 8 | 应用图标 | 手绘矢量气泡 | **小柠 捐的那张**（五个密度 + 自适应图标全出血） |
| 9 | 关于页 | — | 新增「应用图标捐赠」一行（头像 + 昵称 + 捐赠人），无说明文字；版本号后面不再拼 `(4)`（那是 versionCode，只留在诊断报告里） |
| 10 | 应用图标 | 全出血铺满 → 启动器遮罩把四角和签名切掉（用户原话「图标显示不全」） | 背景层 = 原图底色 `#FFCE65`，前景层 = 原图缩到 **70%** 居中（四角半径 0.495 < 遮罩 0.5），整张图连签名都在遮罩内 |
| 11 | 生图模型 | 生图页只有「重新扫描」，**没法选**用哪个 checkpoint | 新增「选择」：列出已下载的扩散模型（Turbo/SDXS 标「快」），选中写进 `ImageSettings.customModelPath`；优先级 `生图页选择 → 对话的 imageModelId → 自动扫描`，两个入口共用 `imageModelPathFor` |
| 12 | 模型分类标签 | 12 个**写死**的枚举：插件里有、枚举里没有的标签永远显示不出来，反之点进去是空页 | 改成**按插件内容实时生成**：推荐 / 全部 + 角色（看图/生图/语音/向量）+ 系列（Qwen / DeepSeek / Kimi / Gemma / Hunyuan…，取前 6）+ 标签（按出现次数取前 8） |
| 13 | 「我的 → 主题」 | 被 `uiThemes.isNotEmpty()` 挡着 —— 没装主题插件就永远看不到这个入口，尽管主题页里有 4 个不需要插件的预设 | **始终显示**，副标题跟着当前生效的插件主题 / 预设走 |
| 14 | prefill 线程数 | 和生成一样（4） | `min(核数, 6)` 且不少于生成线程；诊断页显示 `threads=4/6` |
| 15 | KV cache | f16 | **q8_0**（上下文 3584 → 7168，同样内存）；不允许时自动退回 f16 |
| 16 | prefill 逻辑批 | 512 | **1024**（少切图；占内存的是 ubatch，几乎不涨） |
| 17 | 生图线程 | `autoThreads()` = 4 | `max(4, min(核数, 6))` |
| 18 | 老用户怎么拿到 14–16 | 默认值只在第一次写盘前生效 | 一次性迁移（`gen_v2`）：**精确匹配旧默认值才迁**，用户调过的不碰 |
| 19 | 模型预热 | 权重的缺页全落在「第一句」里 | 加载阶段顺序预读一遍（1 MB 缓冲），只在该模型装得进内存时做 |
| 20 | **生图插件「通用版」** | 只认 `.gguf`；采样器写死默认；出图时只有转圈 | ①格式通用（`.gguf` / `.safetensors` / `.ckpt`）；②采样器/调度器可覆盖（LCM 家族自动用 LCM，其余交给 sd.cpp 按模型族决定）；③**逐帧进度**（界面「第 5/12 步」）；④一个 zip 带 arm64-v8a + x86_64，安装时按 ABI 自选 |
| 21 | 插件打包的 `llvm-strip` | 路径靠「数几级 parentFile」，错了两次（分别指到 SDK 根和 `ndk/`），于是 zip 里塞的是**未 strip 的 147 MB** .so | 改成**向上找** `toolchains/llvm/prebuilt`：strip 后 35.2 / 36.1 MB，zip 从 ~300 MB 降到 **28.7 MB** |

**净效果**：默认设置下 768²@20 步 → 512²@12 步 ≈ **3.7 倍**；Turbo 类模型
（4 步 + CFG 1）比「20 步 + CFG 3.5」快 **5 倍以上**，第一张图还少了模型加载的几十秒。
完整推导与「还能再快吗」（要动插件的那几条）见 [IMAGE_ENGINE.md](IMAGE_ENGINE.md) §四。

验证：模拟器上（无扩散后端）确认分辨率默认 512²、快速放大开关、步数说明、
老设置迁移都按预期。

> **2026-10-17 追加**：原生侧后来加了通用版入口（`nativeSdGenerateEx2`：采样器/
> 调度器覆盖 + 逐帧进度 + `nativeSdUniversal`），所以“不需要重装插件”只对
> 最初的 1.0.3 UI 改动成立。现在要拿到采样器和进度，必须重新导入
> `dist/plugins/本地生图后端插件-通用版.zip`（arm64 + x86_64 同一个包，
> 安装器按本机 ABI 自动选）。旧插件仍能出图，走 Ex → 基础入口回退。


## ✅ 第十二轮（2026-10-17）：把「对话慢」真正修掉 + API 直接进对话设置

> 详细原理、判据与**为什么这么改**见 [SPEED.md](SPEED.md)。这一节只写结论和实测数字。

### 根因：提示词布局把 KV cache 废掉了

llama.cpp 的 KV cache 只能复用「从提示词开头**逐字节相同**」的那一段。而人设聊天的
system prompt 里塞着「聊过几轮 / 几点 / 心情 / 召回了哪几条记忆」—— 这些**每轮都变**，
于是公共前缀在 system prompt 中段就断了：后面的人设、历史、全部重算一遍。
手机上 prefill 常常比逐 token 生成还慢，所以这不是「慢一点」，是「十几秒」。

| 改动 | 之前 | 之后 |
| --- | --- | --- |
| 提示词布局 | 易变内容（心情/轮次/记忆/设备/联网）全在 system prompt | 拆成**稳定段**（system prompt）+ **易变段**（挂在最新一条用户消息前，并**存进那条消息**） |
| 历史窗口 | `takeLast(24)`，超过 24 条后每轮整体前移 → 前缀全废 | 按 6 条（3 轮）为步长滑动 → 每 3 轮才断一次 |
| 末尾空 assistant 占位 | 进历史（正好占住「回答该出现的位置」） | 不进历史 |
| 上一轮的回复 | 用**解析后**的文本重建（标记被剔除、被拆成几条、被 trim）→ token 对不上 | 存**原样生成**的文本（`ChatMessage.rawContent`），短话连发只保留第一段 + 其余标 `promptSkip` |
| 学习（记忆/经验/关系总结） | 在 `onTurnComplete` 里**就地 await**，用户的下一句排在它后面 | 后台作用域 + 空闲窗口 + `Lane.BACKGROUND`（前台来了直接掐掉后台那轮） |
| 召回 / 设备感知 / 联网 | 一条条串行 | `async` 并行，只等最慢的那个 |
| 对话落盘 | 主线程同步序列化**全部**对话，一次发送跑两遍 | 浅快照 + IO 线程 + 250 ms 合并；结构性改动立即写 |
| tok/s 显示 | 数的是「回调片段数」（中文一片一字符）→ 高估 2-3 倍 | 用原生报的真实 token 数 |

### 实测（Android 15 / x86_64 模拟器 / Qwen2.5 0.5B Q4_K_M）

| 轮次 | prompt | 复用 | 新增 | 说明 |
| --- | --- | --- | --- | --- |
| 第 1 轮 | 700 | 0 (0%) | 700 | 冷启动，无缓存可复用 |
| 第 2 轮 | 1051 | **719 (68%)** | 332 | 稳定段 + 第一轮已命中；剩下的是上一轮的 6 条短话 + 易变段 |

> 模拟器的 prefill 被**磁盘 I/O** 主导（mmap 首次要把 468 MB 权重读进来，约 7 tok/s），
> 所以这里**不比墙钟时间**，只比 `reused / prompt` —— 那才是布局改对没改对的判据。
> 真机上 prefill 快一个数量级，复用率提升直接体现在首字时间上。

### 顺带：OpenAI 格式 API 现在就在对话设置里

「AI 对话设置」和「人设聊天设置」都多了一块 **用 API 回答（OpenAI 格式）**：一键切本机/接口、
现场填 Base URL + Key + 模型名（自动补 `/v1`）、点一次就接入 + 绑定这个对话 + 探活。
走 API 时**记忆 / 情绪 / 经验照常工作**（那些是程序算的）。

**已实测**（模拟器 + `tools/mock-upstream.js`）：同一个人设对话切到假上游后，
请求是 `POST /v1/chat/completions`、`Bearer sk-test`、`stream:true`，system 里是完整人设，
气泡回 `MOCK-OPENAI:mock-chat`，**整轮约 1 秒**（走本机 0.5B 时这台模拟器要几分钟）。

### 新发现并修掉的一个统计 bug

`LlamaEngine` 原来把「token 回调次数」当 token 数：中文一个字常常就是一个回调，
于是界面上的 tok/s 比实际高 2-3 倍。现在改用原生侧的 `perf_stats`（真实 token 数），
并且那一行日志同时给出 `reused / prompt / prefill_ms / 首 token_ms`，回归一眼就能看出来。


## ✅ 第十轮（2026-10-16）：内测视角的一轮 —— 8 个真问题已修 + 模拟器复测

> 走法：按**一个刚拿到包的内测用户会怎么用**从头走一遍（全新安装 → 引导 → 装模型 →
> 聊天 → 语音 → API → 关于页 → 测试模式），每一条都在模拟器上真实复现过。
> 逐条记录（含「为什么以前没被发现」）见 [HANDOFF.md](HANDOFF.md) §17。

| # | 问题 | 影响 | 修法 |
| --- | --- | --- | --- |
| 1 | `readGguf` 在 512 KB 窗口边缘抛 `BufferUnderflowException`，被 `runCatching` 吞掉 | 完好的 GGUF 被判成「非 GGUF」；**内存规划器静默退回默认几何**（层数 0 / ctx 恒 4096） | 走访补剩余字节检查 + 单步兜底；复测 ctx 4096 → **18944**，卡片显示 `qwen2 · 24 层 · d=896 · ctx 32768` |
| 2 | `ModelStore.resolveAll()` 按**文件名**去重 | 内置清单 34 条只列 33 条，**唯一的中文决策模型选不到**；官方模型库插件会一次少 5 条 | 去重键改成「文件名 + 用途」；复测 `/v1/models` 33 → **34** |
| 3 | 通知权限在 `onCreate` 无条件申请 | Android 13+ 拒绝后系统还会再弹 → **每次启动都弹同一个窗** | 记 `notif_asked`，一辈子只问一次；复测：拒绝后再启动不再弹 |
| 4 | 系统发音人列表不按语言筛、标签是引擎内部名 | 442 个里前 12 个是阿拉伯语/保加利亚语；中文用户一个都用不上 | `voicesFor(language)` 筛选 + 人类可读且可区分的标签；复测 442 → **16** |
| 5 | Agent / Shell 开关没有消费端但可以拨 | 用户拨完没反应 → 「这应用不靠谱」 | 置灰不可拨 + 说明原因 |
| 6 | `deleteModel` 只删 `.part`，不删多连接的段目录 | 取消/删除下载后几百 MB 永远留在手机上，界面上完全不可见 | 统一清理两种临时产物 + 孤儿 mmproj；测试模式新增「未完成的下载」；复测 239 MB → 0 |
| 7 | 设置页「自动线程数」写的是 `cores-1`，实际是 `cores<=4 ? cores : 4` | 界面上的数字与实际不符 | 收敛成 `MemoryPlanner.autoThreads()`；复测「自动（4）」与 `threads=4` 一致 |
| 8 | 未下载的模型也显示「非 GGUF / 无法解析头部」 | 用户以为文件坏了 | 未下载就不显示该行 |
| + | 上一轮记下的「清单里没有的 GGUF 在应用里看不见」 | 「我下过的东西不见了，磁盘还被占着」 | 新增孤儿扫描，作为「本机文件」列出，可加载可删除；复测 34 → 35 → 删除后回到 34 |

**回归**：自检 12/13（唯一红的是「生图链路」，如实报告缺后端与 checkpoint）；
聊天与 `/v1/chat/completions` 正常；logcat 无 FATAL。
产物：`assembleRelease`（arm64-v8a + armeabi-v7a）**14.39 MB**。


## ✅ 第十一轮（2026-10-16）：提速 + 多接口（对话 / 识图 / 生图 / 语音 / 决策 + 转发）

> 逐条记录（含外部调研结论、A/B 数据、mock 实测输出）见 [HANDOFF.md](HANDOFF.md) §18。

### 提速

| 改动 | 之前 | 之后 |
| --- | --- | --- |
| 生图采样步数 | **字段存在但传不到原生侧，界面上也没有入口**（SD 2.1 只能按默认 20 步跑） | 接进 JNI + 界面新增「采样步数」（自动/4/8/15/25）；步数与耗时几乎线性 |
| 生图线程数 | `hardware_concurrency()`（含小核） | 按大核数（与 llama 同一公式） |
| 生图 Flash Attention | 硬编码 `false` | `auto`（上游 CPU 默认），界面可关 |
| 对话 Flash Attention | 硬编码 `DISABLED` | AUTO，可在设置里关（Arm 上有负收益的实测报告，所以必须能关） |
| KV cache 精度 | 固定 f16 | 可选 q8_0 / q4_0（省一半内存 → 规划器给出更大上下文） |
| 批大小 / 物理批 | 256 / 256 | 512 / 256 |
| 原生降级阶梯 | 无（加速项失败 = 模型加载失败） | 四级降级，**只有真正生效的配置会被显示** |
| ARM KleidiAI | 没开 | arm64-v8a 开启（x86_64 / v7a 必须关）；符号已确认编进 .so |

**必须说清楚的一条**：x86_64 模拟器上 A/B 差异在 ±2% 噪声内（没有 dotprod/i8mm，
也没有 KleidiAI 的用武之地）。所以「提速」里**确定**的是「以前改不了的东西现在能改了」，
以及 arm64 的 KleidiAI 已经**编进去**（真机提升幅度未验证 —— 本机没有 arm64 设备）。

### 多接口 + 转发

| 能力 | 状态 |
| --- | --- |
| 三种协议 | OpenAI 兼容 / Anthropic Messages / Google Gemini —— 对话与识图都走通了（mock 实测） |
| 四类任务 | 对话、识图、生图、语音（+ 决策模型字段）都能指向云端接口 |
| 界面 | **新增「我的 → 云端接口」整页**（以前根本没有入口，数据层一直在空等） |
| 转发 | 本机 `/v1/chat/completions`、`/v1/images/generations` 可把请求原样转给上游（含 SSE 流） |
| 模型发现 | `/v1/models` 会列出已启用接口，客户端能直接选 |
| 验证 | `tools/mock-upstream.js`（三协议假上游）+ `POST /v1/debug/{provider,remote,accel}`；实测输出见 HANDOFF §18.6 |

### 顺带修掉的两个构建问题

* 插件打包的 `llvm-strip` 路径多数了一级 → 插件 zip 里塞的是**未 strip 的 147 MB .so**（正常 37 MB）；
* `:app:assembleSdPlugin` 默认带 armeabi-v7a，而 sd.cpp 在 v7a 上 configure 不过 → **这个任务一直是坏的**。

### 开源声明

新增 **KleidiAI（Arm，Apache-2.0 / BSD-3-Clause）** 与 **libc++（Apache-2.0）** 两条；
并把 llama.cpp / ggml / stable-diffusion.cpp 的许可证与版权人写全。
⚠️ Apache-2.0 第 4 条要求随分发提供许可证副本：签正式包时要把 kleidiai 源码包与它的 LICENSES/ 一起归档。

---
---

## ✅ 真机（Xiaomi 24031PN0DC · Android 16 · arm64 · 16 GB）反馈的四个问题：已定位并修复

> 这一轮全部来自**真机实测**，而之前的验证只在 x86_64 模拟器 + debug 包上做过。
> 下面每一条都写清了"模拟器为什么没发现它"。

### 1. 「选择音色时闪退，之后再也打不开」—— 非 VITS 音色被当成 VITS 加载

**真机复现**：选「Matcha 中文（Baker）」→ 进程 2 秒内消失（无 Java 栈、无 tombstone）；
随后**每次启动都死在同一处**。

| 环节 | 事实 |
| --- | --- |
| 触发 | `NeuralVoiceModels.resolve()` 只按"最大的 .onnx"挑模型，不判断架构 |
| 崩溃方式 | Matcha 的声学模型被塞进 `OfflineTtsVitsModelConfig`，sherpa 在 `offline-tts-vits-model.cc:Init:198` 报 `'comment' does not exist in the metadata` 后**直接结束进程**（不是抛异常，`runCatching` 拦不住） |
| 为什么打不开 | `selectNeuralVoice` **先落盘** `neuralVoiceId` 再加载；`restoreNeuralVoice()` 每次启动重放同一个 id |

**修复**（三层，缺一不可）：

1. **按内容判断架构**：读 ONNX 尾部 64 KB 的 `metadata_props`，取 `model_type` / `comment`。
   判据和 sherpa 自己的一致（它读不到 `comment` 就失败），所以"我们拒绝"和"它会崩"永远是同一件事。
   实测该音色报 `matcha-tts`，界面直接说明并只留「删除」。
2. **加载成功才落盘**：失败就不写设置，从结构上断掉重放路径。
3. **崩溃看门狗**：进原生调用前写 `neural_load_marker`，返回后清掉。标记还在 = 上次死在里面，
   启动时把该音色拉黑（`neural_bad_voices`）并清掉选择。**这条对任何未预料的原生退出都有效**。

验证：同一个毒状态（`neuralVoiceId = "matcha-icefall-zh-baker"`）下，修复前 2 秒必死，修复后正常启动并自动清空选择；
手工注入 marker 后启动，日志 `上次加载 ... 时进程被终止，已拉黑`，应用照常运行。

### 2. 「神经音色没声音」—— R8 把 `GeneratedAudio` 的构造函数剪掉了

真机 logcat（r8debug / release 包）：

```
合成失败：no non-static method "Lcom/k2fsa/sherpa/onnx/GeneratedAudio;.<init>([FI)V"
  at com.k2fsa.sherpa.onnx.OfflineTts.generateImpl(Native Method)
```

`libsherpa-onnx-jni.so` 靠**反射**构造 `GeneratedAudio`（`NewObject` + 构造函数签名 `([FI)V`），
而 Kotlin 侧没有任何调用点，R8 就把构造函数剪了 —— 引擎能加载、能报「1 个说话人 @ 22050 Hz」，
但**一句都念不出来**，界面静默退回系统 TTS。

**为什么模拟器没发现**：模拟器一直用 `assembleDebug`（`isMinifyEnabled = false`），R8 根本没跑。

修复：`proguard-rules.pro` 加 `-keep class com.k2fsa.sherpa.onnx.** { *; }`。
这个包整个都是和 `.so` 的 ABI 约定（配置类字段名 + GeneratedAudio 构造函数），不该挑着 keep。

### 3. 「生图后端无法使用 / 检测不到」—— 插件里的 `.so` 被静默丢掉，且从未 `System.load`

两个独立的 bug，各自都足以让后端永远不可用：

| # | bug | 后果 |
| --- | --- | --- |
| 1 | `PluginInstall` 调 `PluginArchive.read(file, assetsDir)` **没传 `libDir`** | zip 里 `lib/liblocalmind-sd.so` 被 `skipped` 丢掉，插件"装成功但什么都没发生" |
| 2 | 全工程**没有任何地方**调用 `NativeLibraryInstaller.ensureLoaded()` | 即使文件落对了位置，不 `System.load` 就永远回答"没有后端" |

修复：安装时传 `libDir = filesDir/native/<abi>/` → 校验 ELF 头（架构不符就丢弃并说明）→ 立即 `System.load`；
应用启动时也补一次（上次装完就退出的用户，下次进来应该直接可用）；生图页按**真实探测**显示状态，
并提供显式「启用」；自检那一项的措辞同步改成区分"没装"和"装了没加载"。

验证（经 `POST /v1/debug/plugin` 走完整安装链路）：

```json
{"ok":true,"result":"已安装 本地生图后端：原生库 liblocalmind-sd.so（35 MB）、生图后端已启用"}
```

```
NativeLibInstaller: 生图后端已加载：/data/user/0/com.localmind/files/native/arm64-v8a/liblocalmind-sd.so（35 MB）
```

设备上确认 `files/native/arm64-v8a/liblocalmind-sd.so` = 36,867,512 字节，`/v1/debug/backend` 返回 `"probe": true`。

### 3.4 真机第二轮：「点了没反应 / 流式没用 / 得退出去再进来」

这一轮的症状看着很多，其实**两个根因**。

#### 根因 A：`StateFlow` 根本没发出（"点完没反应"系列）

```kotlin
conv.companionMode = on                       // 就地改对象
_conversations.value = _conversations.value.toMutableList()   // ← 无效
```

`StateFlow` 只在 `newValue != oldValue` 时才发出，而 `ArrayList.equals` 是**逐元素**比较的 ——
新旧两个列表装的还是**同一个** Conversation 实例，判定为相等，于是**一声不吭**。
这一句代码上方的注释还写着"触发一次 list 重发，让头像/标题等派生显示立刻跟上"，
说明当初就是想要这个效果，只是没生效。它一次性解释了：

| 症状 | 原因 |
| --- | --- |
| 本对话设置改完要退出去再进来 | `updateConversationIdentity` 就地改 + 无效重发 |
| **流式对话"没用"** | 每个 token 只写进对象、界面永不重组 |
| 头像 / 标题 / 单独的模型设置不刷新 | 同上；模型那三处还叠了 `it.also { ... }` 返回同一对象 |
| 列表里显示的是"新对话 / 还没有消息" | 同上 |

修法：`ConversationList`（一个 `equals` 按**引用**算的 ArrayList）+ `emitConversations()`。
不改整条发送链路，因为列表的**内容本来就是可变对象**，拿内容相等判断"变没变"从根上不成立。

顺带：
* 对话列表的头像改用**对话自己的**人设字段，而不是按 `personaId` 去全局人设库查
  （身份是写在对话上的，`personaId` 只在新建时记一次，改身份并不会更新它）；
* 未命名的对话显示**人设名**而不是"新对话"；
* 删掉最后一个对话不再自动补一个（要空态就给它空态）。

#### 根因 B：R8 把传给 JNI 的 Kotlin lambda 换掉了（**release 专有**）

```
java.lang.NoSuchMethodError: no non-static method "Lcom/localmind/native/llama/a;.invoke(...)"
  at com.localmind.native.llama.LlamaBridge.nativeGenerate(Native Method)
```

`mapping.txt` 里一目了然：

```
LlamaBridge$generate$producer$1                    -> 同名（被 keep 了）
LlamaBridge$generate$producer$1$$ExternalSyntheticLambda0 -> com.localmind.native.llama.a  ← R8 新建的
```

R8 会为"传给原生的 lambda"**合成一个新类**，而合成发生在 keep 规则**之后**，规则管不到它，
`invoke` 就没了。原生侧每次回调都抛 `NoSuchMethodError`，而这个异常被静默吞掉 ——
**token 一个都进不来**，整段回答等生成完才一次性出现。debug 包完全不暴露。

修法：换成具名接口 `LlamaBridge.TokenSink` + 具名实现类（都是**输入里本来就存在**的类，
落在 `com.localmind.native.**` 的 keep 规则里），原生侧改查 `onToken(String)V` 并 `CallVoidMethod`；
再补一条 keep 规则做第二道闸。找不到回调时**大声报一次**，不再静默。

#### 另外两处

* **人设字段没进提示词**：名字 / 年龄 / 性别 / 自称在界面上是单独填的，而提示词只拼了
  `persona.systemPrompt` —— 用户填了名字，模型完全不知道，被问还会说"我是 AI 没有年龄"。
  现在单独写成一节【你自己的设定】的事实（和"行为指令"分开，小模型对两者的权重不一样），
  并把用户昵称也带上。
* **流式节流**：原来**每个 token** 都要 `buffer.toString()` + 正则扫全文 + 切主线程 + 重发列表 +
  整表重排。它是 O(已生成长度) 且乘上 token 数 —— 回答越长越慢，最后必然追不上生成速度，
  收尾那次更新还会被积压饿死，于是"得退出去再进来才显示完整"。现在每 ~70ms 才做一次界面工作
  （约 14fps，文字流看起来仍连续），收尾有一次不受节流影响的完整更新。

### 3.45 第三轮：短话连发、自检误报、开源声明

#### 「流式对话」这个名字和用户的理解错位了

那一行原来叫**流式对话**、副标题"逐字显示"，而用户理解的"流式对话"是
**像人发微信那样一条一条地发短消息**。名字和行为错位，用户就会以为功能没做。

现在拆成两项，各做各的事：

| 开关 | 管什么 |
| --- | --- |
| **逐字显示**（原"流式对话"，`streamToUi`） | 字怎么出现：一个一个，还是一次性 |
| **短话连发**（`burstReplies`，默认开） | 分几条发：一条长气泡，还是几条短的依次到达 |

短话连发的实现要点（[AppViewModel](../../app/src/main/java/com/localmind/ui/AppViewModel.kt) 的 `burstCut`）：

* 断在**第一个够长的句末**（默认 ≥10 字），不是最后一个 —— 否则等于没拆；
* 一直没有句读也不能无限长：到 60 字就在最近的逗号/顿号处硬断，"短"是这一项的目的；
* 拆出来的每一段**各自解析一次**表情/图片标记（一条回答可能被切好几段，每段都可能带表情）；
* 收尾只处理最后一条的尾巴，**已发出的几条不动** —— 否则整段回答会被塞回最后一条，前面成了重复；
* 尾巴为空就删掉那个空气泡；
* **同时告诉模型**这件事（"一句话一条，不超过 40 字，不要书面结构"）：只靠程序切分，
  模型写的是一篇连贯文章，切出来像被腰斩。程序与提示词必须一起改。

实测：一次回答被拆成 5 条独立气泡，时间戳 09:51:42 / 09:52:17 / 09:52:20 / 09:52:22 / 09:52:24，
各自带复制与朗读按钮。

#### 自检里的「图像渲染器」是**误报**

它一直显示失败，但渲染器其实是好的（`/v1/images/generations` 一直能出图）。原因：

```kotlin
val size = 32
val bmp = ImageBridge.render("selftest green forest", size, size, 1234, 0)
val ok = bmp.width == size && bmp.height == size   // 32 != 64，永远为 false
```

`ImageBridge.render` 会把边长 `coerceIn(64, 2048)`，传 32 拿到的是 64×64，尺寸断言必然不成立。
改成 64 并断言"确实渲染出了这个尺寸"。**自检的误报比没有自检更糟** —— 它会让人去查一个不存在的问题。

#### 开源声明

补齐了实际打进包/随运行时下载但漏掉的组件：**ONNX Runtime**（MIT，语音引擎那 30 MB 里它占 21–25 MB，
最该点名的一个）、Okio、Kotlin 标准库与 kotlinx。
按要求去掉了「模型权重许可」整块：应用**不分发任何权重**，每个模型的许可在模型页的条目上逐个标注 ——
那才是用户真正要做决定的地方。

#### 1.0.1 分发包

`release/LocalMind-1.0.1-arm64-v8a/` 已重新生成：APK + 7 个插件 + 更新过版本号/体积的安装说明。
插件取自 `dist/plugins/`（比 1.0.0 那份新：官方模型库 v2.1.0 → **v3.0.0**，52 → 83 个模型）。
写这个 md 时踩了个小坑：PowerShell 5.1 的 `Get-Content -Raw` 默认按 **ANSI** 读，
改完中文全成乱码 —— 必须显式 `[System.IO.File]::ReadAllText(path, UTF8)`，
并用 `UTF8Encoding($false)`（不带 BOM）写回。

### 3.5 测试模式（隐藏页）：内测反馈从"它闪退了"变成"一个文件"

**入口**：关于页 → 版本号**连点三下**（900ms 时间窗，慢慢点三下不会进去）。

页面内容与它存在的理由：

| 区块 | 内容 | 为什么 |
| --- | --- | --- |
| 机型与系统 | 机型 / 代号 / 系统 / 系统指纹 / ABI / CPU 核数 / 内存 / 存储 / 版本 / 构建类型 | 内测反馈**最常缺**的就是这一段 |
| 运行状态 | 模型 / 上下文 / 线程 / tok-s / 生图后端 / API / 语音引擎 / llama.cpp | 不用退出去重进就能读到实时值 |
| 自检 | 复用 `SelfTest` | 换机器先跑一遍 |
| 崩溃记录 | 未捕获异常的完整堆栈 + **机型 + 崩溃前 40 步操作** | 见下 |
| 最近操作 | 全应用操作流水（环形，最多 400 条） | "它卡住了但没崩"这类问题靠它定位 |
| 导出 | 分享 `.txt` 文件 / 复制全文 | 内测的人只要把文件发回来 |

**崩溃捕获**在 `LocalMindApp.onCreate` 最早装上（`Thread.setDefaultUncaughtExceptionHandler`），
记完之后**交回给上一个处理器** —— 系统的崩溃弹窗、进程重启这些行为保持不变，我们只是路过抄一份。

另外还有一层"上次进程有没有正常结束"的判断（磁盘标记 + `Activity` 正常销毁时撤掉）：
Java 异常处理器抓不到的那一类 —— 原生 abort/exit、被系统杀掉、被手动划掉 —— 靠它留痕。
措辞上如实写成"原生崩溃 / 被系统杀掉 / 被手动划掉"三种可能，不硬说成崩溃。
实测：重装 APK 导致的一次 `force-stop` 被完整记下，带上机型和最后一步操作。

**操作流水的收口点选在 `AppViewModel.toast()`** —— 全应用几乎所有"用户做了一件事"都会走到它
（下载、切换、导入、保存、失败提示）。一个点覆盖绝大部分轨迹，比在几十处各插一行可靠：
那种迟早会漏掉新加的调用点。

### 4. 「对话速度过慢」「拟人人设无法使用、加载过慢」

三个叠加的原因，都是"每轮白花的开销"：

| # | 原因 | 影响 |
| --- | --- | --- |
| 1 | **流式是假的**：`LlamaBridge.generate` 先同步调完 `nativeGenerate`（它会跑完整段回答），再从 channel 里取 | 界面长时间空白、最后一次性刷出；首 token 延迟 = 整段回答时长。现在生产者放另一个线程，消费方并发取 |
| 2 | **每次请求都 `llama_memory_clear()`** | 每轮把整段 system prompt + 历史重算一遍（prefill 是手机上最贵的一步）。现在按 token 逐位比较最长公共前缀，只 decode 新增部分；生成的 token 也记账，下一轮连回复部分一起命中 |
| 3 | **内部判断全跑在对话模型上**：召回挑选 1 次 + 记忆提炼 1 次 + 内心活动 1 次 | 没绑决策模型的用户（默认）每轮多 3 次生成，全和用户那句话抢同一个互斥引擎。现在 `DecisionEngine` 只走专用路由器，没绑就用规则兜底 |
| 4 | **多重思考每轮再跑 3 次**：`Deliberation.understand()` 是"理解 -> 自我质疑 -> 决定怎么做"三轮递进，每轮重发整段人设与上下文；拿不准时还有 `clarify()` | 加上上面三条，**一条用户消息最多能触发 8 次生成，其中 6 次用户看不见**。这是"过慢"的主因。改成默认关闭的「我的 -> 对话行为 -> 多重思考」开关 |

#### 实测数字（真机 arm64 · Qwen2.5 0.5B Q4_K_M · ctx 4096 · 4 线程）

走**局域网**（`http://192.168.1.14:8080`，即「允许局域网访问」打开后的地址），
系统提示词 1800 字，`max_tokens=8`：

| 请求 | 修复前（每次清空 KV） | 修复后 |
| --- | --- | --- |
| 首次（全量 prefill） | ~17.8 s | 17,750 ms |
| 同一请求再来一次 | ~17.8 s（整段重算） | **316 ms** |
| 只改最后一句 | ~17.8 s（整段重算） | **467 ms** |

流式（`stream:true`，一次 128 个 SSE chunk 的回答）：

```
data chunks      : 128
first chunk at   : 241 ms      <- 修复前要等整段生成完（约 2.2 s）才出现第一个 chunk
chunk arrival ms : 243, 247, 386, 386, 578, 982, 1,309, 1,558, 1,883, 2,060
```

> **调试提示**：这些测量都走局域网地址，不是 `adb forward`。
> `adb forward tcp:18080 tcp:8080` 只把手机的 127.0.0.1 映射到开发机，
> 手机上的服务仍然只监听回环；要让同网段的其它设备（或开发机直连）访问，
> 必须在「我的 -> 本机 API 服务」里把**允许局域网访问**打开。

#### 顺带发现（未改，留作记录）

内置模型清单里 `unsloth/Qwen2.5-1.5B-Instruct-GGUF` 的地址在 hf-mirror 上返回 **404**
（`unsloth` 侧文件已变更或镜像未同步）。可用的等价地址在**官方** Qwen 仓库：
`https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf`（1,117,320,736 字节，实测 200）。
模型清单是可更新的模型包，改 `plugins-src/` 里的地址即可，不必发版。

另外 `CompanionMind.stateOf()/allStates()/save()` 原来每次都要把 `companion.json` 整个读出来反序列化
（包括发送路径上的主线程那一次），聊得越久文件越大、每句话的固定开销越高。现在读全走内存，只有写才碰磁盘。

### 附带修掉的一个进程级崩溃

真机 crash buffer 里反复出现（10-15 20:14 → 21:05，五次）：

```
FATAL EXCEPTION: DefaultDispatcher-worker-7
java.net.SocketException: Software caused connection abort
  at com.localmind.service.MultiPartDownload$pullSegment$2.invokeSuspend
```

`MultiPartDownload` 的段协程跑在 `SupervisorJob` 作用域且没有 `CoroutineExceptionHandler`，
所以**任何网络抖动都会变成 FATAL EXCEPTION 把整个应用带走** —— 用户下 Qwen3.5-4B 时
在 237 MB / 2.7 GB 处被反复打死。现已把段失败、进度回调、音色安装都包住（失败只让该段重来，
已下载的字节留着，断点续传照常）。

## ✅ 本轮完成：APK 从 73.7 MB 降到 12.15 MB

这一轮最大的一件事是**把包体积砍掉 84%**。三处原因，都是"把能在云端拉的东西
塞进了包里"：

| 原因 | 代价 | 处置 | 效果 |
| --- | --- | --- | --- |
| `material-icons-extended` | 34 MB 的 AAR、上千个图标，实际只用 ~70 个 | 抽出 `ui/icons/MinimalIcons.kt`（50 个图标、字节级从 AAR 提取），改写 103 处调用点，删依赖 | dex 56.5 → 2.96 MB |
| R8 被关掉（`isMinifyEnabled = false`） | 所有不可达代码和未用资源都进包 | 打开 R8 + `shrinkResources`（`proguard-rules.pro` 里本来就写好了 keep 规则，只是没启用） | 包含上一条的效果 |
| sherpa-onnx / onnxruntime 原生库 | **36 MB/ABI**，其中 onnxruntime 一家 21–25 MB | 移出 APK，改为**首次使用神经音色时下载**（`SherpaLibraryInstaller`） | lib 62 → 10.5 MB |

### 最终体积

| 变体 | 之前 | 现在 |
| --- | --- | --- |
| `assembleRelease`（arm64-v8a + armeabi-v7a） | 73.7 MB | **12.15 MB** |
| `assembleR8debug -PabiList=x86_64`（可 run-as 的 R8 变体） | — | 13.78 MB |
| `assembleDebug`（不混淆，仅开发用） | 79.7 MB | 79.71 MB（未变，见下） |

> ⚠️ **debug 变体仍然是 79.7 MB**，因为它 `isMinifyEnabled = false`。
> 出包请用 `assembleRelease`。另外 AGP 有个硬限制：**debuggable 的变体不允许
> 真正混淆**（会打警告 "Debuggable builds are no longer minified"），
> 所以 `r8debug` 只能做到"资源收缩 + 部分裁剪"，拿不到 release 那个体积。
> 它存在的意义是让 `run-as` 可用，好把 GGUF 推进 `files/models/` 做实测。

### 运行时下载原生库：实测通过（这是本轮最关键的验证）

`sherpa-onnx` 的四个 `.so` 现在不在包里。实测链路：

```
10:28:02  SherpaLibs: 下载原生库（直连）: .../sherpa-onnx-v1.13.8-android.tar.bz2
10:29:22  SherpaLibs: 原生库就位：x86_64，34 MB
10:29:22  SherpaOnnx: 从目录加载原生库成功：/data/user/0/com.localmind/files/native/x86_64
```

- 下载 46 MB 官方 tarball → 只解出当前 ABI 的 4 个 `.so` → 删包
- 进程 maps 里 **9 处 sherpa 映射**，`OfflineTts` 构造成功
- 重启应用后**自动从磁盘加载**（`NeuralTtsEngine.init` 里对齐"文件在"与"已加载"）
- 界面随之解锁：引擎行变 `已就绪 / 已安装 34.47 MB / 删除`，
  8 个神经音色全部从 `不可用` 变为 `下载`

**踩到的坑**：`System.load` **不会递归解析 `DT_NEEDED`**。必须先
`System.load(libonnxruntime.so)`，再 load 那三个依赖它的 —— 顺序写在
`SherpaNativeLoader.LOAD_ORDER` 里，注释说明了不能改。

### UI：iOS 26 动效体系 + 去掉冗余文案 + 少一层

**新增 `ui/motion/Motion.kt`** —— 之前整个应用只有 19 处动画引用，基本等于没有动效。
现在把曲线和时长集中定义，保证所有过渡是同一套手感：

| 名字 | 用途 | 手感 |
| --- | --- | --- |
| `smooth` | 跟手元素（滑块、开关、透明度） | 无回弹，快速收敛 |
| `snappy` | 进入元素（列表项、气泡、卡片） | 极小回弹 |
| `bouncy` | 强调（选中态、按钮弹回） | 明显回弹 |
| `gentle` | 大块布局（整页推入、Sheet 升起） | 慢而软 |

配套 Modifier：`pressScale`（按下缩到 0.96）、`appearIn`（上浮+淡入+轻微放大，
带 28ms 错峰）。已接入：

- **顶层页面切换**：同级标签用淡入+上浮，子页（语音/生图）用**从右推入** ——
  层级不同用不同动效，用户从动作本身就能判断"我在往里走还是平级切换"
- **聊天**：会话列表项错峰入场 + 按下缩放；消息气泡入场动画
  （只给最后 3 条，否则往上翻历史时每屏都在重播）
- **标签栏**：选中图标放大 1.12 + 上移，指示器换成统一的 `bouncy` 曲线

**对话详情改成 iOS push**：`AnimatedVisibility` 从右推入/向右退出，
返回按钮从"关闭"改成 **`‹ 对话`** —— 措辞本身就在告诉用户这是回到上一层，
而不是一个要关掉的弹窗。这正是"少一层"的关键：让他知道自己一直在同一条路径上。

**删掉的解释性文案**（用户明确要求）：

| 位置 | 删掉的内容 |
| --- | --- |
| 语音引擎行 | `onnxruntime + sherpa-onnx` 库名、abi、以及"不打进包是为了…"整段设计说明 |
| 下载代理 | "模板里的 `{url}` 会被替换成真实地址…"（模板语法说明） |
| 性能与内存 | "mmap 流式加载是手机上运行超大模型的关键…" |
| 系统提示词 | "语音情感会自动附加在后面" |
| 诊断 | "自检会验证原生库、内存规划、图像渲染器与 API 状态" |
| 默认人设 | "对话里那个 AI 是谁，在对话自己的详情里设置" |

原则：**用户要判断的只有"值不值得下、开了有没有用"。库名和设计理由不是他要读的东西。**

### 对话设置：修掉"关掉才显示"的选项

这是用户明确指出的问题，确实是设计缺陷 —— 选项在开关关闭时**整块消失**，
用户看到的是"这一项没有滑杆"，而不是"要先打开右边的开关"：

| 位置 | 原来 | 现在 |
| --- | --- | --- |
| `OverrideSlider`（温度/top-p/…） | 滑杆只在覆盖开关打开时渲染 | 滑杆**始终渲染**，未覆盖时置灰不可拖 + 一行"打开右边的开关才能单独调这一项" |
| 拟人强度 | 整块藏在 `if (companionMode)` 里 | 始终显示，未开「当人模式」时置灰并说明 |
| 「它可以做什么」能力行 | 只转开关，不说缺什么 | 始终显示；缺前置条件时置灰 + `缺条件` 徽标 + 说明缺什么（如"去「我的 → 语音与音色」下载"） |

`IosSlider` 因此新增 `enabled` 参数（置灰但仍绘制）。

---

## ✅ 本轮完成（代码已写 + **模拟器实测通过**）

### 0. 打通构建与运行（本轮第一件事）

之前的状态是"Kotlin 编译通过，但从没跑过 `assembleDebug`"。真跑之后卡在三处，
都是**必须修才能出 APK** 的硬错误，不是环境问题：

| # | 现象 | 原因 | 修复 |
| --- | --- | --- | --- |
| 1 | `add_library cannot create target` / `llama.cpp sources not found` | `third_party/llama.cpp` 不存在（未提交进 git） | 按 BUILD.md 取回 v0.6.0 |
| 2 | Gradle 卡在 `Downloading gradle-8.11.1-all.zip` | `services.gradle.org` 在开发机上超时 | `distributionUrl` 换腾讯云镜像（见 BUILD.md §0） |
| 3 | **`Task ':app:mergeDebugJniLibFolders' uses this output of task ':app:compileStableDiffusion' without declaring an explicit or implicit dependency`** | `compileStableDiffusion` / `fetchStableDiffusion` 往 `src/main/jniLibs/` 写产物，但打包 jniLibs 的任务没声明这层依赖 | 在 `app/build.gradle.kts` 里给 `merge*JniLibFolders` 加 `dependsOn` |

**第 3 条是真正的拦路虎**：Gradle 8 把它当**硬错误**（不是警告），所以任何一次
`assembleDebug` 都到不了打包那一步 —— 这也解释了为什么之前"只能过 Kotlin 编译"。

修完之后的实测结果：

| 验证项 | 结果 |
| --- | --- |
| `:app:assembleDebug -PabiList=x86_64` | ✅ BUILD SUCCESSFUL |
| `:app:assembleDebug`（arm64-v8a + armeabi-v7a） | ✅ BUILD SUCCESSFUL |
| 原生库加载 | ✅ `llama backend initialised (CPU : SSE3 = 1 \| SSSE3 = 1 \| REPACK = 1)` |
| 真实模型加载（Qwen2.5 1.5B，ctx 4096） | ✅ `model ready` |
| 中文对话 | ✅ `你好，请用一句话说明你现在运行在哪里。` → `我在阿里云的服务器上。`（54 tokens） |
| `/health` | ✅ `{"status":"ok","model_loaded":true,"context":4096}` |
| `/v1/models` | ✅ 32 个条目 |
| `/v1/embeddings` | ✅ 384 维，非零维 14 |
| `/v1/images/generations` + 取回 PNG | ✅ 512×512，271 KB，中文文件名 |
| `/v1/audio/speech` | ✅ `audio/wav`，`RIFF` 头 |
| `/v1/system` | ✅ 真实 tok/s 2.23、内存与线程规划 |

### 1. TTS 神经音色（原「未验证」里的 blocker，已解除）

BLOCKERS.md 原来的结论是"本地 AAR 损坏 + Maven 源全部不可用，做不了"。
本轮找到了**官方 Release 有 Android 包**这条路，并且实测通了：

- 坏掉的 `app/libs/sherpa-onnx-1.13.8.aar` 已删除（它是个下到一半的文件：
  头是 `PK\x03\x04`，末尾没有 EOCD 签名，`jar xf` 直接报
  `zip END header not found`。**加依赖声明是永远不可能成功的**）
- 新增 `:app:fetchSherpaOnnx`：从官方 Release 拉 `sherpa-onnx-v1.13.8-android.tar.bz2`
  （46 MB），只解出 4 个 `.so` 到 `jniLibs/<abi>/`。直连 GitHub 会随机超时，
  所以先直连、失败自动走 `gh-proxy.com`
- vendored `com.k2fsa.sherpa.onnx` 的 **TTS 部分**（`Tts.kt` + `SherpaNativeLoader.kt`），
  没有搬运 5 万行的识别器
- 新增 `service/NeuralTts.kt`：模型解析 + VITS 推理 + `AudioTrack` 播放 + WAV 导出
- 新增 `service/VoiceModelDownloader.kt`：断点续传 + 调系统 `tar -xjf --strip-components=1`
  解压 + **以"能否解析出 VITS 布局"作为验收标准**
- `VoiceCatalog` 重写：每个 id 都是官方 Release 里 tarball 的真实名字，
  附实测体积；`VoiceSettings` 新增 `neuralVoiceId`（与系统音色的 `voiceName` 分开存）
- `TtsEngine.speak()` / `synthesizeToFile()` 改为**神经优先、失败静默退回系统 TTS**
- `VoiceScreen` 新增「神经音色」区：下载进度、试听、使用中、删除

**实测**：小雅（vits-piper-zh_CN-xiao_ya-medium-int8）加载成功 →
`1 个说话人 @ 22050 Hz`；`/v1/audio/speech` 拿到的 WAV
**4.49 s / 198 KB / RMS 2097 / peak 14296 / 98% 非零采样** —— 是真的语音，
不是原来那个 `ProceduralVoice` 的形状音。

#### 整条下载→解压→合成链路也在应用内跑通了

不是只验证了小雅那个手工塞进去的模型：在语音页点了两个音色下载
（布洛妮娅、泛尘），**完整走完了应用内的下载器**：

| 环节 | 实测 |
| --- | --- |
| 直连 GitHub | ❌ `Connection reset` / `Failed to connect to github.com` |
| 自动换 gh-proxy | ✅ 接上并下完 |
| 断点续传字节数 | ✅ 与 `Content-Length` 完全一致（115.01 MiB / 113.80 MiB） |
| 系统 `tar` 解压 + `--strip-components=1` | ✅ 包删掉、目录出现 |
| VITS 布局校验 | ✅ `安装完成：bronya.onnx (132 MB)`、`fanchen-C.onnx (131 MB)` |
| 加载新音色 | ✅ `音色就绪：vits-zh-hf-bronya  804 个说话人 @ 22050 Hz` |
| 合成 | ✅ 2.26 s 音频，`rms=3670 peak=18437`，合成耗时 1.9 s（**快于实时**） |

这一步顺带证明了 `resolve()` **扫目录找 `.onnx`** 这个设计是必要的：
三个音色主模型分别叫 `zh_CN-xiao_ya-medium.onnx` / `bronya.onnx` /
`vits-zh-hf-fanchen-C.onnx`，写死路径必错。

#### ⚠️ 踩到的坑（重要，别再犯）

第一版只留了 VITS 需要的字段，把 Matcha/Kokoro 那些 data class 删了。结果
**一启动就 SIGABRT**：

```
JNI DETECTED ERROR: NoSuchFieldError: no type
"Lcom/k2fsa/sherpa/onnx/OfflineTtsMatchaModelConfig;" found and so no field
"matcha" could be found in class "com/k2fsa/sherpa/onnx/OfflineTtsModelConfig;"
```

原因：`libsherpa-onnx-jni.so` 是**按字段名反射**读配置对象的，它会无条件读遍
每一个字段，而不是只读你这次用的那种模型。所以 vendored 的类形状必须和上游
一一对应，**字段名、类型、顺序一个都不能动**；少一个就是 `NoSuchFieldError`，
而它是 `Error`，ART 当致命 JNI 错误直接终止进程，`runCatching` 拦不住。
（细节写在 `Tts.kt` 顶部。）

### 2. 打通三处「死代码」页面

`VoiceScreen` / `ApiScreen` / `ImageScreen` 都有完整实现，但**没有任何入口**。
本轮把前两个接上（`ImageScreen` 原本已由「模型 → 生图工作台」进入）：

- `AppViewModel` 新增 `showVoiceScreen` 状态位 + `openVoiceScreen()`
  ——做成状态位而不是 VoiceScreen 的局部变量，因为它有**两个来源**
  （聊天页麦克风按钮、「我的」页导航行）
- `LocalMindRoot`：`showVoiceScreen` 优先于当前标签页渲染，返回键先关它
- `ProfileScreen`：「本机 API 服务」→ 真正的 `ApiScreen`；
  「语音与音色」→ 真正的 `VoiceScreen`（原来两行都只是打开设置栏，
  而**设置栏里根本没有 API 和语音这两块** —— 点了等于没反应）
- `ChatScreen`：麦克风按钮 `onOpenVoice` 改为打开 `VoiceScreen`
  （原来也指向设置栏，同样什么都没发生）

### 3. 换掉已失效的下载代理预设

`ProxySettings.PRESETS` 原来 5 个里有 3 个**连不上**。本轮用 GitHub Release 资产
（44 MB 的 sherpa 包）实测：

| 预设 | 实测 | 处置 |
| --- | --- | --- |
| `gh-proxy.com` | ✅ 200，~750 KB/s | 保留 |
| `hk.gh-proxy.com` | ✅ 200，~450 KB/s | 保留 |
| `down.npee.cn` | ✅ 200，~150 KB/s | **新增** |
| `ghproxy.net` | ❌ 连接超时 | **删除** |
| `ghfast.top` | ❌ 连接超时 | **删除** |
| `gh.llkk.cc` | ❌ 连接超时 | **删除** |

另外从 16 个候选里复测：`ghproxy.cc` TLS 证书不受信；`gh-proxy.net` / `gitproxy.click`
只有 7–9 KB/s；`slink.ltd` 1 KB/s —— 都不值得放进预设。

**一个不能连接的预设比没有预设更糟**：用户会去试、会等，然后得自己琢磨出
"不是应用坏了、是镜像死了"。

### 4. 下载器的 `Content-Range` 校验

路线图里列的这条**其实上轮已经实现了**（`ModelDownloader.downloadFrom` 里
有 `declaredStart != existingRaw` 的检查）。本轮确认它在位，并把它复制到了新的
`VoiceModelDownloader`。

---

## ⚠️ 仍未验证 / 未实现

| 项 | 状况 |
| --- | --- |
| **生图链路跑通** | ❌ 未做。`stable-diffusion.cpp` 源码没取回，`image_jni.cpp` 的 `nativeSd*` 仍是桩函数，当前走程序化渲染器（实测 `/v1/images/generations` 可用）。CMake 目标冲突的解法见 [BLOCKERS.md](BLOCKERS.md) |
| **识图（多模态）** | ❌ **未实现**。`visionModelId` 槽位只在 UI 里存取，**没有任何消费端**：`llama_jni.cpp` 里没有 mmproj / clip / mtmd 相关代码，`LlamaBridge` 没有对应的 external fun。要做得从 C++ 一路加起 |
| **腾讯 AUK / 云端 TTS** | ❌ 未接。用户提出的需求。実測结论：**AuK 是 1.5B 的 GPU 服务模型**（[Tencent-Hunyuan/AuK](https://github.com/Tencent-Hunyuan/AuK)），端侧跑不动，只能走云端 API。可行的做法是接腾讯云语音合成，或把 AuK 当 OpenAI 兼容的远程 TTS 端点 |
| **MCP 客户端** | ❌ 只有 `MCP_SERVER` 这个插件能力声明，没有协议实现 |
| **其余插件能力的消费端** | `MODEL_SOURCE` 已接。`REMOTE_PROVIDER` / `TTS_VOICE` / `IMAGE_BACKEND` / `WEB_TOOL` / `PERSONA_PACK` 还只有声明与界面展示 |
| 其余神经音色（特蕾莎/优菈/刻晴/禅雅塔/aishell3） | ⚠️ **只实测了小雅、布洛妮娅、泛尘三个**。其余确认了 Release 资产存在且 `Content-Length` 正确（≈115 MB 各 / 30 MB），但没实际下载解包。因为 `NeuralVoiceModels.resolve()` 是**扫目录找 .onnx** 而不是写死路径，布局不同也不会失效 —— 这一点已被上面三个名字各异的音色证实 |
| 多说话人音色的说话人选择 | ⚠️ 布洛妮娅有 804 个说话人，但 `sid` 固定传 0，UI 还没做选择器 |
| 神经音色的听感 | ⚠️ 模拟器没有真实音频输出设备，只能验证"合成出了正确长度与幅度的 PCM"（RMS/峰值/非零比例都正常，且合成快于实时）。**实际好不好听必须真机确认** |
| 动画的实际观感 | ⚠️ 动效代码已接入并通过编译 + 启动，但**逐帧观感没法在截图里验证**，需要真机/录屏看 |
| emulator 上的推理速度 | ⚠️ Qwen2.5 1.5B 在 x86_64 模拟器上只有 **2.2 tok/s**（通用 CPU 路径，无 dotprod/i8mm）。真机 arm64 才有优化，别用模拟器速度评估体验 |

---

## 尚未实现（路线图顺序）

1. **生图** —— 按 BLOCKERS.md 的路线 A（给 sd.cpp 的 ggml 目标加前缀）或路线 C（独立 CMake 出预编译 `.so`）
2. **识图** —— 需要 `libmtmd`（llama.cpp 自带）+ mmproj 文件管理 + 图片预处理，是**跨 C++/Kotlin 的一整条链路**
3. **MCP 客户端**

---

## 构建性能

**日常改 Kotlin 只跑 `:app:compileDebugKotlin`（热守护进程约 1 分钟），不要 `assembleDebug`。**

带原生编译的 `assembleDebug` 首次约 4-5 分钟（`llama.cpp` 785 个文件），
增量构建（CMake 无变化）约 6-30 秒。

| 注意 | 说明 |
| --- | --- |
| `:app:fetchSherpaOnnx` 会下 46 MB | 产物进 `.gitignore`。不想负担这 36 MB/ABI 就别跑它，神经语音自动不可用、退回系统 TTS |
| sd.cpp 预编译库 | `.github/workflows/prebuilt-sd.yml` 手动触发；本机没配 `localmind.sdPrebuiltRepo` 时静默跳过 |
| `build/sd-alone/` 在 `build/` 下 | `clean` 会误伤 |

---

## 已知隐患（会让"下载完成后仍失败"）

1. **代理反效果**：在「我的」里开代理且勾了「优先走代理」时，`preferProxy` 会把
   **所有** URL 套上代理前缀。而 GitHub 代理对 `hf-mirror` 的地址返回 **404**
   （已实测），于是每个下载源都先经历一次无谓失败。
2. **大小校验过严**：`ModelDownloader.verify()` 要求文件长度**精确等于**目录里的
   `sizeBytes`。任何一处元数据不准，都会在下载**完成之后**被判失败 ——
   现象正是"下完了却说失败"。
3. **CDN 忽略 `Range` 时要重下**：`hf-mirror` 转发到的 CDN 有时对续传请求返回
   200 + 整个文件。代码会删掉半成品重来（正确行为），但长下载遇到断流就要重头。

---

## 一次操作事故（历史记录）

用 PowerShell 批量删行改写 `ProfileScreen.kt` 时**编码处理错误**：
原 UTF-8 字节被按 cp1252 解码后重写，中文全部变成替换字符，且不可逆。

该文件已重建，逻辑与结构完整保留，但**其中文文案是按记忆复原的，不是原文**。
教训：**不要用 shell 改写源码文件**。

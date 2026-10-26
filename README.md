# LocalMind

> 当前版本 **1.0.4（测试通道）**。这一轮把插件开放到「可执行代码」：`code_plugin`（classes.dex，
> 含 View 整页）、`web_screen`（HTML/CSS/JS 整页）、`theme.css`（用 CSS 变量写主题与底栏样式），
> 底栏换成 AGSL 折射 + CPU 降级的液态玻璃。版本号按测试通道保持 1.0.4。

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
| **神经音色** | **sherpa-onnx VITS 离线语音合成**（小雅 / aishell3 / 原神角色等 8 种），模型下载后完全离线、跟机型无关 | 下载时需要 |
| **Breeze 语音** | **外部语音引擎插件**（`tts_engine`）：端点、密钥、模型名由插件声明，手机只当客户端。Breeze TTS 2 是 3.47B / 需要 CUDA 的模型，**手机上跑不动**，必须在 PC 或 GPU 服务器上自建服务 | 合成时需要（局域网/云端） |
| **分步流水线** | `pipeline` 插件：把长文拆成片，逐片跑（本机模型 / 外部语音引擎），最后聚合。手机装不下的模型靠这个绕开内存与上下文限制 | 看具体步骤 |
| **界面布局插件** | `ui_layout`：底栏入口、首页块、应用标题都能声明；`screens` 自己写整页、`overrides` **整页替换**内置页面；动作目标支持 `prompt:` / `send:` / `copy:` | — |
| **聊天动作插件** | `chat_action`：`actions.json` 声明一键提示词，显示在输入框上方，支持 `{{input}}` 引用当前输入 | — |
| **代码插件** | `code_plugin`：classes.dex 经 DexClassLoader 加载，可注册动作、可用 Java View 替换整页；安装前确认，异常自动停用 | — |
| **网页插件** | `web_screen`：HTML/CSS/JS 整页由 WebView 渲染，JS 桥只有 send / prompt / toast / copy | — |
| **CSS 主题** | `theme.css`：用 CSS 变量写颜色、圆角、底栏形状/指示器，支持 `dark-` 深色令牌 | — |
| 开放接口 | 应用内 HTTP 服务器，OpenAI 兼容，可开局域网 | 局域网内 |
| 内存优化 | 内存规划器 + mmap 磁盘流式加载（AirLLM 思路的移动端实现） | — |
| **对话走 API** | 每个对话可以单独选「本机模型」或任意 **OpenAI 格式接口**（OpenAI / DeepSeek / Kimi / 硅基流动 / 局域网 Ollama…），也可在对话设置里现场填地址与 Key；记忆/情绪照常工作 | 走 API 时需要 |
| **提示词前缀复用** | 稳定段进 system prompt、易变段挂在最新消息上，KV cache 能真正命中；日志与界面直接显示命中率 | 完全离线 |

---

## 悬浮底栏（1.0.4 测试）

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
4. 要语音：**我的 → 语音与音色** → 开启"启用语音模式" → 选情感 → 长按麦克风说话。
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


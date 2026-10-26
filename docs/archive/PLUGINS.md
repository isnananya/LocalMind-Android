# LocalMind 插件总览 · 2026-10-15

> **一句话**：主程序**不带任何模型、音色、素材**。下面这 6 个 zip 是可选的
> 能力包，装哪个用哪个。全部是**纯数据**（JSON + 资源文件），不执行任何代码。
>
> 源码在 `plugins-src/`，打好的包在 `dist/plugins/`。
> 格式规范见 [PLUGIN.md](PLUGIN.md)，界面主题另有 [UI-PLUGIN.md](UI-PLUGIN.md)。

---

## 一、已交付的 6 个插件

| zip | 大小 | 能力 (`abilities`) | 内容 | 装完在哪用 |
| --- | --- | --- | --- | --- |
| `官方模型库插件.zip` | 11.3 KB | `model_library` + `tts_voice` | **52 个模型**（对话 19 / 识图 11 / 生图 13 / 决策 9）+ **17 个离线音色** | 「模型」页下载（多一个「语音」分类） |
| `本地生图后端插件-通用版.zip` | 30.7 MB | `native_backend` | **arm64-v8a + x86_64 两套** `liblocalmind-sd.so`（stable-diffusion.cpp，strip 后 36.9 / 40.1 MB）；安装时按本机 ABI 自动选一份 | 「模型 → 生图」，配 SD 模型后真出图；通用版支持步数、线程、flash attention、采样器/调度器与逐帧进度 |
| `语音音色插件.zip` | 3.0 KB | `tts_voice` | **8 个**离线神经音色（sherpa-onnx） | 「我的 → 语音与音色」下载并使用 |
| `中文表情包插件.zip` | 3.7 KB | `emoji_pack` | 31 个 emoji 表情 / 16 标签 / 40 关键词 | 对话里 AI 自动发表情 |
| `图片表情包插件.zip` | 7.0 KB | `emoji_pack` | 6 张真 PNG 梗图（`assets/`） | 同上，图片优先 |
| `暖橙界面主题插件.zip` | 2.9 KB | `ui_theme` | 主色 / 圆角 / 标签栏等令牌 | 「我的 → 主题」 |

> **还有一份不在 `dist/plugins` 里的**：`dist/native/本地生图后端-x86_64.zip`（15.1 MB）——
> 和上表第二行是同一个后端，只是放在 `native/` 目录下按 ABI 命名，给
> "只下原生库、不装插件"的老路径用。

### 每个插件的 id（`plugin.json` 里的 `id`）

| id | 名字 |
| --- | --- |
| `localmind.official.models` | LocalMind 官方模型库 |
| `dev.localmind.backend.sd` | 本地生图后端 |
| `dev.localmind.voices.sherpa` | 神经语音音色 |
| `dev.localmind.stickers.zh` | 中文表情包 |
| `dev.localmind.stickers.demo` | 图片表情包（示例） |
| `dev.localmind.theme.warm` | 暖橙主题 |

装插件时主程序会**把 `packId` 改写成 `plugin.json` 的 id**，所以上面这个 id
才是落库的键 —— 主题/音色/模型库里写什么都不影响。

---

## 二、模型插件（`官方模型库插件.zip`）详解

**52 个模型 + 17 个离线音色**。每个模型的下载地址都在 2026-10-15 用
`curl -sSI -L` **逐个验证过**（`302 → 200`），`sizeBytes` 是响应头的精确
`content-length`，不是估的。

`url` 指向 `hf-mirror.com`（国内直连），`mirrors` 里带官方 huggingface，下载失败按顺序回退。

### 按用途分类

| 用途 | 数量 | 在模型页的分类 |
| --- | --- | --- |
| **对话（文字）** | 19 | 对话（**多模态模型也在里面**，见下） |
| **识图（多模态）** | 11 | 看图 |
| **生图（扩散）** | 13 | 生图（需先装生图后端） |
| **决策（判定 / 路由 / 守卫）** | 9 | 决策模型 |
| **语音（离线 TTS 音色）** | 17 | 语音（走音色下载器） |

### ✅ 多模态模型是一个文件两用

Qwen3.5 / Gemma 4 / InternVL / SmolVLM 这些**既是识图模型也是对话模型** ——
同一个 GGUF 加不加 mmproj 都能聊天。所以它们**同时出现在「对话」和「看图」两个槽位**里，
下**一个**就够了，不需要为了两个用途下两份。

判据在 `modelOptions()`：对话槽位只排除「决策 / 生图 / 向量 / 语音」，
**不排除视觉模型**；识图槽位只列 `vision = true` 的。

### ⚠️ 另外两个必须知道的事实

1. **识图今天还看不见图** —— `canSeeImages` 写死 false、`LocalModel` 没有 mmproj 字段。
   每个识图条目的描述里都写了配套 mmproj 的文件名和字节数，但装了也看不见图。
2. **生图只收单文件 all-in-one GGUF**，且默认 4 步 / EULER_A —— 原版 SD1.5 / SDXL 在 4 步下会糊，
   少数步蒸馏（SDXS / LCM / Lightning / Turbo）才是正解。

### 对话（19）

| 模型 | 系列 | 量化 | 体积 | 内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- |
| MiniCPM4 0.5B | MiniCPM4 | Q8_0 | 441 MB | 3 GB | Apache-2.0 | MiniCPM 中文 第三方转换 |
| Qwen3.5 0.8B | Qwen3.5 | Q4_K_M | 508 MB | 3 GB | Apache-2.0 | Qwen 中文 极速 |
| LFM2.5 1.2B Instruct | LFM | Q4_K_M | 697 MB | 3 GB | LFM Open License | Liquid 英文 |
| Nemotron 推理 1.5B | Nemotron | Q4_K_M | 1066 MB | 4 GB | CC-BY-NC-4.0 | NVIDIA 英文 思维链 ⚠️非商用 |
| 混元 1.8B Instruct | Hunyuan | Q4_K_M | 1081 MB | 4 GB | Tencent Hunyuan 社区许可 | 混元 中文 |
| Qwen3.5 2B | Qwen3.5 | Q4_K_M | 1222 MB | 4 GB | Apache-2.0 | Qwen 中文 |
| MiniCPM5 2B | MiniCPM5 | Q4_K_M | 1489 MB | 4 GB | Apache-2.0见 §6 | MiniCPM 中文 |
| SmolLM3 3B | SmolLM3 | Q4_K_M | 1827 MB | 6 GB | Apache-2.0 | Smol 英文 思维链 |
| Qwen3.5 4B（省内存） | Qwen3.5 | IQ4_XS | 2362 MB | 6 GB | Apache-2.0 | Qwen 中文 |
| Phi-4-mini Instruct 3.8B | Phi | Q4_K_M | 2376 MB | 8 GB | MIT | Phi 英文 |
| Qwen3 4B Thinking 2507 | Qwen3 | Q4_K_M | 2382 MB | 8 GB | Apache-2.0 | Qwen 中文 思维链 |
| Spark-X2.5 4B | Spark | Q4_K_M | 2480 MB | 8 GB | Apache-2.0 | Spark 第三方转换 |
| 混元 4B Instruct | Hunyuan | Q4_K_M | 2487 MB | 8 GB | Tencent Hunyuan 社区许可 | 混元 中文 |
| Gemma 4 E2B IT（QAT） | Gemma 4 | UD-Q4_K_XL | 2499 MB | 6 GB | Gemma Terms | Gemma 英文 |
| Qwen3.5 4B | Qwen3.5 | Q4_K_M | 2614 MB | 8 GB | Apache-2.0 | Qwen 中文 推荐 |
| Qwen3.5 4B（UD 动态量化） | Qwen3.5 | UD-Q4_K_XL | 2777 MB | 8 GB | Apache-2.0 | Qwen 中文 |
| Gemma 4 E2B IT | Gemma 4 | Q4_K_M | 2963 MB | 8 GB | Gemma Terms | Gemma 英文 |
| Gemma 4 E4B IT（QAT） | Gemma 4 | UD-Q4_K_XL | 4020 MB | 12 GB | Gemma Terms | Gemma 英文 |
| Gemma 4 E4B IT | Gemma 4 | Q4_K_M | 4747 MB | 12 GB | Gemma Terms | Gemma 英文 |

### 识图（11）

> 这些模型**也能纯文字对话**（同一个文件）。需要配套 mmproj，描述里给了文件名和大小。

| 模型 | 系列 | 量化 | 体积 | 内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- |
| LFM2-VL 450M（看图） | LFM2-VL | Q4_K_M | 219 MB | 2 GB | LFM Open License | Liquid 英文 识图 极速 |
| SmolVLM 500M（看图） | SmolVLM | Q8_0 | 417 MB | 3 GB | Apache-2.0 | Smol 英文 识图 |
| SmolVLM2 500M（看图+短视频） | SmolVLM2 | Q8_0 | 417 MB | 3 GB | Apache-2.0 | Smol 英文 识图 |
| Qwen3.5 0.8B（看图） | Qwen3.5 | Q4_K_M | 508 MB | 3 GB | Apache-2.0 | Qwen 中文 识图 极速 |
| LFM2-VL 1.6B（看图） | LFM2-VL | Q4_0 | 664 MB | 4 GB | LFM Open License | Liquid 英文 识图 |
| Qwen3.5 2B（看图） | Qwen3.5 | Q4_K_M | 1222 MB | 6 GB | Apache-2.0 | Qwen 中文 识图 |
| InternVL3.5 2B（看图） | InternVL | Q4_K_M | 1223 MB | 6 GB | 仓库无 license 标签；上游 OpenGVLab InternVL3.5 为 Apache-2.0 | InternVL 中文 识图 |
| Qwen3-VL 2B（看图·稳妥档） | Qwen3-VL | Q8_0 | 1749 MB | 6 GB | Apache-2.0 | Qwen 中文 识图 推荐 |
| Qwen3.5 4B（看图） | Qwen3.5 | Q4_K_M | 2614 MB | 8 GB | Apache-2.0 | Qwen 中文 识图 |
| Gemma 4 E2B（看图） | Gemma 4 | Q4_K_M | 2963 MB | 8 GB | Gemma Terms | Gemma 英文 识图 |
| Gemma 4 E4B（看图） | Gemma 4 | Q4_K_M | 4747 MB | 12 GB | Gemma Terms | Gemma 英文 识图 |

### 生图（13）

> 需要先装「本地生图后端插件」。★ = 6 GB 手机能跑。

| 模型 | 系列 | 量化 | 体积 | 内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- |
| SDXS-512-DreamShaper（1 步） | Stable Diffusion | Q8_0 | 651 MB | 6 GB | openrail++ | 真实生图 极速 |
| Stable Diffusion 1.5（Q4_0） | Stable Diffusion | Q4_0 | 1494 MB | 6 GB | OpenRAIL-M | 真实生图 |
| DreamShaper 7 LCM（4 步） | Stable Diffusion | Q4_0 | 1550 MB | 6 GB | creativeml-openrail-m | 真实生图 |
| SD1.5（gpustack 版） | Stable Diffusion | Q4_0 | 1666 MB | 6 GB | OpenRAIL-M | 真实生图 |
| SD 2.1 768（Q8_0） | Stable Diffusion | Q8_0 | 1921 MB | 8 GB | OpenRAIL++-M | 真实生图 |
| FLUX.2-klein 4B | Stable Diffusion | Q4_0 | 2346 MB | 8 GB | Apache-2.0 | 真实生图 |
| Qwen-Image-2.1（Q2_K） | Stable Diffusion | Q2_K | 2443 MB | 8 GB | 仓库无 license 标签 | Qwen 中文 真实生图 |
| SDXL-Lightning（4 步） | Stable Diffusion | Q4_0 | 2465 MB | 8 GB | ByteDance SDXL-Lightning 许可 | 真实生图 |
| Z-Image-Turbo（Q2_K） | Stable Diffusion | Q2_K | 2472 MB | 8 GB | Apache-2.0 | 真实生图 |
| Z-Image-Turbo（Q4_0） | Stable Diffusion | Q4_0 | 3513 MB | 12 GB | Apache-2.0 | 真实生图 |
| Z-Image（非 Turbo，Q4_0） | Stable Diffusion | Q4_0 | 3661 MB | 12 GB | Apache-2.0 | 真实生图 |
| SDXL Base 1.0（Q4_0） | Stable Diffusion | Q4_0 | 3757 MB | 12 GB | OpenRAIL++-M | 真实生图 |
| SDXL-Turbo 1.0（Q8_0） | Stable Diffusion | Q8_0 | 3909 MB | 12 GB | Stability AI Community | 真实生图 |

### 决策（9）

> 这类**不是聊天模型**：它们做的是判定、路由、守卫。别当对话模型用。
> Gutsy 是唯一"输出校准概率"的（Jev 形态），这一类里最对口的。

| 模型 | 系列 | 量化 | 体积 | 内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- |
| ERNIE 4.5 0.3B | ERNIE | Q4_K_M | 229 MB | 2 GB | Apache-2.0 | 文心 中文 极速 也能决策 |
| FunctionGemma 270M | FunctionGemma | Q4_K_M | 241 MB | 2 GB | Gemma Terms of Use | 决策模型 function calling 端侧 极速 |
| Hammer 2.1 0.5B | Hammer | Q4_K_M | 379 MB | 3 GB | CC-BY-NC-4.0 | 决策模型 ⚠️非商用 |
| Qwen3Guard-Gen 0.6B | Qwen3Guard | Q4_K_M | 462 MB | 3 GB | Apache-2.0 | Qwen 中文 决策模型 推荐 第三方转换 |
| Gutsy 0.8B（校准概率） | Gutsy | Q4_K_M | 505 MB | 3 GB | Apache-2.0 | 决策模型 概率输出 Jev 形态 推荐 |
| xLAM-2 1B FC-R | xLAM | Q4_K_M | 940 MB | 4 GB | CC-BY-NC-4.0 | 决策模型 ⚠️非商用 第三方转换 |
| Arch-Router 1.5B | Arch-Router | Q4_K_M | 940 MB | 4 GB | HF 标 other，且基座是 Qwen2.5-1.5B | 决策模型 |
| Granite Guardian 3.1 2B | Granite | Q4_K_M | 1474 MB | 6 GB | Apache-2.0 | IBM 决策模型 |
| Granite 4.0 Micro | Granite | Q4_K_M | 2002 MB | 6 GB | Apache-2.0 | IBM 也能决策 |

### 语音（17 个离线音色）

| 音色 | 徽标 | 体积 | 状态 |
| --- | --- | --- | --- |
| 超文（男声 · 普通话） | 轻量 | 13 MB | ✅ 可直接用 |
| 超文（男声 · fp16） | 轻量 | 28 MB | ✅ 可直接用 |
| 超文（男声 · 全精度） | 标准 | 58 MB | ✅ 可直接用 |
| 华研（女声 · 普通话） | 标准 | 64 MB | ✅ 可直接用 |
| 小雅（女声 · fp16） | 轻量 | 28 MB | ✅ 可直接用 |
| 小雅（女声 · 全精度） | 标准 | 58 MB | ✅ 可直接用 |
| aishell3 多说话人（大） | 多说话人 | 140 MB | ✅ 可直接用 |
| MeloTTS 中英混读 | 中英混读 | 159 MB | ✅ 可直接用 |
| 中文 LL-VITS | 标准 | 113 MB | ✅ 可直接用 |
| 泛尘 Unity（多说话人） | 多说话人 | 114 MB | ✅ 可直接用 |
| 泛尘 WNJ | 角色 | 114 MB | ✅ 可直接用 |
| 智慧老者（新） | 角色 | 114 MB | ✅ 可直接用 |
| 深渊唤者（角色） | 角色 | 115 MB | ✅ 可直接用 |
| Doom（角色） | 角色 | 115 MB | ✅ 可直接用 |
| Echo（角色） | 角色 | 115 MB | ✅ 可直接用 |
| Matcha 中文（Baker） | 需改代码 | 72 MB | ⚠️ 要改代码才能发声 |
| Kokoro 多语言 int8 | 需改代码 | 140 MB | ⚠️ 要改代码才能发声 |

> `id` **必须等于官方 tarball 名**：下载器按 `tts-models/<id>.tar.bz2` 拼地址，
> `url` / `mirrors` 字段会被忽略。

### 关于 Ollama / GitHub 上的模型

主流模型（Qwen / DeepSeek / Gemma / Llama / Mistral / Phi…）的条目里带一个
`sourceUrl` 字段，指向它在 **ollama.com/library** 上的页面 —— 方便看"这个系列还有哪些尺寸"。

> ⚠️ **Ollama 的地址不能当下载源。** Ollama registry 的 blob 是它自己的内容寻址格式
> （要先拿 manifest 才能拼出 blob 路径），不是能直接喂给 llama.cpp 的单文件 GGUF。
> 所以 `sourceUrl` 只用于"去看看"，下载仍走 `url`（hf-mirror 的单文件 GGUF）。

想收 Ollama 上某个模型？去 Hugging Face 找同名的 GGUF 转换（通常是
`unsloth/` / `bartowski/` / `ggml-org/` 这几个组织），
然后在「模型」页粘贴仓库名导入。

### 想加自己的模型

解开 zip 改 `models.json`：复制一个条目，改 `id` / `name` / `fileName` / `url`。
`sizeBytes` 不确定就填 `0`（跳过大小校验）。改完**重新压成 zip**：
`plugin.json` / `models.json` / `voices.json` 必须在 zip 根目录。

字段表在**应用内**：「我的 → 插件 → 开发文档」。

---


## 三、插件能力注册表（主程序认识哪些类型）

`data/Plugin.kt` → `PluginAbilities.ALL`。**加一种能力 = 表里加一行**，
主程序不需要认识具体的包。

| id | 中文名 | 内容文件 | 状态 |
| --- | --- | --- | --- |
| `emoji_pack` | 表情包 | `emoji.json` + `assets/` | ✅ 已实现 |
| `model_library` | 模型库 | `models.json` | ✅ 已实现 |
| `model_source` | 模型下载源 | — | ✅ 已实现 |
| `tts_voice` | 语音音色 | `voices.json` | ✅ 已实现（**仅本地**） |
| `native_backend` | 推理后端 | `backends.json` + `lib/` | ✅ 已实现 |
| `ui_theme` | 界面主题 | `ui.json` | ✅ 已实现（37 个令牌） |
| `remote_provider` | 云端服务 | — | ❌ 占位 |
| `image_backend` | 生图后端 | — | ❌ 占位 |
| `web_tool` | 联网工具 | — | ❌ 占位 |
| `mcp_server` | MCP 服务 | — | ❌ 占位 |
| `persona_pack` | 人设包 | — | ❌ 占位 |
| `api_endpoint` | 接口扩展 | — | ❌ 占位 |

> **`cloud_credentials` 已在 2026-10-15 删除**：它唯一的消费者是腾讯云 TTS，
> 而那整条链路按用户要求移除了（见 [HANDOFF.md](HANDOFF.md) §5.3）。
> 留着一个没有消费端的能力只会误导插件作者。

---

## 四、安全边界（每个插件都受这套约束）

| 约束 | 说明 |
| --- | --- |
| **不执行代码** | 插件是 JSON + 资源。没有 `DexClassLoader`，没有脚本，没有入口函数 |
| **文件名白名单** | 只读 `plugin.json` / `emoji.json` / `models.json` / `voices.json` / `ui.json` / `backends.json` / `providers.json` / `assets/` / `lib/` |
| **拒绝 zip-slip** | 解压路径必须落在目标目录内，`../` 一律拒绝 |
| **单文件与总量上限** | 防止一个 zip 把磁盘塞满 |
| **原生库要验 ELF** | 架构不对直接拒；`lib/` 只用于 `native_backend` |
| **主题只能改外观** | 37 个令牌，越界值被夹取，未知键被如实报告 |
| **密钥不进插件** | 插件只声明"有这个东西"，凭据永远由用户自己填 |

---

## 五、怎么装

**只有一个动作**：选一个 zip。类型由主程序识别，不需要先告诉它这是什么插件。

压缩包必须**是平的** —— `plugin.json` 在 zip 根目录，不能套一层文件夹。
（代码里按 basename 取用，套层也能装，但那是宽容度，不是契约。）

装完可以在「我的 → 插件」里看到已装列表、开关、删除。

### 不用点 UI 的装法（脚本化验证）

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb push 官方模型库插件.zip /data/local/tmp/m.zip
& $adb forward tcp:18080 tcp:8080
Invoke-WebRequest -Method POST http://127.0.0.1:18080/v1/debug/plugin `
  -ContentType 'application/json' -Body '{"path":"/data/local/tmp/m.zip"}'
```

需要先在「我的 → 本机 API 服务」里把服务打开。


---

## 22. 1.0.4 新增的两种插件（2026-10-17）

### 22.1 `tts_engine` —— 语音引擎（`tts.json`）

```json
{
  "version": 1,
  "packId": "com.breezeblue.tts2",
  "packName": "Breeze TTS 2",
  "engines": [{
    "id": "breeze-tts-2",
    "label": "Breeze TTS 2",
    "kind": "breeze",
    "baseUrl": "http://192.168.1.10:7860",
    "apiKey": "",
    "model": "BreezeBlue/breeze-tts-2",
    "defaultVoice": "S0",
    "defaultInstruction": "",
    "cfgScale": 4,
    "sampleRate": 24000,
    "format": "pcm_s16le",
    "notes": "给用户看的一句话",
    "voices": [{ "id": "S0", "label": "默认说话人" }]
  }]
}
```

* `kind`：`breeze`（multipart，Breeze TTS 2 协议）或 `openai`（JSON，任意 OpenAI 兼容 TTS）。
* `format`：`pcm_s16le`（裸流，客户端自动套 44 字节 WAV 头）或 `wav`。
* 端点：`POST {baseUrl}/v1/audio/speech`（`baseUrl` 已以 `/v1` 结尾就不再补）。
* 开关在「我的 → 更多 → Breeze 语音」；打开后 AI 的语音回复优先用它，连不上**静默退回**系统音色。

### 22.2 `ui_layout` —— 界面布局（`layout.json`）

```json
{
  "version": 1,
  "packId": "com.localmind.layout.card",
  "packName": "卡片首页",
  "appName": "LocalMind",
  "subtitle": "模型跑在这台手机上",
  "tabs": [
    { "id": "home",   "label": "首页", "icon": "home" },
    { "id": "chat",   "label": "对话", "icon": "bubble" },
    { "id": "breeze", "label": "语音", "icon": "breeze" },
    { "id": "me",     "label": "我的", "icon": "person" }
  ],
  "startTab": "home",
  "home": [
    { "type": "hero",    "title": "今天想说点什么？", "text": "…", "icon": "sparkle" },
    { "type": "actions", "items": [{ "label": "新建对话", "icon": "bubble", "open": "new" }] },
    { "type": "recent",  "title": "最近", "limit": 3 },
    { "type": "status",  "title": "本机状态" }
  ],
  "tokens": { "accent": "#0FA3A3", "cardCorner": "16" }
}
```

**可选页面**：`chat / models / me / home / voice / breeze / plugins / image`（最多 5 个入口）。
**块类型**：`hero / actions / text / recent / status`。
**动作**：`new / settings / plugins_import` 或任意页面名。

生效方式：「我的 → 更多 → 主题 → 界面布局」里选一个（**单选**，和主题的"可叠加"不同）。

⚠️ 目前 `label` / `icon` 会被解析但**不生效**：底栏文字与图标仍来自页面本身
（`Tab.title` / `tabIconOf`）。要真正可换，得给 `LocalMindRoot` 调 `LiquidGlassTabBar`
时传一份 `labels` 映射 —— 已记在 HANDOFF §19.8 待办里。


---

## 23. `pipeline` —— 分步流水线（`pipeline.json`）

```json
{
  "version": 1,
  "packId": "com.localmind.pipeline.staged",
  "packName": "分步流水线",
  "pipelines": [
    {
      "id": "llm-longdoc",
      "name": "长文分段理解",
      "inputHint": "粘一段长文",
      "inputLines": 8,
      "stages": [
        { "id": "split", "type": "split_text", "maxChars": 800 },
        { "id": "llm",   "type": "llm", "prompt": "概括这一段：\n\n{{chunk}}", "maxTokens": 220 },
        { "id": "join",  "type": "join_text" }
      ]
    }
  ]
}
```

**动作类型**（只能用这几种）：

| type | 作用 | 关键参数 |
| --- | --- | --- |
| `probe` | 打印本机体检（内存 / Breeze 体量 / 结论） | — |
| `split_text` | 按句末标点与长度上限切片 | `maxChars`（20~4000） |
| `llm` | 每片调一次本机对话模型 | `prompt`（**必须含 `{{chunk}}`**）、`maxTokens` |
| `join_text` | 各片结果按序拼成文本 | — |
| `tts` | 每片调一次语音引擎（`tts_engine` 插件提供的那个） | `engine`（`auto`）、`instruction` |
| `concat_audio` | 多段 wav 拼接，段间插静音 | `silenceMs`（0~2000） |

**校验规则**（装的时候就报，不会跑到一半才失败）：动作必须在白名单里、stage id 唯一、
`llm` 的 prompt 必须含 `{{chunk}}`、参数在区间内、至少有一个产出步骤。

**并发**：串行执行。引擎是单实例、语音服务端通常也是单并发，并行只会让每片都变慢。

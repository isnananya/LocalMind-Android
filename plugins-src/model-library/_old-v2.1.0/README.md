# LocalMind 官方模型库 · 插件

> **v2.1.0** · 2026-10-15 重做：模型 33 → **52**，并**带上了 17 个离线语音音色**。
> 所有下载地址在这一天用 `curl -sSI -L` 逐个验证过（`302 → 200`），
> `sizeBytes` 是响应头的 `content-length`，不是估的。

导入后会在「模型」页出现 **52 个模型**和一个**「语音」分类**（17 个音色）。

## 分类

| 分类 | 数量 | 说明 |
| --- | --- | --- |
| 对话 | 19 | 文字对话、写作、代码（**多模态模型也在里面**，一个文件两用） |
| 识图 | 11 | 多模态（⚠️ 见下） |
| 生图 | 13 | 扩散模型，需要 stable-diffusion.cpp 后端 |
| 决策 | 9 | 判定 / 路由 / 守卫，不是聊天模型（Gutsy = 校准概率） |
| 语音 | 17 | 离线 TTS 音色（走音色下载器，不是 GGUF） |

## 三个必须知道的事实

### 1. 识图现在**跑不起来** —— 这是代码事实，不是模型问题

`AppViewModel.canSeeImages` 写死 `false`，`LocalModel` 也没有 mmproj 字段。
所以每个识图条目的 `description` 里都写清了配套 mmproj 的**文件名和字节数**，
但**今天装了也看不见图**。别让用户以为是模型笨。

### 2. 生图默认 4 步 / EULER_A，只有少数步模型能用

后端不传 `--vae` / `--clip_l`，所以**只能收单文件 all-in-one GGUF**（UNet+VAE+CLIP 在一个文件里），
split 布局的 FLUX / Qwen-Image 官方转换不能用。原版 SD1.5 / SDXL 在 4 步下会糊。

### 3. 语音只有 **VITS** 能直接发声

`NeuralTts.kt` 只构造 `vits` 配置。Matcha / Kokoro 的类在 `.so` 里但主程序没写分支，
而且 Matcha 是"两个 onnx"、Kokoro 还要 `voices.bin` —— 所以这两条标了 **「需改代码」**，
收录是为了让你先下，代码接上就能直接用。

## 每个条目都带国内镜像

`url` 指向 `hf-mirror.com`（国内可达），`mirrors` 里有官方 `huggingface.co`。
下载失败按顺序自动回退，支持断点续传。

## 为什么模型库不在 APK 里

模型地址会失效、会出新量化、会换镜像。内置一份清单意味着每改一次都要发新 APK，
而用户只能等。做成插件之后，换地址只需要换一个 zip。

## 想加自己的模型？

解开 zip 改 `models.json`：复制一个条目，改 `id` / `name` / `fileName` / `url`。
`sizeBytes` 不确定就填 `0`（那样会跳过大小校验）。
改完**重新压成 zip**，注意 `plugin.json` / `models.json` / `voices.json` 必须在 zip 根目录。

字段表在应用内「我的 → 插件 → 开发文档」里，也可以看 `docs/PLUGIN.md`。

---

# 全部 50 个模型

## 对话（19）

| id | 名称 | 系列 | 量化 | 体积 | 建议内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `qwen35-4b-q4km` | Qwen3.5 4B | Qwen3.5 | Q4_K_M | 2614 MB | 8 GB | Apache-2.0 | Qwen 中文 推荐 |
| `qwen35-4b-ud-q4kxl` | Qwen3.5 4B（UD 动态量化） | Qwen3.5 | UD-Q4_K_XL | 2777 MB | 8 GB | Apache-2.0 | Qwen 中文 |
| `qwen35-4b-iq4xs` | Qwen3.5 4B（省内存） | Qwen3.5 | IQ4_XS | 2362 MB | 6 GB | Apache-2.0 | Qwen 中文 |
| `qwen35-2b-q4km` | Qwen3.5 2B | Qwen3.5 | Q4_K_M | 1222 MB | 4 GB | Apache-2.0 | Qwen 中文 |
| `qwen35-08b-q4km` | Qwen3.5 0.8B | Qwen3.5 | Q4_K_M | 508 MB | 3 GB | Apache-2.0 | Qwen 中文 极速 |
| `minicpm5-2b-q4km` | MiniCPM5 2B | MiniCPM5 | Q4_K_M | 1489 MB | 4 GB | Apache-2.0见 §6 | MiniCPM 中文 |
| `gemma4-e2b-q4km` | Gemma 4 E2B IT | Gemma 4 | Q4_K_M | 2963 MB | 8 GB | Gemma Terms | Gemma 英文 |
| `gemma4-e2b-qat-q4kxl` | Gemma 4 E2B IT（QAT） | Gemma 4 | UD-Q4_K_XL | 2499 MB | 6 GB | Gemma Terms | Gemma 英文 |
| `gemma4-e4b-q4km` | Gemma 4 E4B IT | Gemma 4 | Q4_K_M | 4747 MB | 12 GB | Gemma Terms | Gemma 英文 |
| `gemma4-e4b-qat-q4kxl` | Gemma 4 E4B IT（QAT） | Gemma 4 | UD-Q4_K_XL | 4020 MB | 12 GB | Gemma Terms | Gemma 英文 |
| `hunyuan-1.8b-instruct-q4km` | 混元 1.8B Instruct | Hunyuan | Q4_K_M | 1081 MB | 4 GB | Tencent Hunyuan 社区许可 | 混元 中文 |
| `hunyuan-4b-instruct-q4km` | 混元 4B Instruct | Hunyuan | Q4_K_M | 2487 MB | 8 GB | Tencent Hunyuan 社区许可 | 混元 中文 |
| `minicpm4-05b-q8` | MiniCPM4 0.5B | MiniCPM4 | Q8_0 | 441 MB | 3 GB | Apache-2.0 | MiniCPM 中文 第三方转换 |
| `lfm25-12b-instruct-q4km` | LFM2.5 1.2B Instruct | LFM | Q4_K_M | 697 MB | 3 GB | LFM Open License | Liquid 英文 |
| `smollm3-3b-q4km` | SmolLM3 3B | SmolLM3 | Q4_K_M | 1827 MB | 6 GB | Apache-2.0 | Smol 英文 思维链 |
| `qwen3-4b-thinking-2507-q4km` | Qwen3 4B Thinking 2507 | Qwen3 | Q4_K_M | 2382 MB | 8 GB | Apache-2.0 | Qwen 中文 思维链 |
| `nemotron-reasoning-qwen-15b-q4km` | Nemotron 推理 1.5B | Nemotron | Q4_K_M | 1066 MB | 4 GB | CC-BY-NC-4.0 | NVIDIA 英文 思维链 ⚠️非商用 |
| `phi4-mini-instruct-q4km` | Phi-4-mini Instruct 3.8B | Phi | Q4_K_M | 2376 MB | 8 GB | MIT | Phi 英文 |
| `spark-x25-4b-q4km` | Spark-X2.5 4B | Spark | Q4_K_M | 2480 MB | 8 GB | Apache-2.0 | Spark 第三方转换 |

## 识图（11）

| id | 名称 | 系列 | 量化 | 体积 | 建议内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `qwen35-08b-vl-q4km` | Qwen3.5 0.8B（看图） | Qwen3.5 | Q4_K_M | 508 MB | 3 GB | Apache-2.0 | Qwen 中文 识图 极速 |
| `qwen35-2b-vl-q4km` | Qwen3.5 2B（看图） | Qwen3.5 | Q4_K_M | 1222 MB | 6 GB | Apache-2.0 | Qwen 中文 识图 |
| `qwen35-4b-vl-q4km` | Qwen3.5 4B（看图） | Qwen3.5 | Q4_K_M | 2614 MB | 8 GB | Apache-2.0 | Qwen 中文 识图 |
| `gemma4-e2b-vl-q4km` | Gemma 4 E2B（看图） | Gemma 4 | Q4_K_M | 2963 MB | 8 GB | Gemma Terms | Gemma 英文 识图 |
| `gemma4-e4b-vl-q4km` | Gemma 4 E4B（看图） | Gemma 4 | Q4_K_M | 4747 MB | 12 GB | Gemma Terms | Gemma 英文 识图 |
| `lfm2vl-450m-q4km` | LFM2-VL 450M（看图） | LFM2-VL | Q4_K_M | 219 MB | 2 GB | LFM Open License | Liquid 英文 识图 极速 |
| `smolvlm-500m-q8` | SmolVLM 500M（看图） | SmolVLM | Q8_0 | 417 MB | 3 GB | Apache-2.0 | Smol 英文 识图 |
| `smolvlm2-500m-video-q8` | SmolVLM2 500M（看图+短视频） | SmolVLM2 | Q8_0 | 417 MB | 3 GB | Apache-2.0 | Smol 英文 识图 |
| `lfm2vl-16b-q4` | LFM2-VL 1.6B（看图） | LFM2-VL | Q4_0 | 664 MB | 4 GB | LFM Open License | Liquid 英文 识图 |
| `internvl35-2b-q4km` | InternVL3.5 2B（看图） | InternVL | Q4_K_M | 1223 MB | 6 GB | 仓库无 license 标签；上游 OpenGVLab InternVL3.5 为 Apache-2.0 | InternVL 中文 识图 |
| `qwen3vl-2b-q8` | Qwen3-VL 2B（看图·稳妥档） | Qwen3-VL | Q8_0 | 1749 MB | 6 GB | Apache-2.0 | Qwen 中文 识图 推荐 |

## 生图（13）

| id | 名称 | 系列 | 量化 | 体积 | 建议内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `sdxs-512-dreamshaper-q8` | SDXS-512-DreamShaper（1 步） | Stable Diffusion | Q8_0 | 651 MB | 6 GB | openrail++ | 真实生图 极速 |
| `dreamshaper7-lcm-q4` | DreamShaper 7 LCM（4 步） | Stable Diffusion | Q4_0 | 1550 MB | 6 GB | creativeml-openrail-m | 真实生图 |
| `flux2-klein-4b-q4` | FLUX.2-klein 4B | Stable Diffusion | Q4_0 | 2346 MB | 8 GB | Apache-2.0 | 真实生图 |
| `z-image-turbo-q2k` | Z-Image-Turbo（Q2_K） | Stable Diffusion | Q2_K | 2472 MB | 8 GB | Apache-2.0 | 真实生图 |
| `z-image-turbo-q4` | Z-Image-Turbo（Q4_0） | Stable Diffusion | Q4_0 | 3513 MB | 12 GB | Apache-2.0 | 真实生图 |
| `sdxl-lightning-4step-q4` | SDXL-Lightning（4 步） | Stable Diffusion | Q4_0 | 2465 MB | 8 GB | ByteDance SDXL-Lightning 许可 | 真实生图 |
| `qwen-image-21-q2k` | Qwen-Image-2.1（Q2_K） | Stable Diffusion | Q2_K | 2443 MB | 8 GB | 仓库无 license 标签 | Qwen 中文 真实生图 |
| `sd15-emaonly-q4` | Stable Diffusion 1.5（Q4_0） | Stable Diffusion | Q4_0 | 1494 MB | 6 GB | CreativeML OpenRAIL-M | 真实生图 |
| `sd15-q4-gpustack` | SD1.5（gpustack 版） | Stable Diffusion | Q4_0 | 1666 MB | 6 GB | CreativeML OpenRAIL-M | 真实生图 |
| `sd21-768-q8` | SD 2.1 768（Q8_0） | Stable Diffusion | Q8_0 | 1921 MB | 8 GB | CreativeML OpenRAIL++-M | 真实生图 |
| `sdxl-base-10-q4` | SDXL Base 1.0（Q4_0） | Stable Diffusion | Q4_0 | 3757 MB | 12 GB | CreativeML OpenRAIL++-M | 真实生图 |
| `sdxl-turbo-10-q8` | SDXL-Turbo 1.0（Q8_0） | Stable Diffusion | Q8_0 | 3909 MB | 12 GB | Stability AI Community License | 真实生图 ⚠️非商用 |
| `z-image-q4` | Z-Image（非 Turbo，Q4_0） | Stable Diffusion | Q4_0 | 3661 MB | 12 GB | Apache-2.0 | 真实生图 |

## 决策（7）

| id | 名称 | 系列 | 量化 | 体积 | 建议内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `ernie45-03b-pt-q4km` | ERNIE 4.5 0.3B | ERNIE | Q4_K_M | 229 MB | 2 GB | Apache-2.0 | 文心 中文 极速 也能决策 |
| `granite4-micro-q4km` | Granite 4.0 Micro | Granite | Q4_K_M | 2002 MB | 6 GB | Apache-2.0 | IBM 也能决策 |
| `qwen3guard-gen-06b-q4km` | Qwen3Guard-Gen 0.6B | Qwen3Guard | Q4_K_M | 462 MB | 3 GB | Apache-2.0 | Qwen 中文 决策模型 推荐 第三方转换 |
| `granite-guardian-31-2b-q4km` | Granite Guardian 3.1 2B | Granite | Q4_K_M | 1474 MB | 6 GB | Apache-2.0 | IBM 决策模型 |
| `hammer21-05b-q4km` | Hammer 2.1 0.5B | Hammer | Q4_K_M | 379 MB | 3 GB | CC-BY-NC-4.0 | 决策模型 ⚠️非商用 |
| `xlam2-1b-fc-r-q4km` | xLAM-2 1B FC-R | xLAM | Q4_K_M | 940 MB | 4 GB | CC-BY-NC-4.0 | 决策模型 ⚠️非商用 第三方转换 |
| `arch-router-15b-q4km` | Arch-Router 1.5B | Arch-Router | Q4_K_M | 940 MB | 4 GB | HF 标 other，且基座是 Qwen2.5-1.5B | 决策模型 |

# 语音音色（17）

| id（= 官方 tarball 名） | 名称 | 徽标 | 体积 |
| --- | --- | --- | --- |
| `vits-piper-zh_CN-chaowen-medium-int8` | 超文（男声 · 普通话） | 轻量 | 13 MB |
| `vits-piper-zh_CN-chaowen-medium-fp16` | 超文（男声 · fp16） | 轻量 | 28 MB |
| `vits-piper-zh_CN-chaowen-medium` | 超文（男声 · 全精度） | 标准 | 58 MB |
| `vits-piper-zh_CN-huayan-medium` | 华研（女声 · 普通话） | 标准 | 64 MB |
| `vits-piper-zh_CN-xiao_ya-medium-fp16` | 小雅（女声 · fp16） | 轻量 | 28 MB |
| `vits-piper-zh_CN-xiao_ya-medium` | 小雅（女声 · 全精度） | 标准 | 58 MB |
| `vits-zh-aishell3` | aishell3 多说话人（大） | 多说话人 | 140 MB |
| `vits-melo-tts-zh_en` | MeloTTS 中英混读 | 中英混读 | 159 MB |
| `sherpa-onnx-vits-zh-ll` | 中文 LL-VITS | 标准 | 113 MB |
| `vits-zh-hf-fanchen-unity` | 泛尘 Unity（多说话人） | 多说话人 | 114 MB |
| `vits-zh-hf-fanchen-wnj` | 泛尘 WNJ | 角色 | 114 MB |
| `vits-zh-hf-fanchen-ZhiHuiLaoZhe_new` | 智慧老者（新） | 角色 | 114 MB |
| `vits-zh-hf-abyssinvoker` | 深渊唤者（角色） | 角色 | 115 MB |
| `vits-zh-hf-doom` | Doom（角色） | 角色 | 115 MB |
| `vits-zh-hf-echo` | Echo（角色） | 角色 | 115 MB |
| `matcha-icefall-zh-baker` | Matcha 中文（Baker） | 需改代码 | 72 MB |
| `kokoro-int8-multi-lang-v1_1` | Kokoro 多语言 int8 | 需改代码 | 140 MB |

> ⚠️ 标「需改代码」的两条（Matcha / Kokoro）需要先给 `NeuralTts` 加分支才能真正发声。

## 授权

模型**权重**不属于本插件，也不随本项目分发 —— 这里只有下载地址。
条目的 `license` 字段标注了它自己的授权。
标了 `⚠️非商用` 的（Nemotron 推理、Hammer、xLAM）**商用前必须自行确认**。

下载并使用之前，请自行确认该授权是否适用于你的用途。

# LocalMind 官方模型库 · 插件

> **v3.0.0** · 2026-10-16 重做：模型 52 → **83**（新增 36 条，重点补上 **DeepSeek** 与 **Kimi**），并继续带 17 个离线语音音色。
> 所有下载地址在这一天用 `curl -sSI -L` 逐个 HEAD 验证过（`302 -> 200`），`sizeBytes` 是响应头的真实 `content-length`，不是估的。

导入后会在「模型」页出现 **83 个模型**和一个**「语音」分类**（17 个音色）。

## 这次新增了什么

| 系列 | 新增 | 说明 |
| --- | --- | --- |
| **DeepSeek** | 10 条 | R1 蒸馏 1.5B / 7B / 8B / 14B、R1-0528 蒸馏 8B、蒸馏 Llama 8B、Coder-V2 Lite。**全部是单文件 GGUF**，最小的 739 MB，最大的 6.0 GB |
| **Kimi（月之暗面）** | 6 条 | Kimi-K3 0.40B 端侧小杯（260 MB / 400 MB）+ Kimi-K3 蒸馏 9B（Qwen3.5 基座）三档量化 |
| Qwen | 9 条 | Qwen3.5 9B 两档、4B/2B/0.8B 的 Q8 满血档、Qwen3 4B Instruct/Thinking 2507、Qwen3 8B、Qwen2.5-Coder 7B |
| 其它热门 | 20 条 | Llama 3.2 3B / Llama 3.1 8B、Mistral 7B v0.3、Phi-4-mini、Gemma 3 4B、GLM-Edge 4B / GLM-4 9B、混元 4B / 1.8B、Granite 4.0 Micro、LFM2.5 |

## 分类

| 分类 | 数量 | 说明 |
| --- | --- | --- |
| 对话 | 55 | 文字对话、写作、代码（多模态模型也在里面，一个文件两用） |
| 识图 | 6 | 多模态（⚠️ 见下） |
| 生图 | 13 | 扩散模型，需要 stable-diffusion.cpp 后端 |
| 决策 | 9 | 判定 / 路由 / 守卫，不是聊天模型（Gutsy = 校准概率） |
| 语音 | 17 | 离线 TTS 音色（走音色下载器，不是 GGUF） |

## 三个必须知道的事实

### 1. 识图现在**跑不起来** —— 这是代码事实，不是模型问题

`AppViewModel.canSeeImages` 写死 `false`，`LocalModel` 也没有 mmproj 字段。
所以每个识图条目的 `description` 里都写清了配套 mmproj 的**文件名和字节数**，
但**今天装了也看不见图**。别让用户以为是模型笨。

### 2. 生图默认 4 步 / EULER_A，只有少数步模型能用

后端不传 `--vae` / `--clip_l`，所以**只能收单文件 all-in-one GGUF**，split 布局的 FLUX / Qwen-Image 官方转换不能用。

### 3. 这次清掉了 5 条「假识图」

原清单里 `qwen35-08b-vl-q4km` / `qwen35-2b-vl-q4km` / `qwen35-4b-vl-q4km` / `gemma4-e2b-vl-q4km` / `gemma4-e4b-vl-q4km`
这 5 条与对应的对话条目**指向同一个文件**。应用是按 `fileName` 去重的，只会留一条 ——
也就是说这 5 条在模型页里其实永远显示不出来（空壳）。新清单把它们去掉了，它们的对话版仍在，能力一点没少。

## 为什么没有 Kimi K2/K3、DeepSeek V3.2/V4 本体

因为**手机上装不下，也跑不动**：

* Kimi-K3 的 GGUF 是 14 到 32 个分片（每片 4 到 40 GB），DeepSeek-V4-Flash 是 3 到 5 个分片 ——
  分片模型要 llama.cpp 按 `-00001-of-000NN` 一起加载，本应用的下载器只收**单文件**；
* 就算不下分片，Kimi-K3 / DeepSeek-V4 的权重都在 TB 级（V4-Flash 最小的 UD-TQ1_0 也有约 170 GB），
  手机内存和闪存都不够。

所以本库收的是它们的**端侧蒸馏 / 小杯**（DeepSeek-R1 蒸馏全系、Kimi-K3 0.40B、Kimi-K3 蒸馏 9B）——
这才是手机真能跑起来的那部分。想要完整 K2/K3、V4 本体，请用云端 API（DeepSeek 开放平台 / Moonshot 开放平台）。

## 每个条目都带国内镜像

`url` 指向 `hf-mirror.com`（国内可达），`mirrors` 里有官方 `huggingface.co`。
下载失败按顺序自动回退，支持断点续传。

## 想加自己的模型？

解开 zip 改 `models.json`：复制一个条目，改 `id` / `name` / `fileName` / `url`。
`sizeBytes` 不确定就填 `0`（那样会跳过大小校验）。
改完**重新压成 zip**，注意 `plugin.json` / `models.json` / `voices.json` 必须在 zip 根目录。

字段表在应用内「我的 → 插件 → 开发文档」里，也可以看 `docs/PLUGIN.md`。

---

# 新增条目明细（36 条）

## DeepSeek（10）

| id | 名称 | 系列 | 量化 | 体积 | 建议内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `deepseek-r1-distill-qwen-15b-q4km` | DeepSeek-R1 蒸馏 1.5B | DeepSeek-R1 | Q4_K_M | 1066 MB | 3 GB | Apache-2.0 | DeepSeek 中文 思维链 极速 |
| `deepseek-r1-distill-qwen-15b-q8` | DeepSeek-R1 蒸馏 1.5B（Q8 全精度） | DeepSeek-R1 | Q8_0 | 1807 MB | 4 GB | Apache-2.0 | DeepSeek 中文 思维链 |
| `deepseek-r1-distill-qwen-15b-iq2xxs` | DeepSeek-R1 蒸馏 1.5B（极限压缩） | DeepSeek-R1 | UD-IQ2_XXS | 739 MB | 2 GB | Apache-2.0 | DeepSeek 中文 思维链 低内存 |
| `deepseek-r1-distill-qwen-7b-q4km` | DeepSeek-R1 蒸馏 7B | DeepSeek-R1 | Q4_K_M | 4466 MB | 8 GB | Apache-2.0 | DeepSeek 中文 思维链 推理 |
| `deepseek-r1-distill-qwen-7b-q2k` | DeepSeek-R1 蒸馏 7B（省内存） | DeepSeek-R1 | Q2_K | 2876 MB | 5 GB | Apache-2.0 | DeepSeek 中文 思维链 |
| `deepseek-r1-0528-qwen3-8b-q4km` | DeepSeek-R1-0528 蒸馏 8B | DeepSeek-R1 | Q4_K_M | 4795 MB | 10 GB | MIT | DeepSeek 中文 思维链 推理 |
| `deepseek-r1-0528-qwen3-8b-iq2m` | DeepSeek-R1-0528 蒸馏 8B（省内存） | DeepSeek-R1 | UD-IQ2_M | 2966 MB | 6 GB | MIT | DeepSeek 中文 思维链 |
| `deepseek-r1-distill-llama-8b-q4km` | DeepSeek-R1 蒸馏 Llama 8B | DeepSeek-R1 | Q4_K_M | 4693 MB | 10 GB | Llama 3.1 Community | DeepSeek 英文 思维链 |
| `deepseek-r1-distill-qwen-14b-q2k` | DeepSeek-R1 蒸馏 14B（极限压缩） | DeepSeek-R1 | Q2_K | 5503 MB | 10 GB | Apache-2.0 | DeepSeek 中文 思维链 推理 |
| `deepseek-coder-v2-lite-q2k` | DeepSeek-Coder-V2 Lite 16B-A2.4B | DeepSeek-Coder | Q2_K | 6133 MB | 10 GB | DeepSeek License | DeepSeek 中文 代码 |

## Kimi / 月之暗面（6）

| id | 名称 | 系列 | 量化 | 体积 | 建议内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `kimi-k3-040b-q4km` | Kimi-K3 0.40B | Kimi-K3 | Q4_K_M | 260 MB | 2 GB | MIT | Kimi 中文 极速 低内存 |
| `kimi-k3-040b-q8` | Kimi-K3 0.40B（Q8） | Kimi-K3 | Q8_0 | 401 MB | 2 GB | MIT | Kimi 中文 极速 |
| `kimi-k3-040b-i1-q4km` | Kimi-K3 0.40B（i1 校准量化） | Kimi-K3 | i1-Q4_K_M | 260 MB | 2 GB | MIT | Kimi 中文 极速 第三方转换 |
| `kimi-k3-distill-9b-q4km` | Kimi-K3 蒸馏 9B（Qwen3.5 基座） | Kimi-K3 | Q4_K_M | 5512 MB | 12 GB | Apache-2.0 | Kimi 中文 推荐 第三方转换 |
| `kimi-k3-distill-9b-i1-q4km` | Kimi-K3 蒸馏 9B（i1 校准） | Kimi-K3 | i1-Q4_K_M | 5512 MB | 12 GB | Apache-2.0 | Kimi 中文 第三方转换 |
| `kimi-k3-distill-9b-q2k` | Kimi-K3 蒸馏 9B（省内存） | Kimi-K3 | Q2_K | 3734 MB | 8 GB | Apache-2.0 | Kimi 中文 第三方转换 |

## 其它新增（20）

| id | 名称 | 系列 | 量化 | 体积 | 建议内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `qwen35-9b-q4km` | Qwen3.5 9B | Qwen3.5 | Q4_K_M | 5417 MB | 10 GB | Apache-2.0 | Qwen 中文 推荐 |
| `qwen35-9b-iq4xs` | Qwen3.5 9B（IQ4_XS） | Qwen3.5 | IQ4_XS | 4929 MB | 8 GB | Apache-2.0 | Qwen 中文 |
| `qwen35-4b-q8` | Qwen3.5 4B（Q8 全精度） | Qwen3.5 | Q8_0 | 4275 MB | 8 GB | Apache-2.0 | Qwen 中文 |
| `qwen35-2b-q8` | Qwen3.5 2B（Q8 全精度） | Qwen3.5 | Q8_0 | 1919 MB | 4 GB | Apache-2.0 | Qwen 中文 推荐 |
| `qwen35-08b-q8` | Qwen3.5 0.8B（Q8 全精度） | Qwen3.5 | Q8_0 | 774 MB | 2 GB | Apache-2.0 | Qwen 中文 极速 |
| `qwen3-4b-instruct-2507-ud-q4kxl` | Qwen3 4B Instruct 2507（UD） | Qwen3 | UD-Q4_K_XL | 2428 MB | 8 GB | Apache-2.0 | Qwen 中文 推荐 |
| `qwen3-4b-thinking-2507-q8` | Qwen3 4B Thinking 2507（Q8） | Qwen3 | Q8_0 | 4082 MB | 8 GB | Apache-2.0 | Qwen 中文 思维链 |
| `qwen3-8b-q4km` | Qwen3 8B | Qwen3 | Q4_K_M | 4795 MB | 10 GB | Apache-2.0 | Qwen 中文 思维链 |
| `qwen25-coder-7b-q4km` | Qwen2.5-Coder 7B | Qwen2.5-Coder | Q4_K_M | 4466 MB | 8 GB | Apache-2.0 | Qwen 中文 代码 |
| `llama32-3b-instruct-q8` | Llama 3.2 3B Instruct（Q8） | Llama 3.2 | Q8_0 | 3263 MB | 5 GB | Llama 3.2 Community | Meta 英文 推荐 |
| `llama31-8b-instruct-q4km` | Llama 3.1 8B Instruct | Llama 3.1 | Q4_K_M | 4693 MB | 10 GB | Llama 3.1 Community | Meta 英文 |
| `mistral-7b-instruct-v03-q4km` | Mistral 7B Instruct v0.3 | Mistral | Q4_K_M | 4170 MB | 8 GB | Apache-2.0 | Mistral 英文 推荐 |
| `phi4-mini-instruct-q8` | Phi-4-mini Instruct 3.8B（Q8） | Phi | Q8_0 | 3895 MB | 6 GB | MIT | Microsoft 英文 |
| `gemma3-4b-it-q8` | Gemma 3 4B IT（Q8） | Gemma 3 | Q8_0 | 3939 MB | 6 GB | Gemma Terms | Gemma 英文 写作 |
| `glm-edge-4b-chat-q4km` | GLM-Edge 4B Chat | GLM | Q4_K_M | 2506 MB | 6 GB | GLM 社区许可（见仓库） | 智谱 中文 推荐 |
| `glm4-9b-0414-iq4xs` | GLM-4 9B 0414 | GLM | IQ4_XS | 5018 MB | 8 GB | MIT | 智谱 中文 推荐 |
| `hunyuan-4b-instruct-q8` | 混元 4B Instruct（Q8） | Hunyuan | Q8_0 | 4283 MB | 8 GB | Tencent Hunyuan 社区许可 | 混元 中文 写作 |
| `hunyuan-18b-instruct-q8` | 混元 1.8B Instruct（Q8） | Hunyuan | Q8_0 | 1820 MB | 3 GB | Tencent Hunyuan 社区许可 | 混元 中文 极速 |
| `granite40-micro-q8` | Granite 4.0 Micro（Q8） | Granite | Q8_0 | 3452 MB | 6 GB | Apache-2.0 | IBM 英文 |
| `lfm25-12b-instruct-ud-q4kxl` | LFM2.5 1.2B Instruct（UD） | LFM | UD-Q4_K_XL | 697 MB | 3 GB | LFM Open License | Liquid 英文 极速 |

---

# 原有 19 个对话模型

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

# 识图（6）

| id | 名称 | 系列 | 量化 | 体积 | 建议内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `lfm2vl-450m-q4km` | LFM2-VL 450M（看图） | LFM2-VL | Q4_K_M | 219 MB | 2 GB | LFM Open License | Liquid 英文 识图 极速 |
| `smolvlm-500m-q8` | SmolVLM 500M（看图） | SmolVLM | Q8_0 | 417 MB | 3 GB | Apache-2.0 | Smol 英文 识图 |
| `smolvlm2-500m-video-q8` | SmolVLM2 500M（看图+短视频） | SmolVLM2 | Q8_0 | 417 MB | 3 GB | Apache-2.0 | Smol 英文 识图 |
| `lfm2vl-16b-q4` | LFM2-VL 1.6B（看图） | LFM2-VL | Q4_0 | 664 MB | 4 GB | LFM Open License | Liquid 英文 识图 |
| `internvl35-2b-q4km` | InternVL3.5 2B（看图） | InternVL | Q4_K_M | 1223 MB | 6 GB | 仓库无 license 标签；上游 OpenGVLab InternVL3.5 为 Apache-2.0 | InternVL 中文 识图 |
| `qwen3vl-2b-q8` | Qwen3-VL 2B（看图·稳妥档） | Qwen3-VL | Q8_0 | 1749 MB | 6 GB | Apache-2.0 | Qwen 中文 识图 推荐 |

# 生图（13）

| id | 名称 | 系列 | 量化 | 体积 | 建议内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `sdxs-512-dreamshaper-q8` | SDXS-512-DreamShaper（1 步） | Stable Diffusion | Q8_0 | 651 MB | 6 GB | openrail++ | 真实生图 极速 |
| `dreamshaper7-lcm-q4` | DreamShaper 7 LCM（4 步） | Stable Diffusion | Q4_0 | 1550 MB | 6 GB | creativeml-openrail-m | 真实生图 |
| `flux2-klein-4b-q4` | FLUX.2-klein 4B | Stable Diffusion | Q4_0 | 2346 MB | 8 GB | Apache-2.0 | 真实生图 |
| `z-image-turbo-q2k` | Z-Image-Turbo（Q2_K） | Stable Diffusion | Q2_K | 2472 MB | 8 GB | Apache-2.0 | 真实生图 |
| `z-image-turbo-q4` | Z-Image-Turbo（Q4_0） | Stable Diffusion | Q4_0 | 3513 MB | 12 GB | Apache-2.0 | 真实生图 |
| `sdxl-lightning-4step-q4` | SDXL-Lightning（4 步） | Stable Diffusion | Q4_0 | 2465 MB | 8 GB | ByteDance SDXL-Lightning 许可 | 真实生图 |
| `qwen-image-21-q2k` | Qwen-Image-2.1（Q2_K） | Stable Diffusion | Q2_K | 2443 MB | 8 GB | 仓库无 license 标签 | Qwen 中文 真实生图 |
| `sd15-emaonly-q4` | Stable Diffusion 1.5（Q4_0） | Stable Diffusion | Q4_0 | 1494 MB | 6 GB | OpenRAIL-M | 真实生图 |
| `sd15-q4-gpustack` | SD1.5（gpustack 版） | Stable Diffusion | Q4_0 | 1666 MB | 6 GB | OpenRAIL-M | 真实生图 |
| `sd21-768-q8` | SD 2.1 768（Q8_0） | Stable Diffusion | Q8_0 | 1921 MB | 8 GB | OpenRAIL++-M | 真实生图 |
| `sdxl-base-10-q4` | SDXL Base 1.0（Q4_0） | Stable Diffusion | Q4_0 | 3757 MB | 12 GB | OpenRAIL++-M | 真实生图 |
| `sdxl-turbo-10-q8` | SDXL-Turbo 1.0（Q8_0） | Stable Diffusion | Q8_0 | 3909 MB | 12 GB | Stability AI Community | 真实生图 |
| `z-image-q4` | Z-Image（非 Turbo，Q4_0） | Stable Diffusion | Q4_0 | 3661 MB | 12 GB | Apache-2.0 | 真实生图 |

# 决策（9）

| id | 名称 | 系列 | 量化 | 体积 | 建议内存 | 许可 | 标签 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `ernie45-03b-pt-q4km` | ERNIE 4.5 0.3B | ERNIE | Q4_K_M | 229 MB | 2 GB | Apache-2.0 | 文心 中文 极速 也能决策 |
| `granite4-micro-q4km` | Granite 4.0 Micro | Granite | Q4_K_M | 2002 MB | 6 GB | Apache-2.0 | IBM 也能决策 |
| `qwen3guard-gen-06b-q4km` | Qwen3Guard-Gen 0.6B | Qwen3Guard | Q4_K_M | 462 MB | 3 GB | Apache-2.0 | Qwen 中文 决策模型 推荐 第三方转换 |
| `granite-guardian-31-2b-q4km` | Granite Guardian 3.1 2B | Granite | Q4_K_M | 1474 MB | 6 GB | Apache-2.0 | IBM 决策模型 |
| `hammer21-05b-q4km` | Hammer 2.1 0.5B | Hammer | Q4_K_M | 379 MB | 3 GB | CC-BY-NC-4.0 | 决策模型 ⚠️非商用 |
| `xlam2-1b-fc-r-q4km` | xLAM-2 1B FC-R | xLAM | Q4_K_M | 940 MB | 4 GB | CC-BY-NC-4.0 | 决策模型 ⚠️非商用 第三方转换 |
| `arch-router-15b-q4km` | Arch-Router 1.5B | Arch-Router | Q4_K_M | 940 MB | 4 GB | HF 标 other，且基座是 Qwen2.5-1.5B | 决策模型 |
| `gutsy-08b-q4km` | Gutsy 0.8B（校准概率） | Gutsy | Q4_K_M | 505 MB | 3 GB | Apache-2.0 | 决策模型 概率输出 Jev 形态 推荐 |
| `functiongemma-270m-q4km` | FunctionGemma 270M | FunctionGemma | Q4_K_M | 241 MB | 2 GB | Gemma Terms of Use | 决策模型 function calling 端侧 极速 |

# 语音音色（17）

| id（= 官方 tarball 名） | 名称 | 徽标 | 体积 |
| --- | --- | --- | --- |
| `vits-piper-zh_CN-chaowen-medium-int8` | undefined | 轻量 | 13 MB |
| `vits-piper-zh_CN-chaowen-medium-fp16` | undefined | 轻量 | 28 MB |
| `vits-piper-zh_CN-chaowen-medium` | undefined | 标准 | 58 MB |
| `vits-piper-zh_CN-huayan-medium` | undefined | 标准 | 64 MB |
| `vits-piper-zh_CN-xiao_ya-medium-fp16` | undefined | 轻量 | 28 MB |
| `vits-piper-zh_CN-xiao_ya-medium` | undefined | 标准 | 58 MB |
| `vits-zh-aishell3` | undefined | 多说话人 | 140 MB |
| `vits-melo-tts-zh_en` | undefined | 中英混读 | 159 MB |
| `sherpa-onnx-vits-zh-ll` | undefined | 标准 | 113 MB |
| `vits-zh-hf-fanchen-unity` | undefined | 多说话人 | 114 MB |
| `vits-zh-hf-fanchen-wnj` | undefined | 角色 | 114 MB |
| `vits-zh-hf-fanchen-ZhiHuiLaoZhe_new` | undefined | 角色 | 114 MB |
| `vits-zh-hf-abyssinvoker` | undefined | 角色 | 115 MB |
| `vits-zh-hf-doom` | undefined | 角色 | 115 MB |
| `vits-zh-hf-echo` | undefined | 角色 | 115 MB |
| `matcha-icefall-zh-baker` | undefined | 需改代码 | 72 MB |
| `kokoro-int8-multi-lang-v1_1` | undefined | 需改代码 | 140 MB |

> ⚠️ 标「需改代码」的音色（Matcha / Kokoro）需要先给 `NeuralTts` 加分支才能真正发声。

## 授权

模型**权重**不属于本插件，也不随本项目分发 —— 这里只有下载地址。
条目的 `license` 字段标注了它自己的授权。
标了 `⚠️非商用` 的（Nemotron 推理、Hammer、xLAM）**商用前必须自行确认**。

下载并使用之前，请自行确认该授权是否适用于你的用途。

> 本清单 83 个条目的理论总体积约 200.5 GB —— 别全下，按「推荐」分类挑一两个就够。

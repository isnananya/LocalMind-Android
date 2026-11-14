# LocalMind 模型候选研究档案（MODEL-CANDIDATES）

> **这是研究档案，不是最终 `models.json`。** 由另一个会话据此撰写模型库插件。
>
> * 调研时点：**2026-10-15**
> * 基线：`app/src/main/assets/model-library/model-library.json`（数组里 34 条，但**有 1 个重复 id，实际 33 个模型**，见 §0.6）——本档案**只收录基线里没有的**。
> * schema 依据：`ModelPack.kt` / `ModelCatalog.kt` / `PLUGIN.md` §6 / `VoicePack.kt` / `PLUGIN.md` §7。

---

## 0. 先读这一节：五条会改变你怎么写 `models.json` 的结论

### 0.1 URL 验证方法（全篇"已验证"都是这个含义）

* **主验证目标 `hf-mirror.com`**（国内可达，项目实测 ~2.8 MB/s）。`huggingface.co` 在本机**不可达**（连接被重置），所以只作为 `mirrors` 里的备用地址，**未独立验证**。
* 方法：`curl -sS -I -L --max-time 40 "https://hf-mirror.com/<repo>/resolve/main/<file>"`。
  * `302 Found → 200 OK` = 文件存在；末尾响应的 `content-length` 就是**精确字节数**（不是估的）。
  * `404 Not Found` = 不存在。
* 交叉核对：`https://hf-mirror.com/api/models/<repo>?blobs=true` 的 `siblings[].size`，两者一致才写进本档案。
* **两级可信度**（本档案里逐条标了出来）：
  * `✅ 302→200` = 我对**这个文件本身**发了 HEAD 并拿到精确字节数 —— **最强**；
  * `✅ API 枚举` = 我从仓库 API 拿到了这个文件的 `rfilename` + `size`，但**没有单独 HEAD 它**（配额限制）。存在性可信，字节数来自仓库元数据；
  * `⚠️` = 存在但许可/支持状态待确认；`🚫` = 明确非商用；`❌` = 确认不可用。
* **不要做的事**：遇到 `Invalid username or password.` / `rate limit` 时**不要**把它当成 404 写进结论 —— 本档案里 `ggml-org/Ovis2-2B-GGUF`、`Green-Sky/SDXL-Turbo-GGUF`、`second-state/LCM_Dreamshaper_v7-GGUF` 就是被这条干扰挡住的，它们**可能真实存在**，只是本轮没验成。
* 语音模型：`https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/tts-models` 的 **assets 列表**（646 个附件，含精确 size），本轮成功拉取。
* **已知干扰**：`hf-mirror.com` 是共享出口配额，高峰期会返回 `{"error":"Invalid username or password."}`（API）或
  `We had to rate limit you, you hit the quota of 100000 resolvers requests per 5 minutes period.`（resolve）。
  **这类响应不等于 404**。本档案里凡标 ✅ 的，都是拿到了 `302→200` 或 assets 元数据的；标 ⚠️ 的说明原因。

> **本次验证结果：57 条 hf-mirror 下载地址全部 `302 Found → 200 OK`，字节数逐一记录；语音侧额外 5 条 GitHub Release 地址 `302→200`（含 `gh-proxy.com` 回退成功）。**

### 0.2 🚨 最重要的发现：时间线是 2026 年，基线清单停留在 2025 年中

本机日期 **2026-10-15**（`hf-mirror.com` 响应头 `Date: Thu, 15 Oct 2026` 亦证实）。现有基线最新的是
Qwen3-4B-Instruct-2507（2025-07）与 FunctionGemma-270M（2025-10）。**2025 年底到 2026 年的三个大版本它完全没有**：

| 时间 | 事件 | 对本项目意味着什么 |
| --- | --- | --- |
| 2026-02-16 ~ 03-02 | **Qwen3.5 全家族开源**（0.8B / 2B / 4B / 9B / 27B / 35B-A3B / 122B-A10B / 397B-A17B），**全部 Apache-2.0**，**全部原生多模态**（文本+图+视频，不再分 VL 分支） | Qwen3 / Qwen2.5 小尺寸整条线被取代；`vision` 与对话可以是**同一个文件** |
| 2026-04-02 | **Gemma 4** 开源（E2B / E4B / 12B / 26B-A4B / 31B） | 小尺寸是 E2B / E4B，**不是** 1B / 4B |
| 2026-09-07 | **MiniCPM5-2B**（OpenBMB），号称 sub-4B 开源第一，明确面向手机/笔记本 | 新的 2B 档中文强候选 |
| 2025-12 ~ 2026-09 | sd.cpp 支持面爆发（Z-Image、FLUX.2-klein、Qwen-Image-2.1、Ideogram4…），作者 **leejet 自己在 HF 上发 GGUF** | 生图类有了官方 zoo，不必只靠第三方 |

### 0.3 🚨 识图（`vision: true`）**当前跑不起来** —— 这是代码事实，不是模型问题

`AppViewModel.kt:2032`：

```kotlin
/** 多头投影（mmproj）支持还没接进 llama.cpp 的 JNI 层，所以**现在返回 false** */
val canSeeImages: Boolean get() = false
```

`ChatScreen.kt:1276` 同义。**并且 `LocalModel` 里只有一个 `fileName` / 一个 `url`，没有任何 mmproj 字段**
（`ModelCatalog.kt:10-42`），下载器也就无法把 mmproj 一起拉下来。

所以第 3 节的所有识图条目应当这样写：

1. **仍然照常填 `vision: true`**（路由槽位判据是 `vision`，PLUGIN.md §6.3）——它决定条目出现在「识图」picker 里；
2. **主 GGUF 的 `description` 里写清楚"还需要 mmproj，且当前 build 还看不见图"**，别让用户以为是模型笨；
3. **不要**把 mmproj 伪装成第二条 `LocalModel`：它没有 `vision` 之外的标记可用，会被路由进**对话**槽位，
   在模型页显示成一个「看起来能聊、其实是个投影器」的条目。**建议先在 `description` 里给出 mmproj 文件名 + URL，
   由用户自行导入**；真正的修法是给 `LocalModel` 加 `mmprojFileName` / `mmprojUrl` 两个字段（本档案第 3 节已给出全部文件名与字节数，随时可用）。

### 0.4 🚨 语音（TTS）只接了 **VITS**

`NeuralTts.kt` 只构造 `vits = OfflineTtsVitsModelConfig(...)`（第 222 行），
布局探测也是「扫目录找最大的 `.onnx`」（第 71-105 行）。

`Tts.kt`（vendored sherpa-onnx v1.13.8）里 **Matcha / Kokoro / ZipVoice / Kitten / Pocket / Supertonic 的配置类都在**，
`.so` 也按字段名反射读它们 —— 但**主程序没写构造它们的分支**。所以：

* 第 5 节把音色分两栏：**「可直接用」（VITS）** 和 **「运行时支持、但要改一处代码」（Matcha/Kokoro/ZipVoice）**；
* Matcha 尤其麻烦：它是 `acoustic-model.onnx + vocoder.onnx` 两个文件，「取最大的 `.onnx`」会**抓错**，
  必须改 `NeuralTts.resolve()`；Kokoro 还需要 `voices.bin` + `lang`。
* **`voices.json` 的 `url` / `mirrors` 字段当前被忽略**（PLUGIN.md §15 第 6 条），下载器只按 `id` 拼官方地址 + `gh-proxy`。
  所以第 5 节所有 `id` **必须与官方 tarball 同名**。

### 0.5 🚨 生图：只能是**单文件 all-in-one GGUF**、且默认 **4 步 / EULER_A**

* 后端调用（`docs/IMAGE_ENGINE.md` §3）只传一个模型路径：
  `new_sd_ctx(p, nullptr, nullptr, nullptr, nullptr, ...)` + `generate_image(..., EULER_A, /*steps*/4, ...)`。
  → **不传 `--vae` / `--clip_l` / `--t5xxl`**，所以 split 布局（FLUX/Qwen-Image 官方那种）**不能用**，
  必须是 UNet+VAE+CLIP 全在一个 `.gguf` 里的转换版本。
* `DiffusionModels.kt:162-171` 用**读 GGUF 头**判断是不是扩散模型，认这些键前缀：
  `vae.` / `cond_stage_model` / `model.diffusion_model` / `clip_l.` / `clip_g.` / `t5xxl.` / `unet.` / `sd.`。
  → 新架构（FLUX.2-klein、Z-Image）能否被认出来**尚未实测**（我尝试用 HTTP Range 抓文件头，被 hf-mirror 的
  配额拦截，见 §0.1），所以我把它们单列并标注**「需要先跑一遍 `DiffusionModels.kind()` 确认」**。
* 4 步 / EULER_A ⇒ **少数步蒸馏模型（SD-Turbo / SDXS / LCM / Lightning / klein / Z-Image-Turbo）才是正解**；
  原版 SD1.5/SDXL 在 4 步下会糊。

### 0.6 顺手发现的一个基线 bug（建议一并修）

`model-library.json` 里 **`deepseek-r1-distill-qwen-1.5b-q4km` 出现了两次**（第 178 行与第 372 行，同 id 同文件）。
`ModelStore.resolveAll()` 用 `distinctBy { it.id }` 去重，所以**第二条被静默丢弃**，两条 `description`/`tags` 不一样，
维护者会以为改了生效其实没有。另外该文件实际是 **33 个条目**（不是 34）。

---

## 1. 对话模型（chat）

**路由写法**：`vision` / `embedding` / `decisionModel` 三个标记**全部省略**（不写 = `false`），`family` 不要写
`"Stable Diffusion"`。这样它才会落进「对话模型」槽位（PLUGIN.md §6.3）。

**选型结论**：≤4B 档，**Qwen3.5 三兄弟（0.8B / 2B / 4B）是新的默认答案** —— Apache-2.0、原生多模态、
混合 GDN 架构（32K 上下文解码快 8.6×）。中文/中文社区生态里，MiniCPM5-2B、Hunyuan 1.8B/4B、ERNIE-4.5-0.3B 是补充。

| id 建议 | 显示名 | repo | fileName | quant | bytes（精确） | license | RAM | URL 已验证 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `qwen35-4b-q4km` | Qwen3.5 4B | `unsloth/Qwen3.5-4B-GGUF` | `Qwen3.5-4B-Q4_K_M.gguf` | Q4_K_M | 2740937888 | **Apache-2.0** | 8 | ✅ 302→200 | ★ 首推。社区公认 4B 档最稳；0.8B/2B/4B/9B 默认**关思考**，`enable_thinking` 控制；**禁止 greedy 解码** |
| `qwen35-4b-ud-q4kxl` | Qwen3.5 4B（UD 动态量化） | `unsloth/Qwen3.5-4B-GGUF` | `Qwen3.5-4B-UD-Q4_K_XL.gguf` | UD-Q4_K_XL | 2912109728 | Apache-2.0 | 8 | ✅ API 枚举（同 repo 另一文件已 HEAD 实测） | 动态量化，同体积质量通常略好；二选一即可 |
| `qwen35-4b-iq4xs` | Qwen3.5 4B（省内存） | `unsloth/Qwen3.5-4B-GGUF` | `Qwen3.5-4B-IQ4_XS.gguf` | IQ4_XS | 2477053088 | Apache-2.0 | 6 | ✅ API 枚举（同 repo 另一文件已 HEAD 实测） | 6GB 机型的 4B 选择 |
| `qwen35-2b-q4km` | Qwen3.5 2B | `unsloth/Qwen3.5-2B-GGUF` | `Qwen3.5-2B-Q4_K_M.gguf` | Q4_K_M | 1280835840 | **Apache-2.0** | 4 | ✅ 302→200 | ★ 均衡首选。对话/摘要够用，6GB 机型满速 |
| `qwen35-08b-q4km` | Qwen3.5 0.8B | `unsloth/Qwen3.5-0.8B-GGUF` | `Qwen3.5-0.8B-Q4_K_M.gguf` | Q4_K_M | 532517120 | **Apache-2.0** | 3 | ✅ 302→200 | ★ 新的「极速」档，取代 Qwen3-0.6B / Qwen2.5-0.5B。约 520MB |
| `minicpm5-2b-q4km` | MiniCPM5 2B | `openbmb/MiniCPM5-2B-GGUF` | `MiniCPM5-2B-Q4_K_M.gguf` | Q4_K_M | 1561318368 | Apache-2.0（HF 标签）⚠️见 §6 | 4 | ✅ 302→200 | 2026-09 发布，OpenBMB 自称 sub-4B 开源第一，**明确面向手机**；中文强 |
| `gemma4-e2b-q4km` | Gemma 4 E2B IT | `unsloth/gemma-4-E2B-it-GGUF` | `gemma-4-E2B-it-Q4_K_M.gguf` | Q4_K_M | 3106738272 | **Gemma Terms**（不是 Apache！） | 8 | ✅ 302→200 | 2026-04 的 Gemma 4「有效 2B」（总参更大，所以 3.1GB）。写作/多语言好；**多模态**，见 §3 |
| `gemma4-e2b-qat-q4kxl` | Gemma 4 E2B IT（QAT） | `unsloth/gemma-4-E2B-it-qat-GGUF` | `gemma-4-E2B-it-qat-UD-Q4_K_XL.gguf` | UD-Q4_K_XL | 2620370976 | Gemma Terms | 6 | ✅ 302→200 | 量化感知训练的权重，**同档质量更高、体积更小**；E2B 首选这个 |
| `gemma4-e4b-q4km` | Gemma 4 E4B IT | `unsloth/gemma-4-E4B-it-GGUF` | `gemma-4-E4B-it-Q4_K_M.gguf` | Q4_K_M | 4977171584 | **Gemma Terms** | 12 | ✅ 302→200 | 4.6GB，12GB 机型；质量档 |
| `gemma4-e4b-qat-q4kxl` | Gemma 4 E4B IT（QAT） | `unsloth/gemma-4-E4B-it-qat-GGUF` | `gemma-4-E4B-it-qat-UD-Q4_K_XL.gguf` | UD-Q4_K_XL | 4215695776 | Gemma Terms | 12 | ✅ 302→200 | QAT 版 E4B，比上面小 0.7GB |
| `hunyuan-1.8b-instruct-q4km` | 混元 1.8B Instruct | `bartowski/tencent_Hunyuan-1.8B-Instruct-GGUF` | `tencent_Hunyuan-1.8B-Instruct-Q4_K_M.gguf` | Q4_K_M | 1133084864 | **Tencent Hunyuan 社区许可** ⚠️ | 4 | ✅ 302→200 | 2025-08 腾讯，中文语感好；许可是社区许可不是 Apache，见 §6 |
| `hunyuan-4b-instruct-q4km` | 混元 4B Instruct | `bartowski/tencent_Hunyuan-4B-Instruct-GGUF` | `tencent_Hunyuan-4B-Instruct-Q4_K_M.gguf` | Q4_K_M | 2607709696 | **Tencent Hunyuan 社区许可** ⚠️ | 8 | ✅ 302→200 | 中文写作/知识不错；同样注意许可 |
| `ernie45-03b-pt-q4km` | ERNIE 4.5 0.3B | `bartowski/baidu_ERNIE-4.5-0.3B-PT-GGUF` | `baidu_ERNIE-4.5-0.3B-PT-Q4_K_M.gguf` | Q4_K_M | 240645440 | **Apache-2.0** | 2 | ✅ 302→200 | **只有 230MB**，百度文心的 0.36B。中文词感好、启动飞快；适合当「先跑通流程」的默认 |
| `minicpm4-05b-q8` | MiniCPM4 0.5B | `Edge-Quant/MiniCPM4-0.5B-Q8_0-GGUF` | `minicpm4-0.5b-q8_0.gguf` | Q8_0 | 462828864 | **Apache-2.0** | 3 | ✅ 302→200 | OpenBMB 端侧小模型，Q8 全精度保留质量；第三方（Edge-Quant）转换，见 §7 |
| `lfm25-12b-instruct-q4km` | LFM2.5 1.2B Instruct | `mradermacher/LFM2.5-1.2B-Instruct-GGUF` | `LFM2.5-1.2B-Instruct.Q4_K_M.gguf` | Q4_K_M | 730895872 | **LFM Open License** ⚠️ | 3 | ✅ 302→200 | Liquid AI 的非 Transformer（conv+attention 混合），**端侧吞吐极高**；许可有营收门槛，见 §6 |
| `smollm3-3b-q4km` | SmolLM3 3B | `unsloth/SmolLM3-3B-GGUF` | `SmolLM3-3B-Q4_K_M.gguf` | Q4_K_M | 1915306528 | **Apache-2.0** | 6 | ✅ 302→200 | HF 官方，双模式推理（think/no-think）。英文强、中文一般 |
| `granite4-micro-q4km` | Granite 4.0 Micro | `ibm-granite/granite-4.0-micro-GGUF` | `granite-4.0-micro-Q4_K_M.gguf` | Q4_K_M | 2099502528 | **Apache-2.0** | 6 | ✅ 302→200 | IBM，**结构化输出/分类/RAG 特别稳**（决策类也见 §2）；官方 GGUF |
| `qwen3-4b-thinking-2507-q4km` | Qwen3 4B Thinking 2507 | `unsloth/Qwen3-4B-Thinking-2507-GGUF` | `Qwen3-4B-Thinking-2507-Q4_K_M.gguf` | Q4_K_M | 2497281152 | **Apache-2.0** | 8 | ✅ 302→200 | 基线只有 Instruct-2507（非思考）。**这是补上的思考版**，数学/逻辑明显更好 |
| `nemotron-reasoning-qwen-15b-q4km` | Nemotron 推理 1.5B | `bartowski/nvidia_Nemotron-Research-Reasoning-Qwen-1.5B-GGUF` | `nvidia_Nemotron-Research-Reasoning-Qwen-1.5B-Q4_K_M.gguf` | Q4_K_M | 1117321248 | **CC-BY-NC-4.0（非商用）** 🚫 | 4 | ✅ 302→200 | NVIDIA 2025-10 的推理蒸馏，同尺寸很强。**但 HF 标签实为 CC-BY-NC-4.0** —— 见 §6，若要收就明确标注非商用 |
| `phi4-mini-instruct-q4km` | Phi-4-mini Instruct 3.8B | `bartowski/microsoft_Phi-4-mini-instruct-GGUF` | `microsoft_Phi-4-mini-instruct-Q4_K_M.gguf` | Q4_K_M | 2491874688 | **MIT** | 8 | ✅ 302→200 | 128K 上下文，长文档总结；MIT 是最省心的许可之一 |
| `spark-x25-4b-q4km` | Spark-X2.5 4B | `XHToken/Spark-X2.5-4B-GGUF` | `Spark-X2.5-4B-Q4_K_M.gguf` | Q4_K_M | 2600224352 | Apache-2.0（HF 标签）⚠️ | 8 | ✅ 302→200 | **第三方**（XHToken）。仓库 discussion 里明确在 OnePlus 8 Pro / Galaxy S26 上实测过端侧运行 —— 手机上验证过这点有价值，见 §7 |

**被排除的（对话类）**：

* `Qwen/Qwen3.5-4B-GGUF` 等 Qwen 官方 GGUF 仓库 —— 本次查询被配额拦截（`Invalid username or password.`），**未能确认存在**，故不列。用 unsloth 的。
* Qwen3.5-9B / 12B / 27B / 35B-A3B —— Q4 需 6–22GB，**超出「小」的目标**（基线已有 8B/12B 档）。
* `Falcon3-3B-Instruct`（`tiiuae`）—— 有 GGUF（`Falcon3-3B-Instruct-q4_k_m.gguf` = 2005684448），但许可在 HF 上标为 `other`（TII Falcon 许可），中文弱于 Qwen3.5；**需要许可澄清后才建议收**。
* `LFM2-350M` / `LFM2-700M` / `LFM2-1.2B`（`LiquidAI/*-GGUF`）—— 被 LFM2.5-1.2B 取代，不重复收。

---

## 2. 决策模型（`decisionModel: true`）

### 2.1 先说结论：**这个类别很薄，别硬凑**

`LocalModel` 对决策模型的注释是「做判断而不是聊天：选一个选项、回答是/否、给某物打分」。
我按「产出形态」把候选分了四类（这比模型大小重要得多）：

| 形态 | 含义 | 对这个 App 的价值 |
| --- | --- | --- |
| **(a)** 自由文本，自己要解析 | 普通 instruct 模型 | 最差，提示词一抖就崩 |
| **(b)** 受约束标签 / JSON | 守卫模型、function-calling 模型 | 好，解析稳定 |
| **(c)** 对「是/否」或对选项的**概率分布** | 基线里的 `gutsy`（校准概率） | 最好，但**开源里极少** |
| **(d)** 标量分数 | reranker / 打分器 | 好，但架构支持是坑（见下） |

**真实情况**：2026 年开源社区**没有**出现「Jev 形态」的通用小型决策模型。真正存在的只有
**function-calling 调优**、**安全/风险守卫（yes/no + 分类）**、**路由**三类，其余都得拿通用小模型顶上。
基线里的 `gutsy-0.8b`（形态 c）仍然是这一类里唯一真正对口的东西 —— 下面的候选是**补充**，不是替代。

### 2.2 候选

| id 建议 | 显示名 | repo | fileName | quant | bytes | license | 形态 | RAM | URL 已验证 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `qwen3guard-gen-06b-q4km` | Qwen3Guard-Gen 0.6B | `mradermacher/Qwen3Guard-Gen-0.6B-GGUF` | `Qwen3Guard-Gen-0.6B.Q4_K_M.gguf` | Q4_K_M | 484220416 | **Apache-2.0** ✅（上游 `Qwen/Qwen3Guard-Gen-0.6B` 官方仓库标签已核实） | **(b)** 输出 `安全/不安全/有争议` + 分类标签 | 3 | ✅ 302→200 | ★ **这一类里我最推荐的一条**。阿里官方安全分类模型，**上游 Apache-2.0**，GGUF 是第三方（mradermacher）转的。判定「这句话该不该拦 / 这个请求属于哪类」比让通用模型自由发挥稳 |
| `granite-guardian-31-2b-q4km` | Granite Guardian 3.1 2B | `DevQuasar-11/ibm-granite.granite-guardian-3.1-2b-GGUF` | `ibm-granite.granite-guardian-3.1-2b.Q4_K_M.gguf` | Q4_K_M | 1545303040 | **Apache-2.0** ✅（上游 `ibm-granite/granite-guardian-3.1-2b` 标签已核实） | **(b)** 风险 yes/no + 维度标签 | 6 | ✅ 302→200 | ★ 唯一「上游明确 Apache-2.0 + 明确 yes/no 判定」的组合。1.5GB 偏大，但它做的是**校准过的是/否判断**，不是聊天 |
| `hammer21-05b-q4km` | Hammer 2.1 0.5B | `mradermacher/Hammer2.1-0.5b-GGUF` | `Hammer2.1-0.5b.Q4_K_M.gguf` | Q4_K_M | 397544384 | **CC-BY-NC-4.0（非商用）** 🚫 | **(b)** function call JSON | 3 | ✅ 302→200 | MadeAgents 专做 function calling 的 0.5B，**「选哪个工具/哪个选项」正是决策形态**。**但 HF 标签是 CC-BY-NC-4.0** —— 想用必须显著标注非商用（见 §6） |
| `xlam2-1b-fc-r-q4km` | xLAM-2 1B FC-R | `Salesforce/xLAM-2-1b-fc-r-gguf` | `xLAM-2-1B-fc-r-Q4_K_M.gguf` | Q4_K_M | 986048192 | **CC-BY-NC-4.0（非商用）** 🚫 | **(b)** 结构化 tool call | 4 | ✅ 302→200 | **Salesforce 官方 GGUF 仓库**（不是第三方转的），function-calling 专精，质量在这一档里最好之一。**但许可确实是非商用** —— 收录必须显著标注，或干脆不收 |
| `arch-router-15b-q4km` | Arch-Router 1.5B | `mradermacher/Arch-Router-1.5B-GGUF` | `Arch-Router-1.5B.Q4_K_M.gguf` | Q4_K_M | 986048960 | ⚠️ **HF 标 `other`，且基座是 Qwen2.5-1.5B（= Qwen-Research）** | **(b)** 直接吐出被选中的路由名 | 4 | ✅ 302→200 | katanemo 专门训练的**路由模型**：给一句用户话 + 一组候选路由名，输出选哪个。**形态上最贴 `decisionModel` 的语义**。但许可需人工确认（见 §6），否则降级为「仅研究」 |
| `granite4-micro-q4km`（同 §1） | Granite 4.0 Micro | `ibm-granite/granite-4.0-micro-GGUF` | `granite-4.0-micro-Q4_K_M.gguf` | Q4_K_M | 2099502528 | **Apache-2.0** | **(a)/(b)** 通用，但 IBM 文档明确说它擅长分类/结构化输出 | 6 | ✅ 302→200 | **诚实说明**：它**不是**决策专用模型，是「被 IBM 定位为适合分类与结构化输出」的通用 8B→micro 模型。当路由用时靠提示词约束 |
| `ernie45-03b-pt-q4km`（同 §1） | ERNIE 4.5 0.3B | `bartowski/baidu_ERNIE-4.5-0.3B-PT-GGUF` | `baidu_ERNIE-4.5-0.3B-PT-Q4_K_M.gguf` | Q4_K_M | 240645440 | **Apache-2.0** | **(a)** 通用 | 2 | ✅ 302→200 | 230MB、Apache-2.0、中文好 —— 当「快速二次判定」很划算。**同样不是专用决策模型** |

### 2.3 调查了但**不能收**的（这类信息同样重要）

| 模型 | 为什么不能收 |
| --- | --- |
| **Qwen3-Reranker-0.6B / 4B** | reranker 需要**分类头**，llama.cpp 侧支持是后加的且社区 GGUF 转换普遍有问题 —— 上游 discussion #22 明确说「多数社区 GGUF 转换在 llama.cpp 下是坏的」。**本档案不提供未经验证的 reranker GGUF 地址。** 若要做「打分」形态，先确认转换是否可用 |
| **BAAI/bge-reranker-v2-m3** | 同上，且是 XLM-R 编码器架构，`LocalModel` 单文件 GGUF 路径未验证 |
| **meta-llama/Llama-Prompt-Guard-2-86m** | 是 BERT 系编码器，**不产出可用的 llama.cpp GGUF**，且是 gated 仓库 |
| **google/shieldgemma-2b** | 存在 GGUF，但功能与 Qwen3Guard / Granite Guardian 重叠，而许可是 Gemma Terms（比 Apache-2.0 紧）。择优后不重复收 |
| **PatronusAI/glider-3.8B / Prometheus 系** | 许可为研究/非商用，且体积上不划算；无稳定 GGUF |
| **Bespoke-MiniCheck-7B** | 7B，超出「小」的目标 |
| **Hammer2.1-1.5b / 3b / 7b** | 0.5B 已覆盖形态；更大体积在这个用途上不划算（且同为 CC-BY-NC） |

**给写 `models.json` 的人一句话**：这一类**建议最多收 3 条**，且**必须**在 `description` 里写清
「它是守卫/工具调用模型，不是通用决策模型」。宁可少收，也不要把通用小模型包装成「专用决策模型」。

---

## 3. 识图模型（`vision: true`）

**先重读 §0.3**：当前 build `canSeeImages = false`，mmproj 也未接下载。本节提供**主 GGUF + mmproj 两个文件的
精确文件名与字节数**，供两种用法：(1) 写进 `description` 让用户手导；(2) 给 `LocalModel` 加字段后直接用。

**`MMPROJ` 一栏的字节数是精确值**（同方法验证）。`合计` = 主 + mmproj，即真实内存下限。

| id 建议 | 显示名 | repo | 主 GGUF | quant | 主 bytes | mmproj 文件 | mmproj bytes | 合计 | license | RAM | 已验证 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `qwen35-08b-vl-q4km` | Qwen3.5 0.8B（看图） | `unsloth/Qwen3.5-0.8B-GGUF` | `Qwen3.5-0.8B-Q4_K_M.gguf` | Q4_K_M | 532517120 | `mmproj-F16.gguf` | 204987232 | **737504352（~703MB）** | Apache-2.0 | 3 | ✅ 两条都 302→200 | ★ **全网最小的"能看图的中文模型"档**。Qwen3.5 原生多模态，同一个 GGUF 配 mmproj 就能看图 |
| `qwen35-2b-vl-q4km` | Qwen3.5 2B（看图） | `unsloth/Qwen3.5-2B-GGUF` | `Qwen3.5-2B-Q4_K_M.gguf` | Q4_K_M | 1280835840 | `mmproj-F16.gguf` | 668227264 | **1949063104（~1.86GB）** | Apache-2.0 | 6 | ✅ 两条都 302→200 | ★ **识图首选**：1.9GB 总量，中文图表/截图理解好。注意 mmproj 有 668MB，占了 1/3 |
| `qwen35-4b-vl-q4km` | Qwen3.5 4B（看图） | `unsloth/Qwen3.5-4B-GGUF` | `Qwen3.5-4B-Q4_K_M.gguf` | Q4_K_M | 2740937888 | `mmproj-F16.gguf` | 672423616 | **3413361504（~3.26GB）** | Apache-2.0 | 8 | ✅ 两条都 302→200 | 质量档；8GB 机型可跑 |
| `gemma4-e2b-vl-q4km` | Gemma 4 E2B（看图） | `unsloth/gemma-4-E2B-it-GGUF` | `gemma-4-E2B-it-Q4_K_M.gguf` | Q4_K_M | 3106738272 | `mmproj-F16.gguf` | 985654080 | **4092392352（~3.81GB）** | Gemma Terms | 8 | ✅ 两条都 302→200 | mmproj 近 1GB（视觉塔大）。多语言/文档理解强 |
| `gemma4-e4b-vl-q4km` | Gemma 4 E4B（看图） | `unsloth/gemma-4-E4B-it-GGUF` | `gemma-4-E4B-it-Q4_K_M.gguf` | Q4_K_M | 4977171584 | `mmproj-F16.gguf` | 990372672 | **5967544256（~5.56GB）** | Gemma Terms | 12 | ✅ 两条都 302→200 | 12GB 机型 |
| `lfm2vl-450m-q4km` | LFM2-VL 450M（看图） | `mradermacher/LFM2-VL-450M-GGUF` | `LFM2-VL-450M.Q4_K_M.gguf` | Q4_K_M | 229310304 | `LFM2-VL-450M.mmproj-Q8_0.gguf` | 105069664 | **334379968（~319MB）** | **LFM Open License** ⚠️ | 2 | ✅ 两条都 302→200 | ★ **绝对最小**：319MB 就能看图。英文为主，但它能当「图里有没有文字/是不是截图」的**极速预筛**，把重活留给大模型 |
| `smolvlm-500m-q8` | SmolVLM 500M（看图） | `ggml-org/SmolVLM-500M-Instruct-GGUF` | `SmolVLM-500M-Instruct-Q8_0.gguf` | Q8_0 | 436806912 | `mmproj-SmolVLM-500M-Instruct-Q8_0.gguf` | 108783360 | **545590272（~520MB）** | **Apache-2.0** | 3 | ✅ 两条都 302→200 | 基线只有 256M 版；500M 是明显更好的小档。**ggml-org 官方**，llama.cpp 支持最稳 |
| `smolvlm2-500m-video-q8` | SmolVLM2 500M（看图+短视频） | `ggml-org/SmolVLM2-500M-Video-Instruct-GGUF` | `SmolVLM2-500M-Video-Instruct-Q8_0.gguf` | Q8_0 | 436808704 | `mmproj-SmolVLM2-500M-Video-Instruct-Q8_0.gguf` | 108785184 | **545593888（~520MB）** | **Apache-2.0** | 3 | ✅ 两条都 302→200 | 与上条同量级，**额外支持短视频输入**。二选一即可 |
| `lfm2vl-16b-q4` | LFM2-VL 1.6B（看图） | `LiquidAI/LFM2-VL-1.6B-GGUF` | `LFM2-VL-1.6B-Q4_0.gguf` | Q4_0 | 695750048 | `mmproj-LFM2-VL-1.6B-Q8_0.gguf` | 564115648 | **1259865696（~1.20GB）** | **LFM Open License** ⚠️ | 4 | ✅ 两条都 302→200 | Liquid 官方仓库（比 mradermacher 更可信）；1.2GB 档 |
| `internvl35-2b-q4km` | InternVL3.5 2B（看图） | `lmstudio-community/InternVL3_5-2B-GGUF` | `InternVL3_5-2B-Q4_K_M.gguf` | Q4_K_M | 1282435904 | `mmproj-model-f16.gguf` | 636106144 | **1918542048（~1.83GB）** | ⚠️ 仓库无 license 标签；上游 OpenGVLab InternVL3.5 为 Apache-2.0 | 6 | ✅ 两条都 302→200 | 上海 AI Lab，**中文 OCR/图表强**。llama.cpp 明确支持 InternVL3.5。许可需人工确认（见 §6） |
| `qwen3vl-2b-q8` | Qwen3-VL 2B（看图·稳妥档） | `ggml-org/Qwen3-VL-2B-Instruct-GGUF` | `Qwen3-VL-2B-Instruct-Q8_0.gguf` | Q8_0 | 1834427296 | `mmproj-Qwen3-VL-2B-Instruct-Q8_0.gguf` | 445053056 | **2279480352（~2.12GB）** | **Apache-2.0** | 6 | ✅ 两条都 302→200 | ★ **保守推荐**：Qwen3-VL（2025-10）比 Qwen3.5 老一代，但 **ggml-org 官方仓库 + llama.cpp 支持已被大量验证**。若 Qwen3.5 的 mmproj 在新 runtime 上出问题，退这一条 |

**关于 mmproj 选的档位**：unsloth 的 Qwen3.5 仓库只发 `mmproj-BF16 / F16 / F32` 三种（**没有 Q8**），
所以 `F16` 已是最小可用档 —— 别写成 Q8，那个文件不存在。`ggml-org` 与 `LiquidAI` 的仓库才提供 `Q8_0` mmproj。

**被排除的（识图类）**：

| 模型 | 原因 |
| --- | --- |
| **MiniCPM-V 4.5**（`openbmb/MiniCPM-V-4_5-gguf`） | 主 GGUF `ggml-model-Q4_K_M.gguf` = 5026714304 + `mmproj-model-f16.gguf` = 1095113184 → **合计 6.1GB**，且 MiniCPM-V 系列许可带**商用登记条款**。手机不划算 |
| `unsloth/Qwen3.5-VL-2B-Instruct-GGUF` 之类 | **不存在**：Qwen3.5 取消了独立 VL 分支（原生统一多模态），没有 `-VL-` 仓库。猜名会得到 404 |
| **Qwen2.5-VL-3B / 7B** | 基线已有 |
| **MiniCPM-V-2_6 / o-2_6** | 许可带商用登记条款；体积与上面重叠 |
| **Ovis2-2B**（`ggml-org/Ovis2-2B-GGUF`） | 本次查询被配额拦截，**未能确认**，故不提供地址 |
| **moondream2 / Florence-2 / FastVLM-0.5B** | llama.cpp 支持状态不明确或仅 safetensors；**不提供未验证地址** |
| **HunyuanOCR / PaddleOCR-VL-0.9B / DeepSeek-OCR / dots.ocr** | 目前是 vLLM/transformers 路线，**没有可用的 llama.cpp GGUF**。这类 OCR 专精模型很吸引人，但现在接不进来 |

---

## 4. 生图模型（`family` 必须**精确等于** `"Stable Diffusion"`）

**先重读 §0.5**：必须是**单文件 all-in-one GGUF**（UNet+VAE+CLIP 在一个文件里），且默认 **4 步 / EULER_A**。

**手机现实**：这些是 0.65–4.1GB 的权重，加上扩散推理的中间激活（sd.cpp 官方数据：SD1.x 512×512 在
q4_0 下约需 **2.0GB 内存**，开 Flash Attention 后约 **1.5GB**）。所以「文件大小 + 2GB」是内存下限。
**能装进 6GB 手机的是标 ★ 的四条**；3.7GB 以上的一律要 12GB 机型且慢。

### 4.1 少数步 / 蒸馏（**首选，因为 App 默认 4 步**）

| id 建议 | 显示名 | repo | fileName | quant | bytes | 步数 | license | 手机适配 | 已验证 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `sdxs-512-dreamshaper-q8` | SDXS-512-DreamShaper（1 步） | `concedo/sdxs-512-tinySDdistilled-GGUF` | `sdxs-512-tinySDdistilled_Q8_0.gguf` | Q8_0 | 682847200 | **1** | ⚠️ `openrail++`（继承自 SD1.5，见 §6） | ★★★ 6GB 轻松 | ✅ 302→200 | ★ **最小最快**：**651MB**、**1 步**出图。sd.cpp 官方文档点名 SDXS 系列是"incredibly fast"，但**必须 `cfg=1 / steps=1`** —— 需要 App 侧把默认 4 步改成 1 步，或在 `description` 里说明 |
| `dreamshaper7-lcm-q4` | DreamShaper 7 LCM（4 步） | `darkmaniac7/TokForge-DreamShaper-LCM-GGUF-q4` | `dreamshaper-7-lcm-q4_0.gguf` | Q4_0 | 1625041920 | **2–8（4 正好）** | `creativeml-openrail-m`（HF 标签） | ★★★ 6GB | ✅ 302→200 | ★ **和 App 默认 4 步完美对齐**：DreamShaper 7 + LCM-LoRA 融合，通用审美最好。1.5GB |
| `flux2-klein-4b-q4` | FLUX.2-klein 4B | `leejet/FLUX.2-klein-4B-GGUF` | `flux-2-klein-4b-Q4_0.gguf` | Q4_0 | 2460378560 | 蒸馏少数步 | **Apache-2.0** ✅ | ★★ 8GB | ✅ 302→200 | **sd.cpp 作者自己的仓库**，2026 新架构，2.46GB。**Apache-2.0 是这一档里许可最干净的**。⚠️ 架构键名可能不被 `DiffusionModels.isDiffusionKey` 识别，需先实测 |
| `z-image-turbo-q2k` | Z-Image-Turbo（Q2_K） | `leejet/Z-Image-Turbo-GGUF` | `z_image_turbo-Q2_K.gguf` | Q2_K | 2592442304 | Turbo（少数步） | **Apache-2.0** ✅ | ★★ 8GB | ✅ 302→200 | 通义 Z-Image-Turbo 的 2.59GB 极限量化。**Q2 画质有损**，但它是少数能让 6–8GB 手机跑 2026 新架构的选择 |
| `z-image-turbo-q4` | Z-Image-Turbo（Q4_0） | `leejet/Z-Image-Turbo-GGUF` | `z_image_turbo-Q4_0.gguf` | Q4_0 | 3683370944 | Turbo | **Apache-2.0** ✅ | ★ 12GB | ✅ 302→200 | 3.68GB，画质档。同样需实测键名 |
| `sdxl-lightning-4step-q4` | SDXL-Lightning（4 步） | `mzwing/SDXL-Lightning-GGUF` | `sdxl_lightning_4step.q4_0.gguf` | Q4_0 | 2584858432 | **4** | ⚠️ ByteDance SDXL-Lightning 许可（`openrail++` 系） | ★★ 8GB | ✅ 302→200 | ★ **4 步 SDXL**，2.58GB，1024×1024 质量比 SD1.5 档高一截。同 repo 还有 `1step_x0` / `2step` 同名规格（各 2584858432） |
| `qwen-image-21-q2k` | Qwen-Image-2.1（Q2_K） | `leejet/Qwen-Image-2.1-GGUF` | `qwen_image_2.1-Q2_K.gguf` | Q2_K | 2561716256 | 少数步 | ⚠️ 仓库无 license 标签（上游 Qwen-Image 系列为 Apache-2.0，**需确认**） | ★★ 8GB | ✅ 302→200 | 2.56GB。**中文提示词理解是它的强项**（通义系），对中文用户价值高。许可需确认 |

### 4.2 经典 SD1.5 / SD2.1 / SDXL（步数多，4 步下会糊 —— 但提示词服从性最好）

| id 建议 | 显示名 | repo | fileName | quant | bytes | license | 手机适配 | 已验证 | 备注 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `sd15-emaonly-q4` | Stable Diffusion 1.5（Q4_0） | `second-state/stable-diffusion-v1-5-GGUF` | `stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf` | Q4_0 | 1566768416 | **CreativeML OpenRAIL-M** ✅ | ★★★ 6GB | ✅ 302→200 | ★ **SD1.5 的标准答案**，1.46GB，单文件含 VAE+CLIP。生态（LoRA/ControlNet）最好。**基线里没有 SD1.5，这是补缺** |
| `sd15-q4-gpustack` | SD1.5（gpustack 版） | `gpustack/stable-diffusion-v1-5-GGUF` | `stable-diffusion-v1-5-Q4_0.gguf` | Q4_0 | 1747190784 | **CreativeML OpenRAIL-M** ✅ | ★★★ 6GB | ✅ 302→200 | 另一家转换。与上条二选一；gpustack 版本另有 Q4_1/Q8_0 |
| `sd21-768-q8` | SD 2.1 768（Q8_0） | `second-state/stable-diffusion-2-1-GGUF` | `v2-1_768-nonema-pruned-Q8_0.gguf` | Q8_0 | 2014680768 | **CreativeML OpenRAIL++-M** ✅ | ★★ 8GB | ✅ 302→200 | 基线只有 Q4_0；**Q8_0 画质明显更好**，2.0GB。是「同一模型补一个高质量量化」的典型 |
| `sdxl-base-10-q4` | SDXL Base 1.0（Q4_0） | `gpustack/stable-diffusion-xl-base-1.0-GGUF` | `stable-diffusion-xl-base-1.0-Q4_0.gguf` | Q4_0 | 3940010720 | **CreativeML OpenRAIL++-M** ✅ | ⚠️ 12GB，勉强 | ✅ 302→200 | 3.67GB。**基线里没有 SDXL**。1024×1024 base 质量好，但**大多数手机装不下**，明确标注 |
| `sdxl-turbo-10-q8` | SDXL-Turbo 1.0（Q8_0） | `OlegSkutte/sdxl-turbo-GGUF` | `sd_xl_turbo_1.0.q8_0.gguf` | Q8_0 | 4098988672 | ⚠️ **Stability AI Community License**（不是纯非商用，见 §6） | ⚠️ 12GB，勉强 | ✅ 302→200 | 4.1GB，1–4 步 SDXL。**基线只有 SD-Turbo（0.9B），没有 SDXL-Turbo**。手机上很吃紧 |
| `z-image-q4` | Z-Image（非 Turbo，Q4_0） | `leejet/Z-Image-GGUF` | `z_image-Q4_0.gguf` | Q4_0 | 3838500672 | **Apache-2.0** ✅ | ★ 12GB | ✅ 302→200 | 3.84GB。比 Turbo 质量高但要更多步，**与 App 默认 4 步不匹配**，仅作质量档 |

### 4.3 明确标注「装不下 / 不要收」的

| 模型 | 问题 |
| --- | --- |
| `leejet/FLUX.1-schnell-gguf` → `flux1-schnell-q2_k.gguf` | **4.11GB**（Q2 已是极限），q4 要 6.88GB、q8 要 12.8GB。**不收**，仅记录 |
| `leejet/FLUX.1-dev-gguf` | FLUX.1-dev 是**非商用**许可，且体积 4GB+。**不收**（除非用户明确接受非商用） |
| `leejet/MiniMax-H3-GGUF` | 最小 6.72GB（Q2_K_M），Q4 要 11–18GB。**不可能上手机** |
| `leejet/Ovis-Image-7B-GGUF` | 4.2GB（Q4_0）起，7B。**勉强但性价比低** |
| `leejet/ideogram-4-GGUF` | 5.64GB 起。**不收** |
| `leejet/FLUX.2-klein-9B-GGUF` / `FLUX.2-klein-base-9B-GGUF` | 9B，明显超手机 |
| `nota-ai/bk-sdm-tiny`、`segmind/tiny-sd` | 只有 diffusers/safetensors，**需要 Python 转换脚本**才能给 sd.cpp 用（sd.cpp `docs/distilled_sd.md` 明确写了要跑转换）。**没有可用 GGUF，不收** |
| `Green-Sky/SDXL-Turbo-GGUF` | 本次查询被配额拦截，**未确认**。要 SDXL-Turbo 用上面 `OlegSkutte` 那条 |
| 社区动漫 checkpoints（Anything / Counterfeit / MeinaMix / ChilloutMix / 国风 等） | **未找到可确认的 sd.cpp 兼容 GGUF**。多数只有 safetensors，且**许可普遍不明或未声明**。**本档案不提供未验证地址** —— 见 §7 |

**生图类「最小可用」排序（给 6GB 手机）**：
1. `sdxs-512-tinySDdistilled_Q8_0.gguf` — 651MB，1 步
2. `dreamshaper-7-lcm-q4_0.gguf` — 1.51GB，4 步
3. `stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf` — 1.46GB
4. `v2-1_768-nonema-pruned-Q8_0.gguf` — 1.88GB

---

## 5. 语音模型（`voices.json`）

**方法**：`https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/tts-models` 的 assets 列表
（**646 个附件，含精确 size**）；并对代表性 tarball 做了 `curl -I -L` 实测。

**核对结果**：实测 `content-length` 与 assets 元数据**逐字节一致**（例：`vits-piper-zh_CN-chaowen-medium-int8`
= 14011298 两处相符）。**`gh-proxy.com` 前缀回退也验证通过**（`https://gh-proxy.com/https://github.com/...`
直接返回 `200 OK` + 同样字节数），所以 PLUGIN.md §7.3 说的回退链路是真实可用的。
**404 也验证过**（故意查一个不存在的 id，返回 `404`），说明这套判据能区分「不存在」和「网络问题」。

现有 8 条（`vits-piper-zh_CN-xiao_ya-medium-int8`、`vits-icefall-zh-aishell3`、`vits-zh-hf-theresa/eula/keqing/zenyatta/bronya`、`vits-zh-hf-fanchen-C`）**不重复收录**。

### 5.1 ✅ 可直接用（**VITS** —— `NeuralTts.kt` 已接线，`id` 即官方 tarball 名）

| id（= tarball 名） | 建议 label | bytes（release 元数据） | 许可 | 备注 |
| --- | --- | --- | --- | --- |
| `vits-piper-zh_CN-chaowen-medium-int8` | 超文（男声 · 普通话） | **14011298** | Apache-2.0（Piper 系） | ★ **14MB 男声**，与现有 14MB 小雅正好配成男女一对。✅ URL 实测 302→200 / 14011298 |
| `vits-piper-zh_CN-chaowen-medium-fp16` | 超文（男声 · fp16） | 29149650 | Apache-2.0 | 29MB，音质比 int8 好一档 |
| `vits-piper-zh_CN-chaowen-medium` | 超文（男声 · 全精度） | 60443846 | Apache-2.0 | 60MB |
| `vits-piper-zh_CN-huayan-medium` | 华研（女声 · 普通话） | 67255926 | Apache-2.0 | ★ Piper 另一个中文女声，**基线与现有小雅不同的音色**。67MB |
| `vits-piper-zh_CN-xiao_ya-medium-fp16` | 小雅（女声 · fp16） | 29151028 | Apache-2.0 | 现有 int8 版的高音质版 |
| `vits-piper-zh_CN-xiao_ya-medium` | 小雅（女声 · 全精度） | 60462944 | Apache-2.0 | 现有 int8 版的全精度版 |
| `vits-zh-aishell3` | aishell3 多说话人（大） | 146922607 | Apache-2.0（AISHELL-3 学术数据集训练） | ★ **多说话人**。注意与现有 `vits-icefall-zh-aishell3`（31MB）**不是同一个**：这个是 147MB 的另一个转换，说话人更多 |
| `vits-melo-tts-zh_en` | MeloTTS 中英混读 | 167006755 | **MIT**（MeloTTS 上游） | ★ **中英混读**是它的卖点：正文里夹英文单词不用切语言。167MB。✅ URL 实测 302→200 / 167006755 |
| `sherpa-onnx-vits-zh-ll` | 中文 LL-VITS | 118810709 | Apache-2.0 | 带中文 lexicon 的 VITS，数字/多音字正则更全 |
| `vits-zh-hf-fanchen-unity` | 泛尘 Unity（多说话人） | 119143475 | ⚠️ 社区模型，**个人/非商用** | 现有 `fanchen-C` 的姊妹版，**多说话人** |
| `vits-zh-hf-fanchen-wnj` | 泛尘 WNJ | 119159130 | ⚠️ 个人/非商用 | 同系列不同音色 |
| `vits-zh-hf-fanchen-ZhiHuiLaoZhe_new` | 智慧老者（新） | 119402911 | ⚠️ 个人/非商用 | **老年音色**，角色扮演用 |
| `vits-zh-hf-abyssinvoker` | 深渊唤者（角色） | 120579632 | ⚠️ 个人/非商用 | 游戏角色音，低沉 |
| `vits-zh-hf-doom` | Doom（角色） | 120556412 | ⚠️ 个人/非商用 | 角色音 |
| `vits-zh-hf-echo` | Echo（角色） | 120559956 | ⚠️ 个人/非商用 | 角色音 |

**情绪/多说话人补充说明（诚实版）**：sherpa-onnx 的 VITS 中文音色里**没有专门的"情感标签"接口** ——
`OfflineTtsVitsModelConfig` 只有 `noiseScale / noiseScaleW / lengthScale`，
`GenerationConfig` 也只有 `speed / sid`。所以：
* **"多说话人"** 靠 `sid`（`vits-zh-aishell3`、`vits-zh-hf-fanchen-unity`、现有 `vits-icefall-zh-aishell3`、`vits-zh-hf-bronya`）；
* **"情感"** 只能靠**选不同音色** + `speed`（NeuralTts 已用重采样实现变调，见其注释）近似，
  或在 `VoiceEntry.description` 里写清"情感丰富"这类**主观描述**，不要写成"支持情感控制"。
* **`sampleText` 要填**：音色差别在短句上听不出来（这是 `VoiceEntry` 字段注释自己说的）。

### 5.2 ⚠️ 运行时 `.so` 支持，但**主程序还没接线**（改一处代码才能用）

`Tts.kt` 里有这些配置类，但 `NeuralTts.kt` 只构造 `vits`。要用必须改 `NeuralTtsEngine.load()` 增加分支，
并且（Matcha/Kokoro）**必须同时改布局探测** —— 现在「取最大的 `.onnx`」会抓错文件。

| id | 建议 label | bytes | 为什么现在不能用 | 备注 |
| --- | --- | --- | --- | --- |
| `matcha-icefall-zh-baker` | Matcha 中文（Baker） | 75480421 | **Matcha 家族**：`acoustic-model.onnx` + `vocoder.onnx` **两个文件**，「取最大的 .onnx」会抓错；且需构造 `matcha = ...` | ★ **速度快**（flow matching，比 VITS 快数倍），中文质量好。75MB。✅ URL 实测 302→200 / 75480421 |
| `matcha-icefall-zh-en` | Matcha 中英 | 79033838 | 同上 | 中英混读 + 快 |
| `kokoro-int8-multi-lang-v1_1` | Kokoro 多语言 int8 | 147031220 | **Kokoro 家族**：需 `model` + `voices` + `lang`，`OfflineTtsKokoroModelConfig` 独有 `voices`/`lang` 字段 | ★ **Kokoro 是这几个里音质口碑最好的**，v1.1 支持中文。147MB。✅ URL 实测 302→200 / 147031220 |
| `sherpa-onnx-zipvoice-distill-int8-zh-en-emilia` | ZipVoice 蒸馏（中英·零样本克隆） | 109162785 | **ZipVoice 家族**：需 `encoder` + `decoder` + `vocoder` 三个文件 | ★ **支持零样本音色克隆**（给一段参考音频）。109MB。注意**未蒸馏版要 635MB / 478MB**，只收 int8 |
| `sherpa-onnx-pocket-tts-int8-2026-01-26` | Pocket TTS int8 | 98336520 | **Pocket 家族**：需 `lmFlow`/`lmMain`/`encoder`/`decoder`/`textConditioner` + 两个 json | ❌ **英文专用**（官方示例文件名就是 `pocket-tts-en-c-api.c`）。**中文项目不必收** |
| `sherpa-onnx-supertonic-3-tts-int8-2026-05-11` | Supertonic 3 int8 | 128774318 | **Supertonic 家族**：需 7 个组件（`durationPredictor`/`textEncoder`/`vectorEstimator`/`vocoder`/`ttsJson`/`unicodeIndexer`/`voiceStyle`） | ⚠️ **语言支持未能确认**（同名 2026-03-06 版为 84692981）。**在确认支持中文之前不要收** |
| `kitten-nano-en-v0_8-int8` | Kitten Nano（英文） | 31220690 | **Kitten 家族**：需 `model` + `voices` | ❌ 英文专用，不相关 |

**给写 `voices.json` 的人**：`id` **必须是官方 tarball 名**（去掉 `.tar.bz2`），因为
`url`/`mirrors` 当前**被忽略**，下载器只按 `id` 拼 `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/<id>.tar.bz2`。
`sizeBytes` 填上表里的精确值（有值时进度条才准）；`0` 也合法但进度只能按已下载量显示。
`badge` 建议：`"轻量"`（<30MB）、`"角色"`（HF 角色音）、`"多说话人"`、`"中英混读"`。

### 5.3 云端音色（`deployment: CLOUD`）

任务里提到 `VoiceEntry` 的云端分支（`cloudVoiceId` / `provider`）。**本轮没有调研云端音色** ——
原因：这个项目的整体取向是「完全离线、文字不出设备」（`VoiceDeployment` 的 KDoc 明确写了云端音色"隐私性质完全不同"），
而且**密钥属于用户、由用户在设置里填**，插件只声明"有这么个音色"。
如果确实要加，`provider` 建议用稳定小写 id（`tencent` / `openai` / `volcengine`），
`cloudVoiceId` 用提供方的原始 id（如腾讯云 `101001`），**不要**把任何 key 写进 JSON。

---

## 6. 许可（`license` 字段）—— 逐条说明，错不起

`license` 会**显示给用户**（AboutScreen 「模型权重许可」一节 + 模型详情），所以下表按「能不能商用」分层。
**⚠️ 标记 = 有使用限制，🚫 = 明确非商用，必须在 `description` 里也写一句。**

### 6.1 干净、可放心标（Apache-2.0 / MIT）

| 模型 | 标什么 |
| --- | --- |
| Qwen3.5 全家族（0.8B/2B/4B/9B…） | `Apache-2.0` —— 阿里官方明确，**这一代没有 Qwen-Research 那种限制** |
| MiniCPM5-2B / MiniCPM4-0.5B | `Apache-2.0`（HF 标签如此）⚠️ OpenBMB 历代许可写法不完全一致，**若你的用途很敏感，再去上游 README 复核一次** |
| ERNIE-4.5-0.3B-PT | `Apache-2.0` |
| Granite 4.0 Micro / Granite Guardian 3.1 2B | `Apache-2.0`（`ibm-granite/*` 官方仓库标签已核实） |
| SmolLM3-3B | `Apache-2.0` |
| Phi-4-mini-instruct | `MIT` |
| SDXS-512 系 | ⚠️ **HF 标签是 `openrail++`**（从 SD1.5 继承），但 SDXS 作者 IDKiro 的原始许可另有说法。**标 `CreativeML OpenRAIL++-M`（继承）**，并在 `description` 指出继承关系 |
| SD 1.5 各 GGUF | `CreativeML OpenRAIL-M`（`second-state` / `gpustack` 仓库标签均已核实） |
| SD 2.1 各 GGUF | `CreativeML OpenRAIL++-M` |
| SDXL base 1.0 | `CreativeML OpenRAIL++-M` |
| Z-Image / Z-Image-Turbo（leejet GGUF） | `Apache-2.0`（leejet 仓库标签已核实；上游通义 Z-Image 亦为 Apache-2.0） |
| FLUX.2-klein-4B / klein-base-4B | `Apache-2.0`（leejet 仓库标签已核实，与 FLUX.1-dev 的非商用形成对比 —— **klein 是宽松那一支**） |
| Qwen3Guard-Gen-0.6B | `Apache-2.0`（`Qwen/Qwen3Guard-Gen-0.6B` 官方标签已核实；GGUF 为第三方转换，见 §7） |
| sherpa-onnx Piper 中文音色 / `vits-zh-aishell3` / `sherpa-onnx-vits-zh-ll` | `Apache-2.0` |
| `vits-melo-tts-zh_en` | `MIT`（MeloTTS 上游） |

### 6.2 ⚠️ 有使用限制，**必须如实标注**

| 模型 | 真实许可 | 限制要点 |
| --- | --- | --- |
| **Gemma 4 E2B / E4B**（含 QAT） | **`Gemma Terms of Use`**（**不是 Apache-2.0！**） | 🚨 **重要陷阱**：`unsloth/gemma-4-*-GGUF` 的 HF `tags` 里写的是 `license:apache-2.0`，**但基座模型是 Google Gemma，受 Gemma Terms 约束**。unsloth 的标签是转换仓库的元数据，不能当成权重许可。**必须标 `Gemma`**，与基线里 `gemma3-1b-q4km` / `gemma2-2b-it-q4km` 的写法保持一致 |
| **Hunyuan 1.8B / 4B Instruct** | **`Tencent Hunyuan Community License`** | 社区许可（`tencent/Hunyuan-4B-Instruct` 仓库有独立 LICENSE）。通常允许商用但带条件（体量门槛、署名、再分发条款）。**不要标 Apache-2.0** |
| **LFM2.5-1.2B / LFM2-VL 450M / LFM2-VL 1.6B** | **`LFM Open License v1.0`**（HF 显示 `other`） | Liquid AI 自有许可：一般允许商用**但有营收门槛**，超过门槛需另行授权。标 `LFM Open License`，并在 `description` 提示"商用前请阅读条款" |
| **Arch-Router-1.5B** | ⚠️ **不明**：HF 标 `other`，但其基座是 **Qwen2.5-1.5B-Instruct（= `Qwen-Research`，非商用研究许可）** | 路由模型本身虽由 katanemo 发布，**继承基座许可的风险很高**。**在人工确认之前，标 `Qwen-Research（继承，待确认）` 或干脆不收** |
| **InternVL3.5-2B**（`lmstudio-community` 转换） | ⚠️ **转换仓库没有 license 标签** | 上游 `OpenGVLab/InternVL3_5-2B` 是 Apache-2.0，但**转换仓库没声明** —— 标 `Apache-2.0（上游）/ 转换仓库未声明` |
| **SDXL-Lightning** | ⚠️ ByteDance 自有许可（`openrail++` 系，仓库有 `LICENSE.md`） | 不是 Apache。标 `SDXL-Lightning License`，保守表述 |
| **SDXL-Turbo 1.0**（`OlegSkutte` 转换） | **`Stability AI Community License`** | 与基线 `sd-turbo-q8` 同类：**非商用免费；年营收 <100 万美元可商用，超过需企业授权**。所以**既不能标"非商用"，也不能标 `Apache-2.0`** —— 标 `Stability AI Community`（基线的写法是对的） |
| **Qwen-Image-2.1** | ⚠️ **leejet 仓库无 license 标签** | 上游 Qwen-Image 系列为 Apache-2.0，但 **2.1 的许可本轮未确认**。**先复核再收**，或标 `Apache-2.0（待确认）` |
| **Spark-X2.5-4B**（`XHToken`） | ⚠️ **第三方**，HF 标签 `apache-2.0`，但基座/来源未在 README 说明 | 标 `Apache-2.0（第三方声明，未复核基座）` |

### 6.3 🚫 明确非商用 / 研究限定（**要么不收，要么显著标注**）

| 模型 | 许可 | 建议 |
| --- | --- | --- |
| **xLAM-2-1b-fc-r**（`Salesforce/xLAM-2-1b-fc-r-gguf`） | **`CC-BY-NC-4.0`**（官方 GGUF 仓库标签） | 🚫 **非商用**。这是本轮唯一「官方自己发的 GGUF + 明确非商用」。若收，`license` 必须写 `CC-BY-NC-4.0（非商用）` |
| **Hammer2.1-0.5b**（`mradermacher/Hammer2.1-0.5b-GGUF`） | **`CC-BY-NC-4.0`** | 🚫 同上。**注意**：Hammer 基座常用 Qwen2.5-0.5B（Apache-2.0），但转换仓库标了 NC —— **以仓库声明为准，标 NC** |
| **Nemotron-Research-Reasoning-Qwen-1.5B**（`bartowski/...`） | **`CC-BY-NC-4.0`** | 🚫 名字里的 "Research" 已经在提示。**若收必须显著标注非商用** |
| **`vits-zh-hf-*` 角色音色**（theresa / eula / keqing / zenyatta / bronya / fanchen 系列 / abyssinvoker / doom / echo） | ⚠️ **社区训练的角色音色** | AboutScreen 已经写「个人 / 非商用」；`voices.json` 的包级 `license` 也已写明。**逐条 `description` 里不必重复，但包级声明必须保留**。新增的 `fanchen-unity` / `fanchen-wnj` / `ZhiHuiLaoZhe_new` / `abyssinvoker` / `doom` / `echo` 属同一类 |
| **FLUX.1-dev**（`leejet/FLUX.1-dev-gguf`） | **非商用**（BFL FLUX.1-dev Non-Commercial License） | 🚫 **不收**（体积也超） |

### 6.4 「不明 / 未声明」清单（**不要猜，照实写**）

* `lmstudio-community/InternVL3_5-2B-GGUF` —— 仓库无 license 标签
* `mzwing/SDXL-Lightning-GGUF` —— 仓库无 license 标签（上游 ByteDance 另有 LICENSE.md）
* `leejet/Qwen-Image-2.1-GGUF`、`leejet/ideogram-4-GGUF`、`leejet/MiniMax-H3-GGUF` —— 仓库无 license 标签
* `bartowski/tencent_Hunyuan-*-GGUF`、`mradermacher/*-GGUF` —— 转换仓库普遍不带 license 标签，**以上游为准**
* `tensorblock/katanemo_Arch-Router-1.5B-GGUF` —— 同

> 原则：**转换者（quantiser）不是许可方**。`bartowski` / `mradermacher` / `unsloth` / `lmstudio-community` /
> `DevQuasar-11` / `Edge-Quant` 的仓库标签只是元数据，**真正的许可以在上游模型仓库**。
> 基线的写法是对的：`license` 填上游许可名（`Apache-2.0` / `Gemma` / `Llama-3.2-Community` / `Qwen-Research`）。

---

## 7. 第三方 / 非官方来源

维护者明确欢迎第三方内容（"可以用三方库和非官方内容"、"用户自行上传开源和挖出来的模型都行"）。
本节把**已验证可下载**和**仅记录思路**分开，避免把没验证过的东西写进 `models.json`。

### 7.1 ✅ 已验证的第三方 / 非官方 repack

| 类别 | 仓库 | 说明 |
| --- | --- | --- |
| 量化转发布（最主流） | `bartowski/*-GGUF`、`mradermacher/*-GGUF`、`unsloth/*-GGUF`、`DevQuasar-11/*`、`Edge-Quant/*`、`lmstudio-community/*`、`XHToken/*` | 本档案里绝大多数条目走的就是这一类。**它们质量可靠、更新及时，但许可一律继承上游**（见 §6.4） |
| unsloth 的 **UD 动态量化** | `unsloth/Qwen3.5-*-GGUF` 的 `UD-Q4_K_XL` | 同体积通常优于标准 `Q4_K_M`。已在 §1 给出确切字节数 |
| **QAT（量化感知训练）权重** | `unsloth/gemma-4-E2B-it-qat-GGUF`、`unsloth/gemma-4-E4B-it-qat-GGUF` | Google 用 QAT 训过的权重再转 GGUF，**低比特下质量明显好于事后量化**。这是「同档更优」的合法来源，值得优先 |
| **Qwen3.5 微调/蒸馏**（社区） | `keypa/Qwen3.5-9B-Claude-Opus-4.7-GGUF`、`reaperdoesntknow/Qwen3.5-0.8-Cyber-GGUF`、`leeminwaan/qwen3.5_4B_...GGUF` | 存在且是社区 finetune。**本轮未逐一验证 URL**，故不给地址。要收的话按 §0.1 的方法自己验一遍 |
| **abliterated / uncensored** | `mradermacher/Qwen3Guard-Gen-0.6B-heretic-i1-GGUF`（存在）、`saidutta69/Qwen3-VL-2B-Instruct-heretic` | ⚠️ **我没有验证这些具体文件的 URL**，只确认仓库存在。**abliterated 变体的许可通常是"继承上游 + 未声明"** —— 而且**去审查化会让模型输出不可控**，用于「决策/守卫」是反效果的（守卫模型被 abliterate 之后就守不住了）。**建议单独做一个包、不混进主清单** |
| **本地小模型集合** | `XHToken/Spark-X2.5-4B-GGUF` | 已在 §1 收录（§7.1 类） |

### 7.2 ⚠️ 存在但我无法确认的（**不要直接写进 `models.json`**）

* **社区动漫生图 checkpoints 的 GGUF**：`Anything` / `Counterfeit` / `MeinaMix` / `ChilloutMix` / `国风3` / `GhostMix` / `AWPainting` 等。sd.cpp 的 `docs/distilled_sd.md` 只点名了 `segmind/SSD-1B`、`segmind/Segmind-Vega`、`concedo/sdxs-512-tinySDdistilled-GGUF`、`akleine/sdxs-512` 等，**没有列动漫 checkpoint**。我**没有找到**这些模型的 sd.cpp 兼容 GGUF 直链。→ **这一片是空白，需要单独调研**。
* **`gpustack/*-GGUF` 的其他生图模型**：`stable-diffusion-v1-5-GGUF` 与 `stable-diffusion-xl-base-1.0-GGUF` 已验证；
  其余（如 SD2.1、SD-Turbo 的 gpustack 版）本轮未查。
* **`leejet` 的 sd.cpp zoo 完整列表**：我已枚举 `leejet` 名下全部 12 个仓库（`MiniMax-H3`、`FLUX.1-dev`、
  `FLUX.1-schnell`、`Z-Image-Turbo`、`Ovis-Image-7B`、`FLUX.2-klein-4B`、`FLUX.2-klein-base-4B`、
  `FLUX.2-klein-9B`、`FLUX.2-klein-base-9B`、`Z-Image`、`ideogram-4`、`Qwen-Image-2.1`），
  **sd.cpp 现在已经没有 `docs/model_zoo.md` 了**（拆成了 `docs/sd.md` / `distilled_sd.md` / `flux.md` / `taichi` 等单篇），
  所以"官方 zoo"= **leejet 自己的 HF 仓库**。上表已覆盖其中够小的。
* **`mradermacher/Arch-Router-1.5B-GGUF` 之外的 Arch-Router 转换**：`tensorblock/katanemo_Arch-Router-1.5B-GGUF`
  只有 `Q2_K`（676304992）和 `Q3_K_M`（824178784），体积更小但质量更差。

### 7.3 三条给第三方条目的实操建议

1. **`publisher` 要写清是转换者还是原作者**。基线里 `repoId` 是 `bartowski/...` 但 `publisher` 写 `Alibaba Qwen` ——
   这是**对的**（publisher 指模型作者），建议保持；转换者信息放进 `description` 或 `tags`（如 `"第三方量化"`）。
2. **`tags` 里加一个 `"第三方"`**，让用户能筛出来。PLUGIN.md §6.5 的分类表里没有这个分类，
   但 `tags` 会展示，不影响现有筛选逻辑。
3. **第三方条目尤其要写 `sizeBytes`**：下载完 `大小不符` 是硬失败（PLUGIN.md §6.4）。
   本档案所有字节数都是 `content-length` 实测值，可直接用。

---

## 8. 附录：可直接粘贴的骨架

> **只填了本轮验证过的字段。** `packId` / `packName` / `author` 按你的插件填。
> `imported` / `packId` 两条**不要填**（PLUGIN.md §6.2：装插件时会被改写）。
> 完整条目请照 §1–§4 的表格逐列补齐 `description` / `tags` / `publisher`。

```json
{
  "version": 1,
  "packId": "你的插件id",
  "packName": "模型库（2026-10 增补）",
  "author": "你",
  "updatedAt": "2026-10-15",
  "note": "本条只收录 2026 年新模型；请与基线 model-library.json 合并。所有 sizeBytes 为 hf-mirror Content-Length 实测值。",
  "models": [
    {
      "id": "qwen35-2b-q4km",
      "name": "Qwen3.5 2B",
      "repoId": "unsloth/Qwen3.5-2B-GGUF",
      "fileName": "Qwen3.5-2B-Q4_K_M.gguf",
      "sizeBytes": 1280835840,
      "url": "https://hf-mirror.com/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
      "mirrors": ["https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf"],
      "license": "Apache-2.0",
      "publisher": "Alibaba Qwen",
      "family": "Qwen3.5",
      "params": "2B",
      "quant": "Q4_K_M",
      "description": "2026-03 阿里 Qwen3.5，原生多模态、Apache-2.0。2B 档对话与摘要够用，6GB 手机满速。默认关闭思考模式。",
      "tags": ["推荐", "中文", "均衡"],
      "recommendedRamGb": 4
    },
    {
      "id": "qwen35-08b-vl-q4km",
      "name": "Qwen3.5 0.8B（看图）",
      "repoId": "unsloth/Qwen3.5-0.8B-GGUF",
      "fileName": "Qwen3.5-0.8B-Q4_K_M.gguf",
      "sizeBytes": 532517120,
      "url": "https://hf-mirror.com/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
      "mirrors": ["https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"],
      "license": "Apache-2.0",
      "publisher": "Alibaba Qwen",
      "family": "Qwen3.5",
      "params": "0.8B",
      "quant": "Q4_K_M",
      "description": "最小的中文多模态档（约 520MB）。看图还需配套 mmproj：unsloth/Qwen3.5-0.8B-GGUF 的 mmproj-F16.gguf（204987232 字节，https://hf-mirror.com/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/mmproj-F16.gguf）。注意：当前 build 的多头投影（mmproj）还没接进 JNI，暂时还看不见图片。",
      "tags": ["识图", "多模态", "中文", "轻量"],
      "recommendedRamGb": 3,
      "vision": true
    },
    {
      "id": "qwen3guard-gen-06b-q4km",
      "name": "Qwen3Guard-Gen 0.6B（判定/守卫）",
      "repoId": "mradermacher/Qwen3Guard-Gen-0.6B-GGUF",
      "fileName": "Qwen3Guard-Gen-0.6B.Q4_K_M.gguf",
      "sizeBytes": 484220416,
      "url": "https://hf-mirror.com/mradermacher/Qwen3Guard-Gen-0.6B-GGUF/resolve/main/Qwen3Guard-Gen-0.6B.Q4_K_M.gguf",
      "mirrors": ["https://huggingface.co/mradermacher/Qwen3Guard-Gen-0.6B-GGUF/resolve/main/Qwen3Guard-Gen-0.6B.Q4_K_M.gguf"],
      "license": "Apache-2.0",
      "publisher": "Alibaba Qwen",
      "family": "Qwen3Guard",
      "params": "0.6B",
      "quant": "Q4_K_M",
      "description": "阿里官方安全分类模型（上游 Qwen/Qwen3Guard-Gen-0.6B 为 Apache-2.0），GGUF 由第三方转换。输出「安全/不安全/有争议」加分类标签，适合做"该不该拦/属于哪类"的判定。它是守卫模型，不是通用对话模型。",
      "tags": ["决策模型", "分类", "非商用风险低"],
      "recommendedRamGb": 3,
      "decisionModel": true
    },
    {
      "id": "sdxs-512-dreamshaper-q8",
      "name": "SDXS-512-DreamShaper（1 步出图）",
      "repoId": "concedo/sdxs-512-tinySDdistilled-GGUF",
      "fileName": "sdxs-512-tinySDdistilled_Q8_0.gguf",
      "sizeBytes": 682847200,
      "url": "https://hf-mirror.com/concedo/sdxs-512-tinySDdistilled-GGUF/resolve/main/sdxs-512-tinySDdistilled_Q8_0.gguf",
      "mirrors": ["https://huggingface.co/concedo/sdxs-512-tinySDdistilled-GGUF/resolve/main/sdxs-512-tinySDdistilled_Q8_0.gguf"],
      "license": "CreativeML OpenRAIL++-M（继承自 SD1.5）",
      "publisher": "IDKiro / concedo",
      "family": "Stable Diffusion",
      "params": "0.9B",
      "quant": "Q8_0",
      "description": "651MB、1 步出图的极小扩散模型，手机 CPU 上最快。需要 stable-diffusion.cpp 后端（默认未编译）。注意：sd.cpp 要求它必须用 1 步、cfg=1，与本 App 默认的 4 步不一致。",
      "tags": ["真实生图", "1步", "最小", "需编译后端", "第三方"],
      "recommendedRamGb": 4
    },
    {
      "id": "dreamshaper7-lcm-q4",
      "name": "DreamShaper 7 LCM（4 步生图）",
      "repoId": "darkmaniac7/TokForge-DreamShaper-LCM-GGUF-q4",
      "fileName": "dreamshaper-7-lcm-q4_0.gguf",
      "sizeBytes": 1625041920,
      "url": "https://hf-mirror.com/darkmaniac7/TokForge-DreamShaper-LCM-GGUF-q4/resolve/main/dreamshaper-7-lcm-q4_0.gguf",
      "mirrors": ["https://huggingface.co/darkmaniac7/TokForge-DreamShaper-LCM-GGUF-q4/resolve/main/dreamshaper-7-lcm-q4_0.gguf"],
      "license": "CreativeML OpenRAIL-M",
      "publisher": "Lykon / darkmaniac7",
      "family": "Stable Diffusion",
      "params": "0.9B",
      "quant": "Q4_0",
      "description": "DreamShaper 7 融合 LCM-LoRA，1.5GB，2-8 步可用 —— 正好匹配 App 默认的 4 步。通用审美最好的一档。需要 stable-diffusion.cpp 后端。",
      "tags": ["真实生图", "LCM", "4步", "需编译后端", "第三方"],
      "recommendedRamGb": 6
    }
  ]
}
```

对应的 `voices.json` 增补（**只列 VITS、可直接用的**）：

```json
{
  "version": 1,
  "packId": "你的插件id",
  "packName": "中文音色增补（2026-10）",
  "author": "你",
  "license": "音色模型来自 k2-fsa/sherpa-onnx 官方 Release，各自遵循其原始许可：Piper/icefall/MeloTTS 系为 Apache-2.0 或 MIT；vits-zh-hf-* 角色音色为社区模型，仅供个人非商业使用。",
  "home": "https://github.com/k2-fsa/sherpa-onnx",
  "note": "id 就是官方 tarball 名。sizeBytes 取自 tts-models release 的 assets 精确字节数。",
  "voices": [
    { "id": "vits-piper-zh_CN-chaowen-medium-int8", "label": "超文（男声 · 普通话）", "description": "Piper 中文男声，仅 14MB，离线", "badge": "轻量", "sizeBytes": 14011298, "sampleText": "你好，我是超文。今天想聊点什么？" },
    { "id": "vits-piper-zh_CN-chaowen-medium-fp16", "label": "超文（男声 · fp16）", "description": "同上，fp16 精度，音质更好", "badge": "", "sizeBytes": 29149650, "sampleText": "这是一段用来试听的普通话。" },
    { "id": "vits-piper-zh_CN-huayan-medium", "label": "华研（女声 · 普通话）", "description": "Piper 中文女声，与小雅不同的音色", "badge": "", "sizeBytes": 67255926, "sampleText": "你好，我是华研。" },
    { "id": "vits-zh-aishell3", "label": "aishell3 多说话人（大）", "description": "147MB 的多说话人中文模型，可切换说话人", "badge": "多说话人", "sizeBytes": 146922607, "sampleText": "这是一段用来试听的普通话。" },
    { "id": "vits-melo-tts-zh_en", "label": "MeloTTS 中英混读", "description": "中英混读，正文里夹英文单词不用切语言。MIT 许可", "badge": "", "sizeBytes": 167006755, "sampleText": "今天我们来聊聊 AI 和 machine learning。" },
    { "id": "sherpa-onnx-vits-zh-ll", "label": "中文 LL-VITS", "description": "带中文 lexicon，数字与多音字处理更全", "badge": "", "sizeBytes": 118810709, "sampleText": "2026 年 10 月 15 日，天气不错。" },
    { "id": "vits-zh-hf-fanchen-unity", "label": "泛尘 Unity（多说话人）", "description": "泛尘系列多说话人版", "badge": "多说话人", "sizeBytes": 119143475, "sampleText": "你好，我是泛尘。" },
    { "id": "vits-zh-hf-fanchen-ZhiHuiLaoZhe_new", "label": "智慧老者（角色）", "description": "老年音色，适合角色扮演", "badge": "角色", "sizeBytes": 119402911, "sampleText": "年轻人，慢慢来。" },
    { "id": "vits-zh-hf-abyssinvoker", "label": "深渊唤者（角色）", "description": "游戏角色音色，低沉", "badge": "角色", "sizeBytes": 120579632, "sampleText": "凡人，你唤我何事？" },
    { "id": "vits-zh-hf-doom", "label": "Doom（角色）", "description": "游戏角色音色", "badge": "角色", "sizeBytes": 120556412, "sampleText": "该结束了。" },
    { "id": "vits-zh-hf-echo", "label": "Echo（角色）", "description": "游戏角色音色", "badge": "角色", "sizeBytes": 120559956, "sampleText": "我听见你了。" }
  ]
}
```

---

## 9. 统计与遗留

| 项 | 数 |
| --- | --- |
| 本轮提出的**对话**模型 | **21 条**（含 2 条非商用标注：`nemotron-reasoning`；1 条第三方：`spark-x25-4b`。另有 2 条只做同 repo 的 API 枚举、未逐条 HEAD） |
| **决策**模型 | **7 条**（其中 2 条非商用 CC-BY-NC、1 条许可不明；**建议实际只收 3 条**） |
| **识图**模型 | **11 个模型 / 22 个文件**（每个都要主 GGUF + mmproj，两边的字节数都已给出） |
| **生图**模型 | **13 条**（4.1 少数步 7 条 + 4.2 经典 6 条；其中 **4 条适合 6GB 手机**，3 条明确标注「装不下」类别另计 10 条） |
| **语音**音色 | **15 条可直接用（VITS）** + 7 条需改代码（其中 1 条英文专用不必收） |
| **hf-mirror 已验证 URL** | **54 条，全部 `302 Found → 200 OK`**，`content-length` 逐条记录；**0 条 404** |
| 另经 API 元数据确认（未逐条 HEAD） | 2 条（`Qwen3.5-4B-UD-Q4_K_XL`、`Qwen3.5-4B-IQ4_XS`，同 repo 另一个文件已 HEAD 实测） |
| GitHub Release 已验证 | 5 条 `302→200`（含 1 条 `gh-proxy.com` 回退）+ 1 条 404 反例（用于确认判据能区分"不存在"与"网络问题"） |
| 明确**故意排除**（许可原因） | 详见 §6.3 与各节排除表：FLUX.1-dev（非商用）、xLAM-2（NC）、Hammer2.1（NC）、Nemotron-Research（NC）、MiniCPM-V 4.5（商用登记条款 + 6.1GB）、shieldgemma-2b（Gemma 条款且功能重叠）、glider/Prometheus（研究限定）、Llama-Prompt-Guard-2（无可用 GGUF + gated） |
| 未能验证 | `leejet/Qwen-Image-2.1-GGUF` / `ideogram-4` / `MiniMax-H3` 等无 license 标签的仓库、社区动漫生图 GGUF、Qwen 官方 GGUF 仓库、`ggml-org/Ovis2-2B-GGUF`、`Green-Sky/SDXL-Turbo-GGUF`、`second-state/LCM_Dreamshaper_v7-GGUF`、`Qwen/Qwen3-Reranker-0.6B-GGUF`（均为配额拦截，**不等于不存在**） |

**三条留给下一个会话的明确任务**：
1. **给 `LocalModel` 加 mmproj 支持**（`mmprojFileName` + `mmprojUrl`），否则第 3 节全部条目在运行时是空头支票
   （今天 `canSeeImages` 恒为 `false`）。
2. **实测 `DiffusionModels.kind()` 对 FLUX.2-klein / Z-Image GGUF 的判定**（本档案因配额限制没能抓文件头）。
   判定不通过就在 `isDiffusionKey` 里补键前缀。
3. **修基线里 `deepseek-r1-distill-qwen-1.5b-q4km` 的重复 id**（`model-library.json` 第 178 / 372 行）。

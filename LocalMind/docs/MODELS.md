# 模型清单与选择建议

`data/ModelCatalog.kt` 里的每个条目都在**本机实测过 URL 与 Content-Length**，
所以界面显示的体积就是真实下载体积，断点续传的进度也准确。

## 为什么默认走 hf-mirror.com

开发这台机器上：

| 站点 | 结果 |
| --- | --- |
| `hf-mirror.com` | ✅ 200 |
| `modelscope.cn` | ✅ 200 |
| `huggingface.co` | ❌ 连接超时（被墙） |
| `github.com` | ✅ 200 |

所以清单里每条模型是 **hf-mirror → huggingface 官方 → ModelScope** 的顺序回退，
任何一个源可用就能下完，且**已下载的字节会跨源复用**（HTTP Range）。

### 实测记录（2026-10，Qwen3 0.6B）

用清单第一条真实 URL 实测：

```
GET https://hf-mirror.com/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf
```

| 检查项 | 结果 |
| --- | --- |
| 首跳 | `302 Found` → `cas-bridge.xethub.hf.co`（AWS CloudFront 签名 URL） |
| 服务端真实大小 | `X-Linked-Size: 396705472` —— 与清单 `sizeBytes` **完全吻合** ✅ |
| 单连接吞吐 | **7.0 MB/s**（40 MB 用时 5.98 s） |
| 378 MB 全量预估 | **约 57 秒** |
| `Range: bytes=0-1023` | `206` |
| `Range: bytes=1000000-1001023` | `206` |
| `Range: bytes=396000000-396001023` | `206` |
| `Range: bytes=396705471-396705471`（最后一个字节） | `206` |
| `Range: bytes=396705472-...`（EOF 之外） | `416` ← 预期行为 |
| `huggingface.co` 直连 | ❌ 21 秒连接失败（回退链第二跳不可用，但第一跳够用） |

> ⚠️ **与代码注释的差异**：`ModelDownloader.kt:198-208` 的注释说
> `cas-bridge.xethub.hf.co` 会忽略 `Range` 并对续传请求返回整个文件。
> **本次实测该 CDN 正常支持 `Range`（206）**，即断点续传是可用的。
> 那段 200-restart 兜底逻辑仍然正确、可以保留（应对 CDN 行为变化），
> 但注释不必再当作常态。
>
> ⚠️ **另一个待改进点**：`downloadFrom` 接受 `206` 时**没有校验响应的
> `Content-Range` 是否与请求的偏移一致**。若未来某源返回 206 但字节起点不对，
> 会静默拼接出错位的文件（最终 `verify()` 只比大小，抓不出来）。
> 建议加一行 `Content-Range` 起点校验。

## 完整清单

| 模型 | 体积 | 量化 | 许可 | 建议内存 | 备注 |
| --- | --- | --- | --- | --- | --- |
| Qwen3 0.6B | 0.37 GB | Q4_K_M | Apache-2.0 | 2 GB | 最小、最快 |
| Qwen2.5 0.5B Instruct | 0.47 GB | Q4_K_M | Apache-2.0 | 2 GB | 入门跑通流程 |
| Gemma 3 1B IT | 0.81 GB | Q4_K_M | Gemma | 3 GB | 写作、多语言 |
| Llama 3.2 1B Instruct | 0.81 GB | Q4_K_M | Llama-3.2-Community | 3 GB | 英文指令跟随 |
| **Qwen2.5 1.5B Instruct** | **1.11 GB** | Q4_K_M | Apache-2.0 | 4 GB | **推荐默认** |
| Qwen3 1.7B | 1.11 GB | Q4_K_M | Apache-2.0 | 4 GB | 同尺寸推理最强 |
| DeepSeek-R1-Distill-Qwen 1.5B | 1.11 GB | Q4_K_M | MIT | 4 GB | 思维链、数学 |
| Qwen2.5 Coder 1.5B | 1.11 GB | Q4_K_M | Apache-2.0 | 4 GB | 代码补全 |
| Llama 3.2 3B Instruct | 2.02 GB | Q4_K_M | Llama-3.2-Community | 6 GB | 英文写作 |
| Qwen2.5 3B Instruct | 2.10 GB | Q4_K_M | Qwen-Research | 6 GB | 质量接近云端小模型 |
| Qwen3 4B Instruct 2507 | 2.50 GB | Q4_K_M | Apache-2.0 | 8 GB | 长文写作 |
| Qwen2.5 7B Instruct | 4.68 GB | Q4_K_M | Apache-2.0 | 12 GB | 会走 mmap 流式加载 |
| BGE-small-zh-v1.5 | 26 MB | Q8_0 | MIT | 1 GB | 中文向量（RAG 用） |

## 手机内存与模型对照

| 手机内存 | 能舒服跑的模型 | 说明 |
| --- | --- | --- |
| 4 GB | 0.5B ~ 1B | 只能上最小档 |
| 6 GB | 1.5B | 推荐档，速度与质量平衡点 |
| 8 GB | 1.5B ~ 3B | 3B 可用但速度下降 |
| 12 GB+ | 3B ~ 7B | 7B 需依赖 mmap 磁盘流式加载 |

应用里的**内存规划器**会自动算好上下文与线程数，并在你手动设置过大时给出提示
（见 `data/MemoryPlanner.kt` 与 `docs/AIRLLM.md`）。

## 添加自己的模型

三种方式：

1. **粘贴仓库**：模型页 → 导入自己的模型 → 填 `owner/repo`（自动通过 HF API
   挑选 Q4_K_M / Q8_0 文件）或直接填 `.../resolve/main/xxx.gguf` 直链。
2. **adb 推送**：`adb push model.gguf /sdcard/Download/` 后在应用内导入。
3. **直接放文件**：放进应用私有目录 `filesDir/models/`（路径在设置页可见），
   或者把任意 GGUF 交给 `ModelStore.addImported()`。

## 关于模型体积的常见误解

- 清单里的体积是 **Q4_K_M 量化**后的真实大小，不是原始 fp16 大小。
  例如 Qwen2.5 7B 原始权重约 15 GB，Q4_K_M 只有 4.68 GB。
- 手机端推理速度主要受**内存带宽**限制，不是 CPU 主频。
  所以 1.5B 在旗舰机上能到 20 tok/s，而 7B 即使在大内存机型上也只有 2-4 tok/s。
- 上下文长度对内存的影响有时比模型本身更明显：KV cache 随上下文线性增长，
  规划器会按 `2 × 层数 × 嵌入维度 × 2 字节/token` 估算后帮你截断。

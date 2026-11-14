# AirLLM 集成说明

> 结论先讲：**AirLLM 本身不能在 Android 上运行**，所以它没有被"装进 APK"，
> 而是把它的**核心思想**用 C++/Kotlin 在移动端重新实现了一遍。
> AirLLM 的原始 LICENSE 与 README 已放在 `third_party/airllm-reference/` 作为出处与授权凭据。

---

## 一、为什么不能直接跑 AirLLM

上游仓库 [lyogavin/airllm](https://github.com/lyogavin/airllm) 的 `requirements.txt`：

```
bitsandbytes==0.39.0
transformers @ git+https://github.com/huggingface/transformers.git
peft @ git+https://github.com/huggingface/peft.git@v0.3.0
accelerate @ git+https://github.com/huggingface/accelerate.git@v0.20.3
einops / evaluate / scikit-learn / sentencepiece / wandb
```

以及 `air_llm/airllm/airllm_base.py` 的实际实现依赖：

| 依赖 | 作用 | 在 Android 上的可行性 |
| --- | --- | --- |
| `torch` | 张量计算与 `nn.Module` | Android 只有 PyTorch Mobile / ExecuTorch，**不支持** AirLLM 需要的动态 `meta` device + hook 机制 |
| `bitsandbytes` | 4bit/8bit 量化 | **CUDA-only** 的 kernel 实现，ARM CPU 上没有后端 |
| `accelerate.init_empty_weights` / `set_module_tensor_to_device` | 逐层加载权重的关键 | 依赖完整 Python 运行时与 `transformers` 模型注册表 |
| `transformers` | 模型加载、`AutoModelForCausalLM` | 体积 + Python 运行时，手机端不现实 |
| `safetensors`（HuggingFace 权重格式） | 权重文件 | 需要完整 PyTorch 权重加载管线 |

简单说：AirLLM 是**桌面/服务器端的 Python 库**，它解决的是"显存不够"的问题，
手段是把 HuggingFace 权重按层从磁盘搬运到 GPU。它依赖的整条链路（Python → torch → CUDA/CUDA-kernel）
在 Android 上都不存在。

顺带一提：它还有一个 `airllm_llama_mlx.py`（Apple Silicon MLX 后端），那是给 macOS 的，同样与 Android 无关。

---

## 二、AirLLM 的思想是什么

剥掉实现细节，AirLLM 做对了两件事：

1. **不假设模型能一次性装进内存。** 它先把模型结构以"空壳"（meta device）建出来，
   只占几百 MB，然后**需要哪一层才去磁盘读哪一层**。
2. **先算预算，再决定怎么加载。** 通过分片、mmap、逐层调度，把"内存上限"从
   约束变成一个可调参数。

这两点在手机上同样关键——而且比在桌面更重要，因为手机的可用内存通常只有 3-6GB。

---

## 三、LocalMind 怎么落地这两点

### 3.1 内存规划器（Kotlin 侧，`data/MemoryPlanner.kt`）

对应 AirLLM 的"先算预算"。加载模型**之前**：

1. 直接读 GGUF 头部，解析 `general.architecture`、`*.block_count`（层数）、
   `*.embedding_length`、`*.attention.head_count`、`*.context_length`、`general.file_type`（量化类型）。
   整个解析只读文件头（≤1MB），**不会把权重读进内存**。
2. 按几何参数估算 KV cache 每 token 的开销：
   `2（K/V）× layers × embedding_length × 2 bytes`（f16 cache）。
3. 预留 900MB 给系统 / Compose UI / API 服务器，得到可用预算。
4. 反推**这台设备这个模型能开多大上下文**，并与模型自身的 `context_length` 取小。
5. 决定线程数（默认 `核心数 - 1`，上限 6，给 UI 渲染留核）。

输出 `ModelMemoryPlan`，在 UI（模型详情、设置页）里完整展示，
并在用户手动指定了超出预算的上下文时**自动下调并给出提示**，而不是等到原生层分配失败崩溃。

### 3.2 磁盘流式加载（C++ 侧，`llama_jni.cpp`）

对应 AirLLM 的"逐层按需取权重"。移动端的等价物是内核页缓存：

```cpp
llama_model_params mp = llama_model_default_params();
mp.use_mmap  = true;   // 权重 mmap 映射，缺页时才从闪存读入
mp.use_mlock = false;  // 不锁定内存 → 内存压力下内核可以回收干净页
```

效果：一个 4.4GB 的 `Qwen2.5 7B Q4_K_M` 在 8GB 手机上**可以运行**，
代价是首个 token 更慢（要等权重页从闪存换入）。这就是 AirLLM 在手机上的
现实版本——不是"把层搬到显存"，而是"让内核按需喂页"。

UI 里对应 **设置 → 性能与内存 → mmap 磁盘流式加载** 开关。
关掉它 = 要求模型全部驻留内存（等价于 AirLLM 之前的传统加载方式），
加载大于可用内存的模型会失败——这本身也是个有用的对照实验。

### 3.3 上下文预算（`LlamaEngine.load`）

若用户手动指定了上下文，规划器会把 `recommendedContext` 下调到能装下的值，
原生层再按这个值 `llama_init_from_model`；万一仍然失败，
`llama_jni.cpp` 会自动退回 `n_ctx = 2048` 重试一次，而不是直接抛出。

---

## 四、和 AirLLM 的差异对照

| | AirLLM | LocalMind |
| --- | --- | --- |
| 运行环境 | Python + PyTorch + CUDA | Android + Kotlin + JNI |
| 权重格式 | HuggingFace safetensors（分片） | GGUF（单文件，内部已量化） |
| 量化 | bitsandbytes 4/8bit（GPU kernel） | GGUF Q4_K_M / Q8_0 等（CPU kernel） |
| 按需加载手段 | Python hook + `meta` device + 逐层 `.to()` | `mmap` + 内核页缓存 |
| 预算决策 | 运行时按剩余显存调度 | 加载前静态规划（层数/嵌入维/KV/内存） |
| 目标硬件 | 单卡 4GB 显存跑 70B | 手机 4-12GB 内存跑 0.5B-7B |
| 可否在本项目里"直接调用" | — | ❌ 不可行（见第一节） |

---

## 五、如果你真的想用 AirLLM 的大模型能力

有两条路，本项目都给留了口子：

1. **把 PC 当后端**：在电脑上跑 AirLLM（或任何推理框架），用 OpenAI 兼容接口暴露出来，
   然后在 LocalMind 里把它当成一个外部提供方使用——
   把对话请求指向 `http://<PC-IP>:<port>/v1`（接口页的 Base URL 用法完全一样）。
   这样手机端负责 UI、语音、局域网转发，重活交给有显卡/大内存的机器。
2. **等真正的移动端大模型内核**：`docs/IMAGE_ENGINE.md` 里描述的多后端接入方式
   同样适用于 LLM 侧——`LlamaEngine` 是唯一推理入口，替换它的实现即可接入
   MNN / ExecuTorch / NCNN 等其它后端，而 UI、API、语音层完全不用改。

---

## 六、出处与授权

- 上游项目：[lyogavin/airllm](https://github.com/lyogavin/airllm) · Apache License 2.0
- 本项目保留其 LICENSE 与 README：`third_party/airllm-reference/`
- 本项目对 AirLLM 的使用属于**思想借鉴与移动端重新实现**，未复制其源代码，
  因此不受其 Python 依赖链影响。

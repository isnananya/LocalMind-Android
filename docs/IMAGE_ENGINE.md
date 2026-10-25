# 生图引擎说明

> **1.0.3 起：默认出图尺寸 512²、自动步数按模型推断（Turbo 4 步 / 普通 12 步）、
> Turbo 系强制 CFG=1（每步只跑一遍）、大图「快速放大」、进页面即预热模型。**
> 下面第四节是这轮的完整说明；一~三节是引擎本身的原理，仍然有效。

## 一、现状：为什么不是"内置 Stable Diffusion"

要让手机**真正**跑扩散模型，需要同时满足三件事，目前每件都不成立：

| 需求 | 现实 |
| --- | --- |
| 权重体积 | SDXL-Turbo 约 6.9GB（fp16），SD-Turbo 约 2.5GB；对侧载 APK 与手机存储都是硬伤 |
| 算力 | 单步 UNet 推理在旗舰手机 CPU 上约 10-60 秒；4 步的 Turbo 也要 1-4 分钟，且发热降频 |
| 授权 | 可商用的 checkpoint 授权各不相同，无法随 APK 一起分发 |

因此 LocalMind 采用**双轨**策略：

1. **内置引擎**（默认，开箱可用）：原生 C++ 程序化渲染器 —— 离线、毫秒级、
   对同一 `(prompt, seed, style, size)` 完全可复现，零模型文件、零网络。
2. **接入位**（给需要真实扩散模型的用户）：`ImageBridge.nativeSdLoad` /
   `nativeSdGenerate` 已经预留，编译时把后端接上即可，UI 与 API 无需改动。

---

## 二、内置引擎做了什么

代码：`app/src/main/cpp/image_jni.cpp`（渲染）+ `service/ImageEngine.kt`（调度与落盘）

1. 提示词哈希 → 64 位种子（`SplitMix64`），保证可复现。
2. 关键词 → 调色板：`night/夜/星空/cyber`、`fire/火/黄昏`、`ocean/海/冰`、
   `forest/森/树`、`sakura/樱/粉`、`desert/沙/金`，否则冷色编辑风。
3. 地平线、太阳位置与半径由 fBm 噪声派生，因此不同提示词构图不同。
4. 天空：垂直渐变 + 大尺度 fBm 云场；`aurora/极光/星云` 叠极光带；
   `night/星空/space/宇宙` 叠星场；太阳/月亮带光晕与实体盘。
5. 地面：三层脊状剪影（`fbm` 取脊线），`city/城市/cyber` 时提高细节频率。
6. 风格后处理：
   - `自然` 轻颗粒
   - `电影感` 青橙调色 + 暗角
   - `插画` 色阶量化 + 饱和度拉伸
   - `赛博霓虹` 辉光 + 扫描线
   - `胶片` 重颗粒 + 柔和滚降
7. 输出 ARGB_8888 `IntArray` → Kotlin 转 `Bitmap` → PNG 落盘到
   `filesDir/images/img_<slug>_<seed>_s<style>_<W>x<H>.png`

文件名的确定性意味着：**同一个提示词 + 同一个种子 = 同一个文件名**，
既方便复现，也方便 `/v1/images/{file}` 做缓存。

Kotlin 侧还有一份等价实现（`ImageBridge.fallbackRender`），
当原生库不可用（例如 ABI 不匹配）时兜底，保证界面永远不空白。

---

## 三、接入真实扩散模型（stable-diffusion.cpp）

`third_party/` 下新拉一份 [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)，
它的 C API 与 llama.cpp 同源（ggml），在本项目的 CMake 里可以直接并列：

```cmake
# app/src/main/cpp/CMakeLists.txt
set(SD_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/stable-diffusion.cpp")
option(SD_BUILD_EXAMPLES "" OFF)
add_subdirectory("${SD_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/sd" EXCLUDE_FROM_ALL)

target_include_directories(localmind PRIVATE "${SD_DIR}")
target_compile_definitions(localmind PRIVATE LOCALMIND_WITH_SD=1)
target_link_libraries(localmind stable-diffusion)
```

然后在 `image_jni.cpp` 里把两个空实现填上：

```cpp
#if LOCALMIND_WITH_SD
static sd_ctx_t * g_sd = nullptr;
static std::mutex g_sd_mtx;

JNIEXPORT jboolean JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdLoad(JNIEnv *env, jclass, jstring modelPath) {
    std::lock_guard<std::mutex> lk(g_sd_mtx);
    if (g_sd) { free_sd_ctx(g_sd); g_sd = nullptr; }
    const char *p = env->GetStringUTFChars(modelPath, nullptr);
    sd_ctx_t *ctx = new_sd_ctx(p, /*vae*/nullptr, nullptr, nullptr, nullptr,
                               /*n_threads*/4, SD_TYPE_COUNT, false, true, false, false);
    env->ReleaseStringUTFChars(modelPath, p);
    g_sd = ctx;
    return ctx ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdGenerate(
        JNIEnv *env, jclass, jstring modelPath, jstring prompt, jint w, jint h,
        jint seed, jfloat guidance, jintArray pixels) {
    std::lock_guard<std::mutex> lk(g_sd_mtx);
    if (!g_sd) return -1;
    const char *p = env->GetStringUTFChars(prompt, nullptr);
    sd_image_t *img = generate_image(g_sd, p, /*negative*/"", w, h,
                                     /*sample_method*/EULER_A, /*steps*/4, guidance,
                                     seed, /*batch*/1, nullptr, 0, nullptr, 0, false, nullptr, 0);
    env->ReleaseStringUTFChars(prompt, p);
    if (!img || !img->data) return -1;
    // sd_image_t 是 RRGGBB 交织的 uint8 缓冲，转成 ARGB_8888
    jint *out = new jint[w * h];
    for (int i = 0; i < w * h; i++) {
        uint8_t r = img->data[i * 3 + 0], g = img->data[i * 3 + 1], b = img->data[i * 3 + 2];
        out[i] = 0xFF000000u | (r << 16) | (g << 8) | b;
    }
    env->SetIntArrayRegion(pixels, 0, w * h, out);
    delete[] out;
    free(img->data); free(img);
    return w * h;
}
#else
// 默认构建：直接返回失败，让 Kotlin 侧回落到内置渲染器
#endif
```

Kotlin 侧**不需要任何改动**：`ImageBridge.render()` 会继续走内置引擎，
而 `ImageBridge.hasDiffusionBackend` 变成 `true` 时会显示在
**设置 → 诊断 → 扩散模型后端** 这一项上。

### 想真正用起来还需要做的

1. 生成 `LOCALMIND_WITH_SD` 的构建变体（`flavor` 或 `-P` 开关），
   避免默认 APK 体积暴涨。
2. 模型文件（SD-Turbo / SDXL-Turbo 的 GGUF 量化版）不随 APK 分发，
   放在模型页让用户自行下载到 `filesDir/models/`。
3. UI 侧把 `steps` / `guidance` 从 `ImageSettings` 透传下去
   （字段已经在 `ImageSettings` 里了，当前只有内置引擎忽略它们）。

---

## 四、接入局域网上的 SD 服务（零编译）

不想编译 C++ 也能用真实扩散模型：在 PC 上跑 Automatic1111 / ComfyUI / SD.cpp server，
然后在 `ImageSettings.externalEndpoint` 填它的地址，
`ImageEngine.generate` 走 HTTP 转发即可（字段已预留，见
`data/Models.kt` 的 `ImageSettings`）。这样手机端负责 UI 与局域网共享，
重活交给 PC —— 和 LLM 侧接 AirLLM 的思路一致（见 `docs/AIRLLM.md` 第五节）。
---

## 四、1.0.3：把「生图太慢」按下去

> 先说量级：手机 CPU 上一次 UNet 推理是**秒级**的，所以出图时间 ≈
> 「边长² × 步数 ×（CFG>1 时再 ×2）」。这三个因子每一个都能砍。

| # | 改了什么 | 之前 | 之后 | 大约省 |
| --- | --- | --- | --- | --- |
| 1 | 默认分辨率 | 768² | **512²** | −56%（面积比 0.44） |
| 2 | 「自动」步数 | 交给 sd.cpp 默认（20 步） | **12 步**（Turbo 系 4 步） | −40%（Turbo −80%） |
| 3 | Turbo/LCM 的 CFG | 3.5（每步跑两遍） | **1.0**（sd.cpp 直接跳过 uncond） | −50% |
| 4 | 大图策略 | 768² / 1024² 直接算 | 「快速放大」默认开：先 512 再放大 | 768² −56%，1024² −75% |
| 5 | 模型加载 | 算在**第一张图**的时间里 | 进「生图」页就在后台预热 | 第一张 −几十秒 |

叠起来：**默认设置下 768²@20 步 → 512²@12 步 ≈ 3.7 倍**；Turbo 类模型
（4 步 + CFG 1）比原来的「20 步 + CFG 3.5」快 **5 倍以上**。

### 为什么 cfg=1 能省一半

sd.cpp 的 `src/pipeline/diffusion_engine.cpp` 里有这么一句：

```cpp
skip_uncond = std::abs(effective_guidance_scale - 1.0f) < kEpsilon;
```

也就是 **guidance == 1 时整个无条件分支被跳过**。CFG>1 时每步要跑两遍 UNet
（cond + uncond；默认 `batched_cfg=true` 会合成一次批处理，但计算量仍是两倍）。
Turbo/LCM 系本来就是按「不做 CFG」蒸馏的，给 3.5 不但慢一倍，图还会发焦 ——
所以这一条由 `ImageEngine.effectiveGuidance` 兜住，不指望用户知道。

### 自动怎么判断是 Turbo

只能看**文件名**：sd.cpp 的 GGUF 元数据里没有「这是不是 turbo」这个字段。
命中 `turbo / lcm / sdxs / hyper / lightning / dmd / schnell / flash` 任一词就按
Turbo 处理（`sd-turbo`、`sdxl-turbo`、`sdxs-512`、`dreamshaper-flash`…）。
判错的代价不对称但可接受：误判成 turbo 只是少跑几步、CFG=1，图还在；
漏判就是「20 步 + 双倍 CFG」，慢且糊。

### 迁移：老用户的 768² 会变 512²

`Storage.loadImageSettings()` 里做了一次性迁移（`image_size_v2` 标记）：
**只有恰好停在旧默认值 768×768 的那一份**才改成 512² —— 用户自己挑过的
512/1024 一律不动。因为 768² 从来不是他的选择，是默认值。

### 界面上的诚实反馈

  * 生成中显示「已用 X 秒 / 上次同样的设置用了 Y 秒」——扩散出图本来就是几十秒
    到几分钟，只给一个转圈等于让用户猜「在跑」还是「卡死」；
  * 出图后显示「512² 出图 → 放大到 768² · 12 步 · 8.4 秒」；
  * 分辨率下面直接写「768² 是 512² 的 2.25 倍，1024² 是 4 倍」。

### 通用版插件（已交付）

上一节列的「只能动插件才能再快」的三件事，现在已在 `sd-alone/sd_jni.cpp` 里接上，
并打了 **`dist/plugins/本地生图后端插件-通用版.zip`**（arm64-v8a + x86_64 同一个包）：

  * `nativeSdGenerateEx2`：步数、线程、flash attention、采样器 / 调度器、逐帧进度；
  * LCM / Hyper / Lightning / TCD / DMD 系显式走 `lcm` 采样器 + `lcm` 调度器，
    其余模型传空串，交给 sd.cpp 按模型族自己决定；
  * 进度回调走具名 `ProgressSink`（R8 不会把 lambda 合成类剪掉），
    界面上生成时能显示「第 X / Y 步」；
  * 安装器只解压与本机 ABI 匹配的那一份，另一个架构记进 `otherAbis`，
    不会出现「后一份覆盖前一份、校验失败、后端消失」。

旧插件仍可出图：调用按 `Ex2 → Ex → 基础入口` 三级回退。
### 「生图模型」现在可以选

以前生图页那一行只有「本机没有检测到扩散模型 / 重新扫描」—— 本机放了好几个
checkpoint 的人没法指定用哪个，只能赌扫描顺序（用户的原话：「生图里面生图模型怎么不能选」）。
现在这一行右边有两个按钮：

  * **选择**：列出本机已下载的扩散 checkpoint（`DiffusionModels.findCheckpoints`，
    读 GGUF 头确认过的那些），选中写进 `ImageSettings.customModelPath`；
    标着「快」的是 Turbo / SDXS 这类少步数模型（就是上面自动步数里说的那一类）；
  * **重新扫描**：重新读目录（刚下完 / 刚手动推进去的时候用）。

优先级统一成一条线，生图页与对话里的「AI 主动画一张」共用：

`ImageSettings.customModelPath`（生图页选的） → 对话的 `imageModelId` → 自动扫目录。

放在 `AppViewModel.imageModelPathFor` 一处实现，两个入口都走它 —— 分开算必然出现
「在生图页选好了，对话里让它画却又换了模型」。
---

## 五、通用版插件（1.0.3）

| 能力 | 之前 | 现在 |
| --- | --- | --- |
| checkpoint 格式 | 只列 `.gguf` | `.gguf` / `.safetensors` / `.ckpt` / `.pt`（sd.cpp 本来就支持，只是我们没列出来） |
| 采样器 / 调度器 | 一律用默认 | 可按名覆盖（`euler_a` / `lcm` / `dpm++2m` …）。**LCM 家族自动用 LCM 采样器 + LCM 调度器**，其余交给 sd.cpp 按模型族自己决定 —— 那才是「通用」的正确含义 |
| 进度 | 只有转圈 | 逐帧回调 → 界面显示「第 5/12 步 · 已用 8 秒」 |
| ABI | 一个包一个 ABI | 一个 zip 带 arm64-v8a + x86_64，安装时按本机自选 |
| 体积 | 未 strip 147 MB / ABI（zip ~300 MB） | strip 后 35.2 / 36.1 MB，zip **28.7 MB** |

JNI 侧**只加不改**：`nativeSdGenerateEx2`（采样器 + 进度）→ `nativeSdGenerateEx`（步数 + 加速）→ 老的 `nativeSdGenerate`，三级回退 —— 用户手机上装着哪一代插件都能出图。

产物：`dist/plugins/本地生图后端插件-通用版.zip`（`我的 → 插件 → 导入 zip` 安装）。

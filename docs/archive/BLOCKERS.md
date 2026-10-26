# 生图与 TTS 的接入障碍（实测记录）

> 更新于 2026-10。这份文档存在的意义：**下次不要重走弯路**。
>
> **当前状态**：TTS 已解决（见第二节末尾）。生图仍然卡在 ggml CMake 目标冲突上。

---

## 一、生图（stable-diffusion.cpp）：ggml CMake 目标冲突

### 现状

| 环节 | 状态 |
| --- | --- |
| `third_party/stable-diffusion.cpp` 源码 | ❌ **当前不存在**（未提交进 git，需自行取回） |
| `image_jni.cpp` 的 `nativeSdLoad` | ⚠️ 桩函数，固定返回 `JNI_FALSE` |
| `image_jni.cpp` 的 `nativeSdGenerate` | ⚠️ 桩函数，固定返回 `-1` |
| `ImageBridge.hasDiffusionBackend` | 因上面两条恒为 `false` |
| 程序化渲染器 | ✅ **可用**（实测 `/v1/images/generations` 返回 512×512 PNG） |

> ⚠️ **取回源码时的坑**：`stable-diffusion.cpp` 依赖 `ggml` 子模块，
> 而 CMake 里是 `file(GLOB SD_LIB_SOURCES "src/*.cpp")`。只拿到
> `CMakeLists.txt` + `ggml/`（**没有 `src/`**）的残缺副本**编译不出任何东西** ——
> 这正是"生图一直没接上"的历史直接原因。取回后必须确认 `src/stable-diffusion.cpp` 在。

### 真正的障碍：CMake 目标名冲突

`llama.cpp` 和 `stable-diffusion.cpp` **各自捆绑了一份 ggml**，而两者的
CMake **目标名完全相同**：

| 目标 | llama.cpp 中的定义 | sd.cpp 中的定义 |
| --- | --- | --- |
| `ggml-base` | `ggml/src/CMakeLists.txt:192` | `ggml/src/CMakeLists.txt:192` |
| `ggml` | `ggml/src/CMakeLists.txt:358` | `ggml/src/CMakeLists.txt:358` |
| `ggml::ggml` (ALIAS) | `:361` | `:361` |

行号完全一致，说明是**同一份 ggml**的两个独立副本。

当前的 `app/src/main/cpp/CMakeLists.txt` 已经 `add_subdirectory(llama.cpp)`，
在此基础上再 `add_subdirectory(stable-diffusion.cpp)`：

```
CMake Error: add_library cannot create target "ggml-base"
because another target with the same name already exists.
```

**即：直接加 `add_subdirectory` 必然失败，这不是猜测。**

### 三条可行路线（按推荐度）

#### 路线 A：把 sd.cpp 的 ggml 目标重命名后单独构建

给 sd.cpp 一侧的 ggml 目标统一加前缀（`sdggml-base` / `sdggml` / `sdggml-cpu`…），
在 `third_party/stable-diffusion.cpp` 的 CMake 里做局部替换，产出**独立的 `libsd.so`**，
再由 `liblocalmind.so` 链接它。

- 需要改动：sd.cpp 的 `ggml/src/CMakeLists.txt`、`ggml/CMakeLists.txt`、
  `stable-diffusion.cpp/CMakeLists.txt` 里的目标名与 `target_link_libraries` 引用
- 风险：改动面较大，且 sd.cpp 升级后需重做
- 验证方式：`cmake -S <probe> -B <build>` 只跑 configure，看是否还有重名报错

#### 路线 B：让 sd.cpp 复用 llama.cpp 自带的 ggml（不推荐）

理论上只保留一份 ggml 最干净（省掉重复编译和体积），但：

- llama.cpp **v0.6.0** 与 sd.cpp 最新 master 捆绑的 ggml **版本相差很远**
- sd.cpp 用到的新 ggml API 在旧版里多半不存在，**编译期就会炸**
- 且升级任一侧都可能再次破

#### 路线 C：放弃 CMake 合并，构建期产出预编译 `libsd.so`（**当前代码已按这条铺好**）

用独立 CMake 工程把 sd.cpp 编成静态库，作为预编译库放进 `jniLibs`。
缺点是构建流程要两步，且需要自己维护 ABI 矩阵。

`app/src/main/cpp/CMakeLists.txt` 与 `app/build.gradle.kts` 已经按这条实现了骨架：

| 已有 | 作用 |
| --- | --- |
| `app/src/main/cpp/sd-alone/CMakeLists.txt` | 独立工程，可见集合里只有 sd.cpp 自己那份 ggml，冲突从根上消失 |
| `:app:fetchStableDiffusion` | 从 GitHub Release 拉预编译 `libstable-diffusion.a` |
| `:app:compileStableDiffusion` | 兜底：本机编译 |
| `CMakeLists.txt` 里的 `if(EXISTS ${SD_PREBUILT})` | 有就 `--whole-archive` 链进来并定义 `LOCALMIND_HAS_SD=1`，没有就只警告 |

**所以生图缺的只是"把 sd.cpp 源码取回来并跑一次 `:app:compileStableDiffusion`"**，
CMake 侧的接线已经完成（`app/src/main/cpp/sd-alone/CMakeLists.txt` 已在位）。

### 另一条已知限制（与构建无关）

`ModelCatalog` 里的生图模型（SD-Turbo、SD 2.1）**体积 2 GB 级**，
x86_64 模拟器上跑扩散不仅慢，而且内存也吃紧。**生图的实测必须在真机 arm64 上做**，
模拟器最多只能验证"能加载模型文件"这一步。

---

## 二、TTS（sherpa-onnx）：✅ **已解决**

### 结论先行

之前这份文档的结论是"本地 AAR 损坏 + Maven 源全部不可用 → 做不了"。
**这个结论错在前提**：它假设必须拿到一个 AAR。实际上官方 Release 有完整的
**Android 原生库 tarball**，拿到 `.so` 之后自己 vendored 一层薄薄的 Kotlin API 就够了。

### 原来卡在哪（保留作教训）

| 环节 | 当时状态 |
| --- | --- |
| `app/libs/sherpa-onnx-1.13.8.aar`（15.18 MB） | ❌ **文件损坏（被截断）** |
| `app/build.gradle.kts` | ❌ 没有任何依赖声明引用它 |
| `TtsEngine` | ⚠️ 纯系统 TTS + 情感包络，零 sherpa / VITS 调用 |

损坏证据（**这个判据本身是对的，值得留着**）：

```
大小: 15,921,289 字节
前 4 字节:   50 4B 03 04   ("PK\x03\x04" —— ZIP 本地文件头，正常)
末尾 32 字节: 无 PK\x05\x06（ZIP 中央目录结束标记）
```

`jar xf` 报 `java.util.zip.ZipException: zip END header not found`。

> 教训：判断一个下载文件是否可用，**不能只看大小和非零**。
> 要检查 ZIP 的 EOCD 签名（文件末尾 22 字节内的 `PK\x05\x06`）。

Maven 侧确实全不可用（**这部分结论没错**）：

| 源 | URL | 结果 |
| --- | --- | --- |
| Maven Central | `repo1.maven.org/maven2/com/k2fsa/sherpa/onnx/sherpa-onnx-android/1.13.8/*.aar` | ❌ **404** |
| Sonatype OSS releases | `s01.oss.sonatype.org/...` | ⚠️ 302 → Central 的 404 路径 |
| JitPack | `jitpack.io/com/github/k2-fsa/sherpa-onnx/v1.13.8/` | ⚠️ 只有 `.jar`，**没有 `.aar`** |

### 走通的路（当前实现）

**关键发现**：官方 v1.13.8 Release 的 288 个附件里，Android 相关的是这些
（`https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/v1.13.8`）：

| 附件 | 大小 |
| --- | --- |
| `sherpa-onnx-v1.13.8-android.tar.bz2` | **46,093,321** ← 用这个 |
| `sherpa-onnx-v1.13.8-android-static-link-onnxruntime.tar.bz2` | 35,101,751 |
| `sherpa-onnx-v1.13.8-android-rknn.tar.bz2` | 24,946,383 |
| `...-android-{aarch64,armeabi-v7a,x86,x86_64}-termux-{shared,static}.tar.bz2` | Termux 用，不是 Android APK |

包里的结构（`tar -tjf` 可见）：

```
jniLibs/<abi>/
  libonnxruntime.so          15–25 MB   ← 大头
  libsherpa-onnx-c-api.so     3–5 MB
  libsherpa-onnx-cxx-api.so   0.3–0.4 MB
  libsherpa-onnx-jni.so       3–5 MB    ← Kotlin API 的 JNI 层
```

**没有 classes.jar**，所以 Kotlin API 要自己 vendored —— 见
`app/src/main/java/com/k2fsa/sherpa/onnx/`。

实现清单：

| 新增 | 作用 |
| --- | --- |
| `:app:fetchSherpaOnnx` | 拉 tarball，只解出 4 个 `.so` 到 `jniLibs/<abi>/`（直连 GitHub 会随机超时，失败自动走 `gh-proxy.com`） |
| `com/k2fsa/sherpa/onnx/Tts.kt` | vendored 的 TTS Kotlin API（**字段一个都不能删**，见文件顶部） |
| `com/k2fsa/sherpa/onnx/SherpaNativeLoader.kt` | 允许失败的 `System.loadLibrary` 包装（库缺失时不让应用崩） |
| `service/NeuralTts.kt` | 模型布局解析 + VITS 推理 + `AudioTrack` 播放 + WAV 导出 |
| `service/VoiceModelDownloader.kt` | 断点续传 + 系统 `tar` 解压 + VITS 布局校验 |
| `data/VoiceCatalog.kt` | 重写：id 就是官方 tarball 名，附实测体积 |
| `VoiceSettings.neuralVoiceId` | 与系统音色的 `voiceName` 分开存 |

### ⚠️ 最大的坑：JNI 按字段名反射

第一版只保留 VITS 需要的字段，把 Matcha/Kokoro 等 data class 删掉了。
结果**进程直接 SIGABRT**：

```
JNI DETECTED ERROR IN APPLICATION: JNI GetObjectField called with pending exception
java.lang.NoSuchFieldError: no type
"Lcom/k2fsa/sherpa/onnx/OfflineTtsMatchaModelConfig;" found and so no field "matcha"
could be found in class "com/k2fsa/sherpa/onnx/OfflineTtsModelConfig;" or its super classes
  at long com.k2fsa.sherpa.onnx.OfflineTts.newFromFile(...)
  at java.lang.String com.localmind.service.NeuralTtsEngine.load(...)
```

`libsherpa-onnx-jni.so` 会**无条件读遍 `OfflineTtsModelConfig` 的每一个字段**，
不是只读你这次用的那种模型。所以：

- vendored 的类形状必须和上游**一一对应**：字段名、类型、顺序都不能动
- 缺字段 → `NoSuchFieldError` → 它是 `Error` 不是 `Exception`，
  ART 当致命 JNI 错误终止进程，**`runCatching` 拦不住**
- 查证手段：`strings libsherpa-onnx-jni.so | grep -i matcha` 能看到它找的字段名

### 音色模型的下载源

也是官方 Release，标签是 `tts-models`：

```
https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/<VoiceCatalog.id>.tar.bz2
```

实测（HEAD 请求拿 `Content-Length`；⭐ = 已实际下载解包并合成通过）：

| 音色 id | 大小 | 实测 |
| --- | --- | --- |
| `vits-piper-zh_CN-xiao_ya-medium-int8` | 14,016,124 | ⭐ 下载+解包+合成通过（1 说话人） |
| `vits-zh-hf-bronya` | 120,595,102 | ⭐ 下载+解包+合成通过（**804 说话人**，132 MB 解包后） |
| `vits-zh-hf-fanchen-C` | 119,326,431 | ⭐ 下载+解包通过（131 MB 解包后） |
| `vits-icefall-zh-aishell3` | 31,559,701 | ⚠️ 只确认 HTTP 200 |
| `vits-zh-hf-theresa` | 120,596,617 | ⚠️ 同上 |
| `vits-zh-hf-eula` | 120,562,119 | ⚠️ 同上 |
| `vits-zh-hf-keqing` | 120,592,220 | ⚠️ 同上 |
| `vits-zh-hf-zenyatta` | 120,588,994 | ⚠️ 同上 |

> **下载字节数与 `Content-Length` 完全一致**（布洛妮娅恰好 115.01 MiB、
> 泛尘恰好 113.80 MiB），说明断续续传没有拼错文件。

> 直连 GitHub 有**随机超时**（同一 URL 有时 200、有时卡住），所以
> `VoiceModelDownloader` 和 `fetchSherpaOnnx` 都是"先直连、失败换 gh-proxy"。
> **这条回退在真实下载里被触发过并成功收尾**：日志里能看到
> `来源 1 失败：Connection reset` → 换代理 → `安装完成`。

### 布局差异（验证了"扫目录"这个设计是对的）

三个音色的文件名**各不相同**，写死路径必错：

| 音色 | 主模型文件 |
| --- | --- |
| xiao_ya | `zh_CN-xiao_ya-medium.onnx` |
| bronya | `bronya.onnx` |
| fanchen-C | `vits-zh-hf-fanchen-C.onnx` |

hf 系还会多带一个 `dict/`（jieba 分词词典 + HMM 词性标注，约 13 MB）和一堆
`*.py` / `*.sh` 导出脚本 —— 都是它自己的产物，`resolve()` 扫到最大的 `.onnx`
就停，不会被这些杂项带偏。

小雅的解包布局（作为命名惯例的参照）：

```
vits-piper-zh_CN-xiao_ya-medium-int8/
  zh_CN-xiao_ya-medium.onnx      18.2 MB   ← 主模型
  tokens.txt
  lexicon.txt
  date.fst  number.fst  phone.fst          ← 可选，文本正则化
  MODEL_CARD
```

因为各音色命名不统一，`NeuralVoiceModels.resolve()` **不写死路径**，
而是扫目录找最大的 `.onnx`，再在同级找 `tokens.txt` / `lexicon.txt` / `*.fst`。
布局猜错最多多花一次扫描，不会失效。

### 仍未验证

- 除小雅、布洛妮娅、泛尘外的 5 个音色**没有实际下载解包过**（体积 30–115 MB，
  本机到 GitHub 只有 150–750 KB/s）。它们的 `Content-Length` 是对的、tarball 存在，
  但解压后的目录布局是按惯例推的。
- 模拟器上合成速度没做基准（`AudioTrack` 在模拟器里没有真实输出设备，
  只能验证"合成出了正确长度的 PCM"）。实测合成约 1 秒出 2.1–2.3 秒音频，
  即**快于实时**，即使在模拟器上。听感必须真机确认。
- 多说话人音色的 `sid` 参数目前固定传 0（布洛妮娅有 804 个说话人，
  但 UI 还没做说话人选择）。

---

## 三、结论

| 任务 | 状态 | 阻塞点 |
| --- | --- | --- |
| 「我的」→ 关于 / 作者 / 捐献榜 / 开源声明 | ✅ 已完成 | 无 |
| **TTS 神经音色** | ✅ **已完成并实测** | 无 |
| 生图 | ❌ | ggml CMake 目标冲突 + 源码未取回；实测定标需真机 arm64 |
| 识图（多模态） | ❌ | 需要从 `llama_jni.cpp` 加 `libmtmd` 支持一路做到 Kotlin，**不是接线问题** |
| MCP 客户端 | ❌ | 只做了能力声明，无协议实现 |

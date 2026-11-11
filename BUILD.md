# BUILD.md · 编译与验证

## -1. 最省事：让 GitHub 帮你编

不想在本机碰 NDK/CMake/llama.cpp 源码的话，**别本地编** —— 推上去让 Actions 编：

```bash
git push origin main                       # → Actions 自动出 APK
gh run list --repo isnananya/LocalMind-Android
gh run download <run-id> --repo isnananya/LocalMind-Android --name LocalMind-release-apk
```

要带版本号发正式包：

```bash
git tag v1.0.8 && git push origin v1.0.8   # → 自动发 Release，APK 挂在 Release 上
```

云端工作流（`.github/workflows/android.yml`）做的事：

1. 克隆 **llama.cpp v0.6.0**（版本钉死，见下）；
2. 装 JDK 17 + Android SDK 35 + NDK 27.0.12077973 + CMake 3.22.1；
3. `assembleDebug` + `assembleRelease`（release 用 debug key 签名，仅供自用）；
4. 两个 APK 都作为 artifact 上传。

首次全新构建约 20 分钟（llama.cpp 有几百个 C++ 编译单元）；之后命中 CMake/Gradle
缓存通常 5–8 分钟。

**为什么版本要钉死**：native 层 `app/src/main/cpp/llama_jni.cpp` 是照着 llama.cpp
**v0.6.0** 的 API 写的（`llama_init_from_model` / `llama_get_memory` /
`llama_model_has_encoder` / `mtmd_*`）。上游到 v0.9.0 之间改过一批接口，
克隆 main 会直接编译失败，而且是那种看不懂为什么的失败。
本机 `third_party/llama.cpp` 是 v0.6.0 的源码快照，`include/llama.h` 与
`tools/mtmd/mtmd.h` 的 blob 哈希与上游 v0.6.0 完全一致（`9bca8ee` / `bcd76a9`），
所以钉住 v0.6.0 就是钉住本机验证过的那个版本。

要升级 llama.cpp：**先在本机改 native 层并跑通**，再改工作流里的 `LLAMA_CPP_REF`，
不要单独改版本号。

生图后端（`prebuilt-sd.yml`）同理：钉在 sd.cpp `d763dae3b` + 仓库内的
`third_party/patches/sd-conv-direct.patch`（本机验证过的是这个组合）。

## 0. 已验证的环境（本机实际跑通）

| 组件 | 版本 / 路径 |
| --- | --- |
| JDK | Microsoft OpenJDK 17.0.20 · `C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot` |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` |
| platform | android-35、android-36 |
| build-tools | 34.0.0、35.0.0 |
| NDK | 27.0.12077973 |
| CMake | 3.22.1（`%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1\bin`） |
| Gradle | wrapper → 8.11.1 |
| AGP / Kotlin | 8.5.2 / 2.0.20 |
| Compose BOM | 2024.09.02 |
| 模拟器 | AVD `lm35`（android-35 google_apis x86_64） |

> ⚠️ **Gradle 发行包**：本机 `services.gradle.org` **直连超时**，
> 卡在 `Downloading gradle-8.11.1-all.zip` 不动。已把
> `gradle/wrapper/gradle-wrapper.properties` 的 `distributionUrl` 换成腾讯云镜像：
>
> ```
> distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-all.zip
> ```
>
> 实测 230 MB / 约 1 分钟。若你的机器直连正常，可以换回
> `https\://services.gradle.org/distributions/gradle-8.11.1-all.zip`；
> 只有 `-bin` 的机器改成 `gradle-8.11.1-bin.zip`。

## 1. 准备

```bash
git clone --depth 1 --branch v0.6.0 https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp
# 或直接取 tarball（避免 clone 失败）：
# curl -L -o llama.tar.gz https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/v0.6.0
```

> 📡 **网络：先直连。** 本机实测 `git clone` 直连 GitHub 是**通的**（浅克隆 15.6 s / 252 MB），
> 但 **`gh-proxy` 系列对 codeload 归档一律 403，已失效**。
> 另有一个坑：`codeload` **会忽略 `Range`**，用 `curl -r 0-1023` 试探会真的下全量。
> 完整实测记录、子模块补取方式与复测命令见 [docs/DOWNLOAD.md](docs/DOWNLOAD.md)。

### 1.1 KleidiAI：arm64 构建会额外拉一份源码

ggml 在 arm64 上通过 FetchContent 拉取 `KleidiAI`（约 2.4 MB）。网络不通时
`configureCMakeRelease[arm64-v8a]` 会在下载阶段失败。

两种处理：

```bash
# 方式 A：手动下载后放到固定位置（推荐，构建不再联网）
curl -L -o kleidiai.tar.gz https://gh-proxy.com/https://github.com/ARM-software/kleidiai/releases/download/v1.24.0/kleidiai-v1.24.0-src.tar.gz
mkdir -p third_party/kleidiai && tar -xzf kleidiai.tar.gz -C third_party/kleidiai --strip-components=1
```

Gradle 检测到 `third_party/kleidiai/` 会自动加
`-DFETCHCONTENT_SOURCE_DIR_KLEIDIAI=<绝对路径>`，跳过下载。
该目录已在 `.gitignore` 里，不进版本库。

CMake 配置时会校验 `third_party/llama.cpp/include/llama.h`，缺失会直接报错并提示这行命令。

`local.properties` 里的 `sdk.dir` 指向本机 SDK：

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

## 2. 编译

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:assembleDebug                              # arm64-v8a + armeabi-v7a
./gradlew :app:assembleRelease                            # debug 签名，可直接安装
./gradlew :app:assembleDebug -PabiList=arm64-v8a,x86_64   # 需要模拟器时
./gradlew :app:assembleDebug -PabiList=x86_64             # 只要模拟器（最快）
```

### 神经语音的原生库（可选，但默认会去拉）

```bash
./gradlew :app:fetchSherpaOnnx -PabiList=arm64-v8a,armeabi-v7a,x86_64
```

从官方 Release 拉 46 MB 的 `sherpa-onnx-v1.13.8-android.tar.bz2`，
只解出 `libonnxruntime.so` / `libsherpa-onnx-jni.so` / `-c-api.so` / `-cxx-api.so`
到 `app/src/main/jniLibs/<abi>/`（每个 ABI 约 36 MB，已 gitignore）。

`assembleDebug` 会自动依赖这个任务。**拿不到时不报错**：sherpa 是可选能力，
缺了神经语音自动不可用、语音页退回系统 TTS。

> 直连 GitHub Releases 在本机**会随机超时**（同一个 URL 有时 200、有时卡住），
> 所以任务里先直连、失败自动换 `gh-proxy.com`。

### 产物体积

| 构建 | 体积 |
| --- | --- |
| `-PabiList=x86_64` | 58.8 MB |
| 默认（arm64-v8a + armeabi-v7a） | 79.7 MB |
| 不含 sherpa 时大约减去 | 22 MB / ABI |

大头是 `libonnxruntime.so`（21–25 MB/ABI）和 `liblocalmind.so`（5.5 MB）。
`onnxruntime` 是 sherpa 的硬依赖，无法裁剪。

### 只验证原生库（绕过 Gradle，快很多）

```bash
cmake -S app/src/main/cpp -B build/native-verify -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_SDK/ndk/27.0.12077973/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release -DGGML_CCACHE=OFF
ninja -C build/native-verify -j6
# 期望产物：build/native-verify/liblocalmind.so （约 20 MB）
```

**日常改 Kotlin 只要 `:app:compileDebugKotlin`**（热守护进程约 1 分钟），
不要 `assembleDebug`。

## 3. 常见问题

| 现象 | 原因 / 解决 |
| --- | --- |
| `llama.cpp sources not found at ...` | 没 clone `third_party/llama.cpp`，见第 1 步 |
| 卡在 `Downloading gradle-8.11.1-all.zip` | `services.gradle.org` 直连超时 → 换腾讯云镜像，见 §0 |
| **`Task ':app:mergeDebugJniLibFolders' uses this output of task ':app:compileStableDiffusion' without declaring an explicit or implicit dependency`** | Gradle 8 的**硬错误**（不是警告），会让 `assembleDebug` 到不了打包。已在 `app/build.gradle.kts` 里给 `merge*JniLibFolders` 加 `dependsOn(fetchStableDiffusion, compileStableDiffusion, fetchSherpaOnnx)`。**加新的写 jniLibs 的任务时必须同步加进去**，否则整条构建再次断掉 |
| `Could not find LLAMA_LIB using the following names: llama` | 旧写法用 `find_library` 找静态库。现在直接链接 `llama` / `ggml` / `ggml-base` 三个 CMake target，不要改回去 |
| Gradle 下载超时 | 换 `distributionUrl` 镜像，或把已解压的 Gradle 加进 PATH 后直接用 `gradle` |
| `NDK not configured` | `sdkmanager --install "ndk;27.0.12077973"`，或改 `ndkVersion` |
| 编译被 OOM Kill | `gradle.properties` 里的 `org.gradle.jvmargs`（默认 3GB）；多 ABI 同时编译时把并发降到 `-j 4` |
| `GGML build version fixed at 1 ... shallow clone` | 只是警告（`--depth 1` 导致），可忽略 |
| 装上打开就退出 | `adb logcat -s LocalMindNative LlamaEngine`；再用应用内 **设置 → 诊断 → 运行自检** |
| 模拟器上加载极慢 | x86_64 走通用 CPU 路径；真机 arm64 才有 dotprod/i8mm 优化。实测 Qwen2.5 1.5B 在模拟器上只有 2.2 tok/s |
| 语音页「神经音色」全是"不可用" | 没跑过 `:app:fetchSherpaOnnx`，APK 里没有原生库。页面 footer 会说明 |
| **`JNI DETECTED ERROR ... NoSuchFieldError: ... OfflineTtsMatchaModelConfig`** | vendored 的 `com.k2fsa.sherpa.onnx` data class 字段和 `.so` 的反射期望不一致。**一个字段都不能删**，见 `Tts.kt` 顶部说明 |

## 4. 运行期验证清单

```bash
# 1. 安装并启动
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.localmind/.MainActivity

# 2. 原生库日志（JNI_OnLoad 会打印 llama.cpp system info）
adb logcat -s LocalMindNative:* LlamaEngine:* ApiServer:*

# 2b. adb 走无线：拔数据线、换姿势调试都不会断（先在 USB 还连着的时候开一次）
adb tcpip 5555 && adb connect <手机IP>:5555
# 之后每条命令都写 -s <手机IP>:5555 —— 同时挂着 USB 和 TCP 两个 transport 时，
# 不写 -s 有可能打到旧的那条。

# 3. 推进一个模型（比手机下载快得多）
adb push qwen2.5-1.5b-instruct-q4_k_m.gguf /sdcard/Download/
# 然后在应用内：模型页 → 粘贴仓库/直链导入，或把文件放进应用模型目录

# 4. 对话与生图都跑一遍后看接口
adb shell curl -s http://127.0.0.1:8080/health

#    ⚠️ 远程/真机联调**别只用 adb forward**：`adb forward tcp:18080 tcp:8080` 只把手机的
#    127.0.0.1 映射到开发机，服务本身仍然只监听回环，同网段的其它设备（平板、另一台
#    电脑、手机上的客户端）都连不上。两条都要做：
#      1) 应用内「我的 -> 本机 API 服务」打开【允许局域网访问】
#         页面上的「局域网设备」会显示真实地址，例如 http://192.168.1.14:8080
#      2) 开发机直接请求那个地址：
#         curl http://192.168.1.14:8080/v1/chat/completions -d '...'

# 5. 应用内自检（设置 → 诊断 → 运行自检）覆盖：
#    原生库 / 引擎句柄 / 图像渲染 / 扩散后端 / API 端口 / 模型状态 / 存储 / 内存
```

## 5. 发布构建说明

`assembleRelease` 默认使用 **debug 签名**（`signingConfig = debug`），因为这个 APK 用于侧载分发。
要正式发布时，在 `app/build.gradle.kts` 里加自己的 `signingConfigs.release` 并替换即可。

## 6. 已实测通过的验证记录

### 6.1 本轮（2026-10，Android 15 API 35 x86_64 模拟器，可见窗口）

从零到跑通整条链路，包括修掉三处构建硬错误（见 §3 与 [docs/STATUS.md](docs/STATUS.md)）。

| 验证项 | 结果 |
| --- | --- |
| `:app:assembleDebug -PabiList=x86_64` | ✅ 58.8 MB |
| `:app:assembleDebug`（arm64-v8a + armeabi-v7a） | ✅ 79.7 MB |
| 安装 + 启动 | ✅ 无崩溃 |
| 原生库加载 | ✅ `llama backend initialised (CPU : SSE3 = 1 \| SSSE3 = 1 \| REPACK = 1)` |
| 真实模型加载（Qwen2.5 1.5B Q4_K_M，ctx 4096，3 线程） | ✅ `model ready` |
| 中文对话（`/v1/chat/completions`） | ✅ 问"你现在运行在哪里" → `我在阿里云的服务器上。`，54 tokens，2.23 tok/s |
| `/health` | ✅ `model_loaded:true, context:4096, model:"Qwen2.5 1.5B Instruct"` |
| `/v1/models` | ✅ 32 个条目 |
| `/v1/embeddings` | ✅ 384 维，非零维 14 |
| `/v1/images/generations` + `GET /v1/images/{file}` | ✅ 512×512 PNG，271 KB，中文文件名经 URL 编码往返 |
| `/v1/audio/speech`（程序化） | ✅ `audio/wav`，62,666 字节，`RIFF` 头 |
| `/v1/system` | ✅ 真实 tok/s、内存规划、原生 CPU 特性 |
| **神经语音加载**（sherpa-onnx VITS 小雅） | ✅ `1 个说话人 @ 22050 Hz` |
| **神经语音合成**（同一个 API 端点） | ✅ 198 KB / 4.49 s / RMS 2097 / peak 14296 / 98% 非零采样 |
| 「我的 → 语音与音色」进入 VoiceScreen | ✅ 截图确认 |
| 「我的 → 本机 API 服务」进入 ApiScreen | ✅ 截图确认 |
| 代理预设只剩可用项 | ✅ 截图确认三个 pill |
| 端口转发跑真实 HTTP 请求 | ✅ `adb forward tcp:18080 tcp:8080` |

> 模拟器用 `-gpu auto`、`-memory 4096`、`-cores 4` 启动。
> **注意**：`-no-window` 会让窗口不可见，调试时不要带这个参数。

### 6.2 历史记录（Android 15 API 35 x86_64 模拟器）

| 验证项 | 结果 |
| --- | --- |
| APK 编译（arm64-v8a / armeabi-v7a / x86_64） | ✅ `app-debug.apk` 22.6 MB、`app-release.apk` 16.8 MB |
| UI 渲染（iOS 风格五标签页） | ✅ 聊天 / 生图 / 模型 / 语音 / 接口 全部截图确认 |
| GGUF 头解析 + 内存规划器 | ✅ 真实解析出 `llama · 5 层 · d=64 · ctx 2048`，并给出建议上下文/线程 |
| 真实文本生成 | ✅ `/v1/chat/completions` 返回 `"He told a new police of frock. He asked his friend, a little bird named B"` |
| SSE 流式 | ✅ 收到 23 条 `data:` 事件并以 `[DONE]` 结束 |
| `/v1/models` | ✅ 13 个条目 |
| 未知路径 | ✅ 返回 404 |

### 验证过程中发现并修掉的三个真 bug

1. **JNI 回调用了 `CallVoidMethod` 调用 `kotlin.jvm.functions.Function1.invoke`**
   （该方法返回 `Object`）→ ART 直接 `SIGABRT`。
   改为 `CallObjectMethod` 并释放返回的 `Unit` 引用。
2. **只加载了编码器的向量模型（BGE 等）不能走 `llama_decode`** → llama.cpp 内部 `ggml_abort` 崩进程。
   现在加载时用 `llama_model_has_decoder` 检测，聊天入口直接给出可读提示而不是崩溃。
3. **HTTP 请求体按 UTF-8 字符串读，却拿字节长度当字符长度** → 中文提示词被截断成 `?????`。
   改为按原始字节读取 `Content-Length`，响应头也改为 ISO-8859-1 输出。

另外补上了键盘发送键（IME Send）没有绑定发送动作的问题。

## 7. llama.cpp v0.6.0 升级记录

从 `b4567`（2025-01）升到 **v0.6.0**（2026-10）。动机：需要一个专门的「决策模型」
（System One / Jev 形态）做功能判断，而 FunctionGemma 等新架构在 b4567 上无法加载。

升级后必须修的 **4 处破坏性 API 变更**（都在 `app/src/main/cpp/llama_jni.cpp`）：

| 旧 | 新 | 说明 |
| --- | --- | --- |
| `llama_kv_cache_clear(ctx)` | `llama_memory_clear(llama_get_memory(ctx), true)` | KV 缓存并入统一的 memory 抽象 |
| `mp.use_mmap` / `mp.use_mlock` | `mp.load_mode = LLAMA_LOAD_MODE_MMAP` | 两个 bool 合成 `enum llama_load_mode` |
| `cp.flash_attn = false` | `cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED` | bool 变成三态枚举 |
| `llama_sampler_init_penalties(last_n, rep, freq, pres)` | 多一个首位 `n_vocab` 参数 | 用 `llama_vocab_n_tokens(llama_model_get_vocab(model))` |

**已在模拟器回归通过**（x86_64、Qwen2.5-1.5B、ctx 4096、3 线程）：

- 中文问答 → `法国的首都是巴黎。`
- 日文（验证 JNI UTF-8 边界处理没被破坏）→ `こんにちは！…`
- SSE 流式 → 5 个 data 事件 + `[DONE]`
- `/v1/embeddings`（encoder-only 路径）→ 384 维
- 32 个模型条目、无崩溃

### 已知限制

- **公开头文件里仍然没有 logprob API**（`llama.h` 中 `logprob` 出现 0 次），
  所以「Jev 形态」的概率输出目前要自己对 `llama_get_logits_ith()` 做 softmax，
  而不是直接读现成的对数概率。
- 上游 `systemone`/decision API 的 PR 仍处于 open 状态（模型无关实现 #29832、
  CLI 选项 #30113、Decision 2.0 GGUF #30161），合并后可以直接对接。

### 回滚

旧版完整备份在 `third_party/llama.cpp.b4567/`，原 commit 记录在
`build/llama-b4567-commit.txt`。回滚只需把目录换回去并还原上述 4 处代码改动。
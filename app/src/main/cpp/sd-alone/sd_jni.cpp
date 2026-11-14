// sd_jni.cpp — 生图后端的**独立** JNI 层。
//
// ## 为什么生图后端要单独一个 .so
//
// 生图和对话各自 vendored 了一份 ggml，而两份 ggml 的 ABI **不一样**：
// sd.cpp 编它的 ggml 时带 `-DGGML_MAX_NAME=160`，llama.cpp 用默认的 64。
// `char name[GGML_MAX_NAME]` 是 `struct ggml_tensor` 里的**真实字段**，
// 所以两边的 `sizeof(ggml_tensor)` 差 96 字节 —— `extra` / `view_src`
// 落在不同偏移上。这不是链接错误，是**静默的内存错乱**。
//
// 把两份 ggml 塞进同一个 .so 时只能靠"全局把宏统一成 160"来绕，
// 代价是 llama 跑在 sd.cpp 那份打过补丁的 ggml 上 —— 能跑，但很脆：
// 以后任一方升级 ggml，这个巧合就没了，而且不会有任何编译期提示。
//
// 分成两个 .so 之后，bionic 在解析符号时**先看库自己**，
// 两个 ggml 各自留在自己的库里，互不干扰。这才是结构上正确的做法。
//
// ## 这个文件里有什么
//
// 只有四个 JNI 入口，转调 stable-diffusion.h 的 C API。
// 程序化渲染器（`nativeRender` / `nativePromptHash`）留在 image_jni.cpp ——
// 那是主程序自带的能力，不需要下载任何东西就能用。
//
// ## 加载方式
//
// 这个库**不在 APK 里**。用户第一次要真生图时，主程序下载它到
// `filesDir/native/<abi>/liblocalmind-sd.so`，然后 `System.load`。
// 所以这里的符号必须自包含：任何未定义符号都会让 System.load 失败，
// 而 Android 链接器**允许**共享库带未定义符号，问题只在运行期暴露。
// 构建脚本必须用 `-Wl,--no-undefined` 把这类问题挡在编译期。

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "stable-diffusion.h"

#define LOG_TAG "LocalMindSD"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 常驻上下文：一个模型只加载一次。
//
// 加载一个 SD checkpoint 要读一两 GB，每次生图重新加载是不能接受的，
// 所以 ctx 在进程生命周期内保持存活，只在换模型时重建。
std::mutex g_mutex;
sd_ctx_t *g_ctx = nullptr;
std::string g_ctx_path;
// 建上下文时用的配置（路径 + 线程 + flash attention 模式）。
// 加速项只在建 ctx 时生效，所以它变了就必须重建 —— 这就是「改了没反应」的解药。
std::string g_ctx_key;
std::string g_flash_mode = "auto";

// 超分上下文独立于生图上下文：它不参与采样，两者可以同时加载。
// 用单独的锁，超分不该被正在进行的一次生图挡住。
std::mutex g_up_mutex;
upscaler_ctx_t *g_up = nullptr;
std::string g_up_key;

void release_locked() {
    if (g_ctx != nullptr) {
        free_sd_ctx(g_ctx);
        g_ctx = nullptr;
    }
    g_ctx_path.clear();
    g_ctx_key.clear();
}

/**
 * 确保 g_ctx 对应 modelPath + 当前加速配置。调用方必须已持有 g_mutex。
 *
 * **线程数由调用方给**（Kotlin 侧按大核数算），不再用 hardware_concurrency()：
 * 手机上那是「全部核心」，包括小核。扩散模型的 UNet 是内存带宽瓶颈，
 * 摊到小核上不但不快，还会因为调度和缓存同步更慢 —— 和 llama 那边同一个道理。
 *
 * @param threads 0 = 退回 hardware_concurrency()
 * @param flashAttnMode on / off / auto；空串 = 保持 sd.cpp 的默认
 */
bool ensure_loaded_locked(const std::string &modelPath, int32_t threads, const std::string &flashAttnMode) {
    // 配置变了也要重建上下文（加速项只在建 ctx 时生效），所以把配置编进 key。
    const std::string key = modelPath + "|" + std::to_string(threads) + "|" + flashAttnMode;
    if (g_ctx != nullptr && g_ctx_key == key) return true;
    release_locked();
    if (modelPath.empty()) return false;

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = modelPath.c_str();
    // mmap 让加载不必把整个 checkpoint 读进内存 —— 手机上这是能不能跑起来的关键。
    params.enable_mmap = true;
    params.n_threads = threads > 0 ? threads
                                   : static_cast<int>(std::thread::hardware_concurrency());
    if (params.n_threads <= 0) params.n_threads = 4;
    params.wtype = SD_TYPE_F16;
    // ---- flash attention ----
    //
    // 原来这里硬编码 false，注释写的是「部分 checkpoint 会算错」。上游早就把这件事
    // 拆开了：flash_attn 是通用开关，diffusion_flash_attn 只管扩散主干，
    // 而新的 flash_attn_mode 支持模块级配置（例如 auto,te=off,vae=on）——
    // 「文本编码器不支持」不再需要连扩散主干一起关掉。
    //
    // sd.cpp 的 CLI 默认是 auto，官方文档明确说 auto 在 CPU 上会选开：
    // 主要收益是**内存**（SD2 768x768 约省 1.4 GB 的注意力矩阵），
    // 而 Android 上内存就是「能不能跑起来」。所以默认 auto，用户可以关。
    g_flash_mode = flashAttnMode;
    if (!g_flash_mode.empty()) {
        params.flash_attn_mode = g_flash_mode.c_str();
    }

    g_ctx = new_sd_ctx(&params);
    if (g_ctx == nullptr) {
        LOGE("new_sd_ctx 失败：%s", modelPath.c_str());
        return false;
    }
    g_ctx_key = key;
    g_ctx_path = modelPath;
    LOGI("模型已加载：%s（%s）threads=%d flash_attn=%s", modelPath.c_str(),
         sd_get_model_version_name(g_ctx) ? sd_get_model_version_name(g_ctx) : "未知版本",
         params.n_threads, g_flash_mode.empty() ? "default" : g_flash_mode.c_str());
    return true;
}

/**
 * 进度回调。
 *
 * sd.cpp 的 `sd_set_progress_callback` 是**全局**的（没有 per-context 版本），
 * 而我们的生图本来就是 g_mutex 串行，所以「装一个、跑完撤掉」是安全的。
 *
 * 为什么走具名接口而不是直接 invoke lambda —— 和 llama 那边同一个坑：
 * R8 会为传给原生的 lambda 合成新类，合成发生在 keep 规则之后，release 包里
 * `invoke` 会消失。所以 Kotlin 侧必须是 `ProgressSink` 具名接口 + 具名实现类。
 */
struct progress_sink {
    JNIEnv * env = nullptr;
    jobject  cb  = nullptr;

    static void trampoline(int step, int steps, float /*time*/, void * data) {
        auto * self = static_cast<progress_sink *>(data);
        if (self == nullptr || self->env == nullptr || self->cb == nullptr) return;
        if (self->env->ExceptionCheck()) return;
        jclass cls = self->env->GetObjectClass(self->cb);
        if (cls == nullptr) return;
        jmethodID mid = self->env->GetMethodID(cls, "onProgress", "(II)V");
        if (mid != nullptr) {
            self->env->CallVoidMethod(self->cb, mid, (jint) step, (jint) steps);
        } else {
            // 旧 APK 不会传这个对象；退到老接口时也走这里。清掉 NoSuchMethodError。
            self->env->ExceptionClear();
        }
        self->env->DeleteLocalRef(cls);
        if (self->env->ExceptionCheck()) self->env->ExceptionClear();
    }
};

}  // namespace

extern "C" {

/**
 * 能力探测 / 模型加载。
 *
 * **空路径 = "这个后端在不在"**，不是"加载空模型"。
 * 这是 [ImageBridge.hasDiffusionBackend] 的契约：它传空串来问
 * "这个库能加载吗"，能加载就说明后端可用。
 *
 * 返回 JNI_TRUE 只表示**库可用**，不表示模型加载成功 ——
 * 后者由非空路径的调用返回。
 */
JNIEXPORT jboolean JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdLoad(JNIEnv *env, jobject /*thiz*/,
                                                         jstring modelPath) {
    if (modelPath == nullptr) return JNI_FALSE;
    const char *raw = env->GetStringUTFChars(modelPath, nullptr);
    if (raw == nullptr) return JNI_FALSE;
    std::string path(raw);
    env->ReleaseStringUTFChars(modelPath, raw);

    // 空路径：这个库被成功加载了，所以后端可用。
    // 真正的"模型能不能用"要看非空路径的结果。
    if (path.empty()) {
        LOGI("扩散后端可用（%s）", sd_get_system_info() ? sd_get_system_info() : "cpu");
        return JNI_TRUE;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    return ensure_loaded_locked(path, 0, g_flash_mode) ? JNI_TRUE : JNI_FALSE;
}

/**
 * 带加速参数的加载（线程数 / flash attention 模式）。
 *
 * 和 llama 那边同一个理由：**不动老函数的参数表**。JNI 的短名解析不含参数，
 * 改了签名之后一个旧 .so 被新代码调到就是按错误参数表读寄存器。
 */
JNIEXPORT jboolean JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdLoadEx(
        JNIEnv * env, jobject, jstring modelPath, jint threads, jstring flashMode) {
    if (modelPath == nullptr) return JNI_FALSE;
    const char *raw = env->GetStringUTFChars(modelPath, nullptr);
    if (raw == nullptr) return JNI_FALSE;
    std::string path(raw);
    env->ReleaseStringUTFChars(modelPath, raw);

    std::string mode = "auto";
    if (flashMode != nullptr) {
        const char *rawMode = env->GetStringUTFChars(flashMode, nullptr);
        if (rawMode != nullptr) {
            mode = rawMode;
            env->ReleaseStringUTFChars(flashMode, rawMode);
        }
    }

    if (path.empty()) {
        LOGI("扩散后端可用（%s）", sd_get_system_info() ? sd_get_system_info() : "cpu");
        return JNI_TRUE;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    return ensure_loaded_locked(path, threads, mode) ? JNI_TRUE : JNI_FALSE;
}

/**
 * 生图。
 *
 * 把结果写成 ARGB 整数填进 `pixels`（和 Kotlin 侧 `Bitmap.setPixels` 的
 * 布局一致：行优先、每像素一个 int）。
 *
 * @return 写入的像素数；失败返回 -1。
 */
/**
 * 生图的公共实现。两个 JNI 入口都走它 —— 老入口补默认值，新入口带加速与步数。
 *
 * @param negative  负面提示词，空串 = 不加（这个应用默认不给用户这个概念）
 * @param steps     采样步数，<=0 = 用 checkpoint 自己的默认
 * @param threads   0 = 用建上下文时的值
 * @param flashMode 空串 = 保持已加载上下文的模式
 */
static jint sd_generate_common(
    JNIEnv *env, jstring modelPath, jstring prompt, jstring negative,
    jint width, jint height, jint steps, jint seed, jfloat guidance,
    jint threads, jstring flashMode, jintArray pixels,
    // ---- 1.0.3 起的「通用版」参数（老入口传空）----
    // 采样器 / 调度器用 sd.cpp 自己的字符串词表（euler_a / lcm / dpm++2m …），
    // 空串 = 让 sd.cpp 按模型族自己决定 —— 那本来就是「通用」的默认行为。
    jstring sampler, jstring scheduler, jobject progress,
    // ---- 1.1.0 起的图生图参数（文生图传 null / 0）----
    // initArgb：初始图的 ARGB_8888 像素（宽 initW、高 initH），null = 文生图。
    // maskGray：蒙版，单通道灰度值放在 int 的低 8 位，255 = 重绘区；null = 整图重绘。
    jintArray initArgb, jint initW, jint initH, jfloat strength, jintArray maskGray) {

    if (modelPath == nullptr || prompt == nullptr || pixels == nullptr) return -1;
    if (width <= 0 || height <= 0) return -1;

    const jsize want = env->GetArrayLength(pixels);
    if (want < width * height) {
        LOGE("像素数组太小：%d < %d", want, width * height);
        return -1;
    }

    const char *rawPath = env->GetStringUTFChars(modelPath, nullptr);
    const char *rawPrompt = env->GetStringUTFChars(prompt, nullptr);
    if (rawPath == nullptr || rawPrompt == nullptr) {
        if (rawPath) env->ReleaseStringUTFChars(modelPath, rawPath);
        if (rawPrompt) env->ReleaseStringUTFChars(prompt, rawPrompt);
        return -1;
    }
    std::string path(rawPath);
    std::string text(rawPrompt);
    env->ReleaseStringUTFChars(modelPath, rawPath);
    env->ReleaseStringUTFChars(prompt, rawPrompt);

    std::string negativeText = "";
    if (negative != nullptr) {
        const char *rawNeg = env->GetStringUTFChars(negative, nullptr);
        if (rawNeg != nullptr) {
            negativeText = rawNeg;
            env->ReleaseStringUTFChars(negative, rawNeg);
        }
    }

    std::string flash = "";
    if (flashMode != nullptr) {
        const char *rawFlash = env->GetStringUTFChars(flashMode, nullptr);
        if (rawFlash != nullptr) {
            flash = rawFlash;
            env->ReleaseStringUTFChars(flashMode, rawFlash);
        }
    }

    std::string samplerText;
    if (sampler != nullptr) {
        const char *raw = env->GetStringUTFChars(sampler, nullptr);
        if (raw != nullptr) { samplerText = raw; env->ReleaseStringUTFChars(sampler, raw); }
    }
    std::string schedulerText;
    if (scheduler != nullptr) {
        const char *raw = env->GetStringUTFChars(scheduler, nullptr);
        if (raw != nullptr) { schedulerText = raw; env->ReleaseStringUTFChars(scheduler, raw); }
    }

    // 进度：装在原生侧，跑完必须撤掉 —— 它是全局回调，留着会在下一次生图时
    // 指着一个已经失效的 jobject。
    progress_sink sink{ env, progress };
    sd_set_progress_callback(progress != nullptr ? progress_sink::trampoline : nullptr,
                             progress != nullptr ? &sink : nullptr);

    struct progress_guard {
        ~progress_guard() { sd_set_progress_callback(nullptr, nullptr); }
    } guard_progress;

    // ---- 图生图的入参准备 ----
    //
    // 放在进锁之前：这里只做像素格式转换（纯 CPU、与模型无关），
    // 没必要占着 g_mutex 让诊断页之类的调用方排队。
    //
    // 三件事：
    //  1. ARGB_8888 int → sd.cpp 要的交错 RGB（每像素 3 字节）
    //  2. 蒙版转单通道灰度（sd.cpp 约定 255 = 要重绘）
    //  3. 尺寸校验 —— 初始图必须和请求的宽高一致，否则 VAE 编码出来的
    //     latent 和采样时的噪声形状对不上，sd.cpp 会直接返回空图。
    std::vector<uint8_t> initRgb;
    std::vector<uint8_t> maskBytes;
    sd_image_t initImage{0, 0, 0, nullptr};
    sd_image_t maskImage{0, 0, 0, nullptr};

    if (initArgb != nullptr) {
        if (initW <= 0 || initH <= 0) return -1;
        if (initW != width || initH != height) {
            LOGE("图生图尺寸不一致：初始图 %dx%d，请求 %dx%d", initW, initH, (int) width, (int) height);
            return -1;
        }
        const jsize n = env->GetArrayLength(initArgb);
        if (n < initW * initH) {
            LOGE("初始图像素数组太小：%d < %d", n, initW * initH);
            return -1;
        }
        std::vector<jint> src(static_cast<size_t>(initW) * initH);
        env->GetIntArrayRegion(initArgb, 0, initW * initH, src.data());
        initRgb.resize(static_cast<size_t>(initW) * initH * 3);
        for (size_t i = 0; i < src.size(); i++) {
            const jint v = src[i];
            initRgb[i * 3 + 0] = static_cast<uint8_t>((v >> 16) & 0xFF);
            initRgb[i * 3 + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
            initRgb[i * 3 + 2] = static_cast<uint8_t>(v & 0xFF);
        }
        initImage = sd_image_t{static_cast<uint32_t>(initW), static_cast<uint32_t>(initH), 3,
                               initRgb.data()};

        if (maskGray != nullptr) {
            const jsize mn = env->GetArrayLength(maskGray);
            if (mn < initW * initH) {
                LOGE("蒙版数组太小：%d < %d", mn, initW * initH);
                return -1;
            }
            std::vector<jint> mv(static_cast<size_t>(initW) * initH);
            env->GetIntArrayRegion(maskGray, 0, initW * initH, mv.data());
            maskBytes.resize(static_cast<size_t>(initW) * initH);
            for (size_t i = 0; i < mv.size(); i++) {
                maskBytes[i] = static_cast<uint8_t>(mv[i] & 0xFF);
            }
            maskImage = sd_image_t{static_cast<uint32_t>(initW), static_cast<uint32_t>(initH), 1,
                                   maskBytes.data()};
        }
    }

    sd_image_t *images = nullptr;
    int num_images = 0;

    {
        std::lock_guard<std::mutex> lock(g_mutex);
        // 线程数 / flash attention 变了会在这里重建上下文 —— 这就是
        // 「设置里改了没反应」的解药：加速项只在建 ctx 时生效。
        if (!ensure_loaded_locked(path, threads, flash.empty() ? g_flash_mode : flash)) return -1;

        sd_img_gen_params_t gen;
        sd_img_gen_params_init(&gen);
        gen.prompt = text.c_str();
        // 负面提示词默认留空：这个应用没有给用户暴露这个概念，
        // 硬塞一段通用负面词会改变模型的原始行为，不如不加。
        gen.negative_prompt = negativeText.c_str();
        gen.width = static_cast<int>(width);
        gen.height = static_cast<int>(height);
        gen.seed = static_cast<int64_t>(seed);
        gen.batch_count = 1;

        // ---- 图生图 / 局部重绘 ----
        //
        // strength = 从初始图出发再往前加多少噪声（0 = 原图不动，1 = 完全重画）。
        // 0.35–0.5 是「换画风但保住构图」的常用区间，0.7+ 基本会改掉构图。
        // 蒙版存在时 sd.cpp 走 inpaint 分支：蒙版外保留初始图，蒙版内按
        // strength 重画 —— 所以蒙版模式下 strength 给 0.9~1.0 才有效果。
        if (initImage.data != nullptr) {
            gen.init_image = initImage;
            gen.strength = strength > 0.f ? (strength > 1.f ? 1.f : strength) : 0.6f;
            if (maskImage.data != nullptr) {
                gen.mask_image = maskImage;
            }
            LOGI("图生图：%dx%d strength=%.2f%s", initW, initH, gen.strength,
                 maskImage.data != nullptr ? " 带蒙版" : "");
        }
        // ---- 步数：这是 CPU 出图最大的一根杠杆 ----
        //
        // 步数与耗时几乎线性：SD 2.1 的默认 20 步在手机上就是「等到怀疑人生」，
        // 而同一个 checkpoint 用 8 步仍然出得来图（质量差一些）。原来这里完全交给
        // sd.cpp 的默认值，用户没有任何办法把它调快 —— 现在由调用方给，
        // <=0 时才回落到 checkpoint 自己的默认。
        if (steps > 0) {
            gen.sample_params.sample_steps = steps;
        }
        gen.sample_params.guidance.txt_cfg = guidance > 0.f ? guidance : 7.0f;

        // ---- 采样器 / 调度器 ----
        //
        // 「通用版」的关键一条：**不写死 Euler**。sd.cpp 会按模型族给出默认值
        //（sd_get_default_sample_method / sd_get_default_scheduler），那是它自己
        // 调过的结论；我们只在**明确知道更好**的时候覆盖 —— 也就是 LCM / SDXS /
        // Turbo 这一类蒸馏模型：它们必须配 LCM 采样器 + LCM 调度器才像样，
        // 用默认 Euler 在 2-4 步下会糊。
        if (!samplerText.empty()) {
            const auto m = str_to_sample_method(samplerText.c_str());
            if (m != SAMPLE_METHOD_COUNT) {
                gen.sample_params.sample_method = m;
                LOGI("采样器覆盖：%s", samplerText.c_str());
            } else {
                LOGW("不认识的采样器：%s（用模型默认）", samplerText.c_str());
            }
        }
        if (!schedulerText.empty()) {
            const auto s = str_to_scheduler(schedulerText.c_str());
            if (s != SCHEDULER_COUNT) {
                gen.sample_params.scheduler = s;
                LOGI("调度器覆盖：%s", schedulerText.c_str());
            } else {
                LOGW("不认识的调度器：%s（用模型默认）", schedulerText.c_str());
            }
        }

        LOGI("生图：%dx%d steps=%d seed=%d cfg=%.1f threads=%d flash=%s",
             (int) width, (int) height, (int) gen.sample_params.sample_steps, (int) seed,
             gen.sample_params.guidance.txt_cfg, threads,
             flash.empty() ? g_flash_mode.c_str() : flash.c_str());

        if (!generate_image(g_ctx, &gen, &images, &num_images) || num_images < 1 ||
            images == nullptr || images[0].data == nullptr) {
            LOGE("generate_image 失败");
            if (images != nullptr) free_sd_images(images, num_images);
            return -1;
        }
    }

    // ---- 转成 ARGB ----
    //
    // sd.cpp 给的是交错的 RGB 或 RGBA（取决于 checkpoint 里 VAE 的输出通道数），
    // R/G/B 永远是第 0/1/2 字节。Kotlin 侧要的是 ARGB int，所以要自己拼。
    const sd_image_t &img = images[0];
    const int w = static_cast<int>(img.width);
    const int h = static_cast<int>(img.height);
    const int ch = static_cast<int>(img.channel);
    if (w <= 0 || h <= 0 || (ch != 3 && ch != 4)) {
        LOGE("图像格式不支持：%dx%d channel=%d", w, h, ch);
        free_sd_images(images, num_images);
        return -1;
    }

    // 模型可能返回和请求不同的尺寸（某些 checkpoint 会吸附到 64 的倍数）。
    // 按实际尺寸写，并且只在能装下时才写，超出就报错而不是越界。
    const int copyW = w < width ? w : static_cast<int>(width);
    const int copyH = h < height ? h : static_cast<int>(height);
    std::vector<jint> argb(static_cast<size_t>(copyW) * copyH);

    for (int y = 0; y < copyH; y++) {
        const uint8_t *row = img.data + static_cast<size_t>(y) * w * ch;
        for (int x = 0; x < copyW; x++) {
            const uint8_t *p = row + static_cast<size_t>(x) * ch;
            argb[static_cast<size_t>(y) * copyW + x] =
                (0xFF << 24) | (p[0] << 16) | (p[1] << 8) | p[2];
        }
    }

    // setPixels 要求整块缓冲。宽度不一致时（模型吸附过）逐行写，
    // 避免把上一行的尾巴当成这一行的开头。
    if (copyW == width) {
        env->SetIntArrayRegion(pixels, 0, copyW * copyH, argb.data());
    } else {
        for (int y = 0; y < copyH; y++) {
            env->SetIntArrayRegion(pixels, y * width, copyW, argb.data() + static_cast<size_t>(y) * copyW);
        }
    }

    free_sd_images(images, num_images);
    return copyW * copyH;
}

/**
 * 老入口：不带步数与加速参数。
 *
 * 保留它是为了**已经在用户手机上的旧插件包**：新版 APK 会优先调下面那个 Ex，
 * 拿不到符号（旧 .so）时自动退回这里 —— 老插件仍然能出图，只是拿不到新开关。
 */
JNIEXPORT jint JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdGenerate(
    JNIEnv *env, jobject, jstring modelPath, jstring prompt,
    jint width, jint height, jint seed, jfloat guidance, jintArray pixels) {
    return sd_generate_common(env, modelPath, prompt, nullptr, width, height, 0,
                              seed, guidance, 0, nullptr, pixels,
                              nullptr, nullptr, nullptr, nullptr, 0, 0, 0.f, nullptr);
}

/**
 * 新入口：步数 / 负面提示词 / 线程数 / flash attention 都能传。
 *
 * @param steps 0 = checkpoint 默认
 * @param threads 0 = 建上下文时的值
 * @param flashMode 空串 = 保持已加载的模式（on/off/auto）
 */
JNIEXPORT jint JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdGenerateEx(
    JNIEnv *env, jobject, jstring modelPath, jstring prompt, jstring negative,
    jint width, jint height, jint steps, jint seed, jfloat guidance,
    jint threads, jstring flashMode, jintArray pixels) {
    return sd_generate_common(env, modelPath, prompt, negative, width, height, steps,
                              seed, guidance, threads, flashMode, pixels,
                              nullptr, nullptr, nullptr, nullptr, 0, 0, 0.f, nullptr);
}

/**
 * 1.0.3 的「通用版」入口：采样器 / 调度器 / 逐帧进度都能传。
 *
 * 为什么又是**新函数名**而不是改 Ex 的参数表：JNI 短名解析不含参数，改了之后
 * 用户手机上那个已经装好的旧插件会被按错误参数表调到 —— 不是报错，是崩溃。
 * 新函数 + Kotlin 侧 try/catch 回退，是唯一安全的加法。
 *
 * @param sampler   sd.cpp 的采样器名（euler_a / lcm / dpm++2m / dpm++2m_sde …），
 *                  空串 = 用模型族自己的默认值
 * @param scheduler 调度器名（discrete / karras / exponential / lcm / sgm_uniform …），
 *                  空串 = 用默认
 * @param progress  逐帧进度回调（可为 null）；实现必须叫 onProgress(II)V
 */
JNIEXPORT jint JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdGenerateEx2(
    JNIEnv *env, jobject, jstring modelPath, jstring prompt, jstring negative,
    jint width, jint height, jint steps, jint seed, jfloat guidance,
    jint threads, jstring flashMode, jstring sampler, jstring scheduler,
    jintArray pixels, jobject progress) {
    return sd_generate_common(env, modelPath, prompt, negative, width, height, steps,
                              seed, guidance, threads, flashMode, pixels,
                              sampler, scheduler, progress, nullptr, 0, 0, 0.f, nullptr);
}

/**
 * 1.1.0：图生图 / 局部重绘入口。
 *
 * 为什么又是一个新函数名：见 Ex2 的注释 —— JNI 短名不带参数签名，改老函数
 * 的参数表会让用户手机上那个已经装好的旧 .so 按错位的栈帧解释参数，直接崩。
 * 新函数 + Kotlin 侧按符号是否存在回退，是唯一安全的加法。
 *
 * @param initArgb 初始图 ARGB_8888（宽 initW、高 initH），必须与 width/height 一致
 * @param strength 0..1，越小越贴近原图
 * @param maskGray 单通道灰度蒙版（低 8 位有效），255 = 重绘；null = 整图重绘
 */
JNIEXPORT jint JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdGenerateEx3(
    JNIEnv *env, jobject, jstring modelPath, jstring prompt, jstring negative,
    jint width, jint height, jint steps, jint seed, jfloat guidance,
    jint threads, jstring flashMode, jstring sampler, jstring scheduler,
    jintArray pixels, jobject progress,
    jintArray initArgb, jint initW, jint initH, jfloat strength, jintArray maskGray) {
    return sd_generate_common(env, modelPath, prompt, negative, width, height, steps,
                              seed, guidance, threads, flashMode, pixels,
                              sampler, scheduler, progress,
                              initArgb, initW, initH, strength, maskGray);
}

/**
 * ESRGAN 放大。独立于生图上下文：它不碰 g_ctx，用自己的一份 upscaler。
 *
 * 为什么值得单独一个入口：手机上出图受**内存**限制，通常只能 384–512 见方；
 * 想拿到 1024 的成品，正确做法是「小图 + 超分」而不是「直接 1024 采样」——
 * 后者在 6 GB 机上会直接 OOM。ESRGAN 的显存/内存开销只有采样的一小部分。
 *
 * 输出几何是**确定的**：固定写成 (inW*factor) x (inH*factor)，模型原生倍数
 * 与 factor 不一致时用最近邻补齐 —— 调用方据此分配数组就够了，不用协商尺寸。
 *
 * @return 写出的宽度（即 inW*factor），失败 -1
 */
JNIEXPORT jint JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdUpscale(
    JNIEnv *env, jobject, jstring esrganPath, jint threads, jint factor,
    jintArray inArgb, jint inW, jint inH, jintArray outArgb) {

    if (esrganPath == nullptr || inArgb == nullptr || outArgb == nullptr) return -1;
    if (inW <= 0 || inH <= 0 || factor < 1 || factor > 8) return -1;

    const jsize inLen = env->GetArrayLength(inArgb);
    const jsize outLen = env->GetArrayLength(outArgb);
    const int outW = inW * factor;
    const int outH = inH * factor;
    if (inLen < inW * inH || outLen < outW * outH) {
        LOGE("超分数组太小：in=%d/%d out=%d/%d", inLen, inW * inH, outLen, outW * outH);
        return -1;
    }

    const char *rawPath = env->GetStringUTFChars(esrganPath, nullptr);
    if (rawPath == nullptr) return -1;
    std::string path(rawPath);
    env->ReleaseStringUTFChars(esrganPath, rawPath);

    std::vector<jint> src(static_cast<size_t>(inW) * inH);
    env->GetIntArrayRegion(inArgb, 0, inW * inH, src.data());
    std::vector<uint8_t> rgb(static_cast<size_t>(inW) * inH * 3);
    for (size_t i = 0; i < src.size(); i++) {
        const jint v = src[i];
        rgb[i * 3 + 0] = static_cast<uint8_t>((v >> 16) & 0xFF);
        rgb[i * 3 + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
        rgb[i * 3 + 2] = static_cast<uint8_t>(v & 0xFF);
    }

    std::lock_guard<std::mutex> lock(g_up_mutex);
    // 路径或线程数变了就重建；ESRGAN 模型只有几十 MB，重建代价可以接受。
    const std::string key = path + "|" + std::to_string(threads);
    if (g_up == nullptr || g_up_key != key) {
        if (g_up != nullptr) {
            free_upscaler_ctx(g_up);
            g_up = nullptr;
        }
        g_up_key.clear();
        const int nThreads = threads > 0 ? threads
                                         : static_cast<int>(std::thread::hardware_concurrency());
        // tile_size 128：分块推理是手机上不 OOM 的关键，整图 1024→4096 一次算
        // 峰值能吃掉几个 GB。分块会有一点点接缝，但比被杀进程好。
        g_up = new_upscaler_ctx(path.c_str(), /*direct=*/false, nThreads > 0 ? nThreads : 4,
                                /*tile_size=*/128, /*backend=*/nullptr, /*params_backend=*/nullptr);
        if (g_up == nullptr) {
            LOGE("超分模型加载失败：%s", path.c_str());
            return -1;
        }
        g_up_key = key;
        LOGI("超分模型已加载：%s threads=%d 原生倍数=%d", path.c_str(), nThreads,
             get_upscaler_model_scale(path.c_str()));
    }

    sd_image_t input{static_cast<uint32_t>(inW), static_cast<uint32_t>(inH), 3, rgb.data()};
    sd_image_t *out = nullptr;
    int n = 0;
    // upscale_factor 传 1：让模型用它自己的原生倍数（RealESRGAN 是 4），
    // 再由下面的重采样统一到调用方要的 factor —— 传错反而会叠乘。
    if (!upscale(g_up, input, 1, &out, &n) || n < 1 || out == nullptr || out[0].data == nullptr) {
        LOGE("upscale 失败");
        if (out != nullptr) free_sd_images(out, n);
        return -1;
    }

    const int sw = static_cast<int>(out[0].width);
    const int sh = static_cast<int>(out[0].height);
    const int sc = static_cast<int>(out[0].channel);
    if (sw <= 0 || sh <= 0 || (sc != 3 && sc != 4)) {
        LOGE("超分输出格式不支持：%dx%d channel=%d", sw, sh, sc);
        free_sd_images(out, n);
        return -1;
    }

    std::vector<jint> dst(static_cast<size_t>(outW) * outH);
    const uint8_t *base = out[0].data;
    if (sw == outW && sh == outH) {
        for (size_t i = 0; i < dst.size(); i++) {
            const uint8_t *p = base + i * sc;
            dst[i] = (0xFF << 24) | (p[0] << 16) | (p[1] << 8) | p[2];
        }
    } else {
        // 最近邻：只在模型原生倍数 != factor 时走到。放大倍数是整数倍，
        // 最近邻不会引入色带，比双线性少一次乘加。
        for (int y = 0; y < outH; y++) {
            const int sy = static_cast<int>((int64_t) y * sh / outH);
            for (int x = 0; x < outW; x++) {
                const int sx = static_cast<int>((int64_t) x * sw / outW);
                const uint8_t *p = base + (static_cast<size_t>(sy) * sw + sx) * sc;
                dst[static_cast<size_t>(y) * outW + x] =
                    (0xFF << 24) | (p[0] << 16) | (p[1] << 8) | p[2];
            }
        }
    }
    env->SetIntArrayRegion(outArgb, 0, outW * outH, dst.data());
    free_sd_images(out, n);
    return outW;
}

/** 这个后端当前生效的配置（诊断页读）。 */
/**
 * 能力位。Kotlin 侧据此决定要不要显示「图生图 / 超分」入口。
 *
 * 为什么不能靠「版本号」：用户手机里可能还躺着从旧插件路径加载的 .so，
 * 而 external 声明只要被调用、符号不存在就直接 UnsatisfiedLinkError。
 * 一个不碰锁、不碰模型的常量返回，是最便宜也最可靠的探测。
 */
JNIEXPORT jint JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdCaps(JNIEnv *, jobject) {
    return 1 /* 图生图 */ | 2 /* 超分 */;
}

/** 这个插件是不是「通用版」（支持采样器覆盖 + 进度回调）。Kotlin 侧据此显示开关。 */
JNIEXPORT jboolean JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdUniversal(JNIEnv *, jobject) {
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdAccelInfo(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::string info = "flash_attn=" + g_flash_mode;
    if (!g_ctx_key.empty()) info += " · 已加载上下文";
    return env->NewStringUTF(info.c_str());
}

/** 显式释放常驻模型（用户在设置里删掉模型时调）。 */
JNIEXPORT void JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdRelease(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    release_locked();
    LOGI("模型已释放");
}

/** 这个后端自己的版本信息，用于诊断页。 */
JNIEXPORT jstring JNICALL
Java_com_localmind_native_image_ImageBridge_nativeSdInfo(JNIEnv *env, jobject /*thiz*/) {
    const char *info = sd_get_system_info();
    return env->NewStringUTF(info ? info : "cpu");
}

}  // extern "C"

// JNI bridge for llama.cpp — LocalMind on-device inference engine.
//
// Design notes
// ------------
// * One engine instance == one loaded model + one context. Kotlin owns the lifecycle
//   behind a Mutex so a single decode runs at a time (llama.cpp contexts are not
//   thread safe).
// * Chat history is re-rendered through llama_chat_apply_template each turn, and
//   the KV cache is kept across turns: the longest common token prefix of the new
//   prompt is reused instead of being recomputed. On a phone prefill is often more
//   expensive than generation, so this is the single biggest latency lever — but it
//   only works when the prompt *prefix* is byte-identical between turns. That is why
//   the volatile parts of the prompt (mood, time, memories, web results) are appended
//   to the newest user message instead of the system prompt: see docs/SPEED.md.
// * Weights are loaded with mmap + no-mlock. That is the Android equivalent of
//   AirLLM's "load layers on demand": pages are faulted in from flash by the kernel
//   and evicted under memory pressure, so a model bigger than free RAM can still be
//   used, just with slower first-token latency.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

// 多模态：视觉投影（mmproj）。llama.cpp 自带这套（tools/mtmd），
// 用官方的 LLAMA_BUILD_MTMD 开关编成独立静态库再链进来，不改它的源码。
#include "mtmd.h"
#include "mtmd-helper.h"

#define LM_TAG "LocalMindNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LM_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LM_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LM_TAG, __VA_ARGS__)

namespace {

struct message {
    std::string role;
    std::string content;
};

/**
 * JNI strings are *modified UTF-8* (CESU-8): supplementary characters such as
 * emoji and some CJK extension glyphs are encoded as surrogate pairs, which is
 * NOT valid UTF-8 and corrupts anything the model or tokenizer sees.
 *
 * Every Java <-> native string crossing therefore goes through GetStringChars
 * (UTF-16) and is converted explicitly. Emoji in a chat message used to mangle
 * the whole prompt; this is the fix.
 */
std::string from_java_utf16(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const jsize len = env->GetStringLength(s);
    if (len <= 0) return {};
    const jchar * chars = env->GetStringChars(s, nullptr);
    if (chars == nullptr) return {};

    std::string out;
    out.reserve((size_t) len * 3);
    for (jsize i = 0; i < len; i++) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && (i + 1) < len) {
            const uint32_t lo = chars[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                i++;
            }
        }
        if (cp < 0x80) {
            out += (char) cp;
        } else if (cp < 0x800) {
            out += (char) (0xC0 | (cp >> 6));
            out += (char) (0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += (char) (0xE0 | (cp >> 12));
            out += (char) (0x80 | ((cp >> 6) & 0x3F));
            out += (char) (0x80 | (cp & 0x3F));
        } else {
            out += (char) (0xF0 | (cp >> 18));
            out += (char) (0x80 | ((cp >> 12) & 0x3F));
            out += (char) (0x80 | ((cp >> 6) & 0x3F));
            out += (char) (0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

/** UTF-8 -> UTF-16 -> jstring. Never overflows a jsize. */
jstring to_java_utf16(JNIEnv * env, const std::string & in) {
    std::vector<jchar> units;
    units.reserve(in.size());
    size_t i = 0;
    const size_t n = in.size();
    while (i < n) {
        const unsigned char c = (unsigned char) in[i];
        uint32_t cp = 0;
        size_t adv = 1;
        if (c < 0x80) { cp = c; }
        else if ((c & 0xE0) == 0xC0 && i + 1 < n) { cp = ((c & 0x1F) << 6) | ((unsigned char) in[i+1] & 0x3F); adv = 2; }
        else if ((c & 0xF0) == 0xE0 && i + 2 < n) { cp = ((c & 0x0F) << 12) | (((unsigned char) in[i+1] & 0x3F) << 6) | ((unsigned char) in[i+2] & 0x3F); adv = 3; }
        else if ((c & 0xF8) == 0xF0 && i + 3 < n) { cp = ((c & 0x07) << 18) | (((unsigned char) in[i+1] & 0x3F) << 12) | (((unsigned char) in[i+2] & 0x3F) << 6) | ((unsigned char) in[i+3] & 0x3F); adv = 4; }
        else { cp = 0xFFFD; }   // invalid byte -> replacement char
        i += adv;

        if (cp >= 0x10000 && cp <= 0x10FFFF) {
            cp -= 0x10000;
            units.push_back((jchar) (0xD800 + (cp >> 10)));
            units.push_back((jchar) (0xDC00 + (cp & 0x3FF)));
        } else if (cp <= 0xFFFF) {
            units.push_back((jchar) cp);
        } else {
            units.push_back((jchar) 0xFFFD);
        }
    }
    return env->NewString(units.data(), (jsize) units.size());
}

std::string jstr(JNIEnv * env, jstring s) { return from_java_utf16(env, s); }

/**
 * 回调方法找不到时只报一次。
 *
 * 放在类之前：generate() 是内联成员函数，用到它时它必须已经声明过。
 */
std::atomic<bool> g_sink_missing{false};

/**
 * 把原生吐出来的 token 片段安全地交给 Kotlin。
 *
 * 两个坑都在这里解决，而且**文本和图像两条路径共用同一份**（复制一份迟早分叉）：
 *
 *  1. llama_token_to_piece() 会把一个多字节 UTF-8 字符切在两个 token 之间
 *     （中日文极常见：一个字 3 字节，而一个 token 可能只有一个字节）。
 *     不完整的尾巴必须**留到下一次**，直接丢掉的话，中文会变成乱码
 *     （实测过：英文正常、中文出来是 你 / こんにち garbage）。
 *  2. 回调走的是 Kotlin 侧的具名接口 TokenSink.onToken(String)V，
 *     不是直接 invoke lambda —— R8 会为传给原生的 lambda 合成新类，
 *     而合成发生在 keep 规则之后，release 包里 invoke 会消失（见下面的长注释）。
 */
struct token_sink {
    JNIEnv *    env = nullptr;
    jobject     cb  = nullptr;
    std::string out;
    std::string pending;

    /** 一个字符需要几个字节；返回 0 表示还凑不齐。 */
    static size_t utf8_need(const std::string & b) {
        if (b.empty()) return 0;
        const unsigned char c0 = (unsigned char) b[0];
        size_t need;
        if (c0 < 0x80)                need = 1;
        else if ((c0 & 0xE0) == 0xC0) need = 2;
        else if ((c0 & 0xF0) == 0xE0) need = 3;
        else if ((c0 & 0xF8) == 0xF0) need = 4;
        else return 1;                       // 非法首字节：原样吐出去
        if (b.size() < need) return 0;
        for (size_t i = 1; i < need; i++) {
            if (((unsigned char) b[i] & 0xC0) != 0x80) return 1;   // 畸形：先冲掉
        }
        return need;
    }

    void emit(const std::string & pieceStr) {
        out += pieceStr;
        if (!env || !cb || env->ExceptionCheck()) return;

        jstring js = to_java_utf16(env, pieceStr);
        if (js == nullptr) return;
        jclass cls = env->GetObjectClass(cb);
        jmethodID mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
        if (mid != nullptr) {
            env->CallVoidMethod(cb, mid, js);
        } else {
            // 没有具名方法：清掉 NoSuchMethodError，退到老接口（R8 下不可靠）。
            env->ExceptionClear();
            jmethodID legacy = env->GetMethodID(cls, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
            if (legacy != nullptr) {
                jobject ignored = env->CallObjectMethod(cb, legacy, js);
                if (ignored != nullptr) env->DeleteLocalRef(ignored);
            } else {
                env->ExceptionClear();
                if (!g_sink_missing.exchange(true)) {
                    LOGE("token callback not found: neither onToken(String)V nor "
                         "invoke(Object)Object - streaming disabled, answer arrives in one piece");
                }
            }
        }
        env->DeleteLocalRef(cls);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    void push(const char * data, size_t n) {
        pending.append(data, n);
        drain(false);
    }

    void drain(bool force) {
        while (!pending.empty()) {
            const size_t need = utf8_need(pending);
            if (need == 0 || need > pending.size()) {
                if (!force) return;          // 等下一个 token 把它补全
                break;
            }
            const std::string pieceStr = pending.substr(0, need);
            pending.erase(0, need);
            emit(pieceStr);
        }
    }
};

// ---------------------------------------------------------------------------
// 推理加速的两个取值映射
// ---------------------------------------------------------------------------
//
// Kotlin 侧传的是**小整数代码**，不是 ggml 的枚举值 —— 枚举值属于 ggml 的内部
// 约定，把它从 Kotlin 传过来等于让两边共用一份会变的常量表（ggml 加一个类型
// 就可能挪位）。代码是稳定的，映射只在这一个地方。

/** 0=off 1=auto 2=on。未知值按 auto。 */
static llama_flash_attn_type flash_mode_of(int32_t code) {
    switch (code) {
        case 0:  return LLAMA_FLASH_ATTN_TYPE_DISABLED;
        case 2:  return LLAMA_FLASH_ATTN_TYPE_ENABLED;
        default: return LLAMA_FLASH_ATTN_TYPE_AUTO;
    }
}

/** 0=F16 1=Q8_0 2=Q4_0。未知值按 F16（最安全）。 */
static ggml_type kv_type_of(int32_t code) {
    switch (code) {
        case 1:  return GGML_TYPE_Q8_0;
        case 2:  return GGML_TYPE_Q4_0;
        default: return GGML_TYPE_F16;
    }
}

static const char * kv_name_of(int32_t code) {
    switch (code) {
        case 1:  return "q8_0";
        case 2:  return "q4_0";
        default: return "f16";
    }
}

class engine {
public:
    llama_model *   model = nullptr;
    llama_context * ctx   = nullptr;

    int32_t n_ctx     = 2048;
    int32_t n_threads = 4;
    int32_t n_batch   = 256;
    int32_t n_ubatch  = 256;

    // ---- 推理加速（运行期开关）----
    //
    // 这三项就是「同样的权重、更少的时间」的全部内容，也是这一轮提速的重点：
    //
    //  * flash attention —— CPU 后端同样生效。长上下文收益最大：人设聊天那种
    //    几千 token 的 system prompt + 历史，普通注意力会物化整张 score 矩阵，
    //    而 flash 是分块算的。AUTO 让 llama.cpp 自己判断这个模型支不支持。
    //  * KV cache 量化（q8_0 / q4_0）—— KV 内存直接减半 / 减到四分之一。
    //    手机上这决定了「能不能开大上下文」，而上下文大小又直接决定 prefill 成本。
    //  * n_batch / n_ubatch —— prefill 的分块。默认 n_ubatch == n_batch 意味着
    //    一次要备整块的激活内存；拆开之后内存曲线更平，长 prompt 不容易被系统杀。
    //
    // ⚠️ 这三项都可能**初始化失败**（不是所有模型/head_dim 都支持 flash attention，
    // 也不是所有后端都支持量化 KV），所以 load() 里有一条降级阶梯，见那里的注释。
    int32_t flash_attn_mode = 1;   // 0=off 1=auto 2=on
    int32_t kv_code         = 0;   // 0=F16 1=Q8_0 2=Q4_0
    std::string accel_desc;        // 诊断页显示的真实生效值

    /**
     * Encoder-only GGUFs (BGE, GTE, most dedicated embedding models) have no
     * decoder graph: llama_decode() on them aborts inside llama.cpp. Detect that
     * at load time and refuse generation with an actionable message instead of
     * taking the whole process down.
     */
    bool encoder_only = false;

    // ---- 多模态（mtmd）----
    //
    // 视觉投影（mmproj）是**另一个文件**，和主模型分开下载。没有它，多模态模型
    // 也只能当纯文字模型用 —— 用户看到的是"发了图它却说没看见"。
    mtmd_context * mctx      = nullptr;
    bool           vision_ok = false;

    std::mutex          mtx;
    std::atomic<bool>   stop{false};
    std::atomic<bool>   loaded{false};

    /**
     * KV cache 里现在放着哪些 token（prompt 的 + 生成的，按位置顺序）。
     *
     * 这是"前缀复用"的账本。llama.cpp 的 KV cache 是按位置索引的，只要新
     * prompt 的和已有的前 N 个 token 完全一样，那 N 个位置就不用重算 —— 手机
     * 上这一步（prefill）恰恰是最贵的：一句话的 prompt 常常几千个 token，
     * 而每轮真正新增的只有最后几十个。
     *
     * 以前这里每次请求都 llama_memory_clear()，于是每轮都把整段 system prompt
     * 加历史重算一遍。对话越往后越慢，用户的体感就是"聊两句就卡住了"。
     */
    std::vector<llama_token> cache_tokens;

    /**
     * 上一轮推理的性能记账。
     *
     * 前缀复用是移动端最大的一根杠杆，但它的收益是**看不见**的 —— 用户只会感觉
     * "快了一点"。把"提示词一共多少 token、其中多少直接命中了 KV、prefill 花了多久、
     * 首 token 多久"记下来，才能证明提示词布局真的生效了（见 docs/SPEED.md）。
     */
    struct perf_stats {
        int32_t prompt_tokens  = 0;   // 渲染后的提示词总 token 数
        int32_t reused_tokens  = 0;   // 其中直接复用 KV、没有重算的
        int32_t prefill_tokens = 0;   // 真正 decode 过的 token 数
        int32_t gen_tokens     = 0;   // 生成 token 数
        int64_t prefill_ms     = 0;   // 模板 + 分词 + prefill 的墙钟时间（≈首 token 延迟）
        int64_t gen_ms         = 0;   // 逐 token 生成的墙钟时间
    } last_perf;

    // Optional temperature override (used by the /v1 API and the Voices screen).
    float forced_temp = -1.f;

    ~engine() { unload(); }

    void unload() {
        // mtmd 必须先于 model 释放：它内部持有 model 的引用。
        if (mctx)  { mtmd_free(mctx);            mctx  = nullptr; vision_ok = false; }
        if (ctx)   { llama_free(ctx);            ctx   = nullptr; }
        if (model) { llama_model_free(model);    model = nullptr; }
        // 换了模型就换了词表与上下文，账本必须一起作废。
        cache_tokens.clear();
        loaded = false;
    }

    /**
     * 加载模型并建上下文。
     *
     * @param want_ubatch 0 = 自动（取 min(n_batch, 256)）
     * @param want_flash  0=off 1=auto 2=on
     * @param want_kv     0=F16 1=Q8_0 2=Q4_0（K 和 V 用同一种）
     */
    bool load(const std::string & path, int32_t want_ctx, int32_t want_threads, int32_t want_batch,
              int32_t want_ubatch = 0, int32_t want_flash = 1, int32_t want_kv = 0) {
        std::lock_guard<std::mutex> lk(mtx);
        unload();

        llama_model_params mp = llama_model_default_params();
        mp.n_gpu_layers = 0;      // CPU only on Android
        // llama.cpp v0.6.0 collapsed use_mmap/use_mlock into a single llama_load_mode.
        // MMAP (not MLOCK) is what this app wants: weights stream from disk, so a model
        // larger than free RAM can still load — the same idea as AirLLM's disk streaming.
        mp.load_mode    = LLAMA_LOAD_MODE_MMAP;
        mp.vocab_only   = false;

        LOGI("loading model: %s", path.c_str());
        model = llama_model_load_from_file(path.c_str(), mp);
        if (!model) {
            LOGE("llama_model_load_from_file failed");
            return false;
        }

        llama_context_params cp = llama_context_default_params();
        cp.n_ctx           = (uint32_t) (want_ctx > 0 ? want_ctx : 2048);
        cp.n_batch         = (uint32_t) (want_batch > 0 ? want_batch : 256);
        // n_ubatch 是「一次物理计算」的大小。原来直接等于 n_batch，也就是 prefill
        // 一次要备满整块的激活内存；拆开之后内存曲线更平（长 prompt 不容易被系统杀），
        // 而吞吐只掉一点点。llama.cpp 自己的默认也是分开的两个值。
        cp.n_ubatch        = (uint32_t) (want_ubatch > 0 ? want_ubatch
                                                         : std::min(cp.n_batch, (uint32_t) 256));
        cp.n_threads       = want_threads > 0 ? want_threads : 4;
        // ---- 生成用 N 个线程，prefill（batch）用更多 ----
        //
        // 这两件事的最优线程数**不一样**，而 llama.cpp 的默认是「batch 跟生成一样」。
        //  * 逐 token 生成：每步只有 1 个 token，是纯内存带宽活，多给线程只会互相抢带宽
        //    （所以 want_threads = 大核数，通常是 4）；
        //  * prefill：一次算几百个 token，是**算力**活，多几个线程能真的摊开 ——
        //    而它恰恰是首字延迟的全部，人设聊天那种几千 token 的提示词全靠它。
        //
        // 上限 6 是经验值：再多就开始被小核与散热拖回去（真机 4+4 的机器上 6 通常最快）。
        // 不能超过硬件的实际核数，也不能比生成线程少。
        {
            const int cores = (int) std::thread::hardware_concurrency();
            const int want_batch = cores > 0 ? std::min(cores, 6) : cp.n_threads;
            cp.n_threads_batch = (uint32_t) std::max((int) cp.n_threads, want_batch);
        }
        cp.flash_attn_type = flash_mode_of(want_flash);
        kv_code            = want_kv;
        cp.type_k          = kv_type_of(kv_code);
        cp.type_v          = cp.type_k;
        cp.no_perf         = true;

        // ---- 初始化的降级阶梯 ----
        //
        // 加速开关都是「可能不被支持」的：flash attention 对 head_dim 有要求，
        // 量化 KV 也不是每个后端都吃。**绝不能因为一个可选加速项让模型加载不了** ——
        // 那等于用一个性能选项换来「这个模型用不了」。所以按下面的顺序退：
        //   1. 用户要的配置；
        //   2. 关掉 flash attention（KV 量化保留）；
        //   3. 连 KV 量化也关掉；
        //   4. 再把上下文降到 2048（内存不够时的最后手段）。
        // 每一步都记进 accel_desc，诊断页看到的就是**真正生效**的那一组。
        struct Attempt { bool flash; bool kv; uint32_t ctx; };
        const Attempt ladder[] = {
            { flash_mode_of(want_flash) != LLAMA_FLASH_ATTN_TYPE_DISABLED, kv_code != 0, cp.n_ctx },
            { false,                                                      kv_code != 0, cp.n_ctx },
            { false,                                                      false,        cp.n_ctx },
            { false,                                                      false,        2048 },
        };
        for (const Attempt & a : ladder) {
            cp.flash_attn_type = a.flash ? LLAMA_FLASH_ATTN_TYPE_AUTO : LLAMA_FLASH_ATTN_TYPE_DISABLED;
            cp.type_k = a.kv ? kv_type_of(kv_code) : GGML_TYPE_F16;
            cp.type_v = cp.type_k;
            cp.n_ctx  = a.ctx;
            ctx = llama_init_from_model(model, cp);
            if (ctx) {
                if (!a.kv && kv_code != 0) {
                    LOGW("量化 KV 不被支持，已回退 f16");
                    kv_code = 0;
                }
                if (!a.flash) {
                    // 只有「用户本来想要 flash」时才算一次降级，否则关着是用户自己的选择。
                    if (flash_mode_of(want_flash) != LLAMA_FLASH_ATTN_TYPE_DISABLED) {
                        LOGW("flash attention 初始化失败，已关闭（模型照常可用）");
                    }
                    flash_attn_mode = 0;
                } else {
                    flash_attn_mode = 2;
                }
                break;
            }
            LOGW("上下文初始化失败（flash=%d kv=%d ctx=%u），降级重试",
                 a.flash ? 1 : 0, a.kv ? 1 : 0, a.ctx);
        }
        if (!ctx) {
            LOGE("llama_init_from_model failed for every fallback; giving up");
            llama_model_free(model);
            model = nullptr;
            return false;
        }

        n_ctx     = (int32_t) llama_n_ctx(ctx);
        n_batch   = (int32_t) llama_n_batch(ctx);
        n_ubatch  = (int32_t) cp.n_ubatch;
        n_threads = cp.n_threads;

        // 诊断页显示的就是**真正生效**的那一组，不是用户选的那一组 ——
        // 降级过之后两者会不一样，而用户需要知道的是后者。
        {
            char buf[256];
            snprintf(buf, sizeof(buf),
                     "flash_attn=%s · kv=%s · n_batch=%d · n_ubatch=%d · threads=%d/%d",
                     cp.flash_attn_type == LLAMA_FLASH_ATTN_TYPE_DISABLED ? "off" : "on",
                     kv_name_of(kv_code), n_batch, n_ubatch, n_threads, cp.n_threads_batch);
            accel_desc = buf;
        }

        // Encoder-only models (BGE/GTE/…) cannot run the causal decoder loop.
        const bool has_enc = llama_model_has_encoder(model);
        const bool has_dec = llama_model_has_decoder(model);
        encoder_only = has_enc && !has_dec;
        if (encoder_only) {
            LOGW("model is encoder-only — chat generation disabled for this file");
        }

        loaded    = true;
        LOGI("model ready: n_ctx=%d n_batch=%d threads=%d encoder_only=%d",
             n_ctx, n_batch, n_threads, encoder_only ? 1 : 0);
        return true;
    }

    /**
     * 装上视觉投影。**必须在主模型加载之后**，且和主模型同一次会话里配套使用。
     *
     * 返回 false 时调用方要如实告诉用户"这台机器/这个模型现在看不见图" ——
     * 而不是默默当文字模型用。
     */
    bool load_mmproj(const std::string & path) {
        std::lock_guard<std::mutex> lk(mtx);
        if (!model) { LOGE("load_mmproj: no model loaded yet"); return false; }
        if (mctx) { mtmd_free(mctx); mctx = nullptr; vision_ok = false; }
        if (path.empty()) return false;

        // 把 mtmd 自己的日志接到 logcat。
        //
        // 默认它写 stderr，在 Android 上等于**看不见** —— 图像预处理失败时只在外层
        // 拿到一个 "tokenize 返回 2"，完全不知道里面为什么失败。这一句就是那双眼。
        mtmd_helper_log_set([](ggml_log_level level, const char * text, void *) {
            int prio = ANDROID_LOG_INFO;
            if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
            else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
            else if (level == GGML_LOG_LEVEL_DEBUG) prio = ANDROID_LOG_DEBUG;
            __android_log_print(prio, "LocalMindMtmd", "%s", text);
        }, nullptr);

        mtmd_context_params mp = mtmd_context_params_default();
        mp.use_gpu       = false;              // 和主模型一致：手机只跑 CPU
        mp.print_timings = false;
        mp.n_threads     = n_threads;
        // 让 mtmd 用官方标记（<__media__>）。Kotlin 侧往消息里插的就是它。
        mp.media_marker  = mtmd_default_marker();

        LOGI("loading mmproj: %s", path.c_str());
        mctx = mtmd_init_from_file(path.c_str(), model, mp);
        if (!mctx) {
            LOGE("mtmd_init_from_file failed");
            return false;
        }
        vision_ok = mtmd_support_vision(mctx);
        LOGI("mmproj ready: vision=%d", vision_ok ? 1 : 0);
        return true;
    }

    bool vision_ready() const { return mctx != nullptr && vision_ok; }

    const llama_vocab * vocab() const { return model ? llama_model_get_vocab(model) : nullptr; }

    std::string apply_template(const std::vector<message> & msgs, bool add_ass) {
        std::vector<llama_chat_message> chat;
        chat.reserve(msgs.size());
        for (const auto & m : msgs) {
            chat.push_back({ m.role.c_str(), m.content.c_str() });
        }

        const char * tmpl = model ? llama_model_chat_template(model, nullptr) : nullptr;

        std::vector<char> buf(4096);
        int32_t rc = llama_chat_apply_template(tmpl, chat.data(), chat.size(), add_ass,
                                               buf.data(), (int32_t) buf.size());
        if (rc > (int32_t) buf.size()) {
            buf.resize((size_t) rc + 64);
            rc = llama_chat_apply_template(tmpl, chat.data(), chat.size(), add_ass,
                                           buf.data(), (int32_t) buf.size());
        }

        if (rc > 0 && tmpl != nullptr) {
            return std::string(buf.data(), (size_t) rc);
        }

        // Fallback: ChatML. Works acceptably for Qwen / Yi / many tuned GGUFs, and the UI
        // always shows the plain transcript anyway.
        std::string out;
        for (const auto & m : msgs) {
            out += "<|im_start|>" + m.role + "\n" + m.content + "<|im_end|>\n";
        }
        if (add_ass) out += "<|im_start|>assistant\n";
        return out;
    }

    std::vector<llama_token> tokenize(const std::string & text, bool add_special, bool parse_special) {
        const llama_vocab * v = vocab();
        std::vector<llama_token> tokens(text.size() / 2 + 8);
        int32_t n = llama_tokenize(v, text.c_str(), (int32_t) text.size(),
                                   tokens.data(), (int32_t) tokens.size(),
                                   add_special, parse_special);
        if (n < 0) {
            tokens.resize((size_t) -n);
            n = llama_tokenize(v, text.c_str(), (int32_t) text.size(),
                               tokens.data(), (int32_t) tokens.size(),
                               add_special, parse_special);
        }
        if (n <= 0) return {};
        tokens.resize((size_t) n);
        return tokens;
    }

    llama_sampler * build_sampler(const float * sp, int32_t n_sp) {
        auto chain_params = llama_sampler_chain_default_params();
        chain_params.no_perf = true;
        llama_sampler * chain = llama_sampler_chain_init(chain_params);

        const float def[10] = {
            /*temp*/ 0.70f, /*top_p*/ 0.90f, /*top_k*/ 40.f, /*min_p*/ 0.05f,
            /*repeat_penalty*/ 1.10f, /*repeat_last_n*/ 64.f,
            /*presence*/ 0.0f, /*frequency*/ 0.0f, /*seed*/ -1.f, /*mirostat*/ 0.f
        };
        const float * p = (sp && n_sp >= 10) ? sp : def;

        // ORDER MATTERS. The chain applies samplers in insertion order, and the LAST
        // sampler must be the one that actually selects a token (dist / greedy).
        // An earlier version added `dist` first, so it sampled straight from raw
        // logits and ignored temperature/top-k/top-p entirely — the model then
        // produced incoherent text regardless of the settings. This is the "抽风" bug.
        // Correct order: logit transforms -> filters -> selection.
        if (p[4] > 0.f) {
            // v0.6.0 added a leading n_vocab argument:
            // (n_vocab, penalty_last_n, repeat, freq, present)
            llama_sampler_chain_add(chain, llama_sampler_init_penalties(
                llama_vocab_n_tokens(llama_model_get_vocab(model)),
                (int32_t) p[5], p[4], p[7], p[6]));
        }
        if (p[0] > 0.f) llama_sampler_chain_add(chain, llama_sampler_init_temp(p[0]));
        if (p[2] > 0.f) llama_sampler_chain_add(chain, llama_sampler_init_top_k((int32_t) p[2]));
        if (p[1] > 0.f) llama_sampler_chain_add(chain, llama_sampler_init_top_p(p[1], 1));
        if (p[3] > 0.f) llama_sampler_chain_add(chain, llama_sampler_init_min_p(p[3], 1));

        const uint32_t seed = p[8] >= 0.f ? (uint32_t) p[8] : LLAMA_DEFAULT_SEED;
        llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
        return chain;
    }

    // Returns the generated text. `cb` may be null.
    std::string generate(const std::vector<message> & msgs,
                         const float * sp, int32_t n_sp,
                         int32_t max_tokens,
                         jobject cb, int32_t cb_flavor,
                         JavaVM * vm,
                         std::string & err) {
        std::lock_guard<std::mutex> lk(mtx);
        if (!loaded || !ctx) { err = "no model loaded"; return {}; }
        if (encoder_only) {
            err = "encoder_only_model";
            return {};
        }

        stop = false;
        last_perf = perf_stats{};

        JNIEnv * env = nullptr;
        bool attached = false;
        if (cb && vm) {
            if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
                if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
            }
        }

        struct detach_guard {
            JavaVM * vm; bool active;
            ~detach_guard() { if (active && vm) vm->DetachCurrentThread(); }
        } guard{ vm, attached };

        // 计时从「渲染模板」开始算：用户等的那一刻，就是从这里开始的。
        const auto t_begin = std::chrono::steady_clock::now();

        const llama_vocab * v = vocab();
        std::string prompt = apply_template(msgs, true);
        std::vector<llama_token> tokens = tokenize(prompt, true, true);
        if (tokens.empty()) { err = "tokenization produced no tokens"; return {}; }

        const int32_t n_ctx_eff = n_ctx > 8 ? n_ctx - 8 : n_ctx;
        if ((int32_t) tokens.size() >= n_ctx_eff) {
            // keep the tail — the newest turns matter most
            const int32_t keep = n_ctx_eff - 32 > 0 ? n_ctx_eff - 32 : n_ctx_eff / 2;
            tokens.erase(tokens.begin(), tokens.end() - keep);
            LOGW("prompt truncated to %d tokens (ctx=%d)", (int) tokens.size(), n_ctx);
        }

        llama_memory_t mem = llama_get_memory(ctx);

        // ---- 前缀复用 ----
        //
        // 新 prompt 和 KV cache 里已有的 token 逐位比较，取最长公共前缀。这一段
        // 的位置已经有 KV 了，直接跳过，只 decode 后面新增的那截。
        //
        // 对话场景下这一段的占比极高：system prompt（含人设、记忆、语气指令）
        // 加前面的历史全都跟上一轮一样，只有最后一条用户消息是新的。
        size_t common = 0;
        while (common < tokens.size() && common < cache_tokens.size() &&
               tokens[common] == cache_tokens[common]) {
            common++;
        }

        // 采样要有 logits，而 logits 只能由 decode 产生 —— 所以最后一个 token
        // 必须真的过一遍图，哪怕它的 KV 已经在缓存里。
        //
        // 这一步必须在**回退 KV 之前**算出来：整段命中时 keep 比 common 小 1，
        // 那一个位置要先从 KV 里删掉再重新 decode。否则同一个位置会被写两次，
        // llama.cpp 会拒绝这个 batch（实测报 "decode: failed to initialize batch"）。
        size_t keep = common;
        if (keep == tokens.size() && !tokens.empty()) {
            keep = tokens.size() - 1;
        }

        // KV 里比要保留的部分多出来的，必须丢掉，否则新 token 会叠在旧位置上。
        if (keep < cache_tokens.size()) {
            // 返回 false = 这个模型没法部分删除（循环层之类），只能整块重来。
            if (!llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
                llama_memory_clear(mem, true);
                keep = 0;
            }
        }
        cache_tokens.resize(keep);
        common = keep;

        llama_batch batch = llama_batch_init(n_batch, 0, 1);

        size_t decoded_from = common;
        for (size_t i = decoded_from; i < tokens.size(); i += (size_t) n_batch) {
            const int32_t chunk = (int32_t) std::min((size_t) n_batch, tokens.size() - i);
            batch.n_tokens = chunk;
            for (int32_t k = 0; k < chunk; k++) {
                batch.token[k]    = tokens[i + k];
                // 位置用绝对下标 —— 前缀被跳过时起点不是 0。
                batch.pos[k]      = (llama_pos) (i + k);
                batch.n_seq_id[k] = 1;
                batch.seq_id[k][0] = 0;
                batch.logits[k]   = (i + k == tokens.size() - 1) ? 1 : 0;
            }
            if (llama_decode(ctx, batch) != 0) {
                // 解码失败说明账本和真实 KV 不一致了，作废重来，免得后面一直错。
                cache_tokens.clear();
                llama_batch_free(batch);
                err = "prompt decode failed";
                return {};
            }
            for (int32_t k = 0; k < chunk; k++) {
                cache_tokens.push_back(tokens[i + k]);
            }
        }

        int32_t n_past = (int32_t) tokens.size();

        // prefill 结束（≈ 首 token 就绪）。下面这两个数字是"提示词布局有没有生效"
        // 的唯一硬证据：reused 越大、prefill 越短，说明这一轮真的只算了新增的那截。
        const auto t_prefill = std::chrono::steady_clock::now();
        last_perf.prompt_tokens  = (int32_t) tokens.size();
        last_perf.reused_tokens  = (int32_t) common;
        last_perf.prefill_tokens = (int32_t) tokens.size() - (int32_t) common;
        last_perf.prefill_ms     =
            std::chrono::duration_cast<std::chrono::milliseconds>(t_prefill - t_begin).count();
        LOGI("prefill: prompt=%d reused=%d (%.0f%%) new=%d in %lldms",
             last_perf.prompt_tokens,
             last_perf.reused_tokens,
             last_perf.prompt_tokens > 0
                 ? 100.0 * (double) last_perf.reused_tokens / (double) last_perf.prompt_tokens
                 : 0.0,
             last_perf.prefill_tokens,
             (long long) last_perf.prefill_ms);

        llama_sampler * smpl = build_sampler(sp, n_sp);

        // token -> UTF-8 -> Kotlin 的那套逻辑在 token_sink 里（文本和图像两条路径共用）。
        token_sink sink{ env, cb };

        const int32_t budget = max_tokens > 0 ? max_tokens : 512;

        for (int32_t produced = 0; produced < budget; produced++) {
            if (stop.load()) { LOGI("generation cancelled"); break; }
            if (n_past + 1 >= n_ctx) { LOGW("context exhausted at %d tokens", n_past); break; }

            const llama_token id = llama_sampler_sample(smpl, ctx, -1);
            if (llama_vocab_is_eog(v, id)) { llama_sampler_accept(smpl, id); break; }
            llama_sampler_accept(smpl, id);

            char piece[256];
            const int32_t np = llama_token_to_piece(v, id, piece, (int32_t) sizeof(piece), 0, false);
            if (np > 0) {
                sink.push(piece, (size_t) np);
            }

            batch.n_tokens = 1;
            batch.token[0]    = id;
            batch.pos[0]      = n_past;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0]   = 1;
            if (llama_decode(ctx, batch) != 0) {
                LOGE("decode failed at token %d", produced);
                cache_tokens.clear();   // 账本不可信了，下次从头来
                err = "decode failed mid-generation";
                break;
            }
            // 生成出来的 token 也在 KV 里。记下来：下一轮的 prompt 通常把它
            // 原样带上（对话模板就是把助手回复拼进 history），于是这一整段
            // 都能命中前缀，不用重算 —— 这是多轮对话越聊越快的关键。
            cache_tokens.push_back(id);
            n_past++;
        }

        const auto t_end = std::chrono::steady_clock::now();
        // 生成 token 数 = KV 里比提示词多出来那截（每个循环都 decode 一个并 push）。
        last_perf.gen_tokens = std::max(0, n_past - (int32_t) tokens.size());
        last_perf.gen_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_prefill).count();
        LOGI("generate: %d tokens in %lldms (%.1f t/s)",
             last_perf.gen_tokens,
             (long long) last_perf.gen_ms,
             last_perf.gen_ms > 0
                 ? 1000.0 * (double) last_perf.gen_tokens / (double) last_perf.gen_ms
                 : 0.0);

        llama_sampler_free(smpl);
        llama_batch_free(batch);
        sink.drain(true);   // 尾巴上那个不完整的序列也吐出去
        return sink.out;
    }

    /**
     * 带图生成：走 mtmd（视觉投影）。
     *
     * ## 和纯文本路径的三个区别
     *
     *  1. prompt 里那个 `<__media__>` 标记由 mtmd 换成图像嵌入，所以**不能**再
     *     自己 tokenize 完直接 decode —— 必须按 chunk 走 mtmd_helper_eval_chunks。
     *  2. 图像嵌入占的**位置数不等于 token 数**（一张图几百到上千个位置），
     *     所以 n_past 只能由 mtmd 回报。
     *  3. **不做前缀复用**：KV 里现在混着图像嵌入，那本账（cache_tokens）对不上，
     *     强行复用只会串味。每次带图都清空重来 —— 带图的轮次本来就少，
     *     换来的是"绝不会错"。
     */
    std::string generate_vision(const std::vector<message> & msgs,
                                const std::string & image_path,
                                const float * sp, int32_t n_sp,
                                int32_t max_tokens,
                                jobject cb, JavaVM * vm,
                                std::string & err) {
        std::lock_guard<std::mutex> lk(mtx);
        if (!loaded || !ctx)      { err = "no model loaded"; return {}; }
        if (encoder_only)         { err = "encoder_only_model"; return {}; }
        if (!vision_ready())      { err = "vision_not_ready"; return {}; }
        if (image_path.empty())   { err = "no image path"; return {}; }

        stop = false;

        JNIEnv * env = nullptr;
        bool attached = false;
        if (cb && vm) {
            if (vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
                if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
            }
        }
        struct detach_guard {
            JavaVM * vm; bool active;
            ~detach_guard() { if (active && vm) vm->DetachCurrentThread(); }
        } guard{ vm, attached };

        // 用 mtmd 官方的图片加载器，而不是自己把 Bitmap 拆成 RGB。
        //
        // 为什么换：自己构造 mtmd_bitmap 时，预处理器拿不到图像 token
        //（"no image tokens produced by preprocessor"，tokenize 返回 2），
        // SmolVLM 和 LFM2-VL 两个模型都一样 —— 说明问题在我这边。
        // 这个 helper 是 mtmd-cli 走的那条路：它自己解码、自己按模型要求缩放，
        // 少一层我可能搞错的假设（尺寸/通道/EXIF）。
        auto wrapper = mtmd_helper_bitmap_init_from_file(
            mctx, image_path.c_str(), false, mtmd_helper_init_opt_default());
        mtmd_bitmap * bmp = wrapper.bitmap;
        if (!bmp) { err = "读不出图片：" + image_path; return {}; }

        const std::string prompt = apply_template(msgs, true);
        mtmd_input_text txt;
        txt.text          = prompt.c_str();
        txt.add_special   = true;
        txt.parse_special = true;

        const mtmd_bitmap * bitmaps[1] = { bmp };
        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        const int32_t tok_rc = mtmd_tokenize(mctx, chunks, &txt, bitmaps, 1);
        mtmd_bitmap_free(bmp);
        if (tok_rc != 0) {
            mtmd_input_chunks_free(chunks);
            err = "mtmd_tokenize failed (" + std::to_string(tok_rc) + ")";
            return {};
        }

        // 见上面的第 3 条：带图一律从干净上下文开始。
        llama_memory_clear(llama_get_memory(ctx), true);
        cache_tokens.clear();

        last_perf = perf_stats{};
        const auto t_begin = std::chrono::steady_clock::now();
        llama_pos new_n_past = 0;
        const int32_t eval_rc = mtmd_helper_eval_chunks(
            mctx, ctx, chunks, 0, 0, n_batch, true, &new_n_past);
        mtmd_input_chunks_free(chunks);
        if (eval_rc != 0) {
            err = "mtmd eval failed (" + std::to_string(eval_rc) + ")";
            return {};
        }
        const auto t_prefill = std::chrono::steady_clock::now();
        last_perf.prompt_tokens  = (int32_t) new_n_past;
        last_perf.reused_tokens  = 0;   // 带图一律重算
        last_perf.prefill_tokens = (int32_t) new_n_past;
        last_perf.prefill_ms =
            std::chrono::duration_cast<std::chrono::milliseconds>(t_prefill - t_begin).count();

        int32_t n_past = (int32_t) new_n_past;
        const llama_vocab * v = vocab();
        const int32_t budget = max_tokens > 0 ? max_tokens : 512;

        llama_batch batch = llama_batch_init(n_batch, 0, 1);
        llama_sampler * smpl = build_sampler(sp, n_sp);
        token_sink sink{ env, cb };

        for (int32_t produced = 0; produced < budget; produced++) {
            if (stop.load()) { LOGI("vision generation cancelled"); break; }
            if (n_past + 1 >= n_ctx) { LOGW("context exhausted at %d", n_past); break; }

            const llama_token id = llama_sampler_sample(smpl, ctx, -1);
            if (llama_vocab_is_eog(v, id)) break;
            llama_sampler_accept(smpl, id);

            char piece[256];
            const int32_t np = llama_token_to_piece(v, id, piece, (int32_t) sizeof(piece), 0, false);
            if (np > 0) sink.push(piece, (size_t) np);

            batch.n_tokens     = 1;
            batch.token[0]     = id;
            batch.pos[0]       = n_past;
            batch.n_seq_id[0]  = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0]    = 1;
            if (llama_decode(ctx, batch) != 0) {
                LOGE("vision decode failed at %d", produced);
                err = "decode failed mid-generation";
                break;
            }
            n_past++;
        }

        last_perf.gen_tokens = std::max(0, n_past - (int32_t) new_n_past);
        last_perf.gen_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - t_prefill).count();

        llama_sampler_free(smpl);
        llama_batch_free(batch);
        sink.drain(true);
        // 生成完之后 KV 里是"图像 + 这一轮"，账本对不上 → 让下一轮从头来。
        cache_tokens.clear();
        return sink.out;
    }
};

engine * as_engine(jlong h) { return reinterpret_cast<engine *>(h); }

JavaVM * g_vm = nullptr;

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    g_vm = vm;
    llama_log_set([](ggml_log_level level, const char * text, void *) {
        if (level >= GGML_LOG_LEVEL_ERROR)      LOGE("%s", text);
        else if (level == GGML_LOG_LEVEL_WARN)  LOGW("%s", text);
        else                                    LOGI("%s", text);
    }, nullptr);
    llama_backend_init();
    LOGI("llama backend initialised (%s)", llama_print_system_info());
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *, void *) {
    llama_backend_free();
    g_vm = nullptr;
}

JNIEXPORT jlong JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeInit(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new engine());
}

JNIEXPORT void JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeFree(JNIEnv *, jclass, jlong h) {
    delete as_engine(h);
}

JNIEXPORT jboolean JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeLoadModel(
        JNIEnv * env, jclass, jlong h, jstring path, jint n_ctx, jint n_threads, jint n_batch) {
    engine * e = as_engine(h);
    if (!e) return JNI_FALSE;
    return e->load(jstr(env, path), n_ctx, n_threads, n_batch) ? JNI_TRUE : JNI_FALSE;
}

/**
 * 带加速参数的加载。
 *
 * **为什么不直接改上面那个函数的签名**：JNI 的短名解析（
 * Java_..._nativeLoadModel）**不包含参数表**，所以改了参数列表之后，一个
 * 旧版本的 .so 被新代码调到时会按错误的参数表读寄存器 —— 不是报错，是崩溃。
 * 这两个函数各自独立，老调用点永远安全。
 */
JNIEXPORT jboolean JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeLoadModelEx(
        JNIEnv * env, jclass, jlong h, jstring path, jint n_ctx, jint n_threads, jint n_batch,
        jint n_ubatch, jint flash, jint kv) {
    engine * e = as_engine(h);
    if (!e) return JNI_FALSE;
    return e->load(jstr(env, path), n_ctx, n_threads, n_batch, n_ubatch, flash, kv)
           ? JNI_TRUE : JNI_FALSE;
}

/** 真正生效的加速配置（降级过之后与用户选的不同）。诊断页读它。 */
JNIEXPORT jstring JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeAccelInfo(JNIEnv * env, jclass, jlong h) {
    engine * e = as_engine(h);
    if (!e) return to_java_utf16(env, "");
    return to_java_utf16(env, e->accel_desc);
}

/**
 * 上一轮推理的性能数字（JSON）。诊断页用它显示「复用 N/M · prefill 多少 ms」。
 *
 * 单独一个函数而不是把它塞进返回值：生成路径的返回值是模型说的话，
 * 往里加任何元数据都会污染文本（这正是当年"回答里混进统计信息"那类 bug 的来源）。
 */
JNIEXPORT jstring JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeLastPerf(JNIEnv * env, jclass, jlong h) {
    engine * e = as_engine(h);
    if (!e) return to_java_utf16(env, "{}");
    const auto & p = e->last_perf;
    char buf[256];
    snprintf(buf, sizeof(buf),
             "{\"prompt\":%d,\"reused\":%d,\"prefill_tokens\":%d,\"gen\":%d,"
             "\"prefill_ms\":%lld,\"gen_ms\":%lld}",
             p.prompt_tokens, p.reused_tokens, p.prefill_tokens, p.gen_tokens,
             (long long) p.prefill_ms, (long long) p.gen_ms);
    return to_java_utf16(env, buf);
}

JNIEXPORT void JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeUnload(JNIEnv *, jclass, jlong h) {
    if (engine * e = as_engine(h)) e->unload();
}

JNIEXPORT jboolean JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeIsLoaded(JNIEnv *, jclass, jlong h) {
    engine * e = as_engine(h);
    return (e && e->loaded.load()) ? JNI_TRUE : JNI_FALSE;
}

/** True when the resident file cannot serve /v1/chat/completions (encoder-only). */
JNIEXPORT jboolean JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeIsEncoderOnly(JNIEnv *, jclass, jlong h) {
    engine * e = as_engine(h);
    return (e && e->encoder_only) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeCancel(JNIEnv *, jclass, jlong h) {
    if (engine * e = as_engine(h)) e->stop = true;
}

JNIEXPORT jint JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeContextSize(JNIEnv *, jclass, jlong h) {
    engine * e = as_engine(h);
    return e ? e->n_ctx : 0;
}

/** Lightweight GGUF header reader — no model load, no mmap of the tensors. */
JNIEXPORT jstring JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeModelInfo(JNIEnv * env, jclass, jlong h) {
    engine * e = as_engine(h);
    if (!e || !e->model) return to_java_utf16(env, "");
    char buf[1024];
    char desc[256] = {0};
    llama_model_desc(e->model, desc, sizeof(desc));
    snprintf(buf, sizeof(buf),
             "{\"desc\":\"%s\",\"params\":%llu,\"size\":%llu,\"n_ctx_train\":%d,\"n_embd\":%d,"
             "\"n_layer\":%d,\"n_head\":%d,\"vocab\":%d,\"system_info\":\"%s\"}",
             desc,
             (unsigned long long) llama_model_n_params(e->model),
             (unsigned long long) llama_model_size(e->model),
             llama_model_n_ctx_train(e->model),
             llama_model_n_embd(e->model),
             llama_model_n_layer(e->model),
             llama_model_n_head(e->model),
             llama_vocab_n_tokens(llama_model_get_vocab(e->model)),
             llama_print_system_info());
    return to_java_utf16(env, buf);
}

JNIEXPORT jstring JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeSystemInfo(JNIEnv * env, jclass) {
    return to_java_utf16(env, llama_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeFormatChat(
        JNIEnv * env, jclass, jlong h, jobjectArray roles, jobjectArray contents, jboolean add_ass) {
    engine * e = as_engine(h);
    if (!e || !e->model) return to_java_utf16(env, "");
    const jsize n = env->GetArrayLength(roles);
    std::vector<message> msgs;
    msgs.reserve((size_t) n);
    for (jsize i = 0; i < n; i++) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        auto c = (jstring) env->GetObjectArrayElement(contents, i);
        msgs.push_back({ jstr(env, r), jstr(env, c) });
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    const std::string out = e->apply_template(msgs, add_ass == JNI_TRUE);
    return to_java_utf16(env, out);
}

JNIEXPORT jstring JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeGenerate(
        JNIEnv * env, jclass, jlong h,
        jobjectArray roles, jobjectArray contents,
        jfloatArray sampler, jint max_tokens, jobject callback) {
    engine * e = as_engine(h);
    if (!e) return to_java_utf16(env, "");

    const jsize n = env->GetArrayLength(roles);
    std::vector<message> msgs;
    msgs.reserve((size_t) n);
    for (jsize i = 0; i < n; i++) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        auto c = (jstring) env->GetObjectArrayElement(contents, i);
        msgs.push_back({ jstr(env, r), jstr(env, c) });
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }

    std::vector<float> sp;
    if (sampler) {
        const jsize m = env->GetArrayLength(sampler);
        sp.resize((size_t) m);
        env->GetFloatArrayRegion(sampler, 0, m, sp.data());
    }

    std::string err;
    const std::string out = e->generate(msgs, sp.empty() ? nullptr : sp.data(), (int32_t) sp.size(),
                                        max_tokens, callback, 1, g_vm, err);
    if (!err.empty() && out.empty()) {
        return to_java_utf16(env, "__ERROR__" + err);
    }
    return to_java_utf16(env, out);
}

// ---------------------------------------------------------------------------
// 多模态（识图）
// ---------------------------------------------------------------------------

/** 装上视觉投影（mmproj）。和主模型配套的那个文件。 */
JNIEXPORT jboolean JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeMmprojLoad(
        JNIEnv * env, jclass, jlong h, jstring path) {
    engine * e = as_engine(h);
    if (!e) return JNI_FALSE;
    return e->load_mmproj(jstr(env, path)) ? JNI_TRUE : JNI_FALSE;
}

/** 现在能不能看图（装了 mmproj 且它支持视觉）。 */
JNIEXPORT jboolean JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeVisionReady(JNIEnv *, jclass, jlong h) {
    engine * e = as_engine(h);
    return (e && e->vision_ready()) ? JNI_TRUE : JNI_FALSE;
}

/**
 * 带图生成。
 *
 * [pixels] 是 ARGB_8888 的整型像素（Android Bitmap.getPixels 的原始格式）。
 * 调用方要在**带图的那条消息内容里**插入 mtmd 的媒体标记，否则图片不会被用到。
 */
JNIEXPORT jstring JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeVisionGenerate(
        JNIEnv * env, jclass, jlong h,
        jobjectArray roles, jobjectArray contents,
        jstring image_path,
        jfloatArray sampler, jint max_tokens, jobject callback) {
    engine * e = as_engine(h);
    if (!e) return to_java_utf16(env, "");

    const jsize n = env->GetArrayLength(roles);
    std::vector<message> msgs;
    msgs.reserve((size_t) n);
    for (jsize i = 0; i < n; i++) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        auto c = (jstring) env->GetObjectArrayElement(contents, i);
        msgs.push_back({ jstr(env, r), jstr(env, c) });
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }

    std::vector<float> sp;
    if (sampler) {
        const jsize m = env->GetArrayLength(sampler);
        sp.resize((size_t) m);
        env->GetFloatArrayRegion(sampler, 0, m, sp.data());
    }

    std::string err;
    const std::string out = e->generate_vision(
        msgs, jstr(env, image_path),
        sp.empty() ? nullptr : sp.data(), (int32_t) sp.size(),
        max_tokens, callback, g_vm, err);
    if (!err.empty() && out.empty()) return to_java_utf16(env, "__ERROR__" + err);
    return to_java_utf16(env, out);
}

/** Token count for a rendered prompt — used by the context/memory planner. */
JNIEXPORT jint JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeCountTokens(
        JNIEnv * env, jclass, jlong h, jstring text) {
    engine * e = as_engine(h);
    if (!e || !e->model) return -1;
    std::lock_guard<std::mutex> lk(e->mtx);
    return (jint) e->tokenize(jstr(env, text), false, true).size();
}

/**
 * Diagnostic: tokenise `text` then detokenise the result and hand the ids back.
 * A round-trip mismatch means the Java<->native encoding path is broken, which is
 * otherwise very hard to see (the prompt just "looks" like nonsense to the model).
 */
JNIEXPORT jstring JNICALL
Java_com_localmind_native_llama_LlamaBridge_nativeTokenRoundTrip(
        JNIEnv * env, jclass, jlong h, jstring text) {
    engine * e = as_engine(h);
    if (!e || !e->model) return to_java_utf16(env, "{\"error\":\"no model\"}");

    const std::string in = jstr(env, text);
    std::lock_guard<std::mutex> lk(e->mtx);
    const auto tokens = e->tokenize(in, false, true);

    // Build the id list + a detokenised form so both directions are observable.
    std::string ids;
    for (size_t i = 0; i < tokens.size() && i < 64; i++) {
        if (i) ids += ",";
        ids += std::to_string(tokens[i]);
    }

    const llama_vocab * v = e->vocab();
    std::string round;
    for (auto t : tokens) {
        char buf[256];
        const int32_t n = llama_token_to_piece(v, t, buf, (int32_t) sizeof(buf), 0, false);
        if (n > 0) round.append(buf, (size_t) n);
    }

    auto esc = [](const std::string & s) {
        std::string o;
        for (unsigned char c : s) {
            if (c == '"' || c == '\\') { o += '\\'; o += (char) c; }
            else if (c < 0x20) { char b[8]; snprintf(b, sizeof(b), "\\u%04x", c); o += b; }
            else o += (char) c;
        }
        return o;
    };

    char head[128];
    snprintf(head, sizeof(head), "{\"in_bytes\":%zu,\"n_tokens\":%zu,\"ids\":\"",
             in.size(), tokens.size());
    std::string out = head;
    out += ids;
    out += "\",\"detok\":\"" + esc(round) + "\",\"match\":";
    out += (round == in) ? "true" : "false";
    out += "}";
    return to_java_utf16(env, out);
}

} // extern "C"

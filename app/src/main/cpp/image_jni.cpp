// LocalMind native image renderer.
//
// Why a **procedural** renderer in the app, and diffusion in a separate download?
// -----------------------------------------------------------------------------
// A 4-step SD-Turbo / SDXL-Turbo pipeline needs 1.5-6 GB of weights plus a compute
// graph no current Android phone runs at interactive speed, and the licence for the
// useful checkpoints is not redistributable inside an APK. So LocalMind ships a real,
// always-available offline text-to-image engine (deterministic, seedable, <100 ms,
// works with no network and no model file) **here**, and the diffusion backend as a
// separately downloaded `liblocalmind-sd.so`.
//
// This file therefore contains only what needs **no download**: the procedural
// renderer and the prompt hash. The `nativeSdLoad` / `nativeSdGenerate` /
// `nativeSdRelease` / `nativeSdInfo` entries live in `sd-alone/sd_jni.cpp` and are
// resolved at runtime from the downloaded library — see the note at the end of this
// file for why they must not be linked in here.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#ifdef LOCALMIND_HAS_SD
#error "生图后端已改为独立的 liblocalmind-sd.so（运行时下载）。不要在 image_jni.cpp 里用 LOCALMIND_HAS_SD 链进来 —— 原因见文件末尾。"
#endif

#define IMG_TAG "LocalMindImage"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, IMG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, IMG_TAG, __VA_ARGS__)

namespace {

/** See llama_jni.cpp: JNI strings are modified UTF-8, so convert via UTF-16. */
std::string utf8_from_java(JNIEnv * env, jstring s) {
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
        if (cp < 0x80) out += (char) cp;
        else if (cp < 0x800) {
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

inline uint32_t mix32(uint64_t & s) {
    s += 0x9E3779B97F4A7C15ull;
    uint64_t z = s;
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
    return (uint32_t) (z ^ (z >> 31));
}
inline float rnd01(uint64_t & s) { return (float) (mix32(s) >> 8) / 16777216.0f; }

inline float clamp01(float v) { return v < 0.f ? 0.f : (v > 1.f ? 1.f : v); }
inline float lerp(float a, float b, float t) { return a + (b - a) * t; }
inline float smooth(float t) { return t * t * (3.f - 2.f * t); }

struct RGB { float r, g, b; };

inline RGB mixc(RGB a, RGB b, float t) {
    return { lerp(a.r, b.r, t), lerp(a.g, b.g, t), lerp(a.b, b.b, t) };
}

// --- palette ---------------------------------------------------------------
struct Palette { RGB sky0, sky1, sun, land0, land1, accent; };

uint32_t fnv1a(const std::string & s) {
    uint32_t h = 2166136261u;
    for (unsigned char c : s) { h ^= c; h *= 16777619u; }
    return h;
}

bool has(const std::string & hay, const char * needle) {
    return hay.find(needle) != std::string::npos;
}

Palette palette_for(const std::string & p) {
    // Dark / neon / night
    if (has(p, "night") || has(p, "夜") || has(p, "星空") || has(p, "space") || has(p, "宇宙") || has(p, "cyber")) {
        return { {0.03f, 0.04f, 0.11f}, {0.10f, 0.06f, 0.24f}, {0.95f, 0.85f, 0.65f},
                 {0.02f, 0.03f, 0.07f}, {0.07f, 0.09f, 0.16f}, {0.35f, 0.85f, 1.00f} };
    }
    // Fire / red
    if (has(p, "fire") || has(p, "火") || has(p, "sunset") || has(p, "黄昏") || has(p, "红")) {
        return { {0.16f, 0.06f, 0.16f}, {0.95f, 0.42f, 0.18f}, {1.00f, 0.85f, 0.45f},
                 {0.10f, 0.04f, 0.09f}, {0.24f, 0.09f, 0.13f}, {1.00f, 0.62f, 0.25f} };
    }
    // Ocean / ice
    if (has(p, "ocean") || has(p, "海") || has(p, "ice") || has(p, "冰") || has(p, "water")) {
        return { {0.04f, 0.16f, 0.30f}, {0.35f, 0.72f, 0.86f}, {0.92f, 0.98f, 1.00f},
                 {0.03f, 0.14f, 0.24f}, {0.10f, 0.34f, 0.46f}, {0.55f, 0.92f, 0.98f} };
    }
    // Forest / green
    if (has(p, "forest") || has(p, "森") || has(p, "树") || has(p, "green") || has(p, "草")) {
        return { {0.06f, 0.14f, 0.12f}, {0.62f, 0.80f, 0.52f}, {0.98f, 0.95f, 0.72f},
                 {0.05f, 0.14f, 0.09f}, {0.14f, 0.32f, 0.18f}, {0.72f, 0.92f, 0.55f} };
    }
    // Sakura / pink pastel
    if (has(p, "sakura") || has(p, "樱") || has(p, "pink") || has(p, "粉")) {
        return { {0.32f, 0.22f, 0.38f}, {0.98f, 0.76f, 0.84f}, {1.00f, 0.94f, 0.94f},
                 {0.22f, 0.14f, 0.28f}, {0.44f, 0.26f, 0.42f}, {1.00f, 0.80f, 0.90f} };
    }
    // Desert / gold
    if (has(p, "desert") || has(p, "沙") || has(p, "gold") || has(p, "金")) {
        return { {0.24f, 0.14f, 0.10f}, {0.96f, 0.74f, 0.38f}, {1.00f, 0.93f, 0.70f},
                 {0.26f, 0.16f, 0.09f}, {0.50f, 0.33f, 0.16f}, {1.00f, 0.85f, 0.45f} };
    }
    // Default: cool editorial
    return { {0.07f, 0.09f, 0.18f}, {0.42f, 0.52f, 0.82f}, {0.98f, 0.96f, 0.90f},
             {0.05f, 0.07f, 0.13f}, {0.16f, 0.19f, 0.32f}, {0.60f, 0.78f, 1.00f} };
}

// --- value noise / fbm -----------------------------------------------------
float hash2(int x, int y, uint32_t seed) {
    uint32_t h = (uint32_t) x * 374761393u + (uint32_t) y * 668265263u + seed * 2246822519u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return (float) ((h ^ (h >> 16)) & 0xFFFFFF) / 16777215.0f;
}

float vnoise(float x, float y, uint32_t seed) {
    const int xi = (int) std::floor(x), yi = (int) std::floor(y);
    const float xf = x - xi, yf = y - yi;
    const float u = smooth(xf), v = smooth(yf);
    const float a = hash2(xi, yi, seed),       b = hash2(xi + 1, yi, seed);
    const float c = hash2(xi, yi + 1, seed),   d = hash2(xi + 1, yi + 1, seed);
    return lerp(lerp(a, b, u), lerp(c, d, u), v);
}

float fbm(float x, float y, uint32_t seed, int oct, float lac = 2.03f, float gain = 0.5f) {
    float sum = 0.f, amp = 1.f, norm = 0.f, fx = x, fy = y;
    for (int i = 0; i < oct; i++) {
        sum  += amp * vnoise(fx, fy, seed + (uint32_t) i * 131u);
        norm += amp;
        amp  *= gain;
        fx *= lac; fy *= lac;
    }
    return norm > 0.f ? sum / norm : 0.f;
}

// --- main generator --------------------------------------------------------
void render(const std::string & prompt, int w, int h, uint32_t seed, int style,
            std::vector<uint32_t> & out) {
    out.assign((size_t) w * h, 0xFF000000u);

    const Palette pal = palette_for(prompt);
    const uint32_t ps = fnv1a(prompt) ^ (seed * 2654435761u);

    // Horizon placement varies with the prompt so different prompts look different.
    const float horizon = 0.52f + 0.22f * (fbm(3.1f, 7.7f, ps, 3) - 0.5f) * 2.f;
    const float sun_x   = 0.15f + 0.70f * fbm(11.3f, 2.9f, ps + 7u, 3);
    const float sun_y   = horizon - 0.10f - 0.22f * fbm(5.5f, 19.1f, ps + 13u, 3);
    const float sun_r   = 0.035f + 0.055f * fbm(21.7f, 4.3f, ps + 29u, 2);

    const bool starfield = has(prompt, "night") || has(prompt, "夜") || has(prompt, "星空") ||
                           has(prompt, "space") || has(prompt, "宇宙") || style == 3;
    const bool aurora    = has(prompt, "aurora") || has(prompt, "极光") || has(prompt, "nebula") || has(prompt, "星云");
    const bool dense_fg  = has(prompt, "city") || has(prompt, "城市") || has(prompt, "building") || has(prompt, "cyber");

    for (int y = 0; y < h; y++) {
        const float fy = (float) y / (float) (h - 1);
        for (int x = 0; x < w; x++) {
            const float fx = (float) x / (float) (w - 1);

            RGB c;

            if (fy < horizon) {
                // ---- sky: vertical gradient + large-scale cloud field
                const float t = fy / (horizon > 0.001f ? horizon : 0.001f);
                c = mixc(pal.sky0, pal.sky1, smooth(clamp01(t)));

                float cl = fbm(fx * 3.4f, fy * 6.0f, ps + 101u, 5);
                cl = clamp01((cl - 0.42f) * 2.2f);
                c = mixc(c, mixc(pal.sky1, pal.sun, 0.35f), cl * 0.55f);

                if (aurora) {
                    const float band = std::exp(-std::pow((fy - horizon * 0.55f) * 6.5f, 2.f));
                    const float wob  = fbm(fx * 2.2f, fy * 1.4f, ps + 211u, 4);
                    const float a    = band * clamp01(wob * 1.6f - 0.25f);
                    c = mixc(c, {0.30f, 1.00f, 0.70f}, a * 0.75f);
                    c = mixc(c, {0.65f, 0.35f, 1.00f}, a * 0.35f);
                }

                if (starfield) {
                    const float sx = fx * (float) w, sy = fy * (float) h;
                    const int   gx = (int) (sx / 6.f), gy = (int) (sy / 6.f);
                    const float s = hash2(gx, gy, ps + 313u);
                    if (s > 0.972f) {
                        const float sxs = (float) gx * 6.f + hash2(gx, gy, ps + 401u) * 6.f;
                        const float sys = (float) gy * 6.f + hash2(gx, gy, ps + 503u) * 6.f;
                        const float d   = std::sqrt((sx - sxs) * (sx - sxs) + (sy - sys) * (sy - sys));
                        const float tw  = std::exp(-d * d * 0.9f);
                        const float mag = 0.35f + 0.65f * hash2(gx, gy, ps + 601u);
                        c = mixc(c, {1.f, 0.98f, 0.92f}, clamp01(tw * mag));
                    }
                }

                // ---- sun / moon disc with glow
                const float dx = (fx - sun_x), dy = (fy - sun_y) * (float) h / (float) w;
                const float d  = std::sqrt(dx * dx + dy * dy);
                const float glow = std::exp(-d * d / (sun_r * sun_r * 26.f));
                c = mixc(c, pal.sun, clamp01(glow * 0.85f));
                const float disc = 1.f - smooth(clamp01((d - sun_r) / (sun_r * 0.35f)));
                c = mixc(c, {1.f, 0.97f, 0.90f}, clamp01(disc));
            } else {
                // ---- ground: ridged silhouettes, three depth layers
                RGB land = mixc(pal.land0, pal.land1, smooth((fy - horizon) / (1.f - horizon)));

                const float depth = (fy - horizon) / (1.f - horizon);
                for (int l = 0; l < 3; l++) {
                    const float base = horizon + (0.045f + 0.115f * (float) l);
                    const float amp  = 0.10f - 0.022f * (float) l;
                    const float ridge = fbm((dense_fg ? fx * 7.5f : fx * 2.1f) + (float) l * 4.7f,
                                            (float) l * 3.3f, ps + 700u + (uint32_t) l * 37u,
                                            dense_fg ? 4 : 5);
                    const float top = base - amp * (ridge - 0.5f) * 2.f;
                    if (fy >= top) {
                        const float k = 0.30f - 0.08f * (float) l;
                        RGB layer = mixc(pal.land0, pal.land1, 0.15f * (float) l / 2.f);
                        layer = mixc(layer, pal.accent, 0.10f * (float) l * clamp01(1.f - depth * 3.f));
                        land = mixc(land, layer, k);
                    }
                }

                // wet reflection of the sun right below the horizon
                if (fy < horizon + 0.22f) {
                    const float band = std::exp(-std::pow((fx - sun_x) * 5.5f, 2.f)) *
                                       clamp01(1.f - (fy - horizon) * 6.f);
                    land = mixc(land, pal.sun, band * 0.22f);
                }
                c = land;
            }

            // ---- style pass
            switch (style) {
                case 1: { // cinematic — teal/orange grade + vignette
                    const float lum = 0.299f * c.r + 0.587f * c.g + 0.114f * c.b;
                    c.r = clamp01(c.r * 1.14f + lum * 0.06f);
                    c.g = clamp01(c.g * 1.02f);
                    c.b = clamp01(c.b * 1.12f + (1.f - lum) * 0.05f);
                    break;
                }
                case 2: { // illustration — posterised, saturated
                    const int steps = 9;
                    c.r = std::round(clamp01(c.r) * steps) / steps;
                    c.g = std::round(clamp01(c.g) * steps) / steps;
                    c.b = std::round(clamp01(c.b) * steps) / steps;
                    c.r = clamp01((c.r - 0.5f) * 1.35f + 0.5f);
                    c.g = clamp01((c.g - 0.5f) * 1.35f + 0.5f);
                    c.b = clamp01((c.b - 0.5f) * 1.35f + 0.5f);
                    break;
                }
                case 3: { // neon — bloom + scanlines
                    const float lum = clamp01(0.299f * c.r + 0.587f * c.g + 0.114f * c.b);
                    const float b = std::pow(lum, 3.0f) * 0.55f;
                    c = mixc(c, pal.accent, b);
                    if ((y % 3) == 0) { c.r *= 0.93f; c.g *= 0.95f; c.b *= 0.97f; }
                    break;
                }
                case 4: { // film — grain + soft roll-off
                    const float g = (hash2(x, y, ps + 909u) - 0.5f) * 0.055f;
                    c.r = clamp01(c.r + g); c.g = clamp01(c.g + g); c.b = clamp01(c.b + g);
                    break;
                }
                default: { // natural — light grain
                    const float g = (hash2(x, y, ps + 909u) - 0.5f) * 0.022f;
                    c.r = clamp01(c.r + g); c.g = clamp01(c.g + g); c.b = clamp01(c.b + g);
                    break;
                }
            }

            // ---- vignette
            const float vx = (fx - 0.5f) * 2.f, vy = (fy - 0.5f) * 2.f;
            const float vig = 1.f - 0.30f * clamp01(vx * vx * 0.55f + vy * vy * 0.75f);
            c.r *= vig; c.g *= vig; c.b *= vig;

            const uint32_t r = (uint32_t) (clamp01(c.r) * 255.f + 0.5f);
            const uint32_t g = (uint32_t) (clamp01(c.g) * 255.f + 0.5f);
            const uint32_t b = (uint32_t) (clamp01(c.b) * 255.f + 0.5f);
            out[(size_t) y * (size_t) w + (size_t) x] = 0xFF000000u | (r << 16) | (g << 8) | b;
        }
    }
}

} // namespace

extern "C" {

/**
 * Renders an image into `pixels` (w*h ARGB_8888 words, pre-allocated by Kotlin).
 * Returns the number of pixels written, or -1 on bad arguments.
 */
JNIEXPORT jint JNICALL
Java_com_localmind_native_image_ImageBridge_nativeRender(
        JNIEnv * env, jclass, jstring prompt, jint w, jint h, jint seed, jint style,
        jintArray pixels) {
    if (w <= 0 || h <= 0 || w > 2048 || h > 2048 || pixels == nullptr) return -1;
    if (env->GetArrayLength(pixels) < w * h) return -1;

    std::string p = utf8_from_java(env, prompt);
    if (p.empty()) p = "abstract";

    std::vector<uint32_t> buf;
    render(p, w, h, (uint32_t) seed, style, buf);

    env->SetIntArrayRegion(pixels, 0, w * h, reinterpret_cast<const jint *>(buf.data()));
    return w * h;
}

/** Cheap hash used by the Kotlin side to make "same prompt + seed == same image" visible. */
JNIEXPORT jint JNICALL
Java_com_localmind_native_image_ImageBridge_nativePromptHash(JNIEnv * env, jclass, jstring prompt) {
    const uint32_t hsh = fnv1a(utf8_from_java(env, prompt));
    return (jint) (hsh & 0x7FFFFFFF);
}


// ---------------------------------------------------------------------------
// 扩散（生图）后端**不在这里**。
//
// 它曾作为条件编译的静态库链进这个 .so，但那样有两个真问题：
//
//  1. **体积**：把 liblocalmind.so 从 3.5 MB 撑到 41.66 MB，而只有愿意下
//     2–6 GB checkpoint 的人用得上。
//  2. **ABI**：sd.cpp 和 llama.cpp 各带一份 ggml，而两份的
//     `GGML_MAX_NAME` 不同（160 vs 64）——`char name[GGML_MAX_NAME]` 是
//     `struct ggml_tensor` 的真实字段，于是 `sizeof(ggml_tensor)` 差 96 字节，
//     `extra` / `view_src` 落在错偏移上。**不报错，只是内存错乱。**
//
// 现在它是独立的 liblocalmind-sd.so：用户第一次要真生图时下载，
// 用 System.load 加载（见 NativeLibraryInstaller / ImageBridge）。
// 分成两个 .so 之后，两个 ggml 各自留在自己的库里 —— bionic 解析符号时
// 先看库自己 —— ABI 冲突从结构上消失。
//
// 这个文件只保留**不需要任何下载**的能力：程序化渲染器与 prompt hash。
// ---------------------------------------------------------------------------

} // extern "C"

package com.localmind.native.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Native procedural image renderer (see app/src/main/cpp/image_jni.cpp) plus the
 * integration slot for a user-supplied diffusion model.
 */
object ImageBridge {

    /**
     * 生图后端现在是**打包在 APK 里**的（装完就能用）。
     *
     * 以前它走"插件"这条路：用户得先下 36 MB 的 zip 再导入，而这一步在国内
     * 网络上经常几十分钟都下不完 —— 功能明明在，用户却永远看不到。
     * 现在 `liblocalmind-sd.so` 直接躺在 `jniLibs/arm64-v8a/` 里，
     * 这里 load 一次就有。**没打包的 ABI（armeabi-v7a）会抛 UnsatisfiedLinkError**，
     * 捕获后当作"没有后端"，退回程序化渲染 —— 和以前行为一致。
     *
     * 顺序必须在 `localmind` **之后**：sd 库依赖主库已经解析好的运行环境。
     */
    @Volatile
    private var bundledLoaded = false

    init {
        System.loadLibrary("localmind")
        bundledLoaded = runCatching { System.loadLibrary("localmind-sd") }.isSuccess
        if (bundledLoaded) android.util.Log.i("ImageBridge", "内置生图后端已加载")
    }

    /** 生图后端是不是随包带进来的（不是用户导入的插件）。 */
    val backendBundled: Boolean get() = bundledLoaded

    /** Renders into [pixels] (w*h ARGB words). Returns pixels written, or -1. */
    private external fun nativeRender(
        prompt: String,
        width: Int,
        height: Int,
        seed: Int,
        style: Int,
        pixels: IntArray,
    ): Int

    external fun nativePromptHash(prompt: String): Int

    external fun nativeSdLoad(modelPath: String): Boolean

    external fun nativeSdGenerate(
        modelPath: String,
        prompt: String,
        width: Int,
        height: Int,
        seed: Int,
        guidance: Float,
        pixels: IntArray,
    ): Int

    // ---------------------------------------------------------------- 加速
    //
    // 和 llama 那边同一个规矩：**不改老函数的参数表**。JNI 短名解析不含参数，
    // 参数列表一变，用户手机上那个已经下载好的旧插件 .so 就会被按错误的参数表
    // 调到 —— 不是报错，是崩溃。所以新能力一律走新函数，拿不到符号就退回旧的。
    private external fun nativeSdLoadEx(modelPath: String, threads: Int, flashMode: String): Boolean

    private external fun nativeSdGenerateEx(
        modelPath: String,
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Int,
        guidance: Float,
        threads: Int,
        flashMode: String,
        pixels: IntArray,
    ): Int

    // ------------------------------------------------------------------ 通用版
    //
    // 1.0.3 的插件多了两件事：**采样器/调度器可以指定**，以及**逐帧进度**。
    // 和老函数一样，这里必须是**新函数名** —— JNI 短名解析不含参数表，改签名等于
    // 让用户手机上那个旧插件被按错误的参数表调到，那是崩溃不是报错。

    /** 逐帧进度。`onProgress(II)V` 这个名字是原生侧写死的，改了两边一起改。 */
    interface ProgressSink {
        fun onProgress(step: Int, steps: Int)
    }

    /** 具名实现类（不是 lambda）—— 理由同 llama 的 TokenSink：R8 会合成掉 lambda。 */
    private class StepSink(private val onStep: (Int, Int) -> Unit) : ProgressSink {
        override fun onProgress(step: Int, steps: Int) {
            runCatching { onStep(step, steps) }
        }
    }

    private external fun nativeSdGenerateEx2(
        modelPath: String,
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Int,
        guidance: Float,
        threads: Int,
        flashMode: String,
        /** sd.cpp 的采样器名；空串 = 用模型族自己的默认值。 */
        sampler: String,
        /** 调度器名；空串 = 用默认。 */
        scheduler: String,
        pixels: IntArray,
        progress: ProgressSink?,
    ): Int

    /** 这个插件是不是「通用版」（支持采样器覆盖 + 进度回调）。 */
    private external fun nativeSdUniversal(): Boolean

    // ------------------------------------------------------- 1.1.0：图生图 / 超分
    /** 能力位：bit0 = 图生图（含蒙版重绘），bit1 = ESRGAN 超分。不碰锁、不碰模型。 */
    private external fun nativeSdCaps(): Int

    private external fun nativeSdGenerateEx3(
        modelPath: String,
        prompt: String,
        negative: String,
        width: Int,
        height: Int,
        steps: Int,
        seed: Int,
        guidance: Float,
        threads: Int,
        flashMode: String,
        sampler: String,
        scheduler: String,
        pixels: IntArray,
        progress: ProgressSink?,
        /** 初始图 ARGB；null = 文生图 */
        initArgb: IntArray?,
        initW: Int,
        initH: Int,
        strength: Float,
        /** 蒙版灰度（低 8 位），255 = 重绘；null = 整图 */
        maskGray: IntArray?,
    ): Int

    private external fun nativeSdUpscale(
        esrganPath: String,
        threads: Int,
        factor: Int,
        inArgb: IntArray,
        inW: Int,
        inH: Int,
        outArgb: IntArray,
    ): Int

    @Volatile private var capsProbed: Int? = null
    private val caps: Int
        get() = capsProbed ?: runCatching { nativeSdCaps() }.getOrDefault(0).also { capsProbed = it }

    /** 后端支不支持以图生图 / 局部重绘。 */
    val hasImg2Img: Boolean get() = hasDiffusionBackend && (caps and 1) != 0

    /** 后端支不支持 ESRGAN 超分。 */
    val hasUpscaler: Boolean get() = hasDiffusionBackend && (caps and 2) != 0

    // ---- 探测结果要缓存 ----
    //
    // 这三个 getter 以前每次调用都进原生：nativeSdUniversal / nativeSdLoad / nativeSdAccelInfo
    // 全都会拿 sd.cpp 的**全局互斥锁**。生图进行时主线程只要碰一下（ImageScreen 在
    // composition 里读 hasDiffusionBackend / hasExtendedDiffusion），就会阻塞在锁上 ——
    // 实测就是一次 ANR（nativeSdAccelInfo+48 → getHasExtendedDiffusion），
    // 随后进程被系统重启，正在生成的那张图一起没了。
    //
    // 这些值只跟"插件装没装/版本"有关，进程生命周期内不会变，缓存完全安全。
    @Volatile private var universalProbed: Boolean? = null
    val hasUniversalDiffusion: Boolean
        get() = universalProbed ?: runCatching { nativeSdUniversal() }
            .getOrDefault(false).also { universalProbed = it }

    private external fun nativeSdAccelInfo(): String

    /** 后端当前生效的 flash attention 模式等（诊断页用）。**会拿原生锁，别在主线程调。** */
    fun diffusionAccelInfo(): String = runCatching { nativeSdAccelInfo() }.getOrDefault("")

    @Volatile private var extendedProbed: Boolean? = null

    /** 插件包是否支持新入口（带步数与加速）。旧的 14 MB 插件不支持。 */
    val hasExtendedDiffusion: Boolean
        get() = extendedProbed ?: runCatching { nativeSdAccelInfo(); true }
            .getOrDefault(false).also { extendedProbed = it }

    /**
     * 扩散后端是不是可用。
     *
     * ## 它现在是"下载来的插件"，不再是"这个 build 有没有链进去"
     *
     * 以前这个值在编译期就定了（`LOCALMIND_HAS_SD`）。现在生图后端是独立的
     * `liblocalmind-sd.so`：用户没装时就**没有**这个能力，装了才有。
     *
     * 探测方式是让 natives 自己回答：这些 external 声明在主库里没有实现，
     * 所以库没加载时调用会抛 `UnsatisfiedLinkError` —— 捕获它返回 false 即可。
     * 这比在 Kotlin 侧维护一份"装了没"的状态更可靠：那份状态会和真实
     * 加载结果分叉，而这个不会。
     */
    @Volatile private var backendProbed: Boolean? = null

    val hasDiffusionBackend: Boolean
        get() = backendProbed ?: runCatching {
            // 随包内置的那份已经 load 进进程了，直接算可用：省掉一次 JNI 探测，
            // 也避免在 .so 缺失的 ABI 上先抛一次 UnsatisfiedLinkError 再被 catch。
            if (backendBundled) true else nativeSdLoad("")
        }.getOrDefault(false).also { backendProbed = it }

    /**
     * 加载 [modelPath] 到常驻上下文。上下文跨调用存活，所以换图不会重读
     * 一个几 GB 的 checkpoint。
     */
    fun loadDiffusion(modelPath: String): Boolean =
        runCatching { nativeSdLoad(modelPath) }.getOrDefault(false)

    /**
     * 带线程数与 flash attention 模式的加载。
     *
     * 旧插件没有 nativeSdLoadEx，会抛 UnsatisfiedLinkError —— 退回老入口，
     * 保证「拿不到加速项」不会变成「生图用不了」。
     */
    fun loadDiffusionEx(modelPath: String, threads: Int, flashMode: String): Boolean =
        runCatching { nativeSdLoadEx(modelPath, threads, flashMode) }.getOrElse {
            android.util.Log.w("ImageBridge", "插件不支持 nativeSdLoadEx，退回基础加载：${it.message}")
            loadDiffusion(modelPath)
        }

    /** 释放常驻模型。用户在设置里删掉模型时调，把那一两 GB 还回去。 */
    fun releaseDiffusion() {
        runCatching { nativeSdRelease() }
    }

    /** 后端自己的版本信息，给诊断页用。没装时返回 null。 */
    fun diffusionInfo(): String? = runCatching { nativeSdInfo() }.getOrNull()

    external fun nativeSdRelease()

    external fun nativeSdInfo(): String

    /**
     * Text -> Bitmap through the real diffusion backend.
     *
     * Returns null when the backend is absent, the checkpoint failed to load, or
     * generation failed — callers fall back to [render], so the UI never goes blank.
     */
    fun diffusionRender(
        modelPath: String,
        prompt: String,
        width: Int,
        height: Int,
        seed: Int,
        guidance: Float,
        /** 采样步数；<=0 = 用 checkpoint 自己的默认（这是 CPU 出图最大的一根杠杆）。 */
        steps: Int = 0,
        /** 负面提示词，空串 = 不加。 */
        negative: String = "",
        /** 线程数；0 = 让原生侧决定。 */
        threads: Int = 0,
        /** on / off / auto；空串 = 保持已加载的模式。 */
        flashMode: String = "",
        /** 采样器名（euler_a / lcm / dpm++2m …）；空串 = 用模型默认 —— 那是「通用」的默认行为。 */
        sampler: String = "",
        /** 调度器名（discrete / karras / lcm …）；空串 = 用模型默认。 */
        scheduler: String = "",
        /** 逐帧回调：现在第 step 步 / 共 steps 步。插件太旧时不会被调用。 */
        onStep: ((Int, Int) -> Unit)? = null,
        /** 初始图。给了就走图生图；尺寸会自动缩放到 [width]×[height]。 */
        init: Bitmap? = null,
        /** 局部重绘蒙版，长度必须 = width*height，值 255 = 重绘、0 = 保留。 */
        mask: IntArray? = null,
        /** 重绘强度 0..1，越小越贴近原图。 */
        strength: Float = 0.6f,
    ): Bitmap? {
        if (modelPath.isBlank() || !hasDiffusionBackend) return null
        val safeW = width.coerceIn(64, 1024)
        val safeH = height.coerceIn(64, 1024)
        val pixels = IntArray(safeW * safeH)

        // 图生图走 Ex3。它是新符号：后端不支持时**明确失败**而不是悄悄退化成
        // 文生图 —— 后者会让用户以为「我选的参考图生效了」，那比报错更难查。
        if (init != null) {
            if (!hasImg2Img) return null
            val initPx = IntArray(safeW * safeH)
            val scaled = if (init.width == safeW && init.height == safeH) init
                         else Bitmap.createScaledBitmap(init, safeW, safeH, true)
            scaled.getPixels(initPx, 0, safeW, 0, 0, safeW, safeH)
            val sink3 = onStep?.let { StepSink(it) }
            val written3 = runCatching {
                nativeSdGenerateEx3(
                    modelPath, prompt, negative, safeW, safeH, steps, seed, guidance,
                    threads, flashMode, sampler, scheduler, pixels, sink3,
                    initPx, safeW, safeH, strength,
                    mask?.takeIf { it.size >= safeW * safeH },
                )
            }.getOrElse { t ->
                android.util.Log.w("ImageBridge", "图生图入口不可用：${t.message}")
                return null
            }
            if (written3 <= 0) return null
            return Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888).also {
                it.setPixels(pixels, 0, safeW, 0, 0, safeW, safeH)
            }
        }
        // 三级回退：通用版 Ex2（采样器 + 进度）→ Ex（步数 + 加速）→ 最老的签名。
        // 每一级都对应一类用户手机上的插件版本，缺一级就是「某些人升级后不能出图」。
        val sink = onStep?.let { StepSink(it) }
        val written = runCatching {
            nativeSdGenerateEx2(
                modelPath, prompt, negative, safeW, safeH, steps, seed, guidance,
                threads, flashMode, sampler, scheduler, pixels, sink,
            )
        }.recoverCatching { e ->
            android.util.Log.i("ImageBridge", "插件不是通用版，退回 Ex：${e.message}")
            nativeSdGenerateEx(
                modelPath, prompt, negative, safeW, safeH, steps, seed, guidance,
                threads, flashMode, pixels,
            )
        }.getOrElse { t ->
            android.util.Log.w("ImageBridge", "插件不支持 nativeSdGenerateEx，退回基础出图：${t.message}")
            runCatching { nativeSdGenerate(modelPath, prompt, safeW, safeH, seed, guidance, pixels) }
                .getOrDefault(-1)
        }
        if (written <= 0) return null
        val bitmap = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, safeW, 0, 0, safeW, safeH)
        return bitmap
    }

    /**
     * ESRGAN 放大。返回 [bitmap] 放大 [factor] 倍后的新位图；后端或模型不可用时 null。
     *
     * 手机上应该「小图采样 + 超分」，而不是直接在大尺寸上采样 —— 后者内存
     * 按边长平方涨，6 GB 机上 1024² 就足以触发 LMK。超分只吃几十 MB 的权重。
     */
    fun upscale(
        esrganPath: String,
        bitmap: Bitmap,
        factor: Int = 4,
        threads: Int = 0,
    ): Bitmap? {
        if (!hasUpscaler || esrganPath.isBlank()) return null
        val f = factor.coerceIn(1, 4)
        val w = bitmap.width
        val h = bitmap.height
        // 4x 的 1024 输入就是 4096² = 6400 万字节，夹住上限防止一次申请把进程打爆。
        if (w <= 0 || h <= 0 || w.toLong() * h * f * f > 16_000_000L) return null
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val outW = w * f
        val outH = h * f
        val dst = IntArray(outW * outH)
        return runCatching {
            val writtenW = nativeSdUpscale(esrganPath, threads, f, src, w, h, dst)
            if (writtenW <= 0) return null
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also {
                it.setPixels(dst, 0, outW, 0, 0, outW, outH)
            }
        }.getOrElse { t ->
            android.util.Log.w("ImageBridge", "超分失败：${t.message}")
            null
        }
    }

    /**
     * Text -> Bitmap. Deterministic for a given (prompt, seed, style, size).
     * Falls back to a Kotlin-side renderer if the native library is unavailable,
     * so the UI never shows an empty screen.
     */
    fun render(
        prompt: String,
        width: Int,
        height: Int,
        seed: Int,
        styleNativeId: Int,
    ): Bitmap {
        val safeW = width.coerceIn(64, 2048)
        val safeH = height.coerceIn(64, 2048)
        val bitmap = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(safeW * safeH)
        val written = runCatching {
            nativeRender(prompt, safeW, safeH, seed, styleNativeId, pixels)
        }.getOrDefault(-1)

        if (written <= 0) {
            fallbackRender(pixels, safeW, safeH, prompt, seed, styleNativeId)
        }
        bitmap.setPixels(pixels, 0, safeW, 0, 0, safeW, safeH)
        return bitmap
    }

    /** Same look as the native generator but written in Kotlin (safety net). */
    private fun fallbackRender(
        pixels: IntArray,
        width: Int,
        height: Int,
        prompt: String,
        seed: Int,
        style: Int,
    ) {
        var h = prompt.hashCode() * 31 + seed
        fun next(): Float {
            h = h * 1664525 + 1013904223
            return ((h ushr 8) and 0xFFFFFF) / 16777215f
        }
        val hue = ((prompt.hashCode() and 0xFFFF) / 65535f)
        for (y in 0 until height) {
            val fy = y.toFloat() / height
            for (x in 0 until width) {
                val fx = x.toFloat() / width
                val n = (next() - 0.5f) * 12f
                val v = (0.55f + 0.35f * kotlin.math.sin((fx * 3f + hue * 6f) + fy * 2.2f)).coerceIn(0f, 1f)
                val r = ((v * 255) + n).toInt().coerceIn(0, 255)
                val g = ((v * 180 + 40) + n).toInt().coerceIn(0, 255)
                val b = (((1f - v) * 220) + n).toInt().coerceIn(0, 255)
                pixels[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** Small helper used for share sheets: stamps a caption onto a rendered image. */
    fun caption(bitmap: Bitmap, text: String): Bitmap {
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC000000.toInt()
            textSize = out.width / 32f
            typeface = Typeface.DEFAULT
        }
        val pad = out.width / 48f
        val lines = text.chunked(48).take(3)
        var y = out.height - pad - (lines.size - 1) * paint.textSize * 1.25f
        lines.forEach {
            canvas.drawText(it, pad, y, paint)
            y += paint.textSize * 1.25f
        }
        return out
    }
}

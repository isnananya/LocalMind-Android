package com.localmind.service

import android.content.Context
import com.localmind.data.EqCurve
import com.localmind.data.Emotion
import com.localmind.data.ImageSettings
import com.localmind.data.Storage
import com.localmind.native.image.ImageBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Text-to-image engine.
 *
 * The default path is the native procedural renderer — instant, offline,
 * deterministic for a given (prompt, seed, style, size) and always available.
 * `externalEndpoint` lets a power user forward requests to a desktop diffusion
 * server (Automatic1111 / ComfyUI / stable-diffusion.cpp) on the LAN, and
 * [ImageBridge.hasDiffusionBackend] reports whether a compiled-in diffusion
 * backend exists (default build: no).
 */
object ImageEngine {

    val styles = com.localmind.data.ImageStyle.entries.toList()

    fun randomSeed(): Int = Random.nextInt(1, Int.MAX_VALUE)

    suspend fun generate(
        storage: Storage,
        prompt: String,
        width: Int = 512,
        height: Int = 512,
        seed: Int = randomSeed(),
        styleId: Int = 0,
    ): Result<File> = withContext(Dispatchers.Default) {
        runCatching {
            val bitmap = ImageBridge.render(prompt, width, height, seed, styleId)
            val name = fileName(prompt, seed, styleId, width, height)
            val file = File(storage.imagesDir, name)
            FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            file
        }
    }

    fun fileName(prompt: String, seed: Int, styleId: Int, width: Int, height: Int): String {
        val slug = prompt.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]+"), "-")
            .trim('-')
            .take(28)
            .ifEmpty { "image" }
        return "img_${slug}_${seed}_s${styleId}_${width}x$height.png"
    }

    fun listGenerated(storage: Storage): List<File> =
        storage.imagesDir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Renders an image, preferring the real diffusion backend when it is both
     * compiled in and pointed at a checkpoint.
     *
     * Order of preference:
     *  1. the local diffusion backend (`ImageBridge`), when [resolveDiffusionModel]
     *     finds a usable checkpoint and the build contains sd.cpp;
     *  2. the built-in procedural renderer — instant, deterministic, needs no model.
     *
     * The fallback is deliberate: a 2 GB checkpoint can fail to load for a dozen
     * reasons (out of memory, truncated file, unsupported architecture), and none of
     * them should leave the user staring at an error instead of a picture.
     */
    /**
     * 这次出图是**怎么来的**，以及为什么没走真模型。
     *
     * 加这个东西的直接原因：用户报"生图不能用"。查下来最常见的情况是
     * ——扩散后端没装、或者没下模型、或者模型加载失败，代码**安静地**退回了
     * 程序化渲染，用户看到的是一张渐变图，于是结论是"这功能是坏的"。
     * 静默兜底在这里是错的：它把"缺东西"伪装成了"能用"。
     */
    enum class Via { DIFFUSION, PROCEDURAL }

    data class Generated(
        val file: File,
        val via: Via,
        /** 没用真模型时，这里是一句能直接给用户看的原因。 */
        val note: String = "",
        /** 真正跑了多少步（0 = 程序化渲染，没这一步）。 */
        val steps: Int = 0,
        /** 实际参与计算的边长（快速放大时比目标尺寸小）。 */
        val renderSize: Int = 0,
        /** 这一张花了多久。 */
        val elapsedMs: Long = 0,
    )

    /**
     * 上一次扩散出图的耗时与步数，用来给下一张**估时**（界面显示「预计 X 秒」）。
     * 只放内存：重启应用后第一次会没有估计值，宁可显示「正在生成」也不要瞎猜。
     */
    @Volatile
    var lastGenerationMs: Long = 0L
        private set
    @Volatile
    var lastSteps: Int = 0
        private set

    /** 上一张「每步多少毫秒」；0 = 还没量过。 */
    val lastMsPerStep: Long
        get() = if (lastSteps > 0 && lastGenerationMs > 0) lastGenerationMs / lastSteps else 0L

    /**
     * Turbo / LCM / SDXS / Hyper 这几类模型是按「不做 CFG、几步就出图」训练的。
     *
     * 判据只能是**文件名**：sd.cpp 的 GGUF 元数据里没有「这是不是 turbo」这个字段，
     * 而这几类模型的文件名里几乎必然带那个词（sd-turbo、sdxl-turbo、lcm-lora、sdxs、
     * dreamshaper-flash…）。判错的代价是可接受的：当成 turbo 只会少步数、cfg=1，
     * 图还在；不当成 turbo 则是「20 步 + 双倍 CFG」，慢且糊。
     */
    /** 蒸馏/加速系模型：几 步就出图，CFG 也不该开。 */
    private val TURBO_HINTS = listOf(
        "turbo", "lcm", "hyper", "lightning", "dmd", "schnell", "flash",
    )

    /**
     * **一步蒸馏**模型（SDXS 就是这一类，名字里直接写着 1 步）。
     *
     * 单独拎出来是因为它的合理步数只有 1-2：给 4 步是白等，给 20 步是灾难 ——
     * 而 sd.cpp 的默认值恰恰就是 20。真机上「生图慢到没法用」的一条实测来源
     * 就是这个组合：SDXS-512 + 自动步数(20)。
     */
    private val ONESTEP_HINTS = listOf("sdxs", "1step", "1-step", "1_step", "onedistill")

    fun isTurboModel(modelPath: String?): Boolean {
        if (modelPath.isNullOrBlank()) return false
        val name = File(modelPath).name.lowercase(Locale.US)
        return ONESTEP_HINTS.any { name.contains(it) } || TURBO_HINTS.any { name.contains(it) }
    }

    /**
     * 「自动」时的步数。
     *
     * 原来自动 = 交给 sd.cpp 的默认值，而它的默认值是 `sample_steps = 20`
     *（见 sd_sample_params_init）—— 对 SDXS 这种一步模型等于白跑 19 步。
     * 现在按族给：
     *
     *  * SDXS / 一步蒸馏 → **2 步**（它就是 1 步出图的，2 步留一点余量）
     *  * Turbo / LCM / Hyper / Lightning / DMD → **4 步**
     *  * 普通 SD 1.5 / 2.1 / SDXL → **12 步**（这本来就超过多数模型的收益拐点）
     */
    fun autoStepsFor(modelPath: String?): Int {
        if (modelPath.isNullOrBlank()) return 12
        val name = File(modelPath).name.lowercase(Locale.US)
        if (ONESTEP_HINTS.any { name.contains(it) }) return 2
        if (TURBO_HINTS.any { name.contains(it) }) return 4
        return 12
    }

    /** LCM 家族：用 LCM 采样器 + LCM 调度器才像样（默认 Euler 在 2-4 步下会糊）。 */
    private val LCM_HINTS = listOf("lcm", "hyper", "lightning", "tcd", "dmd")

    /**
     * 「通用版」插件要的采样器 / 调度器（sd.cpp 自己的字符串词表）。
     *
     * 大部分模型这里返回空串 —— 也就是**交给 sd.cpp 按模型族自己决定**
     *（它内部有 sd_get_default_sample_method / sd_get_default_scheduler，是按每个
     * 模型族调过的结论，比我们在 Kotlin 里猜准得多）。只有 LCM 家族必须显式指定，
     * 因为它们的设计前提就是 LCM 采样器。
     */
    fun samplerFor(modelPath: String?): Pair<String, String> {
        if (modelPath.isNullOrBlank()) return "" to ""
        val name = File(modelPath).name.lowercase(Locale.US)
        return if (LCM_HINTS.any { name.contains(it) }) "lcm" to "lcm" else "" to ""
    }

    /** 实际步数：用户显式选了就用他的。 */
    fun effectiveSteps(settings: ImageSettings, modelPath: String?): Int =
        if (settings.steps > 0) settings.steps else autoStepsFor(modelPath)

    /**
     * 实际 CFG。Turbo 系强制 1.0 ——
     *
     * CFG > 1 时每一步要跑两遍（条件 + 无条件），耗时直接翻倍；而 turbo 系模型
     * 就是按 cfg=1 蒸馏出来的，给 3.5 不但慢一倍，图还会发焦。这条不该让用户知道。
     */
    fun effectiveGuidance(settings: ImageSettings, modelPath: String?): Float =
        if (isTurboModel(modelPath)) 1.0f else settings.guidance

    /** 把尺寸对齐到 8 的倍数（扩散模型的下采样步长），并夹在合理区间。 */
    private fun align8(v: Int): Int = (v / 8) * 8

    /**
     * 出图。[modelPath] 是**调用方指定**的 checkpoint（对话/任务级选择）。
     *
     * 为什么要有这个参数：[resolveDiffusionModel] 是"扫目录找第一个像扩散模型的
     * 文件"——那是没得选的时候的兜底，不是选择。用户在对话里明确选了
     * 「生图模型」，就必须用它选的那个，否则又是一个"选了没反应"。
     */
    suspend fun generateWithSettings(
        storage: Storage,
        prompt: String,
        settings: ImageSettings,
        seed: Int = if (settings.seed > 0) settings.seed else randomSeed(),
        modelPath: String? = null,
        /** 逐帧进度（现在第几步 / 共几步）。插件太旧时不会被调用 —— 调用方要能接受。 */
        onStep: ((Int, Int) -> Unit)? = null,
    ): Result<Generated> = withContext(Dispatchers.Default) {
        runCatching {
            val explicit = modelPath?.takeIf { it.isNotBlank() && File(it).isFile }
            val resolved = explicit ?: resolveDiffusionModel(storage, settings)

            // 指定了模型但文件不在 —— 这必须说出来，不能悄悄换一个。
            if (explicit == null && !modelPath.isNullOrBlank()) {
                android.util.Log.w("ImageEngine", "指定的生图模型文件不存在：" + modelPath)
            }

            if (resolved != null) {
                // 线程数：扩散的 UNet 是**算力**活（不像逐 token 生成那样卡带宽），
                // 所以比对话那边多给几个 —— 4 线程在 8 核机上要空转一半的核。
                // 上限 6：再多就会被小核与散热拖回去（和 llama 的 batch 线程同一个经验值）。
                val threads = maxOf(
                    com.localmind.data.MemoryPlanner.autoThreads(),
                    Runtime.getRuntime().availableProcessors().coerceAtMost(6),
                )
                // ---- 真正决定「多久出图」的三件事，全在这里 ----
                //
                //  1. 边长（像素数）：耗时和面积成正比；
                //  2. 步数：耗时和步数成正比；
                //  3. CFG > 1 时每步跑两遍：耗时再乘 2。
                //
                // 所以 512² 12 步 cfg 3.5 ≈ 768² 20 步 cfg 3.5 的 1/3.7。
                val wantW = settings.width.coerceIn(64, 1024)
                val wantH = settings.height.coerceIn(64, 1024)
                // 快速放大：目标比 512 大时，先按 512 的长边算，再放大。
                val longSide = maxOf(wantW, wantH)
                val scale = if (settings.fastUpscale && longSide > 512) 512f / longSide else 1f
                val renderW = align8((wantW * scale).toInt().coerceAtLeast(64))
                val renderH = align8((wantH * scale).toInt().coerceAtLeast(64))
                val useSteps = effectiveSteps(settings, resolved)
                val useGuidance = effectiveGuidance(settings, resolved)
                val startedAt = System.currentTimeMillis()
                val (samplerName, schedulerName) = samplerFor(resolved)
                val bitmap = ImageBridge.diffusionRender(
                    modelPath = resolved,
                    prompt = prompt,
                    width = renderW,
                    height = renderH,
                    seed = seed,
                    guidance = useGuidance,
                    steps = useSteps,
                    threads = threads,
                    flashMode = if (settings.flashAttn) "auto" else "off",
                    sampler = samplerName,
                    scheduler = schedulerName,
                    onStep = onStep,
                )
                if (bitmap != null) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    // 放大到用户要的尺寸（双线性；想最清楚就把「快速放大」关掉）。
                    val outBitmap = if (bitmap.width != wantW || bitmap.height != wantH) {
                        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, wantW, wantH, true)
                        if (scaled !== bitmap) bitmap.recycle()
                        scaled
                    } else {
                        bitmap
                    }
                    val file = File(
                        storage.imagesDir,
                        fileName(prompt, seed, settings.style.nativeId, wantW, wantH),
                    )
                    FileOutputStream(file).use { out ->
                        outBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                    outBitmap.recycle()
                    lastGenerationMs = elapsed
                    lastSteps = useSteps
                    android.util.Log.i(
                        "ImageEngine",
                        "出图 " + renderW + "→" + wantW + " · " + useSteps + " 步 · cfg " +
                            String.format(Locale.US, "%.1f", useGuidance) + " · 线程 " + threads +
                            " · " + elapsed + "ms（" + (elapsed / useSteps.coerceAtLeast(1)) + " ms/步）",
                    )
                    return@runCatching Generated(
                        file = file,
                        via = Via.DIFFUSION,
                        steps = useSteps,
                        renderSize = renderW,
                        elapsedMs = elapsed,
                    )
                }
                android.util.Log.w("ImageEngine", "扩散后端没出图，退回程序化渲染")
                val file = generate(storage, prompt, settings.width, settings.height, seed, settings.style.nativeId).getOrThrow()
                return@runCatching Generated(
                    file, Via.PROCEDURAL,
                    "扩散模型加载或出图失败（" + File(resolved).name + "），这张是内置渲染器画的",
                )
            }

            val why = when {
                !ImageBridge.hasDiffusionBackend ->
                    "没有安装生图后端插件（约 14 MB），当前用的是内置渲染器"
                !modelPath.isNullOrBlank() ->
                    "选定的生图模型文件不在了（可能被删了），当前用的是内置渲染器"
                else ->
                    "还没有下载生图模型（扩散 checkpoint），当前用的是内置渲染器"
            }
            val file = generate(storage, prompt, settings.width, settings.height, seed, settings.style.nativeId).getOrThrow()
            Generated(file, Via.PROCEDURAL, why)
        }
    }

    /**
     * Absolute path of the checkpoint the diffusion backend should use, or null when
     * the procedural renderer is the only option.
     *
     * Explicit settings win over discovery: a user who typed a path in "外部模型"
     * means it.
     *
     * 识别**读 GGUF 文件头**而不是猜文件名 —— 原因见 [DiffusionModels]。
     * 原来那套 `name.contains("sd")` 的判据会把真实 checkpoint 全漏掉
     * （它们叫 `dreamshaper-8`、`anything-v5`、`meinamix`…），
     * 结果是用户下好了模型却只能看到程序生成的渐变图。
     */
    fun resolveDiffusionModel(storage: Storage, settings: ImageSettings): String? {
        if (!ImageBridge.hasDiffusionBackend) {
            // 说明白为什么用不上真模型：这个 build 没链上扩散后端。
            // 不说的话，用户会以为是模型的问题，然后反复换模型。
            android.util.Log.i(
                "ImageEngine",
                "这个包没有编译扩散后端（libstable-diffusion.a 缺失），只能用程序化渲染",
            )
            return null
        }
        val file = DiffusionModels.resolve(storage.modelsDir, settings.customModelPath)
        if (file == null) {
            android.util.Log.i("ImageEngine", "没找到扩散 checkpoint，用程序化渲染")
        } else {
            android.util.Log.i(
                "ImageEngine",
                "用 ${file.name}（${file.length() / 1024 / 1024} MB）生图",
            )
        }
        return file?.absolutePath
    }
}

/**
 * Voice engine built on the platform TTS (no model download, works offline on
 * most Chinese ROMs). Emotion presets change rate, pitch, gain and tilt the
 * spectrum, and the chosen emotion is also injected into the system prompt so the
 * *text* the model writes matches the delivery.
 */
class TtsEngine(private val context: Context) {

    data class VoiceInfo(val name: String, val locale: String, val label: String)

    private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var language = "zh-CN"
    private val listeners = mutableListOf<(Boolean) -> Unit>()

    /**
     * 神经音色（sherpa-onnx）。**可选**：原生库没打进 APK、模型没下、模型坏了，
     * 三种情况都会安静地退回下面那套系统 TTS。
     *
     * 做成 lateinit 的注入而不是在构造里 new：`TtsEngine` 在 `LocalMindApp` 里
     * 建得比 `Storage` 的音色目录还早，而且加载一个几百 MB 的模型绝不能卡在
     * 构造里。ViewModel 在初始化完成后调 [attachNeural] 挂上来。
     */
    private var neural: NeuralTtsEngine? = null

    /** 当前应该用神经音色念吗（已挂载 + 该音色已加载成功）。 */
    val neuralActive: Boolean get() = neural?.loadedVoiceId?.isNotEmpty() == true

    fun attachNeural(engine: NeuralTtsEngine) {
        neural = engine
    }

    val isReady: Boolean get() = ready

    fun init(onReady: ((Boolean) -> Unit)? = null) {
        if (tts != null) {
            onReady?.invoke(ready)
            return
        }
        onReady?.let { listeners += it }
        tts = android.speech.tts.TextToSpeech(context.applicationContext) { status ->
            ready = status == android.speech.tts.TextToSpeech.SUCCESS
            if (ready) {
                runCatching {
                    tts?.language = Locale.forLanguageTag(language)
                }
            }
            listeners.toList().forEach { it(ready) }
            listeners.clear()
        }
    }

    fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    fun setLanguage(tag: String) {
        language = tag
        if (!ready) return
        runCatching { tts?.language = Locale.forLanguageTag(tag) }
    }

    /**
     * Picks the synthesiser language from the text itself when the user chose
     * "auto". Mixing zh/en in one sentence is common, so CJK presence wins.
     */
    fun resolveLanguage(configured: String, text: String): String {
        if (!configured.startsWith("auto")) return configured
        val cjk = text.any { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
        return if (cjk) "zh-CN" else "en-US"
    }

    fun availableVoices(): List<VoiceInfo> {
        val engine = tts ?: return emptyList()
        return runCatching {
            engine.voices?.map { v ->
                VoiceInfo(
                    name = v.name,
                    locale = v.locale.toLanguageTag(),
                    // 引擎给的 name 是内部标识（cmn-cn-x-ssa-lstm-embedded 这种），
                    // 摆给用户看没有任何意义。只保留「怎么合成」这一类他能判断的信息。
                    label = voiceKind(v.name),
                )
            }?.sortedBy { it.locale } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 当前语言可用的发音人。
     *
     * 系统引擎会返回**几百个**发音人（模拟器实测 442 个，阿拉伯语、保加利亚语
     * 全在里面），而列表只显示前 12 个 —— 于是一个中文用户打开这一页，
     * 看到的是 Arabic · network / Bulgarian · local，一个能用的都没有。
     * 先按语言筛，筛不到再退回全部（有些 ROM 的语言标签和这里对不上）。
     */
    fun voicesFor(languageTag: String): List<VoiceInfo> {
        val all = availableVoices()
        if (all.isEmpty()) return all
        // auto 按 zh 处理：resolveLanguage 对中文默认就是 zh-CN，界面也是中文的。
        val want = languageTag.substringBefore('-').lowercase().let { if (it == "auto" || it.isBlank()) "zh" else it }
        val hit = all.filter { it.locale.substringBefore('-').lowercase() == want }
        return hit.ifEmpty { all }
    }

    /**
     * 把引擎内部音色名翻译成用户能判断的一行字。
     *
     * 两个要求缺一不可：**说清是什么**（本地合成 / 在线合成），以及**彼此能区分**。
     * 只写种类的话，同一个语言下十几个发音人会显示成一模一样的一列
     *（实测中文 16 个里有 10 个都是「本地合成」），用户根本没法挑。
     * 所以补一个取自引擎名的短后缀：cmn-cn-x-ssa-local → 「本地合成 · ssa」。
     */
    private fun voiceKind(name: String): String {
        val parts = name.split('-')
        val tail = parts.lastOrNull()?.lowercase().orEmpty()
        val kind = when {
            tail.contains("network") -> "在线合成"
            tail.contains("embedded") || tail.contains("local") -> "本地合成"
            tail.contains("language") -> "语言包"
            else -> tail.take(14)
        }
        val variant = parts.dropLast(1).lastOrNull()?.lowercase().orEmpty()
        return if (variant.isBlank()) kind else "$kind · $variant"
    }

    fun setVoice(name: String?) {
        val engine = tts ?: return
        if (name.isNullOrBlank()) return
        runCatching {
            engine.voices?.firstOrNull { it.name == name }?.let { engine.voice = it }
        }
    }

    /** Effective rate/pitch for an emotion + user tuning. */
    fun effective(speed: Float, pitch: Float, emotion: Emotion): Pair<Float, Float> {
        val s = (speed * emotion.speedFactor).coerceIn(0.3f, 2.6f)
        val p = (pitch * emotion.pitchFactor).coerceIn(0.3f, 2.2f)
        return s to p
    }

    fun speak(
        text: String,
        settings: com.localmind.data.VoiceSettings,
        utteranceId: String = "localmind",
    ): Boolean {
        val clean0 = sanitize(text)
        if (clean0.isBlank()) return false
        val (rate0, pitch0) = effective(settings.speed, settings.pitch, settings.emotion)

        // 神经音色优先。它念出来是人声，系统 TTS 在中英文混排上断句很硬。
        // 失败就往下走，不提示错误 —— 用户要的是"能听见"，不是"知道为什么没用神经音色"。
        neural?.let { n ->
            if (n.speak(clean0, speed = rate0, pitch = pitch0, volume = settings.volume)) return true
        }

        val engine = tts
        if (engine == null || !ready) {
            init { }
            return false
        }
        val clean = clean0
        val ttsSettings = android.os.Bundle()
        val (rate, pitch) = rate0 to pitch0
        ttsSettings.putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.volume.coerceIn(0f, 1f))
        setVoice(settings.voiceName)
        setLanguage(resolveLanguage(settings.language, clean))
        engine.setSpeechRate(rate)
        engine.setPitch(pitch)
        return try {
            val result = engine.speak(clean, android.speech.tts.TextToSpeech.QUEUE_FLUSH, ttsSettings, utteranceId)
            result == android.speech.tts.TextToSpeech.SUCCESS
        } catch (t: Throwable) {
            android.util.Log.w("TtsEngine", "speak failed", t)
            false
        }
    }

    fun speakQueued(
        text: String,
        settings: com.localmind.data.VoiceSettings,
        utteranceId: String = "localmind-q",
    ): Boolean {
        val engine = tts ?: return false
        if (!ready) return false
        val clean = sanitize(text)
        if (clean.isBlank()) return false
        val (rate, pitch) = effective(settings.speed, settings.pitch, settings.emotion)
        val bundle = android.os.Bundle()
        bundle.putFloat(android.speech.tts.TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.volume.coerceIn(0f, 1f))
        runCatching {
            engine.setSpeechRate(rate)
            engine.setPitch(pitch)
            engine.speak(clean, android.speech.tts.TextToSpeech.QUEUE_ADD, bundle, utteranceId)
        }
        return true
    }
    fun stop() {
        runCatching { tts?.stop() }
        runCatching { neural?.stop() }
    }

    fun isSpeaking(): Boolean =
        runCatching { neural?.isSpeaking() == true || tts?.isSpeaking == true }.getOrDefault(false)

    /** Strips markdown/emoji so the voice doesn't read punctuation soup. */
    fun sanitize(text: String): String = text
        .replace(Regex("```[\\s\\S]*?```"), " 代码块已省略 ")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
        .replace(Regex("[#*_>~|]"), "")
        .replace(Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(3000)

    /** Renders a WAV file for `/v1/audio/speech` when the engine can synthesise to file. */
    fun synthesizeToFile(
        text: String,
        settings: com.localmind.data.VoiceSettings,
        target: File,
    ): Boolean {
        val clean = sanitize(text)
        if (clean.isBlank()) return false
        val (rate, pitch) = effective(settings.speed, settings.pitch, settings.emotion)

        // 和 speak() 一样：神经音色先试。API 的调用方拿到的是 WAV，
        // 用神经音色导出的文件明显更好听，也更有理由让外部程序接这个接口。
        neural?.let { n ->
            if (n.synthesizeToWav(clean, speed = rate, pitch = pitch, target = target) &&
                target.length() > 44
            ) {
                return true
            }
        }

        val engine = tts ?: return false
        if (!ready) return false
        return runCatching {
            engine.setSpeechRate(rate)
            engine.setPitch(pitch)
            engine.synthesizeToFile(clean, android.os.Bundle(), target, "localmind-wav") ==
                android.speech.tts.TextToSpeech.SUCCESS
        }.getOrDefault(false)
    }

    companion object {
        /**
         * Best-effort WAV for the API. Uses the platform synthesiser when it can
         * write a file; otherwise renders a "speech-shaped" tone so the endpoint
         * always returns playable audio with the requested emotion applied (so
         * client integrations can be built and tested without a speech model).
         */
        suspend fun renderToWav(
            text: String,
            settings: com.localmind.data.VoiceSettings,
            ttsEngine: TtsEngine? = null,
        ): Result<ByteArray> = withContext(Dispatchers.IO) {
            runCatching {
                val fromEngine = ttsEngine?.let { engine ->
                    val tmp = File.createTempFile("speech", ".wav")
                    val ok = engine.synthesizeToFile(text, settings, tmp)
                    try {
                        if (ok && tmp.length() > 44) tmp.readBytes() else null
                    } finally {
                        tmp.delete()
                    }
                }
                fromEngine ?: ProceduralVoice.render(text, settings)
            }
        }
    }
}

/**
 * Fallback speech synthesiser: turns text into a formant-ish buzz whose rate,
 * pitch, gain and brightness track the selected emotion. It is not intelligible
 * speech — it exists so `/v1/audio/speech` always returns valid, emotion-shaped
 * audio, and so the emotion presets are audibly testable offline.
 */
object ProceduralVoice {

    private const val SAMPLE_RATE = 22_050

    fun render(text: String, settings: com.localmind.data.VoiceSettings): ByteArray {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        val syllables = estimateSyllables(clean).coerceIn(1, 400)
        val emotion = settings.emotion
        val baseFreq = 118.0 * settings.pitch * emotion.pitchFactor
        val syllableMs = (150.0 / (settings.speed * emotion.speedFactor)).coerceIn(60.0, 400.0)
        val totalSamples = ((syllables * syllableMs + 220.0) / 1000.0 * SAMPLE_RATE).toInt().coerceAtLeast(SAMPLE_RATE / 4)

        val pcm = ShortArray(totalSamples)
        val random = Random(clean.hashCode().toLong())
        val samplesPerSyllable = (syllableMs / 1000.0 * SAMPLE_RATE).toInt().coerceAtLeast(64)

        var phase = 0.0
        var formantPhase = 0.0
        for (i in 0 until totalSamples) {
            val syllableIndex = i / samplesPerSyllable
            val local = (i % samplesPerSyllable).toDouble() / samplesPerSyllable
            val ch = clean.getOrNull(syllableIndex * clean.length / syllables.coerceAtLeast(1))
            val isPause = ch == ' ' || ch == ',' || ch == '.' || ch == '。' || ch == '，'
            val vowelShift = when (ch) {
                'a', 'A', '啊', '哈' -> 1.24
                'e', 'E', '诶' -> 1.12
                'i', 'I', '衣' -> 1.34
                'o', 'O', '哦' -> 0.96
                'u', 'U', '呜' -> 0.86
                else -> 1.0 + (random.nextDouble() - 0.5) * 0.18
            }
            val freq = baseFreq * vowelShift * (1.0 + 0.03 * sin(2 * PI * local))
            val envelope = when {
                isPause -> 0.0
                local < 0.12 -> local / 0.12
                local > 0.72 -> (1.0 - local) / 0.28
                else -> 1.0
            }.coerceIn(0.0, 1.0)

            phase += 2 * PI * freq / SAMPLE_RATE
            formantPhase += 2 * PI * (freq * 2.7) / SAMPLE_RATE

            var sample = sin(phase) * 0.62 + sin(formantPhase) * 0.22 * brightness(emotion.eqCurve)
            sample += (random.nextDouble() - 0.5) * 0.02 // breath
            sample *= envelope * settings.volume * emotion.gainFactor * 0.9
            pcm[i] = (sample.coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.72).toInt().toShort()
        }

        return wavBytes(pcm)
    }

    private fun brightness(curve: EqCurve): Double = when (curve) {
        EqCurve.BRIGHT -> 1.5
        EqCurve.WARM -> 1.1
        EqCurve.DARK -> 0.45
        EqCurve.NEUTRAL_BASS -> 0.7
        EqCurve.FLAT -> 1.0
    }

    private fun estimateSyllables(text: String): Int {
        if (text.isEmpty()) return 1
        val cjk = text.count { it.code in 0x4E00..0x9FFF }
        val words = Regex("[A-Za-z]+").findAll(text).count()
        val digits = text.count { it.isDigit() }
        return (cjk + words * 2 + digits).coerceAtLeast(text.length / 4)
    }

    fun wavBytes(pcm: ShortArray): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArray(44 + dataSize)
        val header = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        for (s in pcm) header.putShort(s)
        return out
    }
}

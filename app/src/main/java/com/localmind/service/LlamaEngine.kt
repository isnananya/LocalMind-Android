package com.localmind.service

import android.content.Context
import android.util.Log
import com.localmind.data.GenerationSettings
import com.localmind.data.LocalModel
import com.localmind.data.MemoryPlanner
import com.localmind.data.ModelMemoryPlan
import com.localmind.data.Role
import com.localmind.native.llama.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private const val TAG = "LlamaEngine"

data class EngineState(
    val ready: Boolean = false,
    val loading: Boolean = false,
    val modelName: String? = null,
    val modelPath: String? = null,
    val contextSize: Int = 0,
    val threads: Int = 0,
    val plan: ModelMemoryPlan? = null,
    val lastError: String? = null,
    val generating: Boolean = false,
    val tokensGenerated: Int = 0,
    val lastTokensPerSecond: Double = 0.0,
    val systemInfo: String = "",
    val nativeAvailable: Boolean = false,
    /** Encoder-only file (embedding model): loadable, but cannot serve chat. */
    val encoderOnly: Boolean = false,
    /**
     * 这个模型现在能真的看图吗。
     *
     * 要两件事同时成立：主模型是多模态的，**且**配套的 mmproj（视觉投影）已经装上。
     * 只有主模型时为 false —— 界面据此如实说"还差视觉投影"，而不是让用户发了图
     * 得到一句没看见图的回复。
     */
    val visionReady: Boolean = false,
    /**
     * 真正生效的推理加速配置（原生侧回读）。
     *
     * 它和设置里选的可能**不一样**：flash attention / 量化 KV 在部分模型上
     * 初始化会失败，原生侧会自动降级后继续加载。诊断页要显示的是生效值。
     */
    val accelInfo: String = "",
    /**
     * 上一轮的提示词一共多少 token、其中多少**直接复用了 KV**（没有重算）。
     *
     * 手机上 prefill 常常比逐 token 生成还贵，所以"对话变快了多少"这个问题，
     * 答案几乎全在这两个数字里：复用率越高，首 token 越早。
     * 见 docs/SPEED.md。
     */
    val lastPromptTokens: Int = 0,
    val lastReusedTokens: Int = 0,
    /** prefill（含模板渲染与分词）花了多少毫秒 —— 也就是首 token 的等待。 */
    val lastPrefillMs: Long = 0,
    /** 逐 token 生成花了多少毫秒。 */
    val lastGenMs: Long = 0,
    /** 首 token 延迟的观测值（prefill + 第一个 token 的解码）。 */
    val lastFirstTokenMs: Long = 0,
) {
    /** 提示词里有多大比例是从上一轮的 KV cache 直接读出来的。 */
    val cacheHitPercent: Int
        get() = if (lastPromptTokens <= 0) 0
        else (lastReusedTokens * 100 / lastPromptTokens).coerceIn(0, 100)

    val canChat: Boolean get() = ready && !encoderOnly
}

/**
 * Owns the single native llama.cpp engine for the whole app (UI + API server).
 *
 * A llama_context can only decode one sequence at a time, so every entry point
 * (`chat`, the HTTP API, voice mode) funnels through the same [Mutex]. Callers
 * that arrive while a generation is running simply wait — which is exactly the
 * behaviour you want when the phone has one model in memory.
 */
class LlamaEngine(private val context: Context) {

    private val mutex = Mutex()
    private var handle: Long = 0L

    /**
     * Called after every completed generation, whatever triggered it.
     *
     * Deliberately at the engine layer: the UI chat path and the HTTP API both end up
     * here, and companion learning must not silently depend on which one was used.
     * Installed once by the application container.
     */
    @Volatile
    var onTurnComplete: (suspend (userText: String, assistantText: String) -> Unit)? = null

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    init {
        _state.value = _state.value.copy(
            nativeAvailable = runCatching { LlamaBridge.nativeInit() }.isSuccess,
            systemInfo = runCatching { LlamaBridge.systemInfoSafe() }.getOrDefault(""),
        )
    }

    val isLoaded: Boolean get() = handle != 0L && _state.value.ready

    fun systemInfo(): String = runCatching { LlamaBridge.systemInfoSafe() }.getOrDefault("不可用")

    /**
     * Loads a GGUF file. The memory planner picks a context size that actually fits
     * this device unless the user pinned one in settings.
     */
    suspend fun load(
        model: LocalModel,
        settings: GenerationSettings,
        /**
         * 配套的视觉投影文件。为空 = 这个模型不带识图能力（或者用户只下了主模型）。
         *
         * 注意它**不影响主模型能否加载**：装不上 mmproj 只是没有识图，
         * 对话照常 —— 用户不该因为一个可选文件而下不了台。
         */
        mmprojPath: String? = null,
    ): Result<Unit> = mutex.withLock {
        val path = model.localPath ?: model.file()?.absolutePath
            ?: return@withLock Result.failure(IllegalStateException("模型文件不存在，请先下载"))
        val file = File(path)
        if (!file.exists()) return@withLock Result.failure(IllegalStateException("模型文件不存在：$path"))

        _state.value = _state.value.copy(
            loading = true,
            lastError = null,
            modelName = model.name,
            systemInfo = runCatching { LlamaBridge.systemInfoSafe() }.getOrDefault(""),
        )

        // 低端模式：先按**总内存**算出这台设备该被夹到什么程度，再交给规划器。
        // 这里算一遍是为了拿到 kvQuant —— 规划器要用"真的会用的精度"估 KV，
        // 否则 2.5 GB 的机器按 f16 估出来会比实际需要的保守一倍。
        val lowEnd = withContext(Dispatchers.IO) { MemoryPlanner.lowEndPolicy(context) }
        val kvName = lowEnd?.kvQuant ?: settings.kvQuant
        val plan = withContext(Dispatchers.IO) {
            MemoryPlanner.plan(
                context, file, settings.contextSize, settings.threads, settings.mmap,
                // 量化 KV 时按实际元素大小估算，否则规划器会按 f16 保守一倍，
                // 用户开了 q8_0 却看不到「上下文能开更大」这件事。
                kvBytesPerElement = when (kvName.lowercase()) {
                    "q8_0", "q8" -> 1
                    "q4_0", "q4" -> 1  // q4_0 实际 0.5，但按 1 估更保守：留出零头
                    else -> 2
                },
                policy = lowEnd,
            )
        }
        _state.value = _state.value.copy(plan = plan)

        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (handle == 0L) {
                    handle = LlamaBridge.nativeInit()
                }
                if (handle == 0L) error("nativeInit 返回 0")
                LlamaBridge.nativeUnload(handle)
                // ---- 加速参数 ----
                //
                // 批量与分块：设置里默认 512/256，但**不能超过上下文** ——
                // n_batch 大于 n_ctx 没有意义，先夹一次是为了让日志与诊断页
                // 显示的数字就是真实用的那个。
                // batch 也要跟低端模式对齐：ctx 只剩 1024 时还提交 1024 的
                // 逻辑批，等于每次 prefill 都把整个上下文重建一遍。
                val batch = (if (settings.batchSize > 0) settings.batchSize else 256)
                    .let { if (plan.recommendedBatch > 0) minOf(it, plan.recommendedBatch) else it }
                    .coerceAtMost(maxOf(64, plan.recommendedContext))
                val ubatch =
                    if (settings.ubatchSize > 0) {
                        settings.ubatchSize.coerceAtMost(batch)
                    } else if (plan.recommendedBatch > 0) {
                        // 低端设备的物理批再小一档，峰值内存跟着下来。
                        minOf(plan.recommendedBatch, batch)
                    } else 0
                val kvCode = when (kvName.lowercase()) {
                    "q8_0", "q8" -> 1
                    "q4_0", "q4" -> 2
                    else -> 0
                }
                val ok = LlamaBridge.loadModel(
                    handle = handle,
                    path = path,
                    nCtx = plan.recommendedContext,
                    nThreads = plan.recommendedThreads,
                    nBatch = batch,
                    nUbatch = ubatch,
                    // 关掉时传 0（明确关闭），开着时传 1（AUTO）—— AUTO 让 llama.cpp
                    // 自己判断这个模型的 head_dim 支不支持，比我们在这里猜更稳。
                    flash = if (settings.flashAttn) 1 else 0,
                    kv = kvCode,
                )
                if (!ok) error("llama.cpp 加载失败（${file.name}）")
                val ctx = LlamaBridge.nativeContextSize(handle)
                val accel = LlamaBridge.accelInfo(handle)
                Log.i(TAG, "加速配置：$accel")
                val encoderOnly = runCatching { LlamaBridge.nativeIsEncoderOnly(handle) }.getOrDefault(false)

                // 视觉投影：装了才能看图。失败**不算加载失败** —— 主模型照常能用，
                // 只是识图这一项保持关闭，界面会如实说明原因。
                var vision = false
                if (!encoderOnly && !mmprojPath.isNullOrBlank() && File(mmprojPath).isFile) {
                    vision = runCatching { LlamaBridge.nativeMmprojLoad(handle, mmprojPath) }
                        .getOrDefault(false)
                    if (vision) {
                        vision = runCatching { LlamaBridge.nativeVisionReady(handle) }.getOrDefault(false)
                    }
                    if (!vision) Log.w(TAG, "mmproj 装不上：$mmprojPath（识图保持关闭）")
                }

                // ---- 预热：把权重**顺序**读进页缓存 ----
                //
                // mmap 的代价是「第一次用到某一页才去磁盘拿」。于是首字延迟里藏着
                // 几百 MB 的随机缺页 —— 实测（模拟器）33 个 token 的提示词要 14.5 秒，
                // 而这 14.5 秒几乎全是 I/O，不是计算。
                //
                // 这里在**加载阶段**（界面本来就在转圈「正在加载」）用大缓冲顺序读一遍，
                // 把缺页提前变成顺序 I/O：同样的字节数，顺序读比 4 KB 缺页快得多，
                // 而且它发生在「加载中」，不是「正在思考」。
                //
                // 只在模型确实能装进内存时做：装不下时顺序读会把已经热起来的部分又挤掉，
                // 反而更慢（那种情况本来就该靠 mmap 流式加载）。
                if (plan.fitsInRam && !encoderOnly) {
                    val t0 = System.currentTimeMillis()
                    runCatching {
                        java.io.FileInputStream(file).use { ins ->
                            val buf = ByteArray(1 shl 20)
                            while (ins.read(buf) > 0) {
                                // 只为了把页读进来，内容不看。
                            }
                        }
                    }.onFailure { Log.w(TAG, "预热失败（不影响使用）：" + it.message) }
                    Log.i(TAG, "预热完成：" + (System.currentTimeMillis() - t0) + "ms")
                }

                _state.value = _state.value.copy(
                    ready = true,
                    loading = false,
                    modelPath = path,
                    contextSize = ctx,
                    threads = plan.recommendedThreads,
                    lastError = null,
                    encoderOnly = encoderOnly,
                    visionReady = vision,
                    accelInfo = accel,
                )
                Log.i(TAG, "loaded ${file.name} ctx=$ctx threads=${plan.recommendedThreads} encoderOnly=$encoderOnly vision=$vision")
            }.onFailure { t ->
                Log.e(TAG, "load failed", t)
                _state.value = _state.value.copy(
                    ready = false,
                    loading = false,
                    lastError = t.message ?: t.javaClass.simpleName,
                )
            }
        }
        result.map { }
    }

    suspend fun unload() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (handle != 0L) LlamaBridge.nativeUnload(handle)
        }
        _state.value = _state.value.copy(
            ready = false, modelName = null, modelPath = null, contextSize = 0, visionReady = false,
        )
    }

    fun release() {
        runCatching {
            if (handle != 0L) {
                LlamaBridge.nativeFree(handle)
                handle = 0L
            }
        }
    }

    fun cancel() {
        if (handle != 0L) runCatching { LlamaBridge.nativeCancel(handle) }
    }

    /**
     * 带图聊天：把一张图交给多模态模型，让它**真的看着图**回答。
     *
     * ## 为什么单独一个函数而不是给 [chat] 加参数
     *
     * 图像走的是完全不同的解码路径（mtmd 的 chunk 求值），而且要求提示词里带
     * 媒体标记、上下文必须从干净状态开始（图像嵌入占的位置数不等于 token 数）。
     * 把它塞进 [chat] 会让那条最热的文本路径到处是 if。
     *
     * ## 调用方必须保证的前置条件
     *
     * [state] 的 visionReady 为 true（主模型加载时成功装上了 mmproj）。
     * 否则这里会返回失败 —— 而不是把图丢掉当纯文字回答，那才是最难查的 bug。
     */
    suspend fun chatWithImage(
        messages: List<Pair<Role, String>>,
        imagePath: String,
        settings: GenerationSettings,
        onToken: (suspend (String) -> Unit)? = null,
        onFirstToken: (() -> Unit)? = null,
    ): Result<String> = mutex.withLock {
        if (!_state.value.visionReady) {
            return@withLock Result.failure(
                IllegalStateException("这个模型现在看不见图（缺少配套的视觉投影 mmproj）"),
            )
        }
        if (handle == 0L) return@withLock Result.failure(IllegalStateException("尚未加载模型"))

        // 图片解码 + 缩小。
        //
        // 为什么要缩：视觉编码器的算力是按像素走的，一张 4000x3000 的相机原图在
        // 手机上要几十秒；而模型看到的细节并不会因此多多少 —— 长边 512 已经够
        // 认字、认主体。缩放放在这里而不是调用方，是因为**每个入口都会忘**。
        // 文件在不在先看一眼 —— 报错要报在"图没了"，而不是等原生回一句
        // 看不懂的 tokenize 失败。
        if (!File(imagePath).isFile) {
            return@withLock Result.failure(IllegalStateException("图片文件不在了：$imagePath"))
        }

        // 解码和缩放**不在这里做**：交给原生侧的 mtmd 官方加载器。
        //
        // 之前这里自己 BitmapFactory 解码 + 缩到 512 再手工拼 RGB 交给 mtmd，
        // 结果是预处理器一个图像 token 都产不出来（tokenize 返回 2），
        // 换两个模型都一样 —— 说明错在我这层。模型的预处理有自己的尺寸/切片
        // 规则（还要处理 EXIF 方向），照着它的官方入口走才不会错位。
        //
        // 代价是一次文件读取要走 JNI 字符串，可以忽略。

        // 媒体标记插在**最后一条用户消息**的开头（那张图就是它带的）。
        val roles = ArrayList<String>(messages.size)
        val contents = ArrayList<String>(messages.size)
        var marked = false
        for (i in messages.indices.reversed()) {
            val (role, text) = messages[i]
            if (!marked && role == Role.USER) {
                roles.add(0, role.name.lowercase())
                contents.add(0, LlamaBridge.MEDIA_MARKER + "\n" + text)
                marked = true
            } else {
                roles.add(0, role.name.lowercase())
                contents.add(0, text)
            }
        }
        if (!marked) {
            return@withLock Result.failure(IllegalStateException("没有用户消息可以挂图片"))
        }

        val sampler = settings.toSamplerArray()
        val maxTokens = settings.maxTokens

        // 带图这一轮同样是"用户正在等"：抢到后台任务前面（后台是记忆提炼那类活儿）。
        foregroundInFlight.incrementAndGet()
        try {
            if (_state.value.generating && runningLane == Lane.BACKGROUND) {
                runCatching { LlamaBridge.nativeCancel(handle) }
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    runningLane = Lane.FOREGROUND
                    onFirstToken?.invoke()
                    val text = LlamaBridge.generateVision(
                        handle = handle,
                        roles = roles.toTypedArray(),
                        contents = contents.toTypedArray(),
                        imagePath = imagePath,
                        sampler = sampler,
                        maxTokens = maxTokens,
                        onToken = onToken,
                    )
                    if (text.startsWith("__ERROR__")) error(text.removePrefix("__ERROR__"))
                    text
                }
            }
            val perf = readPerf()
            _state.value = _state.value.copy(
                lastPromptTokens = perf.prompt,
                lastReusedTokens = perf.reused,
                lastPrefillMs = perf.prefillMs,
                lastGenMs = perf.genMs,
            )
            outcome
        } finally {
            foregroundInFlight.decrementAndGet()
        }
    }

    /**
     * Streams a completion. [onToken] is a *suspending* callback, so callers may
     * switch dispatchers inside it (the tokens themselves arrive from the native
     * decode thread and are serialised through [LlamaBridge.generate]).
     *
     * The companion hook deliberately runs AFTER the lock is released: extraction
     * re-enters [chat], so running it inside `withLock` would deadlock the engine
     * against itself.
     */
    /**
     * 谁在等这次生成。
     *
     * 手机上只有一个模型、一把锁。FOREGROUND 是用户发了消息、正盯着气泡等回答的那一轮；
     * BACKGROUND 是记忆提炼、关系总结这类「没人等」的附带工作。
     *
     * 这条区分是两个真问题的解药，两个都出现在真机反馈里：
     *
     *  1. **后台活儿排在用户前面** —— 回答完之后程序偷偷跑一次提炼，用户紧接着发下一句，
     *     那一句就卡在提炼后面（模型只能跑一路）。
     *  2. **后台活儿不许插队** —— 反过来，后台开工前先看有没有前台在排队，有就直接放弃这一次。
     *
     * 后台任务因此可能一次都没有真正跑起来（一直有人在聊）。这是刻意的：
     * 丢一条记忆的代价，远小于「发出去半天没反应」。
     */
    enum class Lane { FOREGROUND, BACKGROUND }

    /** 正等着/正在跑的**前台**请求数。后台任务开工前先看它。 */
    private val foregroundInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** 此刻在跑的是哪条车道；前台请求到达时据此决定要不要把后台那轮掐掉。 */
    @Volatile
    private var runningLane: Lane = Lane.FOREGROUND

    suspend fun chat(
        messages: List<Pair<Role, String>>,
        settings: GenerationSettings,
        onToken: (suspend (String) -> Unit)? = null,
        onFirstToken: (() -> Unit)? = null,
        /** Set false for internal calls (e.g. memory extraction) to avoid recursion. */
        notifyTurnComplete: Boolean = true,
        /** 默认前台：会抢到后台任务前面。见 [Lane]。 */
        lane: Lane = Lane.FOREGROUND,
    ): Result<String> {
        if (lane == Lane.FOREGROUND) foregroundInFlight.incrementAndGet()
        try {
            // ---- 后台：有人在等，这一次就别做了 ----
            if (lane == Lane.BACKGROUND &&
                (foregroundInFlight.get() > 0 || _state.value.generating)
            ) {
                Log.i(TAG, "后台生成让位（前台有请求或引擎正忙）")
                return Result.success("")
            }
            // ---- 前台：把还在跑的后台那轮掐掉 ----
            //
            // 不掐的话，用户这一句要等后台把 96 个 token 吐完 —— 那正是
            // 「回答完想接着说一句，半天没反应」。被掐断的后台任务拿到的是一段半截回答，
            // 而调用方本来就把「空 / 半截」当作「这次没学到东西」。
            if (lane == Lane.FOREGROUND && _state.value.generating && runningLane == Lane.BACKGROUND) {
                Log.i(TAG, "前台请求到达，打断后台生成")
                runCatching { LlamaBridge.nativeCancel(handle) }
            }

            val outcome = mutex.withLock {
                if (lane == Lane.BACKGROUND && foregroundInFlight.get() > 0) {
                    Result.success("")
                } else {
                    runningLane = lane
                    generateLocked(messages, settings, onToken, onFirstToken)
                }
            }

            if (notifyTurnComplete && outcome.isSuccess) {
                val reply = outcome.getOrNull().orEmpty()
                val lastUser = messages.lastOrNull { it.first == Role.USER }?.second.orEmpty()
                val hook = onTurnComplete
                if (hook != null && reply.isNotBlank() && lastUser.isNotBlank()) {
                    // 钩子本身必须**立刻返回**（见 LocalMindApp）：它里面是记忆抽取，
                    // 不值得让用户等它。这里留一个上限只为兜底。
                    runCatching {
                        kotlinx.coroutines.withTimeoutOrNull(20_000) { hook(lastUser, reply) }
                    }.onFailure { Log.w(TAG, "companion turn hook failed", it) }
                }
            }
            return outcome
        } finally {
            if (lane == Lane.FOREGROUND) foregroundInFlight.decrementAndGet()
        }
    }

    private suspend fun generateLocked(
        messages: List<Pair<Role, String>>,
        settings: GenerationSettings,
        onToken: (suspend (String) -> Unit)?,
        onFirstToken: (() -> Unit)?,
    ): Result<String> {
        if (handle == 0L || !_state.value.ready) {
            return Result.failure(IllegalStateException("尚未加载模型"))
        }

        _state.value = _state.value.copy(generating = true, tokensGenerated = 0, lastError = null)

        val roles = messages.map { it.first.name.lowercase() }.toTypedArray()
        val contents = messages.map { it.second }.toTypedArray()

        var tokenCount = 0
        val started = System.currentTimeMillis()
        var firstTokenAt = 0L
        var lastLivePush = 0L
        val builder = StringBuilder()

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val out = LlamaBridge.generate(
                    handle, roles, contents, settings.toSamplerArray(), settings.maxTokens,
                ) { piece ->
                    if (tokenCount == 0) {
                        firstTokenAt = System.currentTimeMillis()
                        onFirstToken?.invoke()
                    }
                    tokenCount++
                    builder.append(piece)
                    onToken?.invoke(piece)
                    // 状态页要"实时"：每 200ms 推一次当前 token 数和瞬时速度。
                    // 每个 token 都推会让整棵 Compose 树每几毫秒重组一次。
                    val now = System.currentTimeMillis()
                    if (now - lastLivePush >= 200L) {
                        lastLivePush = now
                        val secs = (now - started) / 1000.0
                        _state.value = _state.value.copy(
                            generating = true,
                            tokensGenerated = tokenCount,
                            lastTokensPerSecond = if (secs > 0.01) tokenCount / secs else 0.0,
                        )
                    }
                }
                if (out.startsWith(LlamaBridge.ERROR_PREFIX)) {
                    error(out.removePrefix(LlamaBridge.ERROR_PREFIX))
                }
                out
            }
        }

        val elapsed = System.currentTimeMillis() - started
        // 原生侧的账：提示词多少 token、其中多少直接命中了 KV、prefill / 生成各花了多久。
        // 这四个数字就是「这轮到底快在哪里」的答案，诊断页和日志都用它。
        val perf = readPerf()
        // **token 数要用原生报的那个。**
        //
        // tokenCount 数的是「回调吐出来的片段数」，而一个片段常常只有一个字符
        // （中文尤其明显）—— 拿它当 token 数会把 tok/s 高估两三倍，
        // 用户看到的「速度」于是和体感对不上。原生那边数的是真正的 token。
        val tokens = if (perf.gen > 0) perf.gen else tokenCount
        val tps = if (elapsed > 0) tokens * 1000.0 / elapsed else 0.0
        _state.value = _state.value.copy(
            generating = false,
            tokensGenerated = tokens,
            lastTokensPerSecond = tps,
            lastPromptTokens = perf.prompt,
            lastReusedTokens = perf.reused,
            lastPrefillMs = perf.prefillMs,
            lastGenMs = perf.genMs,
            lastFirstTokenMs = if (firstTokenAt > 0) firstTokenAt - started else 0L,
        )
        Log.i(
            TAG,
            "生成完成：prompt=" + perf.prompt + " token，复用=" + perf.reused +
                "（" + _state.value.cacheHitPercent + "%），prefill=" + perf.prefillMs +
                "ms，首 token=" + (if (firstTokenAt > 0) firstTokenAt - started else -1) +
                "ms，生成 " + tokens + " token / " + perf.genMs + "ms（" +
                String.format("%.1f", tps) + " t/s）",
        )

        val finalText = result.getOrNull() ?: builder.toString()

        result.onFailure {
            Log.e(TAG, "generate failed", it)
            _state.value = _state.value.copy(lastError = it.message ?: it.javaClass.simpleName)
        }

        return result.map { text -> if (text.isNotEmpty()) text else finalText }
    }

    /**
     * 读原生侧的性能账：(提示词 token 数, 复用 token 数, (prefill ms, gen ms))。
     *
     * 解析失败就返回全 0 —— 这只是一份读数，绝不能因为它让一次生成失败。
     */
    /** 原生侧回读的一轮推理账（见 llama_jni.cpp 的 perf_stats）。 */
    private data class Perf(
        val prompt: Int = 0,
        val reused: Int = 0,
        val gen: Int = 0,
        val prefillMs: Long = 0,
        val genMs: Long = 0,
    )

    private fun readPerf(): Perf {
        if (handle == 0L) return Perf()
        return runCatching {
            val obj = Json.parseToJsonElement(LlamaBridge.lastPerfJson(handle)).jsonObject
            fun int(key: String): Int =
                obj[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            fun long(key: String): Long =
                obj[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            Perf(
                prompt = int("prompt"),
                reused = int("reused"),
                gen = int("gen"),
                prefillMs = long("prefill_ms"),
                genMs = long("gen_ms"),
            )
        }.getOrDefault(Perf())
    }

    /** Exact token count for a piece of text, or -1 when no model is loaded. */
    fun countTokens(text: String): Int =
        if (handle == 0L) -1 else runCatching { LlamaBridge.nativeCountTokens(handle, text) }.getOrDefault(-1)

    /** Diagnostic payload describing how `text` tokenises. */
    fun tokenRoundTrip(text: String): String =
        if (handle == 0L) "{\"error\":\"no model\"}"
        else runCatching { LlamaBridge.nativeTokenRoundTrip(handle, text) }.getOrDefault("{\"error\":\"native call failed\"}")

    fun formatChat(messages: List<Pair<Role, String>>, addAssistant: Boolean = true): String {
        if (handle == 0L) return messages.joinToString("\n") { "${it.first.name}: ${it.second}" }
        return runCatching {
            LlamaBridge.nativeFormatChat(
                handle,
                messages.map { it.first.name.lowercase() }.toTypedArray(),
                messages.map { it.second }.toTypedArray(),
                addAssistant,
            )
        }.getOrDefault("")
    }

    fun modelInfoJson(): String = if (handle == 0L) "{}" else runCatching {
        LlamaBridge.nativeModelInfo(handle)
    }.getOrDefault("{}")
}

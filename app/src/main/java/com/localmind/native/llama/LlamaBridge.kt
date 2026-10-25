package com.localmind.native.llama

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * Thin JNI surface over the vendored llama.cpp build.
 *
 * Every call that touches model state must be funnelled through
 * [com.localmind.service.LlamaEngine], which serialises access with a Mutex —
 * a llama_context is not safe for concurrent decode of multiple sequences.
 */
object LlamaBridge {

    init {
        System.loadLibrary("localmind")
    }

    /** Opaque native engine handle. 0 means "not initialised". */
    external fun nativeInit(): Long
    external fun nativeFree(handle: Long)
    external fun nativeLoadModel(handle: Long, path: String, nCtx: Int, nThreads: Int, nBatch: Int): Boolean

    // ------------------------------------------------------------------ 加速
    //
    // 加载时的三个加速开关。**单独一个 JNI 函数**，不是给 nativeLoadModel 加参数：
    // JNI 的短名解析不含参数表，改参数列表会让「新代码调到旧 .so」变成按错误的
    // 参数表读寄存器 —— 那不是报错，是崩溃。两个函数各自独立，老调用点永远安全。
    //
    // @param nUbatch 0 = 自动（min(nBatch, 256)）
    // @param flash   0=off 1=auto 2=on
    // @param kv      0=F16 1=Q8_0 2=Q4_0
    private external fun nativeLoadModelEx(
        handle: Long, path: String, nCtx: Int, nThreads: Int, nBatch: Int,
        nUbatch: Int, flash: Int, kv: Int,
    ): Boolean

    /** 真正生效的加速配置。降级过之后和用户选的不一样，诊断页要显示后者。 */
    private external fun nativeAccelInfo(handle: Long): String

    /**
     * 带加速参数的加载。
     *
     * 原生库**没有** nativeLoadModelEx 时（旧包升级上来的进程内换库）退回老入口，
     * 保证「加速项拿不到」不会变成「模型加载不了」。
     */
    fun loadModel(
        handle: Long,
        path: String,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        nUbatch: Int = 0,
        flash: Int = 1,
        kv: Int = 0,
    ): Boolean = runCatching {
        nativeLoadModelEx(handle, path, nCtx, nThreads, nBatch, nUbatch, flash, kv)
    }.getOrElse {
        android.util.Log.w("LlamaBridge", "nativeLoadModelEx 不可用，退回基础加载：${it.message}")
        nativeLoadModel(handle, path, nCtx, nThreads, nBatch)
    }

    /** 加速配置的可读描述；拿不到就返回空串。 */
    fun accelInfo(handle: Long): String =
        runCatching { nativeAccelInfo(handle) }.getOrDefault("")

    /**
     * 上一轮推理的性能数字（JSON）：prompt / reused / prefill_tokens / gen /
     * prefill_ms / gen_ms。
     *
     * 「前缀复用到底有没有生效」只能靠这个数字回答 —— 提示词布局改得对不对，
     * 眼睛看不出来，只有 reused 从 0 变成几千才是真的。（见 docs/SPEED.md）
     */
    private external fun nativeLastPerf(handle: Long): String

    /** 性能数字；旧 .so 上没有这个符号时返回空串，调用方按"未知"处理。 */
    fun lastPerfJson(handle: Long): String =
        runCatching { nativeLastPerf(handle) }.getOrDefault("")
    external fun nativeUnload(handle: Long)
    external fun nativeIsLoaded(handle: Long): Boolean

    /** Encoder-only files (BGE/GTE embedding models) cannot answer chat requests. */
    external fun nativeIsEncoderOnly(handle: Long): Boolean

    external fun nativeCancel(handle: Long)

    // ------------------------------------------------------------------ 识图
    //
    // 视觉投影（mmproj）走的是 llama.cpp 的 mtmd。三个函数：装、问能不能用、
    // 带图生成。**没有 mmproj 时 visionReady 恒为 false** —— 界面据此如实说明，
    // 而不是让用户发了图却得到一句「没看见图片」。

    /** 装上配套的 mmproj 文件。false = 装不上（文件不对 / 和主模型不配套）。 */
    external fun nativeMmprojLoad(handle: Long, path: String): Boolean

    /** 现在能不能真的看图。 */
    external fun nativeVisionReady(handle: Long): Boolean

    private external fun nativeVisionGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        imagePath: String,
        sampler: FloatArray,
        maxTokens: Int,
        onToken: TokenSink?,
    ): String

    /**
     * 带图生成。和文本版 [generate] 一样把生产者放另一个线程，区别只是多带一张图。
     *
     * [imagePath] 是磁盘上的图片文件（jpg/png/webp 都行）—— **由原生侧自己解码**，
     * 因为它要用 mtmd 官方的加载器，才能和模型的预处理（尺寸/切片/EXIF）完全对上。
     * Kotlin 这边只负责给路径。
     *
     * 调用方必须在**带图那条消息的内容里**插入媒体标记 [MEDIA_MARKER]。
     */
    suspend fun generateVision(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        imagePath: String,
        sampler: FloatArray,
        maxTokens: Int,
        onToken: (suspend (String) -> Unit)?,
    ): String {
        if (onToken == null) {
            return withContext(Dispatchers.IO) {
                nativeVisionGenerate(handle, roles, contents, imagePath, sampler, maxTokens, null)
            }
        }
        val channel = kotlinx.coroutines.channels.Channel<String>(
            capacity = kotlinx.coroutines.channels.Channel.UNLIMITED
        )
        val producer = CoroutineScope(Dispatchers.IO).async {
            try {
                nativeVisionGenerate(
                    handle, roles, contents, imagePath, sampler, maxTokens, ChannelSink(channel),
                )
            } finally {
                channel.close()
            }
        }
        for (piece in channel) onToken(piece)
        return producer.await()
    }

    /**
     * mtmd 的媒体标记，必须**和原生侧 mtmd_default_marker() 一致**（同一个字符串）。
     * 写死在这里是因为多传一个 JNI 函数只为一个常量不值当，而这是 mtmd 的公开契约。
     */
    const val MEDIA_MARKER = "<__media__>"
    external fun nativeContextSize(handle: Long): Int

    /** GGUF/llama system info as a JSON string. */
    external fun nativeModelInfo(handle: Long): String
    external fun nativeSystemInfo(): String

    external fun nativeFormatChat(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String

    /**
     * 逐 token 回调。**必须是一个具名接口，不能直接传 lambda。**
     *
     * ## 为什么（release 包实测踩到的坑）
     *
     * 原先把 `(String) -> Unit` 直接交给 JNI，原生侧用
     * `GetMethodID(cls, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;")` 反射调用。
     * debug 包一切正常，**release 包一个 token 都收不到** —— R8 会为传给原生的 lambda
     * 合成一个新类，而这个新类是在 keep 规则**之后**生成的，规则管不到它：
     *
     *     LlamaBridge$generate$producer$1 -> LlamaBridge$generate$producer$1   （保留）
     *     ...$producer$1$$ExternalSyntheticLambda0 -> com.localmind.native.llama.a （被合成/混淆）
     *
     * 于是每次回调都 `NoSuchMethodError: no non-static method "...a;.invoke(...)"`，
     * 异常被原生侧吞掉，token 全部丢弃 —— 界面表现就是**"流式没用"**：
     * 整段回答等生成完才一次性出现。
     *
     * 换成具名接口 + 具名实现类之后，两者都是**输入里本来就存在**的类，落在
     * `com.localmind.native.**` 的 keep 规则里，方法名和签名不会被改。
     */
    interface TokenSink {
        fun onToken(piece: String)
    }

    /** [TokenSink] 的实现。写成具名类而不是 lambda，理由同上。 */
    private class ChannelSink(
        private val channel: kotlinx.coroutines.channels.Channel<String>,
    ) : TokenSink {
        override fun onToken(piece: String) {
            channel.trySend(piece)
        }
    }

    /**
     * @param sampler 10 floats: temp, topP, topK, minP, repeatPenalty, repeatLastN,
     *                presence, frequency, seed, mirostat
     * @param onToken invoked on the native decode thread. Kotlin callers use the
     *                [generate] overload below, which serialises these callbacks
     *                onto a single consumer. Must be a [TokenSink], not a lambda.
     */
    private external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        sampler: FloatArray,
        maxTokens: Int,
        onToken: TokenSink?,
    ): String

    /**
     * Streams tokens through a `Channel`: the native decode loop produces on its own
     * thread, the caller consumes from a coroutine with `for (piece in channel)`.
     * That split is what lets `LlamaEngine` expose a *suspend* callback **and** have
     * tokens actually arrive while generation is still running.
     */
    suspend fun generate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        sampler: FloatArray,
        maxTokens: Int,
        onToken: (suspend (String) -> Unit)?,
    ): String {
        if (onToken == null) {
            return withContext(Dispatchers.IO) {
                nativeGenerate(handle, roles, contents, sampler, maxTokens, null)
            }
        }
        val channel = kotlinx.coroutines.channels.Channel<String>(
            capacity = kotlinx.coroutines.channels.Channel.UNLIMITED
        )

        // nativeGenerate 是**阻塞**调用：它在返回之前会把整段回答都生成完。
        //
        // 所以不能在同一线程里"先调用、再遍历 channel"—— 那样所有 token 只是
        // 先塞进队列排队，要等生成**全部结束**才开始被消费。界面表现是"长时间
        // 空白，然后一整段同时出现"，也就是流式其实没生效；首 token 延迟等于
        // 整段回答的时长。
        //
        // 把生产者放到另一个线程上，消费方在协程里并发地取，token 才是真的边
        // 生成边到界面。
        val producer = CoroutineScope(Dispatchers.IO).async {
            try {
                nativeGenerate(handle, roles, contents, sampler, maxTokens, ChannelSink(channel))
            } finally {
                channel.close()
            }
        }

        try {
            for (piece in channel) {
                onToken(piece)
            }
        } catch (t: Throwable) {
            // 消费方被取消（用户点了停止）时别把原生生成留在后台空转。
            producer.cancel()
            throw t
        }
        return producer.await()
    }

    external fun nativeCountTokens(handle: Long, text: String): Int

    /** Diagnostic: JSON with token ids + detokenised text, to catch encoding bugs. */
    external fun nativeTokenRoundTrip(handle: Long, text: String): String

    const val ERROR_PREFIX = "__ERROR__"

    fun systemInfoSafe(): String = runCatching { nativeSystemInfo() }.getOrDefault("native library unavailable")
}

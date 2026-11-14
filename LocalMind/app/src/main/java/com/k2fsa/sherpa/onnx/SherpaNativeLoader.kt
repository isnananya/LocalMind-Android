package com.k2fsa.sherpa.onnx

import android.util.Log
import java.io.File

/**
 * 加载 sherpa-onnx 的 JNI 库，**允许失败**，并且支持从**下载目录**加载。
 *
 * ## 为什么要支持目录加载（而不是只用 `System.loadLibrary`）
 *
 * 这套原生库 **36 MB/ABI**，其中 `libonnxruntime.so` 一家就占 21–25 MB。
 * 打进 APK 等于让每个用户先下 36 MB 才可能用到语音 —— 而绝大多数人一次都
 * 不会开神经音色。所以现在**不进包**，用户真的要用时才从官方 Release 下载
 * （见 `SherpaLibraryInstaller`）。
 *
 * 代价是不能再靠 `System.loadLibrary` 去 APK 里找：得先 `System.load` 落盘的
 * `libonnxruntime.so`，再 load 依赖它的那几个 —— `System.load` 不做递归解析
 * `DT_NEEDED`，顺序错了会 `dlopen failed: library "libonnxruntime.so" not found`。
 *
 * ## 为什么不能直接写 `System.loadLibrary`（历史教训）
 *
 * 上游写法是 `companion object { init { System.loadLibrary(...) } }`。
 * 库一旦缺失就是 `UnsatisfiedLinkError` —— 它是 **Error 不是 Exception**，
 * `runCatching` 默认不接，于是点一下语音页就崩。
 * 神经语音是**可选能力**，缺了就该安静退回系统 TTS。
 */
object SherpaNativeLoader {

    private const val TAG = "SherpaOnnx"

    /**
     * 加载顺序**不能改**：`-jni` 依赖 `-c-api`，`-c-api` 依赖 `onnxruntime`。
     */
    private val LOAD_ORDER = listOf(
        "libonnxruntime.so",
        "libsherpa-onnx-c-api.so",
        "libsherpa-onnx-cxx-api.so",
        "libsherpa-onnx-jni.so",
    )

    /** 真正被 `loadLibrary` 的那个；其它三个是它的依赖。 */
    private const val JNI_LIBRARY = "sherpa-onnx-jni"

    private val lock = Any()

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var bundledChecked: Boolean = false

    @Volatile
    private var bundledPresent: Boolean = false

    /** 已经从某个路径成功加载过。 */
    val isLoaded: Boolean get() = loaded

    /** APK 里自带这套库（旧版本 / 本地开发）。只探测一次。 */
    val isBundled: Boolean
        get() {
            if (loaded) return bundledPresent
            if (!bundledChecked) {
                synchronized(lock) {
                    if (!bundledChecked) {
                        bundledPresent = tryLoadLibrary()
                        if (bundledPresent) loaded = true
                        bundledChecked = true
                    }
                }
            }
            return bundledPresent
        }

    /** 从目录加载。目录里必须有 [LOAD_ORDER] 全套。 */
    fun loadFrom(dir: File?): Boolean {
        if (loaded) return true
        if (!isLoadableFrom(dir)) return false
        synchronized(lock) {
            if (loaded) return true
            return try {
                LOAD_ORDER.forEach { System.load(File(dir, it).absolutePath) }
                loaded = true
                Log.i(TAG, "从目录加载原生库成功：${dir!!.absolutePath}")
                true
            } catch (t: Throwable) {
                // UnsatisfiedLinkError 也在这里 —— 它是 Error，必须显式接住。
                Log.w(TAG, "从目录加载失败：${t.message}", t)
                false
            }
        }
    }

    /**
     * 当前可用的库在哪：已加载的目录 > APK。都没有就是 null。
     * 调用方用它决定"要不要提示用户先去下载"。
     */
    fun usable(): Boolean = loaded || isBundled

    /** 目录里是否有一套完整的 `.so`（不代表能加载成功，只代表文件齐）。 */
    fun isLoadableFrom(dir: File?): Boolean =
        dir != null && dir.isDirectory && LOAD_ORDER.all { File(dir, it).isFile }

    private fun tryLoadLibrary(): Boolean = try {
        System.loadLibrary(JNI_LIBRARY)
        true
    } catch (t: Throwable) {
        Log.i(TAG, "APK 里没有 $JNI_LIBRARY（正常：神经语音按需下载）")
        false
    }

    /** 一个 ABI 需要下载的大致字节数，给 UI 显示用。 */
    const val APPROX_BYTES_PER_ABI: Long = 36L * 1024 * 1024
}

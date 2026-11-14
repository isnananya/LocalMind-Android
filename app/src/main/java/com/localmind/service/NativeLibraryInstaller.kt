package com.localmind.service

import android.util.Log
import com.localmind.data.Storage
import java.io.File

private const val TAG = "NativeLibInstaller"

/**
 * 生图后端的**运行时安装器** —— 也就是"生图后端插件"的落地实现。
 *
 * ## 为什么后端要下载而不是打进包
 *
 * 它把 `liblocalmind.so` 从 3.5 MB 撑到 41.66 MB，而这个功能只有愿意再下
 * 1.5–6 GB checkpoint 的人用得上。绝大多数用户为它白白付 38 MB，
 * 而 38 MB 换来的东西他们一次都不会点。
 *
 * ## 它和语音引擎是同一套路
 *
 * 和 [SherpaLibraryInstaller] 完全对称：下载 → 校验 → 解压到
 * `filesDir/native/<abi>/` → `System.load`。两个后端都用"运行时按需取"，
 * 这样 APK 只含**人人都要用**的东西。
 *
 * ## 为什么一定是独立的 .so（而不能链进主库）
 *
 * sd.cpp 和 llama.cpp 各带一份 ggml，两份的 `GGML_MAX_NAME` 不同（160 vs 64）。
 * `char name[GGML_MAX_NAME]` 是 `struct ggml_tensor` 的真实字段，所以两边
 * `sizeof` 差 96 字节，`extra` / `view_src` 落在不同偏移上 —— **不报链接错误**，
 * 只是静默的内存错乱。
 *
 * 分成两个 .so 之后，bionic 解析符号时先看库自己，两个 ggml 各自留在自己的库里。
 * 这不是"绕过去"，这是结构上唯一正确的做法。
 */
object NativeLibraryInstaller {

    /** 下载包里的文件名。 */
    const val LIB_NAME = "liblocalmind-sd.so"

    /** 解压后的完整路径。注意**不是** APK 的 nativeLibraryDir —— 那是只读的。 */
    fun installedFile(storage: Storage, abi: String = currentAbi()): File =
        File(File(storage.nativeDir, abi), LIB_NAME)

    /** 这个后端装了没有（文件在就算装了，**不尝试加载** —— 加载是几百 ms 的事）。 */
    fun isInstalled(storage: Storage, abi: String = currentAbi()): Boolean {
        val f = installedFile(storage, abi)
        return f.isFile && f.length() > MIN_BYTES
    }

    fun installedBytes(storage: Storage, abi: String = currentAbi()): Long =
        installedFile(storage, abi).let { if (it.isFile) it.length() else 0L }

    /**
     * 当前 ABI。
     *
     * 只用 `Build.SUPPORTED_ABIS.first()`：一个设备可能同时支持 arm64 和 armeabi，
     * 我们只要**首选**那个 —— 下载两份 36 MB 没有意义，而加载非首选 ABI 的库
     * 迟早会因为符号或指令集不一致出怪问题。
     */
    fun currentAbi(): String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    @Volatile
    private var loaded = false

    /** 尝试加载。**失败不抛异常** —— 调用方拿到 false 就退回程序化渲染器。 */
    fun ensureLoaded(storage: Storage): Boolean {
        if (loaded) return true
        // 后端随 APK 打包时（ImageBridge 启动时已经 loadLibrary 过），**绝不能**
        // 再从插件目录 load 一份。理由见 dropBundledDuplicate：linker 按路径判重。
        if (com.localmind.native.image.ImageBridge.backendBundled) {
            loaded = true
            return true
        }
        val f = installedFile(storage)
        if (!f.isFile) return false
        return load(f)
    }

    /**
     * 生图后端已经随 APK 打包时，删掉用户存储里那份旧副本。
     *
     * 这两份文件的**内容完全一样**，差别只有路径：APK 里的在 `nativeLibraryDir`，
     * 插件装下来的在 `filesDir/native/<abi>/`。而 Android 的 linker 是**按路径**
     * 判断「这个库加载过没有」，改名或换目录就会被当成另一个库重新映射 ——
     * 于是进程里出现两套 36 MB 代码段、两套静态全局（`sd_jni.cpp` 的 `g_ctx`），
     * 最坏情况同一个 1.6 GB 的 checkpoint 被加载两遍，直接被 LMK 杀掉。
     *
     * 从老版本升级上来的用户手上一定有这份文件，所以启动时顺手删掉，
     * 顺带把 36 MB 存储还回去。
     */
    fun dropBundledDuplicate(storage: Storage): Boolean {
        if (!com.localmind.native.image.ImageBridge.backendBundled) return false
        val f = installedFile(storage)
        if (!f.isFile) return false
        val ok = runCatching { f.delete() }.getOrDefault(false)
        if (ok) Log.i(TAG, "已删除与 APK 内置重复的生图后端：" + f.absolutePath)
        return ok
    }

    /**
     * 加载一个具体的 .so。
     *
     * 顺序有讲究：`libc++_shared.so` 必须**先**加载。下载来的 sd.so 的
     * DT_NEEDED 里有 `libc++_shared.so`，而 Android 只在**已加载**的库里找
     * 依赖（除非它在 APK 的 nativeLibraryDir 里）。APK 里确实有一份
     * （Compose 的路径库带的），所以通常能找到；但显式先加载更稳，
     * 而且能给出更清楚的错误。
     */
    fun load(file: File): Boolean = synchronized(this) {
        if (loaded) return true
        return try {
            // APK 自带的那份 C++ 运行库先加载，保证 sd.so 的依赖能解析到。
            runCatching { System.loadLibrary("c++_shared") }
            System.load(file.absolutePath)
            loaded = true
            Log.i(TAG, "生图后端已加载：${file.absolutePath}（${file.length() / 1024 / 1024} MB）")
            true
        } catch (t: Throwable) {
            // UnsatisfiedLinkError 是 Error 的子类，runCatching 抓不到 Throwable
            // 之外的东西，所以这里显式接 Throwable。
            Log.e(TAG, "生图后端加载失败：${file.absolutePath}", t)
            false
        }
    }

    fun markUnloaded() {
        loaded = false
    }

    /**
     * 把**已经落盘**的后端真正装进进程，返回这次启用了什么（给界面/日志用）。
     *
     * 为什么必须有这个函数：插件安装只负责把 `.so` 放到 `filesDir/native/<abi>/`，
     * 而"放对位置"和"能用"是两件事 —— 不进 `System.load`，
     * [com.localmind.native.image.ImageBridge.hasDiffusionBackend] 永远是 false，
     * 界面就会一直说"检测不到"。安装后要调一次，**应用启动时也要调一次**
     * （上次装完就退出的用户下次进来应该直接可用）。
     */
    fun activateInstalled(storage: Storage): List<String> {
        val out = mutableListOf<String>()
        // 内置副本已经够用了，先把旧的重复文件清掉再判状态。
        dropBundledDuplicate(storage)
        if (isInstalled(storage)) {
            out += if (ensureLoaded(storage)) "生图后端已启用" else "生图后端加载失败"
        } else if (com.localmind.native.image.ImageBridge.backendBundled) {
            out += "生图后端已启用（内置）"
        }
        // 语音引擎同一套路：它落的是另外四个 .so，由 SherpaNativeLoader 认。
        val dir = File(storage.nativeDir, currentAbi())
        if (!com.k2fsa.sherpa.onnx.SherpaNativeLoader.isLoaded &&
            com.k2fsa.sherpa.onnx.SherpaNativeLoader.isLoadableFrom(dir)
        ) {
            out += if (com.k2fsa.sherpa.onnx.SherpaNativeLoader.loadFrom(dir)) {
                "语音引擎已启用"
            } else {
                "语音引擎加载失败"
            }
        }
        return out
    }

    /** 文件小到这个程度肯定是坏的/占位的。36 MB 的正常产物，给 8 MB 的余量。 */
    private const val MIN_BYTES = 8L * 1024 * 1024

    /**
     * 校验下载来的 .so 是不是**这个架构**的 ELF。
     *
     * 为什么要查：用户在设置里填的下载地址可能是另一个 ABI 的包，
     * 或者干脆是个人放错的文件。装上去之后 `System.load` 只会说
     * "is 64-bit instead of 32-bit" 之类的话，而那时已经白下了 36 MB。
     *
     * @return null 表示没问题，否则是一句能直接给用户看的说明。
     */
    fun validate(file: File, abi: String = currentAbi()): String? {
        if (!file.isFile) return "文件不存在"
        if (file.length() < MIN_BYTES) {
            return "文件太小（${file.length() / 1024} KB），可能没下完或是错的包"
        }
        val head = ByteArray(20)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (n < 20) return "读不出文件头"
        // ELF 魔数
        if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
            head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
        ) {
            return "不是 ELF 文件（是不是下到了 HTML 错误页？）"
        }
        // EI_CLASS：1 = 32 位，2 = 64 位
        val is64 = head[4].toInt() == 2
        val want64 = abi.contains("64")
        if (is64 != want64) {
            return "架构不符：这个包是 ${if (is64) "64" else "32"} 位的，本机需要 ${if (want64) "64" else "32"} 位"
        }
        // e_machine：0x3E = x86-64，0xB7 = AArch64，0x28 = ARM，0x03 = i386
        val machine = (head[19].toInt() and 0xFF shl 8) or (head[18].toInt() and 0xFF)
        val wantMachine = when {
            abi.startsWith("arm64") -> 0xB7
            abi.startsWith("x86_64") -> 0x3E
            abi.startsWith("armeabi") -> 0x28
            abi.startsWith("x86") -> 0x03
            else -> -1
        }
        if (wantMachine != -1 && machine != wantMachine) {
            return "架构不符：本机是 $abi，这个包是别的架构"
        }
        return null
    }
}

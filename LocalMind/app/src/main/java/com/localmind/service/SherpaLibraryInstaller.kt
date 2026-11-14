package com.localmind.service

import android.os.Build
import android.util.Log
import com.k2fsa.sherpa.onnx.SherpaNativeLoader
import com.localmind.data.Storage
import java.io.File

private const val TAG = "SherpaLibs"

/**
 * 神经语音的原生库**按需下载**，不进 APK。
 *
 * ## 为什么
 *
 * `libonnxruntime.so` + 三个 sherpa `.so` 每个 ABI 共 36 MB（onnxruntime 一家
 * 21–25 MB）。打进 APK 就是让**所有**用户先付这 36 MB，而绝大多数人一次都不会
 * 打开神经音色。移到首次使用时下载，包体积直接少 36 MB/ABI。
 *
 * ## 从哪来
 *
 * 用官方的 Android tarball —— 和 `:app:fetchSherpaOnnx`（开发时预拉）同一个来源：
 *
 * ```
 * https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2
 * ```
 *
 * 里面是 `jniLibs/<abi>/{libonnxruntime,libsherpa-onnx-jni,-c-api,-cxx-api}.so`。
 * 这条路本机实测过：用 `gh-proxy.com` 前缀稳定可达，直连 GitHub 会随机超时。
 *
 * ## 为什么自己解压而不是发布端拆好
 *
 * 我们没有发布端。官方只发这一个 tarball，所以"下载 → 解开 → 丢掉多余文件"
 * 只能在这边做。解压用系统 `tar`（Android 自带 toybox），和音色模型同一套做法 ——
 * JDK 里没有 bzip2 解码器，而手写一个 bzip2 是纯负债。
 *
 * ## 落盘位置
 *
 * `filesDir/native/<abi>/`。按 ABI 分目录而不是平铺：`.so` 是 ABI 绑定的，
 * 平铺会让"换设备/重装后目录里混着两种 ABI"变成可能，而 `System.load` 只会
 * 加载第一个找到的，错了就是 SIGSEGV 而不是可读的错误。
 */
class SherpaLibraryInstaller(private val storage: Storage) {

    companion object {
        const val TARBALL_NAME = "sherpa-onnx-v1.13.8-android.tar.bz2"
        const val VERSION = "1.13.8"

        private val URLS = listOf(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/$TARBALL_NAME",
        )

        /** 需要落盘的文件（也是 `System.load` 的顺序）。 */
        val LIBRARIES = listOf(
            "libonnxruntime.so",
            "libsherpa-onnx-c-api.so",
            "libsherpa-onnx-cxx-api.so",
            "libsherpa-onnx-jni.so",
        )
    }

    /**
     * 当前设备的首选 ABI。
     *
     * 用 `SUPPORTED_ABIS[0]` 而不是 `Build.CPU_ABI`（已废弃）。首选那个就是
     * 系统会从 APK 里挑的那个，两边一致才不会出现"APK 里是 arm64、下载的是 x86"。
     */
    val abi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    val root: File get() = File(storage.appContext().filesDir, "native")

    val dir: File get() = File(root, abi)

    val archive: File get() = File(root, TARBALL_NAME)

    /**
     * 「这个 tarball 是下完的」标记。
     *
     * 起因是一个能把安装流程**永久卡死**的坑：原来只按大小判断
     * （`archive.length() > 40_000_000`），于是应用在一次 43 MB 下载中途被
     * 杀掉之后，下次启动会把**截断的包**当成"已完成"，解压必然失败；
     * 而失败分支又不清 archive，于是每次点安装都是同一个错误，只有手动清数据
     * 才能恢复。现在「下完」由一个标记文件证明，解压失败也会把包删掉。
     */
    private val doneMarker: File get() = File(root, "$TARBALL_NAME.ok")

    /**
     * 下载进度（已写入的字节数）。
     *
     * 为什么需要：这个包 36 MB，网络差的时候要十几分钟。界面只有一个静态的
     * 「下载中…」时，用户分不清是"在慢慢下"还是"已经卡死" —— 实测有人在这
     * 一屏反复点安装。数字不用很精确，能动就行。
     */
    @Volatile
    var onProgress: ((Long) -> Unit)? = null

    /** 当前速度（字节/秒），UI 直接显示。0 表示没有在下载。 */
    @Volatile
    var speedNow: Double = 0.0
        private set

    private val client = DownloadSources.client()
    private val ioScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** 四个 `.so` 都在，且已经成功 `System.load` 过（或 APK 里自带）。 */
    val isInstalled: Boolean
        get() = SherpaNativeLoader.isBundled || SherpaNativeLoader.isLoadableFrom(dir)

    val isReady: Boolean get() = SherpaNativeLoader.isLoaded || SherpaNativeLoader.isBundled

    /**
     * 语音引擎占的磁盘。
     *
     * **只数自己那四个 .so**：`files/native/<abi>/` 是语音引擎和生图后端
     * 共用的目录（两者都必须落在能被 System.load 的路径下）。整目录求和会把
     * 生图后端的 35 MB 算进"语音引擎已安装"，界面上的数字就对不上了。
     */
    fun installedBytes(): Long {
        if (!dir.isDirectory) return 0L
        return LIBRARIES.sumOf { name ->
            val f = File(dir, name)
            if (f.isFile) f.length() else 0L
        }
    }

    /**
     * 确保原生库可用。已经可用就直接返回；否则下载 + 解压 + 加载。
     *
     * 会阻塞，调用方必须在 IO 调度器上跑。
     *
     * @return 是否可用。
     */
    fun ensureInstalled(): Boolean {
        if (SherpaNativeLoader.isBundled) return true
        if (SherpaNativeLoader.loadFrom(dir)) return true

        if (!download()) return false
        if (!extract()) return false
        return SherpaNativeLoader.loadFrom(dir)
    }

    /** 只删落盘的库（APK 自带的删不掉）。 */
    fun uninstall() {
        dir.deleteRecursively()
        archive.delete()
        doneMarker.delete()
    }

    private fun download(): Boolean {
        if (archive.isFile && doneMarker.isFile && archive.length() > 40_000_000) return true
        archive.parentFile?.mkdirs()

        // 地址与代理：走和模型下载同一套规则（设置里的代理模板，不写死 gh-proxy）。
        val urls = DownloadSources.order(storage, URLS)

        for ((i, url) in urls.withIndex()) {
            Log.i(TAG, "下载原生库（${i + 1}/${urls.size}）：$url")
            val ok = runCatching { downloadOne(url) }.getOrElse { t ->
                Log.w(TAG, "  失败：${t.message}")
                archive.delete()
                false
            }
            if (ok) {
                runCatching { doneMarker.writeText(VERSION) }
                return true
            }
        }
        return false
    }

    /** 单地址下载：先试多连接分段，服务端不认 `Range` 时退回单流。 */
    private fun downloadOne(url: String): Boolean {
        archive.delete()
        val probe = MultiPartDownload.probe(client, url)
        if (probe != null && probe.rangeSupported && probe.totalBytes > 8L * 1024 * 1024) {
            val segments = MultiPartDownload.segmentsFor(probe.totalBytes)
            val segDir = MultiPartDownload.segmentDir(
                archive.parentFile ?: return false, archive.name, probe.totalBytes,
            )
            Log.i(TAG, "分段下载 $segments 路，共 ${probe.totalBytes / 1024 / 1024} MB")
            val done = kotlinx.coroutines.runBlocking {
                MultiPartDownload.download(
                    client = client,
                    scope = ioScope,
                    url = url,
                    target = segDir,
                    assembleTo = archive,
                    totalBytes = probe.totalBytes,
                    segments = segments,
                    isCancelled = { false },
                    onProgress = { bytes, _, speed ->
                        speedNow = speed
                        onProgress?.invoke(bytes)
                    },
                )
            }
            if (done) {
                speedNow = 0.0
                return archive.length() > 40_000_000
            }
            segDir.deleteRecursively()
            Log.w(TAG, "分段下载未完成，退回单连接")
        }

        val conn = java.net.URI(url).toURL().openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 600_000
            setRequestProperty("User-Agent", "LocalMind/1.0 (Android)")
        }
        conn.getInputStream().use { input ->
            archive.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var written = 0L
                var last = 0L
                var window = 0L
                var windowStart = System.currentTimeMillis()
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                    window += n
                    val now = System.currentTimeMillis()
                    if (now - windowStart >= 500) {
                        speedNow = window * 1000.0 / (now - windowStart).coerceAtLeast(1)
                        window = 0
                        windowStart = now
                        last = now
                        onProgress?.invoke(written)
                    }
                }
                onProgress?.invoke(written)
            }
        }
        speedNow = 0.0
        return archive.length() > 40_000_000
    }

    /**
     * 解开 tarball，只留我们那四个 `.so`。
     *
     * 整包解到临时目录再挑，而不是找一个能"只解部分"的 tar 参数 ——
     * tar 没有这种能力（它得顺序读 bzip2 流）。46 MB 解完约腾出 150 MB，
     * 挑完立刻删临时目录。
     */
    private fun extract(): Boolean {
        val staging = File(root, "staging")
        staging.deleteRecursively()
        staging.mkdirs()

        val ok = runCatching {
            val proc = ProcessBuilder(
                "tar", "-xjf", archive.absolutePath, "-C", staging.absolutePath,
            ).redirectErrorStream(true).start()
            // 必须把输出读掉：写满管道缓冲区 tar 会阻塞，waitFor 永不返回。
            proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor() == 0
        }.getOrElse { t ->
            Log.w(TAG, "解压失败：${t.message}")
            false
        }
        if (!ok) {
            // 截断/损坏的包留着只会让下一次安装重复失败，直接丢掉重下。
            staging.deleteRecursively()
            archive.delete()
            doneMarker.delete()
            return false
        }

        dir.mkdirs()
        var copied = 0
        // 压缩包里是 jniLibs/<abi>/<name>.so，只挑当前 ABI。
        val wanted = staging.walkTopDown()
            .filter { it.isFile && it.name in LIBRARIES && it.parentFile?.name == abi }
            .toList()
        wanted.forEach { so ->
            so.copyTo(File(dir, so.name), overwrite = true)
            copied++
        }
        staging.deleteRecursively()
        archive.delete()
        doneMarker.delete()

        if (copied != LIBRARIES.size) {
            Log.w(TAG, "只解出 $copied/${LIBRARIES.size} 个原生库（ABI=$abi），放弃")
            dir.deleteRecursively()
            return false
        }
        Log.i(TAG, "原生库就位：$abi，${installedBytes() / 1024 / 1024} MB")
        return true
    }
}

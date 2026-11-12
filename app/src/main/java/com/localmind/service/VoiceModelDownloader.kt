package com.localmind.service

import android.util.Log
import com.k2fsa.sherpa.onnx.SherpaNativeLoader
import com.localmind.data.Storage
import com.localmind.data.VoiceEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "VoiceDownloader"

/**
 * 原生库状态在那个 map 里用的 key（不属于任何音色）。
 * 公开是因为 ViewModel 要从同一个 states flow 里把它取出来。
 */
const val VOICE_LIBRARY_KEY = "__sherpa_library__"
private const val LIBRARY_KEY = VOICE_LIBRARY_KEY

/**
 * 音色模型的下载与安装。
 *
 * ## 为什么不能用 [ModelDownloader]
 *
 * 那个是给 GGUF 写的：单文件、支持 `Range` 续传、下完校验 `GGUF` 魔数。
 * 音色是 **tar.bz2 压缩包**，要解开、要删包、要在一堆杂项文件里认出模型布局，
 * 校验也得换成"能不能解析出 VITS 布局"。硬塞进 ModelDownloader 会让两边都变脏。
 *
 * ## 续传
 *
 * 从断点继续下 —— 一个 115 MB 的包在手机上重下几次是不能接受的。这样能成立的
 * 前提是 GitHub Release 支持 `Range`（它支持，走 gh-proxy 时由代理透传）。
 * 服务端不认 `Range` 时（返回 200 + 全量）就把半成品删掉重来，和 ModelDownloader
 * 的处理一致。
 *
 * ## 解压
 *
 * **调系统 `tar`**，不是自己写 bz2 解码器：JDK 里没有 bzip2，Android 的
 * `java.util.zip` 只认 deflate/gzip。而 bzip2 单独实现一份（还要防解压炸弹）
 * 是纯粹的负债。Android 的 toybox 自带 `tar -xjf`，从 API 26 起都在。
 * 解压的目标目录是我们自己建的，包名是我们拼的，不接受外部输入 —— 这也是
 * 能安全用 shell 的前提。
 */
class VoiceModelDownloader(
    private val storage: Storage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    enum class Status { IDLE, DOWNLOADING, EXTRACTING, DONE, FAILED }

    data class State(
        val voiceId: String,
        val status: Status = Status.IDLE,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val error: String? = null,
        /** 瞬时速度（字节/秒）。分段下载时是所有段合起来的速度。 */
        val bytesPerSecond: Double = 0.0,
        /** 实际用的地址（直连还是某个代理）。UI 上显示出来，用户才知道代理生效没有。 */
        val source: String = "",
    ) {
        val progress: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

        val busy: Boolean get() = status == Status.DOWNLOADING || status == Status.EXTRACTING
    }

    private val client = DownloadSources.client()

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val cancelled = mutableSetOf<String>()

    fun stateOf(voice: VoiceEntry): State = _states.value[voice.id] ?: State(voice.id)

    /**
     * 神经语音**引擎本身**（36 MB/ABI 原生库）的状态。
     *
     * 借同一个 map 存，key 用 [LIBRARY_KEY]：它不属于任何一个音色，而是所有
     * 神经音色共同的前置条件。这样 UI 只需要订阅一个 flow。
     */
    val libraryState: State get() = _states.value[LIBRARY_KEY] ?: State(LIBRARY_KEY)

    /**
     * 下载并安装原生库（不进 APK，见 [SherpaLibraryInstaller]）。
     *
     * 阻塞在 IO 上，进度只能给个"进行中"—— 官方只发整包 tarball，
     * 我们没法在解压前知道确切总长，所以 [State.totalBytes] 用估算值。
     */
    fun installLibrary(storage: Storage) {
        if (jobs[LIBRARY_KEY]?.isActive == true) return
        cancelled.remove(LIBRARY_KEY)
        update(LIBRARY_KEY) {
            it.copy(
                status = Status.DOWNLOADING,
                error = null,
                downloadedBytes = 0,
                totalBytes = SherpaNativeLoader.APPROX_BYTES_PER_ABI,
            )
        }
        jobs[LIBRARY_KEY] = scope.launch {
            val installer = SherpaLibraryInstaller(storage)
            installer.onProgress = { bytes ->
                update(LIBRARY_KEY) {
                    it.copy(downloadedBytes = bytes, bytesPerSecond = installer.speedNow)
                }
            }
            val ok = runCatching { installer.ensureInstalled() }.getOrElse { t ->
                Log.w(TAG, "原生库安装失败：${t.message}", t)
                false
            }
            // update 里会写 StateFlow，本身不该抛；真抛了也不能变成进程级崩溃。
            update(LIBRARY_KEY) {
                it.copy(
                    status = if (ok) Status.DONE else Status.FAILED,
                    downloadedBytes = if (ok) installer.installedBytes() else 0,
                    totalBytes = if (ok) installer.installedBytes() else it.totalBytes,
                    error = if (ok) null else "下载或解压失败（网络？存储空间？）",
                    bytesPerSecond = 0.0,
                )
            }
        }
    }

    /**
     * 读-改-写必须互斥。
     *
     * 用户可以同时下多个音色（页面上一排「下载」按钮），每个都在自己的协程里
     * 高频调这个函数刷进度。而 `_states.value = current + (id to ...)` 不是原子操作 ——
     * 两个协程交错时，后写的那个会拿旧的 map 覆盖掉前一个刚写进去的进度，
     * 表现就是进度条**跳着走甚至倒退**。
     *
     * 用 `synchronized` 而不是 Mutex：这里是纯内存的短临界区，没有挂起点，
     * 加锁比挂起简单也更省。
     */
    private val stateLock = Any()

    private fun update(id: String, block: (State) -> State) {
        synchronized(stateLock) {
            val cur = _states.value
            _states.value = cur + (id to block(cur[id] ?: State(id)))
        }
    }

    /**
     * 下载并安装一个音色。
     *
     * 完成后**不会**自动加载 —— 加载是 [NeuralTtsEngine] 的事，由 ViewModel 决定
     * （用户可能只是想先下好，稍再切过去）。
     */
    fun install(voice: VoiceEntry) {
        
        if (jobs[voice.id]?.isActive == true) return
        cancelled.remove(voice.id)
        update(voice.id) {
            it.copy(
                status = Status.DOWNLOADING, error = null, downloadedBytes = 0,
                bytesPerSecond = 0.0, source = "",
            )
        }
        // 同样要兜住：scope 是 SupervisorJob 且无异常处理器，runInstall 里任何
        // 未捕获异常都会变成 FATAL EXCEPTION 把应用带走（下载路径上这很常见）。
        jobs[voice.id] = scope.launch {
            runCatching { runInstall(voice) }.onFailure { t ->
                Log.w(TAG, "${voice.id} 安装异常：${t.message}", t)
                update(voice.id) {
                    it.copy(status = Status.FAILED, error = t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    /**
     * 等某个音色的安装任务结束。批量下载用它把**同时进行的下载**压到 N 个以内：
     * [install] 只是起一个协程并立刻返回，真正占带宽的是那个协程。
     */
    suspend fun await(voice: VoiceEntry) {
        jobs[voice.id]?.join()
    }

    fun cancel(voice: VoiceEntry) {
        cancelled += voice.id
        jobs[voice.id]?.cancel()
        NeuralVoiceModels.archiveOf(storage, voice).delete()
        update(voice.id) { it.copy(status = Status.IDLE, downloadedBytes = 0, error = null) }
    }

    /** 删掉已安装的音色（连同解压出来的目录）。 */
    fun uninstall(voice: VoiceEntry) {
        cancel(voice)
        NeuralVoiceModels.dirOf(storage, voice).deleteRecursively()
        update(voice.id) { it.copy(status = Status.IDLE, downloadedBytes = 0, totalBytes = 0, error = null) }
    }

    private suspend fun runInstall(voice: VoiceEntry) {
        val archive = NeuralVoiceModels.archiveOf(storage, voice)
        val target = NeuralVoiceModels.dirOf(storage, voice)
        NeuralVoiceModels.voiceRoot(storage).mkdirs()

        // 地址列表统一由 VoiceCatalog 给出：插件可以自带 url / mirrors，
        // 之前这里按 id 现拼，导致**插件里写的镜像永远不生效** —— 明明允许插件
        // 换地址，却不读那个字段，等于把可维护性又退回发版。
        // 顺序：插件 url（或默认 tarball 地址）→ mirrors → gh-proxy 兜底。
        val urls = DownloadSources.order(storage, com.localmind.data.VoiceCatalog.urlsFor(voice))

        var lastError: String? = null
        for ((i, url) in urls.withIndex()) {
            if (cancelled.contains(voice.id)) return
            val ok = try {
                download(url, archive, voice)
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "来源 ${i + 1} 失败：$lastError", t)
                false
            }
            if (cancelled.contains(voice.id)) return
            if (!ok) continue

            update(voice.id) { it.copy(status = Status.EXTRACTING) }
            val extracted = extract(archive, target)
            archive.delete()

            if (!extracted) {
                lastError = "解压失败"
                target.deleteRecursively()
                continue
            }

            // 解压成功 ≠ 能用。真正验收标准是"能解析出已知架构的模型布局" —— 下到一半的
            // 压缩包、解压出空目录、布局变了，都在这里被挡住，而不是等用户点播放
            // 才发现没声音。支持 VITS / Kokoro / Kitten / Matcha（见 NeuralVoiceModels.resolve）。
            val layout = NeuralVoiceModels.resolve(target)
            if (layout == null) {
                lastError = "解压后没有找到可用的 TTS 模型文件"
                target.deleteRecursively()
                continue
            }

            update(voice.id) {
                it.copy(
                    status = Status.DONE,
                    downloadedBytes = layout.sizeBytes,
                    totalBytes = layout.sizeBytes,
                    error = null,
                )
            }
            Log.i(TAG, "${voice.id} 安装完成：${layout.model} (${layout.sizeBytes / 1024 / 1024} MB)")
            return
        }

        update(voice.id) {
            it.copy(status = Status.FAILED, error = lastError ?: "全部下载源均失败")
        }
    }

    /** 断点续传；服务端忽略 `Range` 时从零重来。 */
    private suspend fun download(url: String, archive: File, voice: VoiceEntry): Boolean {
        // ---- 先试多连接分段 ----
        //
        // 单连接的速度上限就是这条 TCP 能跑多快；移动网络一丢包，
        // 拥塞窗口掉下去就再也爬不起来（实测 109 MB 的克隆底模只有 13 KB/s）。
        // 分段之后每段各有一条连接、各自的拥塞窗口，这是下载器的标准做法。
        //
        // 服务端不认 Range（回 200）时 probe 会给 rangeSupported=false，
        // 那就老老实实单连接 —— 硬分段拼出来的是错位文件，比慢可怕得多。
        archive.parentFile?.mkdirs()
        update(voice.id) { it.copy(source = url) }

        val probe = MultiPartDownload.probe(client, url)
        if (probe != null && probe.rangeSupported && probe.totalBytes > 8L * 1024 * 1024) {
            val segments = MultiPartDownload.segmentsFor(probe.totalBytes)
            val segDir = MultiPartDownload.segmentDir(
                archive.parentFile ?: return false, archive.name, probe.totalBytes,
            )
            Log.i(
                TAG,
                "${voice.id}：分段下载 $segments 路，共 ${probe.totalBytes / 1024 / 1024} MB — $url",
            )
            val ok = MultiPartDownload.download(
                client = client,
                scope = scope,
                url = url,
                target = segDir,
                assembleTo = archive,
                totalBytes = probe.totalBytes,
                segments = segments,
                isCancelled = { cancelled.contains(voice.id) },
                onProgress = { done, total, speed ->
                    update(voice.id) {
                        it.copy(downloadedBytes = done, totalBytes = total, bytesPerSecond = speed)
                    }
                },
            )
            if (ok) {
                update(voice.id) { it.copy(bytesPerSecond = 0.0) }
                return true
            }
            if (cancelled.contains(voice.id)) return false
            // 分段失败：段目录留着没意义了（下一轮可能换镜像，总长也未必一样）。
            segDir.deleteRecursively()
            Log.w(TAG, "${voice.id} 分段下载未完成，退回单连接")
        }
        return downloadSingle(url, archive, voice)
    }

    /** 单连接兜底：服务端不支持 Range，或者分段中途失败。 */
    private fun downloadSingle(url: String, archive: File, voice: VoiceEntry): Boolean {
        val existing = if (archive.isFile) archive.length() else 0L
        val builder = Request.Builder().url(url).header("User-Agent", "LocalMind/1.0 (Android)")
        if (existing > 0) builder.header("Range", "bytes=$existing-")

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")

            var start = if (existing > 0 && response.code == 206) existing else 0L
            if (existing > 0 && response.code == 200) {
                // 200 意味着服务端不认 Range，这是全量重来。
                Log.i(TAG, "服务端忽略 Range，从 0 重新下载")
                archive.delete()
                start = 0L
            }
            if (response.code == 206 && start > 0) {
                val declared = response.header("Content-Range")
                    ?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                if (declared != null && declared != start) {
                    throw IllegalStateException("续传偏移不匹配：请求 $start，服务器从 $declared 开始")
                }
            }

            val body = response.body ?: throw IllegalStateException("响应为空")
            val contentLength = body.contentLength()
            val total = when {
                contentLength > 0 && response.code == 206 -> start + contentLength
                contentLength > 0 -> contentLength
                else -> voice.sizeBytes
            }

            archive.parentFile?.mkdirs()
            java.io.RandomAccessFile(archive, "rw").use { raf ->
                raf.seek(start)
                var written = start
                val buffer = ByteArray(256 * 1024)
                var window = 0L
                var windowStart = System.currentTimeMillis()

                body.byteStream().use { input ->
                    while (true) {
                        if (cancelled.contains(voice.id)) return false
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        written += read
                        window += read

                        val now = System.currentTimeMillis()
                        if (now - windowStart >= 400) {
                            val speed = window * 1000.0 / (now - windowStart).coerceAtLeast(1)
                            window = 0
                            windowStart = now
                            update(voice.id) {
                                it.copy(
                                    downloadedBytes = written,
                                    totalBytes = if (total > 0) total else voice.sizeBytes,
                                    bytesPerSecond = speed,
                                )
                            }
                        }
                    }
                }
                update(voice.id) {
                    it.copy(downloadedBytes = written, totalBytes = if (total > 0) total else written)
                }
            }
            return true
        }
    }

    /**
     * 解压到 [target]。
     *
     * `--strip-components=1`：官方包的顶层是 `<id>/` 目录，剥掉它之后
     * `target` 里直接就是模型文件，路径短一截、也不用再猜顶层目录叫什么。
     */
    private fun extract(archive: File, target: File): Boolean {
        target.deleteRecursively()
        target.mkdirs()
        return runCatching {
            val proc = ProcessBuilder(
                "tar", "-xjf", archive.absolutePath,
                "-C", target.absolutePath,
                "--strip-components=1",
            ).redirectErrorStream(true).start()
            // 必须把输出读掉：tar 写满管道缓冲区就会阻塞，waitFor 永远不返回。
            proc.inputStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            if (code != 0) Log.w(TAG, "tar 退出码 $code")
            code == 0
        }.getOrElse { t ->
            Log.w(TAG, "解压失败：${t.message}", t)
            false
        }
    }
}

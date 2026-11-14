package com.localmind.service

import android.util.Log
import com.localmind.data.ImageModelEntry
import com.localmind.data.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File

private const val TAG = "ImageModelDL"

/**
 * 生图 checkpoint 的下载器。
 *
 * 和 [VoiceModelDownloader] 是同一个套路，但**简单一层**：checkpoint 是单个
 * `.gguf` 文件，不用解压、不用认目录布局，只需要「分段拉下来 + 校验大小」。
 *
 * ## 分段是必须的
 *
 * 1.6 GB 的文件在单连接上实测只有 13 KB/s（拥塞窗口一掉就再也爬不起来），
 * 那是三十多个小时。走 [MultiPartDownload] 分段之后同样的网络能到 175 KB/s
 * 以上，小时级。HF 镜像支持 `Range`（实测 206），所以这条路成立。
 *
 * ## 校验
 *
 * 下完只校验**长度**和 GGUF 魔数。不校验 SHA：镜像仓库的文件会被重传，
 * 硬编码哈希会在某天突然让所有人下载失败，而长度 + 魔数已经能挡住
 * 「下了一半」「下成了错误页」这两类真实故障。
 */
class ImageModelDownloader(
    private val storage: Storage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    enum class Status { IDLE, DOWNLOADING, DONE, FAILED }

    data class State(
        val id: String,
        val status: Status = Status.IDLE,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val bytesPerSecond: Double = 0.0,
        val error: String? = null,
        val source: String = "",
    ) {
        val progress: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

        val busy: Boolean get() = status == Status.DOWNLOADING
    }

    private val client = DownloadSources.client()

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val cancelled = mutableSetOf<String>()
    private val lock = Any()

    fun stateOf(entry: ImageModelEntry): State = _states.value[entry.id] ?: State(entry.id)

    fun fileOf(entry: ImageModelEntry): File = File(storage.modelsDir, entry.fileName)

    fun isInstalled(entry: ImageModelEntry): Boolean {
        val f = fileOf(entry)
        return f.isFile && f.length() > entry.sizeBytes * 9 / 10
    }

    fun installedBytes(entry: ImageModelEntry): Long = fileOf(entry).let { if (it.isFile) it.length() else 0L }

    fun install(entry: ImageModelEntry) {
        if (jobs[entry.id]?.isActive == true) return
        if (isInstalled(entry)) {
            update(entry.id) { it.copy(status = Status.DONE, downloadedBytes = installedBytes(entry), totalBytes = entry.sizeBytes) }
            return
        }
        cancelled.remove(entry.id)
        update(entry.id) {
            it.copy(status = Status.DOWNLOADING, error = null, downloadedBytes = 0, totalBytes = entry.sizeBytes)
        }
        jobs[entry.id] = scope.launch {
            runCatching { run(entry) }.onFailure { t ->
                Log.w(TAG, "${entry.id} 下载异常：${t.message}", t)
                update(entry.id) {
                    it.copy(status = Status.FAILED, error = t.message ?: t.javaClass.simpleName, bytesPerSecond = 0.0)
                }
            }
        }
    }

    fun cancel(entry: ImageModelEntry) {
        cancelled += entry.id
        jobs[entry.id]?.cancel()
        fileOf(entry).delete()
        update(entry.id) { it.copy(status = Status.IDLE, downloadedBytes = 0, bytesPerSecond = 0.0) }
    }

    fun uninstall(entry: ImageModelEntry) {
        cancelled += entry.id
        jobs[entry.id]?.cancel()
        fileOf(entry).delete()
        update(entry.id) { it.copy(status = Status.IDLE, downloadedBytes = 0, totalBytes = 0, error = null) }
    }

    /** 只读-改-写要互斥，理由同 VoiceModelDownloader.update。 */
    private fun update(id: String, block: (State) -> State) {
        synchronized(lock) {
            val cur = _states.value
            _states.value = cur + (id to block(cur[id] ?: State(id)))
        }
    }

    private suspend fun run(entry: ImageModelEntry) {
        storage.modelsDir.mkdirs()
        val target = fileOf(entry)
        var lastError: String? = null

        for (url in DownloadSources.order(storage, entry.urls)) {
            if (cancelled.contains(entry.id)) return
            update(entry.id) { it.copy(source = url) }
            val ok = runCatching { fetch(url, target, entry) }.getOrElse { t ->
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "来源失败 $url：${t.message}", t)
                false
            }
            if (cancelled.contains(entry.id)) return
            if (!ok) continue
            if (!looksValid(target, entry)) {
                lastError = "下载的内容不是有效的模型文件（可能拿到了错误页）"
                target.delete()
                continue
            }
            update(entry.id) {
                it.copy(
                    status = Status.DONE,
                    downloadedBytes = target.length(),
                    totalBytes = target.length(),
                    bytesPerSecond = 0.0,
                    error = null,
                )
            }
            Log.i(TAG, "${entry.id} 下载完成：${target.length() / 1024 / 1024} MB")
            return
        }
        update(entry.id) {
            it.copy(status = Status.FAILED, error = lastError ?: "全部下载源均失败", bytesPerSecond = 0.0)
        }
    }

    private suspend fun fetch(url: String, target: File, entry: ImageModelEntry): Boolean {
        target.parentFile?.mkdirs()
        val probe = MultiPartDownload.probe(client, url)
        if (probe != null && probe.rangeSupported && probe.totalBytes > 8L * 1024 * 1024) {
            val segments = MultiPartDownload.segmentsFor(probe.totalBytes)
            val segDir = MultiPartDownload.segmentDir(
                target.parentFile ?: return false, target.name + ".part", probe.totalBytes,
            )
            Log.i(TAG, "${entry.id}：分段下载 $segments 路，共 ${probe.totalBytes / 1024 / 1024} MB")
            val ok = MultiPartDownload.download(
                client = client,
                scope = scope,
                url = url,
                target = segDir,
                assembleTo = target,
                totalBytes = probe.totalBytes,
                segments = segments,
                isCancelled = { cancelled.contains(entry.id) },
                onProgress = { done, total, speed ->
                    update(entry.id) {
                        it.copy(downloadedBytes = done, totalBytes = total, bytesPerSecond = speed)
                    }
                },
            )
            if (ok) return true
            if (cancelled.contains(entry.id)) return false
            segDir.deleteRecursively()
            Log.w(TAG, "${entry.id} 分段未完成，退回单连接")
        }
        return fetchSingle(url, target, entry)
    }

    /** 单连接：服务端不认 Range，或分段失败时的兜底。 */
    private fun fetchSingle(url: String, target: File, entry: ImageModelEntry): Boolean {
        val existing = if (target.isFile) target.length() else 0L
        val builder = Request.Builder().url(url).header("User-Agent", "LocalMind/1.0 (Android)")
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            var start = if (response.code == 206) existing else 0L
            if (existing > 0 && response.code == 200) {
                target.delete()
                start = 0L
            }
            val body = response.body ?: throw IllegalStateException("响应为空")
            val total = when {
                body.contentLength() > 0 && response.code == 206 -> start + body.contentLength()
                body.contentLength() > 0 -> body.contentLength()
                else -> entry.sizeBytes
            }
            java.io.RandomAccessFile(target, "rw").use { raf ->
                raf.seek(start)
                var written = start
                val buffer = ByteArray(256 * 1024)
                var window = 0L
                var windowStart = System.currentTimeMillis()
                body.byteStream().use { input ->
                    while (true) {
                        if (cancelled.contains(entry.id)) return false
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
                            update(entry.id) {
                                it.copy(downloadedBytes = written, totalBytes = total, bytesPerSecond = speed)
                            }
                        }
                    }
                }
            }
            return true
        }
    }

    /**
     * 只校验文件头，不校验 SHA：镜像仓库的文件会被重传，硬编码哈希会在某天
     * 突然让所有人下载失败，而「长度 + 魔数」已经能挡住「下了一半」「下成了
     * 错误页」这两类真实故障。
     *
     * checkpoint 是 GGUF；ESRGAN 是 `torch.save` 出来的 zip（PK\x03\x04），
     * 两者魔数不同，必须按 [ImageModelEntry.kind] 分开校验 —— 否则超分模型
     * 会永远显示「下载的内容不是 GGUF」，然后被删掉。
     */
    private fun looksValid(file: File, entry: ImageModelEntry): Boolean = runCatching {
        if (!file.isFile || file.length() < 1024) return false
        val magic = ByteArray(4)
        file.inputStream().use { it.read(magic) }
        when (entry.kind) {
            ImageModelEntry.Kind.CHECKPOINT -> String(magic, Charsets.US_ASCII) == "GGUF"
            ImageModelEntry.Kind.UPSCALER -> magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)
}

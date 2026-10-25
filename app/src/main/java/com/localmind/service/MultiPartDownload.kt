package com.localmind.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "MultiPartDownload"

/**
 * 多连接分段下载。
 *
 * ## 为什么要它
 *
 * 原来的下载是**一条连接流式写**，速度上限基本等于"这条 TCP 连接能跑多快"。
 * 下 3 GB 的 Qwen 要十几分钟，用户的原话是"现在有点慢"。
 *
 * 换成 N 条连接各拉一段之后，单条连接的**丢包重传/拥塞窗口**不再是全局瓶颈 ——
 * 这是所有下载器的标准做法。实测在 hf-mirror 上从约 1/3 提速到接近带宽上限
 * （具体倍数取决于网络，不承诺）。
 *
 * ## 为什么分段数不能大
 *
 * 每段一条 TCP 连接，每条连接都要：
 *  * 三次握手 + TLS 握手（几百毫秒）；
 *  * 一份自己的拥塞窗口，起步阶段都跑不满。
 *
 * 所以段数是**按文件大小**选的：小文件分段只会更慢。经验阈值见 [segmentsFor]。
 *
 * ## 断点续传怎么和分段共存
 *
 * 段文件放在 `<name>.part.d/` 目录里，`seg0000` / `seg0001` …
 * **已经下满的段直接用**，只有没下满的那些重新拉（并且只拉缺的那一截）。
 * 暂停/取消/崩溃之后重来，进度不会丢。
 *
 * 段目录名里**带上了文件总大小**（`<name>.<total>.part.d`）：如果上游换了文件、
 * 或者用户换了镜像而那个源的文件大小不一样，旧段会被自动弃用，
 * 而不是把两段不同文件的内容拼在一起 —— 那种文件校验大小是过得去的（总长一样），
 * 但模型加载时会崩，而且极难查。
 */
internal object MultiPartDownload {

    /**
     * 分段数。
     *
     * | 文件大小 | 段数 | 理由 |
     * | --- | --- | --- |
     * | < 24 MB | 1 | 分段的开销（握手）比收益大 |
     * | 24–128 MB | 2 | |
     * | 128 MB–1 GB | 4 | 手机上的甜点区 |
     * | > 1 GB | 6 | 再往上握手和内存占用开始明显 |
     *
     * 上限 6 是有意的：8 条以上在移动网络上反而互相抢带宽，
     * 而且每段一个 1 MB 读缓冲，段数多了内存也不好看。
     */
    fun segmentsFor(totalBytes: Long): Int = when {
        totalBytes < 24L * 1024 * 1024 -> 1
        totalBytes < 128L * 1024 * 1024 -> 2
        totalBytes < 1024L * 1024 * 1024 -> 4
        else -> 6
    }

    /** 上游信息：文件多大、支不支持 Range。 */
    data class Probe(val totalBytes: Long, val rangeSupported: Boolean)

    /**
     * 探测文件大小与 Range 支持。
     *
     * 用 GET + `Range: bytes=0-0` 而不是 HEAD —— 很多 CDN 对 HEAD 返回的
     * header 和 GET 不一致（或者干脆 405）。带 Range 的请求拿到 206 就说明支持分段，
     * 拿到 200 说明服务器无视 Range（那种就只能单连接）。
     *
     * 拿不到大小就返回 null，调用方退回单连接路径。
     */
    fun probe(client: OkHttpClient, url: String): Probe? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "LocalMind/1.0 (Android)")
            .header("Range", "bytes=0-0")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 206) {
                    val total = resp.header("Content-Range")
                        ?.substringAfter('/')
                        ?.trim()
                        ?.toLongOrNull()
                    if (total != null && total > 1) Probe(total, true) else null
                } else if (resp.code == 200) {
                    val len = resp.body?.contentLength() ?: -1
                    if (len > 0) Probe(len, false) else null
                } else {
                    Log.i(TAG, "probe HTTP ${resp.code} for $url")
                    null
                }
            }
        }.getOrNull()
    }

    /** 段目录。名字里带总大小，换了文件就自动不复用旧段。 */
    fun segmentDir(modelsDir: File, fileName: String, totalBytes: Long): File =
        File(modelsDir, "$fileName.$totalBytes.part.d")

    /**
     * 并行拉完所有段。
     *
     * @param onProgress 每 500ms 回一次**总进度**（不是单段）。传的是累计速度，
     *   由所有段的增量合起来算 —— 单段速度在这里没有意义。
     * @return 全部段都完整时 true。中途失败/取消返回 false，已下的段留在磁盘上。
     */
    suspend fun download(
        client: OkHttpClient,
        scope: CoroutineScope,
        url: String,
        /** 段目录（`<name>.<total>.part.d`）。断点续传的单位就是它。 */
        target: File,
        /** 全部段拼好之后写到这里。调用方会拿它去做大小校验和改名。 */
        assembleTo: File,
        totalBytes: Long,
        segments: Int,
        isCancelled: () -> Boolean,
        onProgress: (downloaded: Long, total: Long, bytesPerSecond: Double) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        // target 是**段目录**（调用方传进来的是 <name>.<total>.part.d）
        val dir = target
        dir.mkdirs()

        val segSize = (totalBytes + segments - 1) / segments
        val done = LongArray(segments) { i ->
            // 已经存在的段文件：长度就是它已经下到的位置。
            val f = File(dir, "seg%04d".format(i))
            if (f.exists()) f.length().coerceAtMost(lenOf(i, segSize, totalBytes)) else 0L
        }

        val counter = AtomicLong(done.sum())
        val lastSample = AtomicLong(counter.get())
        val lastAt = AtomicLong(System.currentTimeMillis())
        var speed = 0.0

        // 进度上报：一个协程统一算，避免 N 个段各自去改状态（那样进度会跳、
        // 速度会互相覆盖）。500ms 一次，和原来单连接的节奏一致。
        val reporter = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val now = System.currentTimeMillis()
                val bytes = counter.get()
                val elapsed = now - lastAt.get()
                if (elapsed >= 400) {
                    val instant = (bytes - lastSample.get()) * 1000.0 / elapsed
                    speed = if (speed <= 0.0) instant else speed * 0.6 + instant * 0.4
                    lastSample.set(bytes)
                    lastAt.set(now)
                    // onProgress 是调用方给的，里面可能碰 UI 状态、也可能抛。
                    // 让异常逃出去等于把整个进程带走（见 download 里 workers 的说明）。
                    runCatching { onProgress(bytes, totalBytes, speed) }
                        .onFailure { Log.w(TAG, "progress callback failed", it) }
                }
            }
        }

        val workers = (0 until segments).map { i ->
            val start = i * segSize
            val end = lenOf(i, segSize, totalBytes) + start - 1
            scope.launch(Dispatchers.IO) {
                if (end < start) return@launch                     // 空的尾段
                val segFile = File(dir, "seg%04d".format(i))
                val already = done[i]
                if (already >= end - start + 1) return@launch       // 这一段已经下满

                // 单段失败**必须**在这里被吃掉。
                //
                // scope 是 SupervisorJob 且没有 CoroutineExceptionHandler，所以
                // launch 里逃出来的异常会一路走到线程的默认处理器 —— 结果是
                // FATAL EXCEPTION、**整个应用进程消失**。而这里抛的都是网络常态：
                // SocketException（连接被中断）、HTTP 非 206…… 实测用户在下
                // Qwen3.5-4B 时就是这样被反复打死的（237 MB / 2.7 GB 处反复重启）。
                //
                // 段没下满不影响断点续传：已经写下的字节留在 segNNNN 里，
                // 下面的完整性检查会返回 false，下次接着下。
                runCatching {
                    pullSegment(
                        client = client,
                        url = url,
                        segFile = segFile,
                        rangeStart = start + already,
                        rangeEnd = end,
                        isCancelled = isCancelled,
                        counter = counter,
                    )
                }.onFailure { Log.w(TAG, "segment $i failed: ${it.message}", it) }
            }
        }

        try {
            workers.forEach { it.join() }
        } finally {
            reporter.cancel()
        }

        if (isCancelled()) return@withContext false

        // 每段都得下满才算成功。
        val complete = (0 until segments).all { i ->
            val expect = lenOf(i, segSize, totalBytes)
            File(dir, "seg%04d".format(i)).length() >= expect
        }
        if (!complete) {
            Log.w(TAG, "segments incomplete; keeping them for resume")
            return@withContext false
        }

        // 按顺序拼成一个文件。
        assembleTo.parentFile?.mkdirs()
        val out = RandomAccessFile(assembleTo, "rw")
        try {
            out.setLength(totalBytes)
            (0 until segments).forEach { i ->
                val segFile = File(dir, "seg%04d".format(i))
                RandomAccessFile(segFile, "r").use { input ->
                    val buf = ByteArray(1024 * 1024)
                    out.seek(i * segSize)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                }
            }
        } finally {
            out.close()
        }
        dir.deleteRecursively()
        onProgress(totalBytes, totalBytes, 0.0)
        true
    }

    /** 第 i 段应有的字节数。最后一段吃掉余数。 */
    private fun lenOf(i: Int, segSize: Long, total: Long): Long =
        if (i * segSize >= total) 0L else minOf(segSize, total - i * segSize)

    /**
     * 拉一段（`rangeStart..rangeEnd`），写到 [segFile] 的**相对偏移**位置。
     *
     * 相对偏移 = 文件内位置 - 段起点。所以每个段文件都是"从 0 开始"的，
     * 拼接时按段序读即可。
     */
    private suspend fun pullSegment(
        client: OkHttpClient,
        url: String,
        segFile: File,
        rangeStart: Long,
        rangeEnd: Long,
        isCancelled: () -> Boolean,
        counter: AtomicLong,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LocalMind/1.0 (Android)")
            .header("Range", "bytes=$rangeStart-$rangeEnd")
            .build()

        client.newCall(request).execute().use { resp ->
            // 206 = 服务器照办了，这是唯一能安全并行的情况。
            // 200 = 服务器无视 Range 发了整个文件：**不能**用来填中间段，
            //       否则段文件里会是"整个文件的开头"，拼出来必然错位。
            if (resp.code != 206) {
                Log.w(TAG, "segment got HTTP ${resp.code}, expected 206 — aborting this segment")
                throw IllegalStateException("服务器不支持分段下载（HTTP ${resp.code}）")
            }
            val body = resp.body ?: throw IllegalStateException("响应为空")
            val base = segFile.length()
            RandomAccessFile(segFile, "rw").use { raf ->
                raf.seek(base)
                val buf = ByteArray(512 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        if (isCancelled()) return@withContext
                        val read = input.read(buf)
                        if (read <= 0) break
                        raf.write(buf, 0, read)
                        counter.addAndGet(read.toLong())
                    }
                }
            }
        }
    }
}

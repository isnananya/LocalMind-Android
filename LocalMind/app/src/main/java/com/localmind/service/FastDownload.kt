package com.localmind.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 大文件高速下载：**自动挑源 + 多线程分段**。
 *
 * ## 为什么要有这个文件
 *
 * 主题包、字体、气泡素材全都要从 GitHub 拉，而国内的现实是：同一时刻
 * 只有一两个加速源是活的，而且**活下来的是谁经常在变**（上周 ghfast 能用，
 * 这周只有 gh-proxy 通）。
 *
 * 原来的实现有两个毛病：
 *
 *  1. **按固定顺序试**。写死"先 gh-proxy 再 ghproxy"，一旦第一个挂了，
 *     用户就白等一次连接超时才知道换下一个；源一多，累加起来就是几十秒。
 *  2. **单连接下**。公共代理的单连接吞吐经常只有几十 KB/s —— 一个 9 MB 的
 *     字体要拉三分钟，用户以为卡死了。而这些代理**是支持 `Range` 的**，
 *     开 8 条连接就能把吞吐拉到接近带宽上限。
 *
 * 所以这里做两件事，合成一个入口 [fetch]：
 *
 *  * [rank]：**并行**给每个候选地址发一次 `Range: bytes=0-0` 的探测，按实测
 *    延迟排序 —— 谁快用谁，没人告诉它该用哪个，它自己量出来。
 *  * [fetch]：拿排好序的地址，先试最快的；服务端支持分段就开多连接，
 *    不支持或分段失败就退回单连接；这一个源整体失败就换下一个。
 *
 * ## 为什么探测用 `Range: bytes=0-0` 而不是 HEAD
 *
 * 很多 CDN 对 HEAD 返回的 header 和 GET 不一致（甚至直接 405）。带 `Range`
 * 的 GET 拿 206 就说明支持分段，顺手还能拿到总大小 —— 一次请求得到两个信息。
 */
object FastDownload {

    private const val TAG = "FastDownload"

    /** 单个候选源的探测上限。公共代理大多秒回，4 秒没动静的基本就是挂了。 */
    private const val PROBE_TIMEOUT_MS = 4_000L

    /**
     * 低于这个大小就不值得分段了。
     *
     * 256 KB：再小的话握手开销（每条连接一次 TLS + 一次 Range 请求）会吃掉
     * 并行收益，实测反而不如单连接。
     */
    private const val MIN_SEGMENT_BYTES = 256L * 1024

    /** 分段上限。再多是互相抢带宽：移动网络上下载 8 条已经很激进。 */
    private const val MAX_SEGMENTS = 8

    /**
     * 各加速源的近期实测延迟（host → 毫秒）。
     *
     * 存在的意义：字体的样张、清单、字体文件会连着下几十个，每次都把候选源
     * 全探一遍是浪费。这里记住上一轮谁快，下一轮**先探它**，其余并行探，
     * 结果照旧按实测值排 —— 记忆只影响探测顺序，不影响结论，所以不会因为
     * 记错一个源就永远走错路。
     */
    @Volatile
    private var hostLatency: Map<String, Long> = emptyMap()

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/')

    /**
     * 候选地址按**实测延迟**排序。
     *
     * @return 探测成功的按快慢排在前，探测失败的（或超时的）保持原顺序垫后 ——
     *   仍然给它们机会：有可能只是探测被限流，真下载反而没事。
     */
    suspend fun rank(
        client: OkHttpClient,
        urls: List<String>,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
    ): List<String> {
        if (urls.size <= 1) return urls
        val memo = hostLatency

        // 上一轮表现好的排前面先探，尽早拿到可用结果（不影响最终排序）。
        val toProbe = urls.sortedBy { memo[hostOf(it)] ?: Long.MAX_VALUE }

        val measured = coroutineScope {
            toProbe.map { url ->
                async(Dispatchers.IO) {
                    val latency = withTimeoutOrNull(timeoutMs) {
                        val t0 = System.currentTimeMillis()
                        if (MultiPartDownload.probe(client, url) != null) {
                            System.currentTimeMillis() - t0
                        } else {
                            null
                        }
                    }
                    url to latency
                }
            }.awaitAll()
        }

        // 记忆更新：只记成功的，失败的不覆盖已有成绩（可能是偶发限流）。
        val fresh = HashMap(memo)
        measured.forEach { (url, ms) -> if (ms != null) fresh[hostOf(url)] = ms }
        if (fresh != memo) hostLatency = fresh

        val alive = measured.filter { it.second != null }.sortedBy { it.second }
        val dead = measured.filter { it.second == null }
        val order = (alive + dead).map { it.first }
        Log.i(
            TAG,
            "源竞速：" + measured.joinToString("  ") { (u, ms) ->
                hostOf(u) + "=" + (ms?.let { "${it}ms" } ?: "×")
            },
        )
        return order
    }

    /**
     * 下载 [urls] 里的**任意一个**到 [target]。成功返回 true。
     *
     * @param segmentRoot 分段临时目录的父目录。必须和 [target] 同一个磁盘，
     *   否则拼装时要跨设备拷贝（慢且容易失败）。
     * @param accept 内容校验。返回 false 时**换下一个源**而不是就此失败 ——
     *   镜像返回 HTML 错误页是常态，不能让一张错误页把整个下载判死。
     * @param maxBytes 防御性上限，超过直接放弃这个源（挡掉伪装成字体的巨型文件）。
     */
    suspend fun fetch(
        client: OkHttpClient,
        urls: List<String>,
        target: File,
        segmentRoot: File,
        maxBytes: Long = Long.MAX_VALUE,
        accept: (File) -> Boolean = { true },
        isCancelled: () -> Boolean = { false },
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext false
        target.parentFile?.mkdirs()

        val ranked = rank(client, urls)
        for ((index, url) in ranked.withIndex()) {
            if (isCancelled()) return@withContext false
            val ok = runCatching {
                downloadOne(client, url, target, segmentRoot, maxBytes, isCancelled, onProgress)
            }.onFailure { Log.w(TAG, "源 ${index + 1} 异常：${it.message}", it) }
                .getOrDefault(false)

            if (!ok) continue
            if (accept(target)) {
                Log.i(TAG, "下载成功（源 ${index + 1}/${ranked.size}）：${target.name}")
                return@withContext true
            }
            Log.w(TAG, "内容校验不通过（${target.length()} 字节），换下一个源")
            runCatching { target.delete() }
        }
        Log.w(TAG, "所有源都失败：${target.name}（共 ${ranked.size} 个）")
        false
    }

    /** 单源下载：支持分段就多连接，否则单连接流式。 */
    private suspend fun downloadOne(
        client: OkHttpClient,
        url: String,
        target: File,
        segmentRoot: File,
        maxBytes: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        target.delete()

        val probe = MultiPartDownload.probe(client, url)
        if (probe != null && probe.totalBytes > maxBytes) {
            Log.w(TAG, "文件过大（${probe.totalBytes} > $maxBytes），放弃")
            return false
        }

        // 能分段就分段：公共代理的单连接吞吐很可怜，这才是"下载慢"的真正原因。
        if (probe != null && probe.rangeSupported && probe.totalBytes >= MIN_SEGMENT_BYTES) {
            val segments = MAX_SEGMENTS.coerceAtMost(
                ((probe.totalBytes + MIN_SEGMENT_BYTES - 1) / MIN_SEGMENT_BYTES).toInt(),
            )
            if (segments > 1) {
                val segDir = MultiPartDownload.segmentDir(segmentRoot, target.name, probe.totalBytes)
                val ok = kotlinx.coroutines.coroutineScope {
                    MultiPartDownload.download(
                        client = client,
                        scope = this,
                        url = url,
                        target = segDir,
                        assembleTo = target,
                        totalBytes = probe.totalBytes,
                        segments = segments,
                        isCancelled = isCancelled,
                        onProgress = { done, total, _ -> onProgress(done, total) },
                    )
                }
                if (ok && target.length() > 0) return true
                Log.i(TAG, "分段未完成，退回单连接：${target.name}")
            }
        }
        return singleStream(client, url, target, maxBytes, onProgress)
    }

    /** 单连接流式下载。服务端不认 Range、或分段失败时的兜底。 */
    private fun singleStream(
        client: OkHttpClient,
        url: String,
        target: File,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.delete()
        val ok = runCatching {
            val req = Request.Builder().url(url)
                .header("User-Agent", "LocalMind/1.0 (Android)")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful || body == null) {
                    Log.w(TAG, "HTTP ${resp.code}：$url")
                    false
                } else if (body.contentLength() > maxBytes) {
                    Log.w(TAG, "文件过大（${body.contentLength()} > $maxBytes），放弃")
                    false
                } else {
                    val total = body.contentLength()
                    var written = 0L
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                written += n
                                if (written > maxBytes) break
                                out.write(buf, 0, n)
                                onProgress(written, total)
                            }
                        }
                    }
                    written > 0
                }
            }
        }.getOrDefault(false)

        if (!ok) {
            runCatching { tmp.delete() }
            return false
        }
        target.delete()
        if (tmp.renameTo(target)) return true
        return runCatching {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            true
        }.getOrDefault(false)
    }
}

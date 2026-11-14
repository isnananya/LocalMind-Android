package com.localmind.service

import android.util.Log
import com.localmind.data.LocalModel
import com.localmind.data.ModelCatalog
import com.localmind.data.Storage
import com.localmind.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

private const val TAG = "ModelDownloader"

enum class DownloadStatus { IDLE, QUEUED, RUNNING, PAUSED, VERIFYING, DONE, FAILED }

data class DownloadState(
    val modelId: String,
    val status: DownloadStatus = DownloadStatus.IDLE,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val bytesPerSecond: Double = 0.0,
    val source: String = "",
    val attempt: Int = 0,
    val error: String? = null,
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val speedText: String get() = Formatters.speed(bytesPerSecond)

    val etaText: String
        get() = if (totalBytes > downloadedBytes) Formatters.eta(totalBytes - downloadedBytes, bytesPerSecond) else ""
}

/**
 * Resumable GGUF downloader.
 *
 * * Writes to `<name>.part` then renames, so a half file is never treated as a model.
 * * Resumes with an HTTP `Range` header and falls back through the mirror list
 *   (hf-mirror -> huggingface -> ModelScope) keeping the bytes already on disk.
 */
class ModelDownloader(
    private val storage: Storage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val cancelled = mutableSetOf<String>()

    fun stateOf(modelId: String): DownloadState =
        _states.value[modelId] ?: DownloadState(modelId)

    fun start(model: LocalModel) {
        if (jobs[model.id]?.isActive == true) return
        cancelled.remove(model.id)
        update(model.id) { it.copy(status = DownloadStatus.QUEUED, error = null, attempt = 0) }
        jobs[model.id] = scope.launch { runDownload(model) }
    }

    fun pause(modelId: String) {
        cancelled += modelId
        jobs[modelId]?.cancel()
        update(modelId) { it.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0.0) }
    }

    fun cancel(modelId: String) {
        cancelled += modelId
        jobs[modelId]?.cancel()
        update(modelId) { it.copy(status = DownloadStatus.IDLE, bytesPerSecond = 0.0, downloadedBytes = 0, totalBytes = 0) }
    }

    fun clearError(modelId: String) = update(modelId) { it.copy(error = null, status = DownloadStatus.IDLE) }

    private fun update(id: String, block: (DownloadState) -> DownloadState) {
        // 同一个理由见 VoiceModelDownloader.update：并发下载时读-改-写必须互斥，
        // 否则后写的协程会用旧 map 覆盖别人的进度，进度条会跳。
        synchronized(stateLock) {
            val current = _states.value
            val state = current[id] ?: DownloadState(id)
            _states.value = current + (id to block(state))
        }
    }

    private val stateLock = Any()

    private fun stateOfInternal(modelId: String): DownloadState = stateOf(modelId)

    /**
     * 启用了「模型下载源」能力的插件所提供的额外镜像地址。
     *
     * 约定（写进插件清单的 `config`）：
     *  - `baseUrl`：镜像根地址，必填。末尾有没有 `/` 都行。
     *  - `filePattern`：可选。构造 URL 的方式，
     *    `{file}` 会被换成文件名，`{repo}` 换成仓库 id。
     *    不填就默认 `{baseUrl}/{file}`。
     *
     * 默认按**最后**尝试：插件地址是用户自己加的，可信度不如内置镜像，
     * 让内置的先跑，失败了再试插件的。想让它优先的话，插件可以把
     * `baseUrl` 直接填成内置源前面 —— 但默认不这么做。
     */
    private fun pluginMirrorsFor(model: LocalModel): List<String> {
        val plugins = runCatching { storage.loadPlugins() }.getOrDefault(emptyMap())
        return plugins.values
            .filter { it.isActive && com.localmind.data.PluginAbilities.MODEL_SOURCE.id in it.manifest.abilities }
            .mapNotNull { plugin ->
                val cfg = plugin.manifest.config
                val baseUrl = cfg["baseUrl"]?.trim()?.trimEnd('/')
                if (baseUrl.isNullOrBlank()) {
                    Log.w(TAG, "插件 ${plugin.manifest.id} 声明了模型下载源但没给 baseUrl，已跳过")
                    return@mapNotNull null
                }
                val pattern = cfg["filePattern"]?.takeIf { it.isNotBlank() }
                val url = if (pattern != null) {
                    pattern.replace("{baseUrl}", baseUrl)
                        .replace("{file}", model.fileName)
                        .replace("{repo}", model.repoId)
                } else {
                    "$baseUrl/${model.fileName}"
                }
                url.takeIf { it.startsWith("http") }
            }
    }

    private suspend fun runDownload(model: LocalModel) {        val target = File(storage.modelsDir, model.fileName)
        val part = File(storage.modelsDir, model.fileName + ".part")

        // Base URLs, then the same list run through the user's proxy if one is set.
        // A proxy entry is inserted BEFORE its direct counterpart when `preferProxy`
        // is on, so a working proxy wins without removing the direct fallback.
        val base = buildList {
            add(model.url)
            addAll(model.mirrors)
            // 插件提供的额外下载源。
            //
            // 这是"插件不只是能装、还真的起作用"的第一个落地点。插件的 config 里
            // 给一个 `baseUrl` 和可选的 `filePattern`，就成为一个新镜像：
            // 不需要改一行代码、不需要重新编译 APK 就能加源，这正是插件系统存在的意义。
            addAll(pluginMirrorsFor(model))
        }.filter { it.isNotBlank() }.distinct()

        val proxy = storage.loadProxySettings()
        val sources = if (proxy.isUsable()) {
            if (proxy.preferProxy) {
                base.flatMap { listOf(proxy.apply(it), it) }.distinct()
            } else {
                base.flatMap { listOf(it, proxy.apply(it)) }.distinct()
            }
        } else {
            base
        }

        if (sources.isEmpty()) {
            update(model.id) { it.copy(status = DownloadStatus.FAILED, error = "该条目没有可用下载地址，请用\u201c导入文件\u201d") }
            return
        }

        update(model.id) { it.copy(status = DownloadStatus.RUNNING) }

        var lastError: String? = null

        for ((index, url) in sources.withIndex()) {
            if (cancelled.contains(model.id)) return
            update(model.id) { it.copy(source = url, attempt = index + 1) }
            Log.i(TAG, "source ${index + 1}/${sources.size}: $url")
            val ok = try {
                // 先试多连接分段下载。
                //
                // 条件是"知道确切大小 + 服务器支持 Range"。探不到就退回单连接 ——
                // 单连接那条路径是久经使用的，不能因为要提速就把它拆了。
                val probed = MultiPartDownload.probe(client, url)
                if (probed != null && probed.rangeSupported && probed.totalBytes >= 8L * 1024 * 1024) {
                    val segments = MultiPartDownload.segmentsFor(probed.totalBytes)
                    Log.i(TAG, "  multipart: ${probed.totalBytes} bytes / $segments segments")
                    if (segments > 1) {
                        // 段目录与"拼好的中间文件"分开：段目录是断点续传的单位，
                        // 中间文件是给下面那段公共的 verify + rename 用的。
                        val segDir = MultiPartDownload.segmentDir(
                            storage.modelsDir, model.fileName, probed.totalBytes,
                        )
                        val assembled = File(storage.modelsDir, model.fileName + ".part")
                        val done = MultiPartDownload.download(
                            client = client,
                            scope = scope,
                            url = url,
                            target = segDir,
                            assembleTo = assembled,
                            totalBytes = probed.totalBytes,
                            segments = segments,
                            isCancelled = { cancelled.contains(model.id) },
                            onProgress = { got, total, bps ->
                                update(model.id) {
                                    it.copy(
                                        status = DownloadStatus.RUNNING,
                                        downloadedBytes = got,
                                        totalBytes = total,
                                        bytesPerSecond = bps,
                                    )
                                }
                            },
                        )
                        done
                    } else {
                        downloadFrom(url, part, model)
                    }
                } else {
                    downloadFrom(url, part, model)
                }
            } catch (t: Throwable) {
                if (!scope.isActive) return
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "source failed: $lastError", t)
                false
            }
            if (cancelled.contains(model.id)) return
            if (ok) {
                update(model.id) { it.copy(status = DownloadStatus.VERIFYING) }
                val verified = verify(part, model)
                if (verified == null) {
                    part.renameTo(target)
                    model.localPath = target.absolutePath
                    // ---- 顺带把视觉投影拉下来 ----
                    //
                    // 多模态模型是**两个文件**：主 GGUF + mmproj。用户点一次下载
                    // 却只拿到一半，结果就是"发了图它说没看见" —— 那正是识图
                    // 一直用不起来的直接原因之一。所以这里一起下。
                    //
                    // mmproj 失败**不算主模型失败**：主模型已经可用了，
                    // 只是识图保持关闭（界面会说明原因）。
                    runMmprojDownload(model, sources)

                    update(model.id) {
                        it.copy(
                            status = DownloadStatus.DONE,
                            downloadedBytes = target.length(),
                            totalBytes = target.length(),
                            bytesPerSecond = 0.0,
                            error = null,
                        )
                    }
                    return
                } else {
                    lastError = verified
                    // size mismatch: restart this source from scratch
                    part.delete()
                }
            } else {
                lastError = lastError ?: "连接失败"
            }
        }

        update(model.id) {
            it.copy(
                status = DownloadStatus.FAILED,
                bytesPerSecond = 0.0,
                error = "全部下载源均失败：${lastError ?: "未知错误"}（已保留 ${Formatters.size(part.length())} 断点）",
            )
        }
    }

    /**
     * 下载配套的视觉投影（mmproj）。
     *
     * 地址来自模型条目自己的 mmprojUrl（清单里给的是 hf-mirror，和主模型同一套镜像策略）。
     * 断点、代理、大小校验都走和主文件一样的 [downloadFrom]，不再写第二套。
     */
    private suspend fun runMmprojDownload(model: LocalModel, mainSources: List<String>) {
        val name = model.mmprojFileName
        if (name.isBlank()) return

        val target = File(storage.modelsDir, name)
        val expect = model.mmprojSizeBytes
        if (target.isFile && target.length() > 0 && (expect <= 0 || target.length() == expect)) return

        // 把 mmproj 的地址塞进"和主模型同源的镜像"里：主模型的 url 换成 mmproj
        // 文件名就是它在同一个仓库里的地址（清单里两类文件同仓库）。
        val urls = buildList {
            add(model.mmprojUrl)
            if (model.mmprojUrl.isNotBlank()) addAll(model.mmprojMirrors)
            for (s in mainSources) {
                val guess = s.substringBeforeLast('/') + "/" + name
                if (guess.startsWith("http") && !guess.contains("?download=true")) add(guess)
            }
        }.filter { it.isNotBlank() }.distinct()

        val part = File(storage.modelsDir, name + ".part")
        for (url in urls) {
            if (cancelled.contains(model.id)) return
            Log.i(TAG, "mmproj <- $url")
            val ok = runCatching { downloadFrom(url, part, model) }
                .getOrElse { Log.w(TAG, "mmproj 源失败：${it.message}"); false }
            if (cancelled.contains(model.id)) return
            if (ok && part.length() > 0) {
                if (expect > 0 && part.length() != expect) {
                    Log.w(TAG, "mmproj 大小不对：${part.length()} != $expect，重来")
                    part.delete()
                    continue
                }
                part.renameTo(target)
                Log.i(TAG, "mmproj 就位：$name（${target.length()} 字节）")
                return
            }
        }
        Log.w(TAG, "mmproj 没下下来：$name —— 识图会保持关闭，其余功能不受影响")
    }

    /** @return true when the file for this source is complete. */
    private fun downloadFrom(url: String, part: File, model: LocalModel): Boolean {
        val existingRaw = if (part.exists()) part.length() else 0L
        val builder = Request.Builder().url(url).header("User-Agent", "LocalMind/1.0 (Android)")
        if (existingRaw > 0) builder.header("Range", "bytes=$existingRaw-")

        Log.i(TAG, "GET $url (existing=$existingRaw)")

        client.newCall(builder.build()).execute().use { response ->
            Log.i(
                TAG,
                "  -> HTTP ${response.code} len=${response.body?.contentLength() ?: -1} " +
                    "acceptRanges=${response.header("Accept-Ranges")} host=${response.request.url.host}",
            )

            // hf-mirror 302-redirects model files to cas-bridge.xethub.hf.co, a CDN that
            // answers 200 with the WHOLE file even when we sent a Range header. The old
            // code deleted the partial file and recursed, so a large download restarted
            // from zero every time and never finished. Instead: if the server insists on
            // sending everything, truncate and consume the full body in THIS response.
            var existing = existingRaw
            if (existing > 0 && response.code == 200) {
                Log.i(TAG, "  server ignored Range -> restarting this file from byte 0 in place")
                part.delete()
                existing = 0
            }
            if (!response.isSuccessful && response.code != 206) {
                throw IllegalStateException("HTTP ${response.code}")
            }

            // A 206 must start exactly where we asked it to. Some CDNs happily answer
            // 206 while sending a different slice; appending that to the partial file
            // would silently produce a corrupt model that only fails much later at load
            // time (verify() compares sizes, not content, so it cannot catch this).
            // Treat a wrong offset as this source failing so the next mirror is tried.
            val declaredStart = response.header("Content-Range")
                ?.substringAfter("bytes ", "")
                ?.substringBefore('-')
                ?.trim()
                ?.toLongOrNull()
            if (existingRaw > 0 && response.code == 206 && declaredStart != null &&
                declaredStart != existingRaw
            ) {
                throw IllegalStateException(
                    "续传偏移不匹配：请求 $existingRaw，服务器从 $declaredStart 开始",
                )
            }

            val body = response.body ?: throw IllegalStateException("响应为空")
            val contentLength = body.contentLength()
            val total = if (contentLength > 0) {
                if (response.code == 206) existing + contentLength else contentLength
            } else {
                model.sizeBytes
            }

            part.parentFile?.mkdirs()
            val raf = RandomAccessFile(part, "rw")
            raf.seek(existing)

            var written = existing
            val buffer = ByteArray(256 * 1024)
            var windowBytes = 0L
            var windowStart = System.currentTimeMillis()
            var speed = 0.0

            body.byteStream().use { input ->
                while (true) {
                    if (cancelled.contains(model.id)) break
                    val read = input.read(buffer)
                    if (read <= 0) break
                    raf.write(buffer, 0, read)
                    written += read
                    windowBytes += read

                    val now = System.currentTimeMillis()
                    val elapsed = now - windowStart
                    if (elapsed >= 500) {
                        val instant = windowBytes * 1000.0 / elapsed
                        speed = if (speed <= 0.0) instant else speed * 0.6 + instant * 0.4
                        windowBytes = 0
                        windowStart = now
                        update(model.id) {
                            it.copy(
                                status = DownloadStatus.RUNNING,
                                downloadedBytes = written,
                                totalBytes = if (total > 0) total else model.sizeBytes,
                                bytesPerSecond = speed,
                            )
                        }
                    }
                }
            }
            raf.close()

            update(model.id) {
                it.copy(
                    status = DownloadStatus.RUNNING,
                    downloadedBytes = written,
                    totalBytes = if (total > 0) total else written,
                    bytesPerSecond = speed,
                )
            }

            if (cancelled.contains(model.id)) return false
            val expected = if (total > 0) total else model.sizeBytes
            return expected <= 0 || written >= expected || written == model.sizeBytes
        }
    }

    /** @return null when OK, otherwise a human readable reason. */
    private fun verify(file: File, model: LocalModel): String? {
        if (!file.exists() || file.length() == 0L) return "下载文件为空"
        if (model.sizeBytes > 0 && file.length() != model.sizeBytes) {
            return "大小不符：期望 ${Formatters.size(model.sizeBytes)}，实际 ${Formatters.size(file.length())}"
        }
        // A GGUF starts with the magic "GGUF".
        val magic = ByteArray(4)
        runCatching {
            file.inputStream().use { it.read(magic) }
        }
        if (!(magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte())
        ) {
            return "文件头不是 GGUF（可能下到了网页或分片文件）"
        }
        return null
    }

    /**
     * Resolves `owner/repo` (optionally with a file name) into a concrete model.
     * Used by "paste a Hugging Face id / URL" and by the API's /v1/models/import.
     */
    fun resolveRemote(repoInput: String, preferredFile: String? = null): LocalModel? {
        val parsed = ModelCatalog.parseRepoInput(repoInput) ?: return null
        val (repo, fileInUrl) = parsed
        val fileName = preferredFile?.takeIf { it.isNotBlank() } ?: fileInUrl
        val candidates = hfFileCandidates(repo, fileName)
        val chosen = candidates.firstOrNull { headOk(it) } ?: return null
        val size = headSize(chosen)
        val plainName = chosen.substringAfterLast('/')
        return LocalModel(
            id = "remote-" + repo.replace('/', '-') + "-" + plainName.hashCode().toString(16),
            name = plainName.removeSuffix(".gguf"),
            repoId = repo,
            fileName = plainName,
            sizeBytes = size,
            url = chosen,
            mirrors = listOf(hfOriginal(chosen)),
            license = "见原始仓库",
            publisher = repo.substringBefore('/'),
            family = "GGUF",
            quant = Regex("(?i)(q\\d+_k_[msl]|q\\d+_\\d|iq\\d+_\\w+|f16|f32|bf16)")
                .find(plainName)?.value?.uppercase() ?: "",
            description = "从 $repo 导入",
            tags = listOf("远程导入"),
            recommendedRamGb = ((size / (1024L * 1024 * 1024)).toInt() + 2).coerceAtLeast(2),
            imported = true,
        )
    }

    private fun hfFileCandidates(repo: String, fileName: String?): List<String> {
        val base = "${ModelCatalog.HF}/$repo/resolve/main/"
        if (!fileName.isNullOrBlank()) {
            if (fileName.startsWith("http")) return listOf(fileName)
            return listOf(base + fileName)
        }
        // Ask the HF API for the .gguf siblings.
        return runCatching {
            val api = if (repo.contains(" ")) null else "https://hf-mirror.com/api/models/$repo"
            val request = Request.Builder().url(api!!).header("User-Agent", "LocalMind/1.0").build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                Regex("\"rfilename\"\\s*:\\s*\"([^\"]+\\.gguf)\"").findAll(text)
                    .map { it.groupValues[1] }
                    .filter { it.contains("q4_k_m", true) || it.contains("q8_0", true) }
                    .sortedBy { it.length }
                    .toList()
                    .ifEmpty {
                        Regex("\"rfilename\"\\s*:\\s*\"([^\"]+\\.gguf)\"").findAll(text)
                            .map { it.groupValues[1] }.toList()
                    }
            }
        }.getOrDefault(emptyList()).map { base + it }
    }

    private fun hfOriginal(mirrorUrl: String) = mirrorUrl.replace(ModelCatalog.HF, ModelCatalog.HF_ORIGINAL)

    private fun headOk(url: String): Boolean = runCatching {
        val request = Request.Builder().url(url).head().header("User-Agent", "LocalMind/1.0").build()
        client.newCall(request).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun headSize(url: String): Long = runCatching {
        val request = Request.Builder().url(url).head().header("User-Agent", "LocalMind/1.0").build()
        client.newCall(request).execute().use { resp ->
            resp.header("Content-Length")?.toLongOrNull()
                ?: resp.body?.contentLength()?.takeIf { it > 0 }
                ?: 0L
        }
    }.getOrDefault(0L)

    /** Used by the "test connectivity" row in Settings. */
    fun probeMirrors(): Map<String, Boolean> {
        val targets = linkedMapOf(
            "hf-mirror.com" to "${ModelCatalog.HF}/",
            "modelscope.cn" to "https://www.modelscope.cn/",
            "huggingface.co" to "${ModelCatalog.HF_ORIGINAL}/",
        )
        return targets.mapValues { (_, url) -> runCatching {
            val request = Request.Builder().url(url).head().header("User-Agent", "LocalMind/1.0").build()
            client.newCall(request).execute().use { it.isSuccessful || it.code in 300..499 }
        }.getOrDefault(false) }
    }
}

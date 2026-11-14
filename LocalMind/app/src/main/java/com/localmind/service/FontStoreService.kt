package com.localmind.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在线字体商店。
 *
 * ## 和主题商店的区别
 *
 * 主题包是一整个 zip，要走插件安装流程；字体就是**一个 TTF 文件**，
 * 没有代码、没有目录结构，所以下载完直接落到 `filesDir/fonts/<id>.ttf`
 * 交给 [com.localmind.ui.theme.FontRegistry] 解析即可。
 *
 * 体积差一个量级：主题包 1–2 MB，中文字体 2–20 MB。所以这里
 *  * 超时更宽（字体下到一半被掐很常见）；
 *  * 必须显示进度（几 MB 不给进度用户会以为卡死）；
 *  * 下载失败重试的是**下一个镜像**，不是同一个地址。
 *
 * ## 数据来源
 *
 * `data/fonts.json` —— 和 themes.json 一样放在公开仓库，App 匿名拉。
 * 清单只描述元数据 + 地址；字体文件本身放在 `data/fonts/`。
 */
class FontStoreService(private val context: Context) {

    data class FontEntry(
        val id: String,
        val name: String,
        val author: String = "",
        val description: String = "",
        val style: String = "",
        val sample: String = "",
        val version: String = "",
        val fileUrl: String = "",
        val previewUrl: String = "",
        val sizeBytes: Long = 0,
        val tags: List<String> = emptyList(),
        val mirrors: List<String> = emptyList(),
        val previewMirrors: List<String> = emptyList(),
        /** 是不是随 APK 附带的字体（目前没有，留字段给以后）。 */
        val builtin: Boolean = false,
    ) {
        val isUsable: Boolean get() = id.isNotBlank() && name.isNotBlank() && (fileUrl.isNotBlank() || builtin)

        val fileUrls: List<String>
            get() = candidateUrls(listOf(fileUrl) + mirrors)

        val previewUrls: List<String>
            get() = candidateUrls(listOf(previewUrl) + previewMirrors)

        /**
         * 展开候选地址：**Pages → 加速源 → 直连**，并截断。
         *
         * 顺序现在由 [GitHubMirror.mirrors] 内部统一决定（Pages 第一位），
         * 这里只负责合并去重 —— 之前在外层 `.sortedBy{ github.io 优先 }` 是
         * 因为当时清单里手写了 Pages 地址，现在任何 raw 地址都能自己推导出
         * 对应的 Pages 地址，不必再依赖清单。
         *
         * 截断也不是省事：清单里每条地址原本又配了 4 个镜像，乘上每个镜像
         * 展开的加速源，一条字体最多能拼出 35 个候选。全都不通时一张卡要
         * 转四分多钟 —— 用户看到的就是"预览永远空白"。现在只留前
         * [MAX_URLS] 个，配上磁盘缓存，第二次打开是秒出。
         */
        private fun candidateUrls(raw: List<String>): List<String> =
            raw.filter { it.isNotBlank() }
                .flatMap { GitHubMirror.mirrors(it) }
                .distinct()
                .take(MAX_URLS)

        private companion object {
            /**
             * 单条字体最多试这么多个候选地址。
             *
             * 实测第一个（Pages）就成功，正常情况只发 1 次请求；这个上限只
             * 在 Pages 404 且加速源抽风时才会用到。4 个已经是"3 个源都不通
             * 还能轮询到第 4 个"的余量，再多只是加剧串行超时。
             */
            const val MAX_URLS = 4
        }
    }

    private companion object {
        const val TAG = "FontStore"
        const val CACHE_FILE = "fonts.json"
        val ID_PATTERN = Regex("^[A-Za-z0-9._-]+$")

        /**
         * 字体二进制的大小上限。
         *
         * 字库里的中文字体最大 26 MB（【肆柒】布朗尼），80 MB 是给未来更大的
         * 字族留的余量。这个上限的作用不是防用户，是防**镜像返回一个几百 MB
         * 的奇怪文件**把手机存储写满。
         */
        const val MAX_FONT_BYTES = 80L * 1024 * 1024

        /** 清单的候选地址：统一由 GitHubMirror 给出（加速源在前）。 */
        val BASES = GitHubMirror.DATA_BASES
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 字体下载：慢一点但不能中途断，读超时给到 40 秒。 */
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val dir = File(context.filesDir, "remote_data").apply { mkdirs() }

    private val _fonts = MutableStateFlow<List<FontEntry>>(emptyList())
    val fonts: StateFlow<List<FontEntry>> = _fonts

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError

    /** 已下载到本机的字体 id。 */
    fun installedIds(): Set<String> =
        com.localmind.ui.theme.FontRegistry.installedIds(context)

    fun localFile(entry: FontEntry): File =
        com.localmind.ui.theme.FontRegistry.fontFile(context, entry.id)

    fun cached(entry: FontEntry): File? =
        localFile(entry).takeIf { it.isFile && it.length() > 1024 }

    fun delete(entry: FontEntry): Boolean =
        com.localmind.ui.theme.FontRegistry.delete(context, entry.id)

    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        _loading.value = true
        try {
            if (_fonts.value.isEmpty()) {
                readCache()?.let { _fonts.value = it }
            }
            val body = fetch(CACHE_FILE)
            if (body == null) {
                if (_fonts.value.isEmpty()) _lastError.value = "连不上字体服务器，检查网络后重试"
                return@withContext
            }
            val parsed = parse(body)
            if (parsed.isEmpty()) {
                if (_fonts.value.isEmpty()) _lastError.value = "字体清单为空或格式不对"
                return@withContext
            }
            _fonts.value = parsed
            _lastError.value = ""
            runCatching { File(dir, CACHE_FILE).writeText(body) }
            Log.i(TAG, "拉取到字体清单：${parsed.size} 款")
        } finally {
            _loading.value = false
        }
    }

    /**
     * 下载字体文件。**自动挑源 + 多连接分段**，失败返回 null。
     *
     * 字体平均 9 MB、大的 26 MB，走公共代理单连接经常只有几十 KB/s ——
     * 一支字体要转好几分钟，用户以为卡死了。这里交给 [FastDownload]：
     * 先并行探测所有候选源、按实测延迟排序，再开 8 条连接分段拉。
     *
     * 校验：文件头必须是 TTF/OTF/TTC（`00 01 00 00` / `OTTO` / `ttcf`），
     * 大小和清单一致（清单声明了的话）。镜像返回的 HTML 错误页会被挡掉 ——
     * 否则会把一个 2 KB 的网页存成 .ttf，然后字体解析永远失败。
     */
    suspend fun download(entry: FontEntry, onProgress: (Long, Long) -> Unit = { _, _ -> }): File? =
        withContext(Dispatchers.IO) {
            cached(entry)?.let {
                onProgress(it.length(), it.length())
                return@withContext it
            }
            val target = localFile(entry)
            val ok = FastDownload.fetch(
                client = downloadClient,
                urls = entry.fileUrls,
                target = target,
                segmentRoot = target.parentFile ?: dir,
                maxBytes = MAX_FONT_BYTES,
                accept = { f ->
                    isFontFile(f) && (entry.sizeBytes <= 0 || f.length() == entry.sizeBytes)
                },
                onProgress = onProgress,
            )
            if (ok) target else null
        }

    private fun isFontFile(f: File): Boolean {
        if (!f.isFile || f.length() < 1024) return false
        val head = ByteArray(4)
        val n = runCatching { f.inputStream().use { it.read(head) } }.getOrDefault(0)
        if (n < 4) return false
        val tag = String(head, Charsets.US_ASCII)
        return tag == "OTTO" || tag == "ttcf" ||
            (head[0] == 0.toByte() && head[1] == 1.toByte())
    }

    private fun parse(text: String): List<FontEntry> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("fonts") ?: root.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<FontEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            fun urlList(key: String): List<String> =
                o.optJSONArray(key)?.let { a ->
                    (0 until a.length()).mapNotNull { idx ->
                        a.optString(idx).trim().take(300)
                            .takeIf { u -> u.startsWith("http://") || u.startsWith("https://") }
                    }
                }.orEmpty()
            val entry = FontEntry(
                id = o.optString("id").trim(),
                name = o.optString("name").trim().take(40),
                author = o.optString("author").trim().take(40),
                description = o.optString("description").trim().take(160),
                style = o.optString("style").trim().take(20),
                sample = o.optString("sample").trim().take(60),
                version = o.optString("version").trim().take(20),
                fileUrl = o.optString("fileUrl").trim(),
                previewUrl = o.optString("previewUrl").trim(),
                sizeBytes = o.optLong("sizeBytes", 0L),
                tags = o.optJSONArray("tags")?.let { ta ->
                    (0 until ta.length()).mapNotNull { ta.optString(it).take(16).takeIf { s -> s.isNotBlank() } }
                } ?: emptyList(),
                mirrors = urlList("mirrors"),
                previewMirrors = urlList("previewMirrors"),
            )
            if (!entry.isUsable) continue
            if (entry.id.isEmpty() || entry.id.length > 80 || !ID_PATTERN.matches(entry.id)) continue
            if (!entry.builtin && !entry.fileUrl.startsWith("http")) continue
            out.add(entry)
        }
        return out
    }

    private fun readCache(): List<FontEntry>? {
        val f = File(dir, CACHE_FILE)
        if (!f.isFile) return null
        return runCatching { parse(f.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun fetch(name: String): String? {
        val t = System.currentTimeMillis()
        for (base in BASES) {
            val req = Request.Builder()
                .url("$base/$name?t=$t")
                .header("Cache-Control", "no-cache")
                .build()
            val body = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    resp.body?.string()?.takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (body != null) return body
        }
        return null
    }
}

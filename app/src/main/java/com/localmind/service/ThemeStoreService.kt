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
 * 在线主题商店。
 *
 * ## 为什么单独一个服务，而不是塞进 [RemoteDataService]
 *
 * [RemoteDataService] 拉的是"应用自己的数据"（公告、捐献榜、版本号）——
 * 那几份 JSON 由**作者自己**维护，格式是定死的。
 *
 * 主题商店不一样：**主题包是第三方内容**。用户可能装任何来源的主题包，
 * 所以这里必须做到：
 *
 *  1. **拉清单和下载包分开**。清单只是元数据（名字、作者、预览图、下载地址），
 *     包本身是 zip。清单被篡改最多让用户看到一个坏名字，不会执行任何东西。
 *  2. **下载的包走和插件一样的安装流程**（见 `PluginInstall`）。也就是说，
 *     一个从网上来的主题包，享受和本地插件**完全一样**的校验 ——
 *     路径白名单、zip-slip 防护、代码类插件要用户确认。
 *  3. **清单本身也不信任**。字段全走白名单解析，URL 只允许 http/https，
 *     主题 id 必须符合命名规则。
 *
 * ## 多源策略
 *
 * 和 [RemoteDataService] 一样：GitHub Pages → jsDelivr → raw 依次回退。
 * 国内网络下 raw 经常被墙。
 *
 * ## 缓存
 *
 * 拉到就写本地缓存。下次冷启动先吃缓存立刻上屏，再后台刷新。
 * 网络挂了 = 用上次的清单，不会让主题页变成空白。
 */
class ThemeStoreService(private val context: Context) {

    private companion object {
        const val TAG = "ThemeStore"
        const val CACHE_FILE = "themes.json"

        /** 内置主题的 assets 路径前缀。清单里只能引用这个前缀下的文件。 */
        const val BUILTIN_ASSET_PREFIX = "builtin/themes/"

        /**
         * 清单地址的回退链。
         *
         * 前三个是官方直连（最快，但国内常常被墙）：
         *  - GitHub Pages：作者自己的域名，走 CDN
         *  - jsDelivr：jsDelivr 会缓存 GitHub 内容
         *  - raw：GitHub 原始地址
         *
         * 后三个是 **ghproxy 镜像**（把官方地址套一层代理）。
         * 这不是"备用凑数"—— 实测国内网络下前三个全挂、只有镜像通，
         * 所以镜像必须排在后面但一定要有。
         */
        val BASES = listOf(
            "https://isnananya.github.io/LocalMind-Data/data",
            "https://cdn.jsdelivr.net/gh/isnananya/LocalMind-Data@main/data",
            "https://raw.githubusercontent.com/isnananya/LocalMind-Data/main/data",
            "https://ghproxy.net/https://raw.githubusercontent.com/isnananya/LocalMind-Data/main/data",
            "https://gh-proxy.com/https://raw.githubusercontent.com/isnananya/LocalMind-Data/main/data",
            "https://ghproxy.net/https://isnananya.github.io/LocalMind-Data/data",
        )
    }

    /**
     * 一条主题包记录。
     *
     * @param id 主题包 id。必须是合法包名（字母数字点横线），因为它会被拼成目录名。
     * @param name 显示名。
     * @param author 作者。
     * @param description 一句话说明。
     * @param version 版本号。
     * @param downloadUrl 主题包 zip 的下载地址。http/https。
     * @param previewUrl 预览图地址（可空）。用来在列表里画缩略图。
     * @param sizeBytes 包大小（可空）。用来显示"多少 MB"。
     * @param tags 标签，比如 "二次元" / "极简" / "暗色"。用来筛选。
     * @param builtinAsset 内置主题的 assets 路径（可空）。非空表示这个主题**随 APK 附带**，
     *   不需要联网下载 —— 见 [download]。
     * @param previewAsset 内置主题预览图的 assets 路径（可空）。和 [builtinAsset] 一样
     *   从 APK 直接读，所以离线也能显示缩略图。
     * @param mirrors 主题包的备用下载地址。国内 raw / Pages / jsDelivr 经常被墙，
     *   按 [downloadUrl] → [mirrors] → [githubMirrors] 自动展开的镜像依次尝试。
     * @param previewMirrors 预览图的备用地址。图挂了不该拦住主题下载，所以单独一套。
     */
    data class ThemeEntry(
        val id: String,
        val name: String,
        val author: String = "",
        val description: String = "",
        val version: String = "",
        val downloadUrl: String = "",
        val previewUrl: String = "",
        val sizeBytes: Long = 0,
        val tags: List<String> = emptyList(),
        val builtinAsset: String = "",
        val previewAsset: String = "",
        val mirrors: List<String> = emptyList(),
        val previewMirrors: List<String> = emptyList(),
    ) {
        val isBuiltin: Boolean get() = builtinAsset.isNotBlank()

        /** 能不能装：要么有下载地址，要么是内置主题。 */
        val isUsable: Boolean
            get() = id.isNotBlank() && name.isNotBlank() &&
                (downloadUrl.isNotBlank() || isBuiltin)

        /** 预览图的所有候选地址（去重、已过滤协议）。 */
        val previewUrls: List<String>
            get() = (listOf(previewUrl) + previewMirrors)
                .flatMap { u -> listOf(u) + githubMirrorsFor(u) }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()

        /** 主题包的所有候选地址（去重、已过滤协议）。 */
        val packageUrls: List<String>
            get() = (listOf(downloadUrl) + mirrors)
                .flatMap { u -> listOf(u) + githubMirrorsFor(u) }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * 主题包下载专用 client。
     *
     * 实测 ghproxy 拉 1.7 MB 的主题包要 ~32 秒，而且中间会有长时间没有
     * 数据到达的空档。清单和预览图用短超时（快速失败换下一个源），
     * 大包下载必须给宽一点，否则会在"马上就好了"的时候被掐断。
     */
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val dir = File(context.filesDir, "remote_data").apply { mkdirs() }

    private val _themes = MutableStateFlow<List<ThemeEntry>>(emptyList())
    val themes: StateFlow<List<ThemeEntry>> = _themes

    /**
     * 构造时就装入内置主题。
     *
     * 内置主题不依赖网络，所以**冷启动第一屏就有东西可看** —— 不用等
     * refresh 跑完（那可能要十几秒超时）。在线主题随后追加。
     */
    init {
        _themes.value = builtinThemes()
    }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /** 上次拉取失败的原因。界面上如实显示，别假装成功。 */
    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError

    /**
     * 刷新主题清单。
     *
     * 先上缓存，再拉网络。任何一个失败都不抛 —— 主题商店挂了不该影响
     * 其它功能（本地主题包照样能用）。
     *
     * **内置主题始终在列**：网络挂掉时商店不能变成空白，否则用户会以为
     * 功能坏了。内置项标记 [ThemeEntry.isBuiltin]，界面上要如实区分
     * （"内置" vs "在线"），因为内置的下载其实是从 APK 里复制。
     */
    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        _loading.value = true
        try {
            // 1) 缓存先上屏
            if (_themes.value.isEmpty()) {
                readCache()?.let { cached ->
                    _themes.value = mergeBuiltin(cached)
                    Log.i(TAG, "使用缓存的主题清单：${cached.size} 条")
                }
            }

            // 2) 网络刷新
            val body = fetch(CACHE_FILE)
            if (body == null) {
                // 网络不通：至少把内置主题摆出来，别让商店是空的。
                val onlyBuiltin = _themes.value.all { it.isBuiltin }
                if (onlyBuiltin) _lastError.value = "连不上主题服务器，先给你看内置主题"
                return@withContext
            }
            val parsed = parse(body)
            if (parsed.isEmpty()) {
                if (_themes.value.isEmpty()) _lastError.value = "主题清单为空或格式不对"
                return@withContext
            }
            _themes.value = mergeBuiltin(parsed)
            _lastError.value = ""
            runCatching { File(dir, CACHE_FILE).writeText(body) }
            Log.i(TAG, "拉取到主题清单：${parsed.size} 条")
        } finally {
            _loading.value = false
        }
    }

    /**
     * 把内置主题并进清单。
     *
     * 同 id 时**在线版本优先**（更新的主题应该从服务器拿），但在线的缺失
     * 会补上内置的。顺序：在线在前，内置在后。
     */
    private fun mergeBuiltin(remote: List<ThemeEntry>): List<ThemeEntry> {
        val builtins = builtinThemes()
        val builtinById = builtins.associateBy { it.id }
        // 同 id 时在线版本优先（走 GitHub 下载新版），但把内置那份的
        // previewAsset 借过来当**离线预览兜底**：清单挂了、图片 CDN 挂了，
        // 缩略图照样能画出来，不会出现一整排灰方块。
        val merged = remote.map { r ->
            val b = builtinById[r.id]
            if (b != null && r.previewAsset.isBlank()) r.copy(previewAsset = b.previewAsset) else r
        }
        val remoteIds = remote.map { it.id }.toSet()
        return merged + builtins.filter { it.id !in remoteIds }
    }

    /**
     * 内置主题清单。
     *
     * 从 APK 的 `assets/builtin/themes/manifest.json` 读 —— 清单放在文件里
     * 而不是硬编码在 Kotlin 里，好处是**加一个内置主题不用改代码**：
     * 打个 zip 进 assets、把 manifest 改一行就行。
     *
     * 读不到就返回空列表（不该因此崩，但会在界面上没有内置主题）。
     */
    private fun builtinThemes(): List<ThemeEntry> {
        val raw = runCatching {
            context.assets.open("builtin/themes/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return emptyList()
        return parse(raw).filter { it.isBuiltin }
    }

    /** 下载一个主题包到临时文件。返回文件路径；失败返回 null。 */
    suspend fun download(entry: ThemeEntry, onProgress: (Long, Long) -> Unit = { _, _ -> }): File? =
        withContext(Dispatchers.IO) {
            val target = File(context.cacheDir, "theme_${entry.id}.zip")

            // 内置主题：直接从 APK 复制，不用联网。
            if (entry.isBuiltin) {
                return@withContext copyBuiltin(entry.builtinAsset, target, onProgress, entry.name)
            }

            // GitHub 直连在国内常常不通。把清单里的主地址、备用地址、以及
            // 自动展开的 ghproxy 镜像排成一队，逐个试 —— 一个挂了不影响下一个。
            // 进度回调按「当前这个源」汇报，换源时从 0 重新开始是如实反映。
            val urls = entry.packageUrls
            if (urls.isEmpty()) {
                Log.w(TAG, "没有合法的下载地址：${entry.id}")
                return@withContext null
            }
            val tmp = File(context.cacheDir, "theme_${entry.id}.zip.part")
            target.delete()
            for ((index, url) in urls.withIndex()) {
                val ok = runCatching {
                    tmp.delete()
                    val req = Request.Builder().url(url).build()
                    downloadClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.w(TAG, "源 ${index + 1}/${urls.size} HTTP ${resp.code}：$url")
                            false
                        } else {
                            val total = resp.body?.contentLength() ?: -1L
                            resp.body?.byteStream()?.use { input ->
                                tmp.outputStream().use { out ->
                                    val buf = ByteArray(64 * 1024)
                                    var written = 0L
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        out.write(buf, 0, n)
                                        written += n
                                        onProgress(written, total)
                                    }
                                }
                            }
                            tmp.length() > 0
                        }
                    }
                }.getOrDefault(false)
                if (ok && tmp.renameTo(target)) {
                    Log.i(TAG, "主题包下载成功（源 ${index + 1}/${urls.size}）：${entry.name}")
                    return@withContext target
                }
            }
            runCatching { tmp.delete() }
            Log.w(TAG, "所有源都失败了：${entry.name}（共 ${urls.size} 个）")

            // 最后一招：如果这个主题在 APK 里也附带了一份，就用本地副本。
            // 在线清单和内置清单同 id 时这很常见 —— 网络全挂也别让用户
            // 对着一个"下载失败"发呆，主题本来就在手机上。
            val fallback = builtinThemes().firstOrNull { it.id == entry.id }
            if (fallback != null) {
                Log.i(TAG, "改用随 APK 附带的副本：${entry.name}")
                return@withContext copyBuiltin(fallback.builtinAsset, target, onProgress, entry.name)
            }
            null
        }

    /** 从 APK assets 复制一个主题包到缓存文件。 */
    private fun copyBuiltin(
        asset: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
        name: String,
    ): File? = runCatching {
        target.delete()
        val total = runCatching { context.assets.openFd(asset).use { it.length } }.getOrDefault(0L)
        context.assets.open(asset).use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var written = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                    onProgress(written, if (total > 0) total else written)
                }
            }
        }
        target.takeIf { it.isFile && it.length() > 0 }
    }.getOrElse { t ->
        Log.w(TAG, "读取内置主题失败：$name", t)
        null
    }

    // ------------------------------------------------------------- 解析

    /**
     * 解析清单 JSON。
     *
     * ⚠️ **这是第三方内容**，每一条都要过白名单：
     *  - id 必须符合包名规则（后面要拼成目录名）
     *  - 下载地址只允许 http/https
     *  - 名字、说明有长度上限
     *
     * 解析不出来的条目**跳过**，而不是让整个清单失败。
     *
     * `builtinAsset` 是唯一的例外：它指向 **APK 自己的 assets**，不来自网络，
     * 所以清单里写它没有安全风险（读不到就跳过）。但路径仍然要限定在
     * `builtin/themes/` 下面，防止清单指向 APK 里的任意文件。
     */
    private fun parse(text: String): List<ThemeEntry> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("themes") ?: root.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<ThemeEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val builtinRaw = o.optString("builtinAsset").trim()
            val builtin = builtinRaw
                .takeIf { it.startsWith(BUILTIN_ASSET_PREFIX) }
                ?.takeIf { !it.contains("..") }
                ?.take(120)
                ?: ""
            // 预览图同样只允许指向 APK 自己 assets 的 builtin/themes/ 目录。
            val previewAsset = o.optString("previewAsset").trim()
                .takeIf { it.startsWith(BUILTIN_ASSET_PREFIX) }
                ?.takeIf { !it.contains("..") }
                ?.take(120)
                .orEmpty()
            fun urlList(key: String): List<String> =
                o.optJSONArray(key)?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        a.optString(i).trim().take(300)
                            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    }
                }.orEmpty()
            val entry = runCatching {
                ThemeEntry(
                    id = o.optString("id").trim(),
                    name = o.optString("name").trim().take(60),
                    author = o.optString("author").trim().take(40),
                    description = o.optString("description").trim().take(200),
                    version = o.optString("version").trim().take(20),
                    downloadUrl = o.optString("downloadUrl").trim(),
                    previewUrl = o.optString("previewUrl").trim(),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    tags = o.optJSONArray("tags")?.let { ta ->
                        (0 until ta.length()).mapNotNull { ta.optString(it).take(20).takeIf { s -> s.isNotBlank() } }
                    } ?: emptyList(),
                    builtinAsset = builtin,
                    previewAsset = previewAsset,
                    mirrors = urlList("mirrors"),
                    previewMirrors = urlList("previewMirrors"),
                )
            }.getOrNull() ?: continue

            if (!entry.isUsable) continue
            if (!isValidThemeId(entry.id)) {
                Log.w(TAG, "跳过 id 不合法的条目：${entry.id}")
                continue
            }
            // 有下载地址就必须合法；内置主题没有下载地址，跳过这条校验。
            if (!entry.isBuiltin &&
                !entry.downloadUrl.startsWith("http://") && !entry.downloadUrl.startsWith("https://")
            ) {
                Log.w(TAG, "跳过下载地址不合法的条目：${entry.id}")
                continue
            }
            out.add(entry)
        }
        return out
    }

    /**
     * 主题包 id 的合法性。
     *
     * 和插件 id 用同一套规则（字母数字、点、横线、下划线）——
     * 因为 id 会被拼成 `plugins/<id>/assets` 目录名。
     */
    private fun isValidThemeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 80 && ID_PATTERN.matches(id)

    private fun readCache(): List<ThemeEntry>? {
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

    private val ID_PATTERN = Regex("^[A-Za-z0-9._-]+$")
}

/** 允许被套 ghproxy 的域名白名单。第三方任意地址不在此列，不会被中转。 */
private val GH_HOSTS = listOf(
    "raw.githubusercontent.com",
    "github.com",
    "cdn.jsdelivr.net",
    "isnananya.github.io",
)

/**
 * 把一个 GitHub 系地址展开成国内可达的镜像。
 *
 * 实测（2026-11）：`github.com` / `raw.githubusercontent.com` /
 * `*.github.io` / `cdn.jsdelivr.net` 在国内网络下 **http=000 直接不通**，
 * 而 `ghproxy.net` / `gh-proxy.com` 能正常 200。所以清单里写标准 GitHub
 * 地址（国外用户直连最快），App 在**本地**补出镜像，不把代理写死在清单里。
 *
 * 做成顶层函数而不是类方法，是因为 `ThemeEntry` 是嵌套类 —— Kotlin 的
 * 嵌套类拿不到外部类实例，访问不了普通成员函数。
 */
internal fun githubMirrorsFor(url: String): List<String> {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return emptyList()
    val isGithub = GH_HOSTS.any { url.startsWith("https://$it/") || url.startsWith("http://$it/") }
    if (!isGithub) return emptyList()
    return listOf(
        "https://ghproxy.net/$url",
        "https://gh-proxy.com/$url",
    )
}

package com.localmind.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
         * 主题包 id 的合法性（字母数字、点、横线、下划线）。
         *
         * **必须放在 companion 里**：`init` 阶段就会调 [builtinThemes] → [parse]
         * → [isValidThemeId]，而实例属性是按源码顺序初始化的；放在类尾部会
         * 让 init 期间读到 null，直接 NPE 冷启动崩溃（1.0.6 真实事故）。
         */
        val ID_PATTERN = Regex("^[A-Za-z0-9._-]+$")

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
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val dir = File(context.filesDir, "remote_data").apply { mkdirs() }

    /**
     * 下载过的主题包。
     *
     * 放 `filesDir` 而不是 `cacheDir`：系统清缓存时不该把用户下过的主题一起清掉，
     * 否则"下次点安装又要重下"。代价是每个主题 1–2 MB，装十个也就 20 MB。
     */
    private val packsDir = File(context.filesDir, "theme_packs").apply { mkdirs() }

    /** 主题包缓存文件名：`<id>__<version>.zip`，上游发新版自然换文件。 */
    private fun packFile(entry: ThemeEntry): File {
        val ver = entry.version.ifBlank { "0" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(24)
        return File(packsDir, "${entry.id}__$ver.zip")
    }

    /**
     * 已经下好的包；没有就返回 null。
     *
     * 清单里声明了大小就按大小校验 —— 半包（下到一半被杀）长度一定不对，
     * 直接当没下过。没声明大小就只能看"非空"，那种情况靠安装时的 zip 校验兜底。
     */
    fun cachedPack(entry: ThemeEntry): File? {
        val f = packFile(entry)
        if (!f.isFile || f.length() <= 0L) return null
        if (entry.sizeBytes > 0 && f.length() != entry.sizeBytes) {
            Log.w(TAG, "缓存包大小不符，丢弃：${f.name}（${f.length()} ≠ ${entry.sizeBytes}）")
            runCatching { f.delete() }
            return null
        }
        return f
    }

    /** 已经下过（可直接安装）的主题 id 集合，界面据此把按钮画成"安装"而不是"下载"。 */
    fun cachedIds(): Set<String> = (packsDir.listFiles().orEmpty())
        .filter { it.isFile && it.length() > 0 }
        .map { it.name.substringBefore("__") }
        .toSet()

    /** 删掉一个已下载的包。返回是否真的删到了东西。 */
    fun deleteCached(entry: ThemeEntry): Boolean = runCatching { packFile(entry).delete() }.getOrDefault(false)

    /** 已下载包占用的空间（字节）。 */
    fun cachedBytes(): Long = packsDir.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    private val _themes = MutableStateFlow<List<ThemeEntry>>(emptyList())
    val themes: StateFlow<List<ThemeEntry>> = _themes

    /**
     * 构造时就装入内置主题。
     *
     * 内置主题不依赖网络，所以**冷启动第一屏就有东西可看** —— 不用等
     * refresh 跑完（那可能要十几秒超时）。在线主题随后追加。
     */
    init {
        // 兜底：内置清单读坏了不该让整个 App 起不来。
        // 1.0.6 的冷启动崩溃就是这条链上抛了 NPE（属性初始化顺序），
        // 应用还没画出第一帧就退出 —— 一个主题商店不值得这个代价。
        _themes.value = runCatching { builtinThemes() }
            .onFailure { Log.e(TAG, "内置主题清单解析失败，本次不带内置主题启动", it) }
            .getOrDefault(emptyList())
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

    /** 下载一个主题包。返回可直接安装的本地文件；失败返回 null。 */
    suspend fun download(entry: ThemeEntry, onProgress: (Long, Long) -> Unit = { _, _ -> }): File? =
        withContext(Dispatchers.IO) {
            // 0) 已经下过（并且大小对得上）就不再下一次 —— 用户重复点"安装"
            //    不该再走一遍网络。文件按 id+版本号存，上游发新版自然换新文件。
            cachedPack(entry)?.let {
                Log.i(TAG, "复用已下载的主题包：${entry.name}（${it.length() / 1024} KB）")
                onProgress(it.length(), it.length())
                return@withContext it
            }

            val target = packFile(entry)

            // 内置主题：直接从 APK 复制，不用联网。
            if (entry.isBuiltin) {
                return@withContext copyBuiltin(entry.builtinAsset, target, onProgress, entry.name)
            }

            // GitHub 直连在国内常常不通。候选 = 清单地址 + 内置代理展开，
            // 先**并行测速**（谁先回谁排前面），再按实测顺序逐个下 ——
            // 不再把时间浪费在"第一个源挂了才知道"上。
            // 进度回调按「当前这个源」汇报，换源时从 0 重新开始是如实反映。
            val urls = raceSources(entry.packageUrls)
            if (urls.isEmpty()) {
                Log.w(TAG, "没有合法的下载地址：${entry.id}")
                return@withContext null
            }
            Log.i(TAG, "源竞速结果：${urls.joinToString("  ")}")
            for ((index, url) in urls.withIndex()) {
                val ok = runCatching { downloadFrom(url, target, onProgress) }
                    .onFailure { Log.w(TAG, "源 ${index + 1} 失败：${it.message}", it) }
                    .getOrDefault(false)
                if (ok) {
                    Log.i(TAG, "主题包下载成功（源 ${index + 1}/${urls.size}）：${entry.name}")
                    // 清单里声明了大小就核一遍：不合说明下到了别的东西（镜像返回
                    // 错误页、或者上游换了包），宁可报失败也不装一个坏包。
                    if (entry.sizeBytes > 0 && target.length() != entry.sizeBytes) {
                        Log.w(TAG, "大小不符（${target.length()} ≠ ${entry.sizeBytes}），丢弃")
                        target.delete()
                    } else {
                        return@withContext target
                    }
                }
            }
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

    /**
     * 候选源竞速：**并行**对每个地址发一次 `Range: bytes=0-0` 的小探测，
     * 按响应快慢排序 —— 最快的排最前，探测失败/超时的排最后（但还是给它们
     * 兜底机会，万一是探测被限流而真下载没事）。
     *
     * 探测超时 4 秒：公共代理大多秒回，4 秒还没响应的基本就是挂了；
     * 全部超时时保持原顺序（直连优先），行为退化为旧版。
     */
    private suspend fun raceSources(urls: List<String>): List<String> =
        coroutineScope {
            val probed = urls.map { url ->
                async(Dispatchers.IO) {
                    val latency = withTimeoutOrNull(4_000L) {
                        val t0 = System.currentTimeMillis()
                        if (MultiPartDownload.probe(downloadClient, url) != null) {
                            System.currentTimeMillis() - t0
                        } else null
                    }
                    url to (latency ?: Long.MAX_VALUE)
                }
            }.awaitAll()
            probed.sortedBy { it.second }.map { it.first }
        }

    /**
     * 从一个地址下载到 [target]。
     *
     * **多连接分段**：先用 `Range: bytes=0-0` 探一次（顺带拿到文件大小），
     * 服务端支持 206 且文件够大就走 [MultiPartDownload]（2–6 条连接并行）；
     * 不支持 Range、或者分段中途失败，退回单连接流式写。
     *
     * 主题包通常 1–2 MB，分段收益有限（阈值 24 MB 起，见 [MultiPartDownload.segmentsFor]），
     * 但大主题包（带壁纸的樱雪物语 1.7 MB、极光夜航 1.9 MB，以后可能更大）
     * 和海外源上这段逻辑是有用的；单连接那条路同时充当兜底。
     */
    private suspend fun downloadFrom(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        target.parentFile?.mkdirs()
        target.delete()

        val probe = MultiPartDownload.probe(downloadClient, url)
        if (probe != null && probe.rangeSupported) {
            // 段数拉满：主题包虽小，但公共代理的**单连接吞吐很可怜**
            // （几十 KB/s 很常见），8 条连接把握手开销换来的并行度吃满。
            // 这是用户明确的要求 —— "多线程默认拉满，越快越好"。
            val segments = maxOf(MultiPartDownload.segmentsFor(probe.totalBytes), 8)
            if (segments > 1) {
                val segDir = MultiPartDownload.segmentDir(packsDir, target.name, probe.totalBytes)
                val ok = kotlinx.coroutines.coroutineScope {
                    MultiPartDownload.download(
                        client = downloadClient,
                        scope = this,
                        url = url,
                        target = segDir,
                        assembleTo = target,
                        totalBytes = probe.totalBytes,
                        segments = segments,
                        isCancelled = { false },
                        onProgress = { done, total, _ -> onProgress(done, total) },
                    )
                }
                if (ok) return true
                Log.i(TAG, "分段下载未完成，退回单连接：${target.name}")
            }
        }
        return downloadSingleStream(url, target, onProgress)
    }

    /** 单连接流式下载（服务端不认 Range，或分段失败时的兜底）。 */
    private fun downloadSingleStream(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.delete()
        val ok = runCatching {
            val req = Request.Builder().url(url).build()
            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP ${resp.code}：$url")
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
        if (!ok) {
            runCatching { tmp.delete() }
            return false
        }
        target.delete()
        if (tmp.renameTo(target)) return true
        // rename 失败（跨卷/文件被占用）：复制过去再删。
        return runCatching {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            true
        }.getOrDefault(false)
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
    // 内置多个公共 GitHub 加速代理。哪个活着、哪个快不在这里猜 ——
    // 下载前会全部探一轮延迟（见 ThemeStoreService.raceSources），按实测排序。
    // 代理挂了顶多白测一次，不影响其它源。
    val proxies = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://gh.llkk.cc/",
        "https://ghproxy.cc/",
    )
    return proxies.map { it + url }
}

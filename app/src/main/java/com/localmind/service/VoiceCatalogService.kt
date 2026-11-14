package com.localmind.service

import android.content.Context
import android.util.Log
import com.localmind.data.VoiceEntry
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
 * 在线音色目录。
 *
 * ## 为什么音色本来就能"云端拉取"，还要这个服务
 *
 * 音色**权重**一直就是云端拉的（`BuiltinVoices` 里只有元数据，权重不在 APK 里，
 * 用户在语音页点下载）。但**清单**是编译进 APK 的：内置 4 个 + 语音插件提供的那层。
 *
 * 插件这一层是能加新音色的，可它要用户先装插件。走这条路的人不多。
 *
 * 这个服务补的是中间那一层：**不装任何东西，打开就能看到最新的音色清单**，
 * 新音色上架 = 往云端仓库提交一个 JSON，不需要发版。
 *
 * ## 合并优先级
 *
 * 和 [com.localmind.data.VoiceCatalog.setFromPlugins] 一致：
 *
 * ```
 * 内置清单 → 在线清单 → 插件清单
 * ```
 *
 * 后面的覆盖前面的（同 id 时）。所以：
 *  * 插件能改内置音色的地址/说明（改地址不用等云端同步）；
 *  * 云端能加新音色，不用发版；
 *  * 用户自己装插件带来的音色优先级最高，不会被云端覆盖掉。
 *
 * ## 这份清单是第三方内容
 *
 * 和主题商店一样，字段全走白名单：
 *  - id 必须能安全拼进路径（它会被当 tarball 名用）；
 *  - url 只允许 http/https；
 *  * 体积、说明有上限。
 *
 * 真正下权重还是走 [VoiceModelDownloader]，它自己会校验解压路径。
 */
class VoiceCatalogService(private val context: Context) {

    private companion object {
        const val TAG = "VoiceCatalogService"
        const val CACHE_FILE = "voices.json"

        /**
         * 清单地址的回退链。顺序与 [ThemeStoreService] 一致：
         * 官方直连在前（快），ghproxy 镜像在后（墙内唯一能通的路）。
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val dir = File(context.filesDir, "remote_data").apply { mkdirs() }

    private val _remoteVoices = MutableStateFlow<List<VoiceEntry>>(emptyList())
    val remoteVoices: StateFlow<List<VoiceEntry>> = _remoteVoices

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /**
     * 刷新音色清单。
     *
     * 先吃缓存再拉网络。失败**不抛** —— 拉不到就用内置那 4 个，语音页照样能用。
     */
    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        _loading.value = true
        try {
            if (_remoteVoices.value.isEmpty()) {
                readCache()?.let {
                    _remoteVoices.value = it
                    Log.i(TAG, "使用缓存的音色清单：${it.size} 条")
                }
            }

            val body = fetch(CACHE_FILE) ?: return@withContext
            val parsed = parse(body)
            if (parsed.isEmpty()) {
                Log.w(TAG, "音色清单为空或格式不对")
                return@withContext
            }
            _remoteVoices.value = parsed
            _lastError.value = ""
            runCatching { File(dir, CACHE_FILE).writeText(body) }
            Log.i(TAG, "拉取到音色清单：${parsed.size} 条")
        } finally {
            _loading.value = false
        }
    }

    // ------------------------------------------------------------- 解析

    /**
     * 解析音色清单。
     *
     * 清单格式（`voices.json`）：
     * ```json
     * {
     *   "voices": [
     *     {
     *       "id": "vits-zh-hf-fanchen-unity",
     *       "label": "泛尘（女声 · 多说话人）",
     *       "description": "社区模型，音色更接近真人",
     *       "badge": "推荐",
     *       "sizeBytes": 119143475,
     *       "url": "https://.../xxx.tar.bz2",
     *       "mirrors": ["https://..."],
     *       "sampleText": "你好，我是泛尘。"
     *     }
     *   ]
     * }
     * ```
     *
     * `url` 留空就按官方 Release 约定拼（见 [VoiceCatalog.urlsFor]），
     * 所以清单里只写 id 也行。
     */
    private fun parse(text: String): List<VoiceEntry> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("voices") ?: root.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<VoiceEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val entry = runCatching {
                VoiceEntry(
                    id = o.optString("id").trim(),
                    label = o.optString("label").trim().take(60),
                    description = o.optString("description").trim().take(300),
                    badge = o.optString("badge").trim().take(12),
                    clonable = o.optBoolean("clonable", false),
                    url = o.optString("url").trim(),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    mirrors = o.optJSONArray("mirrors")?.let { ma ->
                        (0 until ma.length()).mapNotNull {
                            ma.optString(it).trim().takeIf { s -> s.isNotBlank() }
                        }.take(8)
                    } ?: emptyList(),
                    sampleText = o.optString("sampleText").trim().take(200),
                )
            }.getOrNull() ?: continue

            if (!entry.isUsable) continue
            if (!isSafeId(entry.id)) {
                Log.w(TAG, "跳过 id 不合法的条目：${entry.id}")
                continue
            }
            // url 留空是合法的（按约定拼官方地址）。但填了就必须合法。
            if (entry.url.isNotBlank() &&
                !entry.url.startsWith("http://") && !entry.url.startsWith("https://")
            ) {
                Log.w(TAG, "跳过 url 不合法的条目：${entry.id}")
                continue
            }
            entry.mirrors.forEach { m ->
                if (!m.startsWith("http://") && !m.startsWith("https://")) {
                    Log.w(TAG, "跳过镜像地址不合法：${m}")
                    return@forEach
                }
            }
            out.add(entry.copy(mirrors = entry.mirrors.filter { it.startsWith("http") }))
        }
        return out
    }

    /**
     * id 的安全性。
     *
     * id 会被拼进下载路径和文件名，所以只允许字母数字、点、横线、下划线。
     * 官方 Release 的 tarball 名就是这个形态，不会误伤。
     */
    private fun isSafeId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 120 && ID_PATTERN.matches(id)

    private fun readCache(): List<VoiceEntry>? {
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

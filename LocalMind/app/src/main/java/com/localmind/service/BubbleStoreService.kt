package com.localmind.service

import android.content.Context
import android.util.Log
import com.localmind.data.UiPresets
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
 * 云端气泡目录。
 *
 * ## 为什么气泡不需要"下载"
 *
 * 气泡就是一组令牌：颜色、圆角、形状、描边、渐变。它没有字体文件、
 * 没有壁纸、没有图片 —— 一条 JSON 就描述完了。所以"下载气泡"实际上是
 * **拉清单 + 把 tokens 合并进用户覆盖**，不需要 zip，也不需要安装流程。
 *
 * 这也意味着气泡可以做得很多（几十个），更新即时生效，不占空间。
 *
 * ## 和内置气泡的关系
 *
 * 内置的（[UiPresets.BUBBLES]）保证离线也有东西可选；云端清单来了之后
 * 按 id 合并，**云端优先**（改一个内置气泡的颜色不用发版）。
 */
class BubbleStoreService(private val context: Context) {

    private companion object {
        const val TAG = "BubbleStore"
        const val CACHE_FILE = "bubbles.json"

        /**
         * 清单地址：和主题、字体、发布信息共用同一份回退链。
         *
         * 这里原本自己抄了一份（Pages / jsDelivr / raw / 两个镜像），顺序还是
         * 老的"官方在前、镜像在后"。既然 [GitHubMirror] 已经把实测最快的那条
         * 排到第一位，就没必要再维护第二份 —— 两份列表一旦不同步，就会出现
         * "主题能刷、气泡刷不出来"这种极难排查的差异。
         */
        val BASES = GitHubMirror.DATA_BASES
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val dir = File(context.filesDir, "remote_data").apply { mkdirs() }

    private val _bubbles = MutableStateFlow<List<UiPresets.Preset>>(emptyList())

    /** 云端气泡（不含内置）。界面自己决定怎么合并。 */
    val cloudBubbles: StateFlow<List<UiPresets.Preset>> = _bubbles

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError

    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        _loading.value = true
        try {
            if (_bubbles.value.isEmpty()) readCache()?.let { _bubbles.value = it }
            val body = fetch(CACHE_FILE) ?: return@withContext
            val parsed = parse(body)
            if (parsed.isEmpty()) return@withContext
            _bubbles.value = parsed
            _lastError.value = ""
            runCatching { File(dir, CACHE_FILE).writeText(body) }
            Log.i(TAG, "拉取到气泡目录：${parsed.size} 款")
        } finally {
            _loading.value = false
        }
    }

    private fun parse(text: String): List<UiPresets.Preset> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("bubbles") ?: root.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<UiPresets.Preset>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            val name = o.optString("name").trim().take(24)
            if (id.isBlank() || name.isBlank()) continue
            if (!id.matches(Regex("^[A-Za-z0-9._-]{1,60}$"))) continue
            fun tokens(key: String): Map<String, String> {
                val obj = o.optJSONObject(key) ?: return emptyMap()
                val m = LinkedHashMap<String, String>()
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    // 只收字符串值；数字/布尔在清单里也统一写成字符串，写错就跳过。
                    val v = obj.optString(k, "")
                    if (k.length <= 40 && v.isNotBlank() && v.length <= 60) m[k] = v
                }
                return m
            }
            val light = tokens("tokens")
            if (light.isEmpty()) continue
            out.add(
                UiPresets.Preset(
                    id = id,
                    name = name,
                    description = o.optString("description").trim().take(80),
                    tokens = light,
                    darkTokens = tokens("darkTokens"),
                )
            )
            if (out.size >= 200) break
        }
        return out
    }

    private fun readCache(): List<UiPresets.Preset>? {
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

package com.localmind.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 远程应用数据：捐献榜 / 公告 / 版本下发。
 *
 * 数据源是公开仓库 LocalMind-Data，App 匿名拉 JSON。
 * 国内网络下 raw.githubusercontent.com 经常被墙，所以按顺序尝试多个可达源：
 * GitHub Pages（同源托管）→ jsDelivr 镜像（国内 CDN）→ 官方 raw（兜底）。
 * 策略：
 *
 *  - 内存 StateFlow 供 UI 订阅；
 *  - 启动时先吃本地缓存（filesDir/remote_data/）立即渲染，
 *    然后后台拉网络，成功就刷新并写缓存，失败保留缓存；
 *  - 公告「已读」只记 id —— 有新 id 才弹窗，平时只出现在关于页；
 *  - 更新检查对比 versionCode，由后台决定是否下发、是否强制。
 *
 * 全程 best-effort：网络挂了/JSON 坏了，静默降级到缓存或内置数据。
 */
class RemoteDataService(private val context: Context) {

    data class Donor(val name: String, val yuan: Int, val avatarBase64: String?)

    data class DonorBoard(val version: Int, val updatedAt: String, val list: List<Donor>) {
        val sorted: List<Donor> get() = list.sortedByDescending { it.yuan }
    }

    data class Announcement(
        val id: String,
        val title: String,
        val content: String,
        val publishedAt: String,
        val enabled: Boolean,
    )

    data class ReleaseInfo(
        val latestVersion: String,
        val versionCode: Int,
        val forceUpdate: Boolean,
        val changelog: String,
        val downloadUrl: String,
        val publishedAt: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val dir = File(context.filesDir, "remote_data").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("remote_data", Context.MODE_PRIVATE)

    private val _donors = MutableStateFlow<DonorBoard?>(null)
    val donors: StateFlow<DonorBoard?> = _donors

    private val _announcement = MutableStateFlow<Announcement?>(null)
    val announcement: StateFlow<Announcement?> = _announcement

    private val _release = MutableStateFlow<ReleaseInfo?>(null)
    val release: StateFlow<ReleaseInfo?> = _release

    /** 更新通道：stable（正式版，release.json）/ beta（测试版，release-beta.json）。 */
    private val _channel = MutableStateFlow(if (prefs.getString(KEY_CHANNEL, null) == "beta") "beta" else "stable")
    val channel: StateFlow<String> = _channel

    fun setChannel(value: String) {
        val v = if (value == "beta") "beta" else "stable"
        if (v == _channel.value) return
        _channel.value = v
        prefs.edit().putString(KEY_CHANNEL, v).apply()
    }

    /** 通道对应的版本文件。 */
    private fun releaseFile(): String = if (_channel.value == "beta") "release-beta.json" else "release.json"

    // ------------------------------------------------------------- 刷新

    /** 启动时调一次：先缓存、后网络。任何一个失败都不影响其他。 */
    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        // 1) 缓存先上屏
        if (_donors.value == null) cache("donors.json")?.let { runCatching { _donors.value = parseDonors(JSONObject(it)) } }
        if (_announcement.value == null) cache("announcement.json")?.let { runCatching { _announcement.value = parseAnnouncement(JSONObject(it)) } }
        if (_release.value == null) cache(releaseFile())?.let { runCatching { _release.value = parseRelease(JSONObject(it)) } }

        // 2) 网络刷新
        val rel = releaseFile()
        listOf(
            async { runCatching { fetch("donors.json")?.let { _donors.value = parseDonors(JSONObject(it)) } } },
            async { runCatching { fetch("announcement.json")?.let { _announcement.value = parseAnnouncement(JSONObject(it)) } } },
            async { runCatching { fetch(rel)?.let { _release.value = parseRelease(JSONObject(it)) } } },
        ).awaitAll()
    }

    /**
     * 轻量轮询：只拉「公告」和「版本」。
     *
     * 捐献榜一般不动，轮询它纯属浪费；公告要"实时"，所以单独给一条
     * 每 60 秒一次的小刷新通道。失败静默返回 false。
     */
    suspend fun refreshLive(): Boolean = withContext(Dispatchers.IO) {
        var ok = false
        runCatching {
            fetch("announcement.json")?.let {
                _announcement.value = parseAnnouncement(JSONObject(it))
                ok = true
            }
        }
        runCatching {
            fetch(releaseFile())?.let {
                _release.value = parseRelease(JSONObject(it))
                ok = true
            }
        }
        ok
    }

    /** 切换通道后重新拉一次版本信息。 */
    suspend fun refreshRelease() = withContext(Dispatchers.IO) {
        runCatching { fetch(releaseFile())?.let { _release.value = parseRelease(JSONObject(it)) } }
    }

    // ------------------------------------------------------------- 公告

    /** 该弹窗的公告：启用了、且 id 与已读不同。没有返回 null。 */
    fun pendingAnnouncement(): Announcement? {
        val a = _announcement.value ?: return null
        if (!a.enabled || a.id.isBlank() || a.title.isBlank()) return null
        return if (a.id == prefs.getString(KEY_ANN_READ, null)) null else a
    }

    fun markAnnouncementRead(id: String) {
        prefs.edit().putString(KEY_ANN_READ, id).apply()
    }

    // ------------------------------------------------------------- 更新

    /** 有新版本返回 ReleaseInfo，否则 null。 */
    fun pendingUpdate(currentVersionCode: Int): ReleaseInfo? {
        val r = _release.value ?: return null
        return if (r.versionCode > currentVersionCode) r else null
    }

    // ------------------------------------------------------------- 内部

    private fun cache(name: String): String? =
        File(dir, name).takeIf { it.isFile }?.readText()

    /** 拉网络并写缓存；失败返回 null（保留旧缓存）。
     *  依次尝试多个可达源，任意一个 200 即采用（国内 raw 被墙时自动走 Pages/jsDelivr）。 */
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
            if (body != null) {
                runCatching { File(dir, name).writeText(body) }
                return body
            }
        }
        return null
    }

    private fun parseDonors(j: JSONObject): DonorBoard {
        val list = j.optJSONArray("list")?.let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Donor(
                    name = o.optString("name"),
                    yuan = o.optInt("yuan"),
                    // dataURL → 纯 base64（bitmap() 直接 Base64.decode）
                    avatarBase64 = o.optString("avatar").takeIf { s -> s.isNotBlank() }
                        ?.let { s -> if (s.startsWith("data:")) s.substringAfter(",") else s },
                )
            }
        } ?: emptyList()
        return DonorBoard(j.optInt("version", 1), j.optString("updatedAt"), list)
    }

    private fun parseAnnouncement(j: JSONObject) = Announcement(
        id = j.optString("id"),
        title = j.optString("title"),
        content = j.optString("content"),
        publishedAt = j.optString("publishedAt"),
        enabled = j.optBoolean("enabled", false),
    )

    private fun parseRelease(j: JSONObject) = ReleaseInfo(
        latestVersion = j.optString("latestVersion"),
        versionCode = j.optInt("versionCode"),
        forceUpdate = j.optBoolean("forceUpdate", false),
        changelog = j.optString("changelog"),
        downloadUrl = j.optString("downloadUrl"),
        publishedAt = j.optString("publishedAt"),
    )

    private companion object {
        // 清单来源统一由 GitHubMirror 给出：加速源在前、GitHub Pages 在后。
        // 以前这里自己抄了一份源列表，导致字体/主题/更新三处能通情况不一样。
        val BASES = GitHubMirror.DATA_BASES
        const val KEY_ANN_READ = "ann_read_id"
        const val KEY_CHANNEL = "update_channel"
    }
}

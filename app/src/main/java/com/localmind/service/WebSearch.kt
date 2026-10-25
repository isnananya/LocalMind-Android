package com.localmind.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val TAG = "WebSearch"

/**
 * 真的联网检索。
 *
 * ## 为什么是"抓 HTML"而不是调 API
 *
 * 搜索 API 基本都要 key、要注册、有额度，而这个应用的前提是**装完就能用**。
 * 所以走公开的搜索结果页，解析出标题 / 摘要 / 链接。代价是页面结构变了就会坏 ——
 * 因此留了 [DEFAULT_ENDPOINTS] 两条和用户自定义端点（设置里的"联网检索地址"），
 * 任何一条挂了都还能换。
 *
 * ## 它做什么、不做什么
 *
 * 只把**摘要**喂给模型（不是整页正文）：手机上模型上下文有限，整页 HTML 既塞不下
 * 也没必要。要让模型读整页，那是另一个能力（抓取），不该混在这里默默发生。
 *
 * 检索结果会带上编号，提示词里要求模型回答时标注来源 —— 用户能看出哪句话是查到的、
 * 哪句是模型自己说的，这比"看起来很肯定"重要得多。
 */
object WebSearch {

    data class Hit(val title: String, val snippet: String, val url: String)

    /**
     * 默认端点。
     *
     * 第一条是 Bing 中国站：国内直连可达、不需要 key、结构稳定；
     * 第二条是 DuckDuckGo 的免 JS 版，作为兜底（第一条结构变了或者被挡时用）。
     */
    val DEFAULT_ENDPOINTS = listOf(
        "https://cn.bing.com/search?q=",
        "https://html.duckduckgo.com/html/?q=",
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 桌面 UA：移动版结果页的结构和桌面版不一样，抓桌面版更稳。 */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

    /**
     * 检索。[endpoint] 为空时依次试 [DEFAULT_ENDPOINTS]。
     *
     * @return 命中的条目；全部失败返回空列表（调用方据此**不注入**任何东西 ——
     *   查不到就让模型按自己的知识答，不要塞一段"检索失败"污染上下文）。
     */
    suspend fun search(query: String, endpoint: String = ""): List<Hit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val endpoints = if (endpoint.isBlank()) DEFAULT_ENDPOINTS else listOf(endpoint)
        for (ep in endpoints) {
            val hits = runCatching { fetch(ep, query) }.getOrElse { t ->
                Log.w(TAG, "检索失败（$ep）：${t.message}")
                emptyList()
            }
            if (hits.isNotEmpty()) {
                Log.i(TAG, "检索到 ${hits.size} 条：${query.take(30)}")
                return@withContext hits
            }
        }
        emptyList()
    }

    private fun fetch(endpoint: String, query: String): List<Hit> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (endpoint.contains("{q}")) {
            endpoint.replace("{q}", encoded)
        } else {
            endpoint + encoded
        }
        val req = Request.Builder().url(url).header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()
        val html = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string().orEmpty()
        }
        if (html.isBlank()) return emptyList()
        return parse(html).take(6)
    }

    /**
     * 从结果页里抠出条目。
     *
     * 两套结构一起认：Bing 的 `<li class="b_algo"><h2><a href=…>` 和
     * DuckDuckGo 的 `<a class="result__a" href=…>`。不引入 HTML 解析库 ——
     * 只为这一个用途拉一个依赖不值得，而两条正则各自很短、坏了也看得出来。
     */
    private fun parse(html: String): List<Hit> {
        val out = mutableListOf<Hit>()

        // Bing
        val bing = Regex(
            "<li class=\"b_algo\"[\\s\\S]{0,4000}?<h2>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
            RegexOption.IGNORE_CASE,
        )
        for (m in bing.findAll(html)) {
            val url = m.groupValues[1]
            val title = clean(m.groupValues[2])
            if (title.isBlank() || !url.startsWith("http")) continue
            out += Hit(title, "", url)
        }

        // DuckDuckGo（当 Bing 一条都没抠到时才用，避免两套结果混在一起）
        if (out.isEmpty()) {
            val ddg = Regex(
                "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
                RegexOption.IGNORE_CASE,
            )
            for (m in ddg.findAll(html)) {
                val url = m.groupValues[1]
                val title = clean(m.groupValues[2])
                if (title.isBlank()) continue
                out += Hit(title, "", url)
            }
        }

        // 摘要：从每个条目的位置往后找第一段 <p>…</p>，找不到就留空。
        // 宁可少给，也不要为了"看起来完整"去猜。
        return out.map { hit ->
            val at = html.indexOf(hit.url)
            val snippet = if (at >= 0) {
                val tail = html.substring(at, (at + 2500).coerceAtMost(html.length))
                Regex("<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE)
                    .find(tail)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
            } else {
                ""
            }
            hit.copy(snippet = snippet.take(300))
        }
    }

    private fun clean(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * 拼成给模型看的一段。**带编号**，并要求它回答时标出来源。
     *
     * 最后那句"和问题无关就别硬套"很重要：搜索页经常返回一堆不相关的东西，
     * 不写清楚，模型会为了"用上资料"而把无关内容硬扯进来。
     */
    fun buildBlock(query: String, hits: List<Hit>): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n【联网检索结果】\n")
        sb.append("下面是刚刚为「").append(query.take(60)).append("」查到的网页摘要。")
        sb.append("它们可能过时、不完整或有错，请据此回答，并在用到的那句话末尾用 [1][2] 标出来源编号。")
        sb.append("如果这些内容和问题无关，就按你自己的知识回答，不要硬套。\n")
        hits.forEachIndexed { i, h ->
            sb.append('[').append(i + 1).append("] ").append(h.title)
            if (h.snippet.isNotBlank()) sb.append(" —— ").append(h.snippet)
            sb.append("（").append(h.url.take(120)).append("）\n")
        }
        return sb.toString()
    }
}

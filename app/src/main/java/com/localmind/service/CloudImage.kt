package com.localmind.service

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * 云端文生图源。
 *
 * **内置 Pollinations**：免费、不用 API key、不用下模型，给一段提示词 URL 就能拿图，
 * 实测 ~2-5 秒出图。这正好对应「更方便速度更快的生图」——没有 1.6 GB 的模型要下，
 * 点一下几秒就出图，CPU 扩散在手机上要好几十秒。
 *
 * **外部端点**（`externalEndpoint`）：OpenAI 兼容的 `/images/generations` 基址。
 * 进阶用户可以把这里指向任意兼容服务（自建的、带 key 的免费 API……）。
 * 留空就走内置 Pollinations。
 *
 * ⚠️ 这是联网操作。所有 URL 要么是写死的 Pollinations 主机，要么是**用户自己填**的
 * 外部端点，没有把任何第三方代理硬编码进去。
 */
object CloudImage {

    private const val TAG = "CloudImage"

    /** 超时给足：云端出图本身快，但国内网络到 pollinations 偶尔要走代理，慢一点。 */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    const val PROVIDER_LOCAL = "local"
    const val PROVIDER_CLOUD = "cloud"

    data class ResultData(val bytes: ByteArray, val source: String) {
        override fun equals(other: Any?): Boolean =
            other is ResultData && source == other.source && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = source.hashCode() * 31 + bytes.contentHashCode()
    }

    suspend fun generate(
        prompt: String,
        width: Int,
        height: Int,
        seed: Int,
        endpoint: String = "",
        apiKey: String = "",
    ): Result<ResultData> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            if (endpoint.isNotBlank()) {
                fetchOpenAI(endpoint.trimEnd('/'), apiKey, prompt, width, height)
            } else {
                fetchPollinations(prompt, width, height, seed)
            }
        }
    }

    /**
     * 内置 Pollinations。长宽夹到 64-1024（Pollinations 免费档上限就是 1024，
     * 再大要付费）。`model=flux` 是默认且质量最好的那个；`enhance=true` 让它能把
     * 短提示词补全成更具体的描述，中文也能吃。
     */
    private fun fetchPollinations(prompt: String, width: Int, height: Int, seed: Int): ResultData {
        val w = max(64, width).coerceAtMost(1024)
        val h = max(64, height).coerceAtMost(1024)
        val q = URLEncoder.encode(prompt, "UTF-8")
        val url = "https://image.pollinations.ai/prompt/$q" +
            "?width=$w&height=$h&seed=$seed&nologo=true&model=flux&enhance=true"
        return download(url, "Pollinations")
    }

    /**
     * 外部端点：OpenAI 兼容的 images/generations。
     * 返回 `data[0].url` 就去下那张图；返回 `data[0].b64_json` 就直接解 base64。
     */
    private fun fetchOpenAI(
        base: String,
        apiKey: String,
        prompt: String,
        width: Int,
        height: Int,
    ): ResultData {
        val body = JSONObject().apply {
            put("prompt", prompt)
            put("n", 1)
            put("size", "${width}x${height}")
            put("response_format", "url")
        }.toString()
        val req = Request.Builder()
            .url("$base/images/generations")
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("外部端点 HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val arr = json.optJSONArray("data") ?: JSONArray()
            val item = arr.optJSONObject(0)
            val b64 = item?.optString("b64_json", "").orEmpty()
            if (b64.isNotBlank()) {
                return ResultData(
                    Base64.decode(b64, Base64.DEFAULT),
                    "外部端点",
                )
            }
            val url = item?.optString("url", "").orEmpty()
            if (url.isNotBlank()) return download(url, "外部端点")
            error("外部端点没有返回图片或链接")
        }
    }

    private fun download(url: String, source: String): ResultData {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("下载图片 HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: error("空响应")
            if (bytes.size < 64) error("图片太小，可能下载失败")
            return ResultData(bytes, source)
        }
    }
}

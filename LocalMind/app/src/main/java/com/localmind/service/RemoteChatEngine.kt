package com.localmind.service

import com.localmind.data.RemoteProvider
import com.localmind.data.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Talks to any OpenAI-compatible `/v1/chat/completions` endpoint: OpenAI, DeepSeek,
 * Kimi/Moonshot, Qwen/DashScope, SiliconFlow, a llama.cpp server on the LAN, …
 *
 * Kept separate from [LlamaEngine] so the UI can switch backends without caring
 * which one answers.
 */
class RemoteChatEngine {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)   // long generations on remote models
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Result of one completion, streamed piece by piece through [onToken]. */
    suspend fun chat(
        provider: RemoteProvider,
        messages: List<Pair<Role, String>>,
        temperature: Float,
        maxTokens: Int,
        onToken: (suspend (String) -> Unit)? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = provider.baseUrl.trimEnd('/') + "/chat/completions"
            val payload = buildJsonObject {
                put("model", provider.model)
                put("temperature", temperature)
                put("max_tokens", maxTokens)
                put("stream", onToken != null)
                putJsonArray("messages") {
                    messages.forEach { (role, content) ->
                        addJsonObject {
                            put("role", role.name.lowercase())
                            put("content", content)
                        }
                    }
                }
            }

            val builder = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
            if (provider.apiKey.isNotBlank()) {
                builder.header("Authorization", "Bearer ${provider.apiKey}")
            }

            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string()?.take(400).orEmpty()
                    error("HTTP ${response.code}${if (detail.isBlank()) "" else ": $detail"}")
                }
                val body = response.body ?: error("empty response body")

                if (onToken == null) {
                    val text = body.string()
                    return@use extractContent(text)
                }

                // SSE: lines of "data: {...}", terminated by "data: [DONE]".
                val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
                val accumulated = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val piece = runCatching {
                        json.parseToJsonElement(data).jsonObject["choices"]
                            ?.jsonArray?.firstOrNull()
                            ?.jsonObject?.get("delta")
                            ?.jsonObject?.get("content")
                            ?.jsonPrimitive?.content
                    }.getOrNull()
                    if (!piece.isNullOrEmpty()) {
                        accumulated.append(piece)
                        onToken(piece)
                    }
                }
                accumulated.toString()
            }
        }
    }

    private fun extractContent(raw: String): String =
        runCatching {
            json.parseToJsonElement(raw).jsonObject["choices"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
                .orEmpty()
        }.getOrElse { error("无法解析响应：${it.message}") }

    /** Cheap reachability probe used by the settings screen. */
    suspend fun probe(provider: RemoteProvider): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val url = provider.baseUrl.trimEnd('/') + "/models"
            val builder = Request.Builder().url(url).get()
            if (provider.apiKey.isNotBlank()) {
                builder.header("Authorization", "Bearer ${provider.apiKey}")
            }
            client.newCall(builder.build()).execute().use { it.code }
        }
    }
}

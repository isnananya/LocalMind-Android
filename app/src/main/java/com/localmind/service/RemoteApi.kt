package com.localmind.service

import android.util.Base64
import com.localmind.data.ProviderKind
import com.localmind.data.RemoteProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 云端 / 局域网接口的**统一入口**。
 *
 * ## 为什么要有这一层
 *
 * 原来只有 [RemoteChatEngine] 一个类，它只会说 OpenAI 的 /v1/chat/completions，
 * 而且只服务「对话」这一件事。于是：
 *
 *  * 想让 AI 看图 → 没有路径（识图槽位只认本地 GGUF + mmproj）；
 *  * 想用云端的生图 / 语音 → 没有路径；
 *  * 想接 Anthropic 或 Gemini → 协议不一样，直接不通。
 *
 * 现在四类任务（对话 / 识图 / 生图 / 语音 + 决策）都从这里走，协议差异只在
 * 下面的三个 adapter 里 —— 上层（界面、HTTP 服务、决策引擎）看到的是同一个
 * [chat] / [generateImage] / [speech]。
 *
 * ## 「转发」是怎么回事
 *
 * [proxyRaw] 是给本机 HTTP 服务用的：把客户端发来的**原始请求体**原样转给上游，
 * 再把响应原样（含 SSE 流）转回去。这样任何 OpenAI 客户端连上手机，
 * 就能用手机里配好的云端模型 —— 手机在这里是个代理，不参与推理。
 */
class RemoteApi {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // 连接池按 provider 的域名复用；超时用最长的那个（云端长回答很慢）。
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val streamClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // SSE 不能有读超时
        .build()

    data class Msg(val role: String, val text: String, val imagePath: String? = null)

    /** 让调用方自己决定「用哪个模型」—— 留空时由 provider 的默认值补上。 */
    private fun modelOf(explicit: String?, fallback: String): String =
        explicit?.takeIf { it.isNotBlank() } ?: fallback

    private fun base(url: String): String = url.trimEnd('/')

    // ------------------------------------------------------------ 传输安全

    /**
     * 这个主机属于"本机 / 局域网"吗。
     *
     * 判断依据是**字面地址**而不是 DNS 解析结果 —— 解析会引入一次网络往返，
     * 而且解析结果可以在两次请求之间变化（DNS rebinding）。所以这里只放行
     * 明确写成本机/私有网段的字面量，域名一律按公网对待。
     */
    private fun isPrivateHost(rawHost: String): Boolean {
        val h = rawHost.trim().lowercase().removeSurrounding("[", "]")
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        if (h == "10.0.2.2") return true   // Android 模拟器里的宿主机
        val parts = h.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val (a, b) = nums[0] to nums[1]
        return a == 127 || a == 10 || a == 0 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            (a == 169 && b == 254)
    }

    /**
     * 明文 HTTP 只允许打到本机 / 局域网。
     *
     * 原因是 `network_security_config.xml` 里 `cleartextTrafficPermitted` 必须是
     * 全局 true —— 用户会把 baseUrl 填成 `http://192.168.1.20:11434`（局域网 Ollama，
     * Android 的安全配置无法表达"IP 段白名单"，只能整体放开）。既然配置文件管不了，
     * 就在这一层管住最要命的那件事：**绝不把 API Key 用一个明文连接发到公网**。
     *
     * 公网地址必须 https；填错直接报一句能看懂的话，而不是静默降级。
     */
    private fun requireSafeUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: error("接口地址格式不对：$url")
        val scheme = uri.scheme?.lowercase()
            ?: error("接口地址缺少协议（要写 http:// 或 https://）：$url")
        if (scheme != "http" && scheme != "https") {
            error("只支持 http / https，当前是 $scheme：$url")
        }
        if (scheme == "http" && !isPrivateHost(uri.host.orEmpty())) {
            error("拒绝用明文 HTTP 访问公网地址 ${uri.host}：密钥会以明文经过网络，请改用 https://")
        }
    }

    // ------------------------------------------------------------------ 对话

    /**
     * 一次补全。[onToken] 非空时走 SSE 流式。
     *
     * 三种协议的差异全部收在这里：[ProviderKind.OPENAI] 是 messages + choices.delta，
     * Anthropic 是 messages + content_block_delta，Gemini 是 contents + parts。
     */
    suspend fun chat(
        provider: RemoteProvider,
        model: String?,
        messages: List<Msg>,
        temperature: Float = 0.7f,
        maxTokens: Int = 1024,
        onToken: (suspend (String) -> Unit)? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (provider.kind) {
                ProviderKind.ANTHROPIC -> anthropicChat(provider, model, messages, temperature, maxTokens, onToken)
                ProviderKind.GEMINI -> geminiChat(provider, model, messages, temperature, maxTokens, onToken)
                ProviderKind.OPENAI -> openAiChat(provider, model, messages, temperature, maxTokens, onToken)
            }
        }
    }

    // ------------------------------------------------------------------ 生图

    /**
     * 文字生图，返回图片字节。
     *
     * 只实现 OpenAI 的 /images/generations —— 它是事实标准，国内厂商（智谱、
     * 硅基流动、通义）与各家网关都兼容它。Anthropic 没有生图接口，Gemini 的
     * 图像模型走的是另一套（predict），今天先如实报「这个协议不支持」。
     */
    suspend fun generateImage(
        provider: RemoteProvider,
        prompt: String,
        size: String = "1024x1024",
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            if (provider.kind != ProviderKind.OPENAI) {
                error(provider.kind.label + " 协议没有生图接口，请换一个 OpenAI 兼容的服务");
            }
            val payload = buildJsonObject {
                put("model", modelOf(provider.imageModel, "dall-e-3"));
                put("prompt", prompt);
                put("n", 1);
                put("size", size);
                put("response_format", "b64_json");
            }
            val body = postJson(provider, "/images/generations", payload.toString())
            val root = json.parseToJsonElement(body).jsonObject
            val item = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: error("响应里没有 data");
            val b64 = item["b64_json"]?.jsonPrimitive?.contentOrNull
            if (!b64.isNullOrBlank()) return@runCatching Base64.decode(b64, Base64.DEFAULT)
            // 有些服务不支持 b64，只给 URL：再取一次。
            val url = item["url"]?.jsonPrimitive?.contentOrNull
                ?: error("响应里既没有 b64_json 也没有 url");
            fetchBytes(provider, url)
        }
    }

    // ------------------------------------------------------------------ 语音

    /**
     * 文字转语音，返回音频字节（OpenAI 兼容的 /audio/speech，默认 mp3）。
     */
    suspend fun speech(
        provider: RemoteProvider,
        text: String,
        voice: String? = null,
        format: String = "mp3",
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            if (provider.kind != ProviderKind.OPENAI) {
                error(provider.kind.label + " 协议没有语音合成接口，请换一个 OpenAI 兼容的服务");
            }
            val payload = buildJsonObject {
                put("model", modelOf(provider.speechModel, "tts-1"));
                put("input", text);
                put("voice", modelOf(voice, provider.speechVoice.ifBlank { "alloy" }));
                put("response_format", format);
            }
            postJsonBytes(provider, "/audio/speech", payload.toString())
        }
    }

    // ------------------------------------------------------------------ 转发

    /**
     * 把客户端发来的原始 JSON **原样**转给上游，并把响应原样转回去。
     *
     * @param path 上游路径（/chat/completions、/images/generations、/audio/speech）
     * @param onChunk 流式响应时逐行回调（SSE）；非流式时整段回调一次
     */
    suspend fun proxyRaw(
        provider: RemoteProvider,
        path: String,
        rawBody: String,
        stream: Boolean,
        onChunk: suspend (String) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (provider.kind != ProviderKind.OPENAI) {
                error("转发目前只支持 OpenAI 兼容的上游（当前是 " + provider.kind.label + "）");
            }
            val request = authed(provider, base(provider.baseUrl) + path)
                .post(rawBody.toRequestBody("application/json".toMediaType()))
                .build();
            val actual = if (stream) streamClient else client;
            actual.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string()?.take(400).orEmpty();
                    error("上游 HTTP " + response.code + (if (detail.isBlank()) "" else ": " + detail));
                }
                val body = response.body ?: error("上游没有响应体");
                if (!stream) {
                    onChunk(body.string());
                    return@use;
                }
                val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8));
                while (true) {
                    val line = reader.readLine() ?: break;
                    onChunk(line);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 探测

    /** 连得通吗、密钥对不对。返回一句能直接给用户看的话。 */
    suspend fun probe(provider: RemoteProvider): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = when (provider.kind) {
                ProviderKind.ANTHROPIC -> base(provider.baseUrl) + "/models"
                ProviderKind.GEMINI -> base(provider.baseUrl) + "/models?key=" + provider.apiKey
                ProviderKind.OPENAI -> base(provider.baseUrl) + "/models"
            }
            val request = authed(provider, url).get().build();
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string()?.take(200).orEmpty();
                    error("HTTP " + response.code + (if (detail.isBlank()) "" else ": " + detail));
                }
                val text = response.body?.string().orEmpty();
                val count = runCatching {
                    val root = json.parseToJsonElement(text).jsonObject;
                    (root["data"] ?: root["models"])?.jsonArray?.size;
                }.getOrNull();
                if (count != null) "连接正常，服务端列出 " + count + " 个模型" else "连接正常";
            }
        }
    }

    // ================================================================== 内部

    private fun authed(provider: RemoteProvider, url: String): Request.Builder {
        requireSafeUrl(url)
        val builder = Request.Builder().url(url).header("Content-Type", "application/json");
        when (provider.kind) {
            // Anthropic 用 x-api-key + 版本头，不认 Bearer。
            ProviderKind.ANTHROPIC -> {
                if (provider.apiKey.isNotBlank()) builder.header("x-api-key", provider.apiKey);
                builder.header("anthropic-version", "2023-06-01");
            }
            // Gemini 的密钥在 query 上（已拼好），这里不再加头。
            ProviderKind.GEMINI -> Unit
            ProviderKind.OPENAI ->
                if (provider.apiKey.isNotBlank()) builder.header("Authorization", "Bearer " + provider.apiKey);
        }
        return builder;
    }

    private fun postJson(provider: RemoteProvider, path: String, body: String): String {
        val request = authed(provider, base(provider.baseUrl) + path)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build();
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty();
            if (!response.isSuccessful) error("HTTP " + response.code + ": " + text.take(300));
            return text;
        }
    }

    private fun postJsonBytes(provider: RemoteProvider, path: String, body: String): ByteArray {
        val request = authed(provider, base(provider.baseUrl) + path)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build();
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0);
            if (!response.isSuccessful) {
                error("HTTP " + response.code + ": " + String(bytes).take(300));
            }
            return bytes;
        }
    }

    private fun fetchBytes(provider: RemoteProvider, url: String): ByteArray {
        // 这个 URL 来自**上游响应**（{"url": "..."}），不是用户填的 baseUrl。
        // 所以只在同源时才带认证头：一个被劫持或本身恶意的上游，只要回一个
        // `{"url":"https://evil.example/x"}`，原本会把用户的真实云端密钥一起送过去。
        val sameOrigin = runCatching {
            val target = URI(url)
            val origin = URI(base(provider.baseUrl))
            target.scheme.equals(origin.scheme, ignoreCase = true) &&
                target.host.equals(origin.host, ignoreCase = true)
        }.getOrDefault(false)

        val request = if (sameOrigin) {
            authed(provider, url).get().build()
        } else {
            requireSafeUrl(url)
            Request.Builder().url(url).get().build()
        }

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("取图失败 HTTP " + response.code);
            return response.body?.bytes() ?: ByteArray(0);
        }
    }

    private fun imageBlock(path: String?): Pair<String, String>? {
        if (path.isNullOrBlank()) return null;
        val file = File(path);
        if (!file.isFile) return null;
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null;
        if (bytes.size > 8 * 1024 * 1024) return null;   // 太大就不发，避免把请求撑爆
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        };
        return mime to Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    // ---- OpenAI ----

    private fun openAiChat(
        provider: RemoteProvider,
        model: String?,
        messages: List<Msg>,
        temperature: Float,
        maxTokens: Int,
        onToken: (suspend (String) -> Unit)?,
    ): String {
        val payload = buildJsonObject {
            put("model", modelOf(model, provider.model));
            put("temperature", temperature);
            put("max_tokens", maxTokens);
            put("stream", onToken != null);
            putJsonArray("messages") {
                messages.forEach { m ->
                    addJsonObject {
                        put("role", m.role);
                        val img = imageBlock(m.imagePath);
                        if (img == null) {
                            put("content", m.text);
                        } else {
                            // 多模态：content 变成数组，图片用 data URL。
                            putJsonArray("content") {
                                addJsonObject { put("type", "text"); put("text", m.text); }
                                addJsonObject {
                                    put("type", "image_url");
                                    putJsonObject("image_url") {
                                        put("url", "data:" + img.first + ";base64," + img.second);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val request = authed(provider, base(provider.baseUrl) + "/chat/completions")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build();

        val actual = if (onToken != null) streamClient else client;
        actual.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.take(400).orEmpty();
                error("HTTP " + response.code + (if (detail.isBlank()) "" else ": " + detail));
            }
            val body = response.body ?: error("空响应");
            if (onToken == null) return extractOpenAi(body.string());

            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8));
            val acc = StringBuilder();
            while (true) {
                val line = reader.readLine() ?: break;
                if (!line.startsWith("data:")) continue;
                val data = line.removePrefix("data:").trim();
                if (data.isEmpty() || data == "[DONE]") continue;
                val piece = runCatching {
                    json.parseToJsonElement(data).jsonObject["choices"]
                        ?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")
                        ?.jsonObject?.get("content")?.jsonPrimitive?.content;
                }.getOrNull();
                if (!piece.isNullOrEmpty()) {
                    acc.append(piece);
                    kotlinx.coroutines.runBlocking { onToken(piece) };
                }
            }
            return acc.toString();
        }
    }

    private fun extractOpenAi(raw: String): String =
        runCatching {
            json.parseToJsonElement(raw).jsonObject["choices"]
                ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")
                ?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty();
        }.getOrElse { error("无法解析响应：" + it.message) }


    // ---- Anthropic（Messages API）----
    //
    // 和 OpenAI 的三个硬差异，都在这里处理掉：
    //  1. system 不是一条 message，而是顶层的 system 字段；
    //  2. content 是**块数组**（图片是 image 块 + base64 source），不是字符串；
    //  3. 流式事件是 content_block_delta，delta 里是 text_delta.text。
    private fun anthropicChat(
        provider: RemoteProvider,
        model: String?,
        messages: List<Msg>,
        temperature: Float,
        maxTokens: Int,
        onToken: (suspend (String) -> Unit)?,
    ): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.text };
        val turns = messages.filter { it.role != "system" };

        val payload = buildJsonObject {
            put("model", modelOf(model, provider.model));
            put("max_tokens", maxTokens);
            put("temperature", temperature);
            if (system.isNotBlank()) put("system", system);
            put("stream", onToken != null);
            putJsonArray("messages") {
                turns.forEach { m ->
                    addJsonObject {
                        // Anthropic 只认 user / assistant。
                        put("role", if (m.role == "assistant") "assistant" else "user");
                        putJsonArray("content") {
                            val img = imageBlock(m.imagePath);
                            if (img != null) {
                                addJsonObject {
                                    put("type", "image");
                                    putJsonObject("source") {
                                        put("type", "base64");
                                        put("media_type", img.first);
                                        put("data", img.second);
                                    }
                                }
                            }
                            addJsonObject {
                                put("type", "text");
                                put("text", m.text.ifBlank { "(空)" });
                            }
                        }
                    }
                }
            }
        }

        val request = authed(provider, base(provider.baseUrl) + "/messages")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build();

        val actual = if (onToken != null) streamClient else client;
        actual.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.take(400).orEmpty();
                error("HTTP " + response.code + (if (detail.isBlank()) "" else ": " + detail));
            }
            val body = response.body ?: error("空响应");
            if (onToken == null) return extractAnthropic(body.string());

            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8));
            val acc = StringBuilder();
            while (true) {
                val line = reader.readLine() ?: break;
                if (!line.startsWith("data:")) continue;
                val data = line.removePrefix("data:").trim();
                if (data.isEmpty()) continue;
                val piece = runCatching {
                    val delta = json.parseToJsonElement(data).jsonObject["delta"]?.jsonObject;
                    if (delta?.get("type")?.jsonPrimitive?.content == "text_delta") {
                        delta["text"]?.jsonPrimitive?.content;
                    } else null;
                }.getOrNull();
                if (!piece.isNullOrEmpty()) {
                    acc.append(piece);
                    kotlinx.coroutines.runBlocking { onToken(piece) };
                }
            }
            return acc.toString();
        }
    }

    private fun extractAnthropic(raw: String): String =
        runCatching {
            json.parseToJsonElement(raw).jsonObject["content"]?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                ?.joinToString("").orEmpty();
        }.getOrElse { error("无法解析响应：" + it.message) }

    // ---- Google Gemini（generateContent）----
    //
    // 角色叫 user / model；内容在 contents[].parts[]；图片是 inline_data；
    // 密钥在 query 上；system 走 systemInstruction。
    private fun geminiChat(
        provider: RemoteProvider,
        model: String?,
        messages: List<Msg>,
        temperature: Float,
        maxTokens: Int,
        onToken: (suspend (String) -> Unit)?,
    ): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.text };
        val turns = messages.filter { it.role != "system" };

        val payload = buildJsonObject {
            if (system.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", system) } }
                }
            }
            putJsonArray("contents") {
                turns.forEach { m ->
                    addJsonObject {
                        put("role", if (m.role == "assistant") "model" else "user");
                        putJsonArray("parts") {
                            val img = imageBlock(m.imagePath);
                            if (img != null) {
                                addJsonObject {
                                    putJsonObject("inline_data") {
                                        put("mime_type", img.first);
                                        put("data", img.second);
                                    }
                                }
                            }
                            addJsonObject { put("text", m.text.ifBlank { "(空)" }) }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature);
                put("maxOutputTokens", maxTokens);
            }
        }

        val m = modelOf(model, provider.model);
        val method = if (onToken != null) ":streamGenerateContent" else ":generateContent";
        val sep = if (provider.baseUrl.contains("?")) "&" else "?";
        val url = base(provider.baseUrl) + "/models/" + m + method + sep + "alt=sse&key=" + provider.apiKey;

        val request = authed(provider, url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build();

        val actual = if (onToken != null) streamClient else client;
        actual.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.take(400).orEmpty();
                error("HTTP " + response.code + (if (detail.isBlank()) "" else ": " + detail));
            }
            val body = response.body ?: error("空响应");
            if (onToken == null) return extractGemini(body.string());

            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8));
            val acc = StringBuilder();
            while (true) {
                val line = reader.readLine() ?: break;
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim();
                    if (data.isEmpty() || data == "[DONE]") continue;
                    val piece = runCatching { geminiTextOf(data) }.getOrNull();
                    if (!piece.isNullOrEmpty()) {
                        acc.append(piece);
                        kotlinx.coroutines.runBlocking { onToken(piece) };
                    }
                } else {
                    // 有些网关把整个 JSON 一行行发（没有 data: 前缀）。
                    val piece = runCatching { geminiTextOf(line.trim()) }.getOrNull();
                    if (!piece.isNullOrEmpty()) {
                        acc.append(piece);
                        kotlinx.coroutines.runBlocking { onToken(piece) };
                    }
                }
            }
            return acc.toString();
        }
    }

    private fun geminiTextOf(rawJson: String): String =
        json.parseToJsonElement(rawJson).jsonObject["candidates"]
            ?.jsonArray?.firstOrNull()?.jsonObject?.get("content")
            ?.jsonObject?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            ?.joinToString("").orEmpty()

    private fun extractGemini(raw: String): String =
        runCatching { geminiTextOf(raw) }.getOrElse { error("无法解析响应：" + it.message) }
}

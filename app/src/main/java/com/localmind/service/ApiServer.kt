package com.localmind.service

import android.util.Log
import com.localmind.BuildConfig
import com.localmind.data.ApiLogEntry
import com.localmind.data.ApiSettings
import com.localmind.data.Emotion
import com.localmind.data.ModelStore
import com.localmind.data.ImageStyle
import com.localmind.data.Role
import com.localmind.data.Storage
import com.localmind.data.VoiceSettings
import com.localmind.util.TextUtils
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

private const val TAG = "ApiServer"

/** Non-suspend lock helper for the HTTP worker threads. */
private inline fun <T> java.util.concurrent.locks.ReentrantLock.withLockBlocking(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

data class ApiServerState(
    val running: Boolean = false,
    val port: Int = 8080,
    val lanAddresses: List<String> = emptyList(),
    val loopback: String = "http://127.0.0.1:8080",
    val requestCount: Long = 0,
    val lastError: String? = null,
    val startedAt: Long = 0,
)

/**
 * OpenAI-compatible HTTP server that runs entirely on the device.
 *
 * Implemented directly on `ServerSocket` + a fixed thread pool: no third-party
 * server dependency, exact control over SSE streaming, and a small APK.
 *
 * Requests are serialised with a fair lock before they reach the engine — one
 * phone has one model in memory, so concurrent decodes would only thrash. HTTP
 * connections are still accepted in parallel, so clients are not refused.
 *
 * Endpoints:
 *   GET  /                       banner
 *   GET  /health                 liveness + engine state
 *   GET  /v1/models              OpenAI model list
 *   POST /v1/chat/completions    OpenAI chat (stream + non-stream)
 *   POST /v1/completions         legacy completion
 *   POST /v1/embeddings          local hashing embedder (384-d)
 *   POST /v1/images/generations  text -> PNG url
 *   GET  /v1/images/{file}       fetch a generated image
 *   POST /v1/audio/speech        WAV via the device TTS engine
 *   GET  /v1/system              memory / cpu / model diagnostics
 *   POST /v1/admin/load          switch the resident model (optional)
 */
class ApiServer(
    private val storage: Storage,
    private val engine: LlamaEngine,
    private val tts: TtsEngine,
    private val modelStore: ModelStore,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(ApiServerState())
    val state: kotlinx.coroutines.flow.StateFlow<ApiServerState> = _state

    private val logs = Collections.synchronizedList(mutableListOf<ApiLogEntry>())
    private val _logFlow = kotlinx.coroutines.flow.MutableStateFlow<List<ApiLogEntry>>(emptyList())
    val logFlow: kotlinx.coroutines.flow.StateFlow<List<ApiLogEntry>> = _logFlow

    private val requestIds = AtomicLong(0)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var settings: ApiSettings = ApiSettings()
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(8)

    /** Fair FIFO gate in front of the single inference engine. */
    private val engineLock = java.util.concurrent.locks.ReentrantLock(true)

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    fun updateSettings(newSettings: ApiSettings) {
        settings = newSettings
    }

    fun start(newSettings: ApiSettings): Result<ApiServerState> {
        settings = newSettings
        if (isRunning) return Result.success(_state.value)
        return runCatching {
            val socket = ServerSocket()
            socket.reuseAddress = true
            val bind = if (newSettings.bindLan) null else InetAddress.getByName("127.0.0.1")
            socket.bind(InetSocketAddress(bind, newSettings.port), 48)
            serverSocket = socket

            _state.value = _state.value.copy(
                running = true,
                port = newSettings.port,
                lanAddresses = lanAddresses(),
                loopback = "http://127.0.0.1:${newSettings.port}",
                lastError = null,
                startedAt = System.currentTimeMillis(),
            )

            acceptThread = thread(name = "localmind-api", isDaemon = true) { acceptLoop(socket) }
            loadPersistedLog()
            Log.i(TAG, "listening on ${if (newSettings.bindLan) "0.0.0.0" else "127.0.0.1"}:${newSettings.port}")
            _state.value
        }.onFailure {
            _state.value = _state.value.copy(running = false, lastError = it.message ?: it.javaClass.simpleName)
            Log.e(TAG, "start failed", it)
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        _state.value = _state.value.copy(running = false)
        persistLog()
        Log.i(TAG, "stopped")
    }

    fun clearLog() {
        logs.clear()
        _logFlow.value = emptyList()
        persistLog()
    }

    // --------------------------------------------------------------- logging

    private fun loadPersistedLog() {
        if (logs.isEmpty()) {
            logs.addAll(storage.loadApiLog().takeLast(settings.requestLogSize))
            _logFlow.value = logs.toList().reversed()
        }
    }

    private fun persistLog() {
        runCatching { storage.saveApiLog(logs.takeLast(300)) }
    }

    private fun addLog(entry: ApiLogEntry) {
        logs.add(entry)
        while (logs.size > settings.requestLogSize.coerceAtLeast(20)) logs.removeAt(0)
        _logFlow.value = logs.toList().reversed()
    }

    private fun record(
        method: String, path: String, status: Int, startedAt: Long, remote: String,
        preview: String, error: String?,
        completionTokens: Int = 0,
    ) {
        addLog(
            ApiLogEntry(
                id = requestIds.incrementAndGet(),
                time = System.currentTimeMillis(),
                method = method,
                path = path,
                status = status,
                durationMs = System.currentTimeMillis() - startedAt,
                remote = remote,
                promptPreview = preview,
                completionTokens = completionTokens,
                error = error,
            )
        )
        _state.value = _state.value.copy(requestCount = _state.value.requestCount + 1)
    }

    // ------------------------------------------------------------- transport

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break
            } catch (t: Throwable) {
                Log.w(TAG, "accept failed", t)
                continue
            }
            pool.execute { handleClient(client) }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 30_000
                // Raw byte stream on purpose: Content-Length counts BYTES, while a
                // character reader would count decoded chars and silently truncate any
                // non-ASCII body (e.g. a Chinese prompt).
                val input = java.io.BufferedInputStream(s.getInputStream(), 8192)
                val requestLine = readAsciiLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1]
                val path = rawPath.substringBefore('?')
                val query = parseQuery(rawPath.substringAfter('?', ""))

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readAsciiLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }

                val body = readBodyBytes(input, headers).toString(Charsets.UTF_8)
                val started = System.currentTimeMillis()
                val remote = s.inetAddress?.hostAddress ?: "?"

                if (method == "OPTIONS") {
                    writeResponse(s.getOutputStream(), 204, "text/plain", "")
                    return
                }

                if (!authorized(headers, query)) {
                    writeJson(s.getOutputStream(), 401, errorJson("invalid_request_error", "invalid api key", "invalid_api_key"))
                    record(method, path, 401, started, remote, "", "invalid key")
                    return
                }

                when {
                    method == "GET" && path == "/" -> {
                        writeJson(s.getOutputStream(), 200, bannerJson())
                        record(method, path, 200, started, remote, "", null)
                    }

                    method == "GET" && path == "/health" -> {
                        writeJson(s.getOutputStream(), 200, healthJson())
                        record(method, path, 200, started, remote, "", null)
                    }

                    method == "GET" && path == "/v1/models" -> {
                        writeJson(s.getOutputStream(), 200, modelsJson())
                        record(method, path, 200, started, remote, "", null)
                    }

                    method == "GET" && path == "/v1/system" -> {
                        writeJson(s.getOutputStream(), 200, systemJson())
                        record(method, path, 200, started, remote, "", null)
                    }

                    // Diagnostic: expose the tokenizer round-trip so encoding problems
                    // in the Java<->JNI bridge are visible instead of looking like
                    // "the model answers nonsense".
                    method == "POST" && path == "/v1/debug/tokens" -> {
                        val text = jsonOf(body)?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
                        val payload = if (text.isBlank()) {
                            errorJson("invalid_request_error", "text is required", "missing_text")
                        } else {
                            val raw = engine.tokenRoundTrip(text)
                            runCatching { json.parseToJsonElement(raw) }
                                .getOrElse { buildJsonObject { put("raw", raw) } }
                        }
                        writeJson(s.getOutputStream(), 200, payload)
                        record(method, path, 200, started, remote, TextUtils.preview(text), null)
                    }

                    // Diagnostic: show the exact prompt string handed to llama.cpp,
                    // plus the model's own chat template and generation settings.
                    // This is how you tell a template problem from a model problem.
                    method == "POST" && path == "/v1/debug/prompt" -> {
                        val root = jsonOf(body)
                        val messages = root?.let { parseMessages(it) }
                            ?: listOf(Role.USER to "你好")
                        val rendered = engine.formatChat(messages, addAssistant = true)
                        writeJson(s.getOutputStream(), 200, buildJsonObject {
                            put("rendered_prompt", rendered)
                            put("n_tokens", engine.countTokens(rendered))
                            put("generation", buildJsonObject {
                                val s = storage.loadGenerationSettings()
                                put("temperature", s.temperature)
                                put("top_p", s.topP)
                                put("top_k", s.topK)
                                put("min_p", s.minP)
                                put("repeat_penalty", s.repeatPenalty)
                                put("max_tokens", s.maxTokens)
                                put("system_prompt", s.systemPrompt)
                            })
                            put("model_info", runCatching {
                                json.parseToJsonElement(engine.modelInfoJson())
                            }.getOrElse { JsonNull })
                        })
                        record(method, path, 200, started, remote, "", null)
                    }

                    // Diagnostic: 从设备上的一个路径安装插件 zip。
                    //
                    // 存在的理由是**可测试性**：插件导入在应用里走的是系统文件选择器
                    // （SAF），而 SAF 的界面没法用 adb 自动化 —— 每次验证插件格式都
                    // 要人工点五六下，改一次试一次不现实。
                    //
                    // 有了这个端点，插件格式就能被脚本化验证：
                    //     adb push my.zip /data/local/tmp/     （或 run-as 推到私有目录）
                    //     curl -X POST .../v1/debug/plugin -d '{"path":"/data/local/tmp/my.zip"}'
                    //
                    // 它**只读一个调用方指定的路径**，不接受上传内容，也不写回该路径。
                    // 路径必须落在应用自己的目录或 /data/local/tmp 下 —— 见下面的检查，
                    // 免得它变成一个"读任意文件"的口子。
                    method == "POST" && path == "/v1/debug/plugin" -> {
                        val raw = jsonOf(body)?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val allowed = raw.startsWith(storage.appContext().filesDir.absolutePath) ||
                            raw.startsWith("/data/local/tmp/")
                        val file = java.io.File(raw)
                        val payload = when {
                            raw.isBlank() -> errorJson("invalid_request_error", "path is required", "missing_path")
                            !allowed -> errorJson(
                                "invalid_request_error",
                                "path must be under the app files dir or /data/local/tmp",
                                "path_not_allowed",
                            )
                            !file.isFile -> errorJson("invalid_request_error", "not a file: $raw", "not_found")
                            else -> buildJsonObject {
                                when (val o = com.localmind.ui.PluginInstall.install(storage, file)) {
                                    is com.localmind.ui.PluginInstall.Outcome.Ok -> {
                                        put("ok", true)
                                        put("result", o.result.message)
                                        put("plugin_id", o.result.manifest.id)
                                        put("abilities", o.result.manifest.abilities.joinToString(","))
                                        // 装完必须通知界面刷新。
                                        //
                                        // 这条路径以前只写存储、不 bump 版本号，于是通过
                                        // /v1/debug/plugin 装的插件"装成功了但界面没反应" ——
                                        // 主题、语音引擎、流水线列表都不更新，得杀进程重进。
                                        // 界面那条路径（PluginInstall 由 ViewModel 调）本来就会 bump，
                                        // 漏的就是诊断端点这一条。
                                        runCatching {
                                            com.localmind.LocalMindApp.instance.bumpPluginRevision()
                                        }
                                    }
                                    is com.localmind.ui.PluginInstall.Outcome.Failed -> {
                                        put("ok", false)
                                        put("result", o.reason)
                                    }
                                }
                            }
                        }
                        writeJson(s.getOutputStream(), 200, payload)
                        record(method, path, 200, started, remote, raw.take(60), null)
                    }


                    // 诊断：内置头像。验证 base64 能不能解成位图、解出来多大。
                    //
                    // 为什么需要它：头像在关于页，点进去要滚三屏；而"显示出来了没有"
                    // 看截图只能靠人眼。这个端点直接报告每张图的字节数与像素尺寸，
                    // 解码失败也能立刻看出来。
                    method == "POST" && path == "/v1/debug/avatars" -> {
                        val payload = buildJsonObject {
                            put("ok", true)
                            val items = listOf(
                                Triple("author", com.localmind.ui.theme.BuiltInAvatars.AUTHOR, "难安"),
                                Triple("donor_weitan", com.localmind.ui.theme.BuiltInAvatars.DONOR_WEITAN, "韪嘆"),
                                Triple("donor_chenbi", com.localmind.ui.theme.BuiltInAvatars.DONOR_CHENBI, "沉璧"),
                            )
                            put("avatars", buildJsonArray {
                                items.forEach { (key, b64, name) ->
                                    val bmp = com.localmind.ui.theme.BuiltInAvatars.bitmap(b64)
                                    add(buildJsonObject {
                                        put("key", key)
                                        put("name", name)
                                        put("base64_chars", b64.length)
                                        put("decoded_bytes", com.localmind.ui.theme.BuiltInAvatars.rawBytes(b64))
                                        put("decoded_ok", bmp != null)
                                        put("width", bmp?.width ?: 0)
                                        put("height", bmp?.height ?: 0)
                                    })
                                }
                            })
                        }
                        writeJson(s.getOutputStream(), 200, payload)
                        record(method, path, 200, started, remote, "avatars", null)
                    }

                    // 诊断：界面主题。读 / 设 / 清。
                    //
                    // 为什么需要它：验证"主题是不是真的生效"必须能**改一次、看一次**。
                    // 在模拟器上靠点 UI 走完「我的 → 主题 → 预设」要五六步，而且
                    // 截图里"变了没有"只能靠人眼比对。有了这个端点，整条链路
                    // （写覆盖 → bump revision → ViewModel 重算 → StateFlow 推给界面）
                    // 可以一条 curl 跑完，返回的 `tokens` 就是**当前真正生效**的值。
                    //
                    //     curl -X POST .../v1/debug/theme -d '{"preset":"earth"}'
                    //     curl -X POST .../v1/debug/theme -d '{"set":{"accent":"#E8590C"}}'
                    //     curl -X POST .../v1/debug/theme -d '{"clear":true}'
                    //     curl -X POST .../v1/debug/theme -d '{}'   （只读当前状态）
                    //
                    // 它只能改**主题令牌**这一件事，不能改任何行为设置。
                    method == "POST" && path == "/v1/debug/theme" -> {
                        val root = jsonOf(body)
                        val presetId = root?.get("preset")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val clear = root?.get("clear")?.jsonPrimitive?.booleanOrNull == true
                        val setPairs = root?.get("set")?.jsonObject?.entries
                            ?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }
                            .orEmpty()

                        var applied = ""
                        var unknownPreset = false
                        if (clear) {
                            storage.saveThemeOverrides(emptyMap())
                            applied = "cleared"
                        } else if (presetId.isNotBlank()) {
                            val preset = com.localmind.data.UiPresets.byId(presetId)
                            if (preset == null) {
                                unknownPreset = true
                            } else {
                                // 注意用 overrideEntries：直接合并 tokens + darkTokens
                                // 会让深色值盖掉浅色值（见 UiPresets.Preset 的说明）。
                                storage.saveThemeOverrides(preset.overrideEntries)
                                applied = "preset:" + preset.id
                            }
                        } else if (setPairs.isNotEmpty()) {
                            // 支持 "dark:accent" 这种键，写的就是"只在深色下生效"的那一项。
                            storage.saveThemeOverrides(storage.themeOverrides() + setPairs.toMap())
                            applied = "set:" + setPairs.joinToString(",") { it.first }
                        }

                        // 通知界面重算。**这一步是整条链路的关键**：只写存储的话
                        // 界面不会知道（以前就是这个毛病）。
                        if (applied.isNotEmpty()) {
                            com.localmind.LocalMindApp.of(storage.appContext()).bumpOverrideRevision()
                        }

                        val overrides = storage.themeOverrides()
                        val payload = if (unknownPreset) {
                            errorJson(
                                "invalid_request_error",
                                "unknown preset: " + presetId + "；可用：" +
                                    com.localmind.data.UiPresets.ALL.joinToString(",") { it.id },
                                "unknown_preset",
                            )
                        } else {
                            buildJsonObject {
                                put("ok", true)
                                put("applied", applied)
                                val dark = storage.isDarkNow()
                                put("dark", dark)
                                val activePreset = com.localmind.data.UiPresets.match(overrides)
                                put("preset", activePreset?.id.orEmpty())
                                put("overrides", overrides.size)
                                // 真正生效的令牌 = 插件主题 + 深色 + 用户覆盖，
                                // 和界面用的是同一个纯函数。
                                val packs = storage.loadUiThemes()
                                val enabled = storage.loadPlugins().values
                                    .filter { it.isActive && "ui_theme" in it.manifest.abilities }
                                val theme = enabled.lastOrNull()?.let { packs[it.manifest.id] }
                                put("plugin_theme", theme?.packName.orEmpty())
                                val tokens = com.localmind.data.UiThemes.resolve(theme, dark, overrides)
                                put("token_count", tokens.size)
                                put("tokens", buildJsonObject {
                                    tokens.forEach { (k, v) -> put(k, v) }
                                })
                            }
                        }
                        writeJson(s.getOutputStream(), if (unknownPreset) 400 else 200, payload)
                        record(method, path, if (unknownPreset) 400 else 200, started, remote, applied, null)
                    }

                    // 诊断：云端接口的增删改查与连通性测试。
                    //
                    // 为什么需要它：接口配置全靠填表单，而「加了接口但连不上」
                    // 是这一块最常见的反馈。有了这个端点，加接口 → 测试 → 发一条
                    // 对话，一条脚本就能跑完全链路（也能在 CI 里对着 mock 服务跑）。
                    //
                    //     curl -X POST .../v1/debug/provider -d '{}'                （列出）
                    //     curl -X POST .../v1/debug/provider -d '{"preset":"openai"}'
                    //     curl -X POST .../v1/debug/provider -d '{"id":"prov-x","test":true}'
                    //     curl -X POST .../v1/debug/provider -d '{"id":"prov-x","delete":true}'
                    //     curl -X POST .../v1/debug/provider -d '{"id":"prov-x","enabled":true,
                    //       "baseUrl":"http://10.0.2.2:9099/v1","apiKey":"k","model":"mock"}'
                    method == "POST" && path == "/v1/debug/provider" -> {
                        val root = jsonOf(body)
                        val list = storage.loadRemoteProviders()
                        val presetId = root?.get("preset")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val wantId = root?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
                        var action = "list"
                        var testResult = ""
                        // 也能直接切「默认对话后端」：验证「界面上发一条消息真的走了
                        // 云端」需要它，否则要去点一串界面。
                        root?.get("backend")?.jsonPrimitive?.contentOrNull?.let { b ->
                            storage.setChatBackend(b)
                        }

                        if (presetId.isNotBlank()) {
                            val preset = com.localmind.data.RemoteProvider.PRESETS
                                .firstOrNull { it.id == presetId }
                            if (preset == null) {
                                action = "unknown_preset"
                            } else {
                                list += com.localmind.data.RemoteProvider(
                                    id = "prov-" + preset.id + "-" + System.currentTimeMillis().toString(36).takeLast(4),
                                    name = preset.name,
                                    kind = preset.kind,
                                    baseUrl = preset.baseUrl,
                                    model = preset.model,
                                    visionModel = preset.visionModel,
                                    imageModel = preset.imageModel,
                                    enabled = true,
                                )
                                storage.saveRemoteProviders(list)
                                action = "added:" + preset.id
                            }
                        } else if (wantId.isNotBlank()) {
                            val idx = list.indexOfFirst { it.id == wantId || it.name == wantId }
                            if (idx < 0) {
                                action = "not_found"
                            } else if (root?.get("delete")?.jsonPrimitive?.booleanOrNull == true) {
                                list.removeAt(idx)
                                storage.saveRemoteProviders(list)
                                action = "deleted"
                            } else {
                                var p = list[idx]
                                root?.get("enabled")?.jsonPrimitive?.booleanOrNull?.let { p = p.copy(enabled = it) }
                                root?.get("baseUrl")?.jsonPrimitive?.contentOrNull?.let { p = p.copy(baseUrl = it) }
                                root?.get("apiKey")?.jsonPrimitive?.contentOrNull?.let { p = p.copy(apiKey = it) }
                                root?.get("model")?.jsonPrimitive?.contentOrNull?.let { p = p.copy(model = it) }
                                root?.get("visionModel")?.jsonPrimitive?.contentOrNull?.let { p = p.copy(visionModel = it) }
                                root?.get("imageModel")?.jsonPrimitive?.contentOrNull?.let { p = p.copy(imageModel = it) }
                                root?.get("name")?.jsonPrimitive?.contentOrNull?.let { p = p.copy(name = it) }
                                root?.get("kind")?.jsonPrimitive?.contentOrNull?.let { k ->
                                    com.localmind.data.ProviderKind.entries
                                        .firstOrNull { it.name.equals(k, true) }?.let { p = p.copy(kind = it) }
                                }
                                list[idx] = p
                                storage.saveRemoteProviders(list)
                                action = "updated"

                                if (root?.get("test")?.jsonPrimitive?.booleanOrNull == true) {
                                    val api = com.localmind.LocalMindApp.of(storage.appContext()).remoteApi
                                    testResult = runBlocking { api.probe(p) }
                                        .getOrElse { t -> "失败：" + (t.message ?: t.javaClass.simpleName) }
                                }
                            }
                        }

                        val finalList = storage.loadRemoteProviders()
                        val payload = buildJsonObject {
                            put("ok", true)
                            put("action", action)
                            put("backend", storage.chatBackend())
                            if (testResult.isNotBlank()) put("test", testResult)
                            put("providers", buildJsonArray {
                                finalList.forEach { p ->
                                    add(buildJsonObject {
                                        put("id", p.id)
                                        put("name", p.name)
                                        put("kind", p.kind.name)
                                        put("baseUrl", p.baseUrl)
                                        put("model", p.model)
                                        put("enabled", p.enabled)
                                        put("hasKey", p.apiKey.isNotBlank())
                                    })
                                }
                            })
                        }
                        writeJson(s.getOutputStream(), 200, payload)
                        record(method, path, 200, started, remote, action, null)
                    }
                    // 诊断：直接走一遍 RemoteApi 的对话适配器（三种协议都行）。
                    //
                    // 为什么需要它：转发只支持 OpenAI 兼容的上游，所以 Anthropic /
                    // Gemini 那两条 adapter **没有任何 HTTP 入口能触发**。而这个仓库
                    // 的规矩是「接进来的协议必须有办法验证」—— 否则就是写了没人跑过的代码。
                    //
                    //     curl -X POST .../v1/debug/remote -d '{"name":"Mock Anthropic","prompt":"hi"}'
                    method == "POST" && path == "/v1/debug/remote" -> {
                        val root = jsonOf(body)
                        val key = root?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
                            .ifBlank { root?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty() }
                        val prompt = root?.get("prompt")?.jsonPrimitive?.contentOrNull ?: "你好"
                        val stream = root?.get("stream")?.jsonPrimitive?.booleanOrNull == true
                        val provider = storage.loadRemoteProviders()
                            .firstOrNull { it.id == key || it.name == key }
                        val payload = if (provider == null) {
                            errorJson("invalid_request_error", "找不到接口：" + key, "provider_not_found")
                        } else {
                            val api = com.localmind.LocalMindApp.of(storage.appContext()).remoteApi
                            val startedAt = System.currentTimeMillis()
                            val pieces = StringBuilder()
                            val result = runBlocking {
                                api.chat(
                                    provider = provider,
                                    model = provider.model,
                                    messages = listOf(com.localmind.service.RemoteApi.Msg("user", prompt)),
                                    onToken = if (stream) { { t -> pieces.append(t) } } else null,
                                )
                            }
                            buildJsonObject {
                                put("ok", result.isSuccess)
                                put("provider", provider.name)
                                put("kind", provider.kind.name)
                                put("model", provider.model)
                                put("ms", System.currentTimeMillis() - startedAt)
                                put("stream", stream)
                                put("streamed_chars", pieces.length)
                                result.onSuccess { put("text", it) }
                                result.onFailure { put("error", it.message ?: it.javaClass.simpleName) }
                            }
                        }
                        writeJson(s.getOutputStream(), 200, payload)
                        record(method, path, 200, started, remote, key, null)
                    }
                    // 诊断：推理加速开关。读 / 改 / 改完重新加载模型。
                    //
                    // 为什么需要它：加速项的效果**只能靠实测** —— 同一个 prompt、
                    // 同一个模型，改一项测一次。而设置页在 UI 深处、改完还要回模型页
                    // 手动重新加载，一轮 A/B 要十几次点击。这个端点让它变成一条 curl。
                    //
                    //     curl -X POST .../v1/debug/accel -d '{}'                  （只读）
                    //     curl -X POST .../v1/debug/accel -d '{"flashAttn":false}'
                    //     curl -X POST .../v1/debug/accel -d '{"kvQuant":"q8_0","reload":true}'
                    method == "POST" && path == "/v1/debug/accel" -> {
                        val root = jsonOf(body)
                        var gen = storage.loadGenerationSettings()
                        val before = gen
                        root?.get("flashAttn")?.jsonPrimitive?.booleanOrNull?.let { v ->
                            gen = gen.copy(flashAttn = v)
                        }
                        root?.get("kvQuant")?.jsonPrimitive?.contentOrNull?.let { v ->
                            gen = gen.copy(kvQuant = v)
                        }
                        root?.get("batchSize")?.jsonPrimitive?.intOrNull?.let { v ->
                            gen = gen.copy(batchSize = v)
                        }
                        root?.get("ubatchSize")?.jsonPrimitive?.intOrNull?.let { v ->
                            gen = gen.copy(ubatchSize = v)
                        }
                        root?.get("threads")?.jsonPrimitive?.intOrNull?.let { v ->
                            gen = gen.copy(threads = v)
                        }
                        // 上下文也要能在这里改：内存规划器把「KV 开多大」和
                        // 「跑多快」绑在一起，A/B 时不改它就分不清是哪一项在起作用。
                        root?.get("contextSize")?.jsonPrimitive?.intOrNull?.let { v ->
                            gen = gen.copy(contextSize = v)
                        }
                        val changed = gen != before
                        if (changed) storage.saveGenerationSettings(gen)

                        var reloaded = false
                        var reloadError = ""
                        val wantReload = root?.get("reload")?.jsonPrimitive?.booleanOrNull == true
                        if (wantReload && engine.state.value.ready) {
                            // 重新加载**当前常驻的那个文件**：加速项只在建上下文时生效，
                            // 不重建上下文就测不出任何差别。
                            val path = engine.state.value.modelPath
                            val model = modelStore.resolveAll().firstOrNull { it.localPath == path }
                            if (model == null) {
                                reloadError = "找不到当前模型对应的条目（可能来自插件或已改名）"
                            } else {
                                val r = runBlocking {
                                    engine.load(model, gen, model.mmprojPathOn(storage.modelsDir))
                                }
                                reloaded = r.isSuccess
                                reloadError = r.exceptionOrNull()?.message.orEmpty()
                            }
                        }

                        val st = engine.state.value
                        val payload = buildJsonObject {
                            put("ok", reloadError.isBlank())
                            put("changed", changed)
                            put("reloaded", reloaded)
                            if (reloadError.isNotBlank()) put("reload_error", reloadError)
                            put("flashAttn", gen.flashAttn)
                            put("kvQuant", gen.kvQuant)
                            put("batchSize", gen.batchSize)
                            put("ubatchSize", gen.ubatchSize)
                            put("threads_setting", gen.threads)
                            // effective 来自原生侧回读，和上面几个「选择值」可能不一样
                            put("effective", st.accelInfo)
                            put("context", st.contextSize)
                            put("threads", st.threads)
                            put("model", st.modelName ?: "")
                        }
                        writeJson(s.getOutputStream(), 200, payload)
                        record(method, path, 200, started, remote,
                               "accel flash=" + gen.flashAttn + " kv=" + gen.kvQuant, null)
                    }


                    // 诊断：模型下载。触发 / 取消 / 查状态。
                    //
                    // 为什么需要它：验证下载**没法靠 UI 自动化** —— 模型卡片可能在
                    // 列表很下面（要滚好几屏），而"下完了没有"看截图也看不出来。
                    // 有了这个端点，整条链路（探测 → 分段 → 拼接 → 校验 → 落盘）
                    // 一条 curl 就能跑完，并且拿得到**真实字节数和速度**。
                    //
                    //     curl -X POST .../v1/debug/download -d '{"id":"ernie45-03b-pt-q4km"}'
                    //     curl -X POST .../v1/debug/download -d '{"id":"…","cancel":true}'
                    //     curl -X POST .../v1/debug/download -d '{}'   （只查状态）
                    method == "POST" && path == "/v1/debug/download" -> {
                        val root = jsonOf(body)
                        val id = root?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val cancel = root?.get("cancel")?.jsonPrimitive?.booleanOrNull == true
                        val app = com.localmind.LocalMindApp.of(storage.appContext())
                        val wanted = storage.loadModelPacks().values.flatMap { it.models }
                            .firstOrNull { it.id == id }

                        var began = false
                        when {
                            id.isBlank() -> Unit
                            cancel -> app.downloader.pause(id)
                            wanted != null -> {
                                val f = java.io.File(storage.modelsDir, wanted.fileName)
                                if (f.isFile && f.length() > 0) wanted.localPath = f.absolutePath
                                app.downloader.start(wanted)
                                began = true
                            }
                        }

                        val payload = if (id.isNotBlank() && wanted == null) {
                            errorJson(
                                "invalid_request_error",
                                "no such model in installed packs: " + id,
                                "model_not_found",
                            )
                        } else {
                            buildJsonObject {
                                put("ok", true)
                                put("started", began)
                                val filesDir = storage.modelsDir
                                put("models_dir", filesDir.absolutePath)
                                if (id.isNotBlank()) {
                                    val st = app.downloader.stateOf(id)
                                    put("id", id)
                                    put("status", st.status.name)
                                    put("downloaded", st.downloadedBytes)
                                    put("total", st.totalBytes)
                                    put("progress", st.progress.toDouble())
                                    put("bytes_per_second", st.bytesPerSecond)
                                    put("attempt", st.attempt)
                                    put("source", st.source)
                                    put("error", st.error ?: "")
                                    put("file_bytes", java.io.File(filesDir, wanted?.fileName ?: "").let { if (it.isFile) it.length() else 0L })
                                }
                                // 段目录也报出来，好确认"真的在分段下"
                                put("segment_dirs", buildJsonArray {
                                    filesDir.listFiles()
                                        ?.filter { it.name.endsWith(".part.d") }
                                        ?.forEach { d ->
                                            add(buildJsonObject {
                                                put("name", d.name)
                                                put("segments", buildJsonArray {
                                                    d.listFiles()?.sortedBy { it.name }?.forEach { seg ->
                                                        add(buildJsonObject {
                                                            put("seg", seg.name)
                                                            put("bytes", seg.length())
                                                        })
                                                    }
                                                })
                                            })
                                        }
                                })
                            }
                        }
                        writeJson(s.getOutputStream(), if (id.isNotBlank() && wanted == null) 404 else 200, payload)
                        record(method, path, if (id.isNotBlank() && wanted == null) 404 else 200, started, remote, id, null)
                    }

                    // 诊断：把设备上的一个 .so 装成"运行时原生后端"并**真的加载**。
                    //
                    // 为什么需要它：生图后端是下载来的独立 .so，它的加载路径
                    // （ELF 校验 → 落到 filesDir/native/<abi>/ → System.load →
                    // 依赖解析）**没法靠 UI 自动化验证** —— 文件选择器驱动不了，
                    // 而 load 失败只在 logcat 里留一行。
                    //
                    // 这个端点把整条链路一次走完并把结果结构化返回：
                    //     adb push liblocalmind-sd.so /data/local/tmp/
                    //     curl -X POST .../v1/debug/backend -d '{"path":"/data/local/tmp/liblocalmind-sd.so"}'
                    //
                    // 返回里 load_ok / probe / info 三个字段分别回答：
                    // 装成功了吗 / JNI 入口通不通 / 后端自报什么身份。
                    method == "POST" && path == "/v1/debug/backend" -> {
                        val raw = jsonOf(body)?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val allowed = raw.startsWith("/data/local/tmp/") ||
                            raw.startsWith(storage.appContext().filesDir.absolutePath)
                        val src = java.io.File(raw)
                        val payload = when {
                            raw.isBlank() ->
                                errorJson("invalid_request_error", "path is required", "missing_path")
                            !allowed ->
                                errorJson(
                                    "invalid_request_error",
                                    "path must be under /data/local/tmp or the app files dir",
                                    "path_not_allowed",
                                )
                            !src.isFile ->
                                errorJson("invalid_request_error", "not a file: $raw", "not_found")
                            else -> {
                                val abi = com.localmind.service.NativeLibraryInstaller.currentAbi()
                                val target = com.localmind.service.NativeLibraryInstaller
                                    .installedFile(storage, abi)
                                target.parentFile?.mkdirs()
                                src.copyTo(target, overwrite = true)
                                val why = com.localmind.service.NativeLibraryInstaller.validate(target, abi)
                                val loaded = why == null &&
                                    com.localmind.service.NativeLibraryInstaller.load(target)
                                buildJsonObject {
                                    put("abi", abi)
                                    put("installed_to", target.absolutePath)
                                    put("bytes", target.length())
                                    put("validate_error", why ?: "")
                                    put("load_ok", loaded)
                                    // 真正调一次 JNI 入口，确认符号解析到了。
                                    put("probe", com.localmind.native.image.ImageBridge.hasDiffusionBackend)
                                    put(
                                        "info",
                                        com.localmind.native.image.ImageBridge.diffusionInfo() ?: "",
                                    )
                                }
                            }
                        }
                        writeJson(s.getOutputStream(), 200, payload)
                        record(method, path, 200, started, remote, raw.take(60), null)
                    }

                    method == "POST" && path == "/v1/debug/vision" -> {
                        // 识图（多模态）的脚本化入口。
                        //
                        //     curl -X POST .../v1/debug/vision \
                        //          -d '{"path":"/data/local/tmp/cat.jpg","prompt":"这是什么？"}'
                        //
                        // **为什么需要这个端点**：图片选择器是系统 SAF 界面，adb 驱动不了
                        //（和插件、主题一样的处境）。没有它，识图这条路就只能靠手点验证，
                        // 而那恰恰是最需要反复跑的路径。
                        //
                        // 它走的是**和聊天完全同一条**引擎路径（engine.chatWithImage），
                        // 不是另写一份 —— 否则测过了也不代表聊天里能用。
                        val raw = jsonOf(body)?.get("path")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val prompt = jsonOf(body)?.get("prompt")?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() } ?: "描述这张图片。"
                        val allowed = raw.startsWith("/data/local/tmp/") ||
                            raw.startsWith(storage.appContext().filesDir.absolutePath)
                        val src = java.io.File(raw)
                        val payload = when {
                            raw.isBlank() ->
                                errorJson("invalid_request_error", "path is required", "missing_path")
                            !allowed ->
                                errorJson(
                                    "invalid_request_error",
                                    "path must be under /data/local/tmp or the app files dir",
                                    "path_not_allowed",
                                )
                            !src.isFile ->
                                errorJson("invalid_request_error", "not a file: $raw", "not_found")
                            !engine.state.value.visionReady ->
                                errorJson(
                                    "invalid_request_error",
                                    "vision not ready: load a multimodal model together with its mmproj",
                                    "vision_not_ready",
                                )
                            else -> {
                                val settings = com.localmind.data.GenerationSettings(maxTokens = 220)
                                val result = runBlocking {
                                    engine.chatWithImage(
                                        messages = listOf(
                                            com.localmind.data.Role.USER to prompt,
                                        ),
                                        imagePath = raw,
                                        settings = settings,
                                    )
                                }
                                result.fold(
                                    onSuccess = { text ->
                                        com.localmind.data.Diagnostics.action(
                                            storage.appContext(),
                                            "调试端点识图：" + raw.substringAfterLast('/') +
                                                " -> " + text.take(40),
                                        )
                                        buildJsonObject {
                                            put("ok", true)
                                            put("model", engine.state.value.modelName ?: "")
                                            put("vision_ready", true)
                                            put("image", src.name)
                                            put("bytes", src.length())
                                            put("prompt", prompt)
                                            put("text", text)
                                        }
                                    },
                                    onFailure = { t ->
                                        errorJson(
                                            "server_error",
                                            t.message ?: t.javaClass.simpleName,
                                            "vision_failed",
                                        )
                                    },
                                )
                            }
                        }
                        writeJson(s.getOutputStream(), 200, payload)
                        record(method, path, 200, started, remote, raw.take(60), null)
                    }

                    method == "POST" && path == "/v1/chat/completions" -> handleChat(s, body, remote, started)
                    method == "POST" && path == "/v1/completions" -> handleCompletion(s, body, remote, started)
                    method == "POST" && path == "/v1/embeddings" -> handleEmbeddings(s, body, remote, started)
                    method == "POST" && path == "/v1/images/generations" -> handleImage(s, body, remote, started)
                    method == "GET" && path.startsWith("/v1/images/") ->
                        handleImageFetch(s, path.removePrefix("/v1/images/"), remote, started)

                    method == "POST" && path == "/v1/audio/speech" -> handleSpeech(s, body, remote, started)
                    method == "POST" && path == "/v1/admin/load" -> handleAdminLoad(s, body, remote, started)

                    else -> {
                        writeJson(s.getOutputStream(), 404, errorJson("invalid_request_error", "unknown endpoint $path", "not_found"))
                        record(method, path, 404, started, remote, "", "not found")
                    }
                }
            }.onFailure { t -> Log.w(TAG, "client error", t) }
        }
    }

    /** Reads one CRLF/LF-terminated header/request line as ASCII. */
    private fun readAsciiLine(input: java.io.InputStream): String? {
        val buffer = java.io.ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, Charsets.ISO_8859_1)
            }
            buffer.write(b)
            if (buffer.size() > 16 * 1024) return null
        }
    }

    private fun readBodyBytes(input: java.io.InputStream, headers: Map<String, String>): ByteArray {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) out else out.copyOf(read)
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotBlank() }.associate {
            val idx = it.indexOf('=')
            if (idx < 0) it to ""
            else it.substring(0, idx) to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
        }

    private fun authorized(headers: Map<String, String>, query: Map<String, String>): Boolean {
        val expected = settings.apiKey
        if (expected.isBlank()) return true
        val bearer = headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
        return bearer == expected ||
            headers["x-api-key"].orEmpty() == expected ||
            query["api_key"].orEmpty() == expected
    }

    private fun corsHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to settings.corsOrigin,
        "Access-Control-Allow-Headers" to "Authorization, Content-Type, x-api-key",
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
    )

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 503 -> "Service Unavailable"
        else -> "OK"
    }

    private fun writeHead(
        writer: BufferedWriter,
        code: Int,
        contentType: String,
        length: Int,
        extra: Map<String, String>,
    ) {
        writer.write("HTTP/1.1 $code ${reason(code)}\r\n")
        writer.write("Content-Type: $contentType\r\n")
        if (length >= 0) writer.write("Content-Length: $length\r\n")
        writer.write("Connection: close\r\n")
        (corsHeaders() + extra).forEach { (k, v) -> writer.write("$k: $v\r\n") }
        writer.write("\r\n")
        writer.flush()
    }

    /** Header bytes are ASCII/ISO-8859-1; only bodies are UTF-8. */
    private fun newHeadWriter(out: OutputStream) =
        BufferedWriter(OutputStreamWriter(out, Charsets.ISO_8859_1))

    private fun writeResponse(
        out: OutputStream,
        code: Int,
        contentType: String,
        body: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val writer = newHeadWriter(out)
        writeHead(writer, code, contentType, bytes.size, extra)
        out.write(bytes)
        out.flush()
    }

    private fun writeBytes(
        out: OutputStream,
        code: Int,
        contentType: String,
        body: ByteArray,
        extra: Map<String, String> = emptyMap(),
    ) {
        val writer = newHeadWriter(out)
        writeHead(writer, code, contentType, body.size, extra)
        out.write(body)
        out.flush()
    }

    private fun writeJson(out: OutputStream, code: Int, payload: JsonElement) =
        writeResponse(out, code, "application/json; charset=utf-8", payload.toString())

    /**
     * SSE head: no Content-Length (the stream is unbounded), no chunked encoding —
     * the body ends when the socket closes, which every SSE client handles.
     */
    private fun writeSseHead(out: OutputStream) {
        val writer = newHeadWriter(out)
        writer.write("HTTP/1.1 200 OK\r\n")
        writer.write("Content-Type: text/event-stream; charset=utf-8\r\n")
        writer.write("Cache-Control: no-cache\r\n")
        writer.write("Connection: close\r\n")
        corsHeaders().forEach { (k, v) -> writer.write("$k: $v\r\n") }
        writer.write("\r\n")
        writer.flush()
    }

    private fun errorJson(type: String, message: String, code: String): JsonObject = buildJsonObject {
        put("error", buildJsonObject {
            put("message", message)
            put("type", type)
            put("param", JsonNull)
            put("code", code)
        })
    }

    private fun jsonOf(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    // ------------------------------------------------------------- endpoints

    private fun bannerJson(): JsonObject = buildJsonObject {
        put("service", "LocalMind")
        // 版本号只从 BuildConfig 取：手写一份迟早在改版本时漏掉。
        put("version", BuildConfig.VERSION_NAME)
        put("description", "On-device LLM inference API (OpenAI-compatible subset)")
        put("endpoints", buildJsonArray {
            listOf(
                "GET /health", "GET /v1/models", "POST /v1/chat/completions",
                "POST /v1/completions", "POST /v1/embeddings", "POST /v1/images/generations",
                "GET /v1/images/{file}", "POST /v1/audio/speech", "GET /v1/system",
                "POST /v1/admin/load",
            ).forEach { add(JsonPrimitive(it)) }
        })
        put("auth", if (settings.apiKey.isBlank()) "none" else "bearer")
    }

    private fun nullableString(value: String?): JsonElement =
        if (value == null) JsonNull else JsonPrimitive(value)

    private fun healthJson(): JsonObject = buildJsonObject {
        put("status", "ok")
        put("model_loaded", engine.state.value.ready)
        put("model", nullableString(engine.state.value.modelName))
        put("context", engine.state.value.contextSize)
        put("busy", engine.state.value.generating)
        put(
            "uptime_ms",
            if (_state.value.startedAt > 0) System.currentTimeMillis() - _state.value.startedAt else 0,
        )
    }

    private fun modelsJson(): JsonObject = buildJsonObject {
        put("object", "list")
        put("data", buildJsonArray {
            // 已启用的云端接口也列出来 —— 客户端要能**发现**它们才谈得上转发。
            // id 用接口名字（providers 的 name 就是 providerFor 认的那个键）。
            enabledProviders().forEach { p ->
                add(buildJsonObject {
                    put("id", p.name)
                    put("object", "model")
                    put("created", 0)
                    put("owned_by", p.kind.label)
                    put("remote", true)
                    put("downloaded", true)
                    put("size_bytes", 0)
                    put("quant", "")
                    put("params", "")
                    put("context_length", 0)
                    put("active", storage.chatBackend() == p.id)
                })
            }
            modelStore.resolveAll().forEach { m ->
                add(buildJsonObject {
                    put("id", m.id)
                    put("object", "model")
                    put("created", 0)
                    put("owned_by", m.publisher.ifEmpty { "local" })
                    put("downloaded", m.isDownloaded)
                    put("size_bytes", m.sizeBytes)
                    put("quant", m.quant)
                    put("params", m.params)
                    put("context_length", 4096)
                    put("active", engine.state.value.modelName == m.name)
                })
            }
        })
    }

    private fun systemJson(): JsonObject = buildJsonObject {
        put("model", nullableString(engine.state.value.modelName))
        put("context", engine.state.value.contextSize)
        put("threads", engine.state.value.threads)
        put("ready", engine.state.value.ready)
        put("tokens_generated", engine.state.value.tokensGenerated)
        put("tokens_per_second", engine.state.value.lastTokensPerSecond)
        put("native", engine.systemInfo())
        val (models, images, free) = storage.usage()
        put("storage", buildJsonObject {
            put("models_bytes", models)
            put("images_bytes", images)
            put("free_bytes", free)
        })
        put("memory", buildJsonObject {
            put("plan_context", engine.state.value.plan?.recommendedContext ?: 0)
            put("plan_threads", engine.state.value.plan?.recommendedThreads ?: 0)
            put("needs_streaming", engine.state.value.plan?.needsStreaming ?: false)
        })
    }

    // ----------------------------------------------------------------- 转发
    //
    // 「转发」= 手机在这里只当代理：请求原样转给上游接口，响应原样转回客户端。
    //
    // 为什么需要它：很多 OpenAI 客户端（沉浸式翻译、Cherry Studio、IDE 插件）
    // 只会填一个 Base URL。用户把手机当网关之后，那些客户端就能用上手机里
    // 配好的云端模型 —— 也包括云端没有、只有手机本地才有的模型。
    //
    // 判定规则只有一条，写在这里以免各处重复：
    //  1. 请求里的 model 等于某个已启用接口的 id 或名字 → 转给那个接口；
    //  2. model 是 localmind / auto / 空 → 看「默认对话后端」是不是接口；
    //  3. 其余 → 本机模型。

    private fun enabledProviders() = storage.loadRemoteProviders().filter { it.enabled }

    /** 这个 model 名对应哪个上游接口；null = 用本机模型。 */
    private fun providerFor(modelId: String): com.localmind.data.RemoteProvider? {
        val list = enabledProviders()
        if (list.isEmpty()) return null
        list.firstOrNull { it.id == modelId || it.name == modelId }?.let { return it }
        if (modelId.isBlank() || modelId == "localmind" || modelId == "auto") {
            return list.firstOrNull { it.id == storage.chatBackend() }
        }
        return null
    }

    /** 把请求体里的 model 换成本接口配置的模型，其余字段原样保留。 */
    private fun patchModel(rawBody: String, model: String): String = runCatching {
        val obj = json.parseToJsonElement(rawBody).jsonObject.toMutableMap()
        obj["model"] = JsonPrimitive(model)
        JsonObject(obj).toString()
    }.getOrDefault(rawBody)

    /**
     * 转发一次请求。
     *
     * @return true = 已经处理并写了响应；false = 上游不支持或没配上，调用方应走本机。
     */
    private fun proxy(
        socket: Socket,
        provider: com.localmind.data.RemoteProvider,
        upstreamPath: String,
        rawBody: String,
        stream: Boolean,
        logPath: String,
        remote: String,
        started: Long,
        preview: String,
        modelForRequest: String,
    ): Boolean {
        val out = socket.getOutputStream()
        val api = com.localmind.LocalMindApp.of(storage.appContext()).remoteApi
        val patched = patchModel(rawBody, modelForRequest)

        if (stream) writeSseHead(out)
        val sb = StringBuilder()
        val result = runBlocking {
            api.proxyRaw(provider, upstreamPath, patched, stream) { chunk ->
                if (stream && chunk.isBlank()) {
                    // 空行是上游自己的帧分隔符。我们每一段都要重新补一个空行，
                    // 所以这一行**必须丢掉** —— 留着就会变成 \n\n\n\n 这种三方
                    // 客户端不一定认的帧。
                    return@proxyRaw
                }
                if (stream) {
                    // 上游给的是 SSE 行；客户端要的是帧，所以补一个空行。
                    // 行尾的换行要先去掉 —— 上游常常已经带了一个空行，
                    // 不去掉的话每一帧会变成三四个换行（实测 \n\n\n\n）。
                    val frame = chunk.trimEnd('\r', '\n') + "\n\n"
                    synchronized(out) {
                        runCatching {
                            out.write(frame.toByteArray(Charsets.UTF_8))
                            out.flush()
                        }
                    }
                } else {
                    sb.append(chunk)
                }
            }
        }

        result.onSuccess {
            if (!stream) writeRawJson(out, 200, sb.toString())
            record("POST", logPath, 200, started, remote, "转发→" + provider.name + " " + preview, null)
        }.onFailure { t ->
            val msg = t.message ?: t.javaClass.simpleName
            if (!stream) {
                writeJson(out, 502, errorJson("upstream_error", "转发失败：" + msg, "upstream_error"))
            } else {
                synchronized(out) {
                    runCatching {
                        val err = buildJsonObject {
                            put("error", buildJsonObject {
                                put("message", "转发失败：" + msg)
                                put("type", "upstream_error")
                            })
                        }
                        out.write(("data: " + err + "\n\ndata: [DONE]\n\n").toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            }
            record("POST", logPath, 502, started, remote, preview, msg)
        }
        return true
    }

    /**
     * 「翻译转发」：客户端说的是 OpenAI，上游说的是 Anthropic / Gemini。
     *
     * 为什么不能像 OpenAI 那样原样转发：请求形状与响应形状都不同（system 的位置、
     * content 是不是块数组、流式事件名）。所以这里做一次**双向翻译**：
     * 用 RemoteApi 的适配器把请求发出去，再把结果拼回 OpenAI 的形状。
     *
     * 好处是客户端那边完全无感 —— 任何只会填 Base URL 的 OpenAI 客户端，
     * 都能用上手机里配的 Claude / Gemini。
     */
    private fun proxyTranslated(
        socket: Socket,
        provider: com.localmind.data.RemoteProvider,
        root: JsonObject,
        stream: Boolean,
        modelId: String,
        remote: String,
        started: Long,
        preview: String,
    ) {
        val out = socket.getOutputStream()
        val api = com.localmind.LocalMindApp.of(storage.appContext()).remoteApi
        val base = storage.loadGenerationSettings()
        val temperature = root["temperature"]?.jsonPrimitive?.floatOrNull ?: base.temperature
        val maxTokens = root["max_tokens"]?.jsonPrimitive?.intOrNull ?: base.maxTokens
        val messages = parseMessages(root).map { (role, text) ->
            com.localmind.service.RemoteApi.Msg(role.name.lowercase(), text)
        }
        val id = "chatcmpl-" + System.currentTimeMillis().toString(16)
        val accumulated = StringBuilder()
        var first = true

        if (stream) writeSseHead(out)
        val result = runBlocking {
            api.chat(
                provider = provider,
                model = provider.model,
                messages = messages,
                temperature = temperature,
                maxTokens = maxTokens,
                onToken = if (stream) {
                    { piece ->
                        accumulated.append(piece)
                        val sb = StringBuilder()
                        if (first) {
                            first = false
                            sb.append(chunkJson(id, modelId, buildJsonObject { put("role", "assistant") }, null))
                        }
                        sb.append(chunkJson(id, modelId, buildJsonObject { put("content", piece) }, null))
                        synchronized(out) {
                            runCatching {
                                out.write(sb.toString().toByteArray(Charsets.UTF_8))
                                out.flush()
                            }
                        }
                    }
                } else null,
            )
        }

        result.onSuccess { text ->
            val answer = text.ifBlank { accumulated.toString() }
            if (stream) {
                synchronized(out) {
                    runCatching {
                        out.write(
                            (chunkJson(id, modelId, buildJsonObject { }, "stop") + "data: [DONE]\n\n")
                                .toByteArray(Charsets.UTF_8),
                        )
                        out.flush()
                    }
                }
            } else {
                writeJson(out, 200, buildJsonObject {
                    put("id", id)
                    put("object", "chat.completion")
                    put("created", System.currentTimeMillis() / 1000)
                    put("model", modelId)
                    put("choices", buildJsonArray {
                        add(buildJsonObject {
                            put("index", 0)
                            put("message", buildJsonObject {
                                put("role", "assistant")
                                put("content", answer)
                            })
                            put("finish_reason", "stop")
                        })
                    })
                })
            }
            record("POST", "/v1/chat/completions", 200, started, remote,
                   "翻译转发→" + provider.name + " " + preview, null)
        }.onFailure { t ->
            val msg = t.message ?: t.javaClass.simpleName
            if (stream) {
                synchronized(out) {
                    runCatching {
                        out.write(("data: [DONE]\n\n").toByteArray(Charsets.UTF_8))
                        out.flush()
                    }
                }
            } else {
                writeJson(out, 502, errorJson("upstream_error", "转发失败：" + msg, "upstream_error"))
            }
            record("POST", "/v1/chat/completions", 502, started, remote, preview, msg)
        }
    }
    /** 原样写一段 JSON（转发时上游的响应体已经是成品，不能再解析重组）。 */
    private fun writeRawJson(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val writer = newHeadWriter(out)
        writeHead(writer, code, "application/json", bytes.size, emptyMap())
        out.write(bytes)
        out.flush()
    }
    // ----------------------------------------------------------------- chat

    private fun parseMessages(root: JsonObject): List<Pair<Role, String>> {
        val array = root["messages"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val roleName = obj["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val content = when (val c = obj["content"]) {
                is JsonPrimitive -> c.contentOrNull ?: ""
                is JsonArray -> c.mapNotNull { part ->
                    (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                }.joinToString("\n")

                else -> ""
            }
            when (roleName) {
                "system" -> Role.SYSTEM
                "assistant" -> Role.ASSISTANT
                else -> Role.USER
            } to content
        }
    }

    private fun handleChat(socket: Socket, body: String, remote: String, started: Long) {
        val out = socket.getOutputStream()
        val root = jsonOf(body)
        if (root == null) {
            writeJson(out, 400, errorJson("invalid_request_error", "body is not valid JSON", "invalid_json"))
            record("POST", "/v1/chat/completions", 400, started, remote, "", "invalid json")
            return
        }
        val modelNameForRoute = root["model"]?.jsonPrimitive?.contentOrNull ?: "localmind"
        // ---- 先看要不要转发 ----
        //
        // ⚠️ 顺序很重要：转发判定必须在「本机有没有加载模型」**之前**。
        // 手机上一个本地模型都没下、但配了云端接口时，客户端照样应该能用 ——
        // 否则用户会看到「no model loaded」，而他明明配好了云端。
        val upstream = providerFor(modelNameForRoute)
        if (upstream != null) {
            val wantsStream = root["stream"]?.jsonPrimitive?.booleanOrNull ?: false
            val previewText = TextUtils.preview(parseMessages(root).lastOrNull { it.first == Role.USER }?.second.orEmpty())
            // OpenAI 兼容的上游可以**原样**转发（连 tools / response_format 都保住了）；
            // 另外两种协议的请求与响应形状都不一样，走翻译转发。
            if (upstream.kind != com.localmind.data.ProviderKind.OPENAI) {
                proxyTranslated(
                    socket = socket,
                    provider = upstream,
                    root = root,
                    stream = wantsStream,
                    modelId = modelNameForRoute,
                    remote = remote,
                    started = started,
                    preview = previewText,
                )
                return
            }
            proxy(
                socket = socket,
                provider = upstream,
                upstreamPath = "/chat/completions",
                rawBody = body,
                stream = wantsStream,
                logPath = "/v1/chat/completions",
                remote = remote,
                started = started,
                preview = previewText,
                modelForRequest = upstream.model,
            )
            return
        }

        if (!engine.state.value.ready) {
            writeJson(out, 503, errorJson("server_error", "no model loaded on the device", "model_not_loaded"))
            record("POST", "/v1/chat/completions", 503, started, remote, "", "no model")
            return
        }

        val messages = parseMessages(root)
        if (messages.isEmpty()) {
            writeJson(out, 400, errorJson("invalid_request_error", "messages is required and must not be empty", "missing_messages"))
            record("POST", "/v1/chat/completions", 400, started, remote, "", "missing messages")
            return
        }

        val base = storage.loadGenerationSettings()
        val gen = base.copy(
            temperature = root["temperature"]?.jsonPrimitive?.floatOrNull ?: base.temperature,
            topP = root["top_p"]?.jsonPrimitive?.floatOrNull ?: base.topP,
            maxTokens = root["max_tokens"]?.jsonPrimitive?.intOrNull ?: base.maxTokens,
            seed = root["seed"]?.jsonPrimitive?.intOrNull ?: base.seed,
        )
        val stream = root["stream"]?.jsonPrimitive?.booleanOrNull ?: false
        val modelId = root["model"]?.jsonPrimitive?.contentOrNull ?: "localmind"
        val id = "chatcmpl-" + System.currentTimeMillis().toString(16)
        val preview = TextUtils.preview(messages.lastOrNull { it.first == Role.USER }?.second.orEmpty())

        if (stream) {
            writeSseHead(out)
            var first = true
            val result = engineLock.withLockBlocking {
                runBlocking {
                    engine.chat(messages, gen, onToken = { piece ->
                        val sb = StringBuilder()
                        if (first) {
                            first = false
                            sb.append(chunkJson(id, modelId, buildJsonObject { put("role", "assistant") }, null))
                        }
                        sb.append(chunkJson(id, modelId, buildJsonObject { put("content", piece) }, null))
                        synchronized(out) {
                            runCatching {
                                out.write(sb.toString().toByteArray(Charsets.UTF_8))
                                out.flush()
                            }
                        }
                    })
                }
            }
            val tail = chunkJson(id, modelId, buildJsonObject { }, if (result.isSuccess) "stop" else "error") +
                "data: [DONE]\n\n"
            synchronized(out) {
                runCatching {
                    out.write(tail.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
            record(
                "POST", "/v1/chat/completions", if (result.isSuccess) 200 else 500,
                started, remote, preview, result.exceptionOrNull()?.message,
                completionTokens = engine.state.value.tokensGenerated,
            )
            return
        }

        val result = engineLock.withLockBlocking { runBlocking { engine.chat(messages, gen) } }
        result.onSuccess { text ->
            val payload = buildJsonObject {
                put("id", id)
                put("object", "chat.completion")
                put("created", System.currentTimeMillis() / 1000)
                put("model", modelId)
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put(
                            "message",
                            buildJsonObject {
                                put("role", "assistant")
                                put("content", text)
                            },
                        )
                        put("finish_reason", "stop")
                    })
                })
                put("usage", buildJsonObject {
                    put("prompt_tokens", engine.countTokens(messages.joinToString("\n") { it.second }).coerceAtLeast(0))
                    put("completion_tokens", engine.state.value.tokensGenerated)
                    put("total_tokens", engine.state.value.tokensGenerated)
                })
            }
            writeJson(out, 200, payload)
            record(
                "POST", "/v1/chat/completions", 200, started, remote, preview, null,
                completionTokens = engine.state.value.tokensGenerated,
            )
        }.onFailure { t ->
            writeJson(out, 500, errorJson("server_error", t.message ?: "generation failed", "generation_failed"))
            record("POST", "/v1/chat/completions", 500, started, remote, preview, t.message)
        }
    }

    private fun chunkJson(id: String, model: String, delta: JsonObject, finish: String?): String {
        val payload = buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", delta)
                    put("finish_reason", nullableString(finish))
                })
            })
        }
        return "data: $payload\n\n"
    }

    private fun handleCompletion(socket: Socket, body: String, remote: String, started: Long) {
        val out = socket.getOutputStream()
        val root = jsonOf(body)
        val prompt = root?.get("prompt")?.jsonPrimitive?.contentOrNull
        if (prompt.isNullOrBlank()) {
            writeJson(out, 400, errorJson("invalid_request_error", "prompt is required", "missing_prompt"))
            record("POST", "/v1/completions", 400, started, remote, "", "missing prompt")
            return
        }
        if (!engine.state.value.ready) {
            writeJson(out, 503, errorJson("server_error", "no model loaded on the device", "model_not_loaded"))
            record("POST", "/v1/completions", 503, started, remote, "", "no model")
            return
        }
        val base = storage.loadGenerationSettings()
        val gen = base.copy(
            temperature = root["temperature"]?.jsonPrimitive?.floatOrNull ?: base.temperature,
            maxTokens = root["max_tokens"]?.jsonPrimitive?.intOrNull ?: base.maxTokens,
        )
        val result = engineLock.withLockBlocking {
            runBlocking { engine.chat(listOf(Role.USER to prompt), gen) }
        }
        result.onSuccess { text ->
            writeJson(out, 200, buildJsonObject {
                put("id", "cmpl-" + System.currentTimeMillis().toString(16))
                put("object", "text_completion")
                put("created", System.currentTimeMillis() / 1000)
                put("model", "localmind")
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("text", text)
                        put("finish_reason", "stop")
                    })
                })
            })
            record("POST", "/v1/completions", 200, started, remote, TextUtils.preview(prompt), null)
        }.onFailure { t ->
            writeJson(out, 500, errorJson("server_error", t.message ?: "failed", "generation_failed"))
            record("POST", "/v1/completions", 500, started, remote, TextUtils.preview(prompt), t.message)
        }
    }

    private fun handleEmbeddings(socket: Socket, body: String, remote: String, started: Long) {
        val out = socket.getOutputStream()
        val input = jsonOf(body)?.get("input")
        val texts: List<String> = when (input) {
            is JsonPrimitive -> listOf(input.contentOrNull ?: "")
            is JsonArray -> input.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> emptyList()
        }
        if (texts.isEmpty()) {
            writeJson(out, 400, errorJson("invalid_request_error", "input is required", "missing_input"))
            record("POST", "/v1/embeddings", 400, started, remote, "", "missing input")
            return
        }
        writeJson(out, 200, buildJsonObject {
            put("object", "list")
            put("model", "localmind-hash-384")
            put("data", buildJsonArray {
                texts.forEachIndexed { index, text ->
                    add(buildJsonObject {
                        put("object", "embedding")
                        put("index", index)
                        put("embedding", buildJsonArray {
                            HashingEmbedder.embed(text).forEach { add(JsonPrimitive(it)) }
                        })
                    })
                }
            })
            put("usage", buildJsonObject {
                put("prompt_tokens", texts.sumOf { it.length / 3 })
                put("total_tokens", texts.sumOf { it.length / 3 })
            })
        })
        record("POST", "/v1/embeddings", 200, started, remote, TextUtils.preview(texts.first()), null)
    }

    private fun handleImage(socket: Socket, body: String, remote: String, started: Long) {
        val out = socket.getOutputStream()
        if (!settings.allowImage) {
            writeJson(out, 403, errorJson("permission_error", "image generation is disabled on the device", "image_disabled"))
            record("POST", "/v1/images/generations", 403, started, remote, "", "disabled")
            return
        }
        val root = jsonOf(body)
        val prompt = root?.get("prompt")?.jsonPrimitive?.contentOrNull
        if (prompt.isNullOrBlank()) {
            writeJson(out, 400, errorJson("invalid_request_error", "prompt is required", "missing_prompt"))
            record("POST", "/v1/images/generations", 400, started, remote, "", "missing prompt")
            return
        }
        // ---- 转发：model 指向某个接口时交给它画 ----
        //
        // 云端的生图模型（dall-e-3 之类）画得比手机快得多，而用户在这个应用里
        // 已经把接口配好了。判据和对话那条完全一致（providerFor），不另写一份。
        val imageUpstream = providerFor(root["model"]?.jsonPrimitive?.contentOrNull.orEmpty())
        if (imageUpstream != null) {
            val sizeText = root["size"]?.jsonPrimitive?.contentOrNull ?: "1024x1024"
            val bytes = runBlocking {
                com.localmind.LocalMindApp.of(storage.appContext())
                    .remoteApi.generateImage(imageUpstream, prompt, sizeText)
            }
            bytes.onSuccess { data ->
                val name = ImageEngine.fileName(prompt, 0, 0, 0, 0).replace(".png", "_remote.png")
                val file = java.io.File(storage.imagesDir, name)
                runCatching { file.writeBytes(data) }
                writeJson(out, 200, buildJsonObject {
                    put("created", System.currentTimeMillis() / 1000)
                    put("data", buildJsonArray {
                        add(buildJsonObject {
                            put("url", "http://" + hostHeader(socket) + "/v1/images/" + file.name)
                            put("revised_prompt", prompt)
                        })
                    })
                })
                record("POST", "/v1/images/generations", 200, started, remote,
                       "转发→" + imageUpstream.name + " " + TextUtils.preview(prompt), null)
            }.onFailure { t ->
                writeJson(out, 502, errorJson("upstream_error",
                    "转发失败：" + (t.message ?: t.javaClass.simpleName), "upstream_error"))
                record("POST", "/v1/images/generations", 502, started, remote, TextUtils.preview(prompt), t.message)
            }
            return
        }

        val defaults = storage.loadImageSettings()
        val (w, h) = parseSize(root["size"]?.jsonPrimitive?.contentOrNull ?: "${defaults.width}x${defaults.height}")
        val seed = root["seed"]?.jsonPrimitive?.intOrNull ?: ImageEngine.randomSeed()
        val styleId = root["style"]?.jsonPrimitive?.intOrNull
            ?: ImageStyle.entries.firstOrNull { it.name.equals(defaults.style.name, true) }?.nativeId
            ?: defaults.style.nativeId

        val generated = runBlocking { ImageEngine.generate(storage, prompt, w, h, seed, styleId) }
        val file = generated.getOrElse { t ->
            writeJson(out, 500, errorJson("server_error", t.message ?: "render failed", "render_failed"))
            record("POST", "/v1/images/generations", 500, started, remote, TextUtils.preview(prompt), t.message)
            return
        }

        writeJson(out, 200, buildJsonObject {
            put("created", System.currentTimeMillis() / 1000)
            put("data", buildJsonArray {
                add(buildJsonObject {
                    put("url", "http://${hostHeader(socket)}/v1/images/${file.name}")
                    put("revised_prompt", prompt)
                    put("seed", seed)
                })
            })
        })
        record("POST", "/v1/images/generations", 200, started, remote, TextUtils.preview(prompt), null)
    }

    private fun parseSize(size: String): Pair<Int, Int> {
        // 解析不出尺寸时用 512²（1.0.3 起的新默认）：768² 是它的 2.25 倍耗时，
        // 而客户端没写 size 的时候本来就该用一个稳妥的默认值。
        val m = Regex("""(\d{2,4})\s*[x*]\s*(\d{2,4})""").find(size) ?: return 512 to 512
        return m.groupValues[1].toInt().coerceIn(128, 2048) to m.groupValues[2].toInt().coerceIn(128, 2048)
    }

    private fun hostHeader(socket: Socket): String {
        val host = socket.localAddress?.hostAddress ?: "127.0.0.1"
        return if (host.contains(':')) "[$host]:${_state.value.port}" else "$host:${_state.value.port}"
    }

    private fun handleImageFetch(socket: Socket, name: String, remote: String, started: Long) {
        // Generated file names can contain non-ASCII (the prompt slug), so the path
        // arrives percent-encoded and must be decoded before hitting the filesystem.
        val decoded = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
        val safe = decoded.substringAfterLast('/')
        val file = java.io.File(storage.imagesDir, safe)
        val inside = runCatching { file.canonicalPath.startsWith(storage.imagesDir.canonicalPath) }.getOrDefault(false)
        if (!file.exists() || !inside) {
            writeJson(socket.getOutputStream(), 404, errorJson("invalid_request_error", "no such image", "not_found"))
            record("GET", "/v1/images/$safe", 404, started, remote, "", "not found")
            return
        }
        writeBytes(
            socket.getOutputStream(), 200, "image/png", file.readBytes(),
            extra = mapOf("Cache-Control" to "public, max-age=86400"),
        )
        record("GET", "/v1/images/$safe", 200, started, remote, safe, null)
    }

    private fun handleSpeech(socket: Socket, body: String, remote: String, started: Long) {
        val out = socket.getOutputStream()
        val root = jsonOf(body)
        val text = root?.get("input")?.jsonPrimitive?.contentOrNull
            ?: root?.get("text")?.jsonPrimitive?.contentOrNull
        if (text.isNullOrBlank()) {
            writeJson(out, 400, errorJson("invalid_request_error", "input is required", "missing_input"))
            record("POST", "/v1/audio/speech", 400, started, remote, "", "missing input")
            return
        }

        val current = storage.loadVoiceSettings()
        val emotion = root?.get("emotion")?.jsonPrimitive?.contentOrNull
            ?.let { name -> Emotion.entries.firstOrNull { it.name.equals(name, true) } }
            ?: current.emotion
        val voiceSettings = current.copy(
            emotion = emotion,
            speed = (root?.get("speed")?.jsonPrimitive?.floatOrNull ?: current.speed).coerceIn(0.4f, 2.5f),
            pitch = (root?.get("pitch")?.jsonPrimitive?.floatOrNull ?: current.pitch).coerceIn(0.4f, 2.0f),
        )

        val wav = runBlocking { TtsEngine.renderToWav(text, voiceSettings, tts) }
        wav.onSuccess { bytes ->
            writeBytes(out, 200, "audio/wav", bytes)
            record("POST", "/v1/audio/speech", 200, started, remote, TextUtils.preview(text), null)
        }.onFailure { t ->
            writeJson(out, 500, errorJson("server_error", t.message ?: "tts failed", "tts_failed"))
            record("POST", "/v1/audio/speech", 500, started, remote, TextUtils.preview(text), t.message)
        }
    }

    private fun handleAdminLoad(socket: Socket, body: String, remote: String, started: Long) {
        val out = socket.getOutputStream()
        if (!settings.allowModelSwitch) {
            writeJson(out, 403, errorJson("permission_error", "model switching is disabled", "switch_disabled"))
            record("POST", "/v1/admin/load", 403, started, remote, "", "disabled")
            return
        }
        val id = jsonOf(body)?.get("model")?.jsonPrimitive?.contentOrNull
        val model = modelStore.resolveAll().firstOrNull { it.id == id || it.name.equals(id, true) }
        if (model == null || !model.isDownloaded) {
            writeJson(out, 404, errorJson("invalid_request_error", "model not found or not downloaded", "not_found"))
            record("POST", "/v1/admin/load", 404, started, remote, id ?: "", "not found")
            return
        }
        // 带上视觉投影：不带的话，用 API 切过去的模型会"看不见图"，
        // 而界面切过去就行 —— 同一件事两条路两种结果，是最难查的那类 bug。
        val result = runBlocking {
            engine.load(model, storage.loadGenerationSettings(), model.mmprojPathOn(storage.modelsDir))
        }
        result.onSuccess {
            storage.setLastModelId(model.id)
            writeJson(out, 200, buildJsonObject {
                put("status", "loaded")
                put("model", model.name)
                put("context", engine.state.value.contextSize)
            })
            record("POST", "/v1/admin/load", 200, started, remote, model.name, null)
        }.onFailure { t ->
            writeJson(out, 500, errorJson("server_error", t.message ?: "load failed", "load_failed"))
            record("POST", "/v1/admin/load", 500, started, remote, model.name, t.message)
        }
    }

    companion object {
        fun lanAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> nif.inetAddresses.toList().map { nif.name to it } }
                .filter { (_, addr) -> addr is Inet4Address && !addr.isLoopbackAddress }
                .map { (_, addr) -> "http://${addr.hostAddress}" }
                .distinct()
        }.getOrDefault(emptyList())
    }
}

/**
 * Deterministic 384-dimension hashing embedder (feature hashing over character
 * n-grams). Needs no model file, so `/v1/embeddings` works the moment the API is
 * enabled. Swap in a real BGE GGUF later; the endpoint shape stays the same.
 */
object HashingEmbedder {
    const val DIM = 384

    fun embed(text: String): FloatArray {
        val vector = FloatArray(DIM)
        val normalized = text.lowercase().trim()
        if (normalized.isEmpty()) return vector
        for (n in 1..3) {
            for (i in 0..(normalized.length - n)) {
                val gram = normalized.substring(i, i + n)
                var hash = 2166136261u
                for (ch in gram) {
                    hash = hash xor ch.code.toUInt()
                    hash *= 16777619u
                }
                val index = (hash % DIM.toUInt()).toInt()
                val sign = if ((hash / DIM.toUInt()) % 2u == 0u) 1f else -1f
                vector[index] += sign
            }
        }
        var norm = 0f
        for (v in vector) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 1e-6f) for (i in vector.indices) vector[i] /= norm
        return vector
    }
}

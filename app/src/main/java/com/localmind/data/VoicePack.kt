package com.localmind.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 语音音色插件的数据格式（`voices.json`）。
 *
 * ## 为什么音色清单也不在主程序里
 *
 * 和模型库同一个理由，而且更极端：音色模型是 13–115 MB 一个，主程序内置一份
 * 清单只是"把地址写死在 APK 里" —— 地址会失效、会出新音色、会换镜像，
 * 每改一次都要发版。做成插件之后换个 zip 就行。
 *
 * ## 音色从哪来
 *
 * 目前唯一的来源是 **sherpa-onnx 官方 Release 的 `tts-models` 标签**：
 *
 * ```
 * https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/<id>.tar.bz2
 * ```
 *
 * 所以 [VoiceEntry.id] 就是那个 tarball 的名字（不含扩展名）。
 * 想加自己的音色，只要那个 tarball 里的布局能被认出来（主程序是扫目录找最大的
 * `.onnx`，不写死路径），就能用。
 *
 * ## ⚠️ 云端音色已被移除（2026-10-15）
 *
 * 这里曾经有一个 `deployment: "cloud"` 通道（腾讯云 TTS）：插件声明一个云端音色，
 * 用户填 SecretId/SecretKey，文字发到腾讯云合成。**整条链路已经删掉**，
 * 原因和影响：
 *
 *  * 这个应用的核心承诺是**完全离线**。"文字会离开这台设备"是一个不同性质的
 *    功能，不是性能取舍；它已经在界面上占了很大一块（密钥、音色、隐私说明），
 *    而用户并没有密钥可以实测，等于一块永远无法验证的代码。
 *  * 所以现在**只有本地音色**：模型跑在手机上，断网能用，文字不出设备。
 *
 * 老插件如果还写着 `"provider": "tencent"` 或 `"deployment": "cloud"`，
 * 那些音色会被**跳过**（不是静默当成本地），并在安装报告里说明数量 ——
 * 静默降级成一个"能点但发音不对"的本地音色是最糟的结果。
 */
@Serializable
data class VoicePackFile(
    val version: Int = 1,
    val packId: String = "",
    val packName: String = "",
    val author: String = "",
    val license: String = "",
    val home: String = "",
    val note: String = "",
    /** 这个包提供的音色。 */
    val voices: List<VoiceEntry> = emptyList(),
) {
    val isValid: Boolean get() = packId.isNotBlank() && packName.isNotBlank() && voices.isNotEmpty()
}

/** 一个可下载的神经音色。**只能是本地推理的**（原因见文件头）。 */
@Serializable
data class VoiceEntry(
    /** 稳定 id。默认就等于官方 tarball 名。 */
    val id: String,
    /** 中文显示名，例如 "小雅（女声 · 普通话）"。 */
    val label: String,
    val description: String = "",
    /** 徽标，例如 "轻量" / "角色"。 */
    val badge: String = "",
    /**
     * 压缩包地址。留空则按官方 Release 约定拼：
     * `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/<id>.tar.bz2`
     */
    val url: String = "",
    /** 压缩包字节数（Content-Length）。0 = 未知，进度就只能按已下载量显示。 */
    val sizeBytes: Long = 0,
    /** 备用地址，按顺序回退。 */
    val mirrors: List<String> = emptyList(),
    /** 试听用的一句话。音色之间的差别在短句上听不出来。 */
    val sampleText: String = "你好，我是运行在你手机上的语音模型。",
) {
    /** 有可下载的 id 才算可用。 */
    val isUsable: Boolean get() = id.isNotBlank()

    /**
     * 映射成模型页能显示的一条。
     *
     * ## 为什么要做这个映射，而不是让语音条目各显示各的
     *
     * 用户明确要求「模型库里要能看到语音模型」。但语音模型**不能走 GGUF 下载器**：
     * 它是 sherpa-onnx 的 `.tar.bz2` 音色包，必须解压到 `files/voices/` 并交给
     * [com.localmind.service.NeuralVoiceModels] 管理。
     *
     * 所以这里映射出一条**只用于展示**的 `LocalModel`（`voice = true`），
     * 模型页认出这个标记后**改调音色下载器**。这样用户在一个地方就能看到
     * 全部可下载的东西，而下载路径仍然是各自正确的那一条。
     *
     * `id` 加 `voice:` 前缀是为了避免和同名 GGUF 撞 key（列表里用 id 做 key）。
     */
    fun toLocalModel(license: String = "见音色原始许可"): com.localmind.data.LocalModel = com.localmind.data.LocalModel(
        id = "voice:$id",
        name = label,
        repoId = "k2-fsa/sherpa-onnx",
        fileName = "$id.tar.bz2",
        // 进度条要准就得知道总大小；0 也合法（那就只按已下载量显示）。
        sizeBytes = sizeBytes,
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$id.tar.bz2",
        mirrors = listOf("https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$id.tar.bz2"),
        license = license,
        publisher = "sherpa-onnx",
        family = "离线语音",
        params = "",
        quant = "",
        description = description.ifBlank { "离线语音音色" } + "（下载后到「我的 → 语音与音色」里使用）",
        tags = buildList {
            add("语音")
            if (badge.isNotBlank()) add(badge)
        },
        // 音色模型不大，2 GB 内存就能跑；给个下限免得在「推荐」里被当成聊天模型。
        recommendedRamGb = ((sizeBytes / (1024L * 1024 * 1024)).toInt() + 2).coerceAtLeast(2),
        voice = true,
    )
}


/** 语音包的解析与校验。失败要给**能直接显示给用户**的中文原因。 */
object VoicePacks {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    sealed interface ParseResult {
        /**
         * @param droppedCloud 因为"声明了云端"而被跳过的音色数。安装报告要如实说，
         *   否则用户会以为插件少提供的音色是自己弄丢了（见文件头）。
         */
        data class Ok(val pack: VoicePackFile, val droppedCloud: Int = 0) : ParseResult
        data class Failed(val reason: String) : ParseResult
    }

    fun parse(text: String): ParseResult {
        if (text.isBlank()) return ParseResult.Failed("voices.json 是空的")
        val pack = runCatching { json.decodeFromString<VoicePackFile>(text) }.getOrElse { t ->
            return ParseResult.Failed("voices.json 格式错误：${t.message?.take(140) ?: "解析失败"}")
        }
        if (pack.packId.isBlank()) return ParseResult.Failed("voices.json 缺少 packId")
        if (pack.packName.isBlank()) return ParseResult.Failed("voices.json 缺少 packName")
        if (pack.voices.isEmpty()) return ParseResult.Failed("voices.json 里 voices 是空的")
        val bad = pack.voices.filter { it.id.isBlank() || it.label.isBlank() }
        if (bad.isNotEmpty()) {
            return ParseResult.Failed("有 ${bad.size} 个音色缺少 id 或 label")
        }

        // 老插件可能还声明着云端音色（见文件头的说明）。**跳过**它们，
        // 而不是当成本地音色 —— 后者会让用户点下去听见一个不对的声音。
        val cloud = cloudDeclaredIds(text)
        val local = if (cloud.isEmpty()) pack.voices else pack.voices.filter { it.id !in cloud }
        if (local.isEmpty()) {
            return ParseResult.Failed(
                "这个包里 ${cloud.size} 个音色都是云端音色，而云端合成已经被移除了。" +
                    "请改用本地音色（sherpa-onnx 的 tts-models）"
            )
        }
        return ParseResult.Ok(pack.copy(voices = local), droppedCloud = cloud.size)
    }

    /**
     * 找出声明了云端字段的音色 id。
     *
     * 用文本匹配而不是反序列化字段：云端字段已经从 [VoiceEntry] 里删掉了，
     * 而 `ignoreUnknownKeys = true` 会把它们吃干净 —— 那样一个"说了自己是云端
     * 音色"的条目会被静默当成本地音色，是这次移除里最容易出的一个错。
     */
    private fun cloudDeclaredIds(text: String): Set<String> {
        val out = mutableSetOf<String>()
        // 每个 voice 对象切一刀，避免跨对象误判。
        Regex("\\{[^{}]*\\}").findAll(text).forEach { m ->
            val obj = m.value
            val isCloud = Regex("\"deployment\"\\s*:\\s*\"cloud\"", RegexOption.IGNORE_CASE)
                .containsMatchIn(obj) ||
                Regex("\"provider\"\\s*:\\s*\"[^\"]+\"").containsMatchIn(obj) ||
                Regex("\"cloudVoiceId\"\\s*:\\s*\"[^\"]+\"").containsMatchIn(obj)
            if (isCloud) {
                Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(obj)?.groupValues?.get(1)?.let { out += it }
            }
        }
        return out
    }

    fun encode(pack: VoicePackFile): String = json.encodeToString(VoicePackFile.serializer(), pack)

    /** 官方 Release 的约定地址。 */
    fun defaultUrl(id: String): String =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$id.tar.bz2"

    /** 官方 Release 的代理地址（直连会随机超时，实测 gh-proxy 稳定）。 */
    fun proxyUrl(id: String): String =
        "https://gh-proxy.com/" + defaultUrl(id)
}

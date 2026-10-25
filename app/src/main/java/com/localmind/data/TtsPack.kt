package com.localmind.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 语音引擎插件（能力 id = `tts_engine`，内容文件 = `tts.json`）。
 *
 * ## 为什么需要它
 *
 * 原来语音只有两条路：系统 TTS（永远在，音色看 ROM）、本机 sherpa-onnx VITS
 * （离线、跟机型无关，但只有 8~17 个固定音色）。这两条都要求**模型在手机上**。
 *
 * 而 Breeze TTS 2 这类"手机跑不动"的模型（3.47B、BF16 约 7 GB、官方只有
 * CUDA/PyTorch 推理路径）唯一的用法是在 PC / GPU 服务器上自建服务，手机当客户端。
 * 把它做成插件而不是内置，是因为：
 *
 *  1. 绝大多数用户没有那个服务，装了也用不了；
 *  2. 端点、密钥、模型名都是用户自己的，天然是配置数据；
 *  3. 换一个引擎（Breeze / 任意 OpenAI 兼容 TTS）不该发版。
 *
 * ## 数据里**不放**的东西
 *
 * 不放权重、不放二进制、不执行代码。这里只有"怎么连上那个服务"。
 */
@Serializable
data class TtsVoiceSpec(
    /** 传给服务的音色 id（Breeze 是说话人槽位，如 `S0`；OpenAI 是 `alloy` 这类名字）。 */
    val id: String,
    val label: String = "",
    val note: String = "",
)

/** 一个可用的语音引擎。 */
@Serializable
data class TtsEngineSpec(
    /** 稳定 id，同一个插件里不能重复。 */
    val id: String,
    val label: String,
    /**
     * 协议：
     *  - `breeze`：Breeze TTS 2 的 `POST /v1/audio/speech`（multipart 表单，
     *    字段 text / instruction / ref_audio / ref_text / cfg_scale / seed），
     *    返回流式 PCM。
     *  - `openai`：OpenAI 兼容的 `POST /v1/audio/speech`（JSON，
     *    字段 model / input / voice / response_format），返回音频文件。
     */
    val kind: String = "breeze",
    /** 形如 `http://192.168.1.10:7860`（不带 /v1 也会自动补）。 */
    val baseUrl: String = "",
    /** 空 = 不需要鉴权。 */
    val apiKey: String = "",
    /** 服务端模型名。Breeze 用仓库 id，OpenAI 用 `tts-1` 之类。 */
    val model: String = "",
    val defaultVoice: String = "S0",
    /** 默认演技/音色描述（Breeze 的 instruction）。 */
    val defaultInstruction: String = "",
    /** 指令强度（Breeze 建议 4）。 */
    val cfgScale: Float = 4f,
    /** 服务返回的采样率。Breeze 是 24000。 */
    val sampleRate: Int = 24000,
    /** `pcm_s16le`（Breeze）或 `wav`（OpenAI）。 */
    val format: String = "pcm_s16le",
    /** 给用户看的一句话：这个引擎在哪跑、要不要联网。 */
    val notes: String = "",
    val voices: List<TtsVoiceSpec> = emptyList(),
) {
    val endpoint: String
        get() {
            val base = baseUrl.trim().trimEnd('/')
            if (base.isEmpty()) return ""
            return if (base.endsWith("/v1")) "$base/audio/speech" else "$base/v1/audio/speech"
        }

    val isBreeze: Boolean get() = kind == "breeze"

    /** 能不能直接拿来合成：有地址就算能用，剩下的问题会在请求时报出来。 */
    val usable: Boolean get() = endpoint.isNotBlank()
}

/** 一个完整的语音引擎包。 */
@Serializable
data class TtsPackFile(
    val version: Int = 1,
    val packId: String = "",
    val packName: String = "",
    val author: String = "",
    val description: String = "",
    val note: String = "",
    val engines: List<TtsEngineSpec> = emptyList(),
) {
    val isValid: Boolean get() = packId.isNotBlank() && packName.isNotBlank()
}

/** `tts.json` 的解析与校验。 */
object TtsPacks {

    val KINDS: Set<String> = setOf("breeze", "openai")
    val FORMATS: Set<String> = setOf("pcm_s16le", "wav")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    sealed interface ParseResult {
        data class Ok(val pack: TtsPackFile) : ParseResult
        data class Failed(val reason: String) : ParseResult
    }

    fun parse(text: String): ParseResult {
        if (text.isBlank()) return ParseResult.Failed("tts.json 是空的")
        val pack = runCatching { json.decodeFromString<TtsPackFile>(text) }.getOrElse { t ->
            return ParseResult.Failed("tts.json 格式错误：${t.message?.take(140) ?: "解析失败"}")
        }
        if (pack.packId.isBlank()) return ParseResult.Failed("tts.json 缺少 packId")
        if (pack.packName.isBlank()) return ParseResult.Failed("tts.json 缺少 packName")
        if (pack.engines.isEmpty()) return ParseResult.Failed("tts.json 里没有 engines")

        val dup = pack.engines.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) return ParseResult.Failed("engines 里有重复的 id：${dup.joinToString()}")

        for (e in pack.engines) {
            if (e.id.isBlank() || e.label.isBlank()) {
                return ParseResult.Failed("engines 里有一项缺少 id 或 label")
            }
            if (e.kind !in KINDS) {
                return ParseResult.Failed("引擎 ${e.id} 的 kind 不认识：${e.kind}（只能是 ${KINDS.joinToString("、")}）")
            }
            if (e.format !in FORMATS) {
                return ParseResult.Failed("引擎 ${e.id} 的 format 不认识：${e.format}（只能是 ${FORMATS.joinToString("、")}）")
            }
            if (e.baseUrl.isNotBlank() &&
                !e.baseUrl.startsWith("http://") && !e.baseUrl.startsWith("https://")
            ) {
                return ParseResult.Failed("引擎 ${e.id} 的 baseUrl 要以 http:// 或 https:// 开头")
            }
            if (e.sampleRate !in 8000..48000) {
                return ParseResult.Failed("引擎 ${e.id} 的 sampleRate 应在 8000~48000 之间")
            }
        }
        return ParseResult.Ok(pack)
    }

    fun encode(pack: TtsPackFile): String = json.encodeToString(TtsPackFile.serializer(), pack)

    /** 装完之后要提醒用户的事（不是错误）。 */
    fun warnings(pack: TtsPackFile): List<String> = pack.engines
        .filter { it.baseUrl.isBlank() }
        .map { "引擎 ${it.label} 还没填地址，去「Breeze 语音」页填" }

    /**
     * 官方 Breeze TTS 2 引擎的示例清单。
     *
     * 地址写的是常见的自建默认值（README 里的 `--port 7860`），用户改一下 IP 即可。
     */
    val SAMPLE: String = encode(
        TtsPackFile(
            packId = "com.breezeblue.tts2",
            packName = "Breeze TTS 2",
            author = "BreezeBlue",
            description = "把 Breeze TTS 2 的语音能力接进 LocalMind。手机跑不动这个模型，" +
                "需要在 PC / GPU 服务器上自建服务，手机当客户端。",
            note = "端点在「Breeze 语音」页填，改成你服务器的局域网地址。",
            engines = listOf(
                TtsEngineSpec(
                    id = "breeze-tts-2",
                    label = "Breeze TTS 2",
                    kind = "breeze",
                    baseUrl = "http://127.0.0.1:7860",
                    model = "BreezeBlue/breeze-tts-2",
                    defaultVoice = "S0",
                    defaultInstruction = "",
                    cfgScale = 4f,
                    sampleRate = 24000,
                    format = "pcm_s16le",
                    notes = "24 kHz 单声道；支持中英双语、自然语言演技指导、声音克隆需要参考音频+文字稿。",
                    voices = listOf(TtsVoiceSpec("S0", "默认说话人", "Breeze 的声音由 instruction 决定，槽位只有一个")),
                ),
            ),
        )
    )
}

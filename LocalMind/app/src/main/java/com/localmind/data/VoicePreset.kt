package com.localmind.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一个「音色预设」= 一组参数的命名组合。
 *
 * ## 对应 VoiceStudio 的什么
 *
 * VoiceStudio 有「Voice Design」：用一句话描述想要的声音，然后生成它。我们这边
 * 没有可微调的声纹模型（sherpa 的 VITS/ZipVoice 都不是可提示的），所以把这件事
 * 落在**能做到的那一层**：把"低沉男声 / 温柔陪伴 / 新闻播报"这类描述映射成
 * 一组具体参数（音高、语速、句间停顿、采样步数、情绪），一键套用。
 *
 * 这比伪造一个"生成音色"的按钮诚实：用户点下去，听到的确实是描述的那种感觉，
 * 而且随时能再手动微调。
 */
@Serializable
data class VoicePreset(
    val id: String,
    val name: String,
    /** 一句话说明这套参数听起来是什么感觉。 */
    val hint: String = "",
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val silenceScale: Float = 0.2f,
    val numSteps: Int = 5,
    val splitSentences: Boolean = true,
    val emotion: Emotion = Emotion.NEUTRAL,
    val builtin: Boolean = false,
) {
    /** 把这套参数盖到用户当前设置上（音色选择不动）。 */
    fun applyTo(v: VoiceSettings): VoiceSettings = v.copy(
        speed = speed,
        pitch = pitch,
        volume = volume,
        silenceScale = silenceScale,
        numSteps = numSteps,
        splitSentences = splitSentences,
        emotion = emotion,
    )
}

/** 预设的持久化：内置 6 套 + 用户自建的存 `filesDir/voice_presets.json`。 */
object VoicePresetStore {
    private const val FILE = "voice_presets.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * 内置预设。
     *
     * 数值不是随手写的：`silenceScale` 越大句间停顿越长（上游默认 0.2 偏"赶"），
     * `numSteps` 越大克隆的细节越足但越慢（只对克隆链路有效，普通音色忽略它）。
     */
    fun builtins(): List<VoicePreset> = listOf(
        VoicePreset(
            id = "builtin-natural",
            name = "自然叙述",
            hint = "像真人说话的节奏，适合长段落",
            speed = 1.0f, pitch = 1.0f, silenceScale = 0.35f, numSteps = 10,
            splitSentences = true, emotion = Emotion.NEUTRAL, builtin = true,
        ),
        VoicePreset(
            id = "builtin-news",
            name = "新闻播报",
            hint = "清楚、略快、停顿短",
            speed = 1.12f, pitch = 0.98f, silenceScale = 0.15f, numSteps = 8,
            splitSentences = true, emotion = Emotion.SERIOUS, builtin = true,
        ),
        VoicePreset(
            id = "builtin-gentle",
            name = "温柔陪伴",
            hint = "慢一点、轻一点、句间留白多",
            speed = 0.92f, pitch = 1.04f, volume = 0.92f, silenceScale = 0.5f,
            numSteps = 12, splitSentences = true, emotion = Emotion.GENTLE, builtin = true,
        ),
        VoicePreset(
            id = "builtin-deep",
            name = "沉稳男声",
            hint = "低音高、稳节奏，适合旁白",
            speed = 0.95f, pitch = 0.86f, silenceScale = 0.3f, numSteps = 12,
            splitSentences = true, emotion = Emotion.NEUTRAL, builtin = true,
        ),
        VoicePreset(
            id = "builtin-lively",
            name = "活泼一点",
            hint = "轻快、上扬，适合短句",
            speed = 1.15f, pitch = 1.12f, silenceScale = 0.2f, numSteps = 8,
            splitSentences = true, emotion = Emotion.HAPPY, builtin = true,
        ),
        VoicePreset(
            id = "builtin-audiobook",
            name = "有声书",
            hint = "整段不切句，句内韵律更连贯",
            speed = 0.98f, pitch = 1.0f, silenceScale = 0.45f, numSteps = 12,
            splitSentences = false, emotion = Emotion.NEUTRAL, builtin = true,
        ),
    )

    fun list(storage: Storage): List<VoicePreset> = builtins() + userList(storage)

    private fun userList(storage: Storage): List<VoicePreset> {
        val file = File(storage.appContext().filesDir, FILE)
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<VoicePreset>>(file.readText())
        }.getOrElse { emptyList() }
    }

    fun save(storage: Storage, preset: VoicePreset): List<VoicePreset> {
        val all = (userList(storage).filter { it.id != preset.id } + preset.copy(builtin = false))
            .sortedBy { it.name }
        write(storage, all)
        return all
    }

    fun delete(storage: Storage, id: String): List<VoicePreset> {
        val all = userList(storage).filter { it.id != id }
        write(storage, all)
        return all
    }

    private fun write(storage: Storage, list: List<VoicePreset>) {
        runCatching {
            val body = json.encodeToString(ListSerializer(VoicePreset.serializer()), list)
            File(storage.appContext().filesDir, FILE).writeText(body)
        }
    }
}

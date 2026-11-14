package com.localmind.data

import kotlinx.serialization.Serializable
import java.io.File

/**
 * 一个「声音克隆」产物：用户上传/录制一段参考音频 + 念的文字，模型据此复刻音色。
 *
 * 参考音频存在 [audioPath]（WAV，16-bit 单声道），念的文字存在 [transcript]——
 * 这两样缺一不可：ZipVoice2 是零样本克隆，靠参考音频的声纹 + 文字对齐来提取音色。
 */
@Serializable
data class ClonedVoice(
    val id: String,
    val name: String,
    /** 参考音频 WAV 文件的绝对路径。 */
    val audioPath: String,
    /** 参考音频里念的文字（克隆质量的凭依，越准越像）。 */
    val transcript: String,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val createdAt: Long = 0L,
)

/** 克隆音色的持久化：存成 `filesDir/cloned_voices.json`。 */
object ClonedVoiceStore {
    private const val FILE = "cloned_voices.json"
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun list(storage: Storage): List<ClonedVoice> {
        val file = File(storage.appContext().filesDir, FILE)
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString<List<ClonedVoice>>(file.readText()) }
            .getOrElse { emptyList() }
    }

    /** 保存（同 id 覆盖），返回最新列表。 */
    fun save(storage: Storage, voice: ClonedVoice): List<ClonedVoice> {
        val all = (list(storage).filter { it.id != voice.id } + voice)
            .sortedByDescending { it.createdAt }
        write(storage, all)
        return all
    }

    fun delete(storage: Storage, id: String): List<ClonedVoice> {
        val all = list(storage).filter { it.id != id }
        write(storage, all)
        return all
    }

    private fun write(storage: Storage, list: List<ClonedVoice>) {
        runCatching {
            // 显式给 ListSerializer：`encodeToString(list)` 会撞上 "SerializationStrategy<T>"
            // 那个重载（泛型推断不出 T），编译期就报参数类型不匹配。
            val body = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ClonedVoice.serializer()),
                list,
            )
            File(storage.appContext().filesDir, FILE).writeText(body)
        }
    }
}

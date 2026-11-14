package com.localmind.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一条「合成记录」（VoiceStudio 的 Recent takes）。
 *
 * 存的是**文件路径**而不是音频本身：一段 30 秒的 WAV 有 1 MB 上下，
 * 十几条就能把 json 撑成几百 KB，而 json 每次启动都要读。音频放
 * `filesDir/voice_takes/`，元数据只留索引。
 */
@Serializable
data class SynthTake(
    val id: String,
    /** 开头一小段文字，列表里当标题。 */
    val title: String,
    /** 完整文本，「复用」时原样填回脚本框。 */
    val text: String,
    /** 用的哪个音色（显示名）。 */
    val voice: String,
    val path: String,
    val createdAt: Long = 0L,
    val durationMs: Int = 0,
)

/** 合成记录持久化到 `filesDir/voice_takes.json`，最多留 [MAX] 条。 */
object SynthTakeStore {
    private const val FILE = "voice_takes.json"
    private const val MAX = 30
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun list(storage: Storage): List<SynthTake> {
        val file = File(storage.appContext().filesDir, FILE)
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<SynthTake>>(file.readText())
        }.getOrElse { emptyList() }
    }

    /** 插到最前面，并把超出的旧记录（连同音频文件）删掉。 */
    fun add(storage: Storage, take: SynthTake): List<SynthTake> {
        val existing = list(storage).filter { it.id != take.id }
        val merged = (listOf(take) + existing)
        val keep = merged.take(MAX)
        merged.drop(MAX).forEach { old ->
            runCatching { File(old.path).delete() }
        }
        write(storage, keep)
        return keep
    }

    fun delete(storage: Storage, id: String): List<SynthTake> {
        val all = list(storage)
        all.firstOrNull { it.id == id }?.let { runCatching { File(it.path).delete() } }
        val rest = all.filter { it.id != id }
        write(storage, rest)
        return rest
    }

    fun clearAll(storage: Storage) {
        list(storage).forEach { runCatching { File(it.path).delete() } }
        write(storage, emptyList())
    }

    private fun write(storage: Storage, list: List<SynthTake>) {
        runCatching {
            val body = json.encodeToString(ListSerializer(SynthTake.serializer()), list)
            File(storage.appContext().filesDir, FILE).writeText(body)
        }
    }
}

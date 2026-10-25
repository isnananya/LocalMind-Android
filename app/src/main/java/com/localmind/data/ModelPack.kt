package com.localmind.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ModelPackFile(
    val version: Int = 1,
    val packId: String = "",
    val packName: String = "",
    val author: String = "",
    val updatedAt: String = "",
    val note: String = "",
    val models: List<LocalModel> = emptyList(),
)

object ModelPacks {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    fun parse(text: String): Result<ModelPackFile> = runCatching {
        val pack = json.decodeFromString(ModelPackFile.serializer(), text)
        require(pack.models.isNotEmpty()) { "模型包里没有任何模型" }
        require(pack.models.all { it.id.isNotBlank() && it.fileName.isNotBlank() && it.url.isNotBlank() }) {
            "有模型条目缺少 id / fileName / url"
        }
        pack
    }

    fun encode(pack: ModelPackFile): String = json.encodeToString(ModelPackFile.serializer(), pack)
}

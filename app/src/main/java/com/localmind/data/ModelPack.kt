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

        // ---- fileName 必须是**纯文件名** ----
        //
        // 它会被拼成 `modelsDir/<fileName>` 去落盘。插件里写一个
        // `"fileName": "../../../shared_prefs/localmind.xml"`，下载下来的内容
        // 就会覆盖应用私有的偏好文件 —— 这是最直接的一条路径穿越。
        // 顺便也挡掉子目录（`sub/model.gguf`）：模型目录是平的，不需要它。
        val badName = pack.models.firstOrNull {
            it.fileName.contains('/') || it.fileName.contains('\\') ||
                it.fileName.contains("..") || !SAFE_FILE_NAME.matches(it.fileName)
        }
        require(badName == null) {
            "模型文件名不合法：「${badName?.fileName?.take(60)}」。只能是不含路径的纯文件名"
        }

        // ---- url 只允许 http / https ----
        //
        // 挡掉 `file://`、`content://` 这类本地协议；同时确认有主机名，
        // 避免 `"https://"` 这种能通过 startsWith 的残缺地址。
        val badUrl = pack.models.firstOrNull {
            val u = it.url.trim().lowercase()
            !(u.startsWith("https://") || u.startsWith("http://")) ||
                it.url.substringAfter("://").substringBefore('/').isBlank()
        }
        require(badUrl == null) {
            "模型下载地址不合法：「${badUrl?.url?.take(80)}」。只支持 http / https"
        }

        pack
    }

    /** 不含任何路径分隔符的文件名。 */
    private val SAFE_FILE_NAME = Regex("^[A-Za-z0-9._-]+$")

    fun encode(pack: ModelPackFile): String = json.encodeToString(ModelPackFile.serializer(), pack)
}

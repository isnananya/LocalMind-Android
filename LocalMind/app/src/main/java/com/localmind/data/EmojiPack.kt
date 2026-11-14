package com.localmind.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 表情包插件的数据格式（`emoji.json`）。
 *
 * ## 表情包是**图片**，不是 emoji
 *
 * 这里的 `char` 字段只是一个轻量兜底：真正的表情包是梗图 —— 一张图片，
 * 配上"这个图表达什么情绪"，AI 在该发的时候把它发出来。
 *
 * 所以每条要么给 [StickerEntry.image]（zip 里 `assets/` 下的文件名），
 * 要么给 [StickerEntry.char]（一个 emoji 字符，用于不想带图片的轻量包）。
 * **两个都给时图片优先** —— 图片信息量大得多。
 *
 * ## 设计参考与授权边界
 *
 * 「给表情打标签 → 按标签/描述匹配 → 结果缓存」这三个做法参考自
 * [RiyaBot](https://github.com/hsd221/riyabot) 的 `emoji_api`（**GPL-3.0**）。
 *
 * 本项目是 MIT，所以**没有搬运它的任何代码或数据**。也正因为它是 GPL，
 * 这个能力被做成**插件**而不是内置：授权边界和代码边界重合。
 *
 * 而**图片素材的授权是插件作者的责任** —— 见 docs/PLUGIN.md 那一节。
 * 主程序不分发任何表情素材。
 */
@Serializable
data class StickerPack(
    val version: Int = 1,
    val packId: String = "",
    val packName: String = "",
    val author: String = "",
    val description: String = "",
    /** 宽松许可声明。强烈建议填 —— 图片素材的授权是使用者最需要知道的事。 */
    val license: String = "",
    val home: String = "",
    /** 所有表情条目。 */
    val stickers: List<StickerEntry> = emptyList(),
    /**
     * 关键词 → 情感标签的兜底映射。
     *
     * 模型有时候不写 `[[emoji: 开心]]` 而是写 `[[emoji: 哈哈]]`。
     * 这张表让它也能命中，不需要模型学会我们的标签词表。
     */
    val keywords: Map<String, String> = emptyMap(),
    /** 注入给模型的提示词片段。留空则用主程序的通用说明。 */
    val promptHint: String = "",
) {
    /** 所有出现过的情感标签，去重后保持稳定顺序。 */
    val emotions: List<String>
        get() = stickers.map { it.emotion }.filter { it.isNotBlank() }.distinct()

    val isValid: Boolean
        get() = packId.isNotBlank() && packName.isNotBlank() && stickers.isNotEmpty()
}

/** 一个表情：一张图片或一个 emoji 字符，加它的情感标签。 */
@Serializable
data class StickerEntry(
    /** 情感标签，中文即可，例如 "开心"。**模型就是按这个点菜的。** */
    val emotion: String,
    /**
     * 图片文件名，对应 zip 里 `assets/` 下的文件，例如 `"happy_laugh.png"`。
     * 图片优先于 [char]。
     */
    val image: String = "",
    /** 兜底：一个 emoji 字符，例如 `"😄"`。没有图片时用它。 */
    val char: String = "",
    /** 更细的场景描述，例如 "开心的大笑"。 */
    val description: String = "",
    /** 同义标签，让模型用别的词也能命中。 */
    val aliases: List<String> = emptyList(),
    /** 权重：同情感下有多个表情时，权重高的更容易被选中。 */
    val weight: Int = 1,
) {
    /** 至少要有图或字符之一，否则这条没法显示。 */
    val isUsable: Boolean get() = image.isNotBlank() || char.isNotBlank()
}

/**
 * 表情包的解析与校验。
 *
 * 和 [PluginManifests] 一样，失败要给出**能直接显示给用户**的中文原因 ——
 * 用户是自己写 JSON 的人，报错信息就是他的文档。
 */
object StickerPacks {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    sealed interface ParseResult {
        data class Ok(val pack: StickerPack) : ParseResult
        data class Failed(val reason: String) : ParseResult
    }

    fun parse(text: String): ParseResult {
        if (text.isBlank()) return ParseResult.Failed("emoji.json 是空的")
        val pack = runCatching { json.decodeFromString<StickerPack>(text) }.getOrElse { t ->
            return ParseResult.Failed("emoji.json 格式错误：${t.message?.take(140) ?: "解析失败"}")
        }
        if (pack.packId.isBlank()) return ParseResult.Failed("emoji.json 缺少 packId")
        if (pack.packName.isBlank()) return ParseResult.Failed("emoji.json 缺少 packName")
        if (pack.stickers.isEmpty()) return ParseResult.Failed("emoji.json 里 stickers 是空的")

        val noEmotion = pack.stickers.filter { it.emotion.isBlank() }
        if (noEmotion.isNotEmpty()) {
            return ParseResult.Failed("有 ${noEmotion.size} 个表情缺少 emotion（标签）")
        }
        val unusable = pack.stickers.filter { !it.isUsable }
        if (unusable.isNotEmpty()) {
            return ParseResult.Failed(
                "有 ${unusable.size} 个表情既没有 image 也没有 char" +
                    "（第一个的 emotion='${unusable.first().emotion}'）"
            )
        }
        return ParseResult.Ok(pack)
    }

    fun encode(pack: StickerPack): String = json.encodeToString(StickerPack.serializer(), pack)
}

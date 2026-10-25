package com.localmind.service

import android.util.Log
import com.localmind.data.StickerEntry
import com.localmind.data.StickerPack
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "StickerResolver"

/**
 * 把模型写出来的 `[[emoji: 标签]]` 解析成一个**真正的表情**。
 *
 * ## 表情包主要是图片
 *
 * 「表情包」在中文语境里指的是**图片梗图** —— 一张图的信息量比 emoji 大得多，
 * 这才是"发表情包"的意思。所以 [StickerEntry.image] 是首选，
 * [StickerEntry.char] 只是不想带图片的轻量包的兜底。
 *
 * 两者都给时**图片优先**，理由不只是信息量：一张梗图能表达的
 * "我不想说但我有情绪"，emoji 表达不出来。
 *
 * ## 设计来源与授权边界（别删这段）
 *
 * 「给表情打标签 → 按标签/描述匹配 → 命中结果缓存」这三个做法参考自
 * [RiyaBot](https://github.com/hsd221/riyabot) 的 `emoji_api`（**GPL-3.0**）。
 *
 * 本项目是 MIT，所以这里**没有搬运它的任何代码或数据**：
 *  * 它是 Python 进程内 API + 本地图片文件 + VLM 两段式识别；
 *  * 我们是纯函数式匹配，图片素材由**插件**提供，主程序不分发任何素材。
 *
 * 相同之处只有"用情感标签做索引"这个思路本身，而思路不受版权保护。
 *
 * ## 三级匹配
 *
 *  1. **精确**：`emotion` 或 `aliases` 完全等于模型写的词
 *  2. **关键词表**：插件里配的 `keywords`（"哈哈" → "开心"）
 *  3. **模糊**：互相包含（只对长度 ≥ 2 的词生效 —— 单字太容易误命中，
 *     「好」会匹配到「不好」，情绪正好相反）
 *
 * 都不中就没有表情 —— **宁可不发，也不乱发**。发错表情比不发更糟：
 * 用户会读出一个完全相反的情绪。
 */
class StickerResolver {

    /** 当前启用的包。null = 没装表情包插件，能力关闭。 */
    @Volatile
    private var pack: StickerPack? = null

    /** 资源目录（`filesDir/plugins/<id>/assets`），图片在这里找。 */
    @Volatile
    private var assetDir: File? = null

    private val cache = ConcurrentHashMap<String, StickerEntry?>()

    val isActive: Boolean get() = pack != null

    val packName: String get() = pack?.packName.orEmpty()

    /** 有多少张图片表情。UI 用它说明"这个包是图片包还是 emoji 包"。 */
    val imageCount: Int get() = pack?.stickers?.count { it.image.isNotBlank() } ?: 0

    /** 有多少个 emoji 兜底。 */
    val charCount: Int get() = pack?.stickers?.count { it.char.isNotBlank() } ?: 0

    fun setPack(p: StickerPack?, assets: File? = null) {
        if (p?.packId != pack?.packId) cache.clear()
        pack = p
        assetDir = assets
        Log.i(
            TAG,
            "表情包：${p?.packName ?: "未启用"}（共 ${p?.stickers?.size ?: 0} 个，" +
                "图片 ${p?.stickers?.count { it.image.isNotBlank() } ?: 0} / " +
                "emoji ${p?.stickers?.count { it.char.isNotBlank() } ?: 0}）",
        )
    }

    /** 解析一个词。@return null 表示没有合适的表情（调用方应该什么都不做）。 */
    fun resolve(word: String?): StickerEntry? {
        val p = pack ?: return null
        val key = word?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return null
        if (cache.containsKey(key)) return cache[key]

        val hit = match(p, key)
        // 只缓存"能直接用"的结果：图片文件后来才落盘的情况不该被永久记成失败。
        if (hit == null || hit.char.isNotBlank() || imageFile(hit) != null) cache[key] = hit
        return hit
    }

    /**
     * 取这条表情对应的图片文件，没有就返回 null。
     *
     * 每次现拼路径而不缓存 File：包可能被重装（资源目录被重建），
     * 缓存 File 对象会指向旧 inode。
     */
    fun imageFile(entry: StickerEntry): File? {
        if (entry.image.isBlank()) return null
        val dir = assetDir ?: return null
        val f = File(dir, entry.image.substringAfterLast('/'))
        return if (f.isFile && f.length() > 0) f else null
    }

    private fun match(p: StickerPack, key: String): StickerEntry? {
        val usable = p.stickers.filter { it.isUsable }

        // 1) 精确
        usable.firstOrNull { it.emotion.equals(key, true) }?.let { return it }
        usable.firstOrNull { e -> e.aliases.any { it.equals(key, true) } }?.let { return it }

        // 2) 关键词表
        p.keywords.entries.firstOrNull { it.key.equals(key, true) }?.let { (_, emotion) ->
            usable.firstOrNull { it.emotion.equals(emotion, true) }?.let { return it }
        }

        // 3) 模糊
        if (key.length >= 2) {
            usable.firstOrNull { e ->
                e.emotion.length >= 2 && (e.emotion.contains(key) || key.contains(e.emotion))
            }?.let { return it }
            usable.firstOrNull { e ->
                e.description.length >= 2 && e.description.contains(key)
            }?.let { return it }
        }

        Log.i(TAG, "没有匹配到表情：'$key'")
        return null
    }

    /**
     * 注入给模型的提示词：告诉它有哪些表情可以发。
     *
     * 只列**标签**而不是所有条目 —— 标签是十几个词，全列出来会让提示词膨胀，
     * 而小模型在长提示词里更容易写错格式。
     */
    fun promptHint(extraHint: String = ""): String {
        val p = pack ?: return ""
        val tags = p.emotions
        if (tags.isEmpty()) return ""
        return buildString {
            append("\n\n【表情】")
            append("你可以在回复里发一张表情图，写法：[[emoji: 标签]]。")
            append("可用的标签：").append(tags.joinToString("、")).append("。")
            append("偶尔发一个就好，用来代替「哈哈」这类干巴巴的回应；不要每条都发。")
            if (extraHint.isNotBlank()) append("\n").append(extraHint.trim())
        }
    }

    /** 供设置页显示：这个包里有哪些标签。 */
    fun availableTags(): List<String> = pack?.emotions.orEmpty()
}

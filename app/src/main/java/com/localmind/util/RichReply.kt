package com.localmind.util

/**
 * AI 主动发图 / 发表情包的**文本协议**。
 *
 * 为什么用标记而不是让模型调函数：小模型的 function calling 很不稳，
 * 而且那套机制在这里是杀鸡用牛刀 —— 我们只需要它在回复里插一个标记。
 * 标记的好处是**失败无害**：模型没写就没有图，写错了最多多一行文字，
 * 不会像函数调用那样把整轮输出弄成一段无法解析的 JSON。
 *
 * 协议刻意做得极简（三个方括号 + 冒号），因为要迁就小模型的输出习惯：
 * 越短越容易被正确复现。同时解析器接受全角冒号、中文括号等常见变体 ——
 * 中文模型经常会"顺手"把它们换掉。
 */
object RichReply {

    private const val TAG = "RichReply"

    /** 模型请求画一张图。 */
    private val DRAW = Regex(
        "\\[\\[\\s*(?:draw|画|图)\\s*[:：]\\s*([^\\[\\]]{2,200}?)\\s*\\]\\]",
        RegexOption.IGNORE_CASE,
    )

    /** 模型发一个表情。 */
    private val EMOJI = Regex(
        "\\[\\[\\s*(?:emoji|表情)\\s*[:：]\\s*([^\\[\\]]{1,12}?)\\s*\\]\\]",
        RegexOption.IGNORE_CASE,
    )

    /** 解析结果。 */
    data class Parsed(
        /** 去掉标记之后的正文，直接显示给用户。 */
        val text: String,
        /** 模型想画的图（提示词），可能多个但只取第一个。 */
        val drawPrompt: String? = null,
        /** 模型发的表情。 */
        val emoji: String? = null,
    )

    fun parse(raw: String): Parsed {
        if (raw.isBlank()) return Parsed(raw)

        val draw = DRAW.find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        val emoji = EMOJI.find(raw)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

        // 把标记从正文里剔除，并收拾一下留下的空行/多余标点。
        val cleaned = raw
            .replace(DRAW, "")
            .replace(EMOJI, "")
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        return Parsed(text = cleaned, drawPrompt = draw, emoji = emoji)
    }

    /**
     * 告诉模型它能用这两个标记。只在对应能力打开时才拼进提示词。
     *
     * 措辞上刻意强调"偶尔""真的想"：否则模型会把发图当成任务，
     * 每条回复都塞一张，那比不发更糟。
     */
    fun instruction(canDraw: Boolean, canEmoji: Boolean): String {
        if (!canDraw && !canEmoji) return ""
        return buildString {
            append("\n\n【表达方式】")
            if (canDraw) {
                append("\n你可以给对方画一张图。真的想给他看点什么的时候，")
                append("在回复里插一句：[[draw: 画面的具体描述]]")
                append("。偶尔用就好，不要每条都画 —— 一直发图就不像在聊天了。")
            }
            if (canEmoji) {
                append("\n也可以发一个表情，写法：[[emoji: 😄]]")
                append("。同样偶尔用，用来代替「哈哈」这类干巴巴的回应。")
            }
            append("\n这两个标记不会显示给对方看，会被替换成真正的内容，所以不用解释它们。")
        }
    }
}

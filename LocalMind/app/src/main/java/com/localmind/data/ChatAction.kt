package com.localmind.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 聊天快捷动作（能力 id = `chat_action`，内容文件 = `actions.json`）。
 *
 * 这是"插件不仅能换皮肤，还能长出功能"的第一个落点：插件声明几个动作，
 * 主程序把它们画在输入框上方，点一下就把预设提示词发出去（或填进输入框）。
 *
 * 为什么不是脚本：
 *  * 动作只有两种语义（发出去 / 填进去），不需要图灵完备；
 *  * 插件仍然是**数据**，安装前就能被完整校验，最坏情况是某个动作不显示。
 *
 * `prompt` 里可以用 `{{input}}` 引用用户当前输入：例如"把下面的话翻译成英文：{{input}}"。
 * 没有 `{{input}}` 时直接发送/插入 prompt 本身。
 */
@Serializable
data class ChatAction(
    val id: String,
    val label: String,
    val prompt: String,
    /** 内置图标 id（见 Layouts.ICONS），空 = 默认。 */
    val icon: String = "",
    /** send = 直接发送；insert = 填进输入框让用户继续改。 */
    val mode: String = "send",
    /** composer = 输入框上方；menu = 暂时保留。 */
    val placement: String = "composer",
)

@Serializable
data class ChatActionPack(
    val version: Int = 1,
    val packId: String = "",
    val packName: String = "",
    val author: String = "",
    val description: String = "",
    val actions: List<ChatAction> = emptyList(),
)

object ChatActions {

    const val MAX_ACTIONS = 12
    const val MAX_PROMPT = 2000

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    sealed interface ParseResult {
        data class Ok(val pack: ChatActionPack) : ParseResult
        data class Failed(val reason: String) : ParseResult
    }

    fun parse(text: String): ParseResult {
        if (text.isBlank()) return ParseResult.Failed("actions.json 是空的")
        val pack = runCatching { json.decodeFromString<ChatActionPack>(text) }.getOrElse { t ->
            return ParseResult.Failed("actions.json 格式错误：" + (t.message?.take(120) ?: "解析失败"))
        }
        if (pack.packId.isBlank()) return ParseResult.Failed("actions.json 缺少 packId")
        if (pack.packName.isBlank()) return ParseResult.Failed("actions.json 缺少 packName")
        if (pack.actions.isEmpty()) return ParseResult.Failed("actions 是空的")
        if (pack.actions.size > MAX_ACTIONS) {
            return ParseResult.Failed("actions 最多 " + MAX_ACTIONS + " 个，现在是 " + pack.actions.size + " 个")
        }
        val dup = pack.actions.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) return ParseResult.Failed("actions 里有重复的 id：" + dup.joinToString())
        pack.actions.forEach { a ->
            if (a.id.isBlank() || a.label.isBlank() || a.prompt.isBlank()) {
                return ParseResult.Failed("每个动作都需要 id / label / prompt：" + a.id)
            }
            if (a.prompt.length > MAX_PROMPT) {
                return ParseResult.Failed("动作「" + a.label + "」的 prompt 超过 " + MAX_PROMPT + " 字")
            }
            if (a.mode !in setOf("send", "insert")) {
                return ParseResult.Failed("动作「" + a.label + "」的 mode 只能是 send 或 insert")
            }
            if (a.placement !in setOf("composer", "menu")) {
                return ParseResult.Failed("动作「" + a.label + "」的 placement 只能是 composer 或 menu")
            }
        }
        return ParseResult.Ok(pack)
    }

    fun encode(pack: ChatActionPack): String = json.encodeToString(ChatActionPack.serializer(), pack)

    /** 用当前草稿填充 `{{input}}`；没有占位符就原样返回。 */
    fun render(prompt: String, draft: String): String =
        if (prompt.contains("{{input}}")) prompt.replace("{{input}}", draft.trim()) else prompt

    val SAMPLE: String = encode(
        ChatActionPack(
            packId = "com.example.quickactions",
            packName = "示例：快捷动作",
            author = "你",
            description = "两个动作：翻译当前输入、写一段周报。",
            actions = listOf(
                ChatAction(
                    id = "translate",
                    label = "翻译成英文",
                    icon = "language",
                    prompt = "把下面这段话翻译成自然的英文，只输出译文：\n{{input}}",
                    mode = "send",
                ),
                ChatAction(
                    id = "weekly",
                    label = "写周报",
                    icon = "bolt",
                    prompt = "帮我写一段本周工作总结：",
                    mode = "insert",
                ),
            ),
        )
    )
}

package com.localmind.service

import android.util.Log
import com.localmind.data.GenerationSettings
import com.localmind.data.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "DecisionEngine"

/**
 * Function-calling decision layer.
 *
 * Why this exists: feature routing ("is this worth remembering?", "should I search
 * the web?", "did the user's mood change?") was originally a pile of hand-tuned
 * regexes and prompt wording — brittle, and every new capability meant more prompt
 * fiddling. Instead, the decision is delegated to a **small dedicated model** that is
 * asked to emit a function call, and the app just executes the chosen option.
 *
 * Two properties make this practical on a phone:
 *
 *  * **A separate, tiny model.** Binding decisions to a 0.5B model keeps the main
 *    chat model free and costs a fraction of a second, so routing never slows a reply.
 *  * **Rules remain the floor.** [Decision`] falls back to a supplied default when no
 *    decision model is bound, when the model is busy, or when it emits something
 *    unparseable. The AI decides; the app never breaks because of it.
 *
 * The wire format is deliberately **not** OpenAI's `tools` array: small quantized
 * models follow a terse `CALL name(k=v, ...)` line far more reliably than nested JSON
 * schemas. JSON is still accepted when the model volunteers it.
 */
class DecisionEngine(
    private val engine: LlamaEngine,
    /**
     * Optional second engine that holds **only** the decision model, so routing never
     * competes with the chat model for the single-model [engine].
     *
     * When null, or when the dedicated engine has no model resident, everything falls
     * back to running the decision on [engine] — the pre-existing behaviour, which is
     * correct whenever the decision model *is* the loaded model.
     */
    private val dedicatedEngine: LlamaEngine? = null,
) {

    /**
     * Name of the model the caller has bound for decision making, or null for "use the
     * chat model". Kept as a plain field so [available] can answer synchronously from
     * the post-turn hook without touching storage.
     */
    @Volatile
    var boundModelName: String? = null

    /**
     * Set when the dedicated engine failed to load [boundModelName]. Suppresses the
     * fallback to [engine] for that binding, so a failed load does not silently make
     * the *chat* model the router — the caller gets rules instead, which is honest.
     * Cleared on every new binding.
     */
    @Volatile
    var dedicatedLoadFailed: Boolean = false

    /** The dedicated engine, once it actually has a model resident. */
    private fun dedicatedReady(): LlamaEngine? =
        dedicatedEngine?.takeIf { it.state.value.canChat }?.takeIf { boundModelName != null }

    /**
     * The engine a decision should run on, or null when no model routing is possible.
     *
     * Precedence: the dedicated decision engine when it is warm, else the shared chat
     * engine — but never the chat engine while the dedicated one has a *failed* load
     * pending, because that would route the decision through the wrong model.
     */
    private fun routeEngine(): LlamaEngine? {
        // **只走专用路由器，绝不退回对话模型。**
        //
        // 这里原来会退回 [engine]（对话模型），理由是"决策模型就是那个已加载的
        // 模型时行为正确"。但在真机上那个分支是**默认**分支：绝大多数用户不会
        // 单独绑一个决策模型，于是每一轮对话都要在同一个引擎上多跑生成 ——
        // 召回挑选 1 次、记忆提炼 1 次、内心活动 1 次，而那个引擎同时只能跑一路，
        // 手机上每秒也就十几 token。「当人模式」因此变成"发一句等半分钟"，
        // 人设功能实际上没法用。
        //
        // 规则兜底本来就是为这种情况准备的（[Decisions] 的 choices 都有对应的
        // 启发式默认值，CompanionMind 里是 ruleBasedMemories）。宁可少一点
        // "AI 自己决定"的花活，也不能让对话卡住。
        //
        // 想要完整的模型决策：在「模型分工」里绑一个小的决策模型，它就只占
        // 自己的那一份算力，不再和对话抢引擎。
        return dedicatedReady()
    }

    /**
     * Whether routing through a model is worth attempting.
     *
     * `false` means the caller should use its cheap default immediately — we never want
     * a missing decision model to become an error path.
     */
    fun available(): Boolean {
        val e = routeEngine() ?: return false
        return e.state.value.canChat && !e.state.value.generating
    }

    /** True when decisions are really being made by the dedicated router model. */
    fun usingDedicatedModel(): Boolean = dedicatedReady() != null

    /**
     * 专用决策引擎本身（没有就是 null）。
     *
     * 给那些「需要真正理解力、但又不该占用对话模型」的判断用 —— 目前是
     * [CompanionMind] 的按含义召回。原来它虽然要求先绑了决策模型，却仍然把请求
     * 发到**对话模型**上：等于先花了绑定决策模型的内存，再白白多一次抢锁。
     */
    fun router(): LlamaEngine? = dedicatedReady()

    /** One thing the AI is allowed to decide. */
    data class Choice(
        val name: String,
        val description: String,
        val params: List<Param> = emptyList(),
    )

    data class Param(val name: String, val description: String, val required: Boolean = true)

    /** What the model chose, plus how we got there (for the UI / logs). */
    data class Outcome(
        val name: String,
        val args: Map<String, String>,
        val source: Source,
        val raw: String = "",
        val latencyMs: Long = 0,
    ) {
        enum class Source { MODEL, FALLBACK, SKIPPED }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Asks the decision model to pick exactly one [Choice].
     *
     * @param fallback used when no model is bound or the answer cannot be parsed —
     *        callers should pass the cheap heuristic answer, so behaviour degrades
     *        rather than stops.
     */
    suspend fun decide(
        systemContext: String,
        userText: String,
        choices: List<Choice>,
        fallback: String,
        maxTokens: Int = 96,
    ): Outcome {
        if (choices.isEmpty()) return Outcome(fallback, emptyMap(), Outcome.Source.SKIPPED)

        // Pick the engine that actually holds the router model. Never queue behind the
        // chat engine: it is single-threaded and shared with the user's own reply.
        val router = routeEngine()
        val state = router?.state?.value
        if (router == null || state == null || !state.canChat || state.generating) {
            return Outcome(fallback, emptyMap(), Outcome.Source.SKIPPED)
        }

        val started = System.currentTimeMillis()
        val settings = GenerationSettings(
            temperature = 0.0f,     // decisions must be reproducible
            topP = 1.0f,
            topK = 1,
            maxTokens = maxTokens,
            systemPrompt = SYSTEM,
            streamToUi = false,
        )

        val prompt = buildString {
            if (systemContext.isNotBlank()) {
                append("背景：").append(systemContext.take(400)).append('\n')
            }
            append("可选动作：\n")
            choices.forEach { c ->
                append("- ").append(c.name).append("：").append(c.description)
                if (c.params.isNotEmpty()) {
                    append("（参数：")
                    append(c.params.joinToString("，") { p ->
                        p.name + "=" + p.description + if (p.required) "" else "?"
                    })
                    append("）")
                }
                append('\n')
            }
            append("\n用户说：").append(userText.take(600)).append('\n')
            append("\n只输出一行：CALL 动作名(参数=值)\n如果都不合适，输出：CALL ").append(fallback)
        }

        val result = runCatching {
            router.chat(
                messages = listOf(Role.SYSTEM to settings.systemPrompt, Role.USER to prompt),
                settings = settings,
                notifyTurnComplete = false,   // decisions are not conversation turns
            )
        }.getOrNull()

        val latency = System.currentTimeMillis() - started
        val raw = result?.getOrNull().orEmpty()
        Log.i(TAG, "decide(${choices.size} choices) -> ${raw.take(160).replace('\n', '⏎')} (${latency}ms)")

        val parsed = parseCall(raw, choices)
            ?: return Outcome(fallback, emptyMap(), Outcome.Source.FALLBACK, raw, latency)

        return Outcome(parsed.first, parsed.second, Outcome.Source.MODEL, raw, latency)
    }

    /**
     * Lenient parse of `CALL name(k=v, ...)`, `name(k=v)`, or a JSON object.
     * Returns null when the model produced something we cannot trust.
     */
    internal fun parseCall(raw: String, choices: List<Choice>): Pair<String, Map<String, String>>? {
        if (raw.isBlank()) return null
        val text = raw.trim().removePrefix("```").removeSuffix("```").trim()
        val names = choices.map { it.name }.toSet()

        // --- JSON form ---
        val jsonStart = text.indexOf('{')
        if (jsonStart >= 0) {
            runCatching {
                val obj = json.parseToJsonElement(text.substring(jsonStart)).jsonObject
                val name = obj["name"]?.jsonPrimitive?.content
                    ?: obj["function"]?.jsonPrimitive?.content
                    ?: obj["action"]?.jsonPrimitive?.content
                if (name != null && name in names) {
                    val argsObj = (obj["arguments"] as? JsonObject) ?: (obj["args"] as? JsonObject)
                    val args = argsObj?.entries
                        ?.associate { it.key to it.value.jsonPrimitive.content }
                        ?: emptyMap()
                    return name to args
                }
            }
        }

        // --- CALL name(k=v, ...) form ---
        val callIdx = text.indexOf("CALL", ignoreCase = true)
        val body = if (callIdx >= 0) text.substring(callIdx + 4).trim() else text

        names.firstOrNull { body.startsWith(it) || body.contains("$it(") || body.trim() == it }?.let { name ->
            val args = mutableMapOf<String, String>()
            val open = body.indexOf('(', body.indexOf(name))
            val close = body.lastIndexOf(')')
            if (open >= 0 && close > open) {
                body.substring(open + 1, close)
                    .split(',', '，')
                    .forEach { pair ->
                        val kv = pair.split('=', '：', ':')
                        if (kv.size >= 2) {
                            val k = kv[0].trim().trim('"', '\'', ' ')
                            val v = kv.drop(1).joinToString("=").trim().trim('"', '\'', ' ')
                            if (k.isNotEmpty() && v.isNotEmpty()) args[k] = v
                        }
                    }
            }
            return name to args
        }

        // Bare name with nothing else ("remember") is still a valid answer.
        val firstWord = body.takeWhile { it.isLetterOrDigit() || it == '_' }
        if (firstWord in names) return firstWord to emptyMap()

        return null
    }

    /** Case-insensitive argument fetch, because small models vary the casing. */
    fun arg(outcome: Outcome, vararg keys: String): String? {
        keys.forEach { k ->
            outcome.args.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.let { return it.value }
        }
        return null
    }

    fun boolArg(outcome: Outcome, vararg keys: String): Boolean? =
        arg(outcome, *keys)?.let {
            when (it.trim().lowercase()) {
                "true", "yes", "1", "是", "对", "需要" -> true
                "false", "no", "0", "否", "不", "不需要" -> false
                else -> null
            }
        }

    fun floatArg(outcome: Outcome, vararg keys: String): Float? =
        arg(outcome, *keys)?.let {
            Regex("-?[0-9]*\\.?[0-9]+").find(it)?.value?.toFloatOrNull()
        }

    private companion object {
        val SYSTEM = "你是一个决策器。根据用户的话，从给定动作里选一个最合适的并输出函数调用。" +
            "不要解释，不要输出多余内容，只输出一行 CALL 语句。"
    }
}

/**
 * Every decision the app delegates to the model, in one place.
 *
 * Adding a capability means adding a [DecisionEngine.Choice] here plus a handler —
 * no prompt tuning scattered through the codebase.
 */
object Decisions {

    const val REMEMBER = "remember"
    const val MOOD = "mood"
    const val WEB_SEARCH = "web_search"
    const val REPLY_STYLE = "reply_style"
    const val TOOL_SHELL = "device_shell"
    const val NONE = "none"

    /** "Is this worth remembering, and what exactly?" — replaces a wall of regexes. */
    val MEMORY_CHOICES = listOf(
        DecisionEngine.Choice(
            REMEMBER,
            "这句话包含关于用户的、以后还用得上的信息（身份、健康、家人宠物、住处、工作、明确喜好、约定、重要日期）",
            listOf(
                DecisionEngine.Param("kind", "fact/preference/event/commitment/feeling"),
                DecisionEngine.Param("text", "第三人称一句话，要具体"),
                DecisionEngine.Param("importance", "0.1-1.0"),
            ),
        ),
        DecisionEngine.Choice(NONE, "只是闲聊、客套、提问，或没有值得长期记住的信息"),
    )

    /** Mood shift for the companion's emotional state. */
    val MOOD_CHOICES = listOf(
        DecisionEngine.Choice("happy", "用户情绪积极：开心、感谢、兴奋、亲昵"),
        DecisionEngine.Choice("sad", "用户情绪低落：难过、疲惫、压力大、倾诉"),
        DecisionEngine.Choice("angry", "用户不满或生气，包括对你不满"),
        DecisionEngine.Choice("neutral", "情绪平稳，或只是问事"),
    )

    /** Whether answering needs fresh information from the network. */
    val SEARCH_CHOICES = listOf(
        DecisionEngine.Choice(WEB_SEARCH, "问题涉及实时信息、新闻、价格、天气、版本号、近期事件，或你不确定的事实"),
        DecisionEngine.Choice(NONE, "常识、闲聊、写作、代码、翻译等不需要联网"),
    )

    /** How long the reply should be. */
    val STYLE_CHOICES = listOf(
        DecisionEngine.Choice("brief", "一句话能说清，用户在忙或只是确认"),
        DecisionEngine.Choice("normal", "普通问答，两三句"),
        DecisionEngine.Choice("detailed", "用户明确要求详细解释、教程、方案对比"),
    )
}

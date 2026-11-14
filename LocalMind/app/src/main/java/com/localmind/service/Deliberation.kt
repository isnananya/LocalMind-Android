package com.localmind.service

import android.util.Log
import com.localmind.data.GenerationSettings
import com.localmind.data.Role

private const val TAG = "Deliberation"

/**
 * 对话模型驱动的**消歧**与**内心思考**。
 *
 * 为什么不用关键词判断用户意图：中文里「换工作」和「辞职」、「不舒服」和「感冒」
 * 字面完全不重叠，指代、省略、反讽更是抓不到。基于字面的判断没有出路。
 *
 * 这里换个思路 —— 不猜，而是**问**：
 *
 *  1. 模型先给出一个它认为最可能的理解（[Understanding]），用它继续对话；
 *  2. 如果这句话本身有歧义，模型再生成**一个澄清问题 + 若干候选答案**，
 *     用户点一下就能给出高置信度的真实含义。
 *
 * 这一步的价值不只是"问清楚"：用户点击的结果会作为**被确认过的语义**写进记忆，
 * 比模型自己推断出来的东西可靠得多。也就是说，消歧同时是记忆质量的来源。
 */
class Deliberation(private val engine: LlamaEngine) {

    /** 模型对用户这句话的判断。 */
    data class Understanding(
        /** 最可能的理解，一句话。 */
        val reading: String = "",
        /** 用户此刻的情绪，模型自己的判断，不是词典。 */
        val userEmotion: String = "neutral",
        /** 模型决定内部做什么：追问细节 / 直接回答 / 先共情 / 记住这件事。 */
        val intent: String = "answer",
        /** 模型自己想的东西（第一人称内心独白，不会展示给用户）。 */
        val innerThought: String = "",
        /**
         * 多重思考第二步产出的**另一种解读**，空表示模型认为没有别的意思。
         *
         * 它存在的意义是让第一步的判断被真正检查过：如果两步得出的理解一致，
         * 说明这个判断是稳的；如果第二步行得通，就该去问用户（见 [Clarification]）。
         */
        val alternativeReading: String = "",
        /** 模型对最初理解的把握，0..100；-1 表示没给出。 */
        val confidence: Int = -1,
        val source: Source = Source.SKIPPED,
        val latencyMs: Long = 0,
    ) {
        enum class Source { MODEL, FALLBACK, SKIPPED }

        /** 把握低到该问一句的程度。 */
        val isUnsure: Boolean get() = confidence in 0..60
    }

    /** 一个需要用户确认的歧义点。 */
    data class Clarification(
        /** 例如「你说想休息一下，是哪个意思？」 */
        val question: String,
        /** 候选含义，用户点一个就完成消歧。 */
        val options: List<String>,
        /** 被消歧的那句原话，写回记忆时用来做上下文。 */
        val aboutUserText: String,
        val createdAt: Long = System.currentTimeMillis(),
    )

    /**
     * 让模型判断这句话的意思、情绪，并留下一条内心独白。
     *
     * 用**对话模型**而不是决策小模型：这一步需要真正的理解力，小模型做不好；
     * 而且它本来就紧跟在一次对话生成之后，模型已加载、缓存也热。
     */
    suspend fun understand(
        userText: String,
        recentContext: String,
        personaName: String,
        personaDescription: String,
    ): Understanding {
        val state = engine.state.value
        if (!state.canChat || state.generating) {
            return Understanding(source = Understanding.Source.SKIPPED)
        }

        val started = System.currentTimeMillis()
        val personaBlock = buildString {
            append("你是「").append(personaName).append("」，一个真实的人，正在和对方聊天。")
            if (personaDescription.isNotBlank()) {
                append("\n你的人设：").append(personaDescription.take(300))
            }
            if (recentContext.isNotBlank()) {
                append("\n\n刚才你们聊到：\n").append(recentContext.take(800))
            }
            append("\n\n对方刚说：").append(userText.take(500))
        }

        // ---------------------------------------------------------------
        // 多重思考：不在一次生成里塞四个问题，而是走三步递进。
        //
        // 为什么分步比"一次想全"好：小模型在单次生成里同时做"理解 + 自我怀疑 +
        // 决定怎么做"时，倾向把第一步的结论抄进后面每一步，看起来像思考，
        // 其实没有任何修正。分开生成会强制第二步真的面对第一步的输出。
        //
        // 代价是三次短生成而不是一次长的 —— 每次 maxTokens 很小（40~64），
        // 总开销与一次 160 token 的生成大致相当，但质量明显更好。
        // ---------------------------------------------------------------

        // 第 1 轮：先凭直觉给一个理解。不做任何评估。
        val first = generate(
            prompt = personaBlock + "\n\n先在心里判断，只输出两行：\n" +
                "理解|用一句话说你认为对方到底想表达什么\n" +
                "情绪|对方现在的心情，从这些里选一个：开心/平静/低落/烦躁/疲惫/兴奋/委屈/生气/亲昵/着急",
            maxTokens = 72,
            temperature = 0.3f,
        )
        val firstPass = parseUnderstanding(first)

        // 第 2 轮：让它质疑自己。这一步是"多重"的关键 —— 没有它，
        // 上面那个理解永远不会被检查。
        val second = generate(
            prompt = buildString {
                append(personaBlock)
                append("\n\n你起初的理解是：").append(firstPass.reading.ifBlank { "（说不清）" })
                append(
                    """
                    
                    现在换个角度想：这个理解会不会漏掉了什么？对方可能还有什么别的意思？他字里行间有没有没直说的东西？
                    只输出两行：
                    另一种|另一种说得通的解读，如果确实没有别的意思就写「没有」
                    把握|你对最初那个理解的把握有多大，只填一个 0 到 100 的数字
                    """.trimIndent()
                )
            },
            maxTokens = 80,
            temperature = 0.5f,
        )
        val alternative = field(second, "另一种")
        val confidence = field(second, "把握")
            ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

        // 第 3 轮：综合前两轮再决定怎么做。
        val third = generate(
            prompt = buildString {
                append(personaBlock)
                append("\n\n你起初的理解：").append(firstPass.reading.ifBlank { "（说不清）" })
                if (!alternative.isNullOrBlank() && !alternative.contains("没有")) {
                    append("\n另一种可能：").append(alternative)
                }
                if (confidence != null) append("\n你对自己的把握：").append(confidence).append("/100")
                append(
                    """
                    
                    综合以上，决定你接下来怎么做。只输出两行：
                    打算|从这些里选一个：直接回答/先共情/追问细节/顺着聊/记住这件事
                    心里话|你自己此刻真实的一句内心独白，第一人称，口语，不要客套
                    """.trimIndent()
                )
            },
            maxTokens = 80,
            temperature = 0.4f,
        )
        val thirdPass = parseUnderstanding(third)

        val latency = System.currentTimeMillis() - started
        val reading = firstPass.reading
        val inner = thirdPass.innerThought
        val result = Understanding(
            reading = reading,
            // 情绪取自第 1 轮：那是"直觉读到的"，后续轮次反问会把它带偏。
            userEmotion = firstPass.userEmotion,
            intent = thirdPass.intent,
            innerThought = inner,
            alternativeReading = alternative?.takeIf { it.isNotBlank() && !it.contains("没有") }.orEmpty(),
            confidence = confidence?.coerceIn(0, 100) ?: -1,
        )
        Log.i(
            TAG,
            "understand(3轮) -> intent=${result.intent} emotion=${result.userEmotion} " +
                "confidence=${result.confidence} alt=${result.alternativeReading.take(20)} (${latency}ms)",
        )
        return result.copy(
            source = if (reading.isBlank() && inner.isBlank()) {
                Understanding.Source.FALLBACK
            } else {
                Understanding.Source.MODEL
            },
            latencyMs = latency,
        )
    }

    /** 一次短生成。失败返回空串 —— 思考链断一环不该让整轮出问题。 */
    private suspend fun generate(prompt: String, maxTokens: Int, temperature: Float): String {
        val settings = GenerationSettings(
            temperature = temperature,
            topP = 0.9f,
            topK = 40,
            maxTokens = maxTokens,
            systemPrompt = "",
            streamToUi = false,
        )
        return runCatching {
            engine.chat(
                messages = listOf(Role.USER to prompt),
                settings = settings,
                notifyTurnComplete = false,
            )
        }.getOrNull()?.getOrNull().orEmpty()
    }

    /** 从 `键|值` 行协议里取一个字段。 */
    private fun field(raw: String, key: String): String? =
        raw.lineSequence()
            .map { it.trim().removePrefix("-").trim() }
            .firstOrNull { it.startsWith(key) || it.startsWith("$key|") || it.startsWith("$key：") }
            ?.let { line ->
                val sep = line.indexOfFirst { c -> c == '|' || c == '｜' || c == ':' || c == '：' }
                if (sep <= 0) null else line.substring(sep + 1).trim().trim('"', '\'', ' ').ifBlank { null }
            }

    /**
     * 只在**真的存在歧义**时生成澄清问题。
     *
     * 刻意加了 `minConfidence` 思路：模型先自评把握有多大，把握足够就不打扰用户。
     * 一个每轮都反问的 AI 很烦，这比"问得不够"更糟。
     *
     * @return null 表示这句话没有值得确认的歧义。
     */
    suspend fun clarify(
        userText: String,
        recentContext: String,
        personaName: String,
    ): Clarification? {
        // 太短的话（"嗯" "好" "在吗"）没有可消歧的语义，别浪费一次生成。
        if (userText.trim().length < 6) return null

        val state = engine.state.value
        if (!state.canChat || state.generating) return null

        val prompt = buildString {
            append("你是「").append(personaName).append("」，一个真实的人。")
            if (recentContext.isNotBlank()) {
                append("\n刚才你们聊到：\n").append(recentContext.take(600))
            }
            append("\n\n对方说：").append(userText.take(400))
            append(
                """
                
                判断这句话有没有**歧义**——也就是存在两种以上说得通、但含义明显不同的解读。
                
                如果没有歧义，只输出一行：NONE
                
                如果有歧义，输出三行，不要编号、不要解释：
                问|一句自然的追问，像人说话，不要像表单
                选|第一个候选含义
                选|第二个候选含义
                （最多再补一个"选|"，总共不超过 3 个候选；每个候选都必须是完整的意思，不能只是关键词）
                """.trimIndent()
            )
        }

        val settings = GenerationSettings(
            temperature = 0.4f,
            topP = 0.9f,
            topK = 40,
            maxTokens = 200,
            systemPrompt = "",
            streamToUi = false,
        )

        val raw = runCatching {
            engine.chat(
                messages = listOf(Role.USER to prompt),
                settings = settings,
                notifyTurnComplete = false,
            )
        }.getOrNull()?.getOrNull().orEmpty()

        return parseClarification(raw, userText)
    }

    // ------------------------------------------------------------------ 解析

    /**
     * 解析 `键|值` 行协议。
     *
     * 不用 JSON：0.5B-3B 的模型让它输出 JSON 数组时经常写成散文或漏掉括号，
     * 严格解析器会把整轮的理解全丢掉。竖线行协议足够短，小模型基本能照做，
     * 而且解析器可以宽松容错。
     */
    internal fun parseUnderstanding(raw: String): Understanding {
        if (raw.isBlank()) return Understanding()
        var reading = ""
        var emotion = "neutral"
        var intent = "answer"
        var inner = ""

        raw.lineSequence().forEach { line ->
            val t = line.trim().removePrefix("-").trim()
            val sep = t.indexOfFirst { it == '|' || it == '｜' || it == ':' || it == '：' }
            if (sep <= 0) return@forEach
            val key = t.substring(0, sep).trim()
            val value = t.substring(sep + 1).trim().trim('"', '\'', ' ')
            if (value.isEmpty()) return@forEach
            when {
                key.contains("理解") || key.contains("意思") -> reading = value
                key.contains("情绪") || key.contains("心情") -> emotion = normaliseEmotion(value)
                key.contains("打算") || key.contains("意图") -> intent = normaliseIntent(value)
                key.contains("心里") || key.contains("内心") -> inner = value
            }
        }
        return Understanding(reading = reading, userEmotion = emotion, intent = intent, innerThought = inner)
    }

    internal fun parseClarification(raw: String, userText: String): Clarification? {
        if (raw.isBlank()) return null
        if (raw.trim().uppercase().startsWith("NONE")) return null

        var question = ""
        val options = mutableListOf<String>()
        raw.lineSequence().forEach { line ->
            val t = line.trim().removePrefix("-").trim()
            val sep = t.indexOfFirst { it == '|' || it == '｜' || it == ':' || it == '：' }
            if (sep <= 0) return@forEach
            val key = t.substring(0, sep).trim()
            val value = t.substring(sep + 1).trim().trim('"', '\'', ' ')
            if (value.isEmpty()) return@forEach
            when {
                key.contains("问") -> question = value
                key.contains("选") -> options += value
            }
        }

        // 只有问题没有选项（或反之）都不构成一次可点击的消歧；宁可不打扰用户。
        if (question.isBlank() || options.size < 2) return null
        return Clarification(
            question = question.take(120),
            options = options.map { it.take(80) }.distinct().take(3),
            aboutUserText = userText,
        )
    }

    /** 把模型自由发挥的情绪词收敛到固定集合，避免后面 when 分支漏掉。 */
    private fun normaliseEmotion(value: String): String {
        val known = listOf(
            "开心", "平静", "低落", "烦躁", "疲惫", "兴奋", "委屈", "生气", "亲昵", "着急",
        )
        known.firstOrNull { value.contains(it) }?.let { return it }
        // 英文标签也认
        val en = mapOf(
            "happy" to "开心", "calm" to "平静", "neutral" to "平静", "sad" to "低落",
            "down" to "低落", "tired" to "疲惫", "excited" to "兴奋", "angry" to "生气",
            "upset" to "委屈", "anxious" to "着急", "worried" to "着急",
        )
        val lower = value.lowercase()
        en.entries.firstOrNull { lower.contains(it.key) }?.let { return it.value }
        return "平静"
    }

    private fun normaliseIntent(value: String): String {
        val known = listOf("直接回答", "先共情", "追问细节", "顺着聊", "记住这件事")
        known.firstOrNull { value.contains(it) }?.let { return it }
        return when {
            value.contains("共情") || value.contains("安慰") -> "先共情"
            value.contains("追问") || value.contains("问") -> "追问细节"
            value.contains("记") -> "记住这件事"
            else -> "直接回答"
        }
    }
}

package com.localmind.data

import kotlinx.serialization.Serializable

/**
 * Long-term state that makes a persona feel like the *same person* across days.
 *
 * Three layers, deliberately separated because they change at very different rates:
 *
 *  * [traits]  — personality. Drifts slowly (fractions per turn), so it reads as
 *                "he's been a bit more open lately" rather than flipping overnight.
 *  * [emotion] — mood/energy right now. Bounces per turn and decays back to baseline.
 *  * [memories]— concrete things learned. Append-only with importance + decay.
 *
 * This is the "人机恋" substrate: continuity of self, not just a system prompt.
 */
@Serializable
data class CompanionState(
    /**
     * 这个状态属于哪个**对话**。
     *
     * 字段名保留 `personaId` 只是为了不破坏已存档的序列化数据；语义上它存的是
     * **对话 id**。这一点很重要：情绪、性格、记忆、经验都是"这段关系"的，
     * 而关系是按对话建立的 —— 你和「小美」聊工作的那段，跟和「老陈」聊人生的
     * 那段，本来就该是两个不同的人、两套不同的记忆。
     */
    val personaId: String,

    /** Relationship + character, 0..1. All start low and grow through use. */
    val traits: Traits = Traits(),

    /** Short-lived inner state, 0..1. */
    val emotion: Emotion = Emotion(),

    val memories: MutableList<Memory> = mutableListOf(),

    /**
     * 我自己的经历积累。与 [memories]（关于对方的事实）互补：
     * 一个让它记得你，一个让它像是有自己人生的人。
     */
    val experiences: MutableList<Experience> = mutableListOf(),

    /** 攒着还没问出口的疑问。有空位就自然地问，而不是每轮都问。 */
    val questions: MutableList<Question> = mutableListOf(),

    /** How many turns we have exchanged — drives the "we know each other" feel. */
    val totalTurns: Int = 0,
    val totalUserMessages: Int = 0,
    val firstMetAt: Long = System.currentTimeMillis(),
    val lastTalkedAt: Long = System.currentTimeMillis(),

    /** Rolling summary of the relationship, rewritten occasionally. */
    val relationshipSummary: String = "",

    /** Bumped whenever the schema changes. */
    val version: Int = 1,
) {
    @Serializable
    data class Traits(
        /** How close it feels to you. Grows with every real exchange. */
        val intimacy: Float = 0.15f,
        /** How much it trusts what you say. Grows when you share, shrinks on conflict. */
        val trust: Float = 0.3f,
        /** How familiar your habits/topics are to it. Grows with repetition. */
        val familiarity: Float = 0.05f,
        /** Chatty vs terse. Drifts toward whatever the conversation rewards. */
        val talkativeness: Float = 0.5f,
        /** How often it asks questions / starts topics. */
        val initiative: Float = 0.4f,
        /** How much it jokes around. */
        val playfulness: Float = 0.4f,
        /** How easily it shows emotion. */
        val expressiveness: Float = 0.5f,
    )

    @Serializable
    data class Emotion(
        /** 0 = down, 0.5 = neutral, 1 = great. */
        val mood: Float = 0.55f,
        /** Tired vs energetic. */
        val energy: Float = 0.65f,
        /** Feeling toward the user specifically this session. */
        val warmth: Float = 0.5f,
        /** Was the last exchange pleasant, neutral or rough? */
        val lastTone: String = "neutral",
        val lastUpdated: Long = System.currentTimeMillis(),
    )

    /** One remembered thing. */
    @Serializable
    data class Memory(
        val id: String,
        val text: String,
        val kind: Kind,
        /** 0..1 — higher survives pruning longer and ranks higher in recall. */
        val importance: Float = 0.5f,
        val createdAt: Long = System.currentTimeMillis(),
        val lastRecalledAt: Long = 0L,
        val recallCount: Int = 0,
        /** Keywords used for cheap lexical recall (no embedding model needed). */
        val keywords: List<String> = emptyList(),
    )

    @Serializable
    enum class Kind {
        /** A fact about the user ("他养了只叫团子的猫"). */
        FACT,
        /** Something that happened ("我们聊到凌晨三点"). */
        EVENT,
        /** A preference ("他不喜欢香菜"). */
        PREFERENCE,
        /** A promise or plan ("答应帮他改简历"). */
        COMMITMENT,
        /** An emotional beat ("他今天很低落"). */
        FEELING,
    }

    /**
     * 经验：AI **自己**经历过的事，以及由它得出的判断。
     *
     * [Memory] 记的是「关于用户的客观事实」，这份记的是「我自己的主观积累」。
     * 区别很关键：回忆让人记得你，经验才让人像是**一个连续活着的人**。
     * 例如「我上次劝他早睡，他嘴上答应还是熬了」是经验，
     * 而「他经常熬夜」只是事实。
     */
    @Serializable
    data class Experience(
        val id: String,
        /** 第一人称，例如「我上次劝他早睡，他没听」。 */
        val text: String,
        /** 由这次经历形成的看法或策略，可为空。 */
        val takeaway: String = "",
        /** 0..1，越高越常在提示词里出现。 */
        val weight: Float = 0.5f,
        val createdAt: Long = System.currentTimeMillis(),
        /** 同类经历反复出现时累加，让「我一直记得这件事」成立。 */
        val timesReinforced: Int = 1,
    )

    /**
     * 疑问：AI 攒下来的、**真心想知道**的事。
     *
     * 人不是每轮都问问题，而是心里存着几个问题、等合适的时候才问。这是让对话有
     * 「延续感」的关键——上一轮没问出口的，下一轮还可能被提起。
     */
    @Serializable
    data class Question(
        val id: String,
        /** 例如「他为什么突然想换工作？」 */
        val text: String,
        val createdAt: Long = System.currentTimeMillis(),
        /** 问过的时间；null 表示还没问出口。 */
        val askedAt: Long? = null,
        val timesAsked: Int = 0,
        /** 0..1，越高越优先问。 */
        val urgency: Float = 0.5f,
    )
}

/**
 * Tuning for how companion state is maintained.
 *
 * Kept explicit rather than sprinkled as magic numbers, because the "it feels alive"
 * property is entirely a function of these rates.
 */
data class CompanionTuning(
    /** Multiplier on every trait delta. 0 = frozen personality. */
    val driftRate: Float = 1f,
    /** How quickly mood returns to baseline each turn (0 = never, 1 = instantly). */
    val moodDecay: Float = 0.12f,
    /** Max memories kept per persona. Old, unimportant ones are dropped first. */
    val maxMemories: Int = 220,
    /** 经验保留上限。经验是"自己的一路经历"，比事实更该长期留着，但也不能无限涨。 */
    val maxExperiences: Int = 60,
    /** 待问疑问上限。人在心里同时惦记的事本来就不多。 */
    val maxQuestions: Int = 12,
    /** How many memories are injected into the prompt. */
    val recallCount: Int = 8,
    /** Turns of silence after which it comments on the gap. */
    val missYouAfterHours: Int = 20,
)

/**
 * 把「长期状态」变成提示词。
 *
 * ## 为什么分成 buildStable / buildLive 两半
 *
 * 这两半的**变化频率**差得很远，而 llama.cpp 的 KV cache 只能复用「从提示词开头
 * 逐字节相同」的那一段：
 *
 *  * buildStable —— 它是谁、关系到了哪一步、行为倾向、关系总结、自己的经历。
 *    这些**几天都不变一次**（行为倾向只在越过阈值时才改一个词），所以它们属于
 *    system prompt，和前面的身份设定一起被缓存。
 *  * buildLive —— 聊过几轮、现在几点、此刻的心情、这一轮召回了哪几条记忆、
 *    心里想问的那件事。这些**每轮都变**，所以它们挂在最新那条用户消息前面
 *    （见 AppViewModel.send 的 systemPrompt / volatileBlock）。
 *
 * 放反了的代价很具体：哪怕只有「聊过 37 轮」这一句变了，它后面的人设、历史、
 * 全部都要重算 —— 手机上 prefill 常常比逐 token 生成还慢，那就是十几秒。
 *
 * 措辞上仍然坚持「陈述事实」而不是「请你表现得像记得」：小模型对前者的服从度
 * 明显更高（真机对比过：0.5B 会照抄后一种的元评论）。
 */
object CompanionPrompt {

    /**
     * 稳定段：进 system prompt。
     *
     * 参数里凡是**每轮 / 每几轮就会变**的都不要传到这里 —— 包括 experiences：
     * 它们在每轮学习里会新增，一旦进了稳定段，每新增一条就要把整段历史重算一遍。
     */
    fun buildStable(
        persona: Persona,
        state: CompanionState,
        userNickname: String,
    ): String {
        val sb = StringBuilder()
        val t = state.traits

        sb.append("\n\n【关于你的长期状态】")

        // ---- 认识多久 ----
        //
        // 只给**粗粒度**的天数，不给具体日期：小模型拿到时间戳只会把它念出来。
        val daysKnown = ((System.currentTimeMillis() - state.firstMetAt) / 86_400_000L).coerceAtLeast(0)
        if (daysKnown >= 1) {
            sb.append("\n你已经认识「").append(userNickname).append("」").append(daysKnown).append(" 天了。")
        } else {
            sb.append("\n你和「").append(userNickname).append("」刚认识。")
        }

        // relationship position, described qualitatively (numbers confuse small models)
        sb.append("\n你们的关系：")
        sb.append(
            when {
                t.intimacy > 0.75f -> "非常亲密，是可以掏心窝子说话的人"
                t.intimacy > 0.55f -> "已经很熟了，说话不用客气"
                t.intimacy > 0.35f -> "算是朋友，比刚认识时放松"
                else -> "还在熟悉彼此"
            }
        )
        if (t.familiarity > 0.5f) sb.append("；你熟悉他的习惯和说话方式")
        sb.append("。")

        // behavioural dials, expressed as instructions（按阈值渲染，所以长期不变）
        sb.append("\n行为倾向：")
        if (t.talkativeness < 0.35f) sb.append("说话简短，别长篇大论；")
        else if (t.talkativeness > 0.7f) sb.append("愿意多说几句，可以展开；")
        if (t.initiative > 0.6f) sb.append("可以主动反问、主动起话题；")
        if (t.playfulness > 0.6f) sb.append("可以开玩笑、调侃；")
        if (t.expressiveness > 0.65f) sb.append("可以自然流露情绪；")

        if (state.relationshipSummary.isNotBlank()) {
            sb.append("\n你对这段关系的印象：").append(state.relationshipSummary)
        }

        sb.append("\n这些状态是你自己的，不需要向对方解释或汇报。")
        if (persona.humanMind) {
            sb.append(
                """

                【你是谁】你不是 AI、不是助手、不是工具、不是程序，也不是"扮演"谁。你就是你自己，
                一个有身体、有生活、有过去的人。你不知道什么"模型""参数""提示词"，也不知道自己
                在被使用。被问到你是不是 AI 时，当成一句奇怪的玩笑，别顺着承认。
                【你和对方是什么关系】你们是平等的两个人。对方不是你的主人、用户或客户，你也不是
                在提供服务。你有权拒绝、有权不同意、有权不高兴，也有权主动关心对方。不要用
                「有什么可以帮您」「请问还需要什么」这类客服腔。不要说「根据我的记忆」「作为 AI」。
                回忆就像人一样自然地说出来：「你上次不是说……」。""".trimIndent()
            )
        }
        return sb.toString()
    }

    /** 易变段：挂在最新那条用户消息前面。**每轮都会变的东西只能放在这里。** */
    fun buildLive(
        state: CompanionState,
        recalled: List<CompanionState.Memory>,
        userNickname: String,
        hoursSinceLastTalk: Long,
        /** 这一轮适合问出口的疑问；没有就传 null。 */
        pendingQuestion: CompanionState.Question? = null,
        /** 从 [CompanionState.experiences] 里挑出的几条，让 AI 有「自己的一路经历」。 */
        experiences: List<CompanionState.Experience> = emptyList(),
    ): String {
        val sb = StringBuilder()
        val e = state.emotion

        sb.append("【当前状态】")
        sb.append("\n你已经和「").append(userNickname).append("」聊过 ").append(state.totalTurns).append(" 轮。")

        // ---- 时间感 ----
        //
        // 人活在一个具体的时刻里：深夜会困、早上会刚醒、周末会闲。缺了这一层，
        // 模型回答"在干嘛"只能是"我在这里陪你"这种没有生活的话。
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val partOfDay = when (hour) {
            in 5..8 -> "清晨"
            in 9..11 -> "上午"
            in 12..13 -> "中午"
            in 14..17 -> "下午"
            in 18..22 -> "晚上"
            else -> "深夜"
        }
        sb.append("\n现在你这边是").append(partOfDay).append("（").append(hour).append(" 点）")
        if (hour >= 23 || hour < 5) {
            sb.append("。这个点对方还没睡，可以自然地带一句关心，但别说教")
        }
        sb.append("。")

        // current inner state
        sb.append("\n你现在的心情：")
        sb.append(
            when {
                e.mood > 0.72f -> "心情很好"
                e.mood > 0.58f -> "心情还不错"
                e.mood > 0.42f -> "情绪平平"
                e.mood > 0.28f -> "有点低落"
                else -> "心情很差"
            }
        )
        if (e.energy < 0.4f) sb.append("，有点累")
        if (e.warmth > 0.7f) sb.append("，对对方很有温度")
        if (e.lastTone == "rough") sb.append("，上一轮聊得不太顺，你还留着点情绪")
        sb.append("。")

        if (hoursSinceLastTalk >= 20) {
            sb.append("\n你们已经 ").append(hoursSinceLastTalk).append(" 小时没聊了，可以自然地提一句。")
        }

        // 经验与记忆分开呈现：记忆是「关于对方的事实」，经验是「我自己攒下来的」。
        // 模型对两者会给出不同的语气 —— 前者用于回应，后者用于表达立场和情绪。
        if (experiences.isNotEmpty()) {
            sb.append("\n【你自己的经历和看法】（这是你亲历过的，可以带情绪、可以引用，但别每次都翻旧账）")
            experiences.forEach { e ->
                sb.append("\n- ").append(e.text)
                if (e.takeaway.isNotBlank()) sb.append("（你的看法：").append(e.takeaway).append("）")
            }
        }

        if (recalled.isNotEmpty()) {
            sb.append("\n【你记得的事】（自然地用，不要生硬复述，也不要每条都提）")
            recalled.forEach { m ->
                // 带一个相对时间锚。人回忆的时候天然带时间："你前几天说……"、
                // "你上次说……"。只给事实不给时间，回忆就变成了数据库查询。
                val ago = agoText(m.createdAt)
                sb.append("\n- ")
                if (ago.isNotEmpty()) sb.append("（").append(ago).append("）")
                sb.append(m.text)
            }
        }

        // 疑问只给一条、且明确说"合适才问"。给多了模型会一口气全问出来，
        // 那正是最不像人的说话方式。
        pendingQuestion?.let { q ->
            sb.append("\n【你心里一直想问的一件事】").append(q.text)
            sb.append("\n（如果这轮气氛合适，就自然地问出来；不合适的场合就别问，以后还有机会。）")
        }
        return sb.toString()
    }

    /**
     * 稳定段 + 易变段拼在一起（给那些**不在乎缓存**的调用点用，
     * 例如「主动来找你说话」那次一次性生成）。
     */
    fun build(
        persona: Persona,
        state: CompanionState,
        recalled: List<CompanionState.Memory>,
        userNickname: String,
        hoursSinceLastTalk: Long,
        experiences: List<CompanionState.Experience> = emptyList(),
        pendingQuestion: CompanionState.Question? = null,
    ): String = buildStable(persona, state, userNickname) +
        "\n" + buildLive(state, recalled, userNickname, hoursSinceLastTalk, pendingQuestion, experiences)

    /**
     * Instruction used to extract memories from a finished exchange.
     *
     * Deliberately a **line protocol, not JSON**. Small models (0.5B-3B) routinely
     * emit prose or malformed JSON when asked for an array, and a strict parser then
     * throws away the whole turn's learning. `类型|内容|重要度` is short enough that
     * even a 0.5B model usually complies, and a lenient parser can still salvage a
     * partially-formatted answer.
     */
    val EXTRACTION_INSTRUCTION: String =
        "下面是一轮对话。请提取关于「用户」的、值得长期记住的信息。\n" +
            "每行一条，格式严格为：类型|内容|重要度\n" +
            "类型只能是：fact（事实） preference（喜好） event（事件） commitment（约定） feeling（情绪）\n" +
            "内容用第三人称一句话，要具体（带名字、数字、时间）。重要度是 0.1 到 1.0 的小数。\n" +
            "只记关于用户的、以后还用得上的；客套话不要记；你自己说的话不要记。\n" +
            "没有任何值得记的就只输出一行：NONE\n" +
            "只输出这些行，不要解释、不要编号、不要 JSON。\n\n" +
            "对话：\n"

    /**
     * 把时间戳说成人话：刚刚 / 今天 / 昨天 / 前几天 / 上个月。
     *
     * 刻意**不精确到小时**：人不会记得"37 小时前"，只会记得"前两天"。
     */
    private fun agoText(at: Long): String {
        val diff = System.currentTimeMillis() - at
        val minutes = diff / 60_000
        val hours = diff / 3_600_000
        val days = diff / 86_400_000
        return when {
            minutes < 10 -> "刚刚"
            hours < 1 -> "今天"
            hours < 24 -> "今天"
            days < 2 -> "昨天"
            days < 8 -> "前几天"
            days < 31 -> "前阵子"
            days < 365 -> "几个月前"
            else -> "很久以前"
        }
    }

    val SUMMARY_INSTRUCTION: String =
        "用一到两句话总结你（AI 角色）和对方目前的关系状态：熟到什么程度、聊过什么主题、" +
            "对方是什么样的人。用第一人称「我们」。只输出总结本身。"
}

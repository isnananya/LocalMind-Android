package com.localmind.service

import android.util.Log
import com.localmind.data.CompanionPrompt
import com.localmind.data.CompanionState
import com.localmind.data.CompanionTuning
import com.localmind.data.GenerationSettings
import com.localmind.data.Role
import com.localmind.data.Storage
import com.localmind.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * The "companion mind": long-term memory, drifting personality, and a present mood.
 *
 * Design constraints that shaped this:
 *
 *  * **It must work with a 0.5B model.** Recall therefore uses cheap lexical scoring
 *    (keyword overlap + recency + importance) instead of embeddings, and memory
 *    extraction tolerates malformed JSON by falling back to nothing rather than
 *    throwing.
 *  * **It must never block a reply.** Extraction and trait drift run *after* the
 *    assistant's answer has been shown; if the model is busy they are skipped for
 *    that turn instead of queueing up.
 *  * **Personality must not flip.** Trait deltas are tiny per turn and clamped, so
 *    change is only visible across many conversations — which is exactly what makes
 *    it feel like a person rather than a mood swing.
 */
private const val TAG = "CompanionMind"

/**
 * 交给模型挑选的候选上限。
 *
 * 不是越大越好：候选越多，小模型越容易挑错，提示词也越长。结构粗筛已经按
 * 「承诺 → 高重要度 → 近期」排好序，取前 24 条覆盖了绝大多数真实情况。
 */
private const val STRUCTURAL_CAP = 24

class CompanionMind(
    private val storage: Storage,
    private val engine: LlamaEngine,
    /**
     * Optional second engine reserved for the decision model. When supplied, routing
     * judgement runs on its own resident model instead of borrowing the chat model.
     */
    dedicatedDecisionEngine: LlamaEngine? = null,
    private val tuning: CompanionTuning = CompanionTuning(),
) {

    /**
     * The function-calling router. Used for judgement calls (what to remember, how the
     * user feels) so behaviour is driven by a model's choice instead of hand-written
     * heuristics. When no decision model is bound, [DecisionEngine.available] is false
     * and callers fall back to the rule floor.
     */
    private val decision = DecisionEngine(engine, dedicatedDecisionEngine)

    /**
     * Which model makes the judgement calls. Bound by the app whenever routing settings
     * change. `null` / blank means "no router bound" -> the rule floor is used,
     * **not** the chat model (see [DecisionEngine.routeEngine]).
     */
    fun bindDecisionModel(modelId: String?) {
        decision.boundModelName = modelId?.takeIf { it.isNotBlank() }
        // A new binding invalidates any previous load failure.
        decision.dedicatedLoadFailed = false
    }

    /**
     * Called when the dedicated decision engine failed to load the bound model, so the
     * router stops pretending and lets callers use the rule floor instead.
     */
    fun markDecisionLoadFailed() {
        decision.dedicatedLoadFailed = true
    }

    /** Exposed so the UI can show whether decisions go through a model or the rules. */
    fun decisionModelBound(): Boolean = decision.boundModelName != null

    /**
     * 允许"经验 / 心事"的提炼退回**对话模型**。
     *
     * 默认 false：那是一次额外的生成，而它和用户的下一条消息抢同一个引擎。
     * 由「我的 -> 对话行为 -> 多重思考」开关驱动 —— 用户既然明确选了"更准但更慢"，
     * 这一项就该跟着打开；否则界面上的"经验"永远是 0 条，成了一个死功能。
     *
     * 绑了专用决策模型的用户不受影响：那条路只花它自己的算力。
     */
    @Volatile
    var allowChatModelFallback: Boolean = false

    /**
     * True only when a **dedicated** router model is resident and making the calls —
     * as opposed to decisions borrowing the chat model.
     */
    fun decisionUsingDedicatedModel(): Boolean = decision.usingDedicatedModel()

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------ state access

    /**
     * 关系状态的内存副本。
     *
     * ## 为什么必须缓存
     *
     * companion.json 是**每轮都在长**的文件：记忆、经验、疑问都会累积。
     * 而原来每一次 [stateOf] / [allStates] / [save] 都要把整个文件读出来、
     * JSON 反序列化一遍 —— 包括 send() 在主线程上取当前关系状态那一次。
     *
     * 结果是聊得越久越慢：文件越大，每发一句话之前的固定开销越高。这跟模型
     * 推理本身没关系，纯粹是白花的 I/O。
     *
     * 现在只有**写**才碰磁盘，读全部走内存。所有访问都在 [stateLock] 里做，
     * 因为发送路径（主线程）和 afterTurn（IO 线程）会同时碰它。
     */
    @Volatile
    private var cache: MutableMap<String, CompanionState>? = null

    private val stateLock = Any()

    /** 取内存副本；第一次调用才读盘。 */
    private fun states(): MutableMap<String, CompanionState> =
        cache ?: synchronized(stateLock) {
            cache ?: storage.loadCompanionStates().also { cache = it }
        }

    fun stateOf(personaId: String): CompanionState =
        states()[personaId] ?: CompanionState(personaId = personaId)

    /** 返回副本：调用方会把它塞进 StateFlow，不能和内部状态共享同一个 map。 */
    fun allStates(): MutableMap<String, CompanionState> =
        synchronized(stateLock) { LinkedHashMap(states()) }

    fun save(state: CompanionState) {
        synchronized(stateLock) {
            val map = states()
            map[state.personaId] = state
            storage.saveCompanionStates(map)
        }
    }

    fun reset(personaId: String) {
        synchronized(stateLock) {
            val map = states()
            map.remove(personaId)
            storage.saveCompanionStates(map)
        }
    }

    // ---------------------------------------------------------------- recall

    /**
     * 存下一条**用户亲自确认过**的语义。
     *
     * 这是整套记忆里最可靠的一类：不是模型从话里推断的，而是用户点了"就是这个意思"。
     * 所以给它高重要度（0.92），并保留原话，方便以后按原话回溯。
     */
    fun recordConfirmedMeaning(personaId: String, userText: String, confirmed: String) {
        var state = stateOf(personaId)
        val text = "他说「${userText.take(80)}」，真实意思是：${confirmed.take(120)}"
        val memory = CompanionState.Memory(
            id = TextUtils.randomId("mem"),
            text = text,
            kind = CompanionState.Kind.FACT,
            // 高于模型推断的事实：这是被确认过的。
            importance = 0.92f,
            keywords = keywordsOf(text).toList(),
        )
        val merged = (state.memories + memory)
            .distinctBy { it.text.lowercase().take(40) }
            .toMutableList()
        state = state.copy(memories = prune(merged))
        save(state)
        Log.i(TAG, "recordConfirmedMeaning: ${confirmed.take(40)}")
    }

    /**
     * Picks the memories worth putting in the prompt.
     *
     * Score = keyword overlap with the current message, + importance, + mild recency,
     * with a penalty for things already recalled many times (they are already part of
     * the relationship and do not need repeating every turn).
     */
    /** 一次召回的结果：记忆 + 经验 + 这次该问的那个疑问。 */
    data class Recall(
        val memories: List<CompanionState.Memory> = emptyList(),
        val experiences: List<CompanionState.Experience> = emptyList(),
        /** 本轮适合问出口的疑问，没有就是 null。 */
        val pendingQuestion: CompanionState.Question? = null,
        /** 这次召回是否真的由模型按含义挑的（用于诊断/展示）。 */
        val byModel: Boolean = false,
    )

    /**
     * 召回：由**模型按含义**挑选，而不是关键词重叠。
     *
     * 为什么必须换掉关键词：中文里「换工作」和「辞职」、「不舒服」和「感冒」字面
     * 完全不重叠，指代、省略、反讽更抓不到。按字面匹配注定召回不到该召回的，
     * 也拦不住不该召回的。
     *
     * 两段式，先便宜后昂贵：
     *
     *  1. **结构粗筛**（不调模型）：只看字段，不看字面。
     *     - `COMMITMENT` 永远入选 —— 答应了的事不能因为"没提关键词"就忘掉；
     *     - 高重要度（≥0.7）的常驻，构成"这个人的基本情况"；
     *     - 其余按时间取近期窗口。
     *     这一步是**结构性**的，不是关键词匹配。
     *  2. **模型选择**：把粗筛出的候选编号列出，让模型挑出与当前这句话**含义相关**的。
     *     小模型也能做，因为只需要输出编号。
     *
     * 模型不可用时退回纯结构结果 —— 宁可少召回，也不要按字面乱召回。
     */
    suspend fun recallByMeaning(
        state: CompanionState,
        userMessage: String,
        limit: Int = tuning.recallCount,
    ): Recall {
        if (state.memories.isEmpty() && state.experiences.isEmpty()) {
            return Recall(pendingQuestion = pickQuestion(state))
        }

        val candidatePool = structuralCandidates(state)
        val shortlist = candidatePool.take(STRUCTURAL_CAP)

        val chosen = selectByMeaning(userMessage, shortlist)
        val memories = if (chosen == null) {
            // 模型不可用：退回结构结果，并说明不是模型选的。
            shortlist.take(limit)
        } else {
            chosen.map { shortlist[it] }
        }

        return Recall(
            memories = memories,
            experiences = topExperiences(state),
            pendingQuestion = pickQuestion(state),
            byModel = chosen != null,
        )
    }

    /**
     * 结构粗筛：完全按字段判断，不涉及任何字面匹配。
     *
     * 顺序本身就是优先级：承诺 → 高重要度 → 最近发生的。
     */
    private fun structuralCandidates(state: CompanionState): List<CompanionState.Memory> {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val commitments = state.memories.filter { it.kind == CompanionState.Kind.COMMITMENT }
        val important = state.memories
            .filter { it.importance >= 0.7f && it.kind != CompanionState.Kind.COMMITMENT }
            .sortedByDescending { it.importance }
        val recent = state.memories
            .filter { it.importance < 0.7f && it.kind != CompanionState.Kind.COMMITMENT }
            .filter { now - it.createdAt <= 14 * day }
            .sortedByDescending { it.createdAt }

        return (commitments + important + recent)
            .distinctBy { it.id }
            // 已经被反复提起的往后放：它已经融进关系里，不必每轮复述。
            .sortedBy { it.recallCount }
    }

    /**
     * 让**对话模型**从候选里挑与当前这句话含义相关的。
     *
     * 用对话模型而不是决策小模型：判断"这句话和哪条记忆在含义上相关"需要真正的
     * 理解力 —— 「最近有点撑不住」要能连到「上个月连续加班」，0.5B 的小路由器
     * 做不到，它只擅长在少数几个固定动作里选一个。
     *
     * 只要求输出编号：这是模型唯一稳得住的结构化任务，不需要它写 JSON。
     * 返回 null 表示模型不可用或输出无法解析，调用方据此退回结构结果。
     */
    private suspend fun selectByMeaning(
        userMessage: String,
        candidates: List<CompanionState.Memory>,
    ): List<Int>? {
        if (candidates.isEmpty()) return emptyList()
        if (candidates.size <= 3) return candidates.indices.toList()   // 不值得为 3 条调模型

        // 只允许**专用决策引擎**做这件事，而且必须真的跑在它上面。
        //
        // 这一步跑在用户那句话**之前**：它一旦占住对话模型，用户的首 token 就得
        // 排在它后面 —— 每轮白白多一次 prefill 加几十个 token 的生成，体感上就是
        // 「发出去半天没反应」。原来的实现虽然要求「先绑了决策模型」，却仍然把
        // 请求发给**对话引擎**，等于既付了决策模型的内存，又没拿到它的算力；
        // 现在改走 DecisionEngine.router()，两边各跑各的。
        //
        // 没绑决策模型就退回结构粗筛：少召回几条，也好过让对话卡住。
        val router = decision.router() ?: return null
        val routerState = router.state.value
        if (!routerState.canChat || routerState.generating) return null

        val prompt = buildString {
            append("下面是你记得关于对方的一些事，以及对方刚说的一句话。\n")
            append("挑出和这句话**在含义上**相关的条目 —— 不要只看字面有没有同样的词。\n")
            append("例如对方说「最近有点撑不住」，那么「他上个月连续加班」相关，「他喜欢猫」不相关。\n\n")
            append("对方说：").append(userMessage.take(300)).append("\n\n你记得的事：\n")
            candidates.forEachIndexed { i, m ->
                append(i + 1).append(". ").append(m.text.take(80)).append('\n')
            }
            append("\n只输出相关条目的编号，逗号分隔，例如：1,3,5\n")
            append("一条都不相关就只输出：NONE")
        }

        val raw = runCatching {
            router.chat(
                messages = listOf(Role.USER to prompt),
                settings = GenerationSettings(
                    temperature = 0.0f,   // 选择要可复现
                    topP = 1.0f,
                    topK = 1,
                    maxTokens = 32,
                    systemPrompt = "",
                    streamToUi = false,
                ),
                notifyTurnComplete = false,
            )
        }.getOrNull()?.getOrNull().orEmpty()

        if (raw.isBlank()) return null
        if (raw.trim().uppercase().startsWith("NONE")) return emptyList()

        val picked = Regex("\\d+").findAll(raw)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..candidates.size }
            .distinct()
            .map { it - 1 }
            .take(tuning.recallCount)
            .toList()

        Log.i(TAG, "recallByMeaning: picked ${picked.size}/${candidates.size}")
        return picked
    }

    /**
     * 给"主动开口"那条路径用的公开入口。
     *
     * 主动开口不调模型去挑记忆（那要多花一次推理），所以它拿的是这个纯函数的结果。
     */
    fun topExperiencesFor(state: CompanionState, limit: Int = 3): List<CompanionState.Experience> =
        topExperiences(state, limit)

    /** 经验按权重取前几条；它们让 AI 显得有"自己的一路经历"。 */
    private fun topExperiences(state: CompanionState, limit: Int = 3): List<CompanionState.Experience> =
        state.experiences
            .sortedWith(compareByDescending<CompanionState.Experience> { it.weight }
                .thenByDescending { it.timesReinforced }
                .thenByDescending { it.createdAt })
            .take(limit)

    /**
     * 挑一个现在值得问出口的疑问。
     *
     * 规则是"能不问就不问"：优先没问过的、紧急度高的；问过两次以上的不再提，
     * 因为反复追问同一件事不像人，像坏掉的录音。
     */
    private fun pickQuestion(state: CompanionState): CompanionState.Question? =
        state.questions
            .filter { it.timesAsked < 2 }
            .sortedWith(compareByDescending<CompanionState.Question> { it.askedAt == null }
                .thenByDescending { it.urgency })
            .firstOrNull()

    /** 问完之后记账，避免同一件事被反复追问。 */
    fun markQuestionAsked(personaId: String, questionId: String) {
        val state = stateOf(personaId)
        val updated = state.questions.map {
            if (it.id == questionId) {
                it.copy(askedAt = System.currentTimeMillis(), timesAsked = it.timesAsked + 1)
            } else {
                it
            }
        }.toMutableList()
        save(state.copy(questions = updated))
    }

    /**
     * 召回：关键词打分版。
     *
     * **已不再是主路径**（[recallByMeaning] 取代了它），保留它有两个用途：
     * 一是模型不可用时仍能给出一个能用的结果；二是作为诊断对照。
     *
     * Score = keyword overlap with the current message, + importance, + mild recency,
     * with a penalty for things already recalled many times (they are already part of
     * the relationship and do not need repeating every turn).
     */
    fun recall(state: CompanionState, userMessage: String, limit: Int = tuning.recallCount): List<CompanionState.Memory> {
        if (state.memories.isEmpty()) return emptyList()

        val query = keywordsOf(userMessage)
        val now = System.currentTimeMillis()
        val hour = 3_600_000.0

        return state.memories
            .map { m ->
                val overlap = if (query.isEmpty()) 0f else {
                    val hits = m.keywords.count { it in query }
                    if (hits == 0) 0f else (hits.toFloat() / query.size).coerceAtMost(1f)
                }
                val ageHours = (now - m.createdAt) / hour
                val recency = (1.0 / (1.0 + ageHours / 72.0)).toFloat()      // ~3 day half-life
                val staleness = (m.recallCount * 0.03f).coerceAtMost(0.15f)
                val commitmentBoost = if (m.kind == CompanionState.Kind.COMMITMENT) 0.15f else 0f

                val score = overlap * 0.58f + m.importance * 0.30f + recency * 0.18f +
                    commitmentBoost - staleness
                m to score
            }
            .sortedByDescending { it.second }
            .take(limit)
            .filter { it.second > 0.12f }
            .map { it.first }
    }

    // ------------------------------------------------------------- one turn

    /** Result of applying a turn, for the UI to show what changed. */
    data class TurnOutcome(
        val state: CompanionState,
        val newMemories: List<CompanionState.Memory>,
        val recalled: List<CompanionState.Memory>,
    )

    /**
     * Post-answer bookkeeping. Runs off the UI thread; safe to skip.
     */
    suspend fun afterTurn(
        personaId: String,
        userMessage: String,
        assistantMessage: String,
        recalled: List<CompanionState.Memory>,
        personaHumanMind: Boolean,
        /**
         * 学这件事跑在哪条车道。默认后台：回答已经给用户了，这里的模型调用
         * （提炼经验 / 重写关系总结）不许排在用户的下一条消息前面。
         */
        lane: LlamaEngine.Lane = LlamaEngine.Lane.BACKGROUND,
    ): TurnOutcome = withContext(Dispatchers.IO) {
        var state = stateOf(personaId)

        // ---- 1. remember what was recalled (so it ages out of the prompt) ----
        if (recalled.isNotEmpty()) {
            val ids = recalled.map { it.id }.toSet()
            state = state.copy(
                memories = state.memories.map { m ->
                    if (m.id in ids) m.copy(lastRecalledAt = System.currentTimeMillis(), recallCount = m.recallCount + 1)
                    else m
                }.toMutableList()
            )
        }

        // ---- 2. sentiment + trait drift from THIS exchange -------------------
        state = drift(state, userMessage, assistantMessage)

        // ---- 3. extract new memories (needs the model) -----------------------
        val fresh = extractMemories(state, userMessage, assistantMessage, personaHumanMind)

        if (fresh.isNotEmpty()) {
            val merged = (state.memories + fresh)
                .distinctBy { it.text.lowercase().take(40) }
                .toMutableList()
            state = state.copy(memories = prune(merged))
        }

        state = state.copy(
            totalTurns = state.totalTurns + 1,
            totalUserMessages = state.totalUserMessages + 1,
            lastTalkedAt = System.currentTimeMillis(),
        )

        // ---- 3b. 经验与疑问：AI 自己的积累 --------------------------------
        // 每轮都做一次代价太高（两轮里就要各调一次模型）。改成本轮一次调用同时产出
        // 两者，且只在开了拟人模式时进行 —— 普通助手不需要"有自己的心事"。
        if (personaHumanMind) {
            val inner = extractInnerLife(userMessage, assistantMessage, lane)
            if (inner.experiences.isNotEmpty()) {
                val merged = (state.experiences + inner.experiences)
                    // 同一条经历反复出现时累加权重，而不是存两份。
                    .groupBy { it.text.lowercase().take(30) }
                    .map { (_, group) ->
                        val newest = group.maxByOrNull { it.createdAt } ?: group.first()
                        newest.copy(
                            timesReinforced = group.sumOf { it.timesReinforced },
                            weight = group.maxOf { it.weight },
                        )
                    }
                    .toMutableList()
                state = state.copy(
                    experiences = merged
                        .sortedByDescending { it.weight }
                        .take(tuning.maxExperiences)
                        .toMutableList()
                )
            }
            if (inner.questions.isNotEmpty()) {
                val merged = (state.questions + inner.questions)
                    .distinctBy { it.text.lowercase().take(24) }
                    .toMutableList()
                state = state.copy(questions = (merged.takeLast(tuning.maxQuestions)).toMutableList())
            }
        }

        // ---- 4. occasionally rewrite the relationship summary ---------------
        if (state.totalTurns % 12 == 0 && state.totalTurns > 0) {
            summarise(state, personaHumanMind, lane)?.let { state = state.copy(relationshipSummary = it) }
        }

        save(state)
        TurnOutcome(state, fresh, recalled)
    }

    /**
     * 让模型从这轮对话里提炼：**我**得到了什么经验、**我**还有什么想问的。
     *
     * 与 [extractMemories] 的区别是视角：那里是"关于对方的客观事实"，
     * 这里是"第一人称的主观积累"。所以提示词明确要求用"我"说话 ——
     * 否则小模型会把两者写成一样的东西，"经验"就退化成了第二条记忆。
     *
     * 一次调用同时产出两者，省掉半次往返。
     */
    private suspend fun extractInnerLife(
        userMessage: String,
        assistantMessage: String,
        lane: LlamaEngine.Lane,
    ): InnerLife {
        // 和召回一样：提炼"自己的经历和心事"需要理解力，所以判断走决策模型
        // （如果用户绑了的话），但**兜底走对话模型**而不是直接放弃 ——
        // 一个没配决策模型的人不该因此永远没有经验积累。
        if (decision.available()) {
            val outcome = decision.decide(
                systemContext = "你是一个有自己生活的真实的人，刚和对方聊完一轮。" +
                    "你要用第一人称提炼自己的收获和疑问，不要写成对对方的客观描述。",
                userText = buildString {
                    append("对方说：").append(userMessage.take(400))
                    append("\n你说：").append(assistantMessage.take(400))
                },
                choices = listOf(
                    DecisionEngine.Choice(
                        name = "EXP",
                        description = "这轮让你有了什么经验或看法，比如「我劝他早睡，他嘴上答应但没当回事」",
                        params = listOf(
                            DecisionEngine.Param("text", "第一人称一句话，以「我」开头"),
                            DecisionEngine.Param("take", "由这件事形成的看法，可为空", required = false),
                        ),
                    ),
                    DecisionEngine.Choice(
                        name = "ASK",
                        description = "你现在真心想知道、但这轮没问出口的一件事",
                        params = listOf(DecisionEngine.Param("text", "一个问句")),
                    ),
                    DecisionEngine.Choice(name = "NONE", "没什么特别的，就是普通闲聊"),
                ),
                fallback = "NONE",
                maxTokens = 96,
            )
            if (outcome.source == DecisionEngine.Outcome.Source.MODEL) {
                return innerLifeFromCall(
                    name = outcome.name,
                    text = decision.arg(outcome, "text", "content", "value"),
                    takeaway = decision.arg(outcome, "take", "takeaway", "view"),
                )
            }
        }
        // 没有专用决策模型时，是否还要用**对话模型**再写一遍？
        //
        // 这一步跑在每轮回答之后，而且和用户的下一条消息抢同一个引擎 ——
        // 所以它**不能默认开**（真机上"回答完接着说下一句半天没反应"就是它）。
        // 但也不能永远关着：界面上的"经验"会一直显示 0 条，变成死功能。
        //
        // 现在由「多重思考」开关统一决定 —— 用户明确选了"更准但更慢"，
        // 那就连这一项一起给他。绑了专用决策模型的用户走上面那条路，不受影响。
        if (!allowChatModelFallback) return InnerLife()
        return extractInnerLifeWithChatModel(userMessage, assistantMessage, lane)
    }

    /**
     * 没有决策模型时的兜底：直接让对话模型用行协议回答。
     *
     * 不用 JSON —— 小模型让它输出数组时经常写成散文，行协议容错得多。
     */
    private suspend fun extractInnerLifeWithChatModel(
        userMessage: String,
        assistantMessage: String,
        lane: LlamaEngine.Lane = LlamaEngine.Lane.BACKGROUND,
    ): InnerLife {
        if (engine.state.value.generating) return InnerLife()

        val prompt = buildString {
            append("你是一个有自己生活的真实的人，刚和对方聊完一轮。\n")
            append("对方说：").append(userMessage.take(400)).append('\n')
            append("你说：").append(assistantMessage.take(400)).append("\n\n")
            append("用第一人称提炼你自己的收获。只输出一到两行，不要解释：\n")
            append("经验|一句话，必须以「我」开头，写这轮让你有了什么看法\n")
            append("想问|你现在真心想知道但没问出口的一件事，没有就写「没有」\n")
            append("如果这轮只是普通闲聊、没有任何值得留下的东西，只输出：NONE")
        }

        val raw = runCatching {
            engine.chat(
                messages = listOf(Role.USER to prompt),
                settings = GenerationSettings(
                    temperature = 0.6f,
                    topP = 0.9f,
                    topK = 40,
                    maxTokens = 96,
                    systemPrompt = "",
                    streamToUi = false,
                ),
                notifyTurnComplete = false,
                lane = lane,
            )
        }.getOrNull()?.getOrNull().orEmpty()

        if (raw.isBlank() || raw.trim().uppercase().startsWith("NONE")) return InnerLife()

        var exp: String? = null
        var take: String? = null
        var ask: String? = null
        raw.lineSequence().forEach { line ->
            val t = line.trim().removePrefix("-").trim()
            val sep = t.indexOfFirst { c -> c == '|' || c == '｜' || c == ':' || c == '：' }
            if (sep <= 0) return@forEach
            val key = t.substring(0, sep).trim()
            val value = t.substring(sep + 1).trim().trim('"', '\'', ' ')
            if (value.isEmpty()) return@forEach
            when {
                key.contains("经验") -> exp = value
                key.contains("看法") -> take = value
                key.contains("想问") || key.contains("问") -> ask = value
            }
        }
        return innerLifeFromCall(
            name = if (exp != null) "EXP" else if (ask != null) "ASK" else "NONE",
            text = exp ?: ask,
            takeaway = take,
        )
    }

    /**
     * 把一次调用结果转成结构化产物，顺便做**质量闸门**。
     *
     * 经验必须真的是第一人称：如果模型给的是一句"他最近很累"，那其实是一条记忆，
     * 冒充经验只会让"它有自己的生活"这件事变成假的。
     */
    private fun innerLifeFromCall(name: String, text: String?, takeaway: String?): InnerLife {
        val t = text?.trim().orEmpty()
        return when (name) {
            "EXP" -> {
                if (t.length >= 6 && t.startsWith("我")) {
                    InnerLife(
                        experiences = listOf(
                            CompanionState.Experience(
                                id = TextUtils.randomId("exp"),
                                text = t.take(160),
                                takeaway = takeaway?.trim().orEmpty().take(100),
                                weight = 0.55f,
                            )
                        )
                    )
                } else {
                    InnerLife()
                }
            }

            "ASK" -> {
                if (t.length >= 4 && !t.contains("没有")) {
                    InnerLife(
                        questions = listOf(
                            CompanionState.Question(
                                id = TextUtils.randomId("q"),
                                text = t.take(120),
                                urgency = 0.5f,
                            )
                        )
                    )
                } else {
                    InnerLife()
                }
            }

            else -> InnerLife()
        }
    }

    /** [extractInnerLife] 的产物。 */
    private data class InnerLife(
        val experiences: List<CompanionState.Experience> = emptyList(),
        val questions: List<CompanionState.Question> = emptyList(),
    )

    // ------------------------------------------------------------- internals

    /**
     * Emotion bounces; personality creeps.
     *
     * Sentiment is detected with a small bilingual lexicon rather than a model call —
     * it has to be free, synchronous and good enough to move a number.
     */
    private fun drift(
        state: CompanionState,
        userMessage: String,
        assistantMessage: String,
    ): CompanionState {
        val sentiment = sentimentOf(userMessage)
        val engaged = userMessage.length
        val askedQuestion = userMessage.contains('?') || userMessage.contains('？')
        val sharedPersonal = PERSONAL_MARKERS.any { userMessage.contains(it) }

        val t = state.traits
        val e = state.emotion
        val k = tuning.driftRate

        // --- emotion: fast, decaying back toward a baseline ---
        val moodTarget = (0.55f + sentiment * 0.35f).coerceIn(0.05f, 1f)
        val mood = e.mood + (moodTarget - e.mood) * (1f - tuning.moodDecay) * 0.55f
        val energy = (e.energy - 0.015f + if (engaged > 40) 0.02f else 0f).coerceIn(0.1f, 1f)
        val warmth = (e.warmth + sentiment * 0.08f * k).coerceIn(0f, 1f)

        // --- traits: very small steps, only in response to real signals ---
        val intimacy = (t.intimacy + (if (sharedPersonal) 0.010f else 0.0022f) * k).coerceIn(0f, 1f)
        val trust = (t.trust + (if (sharedPersonal) 0.008f else 0.0015f) * k + sentiment * 0.004f * k)
            .coerceIn(0f, 1f)
        val familiarity = (t.familiarity + 0.0035f * k).coerceIn(0f, 1f)
        // chatty replies get rewarded if the user writes a lot, and vice versa
        val talkativeness = (t.talkativeness + (if (engaged > 80) 0.003f else if (engaged < 15) -0.002f else 0f) * k)
            .coerceIn(0.15f, 0.9f)
        val initiative = (t.initiative + (if (askedQuestion) 0.0025f else 0f) * k).coerceIn(0.1f, 0.9f)
        val expressiveness = (t.expressiveness + (if (sentiment > 0.4f) 0.002f else 0f) * k).coerceIn(0.2f, 1f)

        val tone = when {
            sentiment > 0.35f -> "warm"
            sentiment < -0.3f -> "rough"
            else -> "neutral"
        }

        return state.copy(
            traits = t.copy(
                intimacy = intimacy,
                trust = trust,
                familiarity = familiarity,
                talkativeness = talkativeness,
                initiative = initiative,
                expressiveness = expressiveness,
            ),
            emotion = e.copy(
                mood = mood,
                energy = energy,
                warmth = warmth,
                lastTone = tone,
                lastUpdated = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Asks the resident model to pull facts out of the exchange.
     *
     * Runs on the same single engine as chat, so it is skipped (returns empty) when a
     * generation is in flight — losing one memory is much better than stalling the UI.
     */
    private suspend fun extractMemories(
        state: CompanionState,
        userMessage: String,
        assistantMessage: String,
        humanMind: Boolean,
    ): List<CompanionState.Memory> {
        if (!engine.state.value.canChat) {
            Log.i(TAG, "extract: skipped, no chat-capable model")
            return emptyList()
        }
        if (userMessage.length < 6) {
            Log.i(TAG, "extract: skipped, message too short")
            return emptyList()
        }
        if (engine.state.value.generating) {
            Log.i(TAG, "extract: skipped, engine busy")
            return emptyList()
        }

        // The decision model picks the function. Because the choice is a checkable
        // boolean/label rather than free prose, this needs no prompt tuning per
        // capability — a new memory rule is just a new Choice in [Decisions].
        val decided = decideMemory(userMessage)
        if (decided != null) return decided

        // No decision model bound (or it was unusable): fall back to the regex floor so
        // durable facts are never lost merely because the router is absent.
        val fromRules = ruleBasedMemories(userMessage, state.memories)
        if (fromRules.isNotEmpty()) {
            Log.i(TAG, "extract: ${fromRules.size} from rules (decision model unavailable)")
            return fromRules
        }
        return emptyList()
    }

    /**
     * One function-calling round trip that yields zero or one memory.
     *
     * Returns null when no decision model is bound, so the caller can fall back.
     *
     * Only the USER's message is ever shown. Feeding the assistant's reply in caused
     * real damage during testing: the model hallucinated "我住在北京，养了狗狗和猫"
     * while *answering a question*, and the extractor stored the hallucination as a
     * fact about the user. A companion that remembers its own lies is worse than one
     * that forgets, so the reply is excluded and every candidate is grounded against
     * the user's own words by [plausibleUserFact].
     */
    private suspend fun decideMemory(userMessage: String): List<CompanionState.Memory>? {
        if (!decision.available()) return null

        val outcome = decision.decide(
            systemContext = "判断用户这句话里有没有值得长期记住的信息。",
            userText = userMessage,
            choices = Decisions.MEMORY_CHOICES,
            fallback = Decisions.NONE,
        )
        Log.i(TAG, "decideMemory -> ${outcome.name} (${outcome.source}, ${outcome.latencyMs}ms)")

        if (outcome.source != DecisionEngine.Outcome.Source.MODEL) return null
        if (outcome.name != Decisions.REMEMBER) return emptyList()

        val text = decision.arg(outcome, "text", "content", "value")?.trim().orEmpty()
        if (text.length < 4) return emptyList()

        val candidate = CompanionState.Memory(
            id = TextUtils.randomId("mem"),
            text = text.take(200),
            kind = kindOf(decision.arg(outcome, "kind", "type")),
            importance = (decision.floatArg(outcome, "importance", "score") ?: 0.6f)
                .let { if (it > 1f) it / 100f else it }
                .coerceIn(0.1f, 1f),
            keywords = keywordsOf(text).toList(),
        )
        if (!plausibleUserFact(candidate, userMessage, "")) {
            Log.i(TAG, "decideMemory: rejected ungrounded '${candidate.text}'")
            return emptyList()
        }
        return listOf(candidate)
    }

    /**
     * Last line of defence against memory poisoning.
     *
     * Keyword overlap alone is NOT enough — a real failure from testing was the model
     * answering "我住在北京，养了猫叫团子" to a question, where "团子" overlapped with
     * the user's real message and let the invented "住在北京" through. So every
     * *asserted property* (place, name, age, pet, preference) must be present in what
     * the user actually wrote; otherwise the candidate is rejected outright.
     */
    private fun plausibleUserFact(
        memory: CompanionState.Memory,
        userMessage: String,
        known: String,
    ): Boolean {
        val userKeys = keywordsOf(userMessage)
        val memKeys = memory.keywords.filter { it.length >= 2 }
        if (memKeys.isEmpty()) return false
        if (memKeys.none { it in userKeys }) return false
        if (known.contains(memory.text.take(12))) return false
        if (memory.text.startsWith("你") || memory.text.startsWith("我建议")) return false

        // Every "住在X / 叫X / X岁 / 养了X / 喜欢X" assertion must be grounded.
        ASSERTION_PATTERNS.forEach { re ->
            re.findAll(memory.text).forEach { m ->
                val value = m.groupValues.getOrNull(1).orEmpty().trim()
                if (value.length >= 2 && !appearsIn(value, userMessage)) {
                    Log.i(TAG, "extract: rejected ungrounded assertion '$value' in '${memory.text}'")
                    return false
                }
            }
        }
        return true
    }

    /** True when [value] (or its distinctive head) actually occurs in the user's text. */
    private fun appearsIn(value: String, userMessage: String): Boolean {
        if (userMessage.contains(value)) return true
        // tolerate inflection: compare the last 2 chars ("北京" in "北京市")
        if (value.length >= 2 && userMessage.contains(value.take(2))) return true
        val keys = keywordsOf(value)
        val userKeys = keywordsOf(userMessage)
        return keys.isNotEmpty() && keys.any { it in userKeys }
    }

    /**
     * High-precision patterns for facts that must survive a weak extraction model.
     *
     * Only fires on explicit, first-person statements: it prefers missing a memory to
     * inventing one.
     */
    internal fun ruleBasedMemories(
        userMessage: String,
        existing: List<CompanionState.Memory>,
    ): List<CompanionState.Memory> {
        val text = userMessage.replace('\n', ' ').trim()
        if (text.length < 6) return emptyList()
        val out = mutableListOf<CompanionState.Memory>()

        fun add(kind: CompanionState.Kind, snippet: String, importance: Float) {
            val clean = snippet.trim().trim('，', ',', '。', '.', '；', ';', '！', '!', ' ')
            if (clean.length < 3 || clean.length > 160) return
            if (existing.any { it.text.contains(clean.take(10)) }) return
            if (out.any { it.text.contains(clean.take(10)) }) return
            out += CompanionState.Memory(
                id = TextUtils.randomId("mem"),
                text = clean,
                kind = kind,
                importance = importance,
                keywords = keywordsOf(clean).toList(),
            )
        }

        ALLERGY_PATTERNS.forEach { re -> re.findAll(text).forEach { add(CompanionState.Kind.FACT, it.value, 0.95f) } }
        PET_PATTERNS.forEach { re -> re.findAll(text).forEach { add(CompanionState.Kind.FACT, it.value, 0.78f) } }
        NAME_PATTERNS.forEach { re -> re.findAll(text).forEach { add(CompanionState.Kind.FACT, it.value, 0.85f) } }
        LIFE_PATTERNS.forEach { re -> re.findAll(text).forEach { add(CompanionState.Kind.FACT, it.value, 0.7f) } }
        PREFERENCE_PATTERNS.forEach { re -> re.findAll(text).forEach { add(CompanionState.Kind.PREFERENCE, it.value, 0.55f) } }
        DATE_PATTERNS.forEach { re -> re.findAll(text).forEach { add(CompanionState.Kind.EVENT, it.value, 0.6f) } }

        return out.take(6)
    }

    /**
     * Parses the `类型|内容|重要度` line protocol, and is lenient about everything:
     * models add numbering, bullets, extra pipes, Chinese type names, or wrap the
     * whole thing in prose. Any line with a usable text fragment is kept — a slightly
     * sloppy memory beats silently forgetting the turn.
     */
    internal fun parseMemories(raw: String): List<CompanionState.Memory> {
        if (raw.isBlank()) return emptyList()
        if (raw.trim().uppercase().startsWith("NONE")) return emptyList()

        val out = mutableListOf<CompanionState.Memory>()

        raw.lines().forEach { lineRaw ->
            var line = lineRaw.trim()
            if (line.isEmpty() || line.uppercase() == "NONE") return@forEach

            // strip bullets, numbering and markdown emphasis
            line = line
                .removePrefix("-").removePrefix("*").removePrefix("•")
                .trimStart { it.isDigit() || it == '.' || it == '、' || it == ')' || it == '）' }
                .replace("**", "")
                .trim()
            if (line.isEmpty()) return@forEach

            // JSON objects still accepted, since larger models like to emit them
            if (line.startsWith("{")) {
                runCatching {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val text = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (text.length >= 4) {
                        out += CompanionState.Memory(
                            id = TextUtils.randomId("mem"),
                            text = text.take(200),
                            kind = kindOf(obj["kind"]?.jsonPrimitive?.content),
                            importance = obj["importance"]?.jsonPrimitive?.content
                                ?.toFloatOrNull()?.coerceIn(0.1f, 1f) ?: 0.5f,
                            keywords = keywordsOf(text).toList(),
                        )
                    }
                }
                return@forEach
            }

            val parts = line.split('|', '｜')
            if (parts.size < 2) {
                // No separator: keep a reasonable-length sentence as a plain fact.
                if (line.length in 8..180) {
                    out += CompanionState.Memory(
                        id = TextUtils.randomId("mem"),
                        text = line.take(200),
                        kind = CompanionState.Kind.FACT,
                        importance = 0.45f,
                        keywords = keywordsOf(line).toList(),
                    )
                }
                return@forEach
            }

            val kind = kindOf(parts[0])
            val text = parts[1].trim()
            if (text.length < 4) return@forEach

            // importance may be absent, in a later field, or written as a percentage
            val importance = parts.drop(2)
                .firstNotNullOfOrNull { p ->
                    Regex("([0-9]*\\.?[0-9]+)").find(p)?.groupValues?.get(1)?.toFloatOrNull()
                }
                ?.let { if (it > 1f) it / 100f else it }
                ?.coerceIn(0.1f, 1f)
                ?: 0.5f

            if (importance < 0.15f) return@forEach

            out += CompanionState.Memory(
                id = TextUtils.randomId("mem"),
                text = text.take(200),
                kind = kind,
                importance = importance,
                keywords = keywordsOf(text).toList(),
            )
        }

        return out.distinctBy { it.text.lowercase().take(40) }.take(8)
    }

    private fun kindOf(label: String?): CompanionState.Kind =
        when (label?.trim()?.lowercase()) {
            "fact", "事实" -> CompanionState.Kind.FACT
            "preference", "pref", "喜好", "偏好" -> CompanionState.Kind.PREFERENCE
            "event", "事件" -> CompanionState.Kind.EVENT
            "commitment", "约定", "承诺" -> CompanionState.Kind.COMMITMENT
            "feeling", "情绪", "感受" -> CompanionState.Kind.FEELING
            else -> CompanionState.Kind.FACT
        }

    /** Occasional rewrite of "how we are doing", used to keep long histories coherent. */
    private suspend fun summarise(
        state: CompanionState,
        humanMind: Boolean,
        lane: LlamaEngine.Lane = LlamaEngine.Lane.BACKGROUND,
    ): String? {
        if (!engine.state.value.canChat || engine.state.value.generating) return null
        val recent = state.memories.takeLast(18).joinToString("\n") { "- ${it.text}" }
        if (recent.isBlank()) return null

        val settings = GenerationSettings(
            temperature = 0.5f,
            maxTokens = 120,
            systemPrompt = "你是一个简洁的总结器，只输出一到两句话。",
        )
        return engine.chat(
            messages = listOf(
                Role.SYSTEM to settings.systemPrompt,
                Role.USER to CompanionPrompt.SUMMARY_INSTRUCTION + "\n\n已知信息：\n" + recent,
            ),
            settings = settings,
            notifyTurnComplete = false,
            lane = lane,
        ).getOrNull()?.trim()?.take(300)?.takeIf { it.isNotBlank() }
    }

    /** Keeps the most valuable memories when the cap is hit. */
    private fun prune(memories: MutableList<CompanionState.Memory>): MutableList<CompanionState.Memory> {
        if (memories.size <= tuning.maxMemories) return memories
        val keep = memories
            .sortedWith(
                compareByDescending<CompanionState.Memory> { it.importance }
                    .thenByDescending { it.createdAt }
            )
            .take(tuning.maxMemories)
            .sortedBy { it.createdAt }
        return keep.toMutableList()
    }

    // -------------------------------------------------------------- text utils

    private fun keywordsOf(text: String): Set<String> {
        val out = mutableSetOf<String>()
        // latin words
        Regex("[A-Za-z]{3,}").findAll(text).forEach { out += it.value.lowercase() }
        // CJK bigrams — cheap stand-in for segmentation
        val cjk = text.filter { it.code in 0x4E00..0x9FFF }
        for (i in 0 until (cjk.length - 1)) out += cjk.substring(i, i + 2)
        return out
    }

    /** Bilingual sentiment in [-1, 1]. Lexicon-based on purpose: free and instant. */
    internal fun sentimentOf(text: String): Float {
        val t = text.lowercase()
        var score = 0f
        POSITIVE.forEach { if (t.contains(it)) score += 0.22f }
        NEGATIVE.forEach { if (t.contains(it)) score += -0.26f }
        if (text.contains('！') || text.contains('!')) score += 0.10f
        if (text.contains("哈哈")) score += 0.15f
        return score.coerceIn(-1f, 1f)
    }

    /** Hours since this persona last talked to the user. */
    fun hoursSinceLastTalk(state: CompanionState): Long =
        ((System.currentTimeMillis() - state.lastTalkedAt) / TimeUnit.HOURS.toMillis(1)).coerceAtLeast(0)

    private companion object {
        /**
         * Assertions whose value must be traceable to the user's own words.
         * Group 1 is the value that has to appear in the original message.
         */
        val ASSERTION_PATTERNS = listOf(
            Regex("住在([\\u4e00-\\u9fa5A-Za-z]{2,10})"),
            Regex("来自([\\u4e00-\\u9fa5A-Za-z]{2,10})"),
            Regex("(?:名字叫|叫|名叫)([\\u4e00-\\u9fa5A-Za-z0-9]{1,12})"),
            Regex("([0-9一二三四五六七八九十]{1,3})岁"),
            Regex("养了([\\u4e00-\\u9fa5]{1,8})"),
            Regex("(?:喜欢|爱|讨厌)([\\u4e00-\\u9fa5A-Za-z]{2,12})"),
            Regex("对([\\u4e00-\\u9fa5A-Za-z]{1,8})过敏"),
            Regex("(?:是|做)([\\u4e00-\\u9fa5]{2,10})(?:工程师|老师|医生|学生|设计师|程序员)"),
        )

        /**
         * Rules that must work even when the resident model is a 0.5B. Each pattern
         * anchors on an explicit first-person statement so false positives stay rare.
         */
        val ALLERGY_PATTERNS = listOf(
            Regex("我[^，。；！？]{0,12}(过敏|忌口|不能吃|吃不了)[^，。；！？]{0,20}"),
            Regex("(对|对)([\\u4e00-\\u9fa5A-Za-z]{1,8})过敏"),
            Regex("(allergic to|allergy to)\\s+[^.,;!?]{1,30}", RegexOption.IGNORE_CASE),
        )

        val PET_PATTERNS = listOf(
            Regex("我(家|养的|有一只|有只)[^，。；！？]{0,6}(猫|狗|仓鼠|兔|鸟|龟|鱼)[^，。；！？]{0,16}"),
            Regex("我(的)?(猫|狗|宠物)[叫名是]{0,2}[^，。；！？]{1,10}"),
            Regex("(my|our)\\s+(cat|dog|pet)\\s+(is\\s+)?(called|named)\\s+\\w+", RegexOption.IGNORE_CASE),
        )

        val NAME_PATTERNS = listOf(
            Regex("我(叫|的名字是|名字叫)[^，。；！？]{1,12}"),
            Regex("(叫我|喊我)[^，。；！？]{1,12}"),
            Regex("(my name is|call me)\\s+[^.,;!?]{1,20}", RegexOption.IGNORE_CASE),
        )

        val LIFE_PATTERNS = listOf(
            // "我住在杭州" / "我住杭州" / "我在杭州工作" — all three are common
            Regex("我住(?:在)?([\\u4e00-\\u9fa5]{2,8})"),
            Regex("我在([\\u4e00-\\u9fa5]{2,8})(?:工作|上班|上学|读书|生活)"),
            Regex("我(?:是|做)([\\u4e00-\\u9fa5]{1,10})(?:工程师|老师|医生|学生|设计师|程序员|律师|会计|护士)"),
            Regex("我(?:在|就读于)([\\u4e00-\\u9fa5]{2,16})(?:大学|学院|公司|医院|学校)"),
            Regex("我今年([0-9一二三四五六七八九十]{1,3})岁"),
        )

        val PREFERENCE_PATTERNS = listOf(
            Regex("我(不)?(喜欢|爱|讨厌|受不了|最爱)[^，。；！？]{1,18}"),
            Regex("我(不)?吃[^，。；！？]{1,12}"),
            Regex("我(习惯|通常|一般)[^，。；！？]{1,16}"),
        )

        val DATE_PATTERNS = listOf(
            Regex("[0-9]{1,2}月[0-9]{1,2}[日号][^，。；！？]{0,10}(生日|考试|面试|结婚|纪念日)?"),
            Regex("下(个)?(月|周|星期)[^，。；！？]{1,14}(考试|面试|出差|旅行|搬家|结婚)"),
        )

        val PERSONAL_MARKERS = listOf(
            "我", "我的", "我妈", "我爸", "我女朋友", "我男朋友", "我老婆", "我老公",
            "工作", "老板", "同事", "考试", "生病", "难过", "开心", "生日",
            "i ", "my ", "i'm", "i am", "me ",
        )
        val POSITIVE = listOf(
            "谢谢", "喜欢", "爱你", "开心", "高兴", "太好了", "哈哈", "棒", "厉害", "抱抱",
            "thank", "love", "great", "happy", "nice", "awesome", "good",
        )
        val NEGATIVE = listOf(
            "烦", "累", "难过", "生气", "讨厌", "滚", "失望", "崩", "哭", "焦虑", "压力",
            "sad", "angry", "tired", "hate", "annoy", "depress", "stress", "sorry",
        )
    }
}

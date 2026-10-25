package com.localmind.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 流水线插件（能力 id = `pipeline`，内容文件 = `pipeline.json`）。
 *
 * ## 这个能力解决什么问题
 *
 * 有些模型**装不进一台手机**：Breeze TTS 2 是 3.47B / 需要 CUDA；
 * 7B 级对话模型在 8 GB 机器上没有上下文可用。常见建议是"换小模型"，
 * 但还有另一条路 —— **把一件事拆成能单独跑的小步，逐步执行，最后聚合**：
 *
 *  1. 长文本拆成句子级分片，每片单独合成（每片的内存占用与文本长度无关）；
 *  2. 长文拆成块，每块单独让模型处理（上下文只装一块）；
 *  3. 各步的产物（音频 / 文本）在最后一步合并。
 *
 * 这样"跑不动"就变成"慢一点，但跑得完"，而且每一步都能看到进度、能中断、
 * 能单独重试 —— 一次性喂整篇的那种失败是没法重试的。
 *
 * ## 为什么是声明式
 *
 * 和 [PluginManifest] 一样的理由：插件是**数据**，不是代码。
 * [PipelineStages] 里列出主程序会执行的全部动作类型，插件只能组合它们。
 * 代价是自由度有上限，换来的是"装个插件不会把应用搞崩"。
 */
@Serializable
data class PipelineStage(
    /** 稳定 id，同一个流水线里不能重复。 */
    val id: String,
    /** 动作类型，见 [PipelineStages.TYPES]。 */
    val type: String,
    /** 显示名。空 = 用类型的默认名。 */
    val label: String = "",
    /** llm：提示词模板，用 `{{chunk}}` 代表当前分片。 */
    val prompt: String = "",
    /** split_text：每片最多多少字。 */
    val maxChars: Int = 600,
    /** llm：每片最多生成多少 token。 */
    val maxTokens: Int = 256,
    /**
     * tts：用哪个引擎。`auto` = 用当前启用的那个；
     * 也可以写 `插件id/引擎id` 指定。
     */
    val engine: String = "auto",
    /** tts：演技/音色描述（Breeze 的 instruction）。 */
    val instruction: String = "",
    /** concat_audio：每段之间插入多少毫秒静音。 */
    val silenceMs: Int = 250,
)

/** 一条流水线。 */
@Serializable
data class Pipeline(
    val id: String,
    val name: String,
    val description: String = "",
    /** 输入框的提示（告诉用户该粘什么进来）。 */
    val inputHint: String = "把文本粘进来",
    /** 输入框要几行：长文类给大一点。 */
    val inputLines: Int = 5,
    val stages: List<PipelineStage> = emptyList(),
) {
    val isValid: Boolean get() = id.isNotBlank() && name.isNotBlank() && stages.isNotEmpty()
}

/** 一个流水线包。 */
@Serializable
data class PipelinePackFile(
    val version: Int = 1,
    val packId: String = "",
    val packName: String = "",
    val author: String = "",
    val description: String = "",
    val pipelines: List<Pipeline> = emptyList(),
) {
    val isValid: Boolean get() = packId.isNotBlank() && packName.isNotBlank()
}

/**
 * 主程序会执行的全部动作类型。
 *
 * 加一种动作 = 在这里加一行 + 在 `PipelineRunner` 里加一个分支。
 * 没实现的动作**不允许**出现在解析通过的插件里（装的时候就报错），
 * 而不是运行到一半才发现"这一步不会做"。
 */
object PipelineStages {

    /** 打印本机可行性（内存、模型大小、能不能本机跑）。不改数据。 */
    const val PROBE = "probe"

    /** 按句末标点 / 长度上限把文本拆成片。 */
    const val SPLIT_TEXT = "split_text"

    /** 每片调一次本机对话模型。 */
    const val LLM = "llm"

    /** 把各片结果按顺序拼成一段文本。 */
    const val JOIN_TEXT = "join_text"

    /** 每片调一次语音引擎（Breeze / OpenAI 兼容），产出 wav。 */
    const val TTS = "tts"

    /** 把多段 wav 按顺序拼接，段间插静音。 */
    const val CONCAT_AUDIO = "concat_audio"

    val TYPES: Set<String> = setOf(PROBE, SPLIT_TEXT, LLM, JOIN_TEXT, TTS, CONCAT_AUDIO)

    val LABELS: Map<String, String> = mapOf(
        PROBE to "本机体检",
        SPLIT_TEXT to "拆片",
        LLM to "逐片生成",
        JOIN_TEXT to "拼文本",
        TTS to "逐片合成",
        CONCAT_AUDIO to "拼音频",
    )
}

/** `pipeline.json` 的解析与校验。 */
object PipelinePacks {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    sealed interface ParseResult {
        data class Ok(val pack: PipelinePackFile) : ParseResult
        data class Failed(val reason: String) : ParseResult
    }

    fun parse(text: String): ParseResult {
        if (text.isBlank()) return ParseResult.Failed("pipeline.json 是空的")
        val pack = runCatching { json.decodeFromString<PipelinePackFile>(text) }.getOrElse { t ->
            return ParseResult.Failed("pipeline.json 格式错误：${t.message?.take(140) ?: "解析失败"}")
        }
        if (pack.packId.isBlank()) return ParseResult.Failed("pipeline.json 缺少 packId")
        if (pack.packName.isBlank()) return ParseResult.Failed("pipeline.json 缺少 packName")
        if (pack.pipelines.isEmpty()) return ParseResult.Failed("pipeline.json 里没有 pipelines")

        val dupPipe = pack.pipelines.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dupPipe.isNotEmpty()) return ParseResult.Failed("流水线 id 重复：${dupPipe.joinToString()}")

        for (p in pack.pipelines) {
            if (!p.isValid) return ParseResult.Failed("流水线 ${p.id} 缺 name 或 stages")
            val dupStage = p.stages.groupBy { it.id }.filterValues { it.size > 1 }.keys
            if (dupStage.isNotEmpty()) {
                return ParseResult.Failed("流水线 ${p.name} 里有重复的步骤 id：${dupStage.joinToString()}")
            }
            for (s in p.stages) {
                if (s.type !in PipelineStages.TYPES) {
                    return ParseResult.Failed(
                        "流水线 ${p.name} 的步骤 ${s.id} 用了不认识的动作：${s.type}。" +
                            "只能是 ${PipelineStages.TYPES.joinToString("、")}"
                    )
                }
                val needsPrompt = s.type == PipelineStages.LLM
                if (needsPrompt && s.prompt.isBlank()) {
                    return ParseResult.Failed("流水线 ${p.name} 的 llm 步骤 ${s.id} 没写 prompt")
                }
                if (s.type == PipelineStages.LLM && !s.prompt.contains("{{chunk}}")) {
                    return ParseResult.Failed(
                        "流水线 ${p.name} 的 llm 步骤 ${s.id} 的 prompt 里必须出现 {{chunk}}，" +
                            "否则每一片都会得到同一句回答"
                    )
                }
                if (s.maxChars !in 20..4000) {
                    return ParseResult.Failed("流水线 ${p.name} 的 split_text 的 maxChars 应在 20~4000")
                }
                if (s.silenceMs !in 0..2000) {
                    return ParseResult.Failed("流水线 ${p.name} 的 concat_audio 的 silenceMs 应在 0~2000")
                }
            }
            // 至少要有一步产出东西，否则跑完什么都没有。
            val produces = p.stages.any {
                it.type == PipelineStages.LLM || it.type == PipelineStages.TTS ||
                    it.type == PipelineStages.JOIN_TEXT || it.type == PipelineStages.CONCAT_AUDIO
            }
            if (!produces) {
                return ParseResult.Failed("流水线 ${p.name} 没有任何产出步骤（llm / tts / join_text / concat_audio）")
            }
        }
        return ParseResult.Ok(pack)
    }

    fun encode(pack: PipelinePackFile): String = json.encodeToString(PipelinePackFile.serializer(), pack)

    /** 这条流水线要跑几轮模型/语音，用来给用户一个"要等多久"的量级。 */
    fun roundCount(pipeline: Pipeline): Int =
        pipeline.stages.count { it.type == PipelineStages.LLM || it.type == PipelineStages.TTS }
}

package com.localmind.data

import kotlinx.serialization.Serializable

/**
 * 这个对话里 AI 能用哪些能力。
 *
 * 和 [CapabilitySettings] 是**两层不同**的东西，别混：
 *
 *  - [CapabilitySettings] 是**设备级权限** —— 能不能联网、能不能跑 shell。
 *    它是安全边界，默认全关。
 *  - [AiAbilities] 是**这个对话的表达方式** —— 要不要说话、要不要发图。
 *    它是偏好，不涉及安全，所以可以放心让用户随便开。
 *
 * 默认值刻意全是"只要文字"：用户说了要普通 AI 就得到普通 AI，
 * 不该因为装了个生图模型就突然开始往对话里塞图片。
 */
@Serializable
data class AiAbilities(
    /** 文字回复。唯一的"基础能力"，关掉它 AI 就没法交流了 —— UI 应禁止关闭。 */
    val text: Boolean = true,

    /** 语音：AI 的回复用 TTS 念出来（也允许它主动发一段语音）。 */
    val voice: Boolean = false,

    /** 生图：AI 可以画图并发给你（需要生图模型）。 */
    val imageGeneration: Boolean = false,

    /** 识图：你发的图片 AI 能看（需要多模态模型 + mmproj）。 */
    val imageRecognition: Boolean = false,

    /** 发表情包：让文字聊天不那么干巴。 */
    val emoji: Boolean = false,

    /** 主动发起话题，而不是只在被问到时才说话。 */
    val proactive: Boolean = false,

    // ---- 设备感知（人设聊天专属，默认全关）----
    //
    // 这三个不是"表达能力"，是"感知能力"：它知道你的手机还有多少电、你在哪、
    // 你那边什么天气。人机恋里这些是**关心**的由头（"这么晚还在外面？"、
    // "你那边降温了，记得加衣服"），没有它们，关心就只能是空话。
    //
    // 默认全关，而且是**按需读**：只有当你这句话聊到相关内容时才去读一次
    //（见 DeviceSense.needs），不是每回合都读。开关在对话设置里，一个对话一份。

    /** 读电量：知道你还有多少电、在不在充电。 */
    val battery: Boolean = false,

    /** 知道你在哪（城市/区一级）。需要定位权限。 */
    val location: Boolean = false,

    /** 看你那边的天气。用设备定位 + 天气服务，需要定位权限 + 网络。 */
    val weather: Boolean = false,
) {
    /** 纯文字：一个"普通 AI"的全部。 */
    val isPlain: Boolean
        get() = !voice && !imageGeneration && !imageRecognition && !emoji && !proactive &&
            !battery && !location && !weather

    /** 打开所有能打开的能力，用于"我全都要"的一键设置。 */
    fun allOn(): AiAbilities = copy(
        text = true,
        voice = true,
        imageGeneration = true,
        imageRecognition = true,
        emoji = true,
        proactive = true,
        // 设备感知**不**跟着「全部打开」走。
        //
        // 那是个一键按钮，点它的人想的是"表达方式全开"（语音/图片/表情），
        // 而不是"把我的位置也交出去"。隐私类的开关必须一个一个点。
    )

    companion object {
        val PLAIN = AiAbilities()

        /** 普通助手：会看你会发的图，但不会主动说话、不发表情。 */
        val ASSISTANT = AiAbilities(imageRecognition = true)

        /** 像人一样聊天：语音、表情、会主动起话题，但不擅自画图。 */
        val COMPANION = AiAbilities(
            voice = true,
            imageRecognition = true,
            emoji = true,
            proactive = true,
        )
    }
}

/**
 * 用户可调的大模型参数。
 *
 * 从 [GenerationSettings] 里拆出来，是因为这两个东西的受众不同：
 * 前者是"我想让它怎么说话"（用户愿意调），后者含 `mmap`、`contextSize`
 * 这类"设备相关"的开关（用户不该碰，调错会崩）。
 *
 * 所有字段可空 = 跟随全局默认，这样单个对话只覆盖它在意的那一两个。
 */
@Serializable
data class SamplingOverrides(
    /** 越高越天马行空，越低越稳定保守。 */
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val minP: Float? = null,
    /** 重复惩罚：太高会让模型"不敢重复用词"，中文里容易变得别扭。 */
    val repeatPenalty: Float? = null,
    val maxTokens: Int? = null,
    val seed: Int? = null,
) {
    val isDefault: Boolean
        get() = temperature == null && topP == null && topK == null && minP == null &&
            repeatPenalty == null && maxTokens == null && seed == null

    /** 把这组覆盖应用到全局设置上，null 字段保持全局值。 */
    fun applyTo(base: GenerationSettings): GenerationSettings = base.copy(
        temperature = temperature ?: base.temperature,
        topP = topP ?: base.topP,
        topK = topK ?: base.topK,
        minP = minP ?: base.minP,
        repeatPenalty = repeatPenalty ?: base.repeatPenalty,
        maxTokens = maxTokens ?: base.maxTokens,
        seed = seed ?: base.seed,
    )
}

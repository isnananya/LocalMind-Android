package com.localmind.data

/**
 * 内置音色：**不装任何插件也能用的 4 个** sherpa-onnx 离线音色。
 *
 * ## 为什么又回到"内置"
 *
 * 上一轮把所有音色都推给了语音插件（见 [VoiceCatalog] 的历史说明），好处是换音色
 * 不用发版。但代价是**首装之后语音页是空的** —— 用户要先去插件页装官方模型库，
 * 才能看到第一个音色。对"离线语音"这种开箱即用的能力来说，这个门槛没有道理：
 * 音色清单会变，但"至少有几个能用的"不该变。
 *
 * 所以现在是一个**两层清单**：
 *  * 这一层：随 APK 带来的 4 个，保证空装机也能下载、能试听；
 *  * 插件那一层：可以加新音色、改地址、换镜像；同 id 时**插件优先**
 *    （见 [VoiceCatalog.setFromPlugins]），所以下架/改地址不需要再发版。
 *
 * ## 为什么是这 4 个
 *
 * 主程序已接 **VITS / Kokoro / Kitten / Matcha** 四种引擎（见
 * `NeuralVoiceModels.Layout.arch`），所以 Kokoro 这类更自然的架构也能直接用。
 * 第一推荐是 Kokoro 多语言：140 MB 的体积、明显更自然的效果。
 *
 * 体积从小到大排，第一项就是低端机的默认答案：
 *
 * | id | 体积 | 定位 |
 * |---|---|---|
 * | vits-piper-zh_CN-chaowen-medium-int8 | 13 MB | 低端机首选，合成最快 |
 * | vits-piper-zh_CN-huayan-medium | 64 MB | Piper 中文女声，断句自然 |
 * | vits-zh-hf-fanchen-unity | 114 MB | 社区模型，音色更接近真人 |
 * | sherpa-onnx-vits-zh-ll | 113 MB | 带中文 lexicon，数字/多音字更准 |
 *
 * id 就是官方 Release 里 tarball 的名字（不带扩展名），下载器按约定拼 URL，
 * 和插件里的音色完全同一条链路。
 */
object BuiltinVoices {

    val all: List<VoiceEntry> = listOf(
        // Kokoro int8 多语言：目前清单里最自然的音色，中英日韩都能念。
        // 140 MB 与 VITS 大模型同量级，效果却明显更好 —— 所以排第一推荐。
        VoiceEntry(
            id = "kokoro-int8-multi-lang-v1_1",
            label = "Kokoro 星野（多语言 · 推荐）",
            badge = "140 MB",
            sizeBytes = 147_052_523,
            description = "140 MB · 完全离线 · Apache-2.0（Kokoro）。中/英/日/韩多语言，" +
                "目前清单里最自然的音色，断句和语气都接近真人。",
            sampleText = "你好，我是星野。用自然的声音，陪你把每一天过得更从容。",
        ),
        VoiceEntry(
            id = "vits-piper-zh_CN-chaowen-medium-int8",
            label = "超文（男声 · 轻量）",
            badge = "13 MB",
            sizeBytes = 14_011_298,
            description = "13 MB · 完全离线 · Apache-2.0（Piper）。低端机首选：几秒下完，合成也是这批里最快的。",
            sampleText = "你好，我是超文。有什么可以帮你的吗？",
        ),
        VoiceEntry(
            id = "vits-piper-zh_CN-huayan-medium",
            label = "华研（女声 · 普通话）",
            badge = "64 MB",
            sizeBytes = 67_255_926,
            description = "64 MB · 完全离线 · Apache-2.0（Piper）。中文女声，断句自然，64 MB 在手机上不算大。",
            sampleText = "你好呀，我是华研。今天想聊点什么？",
        ),
        VoiceEntry(
            id = "vits-zh-hf-fanchen-unity",
            label = "泛尘（女声 · 多说话人）",
            badge = "114 MB",
            sizeBytes = 119_143_475,
            description = "114 MB · 完全离线 · 社区模型（个人/非商用）。音色更接近真人，适合当人设的默认声音。",
            sampleText = "你好，我是运行在你手机上的语音模型。",
        ),
        VoiceEntry(
            id = "sherpa-onnx-vits-zh-ll",
            label = "LL-VITS（中文 · 带词典）",
            badge = "113 MB",
            sizeBytes = 118_810_709,
            description = "113 MB · 完全离线 · Apache-2.0。带中文 lexicon：数字、日期、多音字读得更准，适合长句朗读。",
            sampleText = "数字 2026 年 10 月 15 日，多音字「行」在这里读 háng。",
        ),
    )

    fun byId(id: String?): VoiceEntry? =
        id?.takeIf { it.isNotBlank() }?.let { key -> all.firstOrNull { it.id == key } }
}

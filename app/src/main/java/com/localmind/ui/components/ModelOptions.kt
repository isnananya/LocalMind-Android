package com.localmind.ui.components

import com.localmind.data.LocalModel
import com.localmind.data.RemoteProvider

/**
 * 「哪些模型能胜任这份工作」——**只做筛选，不做界面**。
 *
 * 这一条判据以前住在 ModelRoutingSection（一个全局的"模型分工"面板）里。
 * 那一版已经撤掉：模型现在是**按对话**选的（见 ConversationModelRow /
 * AiChatSettingsScreen / PersonaChatSettingsScreen）。但这个函数必须只有一份 ——
 * 界面里另写一份判据，迟早会出现"对话槽位能选到扩散模型"这种静默失效。
 */

fun modelOptions(
    models: List<LocalModel>,
    providers: List<RemoteProvider>,
    visionOnly: Boolean = false,
    imageOnly: Boolean = false,
    decisionOnly: Boolean = false,
): List<PickerOption> {
    val candidates = models
        .filter {
            when {
                decisionOnly -> it.decisionModel
                visionOnly -> it.vision
                imageOnly -> it.isImageModel
                // ⚠️ 这里**故意不排除** `vision` 条目。
                //
                // 多模态模型（Qwen3.5 / Gemma 4 / InternVL…）是**一个 GGUF 两用**：
                // 它能看图，也能纯文字对话。以前把它从对话槽位里排掉，结果是
                // 用户下了一个 3 GB 的 Qwen3.5，却在「对话模型」里找不到它 ——
                // 只能再下一个纯文字模型。那是白花流量和存储。
                //
                // 仍然排除的：`decisionModel`（做判断不是聊天）、`isImageModel`
                //（扩散模型根本不产文字）、`embedding`（只有向量）、`voice`
                //（音色包不是语言模型）。
                else -> !it.decisionModel && !it.isImageModel && !it.embedding && !it.isVoiceModel
            }
        }
        // **没下载的一律不显示**（用户明确要求）。
        //
        // 理由不只是「列表短一点」：选一个没下载的模型，程序只能启动下载、把这次
        // 选择丢掉（见 ConversationModelRow 里那段），于是用户看到的真实结果是
        // 「我选了它，可是没生效」。下载是「模型」页的事；选择器只列**现在就能用**的。
        .filter { it.isDownloaded }
        // 同组内按体积从小到大（小模型先跑起来）。
        .sortedBy { it.sizeBytes }
        .map {
            PickerOption(
                id = it.id,
                title = it.name,
                subtitle = buildString {
                    append(com.localmind.util.Formatters.size(it.sizeBytes))
                    if (it.quant.isNotBlank()) append(" · ").append(it.quant)
                    // 让用户在对话槽位里也能看出"这条还能看图" —— 否则他会
                    // 以为选了一个只有一半能力的模型。
                    if (it.vision) append(" · 也能看图")
                    if (it.decisionModel) append(" · 专做判断")
                },
                badge = when {
                    it.decisionModel -> "决策"
                    it.vision -> "识图"
                    it.isImageModel -> "生图"
                    else -> null
                },
                enabled = true,
            )
        }

    val remote = providers.filter { it.enabled }.map {
        PickerOption(
            id = it.id,
            title = it.name,
            subtitle = "${it.model} · ${it.baseUrl}",
            badge = "API",
        )
    }
    return remote + candidates
}


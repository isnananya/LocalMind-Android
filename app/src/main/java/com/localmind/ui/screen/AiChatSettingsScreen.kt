package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.localmind.data.Conversation
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosSection
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

/**
 * **AI 对话**的设置页。
 *
 * 这一页的主题只有一句：**它不是一个"人"，它是一个模型。**
 * 所以这里没有任何拟人项 —— 没有人设、没有名字头像、没有情绪、没有记忆、
 * 没有"主动找你说话"、没有表情包。用户在新建时选了「AI 对话」，他要的就是
 * "问事"，把这些东西摆给他看只会让他以为这对话坏了一半。
 *
 * 反过来，这里的东西都是**工具性**的：用哪个模型、能不能发图、回答多长。
 */
@Composable
fun AiChatSettingsScreen(
    viewModel: AppViewModel,
    conversation: Conversation,
    onBack: () -> Unit,
) {
    val ios = IosTheme.palette
    val models by viewModel.models.collectAsState()
    val providers by viewModel.providers.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val voiceOk = viewModel.voiceEngineReady
    val hasImageModel = models.any { it.isDownloaded && it.isImageModel }
    val hasVisionModel = models.any { it.isDownloaded && it.isVisionModel }

    SettingsPage(title = "AI 对话设置", onBack = onBack) {

        // ------------------------------------------------------------ 类型
        IosSection(
            header = "这个对话",
            footer = "类型在创建时定好，之后不能改 —— 改类型要回答「已经聊过的内容怎么办」，" +
                "那不该由一个开关顺手决定。想要有名字、有记忆的聊天，新建时选「人设聊天」。",
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IosPill("AI 对话", ios.blue)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("无人设 · 不留记忆", style = IosTheme.typography.body, color = ios.label)
                    Text(
                        "直接把消息发给模型，不加人设、不召回记忆、不跑情绪 —— 这是最快的路径",
                        style = IosTheme.typography.caption1,
                        color = ios.secondaryLabel,
                    )
                }
            }
        }

        // ------------------------------------------------------------ 模型
        IosSection(
            header = "用哪个模型",
            footer = "选一个本地模型会「立刻把它加载起来」，下次发消息直接开跑；" +
                "选 API 服务就走网络。不指定就用内存里已经加载好的那个。",
        ) {
            ConversationModelRow(
                viewModel = viewModel,
                label = "对话模型",
                hint = if (engineState.ready) "当前已加载：${engineState.modelName ?: "—"}" else "当前还没有加载模型",
                currentId = conversation.chatModelId,
                models = models,
                providers = providers,
                allowCustomApi = true,
                conversationId = conversation.id,
            ) { id -> viewModel.selectConversationChatModel(conversation.id, id) }
        }

        // ------------------------------------------------------------ 交给 API
        //
        // 放在“能力”之前：这是这一页最**能立刻感觉到快**的一项。
        // 本机 1.5B 模型十几 tok/s，而云端小模型首 token 常常不到一秒；
        // 想快就换成 API，想离线/隐私就用本机 —— 两条路在这里一眼可选。
        ApiAccessSection(
            viewModel = viewModel,
            conversation = conversation,
            localModelName = engineState.modelName,
        )

        // ------------------------------------------------------------ 能力
        //
        // 这里**只有工具性能力**。表情包 / 主动找你说话 / 语音哄睡那类属于人设聊天，
        // 在人机恋那套语境里才有意义；摆在 AI 对话里是噪音。
        IosSection(
            header = "它可以做什么",
            footer = "默认只回文字。装了对应模型不等于它会自己开始发图发语音 —— 每一项都要你主动打开。",
        ) {
            AbilityRow(
                title = "文字回复",
                subtitle = "基础能力，始终开启",
                checked = true,
                locked = true,
            ) { }
            HorizontalDivider(color = ios.separator)
            AbilityRow(
                title = "发语音",
                subtitle = if (voiceOk) "回复用 TTS 念出来" else "还缺语音引擎 —— 去「我的 → TTS 测试」下载（约 36 MB）",
                checked = conversation.abilities.voice,
                ready = voiceOk,
            ) { on -> viewModel.updateConversationAbilities(conversation.id) { it.copy(voice = on) } }
            HorizontalDivider(color = ios.separator)
            AbilityRow(
                title = "发图片",
                subtitle = if (hasImageModel) "它可以画图并发给你" else "还没有生图模型 —— 去「模型 → 生图」下载一个",
                checked = conversation.abilities.imageGeneration,
                ready = hasImageModel,
            ) { on -> viewModel.updateConversationAbilities(conversation.id) { it.copy(imageGeneration = on) } }
            HorizontalDivider(color = ios.separator)
            AbilityRow(
                title = "看懂你发的图",
                subtitle = visionSubtitle(hasVisionModel, viewModel.canSeeImages),
                checked = conversation.abilities.imageRecognition,
                // ⚠️ 光下模型**不够**：还要它的 mmproj 下全、并且当前加载的就是它。
                // 只判 hasVisionModel 的话，用户会看到这一行"可用"，打开、发图、
                // 得到一句没看见图的回复 —— 那是我们在骗他。
                ready = hasVisionModel && viewModel.canSeeImages,
            ) { on -> viewModel.updateConversationAbilities(conversation.id) { it.copy(imageRecognition = on) } }
        }

        // ------------------------------------------------------------ 朗读
        ConversationVoiceSection(viewModel, conversation)

        // ------------------------------------------------------------ 提示词
        IosSection(
            header = "怎么回答",
            footer = "这一段只属于这个对话。留空就是一句内置的「直接、简洁」。" +
                "越短越快 —— 每个字都要在每次提问前重新读一遍。",
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                PromptField(
                    value = conversation.systemPrompt,
                    placeholder = "例如：只回答代码，不要解释。",
                ) { v ->
                    viewModel.updateConversationSettings(conversation.id) { it.copy(systemPrompt = v) }
                }
            }
        }

        SamplingSection(viewModel, conversation)

        // ------------------------------------------------------------ 清理
        IosSection(footer = "AI 对话本来就不存记忆，所以没有「清空记忆」这一项。") {
            DangerRow(
                title = "清空这个对话的消息",
                subtitle = "只删消息，保留这个对话本身和它的设置",
                icon = AppIcons.Delete,
            ) { viewModel.clearConversationMessages(conversation.id) }
            HorizontalDivider(color = ios.separator)
            DangerRow(
                title = "删除这个对话",
                subtitle = "连同设置一起删掉，不可恢复",
                icon = AppIcons.Delete,
            ) { viewModel.deleteConversation(conversation.id); onBack() }
        }
    }
}

/**
 * 多行提示词输入框。
 *
 * 不用「我的」页里那种单行输入框：系统提示词经常要写两三句，
 * 单行框里写起来会一直往右跑，看不到自己写了什么。
 */
@Composable
internal fun PromptField(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    val ios = IosTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ios.fillSecondary)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = IosTheme.typography.footnote, color = ios.tertiaryLabel)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = LocalTextStyle.current
                .merge(IosTheme.typography.footnote)
                .copy(color = ios.label),
            cursorBrush = SolidColor(ios.blue),
            modifier = Modifier
                .fillMaxWidth()
                .height(if (value.isEmpty()) 20.dp else 76.dp),
        )
    }
}

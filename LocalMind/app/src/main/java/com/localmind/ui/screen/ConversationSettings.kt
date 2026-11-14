package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.localmind.data.Conversation
import com.localmind.data.LocalModel
import com.localmind.data.RemoteProvider
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.components.ModelPickerSheet
import com.localmind.ui.components.PickerOption
import com.localmind.ui.components.modelOptions
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.icons.AppIconsAutoMirrored
import com.localmind.ui.theme.IosTheme

/**
 * 「本对话设置」的**分流口**。
 *
 * AI 对话和人设聊天是两种东西，它们的设置页**不是同一个页面的两个分支**：
 *
 *  * [AiChatSettingsScreen] —— 无人设、无记忆。只有"用哪个模型、能做什么、怎么回答"。
 *  * [PersonaChatSettingsScreen] —— 人机恋那套：它是谁、关系状态、记忆、情绪、经验。
 *
 * 分成两个文件而不是一个 if/else，是因为它们的**内容完全不重叠**：
 * 混在一起写，AI 对话那边就会莫名其妙地出现"拟人强度""它记得你什么"，
 * 而人设聊天那边又会看到一堆跟"它是不是人"无关的机器参数。用户的原话是
 * "对话单独设置的界面应该不同"。
 */
@Composable
fun ConversationDetailScreen(
    viewModel: AppViewModel,
    conversation: Conversation,
    onBack: () -> Unit,
) {
    if (conversation.companionMode) {
        PersonaChatSettingsScreen(viewModel, conversation, onBack)
    } else {
        AiChatSettingsScreen(viewModel, conversation, onBack)
    }
}

/** 子页统一的返回栏：`‹ 对话 / 标题`。 */
@Composable
internal fun SettingsNavBar(title: String, onBack: () -> Unit) {
    val ios = IosTheme.palette
    IosNavigationBar(
        title = title,
        collapsed = true,
        topInset = statusBarTopPadding(),
        leading = {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIconsAutoMirrored.ArrowBack,
                    contentDescription = "返回",
                    tint = ios.blue,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(2.dp))
                Text("对话", style = IosTheme.typography.body, color = ios.blue)
            }
        },
    )
}

/** 一整页的滚动容器，统一背景与内边距。 */
@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val ios = IosTheme.palette
    Column(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
    ) {
        SettingsNavBar(title, onBack)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            content()
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * 一行「这个对话用哪个模型」。
 *
 * 和 [ModelOverrideRow]（老的全局版）的区别只有一处，但很关键：
 * 选完之后会**真的把本地模型加载起来**（[AppViewModel.selectConversationChatModel]）。
 * 否则用户选完、发消息，回答的还是上一个模型 —— 这正是"多模型配合没写好"的症状。
 */
@Composable
internal fun ConversationModelRow(
    viewModel: AppViewModel,
    label: String,
    hint: String,
    currentId: String,
    models: List<LocalModel>,
    providers: List<RemoteProvider>,
    visionOnly: Boolean = false,
    imageOnly: Boolean = false,
    decisionOnly: Boolean = false,
    /**
     * 允许在选择器最下面「手动添加自定义 API 地址」。
     *
     * 只给**对话模型**这一行开：识图 / 生图 / 决策是四种不同能力，
     * 让用户在每一个槽位里都新建一次接口，只会多出四份看起来一样的接口。
     */
    allowCustomApi: Boolean = false,
    /** 传了就允许「新建接口后直接绑到这个对话」。 */
    conversationId: String? = null,
    defaultLabel: String = "不指定（用已加载的）",
    defaultHint: String = "不换模型，直接用内存里已经加载好的那个 —— 最快",
    onPick: (String) -> Unit,
) {
    val ios = IosTheme.palette
    var picking by remember { mutableStateOf(false) }
    var showCustomApi by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { picking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = IosTheme.typography.body, color = ios.label)
            if (hint.isNotBlank()) {
                Text(hint, style = IosTheme.typography.caption1, color = ios.secondaryLabel, maxLines = 2)
            }
        }
        Text(
            currentId.ifBlank { "未指定" },
            style = IosTheme.typography.caption1,
            color = if (currentId.isBlank()) ios.tertiaryLabel else ios.blue,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = ios.tertiaryLabel,
            modifier = Modifier.size(16.dp),
        )
    }

    if (picking) {
        ModelPickerSheet(
            title = label,
            subtitle = "只对这个对话生效",
            // allowDefault = false：`ModelPickerSheet` 自带的那条"跟随全局默认"在这里是错的 ——
            // 全局路由已经没有界面了，而且它和下面这条 id="" 的选项会**同时打上勾**。
            // 每个槽位自己给一条"不指定"，文案说明它到底是什么。
            allowDefault = false,
            options = listOf(PickerOption("", defaultLabel, defaultHint)) +
                modelOptions(models, providers, visionOnly = visionOnly, imageOnly = imageOnly, decisionOnly = decisionOnly),
            selectedId = currentId,
            extraOption = if (allowCustomApi) {
                PickerOption(
                    id = "__custom_api__",
                    title = "＋ 手动添加自定义 API 地址",
                    subtitle = "OpenAI 格式：填 Base URL / Key / 模型名，接入后直接绑到这个对话",
                )
            } else {
                null
            },
            onExtraOption = if (allowCustomApi) {
                {
                    picking = false
                    showCustomApi = true
                }
            } else {
                null
            },
            onSelect = { id ->
                val notDownloaded = models.firstOrNull { it.id == id && !it.isDownloaded }
                if (notDownloaded != null) {
                    // 未下载：**先下载，不写选择**。写进去只会得到一个静默失败，
                    // 而用户会以为"选了但没生效"。
                    viewModel.downloadAny(notDownloaded)
                    viewModel.toast("开始下载 ${notDownloaded.name}，下完就能在列表里选它了")
                } else {
                    onPick(id)
                }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }

    if (showCustomApi) {
        CustomApiDialog(
            viewModel = viewModel,
            conversationId = conversationId,
            onDismiss = { showCustomApi = false },
        )
    }
}

/**
 * 「看懂你发的图」这一行的说明文案。
 *
 * 三种状态分开说，因为三种情况的**处置完全不同**：
 *  * 没装多模态模型 → 去下一个；
 *  * 装了但代码还没接视觉投影（当前版本就是这样）→ **换模型没用**，等版本更新；
 *  * 都就绪 → 可以打开。
 *
 * 只写前两句里的一句（原来就是那样）会让用户在下完 Qwen3-VL 之后看到
 * "你发的图片它可以看"，然后发图得到一句没看见图的回复 —— 那是我们在骗他。
 */
internal fun visionSubtitle(hasVisionModel: Boolean, visionReady: Boolean): String = when {
    !AppViewModel.CAN_SEE_IMAGES ->
        "这个包没有编进识图能力（省体积的版本）"
    visionReady -> "你发的图片它可以看"
    !hasVisionModel ->
        "还没有多模态模型 —— 下载带 mmproj 的那种（下载时会一起拉下来）"
    else ->
        "这个模型现在看不见图：要么还没加载它，要么它的 mmproj（视觉投影）没下全"
}

/** 「它刚才在想什么」里的一行「标签 / 内容」。 */
@Composable
internal fun ThinkLine(label: String, value: String) {
    val ios = IosTheme.palette
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = IosTheme.typography.caption1,
            color = ios.tertiaryLabel,
            modifier = Modifier.width(112.dp),
        )
        Text(value, style = IosTheme.typography.footnote, color = ios.label, modifier = Modifier.weight(1f))
    }
}

/**
 * 每个对话的朗读设置。
 *
 * 三样东西，都只影响这一个对话：
 *  * 开不开朗读（[com.localmind.data.Conversation.ttsEnabled]）；
 *  * 音色提示词（[com.localmind.data.Conversation.voicePrompt]），留空跟随全局；
 *  * 用哪个神经音色（[com.localmind.data.Conversation.voiceId]），留空跟随全局。
 *
 * 为什么不放在「语音」页：那里的东西是"这台手机怎么发声"，这里是
 * "这个对话听起来像谁"。两者混在一起时，用户改了一个对话的语气，别的对话也变了。
 */
@Composable
internal fun ConversationVoiceSection(
    viewModel: AppViewModel,
    conversation: Conversation,
) {
    val ios = IosTheme.palette
    val voice by viewModel.voice.collectAsState()
    val catalog by viewModel.voiceCatalog.collectAsState()
    val installed = catalog.filter { viewModel.voiceInstalled(it) }

    IosSection(
        header = "朗读",
        footer = "只影响这个对话。音色提示词会替换全局那句；留空就跟随「TTS 测试」里的设置。",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("朗读它的回复", style = IosTheme.typography.body, color = ios.label)
                Text(
                    if (voice.enabled) "还要在语音设置里打开自动朗读" else "语音总开关关着，这里打开也不会出声",
                    style = IosTheme.typography.caption1,
                    color = ios.secondaryLabel,
                )
            }
            IosSwitch(
                checked = conversation.ttsEnabled,
                onCheckedChange = { on ->
                    viewModel.updateConversationSettings(conversation.id) { it.copy(ttsEnabled = on) }
                },
            )
        }
        Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(ios.separator))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
            Text("音色提示词", style = IosTheme.typography.body, color = ios.label)
            Spacer(Modifier.height(2.dp))
            Text(
                "例如：语速慢一点，像在耳边说话。留空跟随全局。",
                style = IosTheme.typography.caption1,
                color = ios.secondaryLabel,
            )
            Spacer(Modifier.height(8.dp))
            PromptField(
                value = conversation.voicePrompt,
                placeholder = "留空 = 跟随全局",
            ) { v ->
                viewModel.updateConversationSettings(conversation.id) { it.copy(voicePrompt = v) }
            }
        }
        Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(ios.separator))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
            Text("音色", style = IosTheme.typography.body, color = ios.label)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                VoiceChip(
                    label = "跟随全局",
                    selected = conversation.voiceId.isBlank(),
                    onClick = {
                        viewModel.updateConversationSettings(conversation.id) { it.copy(voiceId = "") }
                    },
                )
                installed.take(4).forEach { v ->
                    VoiceChip(
                        label = v.label,
                        selected = conversation.voiceId == v.id,
                        onClick = {
                            viewModel.updateConversationSettings(conversation.id) { it.copy(voiceId = v.id) }
                        },
                    )
                }
            }
            if (installed.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "还没有安装神经音色；不装也能用系统 TTS。去「我的 → TTS 测试」下载。",
                    style = IosTheme.typography.caption2,
                    color = ios.tertiaryLabel,
                )
            }
        }
    }
}

@Composable
private fun VoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Text(
        label,
        style = IosTheme.typography.footnote,
        color = if (selected) ios.onAccent else ios.label,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) ios.blue else ios.fillSecondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** 一行危险操作（清空 / 删除）。 */
@Composable
internal fun DangerRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onConfirm: () -> Unit,
) {
    val ios = IosTheme.palette
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (confirming) {
                        confirming = false
                        onConfirm()
                    } else {
                        confirming = true
                    }
                }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = ios.red, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (confirming) "再点一次确认" else title,
                    style = IosTheme.typography.body,
                    color = ios.red,
                )
                Text(subtitle, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
            }
        }
    }
}

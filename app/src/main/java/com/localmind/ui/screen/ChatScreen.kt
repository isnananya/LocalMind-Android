package com.localmind.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.data.AiAbilities
import com.localmind.data.ChatMessage
import com.localmind.data.Conversation
import com.localmind.data.Persona
import com.localmind.data.PersonaPresets
import com.localmind.data.Role
import com.localmind.ui.AppViewModel
import com.localmind.ui.Tab
import com.localmind.ui.components.ConversationInsightSection
import com.localmind.ui.components.scrollBottomPadding
import com.localmind.ui.components.IosLargeTitle
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosSlider
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.components.ModelPickerSheet
import com.localmind.ui.components.PickerOption
import com.localmind.ui.components.modelOptions
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.icons.AppIconsAutoMirrored
import com.localmind.ui.motion.Motion
import com.localmind.ui.motion.appearIn
import com.localmind.ui.motion.pressScale
import com.localmind.ui.theme.IosTheme
import com.localmind.util.Formatters
import com.localmind.util.TextUtils
import java.util.Locale

/**
 * Home = iOS Messages.
 *
 * Two states, no sheets: a conversation **list** (the default), and the **thread**
 * you push into. Everything that is not conversation — models, voice, API, agent
 * permissions, providers — lives in its own tab or in Settings.
 */
@Composable
fun ChatScreen(viewModel: AppViewModel, sharedText: String? = null) {
    val inChat by viewModel.inChat.collectAsState()

    if (inChat) {
        ChatThread(viewModel, sharedText)
    } else {
        ConversationList(viewModel)
    }
}

/* ------------------------------------------------------------------ list */

@Composable
private fun ConversationList(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val conversations by viewModel.conversations.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val backend by viewModel.backend.collectAsState()
    // 新建对话要先选类型，见下面的 Dialog。
    var creating by remember { mutableStateOf(false) }
    val providers by viewModel.providers.collectAsState()
    // Each row shows the persona the thread belongs to, so the list reflects "who
    // you were talking to" instead of one generic glyph for every conversation.
    val personas by viewModel.personas.collectAsState()
    val activePersonaId by viewModel.activePersonaId.collectAsState()
    val fallbackPersona = remember(personas, activePersonaId) {
        personas.firstOrNull { it.id == activePersonaId } ?: PersonaPresets.default
    }

    var query by remember { mutableStateOf("") }
    val remote = providers.firstOrNull { it.id == backend && it.enabled }

    val filtered = remember(conversations, query) {
        if (query.isBlank()) conversations
        else conversations.filter { conv ->
            conv.title.contains(query, true) ||
                conv.messages.any { it.content.contains(query, true) }
        }
    }.sortedByDescending { it.updatedAt }

    Column(
        Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
    ) {
        IosNavigationBar(
            title = "对话",
            collapsed = true,
            topInset = statusBarTopPadding(),
            leading = {
                Icon(
                    AppIcons.Tune,
                    contentDescription = "设置",
                    tint = ios.blue,
                    modifier = Modifier
                        .size(23.dp)
                        .clickable { viewModel.openSettings(true) },
                )
            },
            trailing = {
                Icon(
                    AppIcons.Add,
                    contentDescription = "新对话",
                    tint = ios.blue,
                    modifier = Modifier
                        .size(26.dp)
                        .clickable { creating = true },
                )
            },
        )
        IosLargeTitle("对话")

        // Search field, iOS style
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ios.fillSecondary)
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Search, contentDescription = null, tint = ios.tertiaryLabel, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("搜索", style = IosTheme.typography.subheadline, color = ios.tertiaryLabel)
                }
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = LocalTextStyle.current.merge(IosTheme.typography.subheadline).copy(color = ios.label),
                    cursorBrush = SolidColor(ios.blue),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Which engine will answer — one quiet line, not a banner.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ready = engineState.canChat
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        when {
                            remote != null -> ios.indigo
                            ready -> ios.green
                            else -> ios.orange
                        }
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = when {
                    remote != null -> "云端 · ${remote.name} / ${remote.model}"
                    ready -> "本地 · ${engineState.modelName}"
                    else -> "未加载本地模型"
                },
                style = IosTheme.typography.caption1,
                color = ios.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!ready && remote == null) {
                Text(
                    "去加载",
                    style = IosTheme.typography.caption1,
                    color = ios.blue,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { viewModel.selectTab(Tab.MODELS) },
                )
            }
        }

        // ---- 新建对话：**先选类型，再创建** ----
        //
        // 两种对话是两种东西（AI 对话无人设、要最快；人设聊天有人机恋那套
        // 记忆与情绪），而且**建好之后不能改** —— 切换类型要处理"已有的记忆
        // 怎么办"，那是个产品难题，不如在建的时候问一次。
        //
        // 用 Dialog 而不是自己画浮层：它是独立窗口，不参与这一页的布局，
        // 也就不需要把整个函数包进一个 Box 里。
        if (creating) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { creating = false }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(ios.secondaryGroupedBackground)
                        .padding(18.dp),
                ) {
                    Text("新对话", style = IosTheme.typography.title3, color = ios.label)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "类型建好之后不能改",
                        style = IosTheme.typography.caption1,
                        color = ios.secondaryLabel,
                    )
                    Spacer(Modifier.height(16.dp))
                    CreateKindRow(
                        title = "AI 对话",
                        subtitle = "无人设、不留记忆，直接调模型 —— 最快",
                        tint = ios.blue,
                    ) {
                        creating = false
                        viewModel.newConversation(com.localmind.data.ChatMode.AI)
                    }
                    Spacer(Modifier.height(10.dp))
                    CreateKindRow(
                        title = "人设聊天",
                        subtitle = "有名字、有记忆、有情绪，像在和一个具体的人相处",
                        tint = ios.pink,
                    ) {
                        creating = false
                        viewModel.newConversation(com.localmind.data.ChatMode.COMPANION)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "取消",
                        style = IosTheme.typography.footnote,
                        color = ios.secondaryLabel,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { creating = false }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            EmptyMessages(
                hasQuery = query.isNotBlank(),
                onNew = { creating = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = scrollBottomPadding(tabBarVisible = true, base = 24.dp)),
            ) {
                itemsIndexed(filtered, key = { _, c -> c.id }) { index, conv ->
                    ConversationRow(
                        conversation = conv,
                        // 用**这个对话自己的**身份，不是去人设库里按 personaId 查。
                        //
                        // 「本对话设置」把人设写进的是对话自己的字段（personaName /
                        // personaAvatarEmoji…），而 personaId 只在新建对话时记了一次、
                        // 之后改身份并不会更新。按 id 去全局库里查，改了头像和名字这一行
                        // 当然不会变 —— 这就是"对话列表的名字和头像没法显示"。
                        persona = conv.persona(fallbackPersona),
                        onClick = { viewModel.openConversation(conv.id) },
                        onDelete = { viewModel.deleteConversation(conv.id) },
                        index = index,
                    )
                }
            }
        }
    }
}

/** 新建对话里的一个类型选项。 */
@Composable
private fun CreateKindRow(
    title: String,
    subtitle: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val ios = IosTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(title, style = IosTheme.typography.body, color = tint)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    persona: Persona,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    index: Int = 0,
) {
    val ios = IosTheme.palette
    val last = conversation.messages.lastOrNull()
    val interaction = remember { MutableInteractionSource() }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .background(ios.groupedBackground)
                .padding(horizontal = 16.dp, vertical = 11.dp)
                // 列表项错峰入场。间隔 28ms，10 条也就 0.28s，不会让人等。
                .appearIn(index = index),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar bubble — the persona this thread talks to
            PersonaAvatar(
                emoji = persona.avatarEmoji,
                colorIndex = persona.avatarColor,
                size = 46,
                imagePath = persona.avatarImage,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 还没聊过（标题还是默认值）时显示**这个对话的 AI 名字**。
                    // 列表里这一行回答的是"我在和谁聊"，人设名比"新对话"有用得多。
                    val displayName = if (conversation.title == Conversation.DEFAULT_TITLE) {
                        persona.name.ifBlank { conversation.title }
                    } else {
                        conversation.title
                    }
                    Text(
                        text = displayName,
                        style = IosTheme.typography.body,
                        fontWeight = FontWeight.SemiBold,
                        color = ios.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    // 类型徽标：列表里两条对话看起来可能一模一样（同样的头像、
                    // 同样一句话的开头），但它们是两种东西 —— 一个有人设和记忆，
                    // 一个没有。不标出来，用户只能点进去才知道自己开的是哪种。
                    IosPill(
                        if (conversation.companionMode) "人设" else "AI",
                        if (conversation.companionMode) ios.pink else ios.blue,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = Formatters.dateTime(conversation.updatedAt),
                        style = IosTheme.typography.caption2,
                        color = ios.tertiaryLabel,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = last?.let { (if (it.role == Role.USER) "我：" else "") + TextUtils.preview(it.content, 46) }
                        ?: "还没有消息",
                    style = IosTheme.typography.subheadline,
                    color = ios.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "删除",
                style = IosTheme.typography.caption1,
                color = ios.red,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 74.dp)
                .height(0.5.dp)
                .background(ios.separator)
        )
    }
}

/**
 * 「你说的…是哪个意思？」—— 追问 + 候选含义。
 *
 * 设计取舍：候选做成**整句话**而不是关键词标签。关键词标签只能做分类，
 * 整句话才能携带语义；用户点完，AI 得到的是一个可以直接写进记忆的完整含义。
 *
 * 永远保留「都不对」的出口：模型的候选不可能覆盖所有情况，堵死的话用户只能
 * 被迫选一个错的，那比不问更糟。
 */
@Composable
private fun ClarificationBar(
    clarification: com.localmind.service.Deliberation.Clarification,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ios = IosTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ios.groupedBackground)
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ios.pink.copy(alpha = 0.09f))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                AppIcons.HelpOutline,
                contentDescription = null,
                tint = ios.pink,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                clarification.question,
                style = IosTheme.typography.footnote,
                color = ios.label,
                modifier = Modifier.weight(1f),
            )
            Text(
                "不用了",
                style = IosTheme.typography.caption2,
                color = ios.tertiaryLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        clarification.options.forEach { option ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ios.fillSecondary)
                    .clickable { onPick(option) }
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Text(option, style = IosTheme.typography.footnote, color = ios.label)
            }
        }
    }
}

@Composable
private fun EmptyMessages(hasQuery: Boolean, onNew: () -> Unit) {    val ios = IosTheme.palette
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            AppIcons.Memory,
            contentDescription = null,
            tint = ios.tertiaryLabel,
            modifier = Modifier.size(52.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (hasQuery) "没有匹配的对话" else "开始一段对话",
            style = IosTheme.typography.title3,
            color = ios.label,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (hasQuery) "换个关键词试试" else "全部在本机离线运行",
            style = IosTheme.typography.subheadline,
            color = ios.secondaryLabel,
        )
        if (!hasQuery) {
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(ios.blue)
                    .clickable(onClick = onNew)
                    .padding(horizontal = 20.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Add, contentDescription = null, tint = ios.onAccent, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("新对话", style = IosTheme.typography.headline, color = ios.onAccent)
            }
        }
    }
}

/* ---------------------------------------------------------------- thread */

// 「本对话设置」已经拆成两个独立页面，见 ConversationSettings.kt /
// AiChatSettingsScreen.kt / PersonaChatSettingsScreen.kt。
// 以前这里是**一个** ConversationDetailScreen，里面用 if (companionMode) 分叉 ——
// 结果两种对话共享了同一批栏目（"它可以做什么""模型参数""本对话用的模型"），
// 于是一个无人设的 AI 对话里也会出现"拟人强度"和"记忆 0 条"。
/**
 * 「它可以做什么」里的一行开关。
 *
 * @param locked 用于文字回复这种不能关的项。
 * @param ready 前置条件是否满足（语音引擎装了没、生图模型下了没）。不满足时
 *   **仍然画出来**、只是置灰 —— 让人知道"这个能力存在，只是还缺东西"，
 *   而不是把整行藏掉让人以为没有这个功能。
 */
@Composable
internal fun AbilityRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    locked: Boolean = false,
    ready: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = IosTheme.typography.body,
                    color = if (locked || !ready) ios.secondaryLabel else ios.label,
                )
                if (!ready && !locked) {
                    Spacer(Modifier.width(6.dp))
                    IosPill("缺条件", ios.orange)
                }
            }
            Text(
                subtitle,
                style = IosTheme.typography.caption1,
                color = if (!ready && !locked) ios.orange else ios.secondaryLabel,
            )
        }
        if (locked) {
            IosPill("必开", ios.gray)
        } else {
            IosSwitch(checked = checked, onCheckedChange = onChange, enabled = ready)
        }
    }
}

/**
 * 表情包里的一张图片梗图。
 *
 * 尺寸按"贴纸"处理：不铺满气泡宽度，而是给一个 88dp 的小方块 + 圆角，
 * 这样它读起来像聊天里贴的一张图，而不是一张被发送的照片。
 * 图片不存在时**整个不画** —— 插件被卸载后旧消息里的图会失效，
 * 画一个破图占位比什么都不显示更糟。
 */
@Composable
private fun StickerImage(file: java.io.File) {
    if (!file.isFile) return
    val bitmap = remember(file.path, file.lastModified()) {
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    } ?: return
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier
            .size(88.dp)
            .clip(RoundedCornerShape(12.dp)),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
}

/**
 * 这个对话的大模型参数微调。
 *
 * 每个滑杆都有一个「跟随全局」开关：不打开就用全局值，打开才覆盖。
 * 这样用户不必为了调一项而把其它项也钉死 —— 后者在换模型时会变成陷阱。
 */
@Composable
internal fun SamplingSection(viewModel: AppViewModel, conversation: Conversation) {
    val ios = IosTheme.palette
    // 基线是**内置默认值**，不是某个全局设置。
    //
    // 原因：「我的」里那套全局 AI 设置已经整块撤掉了 —— 参数是"这个对话该怎么说话"，
    // 不该有一个隐形的全局层在后面起作用（用户看不到它，却会被它影响）。
    // 所以这里用 GenerationSettings() 的出厂值当"默认"，只有这个对话的微调能盖它。
    val global = remember { com.localmind.data.GenerationSettings() }
    val s = conversation.sampling

    IosSection(
        header = "模型参数（本对话）",
        footer = "不打开「覆盖」就用内置默认值。中文对话里重复惩罚调太高会让用词变得别扭。",
    ) {
        OverrideSlider(
            label = "温度 temperature",
            hint = "越高越天马行空，越低越稳定保守",
            value = s.temperature,
            globalValue = global.temperature,
            range = 0f..2f,
            format = { String.format("%.2f", it) },
            onChange = { v ->
                viewModel.updateConversationSampling(conversation.id) { it.copy(temperature = v) }
            },
        )

        Divider(ios.separator)
        OverrideSlider(
            label = "top-p",
            hint = "候选词累计概率上限",
            value = s.topP,
            globalValue = global.topP,
            range = 0f..1f,
            format = { String.format("%.2f", it) },
            onChange = { v -> viewModel.updateConversationSampling(conversation.id) { it.copy(topP = v) } },
        )

        Divider(ios.separator)
        OverrideSlider(
            label = "重复惩罚",
            hint = "1.0 = 不惩罚",
            value = s.repeatPenalty,
            globalValue = global.repeatPenalty,
            range = 1f..1.5f,
            format = { String.format("%.2f", it) },
            onChange = { v ->
                viewModel.updateConversationSampling(conversation.id) { it.copy(repeatPenalty = v) }
            },
        )

        Divider(ios.separator)
        OverrideSlider(
            label = "最大生成长度",
            hint = "一次回复的 token 上限",
            value = s.maxTokens?.toFloat(),
            globalValue = global.maxTokens.toFloat(),
            range = 128f..4096f,
            format = { it.toInt().toString() },
            onChange = { v ->
                viewModel.updateConversationSampling(conversation.id) { it.copy(maxTokens = v?.toInt()) }
            },
        )

        Divider(ios.separator)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    viewModel.updateConversationSampling(conversation.id) {
                        com.localmind.data.SamplingOverrides()
                    }
                }
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (s.isDefault) "全部用内置默认值" else "恢复为内置默认值",
                style = IosTheme.typography.body,
                color = if (s.isDefault) ios.secondaryLabel else ios.blue,
            )
        }
    }
}

/**
 * 一行「覆盖开关 + 滑杆」。
 *
 * **滑杆始终画出来**，只是没开覆盖时置灰不可拖。原来没开覆盖时整条滑杆消失，
 * 用户看到的是"这一项没有滑杆"，而不是"要先打开右边的开关" —— 这类"关掉才
 * 显示/打开才显示"的界面很容易让人以为功能不存在。
 */
@Composable
internal fun OverrideSlider(
    label: String,
    hint: String,
    value: Float?,
    globalValue: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float?) -> Unit,
) {
    val ios = IosTheme.palette
    val overridden = value != null
    Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = IosTheme.typography.body, color = ios.label)
                Text(hint, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
            }
            Text(
                format(value ?: globalValue) + if (overridden) "" else "（默认）",
                style = IosTheme.typography.caption1,
                color = if (overridden) ios.blue else ios.tertiaryLabel,
            )
            Spacer(Modifier.width(8.dp))
            IosSwitch(
                checked = overridden,
                onCheckedChange = { on -> onChange(if (on) (value ?: globalValue) else null) },
            )
        }
        Spacer(Modifier.height(4.dp))
        IosSlider(
            value = value ?: globalValue,
            // 滑杆只能给 Float，但"取消覆盖"必须能传回 null，
            // 所以回调参数是可空的：null = 回到跟随全局。
            onValueChange = { onChange(it) },
            valueRange = range,
            enabled = overridden,
        )
        if (!overridden) {
            Text(
                "打开右边的开关才能单独调这一项",
                style = IosTheme.typography.caption2,
                color = ios.tertiaryLabel,
            )
        }
    }
}

/** 一行「用默认 / 指定某个模型」。空串表示不指定。 */
@Composable
internal fun ModelOverrideRow(
    label: String,
    current: String,
    models: List<com.localmind.data.LocalModel>,
    visionOnly: Boolean = false,
    imageOnly: Boolean = false,
    onPick: (String) -> Unit,
) {
    val ios = IosTheme.palette
    var picking by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { picking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = IosTheme.typography.body, color = ios.label, modifier = Modifier.weight(1f))
        Text(
            current.ifBlank { "跟随全局" },
            style = IosTheme.typography.caption1,
            color = ios.secondaryLabel,
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
            options = listOf(PickerOption("", "跟随全局默认", "用「模型分工」里的设置")) +
                modelOptions(models, emptyList(), visionOnly = visionOnly, imageOnly = imageOnly),
            selectedId = current,
            onSelect = {
                onPick(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun ChatThread(viewModel: AppViewModel, sharedText: String?) {
    val ios = IosTheme.palette
    val chatState by viewModel.chat.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val activeId by viewModel.activeId.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val voice by viewModel.voice.collectAsState()
    val capabilities by viewModel.capabilities.collectAsState()
    val backend by viewModel.backend.collectAsState()
    val providers by viewModel.providers.collectAsState()

    val conversation = conversations.firstOrNull { it.id == activeId }
    val listState = rememberLazyListState()
    val messageCount = conversation?.messages?.size ?: 0
    // **这个对话**由谁回答 —— 和 AppViewModel.send 里那套解析保持一致。
    //
    // 原来这里只看全局后端（backend），于是“在对话设置里给这个对话选了 API”之后：
    //  - 底部提示仍然写着本地模型；
    //  - 本机还没加载模型时，`canSend` 为 false，输入框被禁用 —— 明明 API 是通的。
    val routing by viewModel.routing.collectAsState()
    val targetId = conversation?.effectiveChatModel(routing).orEmpty()
    val convProvider = providers.firstOrNull { it.id == targetId && it.enabled }
    val remote = convProvider
        ?: if (targetId.isBlank()) providers.firstOrNull { it.id == backend && it.enabled } else null
    val canSend = engineState.canChat || remote != null
    val clarification by viewModel.clarification.collectAsState()
    val pendingImage by viewModel.pendingImage.collectAsState()

    // 全屏看图。状态放在**列表外面**（这一层），因为查看器要盖住整页，
    // 而不是被某一行的布局裁掉；也保证列表里所有图共用同一个查看器。
    var viewerPath by remember { mutableStateOf<String?>(null) }

    // 发图。图片是**本地路径**，直接给多模态模型看；不需要先上传到哪。
    // 权限走 OpenDocument（SAF），所以不用申请读存储权限。
    //
    // ⚠️ 落盘走 ChatImageStore，**不要**在这里直接 copyTo：
    //  * 原图可能是 5 MB 的相机照片，而聊天里最多画到屏宽 —— 必须缩放；
    //  * 目录必须在 filesDir（不是 cacheDir），否则系统一清缓存，
    //    历史消息里的图就永久没了，而消息本身还在。
    val context = androidx.compose.ui.platform.LocalContext.current
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val saved = com.localmind.data.ChatImageStore.saveFromUri(context, uri)
        if (saved != null) {
            viewModel.attachImage(saved)
        } else {
            viewModel.toast("这张图片读不出来，换一张试试")
        }
    }

    LaunchedEffect(conversation?.id, sharedText) {
        if (!sharedText.isNullOrBlank()) viewModel.setDraft(sharedText)
    }

    LaunchedEffect(messageCount, chatState.streaming) {
        if (messageCount > 0) runCatching { listState.animateScrollToItem(messageCount - 1) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
            .imePadding()
    ) {
        IosNavigationBar(
            // 标题显示**你在和谁说话**：还没聊过时用人设名（和列表那一行同一套判据），
            // 聊过之后才换成由第一句话生成的标题。原来这里永远是"新对话"，
            // 明明设置里写了名字却看不到。
            title = conversation?.let { c ->
                if (c.title == Conversation.DEFAULT_TITLE) {
                    c.persona(com.localmind.data.PersonaPresets.default).name.ifBlank { c.title }
                } else {
                    c.title
                }
            } ?: "对话",
            collapsed = true,
            topInset = statusBarTopPadding(),
            leading = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.leaveConversation() }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
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
            trailing = {
                // 这个按钮打开的是**本对话自己的**详情：AI 是谁 + 它现在的状态。
                // 全局设置（生成参数、下载源、能力开关）在「我的」里。
                Icon(
                    AppIcons.Tune,
                    contentDescription = "本对话设置",
                    tint = ios.blue,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { viewModel.openConversationDetail(true) },
                )
            },
        )

        // ---------------------------------------------------- 对话详情
        //
        // 注意：这里**不再**画详情页了。它已经提升到 `LocalMindRoot` 的根层
        // （见 `AppViewModel.showConversationDetail`）。
        //
        // 原因：画在这里的话，聊天页自己的导航栏（`‹ 对话 / 新对话`）还在下面，
        // 详情页的导航栏叠在它上面 —— 屏幕上同时出现两条返回栏。那是纯粹的层级
        // 错误：整页覆盖必须画在根层，才能把底下的导航栏和标签栏一起盖掉。

        if (!canSend) {
            NoModelBanner(
                onOpenModels = { viewModel.selectTab(Tab.MODELS) },
                error = engineState.lastError,
                loading = engineState.loading,
                encoderOnly = engineState.encoderOnly,
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val messages = conversation?.messages.orEmpty()
            if (messages.isEmpty()) {
                item {
                    ThreadHint(
                        modelName = remote?.name ?: engineState.modelName,
                        onSuggestion = { viewModel.send(it) },
                        enabled = canSend,
                    )
                }
            }
            itemsIndexed(messages, key = { _, m -> m.id }) { index, message ->
                MessageRow(
                    message = message,
                    streaming = chatState.streaming && message.id == messages.lastOrNull()?.id,
                    onCopy = { viewModel.toast("已复制") },
                    onSpeak = { viewModel.speak(message.content) },
                    onOpenImage = { viewerPath = it },
                    painterName = conversation?.personaName?.ifBlank { null } ?: "它",
                    onSaveImage = { p ->
                        val ok = com.localmind.ui.components.saveImageToGallery(context, java.io.File(p))
                        viewModel.toast(if (ok) "已保存到相册" else "保存失败")
                    },
                    // 只给最后几条加入场动画。整个列表都动的话，往上翻历史时
                    // 每一屏都在重播动画，反而看不清内容。
                    animateIn = index >= messages.size - 3,
                )
            }
            if (chatState.thinking) {
                item { ThinkingIndicator() }
            }
        }

        // 消歧：模型没把握时给出的「追问 + 候选含义」。点一下就完成确认，
        // 选中的结果会以高置信度写进记忆。
        clarification?.let { c ->
            ClarificationBar(
                clarification = c,
                onPick = { viewModel.answerClarification(it) },
                onDismiss = { viewModel.dismissClarification() },
            )
        }

        // 待发送的图片：显示成一条可以撤掉的缩略图。
        // 如果这个 build 还看不了图，就在这条上直说 —— 让用户发出去再发现
        // "模型没反应"是最糟的体验。
        pendingImage?.let { path ->
            PendingImageStrip(
                path = path,
                canSee = viewModel.canSeeImages,
                onRemove = { viewModel.clearPendingImage() },
            )
        }

        ChatInputBar(
            value = chatState.draft,
            onValueChange = { viewModel.setDraft(it) },
            streaming = chatState.streaming,
            enabled = canSend,
            voiceEnabled = voice.enabled,
            onSend = { viewModel.send(chatState.draft) },
            onStop = { viewModel.stopGeneration() },
            // 麦克风按钮打开真正的「语音与音色」页（情绪九宫格 / 语速 / 音色 / 试听）。
            // 之前这里打开的是设置栏，而设置栏里没有任何语音项 —— 点了等于没反应。
            onOpenVoice = { viewModel.openVoiceScreen(true) },
            // 加号发图。「上网 / Agent」那两个开关移到对话设置里了 ——
            // 它们是模式开关，不是每次发消息都要碰的东西。
            onPickImage = { pickImage.launch(arrayOf("image/*")) },
            tps = engineState.lastTokensPerSecond,
            tokens = engineState.tokensGenerated,
            cacheHit = engineState.cacheHitPercent,
            usingApi = remote != null,
        )
    }

    // 全屏查看器放在**最后**：它是覆盖层，最后画才在最上面。
    viewerPath?.let { path ->
        com.localmind.ui.components.FullscreenImage(
            path = path,
            onDismiss = { viewerPath = null },
        )
    }
}

/**
 * 待发送图片的缩略图条。
 *
 * ## 为什么要把"还看不了图"写在这里
 *
 * 这个 build 的多头投影（mmproj）支持还没接进 JNI，所以模型**真的看不见**
 * 图片。用户点加号选了图、发出去、得到一个答非所问的回复 —— 这是最糟的
 * 体验，因为他会以为是模型笨，而不是功能没做完。
 *
 * 所以在这条上直接说清楚。等 `llama_jni.cpp` 接上 libmtmd 之后，
 * [AppViewModel.canSeeImages] 会返回 true，这行橙字自动消失。
 */
@Composable
private fun PendingImageStrip(path: String, canSee: Boolean, onRemove: () -> Unit) {
    val ios = IosTheme.palette
    val file = remember(path) { java.io.File(path) }
    val bitmap = remember(path) {
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(ios.barBackground)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ios.fill),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Image,
                        contentDescription = null,
                        tint = ios.tertiaryLabel,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(file.name, style = IosTheme.typography.caption1, color = ios.label, maxLines = 1)
                Text(
                    if (canSee) "会一起发给模型" else "当前版本还不能识图，发出去模型看不到这张图",
                    style = IosTheme.typography.caption2,
                    color = if (canSee) ios.secondaryLabel else ios.orange,
                )
            }
            Icon(
                AppIcons.Close,
                contentDescription = "移除图片",
                tint = ios.secondaryLabel,
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onRemove),
            )
        }
    }
}

@Composable
private fun ThreadHint(modelName: String?, onSuggestion: (String) -> Unit, enabled: Boolean) {
    val ios = IosTheme.palette
    // 预设示例已经去掉：空对话页只留标题和一句提示，保持简洁。
    //
    // 那四条看着热闹，但每开一个新对话都占掉半屏，对熟手全是噪音；
    // 而且它们和"这个对话是什么类型"无关 —— AI 对话和人设聊天看到同一批示例，
    // 本身就在暗示两者是一种东西。
    val suggestions = emptyList<String>()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 30.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 空对话页那个大标题。主题的 `appName` 在这里生效 —— 它是整个应用里
        // 最显眼的"这个应用叫什么"的位置（关于页里的名字是**事实**，不该被主题改）。
        val title = com.localmind.ui.theme.LocalUiTheme.current.appName.ifBlank { "LocalMind" }
        Text(
            text = title,
            style = IosTheme.typography.title3,
            color = ios.label,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "本机运行，对话不离开这台设备",
            style = IosTheme.typography.footnote,
            color = ios.secondaryLabel,
        )
        Spacer(Modifier.height(20.dp))
        suggestions.forEach { suggestion ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ios.secondaryGroupedBackground)
                    .clickable(enabled = enabled) { onSuggestion(suggestion) }
                    .padding(14.dp)
            ) {
                Text(
                    suggestion,
                    style = IosTheme.typography.subheadline,
                    color = if (enabled) ios.label else ios.tertiaryLabel,
                )
            }
        }
    }
}

/* ------------------------------------------------------------- fragments */

@Composable
private fun NoModelBanner(
    onOpenModels: () -> Unit,
    error: String?,
    loading: Boolean,
    encoderOnly: Boolean = false,
) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ios.fillSecondary)
            .clickable(enabled = !loading, onClick = onOpenModels)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ios.blue)
        } else {
            Icon(AppIcons.Memory, contentDescription = null, tint = ios.blue, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = when {
                    loading -> "正在加载模型"
                    encoderOnly -> "该模型不能对话"
                    else -> "还没有可用的模型"
                },
                style = IosTheme.typography.subheadline,
                color = ios.label,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = error ?: "点这里去下载并加载（推荐 Qwen2.5 1.5B，1.1 GB）",
                style = IosTheme.typography.caption1,
                color = ios.secondaryLabel,
            )
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier.padding(start = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp, color = ios.gray)
        Spacer(Modifier.width(8.dp))
        Text("正在思考", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
    }
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    streaming: Boolean,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    /** 点图片 → 全屏查看。由 ChatScreen 持有状态，这样整个列表共用一个查看器。 */
    onOpenImage: (String) -> Unit = {},
    /** AI 的名字，用来标"XX 画的"。 */
    painterName: String = "它",
    onSaveImage: (String) -> Unit = {},
    animateIn: Boolean = false,
) {
    val ios = IosTheme.palette
    val clipboard = LocalClipboardManager.current

    // 气泡入场：从下方 14px 上浮 + 淡入 + 轻微放大。
    // 用户发的那条从右侧、AI 那条从左侧，方向暗示"谁在说" —— 比单纯淡入
    // 更容易一眼看出这轮对话的顺序。
    val entrance = if (animateIn) {
        Modifier.appearIn(offsetY = 14)
    } else {
        Modifier
    }

    when (message.role) {
        Role.USER -> Row(
            Modifier.fillMaxWidth().then(entrance),
            horizontalArrangement = Arrangement.End,
        ) {
            // 图片和文字是**同一坨**内容（都是这条消息的一部分），所以竖着排、
            // 共用右边的对齐线；但图片在外、文字在内，各用自己的圆角 ——
            // 把图塞进彩色气泡里会让气泡变成一个奇怪的大色块。
            Column(
                modifier = Modifier.widthIn(max = 300.dp),
                horizontalAlignment = Alignment.End,
            ) {
                message.imagePath?.takeIf { it.isNotBlank() }?.let { path ->
                    com.localmind.ui.components.UserMessageImage(
                        path = path,
                        onClick = { onOpenImage(path) },
                    )
                    if (message.content.isNotBlank()) Spacer(Modifier.height(6.dp))
                }
                if (message.content.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(IosTheme.metrics.bubbleCorner))
                            .background(ios.bubbleUser)
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = message.content,
                                style = IosTheme.typography.body,
                                color = ios.bubbleUserText,
                            )
                        }
                    }
                }
            }
        }

        Role.ASSISTANT -> Row(Modifier.fillMaxWidth().then(entrance)) {
            Column(Modifier.widthIn(max = 320.dp)) {
                message.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
                    ReasoningFold(reasoning = reasoning, streaming = streaming)
                    Spacer(Modifier.height(6.dp))
                }
                if (message.content.isNotBlank() || streaming) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(IosTheme.metrics.bubbleCorner))
                            .background(ios.bubbleAi)
                            .border(
                                IosTheme.metrics.barBorderWidth,
                                ios.bubbleAiBorder,
                                RoundedCornerShape(IosTheme.metrics.bubbleCorner),
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        MarkdownText(message.content.ifEmpty { if (streaming) "..." else "" })
                    }
                }

                // AI 自己画的图。
                //
                // 出处标记要在**图上方**，并且给一个「保存」—— 这是图出来之后
                // 最自然的下一步。以前标记在图下面、且没有保存入口，用户想留下
                // 这张图只能截图。
                message.generatedImagePath?.takeIf { it.isNotBlank() }?.let { path ->
                    Spacer(Modifier.height(8.dp))
                    com.localmind.ui.components.AiMessageImage(
                        path = path,
                        label = "$painterName 画的",
                        onClick = { onOpenImage(path) },
                        onSave = { onSaveImage(path) },
                    )
                }

                // 表情渲染在气泡**外面**，读起来像"语气"而不是正文的一部分。
                //
                // 两种形态：
                //  * `file://…` = 表情包插件的**图片梗图**，按图片画（这才是"发表情包"）
                //  * 其它 = 一个 emoji 字符，直接当文字画
                message.emoji?.takeIf { it.isNotBlank() }?.let { e ->
                    Spacer(Modifier.height(6.dp))
                    if (e.startsWith("file://")) {
                        StickerImage(java.io.File(e.removePrefix("file://")))
                    } else {
                        Text(e, style = IosTheme.typography.title2)
                    }
                }
                Row(
                    modifier = Modifier.padding(start = 6.dp, top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (message.tokensPerSecond != null && message.tokensPerSecond > 0) {
                        IosPill(
                            text = String.format(Locale.US, "%.1f tok/s", message.tokensPerSecond),
                            color = ios.gray,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = Formatters.time(message.createdAt),
                        style = IosTheme.typography.caption2,
                        color = ios.tertiaryLabel,
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        AppIcons.ContentCopy,
                        contentDescription = "复制",
                        tint = ios.tertiaryLabel,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable {
                                clipboard.setText(AnnotatedString(message.content))
                                onCopy()
                            },
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        AppIcons.GraphicEq,
                        contentDescription = "朗读",
                        tint = ios.tertiaryLabel,
                        modifier = Modifier
                            .size(14.dp)
                            .clickable(onClick = onSpeak),
                    )
                }
            }
        }

        Role.SYSTEM -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = message.content,
                style = IosTheme.typography.caption1,
                color = ios.tertiaryLabel,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

/** Minimal markdown: fenced code blocks, inline code and bold. */
@Composable
fun MarkdownText(text: String) {
    val ios = IosTheme.palette
    Column {
        TextUtils.splitCodeSegments(text).forEach { segment ->
            when (segment) {
                is TextUtils.Segment.Text -> {
                    if (segment.text.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                text = inlineMarkdown(segment.text, ios.blue),
                                style = IosTheme.typography.body,
                                color = ios.label,
                            )
                        }
                    }
                }

                is TextUtils.Segment.Code -> {
                    Column(
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(com.localmind.ui.theme.LocalUiTokens.current["codeBackground"]?.let { runCatching { com.localmind.ui.theme.parseHexColor(it) }.getOrNull() } ?: if (ios.isDark) Color(0xFF0D0D0F) else Color(0xFFF2F2F7))
                            .padding(10.dp)
                    ) {
                        if (segment.language.isNotBlank()) {
                            Text(segment.language, style = IosTheme.typography.caption2, color = ios.secondaryLabel)
                        }
                        SelectionContainer {
                            Text(
                                text = segment.code,
                                style = IosTheme.typography.footnote.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                ),
                                color = ios.label,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun inlineMarkdown(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var index = 0
    val pattern = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
    pattern.findAll(text).forEach { match ->
        if (match.range.first > index) append(text.substring(index, match.range.first))
        val token = match.value
        when {
            token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(token.removeSurrounding("**"))
            }

            token.startsWith("`") -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, color = accent, fontSize = 13.sp)
            ) { append(token.removeSurrounding("`")) }

            else -> append(token)
        }
        index = match.range.last + 1
    }
    if (index < text.length) append(text.substring(index))
}

/**
 * Collapsible reasoning block, DeepSeek-style: a live "深度思考中…" header while the
 * model is still inside ` thinking`, collapsing to a tappable summary afterwards.
 */
@Composable
private fun ReasoningFold(reasoning: String, streaming: Boolean) {
    val ios = IosTheme.palette
    var expanded by remember { mutableStateOf(false) }
    val active = streaming && reasoning.isNotBlank()

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ios.fillSecondary)
            .clickable(enabled = !active) { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = ios.purple)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "深度思考中…",
                    style = IosTheme.typography.footnote,
                    color = ios.purple,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Icon(AppIcons.Psychology, contentDescription = null, tint = ios.purple, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "已深度思考（${reasoning.length} 字）",
                    style = IosTheme.typography.footnote,
                    color = ios.purple,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = ios.tertiaryLabel,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (expanded || active) {
            Spacer(Modifier.height(7.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
            Spacer(Modifier.height(7.dp))
            SelectionContainer {
                Text(
                    text = reasoning,
                    style = IosTheme.typography.footnote.copy(lineHeight = 19.sp),
                    color = ios.secondaryLabel,
                    maxLines = if (active) 8 else 60,
                )
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    enabled: Boolean,
    voiceEnabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenVoice: () -> Unit,
    onPickImage: () -> Unit,
    tps: Double,
    tokens: Int,
    /** 上一轮提示词里有多大比例是直接复用 KV cache 的（见 docs/SPEED.md）。 */
    cacheHit: Int = 0,
    /** 这个对话走的是不是云端 / 局域网 API（那种情况下本机的 tok/s 没有意义）。 */
    usingApi: Boolean = false,
) {
    val ios = IosTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ios.barBackground)
    ) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.barBorder))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp, max = 132.dp)
                    .clip(RoundedCornerShape(IosTheme.metrics.controlCorner * 1.9f))
                    .border(
                        IosTheme.metrics.barBorderWidth,
                        ios.separator,
                        RoundedCornerShape(IosTheme.metrics.controlCorner * 1.9f),
                    )
                    // 输入区底色走令牌。之前写死 secondaryGroupedBackground，
                    // 于是"卡片色"和"输入框色"永远绑死，主题改不动。
                    .background(ios.composerBackground)
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = if (enabled) "发消息…" else "先加载模型",
                        style = IosTheme.typography.body,
                        color = ios.tertiaryLabel,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled && !streaming,
                    textStyle = LocalTextStyle.current.merge(IosTheme.typography.body).copy(color = ios.label),
                    cursorBrush = SolidColor(ios.blue),
                    maxLines = 6,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Send,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { if (value.isNotBlank() && enabled && !streaming) onSend() },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.width(8.dp))

            // 加号：发图。
            //
            // 这里原来是「上网」和「Agent」两个能力开关。删掉的理由：
            //  * 它们是**模式开关**，属于这个对话的设置，不是每次发消息都要碰的东西。
            //    放在输入框旁边会让人以为"这次发消息要勾一下"，而实际上它是持久的。
            //  * 「能力」那一段仍然在对话设置里（`onOpenCaps`），位置更合适。
            //  * 空出来的位置给**发图** —— 那才是每次发消息真的可能用到的东西。
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ios.fill)
                    .clickable(enabled = enabled, onClick = onPickImage),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Add,
                    contentDescription = "发图",
                    tint = if (enabled) ios.label else ios.tertiaryLabel,
                    modifier = Modifier.size(19.dp),
                )
            }

            Spacer(Modifier.width(8.dp))

            if (streaming) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ios.red)
                        .clickable(onClick = onStop),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.Stop, contentDescription = "停止", tint = ios.onAccent, modifier = Modifier.size(18.dp))
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (value.isBlank() || !enabled) ios.fill else ios.blue)
                        .clickable(enabled = value.isNotBlank() && enabled, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIconsAutoMirrored.Send,
                        contentDescription = "发送",
                        tint = if (value.isBlank() || !enabled) ios.secondaryLabel else ios.onAccent,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }

            if (voiceEnabled) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ios.green)
                        .clickable(onClick = onOpenVoice),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(AppIcons.GraphicEq, contentDescription = "语音", tint = ios.onAccent, modifier = Modifier.size(18.dp))
                }
            }
        }

        AnimatedVisibility(visible = streaming && (tokens > 0 || usingApi)) {
            Text(
                text = if (usingApi) {
                    "API 直连 · 推理在云端，本机不加载模型"
                } else {
                    "$tokens tokens · " + String.format(Locale.US, "%.1f tok/s", tps) +
                        if (cacheHit > 0) " · 缓存命中 $cacheHit%（这就是不再重算整段提示词的部分）" else ""
                },
                style = IosTheme.typography.caption2,
                color = ios.tertiaryLabel,
                modifier = Modifier.padding(start = 18.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun CapabilityChip(active: Boolean, label: String, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) ios.green.copy(alpha = 0.18f) else ios.fillSecondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = IosTheme.typography.caption2,
            color = if (active) ios.green else ios.secondaryLabel,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

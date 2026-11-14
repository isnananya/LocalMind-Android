package com.localmind.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.localmind.ui.motion.appearIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.localmind.ui.motion.ScrollPhysics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.data.ChatMode
import com.localmind.data.Conversation
import com.localmind.data.PersonaPresets
import com.localmind.data.Role
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.pressScale
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.screen.PersonaAvatar
import com.localmind.ui.theme.IosTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 对话列表 —— 打开应用的第一屏。
 *
 * 只有两件事：已有的对话、新建。没有状态条、没有能力说明、没有模型参数。
 * 行上不放"删除"按钮：长按才出现，平时不该让一屏都是红色文字。
 */
@Composable
fun ChatListPage(
    viewModel: AppViewModel,
    scrollStates: com.localmind.ui.motion.ScrollStateHolder = com.localmind.ui.motion.rememberScrollStateHolder(),
) {
    val listState = scrollStates.state("chat-list")
    val ios = IosTheme.palette
    val conversations by viewModel.conversations.collectAsState()
    val personas by viewModel.personas.collectAsState()
    val activePersonaId by viewModel.activePersonaId.collectAsState()
    val fallbackPersona = remember(personas, activePersonaId) {
        personas.firstOrNull { it.id == activePersonaId } ?: PersonaPresets.default
    }
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    val sorted = remember(conversations) { conversations.sortedByDescending { it.updatedAt } }
    val filtered = remember(sorted, query) {
        if (query.isBlank()) sorted
        else sorted.filter {
            it.title.contains(query, true) || it.messages.any { m -> m.content.contains(query, true) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(statusBarTopPadding() + 8.dp))
        AuroraTitle(
            text = "对话",
            leading = {
                AuroraCircleButton(
                    icon = AppIcons.GraphicEq,
                    contentDescription = "状态",
                    size = 38.dp,
                    tint = ios.blue,
                    onClick = { viewModel.openStatus(true) },
                )
            },
            trailing = {
                AuroraCircleButton(
                    icon = AppIcons.Add,
                    contentDescription = "新建对话",
                    size = 38.dp,
                    tint = ios.onAccent,
                    onClick = { viewModel.newConversation(ChatMode.AI) },
                    modifier = Modifier.background(ios.blue, CircleShape),
                )
            },
        )
        if (sorted.size >= 5) {
            AuroraSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索对话",
            )
        } else {
            Spacer(Modifier.height(4.dp))
        }

        if (filtered.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AuroraEmpty(
                    icon = AppIcons.ChatBubble,
                    title = if (query.isBlank()) "还没有对话" else "没有找到",
                    message = if (query.isBlank()) "点右上角的 + 开始" else "换个词试试",
                )
            }
        } else {
            LazyColumn(
                state = listState,
                flingBehavior = ScrollPhysics.fling(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Aurora.PagePadding,
                    end = Aurora.PagePadding,
                    top = 4.dp,
                    bottom = 108.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(filtered, key = { _, it -> it.id }) { index, conv ->
                    ConversationCard(
                        conversation = conv,
                        fallback = fallbackPersona,
                        // 错峰入场：列表一次性出现时，逐条轻微上浮比整块硬跳自然得多。
                        // index 在 appearIn 内部被 coerceAtMost(8)，长列表不会越拖越慢。
                        modifier = Modifier.appearIn(index = index),
                        onClick = { viewModel.openConversation(conv.id) },
                        onLongClick = { pendingDelete = conv },
                    )
                }
            }
        }
    }

    pendingDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个对话？", style = IosTheme.typography.headline) },
            text = { Text("聊天记录会一起删掉，不能恢复。", style = IosTheme.typography.subheadline) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(conv.id)
                    pendingDelete = null
                }) { Text("删除", color = ios.red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(
    conversation: Conversation,
    fallback: com.localmind.data.Persona,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ios = IosTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val last = conversation.messages.lastOrNull()
    val preview = when {
        last == null -> "开始聊天"
        last.role == Role.USER -> "你：" + last.content
        else -> last.content
    }.replace('\n', ' ').trim().ifBlank { "…" }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(RoundedCornerShape(18.dp))
            .background(ios.secondaryGroupedBackground)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (conversation.companionMode) {
            PersonaAvatar(
                emoji = conversation.personaAvatarEmoji.ifBlank { fallback.avatarEmoji },
                colorIndex = if (conversation.personaAvatarColor >= 0) conversation.personaAvatarColor else fallback.avatarColor,
                size = 46,
                imagePath = conversation.personaAvatarImage.ifBlank { fallback.avatarImage },
            )
        } else {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(ios.blue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.AutoAwesome,
                    contentDescription = null,
                    tint = ios.blue,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.titleLabel(fallback),
                    style = IosTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                    color = ios.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // fill = false 会让标题只占自己那点宽度，时间就紧贴在标题后面
                    // （"新对话 10:58"）。撑满剩余宽度，时间才会落到卡片右边缘。
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = listTime(conversation.updatedAt),
                    style = IosTheme.typography.caption2,
                    color = ios.tertiaryLabel,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = preview,
                style = IosTheme.typography.footnote,
                color = ios.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (conversation.messages.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = ios.tertiaryLabel,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 一行里显示的对话名：没聊过时显示 AI 的名字，而不是干巴巴的「新对话」。 */
private fun Conversation.titleLabel(fallback: com.localmind.data.Persona): String =
    if (title == Conversation.DEFAULT_TITLE && !companionMode) "新对话"
    else if (title == Conversation.DEFAULT_TITLE) personaName.ifBlank { fallback.name }
    else title

/** 列表时间：今天只显示时分，昨天显示「昨天」，更早显示月日。 */
private fun listTime(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        date == today -> Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        date == today.minusDays(1) -> "昨天"
        date.year == today.year -> Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("M月d日"))
        else -> Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy/M/d"))
    }
}

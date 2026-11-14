package com.localmind.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.data.LayoutAction
import com.localmind.data.LayoutBlock
import com.localmind.data.UiLayout
import com.localmind.ui.AppViewModel
import com.localmind.ui.Tab
import com.localmind.ui.components.pressScale
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.icons.LayoutIcons
import com.localmind.ui.theme.IosTheme

/**
 * 渲染一个由 ui_layout 插件声明的整页。
 *
 * 这一页的排版完全不经过主程序原有代码：插件在 layout.json 的 screens 里写块，
 * 再用 overrides 挂到 chat / models / me / home 上，那个标签就变成插件的样子。
 * 插件仍然只是数据（不执行代码），所以最坏情况是某块不显示。
 */
@Composable
fun PluginScreenPage(
    viewModel: AppViewModel,
    layout: UiLayout,
    screenId: String,
    fallbackTitle: String,
) {
    val base = layout.screens[screenId] ?: return
    var currentId by remember(screenId) { mutableStateOf(screenId) }
    var stack by remember(screenId) { mutableStateOf(listOf<String>()) }
    val current = layout.screens[currentId] ?: base
    val engine by viewModel.engineState.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val context = LocalContext.current

    fun open(target: String) {
        if (target.isBlank()) return
        when {
            target.startsWith("http://") || target.startsWith("https://") -> {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(target))
                    )
                }
            }
            target.startsWith("prompt:") -> {
                viewModel.newConversation(com.localmind.data.ChatMode.AI)
                viewModel.selectTab(Tab.CHAT)
                viewModel.setDraft(target.removePrefix("prompt:"))
            }
            target.startsWith("send:") -> {
                viewModel.newConversation(com.localmind.data.ChatMode.AI)
                viewModel.selectTab(Tab.CHAT)
                viewModel.send(target.removePrefix("send:"))
            }
            target.startsWith("copy:") -> {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("LocalMind", target.removePrefix("copy:")))
                viewModel.toast("已复制")
            }
            target == "new" -> {
                viewModel.newConversation(com.localmind.data.ChatMode.AI)
                viewModel.selectTab(Tab.CHAT)
            }
            target == "settings" -> viewModel.openSettings(true)
            target == "plugins" || target == "plugins_import" -> viewModel.openPlugins(true)
            target == "debug" -> viewModel.openDebug(true)
            target == "back" -> {
                if (stack.isNotEmpty()) {
                    currentId = stack.last()
                    stack = stack.dropLast(1)
                }
            }
            target in layout.screens -> {
                stack = stack + currentId
                currentId = target
            }
            else -> Tab.fromId(target)?.let { viewModel.selectTab(it) }
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) {
        currentId = stack.last()
        stack = stack.dropLast(1)
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(statusBarTopPadding() + 8.dp))
        AuroraTitle(
            text = current.title.ifBlank { fallbackTitle },
            subtitle = current.subtitle.ifBlank { null },
            trailing = if (stack.isNotEmpty()) {
                {
                    AuroraCircleButton(
                        icon = AppIcons.ChevronRight,
                        contentDescription = "返回",
                        onClick = { open("back") },
                    )
                }
            } else null,
        )

        LazyColumn(
            flingBehavior = ScrollPhysics.fling(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 108.dp),
        ) {
            current.blocks.forEach { block ->
                when (block.type) {
                    "hero" -> item { PluginHero(block) }
                    "text" -> item { PluginText(block) }
                    "actions" -> item { PluginActions(block, ::open) }
                    "list" -> item { PluginList(block, ::open) }
                    "recent" -> item {
                        PluginRecent(
                            title = block.title.ifBlank { "最近" },
                            limit = block.limit.coerceIn(1, 8),
                            items = conversations.sortedByDescending { it.updatedAt }
                                .take(block.limit.coerceIn(1, 8))
                                .map { it.id to (it.title.ifBlank { "未命名对话" }) },
                            onOpen = { viewModel.openConversation(it) },
                        )
                    }
                    "status" -> item {
                        PluginStatus(
                            title = block.title.ifBlank { "本机状态" },
                            lines = buildList {
                                add("模型" to if (engine.ready) (engine.modelName ?: "已加载") else "未加载")
                                if (engine.ready) {
                                    add("上下文" to engine.contextSize.toString())
                                    add("速度" to String.format(java.util.Locale.US, "%.1f tok/s", engine.lastTokensPerSecond))
                                }
                                add("对话" to conversations.size.toString() + " 个")
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginHero(block: LayoutBlock) {
    val ios = IosTheme.palette
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .padding(horizontal = Aurora.PagePadding, vertical = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(ios.blue.copy(alpha = if (ios.isDark) 0.22f else 0.10f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuroraIcon(LayoutIcons.of(block.icon) ?: AppIcons.AutoAwesome, ios.blue, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(block.title, style = IosTheme.typography.headline, color = ios.label)
            if (block.text.isNotBlank()) {
                Text(block.text, style = IosTheme.typography.footnote, color = ios.secondaryLabel)
            }
        }
    }
}

@Composable
private fun PluginText(block: LayoutBlock) {
    val ios = IosTheme.palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        if (block.title.isNotBlank()) {
            Text(block.title, style = IosTheme.typography.subheadline.copy(fontWeight = FontWeight.Medium), color = ios.label)
        }
        if (block.text.isNotBlank()) {
            Text(
                block.text,
                style = IosTheme.typography.footnote,
                color = ios.secondaryLabel,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun PluginActions(block: LayoutBlock, onOpen: (String) -> Unit) {
    if (block.items.isEmpty()) return
    if (block.title.isNotBlank()) AuroraSectionLabel(block.title)
    block.items.chunked(2).forEach { row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Aurora.PagePadding, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            row.forEach { item ->
                PluginActionCard(item, Modifier.weight(1f), onOpen)
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun PluginActionCard(item: LayoutAction, modifier: Modifier, onOpen: (String) -> Unit) {
    val ios = IosTheme.palette
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .pressScale(interaction)
            .clip(RoundedCornerShape(18.dp))
            .background(ios.secondaryGroupedBackground)
            .clickable(interactionSource = interaction, indication = null) {
                onOpen(item.open.ifBlank { item.label })
            }
            .padding(14.dp),
    ) {
        AuroraIcon(LayoutIcons.of(item.icon) ?: AppIcons.AutoAwesome, ios.blue, size = 30.dp)
        Spacer(Modifier.height(10.dp))
        Text(item.label, style = IosTheme.typography.subheadline, color = ios.label)
        if (item.text.isNotBlank()) {
            Text(
                item.text,
                style = IosTheme.typography.caption2,
                color = ios.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PluginList(block: LayoutBlock, onOpen: (String) -> Unit) {
    if (block.items.isEmpty()) return
    if (block.title.isNotBlank()) AuroraSectionLabel(block.title)
    AuroraGroup {
        block.items.forEachIndexed { index, item ->
            if (index > 0) AuroraDivider(indent = 58.dp)
            AuroraRow(
                title = item.label,
                subtitle = item.text.ifBlank { null },
                leading = { AuroraIcon(LayoutIcons.of(item.icon) ?: AppIcons.AutoAwesome, IosTheme.palette.blue) },
                onClick = { onOpen(item.open.ifBlank { item.label }) },
            )
        }
    }
}

@Composable
private fun PluginRecent(
    title: String,
    limit: Int,
    items: List<Pair<String, String>>,
    onOpen: (String) -> Unit,
) {
    AuroraSectionLabel(title)
    AuroraGroup {
        if (items.isEmpty()) {
            Text(
                "还没有对话",
                style = IosTheme.typography.footnote,
                color = IosTheme.palette.secondaryLabel,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            items.take(limit).forEachIndexed { index, pair ->
                if (index > 0) AuroraDivider(indent = 16.dp)
                AuroraRow(title = pair.second, onClick = { onOpen(pair.first) })
            }
        }
    }
}

@Composable
private fun PluginStatus(title: String, lines: List<Pair<String, String>>) {
    AuroraSectionLabel(title)
    AuroraGroup {
        lines.forEachIndexed { index, pair ->
            if (index > 0) AuroraDivider(indent = 16.dp)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pair.first,
                    style = IosTheme.typography.subheadline,
                    color = IosTheme.palette.label,
                    modifier = Modifier.width(88.dp),
                )
                Text(
                    pair.second,
                    style = IosTheme.typography.footnote,
                    color = IosTheme.palette.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        }
    }
}

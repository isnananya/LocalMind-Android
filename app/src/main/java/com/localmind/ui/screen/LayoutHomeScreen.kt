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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.data.LayoutAction
import com.localmind.data.LayoutBlock
import com.localmind.data.UiLayout
import com.localmind.ui.AppViewModel
import com.localmind.ui.Tab
import com.localmind.ui.components.IosCard
import com.localmind.ui.components.IosDivider
import com.localmind.ui.components.IosIconBadge
import com.localmind.ui.components.pressScale
import com.localmind.ui.components.IosPill
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

/**
 * 首页：由界面布局插件的 `home` 块拼出来。
 *
 * 没有插件、或者插件没写 `home` 时，退回一份**内置的默认首页** ——
 * 内置那份只做一件事：把用户下一步最可能想去的几个地方摆出来。
 * 它不是"插件没装就空白"，也不是"插件说了算"。
 *
 * 每种块能用到的字段见 [LayoutBlock]；不认识的块在解析阶段就被拒了
 *（见 [com.localmind.data.Layouts.parse]），所以这里可以放心穷举。
 */
@Composable
fun LayoutHomeScreen(viewModel: AppViewModel, layout: UiLayout?) {
    val ios = IosTheme.palette
    val conversations by viewModel.conversations.collectAsState()
    val engine by viewModel.engineState.collectAsState()
    val plugins by viewModel.plugins.collectAsState()
    val breeze by viewModel.breeze.collectAsState()
    val chat by viewModel.chat.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val blocks = layout?.home?.takeIf { it.isNotEmpty() } ?: defaultBlocks(layout)

    Column(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(com.localmind.ui.components.statusBarTopPadding()))
        Text(
            text = layout?.appName?.ifBlank { null } ?: "LocalMind",
            style = IosTheme.typography.largeTitle,
            color = ios.label,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        val subtitle = layout?.subtitle?.ifBlank { null } ?: "全部在本机运行"
        Text(
            text = subtitle,
            style = IosTheme.typography.footnote,
            color = ios.secondaryLabel,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
        )

        blocks.forEach { block ->
            when (block.type) {
                "hero" -> HeroBlock(block)
                "actions" -> ActionsBlock(block) { target -> open(viewModel, target) }
                "text" -> TextBlock(block)
                "recent" -> RecentBlock(
                    title = block.title.ifBlank { "最近" },
                    limit = block.limit.coerceIn(1, 8),
                    items = conversations.take(block.limit.coerceIn(1, 8)).map {
                        it.id to (it.title.ifBlank { "未命名对话" })
                    },
                    onOpen = { id -> viewModel.openConversation(id) },
                )
                "status" -> StatusBlock(
                    title = block.title.ifBlank { "本机状态" },
                    lines = buildList {
                        add(
                            "对话引擎" to if (engine.ready) {
                                "${engine.modelName ?: "已加载"} · ctx ${engine.contextSize} · ${engine.threads} 线程"
                            } else {
                                "未加载"
                            },
                        )
                        if (engine.ready && engine.lastTokensPerSecond > 0) {
                            add("上次速度" to "${"%.1f".format(engine.lastTokensPerSecond)} tok/s")
                        }
                        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                        add(
                            "设备内存" to String.format(
                                "已用 %.1f / %.1f GB",
                                (mi.totalMem - mi.availMem) / 1024.0 / 1024.0 / 1024.0,
                                mi.totalMem / 1024.0 / 1024.0 / 1024.0,
                            ),
                        )
                        add("模型" to (chat.currentModel ?: engine.modelName ?: "未加载"))
                        add(
                            "内部状态" to when {
                                chat.streaming -> "生成中 · " + "%.1f".format(chat.lastTokensPerSecond) + " tok/s"
                                chat.thinking -> "思考中"
                                chat.reasoning -> "推理中"
                                else -> "空闲"
                            },
                        )
                        add("语音引擎" to if (breeze.ready) (breeze.active?.spec?.label ?: "已就绪") else "系统 TTS")
                        add("插件" to "${plugins.count { it.value.isActive }} / ${plugins.size} 个启用")
                    },
                )
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

/** 没装布局插件时的内置首页：四个去处 + 状态。 */
private fun defaultBlocks(layout: UiLayout?): List<LayoutBlock> = listOf(
    LayoutBlock(
        type = "hero",
        title = "开始吧",
        text = layout?.subtitle?.ifBlank { null }
            ?: "模型跑在这台手机上，断网也能用。下面几个入口是常用的。",
        icon = "sparkle",
    ),
    LayoutBlock(
        type = "actions",
        items = listOf(
            LayoutAction("新建对话", "bubble", "new"),
            LayoutAction("模型", "memory", "models"),
            LayoutAction("语音", "voice", "voice"),
            LayoutAction("插件", "extension", "plugins"),
        ),
    ),
    LayoutBlock(type = "recent", title = "最近", limit = 3),
    LayoutBlock(type = "status", title = "本机状态"),
)

private fun open(viewModel: AppViewModel, target: String) {
    when (target) {
        "new" -> {
            viewModel.newConversation(com.localmind.data.ChatMode.COMPANION)
            viewModel.selectTab(Tab.CHAT)
        }
        "settings" -> viewModel.openSettings(true)
        "plugins_import", "plugins" -> viewModel.openPlugins(true)
        else -> Tab.fromId(target)?.let { viewModel.selectTab(it) }
    }
}

@Composable
private fun HeroBlock(block: LayoutBlock) {
    val ios = IosTheme.palette
    IosCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IosIconBadge(icon = iconOf(block.icon, AppIcons.AutoAwesome), tint = ios.blue, size = 34)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(block.title, style = IosTheme.typography.headline, color = ios.label)
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
    }
}

@Composable
private fun TextBlock(block: LayoutBlock) {
    val ios = IosTheme.palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        if (block.title.isNotBlank()) {
            Text(block.title, style = IosTheme.typography.subheadline, color = ios.label)
        }
        if (block.text.isNotBlank()) {
            Text(
                block.text,
                style = IosTheme.typography.footnote,
                color = ios.secondaryLabel,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ActionsBlock(block: LayoutBlock, onOpen: (String) -> Unit) {
    val ios = IosTheme.palette
    if (block.title.isNotBlank()) {
        Text(
            block.title.uppercase(),
            style = IosTheme.typography.footnote,
            color = ios.secondaryLabel,
            modifier = Modifier.padding(start = 32.dp, top = 18.dp, bottom = 6.dp),
        )
    }
    // 两列网格。用 Row 分块而不是 LazyVerticalGrid：块只有几个，
    // 而且首页整体已经在 verticalScroll 里（嵌套可滚动容器会互相抢手势）。
    block.items.chunked(2).forEach { rowItems ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rowItems.forEach { item ->
                val interaction = androidx.compose.runtime.remember {
                    androidx.compose.foundation.interaction.MutableInteractionSource()
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .pressScale(interaction)
                        .clip(RoundedCornerShape(IosTheme.metrics.cardCorner))
                        .background(ios.secondaryGroupedBackground)
                        .clickable(interactionSource = interaction, indication = null) {
                            onOpen(item.open.ifBlank { item.label })
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    IosIconBadge(icon = iconOf(item.icon, AppIcons.AutoAwesome), tint = ios.blue, size = 30)
                    Spacer(Modifier.height(8.dp))
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
            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RecentBlock(
    title: String,
    limit: Int,
    items: List<Pair<String, String>>,
    onOpen: (String) -> Unit,
) {
    val ios = IosTheme.palette
    Text(
        title.uppercase(),
        style = IosTheme.typography.footnote,
        color = ios.secondaryLabel,
        modifier = Modifier.padding(start = 32.dp, top = 18.dp, bottom = 6.dp),
    )
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(IosTheme.metrics.cardCorner))
            .background(ios.secondaryGroupedBackground),
    ) {
        if (items.isEmpty()) {
            Text(
                "还没有对话",
                style = IosTheme.typography.footnote,
                color = ios.secondaryLabel,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            items.forEachIndexed { i, (id, name) ->
                if (i > 0) IosDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        style = IosTheme.typography.body,
                        color = ios.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("›", style = IosTheme.typography.body, color = ios.tertiaryLabel)
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(title: String, lines: List<Pair<String, String>>) {
    val ios = IosTheme.palette
    Text(
        title.uppercase(),
        style = IosTheme.typography.footnote,
        color = ios.secondaryLabel,
        modifier = Modifier.padding(start = 32.dp, top = 18.dp, bottom = 6.dp),
    )
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(IosTheme.metrics.cardCorner))
            .background(ios.secondaryGroupedBackground),
    ) {
        lines.forEachIndexed { i, (k, v) ->
            if (i > 0) IosDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 名字给固定宽度，值吃掉剩下的 —— 否则「对话引擎」会和
                // "qwen2.5-0.5b-instruct-q4_k_m · ctx 30464" 挤在一起（实测重叠）。
                Text(
                    k,
                    style = IosTheme.typography.body,
                    color = ios.label,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(88.dp),
                )
                Text(
                    v,
                    style = IosTheme.typography.footnote,
                    color = ios.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 布局插件里的图标 id → 内置图标。认不出的回退到 [fallback]。 */
private fun iconOf(id: String, fallback: ImageVector): ImageVector = when (id) {
    "chat", "bubble" -> AppIcons.ChatBubble
    "models", "memory" -> AppIcons.Memory
    "me", "person" -> AppIcons.Person
    "home" -> AppIcons.AutoAwesome
    "voice", "mic" -> AppIcons.GraphicEq
    "breeze" -> AppIcons.RecordVoiceOver
    "plugins", "extension" -> AppIcons.Extension
    "image" -> AppIcons.Image
    "sparkle" -> AppIcons.AutoAwesome
    "cloud" -> AppIcons.Cloud
    "bolt" -> AppIcons.Bolt
    "volume" -> AppIcons.VolumeUp
    "search" -> AppIcons.Search
    "settings", "tune" -> AppIcons.Tune
    else -> fallback
}

package com.localmind.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localmind.data.UiPresets
import com.localmind.service.ThemeStoreService
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosSlider
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.components.IosTag
import com.localmind.ui.design.Aurora
import com.localmind.ui.design.AuroraCircleButton
import com.localmind.ui.design.AuroraDivider
import com.localmind.ui.design.AuroraGroup
import com.localmind.ui.design.AuroraNavBar
import com.localmind.ui.design.AuroraRow
import com.localmind.ui.design.AuroraSectionLabel
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.motion.ScrollPhysics
import com.localmind.ui.theme.IosTheme
import com.localmind.ui.theme.rememberFontPreview
import com.localmind.ui.theme.rememberThemePreview
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 外观。
 *
 * ## 这一版的布局换了思路
 *
 * 1.0.11 之前这里是一长串**设置项**：底栏宽度、选中态、模糊半径、高光强度……
 * 用户想"看看我装了什么"，得在十几个滑杆里翻，而真正高频的操作其实是
 * "把已下载的那套换回来"。
 *
 * 所以现在第一屏是**索引**：主题 / 字体 / 气泡 / 布局四行，每行显示**已下载
 * 多少、正在用哪个**，点进去就是这个分类下已经下载到本机的内容，可以直接
 * 应用或删除。底栏那些微调放在下面，需要的人往下滑。
 *
 * 四类内容的数据来源不一样（主题是 pack 目录、字体是文件、气泡写进覆盖、
 * 布局是 layout.json），但这个差别**不该让用户看到** —— 他们只会想"我要换
 * 回之前那个"，不该关心它在磁盘上是什么形态。
 */
@Composable
fun ThemeScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenStore: () -> Unit = {},
) {
    val ios = IosTheme.palette
    val activeTheme by viewModel.activeUiTheme.collectAsState()
    val overrides by viewModel.themeOverrides.collectAsState()
    val layouts by viewModel.installedLayouts.collectAsState()
    val currentLayout by viewModel.uiLayout.collectAsState()
    val plugins by viewModel.plugins.collectAsState()
    val tokens = com.localmind.ui.theme.LocalUiTokens.current
    val themeMode by viewModel.themeMode.collectAsState()

    val barModule = plugins.values.firstOrNull {
        it.manifest.abilities.contains("glass_bar") && it.isActive
    }
    val activePreset = com.localmind.data.UiPresets.match(overrides)

    // 已下载内容的数据源，四个都要在组合期读一次 —— 表格上面要显示数量。
    val cachedIds by viewModel.themeCachedIds.collectAsState()
    val installedThemes by viewModel.installedUiThemes.collectAsState()
    val storeThemes by viewModel.themeStore.themes.collectAsState()
    val fonts by viewModel.fontStore.fonts.collectAsState()
    val activeFontId by viewModel.activeFontId.collectAsState()
    val cloudBubbles by viewModel.bubbleStore.cloudBubbles.collectAsState()
    val currentBubble = com.localmind.data.UiPresets.matchBubble(overrides)

    // 已下载字体 id 通过 FontRegistry 直接问磁盘。
    //
    // `fontRescan` 是**手动失效信号**：删掉一个字体后，`fonts` 清单本身没变
    // （清单是云端的目录，和本地有没有下载无关），只靠 key 里那两个值不会重算，
    // 界面就会留着一个已经删掉的条目。删完把它 +1，强制重新扫一遍磁盘。
    var fontRescan by remember { mutableStateOf(0) }
    val installedFontIds = remember(fonts, fontRescan) { viewModel.fontStore.installedIds() }

    LaunchedEffect(Unit) { viewModel.refreshThemeCache() }

    var sheet by remember { mutableStateOf(DownloadedKind.NONE) }

    Column(Modifier.fillMaxSize()) {
        AuroraNavBar(title = "外观", onBack = onBack)

        LazyColumn(
            flingBehavior = ScrollPhysics.fling(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                AuroraSectionLabel("已下载内容")
                AuroraGroup {
                    DownloadedRow(
                        title = "主题",
                        subtitle = buildString {
                            append(installedThemes.size).append(" 款已安装")
                            if (cachedIds.isNotEmpty()) append(" · ").append(cachedIds.size).append(" 个安装包")
                            append(" · 当前 ")
                            append(activeTheme?.packName ?: activePreset?.name ?: "默认外观")
                        },
                        onClick = { sheet = DownloadedKind.THEME },
                    )
                    AuroraDivider()
                    DownloadedRow(
                        title = "字体",
                        subtitle = if (installedFontIds.isEmpty()) {
                            "还没下载字体"
                        } else {
                            val name = fonts.firstOrNull { it.id == activeFontId }?.name
                            "${installedFontIds.size} 款已下载" +
                                if (!name.isNullOrBlank()) " · 当前 $name" else ""
                        },
                        onClick = { sheet = DownloadedKind.FONT },
                    )
                    AuroraDivider()
                    DownloadedRow(
                        title = "气泡样式",
                        subtitle = (cloudBubbles.size + UiPresets.BUBBLES.size).toString() +
                            " 款可选 · 当前 " + (currentBubble?.name ?: "跟随主题"),
                        onClick = { sheet = DownloadedKind.BUBBLE },
                    )
                    AuroraDivider()
                    DownloadedRow(
                        title = "界面布局",
                        subtitle = layouts.size.toString() + " 个已安装 · 当前 " +
                            (currentLayout?.packName ?: "默认布局"),
                        onClick = { sheet = DownloadedKind.LAYOUT },
                    )
                }
            }

            item {
                AuroraSectionLabel("深浅色")
                AuroraGroup {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("模式", style = IosTheme.typography.body, color = ios.label, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                                IosTag(label, themeMode == mode) { viewModel.setThemeMode(mode) }
                            }
                        }
                    }
                    AuroraDivider(indent = 16.dp)
                    AuroraRow(
                        title = "当前生效",
                        subtitle = when {
                            activeTheme != null -> activeTheme!!.packName
                            activePreset != null -> "预设 · " + activePreset!!.name
                            else -> "默认外观"
                        },
                        showChevron = false,
                    )
                }
            }

            item {
                AuroraSectionLabel("底栏")
                AuroraGroup {
                    ChoiceRow(
                        title = "宽度",
                        options = listOf("compact" to "悬浮胶囊", "full" to "通栏"),
                        selected = tokens["tabBarWidth"] ?: "compact",
                        onPick = { viewModel.setThemeOverride("tabBarWidth", it) },
                    )
                    ChoiceRow(
                        title = "选中态",
                        options = listOf(
                            "capsule" to "圆形",
                            "underline" to "下划线",
                            "dot" to "圆点",
                            "none" to "无",
                        ),
                        selected = tokens["tabBarIndicator"] ?: "capsule",
                        onPick = { viewModel.setThemeOverride("tabBarIndicator", it) },
                    )
                    ChoiceRow(
                        title = "底栏实现",
                        options = listOf(
                            "liquidTabBar" to "液态玻璃",
                            "liquidDock" to "自定义液态",
                        ),
                        selected = tokens["dockImpl"] ?: "liquidTabBar",
                        onPick = { viewModel.setThemeOverride("dockImpl", it) },
                    )
                }
            }

            item {
                val tabStyle = tokens["tabStyle"] ?: "glass"
                val plain = tabStyle == "plain"
                val hidden = tabStyle == "hidden"
                fun n(key: String, def: Float) = tokens[key]?.toFloatOrNull() ?: def
                fun onOff(key: String, def: Boolean) =
                    (tokens[key] ?: def.toString()).toBooleanStrictOrNull() ?: def
                fun setStyle(plainOn: Boolean? = null, hiddenOn: Boolean? = null) {
                    val p = plainOn ?: plain
                    val h = hiddenOn ?: hidden
                    viewModel.setThemeOverride(
                        "tabStyle",
                        when {
                            h -> "hidden"
                            p -> "plain"
                            else -> "glass"
                        },
                    )
                }

                val outlineOn = onOff("glassBarOutline", true)
                val bottomBlurOn = onOff("glassBarBottomBlur", false)
                val iconOnly = onOff("glassBarIconOnly", false)
                val dynamicOn = onOff("dynamicColor", false)

                AuroraSectionLabel("液态底栏")
                AuroraGroup {
                    AuroraRow(
                        title = "液态底栏",
                        subtitle = if (plain) {
                            "关闭：纯色底栏（无玻璃与折射）"
                        } else {
                            "液态玻璃：折射 + 色散 + 按压放大"
                        },
                        showChevron = false,
                        trailing = {
                            IosSwitch(
                                checked = !plain,
                                onCheckedChange = { setStyle(plainOn = !it, hiddenOn = false) },
                            )
                        },
                        onClick = { setStyle(plainOn = plain, hiddenOn = false) },
                    )
                    AuroraDivider()
                    AuroraRow(
                        title = "底部渐变模糊",
                        subtitle = "底栏上方页面底部的渐隐磨砂",
                        showChevron = false,
                        trailing = {
                            IosSwitch(
                                checked = bottomBlurOn && !plain,
                                onCheckedChange = { viewModel.setThemeOverride("glassBarBottomBlur", it.toString()) },
                                enabled = !plain,
                            )
                        },
                        onClick = if (plain) null else ({
                            viewModel.setThemeOverride("glassBarBottomBlur", (!bottomBlurOn).toString())
                        }),
                    )
                    AuroraDivider()
                    AuroraRow(
                        title = "描边",
                        subtitle = "1dp 高光描边，强度可调",
                        showChevron = false,
                        trailing = {
                            IosSwitch(
                                checked = outlineOn && !plain,
                                onCheckedChange = { viewModel.setThemeOverride("glassBarOutline", it.toString()) },
                                enabled = !plain,
                            )
                        },
                        onClick = if (plain) null else ({
                            viewModel.setThemeOverride("glassBarOutline", (!outlineOn).toString())
                        }),
                    )
                    AuroraDivider()
                    AuroraRow(
                        title = "动态配色",
                        subtitle = "从系统壁纸取主色，改的是整个应用的主色",
                        showChevron = false,
                        trailing = {
                            IosSwitch(
                                checked = dynamicOn,
                                onCheckedChange = { viewModel.setThemeOverride("dynamicColor", it.toString()) },
                            )
                        },
                        onClick = { viewModel.setThemeOverride("dynamicColor", (!dynamicOn).toString()) },
                    )
                    AuroraDivider()
                    GlassSliderRow(
                        title = "底栏模糊半径",
                        value = n("tabBarBlur", 10f),
                        range = 0f..16f,
                        unit = "dp",
                        enabled = !plain,
                    ) { viewModel.setThemeOverride("tabBarBlur", it.roundToInt().toString()) }
                    AuroraDivider()
                    GlassSliderRow(
                        title = "高光强度",
                        value = n("glassBarHighlight", 0.5f),
                        range = 0.5f..1.7f,
                        decimals = 1,
                        enabled = !plain,
                    ) { viewModel.setThemeOverride("glassBarHighlight", fmtDecimals(it, 1)) }
                    AuroraDivider()
                    GlassSliderRow(
                        title = "底栏高度",
                        value = n("tabBarHeight", 56f),
                        range = 44f..72f,
                        unit = "dp",
                    ) { viewModel.setThemeOverride("tabBarHeight", it.roundToInt().toString()) }
                    AuroraDivider()
                    GlassSliderRow(
                        title = "单栏宽度",
                        value = n("glassBarSlotWidth", 76f),
                        range = 56f..112f,
                        unit = "dp",
                    ) { viewModel.setThemeOverride("glassBarSlotWidth", it.roundToInt().toString()) }
                    AuroraDivider()
                    GlassSliderRow(
                        title = "距底距离",
                        value = n("tabBarLift", 12f),
                        range = 0f..40f,
                        unit = "dp",
                    ) { viewModel.setThemeOverride("tabBarLift", it.roundToInt().toString()) }
                    AuroraDivider()
                    AuroraRow(
                        title = "仅图标",
                        subtitle = "不画文字，胶囊自动更紧凑",
                        showChevron = false,
                        trailing = {
                            IosSwitch(
                                checked = iconOnly,
                                onCheckedChange = { viewModel.setThemeOverride("glassBarIconOnly", it.toString()) },
                            )
                        },
                        onClick = {
                            viewModel.setThemeOverride("glassBarIconOnly", (!iconOnly).toString())
                        },
                    )
                    AuroraDivider()
                    GlassSliderRow(
                        title = "文字大小",
                        value = n("glassBarLabelSize", 11f),
                        range = 9f..14f,
                        unit = "sp",
                        enabled = !iconOnly,
                    ) { viewModel.setThemeOverride("glassBarLabelSize", it.roundToInt().toString()) }
                    AuroraDivider()
                    GlassSliderRow(
                        title = "图标大小",
                        value = n("tabIconSize", 23f),
                        range = 18f..30f,
                        unit = "dp",
                    ) { viewModel.setThemeOverride("tabIconSize", it.roundToInt().toString()) }
                    AuroraDivider()
                    AuroraRow(
                        title = "隐藏底栏",
                        subtitle = "完全收起底栏；子页会自动收起",
                        showChevron = false,
                        trailing = {
                            IosSwitch(
                                checked = hidden,
                                onCheckedChange = { setStyle(hiddenOn = it, plainOn = if (it) false else plain) },
                            )
                        },
                        onClick = { setStyle(hiddenOn = !hidden, plainOn = if (!hidden) false else plain) },
                    )
                }
            }

            // ---- 原来这里有一排「风格」预设卡（大地/极光/纸感/夜间/二次元/QQ经典）----
            //
            // 1.0.11 删掉了：这些预设和主题商店里的包重复（极光夜航、QQ经典蓝、
            // 樱雪物语……本来就是同一套令牌的另一份拷贝），两处都能换主题的结果是
            // 用户分不清"我现在用的到底是哪个"，改了一边另一边还对不上。
            // 现在换外观只有商店一个入口 —— 预览图更大、有壁纸、还能装插件带来的主题。
            // 预设定义（[com.localmind.data.UiPresets]）仍然留着：老用户手机上已经
            // 应用的覆盖值还在，上面「当前生效」要用它来显示名字。

            item {
                AuroraSectionLabel("更多")
                AuroraGroup {
                    AuroraRow(
                        title = "主题商店",
                        subtitle = "挑选并一键应用界面主题、字体、气泡",
                        onClick = onOpenStore,
                    )
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                AuroraCircleButton(
                    icon = AppIcons.Refresh,
                    contentDescription = "恢复默认外观",
                    onClick = { viewModel.clearThemeOverrides() },
                    modifier = Modifier.padding(horizontal = Aurora.PagePadding),
                )
                Text(
                    "恢复默认外观",
                    style = IosTheme.typography.caption1,
                    color = ios.secondaryLabel,
                    modifier = Modifier.padding(start = Aurora.PagePadding + 2.dp, top = 6.dp),
                )
            }
        }
    }

    when (sheet) {
        DownloadedKind.THEME -> DownloadedSheet(
            title = "已下载主题",
            empty = "还没有安装任何主题。去主题商店挑一款？",
            count = installedThemes.size,
            onClose = { sheet = DownloadedKind.NONE },
        ) {
            items(installedThemes.values.sortedBy { it.packName }, key = { it.packId }) { theme ->
                val entry = storeThemes.firstOrNull { it.id == theme.packId }
                ThemeItemRow(
                    theme = theme,
                    entry = entry,
                    active = activeTheme?.packId == theme.packId,
                    canDelete = entry != null && cachedIds.contains(entry.id),
                    onApply = { viewModel.useUiTheme(theme) },
                    onDelete = entry?.let { { viewModel.deleteCachedTheme(it) } },
                )
            }
        }

        DownloadedKind.FONT -> DownloadedSheet(
            title = "已下载字体",
            empty = "还没有下载字体。去主题商店的「字体」栏看看？",
            count = installedFontIds.size,
            onClose = { sheet = DownloadedKind.NONE },
        ) {
            items(fonts.filter { installedFontIds.contains(it.id) }, key = { it.id }) { f ->
                FontItemRow(
                    id = f.id,
                    name = f.name,
                    style = f.style.ifBlank { f.author },
                    sample = f.sample.ifBlank { "你好，世界" },
                    previewUrls = f.previewUrls,
                    active = activeFontId == f.id,
                    onApply = { viewModel.applyFontId(f.id, f.name) },
                    onDelete = {
                        viewModel.deleteFont(f)
                        fontRescan++
                    },
                )
            }
        }

        DownloadedKind.BUBBLE -> DownloadedSheet(
            title = "气泡样式",
            empty = "暂时没有气泡样式。",
            count = cloudBubbles.size + UiPresets.BUBBLES.size,
            onClose = { sheet = DownloadedKind.NONE },
        ) {
            // 云端在前：云端那份是"升级版"，和商店里的顺序保持一致，
            // 否则同一款气泡在两处排的位置不同，用户会以为是两个东西。
            val seen = HashSet<String>()
            val list = (cloudBubbles + UiPresets.BUBBLES).filter { seen.add(it.name) }
            items(list, key = { it.id }) { p ->
                SimpleItemRow(
                    title = p.name,
                    subtitle = p.description,
                    selected = currentBubble?.id == p.id,
                    onApply = { viewModel.applyBubblePresetCloud(p) },
                )
            }
        }

        DownloadedKind.LAYOUT -> DownloadedSheet(
            title = "界面布局",
            empty = "还没有装布局包。",
            count = layouts.size + 1,
            onClose = { sheet = DownloadedKind.NONE },
        ) {
            item {
                SimpleItemRow(
                    title = "默认布局",
                    subtitle = "对话 / 模型 / 我的",
                    selected = currentLayout == null,
                    onApply = { viewModel.useLayout("") },
                )
            }
            items(layouts.values.sortedBy { it.packName }, key = { it.packId }) { l ->
                SimpleItemRow(
                    title = l.packName,
                    subtitle = l.tabs.size.toString() + " 个入口" +
                        if (l.home.isNotEmpty()) " · " + l.home.size + " 块首页" else "",
                    selected = currentLayout?.packId == l.packId,
                    onApply = { viewModel.useLayout(l.packId) },
                )
            }
        }

        DownloadedKind.NONE -> Unit
    }
}

/** 外观页能"点进去看"的四类内容。 */
private enum class DownloadedKind { NONE, THEME, FONT, BUBBLE, LAYOUT }

/** 索引行：标题 + 说明 + 箭头。 */
@Composable
private fun DownloadedRow(title: String, subtitle: String, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = IosTheme.typography.body, color = ios.label)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = IosTheme.typography.caption1,
                color = ios.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", style = IosTheme.typography.title3, color = ios.tertiaryLabel)
    }
}

/**
 * 已下载内容的弹出层。
 *
 * 用 Dialog 而不是新开一个页面：这些内容是**外观页的一个分支**，用户看完
 * 就要回来接着调，跳页面会把导航栈弄复杂（返回键该回外观还是回主界面？）。
 *
 * 内容用 [LazyListScope] 而不是一个 `Column`：字体现在有 193 款，全塞进一个
 * `item {}` 里就是一次性合成 193 行 + 发起 193 张图，弹层会明显卡住。
 */
@Composable
private fun DownloadedSheet(
    title: String,
    empty: String,
    count: Int,
    onClose: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val ios = IosTheme.palette
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = ios.secondaryGroupedBackground,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 620.dp)
                .padding(vertical = 12.dp),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = IosTheme.typography.title3,
                        color = ios.label,
                        modifier = Modifier.weight(1f),
                    )
                    if (count > 0) {
                        Text(
                            count.toString(),
                            style = IosTheme.typography.caption1,
                            color = ios.secondaryLabel,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "关闭",
                        style = IosTheme.typography.subheadline,
                        color = ios.blue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onClose)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                if (count == 0) {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        Text(
                            empty,
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) { content() }
                }
            }
        }
    }
}

/** 主题一行：缩略图 + 名字 + 使用/删除。 */
@Composable
private fun ThemeItemRow(
    theme: com.localmind.data.UiTheme,
    entry: ThemeStoreService.ThemeEntry?,
    active: Boolean,
    canDelete: Boolean,
    onApply: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val ios = IosTheme.palette
    val preview = entry?.let { rememberThemePreview(it, 64.dp) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 64.dp, height = 46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ios.fillSecondary),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = preview?.value
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp,
                    contentDescription = theme.packName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    theme.packName.take(1),
                    style = IosTheme.typography.title3,
                    color = ios.secondaryLabel,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(theme.packName, style = IosTheme.typography.body, color = ios.label, maxLines = 1)
            Text(
                buildString {
                    if (theme.author.isNotBlank()) append(theme.author)
                    if (theme.version > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("v").append(theme.version)
                    }
                }.ifBlank { "主题包" },
                style = IosTheme.typography.caption1,
                color = ios.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canDelete && onDelete != null) {
            MiniAction("删除", danger = true, onClick = onDelete)
            Spacer(Modifier.width(6.dp))
        }
        if (active) {
            Text("✓", style = IosTheme.typography.subheadline, color = ios.blue, fontWeight = FontWeight.SemiBold)
        } else {
            MiniAction("使用", onClick = onApply)
        }
    }
}

/** 字体一行：样张缩略 + 名字 + 使用/删除。 */
@Composable
private fun FontItemRow(
    id: String,
    name: String,
    style: String,
    sample: String,
    previewUrls: List<String>,
    active: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    val ios = IosTheme.palette
    val preview = rememberFontPreview(id, previewUrls, 76.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 76.dp, height = 48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ios.fillSecondary),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = preview.value
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(sample.take(3), style = IosTheme.typography.footnote, color = ios.secondaryLabel)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = IosTheme.typography.body, color = ios.label, maxLines = 1)
            Text(
                style.ifBlank { "字体" },
                style = IosTheme.typography.caption1,
                color = ios.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MiniAction("删除", danger = true, onClick = onDelete)
        Spacer(Modifier.width(6.dp))
        if (active) {
            Text("✓", style = IosTheme.typography.subheadline, color = ios.blue, fontWeight = FontWeight.SemiBold)
        } else {
            MiniAction("使用", onClick = onApply)
        }
    }
}

/** 纯文本行（气泡 / 布局）。 */
@Composable
private fun SimpleItemRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onApply: () -> Unit,
) {
    val ios = IosTheme.palette
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = IosTheme.typography.body, color = ios.label)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = IosTheme.typography.caption1,
                    color = ios.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Text("✓", style = IosTheme.typography.subheadline, color = ios.blue, fontWeight = FontWeight.SemiBold)
        } else {
            MiniAction("使用", onClick = onApply)
        }
    }
}

@Composable
private fun MiniAction(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Text(
        label,
        style = IosTheme.typography.caption1,
        color = if (danger) ios.red else ios.blue,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (danger) ios.fillSecondary else ios.fillSecondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
) {
    val ios = IosTheme.palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title, style = IosTheme.typography.subheadline, color = ios.label)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val on = value == selected
                Text(
                    label,
                    style = IosTheme.typography.caption1,
                    color = if (on) ios.onAccent else ios.label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (on) ios.blue else ios.fillSecondary)
                        .clickable { onPick(value) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun GlassSliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = "",
    decimals: Int = 0,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    val ios = IosTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = IosTheme.typography.body,
                color = if (enabled) ios.label else ios.tertiaryLabel,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = fmtDecimals(value, decimals) + if (unit.isBlank()) "" else " " + unit,
                style = IosTheme.typography.footnote,
                color = ios.secondaryLabel,
            )
        }
        IosSlider(
            value = value,
            onValueChange = { raw ->
                val q = if (decimals == 0) raw.roundToInt().toFloat()
                else String.format(Locale.US, "%." + decimals + "f", raw).toFloat()
                if (q != value) onChange(q)
            },
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun fmtDecimals(v: Float, decimals: Int): String =
    if (decimals == 0) v.roundToInt().toString()
    else String.format(Locale.US, "%." + decimals + "f", v)

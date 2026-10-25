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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.localmind.data.UiTheme
import com.localmind.data.UiThemes
import com.localmind.data.UiTokens
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosCard
import com.localmind.ui.components.IosDivider
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.scrollBottomPadding
import com.localmind.ui.theme.IosTheme
import com.localmind.ui.theme.parseHexColor
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.motion.appearIn

/**
 * 主题设置。
 *
 * ## 它做什么
 *
 *  1. **内置预设**：点一下就换整套观感 —— 这是"想改风格"的人最快的路径，
 *     不需要写 JSON、不需要压 zip、不需要装插件。
 *  2. **插件主题**：已安装的界面插件，在它们之间切。
 *  3. **看清到底改了什么**：列出解析后的令牌（含颜色色块），以及不认识的键。
 *
 * 切换是立即生效的：令牌来自 `AppViewModel.uiThemeState`（StateFlow），
 * 这一页自己也会跟着变样。
 *
 * ## 为什么现在有"自定义"了
 *
 * 上一版这里写着"不做调色板界面" —— 理由是手机上调色通常做出来更难看。
 * 那个理由只对了一半：真正的问题是**预设只有"内置"一个**，用户看完
 * 一圈插件主题之后没有任何可以立刻动手改的地方，"改风格"这件事就还是空话。
 *
 * 所以现在的分工是：
 *  * 预设负责"好看"（作者配好的整套配色，几个键一起改，不会配歪）；
 *  * 单项覆盖负责"就这一处我不喜欢"（改主色/圆角/字号，不动其它）。
 *
 * 两者写进的是同一份 `themeOverrides`，所以它们可以叠加，
 * 也不会和插件主题打架（用户覆盖优先级最高，见 `UiThemes.resolve`）。
 */
@Composable
fun ThemeScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val ios = IosTheme.palette
    val metrics = IosTheme.metrics
    val themes by viewModel.installedUiThemes.collectAsState()
    val active by viewModel.activeUiTheme.collectAsState()
    val overrides by viewModel.themeOverrides.collectAsState()
    val activePreset = remember(overrides) { com.localmind.data.UiPresets.match(overrides) }
    var editing by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
    ) {
        IosNavigationBar(
            title = "主题",
            collapsed = true,
            topInset = statusBarTopPaddingSafe(),
            leading = {
                Icon(
                    AppIcons.ArrowBack,
                    contentDescription = "返回",
                    tint = ios.blue,
                    modifier = Modifier
                        .size(23.dp)
                        .clickable(onClick = onBack),
                )
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 4.dp,
                bottom = scrollBottomPadding(tabBarVisible = true, base = 24.dp),
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // ---- 界面布局（整套 UI，而不只是配色）----
            //
            // 用户的原话：「主题设置插件主题就是说这个 ui 整体的更改，而不是这种色调等的小更改」。
            // 所以这一栏放在最上面：它换的是**导航结构和首页组成**，颜色只是附带的。
            item {
                Column {
                    val layouts by viewModel.installedLayouts.collectAsState()
                    val currentLayout by viewModel.uiLayout.collectAsState()
                    SectionHeader(
                        "界面布局",
                        "换的是整个应用的结构：底栏有哪几个入口、每个入口进哪一页、首页由哪些块组成。" +
                            "布局插件也能顺带带一套配色。",
                    )
                    Spacer(Modifier.height(10.dp))
                    IosCard(padding = PaddingValues(0.dp)) {
                        LayoutChoiceRow(
                            title = "默认布局",
                            subtitle = "对话 / 模型 / 我的，三个入口",
                            selected = currentLayout == null,
                            onClick = { viewModel.useLayout("") },
                        )
                        layouts.values.sortedBy { it.packName }.forEach { l ->
                            IosDivider()
                            LayoutChoiceRow(
                                title = l.packName,
                                subtitle = buildString {
                                    append("${l.tabs.size} 个入口")
                                    if (l.home.isNotEmpty()) append(" · ${l.home.size} 块首页")
                                    if (l.description.isNotBlank()) append(" · ${l.description.take(40)}")
                                },
                                selected = currentLayout?.packId == l.packId,
                                onClick = { viewModel.useLayout(l.packId) },
                            )
                        }
                    }
                    if (layouts.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "还没有装界面布局插件。想要卡片式首页 / 换掉底栏入口，去「我的 → 插件」导入。",
                            style = IosTheme.typography.caption1,
                            color = ios.tertiaryLabel,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }

            // ---- 快捷风格：2×2 预览网格 ----
            //
            // 上一版这里是一列「色块 + 名称 + 说明 + 勾」的大行，四个预设就是四行，
            // 而色块只是四根竖条纹 —— 既占地方又看不出风格。现在改成真正画出
            // 一小段界面的预览格子：标题条、卡片、主色按钮、两个气泡。
            // 五个元素就把一套配色的性格交代清楚了。
            item {
                Column {
                    SectionHeader("快捷风格", "点一下换整套配色，不用装任何插件。")
                    Spacer(Modifier.height(10.dp))

                    val rows = com.localmind.data.UiPresets.ALL.chunked(2)
                    rows.forEachIndexed { rowIndex, pair ->
                        if (rowIndex > 0) Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEachIndexed { i, preset ->
                                PresetCard(
                                    preset = preset,
                                    selected = activePreset?.id == preset.id,
                                    index = rowIndex * 2 + i,
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.applyUiPreset(preset) },
                                )
                            }
                            // 奇数个预设时补一个空位，避免最后一个被拉成整行宽。
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    ResetRow(
                        enabled = overrides.isNotEmpty(),
                        onClick = { viewModel.clearThemeOverrides() },
                    )
                }
            }

            // ---- 微调 ----
            item {
                Column {
                    SectionHeader("微调", "改一项只影响一项，其余继续跟随当前风格。")
                    Spacer(Modifier.height(10.dp))
                    IosCard(padding = PaddingValues(0.dp)) {
                        TunerRow(
                            title = "主色",
                            value = tunerValue("accent", overrides),
                            options = ACCENT_CHOICES,
                            onPick = { viewModel.setThemeOverride("accent", it) },
                            onClear = { viewModel.setThemeOverride("accent", null) },
                        )
                        IosDivider()
                        TunerRow(
                            title = "圆角",
                            value = tunerValue("cardCorner", overrides),
                            options = listOf(
                                "0" to "直角",
                                "8" to "小",
                                "12" to "标准",
                                "20" to "大",
                            ),
                            onPick = { v ->
                                viewModel.setThemeOverride("cardCorner", v)
                                viewModel.setThemeOverride("controlCorner", v)
                                viewModel.setThemeOverride("bubbleCorner", v)
                            },
                            onClear = {
                                viewModel.setThemeOverride("cardCorner", null)
                                viewModel.setThemeOverride("controlCorner", null)
                                viewModel.setThemeOverride("bubbleCorner", null)
                            },
                        )
                        IosDivider()
                        TunerRow(
                            title = "字号",
                            value = tunerValue("fontScale", overrides),
                            options = listOf(
                                "0.9" to "小",
                                "1.0" to "标准",
                                "1.15" to "大",
                                "1.3" to "很大",
                            ),
                            onPick = { viewModel.setThemeOverride("fontScale", it) },
                            onClear = { viewModel.setThemeOverride("fontScale", null) },
                        )
                        IosDivider()
                        TunerRow(
                            title = "标签栏",
                            value = tunerValue("tabStyle", overrides),
                            options = listOf("glass" to "毛玻璃", "plain" to "不透明", "hidden" to "藏"),
                            onPick = { viewModel.setThemeOverride("tabStyle", it) },
                            onClear = { viewModel.setThemeOverride("tabStyle", null) },
                        )
                        IosDivider()
                        TunerRow(
                            title = "标签文字",
                            value = tunerValue("showTabBarLabels", overrides),
                            options = listOf("true" to "显示", "false" to "只要图标"),
                            onPick = { viewModel.setThemeOverride("showTabBarLabels", it) },
                            onClear = { viewModel.setThemeOverride("showTabBarLabels", null) },
                        )
                        IosDivider()
                        TunerRow(
                            title = "标题位置",
                            value = tunerValue("navTitleAlign", overrides),
                            options = listOf("center" to "居中", "leading" to "靠左"),
                            onPick = { viewModel.setThemeOverride("navTitleAlign", it) },
                            onClear = { viewModel.setThemeOverride("navTitleAlign", null) },
                        )
                    }
                }
            }

            // ---- 插件主题 ----
            item {
                Column {
                    SectionHeader(
                        "插件主题",
                        if (overrides.isNotEmpty()) {
                            "你的手动覆盖会盖过插件主题里的同名项。"
                        } else {
                            "主题也可以由插件提供，装新的去「我的 → 插件」。"
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    IosCard(padding = PaddingValues(0.dp)) {
                        BuiltinThemeRow(
                            selected = active == null,
                            onClick = { viewModel.useBuiltinTheme() },
                        )
                        themes.values.sortedBy { it.packName }.forEach { theme ->
                            IosDivider()
                            ThemeRow(
                                theme = theme,
                                selected = active?.packId == theme.packId,
                                index = 0,
                                onClick = { viewModel.useUiTheme(theme) },
                            )
                        }
                    }
                    if (themes.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "还没有装界面主题插件。上面四个预设不需要插件就能用。",
                            style = IosTheme.typography.caption1,
                            color = ios.tertiaryLabel,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }

            // ---- 正在生效的手动覆盖 ----
            if (overrides.isNotEmpty()) {
                item {
                    Column {
                        SectionHeader("正在生效的覆盖", "${overrides.size} 项")
                        Spacer(Modifier.height(10.dp))
                        IosCard(padding = PaddingValues(0.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                overrides.entries.sortedBy { it.key }.forEach { (rawKey, v) ->
                                    val darkOnly = UiThemes.isDarkOnly(rawKey)
                                    val key = UiThemes.plainKey(rawKey)
                                    val token = UiTokens.byKey[key]
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (token?.type == com.localmind.data.TokenType.COLOR) {
                                            Box(
                                                Modifier
                                                    .size(12.dp)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(
                                                        runCatching { parseHexColor(v) }
                                                            .getOrDefault(Color.Transparent)
                                                    )
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(
                                            (if (darkOnly) "深色 · " else "") + (token?.label ?: key),
                                            style = IosTheme.typography.caption1,
                                            color = ios.label,
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            v,
                                            style = IosTheme.typography.caption1,
                                            color = ios.tertiaryLabel,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- 当前插件主题改了什么 ----
            active?.let { t ->
                item {
                    Column {
                        SectionHeader("「${t.packName}」改了什么", "按当前深浅色解析并夹取之后的值。")
                        Spacer(Modifier.height(10.dp))
                        IosCard(padding = PaddingValues(0.dp)) { ThemeTokenDiff(t) }
                    }
                }
            }
        }
    }
}

/** 分组标题 + 一句说明。比 IosSection 的 header/footer 更紧凑，也更像「标题」。 */
@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    val ios = IosTheme.palette
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(title, style = IosTheme.typography.title3, color = ios.label)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
        }
    }
}

/**
 * 一个预设的卡片：上面是真的微缩预览，下面是名字和一句话。
 *
 * 选中态用**边框 + 勾**，不用背景色 —— 卡片本身已经被预览占满了，
 * 再改背景会和预览里的颜色打架。
 */
@Composable
private fun PresetCard(
    preset: com.localmind.data.UiPresets.Preset,
    selected: Boolean,
    index: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ios = IosTheme.palette
    // 预览用**预设自己声明的**颜色，缺的按当前内置色补 —— 和实际生效的规则一致。
    val colors = com.localmind.ui.components.themePreviewColors(
        lookup = { k -> preset.tokens[k] ?: preset.darkTokens[k] },
        fallback = ios,
    )
    val corner = (preset.tokens["cardCorner"] ?: preset.darkTokens["cardCorner"])
        ?.toFloatOrNull() ?: 12f

    Column(
        modifier = modifier
            .appearIn(index = index)
            .clip(RoundedCornerShape(IosTheme.metrics.controlCorner + 4.dp))
            .background(ios.barBackground)
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = if (selected) ios.blue else ios.separator,
                shape = RoundedCornerShape(IosTheme.metrics.controlCorner + 4.dp),
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box {
            com.localmind.ui.components.ThemePreview(
                background = colors[0],
                card = colors[1],
                accent = colors[2],
                accentOn = colors[3],
                label = colors[4],
                separator = colors[5],
                cornerRadius = corner,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp),
            )
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ios.blue),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Check,
                        contentDescription = "使用中",
                        tint = ios.onAccent,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(preset.name, style = IosTheme.typography.footnote, color = ios.label)
        Text(
            preset.description,
            style = IosTheme.typography.caption2,
            color = ios.tertiaryLabel,
            maxLines = 2,
        )
    }
}

/** 「恢复默认外观」。不是预设，所以不占一格，做成一行按钮的样子。 */
@Composable
private fun ResetRow(enabled: Boolean, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(IosTheme.metrics.controlCorner))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.Refresh,
            contentDescription = null,
            tint = if (enabled) ios.red else ios.tertiaryLabel,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (enabled) "恢复默认外观" else "当前就是默认外观",
            style = IosTheme.typography.footnote,
            color = if (enabled) ios.red else ios.tertiaryLabel,
        )
    }
}

/**
 * 微调里的**一行**：上面是名字，下面是一排可点的选项。
 *
 * 横排而不是纵向列表：这些选项都很短（一个词），竖着排会变成一堵墙，
 * 而横排一眼就能看出「当前在哪一档」。
 */
@Composable
private fun TunerRow(
    title: String,
    value: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    val ios = IosTheme.palette
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(title, style = IosTheme.typography.footnote, color = ios.secondaryLabel)
        Spacer(Modifier.height(7.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (key, label) ->
                val on = value.equals(key, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(IosTheme.metrics.controlCorner))
                        .background(if (on) ios.blue else ios.fillSecondary)
                        .clickable { if (on) onClear() else onPick(key) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = IosTheme.typography.caption1,
                        color = if (on) ios.onAccent else ios.label,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 一项微调的当前值：用户覆盖优先，否则用屏幕上正在生效的值。 */
@Composable
private fun tunerValue(key: String, overrides: Map<String, String>): String {
    val ios = IosTheme.palette
    val resolved = com.localmind.ui.theme.LocalUiTokens.current[key]
    // 深色模式下深色覆盖才是「当前值」。
    return overrides[key]
        ?: (if (ios.isDark) overrides[com.localmind.data.UiPresets.Preset.DARK_PREFIX + key] else null)
        ?: resolved
        ?: UiTokens.byKey[key]?.default.orEmpty()
}

/** 主色候选。挑的都是饱和度足够、配白字能看清的颜色。 */
private val ACCENT_CHOICES: List<Pair<String, String>> = listOf(
    "#0A84FF" to "蓝",
    "#5F3DC4" to "紫",
    "#0B7285" to "青",
    "#2F9E44" to "绿",
    "#E8590C" to "橙",
    "#C2255C" to "玫红",
    "#111111" to "黑",
)

/** 顶部安全区。抽出来是因为这一页可能在别处复用。 */
@Composable
private fun statusBarTopPaddingSafe() = com.localmind.ui.components.statusBarTopPadding()

/** 「内置」那一行。用当前生效的内置色画色块，所以它能反映真实默认值。 */
@Composable
private fun BuiltinThemeRow(selected: Boolean, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Swatch(listOf(ios.blue, ios.green, ios.red, ios.orange))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("内置", style = IosTheme.typography.body, color = ios.label)
            Text("iOS 默认配色与圆角", style = IosTheme.typography.caption1, color = ios.secondaryLabel)
        }
        if (selected) CheckMark()
    }
}

@Composable
private fun ThemeRow(theme: UiTheme, selected: Boolean, index: Int, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .appearIn(index = index)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 用主题**自己声明的颜色**画色块，而不是当前生效的颜色 ——
        // 否则所有主题看起来都一样，这一列就失去意义了。
        Swatch(theme.swatchColors())
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(theme.packName, style = IosTheme.typography.body, color = ios.label)
            val sub = buildString {
                if (theme.author.isNotBlank()) append(theme.author).append(" · ")
                append("${theme.tokens.size} 项")
                val unknown = UiThemes.unknownKeys(theme)
                if (unknown.isNotEmpty()) append(" · 忽略 ${unknown.size} 项不认识的")
            }
            Text(sub, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
            if (theme.description.isNotBlank()) {
                Text(
                    theme.description,
                    style = IosTheme.typography.caption2,
                    color = ios.tertiaryLabel,
                    maxLines = 2,
                )
            }
        }
        if (selected) CheckMark()
    }
}

/** 四个色块拼成的小预览。 */
@Composable
private fun Swatch(colors: List<Color>) {
    Row(
        Modifier
            .size(width = 44.dp, height = 30.dp)
            .clip(RoundedCornerShape(7.dp)),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        colors.take(4).forEach { c ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(c)
            )
        }
    }
}

@Composable
private fun CheckMark() {
    val ios = IosTheme.palette
    Icon(
        AppIcons.Check,
        contentDescription = "使用中",
        tint = ios.blue,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * 把一个主题解析后的令牌列成表。
 *
 * 显示的是**解析并夹取之后**的值，不是 JSON 里写的原值 —— 这样如果作者
 * 写了越界的数字，用户看到的和实际生效的一致，不会被误导。
 */
@Composable
private fun ThemeTokenDiff(theme: UiTheme) {
    val ios = IosTheme.palette
    // 深浅色一律以**实际生效的主题**为准：用户可能在设置里手动选了深色，
    // 那时候 isSystemInDarkTheme() 是错的，这一列会显示浅色那一套值。
    val dark = IosTheme.palette.isDark
    val resolved = androidx.compose.runtime.remember(theme, dark) {
        UiThemes.resolve(theme, dark)
    }
    val unknown = UiThemes.unknownKeys(theme)

    Column(Modifier.padding(16.dp)) {
        if (resolved.isEmpty()) {
            Text("没有生效的令牌", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
        } else {
            resolved.forEach { (k, v) ->
                val token = UiTokens.byKey[k] ?: return@forEach
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 颜色型令牌给一个真实色块，比看十六进制直观得多。
                    if (token.type == com.localmind.data.TokenType.COLOR) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    runCatching { parseHexColor(v) }.getOrDefault(Color.Transparent)
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(token.label, style = IosTheme.typography.caption1, color = ios.label)
                    Spacer(Modifier.weight(1f))
                    Text(v, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                }
            }
        }
        if (unknown.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "不认识的令牌（主程序这个版本还改不了，已忽略）：${unknown.joinToString("、")}",
                style = IosTheme.typography.caption2,
                color = ios.orange,
            )
        }
    }
}

/** 主题自己声明的调色板，用来画列表里的预览色块。 */
private fun UiTheme.swatchColors(): List<Color> {
    val src = tokens
    fun pick(vararg keys: String, fallback: Color): Color {
        keys.forEach { k -> src[k]?.let { return runCatching { parseHexColor(it) }.getOrDefault(fallback) } }
        return fallback
    }
    return listOf(
        pick("accent", fallback = Color(0xFF0A84FF)),
        pick("cardBackground", fallback = Color(0xFFFFFFFF)),
        pick("groupedBackground", fallback = Color(0xFFF7F7F9)),
        pick("success", "danger", fallback = Color(0xFF34C759)),
    )
}

/**
 * 「界面布局」里的一行。
 *
 * 和主题那几行长得像但语义不同：主题可以叠加（预设 + 手动覆盖），布局是**单选** ——
 * 两套布局同时生效没有意义（底栏只有一个）。
 */
@Composable
private fun LayoutChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
            Text(
                subtitle,
                style = IosTheme.typography.footnote,
                color = ios.secondaryLabel,
                maxLines = 2,
            )
        }
        if (selected) {
            Text("使用中", style = IosTheme.typography.footnote, color = ios.blue)
        }
    }
}

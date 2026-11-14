package com.localmind.ui.screen

import androidx.compose.foundation.Image
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
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.localmind.ui.theme.IosTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 外观：只留真正影响观感的东西 —— 底栏、布局、风格、主题包。
 * 旧的「微调」一屏十几行，把底栏和布局埋掉了；这版只保留底栏模块。
 */
@Composable
fun ThemeScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val ios = IosTheme.palette
    val themes by viewModel.installedUiThemes.collectAsState()
    val activeTheme by viewModel.activeUiTheme.collectAsState()
    val overrides by viewModel.themeOverrides.collectAsState()
    val layouts by viewModel.installedLayouts.collectAsState()
    val currentLayout by viewModel.uiLayout.collectAsState()
    val plugins by viewModel.plugins.collectAsState()
    val tokens = com.localmind.ui.theme.LocalUiTokens.current
    val themeMode by viewModel.themeMode.collectAsState()
    // 预览卡要画当前模式下这套主题长什么样，所以要知道现在是深色还是浅色。
    val isDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    // 主题商店的大图预览。点列表里的缩略图打开，装之前先看清楚长什么样。
    var previewEntry by remember {
        mutableStateOf<com.localmind.service.ThemeStoreService.ThemeEntry?>(null)
    }

    val barModule = plugins.values.firstOrNull {
        it.manifest.abilities.contains("glass_bar") && it.isActive
    }
    val activePreset = com.localmind.data.UiPresets.match(overrides)

    Column(Modifier.fillMaxSize()) {
        AuroraNavBar(title = "外观", onBack = onBack)

        LazyColumn(
            flingBehavior = ScrollPhysics.fling(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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
                            else -> "内置外观"
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

                val liquidOn = onOff("glassBarLiquid", true)
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

            item {
                AuroraSectionLabel("界面布局")
                AuroraGroup {
                    AuroraRow(
                        title = "默认布局",
                        subtitle = "对话 / 模型 / 我的",
                        trailing = { CheckMark(currentLayout == null) },
                        onClick = { viewModel.useLayout("") },
                    )
                    layouts.values.sortedBy { it.packName }.forEach { l ->
                        AuroraRow(
                            title = l.packName,
                            subtitle = l.tabs.size.toString() + " 个入口" +
                                if (l.home.isNotEmpty()) " · " + l.home.size + " 块首页" else "",
                            trailing = { CheckMark(currentLayout?.packId == l.packId) },
                            onClick = { viewModel.useLayout(l.packId) },
                        )
                    }
                }
            }

            item {
                AuroraSectionLabel("风格")
                Column(Modifier.padding(horizontal = Aurora.PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.localmind.data.UiPresets.ALL.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { preset ->
                                PresetPreviewCard(
                                    name = preset.name,
                                    accent = runCatching {
                                        Color(android.graphics.Color.parseColor(preset.tokens["accent"] ?: "#2E6BE6"))
                                    }.getOrDefault(ios.blue),
                                    selected = activePreset?.id == preset.id,
                                    // 深浅色各自的令牌：跟着当前模式走。
                                    tokens = if (isDark) preset.darkTokens.ifEmpty { preset.tokens } else preset.tokens,
                                    modifier = Modifier.weight(1f),
                                    onClick = { viewModel.applyUiPreset(preset) },
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                AuroraSectionLabel("主题包")
                AuroraGroup {
                    AuroraRow(
                        title = "内置外观",
                        subtitle = "跟随系统深浅色",
                        trailing = { CheckMark(activeTheme == null) },
                        onClick = { viewModel.useBuiltinTheme() },
                    )
                    themes.values.sortedBy { it.packName }.forEach { theme ->
                        AuroraRow(
                            title = theme.packName,
                            subtitle = (theme.tokens.size + theme.darkTokens.size).toString() +
                                " 项令牌" +
                                if (theme.author.isNotBlank()) " · " + theme.author else "",
                            trailing = { CheckMark(activeTheme?.packId == theme.packId) },
                            onClick = { viewModel.useUiTheme(theme) },
                        )
                    }
                }
            }

            item {
                // ---- 主题商店（内置 + 在线）----
                //
                // 主题包是**第三方内容**，所以这里显示的东西必须来自网络时如实标注
                // 作者与大小；下载走和本地 zip 完全一样的安装流程。
                //
                // 内置主题不联网也能装，所以它排在前面：用户刚打开这一页
                // 就能看到三个能立刻换上的主题，而不是一个空列表 + 转圈。
                val storeThemes by viewModel.themeStore.themes.collectAsState()
                val storeLoading by viewModel.themeStore.loading.collectAsState()
                val storeError by viewModel.themeStore.lastError.collectAsState()
                val downloading by viewModel.themeDownloading.collectAsState()
                val downloadStatus by viewModel.themeDownloadStatus.collectAsState()

                val builtinThemes = storeThemes.filter { it.isBuiltin }
                val onlineThemes = storeThemes.filter { !it.isBuiltin }

                AuroraSectionLabel("主题商店")
                AuroraGroup {
                    AuroraRow(
                        title = "获取主题列表",
                        subtitle = when {
                            downloadStatus.isNotBlank() -> downloadStatus
                            storeLoading -> "正在获取…"
                            storeError.isNotBlank() -> storeError
                            onlineThemes.isNotEmpty() -> "已获取 ${onlineThemes.size} 个在线主题"
                            else -> "从云端拉取更多可安装的主题包"
                        },
                        trailing = {
                            if (storeLoading) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = ios.blue,
                                )
                            } else {
                                CheckMark(false)
                            }
                        },
                        onClick = { viewModel.refreshThemeStore() },
                    )
                }

                if (builtinThemes.isNotEmpty()) {
                    Text(
                        "内置主题",
                        style = IosTheme.typography.footnote,
                        color = ios.secondaryLabel,
                        modifier = Modifier.padding(
                            start = Aurora.PagePadding + 2.dp,
                            top = 12.dp,
                            bottom = 4.dp,
                        ),
                    )
                    AuroraGroup {
                        builtinThemes.forEach { entry ->
                            ThemeStoreRow(
                                entry = entry,
                                busy = downloading == entry.id,
                                accent = ios.blue,
                                onPreview = { previewEntry = entry },
                                onClick = { viewModel.downloadAndInstallTheme(entry) },
                            )
                        }
                    }
                }

                if (onlineThemes.isNotEmpty()) {
                    Text(
                        "在线主题",
                        style = IosTheme.typography.footnote,
                        color = ios.secondaryLabel,
                        modifier = Modifier.padding(
                            start = Aurora.PagePadding + 2.dp,
                            top = 12.dp,
                            bottom = 4.dp,
                        ),
                    )
                    AuroraGroup {
                        onlineThemes.forEach { entry ->
                            ThemeStoreRow(
                                entry = entry,
                                busy = downloading == entry.id,
                                accent = ios.blue,
                                onPreview = { previewEntry = entry },
                                onClick = { viewModel.downloadAndInstallTheme(entry) },
                            )
                        }
                    }
                }

                if (storeThemes.isEmpty() && !storeLoading && storeError.isBlank()) {
                    AuroraGroup {
                        AuroraRow(
                            title = "还没有拉到主题",
                            subtitle = "点上面那一行重试。主题包存在云端仓库，联网才有",
                            showChevron = false,
                            onClick = {},
                        )
                    }
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

    // 装之前先看大图。放在 Column 外面，避免被 LazyColumn 的裁剪影响。
    previewEntry?.let { entry ->
        ThemePreviewDialog(
            entry = entry,
            onDismiss = { previewEntry = null },
            onInstall = {
                previewEntry = null
                viewModel.downloadAndInstallTheme(entry)
            },
        )
    }
}

/**
 * 主题大图预览。
 *
 * 预览图来自主题包本身（内置）或 GitHub 数据仓库（在线），不是现画的
 * 示意图 —— 所以下载失败时这里会如实显示占位，而不是假装有一张图。
 */
@Composable
private fun ThemePreviewDialog(
    entry: com.localmind.service.ThemeStoreService.ThemeEntry,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    val ios = IosTheme.palette
    val big by com.localmind.ui.theme.rememberThemePreview(entry, 320.dp)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.78f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(ios.secondaryGroupedBackground)
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(width = 216.dp, height = 384.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ios.fillSecondary),
                    contentAlignment = Alignment.Center,
                ) {
                    if (big != null) {
                        Image(
                            bitmap = big!!,
                            contentDescription = entry.name + " 主题预览",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text("预览图加载中…", color = ios.secondaryLabel, style = IosTheme.typography.footnote)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(entry.name, color = ios.label, style = IosTheme.typography.headline)
                if (entry.author.isNotBlank()) {
                    Text(
                        "by " + entry.author + if (entry.version.isNotBlank()) " · v" + entry.version else "",
                        color = ios.secondaryLabel,
                        style = IosTheme.typography.footnote,
                    )
                }
                if (entry.description.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        entry.description,
                        color = ios.secondaryLabel,
                        style = IosTheme.typography.footnote,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(ios.fillSecondary)
                            .clickable { onDismiss() }
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                    ) { Text("关闭", color = ios.label, style = IosTheme.typography.callout) }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(ios.blue)
                            .clickable { onInstall() }
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                    ) {
                        Text(
                            if (entry.isBuiltin) "安装" else "下载并安装",
                            color = ios.onAccent,
                            style = IosTheme.typography.callout,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 预设预览卡。
 *
 * 用 [com.localmind.ui.theme.ThemeThumbnail] 画一个**迷你界面**，
 * 而不是只涂一个主色块 —— 用户得能看出这个主题是方的还是圆的、
 * 气泡有没有尾巴、背景是不是渐变。不然挑主题就是瞎猜。
 */
@Composable
private fun PresetPreviewCard(
    name: String,
    accent: Color,
    selected: Boolean,
    /** 这个预设的令牌表，用来画缩略图。 */
    tokens: Map<String, String>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ios = IosTheme.palette
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(ios.secondaryGroupedBackground.copy(alpha = 0.74f))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp),
        ) {
            com.localmind.ui.theme.ThemeThumbnail(
                tokens = tokens,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = IosTheme.typography.footnote, color = ios.label, modifier = Modifier.weight(1f))
            if (selected) Text("✓", style = IosTheme.typography.footnote, color = ios.blue)
        }
    }
}

/**
 * 主题商店里的一行。
 *
 * 内置主题和在线主题**长得不一样**，因为它们的来源不同：
 *  - 内置：随 APK 附带，不用联网，"下载"按钮其实是"安装"（秒完成）
 *  - 在线：从云端仓库拉，"下载"要走网络，可能失败
 *
 * 把这个差别明说在界面上，而不是让用户点了才发现"怎么这么快就好了"。
 */
@Composable
private fun ThemeStoreRow(
    entry: com.localmind.service.ThemeStoreService.ThemeEntry,
    busy: Boolean,
    accent: Color,
    onPreview: () -> Unit,
    onClick: () -> Unit,
) {
    val ios = IosTheme.palette
    // 缩略图走 ThemePreviewLoader：内置主题读 APK assets，在线主题按
    // GitHub 主地址 → 镜像依次拉，结果进内存 + 磁盘缓存。
    val thumb by com.localmind.ui.theme.rememberThemePreview(entry, 84.dp)
    AuroraRow(
        title = entry.name + if (busy) "（处理中）" else "",
        subtitle = buildString {
            if (entry.isBuiltin) {
                append("内置 · ")
            } else {
                if (entry.author.isNotBlank()) append(entry.author).append(" · ")
            }
            if (entry.sizeBytes > 0) {
                val kb = entry.sizeBytes / 1024
                if (kb >= 1024) {
                    append(kb / 1024).append(".").append((kb % 1024) * 10 / 1024).append(" MB · ")
                } else {
                    append(kb).append(" KB · ")
                }
            }
            if (entry.tags.isNotEmpty()) {
                append(entry.tags.take(3).joinToString(" / "))
            } else {
                append(entry.description.ifBlank { "主题包" })
            }
        },
        leading = {
            Box(
                Modifier
                    .size(width = 54.dp, height = 96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ios.fillSecondary)
                    .clickable { onPreview() },
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb!!,
                        contentDescription = "查看「${entry.name}」预览图",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    // 图还没到 / 拉不到：画一个和主题主色相近的占位，
                    // 不阻塞下载，也不让列表里出现一块突兀的空白。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("预览", color = accent, style = IosTheme.typography.caption2)
                    }
                }
            }
        },
        trailing = {
            when {
                busy -> androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = accent,
                )
                entry.isBuiltin -> Text(
                    "安装",
                    style = IosTheme.typography.callout,
                    color = accent,
                )
                else -> Text(
                    "下载",
                    style = IosTheme.typography.callout,
                    color = accent,
                )
            }
        },
        onClick = { if (!busy) onClick() },
    )
}

@Composable
private fun CheckMark(selected: Boolean) {
    val ios = IosTheme.palette
    Text(
        if (selected) "✓" else "",
        style = IosTheme.typography.subheadline,
        color = ios.blue,
        fontWeight = FontWeight.SemiBold,
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

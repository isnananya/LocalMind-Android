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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localmind.ui.AppViewModel
import com.localmind.ui.design.Aurora
import com.localmind.ui.design.AuroraCircleButton
import com.localmind.ui.design.AuroraGroup
import com.localmind.ui.design.AuroraNavBar
import com.localmind.ui.design.AuroraRow
import com.localmind.ui.design.AuroraSectionLabel
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

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

    val barModule = plugins.values.firstOrNull {
        it.manifest.abilities.contains("glass_bar") && it.isActive
    }
    val activePreset = com.localmind.data.UiPresets.match(overrides)

    Column(Modifier.fillMaxSize()) {
        AuroraNavBar(title = "外观", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                AuroraSectionLabel("底栏")
                AuroraGroup {
                    AuroraRow(
                        title = "液态玻璃底栏",
                        subtitle = when {
                            barModule != null -> "已启用 · " + barModule.manifest.name
                            plugins.values.any { it.manifest.abilities.contains("glass_bar") } ->
                                "已安装，去插件页启用"
                            else -> "安装模块后启用紧凑悬浮胶囊"
                        },
                        leading = {
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(ios.blue.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center,
                            ) { Text("◐", color = ios.blue) }
                        },
                        trailing = {
                            Text(
                                if (barModule != null) "已启用" else "去安装",
                                style = IosTheme.typography.caption1,
                                color = ios.blue,
                            )
                        },
                        onClick = { viewModel.openPlugins(true) },
                    )
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
                        title = "显示",
                        options = listOf("glass" to "玻璃", "plain" to "纯色", "hidden" to "隐藏"),
                        selected = tokens["tabStyle"] ?: "glass",
                        onPick = { viewModel.setThemeOverride("tabStyle", it) },
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
}

@Composable
private fun PresetPreviewCard(
    name: String,
    accent: Color,
    selected: Boolean,
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
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent.copy(alpha = 0.16f))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent)
            )
            Spacer(Modifier.size(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.width(46.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(accent.copy(alpha = 0.55f)))
                Box(Modifier.width(30.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(ios.label.copy(alpha = 0.16f)))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = IosTheme.typography.footnote, color = ios.label, modifier = Modifier.weight(1f))
            if (selected) Text("✓", style = IosTheme.typography.footnote, color = ios.blue)
        }
    }
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

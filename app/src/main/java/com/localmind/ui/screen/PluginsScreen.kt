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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.localmind.data.InstalledPlugin
import com.localmind.data.PluginAbilities
import com.localmind.data.PluginState
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.design.Aurora
import com.localmind.ui.design.AuroraButton
import com.localmind.ui.design.AuroraCircleButton
import com.localmind.ui.design.AuroraDivider
import com.localmind.ui.design.AuroraEmpty
import com.localmind.ui.design.AuroraGroup
import com.localmind.ui.design.AuroraIcon
import com.localmind.ui.design.AuroraNavBar
import com.localmind.ui.design.AuroraRow
import com.localmind.ui.design.AuroraSectionLabel
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

/**
 * 插件页。
 *
 * 只做三件事：导入、看已装了什么、开关它。能力标签用彩色小胶囊，
 * 不写"这个能力是什么" —— 那是开发文档的事。
 */
@Composable
fun PluginsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val ios = IosTheme.palette
    val plugins by viewModel.plugins.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingDelete by remember { mutableStateOf<InstalledPlugin?>(null) }
    var pendingCode by remember { mutableStateOf<java.io.File?>(null) }
    var showDev by remember { mutableStateOf(false) }

    val pickZip = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val tmp = java.io.File(viewModel.storage.pluginsDir, "import-" + System.currentTimeMillis())
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return@runCatching
            // 带 classes.dex 的包会执行代码，先让用户确认一次。
            val hasCode = runCatching {
                java.util.zip.ZipFile(tmp).use { z ->
                    z.entries().toList().any { it.name.endsWith(".dex", ignoreCase = true) }
                }
            }.getOrDefault(false)
            if (hasCode) {
                pendingCode = tmp
            } else {
                val msg = viewModel.installPluginZip(tmp)
                tmp.delete()
                viewModel.toast(msg)
            }
        }.onFailure { viewModel.toast("导入失败：" + (it.message?.take(80) ?: "")) }
    }

    fun openPicker() {
        pickZip.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
                "*/*",
            )
        )
    }

    Column(Modifier.fillMaxSize()) {
        AuroraNavBar(
            title = "插件",
            onBack = onBack,
            trailing = {
                AuroraCircleButton(
                    icon = AppIcons.Add,
                    contentDescription = "导入插件",
                    onClick = { openPicker() },
                )
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
            item {
                Row(
                    Modifier
                        .padding(horizontal = Aurora.PagePadding, vertical = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(ios.blue.copy(alpha = if (ios.isDark) 0.18f else 0.09f))
                        .clickable { openPicker() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AuroraIcon(AppIcons.CloudDownload, ios.blue, size = 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "导入插件 zip",
                            style = IosTheme.typography.headline,
                            color = ios.label,
                        )
                        Text(
                            "类型自动识别，不用选",
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                    }
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = ios.blue,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (plugins.isEmpty()) {
                item {
                    AuroraEmpty(
                        icon = AppIcons.Extension,
                        title = "还没有插件",
                        message = "模型库、表情包、语音音色都从这里装",
                    )
                }
            } else {
                item { AuroraSectionLabel("已安装 " + plugins.size + " 个") }
                item {
                    AuroraGroup {
                        val list = plugins.values.sortedBy { it.installedAt }
                        list.forEachIndexed { index, plugin ->
                            if (index > 0) AuroraDivider(indent = 62.dp)
                            PluginRow(
                                plugin = plugin,
                                onToggle = { on -> viewModel.setPluginEnabled(plugin.manifest.id, on) },
                                onLongPress = { pendingDelete = plugin },
                            )
                        }
                    }
                }
            }

            item {
                AuroraSectionLabel("开发")
                AuroraGroup {
                    AuroraRow(
                        title = "开发文档",
                        subtitle = "字段表、能力清单、示例",
                        leading = { AuroraIcon(AppIcons.HelpOutline, ios.purple) },
                        onClick = { viewModel.openPluginDocs(true) },
                    )
                    AuroraDivider(indent = 58.dp)
                    AuroraRow(
                        title = "自己写插件",
                        subtitle = if (showDev) "收起" else "一个 zip + 几份 JSON",
                        leading = { AuroraIcon(AppIcons.Psychology, ios.orange) },
                        onClick = { showDev = !showDev },
                    )
                    if (showDev) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                "不需要写代码。写出示例后直接改 JSON，压成 zip 再导入。",
                                style = IosTheme.typography.footnote,
                                color = ios.secondaryLabel,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AuroraButton(
                                    "表情包示例",
                                    onClick = { viewModel.toast(viewModel.saveSampleStickerPack()) },
                                    color = ios.fill,
                                )
                                AuroraButton(
                                    "模型库示例",
                                    onClick = { viewModel.toast(viewModel.saveSampleModelLibrary()) },
                                    color = ios.fill,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            AuroraButton(
                                "语音音色示例",
                                onClick = { viewModel.toast(viewModel.saveSampleVoicePack()) },
                                color = ios.fill,
                            )
                        }
                    }
                }
            }
        }
    }

    pendingCode?.let { file ->
        AlertDialog(
            onDismissRequest = {
                file.delete()
                pendingCode = null
            },
            title = { Text("安装代码插件？", style = IosTheme.typography.headline) },
            text = {
                Text(
                    "这个 zip 里有 classes.dex，安装后会执行其中的 Java 代码。" +
                        "只安装你信任的来源。代码以主程序同等权限运行。",
                    style = IosTheme.typography.subheadline,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val msg = viewModel.installPluginZip(file)
                    file.delete()
                    pendingCode = null
                    viewModel.toast(msg)
                }) { Text("仍然安装", color = ios.red) }
            },
            dismissButton = {
                TextButton(onClick = {
                    file.delete()
                    pendingCode = null
                }) { Text("取消") }
            },
        )
    }

    pendingDelete?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除插件？", style = IosTheme.typography.headline) },
            text = {
                Text(
                    plugin.manifest.name + " 会被卸载，它提供的模型 / 主题 / 界面会恢复默认。",
                    style = IosTheme.typography.subheadline,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstallPlugin(plugin.manifest.id)
                    pendingDelete = null
                }) { Text("删除", color = ios.red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PluginRow(
    plugin: InstalledPlugin,
    onToggle: (Boolean) -> Unit,
    onLongPress: () -> Unit,
) {
    val ios = IosTheme.palette
    val m = plugin.manifest
    val primary = m.abilities.firstOrNull()
    val color = when (primary) {
        PluginAbilities.UI_THEME.id, PluginAbilities.UI_LAYOUT.id -> ios.purple
        PluginAbilities.MODEL_LIBRARY.id -> ios.blue
        PluginAbilities.EMOJI_PACK.id -> ios.orange
        PluginAbilities.TTS_VOICE.id, PluginAbilities.TTS_ENGINE.id -> ios.green
        PluginAbilities.PIPELINE.id -> ios.yellow
        PluginAbilities.CODE_PLUGIN.id -> ios.indigo
        PluginAbilities.GLASS_BAR.id -> ios.teal
        else -> ios.gray
    }
    val icon = when (primary) {
        PluginAbilities.UI_THEME.id, PluginAbilities.UI_LAYOUT.id -> AppIcons.Brush
        PluginAbilities.MODEL_LIBRARY.id -> AppIcons.Memory
        PluginAbilities.EMOJI_PACK.id -> AppIcons.AutoAwesome
        PluginAbilities.TTS_VOICE.id, PluginAbilities.TTS_ENGINE.id -> AppIcons.Mic
        PluginAbilities.PIPELINE.id -> AppIcons.Bolt
        PluginAbilities.CODE_PLUGIN.id -> AppIcons.Psychology
        PluginAbilities.GLASS_BAR.id -> AppIcons.AutoAwesome
        else -> AppIcons.Extension
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onLongPress,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuroraIcon(icon, color, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                m.name,
                style = IosTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                color = ios.label,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    if (m.author.isNotBlank()) append(m.author).append(" · ")
                    append("v").append(m.version)
                    if (plugin.contents.isNotEmpty()) {
                        append(" · ").append(plugin.contents.joinToString("、"))
                    }
                },
                style = IosTheme.typography.caption2,
                color = ios.secondaryLabel,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                m.abilities.take(3).forEach { id ->
                    val ability = PluginAbilities.fromId(id)
                    AbilityChip(
                        label = ability?.label ?: id,
                        enabled = ability?.implemented == true,
                        color = color,
                    )
                }
                if (m.abilities.size > 3) {
                    AbilityChip(label = "+" + (m.abilities.size - 3), enabled = true, color = ios.gray)
                }
            }
            if (plugin.state == PluginState.FAILED && plugin.lastError.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    plugin.lastError,
                    style = IosTheme.typography.caption2,
                    color = ios.red,
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IosSwitch(
            checked = plugin.state == PluginState.ENABLED,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun AbilityChip(label: String, enabled: Boolean, color: Color) {
    val tint = if (enabled) color else IosTheme.palette.gray
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = IosTheme.typography.caption2,
            color = tint,
            fontWeight = FontWeight.Medium,
        )
    }
}

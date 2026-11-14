package com.localmind.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.data.BuiltinPlugins
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
    // 内置能力（液态底栏、模型库、表情包、语音音色、生图…）已经长在应用里了，
    // 列表只显示用户自己装的 —— 否则一进插件页就是一排「你从没装过」的东西，
    // 还带着卸载按钮，用户会以为是自己误装的。
    val userPlugins = remember(plugins) {
        plugins.filterKeys { !com.localmind.data.BuiltinPlugins.isBuiltin(it) }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingDelete by remember { mutableStateOf<InstalledPlugin?>(null) }
    var pendingCode by remember { mutableStateOf<java.io.File?>(null) }
    var showDev by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<InstalledPlugin?>(null) }
    val installing by viewModel.builtinInstalling.collectAsState()

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
            flingBehavior = ScrollPhysics.fling(),
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

            // 只列**还没装 / 能更新**的内置包。全都装到最新时整段收起来 ——
            // 否则同一批插件在"内置"和"已安装"里各出现一次，用户以为装了两份。
            val pendingBuiltins = BuiltinPlugins.ALL.filter { item ->
                val inst = plugins[item.id]
                inst == null || inst.manifest.version != item.version
            }
            if (pendingBuiltins.isNotEmpty()) {
            item { AuroraSectionLabel("可安装的内置插件") }
            item {
                AuroraGroup {
                    pendingBuiltins.forEachIndexed { index, item ->
                        if (index > 0) AuroraDivider(indent = 58.dp)
                        val installed = plugins[item.id]
                        val upToDate = installed?.manifest?.version == item.version
                        val busy = installing == item.key
                        AuroraRow(
                            title = item.name,
                            subtitle = item.note +
                                if (installed != null) " · 可更新" else " · 未安装",
                            leading = {
                                AuroraIcon(
                                    when (item.key) {
                                        "model-library" -> AppIcons.Memory
                                        "glass-bar" -> AppIcons.AutoAwesome
                                        "breeze-tts" -> AppIcons.RecordVoiceOver
                                        else -> AppIcons.Image
                                    },
                                    when (item.key) {
                                        "model-library" -> ios.blue
                                        "glass-bar" -> ios.teal
                                        "breeze-tts" -> ios.green
                                        else -> ios.pink
                                    },
                                )
                            },
                            showChevron = false,
                            trailing = {
                                Text(
                                    if (busy) "安装中…" else if (upToDate) "重装" else "安装",
                                    style = IosTheme.typography.caption1,
                                    color = if (busy) ios.tertiaryLabel else ios.blue,
                                )
                            },
                            onClick = { if (!busy) viewModel.installBuiltin(item.key) },
                        )
                    }
                }
            }
            }

            if (userPlugins.isEmpty()) {
                item {
                    AuroraEmpty(
                        icon = AppIcons.Extension,
                        title = "还没有插件",
                        message = "模型库、表情包、语音音色都从这里装",
                    )
                }
            } else {
                item { AuroraSectionLabel("已安装 " + userPlugins.size + " 个") }
                item {
                    AuroraGroup {
                        val list = userPlugins.values.sortedBy { it.installedAt }
                        list.forEachIndexed { index, plugin ->
                            if (index > 0) AuroraDivider(indent = 62.dp)
                            PluginRow(
                                plugin = plugin,
                                onToggle = { on -> viewModel.setPluginEnabled(plugin.manifest.id, on) },
                                onClick = { detail = plugin },
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

    detail?.let { plugin ->
        PluginDetailSheet(
            plugin = plugin,
            viewModel = viewModel,
            onDismiss = { detail = null },
            onAskDelete = {
                detail = null
                pendingDelete = plugin
            },
        )
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

/**
 * 插件详情：清单里的每一件事都在这里说清楚 —— 谁写的、什么许可、从哪来、
 * 提供哪些能力、具体装了什么。插件不是黑盒，用户有权在卸载前看到这些。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginDetailSheet(
    plugin: InstalledPlugin,
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
    onAskDelete: () -> Unit,
) {
    val ios = IosTheme.palette
    val context = LocalContext.current
    val m = plugin.manifest
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val builtin = BuiltinPlugins.itemForId(m.id)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ios.secondaryGroupedBackground,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, style = IosTheme.typography.title2, color = ios.label, modifier = Modifier.weight(1f))
                if (builtin != null) {
                    Text(
                        "内置",
                        style = IosTheme.typography.caption2,
                        color = ios.teal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ios.teal.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "v" + m.version + (if (m.author.isNotBlank()) " · " + m.author else ""),
                style = IosTheme.typography.caption1,
                color = ios.secondaryLabel,
            )
            if (m.description.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(m.description, style = IosTheme.typography.footnote, color = ios.secondaryLabel)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                m.abilities.forEach { id ->
                    val ability = PluginAbilities.fromId(id)
                    AbilityChip(ability?.label ?: id, ability?.implemented == true, ios.blue)
                }
            }
            Spacer(Modifier.height(14.dp))
            DetailLine("状态", when (plugin.state) {
                PluginState.ENABLED -> "启用中"
                PluginState.DISABLED -> "已停用"
                PluginState.FAILED -> "加载失败"
                else -> "已安装"
            })
            DetailLine("来源", plugin.source.ifBlank { "本地导入" })
            DetailLine("许可", m.license.ifBlank { "未声明" })
            DetailLine(
                "安装时间",
                runCatching {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(plugin.installedAt))
                }.getOrDefault("—"),
            )
            if (plugin.contents.isNotEmpty()) {
                DetailLine("内容", plugin.contents.joinToString("、"))
            }
            if (m.sourceUrl.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "源码：" + m.sourceUrl,
                    style = IosTheme.typography.caption1,
                    color = ios.blue,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(m.sourceUrl),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                )
            }
            if (plugin.lastError.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(plugin.lastError, style = IosTheme.typography.caption1, color = ios.red)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AuroraButton(
                    if (plugin.state == PluginState.ENABLED) "停用" else "启用",
                    onClick = {
                        viewModel.setPluginEnabled(m.id, plugin.state != PluginState.ENABLED)
                        onDismiss()
                    },
                    color = ios.fill,
                    modifier = Modifier.weight(1f),
                )
                AuroraButton(
                    "删除",
                    onClick = onAskDelete,
                    color = ios.red.copy(alpha = 0.12f),
                    modifier = Modifier.weight(1f),
                )
            }
            builtin?.let { item ->
                Spacer(Modifier.height(8.dp))
                AuroraButton(
                    "重装内置版本（v" + item.version + "）",
                    onClick = {
                        viewModel.installBuiltin(item.key)
                        onDismiss()
                    },
                    color = ios.fill,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "插件的能力由主程序按白名单解释；卸载后它提供的内容（模型、音色、主题）会一起消失。",
                style = IosTheme.typography.caption2,
                color = ios.tertiaryLabel,
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val ios = IosTheme.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = IosTheme.typography.caption1,
            color = ios.secondaryLabel,
            modifier = Modifier.width(64.dp),
        )
        Text(
            value,
            style = IosTheme.typography.caption1,
            color = ios.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PluginRow(
    plugin: InstalledPlugin,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
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
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
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

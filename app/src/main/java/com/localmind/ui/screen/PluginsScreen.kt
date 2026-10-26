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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.localmind.data.InstalledPlugin
import com.localmind.data.PluginAbilities
import com.localmind.data.PluginState
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.motion.Motion
import com.localmind.ui.motion.appearIn
import com.localmind.ui.components.pressScale
import com.localmind.ui.theme.IosTheme

/**
 * 插件页 —— **骨架式**。
 *
 * ## 为什么做得这么素
 *
 * 这里曾经有一大堆东西：能力清单表、"这是什么"的长篇说明、四个并列的安装入口
 * （导入 zip / 新建表情包 / 新建模型库 / 粘贴清单）。
 *
 * 问题是**那些都不是插件页该干的事**：
 *
 *  * 能力清单表是**开发文档**，用户不需要知道主程序认识哪些 ability id；
 *  * 四个安装入口逼用户先判断"我手上这个是什么包" —— 而类型本来就该由程序认；
 *  * 长篇说明在用户每次打开这一页时都要读一遍，而他一辈子可能只装两个插件。
 *
 * 现在只剩三件事：**导入 zip、看已装了什么、开关它**。
 * 每个插件自己的说明由它 zip 里的 `README.md` 提供 —— 谁的功能谁解释。
 */
@Composable
fun PluginsScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val ios = IosTheme.palette
    val plugins by viewModel.plugins.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // 唯一的安装入口。类型由程序从 zip 内容判断，用户不需要选。
    val pickZip = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            // 先落到应用私有目录再解析：ZipInputStream 要能随机读，
            // 直接读 content:// 流在部分 ROM 上会失败。
            val tmp = java.io.File(viewModel.storage.pluginsDir, "import-${System.currentTimeMillis()}")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: return@runCatching
            val msg = viewModel.installPluginZip(tmp)
            tmp.delete()
            viewModel.toast(msg)
        }.onFailure { viewModel.toast("导入失败：${it.message?.take(80)}") }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
    ) {
        IosNavigationBar(
            title = "插件",
            collapsed = true,
            topInset = statusBarTopPadding(),
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
            trailing = {
                Icon(
                    AppIcons.Add,
                    contentDescription = "导入插件",
                    tint = ios.blue,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable {
                            pickZip.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                    "*/*",
                                )
                            )
                        },
                )
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // -------------------------------------------------- 导入
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ios.secondaryGroupedBackground)
                        .clickable {
                            pickZip.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                    "*/*",
                                )
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        AppIcons.Download,
                        contentDescription = null,
                        tint = ios.blue,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("导入插件 zip", style = IosTheme.typography.body, color = ios.label)
                        Text(
                            "选一个 zip 就行，类型会自动识别",
                            style = IosTheme.typography.caption1,
                            color = ios.secondaryLabel,
                        )
                    }
                    Icon(
                        AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = ios.tertiaryLabel,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // -------------------------------------------------- 已安装
            if (plugins.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("还没有插件", style = IosTheme.typography.headline, color = ios.secondaryLabel)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "模型库、表情包、语音音色都从这里装。",
                            style = IosTheme.typography.footnote,
                            color = ios.tertiaryLabel,
                        )
                    }
                }
            } else {
                item {
                    IosSection(header = "已安装 ${plugins.size} 个") {
                        plugins.values.sortedBy { it.installedAt }.forEachIndexed { i, p ->
                            if (i > 0) Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp)
                                    .height(0.5.dp)
                                    .background(ios.separator)
                            )
                            PluginRow(
                                plugin = p,
                                index = i,
                                onToggle = { on -> viewModel.setPluginEnabled(p.manifest.id, on) },
                                onRemove = { viewModel.uninstallPlugin(p.manifest.id) },
                            )
                        }
                    }
                }
            }

            // -------------------------------------------------- 开发文档（整页）
            item {
                IosSection {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.openPluginDocs(true) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("开发文档", style = IosTheme.typography.body, color = ios.label)
                            Text(
                                "字段表、能力清单、路由判据、最小示例",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                        Icon(
                            AppIcons.ChevronRight,
                            contentDescription = null,
                            tint = ios.tertiaryLabel,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // -------------------------------------------------- 写插件（折叠）
            item {
                var showDev by remember { mutableStateOf(false) }
                IosSection {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDev = !showDev }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (showDev) "收起" else "自己写插件",
                            style = IosTheme.typography.body,
                            color = ios.blue,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (showDev) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(ios.separator)
                            )
                        }
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "一个插件就是一个 zip，里面是 JSON，不需要写代码。\n" +
                                    "完整格式、能力清单、路由判据都在上面的「开发文档」里 —— " +
                                    "手机上就能看，不用去翻仓库。",
                                style = IosTheme.typography.footnote,
                                color = ios.secondaryLabel,
                            )
                            Spacer(Modifier.height(10.dp))
                            DevAction("写出表情包示例") { viewModel.toast(viewModel.saveSampleStickerPack()) }
                            DevAction("写出模型库示例") { viewModel.toast(viewModel.saveSampleModelLibrary()) }
                            DevAction("写出语音音色示例") { viewModel.toast(viewModel.saveSampleVoicePack()) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DevAction(label: String, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Text(
        label,
        style = IosTheme.typography.footnote,
        color = ios.blue,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    )
}

/**
 * 一个已安装插件。
 *
 * 只显示**它自己声明的东西**：名字、作者、它提供了哪些能力、占了什么内容。
 * 主程序不替它解释 —— 那是它 README 的活。
 */
@Composable
private fun PluginRow(
    plugin: InstalledPlugin,
    index: Int,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val ios = IosTheme.palette
    val m = plugin.manifest
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .appearIn(index = index)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name, style = IosTheme.typography.body, color = ios.label)
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    if (m.author.isNotBlank()) append(m.author).append(" · ")
                    append("v").append(m.version)
                    if (plugin.contents.isNotEmpty()) {
                        append(" · ").append(plugin.contents.joinToString("、"))
                    }
                },
                style = IosTheme.typography.caption1,
                color = ios.secondaryLabel,
            )
            if (plugin.state == PluginState.FAILED && plugin.lastError.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(plugin.lastError, style = IosTheme.typography.caption1, color = ios.red)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                m.abilities.forEach { id ->
                    val ability = PluginAbilities.fromId(id)
                    IosPill(
                        ability?.label ?: id,
                        when {
                            ability == null -> ios.gray
                            ability.implemented -> ios.blue
                            else -> ios.gray
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IosSwitch(
                checked = plugin.state == PluginState.ENABLED,
                onCheckedChange = onToggle,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "删除",
                style = IosTheme.typography.caption2,
                color = ios.red,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

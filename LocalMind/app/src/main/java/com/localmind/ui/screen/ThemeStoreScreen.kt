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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localmind.service.ThemeStoreService
import com.localmind.ui.AppViewModel
import com.localmind.ui.design.Aurora
import com.localmind.ui.design.AuroraGroup
import com.localmind.ui.design.AuroraNavBar
import com.localmind.ui.design.AuroraRow
import com.localmind.ui.design.AuroraSectionLabel
import com.localmind.ui.motion.ScrollPhysics
import com.localmind.ui.theme.IosTheme
import com.localmind.ui.theme.rememberThemePreview

/**
 * 主题商店（独立页面）。
 *
 * 以前它挤在「外观」里当一段列表，主题一多就和底栏/布局那些开关混在一起，
 * 既看不全也找不到。现在它是「外观 → 主题商店」点进去的**独立一屏**：
 * 顶部只有刷新，下面是分组列表，一屏只干一件事。
 *
 * 三种状态在界面上一眼能分清（这是这一版的重点）：
 *  * **使用中** —— 当前生效，点不动；
 *  * **使用**   —— 已经装过，点了立刻切过去，**不联网**；
 *  * **安装**   —— 包已经下过（在 `filesDir/theme_packs`），直接本地安装；
 *  * **下载**   —— 本机没有，才需要网络。
 */
@Composable
fun ThemeStoreScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val ios = IosTheme.palette
    val storeThemes by viewModel.themeStore.themes.collectAsState()
    val loading by viewModel.themeStore.loading.collectAsState()
    val error by viewModel.themeStore.lastError.collectAsState()
    val busyId by viewModel.themeDownloading.collectAsState()
    val status by viewModel.themeDownloadStatus.collectAsState()
    val progressMap by viewModel.themeProgress.collectAsState()
    val cachedIds by viewModel.themeCachedIds.collectAsState()
    val installed by viewModel.installedUiThemes.collectAsState()
    val active by viewModel.activeUiTheme.collectAsState()

    var previewEntry by remember { mutableStateOf<ThemeStoreService.ThemeEntry?>(null) }

    // 打开就同步一次「哪些包已经在本机」，然后按需拉清单。
    LaunchedEffect(Unit) {
        viewModel.refreshThemeCache()
        if (storeThemes.none { !it.isBuiltin }) viewModel.refreshThemeStore()
    }

    // 云端清单优先；清单里没有的主题（离线兜底的内置包）补在后面。
    // **必须按 id 去重**：同一个主题（如樱雪）同时存在于内置兜底和云端清单，
    // 两个分组各自渲染一次会撞 LazyColumn 的 key，直接闪退（真机已踩过）。
    val mergedThemes = run {
        val online = storeThemes.filter { !it.isBuiltin }.distinctBy { it.id }
        val fallback = storeThemes.filter { entry ->
            entry.isBuiltin && online.none { it.id == entry.id }
        }
        online + fallback
    }

    Column(Modifier.fillMaxSize()) {
        AuroraNavBar(title = "主题商店", onBack = onBack)

        LazyColumn(
            flingBehavior = ScrollPhysics.fling(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                AuroraSectionLabel("清单")
                AuroraGroup {
                    AuroraRow(
                        title = if (loading) "正在获取…" else "刷新主题列表",
                        subtitle = when {
                            status.isNotBlank() -> status
                            error.isNotBlank() -> error
                            else -> buildString {
                                append("共 ${mergedThemes.size} 个主题")
                                if (cachedIds.isNotEmpty()) append(" · 已下载 ${cachedIds.size}")
                                if (installed.isNotEmpty()) append(" · 已安装 ${installed.size}")
                            }
                        },
                        trailing = {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = ios.blue,
                                )
                            } else {
                                Text("刷新", style = IosTheme.typography.callout, color = ios.blue)
                            }
                        },
                        onClick = { viewModel.refreshThemeStore() },
                    )
                }
                Text(
                    "装过的主题不会重复下载；点「使用」直接切换。",
                    style = IosTheme.typography.caption1,
                    color = ios.secondaryLabel,
                    modifier = Modifier.padding(start = Aurora.PagePadding + 2.dp, top = 6.dp),
                )
            }

            if (mergedThemes.isNotEmpty()) {
                storeSection(
                    label = "全部主题",
                    entries = mergedThemes,
                    busyId = busyId,
                    cachedIds = cachedIds,
                    installedIds = installed.keys,
                    activeId = active?.packId,
                    progressMap = progressMap,
                    onPreview = { previewEntry = it },
                    onAction = { viewModel.downloadAndInstallTheme(it) },
                )
            }

            if (mergedThemes.isEmpty() && !loading) {
                item {
                    AuroraGroup {
                        AuroraRow(
                            title = "还没有拉到主题",
                            subtitle = error.ifBlank { "点上面那一行重试。主题包存在云端仓库，联网才有" },
                            showChevron = false,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }

    previewEntry?.let { entry ->
        ThemeStorePreviewDialog(
            entry = entry,
            installed = installed.containsKey(entry.id),
            active = active?.packId == entry.id,
            cached = cachedIds.contains(entry.id),
            onDismiss = { previewEntry = null },
            onInstall = {
                previewEntry = null
                viewModel.downloadAndInstallTheme(entry)
            },
            onDeleteCache = {
                previewEntry = null
                viewModel.deleteCachedTheme(entry)
            },
        )
    }
}

/** 一个分组：标题 + 一组卡片。分组之间用标题隔开，主题多了也不会糊成一片。 */
private fun androidx.compose.foundation.lazy.LazyListScope.storeSection(
    label: String,
    entries: List<ThemeStoreService.ThemeEntry>,
    busyId: String,
    cachedIds: Set<String>,
    installedIds: Set<String>,
    activeId: String?,
    progressMap: Map<String, Int>,
    onPreview: (ThemeStoreService.ThemeEntry) -> Unit,
    onAction: (ThemeStoreService.ThemeEntry) -> Unit,
) {
    item(key = "label_$label") {
        AuroraSectionLabel(label)
    }
    items(entries, key = { it.id }) { entry ->
        ThemeStoreCard(
            entry = entry,
            busy = busyId == entry.id,
            installed = installedIds.contains(entry.id),
            active = activeId == entry.id,
            cached = cachedIds.contains(entry.id),
            progress = progressMap[entry.id],
            onPreview = { onPreview(entry) },
            onAction = { onAction(entry) },
        )
    }
}

/**
 * 商店里的一张卡。
 *
 * 左边缩略图（点开看大图），右边名字 / 作者 / 体积 / 标签，最右边是状态文案。
 * 整行可点 = 执行那个状态对应的动作，和 iOS App Store 的"获取"一致。
 */
@Composable
private fun ThemeStoreCard(
    entry: ThemeStoreService.ThemeEntry,
    busy: Boolean,
    installed: Boolean,
    active: Boolean,
    cached: Boolean,
    progress: Int?,
    onPreview: () -> Unit,
    onAction: () -> Unit,
) {
    val ios = IosTheme.palette
    val thumb by rememberThemePreview(entry, 84.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Aurora.PagePadding, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ios.secondaryGroupedBackground.copy(alpha = 0.72f))
            .clickable(enabled = !busy && !active) { onAction() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 54.dp, height = 96.dp)
                .clip(RoundedCornerShape(9.dp))
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
                Text("预览", color = ios.blue.copy(alpha = 0.7f), style = IosTheme.typography.caption2)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = IosTheme.typography.headline,
                    color = ios.label,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "使用中",
                        style = IosTheme.typography.caption2,
                        color = ios.onAccent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ios.blue)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    if (entry.isBuiltin) append("离线可用 · ")
                    else if (entry.author.isNotBlank()) append(entry.author).append(" · ")
                    if (entry.sizeBytes > 0) append(fmtSize(entry.sizeBytes)).append(" · ")
                    append(entry.tags.take(2).joinToString(" / ").ifBlank { entry.description.ifBlank { "主题包" } })
                },
                style = IosTheme.typography.footnote,
                color = ios.secondaryLabel,
                maxLines = 2,
            )
            if (entry.description.isNotBlank() && entry.tags.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    entry.description,
                    style = IosTheme.typography.caption1,
                    color = ios.tertiaryLabel,
                    maxLines = 1,
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(
                    when {
                        active -> Color.Transparent
                        installed || cached -> ios.blue.copy(alpha = 0.14f)
                        else -> ios.blue
                    }
                )
                .then(
                    if (installed && !active) Modifier.clickable { onAction() } else Modifier
                )
                .padding(horizontal = 14.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = if (installed || cached) ios.blue else ios.onAccent,
                    )
                    // 下载中有百分比就跟着转圈显示 —— 用户要的是"看得见在动"，
                    // 只有一个小圈圈转会被当成卡死。
                    if (progress != null) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "$progress%",
                            style = IosTheme.typography.caption2,
                            color = if (installed || cached) ios.blue else ios.onAccent,
                        )
                    }
                }
                active -> Text("使用中", style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                installed -> Text("使用", style = IosTheme.typography.callout, color = ios.blue, fontWeight = FontWeight.SemiBold)
                cached -> Text("安装", style = IosTheme.typography.callout, color = ios.blue, fontWeight = FontWeight.SemiBold)
                else -> Text("下载", style = IosTheme.typography.callout, color = ios.onAccent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 大图预览 + 安装/删除安装包。 */
@Composable
private fun ThemeStorePreviewDialog(
    entry: ThemeStoreService.ThemeEntry,
    installed: Boolean,
    active: Boolean,
    cached: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onDeleteCache: () -> Unit,
) {
    val ios = IosTheme.palette
    val big by rememberThemePreview(entry, 320.dp)
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
                    DialogButton(text = "关闭", primary = false, onClick = onDismiss)
                    if (active) {
                        DialogButton(text = "使用中", primary = false, onClick = onDismiss)
                    } else if (installed) {
                        DialogButton(text = "使用", primary = true, onClick = onInstall)
                    } else if (cached) {
                        DialogButton(text = "安装", primary = true, onClick = onInstall)
                    } else {
                        DialogButton(text = "下载并安装", primary = true, onClick = onInstall)
                    }
                }
                if (cached && !active) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "删除安装包（${fmtSize(entry.sizeBytes)}）",
                        style = IosTheme.typography.caption1,
                        color = ios.red,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDeleteCache() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) ios.blue else ios.fillSecondary)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            color = if (primary) ios.onAccent else ios.label,
            style = IosTheme.typography.callout,
        )
    }
}

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0) return "未知大小"
    val kb = bytes / 1024
    return if (kb >= 1024) {
        val mb = kb / 1024.0
        String.format(java.util.Locale.US, "%.1f MB", mb)
    } else {
        "$kb KB"
    }
}

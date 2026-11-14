package com.localmind.ui.screen

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localmind.data.UiPresets
import com.localmind.service.FontStoreService
import com.localmind.service.ThemeStoreService
import com.localmind.ui.AppViewModel
import com.localmind.ui.design.Aurora
import com.localmind.ui.design.AuroraNavBar
import com.localmind.ui.motion.ScrollPhysics
import com.localmind.ui.theme.BubbleShape
import com.localmind.ui.theme.IosTheme
import com.localmind.ui.theme.parseHexColor
import com.localmind.ui.theme.rememberFontPreview
import com.localmind.ui.theme.rememberRemoteImage
import com.localmind.ui.theme.rememberThemePreview

/**
 * 主题商店（三栏：主题 / 气泡 / 字体）。
 *
 * ## 为什么拆成三栏
 *
 * 这三件事的心智完全不同：
 *  * **主题** = 整套外观（配色 + 壁纸 + 卡片 + 气泡 + 字体），一次换掉；
 *  * **气泡** = 只换聊天框，不该动别的；
 *  * **字体** = 一个文件，几十 MB，下载要进度、要能删。
 *
 * 混在一页里，用户看到的是"一堆卡片"，而三种东西的"应用"语义并不一样
 * （主题会替换、气泡会合并、字体会下载）。分开之后每个 Tab 只讲一件事。
 *
 * 主题可以**自带**字体和气泡（`fontId` / `bubbleId` 字段），装主题时会一并应用 ——
 * 这就是"主题是成套的"的正确做法：不把字体塞进主题 zip（体积翻十倍），
 * 而是引用字体商店里的条目，按需下载。
 */
@Composable
fun ThemeStoreScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val overrides by viewModel.themeOverrides.collectAsState()

    Column(Modifier.fillMaxSize()) {
        AuroraNavBar(title = "主题商店", onBack = onBack)
        StoreTabBar(
            current = tab,
            counts = listOf(
                viewModel.themeStore.themes.collectAsState().value.size,
                // 云端和内置会有同 id / 同名的条目（云端是那份"升级版"），
                // 计数要按去重后的实际展示条数来，否则徽章数字比列表长。
                viewModel.bubbleStore.cloudBubbles.collectAsState().value
                    .plus(UiPresets.BUBBLES)
                    .distinctBy { it.id }
                    .distinctBy { it.name }
                    .size,
                viewModel.fontStore.fonts.collectAsState().value.size,
            ),
            onPick = { tab = it },
        )
        when (tab) {
            1 -> BubbleSection(viewModel, overrides)
            2 -> FontSection(viewModel)
            else -> ThemeSection(viewModel)
        }
    }
}

/** 顶部三栏切换。 */
@Composable
private fun StoreTabBar(current: Int, counts: List<Int>, onPick: (Int) -> Unit) {
    val ios = IosTheme.palette
    val labels = listOf("主题", "气泡", "字体")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Aurora.PagePadding, vertical = 8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(ios.fillSecondary)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val selected = current == i
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) ios.secondaryGroupedBackground else Color.Transparent)
                    .clickable { onPick(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (counts.getOrNull(i)?.let { it > 0 } == true) "$label ${counts[i]}" else label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) ios.label else ios.secondaryLabel,
                )
            }
        }
    }
}

// ================================================================ 主题

@Composable
private fun ThemeSection(viewModel: AppViewModel) {
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
    var filter by remember { mutableStateOf("all") }

    LaunchedEffect(Unit) {
        viewModel.refreshThemeCache()
        if (storeThemes.none { !it.isBuiltin }) viewModel.refreshThemeStore()
    }

    // 云端清单优先；清单里没有的主题（离线兜底的内置包）补在后面。
    // **必须按 id 去重**：同一个主题（如樱雪）同时存在于内置兜底和云端清单，
    // 渲染两次会撞 LazyGrid 的 key，直接闪退（真机已踩过）。
    val mergedThemes = run {
        val online = storeThemes.filter { !it.isBuiltin }.distinctBy { it.id }
        val fallback = storeThemes.filter { entry -> entry.isBuiltin && online.none { it.id == entry.id } }
        (online + fallback).distinctBy { it.id }
    }

    val visible = when (filter) {
        "downloaded" -> mergedThemes.filter { cachedIds.contains(it.id) || installed.containsKey(it.id) }
        "new" -> mergedThemes.filter { !cachedIds.contains(it.id) && !installed.containsKey(it.id) }
        else -> mergedThemes
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        flingBehavior = ScrollPhysics.fling(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Aurora.PagePadding, end = Aurora.PagePadding, top = 6.dp, bottom = 130.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            CurrentThemeHero(
                activeName = active?.packName ?: "默认主题",
                activeEntry = mergedThemes.firstOrNull { it.id == active?.packId },
                total = mergedThemes.size,
                downloaded = cachedIds.size,
                installedCount = installed.size,
                loading = loading,
                status = if (status.isNotBlank()) status else error,
                onRefresh = { viewModel.refreshThemeStore() },
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            FilterRow(filter = filter, onPick = { filter = it })
        }

        items(visible, key = { it.id }) { entry ->
            ThemeGridCard(
                entry = entry,
                busy = busyId == entry.id,
                installed = installed.containsKey(entry.id),
                active = active?.packId == entry.id,
                cached = cachedIds.contains(entry.id),
                progress = progressMap[entry.id],
                onPreview = { previewEntry = entry },
                onAction = { viewModel.downloadAndInstallTheme(entry) },
            )
        }

        if (visible.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyHint(
                    when {
                        mergedThemes.isEmpty() && loading -> "正在获取主题列表…"
                        mergedThemes.isEmpty() -> error.ifBlank { "还没有拉到主题，检查网络后点上方刷新" }
                        filter == "downloaded" -> "还没有下载过主题"
                        else -> "这个筛选下没有主题"
                    }
                )
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

// ================================================================ 气泡

@Composable
private fun BubbleSection(viewModel: AppViewModel, overrides: Map<String, String>) {
    val ios = IosTheme.palette
    val cloud by viewModel.bubbleStore.cloudBubbles.collectAsState()
    val loading by viewModel.bubbleStore.loading.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshBubbleStore() }

    // 云端优先 + 内置兜底，按 id 去重（同 id 时云端赢）；
    // 再按显示名去一次 —— 云端把内置那批重做了（加了渐变/材质），但 id 不同，
    // 不去名会出现两个「磨砂玻璃」并排。
    val all = (cloud + UiPresets.BUBBLES).distinctBy { it.id }.distinctBy { it.name }
    val selected = all.firstOrNull { p -> p.tokens.all { (k, v) -> overrides[k] == v } }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        flingBehavior = ScrollPhysics.fling(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Aurora.PagePadding, end = Aurora.PagePadding, top = 6.dp, bottom = 130.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHero(
                kicker = "正在使用",
                title = selected?.name ?: "自定义气泡",
                subtitle = if (loading) "正在刷新气泡目录…" else "共 ${all.size} 款 · 点一下立刻换",
            )
        }
        items(all, key = { it.id }) { p ->
            BubbleCard(
                preset = p,
                selected = selected?.id == p.id,
                onPick = { viewModel.applyBubblePreset(p) },
            )
        }
    }
}

/**
 * 缩略气泡高 28dp，真机单行气泡约 56dp，所以形状参数同比折半。
 * 不缩放的话圆角 18dp 在 28dp 高的气泡上就是满圆，49 张卡片全是胶囊。
 */
private const val PREVIEW_SCALE = 0.5f

/**
 * 一张气泡卡：直接用这套令牌画两个迷你气泡。
 *
 * 预览必须**真的走形状引擎** —— 卡片上画个圆角矩形、应用后却是尖尾，
 * 用户会觉得被骗。所以这里和聊天页共用 [BubbleShape] 与同一套解析逻辑。
 */
@Composable
private fun BubbleCard(
    preset: UiPresets.Preset,
    selected: Boolean,
    onPick: () -> Unit,
) {
    val ios = IosTheme.palette
    // 和聊天页共用同一套解析：形状、材质、描边、高光一次算完。
    val userLook = com.localmind.ui.theme.bubbleLookForUser(preset.tokens, ios.blue, Color.White)
    val aiLook = com.localmind.ui.theme.bubbleLookForAi(
        preset.tokens, ios.secondaryGroupedBackground, ios.label,
    )
    // 卡片里的迷你气泡只有真机的 1/4 高（28dp vs 单行 ≈56dp），圆角和尾巴
    // 都是按 dp 给的绝对值，不缩放就会出现"缩略图是胶囊、聊天页是圆角"的走样。
    // 统一按高度比例缩，缩略图和真机的观感才对得上。
    val userShape = remember(userLook.kind, userLook.corner, userLook.tail) {
        BubbleShape(
            userLook.kind,
            userLook.corner * PREVIEW_SCALE,
            userLook.tail * PREVIEW_SCALE,
            true,
        )
    }
    val aiShape = remember(aiLook.kind, aiLook.corner, aiLook.tail) {
        BubbleShape(
            aiLook.kind,
            aiLook.corner * PREVIEW_SCALE,
            aiLook.tail * PREVIEW_SCALE,
            false,
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ios.secondaryGroupedBackground)
            .then(
                if (selected) Modifier.border(1.5.dp, ios.blue, RoundedCornerShape(16.dp)) else Modifier
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onPick() },
    ) {
        // 预览区：故意铺一层彩色渐变当"透过去的背景" ——
        // 磨砂/液体玻璃是半透明的，底下没有颜色就看不出材质差别，
        // 用户会以为"这不还是纯色吗"。
        Box(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .background(ios.fillSecondary)
                .background(
                    Brush.linearGradient(
                        listOf(
                            ios.blue.copy(alpha = 0.22f),
                            ios.purple.copy(alpha = 0.16f),
                            ios.orange.copy(alpha = 0.14f),
                        ),
                    ),
                )
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    com.localmind.ui.theme.BubbleSurface(
                        shape = userShape,
                        material = userLook.material,
                        base = userLook.base,
                        alt = userLook.alt,
                        borderColor = userLook.border,
                        borderWidth = userLook.borderWidth,
                        shadow = userLook.shadow,
                        gloss = userLook.gloss,
                        modifier = Modifier.width(100.dp).height(28.dp),
                        padding = PaddingValues(start = 10.dp, top = 0.dp, end = 14.dp, bottom = 0.dp),
                    ) {
                        Text(
                            "在吗",
                            fontSize = 10.sp,
                            color = userLook.text,
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    com.localmind.ui.theme.BubbleSurface(
                        shape = aiShape,
                        material = aiLook.material,
                        base = aiLook.base,
                        alt = aiLook.alt,
                        borderColor = aiLook.border ?: ios.separator,
                        borderWidth = aiLook.borderWidth,
                        shadow = aiLook.shadow,
                        gloss = aiLook.gloss,
                        modifier = Modifier.width(116.dp).height(26.dp),
                        padding = PaddingValues(start = 14.dp, top = 0.dp, end = 10.dp, bottom = 0.dp),
                    ) {
                        Text(
                            "在的呀",
                            fontSize = 10.sp,
                            color = aiLook.text,
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                    }
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ios.blue)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text("使用中", fontSize = 9.sp, color = ios.onAccent)
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                preset.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ios.label,
                maxLines = 1,
            )
            Text(
                preset.description.ifBlank { "气泡样式" },
                fontSize = 10.sp,
                color = ios.secondaryLabel,
                maxLines = 2,
            )
        }
    }
}

// ================================================================ 字体

@Composable
private fun FontSection(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val context = LocalContext.current
    val fonts by viewModel.fontStore.fonts.collectAsState()
    val loading by viewModel.fontStore.loading.collectAsState()
    val error by viewModel.fontStore.lastError.collectAsState()
    val status by viewModel.fontStatus.collectAsState()
    val busyId by viewModel.fontDownloading.collectAsState()
    val progress by viewModel.fontProgress.collectAsState()
    val installed by viewModel.installedFontIds.collectAsState()
    val activeId by viewModel.activeFontId.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshFontCache()
        viewModel.refreshFontStore()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            viewModel.importLocalFont(uri, displayName(context, uri))
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        flingBehavior = ScrollPhysics.fling(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Aurora.PagePadding, end = Aurora.PagePadding, top = 6.dp, bottom = 130.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            FontHero(
                total = fonts.size,
                installedCount = installed.size,
                loading = loading,
                status = status.ifBlank { error },
                onRefresh = { viewModel.refreshFontStore() },
                onImport = {
                    runCatching { picker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "*/*")) }
                },
                onReset = { viewModel.applyFontId("") },
            )
        }

        items(fonts, key = { it.id }) { entry ->
            FontCard(
                entry = entry,
                busy = busyId == entry.id,
                installed = installed.contains(entry.id),
                active = activeId == entry.id,
                progress = progress[entry.id],
                onApply = { viewModel.downloadAndApplyFont(entry) },
                onDelete = { viewModel.deleteFont(entry) },
            )
        }

        if (fonts.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyHint(
                    when {
                        loading -> "正在获取字体列表…"
                        error.isNotBlank() -> error
                        else -> "还没有拉到字体，点上方刷新；也可以直接用「导入本机字体」"
                    }
                )
            }
        }
    }
}

@Composable
private fun FontHero(
    total: Int,
    installedCount: Int,
    loading: Boolean,
    status: String,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit,
) {
    val ios = IosTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ios.secondaryGroupedBackground)
            .padding(14.dp),
    ) {
        Text("字体", style = IosTheme.typography.title3, color = ios.label)
        Spacer(Modifier.height(3.dp))
        Text(
            buildString {
                append("共 $total 款")
                if (installedCount > 0) append(" · 已下载 $installedCount")
                if (status.isNotBlank()) append(" · ").append(status)
            },
            style = IosTheme.typography.caption1,
            color = ios.secondaryLabel,
            maxLines = 2,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallPill("刷新", filled = false) { onRefresh() }
            SmallPill("导入本机字体", filled = true) { onImport() }
            SmallPill("恢复默认", filled = false) { onReset() }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = ios.secondaryLabel,
                )
            }
        }
    }
}

@Composable
private fun SmallPill(text: String, filled: Boolean, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (filled) ios.blue else ios.fillSecondary)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) ios.onAccent else ios.label,
        )
    }
}

/**
 * 字体卡：上面是云端生成的**样张**（用这款字体真的写了一行字），
 * 下面是名字、分类、大小和操作。
 *
 * 预览图是服务端用 PIL 渲染的，不是在手机上加载整份字体再画 ——
 * 一个中文字体 2–20 MB，为了看一眼样张先下 20 MB 是荒唐的。
 */
@Composable
private fun FontCard(
    entry: FontStoreService.FontEntry,
    busy: Boolean,
    installed: Boolean,
    active: Boolean,
    progress: Int?,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    val ios = IosTheme.palette
    val preview by rememberFontPreview(entry.id, entry.previewUrls, 170.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ios.secondaryGroupedBackground)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (!busy) onApply() },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.55f)
                .background(ios.fillSecondary),
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview!!,
                    contentDescription = "「${entry.name}」样张",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // 兜底：样张没拿到（内置没有、网络也不通）时不留白 ——
                // 画一个带渐变的占位，把样张文案摆上去，至少能看出"这张卡是什么"。
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    ios.blue.copy(alpha = 0.18f),
                                    ios.fillSecondary,
                                    ios.blue.copy(alpha = 0.08f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            entry.sample.ifBlank { entry.name }.take(8),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Medium,
                            color = ios.label,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(entry.name, fontSize = 10.sp, color = ios.tertiaryLabel, maxLines = 1)
                    }
                }
            }
            if (active) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ios.blue)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text("使用中", fontSize = 9.sp, color = ios.onAccent)
                }
            }
            if (busy && progress != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.25f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress / 100f)
                            .height(3.dp)
                            .background(ios.blue),
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                entry.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = ios.label,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildString {
                        if (entry.style.isNotBlank()) append(entry.style)
                        if (entry.sizeBytes > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(fmtSize(entry.sizeBytes))
                        }
                    },
                    fontSize = 10.sp,
                    color = ios.secondaryLabel,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        busy -> progress?.let { "$it%" } ?: "下载中"
                        active -> "已应用"
                        installed -> "应用"
                        else -> "下载"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (active) ios.green else ios.blue,
                )
            }
            if (installed && !active) {
                Text(
                    "删除",
                    fontSize = 10.sp,
                    color = ios.red,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onDelete() }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 从 content:// Uri 里读显示名（有些机型返回的是纯 id，这时给个兜底）。 */
private fun displayName(context: Context, uri: Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
    return name?.substringBeforeLast('.')?.take(20)?.takeIf { it.isNotBlank() } ?: "本机字体"
}

// ================================================================ 公共小件

@Composable
private fun EmptyHint(text: String) {
    val ios = IosTheme.palette
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ios.secondaryGroupedBackground)
            .padding(vertical = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = IosTheme.typography.footnote, color = ios.secondaryLabel)
    }
}

/** 顶部大卡：当前主题的壁纸铺底 + 统计 + 刷新。 */
@Composable
private fun CurrentThemeHero(
    activeName: String,
    activeEntry: ThemeStoreService.ThemeEntry?,
    total: Int,
    downloaded: Int,
    installedCount: Int,
    loading: Boolean,
    status: String,
    onRefresh: () -> Unit,
) {
    val ios = IosTheme.palette
    val thumbState = activeEntry?.let { rememberThemePreview(it, 200.dp) }
    val heroBitmap = thumbState?.value

    Box(
        Modifier
            .fillMaxWidth()
            .height(158.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(ios.secondaryGroupedBackground),
    ) {
        if (heroBitmap != null) {
            Image(
                bitmap = heroBitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        // 底部压一层深色渐变：壁纸再亮，白字也读得清。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.05f),
                        0.55f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Text(
                "正在使用",
                style = IosTheme.typography.caption2,
                color = Color.White.copy(alpha = 0.82f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                activeName,
                style = IosTheme.typography.title1,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("共 $total 个主题")
                    if (downloaded > 0) append(" · 已下载 $downloaded")
                    if (installedCount > 0) append(" · 已装 $installedCount")
                    if (status.isNotBlank()) append(" · ").append(status)
                },
                style = IosTheme.typography.caption1,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
            )
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.34f))
                .clickable(enabled = !loading) { onRefresh() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                Text("刷新", style = IosTheme.typography.caption1, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 气泡 / 字体栏顶部的简洁大卡。 */
@Composable
private fun SectionHero(kicker: String, title: String, subtitle: String) {
    val ios = IosTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(ios.blue.copy(alpha = 0.16f), ios.purple.copy(alpha = 0.10f)),
                )
            )
            .padding(16.dp),
    ) {
        Text(kicker, style = IosTheme.typography.caption2, color = ios.secondaryLabel)
        Spacer(Modifier.height(3.dp))
        Text(title, style = IosTheme.typography.title2, color = ios.label, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
    }
}

/** 筛选条：全部 / 未下载 / 已下载。 */
@Composable
private fun FilterRow(filter: String, onPick: (String) -> Unit) {
    val ios = IosTheme.palette
    val options = listOf("all" to "全部", "new" to "未下载", "downloaded" to "已下载")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) ->
            val selected = filter == id
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) ios.label else ios.fillSecondary)
                    .clickable { onPick(id) }
                    .padding(horizontal = 15.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    style = IosTheme.typography.caption1,
                    color = if (selected) ios.groupedBackground else ios.label,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/**
 * 网格里的一张主题卡。
 *
 * 上半部分是壁纸预览（3:4），文字叠在磨砂渐变上；右上角是状态角标，
 * 点击角标执行动作，点击图片看大图。
 */
@Composable
private fun ThemeGridCard(
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
    val thumb by rememberThemePreview(entry, 200.dp)
    val interaction = remember { MutableInteractionSource() }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ios.secondaryGroupedBackground)
            .clickable(interactionSource = interaction, indication = null) { onPreview() },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .background(ios.fillSecondary),
        ) {
            if (thumb != null) {
                Image(
                    bitmap = thumb!!,
                    contentDescription = "「${entry.name}」预览",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    "预览",
                    style = IosTheme.typography.caption2,
                    color = ios.tertiaryLabel,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 底部渐变压暗，让名字永远读得清
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.66f),
                        ),
                    ),
            )

            // 名字 + 作者 + "含字体/气泡"徽标
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Text(
                    entry.name,
                    style = IosTheme.typography.headline,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    buildString {
                        if (entry.author.isNotBlank()) append(entry.author)
                        if (entry.sizeBytes > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(fmtSize(entry.sizeBytes))
                        }
                        if (isEmpty()) append("主题包")
                    },
                    style = IosTheme.typography.caption2,
                    color = Color.White.copy(alpha = 0.76f),
                    maxLines = 1,
                )
                if (entry.fontId.isNotBlank() || entry.bubbleId.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (entry.fontId.isNotBlank()) BundleTag("含字体")
                        if (entry.bubbleId.isNotBlank()) BundleTag("含气泡")
                    }
                }
            }

            // 状态角标：点它才是「下载 / 安装 / 使用」
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(9.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        when {
                            active -> ios.green
                            installed || cached -> Color.White.copy(alpha = 0.92f)
                            else -> ios.blue
                        },
                    )
                    .clickable(enabled = !active && !busy) { onAction() }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            strokeWidth = 1.6.dp,
                            color = if (installed || cached) ios.blue else Color.White,
                        )
                        if (progress != null) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "$progress%",
                                style = IosTheme.typography.caption2,
                                color = if (installed || cached) ios.blue else Color.White,
                            )
                        }
                    }
                    active -> Text("使用中", style = IosTheme.typography.caption2, color = Color.White, fontWeight = FontWeight.SemiBold)
                    installed -> Text("使用", style = IosTheme.typography.caption2, color = ios.blue, fontWeight = FontWeight.SemiBold)
                    cached -> Text("安装", style = IosTheme.typography.caption2, color = ios.blue, fontWeight = FontWeight.SemiBold)
                    else -> Text("下载", style = IosTheme.typography.caption2, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }

            // 下载进度条压在卡片最底部
            if (busy && progress != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.28f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress / 100f)
                            .height(3.dp)
                            .background(ios.blue),
                    )
                }
            }
        }
    }
}

@Composable
private fun BundleTag(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.22f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text, fontSize = 9.sp, color = Color.White)
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
                        interactionSource = remember { MutableInteractionSource() },
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
                if (entry.fontId.isNotBlank() || entry.bubbleId.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (entry.fontId.isNotBlank()) SmallPill("主题自带字体", filled = false) {}
                        if (entry.bubbleId.isNotBlank()) SmallPill("主题自带气泡", filled = false) {}
                    }
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

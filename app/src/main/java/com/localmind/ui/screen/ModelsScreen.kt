package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localmind.data.LocalModel
import com.localmind.data.MemoryPlanner
import com.localmind.service.DownloadStatus
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosEmptyState
import com.localmind.ui.components.scrollBottomPadding
import com.localmind.ui.components.IosLargeTitle
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosProgressBar
import com.localmind.ui.components.IosTag
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import com.localmind.util.Formatters

/**
 * 一个分类：一个标签、一句说明、一条判据。
 *
 * ## 为什么不是写死的枚举
 *
 * 原来这里是 12 个硬编码的分类（推荐/全部/DeepSeek/Qwen/中文/国产/推理/英文/语音/
 * 看图/生图/向量），每个对应一段 if。安装的模型库插件一换，问题就出来了：
 *
 *  * 插件里有、枚举里没有的标签（混元、文心、代码、Agent、多模态…）**永远显示不出来**；
 *  * 枚举里有、插件里没有的分类点进去是空页；
 *  * 换一套插件就得改一遍 Kotlin 再发一次版。
 *
 * 现在分类**按当前模型列表实时生成**：先放两个固定的（推荐 / 全部），再按
 * 「角色（看图 / 生图 / 语音 / 向量）→ 系列（Qwen / DeepSeek…）→ 标签」的顺序
 * 从数据里推导，只保留真的有条目的那些。
 */
private data class ModelCategory(
    val label: String,
    val hint: String,
    val matches: (LocalModel) -> Boolean,
)

/** 系列名取 family 开头的英文段：Qwen2.5 → Qwen、GLM-4 → GLM、DeepSeek-R1 → DeepSeek。 */
private fun familyKey(family: String): String? =
    Regex("^[A-Za-z]+").find(family.trim())?.value?.takeIf { it.length >= 2 }

private fun buildCategories(models: List<LocalModel>): List<ModelCategory> {
    val out = mutableListOf<ModelCategory>()
    out += ModelCategory("推荐", "适合本机内存的高性价比模型") { m ->
        m.recommendedRamGb <= 8 && !m.embedding && !m.isImageModel && !m.isVoiceModel
    }
    out += ModelCategory("全部", "全部来自开源仓库，URL 均实测可下载") { true }

    // ---- 角色：有就显示 ----
    fun role(label: String, hint: String, pred: (LocalModel) -> Boolean) {
        if (models.any(pred)) out += ModelCategory(label, hint, pred)
    }
    role("看图", "多模态，可描述图片内容") { it.isVisionModel }
    role("生图", "真实扩散模型（需要生图后端插件）") { it.isImageModel }
    role("语音", "离线语音音色（下完到「TTS 测试」选用）") { it.isVoiceModel }
    role("向量", "给 /v1/embeddings 与本地检索用") { it.embedding }

    // ---- 系列：按条目数取前 6 ----
    models.mapNotNull { m -> familyKey(m.family) }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .take(6)
        .forEach { (key, _) ->
            out += ModelCategory(key, "「" + key + "」系列") { m -> m.family.startsWith(key, true) }
        }

    // ---- 标签：按出现次数取前 8，跳过已经用过的名字 ----
    val used = out.map { it.label }.toSet()
    models.flatMap { it.tags }
        .groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }
        .map { it.key }
        .filter { it.isNotBlank() && it !in used }
        .take(8)
        .forEach { tag ->
            out += ModelCategory(tag, "标签：" + tag) { m -> m.tags.contains(tag) }
        }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val models by viewModel.models.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val downloads by viewModel.downloadStates.collectAsState()

    var repoInput by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }
    var previewModel by remember { mutableStateOf<LocalModel?>(null) }
    // 分类**跟着数据走**：换模型库插件之后，标签会自动变（见 buildCategories）。
    val categories = remember(models) { buildCategories(models) }
    var selectedLabel by remember { mutableStateOf("推荐") }
    // 选中的分类可能因为换插件而不存在了 → 退回第一个，而不是显示空页。
    val category = categories.firstOrNull { it.label == selectedLabel } ?: categories.first()

    val downloaded = models.filter { it.isDownloaded }
    val visible = models.filter(category.matches)
    val storage = viewModel.storage
    val (modelBytes, imageBytes, freeBytes) = remember(models) { storage.usage() }

    Column(
        Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
    ) {
        IosNavigationBar(
            title = "模型",
            collapsed = true,
            topInset = statusBarTopPadding(),
            leading = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        AppIcons.Memory,
                        contentDescription = null,
                        tint = ios.blue,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
            trailing = {
                Row {
                    // 生图工作台入口。以前 ImageScreen 没有任何入口，生图功能在界面上
                    // 完全不可达；按「模型页是下载与试用模型的地方」的定位放在这里。
                    Icon(
                        AppIcons.Brush,
                        contentDescription = "生图",
                        tint = ios.pink,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { viewModel.openImageStudio(true) },
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        AppIcons.Refresh,
                        contentDescription = "刷新",
                        tint = ios.blue,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { viewModel.refreshModels() }
                            .padding(0.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        AppIcons.Tune,
                        contentDescription = "设置",
                        tint = ios.blue,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable { viewModel.openSettings(true) },
                    )
                }
            },
        )
        IosLargeTitle("模型库")

        // 分类筛选。
        //
        // 原来是横向 LazyRow —— 12 个分类里后面那几个（含「语音」）要横滑才看得到，
        // 而横滑这一行**没有任何视觉提示**，用户根本不知道还有更多分类。
        // 改成自动换行：多占一两行高度，换来"所有分类一眼可见"。
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            categories.forEach { f ->
                IosTag(
                    text = f.label,
                    selected = category.label == f.label,
                    onClick = { selectedLabel = f.label },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = scrollBottomPadding(tabBarVisible = true, base = 28.dp)),
        ) {
            item {
                StorageSummary(
                    modelBytes = modelBytes,
                    imageBytes = imageBytes,
                    freeBytes = freeBytes,
                    loadedName = engineState.modelName,
                    context = engineState.contextSize,
                    threads = engineState.threads,
                    onUnload = { viewModel.unloadModel() },
                )
            }

            // ---- 空态：一条模型都没有时，这里就是第一屏 ----
            //
            // APK **不再自带模型清单**（那是第三方模型的索引，打进主程序包等于
            // 主程序在分发它们）。所以新装的应用会走到这里，必须把
            // 「清单从哪来、下一步点哪里」写清楚，而不是给一个空白页。
            if (models.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(420.dp)) {
                        IosEmptyState(
                            icon = AppIcons.Extension,
                            title = "还没有模型库",
                            message = "模型清单由插件提供，主程序一条都不带。\n" +
                                "把「官方模型库插件.zip」（和安装包放在一起）" +
                                "在「我的 → 插件」里导入，模型页就会出现可下载的清单。\n" +
                                "也可以直接用下面的「导入」粘贴一个 GGUF 直链。",
                            action = {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(ios.blue)
                                        .clickable { viewModel.openPlugins(true) }
                                        .padding(horizontal = 18.dp, vertical = 11.dp),
                                ) {
                                    Text(
                                        "去导入模型库插件",
                                        style = IosTheme.typography.headline,
                                        color = ios.onAccent,
                                    )
                                }
                            },
                        )
                    }
                }
            }

            if (downloaded.isNotEmpty()) {
                item { ModelsSectionHeader("已下载（${downloaded.size}）") }
                items(downloaded, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        active = engineState.modelName == model.name && engineState.ready,
                        downloadState = downloads[model.id],
                        ggufPreview = viewModel.ggufPreview(model),
                        plan = viewModel.memoryPlanFor(model),
                        onLoad = { viewModel.loadModel(model) },
                        onDownload = { viewModel.downloadAny(model) },
                        onPause = { viewModel.pauseDownload(model) },
                        onDelete = { viewModel.deleteModel(model) },
                        onTap = { previewModel = model },
                    )
                }
            }

            val notDownloaded = visible.filterNot { it.isDownloaded }
            item {
                // 清单来源：用户导入的模型库插件（APK 里一条都不带）。
                // 条数不同（内置 34 / 官方插件 83），说清楚就不会有人以为是重复了。
                val packs by viewModel.modelPacks.collectAsState()
                val packSources = packs.values.map { p ->
                    p.packName.ifBlank { p.packId } + "\u00a0" + p.models.size + " 条"
                }
                ModelsSectionHeader(
                    "可下载（${notDownloaded.size}/${models.size}）· ${category.hint}",
                    subtitle = if (packSources.isEmpty()) {
                        null
                    } else {
                        "清单来源：" + packSources.joinToString("、")
                    },
                )
            }
            items(notDownloaded, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    active = false,
                    downloadState = downloads[model.id],
                    ggufPreview = null,
                    plan = null,
                    onLoad = { viewModel.loadModel(model) },
                    onDownload = { viewModel.downloadAny(model) },
                    onPause = { viewModel.pauseDownload(model) },
                    onDelete = { viewModel.deleteModel(model) },
                    onTap = { previewModel = model },
                )
            }

            item {
                ModelsSectionHeader("导入自己的模型")
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ios.secondaryGroupedBackground)
                        .padding(14.dp)
                ) {
                    Text(
                        "支持任意 GGUF。三种方式：",
                        style = IosTheme.typography.footnote,
                        color = ios.secondaryLabel,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ios.fillSecondary)
                            .clickable { showImport = !showImport }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.CloudDownload, contentDescription = null, tint = ios.blue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "① 粘贴 HuggingFace 仓库 / 直链",
                            style = IosTheme.typography.subheadline,
                            color = ios.label,
                            modifier = Modifier.weight(1f),
                        )
                        Text(if (showImport) "收起" else "展开", style = IosTheme.typography.caption1, color = ios.blue)
                    }
                    if (showImport) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, ios.separator, RoundedCornerShape(10.dp))
                                .background(ios.groupedBackground)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            if (repoInput.isEmpty()) {
                                Text(
                                    "例如 Qwen/Qwen2.5-7B-Instruct-GGUF 或完整 resolve/main 直链",
                                    style = IosTheme.typography.footnote,
                                    color = ios.tertiaryLabel,
                                )
                            }
                            BasicTextField(
                                value = repoInput,
                                onValueChange = { repoInput = it },
                                textStyle = LocalTextStyle.current.merge(IosTheme.typography.footnote).copy(color = ios.label),
                                cursorBrush = SolidColor(ios.blue),
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (repoInput.isBlank()) ios.fill else ios.blue)
                                .clickable(enabled = repoInput.isNotBlank()) {
                                    viewModel.importFromRepo(repoInput)
                                    repoInput = ""
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("解析并下载", style = IosTheme.typography.headline, color = ios.onAccent)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "② 用 adb 推送（推荐大文件）：\n" +
                            "adb push model.gguf /sdcard/Download/\n" +
                            "然后在应用内用文件选择器导入；\n" +
                            "③ 直接把文件放到 " + storage.modelsDir.absolutePath,
                        style = IosTheme.typography.caption1,
                        color = ios.secondaryLabel,
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(ios.fillSecondary)
                            .clickable { viewModel.toast("请把 GGUF 复制到 Download 目录后用系统文件选择器打开") }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.FolderOpen, contentDescription = null, tint = ios.orange, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("查看导入说明", style = IosTheme.typography.subheadline, color = ios.label)
                    }
                }
            }

            item {
                Text(
                    "模型均来自公开仓库（HF / ModelScope），下载后完全离线运行。" +
                        "体积为官方文件实测大小，下载支持断点续传与镜像回退。" +
                        "\n\n「语音」分类里是离线音色包：下载走的不是 GGUF 那一套，" +
                        "它需要先装「语音引擎」，下完在「我的 → 语音与音色」里选。",
                    style = IosTheme.typography.caption1,
                    color = ios.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
                )
            }
        }
    }

    previewModel?.let { model ->
        ModelDetailSheet(
            model = model,
            plan = viewModel.memoryPlanFor(model),
            preview = viewModel.ggufPreview(model),
            onDismiss = { previewModel = null },
            onLoad = {
                viewModel.loadModel(model)
                previewModel = null
            },
            onDelete = {
                viewModel.deleteModel(model)
                previewModel = null
            },
            onDownload = {
                viewModel.downloadAny(model)
                previewModel = null
            },
        )
    }
}

@Composable
private fun ModelsSectionHeader(text: String, subtitle: String? = null) {
    val ios = IosTheme.palette
    Column(Modifier.padding(start = 32.dp, end = 16.dp, top = 20.dp, bottom = 7.dp)) {
        Text(text = text, style = IosTheme.typography.footnote, color = ios.secondaryLabel)
        // 副标题用来回答「这些模型是从哪来的」。
        //
        // 内测的人真的问过：「这个模型插件没装，怎么列表还会显示？」——
        // 因为模型清单来自插件，而以前 APK 里塞过一份 34 条的兜底清单。
        // 页面上只写「可下载（19/34）」，34 这个数字没有任何解释。
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = IosTheme.typography.caption2, color = ios.tertiaryLabel)
        }
    }
}

@Composable
private fun StorageSummary(
    modelBytes: Long,
    imageBytes: Long,
    freeBytes: Long,
    loadedName: String?,
    context: Int,
    threads: Int,
    onUnload: () -> Unit,
) {
    val ios = IosTheme.palette
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ios.secondaryGroupedBackground)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Storage, contentDescription = null, tint = ios.indigo, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("存储", style = IosTheme.typography.headline, color = ios.label, modifier = Modifier.weight(1f))
            Text(
                "可用 ${Formatters.size(freeBytes)}",
                style = IosTheme.typography.footnote,
                color = ios.secondaryLabel,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "模型 ${Formatters.size(modelBytes)} · 生成图片 ${Formatters.size(imageBytes)}",
            style = IosTheme.typography.footnote,
            color = ios.secondaryLabel,
        )

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Memory, contentDescription = null, tint = ios.green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    loadedName ?: "未加载模型",
                    style = IosTheme.typography.headline,
                    color = ios.label,
                )
                Text(
                    loadedName?.let { "上下文 $context · $threads 线程 · mmap 流式加载" }
                        ?: "加载后会显示内存与上下文规划",
                    style = IosTheme.typography.caption1,
                    color = ios.secondaryLabel,
                )
            }
            if (loadedName != null) {
                Text(
                    "卸载",
                    style = IosTheme.typography.footnote,
                    color = ios.red,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ios.red.copy(alpha = 0.12f))
                        .clickable(onClick = onUnload)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: LocalModel,
    active: Boolean,
    downloadState: com.localmind.service.DownloadState?,
    ggufPreview: String?,
    plan: com.localmind.data.ModelMemoryPlan?,
    onLoad: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onTap: () -> Unit,
) {
    val ios = IosTheme.palette
    val status = downloadState?.status ?: DownloadStatus.IDLE
    val downloading = status == DownloadStatus.RUNNING || status == DownloadStatus.QUEUED ||
        status == DownloadStatus.VERIFYING

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(ios.secondaryGroupedBackground)
            .clickable(onClick = onTap)
            .padding(14.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(modelColor(model, ios).copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = model.params.ifEmpty { "GG" }.take(4),
                        style = IosTheme.typography.caption2,
                        color = modelColor(model, ios),
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            model.name,
                            style = IosTheme.typography.headline,
                            color = ios.label,
                            maxLines = 1,
                        )
                        if (active) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                AppIcons.Check,
                                contentDescription = "已加载",
                                tint = ios.green,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                    Text(
                        buildString {
                            // 已下载的以磁盘真值为准：同一份文件被两条清单引用时，
                            // 声明值不一致会让用户看到「同一个文件两个体积」。
                            append(Formatters.size(model.displaySizeBytes))
                            if (model.quant.isNotBlank()) append(" · ${model.quant}")
                            if (model.license.isNotBlank()) append(" · ${model.license}")
                        },
                        style = IosTheme.typography.caption1,
                        color = ios.secondaryLabel,
                    )
                }

                when {
                    active -> IosPill("运行中", ios.green, filled = true)
                    model.isDownloaded -> IosPill("已下载", ios.blue)
                    downloading -> IosPill("下载中", ios.orange)
                    else -> IosPill("未下载", ios.gray)
                }
            }

            if (model.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    model.description,
                    style = IosTheme.typography.footnote,
                    color = ios.secondaryLabel,
                    maxLines = 2,
                )
            }

            if (!ggufPreview.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(ggufPreview, style = IosTheme.typography.caption2, color = ios.tertiaryLabel)
            }

            if (model.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.tags.take(4).forEach { tag ->
                        IosPill(tag, ios.indigo)
                    }
                }
            }

            if (downloading) {
                Spacer(Modifier.height(10.dp))
                val state = downloadState!!
                IosProgressBar(progress = state.progress, color = ios.orange)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Formatters.size(state.downloadedBytes)} / ${Formatters.size(state.totalBytes)}",
                        style = IosTheme.typography.caption1,
                        color = ios.secondaryLabel,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(state.speedText, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                    Spacer(Modifier.weight(1f))
                    Text(
                        state.etaText,
                        style = IosTheme.typography.caption1,
                        color = ios.tertiaryLabel,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "源 ${state.attempt}/${2 + model.mirrors.size}：${state.source.substringAfter("//").take(46)}",
                    style = IosTheme.typography.caption2,
                    color = ios.tertiaryLabel,
                    maxLines = 1,
                )
            }

            if (status == DownloadStatus.FAILED && downloadState?.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    downloadState.error,
                    style = IosTheme.typography.caption1,
                    color = ios.red,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    downloading -> {
                        PillButton("暂停", ios.orange, AppIcons.Pause, onPause)
                    }

                    model.isDownloaded -> {
                        PillButton(if (active) "已加载" else "加载", if (active) ios.gray else ios.blue, AppIcons.PlayArrow, onLoad)
                        PillButton("删除", ios.red, AppIcons.Delete, onDelete)
                    }

                    status == DownloadStatus.PAUSED -> {
                        PillButton("继续", ios.orange, AppIcons.PlayArrow, onDownload)
                        PillButton("删除", ios.red, AppIcons.Delete, onDelete)
                    }

                    else -> {
                        PillButton(
                            "下载 ${Formatters.size(model.sizeBytes)}",
                            ios.blue,
                            AppIcons.Download,
                            onDownload,
                        )
                    }
                }
                if (status == DownloadStatus.FAILED) {
                    PillButton("重试", ios.orange, AppIcons.Refresh, onDownload)
                }
            }
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = IosTheme.typography.footnote, color = color, fontWeight = FontWeight.SemiBold)
    }
}

private fun modelColor(model: LocalModel, ios: com.localmind.ui.theme.IosPalette): Color {
    val palette = ios.accents
    return palette[(model.id.hashCode().let { if (it < 0) -it else it }) % palette.size]
}

@Composable
private fun ModelDetailSheet(
    model: LocalModel,
    plan: com.localmind.data.ModelMemoryPlan?,
    preview: String,
    onDismiss: () -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    val ios = IosTheme.palette
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ios.secondaryGroupedBackground)
                .padding(18.dp)
        ) {
            Text(model.name, style = IosTheme.typography.title3, color = ios.label)
            Spacer(Modifier.height(4.dp))
            Text(model.repoId, style = IosTheme.typography.caption1, color = ios.secondaryLabel)

            Spacer(Modifier.height(14.dp))
            DetailRow("文件", model.fileName)
            DetailRow("体积", Formatters.size(model.displaySizeBytes))
            DetailRow("量化", model.displayQuant)
            DetailRow("许可", model.license)
            DetailRow("发行", model.publisher)
            if (preview.isNotBlank()) DetailRow("GGUF", preview)
            if (plan != null) {
                DetailRow("建议上下文", "${plan.recommendedContext} tokens")
                DetailRow("建议线程", "${plan.recommendedThreads}")
                if (plan.lowEndNote.isNotBlank()) DetailRow("内存模式", plan.lowEndNote)
                DetailRow(
                    "内存策略",
                    if (plan.needsStreaming) "超过可用内存 → mmap 磁盘流式加载" else "可全部驻留内存",
                )
                plan.warnings.forEach {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = IosTheme.typography.caption1, color = ios.warningText)
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.isDownloaded) {
                    PillButton("加载", ios.blue, AppIcons.PlayArrow, onLoad)
                    PillButton("删除", ios.red, AppIcons.Delete, onDelete)
                } else {
                    PillButton("下载", ios.blue, AppIcons.Download, onDownload)
                }
                PillButton("关闭", ios.gray, AppIcons.Check, onDismiss)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val ios = IosTheme.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = IosTheme.typography.footnote, color = ios.secondaryLabel, modifier = Modifier.width(84.dp))
        Text(value, style = IosTheme.typography.footnote, color = ios.label)
    }
}

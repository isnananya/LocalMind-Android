package com.localmind.ui.design

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.data.LocalModel
import com.localmind.service.DownloadState
import com.localmind.service.DownloadStatus
import com.localmind.service.EngineState
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.pressScale
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import com.localmind.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 模型页。
 *
 * 普通用户只需要看三件事：**现在用的是哪个**、**还能装哪些**、**装完怎么加载**。
 * 分类 chip、存储饼图、内存规划说明全部收进详情面板 —— 主界面不做技术展示。
 */
@Composable
fun ModelsPage(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val models by viewModel.models.collectAsState()
    val engine by viewModel.engineState.collectAsState()
    val downloads by viewModel.downloadStates.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val downloaded = remember(models) { models.filter { it.isDownloaded } }
    val available = remember(models) { models.filterNot { it.isDownloaded } }
    // -1 = 还没手动切过。默认落在「已安装」；如果没有已安装的就落在「可下载」。
    // 用派生值而不是 remember 初值：模型是异步扫出来的，首帧 downloaded 一定是空。
    var pickedSegment by remember { mutableStateOf(-1) }
    val segment = if (pickedSegment >= 0) pickedSegment else if (downloaded.isEmpty()) 1 else 0
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<LocalModel?>(null) }
    var linking by remember { mutableStateOf(false) }
    var linkText by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            viewModel.toast("正在导入…")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryName(context, uri)
                    val dst = File(context.cacheDir, "import-" + System.currentTimeMillis() + "-" + name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { out -> input.copyTo(out) }
                    }
                    if (dst.length() <= 0L) return@runCatching false
                    viewModel.importModel(dst)
                    dst.delete()
                    true
                }.getOrDefault(false)
            }
            if (!ok) viewModel.toast("导入失败，换一个文件试试")
        }
    }

    val shown = (if (segment == 0) downloaded else available).filter { m ->
        query.isBlank() ||
            m.name.contains(query, true) ||
            m.family.contains(query, true) ||
            m.tags.any { it.contains(query, true) }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(statusBarTopPadding() + 8.dp))
        AuroraTitle(
            text = "模型",
            trailing = {
                AuroraCircleButton(
                    icon = AppIcons.Refresh,
                    contentDescription = "刷新",
                    onClick = { viewModel.refreshModels() },
                )
            },
        )

        ActiveModelCard(
            engine = engine,
            downloaded = downloaded,
            onLoad = { model -> viewModel.loadModel(model) },
            onUnload = { viewModel.unloadModel() },
            onPick = { pickedSegment = 0 },
        )

        if (models.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            AuroraSegmented(
                options = listOf("已安装 " + downloaded.size, "可下载 " + available.size),
                selected = segment,
                onSelect = { pickedSegment = it },
            )
            AuroraSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索模型",
            )
        }

        when {
            models.isEmpty() -> Box(Modifier.fillMaxSize()) {
                AuroraEmpty(
                    icon = AppIcons.Memory,
                    title = "还没有模型",
                    message = "导入「官方模型库插件」获取推荐列表，\n或者直接导入一个 GGUF 文件。",
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AuroraButton("模型库插件", onClick = { viewModel.openPlugins(true) })
                            AuroraButton(
                                "导入文件",
                                onClick = { picker.launch(arrayOf("*/*")) },
                                color = ios.fill,
                            )
                        }
                    },
                )
            }

            shown.isEmpty() -> Box(Modifier.fillMaxSize()) {
                AuroraEmpty(
                    icon = if (segment == 0) AppIcons.Storage else AppIcons.CloudDownload,
                    title = if (segment == 0) "还没有下载模型" else "没有匹配的模型",
                    message = if (segment == 0) "切到「可下载」挑一个" else null,
                    action = if (segment == 0) {
                        { AuroraTextButton("去挑一个", onClick = { pickedSegment = 1 }) }
                    } else null,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.id }) { model ->
                    ModelRow(
                        model = model,
                        active = engine.ready && engine.modelName == model.name,
                        state = downloads[model.id],
                        onClick = { detail = model },
                        onDownload = { viewModel.downloadAny(model) },
                        onPause = { viewModel.pauseDownload(model) },
                        onLoad = { viewModel.loadModel(model) },
                    )
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    AuroraSectionLabel("添加自己的模型")
                    AuroraGroup {
                        AuroraRow(
                            title = "从文件导入",
                            subtitle = "支持任意 GGUF",
                            leading = { AuroraIcon(AppIcons.FolderOpen, ios.orange) },
                            onClick = { picker.launch(arrayOf("*/*")) },
                        )
                        AuroraDivider(indent = 58.dp)
                        AuroraRow(
                            title = "从链接导入",
                            subtitle = "HuggingFace 仓库或直链",
                            leading = { AuroraIcon(AppIcons.CloudDownload, ios.blue) },
                            onClick = { linking = true },
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    detail?.let { model ->
        ModelDetailSheet(
            viewModel = viewModel,
            model = model,
            onDismiss = { detail = null },
        )
    }

    if (linking) {
        LinkImportDialog(
            value = linkText,
            onValueChange = { linkText = it },
            onDismiss = { linking = false },
            onConfirm = {
                viewModel.importFromRepo(linkText)
                linkText = ""
                linking = false
            },
        )
    }
}

@Composable
private fun ActiveModelCard(
    engine: EngineState,
    downloaded: List<LocalModel>,
    onLoad: (LocalModel) -> Unit,
    onUnload: () -> Unit,
    onPick: () -> Unit,
) {
    val ios = IosTheme.palette
    val active = downloaded.firstOrNull { it.name == engine.modelName }
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Aurora.PagePadding, vertical = 12.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        ios.blue.copy(alpha = if (ios.isDark) 0.30f else 0.14f),
                        ios.purple.copy(alpha = if (ios.isDark) 0.22f else 0.09f),
                    )
                )
            )
            .border(0.6.dp, ios.blue.copy(alpha = 0.18f), shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(ios.blue.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Memory, contentDescription = null, tint = ios.blue, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (engine.ready) (engine.modelName ?: "已加载") else "未加载模型",
                    style = IosTheme.typography.headline,
                    color = ios.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AuroraDot(if (engine.ready) ios.green else ios.gray, size = 6.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = when {
                            engine.loading -> "正在加载…"
                            engine.ready -> buildString {
                                append("已就绪")
                                if (engine.contextSize > 0) append(" · 上下文 ").append(engine.contextSize)
                                if (engine.lastTokensPerSecond > 0) {
                                    append(" · ")
                                    append(String.format(Locale.US, "%.1f", engine.lastTokensPerSecond))
                                    append(" tok/s")
                                }
                            }
                            downloaded.isEmpty() -> "先下载一个模型"
                            else -> "选一个模型加载"
                        },
                        style = IosTheme.typography.footnote,
                        color = ios.secondaryLabel,
                    )
                }
            }
            when {
                engine.loading -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = ios.blue,
                )
                engine.ready -> AuroraButton("卸载", onClick = onUnload, color = ios.fill)
                active != null -> AuroraButton("加载", onClick = { onLoad(active) })
                downloaded.isNotEmpty() -> AuroraButton("选择", onClick = onPick, color = ios.fill)
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: LocalModel,
    active: Boolean,
    state: DownloadState?,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onLoad: () -> Unit,
) {
    val ios = IosTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val total = state?.totalBytes ?: 0L
    val done = state?.downloadedBytes ?: 0L
    val progress = if (total > 0) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else (state?.progress ?: 0f)
    val running = state?.status == DownloadStatus.RUNNING || state?.status == DownloadStatus.QUEUED ||
        state?.status == DownloadStatus.VERIFYING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Aurora.PagePadding)
            .pressScale(interaction)
            .clip(RoundedCornerShape(18.dp))
            .background(ios.secondaryGroupedBackground)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ios.blue.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = model.params.ifBlank { "GGUF" }.take(6),
                style = IosTheme.typography.caption2.copy(fontWeight = FontWeight.SemiBold),
                color = ios.blue,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.name,
                    style = IosTheme.typography.subheadline.copy(fontWeight = FontWeight.Medium),
                    color = ios.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    AuroraDot(ios.green, size = 6.dp)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    if (model.family.isNotBlank()) append(model.family).append(" · ")
                    append(model.displayQuant)
                    append(" · ").append(Formatters.size(model.displaySizeBytes))
                },
                style = IosTheme.typography.caption2,
                color = ios.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (running) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(ios.fill)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(progress)
                                .height(4.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(ios.blue)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = (progress * 100).toInt().toString() + "%",
                        style = IosTheme.typography.caption2,
                        color = ios.secondaryLabel,
                    )
                }
            } else if (state?.status == DownloadStatus.FAILED) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "下载失败，点一下重试",
                    style = IosTheme.typography.caption2,
                    color = ios.red,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        when {
            running -> AuroraCircleButton(
                icon = AppIcons.Pause,
                contentDescription = "暂停",
                onClick = onPause,
                tint = ios.secondaryLabel,
            )
            model.isDownloaded -> AuroraCircleButton(
                icon = AppIcons.PlayArrow,
                contentDescription = "加载",
                onClick = onLoad,
                tint = ios.blue,
                filled = false,
            )
            else -> AuroraCircleButton(
                icon = AppIcons.CloudDownload,
                contentDescription = "下载",
                onClick = onDownload,
                tint = ios.blue,
                filled = false,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDetailSheet(
    viewModel: AppViewModel,
    model: LocalModel,
    onDismiss: () -> Unit,
) {
    val ios = IosTheme.palette
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val plan = remember(model.id) { viewModel.memoryPlanFor(model) }
    val preview = remember(model.id) { viewModel.ggufPreview(model) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ios.secondaryGroupedBackground,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ios.fill)
            )
        },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(model.name, style = IosTheme.typography.title2, color = ios.label)
            Spacer(Modifier.height(4.dp))
            Text(
                listOf(model.publisher, model.params, model.displayQuant, Formatters.size(model.displaySizeBytes))
                    .filter { it.isNotBlank() }.distinct().joinToString(" · "),
                style = IosTheme.typography.footnote,
                color = ios.secondaryLabel,
            )
            if (model.description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(model.description, style = IosTheme.typography.subheadline, color = ios.secondaryLabel)
            }
            Spacer(Modifier.height(16.dp))
            InfoLine("建议内存", "≥ " + model.recommendedRamGb + " GB")
            if (plan != null) {
                InfoLine("文件大小", Formatters.size(plan.fileBytes))
                InfoLine("建议上下文", plan.recommendedContext.toString())
                InfoLine("能否常驻", if (plan.fitsInRam) "可以" else "需要换入换出")
            }
            if (preview.isNotBlank()) {
                InfoLine("文件信息", preview.take(120))
            }
            InfoLine("许可", model.license)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    model.isDownloaded -> {
                        AuroraButton("加载", onClick = {
                            viewModel.loadModel(model)
                            onDismiss()
                        }, modifier = Modifier.weight(1f))
                        AuroraButton("删除", onClick = {
                            viewModel.deleteModel(model)
                            onDismiss()
                        }, color = ios.red.copy(alpha = 0.12f), modifier = Modifier.weight(1f))
                    }
                    else -> {
                        AuroraButton(
                            text = if (model.sizeBytes > 0) "下载 · " + Formatters.size(model.displaySizeBytes) else "下载",
                            onClick = {
                                viewModel.downloadAny(model)
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        AuroraButton("取消", onClick = onDismiss, color = ios.fill, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val ios = IosTheme.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = IosTheme.typography.subheadline, color = ios.secondaryLabel, modifier = Modifier.width(84.dp))
        Text(value, style = IosTheme.typography.subheadline, color = ios.label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LinkImportDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val ios = IosTheme.palette
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从链接导入", style = IosTheme.typography.headline) },
        text = {
            Column {
                Text(
                    "支持 HuggingFace 仓库地址或 GGUF 直链。",
                    style = IosTheme.typography.footnote,
                    color = ios.secondaryLabel,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ios.fillSecondary)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (value.isEmpty()) {
                        Text(
                            "Qwen/Qwen2.5-7B-Instruct-GGUF",
                            style = IosTheme.typography.footnote,
                            color = ios.tertiaryLabel,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = IosTheme.typography.footnote.copy(color = ios.label),
                        cursorBrush = SolidColor(ios.blue),
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = value.isNotBlank(),
                onClick = onConfirm,
            ) { Text("下载") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun queryName(context: android.content.Context, uri: android.net.Uri): String {
    val fallback = "model.gguf"
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        } ?: uri.lastPathSegment ?: fallback
    }.getOrDefault(fallback).ifBlank { fallback }
}

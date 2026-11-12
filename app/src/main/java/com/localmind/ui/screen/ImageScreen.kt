package com.localmind.ui.screen

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.data.ImageModelEntry
import com.localmind.data.ImageModels
import com.localmind.data.ImageStyle
import com.localmind.ui.AppViewModel
import com.localmind.ui.design.AuroraButton
import com.localmind.ui.design.AuroraNavBar
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import java.io.File
import java.util.Locale

/**
 * 生图页 —— 生成 / 模型 / 图库，一个页面三段。
 *
 * ## 为什么重做
 *
 * 旧版把提示词、四个滑块、后端插件管理、模型列表、图库网格全塞进一个
 * LazyColumn：用户要滚三屏才能看到自己刚生成的图。而且它的状态
 * （生成中、进度、结果）全在 `remember` 里 —— 切一次标签回来就没了，
 * 看起来像"图丢了"。
 *
 * 现在拆成三段，状态全部搬进 [AppViewModel]：
 *  * **生成**：只有"输入 → 出图 → 看图"这一条线；
 *  * **模型**：checkpoint 的下载 / 选择 / 删除（1.6 GB 起，必须能看进度）；
 *  * **图库**：历史成品。
 *
 * ## 后端
 *
 * `liblocalmind-sd.so` 现在**打包在 APK 里**（见 ImageBridge），装完就能出图，
 * 不用再下载插件。模型仍然要下 —— 一个 1.6 GB 的 checkpoint 没法塞进 APK。
 */
enum class ImageTab(val label: String) {
    GEN("生成"), MODEL("模型"), GALLERY("图库")
}

@Composable
fun ImageScreen(viewModel: AppViewModel, onBack: () -> Unit = {}) {
    val ios = IosTheme.palette
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(ImageTab.GEN) }
    val backendReady by viewModel.diffusionBackendReady.collectAsState()
    val preview by viewModel.imgPreview.collectAsState()
    val models by viewModel.diffusionModels.collectAsState()

    val status = when {
        !backendReady -> "后端未就绪"
        models.isEmpty() -> "缺模型"
        else -> "就绪"
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            AuroraNavBar(
                title = "生图",
                onBack = onBack,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            status,
                            color = if (backendReady && models.isNotEmpty()) ios.green else ios.secondaryLabel,
                            fontSize = 11.sp,
                        )
                        if (models.isEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(ios.orange.copy(alpha = 0.16f))
                                    .clickable { tab = ImageTab.MODEL }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) { Text("下模型", color = ios.orange, fontSize = 11.sp) }
                        }
                    }
                },
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ios.fillSecondary)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                ImageTab.entries.forEach { t ->
                    val sel = t == tab
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) ios.secondaryGroupedBackground else Color.Transparent)
                            .clickable { tab = t }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            t.label,
                            color = if (sel) ios.label else ios.secondaryLabel,
                            fontSize = 13.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            when (tab) {
                ImageTab.GEN -> GenSection(viewModel) { tab = ImageTab.MODEL }
                ImageTab.MODEL -> ModelSection(viewModel)
                ImageTab.GALLERY -> GallerySection(viewModel)
            }
        }

        preview?.let { f ->
            ImageFullScreen(
                file = f,
                onClose = { viewModel.closeImagePreview() },
                onSave = { viewModel.toast(if (viewModel.exportImage(f)) "已保存到相册" else "保存失败") },
                onShare = { com.localmind.ui.components.shareImage(ctx, f) },
                onDelete = {
                    viewModel.deleteImage(f)
                    viewModel.toast("已删除")
                },
            )
        }
    }
}

/* ------------------------------------------------------------------ 生成 */

@Composable
private fun GenSection(viewModel: AppViewModel, onNeedModel: () -> Unit) {
    val ios = IosTheme.palette
    val settings by viewModel.image.collectAsState()
    val models by viewModel.diffusionModels.collectAsState()
    val busy by viewModel.imgBusy.collectAsState()
    val step by viewModel.imgStep.collectAsState()
    val total by viewModel.imgTotal.collectAsState()
    val elapsed by viewModel.imgElapsed.collectAsState()
    val result by viewModel.imgResult.collectAsState()
    val note by viewModel.imgNote.collectAsState()
    val error by viewModel.imgError.collectAsState()
    val procedural by viewModel.imgProcedural.collectAsState()
    val backendReady by viewModel.diffusionBackendReady.collectAsState()

    var prompt by remember { mutableStateOf("") }
    var negativeShow by remember { mutableStateOf(false) }
    var paramsShow by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshDiffusionBackend()
        viewModel.refreshDiffusionModels()
        viewModel.refreshImageGallery()
    }

    val current = remember(models, settings.customModelPath) {
        settings.customModelPath?.let { p -> models.firstOrNull { it.absolutePath == p } }
            ?: models.firstOrNull()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 当前模型 ----
        item {
            StudioCard2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Brush, null, Modifier.size(15.dp), tint = ios.secondaryLabel)
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            current?.name ?: "还没有生图模型",
                            color = ios.label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when {
                                !backendReady -> "生图后端没起来，只能出内置渲染图"
                                current == null -> "去「模型」下 1.6 GB 的 SD 1.5 就能真出图"
                                else -> mb2(current.length()) + " · " + archHint(current.name)
                            },
                            color = if (current != null && backendReady) ios.secondaryLabel else ios.orange,
                            fontSize = 11.sp,
                        )
                    }
                    SmallBtn2("换") { onNeedModel() }
                }
            }
        }

        // ---- 提示词 ----
        item {
            StudioCard2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("提示词", color = ios.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "负面词",
                        color = if (negativeShow) ios.blue else ios.secondaryLabel, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { negativeShow = !negativeShow }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(96.dp)) {
                    if (prompt.isEmpty()) {
                        Text(
                            "一只在雨夜霓虹街头走路的柴犬，电影感，浅景深",
                            color = ios.tertiaryLabel, fontSize = 13.sp,
                        )
                    }
                    BasicTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = TextStyle(color = ios.label, fontSize = 14.sp, lineHeight = 21.sp),
                        cursorBrush = SolidColor(ios.blue),
                    )
                }
                if (negativeShow) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(ios.fill)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        if (settings.negativePrompt.isEmpty()) {
                            Text(
                                "留空 = 用默认（模糊、水印、畸形手、多肢体）",
                                color = ios.tertiaryLabel, fontSize = 12.sp,
                            )
                        }
                        BasicTextField(
                            value = settings.negativePrompt,
                            onValueChange = { v -> viewModel.updateImage { it.copy(negativePrompt = v) } },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            textStyle = TextStyle(color = ios.label, fontSize = 12.sp, lineHeight = 17.sp),
                            cursorBrush = SolidColor(ios.blue),
                        )
                    }
                }
            }
        }

        // ---- 风格 ----
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ImageStyle.entries.toList()) { s ->
                    ChoiceChip(s.label, settings.style == s) {
                        viewModel.updateImage { it.copy(style = s) }
                    }
                }
            }
        }

        // ---- 参数概览 ----
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { ChoiceChip("${settings.width}²", false) { paramsShow = !paramsShow } }
                item {
                    ChoiceChip(
                        if (settings.steps > 0) "${settings.steps} 步" else "步数自动",
                        false,
                    ) { paramsShow = !paramsShow }
                }
                item {
                    ChoiceChip(
                        String.format(Locale.US, "CFG %.1f", settings.guidance), false,
                    ) { paramsShow = !paramsShow }
                }
                item {
                    ChoiceChip(
                        if (settings.seed > 0) "种子 ${settings.seed}" else "随机种子", false,
                    ) { paramsShow = !paramsShow }
                }
            }
        }
        if (paramsShow) {
            item {
                StudioCard2(padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("边长", color = ios.secondaryLabel, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(384, 512, 640, 768, 1024).forEach { side ->
                            ChoiceChip("$side", settings.width == side) {
                                viewModel.updateImage { it.copy(width = side, height = side) }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("步数", color = ios.secondaryLabel, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 4, 8, 12, 16, 24).forEach { n ->
                            ChoiceChip(if (n == 0) "自动" else "$n", settings.steps == n) {
                                viewModel.updateImage { it.copy(steps = n) }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    SliderRow("CFG", settings.guidance, 1f..12f) {
                        viewModel.updateImage { v -> v.copy(guidance = it) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("种子", color = ios.secondaryLabel, fontSize = 11.sp, modifier = Modifier.width(38.dp))
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ios.fill)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                if (settings.seed > 0) settings.seed.toString() else "每次随机",
                                color = ios.label, fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        SmallBtn2("随机") { viewModel.updateImage { it.copy(seed = -1) } }
                    }
                    Spacer(Modifier.height(4.dp))
                    ToggleRow2("快速放大", "先按 512 出图再放大到目标尺寸，快一倍多，边缘略软", settings.fastUpscale) { on ->
                        viewModel.updateImage { it.copy(fastUpscale = on) }
                    }
                    ToggleRow2(
                        "Flash Attention",
                        "省内存（SD2 768² 约省 1.4 GB）。个别模型上算错时可以关掉",
                        settings.flashAttn,
                    ) { on -> viewModel.updateImage { it.copy(flashAttn = on) } }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // ---- 生成 ----
        item {
            AuroraButton(
                text = when {
                    busy && total > 0 -> "第 $step/$total 步 · ${"%.0f".format(Locale.US, elapsed / 1000f)}s"
                    busy -> "正在加载模型…"
                    else -> "生成图片"
                },
                onClick = { viewModel.generateImage(prompt) },
                enabled = !busy && backendReady,
                loading = busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (busy) {
            item {
                Column {
                    ProgressBar2(if (total > 0) step.toFloat() / total else 0f)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (total > 0) {
                            "已用 ${"%.0f".format(Locale.US, elapsed / 1000f)}s" +
                                if (step > 0) " · 每步约 ${"%.1f".format(Locale.US, elapsed / 1000f / step)}s" else ""
                        } else {
                            "第一次加载模型要读 1.6 GB，慢在这里是正常的"
                        },
                        color = ios.tertiaryLabel, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("可以切到别的页签，出图不会中断。", color = ios.tertiaryLabel, fontSize = 11.sp)
                }
            }
        }

        error?.let { msg ->
            item {
                StudioCard2 {
                    Text("出图失败", color = ios.red, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    Text(msg, color = ios.secondaryLabel, fontSize = 11.sp)
                }
            }
        }

        result?.let { f ->
            item {
                StudioCard2(padding = PaddingValues(10.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { viewModel.openImagePreview(f) },
                    ) {
                        val bmp = rememberImage(f, 960)
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Box(Modifier.fillMaxWidth().height(180.dp).background(ios.fill))
                        }
                    }
                    if (procedural) {
                        Spacer(Modifier.height(8.dp))
                        Text("这张不是模型画的 —— 下面写了原因。", color = ios.orange, fontSize = 11.sp)
                    }
                    if (note.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(note, color = ios.secondaryLabel, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallBtn2("保存相册") {
                            viewModel.toast(if (viewModel.exportImage(f)) "已保存到相册" else "保存失败")
                        }
                        SmallBtn2("用回种子") { viewModel.reuseSeedOf(f) }
                        SmallBtn2("再来一张") { viewModel.generateImage(prompt) }
                        Spacer(Modifier.weight(1f))
                        SmallBtn2("删除", danger = true) { viewModel.deleteImage(f) }
                    }
                }
            }
        }

        if (current == null) {
            item {
                HintCard2(
                    if (!backendReady) "这个包的生图后端没起来（可能是 ABI 不支持）。去「模型」看看。"
                    else "还没下载生图模型。SD 1.5 Q4_0 约 1.6 GB，下完立刻能出图。",
                    "去下载",
                ) { onNeedModel() }
            }
        }
    }
}

/* ------------------------------------------------------------------ 模型 */

@Composable
private fun ModelSection(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val models by viewModel.diffusionModels.collectAsState()
    val settings by viewModel.image.collectAsState()
    val dlStates by viewModel.imageDownloadStates.collectAsState()
    val backendReady by viewModel.diffusionBackendReady.collectAsState()
    val bundled = viewModel.hasDiffusionBundled

    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        viewModel.refreshDiffusionBackend()
        viewModel.refreshDiffusionModels()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            StudioCard2 {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "生图后端（stable-diffusion.cpp）",
                            color = ios.label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            when {
                                backendReady && bundled -> "随包内置 · 已就绪，不用再装插件"
                                backendReady -> "插件已加载"
                                else -> "不可用（这个 ABI 没打包后端）"
                            },
                            color = if (backendReady) ios.green else ios.orange,
                            fontSize = 11.sp,
                        )
                    }
                    if (!backendReady) SmallBtn2("重试") { tick++ }
                }
            }
        }

        item {
            Text(
                "下载走「设置 → 下载代理」，服务器支持时自动多线程分段。" +
                    "模型目录：models/（也可以自己把 .gguf 丢进去）",
                color = ios.secondaryLabel, fontSize = 11.sp,
            )
        }

        item {
            Text("已安装", color = ios.secondaryLabel, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }

        if (models.isEmpty()) {
            item { Text("还没有。下面挑一个下载。", color = ios.tertiaryLabel, fontSize = 12.sp) }
        }
        items(models) { f ->
            val active = (settings.customModelPath?.isNotBlank() == true &&
                settings.customModelPath == f.absolutePath) ||
                (settings.customModelPath.isNullOrBlank() && models.firstOrNull() == f)
            StudioCard2(stroke = if (active) ios.blue else ios.separator) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                f.name, color = ios.label, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (active) {
                                Spacer(Modifier.width(6.dp))
                                Tag2("使用中", ios.blue)
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            mb2(f.length()) + " · " + archHint(f.name),
                            color = ios.secondaryLabel, fontSize = 10.sp,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (!active) {
                        SmallBtn2("使用") {
                            viewModel.updateImage { it.copy(customModelPath = f.absolutePath) }
                            viewModel.toast("已切到 " + f.name)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    SmallBtn2("删除", danger = true) {
                        val entry = ImageModels.byFileName(f.name)
                        if (entry != null) {
                            viewModel.deleteImageModel(entry)
                        } else {
                            runCatching { f.delete() }
                            viewModel.refreshDiffusionModels()
                            viewModel.toast("已删除")
                        }
                    }
                }
            }
        }

        item {
            Text("可下载", color = ios.secondaryLabel, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }

        items(ImageModels.all) { entry ->
            DownloadRow(entry, viewModel, dlStates[entry.id])
        }
    }
}

@Composable
private fun DownloadRow(
    entry: ImageModelEntry,
    viewModel: AppViewModel,
    st: com.localmind.service.ImageModelDownloader.State?,
) {
    val ios = IosTheme.palette
    val installed = viewModel.imageModelInstalled(entry)
    StudioCard2 {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.label, color = ios.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(6.dp))
                    Tag2(entry.badge)
                }
                Spacer(Modifier.height(3.dp))
                Text(entry.note, color = ios.secondaryLabel, fontSize = 10.sp, lineHeight = 14.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    when {
                        st?.busy == true -> progressLine2(st.downloadedBytes, st.totalBytes, st.bytesPerSecond)
                        installed -> mb2(viewModel.imageModelBytes(entry)) + " · 已就绪"
                        st?.status == com.localmind.service.ImageModelDownloader.Status.FAILED ->
                            "下载失败：" + (st.error ?: "网络问题")
                        else -> mb2(entry.sizeBytes)
                    },
                    color = when {
                        st?.busy == true -> ios.blue
                        installed -> ios.green
                        st?.status == com.localmind.service.ImageModelDownloader.Status.FAILED -> ios.orange
                        else -> ios.secondaryLabel
                    },
                    fontSize = 10.sp, maxLines = 2,
                )
                if (st?.busy == true) {
                    Spacer(Modifier.height(6.dp))
                    ProgressBar2(st.progress)
                }
            }
            Spacer(Modifier.width(10.dp))
            when {
                st?.busy == true -> SmallBtn2("取消") { viewModel.cancelImageModel(entry) }
                installed -> SmallBtn2("使用") {
                    val f = File(viewModel.storage.modelsDir, entry.fileName)
                    viewModel.updateImage { it.copy(customModelPath = f.absolutePath) }
                    viewModel.toast("已切到 " + entry.label)
                }
                else -> SmallBtn2("下载", primary = true) { viewModel.downloadImageModel(entry) }
            }
        }
    }
}

/* ------------------------------------------------------------------ 图库 */

@Composable
private fun GallerySection(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val gallery by viewModel.imgGallery.collectAsState()
    LaunchedEffect(Unit) { viewModel.refreshImageGallery() }

    if (gallery.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(AppIcons.Image, null, Modifier.size(34.dp), tint = ios.tertiaryLabel)
                Spacer(Modifier.height(10.dp))
                Text("还没有生成过图", color = ios.secondaryLabel, fontSize = 13.sp)
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(gallery.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { f ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ios.fill)
                            .clickable { viewModel.openImagePreview(f) },
                    ) {
                        val bmp = rememberImage(f, 360)
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/* ------------------------------------------------------------------ 组件 */

@Composable
private fun rememberImage(file: File, target: Int) = remember(file, file.lastModified()) {
    runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= target || opts.outHeight / (sample * 2) >= target) sample *= 2
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()
}

@Composable
private fun ImageFullScreen(
    file: File,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .clickable { onClose() },
    ) {
        val bmp = rememberImage(file, 1440)
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().align(Alignment.Center),
                contentScale = ContentScale.Fit,
            )
        }
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallBtn2("保存") { onSave() }
            SmallBtn2("分享") { onShare() }
            SmallBtn2("删除", danger = true) { onDelete() }
        }
        Text(
            file.name,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StudioCard2(
    stroke: Color? = null,
    padding: PaddingValues = PaddingValues(14.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val ios = IosTheme.palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ios.secondaryGroupedBackground)
            .border(1.dp, stroke ?: ios.separator, RoundedCornerShape(12.dp))
            .padding(padding),
        content = content,
    )
}

@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) ios.blue.copy(alpha = 0.14f) else ios.fillSecondary)
            .border(1.dp, if (selected) ios.blue else Color.Transparent, RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            color = if (selected) ios.blue else ios.label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val ios = IosTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ios.secondaryLabel, fontSize = 11.sp, modifier = Modifier.width(38.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f).height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = ios.blue, activeTrackColor = ios.blue, inactiveTrackColor = ios.fill,
            ),
        )
        Text(
            String.format(Locale.US, "%.1f", value),
            color = ios.secondaryLabel, fontSize = 11.sp, modifier = Modifier.width(36.dp),
        )
    }
}

@Composable
private fun ToggleRow2(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val ios = IosTheme.palette
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ios.label, fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = ios.tertiaryLabel, fontSize = 9.sp, lineHeight = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .width(42.dp)
                .height(24.dp)
                .clip(CircleShape)
                .background(if (checked) ios.green else ios.fill),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(horizontal = 2.dp).size(20.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun SmallBtn2(
    text: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val ios = IosTheme.palette
    val bg = when {
        primary -> ios.blue
        danger -> ios.red.copy(alpha = 0.12f)
        else -> ios.fillSecondary
    }
    val fg = when {
        primary -> ios.onAccent
        danger -> ios.red
        else -> ios.label
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Tag2(text: String, color: Color? = null) {
    val ios = IosTheme.palette
    val c = color ?: ios.secondaryLabel
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) { Text(text, color = c, fontSize = 9.sp) }
}

@Composable
private fun ProgressBar2(fraction: Float) {
    val ios = IosTheme.palette
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(ios.fill),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ios.blue),
        )
    }
}

@Composable
private fun HintCard2(text: String, action: String, onAction: () -> Unit) {
    val ios = IosTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ios.orange.copy(alpha = 0.12f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = ios.orange, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        SmallBtn2(action) { onAction() }
    }
}

/* ------------------------------------------------------------------ 工具 */

private fun mb2(bytes: Long): String =
    if (bytes >= 1024L * 1024 * 1024) String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024 / 1024)
    else String.format(Locale.US, "%d MB", bytes / 1024 / 1024)

/** 从文件名粗判模型族 —— 只用来给用户一句"这大概是几代模型"，不参与逻辑。 */
private fun archHint(name: String): String {
    val n = name.lowercase(Locale.US)
    return when {
        n.contains("turbo") || n.contains("lcm") || n.contains("hyper") -> "少步模型（自动降步数）"
        n.contains("xl") -> "SDXL（吃内存，慎用）"
        n.contains("2-1") || n.contains("v2") -> "SD 2.1"
        n.contains("1-5") || n.contains("v1") -> "SD 1.5"
        else -> "扩散 checkpoint"
    }
}

private fun progressLine2(done: Long, total: Long, speed: Double): String {
    val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
    val sp = com.localmind.service.DownloadSources.speedText(speed)
    return buildString {
        append(mb2(done))
        if (total > 0) append(" / ").append(mb2(total)).append(" · ").append(pct).append("%")
        if (sp.isNotBlank()) append(" · ").append(sp)
    }
}

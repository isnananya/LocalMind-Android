package com.localmind.ui.screen

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localmind.data.ImageStyle
import com.localmind.service.ImageEngine
import com.localmind.ui.AppViewModel
import com.localmind.ui.Tab
import com.localmind.ui.components.IosLargeTitle
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.components.IosTag
import com.localmind.ui.components.ModelPickerSheet
import com.localmind.ui.components.PickerOption
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ImageScreen(viewModel: AppViewModel, onBack: () -> Unit = {}) {
    val ios = IosTheme.palette
    val settings by viewModel.image.collectAsState()
    val scope = rememberCoroutineScope()

    var prompt by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<File?>(null) }
    var gallery by remember { mutableStateOf(ImageEngine.listGenerated(viewModel.storage)) }

    // 上一张图**是怎么来的**。以前这里没有这个状态：兜底渲染出来的渐变图
    // 和真正的模型出图长得不一样，但界面上一句解释都没有，用户只会得出
    // "生图不能用"的结论 —— 而真实原因往往是"后端没装"或"没下模型"。
    var lastNote by remember { mutableStateOf<String?>(null) }
    var lastVia by remember { mutableStateOf<ImageEngine.Via?>(null) }

    // 生成中显示「已用多久 / 上次多久」。没有这一步，用户在手机上等一张图
    // 只能看到一个转圈 —— 分不清「在跑」和「卡死了」，而扩散出图本来就是几十秒到几分钟。
    var elapsedMs by remember { mutableStateOf(0L) }
    var lastTook by remember { mutableStateOf(0L) }
    var lastInfo by remember { mutableStateOf<String?>(null) }
    // 逐帧进度（通用版插件才会给）。没有时是 0，界面退回「已用 X 秒」。
    var stepNow by remember { mutableStateOf(0) }
    var stepTotal by remember { mutableStateOf(0) }

    // ---- 预热：把 checkpoint 的加载挪到后台 ----
    //
    // 加载一个 2 GB 的扩散模型要几十秒到两分钟，而它原来**算在第一张图的时间里**：
    // 用户点「生成图片」，看到的是转圈半分钟以上 —— 结论通常是「生图卡死了」。
    // 现在一进这一页就在后台把它读进来（同一个 key 的重复调用是空操作，见 sd_jni）。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (!com.localmind.native.image.ImageBridge.hasDiffusionBackend) return@runCatching
                val conv = viewModel.activeConversation()
                val path = settings.customModelPath?.takeIf { it.isNotBlank() }
                    ?: conv?.let { viewModel.imageModelPathFor(it) }
                    ?: ImageEngine.resolveDiffusionModel(viewModel.storage, settings)
                if (path.isNullOrBlank()) return@runCatching
                val ok = com.localmind.native.image.ImageBridge.loadDiffusionEx(
                    path,
                    com.localmind.data.MemoryPlanner.autoThreads(),
                    if (settings.flashAttn) "auto" else "off",
                )
                android.util.Log.i("ImageScreen", "生图模型预热：" + (if (ok) "完成" else "失败") + "（" + path + "）")
            }
        }
    }

    LaunchedEffect(generating) {
        if (!generating) {
            elapsedMs = 0L
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
        while (true) {
            elapsedMs = System.currentTimeMillis() - startedAt
            kotlinx.coroutines.delay(300)
        }
    }

    fun generate() {
        if (prompt.isBlank() || generating) return
        generating = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                // 用**当前生效的**生图模型（如果用户在对话里选过就优先它）。
                val conv = viewModel.activeConversation()
                ImageEngine.generateWithSettings(
                    storage = viewModel.storage,
                    prompt = prompt,
                    settings = settings,
                    // 在生图页「选择」过就用它；否则用对话里指定的；再否则让引擎自己扫。
                    modelPath = settings.customModelPath?.takeIf { it.isNotBlank() }
                        ?: conv?.let { viewModel.imageModelPathFor(it) },
                    onStep = { s, total ->
                        stepNow = s
                        stepTotal = total
                    },
                )
            }
            generating = false
            result.onSuccess { gen ->
                gallery = ImageEngine.listGenerated(viewModel.storage)
                preview = gen.file
                lastVia = gen.via
                lastNote = gen.note.ifBlank { null }
                lastInfo = if (gen.via == ImageEngine.Via.DIFFUSION) {
                    buildString {
                        if (gen.renderSize > 0 && gen.renderSize != settings.width) {
                            append(gen.renderSize).append("² 出图 → 放大到 ").append(settings.width)
                            append("² · ")
                        }
                        append(gen.steps).append(" 步 · ")
                        append(String.format(java.util.Locale.US, "%.1f", gen.elapsedMs / 1000.0)).append(" 秒")
                    }
                } else {
                    null
                }
                lastTook = gen.elapsedMs
                viewModel.toast(
                    if (gen.via == ImageEngine.Via.DIFFUSION) "已生成（模型出图）"
                    else "已生成（内置渲染器）"
                )
            }.onFailure { error = it.message ?: "生成失败" }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
    ) {
        IosNavigationBar(
            title = "生图",
            collapsed = true,
            topInset = statusBarTopPadding(),
            leading = {
                com.localmind.ui.design.AuroraCircleButton(
                    icon = AppIcons.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    size = 36.dp,
                    tint = ios.label,
                )
            },
            trailing = {
                com.localmind.ui.design.AuroraCircleButton(
                    icon = AppIcons.Tune,
                    contentDescription = "设置",
                    onClick = { viewModel.openSettings(true) },
                    size = 36.dp,
                    tint = ios.label,
                )
            },
        )
        IosLargeTitle("文字生图")

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
        ) {
            // 生图后端状态。这是用户最需要知道、也最容易困惑的一件事：
            // "我下了模型，为什么画出来的还是那种渐变图？"
            //
            // 答案通常是：没装推理后端。以前这个后端打在 APK 里，所以不存在
            // 这个问题；现在它是按需下载的（36 MB，只有真要用的人付），
            // 所以必须明确告诉用户它装没装。
            item { DiffusionBackendSection(viewModel) { gallery = ImageEngine.listGenerated(viewModel.storage) } }

            // 上一张图的出处。这是"生图不能用"这个反馈的正面回答：
            // 到底是模型画的，还是内置渲染器画的，以及为什么。
            lastVia?.let { via ->
                item {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (via == ImageEngine.Via.DIFFUSION) ios.green.copy(alpha = 0.14f)
                                else ios.orange.copy(alpha = 0.14f)
                            )
                            .padding(14.dp)
                    ) {
                        Text(
                            if (via == ImageEngine.Via.DIFFUSION) "这张是生图模型画的"
                            else "这张是内置渲染器画的",
                            style = IosTheme.typography.subheadline,
                            color = if (via == ImageEngine.Via.DIFFUSION) ios.green else ios.warningText,
                            fontWeight = FontWeight.SemiBold,
                        )
                        lastInfo?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                        }
                        lastNote?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                        }
                    }
                }
            }

            item {
                // 这个渲染器不「理解」提示词——必须先在界面上说清楚，
                // 否则用户会以为它坏了（它只是按关键词换配色和构图）。
                Column(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 12.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                        .background(ios.orange.copy(alpha = 0.14f))
                        .padding(14.dp)
                ) {
                    Text(
                        "这里不是 AI 生图",
                        style = IosTheme.typography.subheadline,
                        color = ios.warningText,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "没有扩散模型时用的是本地程序化渲染器：它只按提示词里的「关键词」换配色和构图，" +
                            "画不出「一只猫在雨中」这种具体内容。\n\n" +
                            "想要真正的 AI 生图，去「模型 → 生图」下载一个 SD-Turbo / LCM 模型（约 2GB）。" +
                            "扩散后端已经内置在 APK 里，不用另外安装。",
                        style = IosTheme.typography.footnote,
                        color = ios.secondaryLabel,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(ios.blue.copy(alpha = 0.14f))
                            .clickable { viewModel.selectTab(Tab.MODELS) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Download, contentDescription = null, tint = ios.blue, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "去模型页选「生图」分类",
                            style = IosTheme.typography.footnote,
                            color = ios.blue,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ios.secondaryGroupedBackground)
                        .padding(14.dp)
                ) {
                    Text("描述你想要的画面", style = IosTheme.typography.headline, color = ios.label)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ios.fillSecondary)
                            .padding(12.dp)
                    ) {
                        if (prompt.isEmpty()) {
                            Text(
                                "例如：夜晚的海边，星空，极光，电影感",
                                style = IosTheme.typography.footnote,
                                color = ios.tertiaryLabel,
                            )
                        }
                        BasicTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            textStyle = LocalTextStyle.current.merge(IosTheme.typography.body).copy(color = ios.label),
                            cursorBrush = SolidColor(ios.blue),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    // 说明英文这件事比再给一个开关重要：SD/SDXL 的 CLIP 只认英文，
                    // 中文提示词在它眼里接近噪声 —— 用户会以为"模型太差"。
                    Text(
                        "扩散模型只认英文提示词。中文写出来也能出图，但那基本是随机画面；" +
                            "想要精美，用英文描述（例：a cat sitting by the window, sunlight, detailed fur）。",
                        style = IosTheme.typography.caption2,
                        color = ios.tertiaryLabel,
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("负面提示词（可选）", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ios.fillSecondary)
                            .padding(12.dp)
                    ) {
                        if (settings.negativePrompt.isEmpty()) {
                            Text(
                                "留空 = 自动排除：低清 / 糊 / 水印 / 畸形手",
                                style = IosTheme.typography.footnote,
                                color = ios.tertiaryLabel,
                            )
                        }
                        BasicTextField(
                            value = settings.negativePrompt,
                            onValueChange = { v -> viewModel.updateImage { it.copy(negativePrompt = v) } },
                            textStyle = LocalTextStyle.current.merge(IosTheme.typography.footnote).copy(color = ios.label),
                            cursorBrush = SolidColor(ios.blue),
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("风格", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ImageStyle.entries.forEach { style ->
                            IosTag(
                                text = style.label,
                                selected = settings.style == style,
                                onClick = { viewModel.updateImage { it.copy(style = style) } },
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("分辨率", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // 512² 是默认值，也是性价比最高的一档：耗时差不多是 768² 的 44%，
                        // 而且正好是 SD 1.5 的训练分辨率（更大反而容易崩构图）。
                        listOf(512, 768, 1024).forEach { size ->
                            IosTag(
                                text = "${size}²",
                                selected = settings.width == size,
                                onClick = { viewModel.updateImage { it.copy(width = size, height = size) } },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "耗时和像素数成正比：768² 是 512² 的 2.25 倍，1024² 是 4 倍。",
                        style = IosTheme.typography.caption2,
                        color = ios.tertiaryLabel,
                    )

                    // 快速放大：大图先 512 出、再放大。默认开 —— 手机上这是
                    // 「要一张 768 的图」最划算的做法（快 2 倍多，细节差异很小）。
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("快速放大", style = IosTheme.typography.body, color = ios.label)
                            Text(
                                if (settings.width > 512) {
                                    "先用 512 出图再放大到 512 以上：快 2 倍多，细节略软"
                                } else {
                                    "目标就是 512 时这一项不生效（本来就没得省）"
                                },
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                        IosSwitch(
                            checked = settings.fastUpscale,
                            onCheckedChange = { on -> viewModel.updateImage { it.copy(fastUpscale = on) } },
                        )
                    }

                    // 后端插件是不是支持新入口（带步数 / 加速）。
                    // 判定放在这里而不是在 ViewModel：它只影响这一块的显示。
                    val extendedBackend = com.localmind.native.image.ImageBridge.hasExtendedDiffusion

                    // 步数：CPU 出图最大的一根杠杆，耗时几乎和它成正比。
                    // 这一栏以前**根本没有** —— ImageSettings.steps 既没有界面入口，
                    // 也从来没有被传给原生侧，所以 SD 2.1 一直按 20 步跑，
                    // 用户只能等（实测同一台机器：20 步 70 秒，8 步 28 秒）。
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (extendedBackend) "采样步数" else "采样步数（需要更新生图后端插件）",
                        style = IosTheme.typography.footnote,
                        color = if (extendedBackend) ios.secondaryLabel else ios.warningText,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 老插件（14 MB 那份）没有 nativeSdGenerateEx，传不进步数。
                    // 这时候**不能**让这一栏看起来能用 —— 那就是又一个「能拨但没用」。
                    if (!extendedBackend) {
                        // 两种「用不了」要分开说：**没加载**（点启用就行）与
                        // **插件版本旧**（要重新导入）。混成一句会让用户白跑一趟插件页。
                        val backendLoaded = com.localmind.native.image.ImageBridge.hasDiffusionBackend
                        Text(
                            if (!backendLoaded) {
                                "生图后端还没加载进来 —— 先点上面的「启用」，" +
                                    "加载之后再选步数才会生效。"
                            } else {
                                "当前装的生图后端不认识「步数」这个参数（插件版本较旧），" +
                                    "到「我的 → 插件」重新导入一个新生图后端插件即可。"
                            },
                            style = IosTheme.typography.caption2,
                            color = ios.warningText,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "自动", 4 to "4", 8 to "8", 15 to "15", 25 to "25").forEach { (v, label) ->
                            IosTag(
                                text = label,
                                selected = settings.steps == v,
                                onClick = { viewModel.updateImage { it.copy(steps = v) } },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (settings.steps == 0) {
                            "自动：按模型名判断 —— SDXS 这类一步模型 2 步，Turbo / LCM / Hyper 系 4 步" +
                                "（且 CFG 固定 1.0，每步只跑一遍，快一倍以上）；普通 SD 1.5 / 2.1 用 12 步。"
                        } else {
                            "步数越少越快、细节越少。Turbo / LCM 系模型用 4 步就够。"
                        },
                        style = IosTheme.typography.caption2,
                        color = ios.tertiaryLabel,
                    )

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("后端 Flash Attention", style = IosTheme.typography.body, color = ios.label)
                            Text(
                                "省内存（768² 约省 1.4 GB），个别模型上出图异常时可以关",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                        IosSwitch(
                            checked = settings.flashAttn,
                            onCheckedChange = { on -> viewModel.updateImage { it.copy(flashAttn = on) } },
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (generating || prompt.isBlank()) ios.fill else ios.pink)
                                .clickable(enabled = !generating && prompt.isNotBlank()) { generate() }
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (generating) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = ios.onAccent)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(AppIcons.AutoAwesome, contentDescription = null, tint = ios.onAccent, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(7.dp))
                                    Text("生成图片", style = IosTheme.typography.headline, color = ios.onAccent)
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ios.fillSecondary)
                                .clickable {
                                    viewModel.updateImage { it.copy(seed = ImageEngine.randomSeed()) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppIcons.Casino, contentDescription = "随机种子", tint = ios.blue, modifier = Modifier.size(20.dp))
                        }
                    }

                    // 生成中：已用多久 / 上次多久。
                    // 扩散出图在手机上是几十秒到几分钟的事，只给一个转圈等于让用户干等，
                    // 他分不清「在跑」和「卡死」。给个秒表 + 上一次的参照，等待就变得可预期。
                    if (generating && lastTook == 0L) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "正在出图…已用 " + (elapsedMs / 1000) + " 秒（第一张要加载模型，会更久）",
                            style = IosTheme.typography.caption1,
                            color = ios.warningText,
                        )
                    }
                    if (generating && stepTotal > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "正在出图…第 " + stepNow + "/" + stepTotal + " 步 · 已用 " +
                                (elapsedMs / 1000) + " 秒" +
                                if (lastTook > 0L) "（上次 " + (lastTook / 1000) + " 秒）" else "",
                            style = IosTheme.typography.caption1,
                            color = ios.warningText,
                        )
                    }
                    if (generating && stepTotal == 0 && lastTook > 0L) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "正在出图…已用 " + (elapsedMs / 1000) + " 秒 / 上次同样的设置用了 " +
                                (lastTook / 1000) + " 秒",
                            style = IosTheme.typography.caption1,
                            color = ios.warningText,
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "种子 ${if (settings.seed > 0) settings.seed.toString() else "随机"} · " +
                            "同一提示词 + 同一种子 = 同一张图（可复现）",
                        style = IosTheme.typography.caption2,
                        color = ios.tertiaryLabel,
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, style = IosTheme.typography.caption1, color = ios.red)
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "作品（${gallery.size}）",
                        style = IosTheme.typography.footnote,
                        color = ios.secondaryLabel,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "清空",
                        style = IosTheme.typography.footnote,
                        color = ios.red,
                        modifier = Modifier.clickable {
                            viewModel.storage.clearImages()
                            gallery = emptyList()
                            preview = null
                            viewModel.toast("已清空生成图片")
                        },
                    )
                }
            }

            if (gallery.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(AppIcons.Image, contentDescription = null, tint = ios.tertiaryLabel, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "还没有作品。生成后可在这里回看，\n也会通过 /v1/images 对外提供。",
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                gallery.chunked(3).forEach { row ->
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            row.forEach { file ->
                                Box(Modifier.weight(1f)) {
                                    Thumbnail(file = file, onClick = { preview = file })
                                }
                            }
                            repeat(3 - row.size) {
                                Box(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    "内置渲染器为本地程序化生成（离线、毫秒级、可复现）。" +
                        "stable-diffusion.cpp 后端已内置；下载一个生图模型即可切换到真实模型，见 docs/IMAGE_ENGINE.md。",
                    style = IosTheme.typography.caption1,
                    color = ios.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }
    }

    preview?.let { file ->
        ImagePreviewDialog(
            file = file,
            onDismiss = { preview = null },
            onInfo = { viewModel.toast("已保存到 ${file.absolutePath}") },
        )
    }
}

@Composable
private fun Thumbnail(file: File, onClick: () -> Unit) {
    val ios = IosTheme.palette
    val bitmap = remember(file.absolutePath) { decodeSampled(file, 256) }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(ios.fillSecondary)
            .clickable(onClick = onClick),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ImagePreviewDialog(file: File, onDismiss: () -> Unit, onInfo: () -> Unit) {
    val ios = IosTheme.palette
    val bitmap = remember(file.absolutePath) { decodeSampled(file, 1280) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ios.secondaryGroupedBackground)
                .padding(14.dp)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(file.name, style = IosTheme.typography.caption1, color = ios.secondaryLabel, maxLines = 2)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(ios.blue.copy(alpha = 0.14f))
                        .clickable(onClick = onInfo)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Share, contentDescription = null, tint = ios.blue, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("文件位置", style = IosTheme.typography.footnote, color = ios.blue, fontWeight = FontWeight.SemiBold)
                }
                IosPill("种子可复现", ios.indigo)
            }
        }
    }
}

private fun decodeSampled(file: File, target: Int): Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > target * 2) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
}.getOrNull()

/**
 * 生图后端状态与安装入口。
 *
 * ## 为什么这块必须存在
 *
 * 后端现在是**按需下载**的（36 MB，只有真要用的人付这个体积）。
 * 代价是出现了一种新的困惑：
 *
 *   用户下了 2 GB 的 SD 模型 → 生图 → 看到程序化渲染的渐变图
 *   → "模型没用上？是不是坏了？"
 *
 * 所以这一块要明确说出三件事：**装没装**、**有没有模型**、**不装会怎样**。
 * 含糊其辞会让用户去反复换模型，而问题根本不在模型上。
 */
@Composable
private fun DiffusionBackendSection(viewModel: AppViewModel, onChanged: () -> Unit) {
    val ios = IosTheme.palette
    val models by viewModel.diffusionModels.collectAsState()
    val abi = remember { com.localmind.service.NativeLibraryInstaller.currentAbi() }
    // 用 lastModified 做键，这样在插件页装完之后回到这一页会重新读一次。
    var tick by remember { mutableIntStateOf(0) }
    val backendFile = remember(tick) {
        com.localmind.service.NativeLibraryInstaller.installedFile(viewModel.storage, abi)
    }
    // "文件在磁盘上"和"这个进程能用"是**两件事**，必须分开显示。
    //
    // 前者是插件安装的产物；后者要 System.load 之后由原生层回答。只按前者显示，
    // 就会出现"这里写着已安装、生图却还在出程序化渐变图"——用户会去反复换模型，
    // 而问题根本不在模型上。
    val onDisk = backendFile.isFile && backendFile.length() > 0
    val ready by viewModel.diffusionBackendReady.collectAsState()
    // 通用版插件（1.1.0 起）会回答 true；旧插件没有这个 JNI 符号，回答 false。
    val universal = remember(ready, tick) {
        ready && com.localmind.native.image.ImageBridge.hasUniversalDiffusion
    }

    IosSection(
        header = "生图后端",
        footer = when {
            ready -> "后端已内置在 APK 里（约 37 MB/ABI），不用另外下载；" +
                "它不影响对话与语音，下载一个生图模型就能用。"
            onDisk -> "文件已经装好了，但还没加载进当前进程 —— 点「启用」。"
            else -> "这个 APK 的后端没有加载成功（常见于 ABI 不匹配）。" +
                "先重启应用；仍然失败时只能用程序化渲染，画不出具体内容。"
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            ready -> "已启用"
                            onDisk -> "已安装 · 未启用"
                            else -> "未安装"
                        },
                        style = IosTheme.typography.body,
                        color = ios.label,
                    )
                    Text(
                        when {
                            ready -> "${com.localmind.util.Formatters.size(backendFile.length())} · $abi" +
                                (if (universal) " · 通用版" else "")
                            onDisk -> "点「启用」把它加载进来（$abi）"
                            else -> "内置后端未加载（$abi）"
                        },
                        style = IosTheme.typography.caption1,
                        color = if (ready) ios.green else ios.warningText,
                    )
                    if (ready) {
                        Text(
                            if (universal) "采样器 / 调度器、逐帧进度已启用"
                            else "旧版后端：只有步数与线程，重新装通用版可拿到采样器与进度",
                            style = IosTheme.typography.caption2,
                            color = if (universal) ios.secondaryLabel else ios.warningText,
                        )
                    }
                }
                if (!onDisk) {
                    ImageRowAction("去插件页装", ios.blue) { viewModel.openPlugins(true) }
                } else {
                    if (!ready) {
                        ImageRowAction("启用", ios.blue) {
                            viewModel.enableDiffusionBackend { ok ->
                                if (!ok) viewModel.toast("后端加载失败，见「诊断」或日志")
                            }
                            tick++
                        }
                    }
                    ImageRowAction("重新检测", ios.secondaryLabel) {
                        viewModel.refreshDiffusionBackend()
                        tick++
                    }
                }
            }

            // 后端装了但没模型，同样生不出真图 —— 而用户往往以为装完后端就好了。
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
            Spacer(Modifier.height(10.dp))
            // 生图模型：**可以选**。
            //
            // 以前这一行只显示「本机没有检测到扩散模型」+ 一个「重新扫描」，没有任何
            // 选择入口 —— 用户的原话是「生图里面生图模型怎么不能选」。本机放了好几个
            // checkpoint 的人没法指定用哪一个，只能赌扫描顺序。
            // 选中的路径写进 ImageSettings.customModelPath（对话里的 AI 主动生图也用它）。
            val imageSettings by viewModel.image.collectAsState()
            var pickingModel by remember { mutableStateOf(false) }
            val chosen = imageSettings.customModelPath?.takeIf { it.isNotBlank() }?.let { File(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("生图模型", style = IosTheme.typography.body, color = ios.label)
                    Text(
                        when {
                            chosen != null -> chosen.name
                            models.isNotEmpty() -> "自动：" + models.first().name
                            else -> "本机没有检测到扩散模型"
                        },
                        style = IosTheme.typography.caption1,
                        color = if (chosen == null && models.isEmpty()) ios.warningText else ios.secondaryLabel,
                    )
                }
                ImageRowAction("选择", ios.blue) { pickingModel = true }
                ImageRowAction("重新扫描", ios.secondaryLabel) { viewModel.refreshDiffusionModels() }
            }
            if (pickingModel) {
                ModelPickerSheet(
                    title = "生图模型",
                    subtitle = "只列本机已下载的扩散 checkpoint；下载去「模型 → 生图」",
                    allowDefault = false,
                    options = listOf(
                        PickerOption(
                            id = "",
                            title = "自动（用检测到的第一个）",
                            subtitle = models.firstOrNull()?.name ?: "本机还没有扩散模型",
                        ),
                    ) + models.map { f ->
                        PickerOption(
                            id = f.absolutePath,
                            title = f.name.removeSuffix(".gguf").removeSuffix(".GGUF"),
                            subtitle = com.localmind.util.Formatters.size(f.length()),
                            badge = if (ImageEngine.isTurboModel(f.name)) "快" else null,
                        )
                    },
                    selectedId = imageSettings.customModelPath.orEmpty(),
                    onSelect = { id ->
                        viewModel.updateImage { it.copy(customModelPath = id.ifBlank { null }) }
                        onChanged()
                    },
                    onDismiss = { pickingModel = false },
                )
            }
            if (models.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "去「模型」页下载一个生图模型，或把已有的 .gguf 放进 " +
                        "files/models/。SD1.5 / SDXL / SD-Turbo 都认。",
                    style = IosTheme.typography.caption2,
                    color = ios.tertiaryLabel,
                )
            }
        }
    }

    // 进这一页就扫一次：模型可能是刚下载的，也可能是用户手动放进去的。
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshDiffusionModels() }
}

/** 这一页自己的小按钮。样式和 VoiceScreen 的 RowAction 一致。 */
@Composable
private fun ImageRowAction(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        style = IosTheme.typography.footnote,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

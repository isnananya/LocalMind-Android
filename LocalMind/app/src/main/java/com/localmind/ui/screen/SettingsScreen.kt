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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.localmind.ui.motion.ScrollPhysics
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.BuildConfig
import com.localmind.LocalMindApp
import com.localmind.data.MemoryPlanner
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosSlider
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.components.IosTag
import com.localmind.ui.design.Aurora
import com.localmind.ui.design.AuroraBackground
import com.localmind.ui.design.AuroraButton
import com.localmind.ui.design.AuroraDivider
import com.localmind.ui.design.AuroraDot
import com.localmind.ui.design.AuroraGroup
import com.localmind.ui.design.AuroraIcon
import com.localmind.ui.design.AuroraNavBar
import com.localmind.ui.design.AuroraSectionLabel
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import com.localmind.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置：**设备级**的东西。
 *
 * 三大类，按"用户多久会改一次"排：
 *  1. 生成与性能 —— 上下文、线程、mmap、加速；
 *  2. 常驻与预热 —— 谁在内存里、要不要预热；
 *  3. 外观 / 下载源 / 诊断 / 关于。
 *
 * 语言模型的每一样东西（用哪个模型、提示词、采样、朗读）都是**按对话**的，
 * 所以不在这里。这里只回答"这台手机怎么跑模型"。
 */
@Composable
fun SettingsScreen(viewModel: AppViewModel, onClose: () -> Unit) {
    val ios = IosTheme.palette
    val settings by viewModel.settings.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val report by viewModel.diagnostics.collectAsState()
    val running by viewModel.diagnosticsRunning.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val app = LocalMindApp.instance
    // 低端模式是**设备属性**，不依赖有没有加载模型 —— 这里算一次给界面看，
    // 不必等用户先跑一个模型才知道自己的设备被夹了。
    val lowEnd = remember { MemoryPlanner.lowEndPolicy(app.storage.appContext()) }

    var keepScreenOn by remember { mutableStateOf(app.storage.keepScreenOn()) }
    var preload by remember { mutableStateOf(app.storage.preloadModel()) }
    val breezeState by viewModel.breeze.collectAsState()
    val diffusionReady by viewModel.diffusionBackendReady.collectAsState()
    var repoProbe by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 这是盖在「我的」上面的整页浮层，必须自己有不透明底色。
    // 用 AuroraBackground 而不是纯色：纯色会让设置页看起来像另一套设计。
    AuroraBackground {
        Column(Modifier.fillMaxSize()) {
        AuroraNavBar(title = "设置", onBack = onClose)

        LazyColumn(
            flingBehavior = ScrollPhysics.fling(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 44.dp),
        ) {
            // ------------------------------------------------------- 当前状态
            item {
                AuroraGroup(modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AuroraDot(if (engineState.ready) ios.green else ios.gray)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (engineState.ready) "模型在内存里" else "没有加载模型",
                                style = IosTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                                color = ios.secondaryLabel,
                            )
                            Spacer(Modifier.weight(1f))
                            if (engineState.generating) {
                                Text("生成中", style = IosTheme.typography.caption2, color = ios.blue)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            engineState.modelName ?: "去模型页加载一个",
                            style = IosTheme.typography.title3,
                            color = ios.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "上下文 " + engineState.contextSize + " · 线程 " + engineState.threads +
                                " · " + String.format(java.util.Locale.US, "%.1f tok/s", engineState.lastTokensPerSecond),
                            style = IosTheme.typography.caption1,
                            color = ios.secondaryLabel,
                        )
                    }
                }
            }

            // ------------------------------------------------------- 生成参数
            item {
                AuroraSectionLabel("生成与性能")
                AuroraGroup {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("上下文长度", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0, 2048, 4096, 8192).forEach { ctx ->
                                IosTag(
                                    text = if (ctx == 0) "自动" else ctx.toString(),
                                    selected = settings.contextSize == ctx,
                                    onClick = { viewModel.updateSettings { it.copy(contextSize = ctx) } },
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "线程数：" + if (settings.threads == 0) {
                                "自动（" + MemoryPlanner.recommendedThreads() + "）"
                            } else settings.threads.toString(),
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                        IosSlider(
                            value = settings.threads.toFloat(),
                            onValueChange = { v -> viewModel.updateSettings { it.copy(threads = v.toInt()) } },
                            valueRange = 0f..MemoryPlanner.cpuCores().toFloat(),
                            activeColor = ios.green,
                        )
                    }
                    lowEnd?.let { policy ->
                        AuroraDivider(indent = 16.dp)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("内存模式", style = IosTheme.typography.body, color = ios.label)
                            Text(policy.note, style = IosTheme.typography.caption1, color = ios.orange)
                            Text(
                                "按总内存自动封顶，加载时不会超过这些值；上面的设置只能更小。",
                                style = IosTheme.typography.caption2,
                                color = ios.tertiaryLabel,
                            )
                        }
                    }
                    AuroraDivider(indent = 16.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("mmap 磁盘流式加载", style = IosTheme.typography.body, color = ios.label)
                            Text(
                                "关闭后模型必须全部装进内存",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                        IosSwitch(
                            checked = settings.mmap,
                            onCheckedChange = { on -> viewModel.updateSettings { it.copy(mmap = on) } },
                        )
                    }
                    AuroraDivider(indent = 16.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Flash Attention", style = IosTheme.typography.body, color = ios.label)
                            Text(
                                "长提示词收益最大；不支持的模型自动退回",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                        IosSwitch(
                            checked = settings.flashAttn,
                            onCheckedChange = { on -> viewModel.updateSettings { it.copy(flashAttn = on) } },
                        )
                    }
                    AuroraDivider(indent = 16.dp)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("KV cache 精度", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("f16", "q8_0", "q4_0").forEach { v ->
                                IosTag(
                                    text = v,
                                    selected = settings.kvQuant == v,
                                    onClick = { viewModel.updateSettings { it.copy(kvQuant = v) } },
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "降精度省内存，同样的内存能装下更大的上下文；需要 flash attention",
                            style = IosTheme.typography.caption2,
                            color = ios.tertiaryLabel,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "批大小 " + settings.batchSize + " / " + settings.ubatchSize + "（逻辑 / 物理）",
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                        IosSlider(
                            value = settings.batchSize.toFloat(),
                            onValueChange = { v ->
                                val b = (((v.toInt() + 32) / 64) * 64).coerceIn(64, 2048)
                                viewModel.updateSettings { it.copy(batchSize = b, ubatchSize = minOf(it.ubatchSize, b)) }
                            },
                            valueRange = 64f..2048f,
                            activeColor = ios.blue,
                        )
                        if (engineState.accelInfo.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "当前生效：" + engineState.accelInfo,
                                style = IosTheme.typography.caption2,
                                color = ios.tertiaryLabel,
                            )
                        }
                        Text(
                            "改完要重新加载模型才生效。",
                            style = IosTheme.typography.caption2,
                            color = ios.tertiaryLabel,
                        )
                    }
                    engineState.plan?.let { plan ->
                        AuroraDivider(indent = 16.dp)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text("当前模型内存规划", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
                            Spacer(Modifier.height(4.dp))
                            InfoRow("模型体积", Formatters.size(plan.fileBytes))
                            InfoRow("可用内存", Formatters.size(plan.freeRamBytes))
                            InfoRow("建议上下文", plan.recommendedContext.toString() + " tokens")
                            InfoRow("建议线程", plan.recommendedThreads.toString())
                            if (plan.lowEndNote.isNotBlank()) InfoRow("内存模式", plan.lowEndNote)
                            InfoRow("层数", if (plan.layers > 0) plan.layers.toString() else "未知")
                            InfoRow("策略", if (plan.needsStreaming) "磁盘流式加载（mmap）" else "全部驻留内存")
                            plan.warnings.forEach {
                                Spacer(Modifier.height(3.dp))
                                Text(it, style = IosTheme.typography.caption2, color = ios.warningText)
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------------- 常驻预热
            item {
                AuroraSectionLabel("常驻与预热")
                AuroraGroup {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("启动时预热对话模型", style = IosTheme.typography.body, color = ios.label)
                            Text(
                                "把几秒的加载挪到你没在等的时候。低内存机型建议关掉",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                        IosSwitch(
                            checked = preload,
                            onCheckedChange = { on ->
                                preload = on
                                viewModel.setPreloadModel(on)
                            },
                        )
                    }
                    AuroraDivider(indent = 16.dp)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("现在真正在内存里的", style = IosTheme.typography.caption1, color = ios.tertiaryLabel)
                        Spacer(Modifier.height(4.dp))
                        viewModel.residencyLines().forEach { (name, value) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(
                                    name,
                                    style = IosTheme.typography.footnote,
                                    color = ios.secondaryLabel,
                                    modifier = Modifier.width(96.dp),
                                )
                                Text(value, style = IosTheme.typography.footnote, color = ios.label)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AuroraButton(
                                "立即预热",
                                onClick = { viewModel.preloadChatModel() },
                                color = ios.fill,
                                modifier = Modifier.weight(1f),
                            )
                            AuroraButton(
                                "卸载模型",
                                onClick = { viewModel.unloadModel() },
                                color = ios.red.copy(alpha = 0.12f),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // ----------------------------------------------------------- 外观
            item {
                AuroraSectionLabel("外观")
                AuroraGroup {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("深浅色", style = IosTheme.typography.body, color = ios.label, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                                IosTag(label, themeMode == mode) { viewModel.setThemeMode(mode) }
                            }
                        }
                    }
                    AuroraDivider(indent = 16.dp)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("生成时保持屏幕常亮", style = IosTheme.typography.body, color = ios.label)
                            Text("避免长回答时息屏中断", style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                        }
                        IosSwitch(
                            checked = keepScreenOn,
                            onCheckedChange = { on ->
                                keepScreenOn = on
                                app.storage.setKeepScreenOn(on)
                            },
                        )
                    }
                }
            }

            // --------------------------------------------------------- 下载源
            item {
                AuroraSectionLabel("下载源")
                AuroraGroup {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "自动按顺序回退：官方源 → 国内镜像 → 代理。",
                            style = IosTheme.typography.caption1,
                            color = ios.secondaryLabel,
                        )
                        repoProbe?.let { probe ->
                            Spacer(Modifier.height(8.dp))
                            probe.forEach { (host, ok) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AuroraDot(if (ok) ios.green else ios.red, size = 7.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(host, style = IosTheme.typography.footnote, color = ios.label, modifier = Modifier.weight(1f))
                                    Text(
                                        if (ok) "可访问" else "不可达",
                                        style = IosTheme.typography.caption1,
                                        color = ios.secondaryLabel,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        AuroraButton(
                            text = if (probing) "测试中…" else "测试下载源连通性",
                            onClick = {
                                probing = true
                                scope.launch {
                                    repoProbe = withContext(Dispatchers.IO) { viewModel.downloader.probeMirrors() }
                                    probing = false
                                }
                            },
                            loading = probing,
                            color = ios.fill,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ----------------------------------------------------------- 诊断
            item {
                AuroraSectionLabel("诊断")
                AuroraGroup {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AuroraIcon(AppIcons.HealthAndSafety, ios.green, size = 30.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("运行自检", style = IosTheme.typography.body, color = ios.label)
                            Text(
                                if (running) "正在检测…" else "原生库 / 内存 / 渲染 / API",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                        if (running) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = ios.green,
                            )
                        } else {
                            Icon(
                                AppIcons.RestartAlt,
                                contentDescription = "开始自检",
                                tint = ios.blue,
                                modifier = Modifier.size(22.dp).clickable { viewModel.runDiagnostics() },
                            )
                        }
                    }
                    report?.let { r ->
                        AuroraDivider(indent = 16.dp)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(
                                "通过 " + r.passed + "/" + r.checks.size + " · 耗时 " + r.durationMs + "ms",
                                style = IosTheme.typography.footnote,
                                color = if (r.allCriticalPassed) ios.green else ios.warningText,
                            )
                            Spacer(Modifier.height(6.dp))
                            r.checks.forEach { check ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                                    Box(
                                        Modifier
                                            .padding(top = 5.dp)
                                            .size(7.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (check.ok) ios.green else ios.red),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(check.name, style = IosTheme.typography.footnote, color = ios.label)
                                        Text(
                                            check.detail,
                                            style = IosTheme.typography.caption2.copy(fontFamily = FontFamily.Monospace),
                                            color = ios.secondaryLabel,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    AuroraDivider(indent = 16.dp)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        InfoRow("Android", android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")")
                        InfoRow("机型", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            engineState.systemInfo.ifBlank { "原生库信息不可用" }.take(400),
                            style = IosTheme.typography.caption2.copy(fontFamily = FontFamily.Monospace, lineHeight = 15.sp),
                            color = ios.secondaryLabel,
                        )
                    }
                }
            }

            // ----------------------------------------------------------- 关于
            item {
                AuroraSectionLabel("关于")
                AuroraGroup {
                    Column(Modifier.padding(16.dp)) {
                        Text("LocalMind " + BuildConfig.VERSION_NAME, style = IosTheme.typography.headline, color = ios.label)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "手机本地大模型工作台：下载 / 运行 / 对话 / 生图 / 语音 / 开放 API。",
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoRow("模型目录", viewModel.storage.modelsDir.absolutePath)
                        InfoRow("图片目录", viewModel.storage.imagesDir.absolutePath)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "推理内核 llama.cpp (MIT)；界面组件与第三方致谢见「关于 → 开源声明」。",
                            style = IosTheme.typography.caption2,
                            color = ios.tertiaryLabel,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val ios = IosTheme.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = IosTheme.typography.caption1,
            color = ios.secondaryLabel,
            modifier = Modifier.width(84.dp),
        )
        Text(
            value,
            style = IosTheme.typography.caption1.copy(fontFamily = FontFamily.Monospace),
            color = ios.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

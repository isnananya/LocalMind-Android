package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localmind.BuildConfig
import androidx.compose.ui.unit.sp
import com.localmind.LocalMindApp
import com.localmind.data.MemoryPlanner
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosSlider
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.components.IosTag
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import com.localmind.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(viewModel: AppViewModel, onClose: () -> Unit) {
    val ios = IosTheme.palette
    val settings by viewModel.settings.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val report by viewModel.diagnostics.collectAsState()
    val running by viewModel.diagnosticsRunning.collectAsState()
    val app = LocalMindApp.instance

    var themeMode by remember { mutableStateOf(app.storage.themeMode()) }
    var keepScreenOn by remember { mutableStateOf(app.storage.keepScreenOn()) }
    var preload by remember { mutableStateOf(app.storage.preloadModel()) }
    val breezeState by viewModel.breeze.collectAsState()
    val diffusionReady by viewModel.diffusionBackendReady.collectAsState()
    var repoProbe by remember { mutableStateOf<Map<String, Boolean>?>(null) }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ios.barBackground)
                .padding(top = statusBarTopPadding())
                .height(48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "设置",
                style = IosTheme.typography.title3,
                color = ios.label,
                modifier = Modifier.weight(1f),
            )
            Icon(
                AppIcons.Close,
                contentDescription = "关闭",
                tint = ios.blue,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onClose),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp),
        ) {
            // 「生成参数」和「系统提示词」两栏**整块撤掉**了。
            //
            // 它们原来是"全局 AI 设置"，而这套应用里语言模型的每一样东西
            //（用哪个模型、怎么说话、提示词、采样）都是**按对话**的：
            // 一个用来问事的对话和一个用来谈心的对话，参数不该共用一份。
            // 所以它们搬到了对话自己的设置页里：
            //   AI 对话   → AiChatSettingsScreen
            //   人设聊天 → PersonaChatSettingsScreen
            // 这里只留**设备级**的东西：性能、外观、下载源、诊断。

            // ------------------------------------------------------- 性能/内存
            item {
                IosSection(
                    header = "性能与内存",
                    footer = "0 = 自动。上下文越大越占内存。",
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0, 2048, 4096, 8192).forEach { ctx ->
                                val selected = settings.contextSize == ctx
                                IosTag(
                                    text = if (ctx == 0) "自动" else ctx.toString(),
                                    selected = selected,
                                    onClick = { viewModel.updateSettings { it.copy(contextSize = ctx) } },
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            // ⚠️ 自动值必须问 MemoryPlanner 要，不能在这里自己算。
                            // 这里原来写的是 cores - 1，而实际用的是 cores<=4 ? cores : 4：
                            // 4 核机器上界面说「自动（3）」实际用 4，8 核机器上说「自动（7）」
                            // 实际用 4 —— 用户拿这个数字去理解速度，只会得出错误结论。
                            "线程数：" + if (settings.threads == 0) {
                                "自动（${MemoryPlanner.autoThreads()}）"
                            } else {
                                settings.threads.toString()
                            },
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                        IosSlider(
                            value = settings.threads.toFloat(),
                            onValueChange = { v -> viewModel.updateSettings { it.copy(threads = v.toInt()) } },
                            valueRange = 0f..MemoryPlanner.cpuCores().toFloat(),
                            activeColor = ios.green,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("mmap 磁盘流式加载", style = IosTheme.typography.body, color = ios.label)
                                Text(
                                    "关闭后模型必须全部装进内存（AirLLM 式按需加载开关）",
                                    style = IosTheme.typography.caption1,
                                    color = ios.secondaryLabel,
                                )
                            }
                            IosSwitch(
                                checked = settings.mmap,
                                onCheckedChange = { on -> viewModel.updateSettings { it.copy(mmap = on) } },
                            )
                        }

                        engineState.plan?.let { plan ->
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
                            Spacer(Modifier.height(10.dp))
                            Text("当前模型内存规划", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
                            InfoRow("模型体积", Formatters.size(plan.fileBytes))
                            InfoRow("可用内存", Formatters.size(plan.freeRamBytes))
                            InfoRow("建议上下文", "${plan.recommendedContext} tokens")
                            InfoRow("建议线程", plan.recommendedThreads.toString())
                            InfoRow("层数", if (plan.layers > 0) plan.layers.toString() else "未知")
                            InfoRow(
                                "策略",
                                if (plan.needsStreaming) "磁盘流式加载（mmap）" else "全部驻留内存",
                            )
                            plan.warnings.forEach {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = IosTheme.typography.caption1, color = ios.orange)
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------------- 推理加速
            //
            // 这一栏是「同样的权重、更少的时间」。放在设备级而不是对话级：
            // 它描述的是这台机器怎么算，不是这段对话要怎么说话。
            item {
                IosSection(
                    header = "推理加速",
                    footer = "加速项初始化失败时会自动关掉它继续加载；下面那行是真正生效的配置。",
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Flash Attention", style = IosTheme.typography.body, color = ios.label)
                                Text(
                                    "长提示词（人设聊天那种几千字）收益最大；不支持的模型会自动退回",
                                    style = IosTheme.typography.caption1,
                                    color = ios.secondaryLabel,
                                )
                            }
                            IosSwitch(
                                checked = settings.flashAttn,
                                onCheckedChange = { on -> viewModel.updateSettings { it.copy(flashAttn = on) } },
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
                        Spacer(Modifier.height(10.dp))

                        Text("KV cache 精度", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("f16" to "f16", "q8_0" to "q8_0", "q4_0" to "q4_0").forEach { (v, label) ->
                                IosTag(
                                    text = label,
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
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
                        Spacer(Modifier.height(10.dp))

                        Text(
                            "批大小 " + settings.batchSize + " / " + settings.ubatchSize +
                                "（逻辑 / 物理，影响长提示词的速度与峰值内存）",
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

                        val accel = engineState.accelInfo
                        if (accel.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "当前生效：" + accel,
                                style = IosTheme.typography.caption2,
                                color = ios.tertiaryLabel,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "改完要重新加载模型才生效（模型页里点一次加载）。",
                            style = IosTheme.typography.caption2,
                            color = ios.tertiaryLabel,
                        )
                    }
                }
            }

            // --------------------------------------------------- 常驻与预热
            //
            // 这一栏回答两个内测里反复被问到的问题：
            //  * "为什么点开应用不能马上说话？" —— 模型还没加载，这里能预热。
            //  * "能不能同时开两个模型？" —— 能，但代价是内存，这里如实列出谁在内存里。
            item {
                IosSection(
                    header = "常驻与预热",
                    footer = "手机上的真正限制是内存。同时常驻的模型越多，每个能用的上下文越小；" +
                        "下面这行是当前**真正**在内存里的东西，不是设置项。",
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                        Spacer(Modifier.height(10.dp))
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
                        Spacer(Modifier.height(8.dp))
                        viewModel.residencyLines().forEach { (name, value) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    name,
                                    style = IosTheme.typography.footnote,
                                    color = ios.secondaryLabel,
                                    modifier = Modifier.width(84.dp),
                                )
                                Text(
                                    value,
                                    style = IosTheme.typography.footnote,
                                    color = ios.label,
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(IosTheme.metrics.controlCorner))
                                    .background(ios.fillSecondary)
                                    .clickable { viewModel.preloadChatModel() }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("立即预热", style = IosTheme.typography.footnote, color = ios.blue)
                            }
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(IosTheme.metrics.controlCorner))
                                    .background(ios.fillSecondary)
                                    .clickable { viewModel.unloadModel() }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("卸载模型", style = IosTheme.typography.footnote, color = ios.red)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "「同时跑多个」的正确做法是把常驻压到最小、其余按需换入：" +
                                "决策模型只有 1K 上下文，语音与生图都是用到才进内存",
                            style = IosTheme.typography.caption2,
                            color = ios.tertiaryLabel,
                        )
                        // breezeState / diffusionReady 只是为了让这栏跟着它们重组。
                        if (breezeState.ready || diffusionReady) {
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }

            // ------------------------------------------------------- 外观
            item {
                IosSection(header = "外观") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("主题", style = IosTheme.typography.body, color = ios.label, modifier = Modifier.weight(1f))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (mode, label) ->
                                IosTag(label, themeMode == mode) {
                                    themeMode = mode
                                    app.storage.setThemeMode(mode)
                                    viewModel.toast("主题将在下次启动时生效")
                                }
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 11.dp),
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

            // 「模型分工（全局默认）」也撤掉了 —— 同上：模型是**按对话**选的，
            // 一个"全局默认"只会制造"我改了怎么没反应"的困惑。
            // 现在在对话自己的设置页里选，见 ConversationModelRow。

            // ------------------------------------------------------- 网络
            item {
                IosSection(header = "下载源", footer = "自动按顺序回退。") {
                    Column(Modifier.padding(14.dp)) {
                        val probe = repoProbe
                        if (probe != null) {
                            probe.forEach { (host, ok) ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (ok) ios.green else ios.red)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(host, style = IosTheme.typography.footnote, color = ios.label, modifier = Modifier.weight(1f))
                                    Text(if (ok) "可访问" else "不可达", style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ios.fillSecondary)
                                .clickable(enabled = !probing) {
                                    probing = true
                                    scope.launch {
                                        repoProbe = withContext(Dispatchers.IO) {
                                            viewModel.downloader.probeMirrors()
                                        }
                                        probing = false
                                    }
                                }
                                .padding(vertical = 11.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (probing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ios.blue)
                            } else {
                                Text("测试下载源连通性", style = IosTheme.typography.footnote, color = ios.blue, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------------- 诊断
            item {
                IosSection(
                    header = "诊断",
                    footer = "换新手机时先跑一遍。",
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(AppIcons.HealthAndSafety, contentDescription = null, tint = ios.green, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("运行自检", style = IosTheme.typography.body, color = ios.label)
                                Text(
                                    if (running) "正在检测…" else "原生库 / 内存 / 渲染 / API",
                                    style = IosTheme.typography.caption1,
                                    color = ios.secondaryLabel,
                                )
                            }
                            if (running) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = ios.green)
                            } else {
                                Icon(
                                    AppIcons.RestartAlt,
                                    contentDescription = null,
                                    tint = ios.blue,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clickable { viewModel.runDiagnostics() },
                                )
                            }
                        }

                        report?.let { r ->
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "通过 ${r.passed}/${r.checks.size} · 耗时 ${r.durationMs}ms",
                                style = IosTheme.typography.footnote,
                                color = if (r.allCriticalPassed) ios.green else ios.orange,
                            )
                            Spacer(Modifier.height(8.dp))
                            r.checks.forEach { check ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Box(
                                        Modifier
                                            .padding(top = 5.dp)
                                            .size(7.dp)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (check.ok) ios.green else ios.red)
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

                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
                        Spacer(Modifier.height(10.dp))
                        Text("运行环境", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · " +
                                "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                            style = IosTheme.typography.caption2,
                            color = ios.label,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            engineState.systemInfo.ifBlank { "原生库信息不可用" }.take(400),
                            style = IosTheme.typography.caption2.copy(fontFamily = FontFamily.Monospace, lineHeight = 15.sp),
                            color = ios.secondaryLabel,
                        )
                    }
                }
            }

            // ------------------------------------------------------- 关于
            item {
                IosSection(
                    header = "关于",
                    footer = "推理内核 llama.cpp (MIT) · 大模型权重各自遵循其原始许可 · " +
                        "AirLLM 思路参考 lyogavin/airllm (Apache-2.0)，见 docs/AIRLLM.md",
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "LocalMind ${BuildConfig.VERSION_NAME}",
                            style = IosTheme.typography.headline,
                            color = ios.label,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "手机本地大模型工作台：下载 / 运行 / 对话 / 生图 / 语音 / 开放 API。",
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                        Spacer(Modifier.height(10.dp))
                        InfoRow("模型目录", viewModel.storage.modelsDir.absolutePath)
                        InfoRow("图片目录", viewModel.storage.imagesDir.absolutePath)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onChange: (Float) -> Unit,
) {
    val ios = IosTheme.palette
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = IosTheme.typography.footnote, color = ios.secondaryLabel, modifier = Modifier.width(150.dp))
            IosSlider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                modifier = Modifier.weight(1f),
                activeColor = accent,
            )
            Text(
                if (range.endInclusive <= 2f) String.format(java.util.Locale.US, "%.2f", value)
                else value.toInt().toString(),
                style = IosTheme.typography.caption1,
                color = ios.label,
                modifier = Modifier.width(46.dp),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val ios = IosTheme.palette
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = IosTheme.typography.caption1, color = ios.secondaryLabel, modifier = Modifier.width(96.dp))
        Text(
            value,
            style = IosTheme.typography.caption1.copy(fontFamily = FontFamily.Monospace),
            color = ios.label,
        )
    }
}

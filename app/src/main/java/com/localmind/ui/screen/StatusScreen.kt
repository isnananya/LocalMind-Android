package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.service.EngineState
import com.localmind.service.SystemStatus
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.SparkSeries
import com.localmind.ui.components.Sparkline
import com.localmind.ui.design.Aurora
import com.localmind.ui.design.AuroraButton
import com.localmind.ui.design.AuroraCircleButton
import com.localmind.ui.design.AuroraDivider
import com.localmind.ui.design.AuroraDot
import com.localmind.ui.design.AuroraGroup
import com.localmind.ui.design.AuroraIcon
import com.localmind.ui.design.AuroraNavBar
import com.localmind.ui.design.AuroraSectionLabel
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import com.localmind.util.Formatters
import java.util.Locale

/**
 * 「状态」：设备与模型的实时状态。
 *
 * 这一页回答「现在怎么样」，不是「怎么设置」：
 *  * 设备：CPU / 内存 / 电池 / 存储 / 网络，每秒刷新，保留一分钟曲线；
 *  * 模型：加载了谁、上下文多大、多少 tok/s、KV 命中率；
 *  * 后端：语音引擎、神经音色、Breeze、生图后端、本机 API。
 *
 * 采样在 ViewModel 的 IO 协程里做（见 AppViewModel.startStatusLoop），
 * 界面只消费结果。CPU 读数在 Android 8+ 上常被内核限制，这时如实显示
 * 「系统限制」，不编数字 —— 见 SystemStatus 的说明。
 */
@Composable
fun StatusScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val ios = IosTheme.palette
    val snap by viewModel.statusSnapshot.collectAsState()
    val history by viewModel.statusHistory.collectAsState()
    val engine by viewModel.engineState.collectAsState()
    val voice by viewModel.voice.collectAsState()
    val breeze by viewModel.breeze.collectAsState()
    val activeVoice by viewModel.activeNeuralVoice.collectAsState()
    val diffusionReady by viewModel.diffusionBackendReady.collectAsState()
    val api by viewModel.apiServer.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        AuroraNavBar(
            title = "状态",
            onBack = onBack,
            trailing = {
                AuroraCircleButton(
                    icon = AppIcons.Refresh,
                    contentDescription = "重新采样",
                    onClick = { viewModel.openStatus(true) },
                )
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 44.dp),
        ) {
            item {
                ModelHero(
                    engine = engine,
                    tpsHistory = history.tps,
                    onOpenModels = {
                        viewModel.openStatus(false)
                        viewModel.selectTab(com.localmind.ui.Tab.MODELS)
                    },
                )
            }

            item {
                AuroraSectionLabel("设备")
                val s = snap
                if (s == null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Aurora.PagePadding)
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("正在采样…", style = IosTheme.typography.subheadline, color = ios.secondaryLabel)
                    }
                } else {
                    Column(
                        Modifier.padding(horizontal = Aurora.PagePadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard(
                                title = "CPU",
                                value = s.cpuUsagePercent?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--",
                                subtitle = buildString {
                                    append(s.cpuCores.size).append(" 核")
                                    s.cpuAvgMhz?.let { append(" · ").append(it).append(" MHz") }
                                },
                                icon = AppIcons.GraphicEq,
                                accent = ios.blue,
                                series = listOf(SparkSeries(history.cpu, ios.blue)),
                                maxValue = 100f,
                                foot = s.cpuSource,
                                modifier = Modifier.weight(1f),
                            )
                            MetricCard(
                                title = "内存",
                                value = String.format(Locale.US, "%.0f%%", s.ramPercent),
                                subtitle = Formatters.size(s.ramUsed) + " / " + Formatters.size(s.ramTotal),
                                icon = AppIcons.Memory,
                                accent = ios.purple,
                                series = listOf(SparkSeries(history.ram, ios.purple)),
                                maxValue = 100f,
                                foot = "可用 " + Formatters.size(s.ramAvailable),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard(
                                title = "电池",
                                value = s.batteryPercent.toString() + "%",
                                subtitle = (if (s.batteryCharging) "充电中" else "放电中") +
                                    (s.batteryTempC?.let { " · " + String.format(Locale.US, "%.1f°C", it) } ?: ""),
                                icon = AppIcons.Bolt,
                                accent = if (s.batteryPercent <= 20 && !s.batteryCharging) ios.red else ios.green,
                                series = listOf(SparkSeries(history.batteryTemp, ios.green)),
                                maxValue = null,
                                foot = "健康 " + s.batteryHealth,
                                modifier = Modifier.weight(1f),
                            )
                            MetricCard(
                                title = "存储",
                                value = String.format(
                                    Locale.US,
                                    "%.0f%%",
                                    if (s.storageTotal > 0) s.storageUsed * 100f / s.storageTotal else 0f,
                                ),
                                subtitle = Formatters.size(s.storageFree) + " 可用",
                                icon = AppIcons.Storage,
                                accent = ios.orange,
                                series = emptyList(),
                                maxValue = null,
                                foot = "数据分区",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        MetricCard(
                            title = "网络",
                            value = s.networkType,
                            subtitle = "↓ " + Formatters.size(s.rxBytesPerSec) + "/s   ↑ " +
                                Formatters.size(s.txBytesPerSec) + "/s",
                            icon = AppIcons.Language,
                            accent = ios.teal,
                            series = listOf(
                                SparkSeries(history.rx, ios.teal),
                                SparkSeries(history.tx, ios.yellow, fill = false, dashed = true),
                            ),
                            maxValue = null,
                            foot = "本次运行 " + formatUptime(s.uptimeMs),
                            modifier = Modifier.fillMaxWidth(),
                            tall = true,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricCard(
                                title = "本应用 CPU",
                                value = s.appCpuPercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--",
                                subtitle = "占全部核心",
                                icon = AppIcons.Api,
                                accent = ios.indigo,
                                series = emptyList(),
                                maxValue = null,
                                foot = s.cpuCores.count { it.online }.toString() + " 核在线",
                                modifier = Modifier.weight(1f),
                            )
                            MetricCard(
                                title = "屏幕",
                                value = s.screen.substringBefore(" · "),
                                subtitle = s.screen.substringAfter(" · ", "刷新率未知"),
                                icon = AppIcons.Visibility,
                                accent = ios.pink,
                                series = emptyList(),
                                maxValue = null,
                                foot = s.soc,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // CPU 计数被系统限制时（usage 全为 null）不画一排空柱子，
                        // 那只会让人以为"核心都没在工作"。
                        if (s.cpuCores.any { it.usagePercent != null }) {
                            CoreBars(s.cpuCores, ios.blue)
                        }
                    }
                }
            }

            item {
                AuroraSectionLabel("正在使用")
                AuroraGroup {
                    StatusInfoRow(
                        label = "状态",
                        value = when {
                            engine.loading -> "加载中…"
                            engine.generating -> "生成中 · 实时"
                            engine.ready -> "已就绪（空闲）"
                            else -> "未加载"
                        },
                        color = when {
                            engine.generating -> ios.blue
                            engine.ready -> ios.green
                            else -> ios.secondaryLabel
                        },
                    )
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("模型", engine.modelName ?: "—")
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow(
                        "实时速度",
                        if (engine.lastTokensPerSecond > 0) {
                            String.format(Locale.US, "%.1f tok/s", engine.lastTokensPerSecond)
                        } else {
                            "—"
                        },
                        color = if (engine.generating) ios.blue else ios.label,
                    )
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("已生成", if (engine.tokensGenerated > 0) engine.tokensGenerated.toString() + " token" else "—")
                    AuroraDivider(indent = 16.dp)
                    val used = engine.lastPromptTokens + engine.tokensGenerated
                    StatusInfoRow(
                        "上下文占用",
                        if (engine.contextSize > 0) used.toString() + " / " + engine.contextSize + " token" else "—",
                    )
                    if (engine.contextSize > 0) {
                        val ratio = (used.toFloat() / engine.contextSize).coerceIn(0f, 1f)
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .height(4.dp)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                .background(ios.fill),
                        ) {
                            androidx.compose.foundation.layout.Box(
                                Modifier
                                    .fillMaxWidth(ratio)
                                    .height(4.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                    .background(ios.blue),
                            )
                        }
                    }
                    if (engine.lastPrefillMs > 0) {
                        AuroraDivider(indent = 16.dp)
                        StatusInfoRow(
                            "本轮明细",
                            "prefill " + engine.lastPrefillMs + " ms · 首 token " +
                                engine.lastFirstTokenMs + " ms · 生成 " + engine.lastGenMs + " ms",
                        )
                    }
                }
            }

            item {
                AuroraSectionLabel("模型")
                AuroraGroup {
                    StatusInfoRow(
                        label = "运行状态",
                        value = when {
                            engine.loading -> "加载中…"
                            engine.ready -> "已加载"
                            else -> "未加载"
                        },
                        color = if (engine.ready) ios.green else ios.secondaryLabel,
                    )
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("模型", engine.modelName ?: "—")
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("上下文", if (engine.contextSize > 0) engine.contextSize.toString() + " tokens" else "—")
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("线程", if (engine.threads > 0) engine.threads.toString() else "自动")
                    engine.plan?.lowEndNote?.takeIf { it.isNotBlank() }?.let { note ->
                        AuroraDivider(indent = 16.dp)
                        StatusInfoRow("内存模式", note, mono = false)
                    }
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow(
                        "上次速度",
                        if (engine.lastTokensPerSecond > 0) String.format(Locale.US, "%.1f tok/s", engine.lastTokensPerSecond) else "—",
                    )
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("首 token", if (engine.lastFirstTokenMs > 0) engine.lastFirstTokenMs.toString() + " ms" else "—")
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("KV 命中", if (engine.cacheHitPercent > 0) engine.cacheHitPercent.toString() + "%" else "—")
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("模型文件", engine.modelPath?.substringAfterLast('/') ?: "—", mono = true)
                    if (engine.accelInfo.isNotBlank()) {
                        AuroraDivider(indent = 16.dp)
                        StatusInfoRow("加速", engine.accelInfo, mono = true)
                    }
                }
            }

            item {
                AuroraSectionLabel("后端")
                AuroraGroup {
                    StatusInfoRow(
                        "语音引擎",
                        if (viewModel.voiceEngineReady) "已就绪" else "未安装 / 不可用",
                        if (viewModel.voiceEngineReady) ios.green else ios.secondaryLabel,
                    )
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow(
                        "神经音色",
                        when {
                            activeVoice.isNotBlank() -> activeVoice
                            voice.neuralVoiceId.isNotBlank() -> voice.neuralVoiceId + "（未加载）"
                            else -> "系统 TTS"
                        },
                    )
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow(
                        "Breeze 语音",
                        when {
                            breeze.ready -> "已连接 · " + (breeze.active?.spec?.label ?: "")
                            breeze.enabled -> "已开启，地址未填好"
                            else -> "关闭"
                        },
                        if (breeze.ready) ios.green else ios.secondaryLabel,
                    )
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow(
                        "生图后端",
                        if (diffusionReady) "已加载" else "程序化渲染",
                        if (diffusionReady) ios.green else ios.secondaryLabel,
                    )
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow(
                        "本机 API",
                        if (api.running) "运行中 · :" + api.port.toString() else "未启动",
                        if (api.running) ios.green else ios.secondaryLabel,
                    )
                }
            }

            item {
                AuroraSectionLabel("本机")
                AuroraGroup {
                    StatusInfoRow("机型", snap?.deviceName ?: "—")
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("系统", snap?.android ?: "—")
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("SoC", snap?.soc ?: "—")
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("ABI", snap?.abi ?: "—", mono = true)
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("运行时长", snap?.let { formatUptime(it.uptimeMs) } ?: "—")
                    AuroraDivider(indent = 16.dp)
                    StatusInfoRow("采样", if (snap != null) "每秒刷新" else "—")
                }
            }
        }
    }
}

@Composable
private fun ModelHero(
    engine: EngineState,
    tpsHistory: List<Float>,
    onOpenModels: () -> Unit,
) {
    val ios = IosTheme.palette
    val accent = when {
        engine.ready -> ios.green
        engine.loading -> ios.orange
        else -> ios.gray
    }
    AuroraGroup(modifier = Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuroraDot(accent)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        engine.loading -> "正在加载"
                        engine.ready -> "模型在内存里"
                        else -> "没有加载模型"
                    },
                    style = IosTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                    color = ios.secondaryLabel,
                )
                Spacer(Modifier.weight(1f))
                if (engine.generating) {
                    Text("生成中", style = IosTheme.typography.caption2, color = ios.blue)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                engine.modelName ?: "去模型页挑一个",
                style = IosTheme.typography.title3,
                color = ios.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (engine.ready) {
                    "上下文 " + engine.contextSize + " · 线程 " + engine.threads +
                        if (engine.lastTokensPerSecond > 0) {
                            " · " + String.format(Locale.US, "%.1f tok/s", engine.lastTokensPerSecond)
                        } else ""
                } else {
                    "加载之后这里会显示速度、上下文和缓存命中率"
                },
                style = IosTheme.typography.caption1,
                color = ios.secondaryLabel,
            )
            if (tpsHistory.size > 1 && engine.lastTokensPerSecond > 0) {
                Spacer(Modifier.height(10.dp))
                Sparkline(
                    series = listOf(SparkSeries(tpsHistory, ios.blue)),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    maxValue = null,
                    frameColor = ios.blue,
                    gridColor = ios.separator.copy(alpha = 0.35f),
                )
            }
            if (!engine.ready) {
                Spacer(Modifier.height(12.dp))
                AuroraButton("去模型页", onClick = onOpenModels)
            }
        }
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    series: List<SparkSeries>,
    maxValue: Float?,
    foot: String,
    modifier: Modifier = Modifier,
    tall: Boolean = false,
) {
    val ios = IosTheme.palette
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            // 原来这圈 0.8dp 描边让状态页的卡片像线框，和全站无边框卡片不一致；
            // 只在深色下留一点极淡的边界，浅色由底色自己分层。
            .background(ios.secondaryGroupedBackground.copy(alpha = if (ios.isDark) 0.62f else 0.74f))
            .then(
                if (ios.isDark) Modifier.border(0.6.dp, ios.separator.copy(alpha = 0.35f), shape)
                else Modifier
            )
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AuroraIcon(icon, accent, size = 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(title, style = IosTheme.typography.footnote, color = ios.secondaryLabel)
        }
        Spacer(Modifier.height(8.dp))
        Text(value, style = IosTheme.typography.title3, color = ios.label, maxLines = 1)
        Text(
            subtitle,
            style = IosTheme.typography.caption1,
            color = ios.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (series.any { it.values.size > 1 }) {
            Spacer(Modifier.height(8.dp))
            Sparkline(
                series = series,
                modifier = Modifier.fillMaxWidth().height(if (tall) 54.dp else 32.dp),
                maxValue = maxValue,
                frameColor = accent.copy(alpha = 0.18f),
                gridColor = ios.separator.copy(alpha = 0.35f),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            foot,
            style = IosTheme.typography.caption2,
            color = ios.tertiaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CoreBars(cores: List<SystemStatus.CpuCore>, accent: Color) {
    val ios = IosTheme.palette
    if (cores.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        cores.forEach { core ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ios.fillSecondary),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(((core.usagePercent ?: 0f) / 100f).coerceIn(0.03f, 1f))
                            .background(if (core.online) accent.copy(alpha = 0.72f) else ios.gray.copy(alpha = 0.3f)),
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    core.id.toString(),
                    style = IosTheme.typography.caption2,
                    color = ios.tertiaryLabel,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StatusInfoRow(
    label: String,
    value: String,
    color: Color? = null,
    mono: Boolean = false,
) {
    val ios = IosTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = IosTheme.typography.footnote,
            color = ios.secondaryLabel,
            modifier = Modifier.width(84.dp),
        )
        Text(
            value,
            style = IosTheme.typography.footnote.copy(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
            ),
            color = color ?: ios.label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatUptime(ms: Long): String {
    val s = ms / 1000
    val days = s / 86_400
    val hours = (s % 86_400) / 3600
    val minutes = (s % 3600) / 60
    return when {
        days > 0 -> days.toString() + " 天 " + hours + " 小时"
        hours > 0 -> hours.toString() + " 小时 " + minutes + " 分"
        minutes > 0 -> minutes.toString() + " 分"
        else -> s.toString() + " 秒"
    }
}

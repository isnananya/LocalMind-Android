package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import com.localmind.ui.motion.ScrollPhysics
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.data.TtsEngineSpec
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.design.Aurora
import com.localmind.ui.design.AuroraButton
import com.localmind.ui.design.AuroraDivider
import com.localmind.ui.design.AuroraEmpty
import com.localmind.ui.design.AuroraGroup
import com.localmind.ui.design.AuroraIcon
import com.localmind.ui.design.AuroraNavBar
import com.localmind.ui.design.AuroraRow
import com.localmind.ui.design.AuroraSectionLabel
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

/**
 * Breeze TTS 2 / 任意 OpenAI 兼容 TTS 的**详细设置**。
 *
 * 这一页是三件事分开的：
 *  1. 连接：地址 / 密钥 / 模型 / 音色，以及「测试连接」（只探网络，不跑模型）；
 *  2. 合成参数：演技、CFG、种子、采样率、格式、超时；
 *  3. 试听：真的合成一句并播放。
 *
 * 分开的理由：连不上和合成慢是两种完全不同的故障，混在一个按钮里
 * 用户只会得到"转圈很久然后失败"，不知道是地址错了还是模型在跑。
 */
@Composable
fun BreezeScreen(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val st by viewModel.breeze.collectAsState()
    var draft by remember { mutableStateOf<TtsEngineSpec?>(st.active?.spec) }
    var text by remember {
        mutableStateOf("你好，我是 Breeze TTS 2。这句话是在另一台机器上合成的。")
    }
    LaunchedEffect(st.activeKey, st.options.size) { draft = st.active?.spec }

    Column(Modifier.fillMaxSize()) {
        AuroraNavBar(title = "Breeze 语音", onBack = { viewModel.openBreeze(false) })

        LazyColumn(
            flingBehavior = ScrollPhysics.fling(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 44.dp),
        ) {
            item {
                AuroraSectionLabel("状态")
                AuroraGroup {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("用外部引擎念回复", style = IosTheme.typography.body, color = ios.label)
                            Text(
                                if (st.options.isEmpty()) "未安装语音引擎插件"
                                else st.options.size.toString() + " 个引擎可选",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                        IosSwitch(
                            checked = st.enabled,
                            enabled = st.options.isNotEmpty(),
                            onCheckedChange = { viewModel.setBreezeEnabled(it) },
                        )
                    }
                    AuroraDivider(indent = 16.dp)
                    AuroraRow(
                        title = "当前引擎",
                        subtitle = st.active?.let { it.packName + " · " + it.spec.label } ?: "无",
                        showChevron = false,
                    )
                    if (st.error.isNotBlank()) {
                        AuroraDivider(indent = 16.dp)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("上次的错误", style = IosTheme.typography.caption1, color = ios.red)
                            Text(st.error.take(200), style = IosTheme.typography.caption2, color = ios.secondaryLabel)
                        }
                    }
                    if (st.probe.isNotBlank()) {
                        AuroraDivider(indent = 16.dp)
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("连接测试", style = IosTheme.typography.caption1, color = ios.green)
                            Text(st.probe, style = IosTheme.typography.caption2, color = ios.secondaryLabel)
                        }
                    }
                    if (st.status.isNotBlank()) {
                        AuroraDivider(indent = 16.dp)
                        Text(
                            st.status,
                            style = IosTheme.typography.caption2,
                            color = ios.secondaryLabel,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            if (st.options.isEmpty()) {
                item {
                    AuroraEmpty(
                        icon = AppIcons.RecordVoiceOver,
                        title = "还没有语音引擎",
                        message = "Breeze TTS 2 是 3.47B 的模型，手机跑不动它。需要在 PC 或 GPU 服务器上自建服务，手机当客户端。",
                        action = {
                            AuroraButton("去插件页导入", onClick = { viewModel.openPlugins(true) })
                        },
                    )
                }
            } else {
                if (st.options.size > 1) {
                    item {
                        AuroraSectionLabel("选择引擎")
                        AuroraGroup {
                            st.options.forEachIndexed { i, opt ->
                                if (i > 0) AuroraDivider(indent = 16.dp)
                                AuroraRow(
                                    title = opt.spec.label,
                                    subtitle = opt.packName,
                                    showChevron = false,
                                    trailing = {
                                        if (opt.key == st.activeKey) {
                                            Text("✓", style = IosTheme.typography.footnote, color = ios.blue)
                                        }
                                    },
                                    onClick = { viewModel.selectBreezeOption(opt.key) },
                                )
                            }
                        }
                    }
                }

                val spec = draft
                if (spec != null) {
                    item {
                        AuroraSectionLabel("连接")
                        AuroraGroup {
                            FieldRow("地址", spec.baseUrl) { draft = spec.copy(baseUrl = it.trim()) }
                            AuroraDivider(indent = 16.dp)
                            FieldRow("密钥", spec.apiKey) { draft = spec.copy(apiKey = it.trim()) }
                            AuroraDivider(indent = 16.dp)
                            FieldRow("模型", spec.model) { draft = spec.copy(model = it.trim()) }
                            AuroraDivider(indent = 16.dp)
                            FieldRow("音色", spec.defaultVoice) { draft = spec.copy(defaultVoice = it.trim()) }
                            AuroraDivider(indent = 16.dp)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text("演技 / instruction", style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 58.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(ios.fillSecondary)
                                        .padding(10.dp),
                                ) {
                                    if (spec.defaultInstruction.isEmpty()) {
                                        Text(
                                            "例如：温柔、语速偏慢、尾音上扬",
                                            style = IosTheme.typography.footnote,
                                            color = ios.tertiaryLabel,
                                        )
                                    }
                                    BasicTextField(
                                        value = spec.defaultInstruction,
                                        onValueChange = { draft = spec.copy(defaultInstruction = it.take(400)) },
                                        textStyle = IosTheme.typography.footnote.copy(color = ios.label),
                                        cursorBrush = SolidColor(ios.blue),
                                        maxLines = 4,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                            AuroraDivider(indent = 16.dp)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                AuroraButton(
                                    "测试连接",
                                    onClick = { viewModel.probeBreeze(draft) },
                                    color = ios.fill,
                                    loading = st.probing,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(10.dp))
                                AuroraButton(
                                    "保存",
                                    onClick = { draft?.let { viewModel.saveBreezeSpec(it) } },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    item {
                        AuroraSectionLabel("合成参数")
                        AuroraGroup {
                            NumRow("CFG", spec.cfgScale.toString()) { v ->
                                v.toFloatOrNull()?.let { draft = spec.copy(cfgScale = it.coerceIn(1f, 8f)) }
                            }
                            AuroraDivider(indent = 16.dp)
                            NumRow("种子", spec.seed.toString()) { v ->
                                v.toIntOrNull()?.let { draft = spec.copy(seed = it.coerceIn(0, 2_147_483_647)) }
                            }
                            AuroraDivider(indent = 16.dp)
                            ChoiceRow(
                                "采样率",
                                listOf(16000, 22050, 24000, 44100).map { it.toString() to it.toString() + " Hz" },
                                spec.sampleRate.toString(),
                            ) { v -> v.toIntOrNull()?.let { draft = spec.copy(sampleRate = it) } }
                            AuroraDivider(indent = 16.dp)
                            ChoiceRow(
                                "格式",
                                listOf("pcm_s16le" to "PCM", "wav" to "WAV"),
                                spec.format,
                            ) { v -> draft = spec.copy(format = v) }
                            AuroraDivider(indent = 16.dp)
                            NumRow("连接超时", spec.connectTimeoutMs.toString()) { v ->
                                v.toIntOrNull()?.let { draft = spec.copy(connectTimeoutMs = it.coerceIn(1_000, 60_000)) }
                            }
                            AuroraDivider(indent = 16.dp)
                            NumRow("读取超时", spec.readTimeoutMs.toString()) { v ->
                                v.toIntOrNull()?.let { draft = spec.copy(readTimeoutMs = it.coerceIn(5_000, 600_000)) }
                            }
                            AuroraDivider(indent = 16.dp)
                            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                                AuroraButton(
                                    "恢复插件的值",
                                    onClick = { draft = st.active?.spec },
                                    color = ios.fill,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    item {
                        AuroraSectionLabel("试听")
                        AuroraGroup {
                            Column(Modifier.padding(16.dp)) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 88.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(ios.fillSecondary)
                                        .padding(10.dp),
                                ) {
                                    BasicTextField(
                                        value = text,
                                        onValueChange = { text = it.take(600) },
                                        textStyle = IosTheme.typography.body.copy(color = ios.label),
                                        cursorBrush = SolidColor(ios.blue),
                                        maxLines = 6,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                AuroraButton(
                                    text = if (st.busy) "合成中…" else "合成并播放",
                                    loading = st.busy,
                                    enabled = !st.busy && spec.usable,
                                    onClick = { viewModel.testBreeze(text) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (!spec.usable) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "先在上面填地址再试听。",
                                        style = IosTheme.typography.caption1,
                                        color = ios.secondaryLabel,
                                    )
                                }
                            }
                        }
                    }

                    item {
                        AuroraSectionLabel("关于")
                        AuroraGroup {
                            AuroraRow(
                                title = "本机推理",
                                subtitle = "不支持（模型 3.47B / 需要 CUDA）",
                                showChevron = false,
                            )
                            AuroraDivider(indent = 16.dp)
                            AuroraRow(
                                title = "协议",
                                subtitle = if (spec.isBreeze) "Breeze 流式 PCM（24 kHz 单声道）" else "OpenAI 兼容",
                                showChevron = false,
                            )
                            AuroraDivider(indent = 16.dp)
                            Row(Modifier.padding(16.dp)) {
                                Text(
                                    "Breeze TTS 2 官方只提供 PyTorch + CUDA 推理，eager 约 7.7 GB 显存。" +
                                        "手机上没有对应运行时，所以这里只做客户端。声音克隆需要参考音频 + 文字稿；" +
                                        "纯描述音色用「演技」那一栏。",
                                    style = IosTheme.typography.caption2,
                                    color = ios.secondaryLabel,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, numeric: Boolean = false, onChange: (String) -> Unit) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = IosTheme.typography.footnote,
            color = ios.secondaryLabel,
            modifier = Modifier.width(72.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(ios.fillSecondary)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = ios.label,
                    fontSize = IosTheme.typography.footnote.fontSize,
                ),
                cursorBrush = SolidColor(ios.blue),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (numeric) {
            Spacer(Modifier.width(6.dp))
            Text("1–8", style = IosTheme.typography.caption2, color = ios.tertiaryLabel)
        }
    }
}

@Composable
private fun NumRow(label: String, value: String, onChange: (String) -> Unit) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = IosTheme.typography.footnote, color = ios.secondaryLabel, modifier = Modifier.width(84.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(ios.fillSecondary)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = ios.label, fontSize = IosTheme.typography.footnote.fontSize),
                cursorBrush = SolidColor(ios.blue),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
) {
    val ios = IosTheme.palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = IosTheme.typography.footnote, color = ios.secondaryLabel)
        Spacer(Modifier.height(7.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (value, text) ->
                val on = value == selected
                Text(
                    text,
                    style = IosTheme.typography.caption1,
                    color = if (on) ios.onAccent else ios.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (on) ios.blue else ios.fillSecondary)
                        .clickable { onPick(value) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

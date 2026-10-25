package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.localmind.data.TtsEngineSpec
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosCell
import com.localmind.ui.components.IosDivider
import com.localmind.ui.components.IosEmptyState
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosPrimaryButton
import com.localmind.ui.components.IosSecondaryButton
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

/**
 * Breeze TTS 2 的独立页。
 *
 * 为什么是一个只有语音的整页：Breeze TTS 2 和本机那两条语音链路
 *（系统 TTS / sherpa-onnx 音色）的性质完全不同 —— 它要**另一台机器**、
 * 要地址和密钥、合成一次有明显的网络往返。把它塞进「语音与音色」页会和那两条混在一起，
 * 用户分不清"为什么这个音色点了没反应"。
 *
 * 页面在**没有装「语音引擎」插件**时也要能打开（从「我的 → 更多」进来），
 * 它会把"缺什么"直接说出来，而不是灰掉或者消失。
 */
@Composable
fun BreezeScreen(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val st by viewModel.breeze.collectAsState()

    // 编辑中的副本：用户改了但没保存时，页面上的值要跟手。
    var draft by remember { mutableStateOf<TtsEngineSpec?>(st.active?.spec) }
    var text by remember {
        mutableStateOf("你好，我是 Breeze TTS 2。这句话是在另一台机器上合成的。")
    }
    LaunchedEffect(st.activeKey, st.options.size) { draft = st.active?.spec }

    Column(Modifier.fillMaxSize().background(ios.groupedBackground)) {
        IosNavigationBar(
            title = "Breeze 语音",
            collapsed = true,
            topInset = com.localmind.ui.components.statusBarTopPadding(),
            leading = {
                Row(
                    modifier = Modifier.clickable(onClick = { viewModel.selectTab(com.localmind.ui.Tab.HOME) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        com.localmind.ui.icons.AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = ios.blue,
                        modifier = Modifier.width(18.dp).height(18.dp),
                    )
                    Text("首页", style = IosTheme.typography.body, color = ios.blue)
                }
            },
        )

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // ---- 状态 ----
            IosSection(
                header = "状态",
                footer = if (st.options.isEmpty()) {
                    "没有可用引擎：去「我的 → 插件」导入一个「语音引擎」插件（tts.json）。"
                } else {
                    "打开后，AI 的语音回复会优先用这里选中的引擎；连不上时自动退回系统音色。"
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("用外部引擎念回复", style = IosTheme.typography.body, color = ios.label)
                        Text(
                            if (st.options.isEmpty()) "未安装语音引擎插件"
                            else "${st.options.size} 个引擎可选",
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                    }
                    IosSwitch(
                        checked = st.enabled,
                        enabled = st.options.isNotEmpty(),
                        onCheckedChange = { viewModel.setBreezeEnabled(it) },
                    )
                }
                IosDivider()
                IosCell(
                    title = "引擎",
                    subtitle = st.active?.let { "${it.packName} · ${it.spec.label}" } ?: "无",
                    onClick = null,
                    showChevron = false,
                )
                if (st.error.isNotBlank()) {
                    IosCell(
                        title = "上次的错误",
                        subtitle = st.error.take(160),
                        showChevron = false,
                        titleColor = ios.red,
                    )
                }
                if (st.status.isNotBlank()) {
                    IosCell(title = st.status, showChevron = false)
                }
            }

            if (st.options.isEmpty()) {
                // 高度给足：这段说明有三行，压成固定 220dp 会被裁掉半句
                //（第一次在模拟器上跑就是这样，用户看到的是"半截话"）。
                Box(Modifier.fillMaxWidth().height(380.dp)) {
                    IosEmptyState(
                        icon = AppIcons.RecordVoiceOver,
                        title = "还没有语音引擎",
                        message = "Breeze TTS 2 是 3.47B 的模型，手机跑不动它。" +
                            "需要在 PC 或 GPU 服务器上自建服务，手机当客户端。",
                        action = {
                            IosPrimaryButton(
                                text = "去插件页导入",
                                onClick = { viewModel.openPlugins(true) },
                                modifier = Modifier.width(200.dp),
                            )
                        },
                    )
                }
            } else {
                // 多引擎时给一个选择器（通常只有一个）。
                if (st.options.size > 1) {
                    IosSection(header = "选择引擎") {
                        st.options.forEachIndexed { i, opt ->
                            if (i > 0) IosDivider()
                            IosCell(
                                title = opt.spec.label,
                                subtitle = "${opt.packName} · ${opt.spec.notes.take(48)}",
                                trailing = {
                                    if (opt.key == st.activeKey) {
                                        IosPill("当前", ios.blue, filled = true)
                                    }
                                },
                                onClick = { viewModel.selectBreezeOption(opt.key) },
                                showChevron = false,
                            )
                        }
                    }
                }

                val spec = draft
                if (spec != null) {
                    IosSection(
                        header = "连接",
                        footer = "地址填你服务器上的服务根地址，例如 http://192.168.1.10:7860 。" +
                            "保存后立刻生效，不用重启。",
                    ) {
                        FieldRow("地址", spec.baseUrl) { draft = spec.copy(baseUrl = it.trim()) }
                        IosDivider()
                        FieldRow("密钥", spec.apiKey) { draft = spec.copy(apiKey = it.trim()) }
                        IosDivider()
                        FieldRow("模型", spec.model) { draft = spec.copy(model = it.trim()) }
                        IosDivider()
                        FieldRow("音色", spec.defaultVoice) { draft = spec.copy(defaultVoice = it.trim()) }
                        IosDivider()
                        FieldRow("演技", spec.defaultInstruction) {
                            draft = spec.copy(defaultInstruction = it)
                        }
                        IosDivider()
                        FieldRow("CFG", spec.cfgScale.toString(), numeric = true) {
                            it.toFloatOrNull()?.let { v -> draft = spec.copy(cfgScale = v.coerceIn(1f, 8f)) }
                        }
                        IosDivider()
                        Row(Modifier.fillMaxWidth().padding(14.dp)) {
                            IosSecondaryButton(
                                text = "恢复插件的值",
                                onClick = { draft = st.active?.spec },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            IosPrimaryButton(
                                text = "保存",
                                onClick = { draft?.let { viewModel.saveBreezeSpec(it) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    IosSection(header = "试听", footer = "合成走的是你服务器上的 Breeze TTS 2。" +
                        "第一次连不上时，错误会显示在上面那栏。") {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ios.fillSecondary)
                                    .padding(10.dp),
                            ) {
                                BasicTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    textStyle = IosTheme.typography.body.copy(color = ios.label),
                                    cursorBrush = SolidColor(ios.blue),
                                    modifier = Modifier.fillMaxWidth().height(76.dp),
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            IosPrimaryButton(
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
                                    style = IosTheme.typography.footnote,
                                    color = ios.secondaryLabel,
                                )
                            }
                        }
                    }

                    IosSection(
                        header = "关于",
                        footer = "Breeze TTS 2：3.47B 参数，官方只提供 PyTorch + CUDA 推理，" +
                            "eager 推理约 7.7 GB 显存。手机上既没有 CUDA 也没有对应运行时，" +
                            "所以这里只做客户端。声音克隆需要参考音频 + 精确文字稿，" +
                            "纯描述生成音色用「演技」那一栏。",
                    ) {
                        IosCell(
                            title = "本机推理",
                            subtitle = "不支持（模型 3.47B / 需要 CUDA）",
                            showChevron = false,
                        )
                        IosDivider()
                        IosCell(
                            title = "协议",
                            subtitle = if (spec.isBreeze) "Breeze 流式 PCM（24 kHz 单声道）" else "OpenAI 兼容",
                            showChevron = false,
                        )
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
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
            modifier = Modifier.width(56.dp),
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

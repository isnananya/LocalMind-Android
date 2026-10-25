package com.localmind.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.localmind.data.Emotion
import com.localmind.ui.AppViewModel
import com.localmind.ui.Tab
import com.localmind.ui.components.IosLargeTitle
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosSlider
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

private val languages = listOf(
    "auto" to "自动",
    "zh-CN" to "普通话",
    "zh-HK" to "粤语",
    "en-US" to "English",
    "ja-JP" to "日本語",
)

/**
 * 语音引擎那一行：下载 / 已就绪 / 删除。
 *
 * 它是所有神经音色的共同前置条件，所以单独占一行 —— 藏起来的话，用户点了
 * "下载音色"才发现还要再下一个，会觉得被骗。
 *
 * 文案刻意只留"多大、什么状态"。`onnxruntime` / `sherpa-onnx` 这类库名对
 * 用户没有任何决策价值 —— 他要判断的只有一件事：值不值得下这 36 MB。
 */
@Composable
private fun EngineRow(
    ready: Boolean,
    state: com.localmind.service.VoiceModelDownloader.State,
    bytes: Long,
    abi: String,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val ios = IosTheme.palette
    val busy = state.busy
    val failed = state.status == com.localmind.service.VoiceModelDownloader.Status.FAILED

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ios.orange.copy(alpha = 0.12f))
            .padding(vertical = 10.dp, horizontal = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("语音引擎", style = IosTheme.typography.footnote, color = ios.label)
                    Spacer(Modifier.width(6.dp))
                    if (ready) {
                        IosPill("已就绪", ios.green)
                    } else {
                        IosPill("36 MB", ios.orange)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        busy -> "下载中…"
                        failed -> "失败：${state.error ?: "未知原因"}"
                        ready -> "已安装 ${
                            com.localmind.util.Formatters.size(bytes)
                        }"
                        else -> "装上才能用神经音色"
                    },
                    style = IosTheme.typography.caption1,
                    color = when {
                        failed -> ios.red
                        busy -> ios.orange
                        else -> ios.secondaryLabel
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                busy -> RowAction("取消", ios.red, {})
                ready -> RowAction("删除", ios.red, onDelete)
                else -> RowAction("下载", ios.orange, onDownload)
            }
        }

        if (busy) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ios.fill)
            ) {
                // 官方只发整包 tarball，长度在解压前拿不到，所以这里是不确定进度条
                // （来回走），而不是假装知道百分比。
                Box(Modifier.fillMaxWidth(0.4f).height(3.dp).background(ios.orange))
            }
        }
    }
}

/**
 * 一行音色：左边是名字与状态，右边是"下载 / 取消 / 试听 / 使用"。
 *
 * 徽标只放**对用户有意义**的：音色自己的标签（轻量/角色）和"使用中"。
 * 云端/本地的徽标已经去掉了 —— 现在只有本地音色，一个人人都有的徽标
 * 只会占地方（见 VoicePack.kt 文件头）。
 */
@Composable
private fun NeuralVoiceRow(
    voice: com.localmind.data.VoiceEntry,
    selected: Boolean,
    installed: Boolean,
    bytes: Long,
    state: com.localmind.service.VoiceModelDownloader.State?,
    enabled: Boolean,
    /**
     * 非空 = 这个音色装上也发不出声（架构不对 / 上次把它加载崩了）。
     *
     * 这一行必须把"试听 / 使用"收掉：点了不是"没声音"，而是整个进程消失 ——
     * 原生库在配置不对时是直接结束进程，界面上没有任何东西能兜住。
     */
    blocked: String?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit,
) {
    val ios = IosTheme.palette
    val busy = state?.busy == true
    val failed = state?.status == com.localmind.service.VoiceModelDownloader.Status.FAILED

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) ios.fillSecondary else Color.Transparent)
            .padding(vertical = 9.dp, horizontal = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(voice.label, style = IosTheme.typography.footnote, color = ios.label)
                    voice.badge?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.width(6.dp))
                        IosPill(it, ios.purple)
                    }
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
                        IosPill("使用中", ios.blue)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        busy -> state?.let {
                            if (it.status == com.localmind.service.VoiceModelDownloader.Status.EXTRACTING) {
                                "正在解压…"
                            } else {
                                "下载中 ${(it.progress * 100).toInt()}%  " +
                                    com.localmind.util.Formatters.size(it.downloadedBytes) +
                                    " / " + com.localmind.util.Formatters.size(it.totalBytes)
                            }
                        } ?: "下载中…"
                        failed -> "失败：${state?.error ?: "未知原因"}"
                        blocked != null -> "⚠️ $blocked（已安装 ${
                            com.localmind.util.Formatters.size(bytes)
                        }，可删除后换一个）"
                        installed -> "${voice.description} · 已安装 ${
                            com.localmind.util.Formatters.size(bytes)
                        }"
                        else -> voice.description.ifBlank { "未下载" }
                    },
                    style = IosTheme.typography.caption1,
                    color = when {
                        failed -> ios.red
                        busy -> ios.blue
                        else -> ios.secondaryLabel
                    },
                )
            }

            Spacer(Modifier.width(8.dp))

            when {
                busy -> {
                    RowAction("取消", ios.red, onCancel)
                }
                // 装了但架构不对：只留"删除"。给出"试听/使用"等于把用户
                // 引到一个必然让应用退出的按钮上。
                installed && blocked != null -> {
                    RowAction("删除", ios.red, onDelete)
                }
                installed -> {
                    RowAction("试听", ios.secondaryLabel, onPreview)
                    if (!selected) {
                        RowAction("使用", ios.blue, onSelect)
                    }
                    RowAction("删除", ios.red, onDelete)
                }
                else -> {
                    RowAction(
                        if (enabled) "下载" else "不可用",
                        if (enabled) ios.blue else ios.tertiaryLabel,
                        onDownload,
                    )
                }
            }
        }

        if (busy) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ios.fill)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((state?.progress ?: 0f).coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(ios.blue)
                )
            }
        }
    }
}

@Composable
private fun RowAction(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        style = IosTheme.typography.footnote,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
fun VoiceScreen(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val voice by viewModel.voice.collectAsState()
    val stt by viewModel.voiceSttState.collectAsState()
    val engineState by viewModel.engineState.collectAsState()

    var lastHeard by remember { mutableStateOf("") }
    var lastReply by remember { mutableStateOf("") }
    var awaitingReply by remember { mutableStateOf(false) }

    // ---- 麦克风权限：**在这里要，不在启动时要** ----
    //
    // 原来是在 MainActivity 启动时就申请，于是**全新安装的第一次打开**，
    // 引导页上先弹一个"允许 LocalMind 录音吗？" —— 用户还没看到这个应用是干什么的，
    // 就被问要不要交出麦克风，正常反应是拒绝，而拒绝之后语音就再也用不了
    //（系统不会再弹，得自己去设置里开）。
    //
    // 现在改成：按住说话的那一刻才申请。那时用户已经知道自己在干嘛。
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { ok ->
        micGranted = ok
        if (!ok) viewModel.toast("没有麦克风权限，语音输入用不了 —— 可以到系统设置里打开")
    }

    // 只列当前语言的发音人。系统引擎会给几百个（实测 442 个），
    // 不筛的话中文用户看到的前 12 个全是阿拉伯语/保加利亚语。
    val ttsVoices = remember(voice.language) { viewModel.tts.voicesFor(voice.language) }

    // When the recogniser produces a final transcript, push it through the model.
    LaunchedEffect(stt.finalText) {
        val heard = stt.finalText.trim()
        if (heard.isEmpty() || heard == lastHeard) return@LaunchedEffect
        lastHeard = heard
        awaitingReply = true
        viewModel.send(heard)
        viewModel.clearStt()
    }

    LaunchedEffect(engineState.generating, engineState.tokensGenerated) {
        if (awaitingReply && !engineState.generating && engineState.tokensGenerated > 0) {
            awaitingReply = false
            lastReply = viewModel.activeConversation()?.messages?.lastOrNull()?.content.orEmpty()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
    ) {
        IosNavigationBar(
            title = "语音对话",
            collapsed = true,
            topInset = statusBarTopPadding(),
            leading = {
                Icon(AppIcons.GraphicEq, contentDescription = null, tint = ios.green, modifier = Modifier.size(20.dp))
            },
            trailing = {
                Icon(
                    AppIcons.Tune,
                    contentDescription = "设置",
                    tint = ios.blue,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { viewModel.openSettings(true) },
                )
            },
        )
        IosLargeTitle("语音对话")

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
        ) {
            item {
                IosSection(
                    header = "语音对话",
                    footer = "开启后：长按下方麦克风说话 → 本机识别 → 本机大模型回答 → 本机语音朗读。全程离线（识别服务由系统提供，部分机型需联网）。",
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("启用语音模式", style = IosTheme.typography.body, color = ios.label)
                            Text(
                                "聊天页会出现语音按钮，回复自动朗读",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                        IosSwitch(
                            checked = voice.enabled,
                            onCheckedChange = { on -> viewModel.updateVoice { it.copy(enabled = on) } },
                        )
                    }
                }
            }

            item {
                IosSection(header = "情感音色") {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "情感会同时改变两件事：朗读的音高/语速/音色，以及模型生成文字的语气。",
                            style = IosTheme.typography.caption1,
                            color = ios.secondaryLabel,
                        )
                        Spacer(Modifier.height(10.dp))
                        Emotion.entries.chunked(4).forEach { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                row.forEach { emotion ->
                                    val selected = voice.emotion == emotion
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (selected) ios.blue else ios.fillSecondary)
                                            .clickable {
                                                viewModel.updateVoice { it.copy(emotion = emotion) }
                                                viewModel.speak("你好，我是本地模型，现在用${emotion.label}的语气说话。")
                                            }
                                            .padding(vertical = 9.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(emotion.emoji, style = IosTheme.typography.body)
                                            Text(
                                                emotion.label,
                                                style = IosTheme.typography.caption2,
                                                color = if (selected) ios.onAccent else ios.label,
                                            )
                                        }
                                    }
                                }
                                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        SliderRow("语速", voice.speed, 0.4f..2.0f, ios.blue) { v ->
                            viewModel.updateVoice { it.copy(speed = v) }
                        }
                        SliderRow("音高", voice.pitch, 0.5f..1.8f, ios.purple) { v ->
                            viewModel.updateVoice { it.copy(pitch = v) }
                        }
                        SliderRow("音量", voice.volume, 0.1f..1.0f, ios.green) { v ->
                            viewModel.updateVoice { it.copy(volume = v) }
                        }

                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ios.fillSecondary)
                                .clickable {
                                    viewModel.speak(
                                        "这是一段试听。当前情感是${voice.emotion.label}，" +
                                            "语速 ${"%.2f".format(voice.speed)}，音高 ${"%.2f".format(voice.pitch)}。"
                                    )
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(AppIcons.VolumeUp, contentDescription = null, tint = ios.blue, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("试听当前音色", style = IosTheme.typography.headline, color = ios.blue)
                            }
                        }
                    }
                }
            }

            item {
                IosSection(header = "语言与发音人") {
                    Column(Modifier.padding(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            languages.forEach { (tag, label) ->
                                val selected = voice.language == tag
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (selected) ios.blue else ios.fillSecondary)
                                        .clickable {
                                            viewModel.updateVoice { it.copy(language = tag) }
                                            viewModel.tts.setLanguage(tag)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        label,
                                        style = IosTheme.typography.footnote,
                                        color = if (selected) ios.onAccent else ios.label,
                                    )
                                }
                            }
                        }

                        if (ttsVoices.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                // 只显示 12 个，就把这件事说出来 —— 否则标题里的数字
                                // 和眼睛看到的条数对不上，用户会以为列表坏了。
                                "系统发音人（${ttsVoices.size}）" +
                                    if (ttsVoices.size > 12) " · 显示前 12 个" else "",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                            Spacer(Modifier.height(6.dp))
                            ttsVoices.take(12).forEach { v ->
                                val selected = voice.voiceName == v.name
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) ios.fillSecondary else Color.Transparent)
                                        .clickable {
                                            viewModel.updateVoice { it.copy(voiceName = v.name) }
                                            viewModel.speak("你好，这是新的发音人音色。")
                                        }
                                        .padding(vertical = 9.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(v.label, style = IosTheme.typography.footnote, color = ios.label, modifier = Modifier.weight(1f))
                                    if (selected) IosPill("使用中", ios.blue)
                                }
                            }
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "未读取到系统发音人列表（部分机型在初始化后才会返回）。",
                                style = IosTheme.typography.caption1,
                                color = ios.tertiaryLabel,
                            )
                        }
                    }
                }
            }

            item {
                // 音色列表**完全来自语音插件**。主程序不带任何音色，
                // 所以这里必须真的处理"一个都没装"的情况，而不是画个空壳。
                val activeNeural by viewModel.activeNeuralVoice.collectAsState()
                val dlStates by viewModel.voiceDownloadStates.collectAsState()
                val engineState by viewModel.voiceEngineState.collectAsState()
                val voices by viewModel.voiceCatalog.collectAsState()
                // 传 engineState 进去：voiceEngineReady 本身不可观察，必须借这个
                // StateFlow 的变化把重组带起来，否则会出现"装完了界面还是灰的"。
                val engineReady = viewModel.engineReady(engineState)
                val engineNeeded = viewModel.voiceEngineNeedsDownload

                IosSection(header = "音色") {
                    if (voices.isEmpty()) {
                        // 空态：告诉用户去哪拿，而不是留一片空白。
                        Column(Modifier.padding(16.dp)) {
                            Text("还没有音色", style = IosTheme.typography.body, color = ios.label)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "音色由插件提供。去「我的 → 插件」导入一个语音音色插件。",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                            if (activeNeural.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                RowAction("关闭当前音色", ios.red) { viewModel.releaseNeuralVoice() }
                            }
                        }
                    } else {
                        Column(Modifier.padding(14.dp)) {
                            // ---- 第 0 层：原生推理库 ----
                            if (engineNeeded) {
                                EngineRow(
                                    ready = engineReady,
                                    state = engineState,
                                    bytes = viewModel.voiceEngineBytes(),
                                    abi = viewModel.voiceEngineAbi,
                                    onDownload = { viewModel.downloadVoiceEngine() },
                                    onDelete = { viewModel.deleteVoiceEngine() },
                                )
                                Spacer(Modifier.height(10.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(ios.separator)
                                )
                                Spacer(Modifier.height(4.dp))
                            }

                            voices.forEach { v ->
                                NeuralVoiceRow(
                                    voice = v,
                                    selected = activeNeural == v.id,
                                    installed = viewModel.voiceInstalled(v),
                                    bytes = viewModel.voiceInstalledBytes(v),
                                    state = dlStates[v.id],
                                    // 引擎没就绪时，音色能不能加载都是未知数，所以：
                                    // 已经装好的仍然允许"试听/使用"（真失败会提示），
                                    // 但**下载**按钮禁用，免得用户先下 115 MB 才发现引擎没有。
                                    enabled = engineReady,
                                    blocked = viewModel.voiceUnusableReason(v),
                                    onSelect = { viewModel.selectNeuralVoice(v) },
                                    onDownload = { viewModel.downloadVoice(v) },
                                    onCancel = { viewModel.cancelVoiceDownload(v) },
                                    onDelete = { viewModel.deleteVoice(v) },
                                    onPreview = { viewModel.previewVoice(v) },
                                )
                            }

                            if (activeNeural.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                RowAction("关闭语音", ios.red) { viewModel.releaseNeuralVoice() }
                            }
                        }
                    }
                }
            }

            item {
                IosSection(header = "对话行为") {
                    ToggleRow("自动朗读回复", "模型写完一句就读一句", voice.autoSpeak) { on ->
                        viewModel.updateVoice { it.copy(autoSpeak = on) }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                            .height(0.5.dp)
                            .background(ios.separator)
                    )
                    ToggleRow("打断播报", "再次说话时自动停止朗读", voice.bargeIn) { on ->
                        viewModel.updateVoice { it.copy(bargeIn = on) }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ios.secondaryGroupedBackground)
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("长按说话", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
                    Spacer(Modifier.height(14.dp))

                    val active = stt.listening
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .scale(if (active) 1.06f else 1f)
                            .clip(CircleShape)
                            .background(if (active) ios.red else ios.blue)
                            .pointerInput(voice.enabled) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitPointerEvent()
                                        val pressed = down.changes.any { it.pressed }
                                        if (pressed && !stt.listening) {
                                            if (!micGranted) {
                                                // 第一次按住：先要权限。给完再按一次即可。
                                                micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                            } else {
                                                if (voice.bargeIn) viewModel.stopSpeaking()
                                                viewModel.startListening()
                                            }
                                        } else if (!pressed && stt.listening) {
                                            viewModel.stopListening()
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (active) AppIcons.Stop else AppIcons.Mic,
                            contentDescription = "麦克风",
                            tint = ios.onAccent,
                            modifier = Modifier.size(42.dp),
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    Text(
                        when {
                            stt.listening -> stt.partial.ifBlank { "正在聆听…" }
                            stt.error != null -> stt.error!!
                            !micGranted -> "按住说话 —— 第一次会向你要麦克风权限"
                            !engineState.ready -> "先到模型页加载一个模型"
                            else -> "按住开始识别"
                        },
                        style = IosTheme.typography.subheadline,
                        color = if (stt.error != null) ios.red else ios.label,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )

                    AnimatedVisibility(visible = stt.listening) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth(0.6f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(ios.fill)
                        ) {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .fillMaxWidth(((stt.rmsDb + 2) / 12f).coerceIn(0.05f, 1f))
                                    .height(4.dp)
                                    .background(ios.green)
                            )
                        }
                    }
                }
            }

            if (lastHeard.isNotBlank() || lastReply.isNotBlank()) {
                item {
                    IosSection(header = "最近一轮") {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(AppIcons.Person, contentDescription = null, tint = ios.blue, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    lastHeard.ifBlank { "—" },
                                    style = IosTheme.typography.subheadline,
                                    color = ios.label,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(AppIcons.SmartToy, contentDescription = null, tint = ios.green, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    lastReply.ifBlank { "等待模型回复…" },
                                    style = IosTheme.typography.subheadline,
                                    color = ios.label,
                                    maxLines = 8,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, ios.separator, RoundedCornerShape(10.dp))
                        .clickable { viewModel.selectTab(Tab.CHAT) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("回到文字聊天", style = IosTheme.typography.headline, color = ios.blue)
                }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onChange: (Float) -> Unit,
) {
    val ios = IosTheme.palette
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = IosTheme.typography.footnote, color = ios.secondaryLabel, modifier = Modifier.width(44.dp))
            IosSlider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                modifier = Modifier.weight(1f),
                activeColor = accent,
            )
            Text(
                String.format(java.util.Locale.US, "%.2f", value),
                style = IosTheme.typography.caption1,
                color = ios.label,
                modifier = Modifier.width(44.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = IosTheme.typography.body, color = ios.label)
            Text(subtitle, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
        }
        IosSwitch(checked = checked, onCheckedChange = onChange)
    }
}

package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.data.Emotion
import com.localmind.data.VoiceEntry
import com.localmind.service.VoiceModelDownloader
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosSlider
import com.localmind.ui.components.navigationBottomPadding
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.design.Aurora
import com.localmind.ui.design.AuroraButton
import com.localmind.ui.design.AuroraDivider
import com.localmind.ui.design.AuroraGroup
import com.localmind.ui.design.AuroraIcon
import com.localmind.ui.design.AuroraNavBar
import com.localmind.ui.design.AuroraRow
import com.localmind.ui.design.AuroraSectionLabel
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import com.localmind.util.Formatters
import java.util.Locale

private val LANGUAGES = listOf(
    "auto" to "自动",
    "zh-CN" to "普通话",
    "zh-HK" to "粤语",
    "en-US" to "English",
    "ja-JP" to "日本語",
)

private val SAMPLES = listOf(
    "你好" to "你好，我是 LocalMind。这是一段 TTS 测试。",
    "天气" to "今天天气不错，适合出去走走。",
    "绕口令" to "四是四，十是十，十四是十四，四十是四十。",
)

/**
 * 「TTS 测试」：只回答一个问题 —— **这台手机现在用什么声音说话，好不好听**。
 *
 * 它不再是「语音对话」页：语音输入是聊天输入框旁边那颗麦克风的事，
 * 把 STT、TTS、音色下载、情绪参数全塞在一页里，用户找不到"试听"按钮。
 *
 * 现在只有四块：
 *  1. 试听：随便写一句话，点朗读；
 *  2. 音色：系统发音人 / 神经音色（内置 4 个 + 插件提供的，按需下载）；
 *  3. 参数：情感、语速、音高、音量、音色提示词；
 *  4. 对话行为。
 *
 * 「Breeze 语音」这一块在 1.0.6 起从这里撤掉了：手机跑不动那个 3.47B 模型，
 * 它只是"给有服务器的人用的客户端"，不该出现在每个人都会打开的 TTS 页里。
 * 代码和整页都还在（`BreezeScreen`，插件装上之后仍能从别处打开）。
 *
 * **每个对话**的朗读开关与音色提示词不在这里 —— 它们在对话自己的设置页
 * （见 ConversationVoiceSection），因为那属于"这个对话听起来像谁"。
 */
@Composable
fun VoiceScreen(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val voice by viewModel.voice.collectAsState()
    val catalog by viewModel.voiceCatalog.collectAsState()
    val downloadStates by viewModel.voiceDownloadStates.collectAsState()
    val engineState by viewModel.voiceEngineState.collectAsState()
    val activeNeural by viewModel.activeNeuralVoice.collectAsState()
    val breeze by viewModel.breeze.collectAsState()
    val engineReady = viewModel.engineReady(engineState)

    var text by remember { mutableStateOf(SAMPLES.first().second) }
    var showSystemVoices by remember { mutableStateOf(false) }
    // 多选：批量下载/删除是语音页最缺的一件操作。选中的是 id，不是下标 ——
    // 列表会随插件刷新而重排，下标一错就会删错音色。
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val selectedVoices = catalog.filter { it.id in selectedIds }
    val downloadable = selectedVoices.filter { !viewModel.voiceInstalled(it) }
    val removable = selectedVoices.filter { viewModel.voiceInstalled(it) }
    val systemVoices = remember(voice.language) { viewModel.tts.voicesFor(voice.language) }

    val current = when {
        breeze.ready -> "Breeze · " + (breeze.active?.spec?.label ?: "")
        activeNeural.isNotBlank() -> "神经音色 · " + activeNeural
        else -> "系统 TTS" + (voice.voiceName?.takeIf { it.isNotBlank() }?.let { " · " + it } ?: "")
    }

    Column(Modifier.fillMaxSize()) {
        AuroraNavBar(
            title = "TTS 测试",
            onBack = { viewModel.openVoiceScreen(false) },
            trailing = {
                if (catalog.isNotEmpty()) {
                    Text(
                        if (selectMode) "完成" else "多选",
                        style = IosTheme.typography.body,
                        color = ios.blue,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable {
                                selectMode = !selectMode
                                if (!selectMode) selectedIds = emptySet()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            },
        )

        LazyColumn(
            // weight 而不是 fillMaxSize：多选时底部要固定一条操作条，
            // 列表必须给它让出位置。
            modifier = Modifier.fillMaxWidth().weight(1f),
            // 多选时底部多一条固定操作条，最后一项要能滚到它上面。
            contentPadding = PaddingValues(top = 4.dp, bottom = if (selectMode) 128.dp else 44.dp),
        ) {
            item {
                AuroraGroup(modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AuroraIcon(AppIcons.GraphicEq, ios.green, size = 34.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("试听", style = IosTheme.typography.headline, color = ios.label)
                                Text(
                                    current,
                                    style = IosTheme.typography.caption1,
                                    color = ios.secondaryLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 92.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ios.fillSecondary)
                                .padding(12.dp),
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    "写一句要读的话…",
                                    style = IosTheme.typography.body,
                                    color = ios.tertiaryLabel,
                                )
                            }
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it.take(600) },
                                textStyle = IosTheme.typography.body.copy(color = ios.label),
                                cursorBrush = SolidColor(ios.blue),
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            SAMPLES.forEach { (label, sample) ->
                                Text(
                                    label,
                                    style = IosTheme.typography.caption1,
                                    color = ios.blue,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(ios.blue.copy(alpha = 0.10f))
                                        .clickable { text = sample }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AuroraButton(
                                text = "朗读",
                                onClick = {
                                    if (text.isBlank()) viewModel.toast("先写一句要读的")
                                    else viewModel.speak(text)
                                },
                                modifier = Modifier.weight(1f),
                            )
                            AuroraButton(
                                text = "停止",
                                onClick = { viewModel.stopSpeaking() },
                                color = ios.fill,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item {
                AuroraSectionLabel("音色")
                AuroraGroup {
                    val engine = engineState
                    if (viewModel.voiceEngineNeedsDownload && !engineReady) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AuroraIcon(AppIcons.Mic, ios.orange, size = 30.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("语音引擎", style = IosTheme.typography.body, color = ios.label)
                                Text(
                                    when {
                                        // 36 MB 的引擎包在慢网络上能下十几分钟，只写「下载中…」
                                        // 用户不知道它是不是卡死了。
                                        engine.busy && engine.downloadedBytes > 0 -> "下载引擎 " +
                                            String.format(Locale.US, "%.0f%%", engine.progress * 100) +
                                            " · " + Formatters.size(engine.downloadedBytes)
                                        engine.busy -> "下载中…"
                                        engine.status == VoiceModelDownloader.Status.FAILED ->
                                            "失败：" + (engine.error ?: "未知原因")
                                        else -> "神经音色要先装引擎（约 36 MB）"
                                    },
                                    style = IosTheme.typography.caption1,
                                    color = if (engine.status == VoiceModelDownloader.Status.FAILED) ios.red else ios.secondaryLabel,
                                )
                            }
                            if (engine.busy) {
                                TextAction("下载中…", ios.secondaryLabel) {}
                            } else {
                                TextAction("安装", ios.blue) { viewModel.downloadVoiceEngine() }
                            }
                        }
                        AuroraDivider(indent = 16.dp)
                    }

                    AuroraRow(
                        title = "系统发音人",
                        subtitle = if (systemVoices.isEmpty()) "系统没有返回发音人" else
                            "当前：" + (voice.voiceName?.takeIf { it.isNotBlank() } ?: "自动"),
                        leading = { AuroraIcon(AppIcons.RecordVoiceOver, ios.gray, size = 30.dp) },
                        showChevron = false,
                        trailing = {
                            Text(
                                if (showSystemVoices) "收起" else "展开",
                                style = IosTheme.typography.caption1,
                                color = ios.blue,
                            )
                        },
                        onClick = { showSystemVoices = !showSystemVoices },
                    )
                    if (showSystemVoices) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                LANGUAGES.forEach { (tag, label) ->
                                    val on = voice.language == tag
                                    Text(
                                        label,
                                        style = IosTheme.typography.caption1,
                                        color = if (on) ios.onAccent else ios.label,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (on) ios.blue else ios.fillSecondary)
                                            .clickable {
                                                viewModel.updateVoice { it.copy(language = tag) }
                                                viewModel.tts.setLanguage(tag)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            if (systemVoices.isEmpty()) {
                                Text(
                                    "这个语言下系统没有发音人，换一个语言或安装系统语音数据。",
                                    style = IosTheme.typography.caption2,
                                    color = ios.tertiaryLabel,
                                )
                            } else {
                                systemVoices.take(12).forEach { v ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(9.dp))
                                            .clickable {
                                                viewModel.updateVoice { it.copy(voiceName = v.name) }
                                                viewModel.speak("你好，这是新的发音人。")
                                            }
                                            .padding(vertical = 7.dp, horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            v.label,
                                            style = IosTheme.typography.footnote,
                                            color = ios.label,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (voice.voiceName == v.name) {
                                            Text("✓", style = IosTheme.typography.footnote, color = ios.blue)
                                        }
                                    }
                                }
                                if (systemVoices.size > 12) {
                                    Text(
                                        "只显示前 12 个，共 " + systemVoices.size + " 个",
                                        style = IosTheme.typography.caption2,
                                        color = ios.tertiaryLabel,
                                    )
                                }
                            }
                        }
                        AuroraDivider(indent = 16.dp)
                    }

                    if (catalog.isEmpty()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("还没有神经音色", style = IosTheme.typography.body, color = ios.label)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "内置音色清单没加载出来（APK 资源被裁掉了？）；也可以装一个语音插件。",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                    } else {
                        catalog.forEachIndexed { index, v ->
                            if (index > 0) AuroraDivider(indent = 16.dp)
                            NeuralVoiceRow(
                                viewModel = viewModel,
                                voice = v,
                                selected = activeNeural == v.id,
                                state = downloadStates[v.id],
                                selectMode = selectMode,
                                checked = v.id in selectedIds,
                                onToggle = {
                                    selectedIds =
                                        if (v.id in selectedIds) selectedIds - v.id else selectedIds + v.id
                                },
                            )
                        }
                    }
                }
            }

            item {
                AuroraSectionLabel("参数")
                AuroraGroup {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("情感", style = IosTheme.typography.footnote, color = ios.secondaryLabel)
                        Spacer(Modifier.height(7.dp))
                        Emotion.entries.chunked(4).forEach { row ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                row.forEach { emotion ->
                                    val selected = voice.emotion == emotion
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (selected) ios.blue else ios.fillSecondary)
                                            .clickable { viewModel.updateVoice { it.copy(emotion = emotion) } }
                                            .padding(vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(emotion.emoji, style = IosTheme.typography.body)
                                        Text(
                                            emotion.label,
                                            style = IosTheme.typography.caption2,
                                            color = if (selected) ios.onAccent else ios.label,
                                        )
                                    }
                                }
                                repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        SliderRow("语速", voice.speed, 0.4f..2.0f, ios.blue) { v ->
                            viewModel.updateVoice { it.copy(speed = v) }
                        }
                        SliderRow("音高", voice.pitch, 0.5f..1.8f, ios.purple) { v ->
                            viewModel.updateVoice { it.copy(pitch = v) }
                        }
                        SliderRow("音量", voice.volume, 0.1f..1.0f, ios.green) { v ->
                            viewModel.updateVoice { it.copy(volume = v) }
                        }
                    }
                    AuroraDivider(indent = 16.dp)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("音色提示词", style = IosTheme.typography.body, color = ios.label)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "全局默认。每个对话可以在自己的设置里覆盖；留空则用系统发音人的自然语气。",
                            style = IosTheme.typography.caption1,
                            color = ios.secondaryLabel,
                        )
                        Spacer(Modifier.height(8.dp))
                        PromptField(
                            value = voice.stylePrompt,
                            placeholder = "例如：温柔一点，语速偏慢，尾音上扬。",
                        ) { v -> viewModel.updateVoice { it.copy(stylePrompt = v) } }
                    }
                }
            }

            item {
                AuroraSectionLabel("对话行为")
                AuroraGroup {
                    ToggleRow(
                        title = "启用语音",
                        subtitle = "聊天页显示麦克风与朗读入口",
                        checked = voice.enabled,
                    ) { on -> viewModel.updateVoice { it.copy(enabled = on) } }
                    AuroraDivider(indent = 16.dp)
                    ToggleRow(
                        title = "自动朗读回复",
                        subtitle = "模型写完一段就读一段",
                        checked = voice.autoSpeak,
                    ) { on -> viewModel.updateVoice { it.copy(autoSpeak = on) } }
                    AuroraDivider(indent = 16.dp)
                    ToggleRow(
                        title = "打断播报",
                        subtitle = "再次说话时先停下当前朗读",
                        checked = voice.bargeIn,
                    ) { on -> viewModel.updateVoice { it.copy(bargeIn = on) } }
                }
            }

            item {
                Column(Modifier.padding(horizontal = Aurora.PagePadding + 4.dp, vertical = 14.dp)) {
                    Text(
                        "提示：每个对话的朗读开关与音色提示词在「对话 → 右上角设置 → 朗读」里；" +
                            "那一页只影响当前对话。",
                        style = IosTheme.typography.caption2,
                        color = ios.tertiaryLabel,
                    )
                }
            }
        }

        // ---- 多选的固定底栏 ----
        //
        // 放在列表外面，而不是列表最后一项：勾了 5 个音色之后用户要能在**任何**
        // 滚动位置点到「下载所选」，否则得先滑到底才能开始。
        if (selectMode) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(ios.groupedBackground)
                    .padding(
                        start = Aurora.PagePadding,
                        top = 10.dp,
                        end = Aurora.PagePadding,
                        bottom = 10.dp + navigationBottomPadding(),
                    ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AuroraButton(
                    text = if (downloadable.isEmpty()) "下载所选" else "下载所选 (" + downloadable.size + ")",
                    enabled = downloadable.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.downloadVoices(downloadable)
                        selectedIds = emptySet()
                    },
                )
                AuroraButton(
                    text = if (removable.isEmpty()) "删除所选" else "删除所选 (" + removable.size + ")",
                    color = ios.fill,
                    enabled = removable.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        viewModel.deleteVoices(removable)
                        selectedIds = emptySet()
                    },
                )
            }
        }
    }
}

@Composable
private fun NeuralVoiceRow(
    viewModel: AppViewModel,
    voice: VoiceEntry,
    selected: Boolean,
    state: VoiceModelDownloader.State?,
    selectMode: Boolean = false,
    checked: Boolean = false,
    onToggle: () -> Unit = {},
) {
    val ios = IosTheme.palette
    val busy = state?.busy == true
    val failed = state?.status == VoiceModelDownloader.Status.FAILED
    val installed = viewModel.voiceInstalled(voice)
    val bytes = viewModel.voiceInstalledBytes(voice)
    val blocked = viewModel.voiceUnusableReason(voice)

    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when {
                    checked -> ios.blue.copy(alpha = 0.08f)
                    selected -> ios.blue.copy(alpha = 0.06f)
                    else -> Color.Transparent
                }
            )
            .then(if (selectMode) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            SelectMark(checked)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 标题必须让位给徽标：Row 会先量非加权子项（徽标），剩下的才给标题。
                // 之前标题把整行吃满，徽标拿到的宽度只剩几个像素，于是
                // "使用中" / "多说话人" 被逐字折行、看起来像错位的怪东西。
                Text(
                    voice.label,
                    style = IosTheme.typography.body,
                    color = ios.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (voice.badge.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        voice.badge,
                        style = IosTheme.typography.caption2,
                        color = ios.purple,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ios.purple.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "使用中",
                        style = IosTheme.typography.caption2,
                        color = ios.blue,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ios.blue.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                when {
                    busy -> state?.let {
                        if (it.status == VoiceModelDownloader.Status.EXTRACTING) "正在解压…"
                        else "下载中 " + String.format(Locale.US, "%.0f%%", it.progress * 100) +
                            "  " + Formatters.size(it.downloadedBytes) + " / " + Formatters.size(it.totalBytes)
                    } ?: "下载中…"
                    failed -> "失败：" + (state?.error ?: "未知原因")
                    blocked != null -> "⚠️ " + blocked
                    installed -> voice.description.ifBlank { "已安装" } + " · " + Formatters.size(bytes)
                    else -> voice.description.ifBlank { "未下载" }
                },
                style = IosTheme.typography.caption1,
                color = when {
                    failed -> ios.red
                    busy -> ios.blue
                    else -> ios.secondaryLabel
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        // 多选时行内操作全部收起：那些按钮只对**当前这一行**有意义，
        // 而多选的语义是"对勾上的这批做同一件事"。
        if (!selectMode) {
            when {
                busy -> TextAction("取消", ios.red) { viewModel.cancelVoiceDownload(voice) }
                blocked != null && installed -> TextAction("删除", ios.red) { viewModel.deleteVoice(voice) }
                installed -> {
                    if (!selected) TextAction("使用", ios.blue) { viewModel.selectNeuralVoice(voice) }
                    TextAction("试听", ios.secondaryLabel) { viewModel.previewVoice(voice) }
                    TextAction("删除", ios.red) { viewModel.deleteVoice(voice) }
                }
                else -> TextAction(
                    if (viewModel.voiceEngineReady) "下载" else "先装引擎",
                    if (viewModel.voiceEngineReady) ios.blue else ios.tertiaryLabel,
                ) {
                    // 按钮上写着「先装引擎」，点了就真的去装引擎。
                    // 原来这里只弹一句"先下载语音引擎"，等于把用户指回上面那一行。
                    if (viewModel.voiceEngineReady) viewModel.downloadVoice(voice)
                    else viewModel.downloadVoiceEngine()
                }
            }
        }
    }
}

/** 多选用的圆形勾选框。自己画而不是用 Material 的 Checkbox：后者的方块圆角
 *  和这一页的 iOS 风格对不上，而且自带 48dp 触摸区会把行挤歪。 */
@Composable
private fun SelectMark(checked: Boolean) {
    val ios = IosTheme.palette
    val color = if (checked) ios.blue else ios.separator
    Box(
        Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) ios.blue else Color.Transparent)
            .border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            androidx.compose.material3.Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = ios.onAccent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun TextAction(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
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
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: androidx.compose.ui.graphics.Color,
    onChange: (Float) -> Unit,
) {
    val ios = IosTheme.palette
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = IosTheme.typography.footnote, color = ios.secondaryLabel, modifier = Modifier.width(42.dp))
            IosSlider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                modifier = Modifier.weight(1f),
                activeColor = accent,
            )
            Text(
                String.format(Locale.US, "%.2f", value),
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

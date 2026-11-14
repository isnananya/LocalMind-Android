package com.localmind.ui.screen

import android.content.Intent
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import com.localmind.ui.motion.ScrollPhysics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.ui.AppViewModel
import com.localmind.ui.design.AuroraButton
import com.localmind.ui.design.AuroraNavBar
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import com.localmind.util.VoiceRecorder
import com.localmind.data.SynthSubtitle
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * TTS 设置 —— 语音合成、音色库与声音克隆，一个页面三段。
 *
 * ## 布局来源
 *
 * 交互骨架照 VoiceStudio（github.com/debpalash/VoiceStudio）：挑音色 → 写脚本 →
 * 一个主按钮合成 → 出播放器；音色下载与克隆分工在旁边两段。
 *
 * ## 配色为什么要用 IosTheme 而不是自己那套
 *
 * 第一版这里写了一套独立的深色（照着 VoiceStudio 的深色界面），结果是从「我的」
 * 点进来像进了另一个应用：背景、卡片、圆角、分割线全都和外面的 iOS 风格不一致。
 * 现在**全部走 `IosTheme.palette`**：卡片 `secondaryGroupedBackground`、描边
 * `separator`、强调色 `blue`、次级文字 `secondaryLabel` —— 浅色/深色主题和用户
 * 换的主题色都会自动跟上，页面不再自成一派。
 */
enum class StudioTab(val label: String) { SYNTH("合成"), LIBRARY("音色库"), CLONE("克隆") }

/** 合成区的三种产出形态：单条 / 批量（分文件）/ 有声书（合并 + 字幕）。 */
enum class SynthMode(val label: String) { SINGLE("单条"), BATCH("批量"), AUDIOBOOK("有声书") }

/**
 * 音色库的筛选维度。
 *
 * 只按「下没下过」和「能不能克隆」分，不做语言/性别猜测：音色清单是云端可改的，
 * 猜出来的分类一旦不准，用户会以为筛选坏了。搜索框能覆盖"找某个名字"的需求。
 */
private enum class LibFilter(val label: String) {
    ALL("全部"),
    INSTALLED("已下载"),
    NOT_INSTALLED("未下载"),
    CLONE("可克隆"),
}

@Composable
fun VoiceStudioScreen(
    viewModel: AppViewModel,
    initialTab: StudioTab = StudioTab.SYNTH,
    onClose: () -> Unit,
) {
    val ios = IosTheme.palette
    var tab by remember { mutableStateOf(initialTab) }
    val activeNeural by viewModel.activeNeuralVoice.collectAsState()
    val activeClone by viewModel.activeClone.collectAsState()
    val engineReady = viewModel.voiceEngineReady

    val statusText = when {
        activeClone != null -> "克隆 · " + activeClone!!.name
        activeNeural.isNotBlank() -> {
            val label = viewModel.voiceCatalog.value.firstOrNull { it.id == activeNeural }?.label
                ?: activeNeural
            "音色 · " + label
        }
        else -> "未选音色"
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        AuroraNavBar(
            title = "TTS 设置",
            onBack = onClose,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(statusText, color = ios.secondaryLabel, fontSize = 11.sp)
                    if (!engineReady) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(ios.orange.copy(alpha = 0.16f))
                                .clickable { tab = StudioTab.LIBRARY }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) { Text("装引擎", color = ios.orange, fontSize = 11.sp) }
                    }
                }
            },
        )

        // 顶部分段：和列表页的分组标题同一个视觉层级。
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ios.fillSecondary)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            StudioTab.entries.forEach { t ->
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
            StudioTab.SYNTH -> SynthSection(viewModel) { tab = StudioTab.LIBRARY }
            StudioTab.LIBRARY -> LibrarySection(viewModel)
            StudioTab.CLONE -> CloneSection(viewModel)
        }
    }
}

/* ------------------------------------------------------------------ 合成 */

@Composable
private fun SynthSection(viewModel: AppViewModel, onNeedLibrary: () -> Unit) {
    val ios = IosTheme.palette
    val voice by viewModel.voice.collectAsState()
    val catalog by viewModel.voiceCatalog.collectAsState()
    val dl by viewModel.voiceDownloadStates.collectAsState()
    val activeNeural by viewModel.activeNeuralVoice.collectAsState()
    val activeClone by viewModel.activeClone.collectAsState()
    val clones by viewModel.clonedVoices.collectAsState()
    val draft by viewModel.studioDraft.collectAsState()
    val busy by viewModel.synthBusy.collectAsState()
    val file by viewModel.synthFile.collectAsState()
    val meta by viewModel.synthMeta.collectAsState()
    val error by viewModel.synthError.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val takes by viewModel.takes.collectAsState()
    val batchBusy by viewModel.batchBusy.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    val audiobookBusy by viewModel.audiobookBusy.collectAsState()
    val audiobookProgress by viewModel.audiobookProgress.collectAsState()
    val subs by viewModel.synthSubs.collectAsState()

    // 分享要 startActivity，Compose 里只能从 Context 拿。
    val ctx = LocalContext.current

    // 记录和预设不在 init 里读（磁盘 IO），进页面读一次就够。
    LaunchedEffect(Unit) { viewModel.refreshStudioLists() }

    // 已安装的音色 + 克隆音色，拼成横滑列表。
    // remember 的 key 带上下载状态：刚下完的音色要立刻出现在列表里。
    val installed = remember(catalog, dl) { catalog.filter { viewModel.voiceInstalled(it) } }
    var showParams by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SynthMode.SINGLE) }

    LazyColumn(
        flingBehavior = ScrollPhysics.fling(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 4.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 音色横滑 ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音色", color = ios.secondaryLabel, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "管理",
                    color = ios.blue, fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onNeedLibrary() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(clones, key = { "c-" + it.id }) { c ->
                    VoiceChip(
                        title = c.name,
                        letter = c.name.take(1),
                        badge = "克隆",
                        selected = activeClone?.id == c.id,
                        onClick = { viewModel.selectClonedVoice(c) },
                    )
                }
                items(installed, key = { "v-" + it.id }) { v ->
                    VoiceChip(
                        title = v.label,
                        letter = v.label.take(1),
                        badge = v.badge.ifBlank { "内置" },
                        selected = activeClone == null && activeNeural == v.id,
                        onClick = { viewModel.selectNeuralVoice(v) },
                    )
                }
                if (clones.isEmpty() && installed.isEmpty()) {
                    item {
                        VoiceChip(
                            title = "还没有音色",
                            letter = "+",
                            badge = "去音色库",
                            selected = false,
                            onClick = { onNeedLibrary() },
                        )
                    }
                }
            }
        }

        // ---- 预设：一套参数起个名字，下次一键回到这个感觉 ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.AutoAwesome, null, Modifier.size(13.dp), tint = ios.secondaryLabel)
                Spacer(Modifier.width(6.dp))
                Text("参数预设", color = ios.secondaryLabel, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (naming) "取消" else "存为预设",
                    color = ios.blue, fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            naming = !naming
                            if (naming) presetName = ""
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (naming) {
            item {
                StudioCard {
                    StudioInput(presetName, { presetName = it }, "预设名称，例如「我的旁白」")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallBtn("保存", primary = true) {
                            viewModel.saveCurrentAsPreset(presetName)
                            naming = false
                            presetName = ""
                        }
                        SmallBtn("取消") { naming = false }
                    }
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presets, key = { it.id }) { p ->
                    PresetChip(
                        name = p.name,
                        hint = p.hint,
                        builtin = p.builtin,
                        onClick = { viewModel.applyPreset(p) },
                        onDelete = if (p.builtin) null else ({ viewModel.deletePreset(p.id) }),
                    )
                }
            }
        }

        // ---- 脚本 ----
        item {
            StudioCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.GraphicEq, null, Modifier.size(15.dp), tint = ios.secondaryLabel)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (mode == SynthMode.SINGLE) "脚本" else "脚本（空行分隔每段）",
                        color = ios.label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SynthMode.entries.forEach { m ->
                            val sel = m == mode
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sel) ios.blue else ios.fillSecondary)
                                    .clickable { mode = m }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    m.label,
                                    color = if (sel) ios.onAccent else ios.secondaryLabel,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                    if (draft.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "清空",
                            color = ios.secondaryLabel, fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { viewModel.setStudioDraft("") }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(168.dp)) {
                    if (draft.isEmpty()) {
                        Text(
                            when (mode) {
                                SynthMode.SINGLE -> "在这里写下要念的内容。中文、英文、中英混读都行。"
                                SynthMode.BATCH -> "每一条之间空一行。合成完每条单独导出一个文件。"
                                SynthMode.AUDIOBOOK -> "每一段之间空一行。所有段落会连成一条长音频，并附字幕。"
                            },
                            color = ios.tertiaryLabel, fontSize = 13.sp,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { viewModel.setStudioDraft(it) },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = TextStyle(color = ios.label, fontSize = 14.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(ios.blue),
                    )
                }
                Text(
                    if (mode == SynthMode.SINGLE) "${draft.length} 个字符"
                    else "${batchCount(draft)} 段 · ${draft.length} 个字符",
                    color = ios.tertiaryLabel, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }

        // ---- 参数概览（点开微调） ----
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 试听放在第一个：它是改参数之后最常做的动作，不该藏在面板里。
                item {
                    ParamChip(if (busy) "试听中…" else "▶ 试听这句") { viewModel.previewVoiceLine() }
                }
                item { ParamChip("语速 %.2fx".format(Locale.US, voice.speed)) { showParams = !showParams } }
                item { ParamChip("音高 %.2fx".format(Locale.US, voice.pitch)) { showParams = !showParams } }
                item { ParamChip("停顿 %.2f".format(Locale.US, voice.silenceScale)) { showParams = !showParams } }
                item { ParamChip(voice.emotion.emoji + " " + voice.emotion.label) { showParams = !showParams } }
                item {
                    ParamChip(if (showParams) "收起" else "全部参数") { showParams = !showParams }
                }
            }
        }
        if (showParams) {
            item {
                StudioCard(padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                    StudioSlider("语速", voice.speed, 0.4f..2.0f) {
                        viewModel.updateVoice { v -> v.copy(speed = it) }
                    }
                    StudioSlider("音高", voice.pitch, 0.5f..1.8f) {
                        viewModel.updateVoice { v -> v.copy(pitch = it) }
                    }
                    StudioSlider("音量", voice.volume, 0f..1f) {
                        viewModel.updateVoice { v -> v.copy(volume = it) }
                    }
                    StudioSlider("停顿", voice.silenceScale, 0f..1f) {
                        viewModel.updateVoice { v -> v.copy(silenceScale = it) }
                    }
                    if (activeClone != null) {
                        // 只有克隆链路认这两个参数；普通音色改它没有效果，
                        // 所以灰色提示而不是干脆藏起来（藏了用户会以为是 bug）。
                        StudioStepper("步数", voice.numSteps, 1..24) {
                            viewModel.updateVoice { v -> v.copy(numSteps = it) }
                        }
                    }
                    StudioToggle(
                        "按句切分",
                        "长文本逐句合成再拼接，越读越糊的问题会好很多；关掉则整段喂给模型，句内韵律更连贯。",
                        voice.splitSentences,
                    ) { on -> viewModel.updateVoice { v -> v.copy(splitSentences = on) } }

                    Spacer(Modifier.height(6.dp))
                    Text("情绪", color = ios.secondaryLabel, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    EmotionGrid(voice.emotion) { e -> viewModel.updateVoice { v -> v.copy(emotion = e) } }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // ---- 合成 ----
        item {
            when (mode) {
                SynthMode.SINGLE -> AuroraButton(
                    text = if (busy) "合成中…" else "合成语音",
                    onClick = { viewModel.synthesizeStudio(draft) },
                    enabled = !busy && !batchBusy && !audiobookBusy,
                    loading = busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                SynthMode.BATCH -> AuroraButton(
                    text = if (batchBusy) "批量合成中…" else "批量合成并导出",
                    onClick = { viewModel.synthesizeBatch(draft) },
                    enabled = !batchBusy && !busy && !audiobookBusy,
                    loading = batchBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
                SynthMode.AUDIOBOOK -> AuroraButton(
                    text = if (audiobookBusy) "生成有声书中…" else "生成有声书（合并 + 字幕）",
                    onClick = { viewModel.synthesizeAudiobook(draft) },
                    enabled = !audiobookBusy && !busy && !batchBusy,
                    loading = audiobookBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val prog = when (mode) {
                SynthMode.BATCH -> batchProgress
                SynthMode.AUDIOBOOK -> audiobookProgress
                else -> ""
            }
            if (prog.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(prog, color = ios.secondaryLabel, fontSize = 11.sp)
            }
        }

        error?.let { msg ->
            item {
                StudioCard {
                    Text(msg, color = ios.red, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "常见原因：音色模型没下完、缺少 espeak-ng-data，或参考音频不是 16-bit PCM WAV。",
                        color = ios.secondaryLabel, fontSize = 11.sp,
                    )
                }
            }
        }
        file?.let { f ->
            item {
                StudioPlayer(
                    file = f,
                    meta = meta,
                    subs = subs,
                    onExport = {
                        val where = viewModel.exportSynthAudio()
                        viewModel.toast(if (where != null) "已导出到 $where" else "导出失败")
                    },
                    onExportSrt = {
                        val where = viewModel.exportSrt()
                        viewModel.toast(if (where != null) "字幕已导出到 $where" else "没有可导出的字幕")
                    },
                    onShare = { shareAudioFile(viewModel, ctx, f) },
                    onDelete = { viewModel.clearStudioAudio() },
                )
            }
        }

        // ---- 最近合成 ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最近合成", color = ios.secondaryLabel, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.weight(1f))
                if (takes.isNotEmpty()) {
                    Text(
                        "清空",
                        color = ios.secondaryLabel, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { viewModel.clearTakes() }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }
        if (takes.isEmpty()) {
            item {
                Text("合成一次就会留一条在这里，最多 30 条。", color = ios.tertiaryLabel, fontSize = 11.sp)
            }
        }
        items(takes, key = { it.id }) { t ->
            TakeRow(
                take = t,
                onPlay = { viewModel.playTake(t) },
                onReuse = { viewModel.reuseTake(t) },
                onExport = {
                    val where = viewModel.exportTake(t)
                    viewModel.toast(if (where != null) "已导出到 $where" else "导出失败")
                },
                onShare = { shareAudioFile(viewModel, ctx, File(t.path)) },
                onDelete = { viewModel.deleteTake(t.id) },
            )
        }

        if (!viewModel.voiceEngineReady) {
            item {
                HintCard(
                    "神经语音引擎还没装（约 36 MB）。装完才能用音色和克隆。",
                    "去安装",
                ) { onNeedLibrary() }
            }
        } else if (activeClone != null && !viewModel.cloneBaseInstalled()) {
            item {
                HintCard("克隆音色需要「声音克隆底模」，还没下载。", "去下载") { onNeedLibrary() }
            }
        }
    }
}

/* ------------------------------------------------------------------ 音色库 */

@Composable
private fun LibrarySection(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val catalog by viewModel.voiceCatalog.collectAsState()
    val states by viewModel.voiceDownloadStates.collectAsState()
    val activeNeural by viewModel.activeNeuralVoice.collectAsState()
    val engine = states[com.localmind.service.VOICE_LIBRARY_KEY]
        ?: viewModel.voiceEngineState.value
    val engineReady = viewModel.voiceEngineReady

    // ---- 搜索 / 筛选 ----
    //
    // 音色清单是「内置 + 云端 + 插件」三层合并出来的（见 VoiceCatalog），
    // 装了几个插件之后动辄十几二十条，纯列表找不到东西。搜索匹配 label / id /
    // description —— id 是官方模型名，用户从别处看到的就是它。
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(LibFilter.ALL) }

    // 安装状态和占用体积都要读磁盘，以 states 为 key 一起重算：
    // 下载完成会改 states，装完这一格才会从「未下载」跳到「已安装」。
    val installedCount = remember(catalog, states) { catalog.count { viewModel.voiceInstalled(it) } }
    val usedBytes = remember(catalog, states) { catalog.sumOf { viewModel.voiceInstalledBytes(it) } }
    val shown = remember(catalog, query, filter, states) {
        val q = query.trim()
        catalog.filter { v ->
            val hit = q.isEmpty() ||
                v.label.contains(q, ignoreCase = true) ||
                v.id.contains(q, ignoreCase = true) ||
                v.description.contains(q, ignoreCase = true)
            val keep = when (filter) {
                LibFilter.ALL -> true
                LibFilter.INSTALLED -> viewModel.voiceInstalled(v)
                LibFilter.NOT_INSTALLED -> !viewModel.voiceInstalled(v)
                LibFilter.CLONE -> v.clonable
            }
            hit && keep
        }
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
            Text(
                "下载走「设置 → 下载代理」里的代理；服务器支持分段时自动多线程拉。",
                color = ios.secondaryLabel, fontSize = 11.sp,
            )
        }

        // ---- 搜索 + 筛选 + 占用统计 ----
        item {
            StudioInput(query, { query = it }, "搜音色：名字、模型 id、说明里的字")
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibFilter.entries) { f ->
                    val sel = f == filter
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) ios.blue else ios.fillSecondary)
                            .clickable { filter = f }
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                    ) {
                        Text(
                            f.label,
                            color = if (sel) ios.onAccent else ios.secondaryLabel,
                            fontSize = 11.sp,
                            fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        item {
            Text(
                "已下载 $installedCount / ${catalog.size} 个 · 占用 ${mb(usedBytes)}",
                color = ios.tertiaryLabel, fontSize = 11.sp,
            )
        }

        // ---- 引擎 ----
        item {
            StudioCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "语音引擎（sherpa-onnx）",
                            color = ios.label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(3.dp))
                        val line = when {
                            engineReady -> "已就绪 · ${viewModel.voiceEngineAbi} · ${mb(viewModel.voiceEngineBytes())}"
                            engine.busy -> progressLine(engine.downloadedBytes, engine.totalBytes, engine.bytesPerSecond)
                            engine.status == com.localmind.service.VoiceModelDownloader.Status.FAILED ->
                                "安装失败：" + (engine.error ?: "网络问题")
                            else -> "未安装 · 约 36 MB"
                        }
                        Text(
                            line,
                            color = if (engineReady) ios.green else ios.secondaryLabel,
                            fontSize = 11.sp,
                        )
                        if (engine.busy && engine.totalBytes > 0) {
                            Spacer(Modifier.height(6.dp))
                            StudioProgress(engine.progress)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    when {
                        engineReady -> SmallBtn("删除", danger = true) { viewModel.deleteVoiceEngine() }
                        engine.busy -> SmallBtn("下载中") { }
                        else -> SmallBtn("安装", primary = true) { viewModel.downloadVoiceEngine() }
                    }
                }
            }
        }

        item {
            Text(
                "音色" + if (shown.size != catalog.size) "（${shown.size} 个结果）" else "",
                color = ios.secondaryLabel, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (shown.isEmpty()) {
            item {
                Text(
                    when (filter) {
                        LibFilter.NOT_INSTALLED -> "全部音色都下好了，可以直接用。"
                        LibFilter.INSTALLED -> "还没下载任何音色。切到「全部」挑一个下载。"
                        LibFilter.CLONE -> "没有可克隆的音色，看看「全部」里有没有「克隆」标签的。"
                        LibFilter.ALL -> "没有匹配「$query」的音色，换个词试试。"
                    },
                    color = ios.tertiaryLabel, fontSize = 11.sp,
                )
            }
        }

        items(shown, key = { it.id }) { v ->
            val st = states[v.id]
            val installed = viewModel.voiceInstalled(v)
            val reason = viewModel.voiceUnusableReason(v)
            val active = activeNeural == v.id
            StudioCard(
                stroke = if (active) ios.blue else ios.separator,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                v.label, color = ios.label, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            if (v.badge.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Tag(v.badge)
                            }
                            if (active) {
                                Spacer(Modifier.width(6.dp))
                                Tag("使用中", ios.blue)
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            when {
                                st?.busy == true ->
                                    progressLine(st.downloadedBytes, st.totalBytes, st.bytesPerSecond) +
                                        if (st.status == com.localmind.service.VoiceModelDownloader.Status.EXTRACTING) " · 解压中" else ""
                                installed -> "${mb(viewModel.voiceInstalledBytes(v))} · ${viewModel.voiceArch(v) ?: "已安装"}"
                                else -> "${mb(v.sizeBytes)} · 未下载"
                            },
                            color = when {
                                st?.busy == true -> ios.blue
                                installed -> ios.green
                                else -> ios.secondaryLabel
                            },
                            fontSize = 11.sp,
                            maxLines = 2,
                        )
                        // 说明里有授权协议、适用场景这些真正影响选择的信息
                        // （"个人/非商用"「带 lexicon，数字多音字更准」），
                        // 之前只存在数据里没展示，等于用户只能凭名字瞎选。
                        if (v.description.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                v.description,
                                color = ios.tertiaryLabel, fontSize = 10.sp, lineHeight = 14.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (reason != null && installed) {
                            Spacer(Modifier.height(3.dp))
                            Text("⚠ " + reason, color = ios.orange, fontSize = 10.sp)
                        }
                        if (st?.busy == true && st.totalBytes > 0) {
                            Spacer(Modifier.height(6.dp))
                            StudioProgress(st.progress)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    when {
                        st?.busy == true -> SmallBtn("取消") { viewModel.cancelVoiceDownload(v) }
                        installed -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!active) SmallBtn("使用") { viewModel.selectNeuralVoice(v) }
                            SmallBtn("删除", danger = true) { viewModel.deleteVoice(v) }
                        }
                        else -> SmallBtn("下载", primary = true) { viewModel.downloadVoice(v) }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 克隆 */

@Composable
private fun CloneSection(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val ctx = LocalContext.current
    val clones = viewModel.clonedVoices.collectAsState().value
    val activeClone by viewModel.activeClone.collectAsState()

    var name by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }
    var audio by remember { mutableStateOf<File?>(null) }
    var recording by remember { mutableStateOf(false) }
    var amplitude by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableIntStateOf(0) }
    val recorder = remember { VoiceRecorder() }

    fun startRecording() {
        val dst = File(ctx.cacheDir, "tts-clone-" + System.currentTimeMillis() + ".wav")
        amplitude = 0f
        durationMs = 0
        recording = true
        recorder.start(
            target = dst,
            maxSeconds = 30,
            onAmplitude = { amplitude = it },
            onFinished = { ok, ms ->
                recording = false
                amplitude = 0f
                if (ok) {
                    audio = dst
                    durationMs = ms
                }
            },
        )
    }

    // 授权后立刻开录：让用户点两次「录音」是最容易被骂的交互。
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) startRecording() else viewModel.toast("需要麦克风权限才能录音") }

    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val dst = File(ctx.cacheDir, "tts-pick-" + System.currentTimeMillis() + ".wav")
            val copied = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                }
                dst.length() > 44L
            }.getOrDefault(false)
            if (!copied) {
                viewModel.toast("读不出这个文件，换一段 WAV")
                return@rememberLauncherForActivityResult
            }
            val parsed = com.localmind.util.AudioUtil.readWavToMonoFloat(dst.absolutePath)
            if (parsed == null) {
                dst.delete()
                viewModel.toast("需要 16-bit PCM 的 WAV")
                return@rememberLauncherForActivityResult
            }
            durationMs = (parsed.first.size * 1000L / parsed.second).toInt()
            audio = dst
        }
    }

    DisposableEffect(Unit) { onDispose { recorder.stop() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!viewModel.cloneBaseInstalled()) {
            item {
                HintCard(
                    "还没下载「声音克隆底模（中英 · 零样本，109 MB）」。可以先建音色，合成前要装它。",
                    "去音色库",
                ) { viewModel.toast("到「音色库」下载「声音克隆底模」") }
            }
        }

        item {
            StudioCard {
                Text("新建克隆音色", color = ios.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(3.dp))
                Text(
                    "录一段 5–30 秒的干净人声（一个人、没有背景音乐），再把它念的内容原样写下来。",
                    color = ios.secondaryLabel, fontSize = 11.sp,
                )
                Spacer(Modifier.height(12.dp))

                StudioInput(name, { name = it }, "音色名称，例如「我自己」")
                Spacer(Modifier.height(8.dp))
                StudioInput(transcript, { transcript = it }, "参考音频里念的文字（越准越像）", lines = 2)
                Spacer(Modifier.height(12.dp))

                if (recording) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(ios.red),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("录音中… 再点一下停止", color = ios.red, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    StudioProgress(amplitude)
                    Spacer(Modifier.height(10.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (recording) {
                        SmallBtn("停止录音", primary = true) { recorder.stop() }
                    } else {
                        SmallBtn("录音", primary = true) {
                            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                startRecording()
                            } else {
                                permLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                        }
                        SmallBtn("上传 WAV") { pickLauncher.launch("audio/*") }
                    }
                    if (audio != null && !recording) {
                        SmallBtn("试听") { viewModel.previewWavFile(audio!!) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (audio != null) {
                        Text(
                            "%.1fs".format(Locale.US, durationMs / 1000f),
                            color = if (durationMs in 2000..30_000) ios.green else ios.orange,
                            fontSize = 11.sp,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                AuroraButton(
                    text = "保存为我的音色",
                    enabled = audio != null && !recording,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val a = audio ?: return@AuroraButton
                        if (durationMs < 2000) {
                            viewModel.toast("太短了，至少 2 秒")
                            return@AuroraButton
                        }
                        if (durationMs > 30_000) {
                            viewModel.toast("太长了，30 秒以内")
                            return@AuroraButton
                        }
                        val created = viewModel.createClonedVoice(name, transcript, a)
                        if (created != null) {
                            name = ""
                            transcript = ""
                            audio = null
                            durationMs = 0
                            viewModel.toast("已保存「${created.name}」")
                        }
                    },
                )
            }
        }

        item {
            Text("我的克隆音色", color = ios.secondaryLabel, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (clones.isEmpty()) {
            item { Text("还没有。上面录一段就有了。", color = ios.tertiaryLabel, fontSize = 12.sp) }
        }
        items(clones) { c ->
            StudioCard(stroke = if (activeClone?.id == c.id) ios.blue else ios.separator) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, color = ios.label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            if (activeClone?.id == c.id) {
                                Spacer(Modifier.width(6.dp))
                                Tag("使用中", ios.blue)
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "参考文字 " + (c.transcript.ifBlank { "（未填）" }.take(28)),
                            color = ios.secondaryLabel, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (activeClone?.id != c.id) {
                            SmallBtn("使用") { viewModel.selectClonedVoice(c) }
                        }
                        SmallBtn("试听") { viewModel.previewClonedVoice(c) }
                        SmallBtn("删除", danger = true) { viewModel.deleteClonedVoice(c.id) }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ 组件 */

@Composable
private fun StudioCard(
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
private fun VoiceChip(
    title: String,
    letter: String,
    badge: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ios = IosTheme.palette
    Column(
        Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) ios.blue.copy(alpha = 0.12f) else ios.fillSecondary)
            .border(1.dp, if (selected) ios.blue else ios.separator, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (selected) ios.blue else ios.secondaryGroupedBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                letter,
                color = if (selected) ios.onAccent else ios.label,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            color = ios.label, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Text(badge, color = ios.secondaryLabel, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun ParamChip(text: String, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(ios.fillSecondary)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(text, color = ios.label, fontSize = 11.sp) }
}

@Composable
private fun StudioSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val ios = IosTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ios.secondaryLabel, fontSize = 11.sp, modifier = Modifier.width(34.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f).height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = ios.blue,
                activeTrackColor = ios.blue,
                inactiveTrackColor = ios.fill,
            ),
        )
        Text(
            "%.2f".format(Locale.US, value),
            color = ios.secondaryLabel, fontSize = 11.sp, modifier = Modifier.width(38.dp),
        )
    }
}

@Composable
private fun StudioInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    lines: Int = 1,
) {
    val ios = IosTheme.palette
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(ios.fill)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = ios.tertiaryLabel, fontSize = 12.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().height((16 + 18 * lines).dp),
            textStyle = TextStyle(color = ios.label, fontSize = 13.sp, lineHeight = 18.sp),
            cursorBrush = SolidColor(ios.blue),
        )
    }
}

@Composable
private fun SmallBtn(
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
private fun Tag(text: String, color: Color? = null) {
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
private fun StudioProgress(fraction: Float) {
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
private fun HintCard(text: String, action: String, onAction: () -> Unit) {
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
        SmallBtn(action) { onAction() }
    }
}

@Composable
private fun StudioPlayer(
    file: File,
    meta: String,
    subs: List<SynthSubtitle> = emptyList(),
    onExport: () -> Unit,
    onExportSrt: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    val ios = IosTheme.palette
    var playing by remember(file) { mutableStateOf(false) }
    var position by remember(file) { mutableLongStateOf(0L) }
    var duration by remember(file) { mutableLongStateOf(0L) }
    val player = remember(file) { MediaPlayer() }

    DisposableEffect(file) {
        runCatching {
            player.setDataSource(file.absolutePath)
            player.prepare()
            duration = player.duration.toLong().coerceAtLeast(0L)
        }
        player.setOnCompletionListener {
            playing = false
            position = 0L
        }
        onDispose { runCatching { player.release() } }
    }

    LaunchedEffect(playing) {
        while (playing) {
            position = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
            delay(120)
        }
    }

    // 当前念到哪一句：用来在字幕列表里高亮，播放进度条动它就跟着动。
    val current = subs.indexOfFirst { position in it.startMs..it.endMs }

    StudioCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ios.blue)
                    .clickable {
                        runCatching {
                            if (playing) {
                                player.pause()
                                playing = false
                            } else {
                                if (position >= duration - 200) {
                                    player.seekTo(0)
                                    position = 0
                                }
                                player.start()
                                playing = true
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (playing) AppIcons.Pause else AppIcons.PlayArrow,
                    null, Modifier.size(20.dp), tint = ios.onAccent,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Slider(
                    value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { f ->
                        runCatching {
                            val ms = (f * duration).toLong()
                            player.seekTo(ms.toInt())
                            position = ms
                        }
                    },
                    modifier = Modifier.height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = ios.blue,
                        activeTrackColor = ios.blue,
                        inactiveTrackColor = ios.fill,
                    ),
                )
                Row {
                    Text(clock(position), color = ios.tertiaryLabel, fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    Text(clock(duration), color = ios.tertiaryLabel, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                meta.ifBlank { "已生成" },
                color = ios.secondaryLabel, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (subs.isNotEmpty() && onExportSrt != null) {
                SmallBtn("字幕") { onExportSrt() }
                Spacer(Modifier.width(6.dp))
            }
            SmallBtn("导出") { onExport() }
            if (onShare != null) {
                Spacer(Modifier.width(6.dp))
                SmallBtn("分享") { onShare() }
            }
            Spacer(Modifier.width(6.dp))
            SmallBtn("清除", danger = true) { onDelete() }
        }
        if (subs.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("字幕（${subs.size} 句）", color = ios.secondaryLabel, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ios.fill)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    subs.forEachIndexed { i, s ->
                        val active = i == current
                        Text(
                            s.text,
                            color = if (active) ios.blue else ios.label,
                            fontSize = if (active) 13.sp else 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetChip(
    name: String,
    hint: String,
    builtin: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val ios = IosTheme.palette
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ios.fillSecondary)
            .border(1.dp, ios.separator, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(start = 10.dp, end = if (onDelete == null) 10.dp else 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.widthIn(max = 132.dp)) {
            Text(name, color = ios.label, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (hint.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    hint, color = ios.tertiaryLabel, fontSize = 9.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 11.sp,
                )
            }
        }
        if (onDelete != null) {
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center,
            ) { Text("×", color = ios.secondaryLabel, fontSize = 13.sp) }
        }
    }
}

/** 整数步进器（采样步数这种「不是连续量」的参数，滑块反而难对准）。 */
@Composable
private fun StudioStepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val ios = IosTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(38.dp)) {
        Text(label, color = ios.secondaryLabel, fontSize = 11.sp, modifier = Modifier.width(34.dp))
        Spacer(Modifier.weight(1f))
        StepBtn("−", enabled = value > range.first) { onChange((value - 1).coerceIn(range)) }
        Text(
            value.toString(),
            color = ios.label, fontSize = 13.sp,
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepBtn("+", enabled = value < range.last) { onChange((value + 1).coerceIn(range)) }
    }
}

@Composable
private fun StepBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (enabled) ios.fill else ios.fill.copy(alpha = 0.4f))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) ios.label else ios.tertiaryLabel,
            fontSize = 15.sp, fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StudioToggle(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val ios = IosTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ios.label, fontSize = 12.sp)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = ios.tertiaryLabel, fontSize = 9.sp, lineHeight = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        // 自己画开关而不是用 Material Switch：Material 的 track 尺寸跟着
        // M3 版本变，混在这个 iOS 风格里会突然变胖。
        Box(
            Modifier
                .width(42.dp)
                .height(24.dp)
                .clip(CircleShape)
                .background(if (checked) ios.green else ios.fill),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

/** 情绪九宫格（8 个，两行四列）。 */
@Composable
private fun EmotionGrid(selected: com.localmind.data.Emotion, onPick: (com.localmind.data.Emotion) -> Unit) {
    val ios = IosTheme.palette
    val all = com.localmind.data.Emotion.entries
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        all.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { e ->
                    val on = e == selected
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) ios.blue.copy(alpha = 0.14f) else ios.fill)
                            .border(1.dp, if (on) ios.blue else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { onPick(e) }
                            .padding(vertical = 7.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(e.emoji, fontSize = 11.sp)
                        Spacer(Modifier.width(3.dp))
                        Text(
                            e.label,
                            color = if (on) ios.blue else ios.label,
                            fontSize = 10.sp,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
                // 最后一行不满 4 个时补空，保持等宽
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 一条合成记录。 */
@Composable
private fun TakeRow(
    take: com.localmind.data.SynthTake,
    onPlay: () -> Unit,
    onReuse: () -> Unit,
    onExport: () -> Unit,
    onShare: (() -> Unit)? = null,
    onDelete: () -> Unit,
) {
    val ios = IosTheme.palette
    StudioCard(padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(ios.blue.copy(alpha = 0.14f))
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.PlayArrow, null, Modifier.size(16.dp), tint = ios.blue)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    take.title.ifBlank { "（无文字）" },
                    color = ios.label, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    take.voice + " · " + clock(take.durationMs.toLong()) + " · " + ago(take.createdAt),
                    color = ios.tertiaryLabel, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallBtn("复用") { onReuse() }
            SmallBtn("导出") { onExport() }
            if (onShare != null) SmallBtn("分享") { onShare() }
            Spacer(Modifier.weight(1f))
            SmallBtn("删除", danger = true) { onDelete() }
        }
    }
}

/* ------------------------------------------------------------------ 工具 */

/**
 * 分享一条 WAV。
 *
 * ViewModel 负责把文件搬到 FileProvider 授权目录并拼好 Intent；
 * 这里只做"发出去 + 失败要说人话"——`startActivity` 在没装任何能收音频的
 * 设备上会抛 `ActivityNotFoundException`，静默失败会让用户以为按钮坏了。
 */
private fun shareAudioFile(
    viewModel: AppViewModel,
    ctx: android.content.Context,
    file: File,
) {
    val send = viewModel.shareAudio(file)
    if (send == null) {
        viewModel.toast("分享失败：音频文件读不出来")
        return
    }
    runCatching { ctx.startActivity(Intent.createChooser(send, "分享语音")) }
        .onFailure { viewModel.toast("没有能接收音频的应用") }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, total / 60, total % 60)
}

/** 批量条数 = 非空段落数，和 AppViewModel.synthesizeBatch 的切法一致。 */
private fun batchCount(text: String): Int =
    text.split("\n\n").count { it.trim().isNotEmpty() }

/** 「刚刚 / 12 分钟前 / 3 小时前 / 09-21」。列表里显示绝对时间没人愿意换算。 */
private fun ago(at: Long): String {
    if (at <= 0L) return ""
    val diff = System.currentTimeMillis() - at
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> (diff / 60_000).toString() + " 分钟前"
        diff < 86_400_000L -> (diff / 3_600_000).toString() + " 小时前"
        else -> java.text.SimpleDateFormat("MM-dd", Locale.US).format(java.util.Date(at))
    }
}

private fun mb(bytes: Long): String =
    if (bytes >= 1024L * 1024 * 1024) "%.1f GB".format(Locale.US, bytes / 1024.0 / 1024 / 1024)
    else "%d MB".format(bytes / 1024 / 1024)

private fun progressLine(done: Long, total: Long, speed: Double): String {
    val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
    val sp = com.localmind.service.DownloadSources.speedText(speed)
    return buildString {
        append(mb(done))
        if (total > 0) append(" / ").append(mb(total)).append(" · ").append(pct).append("%")
        if (sp.isNotBlank()) append(" · ").append(sp)
    }
}

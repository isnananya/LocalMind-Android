package com.localmind.ui.screen

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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.ui.AppViewModel
import com.localmind.ui.icons.AppIcons
import com.localmind.util.VoiceRecorder
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * 语音工作室 —— 参照 VoiceStudio（github.com/debpalash/VoiceStudio）的布局重做。
 *
 * ## 为什么整页重写而不是改旧页
 *
 * 旧页（VoiceScreen）是"设置项清单"的形状：一排音色、一排滑块、一排开关。
 * 而 VoiceStudio 的心智是**工作台**：左边挑音色、中间写脚本、下面一个大按钮合成、
 * 生成后出现播放器。两者不是同一个东西，在旧页上叠功能只会越来越像设置页。
 *
 * ## 结构
 *
 * ```
 * ┌ 顶部栏：返回 · 语音工作室 · 引擎/音色状态
 * ├ 分段：合成 | 音色库 | 克隆
 * ├ 合成 ─ 音色横滑 · 脚本卡片 · 参数 · [合成语音] · 播放器
 * ├ 音色库 ─ 引擎行 · 音色下载列表（进度/速度/来源/删除）
 * └ 克隆 ─ 新建（录音/上传 + 参考文字）· 我的克隆音色
 * ```
 *
 * 配色刻意独立于应用的 iOS 主题：VoiceStudio 是深色的，工作台页用浅色反而陌生。
 */
private object St {
    val bg = Color(0xFF0A0A0F)
    val surface = Color(0xFF14141B)
    val surfaceHi = Color(0xFF1B1B25)
    val stroke = Color(0xFF272733)
    val accent = Color(0xFF3B82F6)
    val accentDim = Color(0xFF1D3A6B)
    val text = Color(0xFFF3F4F8)
    val dim = Color(0xFF8C8F9E)
    val ok = Color(0xFF34D399)
    val warn = Color(0xFFF59E0B)
    val err = Color(0xFFF87171)
}

enum class StudioTab(val label: String) { SYNTH("合成"), LIBRARY("音色库"), CLONE("克隆") }

@Composable
fun VoiceStudioScreen(
    viewModel: AppViewModel,
    initialTab: StudioTab = StudioTab.SYNTH,
    onClose: () -> Unit,
) {
    var tab by remember { mutableStateOf(initialTab) }
    val engine by viewModel.voiceEngineState.collectAsState()
    val activeNeural by viewModel.activeNeuralVoice.collectAsState()
    val activeClone by viewModel.activeClone.collectAsState()
    val copies = viewModel.clonedVoices.collectAsState()

    val statusText = when {
        activeClone != null -> "克隆音色 · " + activeClone!!.name
        activeNeural.isNotBlank() ->
            "神经音色 · " + (viewModel.voiceCatalog.value.firstOrNull { it.id == activeNeural }?.label ?: activeNeural)
        else -> "未选音色"
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(St.bg)
            .imePadding(),
    ) {
        // ---------------- 顶部栏 ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                    start = 8.dp, end = 12.dp, bottom = 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center,
            ) { Icon(AppIcons.ArrowBack, null, Modifier.size(22.dp), tint = St.text) }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("语音工作室", color = St.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    statusText + if (engine.status == com.localmind.service.VoiceModelDownloader.Status.DONE ||
                        viewModel.voiceEngineReady
                    ) "" else " · 引擎未安装",
                    color = St.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // 引擎未就绪时顶部给一个显眼的状态点，用户不必进音色库才知道为什么没声音。
            if (!viewModel.voiceEngineReady) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(St.accentDim)
                        .clickable { tab = StudioTab.LIBRARY }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) { Text("装引擎", color = Color(0xFFBFD4FF), fontSize = 11.sp) }
            }
        }

        // ---------------- 分段 ----------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(St.surface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StudioTab.entries.forEach { t ->
                val sel = t == tab
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (sel) St.accent else Color.Transparent)
                        .clickable { tab = t }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t.label,
                        color = if (sel) Color.White else St.dim,
                        fontSize = 13.sp,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        when (tab) {
            StudioTab.SYNTH -> SynthSection(viewModel, onNeedLibrary = { tab = StudioTab.LIBRARY })
            StudioTab.LIBRARY -> LibrarySection(viewModel)
            StudioTab.CLONE -> CloneSection(viewModel)
        }
    }
}

/* ------------------------------------------------------------------ 合成 */

@Composable
private fun SynthSection(viewModel: AppViewModel, onNeedLibrary: () -> Unit) {
    val voice by viewModel.voice.collectAsState()
    val catalog by viewModel.voiceCatalog.collectAsState()
    val dl by viewModel.voiceDownloadStates.collectAsState()
    val activeNeural by viewModel.activeNeuralVoice.collectAsState()
    val activeClone by viewModel.activeClone.collectAsState()
    val clones = viewModel.clonedVoices.collectAsState().value
    val draft by viewModel.studioDraft.collectAsState()
    val busy by viewModel.synthBusy.collectAsState()
    val file by viewModel.synthFile.collectAsState()
    val meta by viewModel.synthMeta.collectAsState()
    val error by viewModel.synthError.collectAsState()

    // 已安装的音色 + 克隆音色，拼成横滑列表。
    // remember 的 key 带上下载状态：刚下完的音色要立刻出现在列表里。
    val installed = remember(catalog, dl) {
        catalog.filter { viewModel.voiceInstalled(it) }
    }
    var showParams by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 12.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 音色横滑 ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音色", color = St.dim, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "管理",
                    color = St.accent, fontSize = 11.sp,
                    modifier = Modifier.clickable { onNeedLibrary() },
                )
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(clones) { c ->
                    VoiceChip(
                        title = c.name,
                        letter = c.name.take(1),
                        badge = "克隆",
                        selected = activeClone?.id == c.id,
                        onClick = { viewModel.selectClonedVoice(c) },
                    )
                }
                items(installed) { v ->
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

        // ---- 脚本 ----
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(St.surface)
                    .border(1.dp, St.stroke, RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.GraphicEq, null, Modifier.size(15.dp), tint = St.dim)
                    Spacer(Modifier.width(6.dp))
                    Text("脚本", color = St.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    if (draft.isNotEmpty()) {
                        Text(
                            "清空",
                            color = St.dim, fontSize = 11.sp,
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
                            "在这里写下要念的内容。中文、英文、中英混读都行。",
                            color = St.dim, fontSize = 13.sp,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { viewModel.setStudioDraft(it) },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = TextStyle(color = St.text, fontSize = 14.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(St.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    )
                }
                Text(
                    "${draft.length} 个字符",
                    color = St.dim, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }

        // ---- 参数（VoiceStudio 的 composer 工具行） ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ParamChip("语速 %.2fx".format(Locale.US, voice.speed)) { showParams = !showParams }
                ParamChip("音高 %.2fx".format(Locale.US, voice.pitch)) { showParams = !showParams }
                ParamChip(voice.emotion.label) { showParams = !showParams }
            }
        }
        if (showParams) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(St.surface)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    StudioSlider("语速", voice.speed, 0.4f..2.0f) {
                        viewModel.updateVoice { v -> v.copy(speed = it) }
                    }
                    StudioSlider("音高", voice.pitch, 0.5f..1.8f) {
                        viewModel.updateVoice { v -> v.copy(pitch = it) }
                    }
                    StudioSlider("音量", voice.volume, 0f..1f) {
                        viewModel.updateVoice { v -> v.copy(volume = it) }
                    }
                }
            }
        }

        // ---- 合成按钮 ----
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (busy) St.accentDim else St.accent)
                    .clickable(enabled = !busy) {
                        if (draft.isBlank()) {
                            viewModel.toast("先写点要念的内容")
                        } else {
                            viewModel.synthesizeStudio(draft)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("合成中…", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(AppIcons.PlayArrow, null, Modifier.size(18.dp), tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("合成语音", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ---- 播放器 / 错误 ----
        error?.let { msg ->
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF2A1618))
                        .padding(12.dp),
                ) {
                    Text(msg, color = St.err, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "常见原因：音色模型没下完、缺少 espeak-ng-data、或参考音频不是 16-bit PCM WAV。",
                        color = St.dim, fontSize = 11.sp,
                    )
                }
            }
        }
        file?.let { f ->
            item {
                StudioPlayer(
                    file = f,
                    meta = meta,
                    onExport = {
                        val where = viewModel.exportSynthAudio()
                        viewModel.toast(if (where != null) "已导出到 $where" else "导出失败")
                    },
                    onDelete = { viewModel.clearStudioAudio() },
                )
            }
        }

        // 提示：引擎与底模状态
        if (!viewModel.voiceEngineReady) {
            item {
                HintCard(
                    "神经语音引擎还没装（约 36 MB）。装完才能用下面的音色和克隆。",
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
    val catalog by viewModel.voiceCatalog.collectAsState()
    val states by viewModel.voiceDownloadStates.collectAsState()
    val activeNeural by viewModel.activeNeuralVoice.collectAsState()
    val engine = states[com.localmind.service.VOICE_LIBRARY_KEY]
        ?: viewModel.voiceEngineState.value

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 12.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "下载走「设置 → 网络」里的代理；支持分段时自动多线程拉，速度更快。",
                color = St.dim, fontSize = 11.sp,
            )
        }

        // ---- 引擎 ----
        item {
            val engineReady = viewModel.voiceEngineReady
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(St.surface)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("语音引擎（sherpa-onnx）", color = St.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    val ready = engineReady
                    val line = when {
                        ready -> "已就绪 · ${viewModel.voiceEngineAbi} · ${mb(viewModel.voiceEngineBytes())}"
                        engine.busy -> progressLine(engine.downloadedBytes, engine.totalBytes, engine.bytesPerSecond)
                        engine.status == com.localmind.service.VoiceModelDownloader.Status.FAILED ->
                            "安装失败：" + (engine.error ?: "网络问题")
                        else -> "未安装 · 约 36 MB"
                    }
                    Text(line, color = if (ready) St.ok else St.dim, fontSize = 11.sp)
                    if (engine.busy && engine.totalBytes > 0) {
                        Spacer(Modifier.height(6.dp))
                        StudioProgress(engine.progress)
                    }
                }
                Spacer(Modifier.width(10.dp))
                when {
                    engineReady -> SmallBtn("删除", danger = true) { viewModel.deleteVoiceEngine() }
                    engine.busy -> SmallBtn("取消") { /* 引擎下载不支持中途取消，见说明 */ }
                    else -> SmallBtn("安装", primary = true) { viewModel.downloadVoiceEngine() }
                }
            }
        }

        item { Text("音色", color = St.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)) }

        items(catalog) { v ->
            val st = states[v.id]
            val installed = viewModel.voiceInstalled(v)
            val reason = viewModel.voiceUnusableReason(v)
            val active = activeNeural == v.id
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(St.surface)
                    .border(
                        1.dp,
                        if (active) St.accent else St.stroke,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(v.label, color = St.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            if (v.badge.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                                Tag(v.badge)
                            }
                            if (active) {
                                Spacer(Modifier.width(6.dp))
                                Tag("使用中", St.accent)
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            when {
                                st?.busy == true -> progressLine(st.downloadedBytes, st.totalBytes, st.bytesPerSecond) +
                                    (if (st.status == com.localmind.service.VoiceModelDownloader.Status.EXTRACTING) " · 解压中" else "")
                                installed -> "${mb(viewModel.voiceInstalledBytes(v))} · ${viewModel.voiceArch(v) ?: "已安装"}"
                                else -> "${mb(v.sizeBytes)} · 未下载"
                            },
                            color = when {
                                st?.busy == true -> St.accent
                                installed -> St.ok
                                else -> St.dim
                            },
                            fontSize = 11.sp,
                            maxLines = 2,
                        )
                        if (reason != null && installed) {
                            Spacer(Modifier.height(3.dp))
                            Text("⚠ " + reason, color = St.warn, fontSize = 10.sp)
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
        val dst = File(ctx.cacheDir, "studio-clone-" + System.currentTimeMillis() + ".wav")
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
            val dst = File(ctx.cacheDir, "studio-pick-" + System.currentTimeMillis() + ".wav")
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
            start = 16.dp, end = 16.dp, top = 12.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!viewModel.cloneBaseInstalled()) {
            item {
                HintCard(
                    "还没下载「声音克隆底模（中英 · 零样本，109 MB）」。可以先建音色，但合成前要装它。",
                    "去下载",
                ) { viewModel.toast("到「音色库」里下载「声音克隆底模」") }
            }
        }

        // ---- 新建 ----
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(St.surface)
                    .border(1.dp, St.stroke, RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                Text("新建克隆音色", color = St.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "录一段 5–30 秒的干净人声（一个人、没有背景音乐），再把它念的内容原样写下来。",
                    color = St.dim, fontSize = 11.sp,
                )
                Spacer(Modifier.height(12.dp))

                StudioInput(name, { name = it }, "音色名称，例如「我自己」")
                Spacer(Modifier.height(8.dp))
                StudioInput(transcript, { transcript = it }, "参考音频里念的文字（越准越像）", lines = 2)
                Spacer(Modifier.height(12.dp))

                if (recording) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(St.err),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("录音中… 再点一下停止", color = St.err, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        StudioProgress(amplitude)
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            color = if (durationMs in 2000..30_000) St.ok else St.warn,
                            fontSize = 11.sp,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (audio == null) St.surfaceHi else St.accent)
                        .clickable(enabled = audio != null && !recording) {
                            val a = audio ?: return@clickable
                            if (durationMs < 2000) {
                                viewModel.toast("太短了，至少 2 秒")
                                return@clickable
                            }
                            if (durationMs > 30_000) {
                                viewModel.toast("太长了，30 秒以内")
                                return@clickable
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
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "保存为我的音色",
                        color = if (audio == null) St.dim else Color.White,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // ---- 已有 ----
        item {
            Text("我的克隆音色", color = St.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (clones.isEmpty()) {
            item {
                Text("还没有。上面录一段就有了。", color = St.dim, fontSize = 12.sp)
            }
        }
        items(clones) { c ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(St.surface)
                    .border(
                        1.dp,
                        if (activeClone?.id == c.id) St.accent else St.stroke,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, color = St.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (activeClone?.id == c.id) {
                            Spacer(Modifier.width(6.dp))
                            Tag("使用中", St.accent)
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "参考文字 " + (c.transcript.ifBlank { "（未填）" }.take(28)),
                        color = St.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
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
private fun VoiceChip(
    title: String,
    letter: String,
    badge: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(84.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) St.accentDim else St.surface)
            .border(1.dp, if (selected) St.accent else St.stroke, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (selected) St.accent else St.surfaceHi),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                letter,
                color = if (selected) Color.White else St.text,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            color = St.text, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        Text(badge, color = St.dim, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun ParamChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(St.surface)
            .border(1.dp, St.stroke, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(text, color = St.text, fontSize = 11.sp) }
}

@Composable
private fun StudioSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = St.dim, fontSize = 11.sp, modifier = Modifier.width(34.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f).height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = St.accent,
                activeTrackColor = St.accent,
                inactiveTrackColor = St.surfaceHi,
            ),
        )
        Text("%.2f".format(Locale.US, value), color = St.text, fontSize = 11.sp, modifier = Modifier.width(38.dp))
    }
}

@Composable
private fun StudioInput(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    lines: Int = 1,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(St.surfaceHi)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = St.dim, fontSize = 12.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().height((16 + 18 * lines).dp),
            textStyle = TextStyle(color = St.text, fontSize = 13.sp, lineHeight = 18.sp),
            cursorBrush = SolidColor(St.accent),
        )
    }
}

@Composable
private fun SmallBtn(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val bg = when {
        primary -> St.accent
        danger -> Color(0xFF2A1618)
        else -> St.surfaceHi
    }
    val fg = when {
        primary -> Color.White
        danger -> St.err
        else -> St.text
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun Tag(text: String, color: Color = St.dim) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) { Text(text, color = color, fontSize = 9.sp) }
}

@Composable
private fun StudioProgress(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(St.surfaceHi),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(St.accent),
        )
    }
}

@Composable
private fun HintCard(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1B1A12))
            .border(1.dp, Color(0xFF3A3320), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = St.warn, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        SmallBtn(action) { onAction() }
    }
}

@Composable
private fun StudioPlayer(file: File, meta: String, onExport: () -> Unit, onDelete: () -> Unit) {
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

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(St.surface)
            .border(1.dp, St.stroke, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(St.accent)
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
                    null, Modifier.size(20.dp), tint = Color.White,
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
                        thumbColor = St.accent,
                        activeTrackColor = St.accent,
                        inactiveTrackColor = St.surfaceHi,
                    ),
                )
                Row {
                    Text(clock(position), color = St.dim, fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    Text(clock(duration), color = St.dim, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                meta.ifBlank { "已生成" },
                color = St.dim, fontSize = 10.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SmallBtn("导出") { onExport() }
            Spacer(Modifier.width(6.dp))
            SmallBtn("清除", danger = true) { onDelete() }
        }
    }
}

/* ------------------------------------------------------------------ 工具 */

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, total / 60, total % 60)
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

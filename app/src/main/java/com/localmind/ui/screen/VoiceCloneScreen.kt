package com.localmind.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.localmind.data.ClonedVoice
import com.localmind.ui.AppViewModel
import com.localmind.ui.design.Aurora
import com.localmind.ui.design.AuroraButton
import com.localmind.ui.design.AuroraDivider
import com.localmind.ui.design.AuroraGroup
import com.localmind.ui.design.AuroraIcon
import com.localmind.ui.design.AuroraNavBar
import com.localmind.ui.design.AuroraSectionLabel
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import com.localmind.util.AudioUtil
import com.localmind.util.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 「新建克隆音色」：上传或录一段参考音频 + 写下它念的文字 → 存成一个可用的音色。
 *
 * 这一页的心智模型只有一句话：**你给我一段声音，我就用那个声音说话**。所以它
 * 不做任何模型层面的解释，只解决三件事 —— 拿到音频、拿到文字、给个名字。
 *
 * 几个刻意的取舍：
 *  * **只收 WAV**。ZipVoice2 的参考音频最终要变成浮点波形，MP3/m4a 得先过
 *    MediaCodec 解码一大套；录音本来就出 WAV，导入也提示 WAV，不值得为小众格式加复杂度。
 *  * **文字必填**。零样本克隆靠「音频 ↔ 文字」对齐来提声纹；只给音频、不给文字，
 *    合成出来会明显跑调（参考音频的长度和内容对不上）。
 *  * **5–30 秒**。太短（<3 秒）声纹不稳，太长（>30 秒）推理时间线性上涨而收益递减。
 */
@Composable
fun VoiceCloneScreen(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder() }

    var name by remember { mutableStateOf("") }
    var transcript by remember { mutableStateOf("") }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var durationMs by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var amplitude by remember { mutableStateOf(0f) }
    var saving by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    // 录音动作要定义在权限回调之前：Kotlin 的局部函数不支持前向引用，
    // 而权限回调里必须在授权成功后立刻接着录。
    fun startRecording() {
        val dst = File(ctx.cacheDir, "clone-rec-" + System.currentTimeMillis() + ".wav")
        amplitude = 0f
        recording = true
        recorder.start(
            target = dst,
            maxSeconds = 30,
            onAmplitude = { amplitude = it },
            onFinished = { ok, ms ->
                recording = false
                amplitude = 0f
                if (ok) {
                    audioFile = dst
                    durationMs = ms
                }
            },
        )
    }

    // 录音权限：授权后立刻接着录 —— 让用户点两次「录音」是最容易被骂的交互。
    var pendingStart by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingStart) {
            pendingStart = false
            startRecording()
        } else if (!granted) {
            pendingStart = false
            viewModel.toast("没有麦克风权限，可以改成上传一段 WAV")
        }
    }

    // 上传 WAV：拷进 cacheDir 再解析一遍，确认它是能读的 PCM WAV，顺便拿到时长。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dst = File(ctx.cacheDir, "clone-pick-" + System.currentTimeMillis() + ".wav")
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        dst.outputStream().use { out -> input.copyTo(out) }
                    }
                    val parsed = AudioUtil.readWavToMonoFloat(dst.absolutePath)
                    if (parsed == null) {
                        dst.delete()
                        null
                    } else {
                        val (samples, rate) = parsed
                        dst to (samples.size * 1000 / rate.coerceAtLeast(1))
                    }
                }.getOrNull()
            }
            if (result == null) {
                viewModel.toast("这个文件不是可读的 WAV（需要 16-bit PCM）")
            } else {
                audioFile = result.first
                durationMs = result.second
                viewModel.toast("已载入参考音频 · " + seconds(durationMs))
            }
        }
    }

    // 底模没下载也能先把音色做出来（存音频而已），只是暂时还用不了它说话。
    // 所以这里不拦录音，只在保存按钮下面提示一句。

    DisposableEffect(Unit) {
        onDispose {
            recorder.stop()
            runCatching { player?.release() }
            player = null
        }
    }

    val ready = audioFile != null
    val tooShort = ready && durationMs < 2000
    val tooLong = ready && durationMs > 35_000
    val canSave = ready && !recording && !saving && !tooShort && !tooLong

    Column(Modifier.fillMaxSize()) {
        AuroraNavBar(title = "新建克隆音色", onBack = { viewModel.openVoiceCloneScreen(false) })

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(top = 4.dp, bottom = 44.dp),
        ) {
            item {
                AuroraSectionLabel("参考音频")
                AuroraGroup {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AuroraIcon(
                                AppIcons.Mic,
                                if (recording) ios.red else ios.blue,
                                size = 34.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        recording -> "正在录音…"
                                        audioFile != null -> "已就绪 · " + seconds(durationMs)
                                        else -> "还没有参考音频"
                                    },
                                    style = IosTheme.typography.headline,
                                    color = ios.label,
                                )
                                Text(
                                    when {
                                        recording -> "点「停止」结束，建议说满 10 秒以上"
                                        audioFile != null -> audioFile!!.name
                                        else -> "录一段，或从文件里挑一段 WAV"
                                    },
                                    style = IosTheme.typography.caption1,
                                    color = ios.secondaryLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        // 录音时的电平条：没有它用户不知道麦克风到底有没有在收声音。
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(ios.fillSecondary),
                        ) {
                            val frac = if (recording) amplitude.coerceIn(0.02f, 1f) else 0f
                            if (frac > 0f) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(frac)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(ios.red.copy(alpha = 0.75f)),
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AuroraButton(
                                text = if (recording) "停止" else if (ready) "重录" else "录音",
                                onClick = {
                                    if (recording) {
                                        recorder.stop()
                                    } else {
                                        val granted = ContextCompat.checkSelfPermission(
                                            ctx, Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) startRecording() else {
                                            pendingStart = true
                                            permission.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                },
                                color = if (recording) ios.red else null,
                                modifier = Modifier.weight(1f),
                            )
                            AuroraButton(
                                text = "上传 WAV",
                                onClick = {
                                    if (recording) recorder.stop()
                                    picker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*"))
                                },
                                color = ios.fill,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        if (ready && !recording) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AuroraButton(
                                    text = "试听原音",
                                    onClick = {
                                        val f = audioFile ?: return@AuroraButton
                                        runCatching {
                                            player?.release()
                                            val mp = MediaPlayer()
                                            mp.setDataSource(f.absolutePath)
                                            mp.prepare()
                                            mp.start()
                                            mp.setOnCompletionListener {
                                                runCatching { it.release() }
                                                player = null
                                            }
                                            player = mp
                                        }.onFailure { viewModel.toast("播放失败，换个文件试试") }
                                    },
                                    color = ios.fill,
                                    modifier = Modifier.weight(1f),
                                )
                                AuroraButton(
                                    text = "清空",
                                    onClick = {
                                        runCatching { player?.release() }
                                        player = null
                                        audioFile = null
                                        durationMs = 0
                                    },
                                    color = ios.fill,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        if (tooShort) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "这段太短了（不到 2 秒），声纹不够稳，建议 5–30 秒。",
                                style = IosTheme.typography.caption1,
                                color = ios.orange,
                            )
                        }
                        if (tooLong) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "超过 30 秒了。长音频不会更准，只会更慢，截一段就好。",
                                style = IosTheme.typography.caption1,
                                color = ios.orange,
                            )
                        }
                    }
                }
            }

            item {
                AuroraSectionLabel("这段音频念的是什么")
                AuroraGroup {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "把音频里说的话原样打下来。克隆靠「声音 ↔ 文字」对齐来提音色，写错会明显跑调。",
                            style = IosTheme.typography.caption1,
                            color = ios.secondaryLabel,
                        )
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 84.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ios.fillSecondary)
                                .padding(12.dp),
                        ) {
                            if (transcript.isEmpty()) {
                                Text(
                                    "例如：今天天气不错，我们一起出去走走吧。",
                                    style = IosTheme.typography.body,
                                    color = ios.tertiaryLabel,
                                )
                            }
                            BasicTextField(
                                value = transcript,
                                onValueChange = { transcript = it.take(400) },
                                textStyle = IosTheme.typography.body.copy(color = ios.label),
                                cursorBrush = SolidColor(ios.blue),
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            item {
                AuroraSectionLabel("起个名字")
                AuroraGroup {
                    Column(Modifier.padding(16.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(ios.fillSecondary)
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                        ) {
                            if (name.isEmpty()) {
                                Text("例如：我自己", style = IosTheme.typography.body, color = ios.tertiaryLabel)
                            }
                            BasicTextField(
                                value = name,
                                onValueChange = { name = it.take(24) },
                                singleLine = true,
                                textStyle = IosTheme.typography.body.copy(color = ios.label),
                                cursorBrush = SolidColor(ios.blue),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "保存后可以在语音页「我的克隆音色」里选用，聊天回复也会用它的声音读。",
                            style = IosTheme.typography.caption1,
                            color = ios.secondaryLabel,
                        )
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = Aurora.PagePadding, vertical = 12.dp)) {
                    AuroraButton(
                        text = if (saving) "保存中…" else "保存为我的音色",
                        enabled = canSave,
                        loading = saving,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val f = audioFile ?: return@AuroraButton
                            saving = true
                            scope.launch {
                                val created = withContext(Dispatchers.IO) {
                                    viewModel.createClonedVoice(name, transcript, f)
                                }
                                saving = false
                                if (created != null) {
                                    viewModel.toast("已保存「" + created.name + "」")
                                    viewModel.openVoiceCloneScreen(false)
                                }
                            }
                        },
                    )
                    if (!viewModel.cloneBaseInstalled()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "提示：克隆底模还没下载。音色可以先存，但要用它说话，得先在语音页下载" +
                                "「声音克隆底模（中英 · 零样本）」。",
                            style = IosTheme.typography.caption2,
                            color = ios.tertiaryLabel,
                        )
                    }
                }
            }
        }
    }
}

/** 语音页「我的克隆音色」里的一行：名字 + 文字摘要 + 使用/试听/删除。 */
@Composable
fun ClonedVoiceRow(
    viewModel: AppViewModel,
    clone: ClonedVoice,
    selected: Boolean,
) {
    val ios = IosTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) ios.blue.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    clone.name,
                    style = IosTheme.typography.body,
                    color = ios.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
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
                clone.transcript.takeIf { it.isNotBlank() }?.let { "“" + it.take(28) + "…”" }
                    ?: "没有参考文字（克隆会不太像）",
                style = IosTheme.typography.caption1,
                color = if (clone.transcript.isBlank()) ios.orange else ios.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (!selected) CloneAction("使用", ios.blue) { viewModel.selectClonedVoice(clone) }
        CloneAction("试听", ios.secondaryLabel) { viewModel.previewClonedVoice(clone) }
        CloneAction("删除", ios.red) { viewModel.deleteClonedVoice(clone.id) }
    }
}

@Composable
private fun CloneAction(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
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

/** 毫秒 → 「12.3 秒」。 */
private fun seconds(ms: Int): String =
    String.format(Locale.US, "%.1f 秒", ms / 1000f)

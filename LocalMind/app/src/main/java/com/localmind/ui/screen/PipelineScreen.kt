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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.data.PipelineStages
import com.localmind.ui.AppViewModel
import com.localmind.ui.Tab
import com.localmind.ui.components.IosCell
import com.localmind.ui.components.IosDivider
import com.localmind.ui.components.IosEmptyState
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosPrimaryButton
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosSecondaryButton
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

/**
 * 分步流水线页。
 *
 * 这个页面的定位很具体：**有些东西一台手机装不下，但拆开就能跑完**。
 *
 *  * 长文 → 拆句 → 逐句合成 → 拼成一个 wav（Breeze / 任意外部语音引擎都适用）；
 *  * 长文 → 拆块 → 逐块让本机模型处理 → 拼成一段结论（小上下文也能读长文）。
 *
 * 所以界面只有三块：选一条流水线、粘输入、看每一步的进度与结果。
 * 不做"参数大全"—— 参数在插件里声明，用户改的是插件，不是这里。
 */
@Composable
fun PipelineScreen(viewModel: AppViewModel, onBack: (() -> Unit)? = null) {
    val ios = IosTheme.palette
    val st by viewModel.pipelineUi.collectAsState()
    var input by remember { mutableStateOf("") }
    val active = st.pipelines.firstOrNull { it.id == st.activeId } ?: st.pipelines.firstOrNull()

    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent)) {
        IosNavigationBar(
            title = "流水线",
            collapsed = true,
            topInset = statusBarTopPadding(),
            leading = {
                com.localmind.ui.design.AuroraCircleButton(
                    icon = com.localmind.ui.icons.AppIconsAutoMirrored.ArrowBack,
                    contentDescription = "返回",
                    onClick = { onBack?.invoke() },
                    size = 36.dp,
                    tint = ios.label,
                )
            },
        )

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (st.pipelines.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(420.dp)) {
                    IosEmptyState(
                        icon = AppIcons.Extension,
                        title = "还没有流水线",
                        message = "装一个「分步流水线」插件：它把长任务拆成能单独跑的小步，" +
                            "跑完再聚合。手机装不下的大模型就靠这个用。",
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
                // ---- 选一条 ----
                IosSection(
                    header = "流程",
                    footer = "每条流程都是「拆解 → 逐步执行 → 聚合」。跑不动的大模型靠这个绕开显存/内存限制。",
                ) {
                    st.pipelines.forEachIndexed { i, p ->
                        if (i > 0) IosDivider()
                        val selected = p.id == active?.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !st.running) {
                                    viewModel.selectPipeline(p.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = IosTheme.typography.body, color = ios.label)
                                Text(
                                    p.description.take(60),
                                    style = IosTheme.typography.footnote,
                                    color = ios.secondaryLabel,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    p.stages.joinToString(" → ") {
                                        it.label.ifBlank { PipelineStages.LABELS[it.type] ?: it.type }
                                    },
                                    style = IosTheme.typography.caption2,
                                    color = ios.tertiaryLabel,
                                    maxLines = 2,
                                )
                            }
                            if (selected) {
                                Text("选中", style = IosTheme.typography.footnote, color = ios.blue)
                            }
                        }
                    }
                }

                if (active != null) {
                    // ---- 输入 ----
                    IosSection(header = "输入", footer = active.inputHint) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ios.fillSecondary)
                                .padding(10.dp),
                        ) {
                            BasicTextField(
                                value = input,
                                onValueChange = { input = it },
                                textStyle = IosTheme.typography.body.copy(color = ios.label),
                                cursorBrush = SolidColor(ios.blue),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = (active.inputLines * 26).dp, max = 320.dp),
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                            Text(
                                "${input.length} 字",
                                style = IosTheme.typography.caption2,
                                color = ios.tertiaryLabel,
                                modifier = Modifier.weight(1f),
                            )
                            if (input.isNotEmpty() && !st.running) {
                                Text(
                                    "清空",
                                    style = IosTheme.typography.caption1,
                                    color = ios.blue,
                                    modifier = Modifier.clickable { input = "" },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (st.running) {
                                IosSecondaryButton(
                                    text = "停止",
                                    onClick = { viewModel.cancelPipeline() },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            IosPrimaryButton(
                                text = if (st.running) "运行中…" else "开始",
                                loading = st.running,
                                enabled = !st.running && input.isNotBlank(),
                                onClick = { viewModel.runPipeline(active, input) },
                                modifier = Modifier.weight(2f),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    // ---- 进度 / 错误 ----
                    if (st.progress.isNotBlank() || st.error.isNotBlank()) {
                        IosSection(header = "状态") {
                            if (st.progress.isNotBlank()) {
                                IosCell(title = st.progress, showChevron = false)
                            }
                            if (st.error.isNotBlank()) {
                                if (st.progress.isNotBlank()) IosDivider()
                                IosCell(
                                    title = "出错了",
                                    subtitle = st.error.take(240),
                                    titleColor = ios.red,
                                    showChevron = false,
                                )
                            }
                        }
                    }

                    // ---- 体检结论（probe 步骤） ----
                    if (st.logs.isNotEmpty()) {
                        IosSection(header = "本机体检") {
                            Column(Modifier.padding(14.dp)) {
                                st.logs.forEach { line ->
                                    Text(
                                        "· $line",
                                        style = IosTheme.typography.caption1,
                                        color = ios.secondaryLabel,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }

                    // ---- 结果 ----
                    if (st.resultText.isNotBlank() || st.audioPath.isNotBlank()) {
                        IosSection(header = "结果", footer = if (st.audioPath.isNotBlank()) null else "文本结果可以直接复制出去。") {
                            Column(Modifier.padding(14.dp)) {
                                if (st.audioPath.isNotBlank()) {
                                    IosPrimaryButton(
                                        text = "播放合成结果",
                                        onClick = { viewModel.playPipelineAudio() },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        st.audioPath.substringAfterLast('/'),
                                        style = IosTheme.typography.caption2,
                                        color = ios.tertiaryLabel,
                                    )
                                }
                                if (st.resultText.isNotBlank()) {
                                    Text(
                                        st.resultText,
                                        style = IosTheme.typography.body,
                                        color = ios.label,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(100.dp))
        }
    }
}

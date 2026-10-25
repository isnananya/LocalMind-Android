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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localmind.data.ProviderKind
import com.localmind.data.RemoteProvider
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosLargeTitle
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.components.IosTag
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

/**
 * 云端接口。
 *
 * ## 这一页以前**不存在**
 *
 * 数据层（RemoteProvider / Storage.loadRemoteProviders）一直都在，模型选择器里也
 * 会列出来，但**没有任何地方能新建一个接口** —— 于是「接云端 API」这件事在界面上
 * 根本走不通，而聊天页的提示还写着「到「能力」页接入一个 API 服务」。
 * 这一页就是那句话指向的地方。
 *
 * ## 设计上的两条
 *
 *  1. **一键预设，密钥自己填**。baseUrl 带不带 /v1、Gemini 的密钥放 query、
 *     Anthropic 的角色映射 —— 这些坑每个人都会踩一遍，预设把它们挡掉。
 *  2. **能测**。填完密钥立刻能点「测试」，结果直接显示在这一行下面。
 *     配错了却要发一条消息才发现，是最费时间的一种失败。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ProviderScreen(viewModel: AppViewModel, onClose: () -> Unit) {
    val ios = IosTheme.palette
    val providers by viewModel.providers.collectAsState()
    val tests by viewModel.providerTests.collectAsState()
    val backend by viewModel.backend.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(ios.groupedBackground),
    ) {
        IosNavigationBar(
            title = "云端接口",
            collapsed = true,
            topInset = statusBarTopPadding(),
            leading = {
                Row(
                    modifier = Modifier.clickable(onClick = onClose),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.ChevronRight, contentDescription = null, tint = ios.blue, modifier = Modifier.size(18.dp))
                    Text("我的", style = IosTheme.typography.body, color = ios.blue)
                }
            },
        )

        LazyColumn(Modifier.fillMaxSize()) {
            item { IosLargeTitle("云端接口") }

            // ---------------------------------------------------- 默认后端
            item {
                IosSection(
                    header = "默认对话后端",
                    footer = "新建对话没单独指定模型时用哪个。接口在这里只是被选中，" +
                        "真正的请求会直接发到那台服务器，不经过任何第三方。",
                ) {
                    // FlowRow 而不是 Row：接口名有长有短（Google Gemini 就很长），
                    // 挤在一行里会被压成竖排的「Googl e Gemin i」—— 实测就是这样。
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IosTag(
                            text = "本机模型",
                            selected = backend == "local",
                            onClick = { viewModel.setBackend("local") },
                        )
                        providers.filter { it.enabled }.forEach { p ->
                            IosTag(
                                text = p.name,
                                selected = backend == p.id,
                                onClick = { viewModel.setBackend(p.id) },
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------- 已添加
            item {
                IosSection(
                    header = "已添加的接口",
                    footer = "密钥只存在这台手机上（files/remote-providers.json）。" +
                        "要发给别的设备只能自己复制。",
                ) {
                    if (providers.isEmpty()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("还没有接口", style = IosTheme.typography.body, color = ios.label)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "在下面选一个服务，填上 API Key 就能把对话、识图、生图、" +
                                    "语音都交给它。",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                    }
                    providers.forEach { p ->
                        ProviderRow(
                            provider = p,
                            test = tests[p.id],
                            expanded = editing == p.id,
                            onToggle = { on -> viewModel.updateProvider(p.id) { it.copy(enabled = on) } },
                            onEdit = { editing = if (editing == p.id) null else p.id },
                            onTest = { viewModel.testProvider(p.id) },
                            onDelete = { viewModel.removeProvider(p.id) },
                            onUpdate = { t -> viewModel.updateProvider(p.id, t) },
                        )
                    }
                }
            }

            // ---------------------------------------------------- 添加
            item {
                IosSection(
                    header = "添加接口",
                    footer = "预设只填地址与示例模型，Key 要自己填才生效。" +
                        "局域网里的 Ollama / vLLM 也走第一项（OpenAI 兼容）。",
                ) {
                    Column(Modifier.padding(14.dp)) {
                        RemoteProvider.PRESETS.chunked(2).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                row.forEach { preset ->
                                    Box(Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(9.dp))
                                                .background(ios.fillSecondary)
                                                .clickable { viewModel.addProviderFromPreset(preset) }
                                                .padding(horizontal = 10.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(preset.name, style = IosTheme.typography.footnote, color = ios.label, fontWeight = FontWeight.Medium)
                                                Text(preset.kind.label, style = IosTheme.typography.caption2, color = ios.tertiaryLabel)
                                            }
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }
}


@Composable
private fun ProviderRow(
    provider: RemoteProvider,
    test: String?,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: ((RemoteProvider) -> RemoteProvider) -> Unit,
) {
    val ios = IosTheme.palette
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(provider.name, style = IosTheme.typography.body, color = ios.label)
                    Spacer(Modifier.width(6.dp))
                    IosPill(provider.kind.label, ios.indigo)
                }
                Text(
                    provider.baseUrl + "  ·  " + provider.model,
                    style = IosTheme.typography.caption1,
                    color = ios.secondaryLabel,
                )
            }
            IosSwitch(checked = provider.enabled, onCheckedChange = onToggle)
        }

        if (test != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                test,
                style = IosTheme.typography.caption1,
                color = if (test.contains("正常")) ios.green else ios.orange,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallBtn("测试", ios.blue, onTest)
            SmallBtn(if (expanded) "收起" else "编辑", ios.gray, onEdit)
            SmallBtn("删除", ios.red, onDelete)
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Field("名称", provider.name) { v -> onUpdate { it.copy(name = v) } }
            Field("Base URL", provider.baseUrl) { v -> onUpdate { it.copy(baseUrl = v) } }
            Field("API Key", provider.apiKey) { v -> onUpdate { it.copy(apiKey = v) } }
            Field("对话模型", provider.model) { v -> onUpdate { it.copy(model = v) } }
            Field("识图模型", provider.visionModel) { v -> onUpdate { it.copy(visionModel = v) } }
            Field("生图模型", provider.imageModel) { v -> onUpdate { it.copy(imageModel = v) } }
            Field("语音模型", provider.speechModel) { v -> onUpdate { it.copy(speechModel = v) } }
            Spacer(Modifier.height(6.dp))
            Text(
                "协议：" + provider.kind.label + "。识图/生图/语音留空就用对话模型。",
                style = IosTheme.typography.caption2,
                color = ios.tertiaryLabel,
            )
        }
    }
}

@Composable
private fun SmallBtn(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, style = IosTheme.typography.footnote, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = IosTheme.typography.footnote, color = ios.secondaryLabel, modifier = Modifier.width(96.dp))
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
                textStyle = LocalTextStyle.current.merge(IosTheme.typography.footnote).copy(color = ios.label),
                cursorBrush = SolidColor(ios.blue),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

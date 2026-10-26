package com.localmind.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localmind.data.Conversation
import com.localmind.data.ProviderKind
import com.localmind.data.RemoteProvider
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosTag
import com.localmind.ui.theme.IosTheme

/**
 * 「这个对话用谁来回答」—— **本机模型 / 云端 OpenAI 格式接口** 的一块直接入口。
 *
 * ## 为什么单独做这么一块
 *
 * 原来的路径是：我的 → 云端接口 → 新建 → 填 Key → 回来 → 对话设置 → 点「对话模型」
 * → 在同一个列表里找那个 API 名字。模型选择器里确实**能**选到接口，但那条路藏得
 * 太深，用户的原话是「ai 对话的单独设置里也应该能选 api」。
 *
 * 还有一个更实际的理由：手机上跑 1.5B 模型大概十几 tok/s，而一个云端小模型的
 * 首 token 常常不到一秒。把「换成 API」放在这一页，是这个应用里**最能立刻感觉到快**
 * 的一个开关，不该让人翻三层菜单才找到。
 *
 * ## 这一块做什么
 *
 *  1. 一键切换：本机 / 任意已启用的接口（写入 conversation.chatModelId）；
 *  2. 现场新建一个 OpenAI 格式接口（Base URL + Key + 模型名），建完**直接绑到
 *     这个对话**，并且立刻发一次测试请求 —— 配错了当场就知道，而不是发一句话才发现；
 *  3. 显示上一次测试的结果，以及现在这个对话到底走哪条路。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ApiAccessSection(
    viewModel: AppViewModel,
    conversation: Conversation,
    /** 当前已加载的本地模型名；null = 还没加载，这一项点了会提示去加载。 */
    localModelName: String?,
) {
    val ios = IosTheme.palette
    val providers by viewModel.providers.collectAsState()
    val tests by viewModel.providerTests.collectAsState()
    var adding by remember { mutableStateOf(false) }

    val selectedProvider = providers.firstOrNull { it.id == conversation.chatModelId }
    val enabled = providers.filter { it.enabled }

    IosSection(
        header = "用 API 回答（OpenAI 格式）",
        footer = "本机模型完全离线、不花钱、隐私最好，但速度取决于手机；" +
            "接一个 OpenAI 兼容接口（OpenAI / DeepSeek / Kimi / 硅基流动 / 局域网 Ollama…）" +
            "首字通常快得多，代价是内容会离开这台手机。这里选的是这个对话，别的对话不受影响。",
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IosTag(
                    text = if (localModelName.isNullOrBlank()) "本机模型" else "本机 · " + localModelName,
                    selected = selectedProvider == null,
                    onClick = {
                        viewModel.selectConversationChatModel(conversation.id, "")
                        if (localModelName.isNullOrBlank()) {
                            viewModel.toast("还没有加载本地模型 —— 到「模型」页加载一个，或者继续用 API")
                        }
                    },
                )
                enabled.forEach { p ->
                    IosTag(
                        text = p.name,
                        selected = selectedProvider?.id == p.id,
                        onClick = { viewModel.selectConversationChatModel(conversation.id, p.id) },
                    )
                }
            }

            val current = selectedProvider
            if (current != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    current.model + " · " + current.baseUrl,
                    style = IosTheme.typography.caption1,
                    color = ios.secondaryLabel,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "API Key " + (if (current.apiKey.isBlank()) "未填 —— 大多数服务会直接 401" else "已填"),
                    style = IosTheme.typography.caption1,
                    color = if (current.apiKey.isBlank()) ios.warningText else ios.secondaryLabel,
                )
                tests[current.id]?.let { t ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t,
                        style = IosTheme.typography.caption1,
                        color = if (t.contains("正常")) ios.green else ios.warningText,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ApiSmallBtn("测一下", ios.blue) { viewModel.testProvider(current.id) }
                    ApiSmallBtn("详情与密钥", ios.gray) {
                        viewModel.toast("在「我的 → 云端接口」里可以改这个接口的地址和密钥")
                    }
                }
            }
        }

        ApiDivider(ios.separator)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { adding = !adding }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (adding) "收起" else "＋ 添加一个 OpenAI 格式接口",
                style = IosTheme.typography.body,
                color = ios.blue,
            )
        }

        if (adding) {
            ApiAddForm(viewModel = viewModel, conversationId = conversation.id)
        }
    }
}

/**
 * 添加接口的表单本体。
 *
 * 抽成一个 composable 是因为它有**两个入口**：对话设置里那一块（就地展开），
 * 以及模型选择器里的「＋ 手动添加自定义 API 地址」（弹窗）。复制一份的话，
 * 迟早出现「两个地方一个会自动补 /v1、另一个不会」这种静默不一致。
 */
@Composable
private fun ApiAddForm(
    viewModel: AppViewModel,
    /** null = 只新建接口，不绑定到任何对话（用在能力页那种全局入口）。 */
    conversationId: String?,
    /** 提交之后干点什么（弹窗里就是关掉自己）。 */
    onDone: () -> Unit = {},
) {
    val ios = IosTheme.palette
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var pickPreset by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
        // 预设：省掉「baseUrl 到底带不带 /v1」这个每个人都会踩的坑。
        Row(
            modifier = Modifier.fillMaxWidth().clickable { pickPreset = !pickPreset },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (pickPreset) "收起常用服务" else "从常用服务里挑一个（自动填地址）",
                style = IosTheme.typography.footnote,
                color = ios.blue,
            )
        }
        if (pickPreset) {
            Spacer(Modifier.height(6.dp))
            RemoteProvider.PRESETS.filter { it.kind == ProviderKind.OPENAI }
                .chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { p ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(ios.fillSecondary)
                                    .clickable {
                                        name = p.name
                                        baseUrl = p.baseUrl
                                        if (model.isBlank()) model = p.model
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Column {
                                    Text(p.name, style = IosTheme.typography.footnote, color = ios.label)
                                    Text(
                                        p.model,
                                        style = IosTheme.typography.caption2,
                                        color = ios.tertiaryLabel,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
        }

        Spacer(Modifier.height(8.dp))
        ApiField("名称", "随便起，例如「我的 DeepSeek」", name) { name = it }
        ApiField("Base URL", "https://api.deepseek.com 或 …/v1", baseUrl) { baseUrl = it }
        ApiField("API Key", "sk-…（只存在这台手机上）", apiKey) { apiKey = it }
        ApiField("模型名", "deepseek-chat / gpt-4o-mini / qwen-plus", model) { model = it }

        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ios.blue.copy(alpha = if (submitting) 0.4f else 1f))
                .clickable(enabled = !submitting) {
                    submitting = true
                    viewModel.addCustomOpenAiProvider(
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        bindConversationId = conversationId,
                    )
                    submitting = false
                    // 就地展开那种用法不收起（测试结果就在上面，用户可能还要改）；
                    // 弹窗那种用法由调用方传 onDone 关掉自己。
                    onDone()
                }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "接入并测试（立刻用这个对话试）",
                style = IosTheme.typography.body,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "点一次就行：会自动补 /v1、把它绑到这个对话、并发一次探活请求。" +
                "地址写错或 Key 不对会当场显示原因。",
            style = IosTheme.typography.caption2,
            color = ios.tertiaryLabel,
        )
    }
}

@Composable
private fun ApiField(label: String, placeholder: String, value: String, onChange: (String) -> Unit) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = IosTheme.typography.footnote,
            color = ios.secondaryLabel,
            modifier = Modifier.width(74.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(ios.fillSecondary)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = IosTheme.typography.footnote, color = ios.tertiaryLabel, maxLines = 1)
            }
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

@Composable
private fun ApiSmallBtn(text: String, color: Color, onClick: () -> Unit) {
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
private fun ApiDivider(color: Color) {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(color),
    )
}

/**
 * 「手动添加自定义 API 地址」的弹窗。
 *
 * 入口有两个：对话设置里那一块（就地展开），以及**模型选择器**里最下面那一行。
 * 后者是用户点名的：「在对话模型里手动添加自定义 api 地址」。人在选模型的那一刻
 * 最想接自己的接口，却要为此离开弹窗、跑到另一页、建完再回来找 —— 一件事被拆成四步。
 */
@Composable
internal fun CustomApiDialog(
    viewModel: AppViewModel,
    conversationId: String?,
    onDismiss: () -> Unit,
) {
    val ios = IosTheme.palette
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ios.groupedBackground)
                .imePadding()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        ) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Text("手动添加自定义 API", style = IosTheme.typography.title3, color = ios.label)
                Spacer(Modifier.height(4.dp))
                Text(
                    "OpenAI 格式（/v1/chat/completions）。地址不写 /v1 也行，会自动补；" +
                        "填完立刻测一次连接，并直接绑到这个对话。",
                    style = IosTheme.typography.caption1,
                    color = ios.secondaryLabel,
                )
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.padding(horizontal = 4.dp)) {
                ApiAddForm(
                    viewModel = viewModel,
                    conversationId = conversationId,
                    onDone = onDismiss,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("取消", style = IosTheme.typography.body, color = ios.blue)
            }
        }
    }
}
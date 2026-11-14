package com.localmind.ui.screen

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import com.localmind.ui.motion.ScrollPhysics
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosLargeTitle
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme
import com.localmind.util.Formatters

private data class EndpointSpec(val method: String, val path: String, val summary: String)

private val endpoints = listOf(
    EndpointSpec("GET", "/health", "存活与模型状态"),
    EndpointSpec("GET", "/v1/models", "列出本机模型（OpenAI 格式）"),
    EndpointSpec("POST", "/v1/chat/completions", "对话补全，支持 stream=true 流式"),
    EndpointSpec("POST", "/v1/completions", "旧版文本补全"),
    EndpointSpec("POST", "/v1/embeddings", "384 维向量（RAG 检索用）"),
    EndpointSpec("POST", "/v1/images/generations", "文字生图，返回 PNG 链接"),
    EndpointSpec("GET", "/v1/images/{file}", "取回生成的图片"),
    EndpointSpec("POST", "/v1/audio/speech", "文字转语音，返回 WAV"),
    EndpointSpec("GET", "/v1/system", "内存 / 线程 / 模型诊断"),
    EndpointSpec("POST", "/v1/admin/load", "切换常驻模型（可在设置里关闭）"),
)

@Composable
fun ApiScreen(viewModel: AppViewModel, onBack: (() -> Unit)? = null) {
    val ios = IosTheme.palette
    val settings by viewModel.api.collectAsState()
    val state by viewModel.apiState.collectAsState()
    val logs by viewModel.apiLog.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val clipboard = LocalClipboardManager.current

    var portText by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var keyVisible by remember { mutableStateOf(false) }

    val localUrl = "http://127.0.0.1:${settings.port}"
    val lanIp = com.localmind.service.ApiServer.lanAddresses().firstOrNull() ?: "未检测到局域网地址"
    val lanUrl = if (settings.bindLan) "$lanIp:${settings.port}" else "（未开启局域网监听）"

    Column(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
    ) {
        // 标题就叫「本地 API」：原来顶栏写「开放接口」、下面大标题又写「本地 API」，
        // 同一个页面两个名字，用户以为进了两个地方。返回键也用全站统一的圆形箭头。
        IosNavigationBar(
            title = "本地 API",
            collapsed = true,
            topInset = statusBarTopPadding(),
            leading = {
                if (onBack != null) {
                    com.localmind.ui.design.AuroraCircleButton(
                        icon = com.localmind.ui.icons.AppIconsAutoMirrored.ArrowBack,
                        contentDescription = "返回",
                        onClick = { onBack() },
                        size = 36.dp,
                        tint = ios.label,
                    )
                } else {
                    Icon(AppIcons.Api, contentDescription = null, tint = ios.teal, modifier = Modifier.size(20.dp))
                }
            },
            trailing = {
                com.localmind.ui.design.AuroraCircleButton(
                    icon = AppIcons.Delete,
                    contentDescription = "清空日志",
                    onClick = { viewModel.clearApiLog() },
                    size = 36.dp,
                    tint = ios.secondaryLabel,
                )
            },
        )

        LazyColumn(
            flingBehavior = ScrollPhysics.fling(),
            modifier = Modifier.fillMaxSize(),
            // 顶栏下面直接贴卡片会显得挤；留 12dp，和外观页的节奏一致。
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 28.dp),
        ) {
            item {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ios.secondaryGroupedBackground)
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (state.running) ios.green else ios.gray)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.running) "服务运行中" else "服务已停止",
                            style = IosTheme.typography.headline,
                            color = ios.label,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.running) IosPill("${state.requestCount} 次请求", ios.green)
                    }

                    Spacer(Modifier.height(12.dp))
                    UrlRow("本机应用", localUrl) {
                        clipboard.setText(AnnotatedString(localUrl))
                        viewModel.toast("已复制")
                    }
                    UrlRow("局域网设备", if (settings.bindLan) lanUrl else lanUrl, enabled = settings.bindLan) {
                        if (settings.bindLan) {
                            clipboard.setText(AnnotatedString(lanUrl))
                            viewModel.toast("已复制局域网地址")
                        }
                    }
                    UrlRow("OpenAI Base URL", "$localUrl/v1") {
                        clipboard.setText(AnnotatedString("$localUrl/v1"))
                        viewModel.toast("已复制 Base URL")
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (state.running) ios.red.copy(alpha = 0.14f) else ios.blue)
                                .clickable { if (state.running) viewModel.stopApi() else viewModel.startApi() }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (state.running) AppIcons.Stop else AppIcons.PlayArrow,
                                    contentDescription = null,
                                    tint = if (state.running) ios.red else ios.onAccent,
                                    modifier = Modifier.size(17.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (state.running) "停止服务" else "启动服务",
                                    style = IosTheme.typography.headline,
                                    color = if (state.running) ios.red else ios.onAccent,
                                )
                            }
                        }
                    }

                    state.lastError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = IosTheme.typography.caption1, color = ios.red)
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        "典型用法：在电脑的 OpenAI 客户端里把 Base URL 指向上面的地址，" +
                            "任何支持 OpenAI 接口的程序都能用这个手机上的模型。",
                        style = IosTheme.typography.caption1,
                        color = ios.secondaryLabel,
                    )

                    if (!engineState.ready) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "注意：当前没有加载模型，对话接口会返回 503。",
                            style = IosTheme.typography.caption1,
                            color = ios.warningText,
                        )
                    }
                }
            }

            item {
                IosSection(header = "服务设置") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Lan, contentDescription = null, tint = ios.indigo, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("允许局域网访问", style = IosTheme.typography.body, color = ios.label)
                            Text(
                                "开启后同 WiFi 的电脑/平板可以调用；关闭则只有本机可访问",
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                            )
                        }
                        IosSwitch(
                            checked = settings.bindLan,
                            onCheckedChange = { on ->
                                viewModel.updateApi { it.copy(bindLan = on) }
                                if (state.running) {
                                    viewModel.stopApi()
                                    viewModel.startApi()
                                }
                            },
                        )
                    }

                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("端口", style = IosTheme.typography.body, color = ios.label)
                            Text("默认 8080，被占用时改成 8081/9090", style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                        }
                        Box(
                            modifier = Modifier
                                .width(88.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ios.fillSecondary)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            BasicTextField(
                                value = portText,
                                onValueChange = { text ->
                                    portText = text.filter { it.isDigit() }.take(5)
                                    portText.toIntOrNull()?.let { p ->
                                        if (p in 1024..65535) viewModel.updateApi { it.copy(port = p) }
                                    }
                                },
                                textStyle = LocalTextStyle.current.merge(IosTheme.typography.body).copy(color = ios.label),
                                cursorBrush = SolidColor(ios.blue),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("API Key", style = IosTheme.typography.body, color = ios.label)
                            Text(
                                settings.apiKey.ifBlank { "未设置（任何本机/局域网请求都可访问）" },
                                style = IosTheme.typography.caption1.copy(fontFamily = FontFamily.Monospace),
                                color = ios.secondaryLabel,
                            )
                        }
                        Row {
                            Icon(
                                AppIcons.Refresh,
                                contentDescription = "生成",
                                tint = ios.blue,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { viewModel.regenerateApiKey() },
                            )
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                AppIcons.ContentCopy,
                                contentDescription = "复制",
                                tint = ios.blue,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        if (settings.apiKey.isNotBlank()) {
                                            clipboard.setText(AnnotatedString(settings.apiKey))
                                            viewModel.toast("已复制 API Key")
                                        }
                                    },
                            )
                        }
                    }

                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))

                    SwitchRow(
                        "允许通过 API 切换模型",
                        "POST /v1/admin/load，可用本机已下载的其它模型",
                        settings.allowModelSwitch,
                    ) { on -> viewModel.updateApi { it.copy(allowModelSwitch = on) } }

                    SwitchRow(
                        "允许图片生成接口",
                        "POST /v1/images/generations",
                        settings.allowImage,
                    ) { on -> viewModel.updateApi { it.copy(allowImage = on) } }

                    SwitchRow(
                        "允许浏览器跨域读取",
                        if (settings.corsOrigin.isBlank()) "关（推荐）。浏览器页面直连时才需要打开"
                        else "开：Allow-Origin: ${settings.corsOrigin}",
                        settings.corsOrigin.isNotBlank() && settings.corsOrigin != "null",
                    ) { on -> viewModel.updateApi { it.copy(corsOrigin = if (on) "*" else "") } }
                }
            }

            item {
                IosSection(header = "调用示例") {
                    Column(Modifier.padding(14.dp)) {
                        val example = """
curl $localUrl/v1/chat/completions \
  -H "Content-Type: application/json" \
  ${if (settings.apiKey.isNotBlank()) "-H \"Authorization: Bearer ${settings.apiKey}\" \\\n  " else ""}-d '{
    "model": "localmind",
    "messages": [{"role":"user","content":"你好"}],
    "stream": true
  }'
                        """.trimIndent()
                        SelectionContainer {
                            Text(
                                example,
                                style = IosTheme.typography.caption1.copy(fontFamily = FontFamily.Monospace, lineHeight = 16.sp),
                                color = ios.label,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(ios.fillSecondary)
                                .clickable {
                                    clipboard.setText(AnnotatedString(example))
                                    viewModel.toast("已复制 curl 示例")
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("复制这段命令", style = IosTheme.typography.footnote, color = ios.blue, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            item {
                IosSection(header = "可用端点") {
                    Column {
                        endpoints.forEachIndexed { index, spec ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IosPill(
                                    spec.method,
                                    if (spec.method == "GET") ios.green else ios.blue,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        spec.path,
                                        style = IosTheme.typography.footnote.copy(fontFamily = FontFamily.Monospace),
                                        color = ios.label,
                                    )
                                    Text(spec.summary, style = IosTheme.typography.caption2, color = ios.secondaryLabel)
                                }
                            }
                            if (index != endpoints.lastIndex) {
                                Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, end = 16.dp, top = 22.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("请求日志", style = IosTheme.typography.footnote, color = ios.secondaryLabel, modifier = Modifier.weight(1f))
                    Text("${logs.size} 条", style = IosTheme.typography.caption1, color = ios.tertiaryLabel)
                }
            }

            if (logs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ios.secondaryGroupedBackground)
                            .padding(18.dp)
                    ) {
                        Text(
                            "还没有请求。启动服务后用 curl / 浏览器 / OpenAI 客户端访问一下试试。",
                            style = IosTheme.typography.footnote,
                            color = ios.secondaryLabel,
                        )
                    }
                }
            }

            items(logs.take(60), key = { it.id }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ios.secondaryGroupedBackground)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IosPill(
                        entry.status.toString(),
                        when {
                            entry.status in 200..299 -> ios.green
                            entry.status in 400..499 -> ios.orange
                            else -> ios.red
                        },
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${entry.method} ${entry.path}",
                            style = IosTheme.typography.caption1.copy(fontFamily = FontFamily.Monospace),
                            color = ios.label,
                            maxLines = 1,
                        )
                        Text(
                            "${Formatters.time(entry.time)} · ${entry.durationMs}ms · ${entry.remote}",
                            style = IosTheme.typography.caption2,
                            color = ios.secondaryLabel,
                        )
                        if (entry.promptPreview.isNotBlank()) {
                            Text(
                                entry.promptPreview,
                                style = IosTheme.typography.caption2,
                                color = ios.tertiaryLabel,
                                maxLines = 1,
                            )
                        }
                        entry.error?.let {
                            Text(it, style = IosTheme.typography.caption2, color = ios.red, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlRow(label: String, url: String, enabled: Boolean = true, onCopy: () -> Unit) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = IosTheme.typography.caption1, color = ios.secondaryLabel, modifier = Modifier.width(126.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, ios.separator, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onCopy)
                .padding(horizontal = 9.dp, vertical = 7.dp)
        ) {
            Text(
                url,
                style = IosTheme.typography.caption1.copy(fontFamily = FontFamily.Monospace),
                color = if (enabled) ios.blue else ios.tertiaryLabel,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SwitchRow(
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

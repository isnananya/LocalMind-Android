package com.localmind.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import com.localmind.ui.motion.ScrollPhysics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.data.Diagnostics
import com.localmind.ui.AppViewModel
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIconsAutoMirrored
import com.localmind.ui.theme.IosTheme
import kotlinx.coroutines.launch

/**
 * 测试模式。
 *
 * ## 入口是隐藏的
 *
 * 关于页的版本号**连点三下**进来。诊断信息对普通用户是纯噪音，对内测的人是刚需，
 * 藏起来两边都满意 —— 而且不需要在正式界面里塞一个"开发者选项"。
 *
 * ## 这一页解决什么
 *
 * 内测反馈最常见的样子是一句"它闪退了 / 它卡住了"。没有机型、没有系统版本、
 * 不知道他点之前做了什么，这条反馈基本没法用。所以这一页把三件事摆在一起：
 *
 *  1. **机型与系统** —— 反馈里最常缺的东西；
 *  2. **崩溃记录** —— 未捕获异常的完整堆栈 + 崩溃前 40 步操作；
 *  3. **最近操作** —— 没崩但"它就是不对"时，看用户到底点了什么。
 *
 * 底部两个按钮把它变成**一个可以直接发出去的文件**（分享）或一段可以粘贴的文字
 * （复制）。测试者不需要理解里面写了什么。
 */
@Composable
fun DebugScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val ios = IosTheme.palette
    val ctx = LocalContext.current

    // 刷新键：日志是文件里的东西，不是 State，删掉/新增之后要手动重读。
    var tick by remember { mutableIntStateOf(0) }
    // 这三个订阅**只为了让这一页实时刷新**，值本身不直接用：
    // diagnosticsExtra() 读的是各个 .value（不是 State），不订阅的话，
    // 引擎加载完、后端起停都不会让这一页重组 —— 就成了"得退出去再进来"。
    // 不留变量是为了避免"声明了没用"的警告，读 State 这一步已经完成订阅。
    viewModel.engineState.collectAsState()
    viewModel.diffusionBackendReady.collectAsState()
    viewModel.apiServer.state.collectAsState()
    val report by viewModel.diagnostics.collectAsState()
    var exported by remember { mutableStateOf<String?>(null) }
    // 设备感知：一键读取的结果 + 正在读的状态。
    var senseResult by remember { mutableStateOf<String?>(null) }
    var sensing by remember { mutableStateOf(false) }
    // 「试画一张」：结果 + 正在画的状态。生图链路上有四个可能断的地方
    //（后端 / checkpoint / 加载 / 出图），不真画一张就只能猜。
    var drawResult by remember { mutableStateOf<String?>(null) }
    var drawing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 四类任务路由要显示"当前对话用的是谁"，所以要订阅这两个。
    val conversations by viewModel.conversations.collectAsState()
    val activeConvId by viewModel.activeId.collectAsState()

    val device = remember(tick) { Diagnostics.deviceRows(ctx) }
    val crashes = remember(tick) { Diagnostics.crashes(ctx) }
    val actions = remember(tick) { Diagnostics.actions(ctx, 60) }

    // ---- 完整 logcat ----
    // 只读**本进程**的日志（Android 4.1 起 READ_LOGS 是系统权限，读别人的日志会失败）。
    // 这一块是给内测反馈用的：出问题时把整段日志复制/分享出去，比"它闪退了"有用得多。
    var logLevel by remember { mutableStateOf("V") }
    var logText by remember { mutableStateOf("") }
    var logLoading by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        logLoading = true
        logText = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { readLogcat() }
        logLoading = false
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    val selfTestText = report?.let { r ->
        r.checks.joinToString("\n") { c -> (if (c.ok) "[OK] " else "[!!] ") + c.name + ": " + c.detail }
    }

    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Transparent)) {
        com.localmind.ui.design.AuroraNavBar(title = "测试模式", onBack = onBack)

        LazyColumn(
            flingBehavior = ScrollPhysics.fling(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ---------------------------------------------------------- 机型
            item {
                IosSection(
                    header = "机型与系统",
                    footer = "内测反馈里最常缺的就是这一段。导出时会自动带上。",
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        device.forEach { (k, v) -> InfoRow(k, v) }
                    }
                }
            }

            // ---------------------------------------------------------- 运行状态
            item {
                IosSection(header = "运行状态", footer = "实时值，不用退出重进。") {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        // **只从 diagnosticsExtra() 取**，不要在这里再拼一份：
                        // 两处各写一遍必然分叉，而且标签稍不一样（"上下文 / 线程" vs
                        // "上下文" + "线程"）时 distinctBy 也去不掉，界面会重复两行。
                        // 界面看到的和导出报告里的必须是同一份来源。
                        viewModel.diagnosticsExtra().forEach { (k, v) -> InfoRow(k, v) }
                    }
                }
            }

            // ---------------------------------------------------------- 完整日志
            item {
                IosSection(
                    header = "完整日志",
                    footer = "本应用进程的 logcat，最多 800 行。反馈问题时直接复制或分享这一段。",
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            listOf("V", "D", "I", "W", "E").forEach { level ->
                                LevelChip(level, logLevel == level) { logLevel = level }
                                Spacer(Modifier.width(6.dp))
                            }
                            Spacer(Modifier.weight(1f))
                            if (logLoading) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = ios.blue,
                                )
                            } else {
                                Btn("刷新", ios.blue) { tick++ }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        val shown = remember(logText, logLevel) { filterLog(logText, logLevel) }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(340.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ios.codeBackground)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = shown.ifBlank { "（没有日志）" },
                                style = IosTheme.typography.caption2.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                ),
                                color = ios.secondaryLabel,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(
                                        androidx.compose.foundation.rememberScrollState()
                                    ),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Btn("复制日志", ios.green) {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(shown))
                                viewModel.toast("日志已复制（" + shown.length + " 字）")
                            }
                            Btn("刷新一次", ios.purple) { tick++ }
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 自检
            item {
                IosSection(
                    header = "自检",
                    footer = "原生库 / 引擎 / 渲染 / 后端 / API / 存储。换机器先跑一遍。",
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Btn("运行自检", ios.green) { viewModel.runDiagnostics() }
                        report?.let { r ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "通过 " + r.passed + "/" + r.checks.size + " · 耗时 " + r.durationMs + "ms",
                                style = IosTheme.typography.footnote,
                                color = if (r.allCriticalPassed) ios.green else ios.warningText,
                            )
                            Spacer(Modifier.height(6.dp))
                            r.checks.forEach { c ->
                                Text(
                                    (if (c.ok) "✓ " else "✗ ") + c.name + " — " + c.detail,
                                    style = IosTheme.typography.caption1,
                                    color = if (c.ok) ios.secondaryLabel else ios.red,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 存储占用
            //
            // 内测反馈里"手机没空间了"很常见，而用户不知道是谁占的
            //（模型 2GB？音色 200MB？还是表情包？）。一次列清楚，省一轮来回。
            item {
                IosSection(
                    header = "存储占用",
                    footer = "模型和音色是运行时下载的，不在 APK 里。想腾空间优先删模型。",
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        val app = com.localmind.LocalMindApp.of(ctx)
                        val rows = remember(tick) {
                            listOf(
                                "模型" to app.storage.modelsDir,
                                "图片" to app.storage.imagesDir,
                                "插件" to app.storage.pluginsDir,
                                "原生库" to app.storage.nativeDir,
                                "诊断日志" to com.localmind.data.Diagnostics.dir(ctx),
                            ).map { (k, f) -> k to com.localmind.util.Formatters.size(dirSize(f)) } +
                                // 下载中断/失败留下的临时产物（.part 与 .part.d 段目录）。
                                // 它们**没有任何界面入口**，所以只能在这里露出来 ——
                                // 否则用户永远不知道手机里那几百 MB 是谁占的。
                                listOf(
                                    "未完成的下载" to com.localmind.util.Formatters.size(
                                        app.storage.allLeftoverParts().sumOf { dirSize(it) },
                                    ),
                                )
                        }
                        rows.forEach { (k, v) -> InfoRow(k, v) }
                        InfoRow("合计", com.localmind.util.Formatters.size(dirSize(ctx.filesDir)))
                        Spacer(Modifier.height(6.dp))
                        Btn("重新统计", ios.blue) { tick++ }
                    }
                }
            }

            // ---------------------------------------------------------- 四类任务
            //
            // 「多模型配合」最难验证的地方就是**界面上看不出谁在用谁**：用户选了四个
            // 模型，出问题时不知道是"没生效"还是"模型不行"。把解析结果直接摊开，
            // 再给一个真画一张的按钮，就不用猜了。
            item {
                IosSection(
                    header = "四类任务用哪个模型",
                    footer = "对话 / 识图 / 生图 / 决策各用各的模型。没有打开的对话时，" +
                        "显示的是全局默认。",
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        val conv = conversations.firstOrNull { it.id == activeConvId }
                        viewModel.taskRoutingReport(conv).forEach { (k, v) -> InfoRow(k, v) }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (conv == null) "当前没有打开的对话 —— 上面是全局默认"
                            else "当前对话：" + conv.title + "（" +
                                (if (conv.companionMode) "人设聊天" else "AI 对话") + "）",
                            style = IosTheme.typography.caption1,
                            color = ios.secondaryLabel,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            Btn(if (drawing) "正在画…" else "试画一张 64×64", ios.green) {
                                if (!drawing) {
                                    drawing = true
                                    drawResult = null
                                    Diagnostics.action(ctx, "测试模式：试画一张")
                                    scope.launch {
                                        drawResult = runCatching { viewModel.debugDrawTest() }
                                            .getOrElse { "失败：" + (it.message ?: "未知") }
                                        drawing = false
                                        tick++
                                    }
                                }
                            }
                        }
                        drawResult?.let { r ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                r,
                                style = IosTheme.typography.caption1,
                                fontFamily = FontFamily.Monospace,
                                color = ios.label,
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ios.fillSecondary)
                                    .padding(8.dp),
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 设备感知
            //
            // 为什么单独一块、而且**绕开对话开关**：对话里那三个开关是"要不要用它"，
            // 而这一页要回答的是"这台机器读不读得到"。两个问题混在一起时，
            // "读不到"到底是没开还是坏了，谁都说不清。
            item {
                IosSection(
                    header = "设备感知",
                    footer = "电量 / 位置 / 天气。这里三项都读一遍，不受对话开关影响。" +
                        "读到的和聊天时注入给模型的完全一样（同一个函数）。",
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Btn(if (sensing) "正在读…" else "读一次", ios.green) {
                                if (!sensing) {
                                    sensing = true
                                    senseResult = null
                                    Diagnostics.action(ctx, "测试模式：读设备感知")
                                    scope.launch {
                                        senseResult = runCatching { viewModel.debugSenseSnapshot() }
                                            .getOrElse { "读取失败：" + (it.message ?: "未知") }
                                            .ifBlank { "什么都没读到（看下面自检里的「定位与天气」）" }
                                        sensing = false
                                        tick++
                                    }
                                }
                            }
                            Btn("清缓存", ios.orange) {
                                com.localmind.service.DeviceSense.invalidate()
                                senseResult = "已清空缓存（电量 45 秒 / 位置 10 分钟 / 天气 25 分钟），下一次一定真读"
                            }
                        }
                        senseResult?.let { r ->
                            Spacer(Modifier.height(10.dp))
                            Text(
                                r,
                                style = IosTheme.typography.caption1,
                                fontFamily = FontFamily.Monospace,
                                color = ios.label,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(ios.fillSecondary)
                                    .padding(8.dp),
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 崩溃
            item {
                IosSection(
                    header = "崩溃记录",
                    footer = if (crashes.isEmpty()) {
                        "没有记录。真崩过一次之后这里会带上堆栈、机型和崩溃前的操作。"
                    } else {
                        "共 " + crashes.size + " 条，新的在前。导出时会全部带上。"
                    },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        if (crashes.isEmpty()) {
                            Text("（空）", style = IosTheme.typography.footnote, color = ios.tertiaryLabel)
                        } else {
                            crashes.take(3).forEach { c ->
                                Text(
                                    c.trim().lines().take(14).joinToString("\n"),
                                    style = IosTheme.typography.caption2,
                                    fontFamily = FontFamily.Monospace,
                                    color = ios.label,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ios.fillSecondary)
                                        .padding(8.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Btn("刷新", ios.blue) { tick++ }
                            Btn("清空", ios.red) {
                                Diagnostics.clear(ctx)
                                exported = null
                                tick++
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------------------- 操作流水
            item {
                IosSection(
                    header = "最近操作",
                    footer = "全应用的操作都记在这里（" + actions.size + " 条，新的在前）。" +
                        "「它卡住了但没崩」这类问题基本靠这一段定位。",
                ) {
                    Column(Modifier.padding(14.dp)) {
                        if (actions.isEmpty()) {
                            Text("（空）", style = IosTheme.typography.footnote, color = ios.tertiaryLabel)
                        } else {
                            Text(
                                actions.take(40).joinToString("\n"),
                                style = IosTheme.typography.caption2,
                                fontFamily = FontFamily.Monospace,
                                color = ios.label,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Btn("刷新", ios.blue) { tick++ }
                    }
                }
            }

            // ---------------------------------------------------------- 导出
            item {
                IosSection(
                    header = "导出",
                    footer = "报告 = 机型与系统 + 运行状态（含设备感知状态与每个对话用的模型）" +
                        " + 自检 + 崩溃记录 + 最近 120 步操作。" +
                        "内测时让对方把这个文件发回来，原因基本就在里面。",
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Btn("分享报告文件", ios.blue) {
                                val text = Diagnostics.buildReport(ctx, viewModel.diagnosticsExtra(), selfTestText)
                                exported = runCatching { shareReport(ctx, text) }
                                    .getOrElse { "导出失败：" + (it.message ?: "未知") }
                            }
                            Btn("复制全文", ios.purple) {
                                val text = Diagnostics.buildReport(ctx, viewModel.diagnosticsExtra(), selfTestText)
                                runCatching {
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("LocalMind 诊断报告", text))
                                    exported = "已复制到剪贴板（" + text.length + " 字）"
                                }.onFailure { exported = "复制失败：" + (it.message ?: "未知") }
                            }
                        }
                        exported?.let { msg ->
                            Spacer(Modifier.height(10.dp))
                            Text(msg, style = IosTheme.typography.caption1, color = ios.green)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 递归算目录大小。
 *
 * 放在 IO 之外会卡界面（模型目录里有几百 MB 到几 GB 的文件，递归要一秒左右），
 * 所以调用方用 remember(tick) 只在刷新时算一次 —— 见存储占用那一块。
 */
private fun dirSize(dir: java.io.File?): Long {
    if (dir == null || !dir.exists()) return 0L
    return runCatching {
        dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }.getOrDefault(0L)
}

/** 把报告写成文件并拉起系统分享。返回一句给用户看的结果。 */
private fun shareReport(ctx: Context, text: String): String {
    val dir = java.io.File(ctx.cacheDir, "diagnostics").apply { mkdirs() }
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        .format(java.util.Date())
    val file = java.io.File(dir, "localmind-诊断-" + stamp + ".txt")
    file.writeText(text)

    val uri = androidx.core.content.FileProvider.getUriForFile(
        ctx, ctx.packageName + ".fileprovider", file,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "LocalMind 诊断报告")
        // 有些客户端只读 EXTRA_TEXT，不带的话发出去是个空消息。
        putExtra(Intent.EXTRA_TEXT, text.take(6000))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "导出诊断报告"))
    return "已生成：" + file.name + "（" + text.length + " 字）"
}

@Composable
private fun LevelChip(level: String, selected: Boolean, onClick: () -> Unit) {
    val ios = IosTheme.palette
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) ios.blue else ios.fillSecondary)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            level,
            style = IosTheme.typography.caption2,
            color = if (selected) ios.onAccent else ios.secondaryLabel,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = IosTheme.typography.footnote,
            color = ios.secondaryLabel,
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            style = IosTheme.typography.footnote,
            color = ios.label,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Btn(label: String, tint: Color, onClick: () -> Unit) {
    Text(
        label,
        style = IosTheme.typography.footnote,
        fontWeight = FontWeight.Medium,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** 读本进程最近的 logcat；读不到就返回空串，不抛。 */
private fun readLogcat(): String = runCatching {
    val pid = android.os.Process.myPid()
    val proc = Runtime.getRuntime().exec(
        arrayOf("logcat", "-d", "-v", "time", "--pid=" + pid)
    )
    val text = proc.inputStream.bufferedReader().use { it.readText() }
    proc.destroy()
    val lines = text.lines()
    lines.takeLast(800).joinToString("\n")
}.getOrDefault("")

/** 按级别过滤；V 表示全部。 */
private fun filterLog(text: String, level: String): String {
    if (text.isBlank() || level == "V") return text
    return text.lines().filter { it.contains(" " + level + "/") }.joinToString("\n")
}

package com.localmind.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.localmind.data.Persona
import com.localmind.data.PersonaPresets
import com.localmind.data.ProxySettings
import com.localmind.ui.AppViewModel
import com.localmind.ui.Tab
import com.localmind.ui.components.IosNavigationBar
import com.localmind.ui.components.IosPill
import com.localmind.ui.components.IosSection
import com.localmind.ui.components.IosSwitch
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.motion.Motion
import com.localmind.ui.theme.IosTheme

/**
 * 「我的」——**设备级 / 账号级**的设置。
 *
 * 这里只放"整台手机共用"的东西：你是谁、默认人设库、能力开关、下载代理、本机 API。
 *
 * 刻意**不放**情绪、性格、记忆、经验、这个 AI 是谁 —— 那些都是**某一段关系**的属性，
 * 而关系是按对话建立的。它们属于对话详情（见
 * [com.localmind.ui.components.ConversationInsightSection]），不属于全局设置页。
 * 把两者混在一起，会让用户以为改一处就影响所有对话。
 */
@Composable
fun ProfileScreen(viewModel: AppViewModel) {
    val ios = IosTheme.palette
    val profile by viewModel.userProfile.collectAsState()
    val personas by viewModel.personas.collectAsState()
    val activePersonaId by viewModel.activePersonaId.collectAsState()
    val caps by viewModel.capabilities.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val api by viewModel.api.collectAsState()
    val apiState by viewModel.apiServer.state.collectAsState()
    val activeTheme by viewModel.activeUiTheme.collectAsState()
    val uiThemes by viewModel.installedUiThemes.collectAsState()
    // 设置是根层渲染的整页；这里读它只是为了让 meFullPage 把它算进去（底栏让位）。
    val showSettings by viewModel.showSettings.collectAsState()

    var editing by remember { mutableStateOf<Persona?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var showPersonas by remember { mutableStateOf(false) }
    var showApi by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var showProviders by remember { mutableStateOf(false) }
    var showThemes by remember { mutableStateOf(false) }
    var editingMe by remember { mutableStateOf(false) }
    var proxy by remember { mutableStateOf(viewModel.storage.loadProxySettings()) }
    val providers by viewModel.providers.collectAsState()

    // 「主题」这一行**始终可见**。
    //
    // 原来它被 `uiThemes.isNotEmpty()` 挡着，也就是「装过界面主题插件才出现」。
    // 但主题页里有**四个内置预设**（默认 / 米黄 / 青瓷 / 夜樱…），它们不需要任何插件、
    // 点一下就换 —— 于是没装插件的人永远看不到那个入口，也就永远不知道能换风格。
    // 用户的原话是「主题按钮默认开启」。
    val breezeState by viewModel.breeze.collectAsState()
    val pipelineState by viewModel.pipelineUi.collectAsState()
    val pipelineCount = pipelineState.pipelines.size
    // Breeze 语音那一行的副标题：如实写出"装没装 / 开没开"，不写"去配置"这种空话。
    val breezeSubtitle = when {
        breezeState.options.isEmpty() -> "未安装语音引擎插件"
        breezeState.ready -> "已启用 · " + (breezeState.active?.spec?.label ?: "")
        else -> breezeState.options.size.toString() + " 个引擎 · 未启用"
    }

    val activeThemeName = activeTheme?.packName?.takeIf { it.isNotBlank() }
    val activePresetName = viewModel.currentUiPreset()?.name
    val themeSubtitle = when {
        activeThemeName != null -> "当前：" + activeThemeName
        activePresetName != null -> "当前：" + activePresetName
        else -> "选择界面主题（四个预设不需要插件）"
    }

    // 下面是四个整页子页（主题 / API / 语音 / 关于）。它们都是 early-return 出来的，
    // 所以**必须在这之前**把"我在子页"这件事告诉根层 —— 否则底栏不会让位，
    // 整页看起来只换了一半。（showSettings 是根层渲染的，在那边单独算。）
    val meFullPage = showThemes || showApi || showVoice || showAbout || showProviders || showSettings
    androidx.compose.runtime.LaunchedEffect(meFullPage) {
        viewModel.setMeFullPage(meFullPage)
    }
    // 离开这个标签页时（切走 / 退出）必须复位，不然底栏会一直藏着。
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { viewModel.setMeFullPage(false) }
    }

    // 主题设置是整页替换，要自己拦返回键。
    if (showThemes) {
        androidx.activity.compose.BackHandler(enabled = true) { showThemes = false }
        ThemeScreen(viewModel, onBack = { showThemes = false })
        return
    }

    // 插件页已经**提到根层**（`AppViewModel.showPlugins`），因为生图页发现
    // "后端没装"时要能直接把人送过去。这里不再自己渲染一份 —— 两份会分叉，
    // 而且根层那份渲染在聊天页之上、能正确盖掉标签栏，局部这份不能。

    // 开放接口是整页替换。这里给的是**真页面**，不是把用户丢进设置栏 ——
    // 启动/停止服务、端口、局域网开关、请求日志、API Key 都在这页上，
    // 塞进底部升起的设置浮层会让它既太长又难找。
    if (showApi) {
        androidx.activity.compose.BackHandler(enabled = true) { showApi = false }
        ApiScreen(viewModel)
        return
    }

    // 云端接口也是整页：预设、编辑、测试、默认后端选择，塞进设置浮层会太长。
    if (showProviders) {
        androidx.activity.compose.BackHandler(enabled = true) { showProviders = false }
        ProviderScreen(viewModel, onClose = { showProviders = false })
        return
    }

    // 语音与音色同理：情绪、语速、音色、试听是一整页的内容。
    if (showVoice) {
        androidx.activity.compose.BackHandler(enabled = true) { showVoice = false }
        VoiceStudioScreen(viewModel) { showVoice = false }
        return
    }

    // 关于页是整页替换，不是浮层：开源声明会越来越长。
    // 系统返回键必须只退出这一层 —— 否则会直接退出应用（LocalMindRoot 的
    // BackHandler 只看 showSettings 和 inChat，不知道这里多了一层）。
    if (showAbout) {
        androidx.activity.compose.BackHandler(enabled = true) { showAbout = false }
        AboutScreen(
                        onBack = { showAbout = false },
                        onOpenDebug = { viewModel.openDebug() },
                    )
        return
    }

    editing?.let { p ->
        PersonaEditorSheet(viewModel = viewModel, persona = p, onDismiss = { editing = null })
    }

    val active = personas.firstOrNull { it.id == activePersonaId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
    ) {
        // 固定顶栏。
        //
        // 这一页原来只有滚动内容里的一个大标题，**顶部没有任何固定元素** ——
        // 往下滚之后状态栏直接压在正文上，看起来像少了一块挡板（对话页和模型页
        // 都有这条，只有这里没有）。
        IosNavigationBar(
            title = "我的",
            collapsed = true,
            topInset = statusBarTopPadding(),
            leading = {
                Icon(
                    AppIcons.Tune,
                    contentDescription = "设置",
                    tint = ios.blue,
                    modifier = Modifier
                        .size(23.dp)
                        .clickable { viewModel.openSettings(true) },
                )
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            ScreenHeader("我的")

        // ---------------------------------------------------------- 我
        //
        // 头像 / 昵称 / 性别**不再常显示**。
        //
        // 原来这三点一直摊在页面上：一个昵称输入框、一整个头像编辑器（16 个
        // emoji + 一排颜色 + 相册按钮）、一排性别药丸。它们加起来占掉首屏一大半，
        // 而一个人一辈子改不了几次 —— 更要命的是，"我的"这一页真正要用的
        // 是下面那些入口（人设、插件、主题、API），却被这三块挤到下面去了。
        //
        // 现在收成一行：点头像才展开编辑。信息（昵称、性别）仍然直接显示，
        // 所以不进编辑态也能看清自己设的是什么。
        IosSection(header = "我") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editingMe = !editingMe }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PersonaAvatar(
                    profile.avatarEmoji,
                    profile.avatarColor,
                    size = 58,
                    imagePath = profile.avatarImage,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        profile.nickname.ifBlank { "我" },
                        style = IosTheme.typography.title3,
                        color = ios.label,
                    )
                    Text(
                        profile.gender.ifBlank { "未填写性别" },
                        style = IosTheme.typography.caption1,
                        color = ios.secondaryLabel,
                    )
                }
                Text(
                    if (editingMe) "收起" else "编辑",
                    style = IosTheme.typography.footnote,
                    color = ios.blue,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (editingMe) AppIcons.ExpandLess else AppIcons.ExpandMore,
                    contentDescription = null,
                    tint = ios.tertiaryLabel,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(
                visible = editingMe,
                enter = expandVertically(Motion.gentle()) + fadeIn(Motion.smooth(180)),
                exit = shrinkVertically(Motion.smooth(200)) + fadeOut(Motion.smooth(140)),
            ) {
                Column {
                    Divider(ios.separator)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("昵称", style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                        Spacer(Modifier.height(6.dp))
                        Field(
                            value = profile.nickname,
                            placeholder = "你",
                            onChange = { v -> viewModel.updateUserProfile { it.copy(nickname = v) } },
                        )
                    }
                    Divider(ios.separator)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("头像", style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                        Spacer(Modifier.height(8.dp))
                        AvatarEditor(
                            emoji = profile.avatarEmoji,
                            colorIndex = profile.avatarColor,
                            imagePath = profile.avatarImage,
                            emojis = myAvatars,
                            pickerPrefix = "user",
                            onEmoji = { e -> viewModel.updateUserProfile { it.copy(avatarEmoji = e) } },
                            onColor = { c -> viewModel.updateUserProfile { it.copy(avatarColor = c) } },
                            onImage = { p -> viewModel.updateUserProfile { it.copy(avatarImage = p) } },
                            size = 64,
                        )
                    }
                    Divider(ios.separator)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text("性别", style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("女", "男", "不便透露").forEach { g ->
                                val sel = profile.gender == g
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (sel) ios.blue else ios.fillSecondary)
                                        .clickable {
                                            viewModel.updateUserProfile {
                                                it.copy(gender = if (sel) "" else g)
                                            }
                                        }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        g,
                                        style = IosTheme.typography.footnote,
                                        color = if (sel) ios.onAccent else ios.label,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------- 人设库
        IosSection(
            header = "默认人设",
            footer = "新建对话的起点。每个对话之后可以各改各的。",
        ) {
            if (active != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = active }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PersonaAvatar(
                        active.avatarEmoji,
                        active.avatarColor,
                        size = 54,
                        imagePath = active.avatarImage,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(active.name, style = IosTheme.typography.title3, color = ios.label)
                            Spacer(Modifier.width(6.dp))
                            IosPill(
                                if (active.mode.isCompanion) "当人" else "AI",
                                if (active.mode.isCompanion) ios.pink else ios.blue,
                            )
                        }
                        val sub = buildString {
                            append(active.tagline.ifBlank { "还没有人设描述" })
                            if (active.age.isNotBlank()) append(" · ").append(active.age).append("岁")
                            if (active.gender.isNotBlank()) append(" · ").append(active.gender)
                        }
                        Text(sub, style = IosTheme.typography.caption1, color = ios.secondaryLabel, maxLines = 1)
                    }
                    Icon(AppIcons.ChevronRight, null, tint = ios.tertiaryLabel, modifier = Modifier.size(18.dp))
                }
            }
            Divider(ios.separator)
            // 展开/收起人设列表。点开可以看到全部人设并切换当前默认。
            NavRow(
                AppIcons.Psychology,
                ios.pink,
                "切换 / 新建人设",
                if (showPersonas) "收起" else "共 ${personas.size} 个",
            ) { showPersonas = !showPersonas }
            if (showPersonas) {
                personas.forEach { p ->
                    Divider(ios.separator)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.selectPersona(p.id)
                                showPersonas = false
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PersonaAvatar(
                            p.avatarEmoji,
                            p.avatarColor,
                            size = 36,
                            imagePath = p.avatarImage,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = IosTheme.typography.body, color = ios.label)
                            Text(
                                p.tagline.ifBlank { if (p.mode.isCompanion) "当人" else "AI 助手" },
                                style = IosTheme.typography.caption1,
                                color = ios.secondaryLabel,
                                maxLines = 1,
                            )
                        }
                        if (p.id == activePersonaId) {
                            IosPill("当前", ios.blue)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "编辑",
                            style = IosTheme.typography.caption1,
                            color = ios.blue,
                            modifier = Modifier
                                .clip(RoundedCornerShape(7.dp))
                                .clickable { editing = p }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            Divider(ios.separator)
            NavRow(AppIcons.Add, ios.blue, "新建人设", "从空白开始写一个") {
                editing = PersonaPresets.newBlank()
            }
        }


        // ---------------------------------------------------------- 能力
        IosSection(
            header = "能力开关",
            footer = "默认全部关闭：不开就是完全离线，不会有任何网络请求。",
        ) {
            ToggleRow(AppIcons.Language, ios.blue, "允许 AI 上网", "联网搜索作为回答依据", caps.webSearch) {
                viewModel.updateCapabilities { c -> c.copy(webSearch = it) }
            }
            Divider(ios.separator)
            // 这两项**没有消费端**：置灰不可拨，而不是"能拨但没用"。
            // 灰色 + 一句原因，用户得到的是"还没做"；能拨的开关得到的是"这应用不靠谱"。
            ToggleRow(
                AppIcons.Bolt, ios.orange, "让 AI 成为 Agent",
                "这个版本还没做，暂时不能开启", caps.agentMode, enabled = false,
            ) { }
            Divider(ios.separator)
            ToggleRow(
                AppIcons.Security, ios.purple, "允许使用设备 Shell",
                "这个版本还没做，暂时不能开启", caps.shellAccess, enabled = false,
            ) { }
            Divider(ios.separator)
            ToggleRow(AppIcons.Security, ios.red, "每次操作前确认", "强烈建议保持开启", caps.confirmActions) {
                viewModel.updateCapabilities { c -> c.copy(confirmActions = it) }
            }
        }

        // ---------------------------------------------------------- 下载代理
        IosSection(
            header = "下载代理",
            // 一句话结论就够。原来那段解释 `{url}` 模板语法的是给维护者看的，
            // 而用户要判断的只有"开了有没有用"。
            footer = "对 GitHub 资源生效：语音音色、语音引擎、第三方源码。模型走 hf-mirror，不受这里影响。",
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("启用代理", style = IosTheme.typography.body, color = ios.label)
                    Text(
                        if (proxy.isUsable()) proxy.template else "未配置",
                        style = IosTheme.typography.caption1,
                        color = ios.secondaryLabel,
                        maxLines = 1,
                    )
                }
                IosSwitch(checked = proxy.enabled, onCheckedChange = {
                    proxy = proxy.copy(enabled = it)
                    viewModel.storage.saveProxySettings(proxy)
                    viewModel.toast(if (it) "代理已启用" else "代理已关闭")
                })
            }
            Divider(ios.separator)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("常用 GitHub 代理（点一下填入）", style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ProxySettings.PRESETS.size) { i ->
                        val (name, tpl) = ProxySettings.PRESETS[i]
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (proxy.template == tpl) ios.blue else ios.fillSecondary
                                )
                                .clickable {
                                    proxy = proxy.copy(template = tpl, enabled = true)
                                    viewModel.storage.saveProxySettings(proxy)
                                }
                                .padding(horizontal = 13.dp, vertical = 8.dp)
                        ) {
                            Text(
                                name,
                                style = IosTheme.typography.footnote,
                                color = if (proxy.template == tpl) ios.onAccent else ios.label,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("自定义模板", style = IosTheme.typography.caption1, color = ios.secondaryLabel)
                Spacer(Modifier.height(6.dp))
                Field(
                    value = proxy.template,
                    placeholder = "https://你的代理/{url}",
                    onChange = {
                        proxy = proxy.copy(template = it)
                        viewModel.storage.saveProxySettings(proxy)
                    },
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("优先走代理", style = IosTheme.typography.footnote, color = ios.label)
                        Text(
                            "关闭时先直连，失败再走代理",
                            style = IosTheme.typography.caption1,
                            color = ios.secondaryLabel,
                        )
                    }
                    IosSwitch(checked = proxy.preferProxy, onCheckedChange = {
                        proxy = proxy.copy(preferProxy = it)
                        viewModel.storage.saveProxySettings(proxy)
                    })
                }
            }
        }

        // ---------------------------------------------------------- 接口 / 语音 / 其它
        IosSection(header = "更多") {
            NavRow(
                AppIcons.Cloud,
                ios.blue,
                "本机 API 服务",
                if (apiState.running) "运行中 · 端口 ${api.port}" else "已停止",
            ) { showApi = true }
            Divider(ios.separator)
            // 云端接口 —— 对话 / 识图 / 生图 / 语音都能交给它。
            //
            // 这一行以前**不存在**，而数据层与模型选择器一直都在等它：
            // 结果就是「接云端 API」在界面上根本走不通。
            NavRow(
                AppIcons.Language,
                ios.indigo,
                "云端接口",
                if (providers.isEmpty()) "接 OpenAI / Claude / Gemini / 局域网" else "已添加 " + providers.size + " 个",
            ) { showProviders = true }
            Divider(ios.separator)
            NavRow(AppIcons.GraphicEq, ios.green, "语音与音色", "TTS 音色、情绪、语速") {
                showVoice = true
            }
            Divider(ios.separator)
            // Breeze TTS 2：手机跑不动的 3.47B 模型，只能在别的机器上跑、这里当客户端。
            // 单独一页而不是塞进「语音与音色」：它要地址和密钥，合成还有网络往返，
            // 和本机那两条链路的性质完全不同。
            NavRow(
                AppIcons.RecordVoiceOver,
                ios.orange,
                "Breeze 语音",
                breezeSubtitle,
            ) { viewModel.openBreeze(true) }
            Divider(ios.separator)
            // 分步流水线：装不下的大模型靠"拆开跑、再聚合"用起来。
            NavRow(
                AppIcons.Bolt,
                ios.yellow,
                "分步流水线",
                "${pipelineCount} 条 · 拆解 → 逐步执行 → 聚合",
            ) { viewModel.selectTab(Tab.PIPELINE) }
            Divider(ios.separator)
            // 原来这里有一个「模型分工」入口，指向设置页里的全局路由表。
            // 已经撤掉：模型、提示词、采样都是**按对话**设置的，入口在
            // 每个对话自己的设置页（聊天页右上角那个滑块图标）里。
            // 「我的」这一页只放整台设备共用的东西。
            NavRow(AppIcons.Refresh, ios.gray, "重新运行引导", "重新填写昵称、头像与模式") {
                viewModel.restartOnboarding()
            }
            Divider(ios.separator)
            // 主题设置：**始终显示**（内置预设不需要插件，见上面 themeSubtitle 的说明）。
            //
            // 为什么放在这儿而不是设置里：主题是"这台设备看起来什么样"，
            // 属于个人偏好，和「我的」这一栏的性质一致。
            NavRow(
                AppIcons.Brush,
                ios.pink,
                "主题",
                themeSubtitle,
            ) { showThemes = true }
            Divider(ios.separator)
            NavRow(
                AppIcons.Extension,
                ios.purple,
                "插件",
                "先试新功能，确认可用再做进主程序",
            ) { viewModel.openPlugins(true) }
            Divider(ios.separator)
            NavRow(
                AppIcons.Info,
                ios.indigo,
                "关于 LocalMind",
                "作者 · 捐献感谢榜 · 开源声明",
            ) { showAbout = true }
        }

            Spacer(Modifier.height(30.dp))
        }
    }
}

private val myAvatars = listOf(
    "😀", "😎", "🙂", "🤓", "🥰", "😼", "🐱", "🐶",
    "🦊", "🐼", "🐧", "🌿", "🌸", "🔥", "⚡", "🌙",
)

@Composable
internal fun ScreenHeader(title: String) {
    val ios = IosTheme.palette
    Text(
        title,
        style = IosTheme.typography.largeTitle,
        color = ios.label,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
    )
}

@Composable
internal fun Divider(color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(color)
    )
}

@Composable
private fun Field(value: String, placeholder: String, onChange: (String) -> Unit) {
    val ios = IosTheme.palette
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(ios.fillSecondary)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = IosTheme.typography.footnote, color = ios.tertiaryLabel)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = LocalTextStyle.current.merge(IosTheme.typography.footnote).copy(color = ios.label),
            cursorBrush = SolidColor(ios.blue),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val ios = IosTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = IosTheme.typography.body, color = ios.label, modifier = Modifier.weight(1f))
        Text(subtitle, style = IosTheme.typography.caption1, color = ios.secondaryLabel)
        Spacer(Modifier.width(4.dp))
        Icon(AppIcons.ChevronRight, null, tint = ios.tertiaryLabel, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    /**
     * 没有消费端的开关一律传 false：**能拨的开关就是在承诺一个行为**。
     *
     * 以前 Agent / Shell 那两个不但能拨，还会把值写进设置 —— 用户拨完
     * 发现什么也没发生，得到的结论不是「这个功能还没做」，而是「这应用不靠谱」。
     * 置灰 + 说明原因，才是对「还没接线」这四个字的正确表达。
     */
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val ios = IosTheme.palette
    val dim = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint.copy(alpha = dim), modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = IosTheme.typography.body, color = ios.label.copy(alpha = dim))
            Text(subtitle, style = IosTheme.typography.caption1, color = ios.secondaryLabel.copy(alpha = dim))
        }
        IosSwitch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

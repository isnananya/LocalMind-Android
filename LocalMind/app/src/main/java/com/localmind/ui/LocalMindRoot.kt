package com.localmind.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localmind.ui.components.LiquidGlassTabBar
import com.localmind.ui.components.statusBarTopPadding
import com.localmind.ui.design.AuroraBackground
import com.localmind.ui.design.ChatListPage
import com.localmind.ui.design.MePage
import com.localmind.ui.design.ModelsPage
import com.localmind.ui.design.PluginScreenPage
import com.localmind.ui.motion.Motion
import com.localmind.ui.screen.ChatScreen
import com.localmind.ui.screen.ConversationDetailScreen
import com.localmind.ui.screen.DebugScreen
import com.localmind.ui.screen.ImageScreen
import com.localmind.ui.screen.LayoutHomeScreen
import com.localmind.ui.screen.OnboardingScreen
import com.localmind.ui.screen.PluginDevDocsScreen
import com.localmind.ui.screen.PluginsScreen
import com.localmind.ui.screen.SettingsScreen
import com.localmind.ui.screen.StatusScreen
import com.localmind.ui.screen.VoiceStudioScreen
import com.localmind.ui.theme.IosTheme
import com.example.liquiddock.*
import com.localmind.ui.icons.AppIcons
import androidx.compose.ui.unit.sp

private val dockIconOf = mapOf(
    Tab.HOME to AppIcons.AutoAwesome,
    Tab.CHAT to AppIcons.ChatBubble,
    Tab.MODELS to AppIcons.Memory,
    Tab.ME to AppIcons.Person,
    Tab.VOICE to AppIcons.Mic,
    Tab.BREEZE to AppIcons.RecordVoiceOver,
    Tab.PLUGINS to AppIcons.Extension,
    Tab.IMAGE to AppIcons.Image,
)

/**
 * 把现有主题令牌映射成 liquiddock 的 [DockConfig]，让「外观」页里的底栏参数
 * 对两种底栏实现都生效。这样融合后，用户既能用回自定义液态底栏，
 * 也保留当前的液态玻璃底栏，互不丢失。
 */
private fun dockConfigFromTokens(tokens: Map<String, String>?, isDark: Boolean): DockConfig {
    if (tokens == null) return DockConfig()
    fun num(k: String, d: Float) = tokens[k]?.toFloatOrNull() ?: d
    fun bool(k: String, d: Boolean) = tokens[k]?.toBooleanStrictOrNull() ?: d
    fun str(k: String, d: String) = tokens[k] ?: d
    val indicator = when (str("tabBarIndicator", "capsule").lowercase()) {
        "underline" -> DockIndicator.Underline
        "dot" -> DockIndicator.Dot
        "none" -> DockIndicator.None
        else -> DockIndicator.Capsule
    }
    val shape = when (str("tabBarShape", "capsule").lowercase()) {
        "rounded" -> DockShape.Rounded
        "square" -> DockShape.Square
        else -> DockShape.Capsule
    }
    val width = when (str("tabBarWidth", "compact").lowercase()) {
        "full" -> DockWidth.Full
        else -> DockWidth.Compact
    }
    val style = when (str("tabStyle", "glass").lowercase()) {
        "plain" -> DockStyle.Plain
        else -> DockStyle.Glass
    }
    return DockConfig(
        style = style,
        indicator = indicator,
        shape = shape,
        width = width,
        colors = if (isDark) DockColors.Dark else DockColors.Light,
        height = num("tabBarHeight", 60f).dp,
        slotWidth = num("glassBarSlotWidth", 76f).dp,
        sideMargin = num("tabBarMargin", 18f).dp,
        bottomGap = num("tabBarLift", 12f).dp,
        roundedCorner = 24.dp,
        blurRadius = num("tabBarBlur", 10f),
        fillAlpha = 0.40f,
        showOutline = bool("glassBarOutline", true),
        highlight = num("glassBarHighlight", 0.5f),
        liquid = bool("glassBarLiquid", true),
        bottomVeil = bool("glassBarBottomBlur", false),
        iconOnly = bool("glassBarIconOnly", false),
        iconSize = num("tabIconSize", 23f).dp,
        labelSize = num("glassBarLabelSize", 11f).sp,
    )
}

/**
 * 应用外壳。
 *
 * 这里只做三件事：决定**显示哪一页**、决定**底栏在不在**、决定**怎么过渡**。
 * 每一页自己的排版在 design/ 下，不在这里堆。
 */
@Composable
fun LocalMindRoot(
    sharedText: String? = null,
    viewModel: AppViewModel = viewModel(),
    hideTabBar: Boolean = false,
) {
    val ios = IosTheme.palette
    val tab by viewModel.tab.collectAsState()
    // 朗读状态提到根层：停止按钮要覆盖所有页面（聊天页朗读、语音页试听都算）。
    val speaking by viewModel.speaking.collectAsState()
    val showSettings by viewModel.showSettings.collectAsState()
    val showImageStudio by viewModel.showImageStudio.collectAsState()
    val showVoiceScreen by viewModel.showVoiceScreen.collectAsState()
    val showBreeze by viewModel.showBreeze.collectAsState()
    val showConversationDetail by viewModel.showConversationDetail.collectAsState()
    val activeConversationId by viewModel.activeId.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val toast by viewModel.toast.collectAsState()
    val inChat by viewModel.inChat.collectAsState()
    val profile by viewModel.userProfile.collectAsState()
    val showPlugins by viewModel.showPlugins.collectAsState()
    val showDebug by viewModel.showDebug.collectAsState()
    val showStatus by viewModel.showStatus.collectAsState()
    val meFullPage by viewModel.meFullPage.collectAsState()
    val showPluginDocs by viewModel.showPluginDocs.collectAsState()

    // 跨标签页保留滚动位置：AnimatedContent 会销毁离开的页面，
    // 不把 LazyListState 提到根层的话，切回来列表就跳回顶部。
    val scrollStates = com.localmind.ui.motion.rememberScrollStateHolder()
    val uiTheme = com.localmind.ui.theme.LocalUiTheme.current
    val uiTokens = uiTheme.tokens
    // 动效档位要在第一帧之前生效，否则首屏沿用上一档曲线。
    // 取**解析后**的令牌（LocalUiTokens 已合并深浅色两套），configure 幂等，
    // 内部先比对再写，所以每帧调一次也不心疼。
    com.localmind.ui.motion.Motion.configure(com.localmind.ui.theme.LocalUiTokens.current)
    val tabIds = uiTheme.tabs
    val startTabId = uiTheme.startTab
    val uiLayout by viewModel.uiLayout.collectAsState()

    val tabs = (uiLayout?.tabs?.map { it.target } ?: tabIds).mapNotNull { Tab.fromId(it) }
        .distinct()
        .ifEmpty { listOf(Tab.CHAT, Tab.MODELS, Tab.ME) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (startTabId.isNotBlank()) viewModel.applyStartTab(startTabId)
    }

    if (!profile.onboarded) {
        val step = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
        androidx.activity.compose.BackHandler(enabled = step.intValue > 0) { step.intValue-- }
        androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.setBarVisible(false) }
        AuroraBackground {
            OnboardingScreen(viewModel, step = step.intValue, onStepChange = { step.intValue = it })
        }
        ToastOverlay(toast, bottomPadding = 24.dp)
        return
    }

    val canGoBack = showSettings || showImageStudio || showVoiceScreen || showBreeze || showPlugins ||
        showPluginDocs || showConversationDetail || showDebug || showStatus || (tab == Tab.CHAT && inChat) ||
        (tab != Tab.CHAT && tab != Tab.MODELS && tab != Tab.ME && tab != Tab.HOME)
    val goBack: () -> Unit = {
        when {
            showDebug -> viewModel.openDebug(false)
            showStatus -> viewModel.openStatus(false)
            showSettings -> viewModel.openSettings(false)
            showConversationDetail -> viewModel.openConversationDetail(false)
            showBreeze -> viewModel.openBreeze(false)
            showVoiceScreen -> viewModel.openVoiceScreen(false)
            showPluginDocs -> viewModel.openPluginDocs(false)
            showPlugins -> viewModel.openPlugins(false)
            showImageStudio -> viewModel.openImageStudio(false)
            tabs.size > 1 && tab != tabs.first() && !inChat -> viewModel.selectTab(tabs.first())
            else -> viewModel.leaveConversation()
        }
    }
    androidx.activity.compose.BackHandler(enabled = canGoBack) { goBack() }

    // 预返回：系统手势拖动时按 progress 缩放/位移，松手才真正返回。
    var backProgress by remember { mutableFloatStateOf(0f) }
    androidx.activity.compose.PredictiveBackHandler(enabled = canGoBack) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            backProgress = 0f
            goBack()
        } catch (e: kotlinx.coroutines.CancellationException) {
            backProgress = 0f
            throw e
        }
    }

    val tabStyleTop = uiTokens["tabStyle"] ?: "glass"
    val canHideTop = tabs.size > 1
    val subPageTop = showVoiceScreen || showBreeze || showConversationDetail || showPlugins ||
        showPluginDocs || showDebug || showStatus || showSettings || meFullPage ||
        showImageStudio
    val immersiveTop = tab == Tab.CHAT && inChat
    val barVisibleTop = !subPageTop && !immersiveTop && !(tabStyleTop == "hidden" && canHideTop)

    Box(Modifier.fillMaxSize()) {
        AuroraBackground {
            Box(Modifier.fillMaxSize()) {
                val subPage = showVoiceScreen || showBreeze || showConversationDetail || showPlugins ||
                    showPluginDocs || showDebug || showStatus || showSettings || meFullPage ||
                    showImageStudio
                val immersiveChat = tab == Tab.CHAT && inChat

                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (backProgress > 0f) {
                                translationX = backProgress * size.width * 0.30f
                                scaleX = 1f - 0.05f * backProgress
                                scaleY = 1f - 0.05f * backProgress
                                alpha = 1f - 0.18f * backProgress
                            }
                        }
                ) {
                    AnimatedContent(
                        targetState = when {
                            showDebug -> "debug"
                            showStatus -> "status"
                            showConversationDetail -> "convdetail"
                            showBreeze -> "breeze"
                            showVoiceScreen -> "voice"
                            showPluginDocs -> "plugindocs"
                            showPlugins -> "plugins"
                            showImageStudio -> "image"
                            tab == Tab.CHAT -> "chat"
                            tab == Tab.MODELS -> "models"
                            tab == Tab.HOME -> "home"
                            tab == Tab.VOICE -> "voice"
                            tab == Tab.BREEZE -> "breeze"
                            tab == Tab.PLUGINS -> "plugins"
                            tab == Tab.IMAGE -> "image"
                            tab == Tab.PIPELINE -> "pipeline"
                            else -> "me"
                        },
                        transitionSpec = {
                            // 主题可改页面切换方式（令牌 pageTransition）。
                            // 放在 transitionSpec 内部读：切主题后这里会跟着重组。
                            when (uiTokens["pageTransition"] ?: "slide") {
                                "none" -> fadeIn(Motion.smooth(0)) togetherWith fadeOut(Motion.smooth(0))
                                "fade" -> fadeIn(Motion.smooth(200)) togetherWith fadeOut(Motion.smooth(150))
                                "scale" -> (
                                    fadeIn(Motion.smooth(200)) +
                                        androidx.compose.animation.scaleIn(
                                            animationSpec = Motion.smooth(240),
                                            initialScale = 0.94f,
                                        )
                                    ) togetherWith (
                                    fadeOut(Motion.smooth(150)) +
                                        androidx.compose.animation.scaleOut(
                                            animationSpec = Motion.smooth(170),
                                            targetScale = 1.02f,
                                        )
                                    )
                                else -> {
                            val isSub = { s: String ->
                                s in setOf("voice", "image", "convdetail", "plugins", "plugindocs", "debug", "status")
                            }
                            if (isSub(targetState) || isSub(initialState)) {
                                val forward = isSub(targetState)
                                val dir = if (forward) 1 else -1
                                (
                                    slideInHorizontally(
                                        animationSpec = Motion.gentle(),
                                        initialOffsetX = { (it * 0.22f * dir).toInt() },
                                    ) + fadeIn(Motion.smooth(180))
                                    ) togetherWith (
                                    slideOutHorizontally(
                                        animationSpec = Motion.gentle(),
                                        targetOffsetX = { (-it * 0.10f * dir).toInt() },
                                    ) + fadeOut(Motion.smooth(140))
                                    )
                            } else {
                                (
                                    fadeIn(Motion.smooth(200)) + slideInVertically(
                                        animationSpec = Motion.gentle(),
                                        initialOffsetY = { (it * 0.03f).toInt() },
                                    )
                                    ) togetherWith fadeOut(Motion.smooth(120))
                            }
                                }
                            }
                        },
                        label = "rootPage",
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val layout = uiLayout
                        val overrideId = layout?.overrides?.get(page)
                        when {
                            overrideId != null && overrideId.startsWith("code:") -> {
                                com.localmind.ui.design.PluginCodeScreen(
                                    viewModel = viewModel,
                                    pluginId = overrideId.removePrefix("code:"),
                                )
                            }
                            overrideId != null && overrideId.startsWith("web:") -> {
                                com.localmind.ui.design.PluginWebScreen(
                                    viewModel = viewModel,
                                    pluginId = overrideId.removePrefix("web:"),
                                )
                            }
                            layout != null && overrideId != null -> {
                                PluginScreenPage(
                                    viewModel = viewModel,
                                    layout = layout,
                                    screenId = overrideId,
                                    fallbackTitle = page,
                                )
                            }
                            else -> when (page) {
                            "convdetail" -> {
                                val conv = conversations.firstOrNull { it.id == activeConversationId }
                                if (conv != null) {
                                    ConversationDetailScreen(viewModel, conv) {
                                        viewModel.openConversationDetail(false)
                                    }
                                }
                            }
                            "debug" -> DebugScreen(viewModel) { viewModel.openDebug(false) }
                            "status" -> StatusScreen(viewModel) { viewModel.openStatus(false) }
                            "voice" -> VoiceStudioScreen(
                                viewModel = viewModel,
                                onClose = { viewModel.openVoiceScreen(false) },
                            )
                            "plugins" -> PluginsScreen(viewModel) { viewModel.openPlugins(false) }
                            "plugindocs" -> PluginDevDocsScreen { viewModel.openPluginDocs(false) }
                            "image" -> ImageScreen(viewModel) { viewModel.openImageStudio(false) }
                            "home" -> LayoutHomeScreen(viewModel, uiLayout)
                            "breeze" -> com.localmind.ui.screen.BreezeScreen(viewModel)
                            "pipeline" -> com.localmind.ui.screen.PipelineScreen(viewModel)
                            "chat" -> {
                                if (inChat) ChatScreen(viewModel, sharedText)
                                else ChatListPage(viewModel, scrollStates)
                            }
                            "models" -> ModelsPage(viewModel, scrollStates)
                            else -> MePage(viewModel, scrollStates)
                        }
                    }
                }
            }

                val tabStyle = tabStyleTop
                val showLabels = (uiTokens["showTabBarLabels"] ?: "true").toBooleanStrictOrNull() ?: true
                val canHideTabBar = canHideTop
                val barVisible = barVisibleTop
                androidx.compose.runtime.LaunchedEffect(barVisible, hideTabBar) {
                    viewModel.setBarVisible(barVisible)
                }
                AnimatedVisibility(
                    visible = barVisible && !hideTabBar,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn(Motion.smooth(200)) + slideInVertically(
                        animationSpec = Motion.gentle(),
                        initialOffsetY = { it / 2 },
                    ),
                    exit = fadeOut(Motion.smooth(140)) + slideOutVertically(
                        animationSpec = Motion.smooth(180),
                        targetOffsetY = { it / 2 },
                    ),
                ) {
                    val labels = uiLayout?.tabs
                        ?.mapNotNull { spec ->
                            val t = Tab.fromId(spec.target) ?: return@mapNotNull null
                            spec.label.takeIf { it.isNotBlank() }?.let { t to it }
                        }
                        ?.toMap()
                        .orEmpty()
                    val icons = uiLayout?.tabs
                        ?.mapNotNull { spec ->
                            val t = Tab.fromId(spec.target) ?: return@mapNotNull null
                            com.localmind.ui.icons.LayoutIcons.of(spec.icon)?.let { t to it }
                        }
                        ?.toMap()
                        .orEmpty()
                    val dockImplMode = uiTokens["dockImpl"] ?: "liquidTabBar"
                    val dockItems = tabs.mapNotNull { t ->
                        val icon = (icons[t] ?: dockIconOf[t]) ?: return@mapNotNull null
                        DockItem(t.name.lowercase(), labels[t] ?: t.title, icon)
                    }
                    if (dockImplMode == "liquidDock" && dockItems.isNotEmpty()) {
                        LiquidDock(
                            items = dockItems,
                            selectedId = tab.name.lowercase(),
                            onSelect = { id -> Tab.fromId(id)?.let { viewModel.selectTab(it) } },
                            bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                            config = dockConfigFromTokens(uiTokens, IosTheme.palette.isDark),
                        )
                    } else {
                        LiquidGlassTabBar(
                            selected = tab,
                            onSelect = { viewModel.selectTab(it) },
                            bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                            items = tabs,
                            labels = labels,
                            icons = icons,
                            showLabels = showLabels,
                            style = tabStyle,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically(animationSpec = Motion.gentle(), initialOffsetY = { it }) + fadeIn(Motion.smooth(160)),
            exit = slideOutVertically(animationSpec = Motion.smooth(220), targetOffsetY = { it }) + fadeOut(Motion.smooth(140)),
        ) {
            SettingsScreen(viewModel) { viewModel.openSettings(false) }
        }

        ToastOverlay(
            toast = toast,
            bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp,
        )

        // -------------------------------------------------- 公告弹窗
        //
        // 只有「远程公告 id 与本机已读不同」才会出现（见 RemoteDataService）。
        // 确认后记为已读，下次启动不再弹；公告正文常驻在关于页。
        val pendingAnnouncement by viewModel.pendingAnnouncement.collectAsState()
        pendingAnnouncement?.let { ann ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissAnnouncementRead(ann.id) },
                title = { Text(ann.title, style = IosTheme.typography.headline) },
                text = {
                    Text(
                        ann.content + ann.publishedAt.takeIf { it.isNotBlank() }
                            ?.let { "\n\n—— $it" }.orEmpty(),
                        style = IosTheme.typography.subheadline,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissAnnouncementRead(ann.id) }) { Text("知道了") }
                },
            )
        }

        // -------------------------------------------------- 更新弹窗（应用内下载）
        //
        // 1.0.11 起不再把用户甩去浏览器：这里自己下 APK、自己拉起系统安装器。
        // 下载中给进度和速度，失败给重试；地址先过 GitHubMirror 展开加速源。
        val pendingUpdate by viewModel.pendingUpdate.collectAsState()
        val updateChannel by viewModel.updateChannel.collectAsState()
        val apkState by viewModel.apkUpdater.state.collectAsState()

        // 下载 + 校验通过 → 直接拉起安装器。
        // 没给「安装未知应用」权限时会跳到系统设置页，用户授权回来再点一次即可。
        androidx.compose.runtime.LaunchedEffect(apkState) {
            val ready = apkState as? com.localmind.service.ApkUpdater.State.Ready
            if (ready != null) viewModel.apkUpdater.install(ready)
        }

        pendingUpdate?.let { rel ->
            UpdateDialog(
                rel = rel,
                channel = updateChannel,
                state = apkState,
                onDownload = {
                    viewModel.reportUpdateDecision("accept", rel)
                    viewModel.apkUpdater.download(rel.latestVersion, listOf(rel.downloadUrl))
                },
                onCancel = { viewModel.apkUpdater.cancel() },
                onLater = {
                    viewModel.apkUpdater.reset()
                    viewModel.reportUpdateDecision("reject", rel)
                    viewModel.consumeUpdate()
                },
            )
        }

        // -------------------------------------------------- 崩溃上报弹窗
        //
        // 崩溃只落盘。是否上传由用户当场决定 —— 没有「默认帮我传」这回事。
        val pendingCrash by viewModel.pendingCrash.collectAsState()
        pendingCrash?.let { crash ->
            val noteState = androidx.compose.runtime.remember(crash.at) {
                androidx.compose.runtime.mutableStateOf("")
            }
            AlertDialog(
                onDismissRequest = { viewModel.dismissCrash() },
                title = { Text("上次崩溃了", style = IosTheme.typography.headline) },
                text = {
                    androidx.compose.foundation.layout.Column {
                        Text(
                            "上次运行时异常退出（${crash.timeText}）。把崩溃日志发给我能帮我更快定位问题。" +
                                "\n\n日志里只有堆栈和机型信息，不含任何个人内容。",
                            style = IosTheme.typography.subheadline,
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = noteState.value,
                            onValueChange = { noteState.value = it },
                            singleLine = true,
                            label = { Text("当时在做什么？(可选)") },
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.submitCrash(crash, noteState.value) }) {
                        Text("上传日志", color = ios.pink)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissCrash() }) { Text("不用了") }
                },
            )
        }

        // ---- 朗读中的全局停止按钮 ----
        //
        // 自动朗读是把整段回复拆成多句排队播的，长回复能念好几分钟，而界面上
        // 原来**没有任何停止入口**（stopSpeaking 只在内部被调用）——
        // 用户要么等它念完，要么杀进程。挂在根层而不是聊天页：聊天朗读、
        // 语音页试听、外部 API 播报都能一键停，也不用每个页面各加一个。
        if (speaking) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusBarTopPadding() + 8.dp)
                    .shadow(10.dp, RoundedCornerShape(999.dp))
                    .clip(RoundedCornerShape(999.dp))
                    .background(ios.secondaryGroupedBackground.copy(alpha = 0.97f))
                    .border(0.5.dp, ios.separator, RoundedCornerShape(999.dp))
                    .clickable { viewModel.stopSpeaking() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Stop, null, Modifier.size(13.dp), tint = ios.red)
                Spacer(Modifier.width(6.dp))
                Text(
                    "停止朗读",
                    color = ios.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun ToastOverlay(toast: String?, bottomPadding: androidx.compose.ui.unit.Dp) {
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn(tween(120)) + slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(180)),
        exit = fadeOut(tween(200)) + slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(200)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            val message = toast
            if (message != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomPadding)
                        .padding(horizontal = 32.dp)
                        .shadow(12.dp, RoundedCornerShape(999.dp))
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            com.localmind.ui.theme.LocalUiTokens.current["overlay"]
                                ?.let { runCatching { com.localmind.ui.theme.parseHexColor(it) }.getOrNull() }
                                ?: Color(0xE6000000)
                        )
                        .padding(horizontal = 18.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = message,
                        style = IosTheme.typography.subheadline,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * 更新弹窗：一个弹窗走完「看说明 → 下载 → 装」。
 *
 * 之前点「去更新」是 `ACTION_VIEW` 跳浏览器，用户还得自己找下载按钮、等浏览器
 * 下完、从通知栏点开。任何一步没走对就是"更新不了"。现在下载在这里做，
 * 完成直接拉系统安装器 —— 用户只剩下最后那个「安装」确认。
 *
 * 下载中**不给「稍后」**：正在下 60 MB 的包，关掉弹窗却继续下载是最糟的组合。
 * 想停就点「取消」。
 */
@Composable
private fun UpdateDialog(
    rel: com.localmind.service.RemoteDataService.ReleaseInfo,
    channel: String,
    state: com.localmind.service.ApkUpdater.State,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onLater: () -> Unit,
) {
    val ios = IosTheme.palette
    val downloading = state as? com.localmind.service.ApkUpdater.State.Downloading
    val failed = state as? com.localmind.service.ApkUpdater.State.Failed
    val ready = state as? com.localmind.service.ApkUpdater.State.Ready
    val busy = downloading != null || ready != null

    AlertDialog(
        onDismissRequest = { if (!rel.forceUpdate && !busy) onLater() },
        title = {
            Text(
                (if (channel == "beta") "内测版本 " else "发现新版本 ") + rel.latestVersion,
                style = IosTheme.typography.headline,
            )
        },
        text = {
            Column {
                if (downloading != null) {
                    val done = if (downloading.percent >= 0) {
                        "已下载 ${downloading.percent}%"
                    } else {
                        "已下载 ${downloading.received / 1024 / 1024} MB"
                    }
                    Text(done, style = IosTheme.typography.subheadline, color = ios.label)
                    Spacer(Modifier.size(8.dp))
                    // 自己画进度条：material3 的 LinearProgressIndicator 在不同
                    // 版本上参数名不一样（progress / progress = {}），离线构建时
                    // 升不了库，直接用 Box 更稳。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(ios.fillSecondary),
                    ) {
                        if (downloading.percent >= 0) {
                            Box(
                                Modifier
                                    .fillMaxWidth(downloading.percent / 100f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(ios.blue),
                            )
                        }
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(
                        buildString {
                            if (downloading.bytesPerSecond > 0) {
                                append(com.localmind.service.DownloadSources.speedText(downloading.bytesPerSecond))
                            }
                            if (downloading.total > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("${downloading.total / 1024 / 1024} MB")
                            }
                            if (downloading.sourceCount > 1) {
                                if (isNotEmpty()) append(" · ")
                                append("源 ${downloading.sourceIndex}/${downloading.sourceCount}")
                            }
                        },
                        style = IosTheme.typography.caption1,
                        color = ios.secondaryLabel,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "下载完会直接打开安装器，不用再去浏览器。",
                        style = IosTheme.typography.caption1,
                        color = ios.tertiaryLabel,
                    )
                } else {
                    Text(
                        (if (channel == "beta") "这是测试版，可能不稳定，欢迎反馈问题。\n\n" else "") +
                            (rel.changelog.takeIf { it.isNotBlank() } ?: "暂无更新说明") +
                            "\n\n当前版本：" + com.localmind.BuildConfig.VERSION_NAME,
                        style = IosTheme.typography.subheadline,
                    )
                    if (ready != null) {
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "安装包已就绪，正在打开系统安装器…",
                            style = IosTheme.typography.caption1,
                            color = ios.green,
                        )
                    }
                    if (failed != null) {
                        Spacer(Modifier.size(10.dp))
                        Text(
                            failed.message,
                            style = IosTheme.typography.caption1,
                            color = ios.red,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                downloading != null -> TextButton(onClick = onCancel) { Text("取消") }
                ready != null -> TextButton(onClick = onDownload) { Text("重新下载") }
                failed != null -> TextButton(onClick = onDownload) { Text("重试", color = ios.pink) }
                else -> TextButton(onClick = onDownload) { Text("立即更新", color = ios.pink) }
            }
        },
        dismissButton = {
            if (!rel.forceUpdate && !busy) {
                TextButton(onClick = onLater) { Text("稍后") }
            }
        },
    )
}

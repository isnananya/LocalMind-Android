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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
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
import com.localmind.ui.screen.VoiceScreen
import com.localmind.ui.theme.IosTheme

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

    val uiTheme = com.localmind.ui.theme.LocalUiTheme.current
    val uiTokens = uiTheme.tokens
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
                            "voice" -> VoiceScreen(viewModel)
                            "plugins" -> PluginsScreen(viewModel) { viewModel.openPlugins(false) }
                            "plugindocs" -> PluginDevDocsScreen { viewModel.openPluginDocs(false) }
                            "image" -> ImageScreen(viewModel) { viewModel.openImageStudio(false) }
                            "home" -> LayoutHomeScreen(viewModel, uiLayout)
                            "breeze" -> com.localmind.ui.screen.BreezeScreen(viewModel)
                            "pipeline" -> com.localmind.ui.screen.PipelineScreen(viewModel)
                            "chat" -> {
                                if (inChat) ChatScreen(viewModel, sharedText) else ChatListPage(viewModel)
                            }
                            "models" -> ModelsPage(viewModel)
                            else -> MePage(viewModel)
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
                    LiquidGlassTabBar(
                        selected = tab,
                        onSelect = { viewModel.selectTab(it) },
                        bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                        items = tabs,
                        labels = uiLayout?.tabs
                            ?.mapNotNull { spec ->
                                val t = Tab.fromId(spec.target) ?: return@mapNotNull null
                                spec.label.takeIf { it.isNotBlank() }?.let { t to it }
                            }
                            ?.toMap()
                            .orEmpty(),
                        icons = uiLayout?.tabs
                            ?.mapNotNull { spec ->
                                val t = Tab.fromId(spec.target) ?: return@mapNotNull null
                                com.localmind.ui.icons.LayoutIcons.of(spec.icon)?.let { t to it }
                            }
                            ?.toMap()
                            .orEmpty(),
                        showLabels = showLabels,
                        style = tabStyle,
                    )
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

        // -------------------------------------------------- 更新弹窗
        //
        // 后台 release.json 的 versionCode 高于本机才弹；forceUpdate 时不给「稍后」。
        val context = androidx.compose.ui.platform.LocalContext.current
        val pendingUpdate by viewModel.pendingUpdate.collectAsState()
        pendingUpdate?.let { rel ->
            AlertDialog(
                onDismissRequest = { if (!rel.forceUpdate) viewModel.consumeUpdate() },
                title = {
                    Text(
                        "发现新版本 " + rel.latestVersion,
                        style = IosTheme.typography.headline,
                    )
                },
                text = {
                    Text(
                        (rel.changelog.takeIf { it.isNotBlank() } ?: "暂无更新说明") +
                            "\n\n当前版本：" + com.localmind.BuildConfig.VERSION_NAME,
                        style = IosTheme.typography.subheadline,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(
                                        rel.downloadUrl.takeIf { it.isNotBlank() }
                                            ?: "https://github.com/isnananya/LocalMind-Android/releases",
                                    ),
                                ),
                            )
                        }
                        viewModel.consumeUpdate()
                    }) { Text("去更新", color = ios.pink) }
                },
                dismissButton = {
                    if (!rel.forceUpdate) {
                        TextButton(onClick = { viewModel.consumeUpdate() }) { Text("稍后") }
                    }
                },
            )
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

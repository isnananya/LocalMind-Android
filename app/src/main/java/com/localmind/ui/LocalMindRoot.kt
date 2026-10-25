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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localmind.ui.components.LiquidGlassTabBar
import com.localmind.ui.motion.Motion
import com.localmind.ui.screen.ChatScreen
import com.localmind.ui.screen.ConversationDetailScreen
import com.localmind.ui.screen.DebugScreen
import com.localmind.ui.screen.ImageScreen
import com.localmind.ui.screen.ModelsScreen
import com.localmind.ui.screen.OnboardingScreen
import com.localmind.ui.screen.PluginDevDocsScreen
import com.localmind.ui.screen.PluginsScreen
import com.localmind.ui.screen.ProfileScreen
import com.localmind.ui.screen.SettingsScreen
import com.localmind.ui.screen.VoiceScreen
import com.localmind.ui.theme.IosTheme

@Composable
fun LocalMindRoot(
    sharedText: String? = null,
    viewModel: AppViewModel = viewModel(),
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
    val meFullPage by viewModel.meFullPage.collectAsState()
    val showPluginDocs by viewModel.showPluginDocs.collectAsState()
    // 界面主题：令牌、显示哪几个标签、初始标签、标题。
    //
    // 从 CompositionLocal 读而不是从 ViewModel 读：MainActivity 已经把
    // 同一份 [ThemeState] 注入给整棵树了，再收集一遍 StateFlow 只会多一处
    // 可能分叉的地方（这正是以前的老毛病）。
    val uiTheme = com.localmind.ui.theme.LocalUiTheme.current
    val uiTokens = uiTheme.tokens
    val tabIds = uiTheme.tabs
    val startTabId = uiTheme.startTab
    // 界面**布局**插件（结构：底栏有哪几个入口、首页由哪些块组成）。
    // 和主题分开是因为它俩改的不是同一件事，优先级也不同（见 refreshUiTheme）。
    val uiLayout by viewModel.uiLayout.collectAsState()

    /**
     * 底栏的入口。
     *
     * 布局插件给了 `tabs` 就用它（可以指向 chat / models / me / home / voice /
     * breeze / plugins / image 任意页），否则用主题插件声明的那几个 id。
     * 认不出的 id 直接丢掉 —— 一个指向不存在页面的入口会变成一个空白格。
     */
    val tabs = (uiLayout?.tabs?.map { it.target } ?: tabIds).mapNotNull { Tab.fromId(it) }
        .distinct()
        .ifEmpty { listOf(Tab.CHAT, Tab.MODELS, Tab.ME) }

    // `startTab` 只在**第一次组合**时应用一次。之后用户自己切了标签，
    // 不该被它拽回去 —— 所以键是 Unit 而不是 startTabId。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (startTabId.isNotBlank()) viewModel.applyStartTab(startTabId)
    }

    // A brand-new install shows the wizard instead of the app. It asks who the user is
    // once, so nobody has to go hunting for a profile page before they can chat.
    if (!profile.onboarded) {
        val step = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
        // Back must step backwards through the wizard, not drop the user to the home
        // screen mid-setup. Only step 0 lets the system handle it (exit).
        androidx.activity.compose.BackHandler(enabled = step.intValue > 0) { step.intValue-- }
        OnboardingScreen(viewModel, step = step.intValue, onStepChange = { step.intValue = it })
        ToastOverlay(toast, bottomPadding = 24.dp)
        return
    }

    // Android back: leave the thread / the image studio / close settings before
    // exiting the app.
    androidx.activity.compose.BackHandler(
        enabled = showSettings || showImageStudio || showVoiceScreen || showBreeze || showPlugins ||
            showPluginDocs || showConversationDetail || showDebug || (tab == Tab.CHAT && inChat) ||
            // 插件布局可以把「语音 / Breeze / 插件 / 生图」放到底栏。那几页是**标签**，
            // 不是子页，返回键不该直接退出应用 —— 先回到第一个标签。
            (tab != Tab.CHAT && tab != Tab.MODELS && tab != Tab.ME && tab != Tab.HOME)
    ) {
        when {
            // 测试模式压在最上面（它是从关于页进去的整页），所以先退它。
            showDebug -> viewModel.openDebug(false)
            showSettings -> viewModel.openSettings(false)
            showConversationDetail -> viewModel.openConversationDetail(false)
            showBreeze -> viewModel.openBreeze(false)
            showVoiceScreen -> viewModel.openVoiceScreen(false)
            // 文档页压在插件页之上，所以它先退出。
            showPluginDocs -> viewModel.openPluginDocs(false)
            showPlugins -> viewModel.openPlugins(false)
            showImageStudio -> viewModel.openImageStudio(false)
            // 标签级页面：返回回到第一个标签，而不是退出应用。
            // （布局插件允许把语音/生图/插件页放到底栏，那些页没有自己的返回键。）
            tabs.size > 1 && tab != tabs.first() && !inChat -> viewModel.selectTab(tabs.first())
            else -> viewModel.leaveConversation()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ios.groupedBackground)
    ) {
        Column(Modifier.fillMaxSize()) {
            // 当前是不是"子页"（压在标签页之上的整页）。底栏要据此淡出。
            // 只要不是"三个主标签本身在外层"，底栏就该让位 ——
            // 整页子页必须全屏，留着底栏会让人以为那是个浮层。
            // meFullPage 覆盖「我的」内部 early-return 的那几页（主题/API/语音/关于），
            // showSettings 是根层渲染的设置整页。
            val subPage = showVoiceScreen || showBreeze || showConversationDetail || showPlugins ||
                showPluginDocs || showDebug || showSettings || meFullPage ||
                (showImageStudio && tab == Tab.MODELS)

            // 沉浸式对话：一旦进了某个对话，底栏就收起来。
            //
            // 理由和子页一样，而且更强：在对话里，用户的注意力应该在消息上，
            // 底部那一条会一直暗示"你还能切走"。微信、Telegram 进对话之后底下
            // 也只有一个输入框。要出去就返回 —— 那是进入对话时就已经建立的
            // 层级关系，不需要常驻一个出口。
            val immersiveChat = tab == Tab.CHAT && inChat

            Box(Modifier.weight(1f)) {
                // 顶层切换全部带动效。
                //
                // 之前这里是裸 `when` —— 切标签是硬跳，进语音页/生图页也是硬跳，
                // 整个应用没有任何"过渡"的感觉。现在：
                //   * 底部三个标签之间用**淡入淡出 + 轻微上浮**（同级切换，不该左右滑）
                //   * 子页（语音 / 生图）用**从右推入**（有层级关系，iOS 的 push 语义）
                // 层级不同用不同动效，是为了让用户从动作本身就能判断"我在往里走
                // 还是在平级切换"。
                AnimatedContent(
                    targetState = when {
                        showDebug -> "debug"
                        showConversationDetail -> "convdetail"
                        showBreeze -> "breeze"
                        showVoiceScreen -> "voice"
                        showPluginDocs -> "plugindocs"
                        showPlugins -> "plugins"
                        showImageStudio && tab == Tab.MODELS -> "image"
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
                        val isSub = { s: String -> s in setOf("voice", "image", "convdetail", "plugins", "plugindocs", "debug") }
                        val forward = isSub(targetState)
                        if (forward || isSub(initialState)) {
                            // 子页：右侧推入 / 向右退出。
                            val dir = if (forward) 1 else -1
                            (
                                slideInHorizontally(
                                    animationSpec = Motion.gentle(),
                                    initialOffsetX = { (it * 0.25f * dir).toInt() },
                                ) + fadeIn(Motion.smooth(200))
                                ) togetherWith (
                                slideOutHorizontally(
                                    animationSpec = Motion.gentle(),
                                    targetOffsetX = { (-it * 0.12f * dir).toInt() },
                                ) + fadeOut(Motion.smooth(160))
                                )
                        } else {
                            // 同级标签：淡入 + 上浮一点点。
                            (
                                fadeIn(Motion.smooth(220)) + slideInVertically(
                                    animationSpec = Motion.gentle(),
                                    initialOffsetY = { (it * 0.035f).toInt() },
                                )
                                ) togetherWith fadeOut(Motion.smooth(140))
                        }
                    },
                    label = "rootPage",
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        // 「本对话设置」是整页覆盖，必须画在根层才能盖掉聊天页的
                        // 导航栏 —— 之前它画在 ChatThread 内部，于是屏幕上同时出现
                        // 两条 `‹ 对话`，看起来就是"又高了一层"。
                        "convdetail" -> {
                            val conv = conversations.firstOrNull { it.id == activeConversationId }
                            if (conv != null) {
                                ConversationDetailScreen(viewModel, conv) {
                                    viewModel.openConversationDetail(false)
                                }
                            }
                        }
                        "debug" -> DebugScreen(viewModel) { viewModel.openDebug(false) }
                        "voice" -> VoiceScreen(viewModel)
                        // 插件页提到根层：生图页发现"后端没装"时要能直接把人送到这。
                        "plugins" -> PluginsScreen(viewModel) { viewModel.openPlugins(false) }
                        // 插件开发文档：从插件页的「自己写插件 → 看开发文档」进来。
                        "plugindocs" -> PluginDevDocsScreen { viewModel.openPluginDocs(false) }
                        "image" -> ImageScreen(viewModel) { viewModel.openImageStudio(false) }
                        // 块式首页：由 ui_layout 插件的 home 块拼出来（没装插件时是内置默认首页）。
                        "home" -> com.localmind.ui.screen.LayoutHomeScreen(viewModel, uiLayout)
                        "breeze" -> com.localmind.ui.screen.BreezeScreen(viewModel)
                        "pipeline" -> com.localmind.ui.screen.PipelineScreen(viewModel)
                        "chat" -> ChatScreen(viewModel, sharedText)
                        "models" -> ModelsScreen(viewModel)
                        else -> ProfileScreen(viewModel)
                    }
                }
            }

            // 子页（语音 / 生图 / 对话设置）是自己的一层，底栏跟着淡出 ——
            // 留着会让人以为还能切标签，但实际上那几页是压在上面的。
            // 沉浸式对话同理（见上面的 immersiveChat）。
            //
            // 界面插件还能做两件事（`tabStyle` / `showTabBarLabels`）：
            //  * `tabStyle = "hidden"` → 完全不要底栏（比如插件自己提供了导航）
            //    但**标签只剩一个时会失效**：那等于把用户困在一页里出不来，
            //    所以这种情况回退成 plain，宁可不好看也不能让人出不去。
            //  * `showTabBarLabels = "false"` → 只显示图标
            val tabStyle = uiTokens["tabStyle"] ?: "glass"
            val showLabels = (uiTokens["showTabBarLabels"] ?: "true").toBooleanStrictOrNull() ?: true
            val canHideTabBar = tabs.size > 1
            AnimatedVisibility(
                visible = !subPage && !immersiveChat && !(tabStyle == "hidden" && canHideTabBar),
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
                    // 布局插件能给入口改名、换图标：名字来自 tabs[].label，
                    // 图标来自 tabs[].icon（只认内置名单里的那几种）。
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

        // The chat screen owns its own settings sheet (model routing, TTS, vision,
        // image, decision model), which is where the user asked for it to live.
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically(
                animationSpec = Motion.gentle(),
                initialOffsetY = { it },
            ) + fadeIn(Motion.smooth(180)),
            exit = slideOutVertically(
                animationSpec = Motion.smooth(240),
                targetOffsetY = { it },
            ) + fadeOut(Motion.smooth(160)),
        ) {
            SettingsScreen(viewModel) { viewModel.openSettings(false) }
        }

        ToastOverlay(
            toast = toast,
            bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 78.dp,
        )
    }
}

@Composable
private fun ToastOverlay(toast: String?, bottomPadding: androidx.compose.ui.unit.Dp) {
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn(tween(120)) + slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(180)),
        exit = fadeOut(tween(200)) + slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(200)),
        modifier = Modifier
            .fillMaxSize(),
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
                        .background(com.localmind.ui.theme.LocalUiTokens.current["overlay"]?.let { runCatching { com.localmind.ui.theme.parseHexColor(it) }.getOrNull() } ?: Color(0xE6000000))
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

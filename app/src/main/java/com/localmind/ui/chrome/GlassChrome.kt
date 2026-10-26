package com.localmind.ui.chrome

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.ui.AppViewModel
import com.localmind.ui.Tab
import com.localmind.ui.components.pressScale
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.icons.LayoutIcons
import com.localmind.ui.theme.IosTheme
import com.localmind.ui.theme.LocalUiTokens

/**
 * 原生玻璃栏里的 Compose 内容：只画图标/文字/选中胶囊，不画背景。
 * 背景由外面的 AndroidLiquidGlassView 真实采样页面内容并折射。
 */
@Composable
fun GlassChrome(
    viewModel: AppViewModel,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val ios = IosTheme.palette
    val tokens = LocalUiTokens.current
    val tab by viewModel.tab.collectAsState()
    val layout by viewModel.uiLayout.collectAsState()
    val haptics = LocalHapticFeedback.current

    val tabs = (layout?.tabs?.map { it.target } ?: listOf("chat", "models", "me"))
        .mapNotNull { Tab.fromId(it) }
        .distinct()
        .ifEmpty { listOf(Tab.CHAT, Tab.MODELS, Tab.ME) }
    val labels = layout?.tabs?.mapNotNull { s ->
        val t = Tab.fromId(s.target) ?: return@mapNotNull null
        s.label.takeIf { it.isNotBlank() }?.let { t to it }
    }?.toMap().orEmpty()
    val icons = layout?.tabs?.mapNotNull { s ->
        val t = Tab.fromId(s.target) ?: return@mapNotNull null
        LayoutIcons.of(s.icon)?.let { t to it }
    }?.toMap().orEmpty()
    val showLabels = (tokens["showTabBarLabels"] ?: "true").toBooleanStrictOrNull() ?: true

    val selectedIndex = tabs.indexOf(tab).coerceAtLeast(0)
    val scale by animateFloatAsState(if (tabs.isNotEmpty()) 1f else 0f, label = "chrome")

    Row(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.x, amount.y)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEachIndexed { index, t ->
            val selected = index == selectedIndex
            val tint = if (selected) ios.blue else ios.label.copy(alpha = 0.48f)
            val interaction = remember(t) { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .pressScale(interaction, scaleDown = 0.88f)
                    .clickable(interactionSource = interaction, indication = null) {
                        if (t != tab) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewModel.selectTab(t)
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (selected) {
                        Box(
                            Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (ios.isDark) Color.White.copy(alpha = 0.14f)
                                    else Color(0xFFD9D9D9).copy(alpha = 0.62f)
                                )
                        )
                    }
                    Icon(
                    imageVector = icons[t] ?: iconOf(t),
                    contentDescription = labels[t] ?: t.title,
                    tint = tint,
                        modifier = Modifier.size(23.dp),
                    )
                }
                if (showLabels) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = labels[t] ?: t.title,
                        style = IosTheme.typography.caption2.copy(fontSize = 11.sp),
                        color = tint,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun iconOf(tab: Tab) = when (tab) {
    Tab.HOME -> AppIcons.AutoAwesome
    Tab.CHAT -> AppIcons.ChatBubble
    Tab.MODELS -> AppIcons.Memory
    Tab.ME -> AppIcons.Person
    Tab.VOICE -> AppIcons.Mic
    Tab.BREEZE -> AppIcons.RecordVoiceOver
    Tab.PLUGINS -> AppIcons.Extension
    Tab.IMAGE -> AppIcons.Image
    else -> AppIcons.ChatBubble
}

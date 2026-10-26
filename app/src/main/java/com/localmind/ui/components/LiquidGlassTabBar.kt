package com.localmind.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.ui.Tab
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.motion.Motion
import com.localmind.ui.theme.IosTheme
import kotlinx.coroutines.isActive

private data class BarItem(val tab: Tab, val icon: ImageVector)

private val tabIconOf: Map<Tab, ImageVector> = mapOf(
    Tab.HOME to AppIcons.AutoAwesome,
    Tab.CHAT to AppIcons.ChatBubble,
    Tab.MODELS to AppIcons.Memory,
    Tab.ME to AppIcons.Person,
    Tab.VOICE to AppIcons.Mic,
    Tab.BREEZE to AppIcons.RecordVoiceOver,
    Tab.PLUGINS to AppIcons.Extension,
    Tab.IMAGE to AppIcons.Image,
)

private val defaultTabs = listOf(Tab.CHAT, Tab.MODELS, Tab.ME)

/**
 * 底部「液态玻璃」栏。
 *
 * 视觉层次（从下到上）：
 *  1. 真实背景模糊 —— 窗口内容抓 1/6 缩略图做三次 box blur，只取栏所在那一条。
 *  2. 染色 —— 浅色半透明白，深色半透明黑。纯色会让玻璃"死掉"。
 *  3. 高光边 —— 上缘亮、下缘暗，模拟玻璃厚度。
 *  4. 选中胶囊 —— 白玻璃 + 细边，用弹簧滑动。
 *  5. 图标与文字。
 *
 * 动效只做两处：胶囊滑动的回弹、按下时 0.9 缩放。整个栏一起弹会晕。
 */
@Composable
fun LiquidGlassTabBar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    bottomInset: Dp = 0.dp,
    items: List<Tab> = defaultTabs,
    labels: Map<Tab, String> = emptyMap(),
    icons: Map<Tab, ImageVector> = emptyMap(),
    showLabels: Boolean = true,
    style: String = "glass",
) {
    val ios = IosTheme.palette
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current

    val specs = items.mapNotNull { tab ->
        (icons[tab] ?: tabIconOf[tab])?.let { BarItem(tab, it) }
    }.ifEmpty { defaultTabs.map { BarItem(it, tabIconOf.getValue(it)) } }

    // ---- 背景采样 ----
    var backdrop by remember { mutableStateOf<Bitmap?>(null) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    var barPos by remember { mutableStateOf(IntOffset.Zero) }
    var srcRect by remember { mutableStateOf(Rect.Zero) }
    val decorView = remember(view) { GlassBackdrop.findDecor(view) }

    LaunchedEffect(selected, ios.isDark, style) {
        GlassBackdrop.invalidate()
        while (isActive) {
            val bmp = GlassBackdrop.capture(decorView)
            if (bmp != null && decorView != null && barSize.width > 0) {
                val decorW = decorView.width.coerceAtLeast(1)
                val decorLoc = IntArray(2).also { decorView.getLocationInWindow(it) }
                val viewLoc = IntArray(2).also { view.getLocationInWindow(it) }
                val left = (viewLoc[0] - decorLoc[0]) + barPos.x
                val top = (viewLoc[1] - decorLoc[1]) + barPos.y
                val scale = bmp.width.toFloat() / decorW.toFloat()
                val x = (left * scale).coerceIn(0f, (bmp.width - 1).toFloat())
                val y = (top * scale).coerceIn(0f, (bmp.height - 1).toFloat())
                val w = (barSize.width * scale).coerceIn(1f, bmp.width - x)
                val h = (barSize.height * scale).coerceIn(1f, bmp.height - y)
                srcRect = Rect(x, y, x + w, y + h)
                backdrop = bmp
            }
            kotlinx.coroutines.delay(180)
        }
    }

    // ---- 选中胶囊 ----
    val count = specs.size
    val selectedIndex = specs.indexOfFirst { it.tab == selected }.coerceAtLeast(0)
    var widthPx by remember { mutableStateOf(0f) }
    val pillX = remember { Animatable(0f) }
    LaunchedEffect(selectedIndex, widthPx, count) {
        if (widthPx > 0f && count > 0) {
            val slot = widthPx / count
            // 胶囊按**中心点**定位：slot 中心 = (i + 0.5) * slot。
            pillX.animateTo(slot * (selectedIndex + 0.5f), Motion.bouncy())
        }
    }

    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = bottomInset + 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .shadow(
                    elevation = 18.dp,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = if (ios.isDark) 0.4f else 0.10f),
                    spotColor = Color.Black.copy(alpha = if (ios.isDark) 0.5f else 0.14f),
                )
                .clip(shape)
                .onSizeChanged {
                    widthPx = it.width.toFloat()
                    barSize = it
                }
                .onGloballyPositioned { coords ->
                    val p = coords.positionInRoot()
                    barPos = IntOffset(p.x.toInt(), p.y.toInt())
                },
        ) {
            // 1. 背景模糊
            if (style != "plain") {
                backdrop?.let { bmp ->
                    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = if (ios.isDark) 0.42f else 0.55f }) {
                        val src = if (srcRect.width > 1f) srcRect else Rect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
                        drawImage(
                            image = bmp.asImageBitmap(),
                            srcOffset = IntOffset(src.left.toInt().coerceAtLeast(0), src.top.toInt().coerceAtLeast(0)),
                            srcSize = IntSize(
                                src.width.toInt().coerceIn(1, bmp.width),
                                src.height.toInt().coerceIn(1, bmp.height),
                            ),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
                            filterQuality = FilterQuality.Low,
                        )
                    }
                }
            }

            // 2. 染色
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (ios.isDark) {
                            Color(0xFF141416).copy(alpha = if (style == "plain") 0.96f else 0.62f)
                        } else {
                            Color.White.copy(alpha = if (style == "plain") 0.96f else 0.40f)
                        }
                    )
            )

            // 3. 高光与厚度
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to (if (ios.isDark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.70f)),
                            0.42f to Color.Transparent,
                            1f to (if (ios.isDark) Color.Black.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.05f)),
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .border(
                        width = 0.7.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (ios.isDark) 0.22f else 0.98f),
                                Color.White.copy(alpha = if (ios.isDark) 0.06f else 0.30f),
                                Color.Black.copy(alpha = if (ios.isDark) 0.35f else 0.06f),
                            )
                        ),
                        shape = shape,
                    )
            )

            // 4. 选中胶囊
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        // 减去半个胶囊宽度，让 pillX 表示中心而不是左边缘。
                        translationX = pillX.value - 37.dp.toPx()
                        alpha = if (pillX.value > 0f) 1f else 0f
                    }
                    .width(74.dp)
                    .height(46.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                if (ios.isDark) Color.White.copy(alpha = 0.13f) else ios.blue.copy(alpha = 0.14f),
                                if (ios.isDark) Color.White.copy(alpha = 0.04f) else ios.blue.copy(alpha = 0.08f),
                            )
                        )
                    )
                    .border(
                        0.6.dp,
                        if (ios.isDark) Color.White.copy(alpha = 0.14f) else ios.blue.copy(alpha = 0.16f),
                        RoundedCornerShape(percent = 50),
                    )
            )

            // 5. 内容
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                specs.forEach { item ->
                    BarButton(
                        item = item,
                        label = labels[item.tab] ?: item.tab.title,
                        selected = item.tab == selected,
                        showLabel = showLabels && count <= 4,
                        onClick = {
                            if (item.tab != selected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(item.tab)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BarButton(
    item: BarItem,
    label: String,
    selected: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ios = IosTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val tint = if (selected) ios.blue else ios.label.copy(alpha = 0.48f)
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = Motion.snappy(),
        label = "barIcon",
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .pressScale(interaction, scaleDown = 0.9f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = label.ifBlank { item.tab.title },
            tint = tint,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )
        if (showLabel) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = label.ifBlank { item.tab.title },
                style = IosTheme.typography.caption2.copy(fontSize = 10.sp),
                color = tint,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

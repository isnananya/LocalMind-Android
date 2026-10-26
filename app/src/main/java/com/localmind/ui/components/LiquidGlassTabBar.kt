package com.localmind.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
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
import com.localmind.ui.design.LiquidGlassLayer
import com.localmind.ui.design.rememberLiquidGlassSupport
import com.localmind.ui.theme.LocalUiTokens
import com.localmind.ui.theme.parseHexColor
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

/** 底部液态玻璃栏：模糊 + AGSL 折射 + 材质；形状/留白/染色/指示器走 ui_theme 令牌。 */
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
    val metrics = IosTheme.metrics
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val tokens = LocalUiTokens.current
    val density = androidx.compose.ui.platform.LocalDensity.current

    val shapeKind = (tokens["tabBarShape"] ?: "capsule").lowercase()
    val margin = (tokens["tabBarMargin"]?.toFloatOrNull() ?: 18f).dp
    val lift = (tokens["tabBarLift"]?.toFloatOrNull() ?: 8f).dp
    val blurPct = (tokens["tabBarBlur"]?.toFloatOrNull() ?: 100f).coerceIn(0f, 100f)
    val tint = tokens["tabBarTint"]?.let { runCatching { parseHexColor(it) }.getOrNull() }
        ?: if (ios.isDark) Color(0xFF171A20) else Color(0xFFF2F6FB)
    val indicator = (tokens["tabBarIndicator"] ?: "capsule").lowercase()

    val specs = items.mapNotNull { tab ->
        (icons[tab] ?: tabIconOf[tab])?.let { BarItem(tab, it) }
    }.ifEmpty { defaultTabs.map { BarItem(it, tabIconOf.getValue(it)) } }

    var backdrop by remember { mutableStateOf<Bitmap?>(null) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    var barPos by remember { mutableStateOf(IntOffset.Zero) }
    var srcRect by remember { mutableStateOf(Rect.Zero) }
    val decorView = remember(view) { GlassBackdrop.findDecor(view) }

    LaunchedEffect(selected, ios.isDark, style, blurPct) {
        GlassBackdrop.invalidate()
        while (isActive) {
            GlassBackdrop.capturing = true
            val bmp = try { GlassBackdrop.capture(decorView) } finally { GlassBackdrop.capturing = false }
            if (bmp != null && decorView != null && barSize.width > 0) {
                val decorW = decorView.width.coerceAtLeast(1)
                val decorLoc = IntArray(2).also { decorView.getLocationInWindow(it) }
                val viewLoc = IntArray(2).also { view.getLocationInWindow(it) }
                val left = (viewLoc[0] - decorLoc[0]) + barPos.x
                // 采样栏体上方的区域：decor 截图里已经包含底栏自己，直接采样会把自己的图标
                // 再画一遍（重影）。往上挪一栏高度，取到的仍是栏后内容的近似。
                val top = (viewLoc[1] - decorLoc[1]) + barPos.y - barSize.height
                val scale = bmp.width.toFloat() / decorW.toFloat()
                val x = (left * scale).coerceIn(0f, (bmp.width - 1).toFloat())
                val y = (top * scale).coerceIn(0f, (bmp.height - 1).toFloat())
                val w = (barSize.width * scale).coerceIn(1f, bmp.width - x)
                val h = (barSize.height * scale).coerceIn(1f, bmp.height - y)
                srcRect = Rect(x, y, x + w, y + h)
                backdrop = bmp
            }
            kotlinx.coroutines.delay(150)
        }
    }

    val count = specs.size
    val selectedIndex = specs.indexOfFirst { it.tab == selected }.coerceAtLeast(0)
    var widthPx by remember { mutableStateOf(0f) }
    val pillX = remember { Animatable(0f) }
    LaunchedEffect(selectedIndex, widthPx, count) {
        if (widthPx > 0f && count > 0) {
            val slot = widthPx / count
            pillX.animateTo(slot * (selectedIndex + 0.5f), Motion.bouncy())
        }
    }

    val barHeight = metrics.tabBarHeight
    val corner = when (shapeKind) {
        "square" -> 0.dp
        "rounded" -> 26.dp
        else -> barHeight / 2
    }
    val shape = RoundedCornerShape(corner)
    // 参考 LiquidTab：紧凑胶囊，不是通栏。3 个 240dp，4 个 312dp，最多 360dp。
    val compactWidth = minOf(360, 72 * count + 24).dp
    val cornerPx = with(density) { corner.toPx() }

    val shader = remember(backdrop, srcRect, barSize, cornerPx, tint, ios.isDark, blurPct, style) {
        val bmp = backdrop ?: return@remember null
        if (style == "plain" || blurPct <= 1f) return@remember null
        GlassShader.create(
            bitmap = bmp,
            src = if (srcRect.width > 1f) srcRect else Rect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()),
            width = barSize.width,
            height = barSize.height,
            radius = cornerPx,
            refraction = 8f + 6f * (blurPct / 100f),
            tint = (tint.value shr 32).toInt(),
            tintAlpha = if (ios.isDark) 0.46f else 0.55f,
            highlight = if (ios.isDark) 0.10f else 0.35f,
        )
    }

    Box(
        modifier = Modifier
            .width(compactWidth)
            .padding(bottom = bottomInset + lift),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .shadow(
                    elevation = 18.dp,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = if (ios.isDark) 0.45f else 0.12f),
                    spotColor = Color.Black.copy(alpha = if (ios.isDark) 0.55f else 0.16f),
                )
                .clip(shape)
                .onSizeChanged {
                    widthPx = it.width.toFloat()
                    barSize = it
                }
                .onGloballyPositioned { coords ->
                    val p = coords.positionInRoot()
                    barPos = IntOffset(p.x.toInt(), p.y.toInt())
                }
                .drawWithContent { if (!GlassBackdrop.capturing) drawContent() },
        ) {
            // 真折射：采样时不画底栏自己（见 capturing 开关），所以不会重影。
            if (shader != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        val paint = Paint().apply {
                            this.shader = shader
                            isAntiAlias = true
                        }
                        val r = corner.toPx()
                        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, paint)
                    }
                }
            } else {
                backdrop?.let { bmp ->
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = if (ios.isDark) 0.55f else 0.65f }
                    ) {
                        val src = if (srcRect.width > 1f) srcRect else Rect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
                        drawImage(
                            image = bmp.asImageBitmap(),
                            srcOffset = IntOffset(src.left.toInt().coerceAtLeast(0), src.top.toInt().coerceAtLeast(0)),
                            srcSize = IntSize(
                                src.width.toInt().coerceIn(1, bmp.width),
                                src.height.toInt().coerceIn(1, bmp.height),
                            ),
                            dstOffset = IntOffset(-3, -3),
                            dstSize = IntSize(
                                size.width.toInt().coerceAtLeast(1) + 6,
                                size.height.toInt().coerceAtLeast(1) + 6,
                            ),
                            filterQuality = FilterQuality.Low,
                        )
                    }
                }
            }
            // 极淡染色，给玻璃一点乳感，不遮住折射。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tint.copy(alpha = if (ios.isDark) 0.32f else 0.46f))
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (ios.isDark) 0.06f else 0.22f),
                            0.35f to Color.Transparent,
                            1f to Color.Black.copy(alpha = if (ios.isDark) 0.22f else 0.05f),
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .border(
                        width = metrics.barBorderWidth,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (ios.isDark) 0.22f else 0.95f),
                                Color.White.copy(alpha = if (ios.isDark) 0.06f else 0.25f),
                                Color.Black.copy(alpha = if (ios.isDark) 0.30f else 0.06f),
                            )
                        ),
                        shape = shape,
                    )
            )

            when (indicator) {
                "underline" -> Box(
                    Modifier
                        .graphicsLayer {
                            translationX = pillX.value - 14.dp.toPx()
                        }
                        .align(Alignment.BottomStart)
                        .padding(bottom = 8.dp)
                        .width(28.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ios.blue),
                )
                "dot" -> Box(
                    Modifier
                        .graphicsLayer { translationX = pillX.value - 3.dp.toPx() }
                        .align(Alignment.BottomStart)
                        .padding(bottom = 7.dp)
                        .size(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ios.blue),
                )
                "none" -> Unit
                else -> Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = pillX.value - 27.dp.toPx()
                            alpha = if (pillX.value > 0f) 1f else 0f
                        }
                        .width(54.dp)
                        .height(54.dp)
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    if (ios.isDark) Color.White.copy(alpha = 0.16f) else Color(0xFFD9D9D9).copy(alpha = 0.66f),
                                    if (ios.isDark) Color.White.copy(alpha = 0.06f) else Color(0xFFD9D9D9).copy(alpha = 0.52f),
                                )
                            )
                        )
                        .border(
                            0.6.dp,
                            if (ios.isDark) Color.White.copy(alpha = 0.14f) else ios.blue.copy(alpha = 0.18f),
                            RoundedCornerShape(999.dp),
                        )
                )
            }

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

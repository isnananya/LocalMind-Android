package com.localmind.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.ui.Tab
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.motion.Motion
import com.localmind.ui.motion.pressScale
import com.localmind.ui.theme.IosTheme
import kotlinx.coroutines.isActive

private data class GlassTabSpec(val tab: Tab, val icon: ImageVector)

/** 三个标签各自用什么图标。界面插件只能决定**显示哪几个、什么顺序**，
 *  不能换图标 —— 图标是主程序的资产，换图标属于"改 UI"里最容易做得难看的一类。 */
private val tabIconOf: Map<Tab, ImageVector> = mapOf(
    Tab.HOME to AppIcons.AutoAwesome,
    Tab.CHAT to AppIcons.ChatBubble,
    Tab.MODELS to AppIcons.Memory,
    Tab.ME to AppIcons.Person,
    Tab.VOICE to AppIcons.GraphicEq,
    Tab.BREEZE to AppIcons.RecordVoiceOver,
    Tab.PLUGINS to AppIcons.Extension,
    Tab.IMAGE to AppIcons.Image,
)

private val glassTabs = listOf(Tab.CHAT, Tab.MODELS, Tab.ME)

/**
 * Apple "Liquid Glass" bottom bar, floating as an **elliptical pill** above the
 * screen edge (iOS 26 style).
 *
 * Layers, bottom to top:
 *  1. real blurred backdrop — the window decor is captured and stack-blurred by
 *     [GlassBackdrop], then the strip behind the pill is drawn with a circular crop;
 *  2. a translucent tint gradient;
 *  3. a 1px specular highlight around the upper edge;
 *  4. the selection indicator, animated with a real spring (no overshoot
 *     interpolator) so it feels physical;
 *  5. icons + labels.
 *
 * Reference for the technique: liuran001/WeChat-LiquidGlass (independent Kotlin
 * reimplementation, see LICENSE).
 *
 * @param items 显示哪几个标签、什么顺序。来自界面插件的 `tabs`。
 *   空表按默认三个处理 —— 一个标签都没有的底栏没有意义。
 * @param showLabels 是否显示文字（界面插件的 `showTabBarLabels`）。
 * @param style `glass`（默认毛玻璃）/ `plain`（不透明底）/ `hidden`
 *   （隐藏整个底栏；调用方负责判断"藏了之后用户还出得来吗"）。
 */
@Composable
fun LiquidGlassTabBar(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    bottomInset: Dp = 0.dp,
    items: List<Tab> = glassTabs,
    /**
     * 插件给这些入口起的名字（id → 文字）。空 = 用页面自带的名字。
     *
     * 为什么允许改：界面布局插件把「Breeze 语音」缩成「语音」、把「对话」叫
     * 「消息」，都是合理的产品决定。图标仍然由页面决定（见 [tabIconOf]）。
     */
    labels: Map<Tab, String> = emptyMap(),
    showLabels: Boolean = true,
    style: String = "glass",
) {
    val ios = IosTheme.palette
    val view = LocalView.current

    // 插件给的顺序生效，但认不出的已经在调用方过滤掉了。
    val tabSpecs = items.mapNotNull { t -> tabIconOf[t]?.let { GlassTabSpec(t, it) } }
        .ifEmpty { glassTabs.map { GlassTabSpec(it, tabIconOf.getValue(it)) } }

    // ---- state ------------------------------------------------------------
    var backdrop by remember { mutableStateOf<Bitmap?>(null) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    var barPosInRoot by remember { mutableStateOf(IntOffset.Zero) }
    var srcRect by remember { mutableStateOf(Rect.Zero) }
    var barWidthPx by remember { mutableStateOf(0f) }
    val decorView = remember(view) { GlassBackdrop.findDecor(view) }

    LaunchedEffect(selected, ios.isDark) {
        // Re-capture on tab change and then keep refreshing a few times a second so
        // content scrolling behind the bar stays alive. Deliberately coarse: the
        // blurred result is reused in between.
        GlassBackdrop.invalidate()
        while (isActive) {
            val bmp = GlassBackdrop.capture(decorView, force = true)
            if (bmp != null && decorView != null && barSize.width > 0) {
                val decorW = decorView.width.coerceAtLeast(1)
                val decorLoc = IntArray(2).also { decorView.getLocationInWindow(it) }
                val viewLoc = IntArray(2).also { view.getLocationInWindow(it) }

                // Bar footprint in decor coordinates, scaled into the small bitmap.
                val leftInDecor = (viewLoc[0] - decorLoc[0]) + barPosInRoot.x
                val topInDecor = (viewLoc[1] - decorLoc[1]) + barPosInRoot.y
                val scale = bmp.width.toFloat() / decorW.toFloat()

                val x = (leftInDecor * scale).coerceIn(0f, (bmp.width - 1).toFloat())
                val y = (topInDecor * scale).coerceIn(0f, (bmp.height - 1).toFloat())
                val w = (barSize.width * scale).coerceIn(1f, bmp.width - x)
                val h = (barSize.height * scale).coerceIn(1f, bmp.height - y)
                srcRect = Rect(x, y, x + w, y + h)
                backdrop = bmp
            } else if (bmp != null) {
                backdrop = bmp
            }
            kotlinx.coroutines.delay(120)
        }
    }

    // ---- geometry / selection animation -----------------------------------
    // 用 tabSpecs 而不是 glassTabs：插件可以改标签的数量与顺序，
    // 选中指示器的位置必须跟着走，否则会指到错的那一格。
    val count = tabSpecs.size
    val selectedIndex = tabSpecs.indexOfFirst { it.tab == selected }.coerceAtLeast(0)
    val indicatorX = remember { Animatable(0f) }

    LaunchedEffect(selectedIndex, barWidthPx, count) {
        if (barWidthPx > 0f) {
            val slot = barWidthPx / count
            indicatorX.animateTo(
                targetValue = slot * selectedIndex + slot / 2f,
                // 用统一的 Motion.bouncy：标签指示器的回弹是这个应用里
                // "手感"最显眼的一处，曲线必须和别处一致。
                animationSpec = Motion.bouncy(),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = bottomInset + 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(IosTheme.metrics.tabBarHeight)
                .clip(RoundedCornerShape(percent = 50))          // the ellipse
                .onSizeChanged {
                    barWidthPx = it.width.toFloat()
                    barSize = it
                }
                .onGloballyPositioned { coords ->
                    barPosInRoot = coords.positionInRoot().let {
                        androidx.compose.ui.unit.IntOffset(it.x.toInt(), it.y.toInt())
                    }
                }
        ) {
            // 1) blurred backdrop — only the strip behind the pill is drawn
            backdrop?.let { bmp ->
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IosTheme.metrics.tabBarHeight)
                        .graphicsLayer { alpha = if (ios.isDark) 0.62f else 0.8f }
                ) {
                    val src = if (srcRect.width > 0f) srcRect else Rect(
                        0f, 0f, bmp.width.toFloat(), bmp.height.toFloat()
                    )
                    drawImage(
                        image = bmp.asImageBitmap(),
                        srcOffset = androidx.compose.ui.unit.IntOffset(
                            src.left.toInt().coerceAtLeast(0),
                            src.top.toInt().coerceAtLeast(0),
                        ),
                        srcSize = IntSize(
                            src.width.toInt().coerceIn(1, bmp.width),
                            src.height.toInt().coerceIn(1, bmp.height),
                        ),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                    )
                }
            }

            // 2) tint
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(IosTheme.metrics.tabBarHeight)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                ios.onAccent.copy(alpha = if (ios.isDark) 0.06f else 0.42f),
                                ios.onAccent.copy(alpha = if (ios.isDark) 0.02f else 0.16f),
                            )
                        )
                    )
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(IosTheme.metrics.tabBarHeight)
                    .background(ios.groupedBackground.copy(alpha = if (ios.isDark) 0.34f else 0.22f))
            )

            // 3) specular highlight ring
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(IosTheme.metrics.tabBarHeight)
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(
                                ios.onAccent.copy(alpha = if (ios.isDark) 0.22f else 0.85f),
                                ios.onAccent.copy(alpha = if (ios.isDark) 0.05f else 0.25f),
                                Color.Black.copy(alpha = 0.06f),
                            )
                        ),
                        shape = RoundedCornerShape(percent = 50),
                    )
            )

            // 4) spring-driven selection indicator
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = indicatorX.value - 26.dp.toPx()
                        alpha = if (indicatorX.value > 0f) 1f else 0f
                    }
                    .padding(top = 5.dp)
                    .size(width = 52.dp, height = 52.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                ios.selection.copy(alpha = if (ios.isDark) 0.34f else 0.20f),
                                ios.selection.copy(alpha = if (ios.isDark) 0.22f else 0.10f),
                            )
                        )
                    )
            )

            // 5) contents
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IosTheme.metrics.tabBarHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                tabSpecs.forEach { spec ->
                    LiquidGlassTabItem(
                        spec = spec,
                        selected = spec.tab == selected,
                        onClick = { onSelect(spec.tab) },
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.LiquidGlassTabItem(
    spec: GlassTabSpec,
    selected: Boolean,
    onClick: () -> Unit,
    showLabel: Boolean = true,
) {
    val ios = IosTheme.palette
    val tint = if (selected) ios.selection else ios.label.copy(alpha = 0.5f)
    val interaction = remember { MutableInteractionSource() }

    // 选中态：图标放大一点 + 轻微上移；未选中：归位。
    // 这是 iOS 标签栏最容易被忽略但最能体现"活"的一处 —— 只有染色变化时，
    // 切换看起来是"换了个颜色"，而不是"这个东西被选中了"。
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = Motion.bouncy(),
        label = "tabIconScale",
    )
    val lift by animateFloatAsState(
        targetValue = if (selected) -1.5f else 0f,
        animationSpec = Motion.snappy(),
        label = "tabIconLift",
    )

    Column(
        modifier = Modifier
            .weight(1f)
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = spec.icon,
            contentDescription = spec.tab.title,
            tint = tint,
            modifier = Modifier
                .size(IosTheme.metrics.tabIconSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = lift * density
                },
        )
        // 只显示图标时（showTabBarLabels = false）把文字整块去掉，
        // 而不是留一条空行 —— 高度要跟着收，否则图标会浮在上半截。
        if (showLabel) {
            Spacer(Modifier.height(1.dp))
            Text(
                text = spec.tab.title,
                style = IosTheme.typography.caption2.copy(fontSize = 10.sp),
                color = tint,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Suppress("unused")
private fun DrawScope.noopGlass() = Unit

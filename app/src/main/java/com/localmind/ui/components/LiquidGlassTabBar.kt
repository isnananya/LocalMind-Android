package com.localmind.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.ui.Tab
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.motion.Motion
import com.localmind.ui.theme.IosTheme
import com.localmind.ui.theme.LocalUiTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

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


/** LiquidTab 的面板在两端的横向内缩：tabWidth = (width - 8dp) / slots。 */
private val PanelInset = 4.dp

/**
 * 底部液态玻璃栏 —— 渲染按 LiquidTab 源码移植。
 *
 * 面板：模糊底板 → 圆角 SDF 折射 → 40% 表面填充 → 双光源高光描边；
 * 胶囊：整槽宽、做色散折射 → 10% 白/黑 → 按压光环与提亮。
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
    val tokens = LocalUiTokens.current
    val density = LocalDensity.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    fun num(key: String, fallback: Float) = tokens[key]?.toFloatOrNull() ?: fallback
    fun flag(key: String, fallback: Boolean) = tokens[key]?.toBooleanStrictOrNull() ?: fallback

    val solid = style == "plain"
    val liquid = flag("glassBarLiquid", true) && !solid
    val outline = flag("glassBarOutline", true)
    val highlight = num("glassBarHighlight", 0.5f).coerceIn(0.5f, 1.7f)
    val blurDp = num("tabBarBlur", 10f).coerceIn(0f, 16f)
    val bottomBlur = flag("glassBarBottomBlur", false) && !solid
    val barHeight = num("tabBarHeight", 56f).coerceIn(44f, 80f).dp
    val slotWidth = num("glassBarSlotWidth", 76f).coerceIn(56f, 112f).dp
    val bottomGap = num("tabBarLift", 12f).coerceIn(0f, 40f).dp
    val sideMargin = num("tabBarMargin", 18f).coerceIn(0f, 48f).dp
    val iconOnly = flag("glassBarIconOnly", false)
    val labelSize = num("glassBarLabelSize", 11f).coerceIn(9f, 14f).sp
    val iconSize = num("tabIconSize", 23f).coerceIn(18f, 30f).dp
    val widthMode = tokens["tabBarWidth"] ?: "compact"
    val shapeKind = (tokens["tabBarShape"] ?: "capsule").lowercase()
    val indicatorKind = (tokens["tabBarIndicator"] ?: "capsule").lowercase()

    val specs = items.mapNotNull { tab ->
        (icons[tab] ?: tabIconOf[tab])?.let { BarItem(tab, it) }
    }.ifEmpty { defaultTabs.map { BarItem(it, tabIconOf.getValue(it)) } }
    val count = specs.size.coerceAtLeast(1)
    val selectedIndex = specs.indexOfFirst { it.tab == selected }.coerceAtLeast(0)

    val corner = when (shapeKind) {
        "square" -> 0.dp
        "rounded" -> 24.dp
        else -> barHeight / 2
    }
    val shape = RoundedCornerShape(corner)

    var frameTick by remember { mutableIntStateOf(0) }
    var backdrop by remember { mutableStateOf<Backdrop?>(null) }
    val decor = remember(view) { GlassBackdrop.findDecor(view) }
    // RenderEffect 的模糊半径单位是屏幕像素。
    val blurPx = remember(blurDp, density) { (blurDp * density.density).coerceIn(0f, 48f) }
    LaunchedEffect(decor, blurPx, solid) {
        if (solid) return@LaunchedEffect
        while (isActive) {
            if (!view.isShown || decor == null) {
                delay(400)
                continue
            }
            val bd = runCatching { GlassBackdrop.capture(decor, blurPx.roundToInt()) }.getOrNull()
            if (bd != null) {
                backdrop = bd
                frameTick++
            }
            delay(180)
        }
    }

    var panelWindow by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var panelPx by remember { mutableStateOf(IntSize.Zero) }
    var veilWindow by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val tilt = rememberTiltX()
    val glass = remember { LiquidBarGlass() }
    glass.density = density.density
    // 底栏内容（图标 + 文字）的截图。胶囊折射的是这张图，不是底栏下面的页面。
    val contentLayer = rememberGraphicsLayer()
    var contentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(contentLayer, selectedIndex, panelPx, showLabels, iconOnly, labels, ios.isDark) {
        // 两帧：一帧让选中色/布局落到 Row，一帧让 record 拿到新内容。
        withFrameNanos { }
        withFrameNanos { }
        contentBitmap = runCatching { contentLayer.toImageBitmap().asAndroidBitmap() }.getOrNull()
    }

    var dragY by remember { mutableFloatStateOf(0f) }
    var draggingBar by remember { mutableStateOf(false) }
    // 按压态有两个来源：手指按在某个标签上（子级 clickable 的 press），
    // 以及"正在拖胶囊"这个手势本身。拖到一半时 clickable 会因为超过 slop
    // 取消 press，如果只看子级，拖起来就没有折射了 —— 用户看到的正是
    // 「只有长按底框才有折射，拖不动」。
    var childPressed by remember { mutableStateOf(false) }
    var gesturePressed by remember { mutableStateOf(false) }
    val pressed = childPressed || gesturePressed
    // LiquidTab：pressSpring 1000 临界阻尼；缩放弹簧 250 / 0.62 会明显过冲。
    // 按住时胶囊会长到 1.36 倍（原版 PRESSED_SCALE = 78/56 ≈ 1.39）。
    val press = remember { Animatable(0f) }
    val pressScale = remember { Animatable(1f) }
    LaunchedEffect(pressed, liquid) {
        val down = pressed && liquid
        press.animateTo(if (down) 1f else 0f, spring(dampingRatio = 1f, stiffness = 1000f))
        pressScale.animateTo(if (down) 1.36f else 1f, spring(dampingRatio = 0.62f, stiffness = 250f))
    }
    // 长按拖底栏：整块放大 6% + 阴影加深 + 边缘折射加强（"抓起来"的手感）。
    val grab by animateFloatAsState(
        targetValue = if (draggingBar) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 260f),
        label = "barGrab",
    )
    val barGrow = 1f + grab * 0.08f
    val barShadow = (12f + grab * 10f).dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = bottomInset + bottomGap)
            .drawWithContent { if (!GlassBackdrop.capturing) drawContent() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        // LiquidTab：面板宽 = 8dp + 槽宽 × 槽数
        val compactWidth = minOf(PanelInset * 2 + slotWidth * count, maxWidth - sideMargin * 2)
        val panelWidth = if (widthMode == "full") maxWidth - sideMargin * 2 else compactWidth

        if (bottomBlur) {
            BottomGradientVeil(
                snapshot = backdrop?.blurred,
                tick = frameTick,
                veilWindow = veilWindow,
                tint = if (ios.isDark) Color(0xFF1F1F26) else Color.White,
                dark = ios.isDark,
                glass = glass,
                blurPx = blurPx,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight + 96.dp)
                    .onGloballyPositioned { veilWindow = it.boundsInWindow() },
            )
        }

        val slotPx = if (panelPx.width > 0) panelPx.width / count.toFloat() else 0f
        val pillX = remember { Animatable(0f) }
        var dragging by remember { mutableStateOf(false) }
        var dragVelocity by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(selectedIndex, slotPx, count) {
            if (slotPx > 0f) {
                pillX.animateTo(slotPx * (selectedIndex + 0.5f), Motion.bouncy())
            }
        }

        Box(
            modifier = Modifier
                .width(panelWidth)
                .height(barHeight)
                .graphicsLayer { translationY = dragY; scaleX = barGrow; scaleY = barGrow }
                .drawWithContent { if (!GlassBackdrop.capturing) drawContent() }
                .shadow(
                    elevation = barShadow,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = if (ios.isDark) 0.55f else 0.10f),
                    spotColor = Color.Black.copy(alpha = if (ios.isDark) 0.60f else 0.12f),
                )
                .clip(shape)
                .onSizeChanged { panelPx = it }
                .onGloballyPositioned { panelWindow = it.boundsInWindow() },
            contentAlignment = Alignment.Center,
        ) {
            val w = panelPx.width.toFloat()
            val h = panelPx.height.toFloat()
            val cornerPx = with(density) { corner.toPx() }
            // 胶囊在底板里留一圈可读的边距：贴到 4dp 时，前排选中项和底板
            // 左端的圆弧几乎相切，看起来像被底板裁掉一角。
            val pillInset = with(density) { 4.dp.toPx() }
            val pillTop = with(density) { 4.dp.toPx() }
            val fillColor = if (ios.isDark) 0xFF211F26.toInt() else 0xFFFFFFFF.toInt()

            Canvas(Modifier.fillMaxSize()) {
                if (frameTick < 0) return@Canvas
                val bd = backdrop
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    val bd = backdrop
                    if (solid) {
                        glass.drawPanel(
                            canvas = nc, backdrop = null, sample = GlassBackdrop.SAMPLE,
                            winLeft = panelWindow.left, winTop = panelWindow.top,
                            width = w, height = h, radius = cornerPx,
                            dark = ios.isDark, liquid = false, outline = false, highlight = 0f,
                            fillColor = fillColor, fillAlpha = 1f, tiltX = tilt,
                        )
                    } else {
                        glass.drawPanel(
                            canvas = nc, backdrop = bd?.blurred, sample = GlassBackdrop.SAMPLE,
                            winLeft = panelWindow.left, winTop = panelWindow.top + dragY,
                            width = w, height = h, radius = cornerPx,
                            dark = ios.isDark, liquid = liquid, outline = outline,
                            highlight = highlight,
                            // 40% 太透：底下的列表文字会透出来，看起来像脏污（实测
                            // "关于 LocalMind" 整行都能读出来）。抬到 58%，保留磨砂的
                            // 颜色感，同时把内容压成柔和的色块。
                            fillColor = fillColor,
                            // LiquidTab 的 surfaceContainer 固定 40%：再高就变成实心卡片，
                            // 磨砂和折射都看不出来了（内容靠模糊压住，不靠不透明度）。
                            fillAlpha = 0.40f,
                            tiltX = tilt,
                            // 拖动时把边缘折射再加强一点，移动中能明显看到内容被掰弯。
                            lensBoost = 1f + grab * 0.5f,
                        )
                    }
                }
                if (indicatorKind == "underline" || indicatorKind == "dot") {
                    val w2 = if (indicatorKind == "dot") 6.dp.toPx() else 28.dp.toPx()
                    val h2 = if (indicatorKind == "dot") 6.dp.toPx() else 3.dp.toPx()
                    val x = (pillX.value - w2 / 2f).coerceIn(0f, size.width - w2)
                    drawRoundRect(
                        color = ios.blue,
                        topLeft = androidx.compose.ui.geometry.Offset(x, size.height - h2 - 7.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(w2, h2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h2 / 2f, h2 / 2f),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        contentLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(contentLayer)
                    }
                    .pointerInput(count, panelPx.width, indicatorKind) {
                        if (indicatorKind != "capsule") return@pointerInput
                        awaitEachGesture {
                            // ---- 手写手势循环，而不是 awaitLongPressOrCancellation / await*Slop ----
                            //
                            // 原因：标签按钮自己是 clickable，**它会把 DOWN 事件吃掉**。
                            // Compose 的 awaitHorizontalTouchSlopOrCancellation 和
                            // awaitLongPressOrCancellation 一看到 isConsumed 就取消，
                            // 于是挂在 Row 上的手势永远不会进入拖动分支 —— 表现就是
                            // 「长按只亮一下按压态，拖不动胶囊」。
                            //
                            // 这里直接读指针流（不因 consumed 退出），自己判定方向与长按：
                            //  * 起手在选中胶囊上 + 横向过 slop → 拖胶囊（无需长按）；
                            //  * 长按之后 → 横向拖胶囊 / 纵向拖整条底栏；
                            //  * 其它情况一律不接管，点按照常交给按钮。
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val slot = panelPx.width / count.toFloat()
                            if (slot <= 0f) return@awaitEachGesture
                            val minX = slot / 2f
                            val maxX = panelPx.width - slot / 2f
                            val pillHalf = slot / 2f - 4.dp.toPx()
                            val startOnPill = abs(down.position.x - pillX.value) <= pillHalf
                            val slop = viewConfiguration.touchSlop
                            var totalX = 0f
                            var totalY = 0f
                            var axis = 0 // 0 未定 / 1 胶囊 / 2 底栏
                            var grabbed = false
                            var pointerDown = true
                            // 长按用"另一个协程等 500ms"实现，而不是在指针作用域里
                            // withTimeout —— AwaitPointerEventScope 是受限挂起作用域，
                            // 不能把 awaitPointerEvent 包进别的 suspend lambda。
                            val longPressJob = scope.launch {
                                delay(viewConfiguration.longPressTimeoutMillis)
                                if (pointerDown && axis == 0) {
                                    grabbed = true
                                    gesturePressed = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                            try {
                                while (true) {
                                    // Initial 阶段读事件：子级 clickable 在 Main 阶段才会
                                    // 消费 DOWN，所以在 Initial 里看得到"没被吃掉"的原始移动。
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val d = change.positionChange()
                                    totalX += d.x
                                    totalY += d.y
                                    if (axis == 0) {
                                        val horizontal = abs(totalX) > slop && abs(totalX) > abs(totalY)
                                        val vertical = abs(totalY) > slop && abs(totalY) >= abs(totalX)
                                        when {
                                            horizontal && (startOnPill || grabbed) -> {
                                                axis = 1
                                                dragging = true
                                                gesturePressed = true
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                tracker.resetTracking()
                                                tracker.addPosition(change.uptimeMillis, change.position)
                                            }
                                            grabbed && vertical -> {
                                                axis = 2
                                                draggingBar = true
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            else -> if ((horizontal && !startOnPill) || (!grabbed && vertical)) break
                                        }
                                    }
                                    if (axis == 1) {
                                        change.consume()
                                        tracker.addPosition(change.uptimeMillis, change.position)
                                        scope.launch {
                                            pillX.snapTo((pillX.value + d.x).coerceIn(minX, maxX))
                                        }
                                    } else if (axis == 2) {
                                        change.consume()
                                        dragY = (dragY + d.y).coerceIn((-160).dp.toPx(), 0f)
                                    }
                                }
                                if (axis == 1) {
                                    dragVelocity = tracker.calculateVelocity().x
                                    val target = (pillX.value / slot).toInt().coerceIn(0, count - 1)
                                    specs.getOrNull(target)?.let { if (it.tab != selected) onSelect(it.tab) }
                                    scope.launch {
                                        pillX.animateTo(
                                            slot * (target + 0.5f),
                                            Motion.bouncy(),
                                            initialVelocity = dragVelocity,
                                        )
                                    }
                                } else if (axis == 2) {
                                    if (abs(dragY) < 24.dp.toPx()) dragY = 0f
                                }
                            } finally {
                                // 手势被取消（面板尺寸变化/重组）时，别把按压态和抓取态留在 true。
                                pointerDown = false
                                longPressJob.cancel()
                                dragging = false
                                draggingBar = false
                                gesturePressed = false
                            }
                        }
                    },

                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                specs.forEach { item ->
                    BarButton(
                        item = item,
                        label = labels[item.tab] ?: item.tab.title,
                        selected = item.tab == selected,
                        showLabel = showLabels && !iconOnly,
                        iconSize = iconSize,
                        labelSize = labelSize,
                        liquid = liquid,
                        onClick = {
                            if (item.tab != selected) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(item.tab)
                            }
                        },
                        onPressChanged = { p -> childPressed = p },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ---- 胶囊：画在图标/文字**之上** ----
            //
            // 顺序是 底板 → 内容 → 胶囊。胶囊先用底栏自己的磨砂底盖住这一格里的
            // 原始图标，再把内容截图经过透镜着色器画上去 —— 被折射的是"我的/模型"
            // 这些字和图标，底栏本身始终是连续的磨砂玻璃。
            val bmp = contentBitmap
            if (indicatorKind == "capsule" && slotPx > 0f && bmp != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { c ->
                        val nc = c.nativeCanvas
                        val baseHalfW = (w / count.toFloat() - pillInset * 2f) / 2f
                        val baseHalfH = (h - pillTop * 2f) / 2f
                        val cx = pillX.value
                        val cy = h / 2f
                        // LiquidTab drawSelection：位移速度反向缩放宽/高，拖起来像液体。
                        val velocity = (if (dragging) dragVelocity else pillX.velocity) / 10f
                        val sx = pressScale.value / (1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f))
                        val sy = pressScale.value * (1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f))
                        glass.drawPill(
                            canvas = nc,
                            base = backdrop?.blurred,
                            content = bmp,
                            sample = GlassBackdrop.SAMPLE,
                            winLeft = panelWindow.left,
                            winTop = panelWindow.top + dragY,
                            // 内容截图和画布同源（都在底栏本地坐标里，左上角 = 0,0），
                            // 着色器用 canvas 坐标 eval(content)，所以这里是 0。
                            contentLeft = 0f,
                            contentTop = 0f,
                            left = cx - baseHalfW * sx,
                            top = cy - baseHalfH * sy,
                            right = cx + baseHalfW * sx,
                            bottom = cy + baseHalfH * sy,
                            dark = ios.isDark,
                            liquid = liquid,
                            press = press.value,
                            tiltX = tilt,
                            fillColor = fillColor,
                            fillAlpha = 0.40f,
                        )
                    }
                }
            }
        }
    }
}

/** 底栏上方的「底部渐变模糊」：把窗口模糊图铺在页面底部，再用竖直渐变遮罩。 */
@Composable
private fun BottomGradientVeil(
    snapshot: Bitmap?,
    tick: Int,
    veilWindow: androidx.compose.ui.geometry.Rect,
    tint: Color,
    dark: Boolean,
    glass: LiquidBarGlass,
    blurPx: Float,
    modifier: Modifier = Modifier,
) {
    val paint = remember { android.graphics.Paint().apply { isAntiAlias = true; isFilterBitmap = true } }
    val mask = remember { android.graphics.Paint() }

    Canvas(modifier) {
        if (tick < 0) return@Canvas
        val bmp = snapshot ?: return@Canvas
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val alpha = if (dark) 0.92f else 0.80f
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            val save = nc.saveLayer(0f, 0f, size.width, size.height, null)
            glass.drawBlurredVeil(
                canvas = nc,
                blurred = bmp,
                sample = GlassBackdrop.SAMPLE,
                winLeft = veilWindow.left,
                winTop = veilWindow.top,
                width = size.width,
                height = size.height,
            )
            val end = ((alpha * 255f).roundToInt().coerceIn(0, 255) shl 24)
            mask.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, size.height,
                intArrayOf(0x00000000, end),
                floatArrayOf(0.0f, 1.0f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            mask.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            nc.drawRect(0f, 0f, size.width, size.height, mask)
            mask.xfermode = null
            nc.restoreToCount(save)
        }
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.Transparent,
                1f to tint.copy(alpha = if (dark) 0.10f else 0.16f),
            ),
            size = androidx.compose.ui.geometry.Size(size.width, size.height),
        )
    }
}

@Composable
private fun BarButton(
    item: BarItem,
    label: String,
    selected: Boolean,
    showLabel: Boolean,
    iconSize: Dp,
    labelSize: TextUnit,
    liquid: Boolean,
    onClick: () -> Unit,
    onPressChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ios = IosTheme.palette
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    LaunchedEffect(isPressed) { onPressChanged(isPressed) }

    val tint = when {
        selected && ios.isDark -> Color.White
        selected -> ios.blue
        else -> ios.label.copy(alpha = 0.48f)
    }
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = Motion.snappy(),
        label = "barIcon",
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .pressScale(interaction, scaleDown = if (liquid) 0.94f else 1f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = label.ifBlank { item.tab.title },
            tint = tint,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )
        if (showLabel) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = label.ifBlank { item.tab.title },
                style = IosTheme.typography.caption2.copy(fontSize = labelSize),
                color = tint,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}
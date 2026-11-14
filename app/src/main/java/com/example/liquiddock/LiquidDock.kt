package com.example.liquiddock

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 面板两端的横向内缩（LiquidTab 的 8dp / 2）。
 * 面板宽 = 2 x 内缩 + 格宽 x 格数，这样第一格/最后一格的中心落在内缩之外的整数位上。
 */
private val PanelInset = 4.dp

/** 胶囊相对底板的内缩，避免贴到圆角看起来像被切掉一角。 */
private val PillInset = 4.dp

/**
 * 液态玻璃底栏：磨砂面板 + 边缘折射 + 液态选中胶囊（可拖动、按压放大、色散）+ 底部渐变模糊。
 *
 * 用法：
 *
 *     var tab by remember { mutableStateOf("chat") }
 *     LiquidDock(
 *         items = listOf(
 *             DockItem("chat", "对话", Icons.Filled.ChatBubble),
 *             DockItem("models", "模型", Icons.Filled.Memory),
 *             DockItem("me", "我的", Icons.Filled.Person),
 *         ),
 *         selectedId = tab,
 *         onSelect = { tab = it },
 *         bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
 *     )
 *
 * 绘制顺序：面板 -> 图标文字 -> 胶囊。胶囊必须最后画，因为它折射的是前面那层的截图。
 *
 * @param bottomInset 系统导航栏高度，叠在 [DockConfig.bottomGap] 之上
 * @param config 尺寸 / 颜色 / 开关，见 [DockConfig]
 */
@Composable
fun LiquidDock(
    items: List<DockItem>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
    config: DockConfig = DockConfig(),
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val colors = config.colors
    val dark = colors.isDark
    val solid = config.solid
    val liquid = config.liquid && !solid
    val count = items.size
    val selectedIndex = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val showLabel = config.showLabels && !config.iconOnly

    val corner = config.cornerRadius()
    val shape = RoundedCornerShape(corner)

    // ---- 背景帧：每 180ms 抓一次整窗，抓的时候藏起自己 ----
    var frameTick by remember { mutableIntStateOf(0) }
    var backdrop by remember { mutableStateOf<DockFrame?>(null) }
    val decor = remember(view) { DockBackdrop.findDecor(view) }
    // RenderEffect 的模糊半径单位是屏幕像素。
    val blurPx = remember(config.blurRadius, density) {
        (config.blurRadius * density.density).coerceIn(0f, 48f)
    }
    LaunchedEffect(decor, blurPx, solid) {
        if (solid) return@LaunchedEffect
        while (isActive) {
            if (!view.isShown || decor == null) {
                delay(400)
                continue
            }
            runCatching { DockBackdrop.capture(decor, blurPx.roundToInt()) }
                .getOrNull()
                ?.let {
                    backdrop = it
                    frameTick++
                }
            delay(config.backdropIntervalMs)
        }
    }

    var panelWindow by remember { mutableStateOf(Rect.Zero) }
    var panelPx by remember { mutableStateOf(IntSize.Zero) }
    var veilWindow by remember { mutableStateOf(Rect.Zero) }
    val tilt = rememberTiltX()
    val glass = remember { LiquidDockGlass() }
    glass.density = density.density

    // 底栏内容（图标 + 文字）的截图：胶囊折射的是这张图，不是底栏下面的页面。
    val contentLayer = rememberGraphicsLayer()
    var contentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(contentLayer, selectedIndex, panelPx, showLabel, config.labelSize, items) {
        // 两帧：一帧让选中色/布局落到 Row，一帧让 record 拿到新内容。
        withFrameNanos { }
        withFrameNanos { }
        val previous = contentBitmap
        val fresh = runCatching { contentLayer.toImageBitmap().asAndroidBitmap() }.getOrNull()
        contentBitmap = fresh
        // 旧图必须显式回收。这张是**全屏尺寸**的 ARGB_8888（1080p 约 8 MB），
        // 而 key 里带着 selectedIndex —— 每切一次底栏标签就会重截一张。
        // 只靠 GC 的话，低端机上连点几下标签就可能 OOM。
        //
        // 回收放在下一帧之后：新位图已经接管绘制，此时再释放旧的，
        // 不会让某个正在进行的 draw 读到已经回收的内存。
        if (previous != null && previous !== fresh) {
            withFrameNanos { }
            runCatching { if (!previous.isRecycled) previous.recycle() }
        }
    }
    // 离开组合时把最后一张也收掉，否则它会一直挂到 GC 想起来为止。
    DisposableEffect(Unit) {
        onDispose {
            runCatching { contentBitmap?.takeIf { !it.isRecycled }?.recycle() }
        }
    }

    // 按压态有两个来源：手指按在某个格子上（子级 clickable 的 press），
    // 以及「正在拖胶囊」这个手势本身。拖到一半时 clickable 会因为超过 slop 取消 press，
    // 只看子级的话，拖起来就看不到折射了。
    var childPressed by remember { mutableStateOf(false) }
    var gesturePressed by remember { mutableStateOf(false) }
    val pressed = childPressed || gesturePressed

    // 按压弹簧 1000 临界阻尼（不抖）；缩放弹簧 250 / 0.62（明显过冲，长到 1.36 倍）。
    val press = remember { Animatable(0f) }
    val pressScale = remember { Animatable(1f) }
    LaunchedEffect(pressed, liquid) {
        val down = pressed && liquid
        press.animateTo(if (down) 1f else 0f, spring(dampingRatio = 1f, stiffness = 1000f))
        pressScale.animateTo(
            if (down) 1.36f else 1f,
            spring(dampingRatio = 0.62f, stiffness = 250f),
        )
    }

    val barShadow = 12.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomInset + config.bottomGap)
            // 抓图时把自己整块跳过，否则玻璃里会映出上一帧的底栏。
            .drawWithContent { if (!DockBackdrop.capturing) drawContent() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val compactWidth = minOf(
            PanelInset * 2 + config.slotWidth * count,
            maxWidth - config.sideMargin * 2,
        )
        val panelWidth = if (config.width == DockWidth.Full) maxWidth - config.sideMargin * 2 else compactWidth

        if (config.bottomVeil && !solid) {
            BottomGradientVeil(
                snapshot = backdrop?.blurred,
                tick = frameTick,
                veilWindow = veilWindow,
                tint = if (dark) Color(0xFF1F1F26) else Color.White,
                dark = dark,
                glass = glass,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(config.height + 96.dp)
                    .onGloballyPositioned { veilWindow = it.boundsInWindow() },
            )
        }

        val slotPx = if (panelPx.width > 0) panelPx.width / count.toFloat() else 0f
        val pillX = remember { Animatable(0f) }
        var dragging by remember { mutableStateOf(false) }
        var dragVelocity by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(selectedIndex, slotPx, count) {
            // 拖动中不要被「选中项变了」拉回去：拖动本身就在改选中项。
            if (slotPx > 0f && !dragging) {
                pillX.animateTo(slotPx * (selectedIndex + 0.5f), DockMotion.bouncy())
            }
        }

        Box(
            modifier = Modifier
                .width(panelWidth)
                .height(config.height)
                .shadow(
                    elevation = barShadow,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = if (dark) 0.55f else 0.10f),
                    spotColor = Color.Black.copy(alpha = if (dark) 0.60f else 0.12f),
                )
                .clip(shape)
                .onSizeChanged { panelPx = it }
                .onGloballyPositioned { panelWindow = it.boundsInWindow() },
            contentAlignment = Alignment.Center,
        ) {
            val w = panelPx.width.toFloat()
            val h = panelPx.height.toFloat()
            val cornerPx = with(density) { corner.toPx() }
            val fillColor = colors.surface.toArgb()
            val fillAlpha = if (solid) 1f else config.fillAlpha

            // ---- 第 1 层：面板 ----
            Canvas(Modifier.fillMaxSize()) {
                // 读 frameTick：背景换帧时靠它把这张画布重画一遍。
                if (frameTick < 0) return@Canvas
                drawIntoCanvas { c ->
                    glass.drawPanel(
                        canvas = c.nativeCanvas,
                        backdrop = if (solid) null else backdrop?.blurred,
                        sample = DockBackdrop.SAMPLE,
                        winLeft = panelWindow.left,
                        winTop = panelWindow.top,
                        width = w,
                        height = h,
                        radius = cornerPx,
                        dark = dark,
                        liquid = liquid,
                        outline = config.showOutline && !solid,
                        highlight = config.highlight.coerceIn(0.5f, 1.7f),
                        fillColor = fillColor,
                        fillAlpha = fillAlpha,
                        tiltX = tilt,
                    )
                }
                // 下划线 / 圆点指示器
                when (config.indicator) {
                    DockIndicator.Underline, DockIndicator.Dot -> {
                        val w2 = if (config.indicator == DockIndicator.Dot) 6.dp.toPx() else 28.dp.toPx()
                        val h2 = if (config.indicator == DockIndicator.Dot) 6.dp.toPx() else 3.dp.toPx()
                        val x = (pillX.value - w2 / 2f).coerceIn(0f, size.width - w2)
                        drawRoundRect(
                            color = colors.accent,
                            topLeft = Offset(x, size.height - h2 - 7.dp.toPx()),
                            size = Size(w2, h2),
                            cornerRadius = CornerRadius(h2 / 2f, h2 / 2f),
                        )
                    }
                    else -> Unit
                }
            }

            // ---- 第 2 层：图标 + 文字（会被胶囊折射的那一层） ----
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        contentLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(contentLayer)
                    }
                    .pointerInput(count, panelPx.width, config.indicator) {
                        if (config.indicator != DockIndicator.Capsule) return@pointerInput
                        awaitEachGesture {
                            // 手写手势循环，而不是 awaitLongPressOrCancellation / await*Slop：
                            // 每格按钮自己是 clickable，会把 DOWN 吃掉，标准手势检测器
                            // 一看到 isConsumed 就取消，于是「长按只亮一下、拖不动胶囊」。
                            // 这里直接读 Initial 阶段的指针流（不因 consumed 退出）。
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val slot = panelPx.width / count.toFloat()
                            if (slot <= 0f) return@awaitEachGesture
                            val minX = slot / 2f
                            val maxX = panelPx.width - slot / 2f
                            val pillHalf = slot / 2f - PillInset.toPx()
                            val startOnPill = abs(down.position.x - pillX.value) <= pillHalf
                            val slop = viewConfiguration.touchSlop
                            var totalX = 0f
                            var totalY = 0f
                            var axis = 0 // 0 未定 / 1 胶囊
                            var grabbed = false
                            var pointerDown = true
                            var liveTarget = -1
                            // 长按用「另一个协程等 500ms」实现：AwaitPointerEventScope 是受限
                            // 挂起作用域，不能把 awaitPointerEvent 包进别的 suspend lambda。
                            val longPressJob = scope.launch {
                                delay(viewConfiguration.longPressTimeoutMillis)
                                if (pointerDown && axis == 0 && startOnPill) {
                                    grabbed = true
                                    gesturePressed = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                            val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                            try {
                                while (true) {
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
                                            // 只有起手在胶囊上（或长按过胶囊）才横拖；
                                            // 竖向一律不接管：底栏是固定的。
                                            horizontal && (startOnPill || grabbed) -> {
                                                axis = 1
                                                dragging = true
                                                gesturePressed = true
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                tracker.resetTracking()
                                                tracker.addPosition(change.uptimeMillis, change.position)
                                            }
                                            else -> if (vertical || horizontal) break
                                        }
                                    }
                                    if (axis == 1) {
                                        change.consume()
                                        tracker.addPosition(change.uptimeMillis, change.position)
                                        scope.launch {
                                            val next = (pillX.value + d.x).coerceIn(minX, maxX)
                                            pillX.snapTo(next)
                                            // 实时切页：拖到哪一格就显示哪一页。
                                            val live = (next / slot).toInt().coerceIn(0, count - 1)
                                            if (live != liveTarget) {
                                                liveTarget = live
                                                items.getOrNull(live)?.let { onSelect(it.id) }
                                            }
                                        }
                                    }
                                }
                                if (axis == 1) {
                                    dragVelocity = tracker.calculateVelocity().x
                                    val target = (pillX.value / slot).toInt().coerceIn(0, count - 1)
                                    items.getOrNull(target)?.let { if (it.id != selectedId) onSelect(it.id) }
                                    scope.launch {
                                        pillX.animateTo(
                                            slot * (target + 0.5f),
                                            DockMotion.bouncy(),
                                            initialVelocity = dragVelocity,
                                        )
                                    }
                                }
                            } finally {
                                pointerDown = false
                                longPressJob.cancel()
                                dragging = false
                                gesturePressed = false
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                items.forEach { item ->
                    DockButton(
                        item = item,
                        selected = item.id == selectedId,
                        showLabel = showLabel,
                        colors = colors,
                        iconSize = config.iconSize,
                        labelSize = config.labelSize,
                        liquid = liquid,
                        onClick = {
                            if (item.id != selectedId) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSelect(item.id)
                            }
                        },
                        onPressChanged = { p -> childPressed = p },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ---- 第 3 层：胶囊（画在内容之上） ----
            val bmp = contentBitmap
            if (config.indicator == DockIndicator.Capsule && slotPx > 0f && bmp != null) {
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { c ->
                        val insetPx = PillInset.toPx()
                        val baseHalfW = (w / count.toFloat() - insetPx * 2f) / 2f
                        val baseHalfH = (h - insetPx * 2f) / 2f
                        val cx = pillX.value
                        val cy = h / 2f
                        // LiquidTab drawSelection：位移速度反向缩放宽/高，拖起来像液体。
                        val velocity = (if (dragging) dragVelocity else pillX.velocity) / 10f
                        val sx = pressScale.value / (1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f))
                        val sy = pressScale.value * (1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f))
                        glass.drawPill(
                            canvas = c.nativeCanvas,
                            base = backdrop?.blurred,
                            content = bmp,
                            sample = DockBackdrop.SAMPLE,
                            winLeft = panelWindow.left,
                            winTop = panelWindow.top,
                            // 内容截图和画布同源（原点都在底栏左上角），所以是 0。
                            contentLeft = 0f,
                            contentTop = 0f,
                            left = cx - baseHalfW * sx,
                            top = cy - baseHalfH * sy,
                            right = cx + baseHalfW * sx,
                            bottom = cy + baseHalfH * sy,
                            dark = dark,
                            liquid = liquid,
                            press = press.value,
                            tiltX = tilt,
                            fillColor = fillColor,
                            fillAlpha = config.fillAlpha,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 底栏上方的「底部渐变模糊」：把磨砂图铺在页面底部，再用竖直渐变遮罩，
 * 越靠近底栏越实、越往上越透。
 */
@Composable
private fun BottomGradientVeil(
    snapshot: Bitmap?,
    tick: Int,
    veilWindow: Rect,
    tint: Color,
    dark: Boolean,
    glass: LiquidDockGlass,
    modifier: Modifier = Modifier,
) {
    val mask = remember { android.graphics.Paint() }

    Canvas(modifier) {
        // 读 tick：背景换帧时重新画。
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
                sample = DockBackdrop.SAMPLE,
                winLeft = veilWindow.left,
                winTop = veilWindow.top,
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
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to tint.copy(alpha = if (dark) 0.10f else 0.16f),
            ),
            size = Size(size.width, size.height),
        )
    }
}

/** 一格：图标 +（可选）文字 + 按下缩放。 */
@Composable
private fun DockButton(
    item: DockItem,
    selected: Boolean,
    showLabel: Boolean,
    colors: DockColors,
    iconSize: Dp,
    labelSize: TextUnit,
    liquid: Boolean,
    onClick: () -> Unit,
    onPressChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    LaunchedEffect(isPressed) { onPressChanged(isPressed) }

    val tint = when {
        selected && colors.isDark -> Color.White
        selected -> colors.accent
        else -> colors.label.copy(alpha = 0.48f)
    }
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = DockMotion.snappy(),
        label = "dockIcon",
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .dockPressScale(interaction, scaleDown = if (liquid) 0.94f else 1f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer { scaleX = scale; scaleY = scale },
        )
        if (showLabel) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.label,
                color = tint,
                fontSize = labelSize,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

package com.localmind.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * 全局动效语言。
 *
 * ## 为什么单独一个文件
 *
 * 这个项目原来整个 UI 只有 19 处动画引用 —— 基本等于"没有动画"，切换、出现、
 * 按下去都是硬跳。iOS 的"顺"一半来自动效，不是来自颜色。把曲线和时长集中
 * 定义在这里，才能保证**所有**过渡用的是同一套手感；散落在各个 screen 里各写
 * 各的 `tween(200)`，最后就是有的地方弹、有的地方木。
 *
 * ## 数值怎么来的
 *
 * 对着 iOS 26 的实际手感调的，不是抄某个文档：
 *
 * | 用途 | 效果 | 手感 |
 * | --- | --- | --- |
 * | [smooth] | 无回弹，快速收敛 | 用于**跟手**的东西：滑块、开关、透明度 |
 * | [snappy] | 极小回弹 | 用于**进入**的东西：列表项、气泡、卡片 |
 * | [bouncy] | 明显回弹 | 只用于**强调**：选中态、按钮按下弹回 |
 * | [gentle] | 慢而软 | 用于**大块布局**：整页推入、Sheet 升起 |
 *
 * 关键约定：**回弹幅度与位移成正比**。一个 4dp 的位移配 0.6 的回弹会看起来像
 * 在抖；所以小位移用 [smooth]，大位移才用 [bouncy]。
 */
object Motion {

    /** iOS 的默认缓动近似：起步快、尾巴长。 */
    val iosEase: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    /** 松手回弹用的曲线。 */
    val iosEaseOut: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 无回弹。跟手元素（滑块、开关）用它，加了回弹会显得"黏"。 */
    fun <T> smooth(durationMillis: Int = 220): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = iosEaseOut)

    /** 轻回弹。列表项、气泡、卡片出现的默认选择。 */
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.82f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** 明显回弹。只给强调用。 */
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.55f,
        stiffness = Spring.StiffnessMedium,
    )

    /** 慢而软。整页推入、Sheet 升起用。 */
    fun <T> gentle(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.9f,
        stiffness = Spring.StiffnessLow,
    )

    /** 列表项逐个出现的错峰间隔。太大会显得慢，太小看不出层次。 */
    const val STAGGER_MS = 28

    /** 按下时缩到多小。0.96 是 iOS 按钮的典型值；再小就开始显得廉价。 */
    const val PRESS_SCALE = 0.96f

    /** 整页推入/推出版位移，按屏幕宽度比例算，适配不同屏宽。 */
    const val SCREEN_SLIDE_FRACTION = 0.28f
}

/**
 * 入场动画：从下方轻微上浮 + 淡入 + 从略小放大。
 *
 * 三件事一起做才像 iOS 的"出现"。只做淡入会显得平；只做位移会显得飘。
 *
 * @param index 列表中的序号，用来做错峰（stagger）。传 0 就是不延迟。
 */
fun Modifier.appearIn(
    visible: Boolean = true,
    index: Int = 0,
    offsetY: Int = 14,
): Modifier = composed {
    // 用 Animatable 而不是 animateFloatAsState：需要"出现时跑一次"而不是
    // "跟随一个状态值动画"，两者语义不同。
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) {
            progress.animateTo(1f, Motion.snappy())
        } else {
            progress.snapTo(0f)
        }
    }
    val delayFraction = (index.coerceAtMost(8) * Motion.STAGGER_MS) / 300f
    val p = ((progress.value - delayFraction) / (1f - delayFraction)).coerceIn(0f, 1f)

    this
        .graphicsLayer {
            alpha = p
            translationY = (1f - p) * offsetY * density
            val s = 0.97f + 0.03f * p
            scaleX = s
            scaleY = s
        }
}

/**
 * 整页推入：从右侧滑入。
 *
 * [progress] 0 = 完全在屏幕右侧外，1 = 归位。做成外部驱动的进度，
 * 是为了让它可以和手势（边缘拖拽返回）共用同一条进度值。
 */
fun Modifier.screenSlideIn(progress: Float, screenWidthPx: Float): Modifier = composed {
    this.graphicsLayer {
        translationX = (1f - progress) * screenWidthPx * Motion.SCREEN_SLIDE_FRACTION
        alpha = 0.6f + 0.4f * progress
    }
}

/** 当前是否处于"正在被按下"状态，给需要自定义反馈的组件用。 */
@Composable
fun rememberPressed(interactionSource: MutableInteractionSource): Boolean {
    val pressed by interactionSource.collectIsPressedAsState()
    return pressed
}

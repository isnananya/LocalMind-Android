package com.localmind.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 按下时轻微缩小的反馈（iOS 的"按下去有反应"）。
 *
 * ## 为什么用缩放而不是颜色
 *
 * 颜色反馈会和主题打架：插件可以改主色、改气泡、改填充，一个写死的
 * `pressedBackground` 在某些配色下会变得看不出来。缩放不依赖任何颜色令牌，
 * 在任何主题下都成立，而且它是**物理感**的来源 —— 大厂 App 的"丝滑"里
 * 有相当一部分就是这种 1~3% 的形变。
 *
 * ## 参数
 *
 * @param interaction 和 clickable 共用的 [MutableInteractionSource]。**必须共用**，
 *   否则按下的状态传不过来（各建一个就会出现"点了但不动"）。
 * @param enabled false 时完全不动（禁用按钮不该有反馈）。
 */
fun Modifier.pressScale(
    interaction: MutableInteractionSource?,
    enabled: Boolean = true,
    scaleDown: Float = 0.972f,
): Modifier = if (interaction == null || !enabled) this else composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

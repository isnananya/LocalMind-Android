package com.example.liquiddock

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
 * 按下轻微缩小的反馈。
 *
 * 用缩放而不是换色：插件/主题可以改任何颜色，缩放始终成立，而且「1~3% 的形变」
 * 正是手感的一部分。
 *
 * @param interaction 必须和 [androidx.compose.foundation.clickable] 共用同一个
 *   [MutableInteractionSource]，否则按下状态传不过来。
 * @param scaleDown 按下时的缩放比。1f = 完全不动。
 */
internal fun Modifier.dockPressScale(
    interaction: MutableInteractionSource?,
    scaleDown: Float = 0.94f,
): Modifier = if (interaction == null || scaleDown == 1f) this else composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "dockPressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

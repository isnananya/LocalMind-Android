package com.example.liquiddock

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * 底栏用到的两档弹簧。
 *
 * 数值和原软件一致：胶囊吸附用 0.55 阻尼（明显回弹，像液体），
 * 图标缩放用 0.82（几乎不回弹，只负责「跟上」）。
 */
internal object DockMotion {

    /** 胶囊吸附 / 位移动画：明显回弹。 */
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.55f,
        stiffness = Spring.StiffnessMedium,
    )

    /** 图标、颜色等小变化：轻回弹。 */
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.82f,
        stiffness = Spring.StiffnessMediumLow,
    )
}

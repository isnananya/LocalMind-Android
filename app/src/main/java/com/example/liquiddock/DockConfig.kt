package com.example.liquiddock

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底栏里的一格。
 *
 * 图标用 Compose 的 [ImageVector]（Material Icons / 自己画的 vector 都行），
 * 组件本身不引用任何图标资源，方便整包复制到别的工程。
 */
@Immutable
data class DockItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

/** 底栏画法：液态玻璃（默认）或纯色。 */
enum class DockStyle {
    /** 磨砂底板 + 边缘折射 + 高光描边 + 液态选中胶囊。 */
    Glass,

    /** 纯色圆角底板，选中态只留色块；用于低端机 / 不想用模糊的场景。 */
    Plain,
}

/** 面板宽度：紧凑悬浮胶囊，或者左右各留 [DockConfig.sideMargin] 的通栏。 */
enum class DockWidth { Compact, Full }

/** 面板形状。Capsule 用高度的一半做圆角，Rounded 用 [DockConfig.roundedCorner]。 */
enum class DockShape { Capsule, Rounded, Square }

/** 选中指示器。Capsule 是液态胶囊；其余是轻量的静态指示。 */
enum class DockIndicator { Capsule, Underline, Dot, None }

/** 底栏配色。四个值就够了，玻璃的其余部分（白/黑叠层）由内核按明暗自动推。 */
@Immutable
data class DockColors(
    val isDark: Boolean,
    val accent: Color,
    val label: Color,
    /** 面板填充色：浅色用白，深色用近黑的紫灰（和 LiquidTab 的 surfaceContainer 一致）。 */
    val surface: Color,
) {
    companion object {
        val Light = DockColors(
            isDark = false,
            accent = Color(0xFF0A84FF),
            label = Color(0xFF0B0B0F),
            surface = Color(0xFFFFFFFF),
        )

        val Dark = DockColors(
            isDark = true,
            accent = Color(0xFF0A84FF),
            label = Color(0xFFF5F5F7),
            surface = Color(0xFF211F26),
        )
    }
}

/**
 * 液态底栏的全部可调参数。
 *
 * 默认值 = 原软件（LocalMind）出厂设置，也是调得最久的一组：
 *
 * | 参数 | 默认 | 说明 |
 * | --- | --- | --- |
 * | [height] | 56dp | 面板高度，圆角胶囊时等于高度的一半 |
 * | [slotWidth] | 76dp | 单格宽度，面板宽 = 2×面板内缩 + 格宽 × 格数 |
 * | [sideMargin] | 18dp | 悬浮时左右最小留白 |
 * | [bottomGap] | 12dp | 面板底边距屏幕底部的距离（不含系统导航栏） |
 * | [blurRadius] | 10dp | 背景磨砂半径（屏幕像素换算后裁剪到 0..48） |
 * | [fillAlpha] | 0.40 | 面板填充不透明度：40% 以内还能看出磨砂，再高就成实心卡片 |
 * | [highlight] | 0.5 | 高光描边强度倍率（0.5..1.7） |
 */
@Immutable
data class DockConfig(
    val style: DockStyle = DockStyle.Glass,
    val indicator: DockIndicator = DockIndicator.Capsule,
    val shape: DockShape = DockShape.Capsule,
    val width: DockWidth = DockWidth.Compact,
    val colors: DockColors = DockColors.Light,
    val height: Dp = 56.dp,
    val slotWidth: Dp = 76.dp,
    val sideMargin: Dp = 18.dp,
    val bottomGap: Dp = 12.dp,
    /** [DockShape.Rounded] 用的圆角。 */
    val roundedCorner: Dp = 24.dp,
    /** 背景磨砂半径，单位 dp。0 = 不磨砂（仍会抓图，折射还在）。 */
    val blurRadius: Float = 10f,
    /** 面板填充透明度。LiquidTab 原值是 0.40。 */
    val fillAlpha: Float = 0.40f,
    val showOutline: Boolean = true,
    val highlight: Float = 0.5f,
    /** 关掉就只剩磨砂/色块，没有折射、色散、按压放大。 */
    val liquid: Boolean = true,
    /** 面板上方那块「越往上越透」的底部渐变模糊。 */
    val bottomVeil: Boolean = false,
    val iconOnly: Boolean = false,
    val iconSize: Dp = 23.dp,
    val labelSize: TextUnit = 11.sp,
    /** 抓背景帧的间隔。越小越跟手，越费电。 */
    val backdropIntervalMs: Long = 180L,
) {
    /** 是否走纯色路径（不做玻璃）。 */
    val solid: Boolean get() = style == DockStyle.Plain

    /** 面板圆角。 */
    fun cornerRadius(): Dp = when (shape) {
        DockShape.Capsule -> height / 2
        DockShape.Rounded -> roundedCorner
        DockShape.Square -> 0.dp
    }
}

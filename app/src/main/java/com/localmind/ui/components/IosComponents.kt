package com.localmind.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmind.ui.icons.AppIconsAutoMirrored
import kotlin.math.pow
import com.localmind.ui.theme.IosMetrics
import com.localmind.ui.theme.IosTheme

/**
 * 给定一个底色，挑一个**看得清**的字色（黑或白）。
 *
 * ## 为什么需要它
 *
 * 有几处控件的底色是**调用方传进来的颜色**（`IosPill` 的徽标色、`IosIconBadge`
 * 的图标底、`IosPrimaryButton(color = …)`），不是主题主色。对这些地方，
 * `ios.onAccent` 是不对的 —— 那个是"主色之上的字色"。写死白色更不对：
 * 一个浅绿徽标配白字等于没有字。
 *
 * 所以按底色亮度算：用 sRGB 的相对亮度公式（带 gamma），阈值取 0.55 ——
 * 比"整数平均"准，尤其是绿色和蓝色（人眼对绿最敏感、对蓝最不敏感，
 * 简单平均会把 #34C759 算成暗色从而配白字，实际配深色字更清楚）。
 */
internal fun onColorFor(background: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color {
    fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    val l = 0.2126 * channel(background.red) +
        0.7152 * channel(background.green) +
        0.0722 * channel(background.blue)
    return if (l > 0.55) androidx.compose.ui.graphics.Color(0xE6000000) else androidx.compose.ui.graphics.Color.White
}

/* ------------------------------------------------------------------ */
/*  Inset grouped list (the backbone of every iOS settings screen)     */
/* ------------------------------------------------------------------ */

@Composable
fun IosSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ios = IosTheme.palette
    val shape = RoundedCornerShape(22.dp)
    Column(modifier = modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                text = header,
                style = IosTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
                color = ios.secondaryLabel,
                modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
            )
        } else {
            Spacer(Modifier.height(22.dp))
        }
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .then(
                    if (ios.isDark) Modifier
                    else Modifier.shadow(
                        elevation = 10.dp,
                        shape = shape,
                        ambientColor = Color.Black.copy(alpha = 0.05f),
                        spotColor = Color.Black.copy(alpha = 0.06f),
                    )
                )
                .clip(shape)
                .background(ios.secondaryGroupedBackground)
                .border(0.5.dp, ios.barBorder, shape),
            content = content,
        )
        if (footer != null) {
            Text(
                text = footer,
                style = IosTheme.typography.footnote,
                color = ios.secondaryLabel,
                modifier = Modifier.padding(start = 24.dp, end = 32.dp, top = 8.dp),
            )
        }
    }
}

@Composable
fun IosDivider(startIndent: Int = 16) {
    val ios = IosTheme.palette
    HorizontalDivider(
        modifier = Modifier.padding(start = startIndent.dp),
        thickness = 0.5.dp,
        color = ios.separator,
    )
}

@Composable
fun IosCell(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    enabled: Boolean = true,
    titleColor: Color? = null,
    centerTitle: Boolean = false,
) {
    val ios = IosTheme.palette
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 按下的形变和 clickable 共用同一个 interaction —— 各建一个就"点了不动"。
            .pressScale(interaction, enabled = onClick != null && enabled)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(interactionSource = interaction, indication = ripple(color = ios.gray), onClick = onClick)
                } else Modifier
            )
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = if (centerTitle) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text(
                text = title,
                style = IosTheme.typography.body,
                color = titleColor ?: ios.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = IosTheme.typography.footnote,
                    color = ios.secondaryLabel,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = AppIconsAutoMirrored.KeyboardArrowRight,
                contentDescription = null,
                tint = ios.tertiaryLabel,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Pills, buttons, badges                                             */
/* ------------------------------------------------------------------ */

@Composable
fun IosPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(IosTheme.metrics.pillCorner))
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = IosTheme.typography.caption2,
            fontWeight = FontWeight.SemiBold,
            // 填充态的底色是**传进来的颜色**，不是主题主色，所以不能一律用
            // ios.onAccent —— 那个是"主色之上的字色"。这里按底色亮度挑黑白，
            // 任何颜色都不会出现"看不见的字"。
            color = if (filled) onColorFor(color) else color,
        )
    }
}

@Composable
fun IosPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color? = null,
    loading: Boolean = false,
    shape: Shape = RoundedCornerShape(IosTheme.metrics.controlCorner),
) {
    val ios = IosTheme.palette
    val bg = color ?: ios.blue
    // 按钮文字用**主题声明的**主色之上文字色（accentOn），而不是写死白色。
    // 浅色主色（比如米黄）配白字是看不见的 —— 这正是 accentOn 存在的理由。
    val fg = if (color == null) ios.onAccent else onColorFor(color)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) bg else ios.fill)
            .then(
                if (enabled && !loading) {
                    Modifier.clickable(interactionSource = interaction, indication = ripple(color = fg), onClick = onClick)
                } else Modifier
            )
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = fg,
            )
        } else {
            Text(
                text = text,
                style = IosTheme.typography.headline,
                color = if (enabled) fg else ios.secondaryLabel,
            )
        }
    }
}

@Composable
fun IosSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val ios = IosTheme.palette
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(IosTheme.metrics.controlCorner))
            .background(ios.fillSecondary)
            .then(
                if (enabled) {
                    Modifier.clickable(interactionSource = interaction, indication = ripple(color = ios.gray), onClick = onClick)
                } else Modifier
            )
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = IosTheme.typography.headline, color = if (enabled) ios.blue else ios.secondaryLabel)
    }
}

/** iOS-style switch: green when on, 51x31pt proportions. */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val ios = IosTheme.palette
    val trackColor by animateColorAsState(
        targetValue = if (checked) ios.green else ios.fill,
        label = "switchTrack",
    )
    val thumbOffset by animateFloatAsState(targetValue = if (checked) 20f else 0f, label = "switchThumb")
    Box(
        modifier = Modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(RoundedCornerShape(IosTheme.metrics.pillCorner))
            .background(trackColor)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 2.dp)
                .offset(x = thumbOffset.dp)
                .size(27.dp)
                .clip(RoundedCornerShape(IosTheme.metrics.pillCorner))
                // 滑块圆钮原来写死白色。它落在**填充色**（ios.fill）上，
                // 所以跟卡片色走最稳：深色主题下卡片色就是那个"亮块"。
                .background(IosTheme.palette.secondaryGroupedBackground)
        )
    }
}

/**
 * iOS-style slider row (filled track, white thumb).
 *
 * [enabled] 为 false 时置灰且不可拖 —— 但**仍然画出来**。
 *
 * 为什么不是直接不画：这些滑杆的"不可用"通常来自一个开关（"覆盖全局参数"、
 * "当人模式"）。原来关闭时整条滑杆消失，用户看到的是"这一项没有滑杆"，
 * 而不是"这一项要先打开开关"。留下一条置灰的滑杆，才能让人知道**这里有东西**，
 * 以及它为什么是灰的。
 */
@Composable
fun IosSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    activeColor: Color? = null,
    enabled: Boolean = true,
) {
    val ios = IosTheme.palette
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        enabled = enabled,
        modifier = modifier.alpha(if (enabled) 1f else 0.4f),
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = ios.secondaryGroupedBackground,
            activeTrackColor = activeColor ?: ios.blue,
            inactiveTrackColor = ios.fill,
            disabledThumbColor = ios.secondaryGroupedBackground,
            disabledActiveTrackColor = ios.gray,
            disabledInactiveTrackColor = ios.fill,
        ),
    )
}

@Composable
fun IosProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color? = null,
    track: Color? = null,
) {
    val ios = IosTheme.palette
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(IosTheme.metrics.pillCorner))
            .background(track ?: ios.fill)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(IosTheme.metrics.pillCorner))
                .background(color ?: ios.blue)
        )
    }
}

@Composable
fun IosIconBadge(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Int = 29,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.24f).dp))
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = onColorFor(tint),
            modifier = Modifier.size((size * 0.62f).dp),
        )
    }
}

@Composable
fun IosEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val ios = IosTheme.palette
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = ios.tertiaryLabel, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = IosTheme.typography.title3, color = ios.label)
        Spacer(Modifier.height(6.dp))
        CompositionLocalProvider(LocalContentColor provides ios.secondaryLabel) {
            Text(message, style = IosTheme.typography.subheadline, color = ios.secondaryLabel)
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun IosCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val ios = IosTheme.palette
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .then(
                if (ios.isDark) Modifier
                else Modifier.shadow(
                    elevation = 10.dp,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = 0.05f),
                    spotColor = Color.Black.copy(alpha = 0.06f),
                )
            )
            .clip(shape)
            .background(ios.secondaryGroupedBackground)
            .border(0.5.dp, ios.barBorder, shape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun IosTag(text: String, selected: Boolean, onClick: () -> Unit) {
    val ios = IosTheme.palette
    val bg = if (selected) ios.blue else ios.fillSecondary
    val fg = if (selected) ios.onAccent else ios.label
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(IosTheme.metrics.pillCorner))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, style = IosTheme.typography.footnote, color = fg, fontWeight = FontWeight.Medium)
    }
}

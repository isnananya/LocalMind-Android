package com.localmind.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmind.ui.components.pressScale
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.motion.Motion
import com.localmind.ui.design.LiquidGlassLayer
import com.localmind.ui.design.rememberLiquidGlassSupport
import com.localmind.ui.theme.IosTheme

/**
 * 「Aurora」设计语言。
 *
 * 这一层是 1.0.5 界面重构的唯一视觉入口：页面背景、卡片、分组列表、
 * 分段控件、大标题全部从这里来。目标只有一个 —— 普通用户打开就知道
 * 能干什么，不需要读解释。
 *
 * 三条硬规则（改界面前先读一遍）：
 *  1. **一屏只讲一件事**。首页是对话，模型页是模型，我的页是设置。
 *  2. **不放说明书**。功能自己说话；一句副标题超过 12 个字就该删。
 *  3. **动效只做三处**：页面切换、按下反馈、选中指示器。其余一律不做，
 *     否则会从"丝滑"变成"晃眼"。
 */
object Aurora {
    /** 卡片圆角。20dp 是 iOS 26 分组列表的观感，再大就像玩具。 */
    val CardCorner = 22.dp
    /** 列表行高。 */
    val RowMinHeight = 58.dp
    /** 页面左右留白。 */
    val PagePadding = 20.dp
    /** 底部悬浮栏高度（不含安全区）。 */
    val BarHeight = 62.dp
}

/** 页面底色：极浅的竖向渐变 + 顶部两团几乎看不见的色晕，深色下是纯黑。 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val ios = IosTheme.palette
    val base = Brush.verticalGradient(
        if (ios.isDark) listOf(Color(0xFF04060A), Color(0xFF080B11), Color(0xFF05070B))
        else listOf(Color(0xFFEAF2FF), Color(0xFFF7F9FD), Color(0xFFF1F4F9))
    )
    Box(modifier.fillMaxSize().background(base)) {
        // 大色团：让悬浮玻璃有真实的颜色可以弯折，不再是一块死白。
        Box(
            Modifier
                .size(520.dp)
                .offset(x = (-160).dp, y = (-120).dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            ios.blue.copy(alpha = if (ios.isDark) 0.16f else 0.26f),
                            Color.Transparent,
                        )
                    )
                )
        )
        Box(
            Modifier
                .size(420.dp)
                .offset(x = 220.dp, y = 60.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            ios.purple.copy(alpha = if (ios.isDark) 0.14f else 0.20f),
                            Color.Transparent,
                        )
                    )
                )
        )
        Box(
            Modifier
                .size(360.dp)
                .offset(x = 40.dp, y = 560.dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            (if (ios.isDark) Color(0xFF19C2B0) else Color(0xFF7BD8D0)).copy(alpha = 0.16f),
                            Color.Transparent,
                        )
                    )
                )
        )
        content()
    }
}

/**
 * 子页顶栏：圆形返回 + 居中标题 + 可选右侧按钮。
 *
 * 和 AuroraTitle 分工：标签根页用大标题，被推入的子页用这条，
 * 一眼就能看出这是压在上面的，不是标签。
 */
@Composable
fun AuroraNavBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val ios = IosTheme.palette
    val glassSupported = rememberLiquidGlassSupport()
    Box(modifier.fillMaxWidth()) {
        if (glassSupported) {
            LiquidGlassLayer(
                modifier = Modifier.matchParentSize(),
                cornerRadius = 0.dp,
                refractionHeight = 12.dp,
                blurRadius = 4f,
                tint = ios.groupedBackground,
                tintAlpha = if (ios.isDark) 0.10f else 0.06f,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
        ) {
        Spacer(Modifier.height(com.localmind.ui.components.statusBarTopPadding()))
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AuroraCircleButton(
                icon = com.localmind.ui.icons.AppIconsAutoMirrored.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                size = 36.dp,
                tint = ios.label,
            )
            Text(
                text = title,
                style = IosTheme.typography.headline,
                color = ios.label,
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            if (trailing != null) {
                trailing()
            } else {
                Spacer(Modifier.size(36.dp))
            }
        }
        }
    }
}

/**
 * 页面大标题。
 *
 * [trailing] 放一个圆形操作按钮（新对话 / 刷新），圆按钮比文字按钮更像 iOS。
 */
@Composable
fun AuroraTitle(
    text: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val ios = IosTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Aurora.PagePadding, end = Aurora.PagePadding - 6.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = text,
                style = IosTheme.typography.largeTitle.copy(fontWeight = FontWeight.SemiBold),
                color = ios.label,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = IosTheme.typography.footnote,
                    color = ios.secondaryLabel,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** 圆形图标按钮。底是半透明填充，按下去会缩。 */
@Composable
fun AuroraCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 36.dp,
    filled: Boolean = true,
) {
    val ios = IosTheme.palette
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(size)
            .pressScale(interaction)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(ios.fillSecondary) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: ios.blue,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/** 分组标题。全大写英文那一套不用了 —— 中文没有大小写，放大写只会变得莫名其妙。 */
@Composable
fun AuroraSectionLabel(text: String, modifier: Modifier = Modifier) {
    val ios = IosTheme.palette
    Text(
        text = text,
        style = IosTheme.typography.footnote.copy(fontWeight = FontWeight.Medium),
        color = ios.secondaryLabel,
        modifier = modifier.padding(start = Aurora.PagePadding + 4.dp, top = 24.dp, bottom = 8.dp),
    )
}

/**
 * 分组容器：白卡片 + 发丝描边 + 极轻投影。
 *
 * 深色下不要投影（黑色上投影只会糊掉边缘），靠卡片本身的亮度分层。
 */
@Composable
fun AuroraGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ios = IosTheme.palette
    val shape = RoundedCornerShape(Aurora.CardCorner)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Aurora.PagePadding)
            .then(
                if (ios.isDark) Modifier
                else Modifier.shadow(
                    elevation = 16.dp,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = 0.07f),
                    spotColor = Color.Black.copy(alpha = 0.08f),
                )
            )
            .clip(shape)
            .background(ios.secondaryGroupedBackground.copy(alpha = if (ios.isDark) 0.62f else 0.74f))
            .border(
                0.8.dp,
                if (ios.isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.72f),
                shape,
            ),
        content = content,
    )
}

/** 分组内的一行。左侧可带色块图标，右侧可带值/箭头。 */
@Composable
fun AuroraRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val ios = IosTheme.palette
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Aurora.RowMinHeight)
            .pressScale(interaction, enabled = onClick != null)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = IosTheme.typography.body,
                color = if (danger) ios.red else ios.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = IosTheme.typography.footnote,
                    color = ios.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = AppIcons.ChevronRight,
                contentDescription = null,
                tint = ios.tertiaryLabel,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 分组内的分隔线，左侧缩进对齐文字。 */
@Composable
fun AuroraDivider(indent: Dp = 16.dp) {
    val ios = IosTheme.palette
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .height(0.5.dp)
            .background(ios.separator)
    )
}

/** 彩色圆角图标块。列表行左侧用。 */
@Composable
fun AuroraIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.29f))
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = onTintFor(tint),
            modifier = Modifier.size(size * 0.58f),
        )
    }
}

/** 按底色亮度挑黑/白，避免浅色图标块上出现看不见的图标。 */
fun onTintFor(background: Color): Color {
    val luminance = 0.2126f * background.red + 0.7152f * background.green + 0.0722f * background.blue
    return if (luminance > 0.62f) Color(0xE6000000) else Color.White
}

/** 药丸按钮。主按钮用它，次要操作用 [AuroraTextButton]。 */
@Composable
fun AuroraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color? = null,
    loading: Boolean = false,
) {
    val ios = IosTheme.palette
    val bg = color ?: ios.blue
    // 半透明的"填充色"按钮（次要操作）不能按不透明色的亮度挑字色，
    // 否则浅填充上会出现白字。这一条在模型页的「导入文件」上实测过。
    val fg = when {
        color == null -> ios.onAccent
        bg.alpha < 0.5f -> ios.label
        else -> onTintFor(bg)
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(42.dp)
            .pressScale(interaction, enabled = enabled)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) bg else ios.fill)
            .then(
                if (enabled && !loading) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = fg,
            )
        } else {
            Text(
                text = text,
                style = IosTheme.typography.headline,
                color = if (!enabled) ios.secondaryLabel else fg,
            )
        }
    }
}

/** 纯文字按钮，次要动作用。 */
@Composable
fun AuroraTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
    enabled: Boolean = true,
) {
    val ios = IosTheme.palette
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressScale(interaction, enabled = enabled)
            .clip(RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = IosTheme.typography.subheadline,
            color = if (enabled) (color ?: ios.blue) else ios.tertiaryLabel,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 分段控件（未安装 / 已安装这种二选一）。
 *
 * 选中指示器用**位移**而不是换底色：连续滑动比颜色跳变更像原生控件。
 */
@Composable
fun AuroraSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ios = IosTheme.palette
    if (options.isEmpty()) return
    val shape = RoundedCornerShape(12.dp)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Aurora.PagePadding)
            .height(36.dp)
            .clip(shape)
            .background(ios.fillSecondary),
    ) {
        val itemWidth = maxWidth / options.size
        Box(
            Modifier
                .padding(3.dp)
                .width(itemWidth - 6.dp)
                .fillMaxHeight()
                .offset(x = itemWidth * selected)
                .then(if (ios.isDark) Modifier else Modifier.shadow(6.dp, RoundedCornerShape(9.dp)))
                .clip(RoundedCornerShape(9.dp))
                .background(if (ios.isDark) Color.White.copy(alpha = 0.14f) else ios.secondaryGroupedBackground),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = IosTheme.typography.subheadline,
                        color = if (index == selected) ios.label else ios.secondaryLabel,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 搜索框：一个安静的胶囊，不抢标题的注意力。 */
@Composable
fun AuroraSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val ios = IosTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Aurora.PagePadding, vertical = 8.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ios.fillSecondary)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Search, contentDescription = null, tint = ios.tertiaryLabel, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        BasicTextFieldCompat(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Icon(
                AppIcons.Close,
                contentDescription = "清空",
                tint = ios.tertiaryLabel,
                modifier = Modifier
                    .size(15.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onValueChange("") },
            )
        }
    }
}

@Composable
private fun BasicTextFieldCompat(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val ios = IosTheme.palette
    Box(modifier) {
        if (value.isEmpty()) {
            Text(placeholder, style = IosTheme.typography.subheadline, color = ios.tertiaryLabel)
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = IosTheme.typography.subheadline.copy(color = ios.label),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(ios.blue),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 空态：一个柔软的大图标 + 一句话 + 一个动作。不写长说明。 */
@Composable
fun AuroraEmpty(
    icon: ImageVector,
    title: String,
    message: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val ios = IosTheme.palette
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(ios.blue.copy(alpha = 0.09f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = ios.blue.copy(alpha = 0.75f), modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = IosTheme.typography.headline, color = ios.label)
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = IosTheme.typography.subheadline,
                color = ios.secondaryLabel,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

/** 小圆点状态。 */
@Composable
fun AuroraDot(color: Color, size: Dp = 7.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

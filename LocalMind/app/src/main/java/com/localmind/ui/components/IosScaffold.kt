package com.localmind.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import com.localmind.ui.Tab
import com.localmind.ui.icons.AppIcons
import com.localmind.ui.theme.IosTheme

/**
 * iOS navigation bar. 52dp so the title and buttons are not cramped.
 */
@Composable
fun IosNavigationBar(
    title: String,
    collapsed: Boolean,
    topInset: Dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val ios = IosTheme.palette
    val metrics = IosTheme.metrics
    val alignLeading = com.localmind.ui.theme.LocalUiTokens.current["navTitleAlign"] == "leading"
    val titleStyle = IosTheme.typography.headline
    val glassSupported = com.localmind.ui.design.rememberLiquidGlassSupport()

    Box(Modifier.fillMaxWidth()) {
        if (glassSupported) {
            com.localmind.ui.design.LiquidGlassLayer(
                modifier = Modifier.matchParentSize(),
                cornerRadius = 0.dp,
                refractionHeight = 12.dp,
                blurRadius = 4f,
                tint = ios.barBackground,
                tintAlpha = if (ios.isDark) 0.10f else 0.06f,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
        ) {
            Spacer(Modifier.height(topInset))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.navBarHeight)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (alignLeading) Arrangement.Start else Arrangement.Center,
            ) {
                Box(Modifier.width(84.dp), contentAlignment = Alignment.CenterStart) {
                    if (leading != null) leading() else Spacer(Modifier.width(1.dp))
                }
                Box(
                    Modifier.weight(1f),
                    contentAlignment = if (alignLeading) Alignment.CenterStart else Alignment.Center,
                ) {
                    if (collapsed) {
                        Text(
                            text = title,
                            style = titleStyle,
                            color = ios.label,
                            maxLines = 1,
                            modifier = if (alignLeading) Modifier.padding(start = 4.dp) else Modifier,
                        )
                    }
                }
                Box(Modifier.width(84.dp), contentAlignment = Alignment.CenterEnd) {
                    if (trailing != null) trailing()
                }
            }
            if (metrics.barBorderWidth > 0.dp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(metrics.barBorderWidth)
                        .background(Color.Transparent)
                )
            }
        }
    }
}

@Composable
fun IosLargeTitle(title: String, modifier: Modifier = Modifier) {
    val ios = IosTheme.palette
    Text(
        text = title,
        style = IosTheme.typography.largeTitle.copy(fontWeight = FontWeight.SemiBold),
        color = ios.label,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
    )
}

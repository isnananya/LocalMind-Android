package com.localmind.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 主题缩略图：用**这个主题自己的令牌**画一个迷你界面。
 *
 * ## 为什么不用一张图片
 *
 * 主题是**声明式令牌**（颜色 + 圆角 + 气泡形状 + 背景风格），
 * 不是一张图。所以缩略图也得是画出来的 —— 才能如实反映：
 *
 *  * 主色和卡片底色
 *  * 卡片圆角（直角 vs 大圆角，一眼能看出）
 *  * 气泡形状（圆 / 直角 / 带尾巴）
 *  * 背景风格（纯色 / 渐变）
 *
 * 用图片做预览，主题改了图不会变 —— 那是假的预览。
 *
 * @param tokens 这个主题的令牌表（浅色或深色那一套）。
 */
@Composable
fun ThemeThumbnail(
    tokens: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    val c = tokens["accent"]?.let { parseHexColor(it) } ?: Color(0xFF0A84FF)
    val onAccent = tokens["accentOn"]?.let { parseHexColor(it) } ?: Color.White
    val pageBg = tokens["groupedBackground"]?.let { parseHexColor(it) } ?: Color(0xFFF7F7F9)
    val cardBg = tokens["cardBackground"]?.let { parseHexColor(it) } ?: Color.White
    val label = tokens["label"]?.let { parseHexColor(it) } ?: Color(0xFF0B0B0F)
    val secondaryLabel = tokens["secondaryLabel"]?.let { parseHexColor(it) } ?: Color(0xFF8A3C3C43)

    val cardCorner = tokens["cardCorner"]?.toFloatOrNull()?.coerceIn(0f, 40f) ?: 12f
    val bubbleCorner = tokens["bubbleCorner"]?.toFloatOrNull()?.coerceIn(0f, 32f) ?: 18f
    val bubbleStyle = tokens["bubbleStyle"] ?: "rounded"
    val bgStyle = tokens["bgStyle"] ?: "aurora"

    val bubbleShape = when (bubbleStyle) {
        "square" -> RoundedCornerShape(0.dp)
        "tail" -> RoundedCornerShape(topStart = bubbleCorner.dp, topEnd = bubbleCorner.dp, bottomEnd = 3.dp, bottomStart = bubbleCorner.dp)
        else -> RoundedCornerShape(bubbleCorner.dp)
    }

    val pageBrush = when (bgStyle) {
        "flat" -> null
        "gradient" -> Brush.linearGradient(listOf(c.copy(alpha = 0.16f), pageBg))
        else -> Brush.linearGradient(
            listOf(
                c.copy(alpha = 0.22f),
                Color.Transparent,
                pageBg,
            )
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .then(if (pageBrush != null) Modifier.background(pageBrush) else Modifier.background(pageBg))
            .padding(8.dp),
    ) {
        Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp)) {
            // 顶部条：模拟导航栏
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(cardBg.copy(alpha = 0.9f))
                    .border(0.5.dp, label.copy(alpha = 0.06f), RoundedCornerShape(5.dp))
            )
            // 一张卡片
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .clip(RoundedCornerShape(cardCorner.dp))
                    .background(cardBg)
                    .border(
                        if (bubbleStyle == "tail") 1.2.dp else 0.8.dp,
                        label.copy(alpha = 0.10f),
                        RoundedCornerShape(cardCorner.dp),
                    )
                    .padding(horizontal = 5.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // 卡片里两行"文字"
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(
                        Modifier
                            .width(34.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(label.copy(alpha = 0.34f))
                    )
                    Box(
                        Modifier
                            .width(22.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(secondaryLabel.copy(alpha = 0.30f))
                    )
                }
            }
            // 两条气泡：左边 AI、右边用户
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(15.dp)
                        .clip(bubbleShape)
                        .background(cardBg)
                        .border(0.7.dp, label.copy(alpha = 0.10f), bubbleShape)
                )
                Box(
                    Modifier
                        .width(32.dp)
                        .height(15.dp)
                        .clip(bubbleShape)
                        .background(c)
                )
            }
        }
    }
}

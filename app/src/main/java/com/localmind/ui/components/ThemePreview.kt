package com.localmind.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 主题的**微缩预览**：一小块真的按这套配色画出来的界面。
 *
 * ## 为什么不能只用四个色块
 *
 * 上一版每个主题旁边是四根竖条纹。问题是**它看不出风格**：任何一个配色都能
 * 拼出四根条纹，用户想知道"这个主题长什么样"只能装上试、不满意再换回来。
 * 而"暖橙"和"纸张"的差别根本不在颜色本身，在于**它们怎么搭配**。
 *
 * 所以这里画一小段仿真的界面：标题条、卡片、主色按钮、用户气泡、AI 气泡。
 * 五个元素就能把一套配色的性格交代清楚，而且每个预览用的都是那套主题
 * **自己声明的**颜色，不是当前生效的颜色。
 */
@Composable
fun ThemePreview(
    background: Color,
    card: Color,
    accent: Color,
    accentOn: Color,
    label: Color,
    separator: Color,
    cornerRadius: Float,
    modifier: Modifier = Modifier,
) {
    val corner = cornerRadius.coerceIn(0f, 16f).dp
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background),
    ) {
        Column(Modifier.fillMaxSize().padding(9.dp)) {
            // 导航条：一小段标题
            Box(
                Modifier
                    .width(34.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(label.copy(alpha = 0.55f))
            )
            Spacer(Modifier.height(8.dp))

            // 卡片 + 主色按钮
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(RoundedCornerShape(corner))
                    .background(card)
                    .border(0.5.dp, separator, RoundedCornerShape(corner))
                    .padding(6.dp),
            ) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(label.copy(alpha = 0.35f))
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(9.dp)
                            .clip(RoundedCornerShape(corner.coerceAtMost(5.dp)))
                            .background(accent)
                    )
                }
            }
            Spacer(Modifier.height(7.dp))

            // 两个气泡：AI 在左、用户在右 —— 和真实聊天里的方向一致，
            // 这样"用户气泡是什么颜色"一眼就能看到。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Box(
                    Modifier
                        .width(46.dp)
                        .height(13.dp)
                        .clip(RoundedCornerShape(corner))
                        .background(accent)
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .width(58.dp)
                        .height(13.dp)
                        .clip(RoundedCornerShape(corner))
                        .background(card)
                        .border(0.5.dp, separator, RoundedCornerShape(corner))
                )
            }
        }
    }
}

/**
 * 从一份令牌表里取出预览要用的六个颜色。
 *
 * 缺的值按 [fallback] 那一套补 —— 主题通常只声明**改了什么**，
 * 没声明的部分就是内置值，预览必须按这个规则还原，否则会和实际效果不一致。
 */
fun themePreviewColors(
    lookup: (String) -> String?,
    fallback: com.localmind.ui.theme.IosPalette,
): List<Color> {
    fun c(key: String, fb: Color): Color =
        lookup(key)?.let { runCatching { com.localmind.ui.theme.parseHexColor(it) }.getOrNull() } ?: fb
    return listOf(
        c("groupedBackground", fallback.groupedBackground),
        c("cardBackground", fallback.secondaryGroupedBackground),
        c("accent", fallback.blue),
        c("accentOn", fallback.onAccent),
        c("label", fallback.label),
        c("separator", fallback.separator),
    )
}

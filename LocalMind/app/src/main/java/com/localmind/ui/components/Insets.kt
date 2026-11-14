package com.localmind.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Height of the status bar, in dp — the iOS "safe area top inset". */
@Composable
fun statusBarTopPadding(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** Height of the gesture/navigation bar. */
@Composable
fun navigationBottomPadding(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** Spacer that pushes content below the status bar. */
@Composable
fun topSafeSpacer() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = statusBarTopPadding())
    )
}

/**
 * 浮动标签栏在滚动内容底部**吃掉的高度**。
 *
 * ## 为什么需要它
 *
 * 底部标签栏是**浮在内容之上**的（iOS 26 的胶囊形，故意做成半透明，
 * 让内容从底下滚过去）。代价是：任何可滚动页面的最后一项都会停在标签栏背后，
 * 看不全也点不到 —— 只有在列表够长、滚到底的时候才会发现。
 *
 * 所以根标签页的列表底部留白要用这个值。**子页（语音/生图/插件/对话设置）
 * 不用**：那几页调用了 [scrollBottomPadding]，而它会根据标签栏是否可见返回 0。
 *
 * 值的构成：胶囊高度 + 上下两个 10dp 的间距 + 一点余量。
 * 不写死 62dp 是因为主题可以改标签栏高度（`tabBarHeight` 令牌）。
 */
@Composable
fun rootTabBarClearance(): Dp {
    val metrics = com.localmind.ui.theme.IosTheme.metrics
    return metrics.tabBarHeight + 20.dp + 8.dp
}

/**
 * 可滚动页面底部该留多少白。
 *
 * @param tabBarVisible 这一页显示时底部标签栏是否在场。根标签页传 true
 *   （LocalMindRoot 里那几个），子页传 false。**不确定时传 true** ——
 *   多留 90dp 白只是不好看，少留就是内容被挡住。
 * @param base 这一页原本想要的留白（没有标签栏时的值）。
 */
@Composable
fun scrollBottomPadding(tabBarVisible: Boolean = false, base: Dp = 32.dp): Dp =
    if (tabBarVisible) base + rootTabBarClearance() else base

package com.localmind.ui.design

import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.localmind.ui.theme.LocalUiTokens
import com.qmdeve.liquidglass.widget.LiquidGlassView

/**
 * 是否能用 AndroidLiquidGlassView 的真实折射。
 *
 * 库依赖 RenderEffect / RuntimeShader：Android 13+，且必须是硬件渲染。
 * 模拟器用 SwiftShader 时创建 RuntimeShader 会抛 "Software rendering doesn't
 * support RuntimeShader"，这里提前探一次，失败就回退到内置玻璃，避免闪退。
 */
@Composable
fun rememberLiquidGlassSupport(): Boolean {
    // 实测：AndroidLiquidGlassView 在单棵 ComposeView 内会重入 RenderNode
    // （Recording currently in progress），启动即崩。正确接法需要把玻璃层
    // 放到 ComposeView 之外，让采样源不包含玻璃自己；这项改造尚未完成，
    // 先关闭，统一走内置玻璃，保证可用。
    return false
}

/** 向上找承载 Compose 的 ComposeView。液态玻璃要采样它。 */
private fun findComposeHost(view: View): ViewGroup? {
    var v: View? = view
    while (v != null) {
        if (v is androidx.compose.ui.platform.ComposeView) return v
        val parent = v.parent
        v = if (parent is View) parent else null
    }
    return (view.parent as? ViewGroup)
}

/**
 * 用 AndroidLiquidGlassView 铺一层真实的液态玻璃（折射 + 色散 + 模糊）。
 *
 * 只在 [rememberLiquidGlassSupport] 为 true 时调用；否则调用方走内置降级。
 * 采样源是 ComposeView 本身：玻璃会显示它下面的内容，并做边缘弯折。
 */
@Composable
fun LiquidGlassLayer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    refractionHeight: Dp = 20.dp,
    refractionOffset: Dp = 70.dp,
    dispersion: Float = 0.5f,
    blurRadius: Float = 6f,
    tint: Color = Color.White,
    tintAlpha: Float = 0.08f,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val host = remember(view) { findComposeHost(view) }
    val cornerPx = with(density) { cornerRadius.toPx() }
    val refH = with(density) { refractionHeight.toPx() }
    val refO = with(density) { refractionOffset.toPx() }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            LiquidGlassView(ctx).apply {
                setCornerRadius(cornerPx)
                setRefractionHeight(refH)
                setRefractionOffset(refO)
                setDispersion(dispersion)
                setBlurRadius(blurRadius)
                setTintColorRed(tint.red)
                setTintColorGreen(tint.green)
                setTintColorBlue(tint.blue)
                setTintAlpha(tintAlpha)
                host?.let { bind(it) }
            }
        },
        update = { v ->
            v.setCornerRadius(cornerPx)
            v.setRefractionHeight(refH)
            v.setRefractionOffset(refO)
            v.setDispersion(dispersion)
            v.setBlurRadius(blurRadius)
            v.setTintAlpha(tintAlpha)
        },
    )
}

/** 没有硬件折射时的统一降级：半透明 + 细边（不闪、不空白）。 */
@Composable
fun LiquidGlassFallback(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tint: Color = Color.White,
    alpha: Float = 0.55f,
) {
    val ios = com.localmind.ui.theme.IosTheme.palette
    Box(
        modifier
            .fillMaxSize()
            .background(
                tint.copy(alpha = if (ios.isDark) alpha * 0.35f else alpha),
                androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius),
            )
    )
}

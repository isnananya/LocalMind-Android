package com.localmind.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.localmind.ui.theme.DynamicAccent
import com.localmind.ui.theme.LocalMindTheme

/** 主题壳：主内容与原生玻璃栏里的 chrome 共用同一份令牌。 */
@Composable
fun AppTheme(viewModel: AppViewModel, content: @Composable () -> Unit) {
    val mode by viewModel.themeMode.collectAsState()
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val themeState by viewModel.uiThemeState.collectAsState()
    val context = LocalContext.current

    // 动态配色：开关打开时用壁纸主色覆盖 accent（用户/预设显式设的 accent 也让位，
    // 因为用户就是主动打开"跟着壁纸走"）。关掉后完全回到令牌表原来的值。
    val dynamicOn = themeState.tokens["dynamicColor"]?.toBooleanStrictOrNull() == true
    val dynamicAccent = remember(dynamicOn, dark) {
        if (dynamicOn) DynamicAccent.accent(context, dark) else null
    }
    val tokens = remember(themeState.tokens, dynamicAccent) {
        if (dynamicAccent == null) themeState.tokens
        else themeState.tokens + DynamicAccent.overrides(dynamicAccent)
    }

    // 系统栏图标的明暗必须跟着**应用内**的深浅色走，而不是只跟系统走。
    // 之前只调了 enableEdgeToEdge()：切到深色后状态栏图标仍然是黑的，
    // 在纯黑背景上等于消失（第一版在模拟器上就是这样）。
    val view = LocalView.current
    LaunchedEffect(dark) {
        val activity = view.context.findActivity() ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    LaunchedEffect(dark) { viewModel.refreshUiTheme(dark) }
    LocalMindTheme(
        darkTheme = dark,
        tokens = tokens,
        themeState = themeState,
        content = content,
    )
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

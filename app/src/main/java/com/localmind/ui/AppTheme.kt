package com.localmind.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.localmind.ui.theme.LocalMindTheme

/** 主题壳：主内容与原生玻璃栏里的 chrome 共用同一份令牌。 */
@Composable
fun AppTheme(viewModel: AppViewModel, content: @Composable () -> Unit) {
    val mode = viewModel.storage.themeMode()
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val themeState by viewModel.uiThemeState.collectAsState()
    LaunchedEffect(dark) { viewModel.refreshUiTheme(dark) }
    LocalMindTheme(
        darkTheme = dark,
        tokens = themeState.tokens,
        themeState = themeState,
        content = content,
    )
}

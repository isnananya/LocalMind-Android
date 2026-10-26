package com.localmind

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.localmind.ui.LocalMindRoot
import com.localmind.ui.theme.LocalMindTheme

class MainActivity : ComponentActivity() {

    /** onCreate 里赋值，onResume / onDestroy 都要用。 */
    private lateinit var app: LocalMindApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        app = LocalMindApp.of(this)
        if (app.storage.keepScreenOn()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        setContent {
            // 深浅色：以用户选的 theme_mode 为准，system 就跟系统走。
            val mode = app.storage.themeMode()
            val dark = when (mode) {
                "dark" -> true
                "light" -> false
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            // 主题要盖住**整个**应用（包括引导页和浮层），所以注入点在这里，
            // 不在 LocalMindRoot。
            //
            // ⚠️ 令牌从 ViewModel 的 StateFlow 收集，**不要**再自己算一遍。
            // 以前这里有第二份解析（rememberPluginUiTokens），而它的 remember 键
            // 不是 Compose 状态 → 换主题后界面永远不重画。见
            // AppViewModel._uiThemeState。
            val viewModel: com.localmind.ui.AppViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            val themeState by viewModel.uiThemeState.collectAsState()

            // 深色和 ViewModel 自己算的那份对齐（系统在运行中切深浅色时这里也会跑）。
            androidx.compose.runtime.LaunchedEffect(dark) {
                viewModel.refreshUiTheme(dark)
            }

            LocalMindTheme(
                darkTheme = dark,
                tokens = themeState.tokens,
                themeState = themeState,
            ) {
                LocalMindRoot(viewModel = viewModel, sharedText = extractSharedText())
            }
        }
    }

    private fun extractSharedText(): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent?.getStringExtra(Intent.EXTRA_TEXT)
    }

    // ---------------------------------------------------------- 会话标记
    //
    // 这三处是**假崩溃的根**：原来 Diagnostics.endSession() 定义了但**零调用点**，
    // 于是每一次退出（用户划掉 / 系统回收 / 真崩溃）都会在下次启动时记一条"崩溃"。
    // 现在前台后台分开记，正常 finish 时清标记，后台被回收只留一行流水。
    //
    // 位置放在 Activity 而不是 Application：单 Activity 应用，生命周期最准。

    override fun onResume() {
        super.onResume()
        runCatching { com.localmind.data.Diagnostics.setForeground(this, true) }
    }

    override fun onPause() {
        runCatching { com.localmind.data.Diagnostics.setForeground(this, false) }
        super.onPause()
    }

    override fun onDestroy() {
        // isFinishing = 用户真的退出了（返回键 / 划掉任务），不是转屏重建。
        if (isFinishing) {
            runCatching { com.localmind.data.Diagnostics.endSession(this) }
        }
        super.onDestroy()
    }

}

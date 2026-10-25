package com.localmind

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.localmind.ui.LocalMindRoot
import com.localmind.ui.theme.LocalMindTheme

class MainActivity : ComponentActivity() {

    /** onCreate 里赋值，requestBasePermissions 与 onDestroy 都要用。 */
    private lateinit var app: LocalMindApp

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results surface through the feature screens themselves */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        app = LocalMindApp.of(this)
        if (app.storage.keepScreenOn()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        requestBasePermissions()

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

    /**
     * 启动时只申请**通知**权限，不申请麦克风。
     *
     * 麦克风改成"按住说话时才申请"（见 VoiceScreen）。理由：全新安装的第一次打开，
     * 用户看到的第一个东西如果是「允许 LocalMind 录音吗？」，他还没搞清楚这个应用
     * 是干什么的就会被问要不要交出麦克风 —— 拒绝是正常反应，而一旦拒绝，系统就
     * 不再弹了，语音功能等于永久失效，他只会觉得"语音是坏的"。
     *
     * 通知权限留着：它只在开启「本机 API 服务」时才有实际作用，但那个时间点
     * 太深了，用户不会主动去找；而这个弹窗本身不含隐私含义。
     */
    private fun requestBasePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        // ⚠️ 通知权限**一辈子只问一次**。
        //
        // Android 13+ 的 POST_NOTIFICATIONS 与别的权限不同：用户点了「不允许」
        // 之后系统**还会**再弹，所以「每次启动都申请」的真实表现是
        // **每次打开应用都弹同一个窗**（模拟器实测：第一次拒绝，第二次启动照弹）。
        // 用户对这个的解读只有一个 —— 这软件像广告。
        //
        // 弹窗本身确实没有隐私含义，但它要有意义：只有开了「本机 API 服务」
        //（前台服务要挂通知）才用得上，所以问到一次就够了，之后想开去系统设置。
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted || app.storage.notificationAsked()) return
        app.storage.setNotificationAsked()
        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
}

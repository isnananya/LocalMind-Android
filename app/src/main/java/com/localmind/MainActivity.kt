package com.localmind

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.localmind.ui.AppTheme
import com.localmind.ui.AppViewModel
import com.localmind.ui.LocalMindRoot
import com.localmind.ui.chrome.GlassChrome
import com.localmind.ui.chrome.NativeGlassBar
import com.localmind.ui.components.LiquidGlassTabBar
import com.qmdeve.liquidglass.widget.LiquidGlassView
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private lateinit var app: LocalMindApp
    private var glassBar: LiquidGlassView? = null
    private var maxLiftPx = 0f
    private var maxSidePx = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        app = LocalMindApp.of(this)
        if (app.storage.keepScreenOn()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val viewModel: AppViewModel by viewModels()
        val nativeBar = NativeGlassBar.supported()
        val root = FrameLayout(this)

        // 页面内容：全屏，底栏不再占它的布局。
        val content = ComposeView(this).apply {
            setContent {
                AppTheme(viewModel) {
                    LocalMindRoot(
                        viewModel = viewModel,
                        sharedText = extractSharedText(),
                        hideTabBar = nativeBar,
                    )
                }
            }
        }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        if (nativeBar) {
            attachGlassBar(root, content, viewModel)
        }

        setContentView(root)
    }

    /**
     * 真·原生液态玻璃底栏。
     *
     * 关键：玻璃是 content 的**兄弟节点**，采样源是 content 而不是包含自己的树，
     * 所以不会触发 RenderNode 重入；chrome 用另一个 ComposeView 画在玻璃上面。
     */
    private fun attachGlassBar(root: FrameLayout, content: ComposeView, viewModel: AppViewModel) {
        val dm = resources.displayMetrics
        val height = (68 * dm.density).toInt()
        val side = (18 * dm.density).toInt()
        val liftPx = (20 * dm.density).toInt()
        maxLiftPx = 140 * dm.density
        maxSidePx = 44 * dm.density

        val chrome = ComposeView(this).apply {
            setContent {
                AppTheme(viewModel) {
                    GlassChrome(
                        viewModel = viewModel,
                        onDrag = { dx, dy -> moveBar(dx, dy) },
                        onDragEnd = { settleBar() },
                    )
                }
            }
        }

        val glass = LiquidGlassView(this).apply {
            setCornerRadius(height / 2f)
            setRefractionHeight(20 * dm.density)
            setRefractionOffset(70 * dm.density)
            setDispersion(0.6f)
            setBlurRadius(0.55f)
            setTintColorRed(1f)
            setTintColorGreen(1f)
            setTintColorBlue(1f)
            setTintAlpha(0.60f)
            setElasticEnabled(true)
            setTouchEffectEnabled(true)
            setDraggableEnabled(false)
        }
        glass.addView(chrome, FrameLayout.LayoutParams(MATCH, MATCH))

        // onCreate 阶段 decorView 还没 attach，rootWindowInsets 是 null，用系统资源兜底。
        val navRes = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val bottomInset = if (navRes > 0) resources.getDimensionPixelSize(navRes) else 0
        // 参考 LiquidTab：不是通栏，而是居中的紧凑胶囊。
        fun widthFor(count: Int): Int =
            (minOf(360, 72 * count + 24) * dm.density).toInt()
        val lp = FrameLayout.LayoutParams(widthFor(4), height, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = bottomInset + (10 * dm.density).toInt()
        }
        root.addView(glass, lp)
        glassBar = glass
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiLayout.collect { layout ->
                    val n = layout?.tabs?.size?.takeIf { it > 0 } ?: 4
                    glass.layoutParams = (glass.layoutParams as FrameLayout.LayoutParams).apply {
                        width = widthFor(n)
                    }
                    // 宽度变化后重新绑定，确保玻璃采样区域跟着新尺寸重建。
                    glass.post {
                        glass.bind(content)
                        chrome.bringToFront()
                        glass.invalidate()
                    }
                }
            }
        }

        glass.post {
            // 采样页面内容；玻璃自己是兄弟节点，不会递归。
            glass.bind(content)
            // LiquidGlassView 会在 bind 后把自己内部的 LiquidGlass 加到最上层，
            // chrome 必须再提到前面，否则图标被玻璃盖住。
            chrome.bringToFront()
            // 每次启动都回到底部；拖动只在本次会话内保持，避免“上次拖到半屏”变成常态。
            glass.translationY = 0f
            glass.translationX = 0f
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.barVisible.collect { visible ->
                    glass.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }

    private fun moveBar(dx: Float, dy: Float) {
        val glass = glassBar ?: return
        glass.translationY = (glass.translationY + dy).coerceIn(-maxLiftPx, 0f)
        glass.translationX = (glass.translationX + dx).coerceIn(-maxSidePx, maxSidePx)
    }

    private fun settleBar() {
        val glass = glassBar ?: return
        // 轻拖回弹；拖过阈值就留在那里（真正可拖动）。
        if (abs(glass.translationY) < 24 * resources.displayMetrics.density) {
            glass.animate().translationY(0f).setDuration(260).start()
        }
        if (abs(glass.translationY) < 40 * resources.displayMetrics.density) {
            glass.animate().translationY(0f).setDuration(260).start()
        }
    }

    private fun extractSharedText(): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return intent?.getStringExtra(Intent.EXTRA_TEXT)
    }

    override fun onResume() {
        super.onResume()
        runCatching { com.localmind.data.Diagnostics.setForeground(this, true) }
    }

    override fun onPause() {
        runCatching { com.localmind.data.Diagnostics.setForeground(this, false) }
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing) {
            runCatching { com.localmind.data.Diagnostics.endSession(this) }
        }
        super.onDestroy()
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
    }
}

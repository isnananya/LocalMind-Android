package com.localmind.ui.theme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import java.util.Locale

/**
 * 动态配色：从系统壁纸取主色，作为整个应用的主色（accent）。
 *
 * LiquidTab 的「动态配色」读的是系统取色板（Settings.Secure
 * theme_customization_overlay_packages → system_palette），那条路径在非 MIUI
 * 机器上不存在；这里直接用 WallpaperManager.getWallpaperColors 拿壁纸主色，
 * 再做饱和/明度修正，保证当主色用的时候看得清（壁纸主色经常是灰白）。
 *
 * 取不到就退回系统 accent（Android 12+ 的 system_accent1_500），最后退回默认蓝。
 */
object DynamicAccent {

    @Volatile private var cacheKey: String? = null
    @Volatile private var cacheArgb: Int? = null

    /** 当前该用的主色；返回 null 表示这台机器完全取不到（调用方保持原色）。 */
    fun accent(context: Context, dark: Boolean): Color? {
        val key = if (dark) "dark" else "light"
        val cached = cacheArgb
        if (cacheKey == key && cached != null) return Color(cached)
        val raw = runCatching { fromWallpaper(context) }.getOrNull()
            ?: runCatching { systemAccent(context) }.getOrNull()
            ?: return null
        val argb = adjust(raw, dark)
        cacheKey = key
        cacheArgb = argb
        return Color(argb)
    }

    /** 把动态主色写进令牌表（覆盖 accent / accentOn）。 */
    fun overrides(color: Color): Map<String, String> {
        val hex = String.format(Locale.US, "#%06X", 0xFFFFFF and color.toArgb())
        val on = if (color.luminance() > 0.55f) "#000000" else "#FFFFFF"
        return mapOf("accent" to hex, "accentOn" to on)
    }

    private fun fromWallpaper(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
        val manager = WallpaperManager.getInstance(context) ?: return null
        val colors = manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM) ?: return null
        return colors.primaryColor?.toArgb()
    }

    private fun systemAccent(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return context.getColor(android.R.color.system_accent1_500)
    }

    /** 壁纸主色常偏灰/偏暗，当主色时要提饱和、夹明度。 */
    private fun adjust(argb: Int, dark: Boolean): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        if (hsv[1] < 0.35f) hsv[1] = 0.55f
        hsv[2] = if (dark) {
            hsv[2].coerceIn(0.60f, 0.92f)
        } else {
            hsv[2].coerceIn(0.45f, 0.78f)
        }
        return android.graphics.Color.HSVToColor(hsv)
    }

    fun invalidate() {
        cacheKey = null
        cacheArgb = null
    }
}

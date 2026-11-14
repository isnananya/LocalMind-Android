package com.localmind.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import java.io.File

/**
 * 壁纸加载器。
 *
 * ## 为什么自己写而不引 Coil
 *
 * 这个项目**离线构建**（`--offline`），加一个新依赖要预热 Gradle 缓存，
 * 在只有一台开发机的环境下很脆。而壁纸的需求其实很窄：
 * 一张图、一次加载、按屏幕尺寸采样 —— 不值得为它引入一整个图片库。
 *
 * ## 为什么必须采样
 *
 * 一张 1440×3120 的壁纸按 ARGB_8888 全解码是 **18 MB**。一张就是 18 MB，
 * 再加上一张缩略图和 Compose 的图层缓存，很容易把低端机推到 OOM。
 * 所以这里照抄 [com.localmind.data.ChatImageStore.decodeScaled] 的做法：
 * 先读边界算采样率，再解码。
 *
 * ## 缓存
 *
 * 按「文件路径 + 目标宽度」缓存已解码的位图。换主题时会命中别的 key，
 * 旧的那张留在缓存里（LRU 有上限），不用反复解码。
 */
object WallpaperLoader {

    private const val TAG = "WallpaperLoader"

    /** 缓存条目上限。壁纸图大，条目多了会吃掉可观内存。 */
    private const val MAX_CACHE_ENTRIES = 4

    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    /**
     * 解码壁纸。
     *
     * @param file 壁纸文件。已经过路径校验（见 [safeWallpaperFile]）。
     * @param targetWidth 目标显示宽度（px）。按它采样，不会解码出远超需要的位图。
     * @return 解码成功返回位图；文件不存在 / 格式不对返回 null（调用方退回内置背景）。
     */
    @Synchronized
    fun load(file: File, targetWidth: Int): Bitmap? {
        if (!file.isFile) return null
        val key = "${file.absolutePath}@$targetWidth"
        cache[key]?.let { return it }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "壁纸解码失败（不是有效图片）：${file.name}")
            return null
        }

        // 采样：让解码后的宽度不小于目标宽度太多。宁可略大也不要糊。
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2

        val bmp = runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }.getOrNull()

        if (bmp == null) {
            Log.w(TAG, "壁纸解码异常：${file.name}")
            return null
        }
        cache[key] = bmp
        return bmp
    }

    /**
     * 把主题令牌里的壁纸文件名解析成**真实文件**。
     *
     * ⚠️ **必须做路径校验。** 文件名来自插件的 `ui.json`，而插件是第三方内容。
     * 如果直接 `File(assetsDir, name)`，`name = "../../databases/app.db"` 就能
     * 把应用私有目录里的东西当壁纸读出来。虽然读自己沙箱内的文件不构成越权，
     * 但这会让"主题包"变成一个任意文件读取器 —— 一旦以后有云端主题包
     * （第三方内容）进来，这就是真的漏洞了。
     *
     * 所以这里只接受**纯文件名**（不含任何目录分隔符、不含 `..`）。
     */
    fun safeWallpaperFile(assetsDir: File, fileName: String): File? {
        val name = fileName.trim()
        if (name.isEmpty()) return null
        // 纯文件名：不能有斜杠、反斜杠，不能是 . 或 ..
        if (name.contains('/') || name.contains('\\')) return null
        if (name == "." || name == "..") return null
        // 再挡一次 Windows 风格的盘符/冒号（某些文件系统的怪癖）
        if (name.contains(':')) return null

        val dir = assetsDir.canonicalFile
        val f = File(dir, name)
        // 必须在 assets 目录内。用 canonicalPath 比对，挡掉符号链接。
        val real = runCatching { f.canonicalFile }.getOrNull() ?: return null
        if (real.parentFile?.canonicalPath != dir.path) return null
        return if (real.isFile) real else null
    }

    /** 清缓存。主题切换时旧位图及时释放。 */
    @Synchronized
    fun clear() {
        cache.values.forEach { runCatching { it.recycle() } }
        cache.clear()
    }
}

/**
 * 在 Composable 里加载一张壁纸。
 *
 * @param fileName 主题令牌里的壁纸文件名（纯文件名）。
 * @param assetsDir 插件的 assets 目录。
 * @return 加载好的位图；没配壁纸 / 加载失败返回 null，调用方退回内置背景。
 */
@Composable
fun rememberWallpaper(fileName: String, assetsDir: File?): ImageBitmap? {
    if (fileName.isBlank() || assetsDir == null) return null

    val context = LocalContext.current
    val density = LocalDensity.current
    // 按屏幕宽度采样就够了：壁纸总是铺满屏幕宽度。
    val targetPx = with(density) { screenWidthDp(context).toPx() }.toInt().coerceAtLeast(320)

    var bmp by remember(fileName, targetPx) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(fileName, targetPx) {
        val file = WallpaperLoader.safeWallpaperFile(assetsDir, fileName) ?: return@LaunchedEffect
        val decoded = withContextIo { WallpaperLoader.load(file, targetPx) }
        bmp = decoded?.asImageBitmap()
    }
    return bmp
}

/** 屏幕宽度（dp）。取不到就退到 360dp 这个常见值。 */
private fun screenWidthDp(context: Context): Dp {
    val metrics = runCatching {
        context.resources.displayMetrics
    }.getOrNull() ?: return Dp(360f)
    return Dp((metrics.widthPixels / metrics.density).toFloat())
}

/** 壁纸解码是 IO，放到后台。 */
private suspend fun <T> withContextIo(block: () -> T): T? = kotlinx.coroutines.withContext(
    kotlinx.coroutines.Dispatchers.IO
) {
    runCatching { block() }.getOrNull()
}

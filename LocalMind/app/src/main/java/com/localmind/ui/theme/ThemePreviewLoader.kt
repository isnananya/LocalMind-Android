package com.localmind.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.localmind.service.ThemeStoreService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在线主题的**预览图**加载器。
 *
 * ## 为什么又自己写一个
 *
 * 项目离线构建，加不了 Coil；而且预览图的需求和壁纸不同：
 *  * 来源是网络（第三方内容），不是本地文件 —— 要**限制大小**、**校验图片头**，
 *    否则一个伪装成 png 的 HTML 错误页会被当图片解码，甚至一份几百 MB 的
 *    "图"能把应用撑爆；
 *  * 一个源挂了要能自动换下一个（GitHub 直连在国内经常不通）；
 *  * 列表里同一张图会被反复重组，必须有内存 + 磁盘两级缓存，否则每次
 *    滑动都重新下载一次。
 *
 * ## 优先级
 *
 * 1. 内存（已解码位图，按 url/id + 目标宽度）
 * 2. APK 内置 assets —— 内置主题离线也有预览
 * 3. 磁盘缓存（上次下载的原图字节）
 * 4. 网络：`previewUrl` → `previewMirrors` → 自动展开的 ghproxy 镜像
 *
 * 任何一步失败都不抛异常，返回 null 让界面画占位图 —— 预览图挂了
 * 不该让主题列表消失，更不该拦住下载。
 */
object ThemePreviewLoader {

    private const val TAG = "ThemePreview"

    /** 单张预览图最大 6 MB。正常主题预览图只有 100~200 KB。 */
    private const val MAX_BYTES = 6 * 1024 * 1024

    /** 已解码位图的内存缓存上限。缩略图很小，16 张大约 2 MB。 */
    private const val MAX_MEMORY_ENTRIES = 16

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val memCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_MEMORY_ENTRIES
    }

    @Synchronized
    private fun cached(key: String): Bitmap? = memCache[key]?.takeIf { !it.isRecycled }

    @Synchronized
    private fun put(key: String, bmp: Bitmap) {
        memCache[key] = bmp
    }

    /** 清内存缓存。主题商店退出时调用。 */
    @Synchronized
    fun clearMemory() {
        memCache.values.forEach { runCatching { it.recycle() } }
        memCache.clear()
    }

    private fun diskFile(context: Context, entry: ThemeStoreService.ThemeEntry): File {
        val dir = File(context.cacheDir, "theme_previews").apply { mkdirs() }
        // id 已经过白名单（字母数字点横线），可以直接当文件名。
        val safe = entry.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, "$safe-${entry.version.ifBlank { "0" }}.img")
    }

    /** 是不是真图片。只认 PNG / JPEG / WebP，挡掉错误页和脚本。 */
    private fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val png = bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        val jpg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val webp = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        return png || jpg || webp
    }

    /** 按目标宽度采样解码，避免把 360×640 甚至更大的图全量解进内存。 */
    private fun decode(bytes: ByteArray, targetWidth: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        return runCatching {
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }.getOrNull()
    }

    /**
     * 取一张预览图。挂起函数，调用方负责切到 IO。
     *
     * @param targetWidth 显示宽度（px）。缩略图传 200 左右，全屏预览传屏幕宽度。
     */
    suspend fun load(
        context: Context,
        entry: ThemeStoreService.ThemeEntry,
        targetWidth: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${entry.id}|${entry.previewAsset}|${entry.previewUrl}|$targetWidth"
        cached(key)?.let { return@withContext it }

        // 1) 内置 assets：随 APK 附带，不联网。路径在解析时已限定在
        //    builtin/themes/ 前缀下且不含 ".."，这里再挡一次分隔符。
        val bytes: ByteArray? = if (entry.previewAsset.isNotBlank()) {
            runCatching {
                context.assets.open(entry.previewAsset).use { it.readBytes() }
            }.getOrElse {
                Log.w(TAG, "内置预览图读不到：${entry.previewAsset}")
                null
            }
        } else {
            // 2) 磁盘缓存
            val disk = diskFile(context, entry)
            val hit = if (disk.isFile && disk.length() in 12..MAX_BYTES.toLong()) {
                runCatching { disk.readBytes() }.getOrNull()
            } else null
            if (hit != null && looksLikeImage(hit)) {
                hit
            } else {
                // 3) 网络：候选地址里只要有一个成功就收工。
                var fetched: ByteArray? = null
                for (url in entry.previewUrls) {
                    val data = runCatching {
                        val req = Request.Builder().url(url).build()
                        client.newCall(req).execute().use { resp ->
                            val body = if (resp.isSuccessful) resp.body else null
                            if (body == null || body.contentLength() > MAX_BYTES) {
                                null
                            } else {
                                val buf = java.io.ByteArrayOutputStream()
                                var tooBig = false
                                body.byteStream().use { input ->
                                    val tmp = ByteArray(16 * 1024)
                                    var total = 0
                                    while (true) {
                                        val n = input.read(tmp)
                                        if (n <= 0) break
                                        total += n
                                        if (total > MAX_BYTES) {
                                            tooBig = true
                                            break
                                        }
                                        buf.write(tmp, 0, n)
                                    }
                                }
                                if (tooBig) null else buf.toByteArray()
                            }
                        }
                    }.getOrNull()
                    if (data != null && looksLikeImage(data)) {
                        fetched = data
                        break
                    }
                }
                fetched?.also { data ->
                    runCatching { diskFile(context, entry).writeBytes(data) }
                }
            }
        }

        val bmp = bytes?.takeIf { looksLikeImage(it) }?.let { decode(it, targetWidth) }
        if (bmp != null) put(key, bmp)
        bmp
    }
}

/**
 * 在 Composable 里加载主题预览图。
 *
 * @param targetWidthDp 期望的显示宽度。缩略图用 100dp 左右，全屏用 320dp。
 * @return 加载好返回位图；失败返回 null，调用方画占位图。
 */
@Composable
fun rememberThemePreview(
    entry: ThemeStoreService.ThemeEntry,
    targetWidthDp: Dp,
): State<ImageBitmap?> {
    val context = LocalContext.current
    val density = LocalDensity.current
    val targetPx = with(density) { targetWidthDp.toPx() }.toInt().coerceAtLeast(120)
    val state = remember(entry.id, entry.previewAsset, entry.previewUrl, entry.version, targetPx) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(entry.id, entry.previewAsset, entry.previewUrl, entry.version, targetPx) {
        state.value = ThemePreviewLoader.load(context, entry, targetPx)?.asImageBitmap()
    }
    return state
}

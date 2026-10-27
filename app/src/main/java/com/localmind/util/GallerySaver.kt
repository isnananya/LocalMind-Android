package com.localmind.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把应用内生成的图片存进系统相册。
 *
 * ## 为什么不直接写 /sdcard/Pictures
 *
 * Android 10 起这是被禁止的（scoped storage）：直接写公共目录要么被拒，
 * 要么写出一个相册 App 看不到的孤儿文件。唯一正确的做法是走 MediaStore，
 * 让系统把文件登记进媒体库。
 *
 * Android 9 及以下没有 MediaStore 的 RELATIVE_PATH，只能写文件再让
 * MediaScanner 扫一遍；这一档需要 WRITE_EXTERNAL_STORAGE（manifest 里
 * 限制到 maxSdkVersion=28）。真机/模拟器只要 ≥10 就不会走到那条路。
 */
object GallerySaver {

    /** @return 是否真的写进了相册。 */
    fun save(context: Context, file: File): Boolean = runCatching {
        if (!file.isFile) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, file)
        } else {
            saveLegacy(context, file)
        }
    }.getOrDefault(false)

    private fun saveViaMediaStore(context: Context, file: File): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LocalMind")
            // IS_PENDING: 写完之前别让相册 App 看到半张图。
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val out = resolver.openOutputStream(uri) ?: return false
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File): Boolean {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "LocalMind",
        )
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val dest = File(dir, file.name)
        file.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(
            context, arrayOf(dest.absolutePath), arrayOf("image/png"), null,
        )
        return true
    }
}
